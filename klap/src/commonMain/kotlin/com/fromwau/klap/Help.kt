package com.fromwau.klap

/** `<required> [optional] [name=default] <variadic>...` — the positional shape shown in usage. */
internal fun Cli.argSummary(): String = arguments.joinToString(" ") { spec ->
    when (val c = spec.cardinality) {
        Cardinality.Required -> "<${spec.name}>"
        Cardinality.Optional -> "[${spec.name}]"
        is Cardinality.Default -> "[${spec.name}=${spec.display(c.value)}]"
        is Cardinality.Multiple -> "<${spec.name}>..."
    }
}

/** An option's value placeholder shows its choices when constrained, else a generic `value`. */
private fun HolderSpec.valuePlaceholder(): String = choices?.joinToString("|") ?: "value"

/** Render a value for help: for a choice-backed spec, show the matching choice so an enum default prints in the choices' casing. */
private fun HolderSpec.display(value: Any?): String {
    val text = value.toString()
    return choices?.firstOrNull { it.equals(text, ignoreCase = true) } ?: text
}

/** The word-form of an option/flag: `-s, --long` (options add `<placeholder>`; flags never do). */
private fun HolderSpec.words(): String {
    val base = if (short != null) "-$short, --$name" else "    --$name"
    return if (kind == InputKind.OPTION) "$base <${valuePlaceholder()}>" else base
}

/** A trailing `  (…)` note on an arg/option row: choices, and its required/optional/default/repeatable rule. */
private fun HolderSpec.metaHint(): String {
    if (kind == InputKind.FLAG) return ""
    val hints = buildList {
        if (kind == InputKind.ARGUMENT && choices != null) add("one of: ${choices!!.joinToString(", ")}")
        when (val c = cardinality) {
            Cardinality.Required -> add("required")
            Cardinality.Optional -> add("optional")
            is Cardinality.Default -> add("default: ${display(c.value)}")
            is Cardinality.Multiple -> add(if (c.min > 0) "repeatable, min ${c.min}" else "repeatable")
        }
    }
    return if (hints.isEmpty()) "" else "  (${hints.joinToString("; ")})"
}

/** Full help for a command: usage line, its options/flags, and any subcommands. */
internal fun Cli.helpText(qualifiedName: String = name): String {
    val rows = mutableListOf<Pair<String, String>>()
    arguments.forEach { rows += "<${it.name}>" to (it.help + it.metaHint()) }
    (options + flags).forEach { rows += it.words() to (it.help + it.metaHint()) }
    rows += "-h, --help" to "Show this help"
    rows += "    --json" to "Output as JSON"
    if (version != null) rows += "    --version" to "Show the version"
    // List subcommands whenever there are any — including a single-command tool whose root also acts and only carries `completion`.
    subcommands.forEach { rows += it.name to it.description }
    val width = rows.maxOfOrNull { it.first.length } ?: 0
    return buildString {
        val usageTail = if (isGroup) {
            "<command> [args]"
        } else {
            listOfNotNull(
                argSummary().ifEmpty { null },
                if (options.isNotEmpty() || flags.isNotEmpty()) "[options]" else null,
            ).joinToString(" ")
        }
        append("usage: $qualifiedName $usageTail".trimEnd())
        if (description.isNotEmpty()) {
            append("\n\n")
            append(description)
        }
        if (rows.isNotEmpty()) {
            append("\n\n")
            append(rows.joinToString("\n") { (sig, desc) -> "  ${sig.padEnd(width)}  $desc".trimEnd() })
        }
    }
}
