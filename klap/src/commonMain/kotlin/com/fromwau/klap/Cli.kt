package com.fromwau.klap

import com.fromwau.klap.internal.parse.NameMatch
import com.fromwau.klap.internal.parse.resolveName
import com.fromwau.klap.internal.render.HelpExample
import com.fromwau.klap.internal.spec.Action
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.Builtin
import com.fromwau.klap.internal.spec.Display
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.InputConstraint
import com.fromwau.klap.internal.spec.NamedSpec
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.longs
import com.fromwau.klap.internal.spec.negativeLongs

/**
 * One command in a built tree: its name, its aliases, its subcommands and its help text. A command that has
 * subcommands but no behaviour of its own lists them when invoked instead of doing anything.
 */
public open class Command internal constructor(
    public val name: String,
    public val aliases: List<String>,
    internal val specs: List<HolderSpec>,
    // Cross-input rules over [specs], in declaration order; the parse-time source of truth for them (the
    // `(one of ...)` note help shows is derived onto each member spec at build time).
    internal val constraints: List<InputConstraint> = emptyList(),
    public val subcommands: List<Command>,
    internal val action: Action?,
    // The option `-<NUM>` is shorthand for, if `numericAlias(...)` declared one; null is the default, and
    // then a dash-led number is simply an unknown option.
    internal val numericAlias: OptionSpec? = null,
    internal val display: Display = Display(),
    // Non-null only for the completion/docs/__complete nodes cli() injects: names the builtin so parse()
    // routes it to the matching render invocation, since the node carries no action. A user command is null.
    internal val builtinKind: Builtin? = null,
    // Whether THIS node's own sift stops at its first operand rather than permuting past it; see
    // CommandBuilder.optionsEndAtFirstOperand for what that trades away and why it is per-node.
    internal val optionsEndAtFirstOperand: Boolean = false,
) {
    internal val arguments: List<ArgumentSpec> get() = specs.filterIsInstance<ArgumentSpec>()
    internal val options: List<OptionSpec> get() = specs.filterIsInstance<OptionSpec>()
    internal val flags: List<FlagSpec> get() = specs.filterIsInstance<FlagSpec>()

    // Options and flags interleaved in DECLARATION order, which `options + flags` discards by grouping all
    // options ahead of all flags. Help renders from this so a related --verbose/--quiet pair the author
    // wrote together stays together.
    internal val namedInputs: List<NamedSpec> get() = specs.filterIsInstance<NamedSpec>()

    // Presentation, read straight off [display]; description/epilogue stay public, the rest is the
    // render walk's concern. section/hidden mirror HolderSpec's own, so a subcommand and an input read
    // the same way in that walk.
    public val description: String get() = display.description
    public val epilogue: String get() = display.epilogue
    internal val examples: List<HelpExample> get() = display.examples
    internal val section: String? get() = display.section
    internal val hidden: Boolean get() = display.hidden

    /** A group prints subcommand help when invoked: it has children and no own action. */
    internal val isGroup: Boolean get() = subcommands.isNotEmpty() && action == null

    /**
     * The child named exactly [token], by its own name or one of its aliases, or null. This never resolves
     * an abbreviation, even under [Abbreviation.All], so it can miss a token that parsing would have matched.
     */
    public fun subcommand(token: String): Command? =
        subcommands.firstOrNull { it.name == token || token in it.aliases }
}

/** What a typed subcommand token reached. [Ambiguous.candidates] are full spellings, in declaration order. */
internal sealed interface SubcommandMatch {
    data class One(val command: Command) : SubcommandMatch
    data class Ambiguous(val candidates: List<String>) : SubcommandMatch
    data object None : SubcommandMatch
}

/**
 * The child [token] names, exactly or — when [infer] — as an unambiguous abbreviation of a name or alias.
 *
 * Hidden children take part, mirroring a hidden option: hiding removes a name from help, not from the
 * parser, so the same line must not bind differently on a tree that hides nothing. A prefix reaching a
 * command's own name AND one of its aliases is not ambiguous, since both name one command.
 */
