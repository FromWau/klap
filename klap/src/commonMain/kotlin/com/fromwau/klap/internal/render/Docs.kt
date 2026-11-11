package com.fromwau.klap.internal.render

import com.fromwau.klap.Builtins
import com.fromwau.klap.Command
import com.fromwau.klap.Exit
import com.fromwau.klap.internal.spec.NamedSpec
import com.fromwau.klap.internal.spec.shown

/**
 * Neutralizes roff control syntax so arbitrary command/option/help text cannot corrupt the man page.
 * Backslash is escaped first so the backslashes later substitutions introduce are not re-escaped; `"`
 * becomes `\(dq` (a raw quote is swallowed as a macro-argument delimiter) and `-` becomes `\-` (read as
 * a minus sign); a leading `.`/`'` (both start a roff control line) gets the no-op `\&` prefix.
 */
internal fun roffEscape(text: String): String = roffEscapeLines(roffEscapeChars(text))

/** [roffEscape] for help prose, where a `` `code` `` span prints bold, as man pages print literals. */
internal fun roffProse(text: String): String = roffEscapeLines(
    codeSpans(text).joinToString("") { (piece, isCode) ->
        if (isCode) "\\fB${roffEscapeChars(piece)}\\fP" else roffEscapeChars(piece)
    },
)

private fun roffEscapeChars(text: String): String = text
    .replace("\\", "\\e")
    .replace("\"", "\\(dq")
    .replace("-", "\\-")

private fun roffEscapeLines(text: String): String = text
    .lines()
    .joinToString("\n") { if (it.startsWith(".") || it.startsWith("'")) "\\&$it" else it }

/**
 * [text] cut at its pairs of backticks: each piece, and whether it was the inside of a `` `code` `` span. A
 * backtick left without a partner, or a pair with nothing between, stays text.
 */
internal fun codeSpans(text: String): List<Pair<String, Boolean>> {
    val pieces = mutableListOf<Pair<String, Boolean>>()
    var textStart = 0
    var from = 0
    while (true) {
        val open = text.indexOf('`', from)
        val close = if (open < 0) -1 else text.indexOf('`', open + 1)
        if (close < 0) break
        if (close == open + 1) {
            from = close + 1
            continue
        }
        pieces += text.substring(textStart, open) to false
        pieces += text.substring(open + 1, close) to true
        textStart = close + 1
        from = textStart
    }
    pieces += text.substring(textStart) to false
    return pieces
}

/**
 * One node's roff body: everything a section contains except the `.SH` heading. Shared by
 * [manSectionFor] (dispatcher nodes) and the single-command root's body, which [renderManPage] folds
 * below the page's own opening sections. [rootVersioned] and [rootMetaOptions] thread the root's facts down so a
 * non-root node's entries list `--version`, `--completion` and `--docs` where they work, matching `--help`.
 */
internal fun manBodyFor(
    node: Command,
    path: String,
    globalSpecs: List<NamedSpec>,
    rootVersioned: Boolean,
    rootMetaOptions: Boolean,
    builtins: Builtins,
    showsIntro: Boolean,
): String = buildString {
    if (showsIntro) {
        appendLine(".B ${roffEscape(node.usageLine(path))}")
        if (node.description.isNotEmpty()) {
            // Break the paragraph so roff does not fill the description onto the bold usage line.
            appendLine(".PP")
            appendLine(roffProse(node.description))
        }
    }
    if (node.aliases.isNotEmpty()) {
        // Mirrors markdownFor's `Aliases:` line, roff-escaped like every other piece of user text here.
        appendLine(".PP")
        appendLine(roffEscape("Aliases: ${node.aliases.joinToString(", ")}"))
    }
    node.helpSections(globalSpecs, rootVersioned, rootMetaOptions, builtins).forEach { section ->
        section.title?.let { appendLine(".SS ${roffEscape(it)}") }
        section.rows.forEach { row ->
            appendLine(".TP")
            // .trim() strips the same --help-only alignment padding as markdownFor's table cell above.
            appendLine(".B ${roffEscape(row.signature.trim())}")
            if (row.description.isNotEmpty()) appendLine(roffProse(row.description))
        }
    }
    if (node.examples.isNotEmpty()) {
        appendLine(".SS ${roffEscape("Examples")}")
        node.examples.forEach { ex ->
            appendLine(".TP")
            appendLine(".B ${roffEscape(ex.command)}")
            if (ex.description.isNotEmpty()) appendLine(roffProse(ex.description))
        }
    }
    if (node.epilogue.isNotEmpty()) {
        appendLine(".PP")
        appendLine(roffProse(node.epilogue))
    }
}

