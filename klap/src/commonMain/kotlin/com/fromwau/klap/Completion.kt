package com.fromwau.klap

enum class CompletionShell { BASH, ZSH, FISH }

fun completionShellOf(raw: String): CompletionShell? = when (raw.lowercase()) {
    "bash" -> CompletionShell.BASH
    "zsh" -> CompletionShell.ZSH
    "fish" -> CompletionShell.FISH
    else -> null
}

/** Description budget per menu entry; long enough to be useful, short enough for one row. */
private const val DESC_MAX = 70

/** Safe to embed in a single-quoted shell string and short enough for a menu. */
private fun clean(desc: String): String {
    val flat = desc.replace("'", "").replace("\n", " ")
    if (flat.length <= DESC_MAX) return flat
    val cut = flat.take(DESC_MAX)
    return cut.substringBeforeLast(' ', cut).trimEnd() + "..."
}

/** Every command in the tree, deduped by NAME breadth-first so the shallowest wins — shells key dispatch on the name, so a name recurring at different depths must resolve to one arm. */
internal fun Cli.completionNodes(): List<Cli> {
    val ordered = mutableListOf<Cli>()
    var frontier = subcommands
    while (frontier.isNotEmpty()) {
        ordered += frontier
        frontier = frontier.flatMap { it.subcommands }
    }
    return ordered.distinctBy { it.name }
}

private fun Cli.hasFileArg(): Boolean = arguments.any { it.isPath }

fun Cli.renderCompletion(shell: CompletionShell): String = when (shell) {
    CompletionShell.BASH -> renderBash()
    CompletionShell.ZSH -> renderZsh()
    CompletionShell.FISH -> renderFish()
}

private fun Cli.renderFish(): String = buildString {
    appendLine("# $name fish completion (generated)")
    appendLine("complete -c $name -f")
    subcommands.forEach { appendLine("complete -c $name -n '__fish_use_subcommand' -a '${it.name}' -d '${clean(it.description)}'") }
    completionNodes().forEach { node ->
        node.subcommands.forEach { child ->
            appendLine("complete -c $name -n '__fish_seen_subcommand_from ${node.name}' -a '${child.name}'")
        }
        if (node.hasFileArg()) {
            appendLine("complete -c $name -n '__fish_seen_subcommand_from ${node.name}' -F")
        }
    }
}

private fun Cli.renderBash(): String {
    val top = subcommands.joinToString(" ") { it.name }
    val arms = completionNodes()
        .filter { it.subcommands.isNotEmpty() }
        .joinToString("\n") { node ->
            val words = node.subcommands.joinToString(" ") { it.name }
            "    ${node.name}) COMPREPLY=( \$(compgen -W \"$words\" -- \"\$cur\") ); return ;;"
        }
    return buildString {
        appendLine("# $name bash completion (generated)")
        appendLine("_$name() {")
        appendLine("  local cur=\"\${COMP_WORDS[COMP_CWORD]}\"")
        appendLine("  local top=\"$top\"")
        appendLine("  if [ \"\$COMP_CWORD\" -le 1 ]; then")
        appendLine("    COMPREPLY=( \$(compgen -W \"\$top\" -- \"\$cur\") ); return")
        appendLine("  fi")
        appendLine("  case \"\${COMP_WORDS[1]}\" in")
        appendLine(arms)
        appendLine("  esac")
        appendLine("}")
        appendLine("complete -F _$name $name")
    }
}

private fun Cli.renderZsh(): String {
    val commands = subcommands.joinToString(" ") { "'${it.name}:${clean(it.description)}'" }
    val arms = completionNodes()
        .filter { it.subcommands.isNotEmpty() }
        .joinToString("\n") { node ->
            "    ${node.name}) compadd ${node.subcommands.joinToString(" ") { it.name }} ;;"
        }
    return buildString {
        appendLine("#compdef $name")
        appendLine("_$name() {")
        appendLine("  local -a commands")
        appendLine("  commands=($commands)")
        appendLine("  if (( CURRENT == 2 )); then")
        appendLine("    _describe 'command' commands")
        appendLine("    return")
        appendLine("  fi")
        appendLine("  case \$words[2] in")
        appendLine(arms)
        appendLine("  esac")
        appendLine("}")
        appendLine("_$name \"\$@\"")
    }
}
