package com.fromwau.klap

/** POSIX end-of-options: every token after it is positional, never a flag. */
internal const val END_OF_OPTIONS = "--"

/** A dash followed by a digit or '.' is a value (`-1m`, `-100`), not a flag — no flag starts with one. */
internal fun String.isDashLedValue(): Boolean = length > 1 && (this[1].isDigit() || this[1] == '.')

/** A token that reads as an option: starts with '-', and is not a dash-led value. */
internal fun String.isFlagLike(): Boolean =
    startsWith("-") && !isDashLedValue() && this != END_OF_OPTIONS && this != "-"

/** Parse [argv] against this root command. Pure: no output, no exit; the escape hatch. */
fun Cli.parse(argv: List<String>): Result<Invocation, CliError> {
    val beforeEnd = argv.takeWhile { it != END_OF_OPTIONS }
    val json = argv.hasGlobalJson()
    if (version != null && "--version" in beforeEnd) return Result.Success(Invocation.ShowVersion(this))

    val stripped = stripToken(argv, "--json")

    var cmd = this
    var rest = stripped
    val path = mutableListOf(name)
    while (rest.isNotEmpty()) {
        val child = cmd.subcommand(rest.first()) ?: break
        cmd = child
        path += child.name
        rest = rest.drop(1)
    }
    val qualifiedName = path.joinToString(" ")

    val segBeforeEnd = rest.takeWhile { it != END_OF_OPTIONS }
    if ("-h" in segBeforeEnd || "--help" in segBeforeEnd) return Result.Success(Invocation.ShowHelp(cmd, qualifiedName))

    return cmd.bind(rest, Globals(json), qualifiedName)
}

/** Array overload of [parse]: lets an escape-hatch caller pass the `main`-shaped `Array<String>` directly. */
fun Cli.parse(argv: Array<String>): Result<Invocation, CliError> = parse(argv.toList())

/** Whether the position-independent global --json flag is present (before the end-of-options marker). */
internal fun List<String>.hasGlobalJson(): Boolean = takeWhile { it != END_OF_OPTIONS }.contains("--json")

/** Remove every occurrence of [token] that appears before the end-of-options marker. */
private fun stripToken(argv: List<String>, token: String): List<String> {
    val end = argv.indexOf(END_OF_OPTIONS)
    return if (end < 0) {
        argv.filter { it != token }
    } else {
        argv.take(end).filter { it != token } + argv.drop(end)
    }
}

/** Collected option/flag occurrences plus leftover positionals for a command segment. */
internal class Sifted(
    val flags: Set<HolderSpec>,
    val options: Map<HolderSpec, List<String>>,
    val positionals: List<String>,
)

private fun Cli.findFlag(token: String): HolderSpec? = when {
    token.startsWith("--") -> flags.firstOrNull { it.name == token.removePrefix("--") }
    token.startsWith("-") -> flags.firstOrNull { it.short == token.removePrefix("-") }
    else -> null
}

private fun Cli.findOption(long: String?, short: String?): HolderSpec? =
    options.firstOrNull { (long != null && it.name == long) || (short != null && it.short == short) }

/** Split a segment into flags set, option->values map, and positionals. Errors on unknown dash tokens. */
internal fun Cli.sift(segment: List<String>): Result<Sifted, CliError> {
    val flagsSeen = mutableSetOf<HolderSpec>()
    val optionValues = mutableMapOf<HolderSpec, MutableList<String>>()
    val positionals = mutableListOf<String>()

    var i = 0
    var optionsEnded = false
    while (i < segment.size) {
        val token = segment[i]
        when {
            optionsEnded -> {
                positionals += token
                i += 1
            }

            token == END_OF_OPTIONS -> {
                optionsEnded = true
                i += 1
            }

            !token.isFlagLike() -> {
                positionals += token
                i += 1
            }

            token.startsWith("--") -> {
                val body = token.removePrefix("--")
                val eq = body.indexOf('=')
                val long = if (eq >= 0) body.take(eq) else body
                val inlineValue = if (eq >= 0) body.drop(eq + 1) else null
                val flag = findFlag("--$long")
                if (flag != null) {
                    flagsSeen += flag
                    i += 1
                } else {
                    val opt = findOption(long, null)
                        ?: return Result.Error(CliError.UnknownOption("--$long"))
                    val value = inlineValue
                        ?: segment.getOrNull(i + 1)?.takeUnless { it.isFlagLike() || it == END_OF_OPTIONS }
                        ?: return Result.Error(CliError.MissingOptionValue(long))
                    optionValues.getOrPut(opt) { mutableListOf() } += value
                    i += if (inlineValue != null) 1 else 2
                }
            }

            else -> {
                // Short cluster: each char is a flag until one names an option, which takes the rest (`-p8080`) or the next token.
                val chars = token.removePrefix("-")
                var advance = 1
                var j = 0
                while (j < chars.length) {
                    val ch = chars[j].toString()
                    val flag = findFlag("-$ch")
                    if (flag != null) {
                        flagsSeen += flag
                        j += 1
                    } else {
                        val opt = findOption(null, ch)
                            ?: return Result.Error(CliError.UnknownOption("-$ch"))
                        val attached = chars.substring(j + 1).ifEmpty { null }
                        val value = attached
                            ?: segment.getOrNull(i + 1)?.takeUnless { it.isFlagLike() || it == END_OF_OPTIONS }
                            ?: return Result.Error(CliError.MissingOptionValue(opt.name))
                        optionValues.getOrPut(opt) { mutableListOf() } += value
                        advance = if (attached != null) 1 else 2
                        j = chars.length
                    }
                }
                i += advance
            }
        }
    }
    return Result.Success(Sifted(flagsSeen, optionValues, positionals))
}

