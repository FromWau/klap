package com.fromwau.klap.internal.render

import com.fromwau.klap.Builtins
import com.fromwau.klap.COLOR_MODE_NAMES
import com.fromwau.klap.COMPLETION_SHELL_NAMES
import com.fromwau.klap.Cli
import com.fromwau.klap.Command
import com.fromwau.klap.ColorScope
import com.fromwau.klap.DOC_FORMAT_NAMES
import com.fromwau.klap.Palette
import com.fromwau.klap.bold
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.ConstraintArity
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.InputConstraint
import com.fromwau.klap.internal.spec.NamedSpec
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.ValueSpec
import com.fromwau.klap.internal.spec.constraintHintToken
import com.fromwau.klap.internal.spec.constraintToken
import com.fromwau.klap.internal.spec.longs
import com.fromwau.klap.internal.spec.negativeLongs
import com.fromwau.klap.internal.spec.negativeShorts
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.internal.spec.token
import com.fromwau.klap.render

// --- Reusable row/section model ---
// helpText() renders through this model; the docs renderer consumes the same assembly, so the model and
// the small formatting helpers below are internal rather than private to helpText().

/** One help/doc entry: a signature column and its description (help text plus any metaHint note). */
internal data class InputRow(val signature: String, val description: String)

/** A labeled block of [rows]; a null [title] is the default, unlabeled block. */
internal data class HelpSection(val title: String?, val rows: List<InputRow>)

/** One example invocation shown under the `Examples:` heading. */
internal data class HelpExample(val command: String, val description: String = "")

/**
 * Wording for the five built-in options, shared between this file's [helpSections] (the `Global
 * options` rows) and Completion.kt's candidate descriptions, so `--help` and tab completion can
 * never disagree on what a built-in flag does.
 */
internal object BuiltinOptionHelp {
    const val HELP = "Show this help"
    const val JSON = "Output as JSON"
    const val VERSION = "Show the version"
    const val COMPLETION = "Print a shell completion script"
    const val DOCS = "Print generated documentation"
    const val COLOR = "Colorize output: auto, always, or never"
}

// --- formatting helpers ---

/**
 * `<required> [optional] [name=default] <variadic>... [optional-variadic...]`: the visible positional
 * shape shown in usage.
 */
internal fun Command.argSummary(): String = arguments.filter { !it.hidden }.joinToString(" ") { spec ->
    val shown = spec.displayName()
    val token = when (val c = spec.cardinality) {
        Cardinality.Required -> "<$shown>"
        Cardinality.Optional -> "[$shown]"
        is Cardinality.Default -> "[$shown=${spec.display(c.value)}]"
        // Square brackets whenever zero operands are reachable, whether the declared minimum is already
        // zero or a relaxedWhen trigger drops it there: `rm [file...]` is real rm's own synopsis, and the
        // usage line is the only place that distinction is visible.
        is Cardinality.Multiple ->
            if (c.min == 0 || spec.relaxedWhen != null) "[$shown...]" else "<$shown>..."
    }
    spec.absentSlotToken(token)
}

/**
 * Wraps [token] in an extra pair of brackets when this slot's absentWhen trigger fired removes it on some
 * lines: `chmod`'s mode is Required in every other reading, so leaving its usual `<mode>` unbracketed would
 * claim the operand is always there. Wrapping rather than replacing keeps the inner `<>` legible, so the
 * reader still sees "a value, when present" instead of the plain-optional reading `[mode]` would suggest.
 */
private fun ArgumentSpec.absentSlotToken(token: String): String = if (absentWhen != null) "[$token]" else token

/** The name help shows for this input: an explicit `.placeholder()` if given, else the declared name. */
internal fun ValueSpec.displayName(): String = placeholder ?: name

// An explicit .placeholder() wins over the choice list: a long list of choices in the signature column widens
// every other row (see the choice-list layout note), and a tool that names its operand FILE means it.
internal fun ValueSpec.resolvedPlaceholder(): String = placeholder ?: choices?.joinToString("|") ?: "value"

/**
 * Render a value for help: for a choice-backed spec, show the matching choice so an enum default
 * prints in the choices' casing.
 */