internal fun Command.resolveSubcommand(token: String, infer: Boolean): SubcommandMatch {
    val pool = subcommands.flatMap { listOf(it.name) + it.aliases }
    return when (val match = resolveName(token, pool, infer)) {
        is NameMatch.Exact -> subcommand(match.name)?.let { SubcommandMatch.One(it) } ?: SubcommandMatch.None
        is NameMatch.Prefix -> subcommand(match.name)?.let { SubcommandMatch.One(it) } ?: SubcommandMatch.None
        is NameMatch.Ambiguous -> {
            val commands = match.candidates.mapNotNull { subcommand(it) }.distinct()
            commands.singleOrNull()?.let { SubcommandMatch.One(it) }
                ?: SubcommandMatch.Ambiguous(match.candidates)
        }

        NameMatch.None -> SubcommandMatch.None
    }
}

/**
 * A built command tree, ready to use: what `cli { }` and `cliOf { }` hand back. Give it to `run` to parse,
 * dispatch and render in one call, to `main` to do that and exit the process, or to `parse` to get the
 * resolved invocation with nothing printed.
 *
 * The root differs from the commands under it in two ways: it answers to its binary name rather than to a
 * token, so it has no aliases, and it is where [author] and [version] live.
 */
public class Cli internal constructor(
    name: String,
    /** The `Author:` line `--help` and the generated docs show, or null when the CLI declares none. */
    public val author: String?,
    /** The version `--version` reports, or null when the CLI declares none, which also removes the flag. */
    public val version: String?,
    specs: List<HolderSpec>,
    constraints: List<InputConstraint>,
    subcommands: List<Command>,
    action: Action?,
    numericAlias: OptionSpec? = null,
    internal val globalSpecs: List<NamedSpec> = emptyList(),
    display: Display = Display(),
    // Which built-ins this tree offers, resolved once from the root's `builtins { }` block. Threaded from
    // here into parse and every renderer, since a subcommand node carries no root-only facts of its own.
    internal val builtins: Builtins = Builtins.DEFAULT,
    // Root-only, like [builtins]: threaded from here into every scan and sift, since a subcommand node
    // carries no root-only facts of its own.
    internal val abbreviation: Abbreviation = Abbreviation.None,
    optionsEndAtFirstOperand: Boolean = false,
) : Command(
    name = name,
    aliases = emptyList(),
    specs = specs,
    constraints = constraints,
    subcommands = subcommands,
    action = action,
    numericAlias = numericAlias,
    display = display,
    optionsEndAtFirstOperand = optionsEndAtFirstOperand,
) {
    init {
        val reserved = subcommands
            .filter { it.builtinKind != null }
            .map { it.name }
            .toSet()
        for (sub in subcommands.filter { it.builtinKind == null }) {
            require(sub.name !in reserved) {
                "cli '$name': subcommand '${sub.name}' uses a name reserved by a klap built-in"
            }
            for (alias in sub.aliases) {
                require(alias !in reserved) {
                    "cli '$name': subcommand '${sub.name}' alias '$alias' uses a name reserved by a klap built-in"
                }
            }
        }
    }

    /**
     * A single-command root (one carrying its own [action]) exposes completion/docs as `--completion` /
     * `--docs` meta-options rather than injected subcommands; a dispatcher uses the subcommands. That is
     * exactly "has its own action", so it is derived, not a stored flag.
     */
    internal val metaOptions: Boolean get() = action != null

    /**
     * Every long spelling declared anywhere in this tree, dashes stripped: each node's own options and
     * flags, plus each negatable flag's negative half, folded over the whole subcommand walk.
     *
     * Held rather than derived per call, unlike the other spec views above: the scans that run before the
     * subcommand walk resolve an abbreviation against it on every parse, and the tree is immutable once
     * built, so the walk can only ever produce the same list.
     */
    internal val declaredLongs: List<String> by lazy { subtreeLongs() }

    /**
     * The qualified path of the first command in this tree declared with `actionSuspending { }`, or null
     * when none is.
     *
     * Held rather than derived per call, for the same reason as [declaredLongs]: `run` and `main` read this
     * on every call, the walk allocates a path string per node visited, and the tree is immutable once
     * built, so it can only ever produce the same answer.
     */
    internal val suspendingPath: String? by lazy { firstSuspendingPath() }
}

private fun Command.subtreeLongs(): List<String> =
    namedInputs.flatMap { it.longs } +
            flags.filter { it.negatable }.flatMap { it.negativeLongs } +
            subcommands.flatMap { it.subtreeLongs() }

private fun Command.firstSuspendingPath(prefix: String = name): String? {
    if (action?.suspending == true) return prefix
    for (sub in subcommands) {
        sub.firstSuspendingPath("$prefix ${sub.name}")?.let { return it }
    }
    return null
}
