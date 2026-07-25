package com.fromwau.klap.internal.render

import com.fromwau.klap.Builtins
import com.fromwau.klap.Command
import com.fromwau.klap.internal.spec.NamedSpec

/**
 * Neutralizes roff control syntax so arbitrary command/option/help text cannot corrupt the man page.
 * Backslash is escaped first so the backslashes later substitutions introduce are not re-escaped; `"`
 * becomes `\(dq` (a raw quote is swallowed as a macro-argument delimiter) and `-` becomes `\-` (read as
 * a minus sign); a leading `.`/`'` (both start a roff control line) gets the no-op `\&` prefix.
 */
internal fun roffEscape(text: String): String = text
    .replace("\\", "\\e")
    .replace("\"", "\\(dq")
    .replace("-", "\\-")
    .lines()
    .joinToString("\n") { if (it.startsWith(".") || it.startsWith("'")) "\\&$it" else it }

/**
 * One node's roff body: everything a section contains except the `.SH` heading. Shared by
 * [manSectionFor] (dispatcher nodes) and the single-command root's body, which [renderManPage] folds
 * directly under `.SH NAME`. [rootVersioned] threads the root's versioned status down so a non-root
 * node's entries list `--version` too, matching `--help`.
 */
internal fun manBodyFor(
    node: Command,
    path: String,
    globalSpecs: List<NamedSpec>,
    rootVersioned: Boolean,
    builtins: Builtins = Builtins.DEFAULT,
): String = buildString {
    appendLine(".B ${roffEscape(node.usageLine(path))}")
    if (node.description.isNotEmpty()) {
        // Break the paragraph so roff does not fill the description onto the bold usage line.
        appendLine(".PP")
        appendLine(roffEscape(node.description))
    }
    if (node.aliases.isNotEmpty()) {
        // Mirrors markdownFor's `Aliases:` line, roff-escaped like every other piece of user text here.
        appendLine(".PP")
        appendLine(roffEscape("Aliases: ${node.aliases.joinToString(", ")}"))
    }
    node.helpSections(globalSpecs, rootVersioned, builtins).forEach { section ->
        section.title?.let { appendLine(".SS ${roffEscape(it)}") }
        section.rows.forEach { row ->
            appendLine(".TP")
            // .trim() strips the same --help-only alignment padding as markdownFor's table cell above.
            appendLine(".B ${roffEscape(row.signature.trim())}")
            if (row.description.isNotEmpty()) appendLine(roffEscape(row.description))
        }
    }
    if (node.examples.isNotEmpty()) {
        appendLine(".SS ${roffEscape("Examples")}")
        node.examples.forEach { ex ->
            appendLine(".TP")
            appendLine(".B ${roffEscape(ex.command)}")
            if (ex.description.isNotEmpty()) appendLine(roffEscape(ex.description))
        }
    }
    if (node.epilogue.isNotEmpty()) {
        appendLine(".PP")
        appendLine(roffEscape(node.epilogue))
    }
}

/**
 * One node's roff block: a `.SH` titled with its qualified path, wrapping [manBodyFor]'s content. Used
 * for dispatcher nodes only; a single-command root's body is folded directly under `.SH NAME` instead
 * (see [renderManPage]), since a second `.SH <ROOT>` heading would just duplicate NAME.
 */
internal fun manSectionFor(
    node: Command,
    path: String,
    globalSpecs: List<NamedSpec>,
    rootVersioned: Boolean,
    builtins: Builtins = Builtins.DEFAULT,
): String = buildString {
    appendLine(".SH ${roffEscape(path.uppercase())}")
    append(manBodyFor(node, path, globalSpecs, rootVersioned, builtins))
}

/**
 * Neutralizes the injection-prone markdown in free paragraph text (a description, epilogue, or example note):
 * escapes a literal backslash first (so it cannot combine with what follows into an unintended escape, e.g. the
 * `\.` in a Windows path), then a literal backtick (so it cannot open a code span), then converts `<`/`>` to the
 * `&lt;`/`&gt;` entities (like [mdCell]) so an angle-bracketed word such as `<name>` is not swallowed as a raw
 * HTML tag. It only closes those code-span and raw-HTML-tag holes, not every markdown construct: headings, list
 * markers, and emphasis still pass through. Unlike [mdCell], it keeps newlines and other structure intact rather
 * than flattening the text into a single table cell.
 */