internal fun ValueSpec.display(value: Any?): String {
    // Help/docs rendering has no action seam to carry a typed error through (unlike Action.kt's
    // renderHuman), so a throwing toString() falls back to a placeholder here instead of escaping.
    val text = try {
        value.toString()
    } catch (_: Exception) {
        "<unprintable>"
    }
    return choices?.firstOrNull { it.equals(text, ignoreCase = true) } ?: text
}

/**
 * The word-form of an option/flag: every spelling, shorts first (options add `<placeholder>`, or
 * `[=<placeholder>]` when the value is optional; a negatable flag shows `--[no-]long`, or both real
 * halves when the negative one has its own spellings).
 */
internal fun NamedSpec.words(): String {
    val generatedNegation = this is FlagSpec && negatable && negativeNames.isEmpty()
    val negatedShorts = if (this is FlagSpec) negativeShorts else emptyList()
    // Suppressed for the generated form: `--[no-]x` below already states it, so listing the generated
    // `no-x` again would render the same negation twice.
    val negatedLongs = if (this is FlagSpec && !generatedNegation) negativeLongs else emptyList()
    // Shorts first, and grouped positive-then-negative within each half rather than positives-then-all-
    // negatives: an explicit negative half is not a prefix of the positive one, so `--[no-]x` cannot state
    // it, and grouping keeps a pair's two real spellings next to each other the way the tools being
    // modelled document them (`-L, -P, --dereference, --no-dereference`, not `-L, --dereference, -P, ...`).
    val allShorts = (shorts + negatedShorts).map { "-$it" }
    val allLongs = longs.map { if (generatedNegation) "--[no-]$it" else "--$it" } + negatedLongs.map { "--$it" }
    val spellings = (allShorts + allLongs).joinToString(", ")
    // A row starts with a short whenever EITHER half has one: allShorts (built above) already puts the
    // negative short first if there is no positive one, so the row itself, not just `shorts`, decides the indent.
    val base = if (allShorts.isEmpty()) "    $spellings" else spellings
    // `[=<WHEN>]` rather than `<WHEN>`: the brackets say the value is optional and the `=` says the ATTACHED
    // spelling is the only one that carries it, which is the half of the rule a user cannot guess.
    return when {
        this !is OptionSpec -> base
        bareValue != null -> "$base[=<${resolvedPlaceholder()}>]"
        else -> "$base <${resolvedPlaceholder()}>"
    }
}

/**
 * A trailing `  (...)` note on a flag row: any constraint set it belongs to, then its negation default
 * or repeatability.
 */
internal fun FlagSpec.metaHint(): String {
    val hints = buildList {
        constraintHint?.let { add(it) }
        if (negatable) {
            val default = (cardinality as Cardinality.Default).value as Boolean
            add("default: ${if (default) "on" else "off"}")
        } else if (isCount) {
            add("repeatable")
        }
    }
    return if (hints.isEmpty()) "" else " (${hints.joinToString("; ")})"
}