/** Convert one raw value through a spec, mapping a converter failure to the right CliError. */
private fun HolderSpec.convertOne(raw: String): Result<Any?, CliError> =
    convert(raw).mapError { reason ->
        if (choices != null) CliError.InvalidChoice(name, raw, choices!!) else CliError.BadValue(name, raw, reason)
    }

internal fun Cli.bind(segment: List<String>, globals: Globals, qualifiedName: String): Result<Invocation, CliError> {
    if (isGroup) {
        val ddIndex = segment.indexOf(END_OF_OPTIONS)
        val positionals = if (ddIndex < 0) {
            segment.filterNot { it.isFlagLike() }
        } else {
            segment.take(ddIndex).filterNot { it.isFlagLike() } + segment.drop(ddIndex + 1)
        }
        return when {
            positionals.isNotEmpty() -> Result.Error(CliError.UnknownSubcommand(name, positionals.first()))
            segment.any { it.isFlagLike() } -> Result.Error(CliError.UnknownOption(segment.first { it.isFlagLike() }))
            else -> Result.Success(Invocation.ShowHelp(this, qualifiedName))
        }
    }

    val sifted = sift(segment).getOrElse { return Result.Error(it) }

    flags.forEach { it.bind(it in sifted.flags) }

    for (opt in options) {
        val raws = sifted.options[opt].orEmpty()
        when (val c = opt.cardinality) {
            is Cardinality.Multiple -> {
                val converted = raws.map { opt.convertOne(it).getOrElse { e -> return Result.Error(e) } }
                opt.bind(converted)
            }
            else -> {
                val raw = raws.lastOrNull()
                if (raw != null) {
                    opt.bind(opt.convertOne(raw).getOrElse { return Result.Error(it) })
                } else when (c) {
                    is Cardinality.Default -> opt.bind(c.value)
                    Cardinality.Required -> return Result.Error(CliError.MissingRequiredOption(opt.name))
                    else -> opt.bind(null)
                }
            }
        }
    }

    bindPositionals(sifted.positionals).getOrElse { return Result.Error(it) }
    return Result.Success(Invocation.Execute(this, globals))
}

/** Assign [values] to this command's argument specs; enforce required/variadic/extra rules. */
internal fun Cli.bindPositionals(values: List<String>): Result<Unit, CliError> {
    val args = arguments
    var i = 0
    for ((index, spec) in args.withIndex()) {
        val isLast = index == args.lastIndex
        when (val c = spec.cardinality) {
            is Cardinality.Multiple -> {
                val slice = values.drop(i)
                if (slice.size < c.min) return Result.Error(CliError.MissingArgument(name, spec.name))
                val converted = slice.map { spec.convertOne(it).getOrElse { e -> return Result.Error(e) } }
                spec.bind(converted)
                i = values.size
            }
            else -> {
                val raw = values.getOrNull(i)
                if (raw == null) {
                    when (c) {
                        is Cardinality.Default -> spec.bind(c.value)
                        Cardinality.Optional -> spec.bind(null)
                        else -> return Result.Error(CliError.MissingArgument(name, spec.name))
                    }
                } else {
                    val value = spec.convertOne(raw).getOrElse { return Result.Error(it) }
                    spec.bind(value)
                    i += 1
                }
            }
        }
        if (isLast && i < values.size) {
            return Result.Error(CliError.TooManyArguments(name, values.drop(i)))
        }
    }
    if (args.isEmpty() && values.isNotEmpty()) {
        return Result.Error(CliError.TooManyArguments(name, values))
    }
    return Result.Success(Unit)
}
