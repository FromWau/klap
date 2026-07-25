package com.fromwau.klap

import com.fromwau.klap.internal.spec.Action
import com.fromwau.klap.internal.spec.ActionSpec
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Restricts implicit receivers so a nested block can't reach an outer builder's members. */
@DslMarker
public annotation class KlapDsl

/**
 * DSL receiver for a (sub)command. Has NO version — that is a root-only concern.
 *
 * An abstract class with an internal constructor, not an interface: [action] must be a `reified` inline
 * member writing to internal state, which an interface cannot host (`internal` members are illegal
 * there) — and the internal constructor guarantees every receiver is klap's own implementation, so
 * nothing ever needs a cast to reach it.
 */
@KlapDsl
public abstract class CommandBuilder internal constructor() : ConverterScope() {
    public abstract var description: String
    public abstract var aliases: Collection<String>

    /** A trailing documentation paragraph, rendered after everything else in `--help`. */
    public abstract var epilogue: String

    /** Hide THIS (sub)command from `--help`; it still parses and executes normally. */
    public abstract var hidden: Boolean

    /**
     * Ends option parsing at the first operand, so every token after it is an operand verbatim, dash-led or
     * not: `ssh web1 ls -la` passes `ls -la` through untouched, and `find . -name '*.kt'` hands find its own
     * expression. Off by default.
     *
     * This is strict POSIX `getopt` behaviour (every option before the operands); klap's default is GNU's
     * permuting style, which keeps reading options after operands too. Turning this on gives up the
     * extension for the conforming rule, which is what a wrapper needs: `sudo`, `env`, `xargs`, and
     * `git bisect run` all pass a whole command line through untouched.
     *
     * `--` is unaffected and still ends options wherever they have not already ended. It is not, however, a
     * second escape hatch once options have already ended here: a `--` written after the first operand is
     * itself just another verbatim token in the tail, exactly like everything else the wrapper hands through.
     *
     * One thing this switch cannot reach: a token spelled like one of klap's own position-independent
     * built-ins (`--json`, `--color`, `--help`, `--version`, `--completion`, `--docs`), a long global, or a
     * short cluster made entirely of global characters is still claimed wherever it sits in the tail.
     * Ending options here does not end THEIR reach, which is by design (see `siftGlobals`). The one slot
     * none of them reach is a value-taking option's argument, which belongs to that option (see
     * `optionValueSlots`).
     *
     * A cluster that MIXES a global character with a local one does not reach the global once this switch
     * has fired, unlike the two cases above: `siftGlobals` leaves such a cluster whole for the reached
     * command's own `sift` to split, but that split never runs once an earlier operand has already ended
     * options, so the whole cluster, and any value the global char takes, binds as a literal operand and the
     * global silently keeps its default.
     *
     * A wrapper whose passed-through tail must carry one of the built-in spellings literally still needs its
     * own `--` ahead of it.
     *
     * Per command rather than inherited: a subcommand that wraps another program (`git bisect run`) sits
     * beside siblings that must keep permuting, so the setting belongs on the node that wants it.
     */
    public abstract var optionsEndAtFirstOperand: Boolean

    public abstract fun argument(name: String, help: String = ""): Arg<String>

    /**
     * Declares an option answering to every spelling in [names], the first of which is its primary: the
     * name its errors use and the key its value binds under. Each spelling is written as the token it is —
     * `--since` is a long, `-a` a short — so `option("--since", "--after", "-a")` is one option under three
     * spellings and `option("-Z")` is short-only. A short is one character; a long may be any length,
     * `--h` included.
     *
     * [help] is named-only: it follows a vararg, so a positionally passed help string would be read as a
     * further spelling. It carries no dashes, so it is rejected at construction rather than silently
     * becoming one.
     */
    public abstract fun option(vararg names: String, help: String = ""): Opt<String?>

    /** The flag counterpart of [option]; a `.negatable()` flag generates a `--no-` form per long spelling. */
    public abstract fun flag(vararg names: String, help: String = ""): Flag

    /** Declares a subcommand with no inline help: the empty-[help] case of the overload below. */
    public fun <R> command(name: String, block: CommandBuilder.() -> R): R = command(name, help = "", block)

    /**
     * Overload that seeds the subcommand's [description] with [help], the same inline-help convenience
     * [argument]/[option]/[flag] get. If [block] also assigns `description = "..."`, the block's value
     * wins — it runs after [help] is applied, so an explicit assignment always overrides the inline help.
     */
    public abstract fun <R> command(name: String, help: String, block: CommandBuilder.() -> R): R

    /** The help section every input declared right now renders under; null is the default, unlabeled block. */
    internal abstract var currentSection: String?

    /**
     * Scopes a named help section over every option/flag/subcommand declared inside [block], and returns
     * whatever [block] returns.
     *
     * Generic and `callsInPlace`, not `Unit`-returning, so a handle declared inside can be captured by a
     * plain `val` outside — either as the block's own result, or by assigning it in the block, which the
     * contract makes legal. Without both, every grouped input costs a hoisted `lateinit var` with its
     * converted type written out by hand, giving up definite-initialisation and inference for nothing.
     * Final rather than abstract for the same reason: an abstract member cannot carry a contract.
     */
    @OptIn(ExperimentalContracts::class)
    public fun <R> group(title: String, block: CommandBuilder.() -> R): R {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        val previous = currentSection
        currentSection = title
        // try/finally so a throwing block cannot leave later inputs stranded in this section.
        return try { block() } finally { currentSection = previous }
    }