internal fun mdText(text: String): String = text
    .replace("\\", "\\\\")
    .replace("`", "\\`")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/**
 * Wraps [command] in a CommonMark code span that survives any backtick it contains. The delimiter is a
 * run of backticks one longer than the longest backtick run inside [command], so an embedded run can
 * never be read as the closing delimiter; and a command that starts or ends with a backtick gets a single
 * padding space just inside each delimiter, which CommonMark strips (it removes one leading and one
 * trailing space when both are present), so the boundary backtick stays literal instead of merging into
 * the delimiter. A command with no backtick keeps the plain single-backtick span, byte-identical to the
 * previous output.
 */
private fun mdCodeSpan(command: String): String {
    val longestBacktickRun = Regex("`+").findAll(command).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(longestBacktickRun + 1)
    val pad = if (command.startsWith("`") || command.endsWith("`")) " " else ""
    return "$fence$pad$command$pad$fence"
}

/**
 * One node's section of the combined markdown doc, built from the same [helpSections] rows `--help`
 * renders so the two cannot drift. [rootVersioned] threads the root's versioned status down so a
 * non-root node's table lists `--version` too, matching `--help`.
 */
internal fun markdownFor(
    node: Command,
    path: String,
    globalSpecs: List<NamedSpec>,
    rootVersioned: Boolean,
    builtins: Builtins = Builtins.DEFAULT,
): String = buildString {
    val depth = path.count { it == ' ' }
    val level = (2 + depth).coerceAtMost(6)
    appendLine("${"#".repeat(level)} ${mdCell(path)}")
    appendLine()
    appendLine("```")
    appendLine(node.usageLine(path))
    appendLine("```")
    if (node.description.isNotEmpty()) {
        appendLine()
        appendLine(mdText(node.description))
    }
    if (node.aliases.isNotEmpty()) {
        appendLine()
        // mdText-escaped like description/epilogue and the man twin: an alias is arbitrary user text
        // (requireValidName still permits markdown-active chars like brackets/backticks in an alias),
        // so a stray backtick/angle-bracket must not corrupt the page.
        appendLine(mdText("Aliases: ${node.aliases.joinToString(", ")}"))
    }
    node.helpSections(globalSpecs, rootVersioned, builtins).forEach { section ->
        if (section.rows.isEmpty()) return@forEach
        appendLine()
        // The untitled default block (positionals / ungrouped inputs, or a group's subcommands) carries no
        // heading in --help or the man page; don't invent an "Options" label for it here, which mislabels
        // subcommand and positional rows. Titled group and "Global options" sections keep their heading.
        section.title?.let {
            appendLine("**$it**")
            appendLine()
        }
        appendLine("| Name | Description |")
        appendLine("|---|---|")
        // .trim() strips the leading spaces --help pads a short-less option's signature with to align
        // "--long" under "-s, --long"; that alignment is --help-only and would otherwise leak raw into
        // the table cell.
        section.rows.forEach { row -> appendLine("| ${mdCell(row.signature.trim())} | ${mdCell(row.description)} |") }
    }
    if (node.examples.isNotEmpty()) {
        appendLine()
        appendLine("**Examples**")
        appendLine()
        node.examples.forEach { ex ->
            // ex.command sits inside a code span, where CommonMark does not interpret backslash escapes, so
            // mdText() is not applied; mdCodeSpan widens the delimiter so a backtick inside the command
            // cannot close the span early. ex.description is plain paragraph text, same as description/epilogue.
            val desc = if (ex.description.isNotEmpty()) ": ${mdText(ex.description)}" else ""
            appendLine("- ${mdCodeSpan(ex.command)}$desc")
        }
    }
    if (node.epilogue.isNotEmpty()) {
        appendLine()
        appendLine(mdText(node.epilogue))
    }
}

/**
 * GitHub-style heading anchor: lowercase, spaces to hyphens. Paths are plain space-joined names, so no
 * further stripping is needed.
 */
internal fun mdAnchor(path: String): String = path.lowercase().replace(" ", "-")

/**
 * Neutralizes a value bound for a markdown table cell: no literal pipe, backtick, or newline can escape
 * the cell or start a code span, and no `<`/`>` can be read as an (unclosed) HTML tag. The backtick is
 * escaped (`` \` ``), same as [mdText], rather than replaced, so the character survives instead of
 * silently becoming an apostrophe. Angle brackets become the `&lt;`/`&gt;` entities so a klap-generated
 * signature placeholder like `<value>`/`<name>` renders literally instead of being swallowed by a real
 * CommonMark/GFM renderer.
 */
internal fun mdCell(text: String): String = text
    .replace("\\", "\\\\")
    .replace("|", "\\|")
    .replace("`", "\\`")
    .replace("\n", " ")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

