package com.fromwau.klap

import com.fromwau.kern.result.Result
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

/** Receiver of `cli { }` and `command { }`: declares one command's inputs, subcommands, help text and action. */
@KlapDsl
public abstract class CommandBuilder internal constructor() : ConverterScope() {
    /** Prose for this command: its row in the parent's `Commands` list, and the paragraph under its own usage line. */
    public abstract var description: String

    /** Further names this command answers to. `--help` lists them beside the canonical one, as `list, ls`. */
    public abstract var aliases: Collection<String>

    /** A closing paragraph, rendered after everything else in this command's `--help`. */
    public abstract var epilogue: String

    /** Hides this command from `--help`. It still parses and runs exactly as before. */
    public abstract var hidden: Boolean

    /**
     * Stops reading dash-led tokens as options once the first operand appears, so the rest of the line
     * reaches your action verbatim: `ssh web1 ls -la` passes `ls -la` through untouched, and
     * `find . -name '*.kt'` hands find its own expression. Off by default.
     */
    public abstract var optionsEndAtFirstOperand: Boolean

    /**
     * Declares a positional operand, bound by where it sits on the command line rather than by a name.
     * [name] is never a token your user types: it labels the operand in error messages and stands in for it
     * in `--help`. Operands bind in declaration order, each a required `String` until `.optional()`,
     * `.default()` or `.multiple()` says otherwise.
     */
    public abstract fun argument(name: String, help: String = ""): Arg<String>

    /**
     * Declares an option answering to every spelling in [names]. The first is its primary: the one errors
     * name it by, and the key its value binds under. Write each spelling as the token it is, so
     * `option("--since", "--after", "-a")` is one option under three spellings and `option("-Z")` is
     * short-only. A short is one character; a long may be any length, `--h` included.
     *
     * Pass [help] by name. Given positionally it would read as one more spelling, and it must carry no
     * dashes, which is rejected when the command is built.
     */
    public abstract fun option(vararg names: String, help: String = ""): Opt<String?>

    /** The flag counterpart of [option]. `.negatable()` adds a `--no-` form for every long spelling. */
    public abstract fun flag(vararg names: String, help: String = ""): Flag

    /** Declares a subcommand that takes its help from its own [description]. */
    public fun <R> command(name: String, block: CommandBuilder.() -> R): R = command(name, help = "", block)

    /**
     * Declares a subcommand, using [help] as its [description] so a short one needs no assignment inside the
     * block. A `description = "..."` written in [block] overrides it.
     */
    public abstract fun <R> command(name: String, help: String, block: CommandBuilder.() -> R): R

    /** The section options, flags and subcommands declared right now render under; null is the unlabeled default. */
    internal abstract var currentSection: String?

    /**
     * Scopes a named help section over every option/flag/subcommand declared inside [block], and returns
     * whatever [block] returns.
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
     * Requires exactly one of [inputs]: giving none and giving several are both usage errors, exiting
     * [USAGE_ERROR_EXIT]. `tar`'s `-c`/`-x`/`-t` mode set is the shape. A conflict is reported even when
     * something else is missing too, so `tar -c -x` names the mode conflict rather than the absent `-f`.
     * Each member's `--help` row is annotated `(one of -c, -x, -t; required)`, so the set reads as a set
     * rather than as independent toggles.
     *
     * Only what your user actually typed counts: a member carrying `.default()` does not count when the
     * default supplied it, and for a `.negatable()` flag only the positive form selects.
     *
     * Members must all be inputs of this one command, never a `globalOption`/`globalFlag` or another
     * command's. Fewer than two members, a repeat, or a foreign input throws when the command is built.
     */
    public abstract fun requireExactlyOne(vararg inputs: Input)

    /**
     * The at-most-one counterpart of [requireExactlyOne]: none is fine, two or more is a usage error.
     * `tar`'s `-z`/`-j` compression set, or `find`'s `-H`/`-L`/`-P`. Rows are annotated
     * `(at most one of -z, -j)`, and membership follows the same rules.
     */
    public abstract fun requireAtMostOne(vararg inputs: Input)

    /**
     * Declares that [inputs] override one another, the one written last winning: `rm -i -f` forces,
     * `rm -f -i` prompts, and neither is an error. `find`'s `-P`/`-L`/`-H`, `head`'s `-c`/`-n`, and `ls`'s
     * `-S` against `--sort=WORD` all behave this way.
     *
     * **This overrides, it does not exclude.** [requireAtMostOne] reads plausibly here and is the wrong
     * tool: it would reject a line every one of those tools accepts.
     *
     * Order comes from the command line, inside a cluster too, so `-if` forces and `-fi` prompts. Losers
     * bind as if they had never been written, so your action reads each handle with no precedence logic of
     * its own, and a set nobody supplied is left alone.
     *
     * A set may mix flags and options, since a tool often spells one setting both ways. It may not include
     * a positional, a `.required()` option or a `.multiple()` option. Membership follows
     * [requireExactlyOne]'s rules.
     */
    public abstract fun lastWins(vararg inputs: Input)

    /**
     * Makes `-<NUM>`, any number rather than a fixed set, shorthand for [option]: `head -5` means
     * `head -n 5` and `tail -20` means `tail -n 20`. The digits become the option's value and run through
     * its own converter and validation, so you read them back off the handle you already hold.
     *
     * Without this a dash-led number is an unknown option, which is what real `ls -5` and `sleep -1` say.
     * A short the command declares itself outranks the alias, so declaring `flag("-4")` alongside leaves
     * `-4` the flag and `-5` the alias.
     *
     * One per command, and [option] must belong to this command; both are checked when it is built. A
     * `Flag` cannot be aliased, since `-5` carries a value and a flag has nowhere to put one.
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

    /**
     * Defines what this command does when it runs.
     *
     * ```kotlin
     * command("greet") {
     *     val name = argument("name")
     *     action { Ok("hello, ${name()}") }
     * }
     * ```
     *
     * @param human turns the returned value into the line printed on success; without it the value's
     *   `toString()` is printed. A `--json` run serializes the value instead of calling this, so [T] must be
     *   `@Serializable` for a CLI that offers `--json`.
     * @param block the work itself, returning `Ok(value)` or a typed [CliError] that klap renders to stderr
     *   and turns into the exit code.
     */
    public inline fun <reified T> action(
        noinline human: (ActionScope.(T) -> String)? = null,
        noinline block: ActionScope.() -> Result<T, CliError>,
    ) {
        registerAction(block, { serializer<T>() }, human)
    }
}
