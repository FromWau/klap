package com.fromwau.klap.internal.builder

import com.fromwau.klap.Arg
import com.fromwau.klap.Builtins
import com.fromwau.klap.BuiltinsBuilder
import com.fromwau.klap.CliBuilder
import com.fromwau.klap.Command
import com.fromwau.klap.CommandBuilder
import com.fromwau.klap.Flag
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Input
import com.fromwau.klap.Opt
import com.fromwau.klap.Projection
import com.fromwau.klap.Result
import com.fromwau.klap.holderSpec
import com.fromwau.klap.resolve
import com.fromwau.klap.internal.render.HelpExample
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.Builtin
import com.fromwau.klap.internal.spec.ConstraintArity
import com.fromwau.klap.internal.spec.Display
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.InputConstraint
import com.fromwau.klap.internal.spec.NamedSpec
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.constraintToken
import com.fromwau.klap.internal.spec.hint
import com.fromwau.klap.internal.spec.requireValidName
import com.fromwau.klap.internal.spec.token

/** Identity converter: a raw string passes through unchanged until a type transformer replaces it. */
private val passthrough: (String) -> Result<Any?, String> = { Result.Success(it) }

/**
 * The single implementation behind both builder classes: it accumulates the DSL calls into spec lists,
 * then [build] turns them into an immutable [Command] after the well-formedness checks in
 * `BuilderValidation.kt` pass.
 */