/** A trailing `  (...)` note on an arg/option row: choices, and its required/optional/default/repeatable rule. */
internal fun ValueSpec.metaHint(): String {
    val absentWhen = (this as? ArgumentSpec)?.absentWhen
    val relaxedWhen = (this as? ArgumentSpec)?.relaxedWhen
    val hints = buildList {
        // An option normally shows its choices inside the value placeholder (see [resolvedPlaceholder]), so
        // repeating them here would duplicate. A placeholder replaces that placeholder, and an argument's
        // placeholder is just its name — in both cases the choices have nowhere else to appear, and
        // silently dropping the set of legal values is worse than a slightly longer row.
        if (choices != null && (this@metaHint is ArgumentSpec || placeholder != null)) {
            add("one of: ${choices!!.joinToString(", ")}")
        }
        valueHint?.let { add(it) }
        // The bare form's value is not derivable from the signature: `--color[=<WHEN>]` says a bare
        // occurrence is legal but not what it then means.
        (this@metaHint as? OptionSpec)?.bareValue?.let { add("bare: $it") }
        constraintHint?.let { add(it) }
        // Named rather than merely bracketed: "[<mode>]" says the operand is sometimes absent, and only the
        // trigger's own spelling says WHEN, which is the half a reader cannot guess.
        absentWhen?.let { add("absent with ${it.constraintToken()}") }
        relaxedWhen?.let { add("optional with ${it.constraintToken()}") }
        when (val c = cardinality) {
            // "absent with X" already carries the whole rule, so a bare "required" beside it reads as a
            // contradiction ("absent with --reference; required"); same suppression as Optional's below.
            Cardinality.Required -> if (absentWhen == null) add("required")
            // A constrained input is optional only in isolation: the hint above already states whether the
            // SET is required, so a bare "optional" beside it would read as a contradiction ("one of --a,
            // --b; required; optional"). Every other cardinality still says its own piece.
            Cardinality.Optional -> if (constraintHint == null) add("optional")
            is Cardinality.Default -> {
                // An empty-string default renders as bare "default: " (dangling space before the
                // closing paren); word-wrap then treats "(default:" and ")" as separate tokens and can
                // orphan the ")" onto its own line. Quoting it keeps the hint one visible unit.
                val rendered = display(c.value)
                add("default: ${rendered.ifEmpty { "\"\"" }}")
            }
            // min == 0 states "optional" as well: every other cardinality says whether it is required in
            // words here, and the usage line renders this one as [name...], so a bare "repeatable" would
            // leave the row contradicting the usage line about whether zero is allowed.
            is Cardinality.Multiple ->
                add(if (c.min > 0) "repeatable, min ${c.min}" else "optional; repeatable")
        }
    }
    return if (hints.isEmpty()) "" else " (${hints.joinToString("; ")})"
}

/** The help row for an option/flag: its word-form signature and help+metaHint description. */
internal fun NamedSpec.helpRow(): InputRow = InputRow(
    words(),
    help + when (this) {
        is OptionSpec -> metaHint()
        is FlagSpec -> metaHint()
    },
)

/**
 * A subcommand's row name: its canonical name plus any aliases, `list, ls` (mirrors [words]'s
 * comma-separated `-s, --long` form). Used everywhere a subcommand renders as a help/doc row, so
 * `--help`, the man page, and the markdown table agree with each other and with the markdown
 * `Aliases:` line.
 */
internal fun Command.commandRowName(): String = (listOf(name) + aliases).joinToString(", ")

/** A subcommand's help row, the [NamedSpec.helpRow] counterpart for the Commands and group blocks. */
internal fun Command.helpRow(): InputRow = InputRow(commandRowName(), description)

/**
 * A constraint's usage-line group: `(-c|-x|-t)` when one member is required, `[-z|-j]` when it is merely
 * allowed, each member named the compact way its help row's note names it ([constraintHintToken], since the
 * usage line is width-sensitive for the same reason). Null when every member is hidden; a partly hidden set
 * still renders its visible members, whose arity is unchanged by the ones help does not show.
 */
internal fun InputConstraint.usageGroup(): String? {
    val visible = members.filterNot { it.hidden }
    if (visible.isEmpty()) return null
    val body = visible.joinToString("|") { it.constraintHintToken() }
    return when (arity) {
        ConstraintArity.ExactlyOne -> "($body)"
        // A last-wins set is optional as a whole and its members are not exclusive, so it renders like an
        // at-most-one set: the square brackets say "optional" and the bar says "these belong together".
        ConstraintArity.AtMostOne, ConstraintArity.LastWins -> "[$body]"
    }
}

/**
 * The positional/subcommand `usage:` tail (`<command> [args]` for a group; the constraint groups + arg
 * shape + `[options]` otherwise).
 */
internal fun Command.usageTail(): String = if (isGroup) {
    "<command> [args]"
} else {
    // Constraint groups lead: they say which of the named inputs the command actually demands, which
    // `[options]` alone renders as merely optional. Required options come next for the same reason —
    // a line the command will refuse without belongs in the line that shows how to call it.
    val required = requiredOptionSummary()
    val tail = listOfNotNull(
        argSummary().ifEmpty { null },
        if (options.any { !it.hidden } || flags.any { !it.hidden }) "[options]" else null,
    )
    (constraints.mapNotNull { it.usageGroup() } + required + tail).joinToString(" ")
}