    /**
     * Requires that exactly one of [inputs] be supplied: none given and more than one given are both usage
     * errors, exiting [USAGE_ERROR_EXIT]. `tar`'s `-c`/`-x`/`-t` mode set is the shape.
     *
     * Checked at parse time, ahead of every bind, so a conflict outranks the "missing required option"
     * a later bind would raise — `tar -c -x` (no `-f`) reports the mode conflict, as GNU tar does.
     * Every member's `--help` row is annotated `(one of -c, -x, -t; required)`, so the set reads as a set
     * rather than as three independent toggles.
     *
     * "Supplied" means actually typed: a member carrying `.default()` counts only when the user gave it,
     * never when the default filled it in. For a `.negatable()` flag, only the positive form counts —
     * `--no-x` is a request to turn `x` off, not to select it.
     *
     * Deliberately separate from [group], which is a help heading and nothing else: either concern is
     * useful without the other.
     *
     * Scoped to ONE command's own inputs. Every member must be declared on this (sub)command, so a
     * `globalOption`/`globalFlag` handle cannot take part (globals are out of scope). Throws at
     * construction if fewer than two inputs are given, an input is repeated, or an input is not declared
     * here.
     */
    public abstract fun requireExactlyOne(vararg inputs: Input)

    /**
     * The at-most-one counterpart of [requireExactlyOne]: none given is fine, two or more is a usage error.
     * `tar`'s `-z`/`-j` compression set, or `find`'s `-H`/`-L`/`-P`. Same construction-time rules, same
     * parse-time position, and each member's row is annotated `(at most one of -z, -j)`.
     */
    public abstract fun requireAtMostOne(vararg inputs: Input)

    /**
     * Declares that [inputs] override each other, and the one written LAST on the command line is the one
     * that holds: `rm -i -f` forces, `rm -f -i` prompts, and neither is an error. `find`'s `-P`/`-L`/`-H`,
     * `head`'s `-c`/`-n`, and `ls`'s `-S` against `--sort=WORD` all work the same way.
     *
     * **This is an override rule, not an exclusivity rule**, which is why [requireAtMostOne] is the wrong
     * tool here even though it reads plausibly: it would reject a line every one of those tools accepts.
     *
     * Order is read from the command line itself, including *inside* a cluster: `-if` forces and `-fi`
     * prompts. Every member the user did not write last binds what it would have bound had the user not
     * written it at all, so an action reads the winner off its own handle with no precedence logic. A set
     * nobody supplied is left alone.
     *
     * A set may mix flags and options, because a tool routinely spells one setting both ways. A positional
     * cannot join one: operands bind by position rather than by being named, so there is no occurrence to
     * order and nothing a loser could be reset to. Nor can a `.required()` or `.multiple()` option, since a
     * loser has to bind its absent form and neither has one: a required option's absence is itself the
     * usage error, and a repeatable option accumulates its occurrences instead of collapsing to one. Scoped
     * to ONE command's own inputs, with the same construction-time rules as [requireExactlyOne] - at least
     * two members, no repeats, and every member declared here rather than a global or another command's.
     */
    public abstract fun lastWins(vararg inputs: Input)

    /**
     * Declares that `-<NUM>` — any number, not a fixed set — is shorthand for [option], the way `head -5`
     * means `head -n 5` and `tail -20` means `tail -n 20`. The digits become the option's value and run
     * through its own converter and validation, so the value reads back off the handle already declared;
     * there is no second accessor and no new input kind.
     *
     * Without this, a dash-led number is an unknown option, which is what real `ls -5` and `sleep -1` say.
     * A short the command declares itself outranks the alias, so a tree with both `flag("-4")` and an
     * alias binds `-4` to the flag and `-5` to the alias.
     *
     * At most one per command, and [option] must be declared on this (sub)command; both are checked at
     * construction. A `Flag` cannot be aliased — `-5` carries a value, and a flag has nowhere to put it.
     */
    public abstract fun numericAlias(option: Opt<*>)

    /** One example invocation shown under an `Examples:` heading in `--help`. */
    public abstract fun example(command: String, description: String = "")

    /** Written only through [registerAction]; read back by the impl's build(). */
    internal var actionSpec: Action? = null
        private set

    @PublishedApi
    internal fun <T> registerAction(
        block: ActionScope.() -> Result<T, CliError>,
        serializer: () -> KSerializer<T>,
        human: (ActionScope.(T) -> String)?,
    ) {
        actionSpec = ActionSpec(block, serializer, human)
    }

    public inline fun <reified T> action(
        noinline human: (ActionScope.(T) -> String)? = null,
        noinline block: ActionScope.() -> Result<T, CliError>,
    ) {
        registerAction(block, { serializer<T>() }, human)
    }
}
