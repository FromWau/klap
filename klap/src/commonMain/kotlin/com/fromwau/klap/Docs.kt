package com.fromwau.klap

import com.fromwau.klap.internal.render.helpSections
import com.fromwau.klap.internal.render.manBodyFor
import com.fromwau.klap.internal.render.manSectionFor
import com.fromwau.klap.internal.render.markdownFor
import com.fromwau.klap.internal.render.mdAnchor
import com.fromwau.klap.internal.render.mdText
import com.fromwau.klap.internal.render.roffEscape

public enum class DocFormat {
    MARKDOWN, MAN,
    ;

    public companion object {
        public fun fromOrNull(raw: String): DocFormat? = when (raw.lowercase()) {
            "markdown", "md" -> MARKDOWN
            "man", "roff" -> MAN
            else -> null
        }
    }
}

/** The doc format names as shown to users (matches the `docs` subcommand's enum choices). */
internal val DOC_FORMAT_NAMES: List<String> = DocFormat.entries.map { it.name.lowercase() }

/**
 * Every command pre-order (self first, then each subtree), paired with its space-joined qualified
 * path from the root (e.g. "fleet disk attach"). Unlike a BFS walk deduped by bare name, this walk
 * keeps every node at its own depth, so distinct commands that share a name at different depths both
 * appear. A hidden subcommand is skipped, mirroring `--help` and completion: a doc mirrors what a user
 * can discover, not internal plumbing like `__complete`.
 */
private fun Cli.docNodes(): List<Pair<Command, String>> {
    val out = mutableListOf<Pair<Command, String>>()
    fun visit(node: Command, path: String) {
        out += node to path
        node.subcommands.filterNot { it.hidden }.forEach { visit(it, "$path ${it.name}") }
    }
    visit(this, name)
    return out
}

/**
 * One browsable markdown page for the whole tree: a table of contents over [docNodes], then one section
 * per node, built from the same [helpSections] rows `--help` renders, so the two cannot drift. A
 * single-command tool (no non-hidden subcommand, so [docNodes] collapses to just the root) drops the ToC
 * entirely: `--help` never shows a "Commands:" section for such a tool, and a lone self-referencing
 * `- [name](#name)` entry would be pure noise. A dispatcher (>=1 non-hidden subcommand) keeps it.
 */
public fun Cli.renderMarkdownDocs(): String {
    val nodes = docNodes()
    return buildString {
        appendLine("# $name")
        // A single-command tool's one node renders this description again in its own section below
        // (markdownFor), so a top copy would print it twice back-to-back; only a dispatcher, whose top
        // synopsis introduces the ToC and the separate per-command sections, keeps it. mdText escaping
        // matches markdownFor so both renderings stay consistent.
        if (nodes.size > 1 && description.isNotEmpty()) {
            appendLine()
            appendLine(mdText(description))
        }
        if (nodes.size > 1) {
            appendLine()
            // A whole-tree navigation index, labeled "Contents" not "Commands": it is flat over every node
            // and ignores per-command grouping, so reusing "Commands" would clash with the per-node
            // grouped/"Commands" sections below (which mirror --help). Those keep their own titles.
            appendLine("## Contents")
            // Two distinct paths can slug to the same base anchor (a nested "db migrate" and a flat
            // "db-migrate" both become "db-migrate"); mirror how a CommonMark/GitHub renderer dedups
            // duplicate heading ids by suffixing -1, -2, ... in document order, so a later link targets its
            // own section instead of silently jumping to the first.
            val anchorSeen = mutableMapOf<String, Int>()
            nodes.forEach { (_, path) ->
                val base = mdAnchor(path)
                val seen = anchorSeen.getOrPut(base) { 0 }
                anchorSeen[base] = seen + 1
                val anchor = if (seen == 0) base else "$base-$seen"
                // Escape the characters that break markdown link-text syntax so a name containing '[' or
                // ']' (both allowed by requireValidName) cannot close the '[...]' early. The anchor target
                // computed above is left unchanged.
                val linkText = path
                    .replace("\\", "\\\\")
                    .replace("[", "\\[")
                    .replace("]", "\\]")
                appendLine("- [$linkText](#$anchor)")
            }
        }
        nodes.forEach { (node, path) ->
            appendLine()
            append(markdownFor(node, path, globalSpecs, version != null, builtins))
        }
        author?.takeUnless { it.isBlank() }?.let {
            appendLine()
            appendLine("## Author")
            appendLine()
            appendLine(mdText(it))
        }
    }.trimEnd() + "\n"
}

// --- roff (man) ---

/**
 * One roff man page for the whole tree: a `.TH` header (root name/version; [date] fills the date
 * field, empty by default since commonMain has no portable clock), a `.SH NAME`, then either a `.SH` per
 * [docNodes] entry (a dispatcher, >=1 non-hidden subcommand) covering every command's usage, description,
 * and arg/option/flag rows, or (for a single-command tool, where [docNodes] collapses to just the root)
 * that root's body folded directly under `.SH NAME` with no redundant `.SH <ROOT>` wrapper, matching how
 * `--help` never shows a "Commands:" section for such a tool.
 */
public fun Cli.renderManPage(date: String? = null): String {
    val stamp = date.orEmpty()
    val source = listOfNotNull(name, version).joinToString(" ")
    val nodes = docNodes()
    return buildString {
        val titleName = roffEscape(name.uppercase())
        val titleDate = roffEscape(stamp)
        val titleSource = roffEscape(source)
        val titleManual = "${roffEscape(name)} Manual"
        appendLine(".TH \"$titleName\" 1 \"$titleDate\" \"$titleSource\" \"$titleManual\"")
        appendLine(".SH NAME")
        // The POSIX NAME section is one line ("name \- summary"); collapse a multi-line description so an
        // embedded newline does not split it into a broken second roff line.
        val nameSummary = description
            .lineSequence()
            .joinToString(" ") { it.trim() }
            .trim()
        appendLine("${roffEscape(name)} \\- ${roffEscape(nameSummary)}")
        if (nodes.size > 1) {
            nodes.forEach { (node, path) ->
                append(
                    manSectionFor(
                        node,
                        path,
                        globalSpecs,
                        version != null,
                        builtins,
                    )
                )
            }
        } else {
            val (node, path) = nodes.single()
            append(manBodyFor(node, path, globalSpecs, version != null, builtins))
        }
        author?.takeUnless { it.isBlank() }?.let {
            appendLine(".SH AUTHOR")
            appendLine(roffEscape(it))
        }
    }.trimEnd() + "\n"
}

/** Dispatcher driving the `docs <format>` builtin with a typed [DocFormat], mirroring [renderCompletion]'s shape. */
public fun Cli.renderDocs(format: DocFormat, date: String? = null): String = when (format) {
    DocFormat.MARKDOWN -> renderMarkdownDocs()
    DocFormat.MAN -> renderManPage(date)
}