/**
 * The `--host <value>` fragments for options the command cannot run without, in declaration order.
 *
 * The value is always angle-bracketed, matching [argSummary]'s notation for a required positional; square
 * brackets additionally wrap it (`[=<value>]`) when the option's value is itself optional. The primary
 * spelling is used rather than the compact short a constraint group uses: a required option is named once
 * here and the reader has to be able to type it. A member of a constraint set is left out — its group
 * already renders, and listing it twice would say the set is required AND that one member is.
 */
internal fun Command.requiredOptionSummary(): List<String> {
    val constrained = constraints.flatMap { it.members }.toSet()
    return options
        .filter { !it.hidden && it.cardinality == Cardinality.Required && it !in constrained }
        .map { spec ->
            if (spec.bareValue != null) "${spec.token()}[=<${spec.resolvedPlaceholder()}>]"
            else "${spec.token()} <${spec.resolvedPlaceholder()}>"
        }
}

/** The full `usage: <name> <tail>` line, plain (unstyled) so docs can reuse it. */
internal fun Command.usageLine(qualifiedName: String = name): String = "usage: $qualifiedName ${usageTail()}".trimEnd()

/**
 * The ordered, labeled blocks for this command's help: an unlabeled default block, a `Commands` block,
 * custom [group] headings in first-appearance order, and a final `Global options` block. [globalSpecs]
 * and [rootVersioned] are caller-supplied, since only the root carries them. Hidden inputs are filtered
 * out throughout so they never widen the shared signature column [helpText] computes.
 */
internal fun Command.helpSections(
    globalSpecs: List<NamedSpec> = emptyList(),
    rootVersioned: Boolean = false,
    builtins: Builtins = Builtins.DEFAULT,
): List<HelpSection> {
    val sections = mutableListOf<HelpSection>()

    val main = mutableListOf<InputRow>()
    arguments.filter { !it.hidden }.forEach {
        main += InputRow(it.absentSlotToken("<${it.displayName()}>"), it.help + it.metaHint())
    }
    namedInputs.filter { !it.hidden && it.section == null }.forEach { main += it.helpRow() }
    if (main.isNotEmpty()) sections += HelpSection(null, main)

    // Ungrouped subcommands (the app's own plus the injected completion/docs) read as commands, so they
    // get a Commands heading rather than floating unlabeled in the default block above the options.
    val commands = subcommands
        .filter { !it.hidden && it.section == null }
        .map { it.helpRow() }
    if (commands.isNotEmpty()) sections += HelpSection("Commands", commands)

    val groupTitles = LinkedHashSet<String>()
    namedInputs
        .filter { !it.hidden }
        .mapNotNull { it.section }
        .forEach { groupTitles += it }
    subcommands
        .filter { !it.hidden }
        .mapNotNull { it.section }
        .forEach { groupTitles += it }
    for (title in groupTitles) {
        val rows = mutableListOf<InputRow>()
        namedInputs.filter { !it.hidden && it.section == title }.forEach { rows += it.helpRow() }
        subcommands
            .filter { !it.hidden && it.section == title }
            .forEach { rows += it.helpRow() }
        if (rows.isNotEmpty()) sections += HelpSection(title, rows)
    }

    // Built-ins are position-independent, so they render under Global options alongside the app's own
    // globals rather than in the default block. The section is always present since the built-ins are.
    val globalRows = globalSpecs
        .filter { !it.hidden }
        .map { it.helpRow() }
        .toMutableList()
    // --help is never declinable; only its -h short is, and dropping it leaves the row aligned with the
    // short-less padding [words] uses.
    globalRows += InputRow(if (builtins.helpShort) "-h, --help" else "    --help", BuiltinOptionHelp.HELP)
    // --help-all is only meaningful (and only advertised) where there are subcommands to expand.
    if (subcommands.any { !it.hidden }) globalRows += InputRow("    --help-all", "Show help for every subcommand")
    if (builtins.json) globalRows += InputRow("    --json", BuiltinOptionHelp.JSON)
    // --color is universal (like --json), so it advertises on every node the tree still offers it on.
    if (builtins.color) {
        globalRows += InputRow("    --color <${COLOR_MODE_NAMES.joinToString("|")}>", BuiltinOptionHelp.COLOR)
    }
    // version/metaOptions are root-only ([Cli]) facts; a plain node relies on the caller's [rootVersioned].
    if ((this is Cli && version != null) || rootVersioned) {
        globalRows += InputRow("    --version", BuiltinOptionHelp.VERSION)
    }
    if (this is Cli && metaOptions) {
        if (builtins.completion) {
            globalRows += InputRow(
                "    --completion <${COMPLETION_SHELL_NAMES.joinToString("|")}>",
                BuiltinOptionHelp.COMPLETION,
            )
        }
        if (builtins.docs) {
            globalRows += InputRow("    --docs <${DOC_FORMAT_NAMES.joinToString("|")}>", BuiltinOptionHelp.DOCS)
        }
    }
    sections += HelpSection("Global options", globalRows)

    return sections
}