internal class BuilderImpl(
    private val name: String,
    // The root's own globalSpecs, threaded down so a nested `.requiredIf()` trigger can resolve against it.
    // Null exactly for the root, which [build] reads as "this is the tree's outermost build()".
    private val rootGlobalSpecs: List<NamedSpec>? = null,
) : CliBuilder() {
    init {
        // Applies to every (sub)command AND the root `cli(name)`, since both construct a BuilderImpl
        // directly; same rules as input names — see DocsTest's roff-escaping test for a dotted name.
        requireValidName("command", name)
    }

    override var description: String = ""
    override var aliases: Collection<String> = emptyList()
    override var version: String? = null
    override var author: String? = null
    override var abbreviation: Abbreviation = Abbreviation.None
    override var epilogue: String = ""
    override var hidden: Boolean = false
    override var optionsEndAtFirstOperand: Boolean = false

    private val specs = mutableListOf<HolderSpec>()
    private val globalSpecs = mutableListOf<NamedSpec>()
    private val subs = mutableListOf<Command>()
    private val examples = mutableListOf<HelpExample>()

    // Held by reference rather than copied: a global declared after a nested command was constructed must
    // still be visible to that command's own checks.
    private val reachableGlobalSpecs: List<NamedSpec> get() = rootGlobalSpecs ?: globalSpecs

    // The group heading in effect for declarations made right now; restore-previous, so nesting is harmless.
    override var currentSection: String? = null

    override fun argument(name: String, help: String): Arg<String> {
        // Positionals never take a section: they always render in the unlabeled block.
        val spec = ArgumentSpec(name, help, passthrough)
        specs += spec
        return Arg(spec)
    }

    override fun option(vararg names: String, help: String): Opt<String?> {
        val spec = OptionSpec(names.toList(), help, passthrough, currentSection)
        specs += spec
        return Opt(spec)
    }

    override fun flag(vararg names: String, help: String): Flag {
        val spec = FlagSpec(names.toList(), help, currentSection)
        specs += spec
        return Flag(spec)
    }

    override fun globalOption(vararg names: String, help: String): Opt<String?> {
        val spec = OptionSpec(names.toList(), help, passthrough)
        globalSpecs += spec
        return Opt(spec)
    }

    override fun globalFlag(vararg names: String, help: String): Flag {
        val spec = FlagSpec(names.toList(), help)
        globalSpecs += spec
        return Flag(spec)
    }

    private var numericAliasSpec: OptionSpec? = null

    override fun numericAlias(option: Opt<*>) {
        val spec = option.spec
        require(numericAliasSpec == null) {
            "command '$name': numericAlias is already declared on '${numericAliasSpec?.token()}'; " +
                    "`-<NUM>` can only mean one option"
        }
        require(specs.any { it === spec }) {
            "command '$name': numericAlias names '${spec.token()}', which is not declared on '$name'; " +
                    "the alias binds through one of this command's own options"
        }
        numericAliasSpec = spec
        // Help-only: the row has to advertise the spelling, or `-5` is a feature nobody can discover.
        spec.valueHint = listOfNotNull(spec.valueHint, "or -NUM").joinToString("; ")
    }

    private val constraints = mutableListOf<InputConstraint>()

    override fun requireExactlyOne(vararg inputs: Input): Unit =
        addConstraint(ConstraintArity.ExactlyOne, "requireExactlyOne", inputs)

    override fun requireAtMostOne(vararg inputs: Input): Unit =
        addConstraint(ConstraintArity.AtMostOne, "requireAtMostOne", inputs)

    override fun lastWins(vararg inputs: Input): Unit =
        addConstraint(ConstraintArity.LastWins, "lastWins", inputs)

    /**
     * Record one cross-input rule, rejecting a malformed set on the spot rather than at parse time: a
     * one-member set states nothing, a repeated member would make its own presence count twice, and a
     * member this command never declared (a global, or another command's input) could never be seen by
     * the sift that enforces the rule. [label] names the DSL call in the message so the author is pointed
     * at the line they wrote.
     *
     * Membership is compared by identity: a spec is the handle, and two distinct inputs never share one.
     */
    private fun addConstraint(arity: ConstraintArity, label: String, inputs: Array<out Input>) {
        require(inputs.size >= 2) {
            "command '$name': $label needs at least two inputs, got ${inputs.size}"
        }
        val members = inputs.map { it.holderSpec() }
        for (spec in members) {
            require(members.count { it === spec } == 1) {
                "command '$name': $label lists '${spec.constraintToken()}' more than once"
            }
            require(specs.any { it === spec }) {
                "command '$name': $label lists '${spec.constraintToken()}', which is not declared on " +
                        "'$name'; a constraint relates one command's own inputs, so a global or another " +
                        "command's input cannot join it"
            }
            require(!(arity == ConstraintArity.LastWins && spec is ArgumentSpec)) {
                "command '$name': $label lists '${spec.constraintToken()}', which is a positional; an " +
                        "operand binds by position rather than by being named, so there is no occurrence " +
                        "to order and nothing a loser could be reset to"
            }
        }
        val constraint = InputConstraint(arity, members)
        constraints += constraint
        // Appended, not assigned: an input may sit in two sets, and metaHint joins its own hints with the
        // same "; " separator, so the notes concatenate into one well-formed parenthetical.
        val hint = constraint.hint()
        members.forEach { spec ->
            spec.constraintHint = listOfNotNull(spec.constraintHint, hint).joinToString("; ")
        }
    }

    private val builtinsBuilder = BuiltinsBuilder()

    override fun builtins(block: BuiltinsBuilder.() -> Unit) {
        builtinsBuilder.block()
    }

    /** The root's resolved built-in surface, read by [cli]; a [Command] node never carries one. */
    internal fun builtBuiltins(): Builtins = builtinsBuilder.resolve()

    override fun example(command: String, description: String) {
        examples += HelpExample(command, description)
    }

    override fun <R> command(name: String, help: String, block: CommandBuilder.() -> R): R {
        // The child renders under the group heading enclosing this `command(...)` call (currentSection),
        // if any; its own `hidden` comes from the child block. Both are read back off the built child.
        val child = BuilderImpl(name, rootGlobalSpecs = reachableGlobalSpecs)
        child.description = help
        val result = child.block()
        val built = child.build(section = currentSection)
        subs += built
        // A block that ended in `projection { }` has just told us which command it reads; nothing else can
        // supply that, because the handles it closes over are locals of the block that just returned.
        (result as? Projection<*>)?.claim(built)
        return result
    }

    /** The root's globals, read by [cli] when assembling the [com.fromwau.klap.Cli]; a [Command] node never carries them. */
    internal fun builtGlobals(): List<NamedSpec> = globalSpecs.toList()

    /**
     * Freeze the accumulated declarations into an immutable [Command], once every well-formedness rule passes.
     * [section] is the enclosing `group(...)` heading the parent threads in so this command renders under it.
     */
    internal fun build(section: String? = null, builtinKind: Builtin? = null): Command {
        validatePositionals(name, specs)
        validateSpellings(name, specs, globalSpecs)
        validateDuplicateOptionFlagNames(name, specs)
        validateNegationCollisions(name, specs)
        validateGlobalCollisions(name, specs, globalSpecs, subs)
        validateSubcommands(name, subs)
        validateActionlessLocalOptions(
            name,
            specs,
            hasAction = actionSpec != null,
            isBuiltin = builtinKind != null,
        )
        validateSectionTitles(name, specs)
        validateLastWinsMembers(name, constraints)
        validateConditionalOperandTriggers(name, specs)
        validateRequiredIfTriggers(name, specs, reachableGlobalSpecs)
        val built = Command(
            name = name,
            aliases = aliases.toList(),
            specs = specs.toList(),
            constraints = constraints.toList(),
            subcommands = subs.toList(),
            action = actionSpec,
            numericAlias = numericAliasSpec,
            optionsEndAtFirstOperand = optionsEndAtFirstOperand,
            display = Display(
                description = description,
                examples = examples.toList(),
                epilogue = epilogue,
                section = section,
                hidden = hidden,
            ),
            builtinKind = builtinKind,
        )
        if (rootGlobalSpecs == null) {
            subs.forEach(::revalidateAgainstLaterMutation)
        }
        return built
    }
}