/**
 * One node's roff block: a `.SH` titled with its qualified path, wrapping [manBodyFor]'s content. Used
 * for dispatcher nodes only; a single-command root's body is folded without one instead
 * (see [renderManPage]), since a second `.SH <ROOT>` heading would just duplicate NAME.
 */
internal fun manSectionFor(
    node: Command,
    path: String,
    globalSpecs: List<NamedSpec>,
    rootVersioned: Boolean,
    rootMetaOptions: Boolean,
    builtins: Builtins,
    showsIntro: Boolean,
): String = buildString {
    appendLine(".SH ${roffEscape(path.uppercase())}")
    append(manBodyFor(node, path, globalSpecs, rootVersioned, rootMetaOptions, builtins, showsIntro))
}

/** klap's exits every command's `--help` lists: all but 141, which only a pipe closed early gives. */
internal val KLAP_HELP_EXITS: List<Exit> = listOf(Exit.Success, Exit.Failure, Exit.Usage)

/** The exits klap gives every run, as the man page's `EXIT STATUS` lists them. */
internal val KLAP_EXITS: List<Exit> = KLAP_HELP_EXITS + Exit.BrokenPipe

/**
 * Every exit of the whole tool, one entry per code, sorted, with its meanings: klap's own first, then each exit the
 * commands declare, once however many declare it. A code with more than one declared exit names before each the
 * paths of the commands declaring it, so a reader can tell whose meaning is whose.
 */
internal fun exitMeanings(nodes: List<Pair<Command, String>>): List<Pair<Int, List<String>>> {
    val declarers = linkedMapOf<Exit, MutableList<String>>()
    nodes.forEach { (node, path) -> node.exits.forEach { declarers.getOrPut(it) { mutableListOf() } += path } }
    val declaredPerCode = declarers.keys.groupingBy { it.code }.eachCount()
    val declared = declarers.map { (exit, paths) ->
        val named = declaredPerCode.getValue(exit.code) > 1
        exit.code to if (named) "${paths.joinToString(", ")}: ${exit.shown}" else exit.shown
    }
    return grouped(KLAP_EXITS.map { it.code to it.shown } + declared)
}

/** [exits] one entry per code, sorted, each code's meanings as [shown] in the order given. */
internal fun byCode(exits: List<Exit>): List<Pair<Int, List<String>>> = grouped(exits.map { it.code to it.shown })

private fun grouped(meanings: List<Pair<Int, String>>): List<Pair<Int, List<String>>> {
    val byCode = meanings.groupBy({ it.first }, { it.second })
    return byCode.keys.sorted().map { it to byCode.getValue(it) }
}

/**
 * Neutralizes the injection-prone markdown in free paragraph text (a description, epilogue, or example note),
 * keeping each `` `code` `` span as code: outside the spans it escapes a literal backslash first (so it cannot
 * combine with what follows into an unintended escape, e.g. the `\.` in a Windows path), then a lone backtick (so
 * it cannot open a code span), then converts `<`/`>` to the `&lt;`/`&gt;` entities (like [mdCell]) so an
 * angle-bracketed word such as `<name>` is not swallowed as a raw HTML tag. Headings, list markers, and emphasis
 * still pass through. Unlike [mdCell], it keeps newlines and other structure intact rather than flattening the
 * text into a single table cell.
 */
internal fun mdText(text: String): String = codeSpans(text).joinToString("") { (piece, isCode) ->
    if (isCode) "`$piece`" else mdPlain(piece)
}

private fun mdPlain(text: String): String = text
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
 * renders so the two cannot drift. [rootVersioned] and [rootMetaOptions] thread the root's facts down so a
 * non-root node's table lists `--version`, `--completion` and `--docs` where they work, matching `--help`.
 */
internal fun markdownFor(
    node: Command,
    path: String,
    globalSpecs: List<NamedSpec>,
    rootVersioned: Boolean,
    rootMetaOptions: Boolean,
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
    node.helpSections(globalSpecs, rootVersioned, rootMetaOptions, builtins).forEach { section ->
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
        section.rows.forEach { row ->
            appendLine("| ${mdCell(row.signature.trim())} | ${mdProseCell(row.description)} |")
        }
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
    if (node.exits.isNotEmpty()) {
        appendLine()
        appendLine("**Exit status**")
        appendLine()
        appendLine("| Code | Meaning |")
        appendLine("|---|---|")
        node.exits.sortedBy { it.code }.forEach { appendLine("| ${it.code} | ${mdProseCell(it.shown)} |") }
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

/** [mdCell] for help prose, where a `` `code` `` span stays code; GFM still needs its `|` escaped. */
internal fun mdProseCell(text: String): String = codeSpans(text).joinToString("") { (piece, isCode) ->
    if (isCode) "`${piece.replace("|", "\\|").replace("\n", " ")}`" else mdCell(piece)
}