// --- style ---

/**
 * Rendering parameters resolved once at the boundary. [columns] `== 0` disables wrapping (single-line
 * output).
 */
internal data class HelpStyle(val columns: Int, val color: Boolean) {
    // Not a constructor property: it is derived from [color], so it must stay out of equals/hashCode.
    val palette: Palette = Palette(color)

    companion object {
        val PLAIN = HelpStyle(columns = 0, color = false)
    }
}

/** Descriptions below this many columns are left on one line rather than wrapped into a sliver. */
private const val WRAP_FLOOR = 20

/** Greedy word-wrap on spaces. An over-long unbreakable token (a URL) is left intact on its own line. */
internal fun wrap(text: String, width: Int): List<String> {
    if (width <= 0 || text.isEmpty()) return listOf(text)
    val lines = mutableListOf<String>()
    var current = StringBuilder()
    for (word in text.split(" ")) {
        when {
            current.isEmpty() -> current.append(word)
            current.length + 1 + word.length <= width -> current.append(' ').append(word)
            else -> {
                lines += current.toString()
                current = StringBuilder(word)
            }
        }
    }
    if (current.isNotEmpty()) lines += current.toString()
    return lines.ifEmpty { listOf(text) }
}

/** Chrome bold, resolved through the same [ColorScope] an action's own output uses. */
private fun HelpStyle.paint(text: String): String = with(palette) { bold(text) }

/** The padded (and, when colored, bolded) signature column; the visible width stays [width] either way. */
private fun HelpStyle.paintSignature(signature: String, width: Int): String {
    // Branches on the flag rather than the palette: a bolded signature and a plain one must occupy the
    // same visible width, and only the plain form can use padEnd (ANSI codes are zero-width but counted).
    if (!color) return signature.padEnd(width)
    val pad = " ".repeat((width - signature.length).coerceAtLeast(0))
    return with(palette) { bold(signature) } + pad
}

/**
 * Collapses embedded newlines and runs of whitespace in a row's help/description text to a single
 * space, so a multi-line help string still renders as one aligned row in the plain --help output.
 * Markdown flattens the same text into a table cell and man relies on troff's own paragraph fill, so
 * neither needs this; it is scoped to the plain renderer alone.
 */
private fun collapseWhitespace(text: String): String =
    text.split(' ', '\n', '\t', '\r').filter { it.isNotEmpty() }.joinToString(" ")

private fun HelpStyle.renderRow(row: InputRow, width: Int): String {
    val description = collapseWhitespace(row.description)
    val gutter = 2 + width + 2
    val budget = columns - gutter
    // PLAIN (columns <= 0) must stay byte-for-byte stable: docs and tests quote this rendering verbatim.
    if (columns <= 0) {
        return "  ${paintSignature(row.signature, width)}  $description".trimEnd()
    }
    // Aligned two-column wrap while the description column is wide enough to be worth aligning.
    if (budget >= WRAP_FLOOR) {
        val lines = wrap(description, budget)
        val first = "  ${paintSignature(row.signature, width)}  ${lines.first()}".trimEnd()
        val indent = " ".repeat(gutter)
        val rest = lines.drop(1).map { "$indent$it".trimEnd() }
        return (listOf(first) + rest).joinToString("\n")
    }
    // Narrow terminal: aligning would leave a sliver of a description column and overflow the width,
    // so stack the description on its own indented lines under the signature, wrapped to full width.
    val signatureLine = "  ${paint(row.signature)}".trimEnd()
    if (description.isEmpty()) return signatureLine
    val indent = "    "
    val body = wrap(description, (columns - indent.length).coerceAtLeast(WRAP_FLOOR))
        .map { "$indent$it".trimEnd() }
    return (listOf(signatureLine) + body).joinToString("\n")
}

private fun HelpStyle.wrapParagraph(text: String): String =
    if (columns <= 0) {
        text
    } else {
        // Wrap each logical line independently: an embedded newline is an intentional break, and
        // folding it into a "word" would miscount the running width and force a ragged early break
        // on the line that follows it.
        text.split("\n").joinToString("\n") { wrap(it, columns).joinToString("\n") }
    }

/**
 * Full help for a command: usage line, description, the [helpSections] blocks, any [examples], and the
 * [epilogue]. [HelpStyle.PLAIN] renders unwrapped and uncolored; a non-zero `columns` word-wraps and
 * `color` adds a conservative ANSI palette. [globalSpecs] and [rootVersioned] come from the caller (see
 * [com.fromwau.klap.Invocation.ShowHelp]) and pass straight through to [helpSections].
 */
internal fun Command.helpText(
    qualifiedName: String = name,
    globalSpecs: List<NamedSpec> = emptyList(),
    style: HelpStyle = HelpStyle.PLAIN,
    rootVersioned: Boolean = false,
    builtins: Builtins = Builtins.DEFAULT,
): String {
    val sections = helpSections(globalSpecs, rootVersioned, builtins)
    val width = sections.flatMap { it.rows }.maxOfOrNull { it.signature.length } ?: 0

    return buildString {
        val usage = usageLine(qualifiedName)
        append(if (style.color) style.paint("usage:") + usage.removePrefix("usage:") else usage)

        if (description.isNotEmpty()) {
            append("\n\n")
            append(style.wrapParagraph(description))
        }

        for (section in sections) {
            append("\n\n")
            if (section.title != null) {
                append(style.paint("${section.title}:"))
                append("\n")
            }
            append(section.rows.joinToString("\n") { style.renderRow(it, width) })
        }

        if (examples.isNotEmpty()) {
            append("\n\n")
            append(style.paint("Examples:"))
            for (ex in examples) {
                append("\n")
                append("  $ ${ex.command}")
                if (ex.description.isEmpty()) continue
                style.wrapExampleDescription(ex.description).forEach { append("\n").append(it) }
            }
        }

        if (epilogue.isNotEmpty()) {
            append("\n\n")
            append(style.wrapParagraph(epilogue))
        }

        // Author is a root-only fact; a subcommand's help ([Command], not [Cli]) has none to show.
        (this@helpText as? Cli)?.author?.takeUnless { it.isBlank() }?.let { author ->
            append("\n\n")
            append(style.paint("Author:"))
            append(" $author")
        }
    }
}

/**
 * Recursive [helpText] backing `--help-all`: this node and every non-hidden descendant, each rendered as
 * its own complete help block (globals repeated per block, matching the man/markdown docs), joined by a
 * blank-line gap. Each block's usage line carries its qualified path, so the blocks are self-identifying.
 */
internal fun Command.helpTextAll(
    qualifiedName: String,
    globalSpecs: List<NamedSpec>,
    style: HelpStyle,
    rootVersioned: Boolean,
    builtins: Builtins = Builtins.DEFAULT,
): String {
    val blocks = mutableListOf<String>()
    fun visit(node: Command, path: String) {
        blocks += node.helpText(path, globalSpecs, style, rootVersioned, builtins)
        node.subcommands.filterNot { it.hidden }.forEach { visit(it, "$path ${it.name}") }
    }
    visit(this, qualifiedName)
    return blocks.joinToString("\n\n\n")
}

private fun HelpStyle.wrapExampleDescription(description: String): List<String> {
    val indent = "      "
    if (columns <= 0) return listOf("$indent$description")
    return wrap(description, (columns - indent.length).coerceAtLeast(WRAP_FLOOR)).map { "$indent$it" }
}
