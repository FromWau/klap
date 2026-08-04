package com.fromwau.klap

import com.fromwau.klap.internal.parse.ArgvScan
import com.fromwau.klap.internal.parse.END_OF_OPTIONS
import com.fromwau.klap.internal.parse.accumulator
import com.fromwau.klap.internal.parse.bind
import com.fromwau.klap.internal.parse.bindGlobals
import com.fromwau.klap.internal.parse.isFlagLike
import com.fromwau.klap.internal.parse.optionValueSlots
import com.fromwau.klap.internal.parse.resolveChoice
import com.fromwau.klap.internal.parse.resolvedLongPool
import com.fromwau.klap.internal.parse.siftGlobals
import com.fromwau.klap.internal.parse.subcommandCandidates
import com.fromwau.klap.internal.parse.suggest
import com.fromwau.klap.internal.spec.Builtin
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.longs
import com.fromwau.klap.internal.spec.negativeLongs

/** How `--color` resolves; see [colorMode] (lenient extraction) and `parse` (the strict, error-reporting form). */
internal enum class ColorMode {
    AUTO, ALWAYS, NEVER,
    ;

    companion object {
        fun fromOrNull(raw: String): ColorMode? = when (raw.lowercase()) {
            "auto" -> AUTO
            "always" -> ALWAYS
            "never" -> NEVER
            else -> null
        }
    }
}

/** The `--color` choice names as shown to users (matches `parse`'s `InvalidChoice` list). */
internal val COLOR_MODE_NAMES: List<String> = ColorMode.entries.map { it.name.lowercase() }

/**
 * Every long spelling the built-in layer answers to, dashes stripped: the ones a tree resolved this way
 * actually offers, gated exactly as [parse] gates them. Used both to resolve an abbreviated built-in and to
 * let a built-in take part in an app option's ambiguity, so `--he` against a declared `--header` reports
 * both possibilities rather than silently choosing. [versioned] is `version != null` and [metaOptions] the
 * single-command root's `--completion`/`--docs` surface.
 */
internal fun builtinLongs(
    builtins: Builtins,
    versioned: Boolean,
    metaOptions: Boolean,
): List<String> = listOfNotNull(
    "help",
    "help-all",
    "json".takeIf { builtins.json },
    "color".takeIf { builtins.color },
    "version".takeIf { versioned },
    "completion".takeIf { metaOptions && builtins.completion },
    "docs".takeIf { metaOptions && builtins.docs },
)

/**
 * The pool every scan that runs BEFORE the subcommand walk resolves against: this tree's [builtinLongs], its
 * globals, and every long declared anywhere in the tree ([Cli.declaredLongs]).
 *
 * Only a built-in NAME is ever acted on by those scans; the other two widen the pool for ambiguity detection
 * alone, since a scan here runs before the walk knows which command it reaches: `--ver` on a tree that
 * declares `--verbose` anywhere must not resolve to the built-in `--version`. Resolving to anything but a
 * built-in leaves the token untouched, so [parse]'s strip and precedence order are unchanged.
 *
 * The cost: one command's long can decline an abbreviation on behalf of its siblings, so `app sub --ver` is
 * refused even where `sub` alone declares no `--verbose`. An error in place of a mis-binding, at a position
 * where the command is not yet known.
 */
internal fun Cli.positionIndependentLongs(): List<String> {
    val globalFlags = globalSpecs.filterIsInstance<FlagSpec>()
    return builtinLongs(builtins, version != null, metaOptions) +
            globalSpecs.flatMap { it.longs } +
            globalFlags.filter { it.negatable }.flatMap { it.negativeLongs } +
            declaredLongs
}

/**
 * The pre-walk view of [argv] every position-independent scan shares: [positionIndependentLongs] as the
 * pool it resolves against, and the value slots [optionValueSlots] read off the tree's full arity.
 *
 * The pool is passed in rather than rebuilt: a caller that also renders already holds the one it must
 * agree with.
 */
internal fun Cli.builtinScan(
    argv: List<String>,
    pool: List<String> = positionIndependentLongs(),
): ArgvScan = ArgvScan(pool, inference != Inference.None, optionValueSlots(argv), argv)

/** The built-ins that are boolean, so an inline `=value` on one is always a usage error. */
private val VALUELESS_BUILTIN_LONGS = listOf("help", "help-all", "json", "version")

/**
 * The effective `--color` mode read leniently from raw [argv]: absent, present without a value, or an
 * unrecognized value all resolve to [ColorMode.AUTO] rather than error, since [parse] is the one place
 * that reports a specific `--color` mistake; [run] only ever needs a mode to resolve against, never a
 * reason to fail. Strips `--json` first, same as [parse]'s own `withoutJson` step, so this sees the same
 * token view `parse()` does: a `--json` sitting between a space-form `--color` and its value is never
 * mistaken for that value. A tree that declined either built-in reads neither token: `--color` is then
 * the app's own (or unknown), and AUTO is the only mode klap still has an opinion about.
 *
 * [pool] is the root's own [positionIndependentLongs], [valueSlots] the root's own [optionValueSlots], and
 * [infer] the root's own [Cli.inference] reduced to a boolean, so `--col` abbreviates, `-e --color` stays
 * `-e`'s value, and `al` resolves to `always` here exactly as they do in [parse]; the defaults stand in for
 * a caller with no root at hand, mirroring [Builtins.DEFAULT] and [Inference.None].
 */
internal fun List<String>.colorMode(
    builtins: Builtins = Builtins.DEFAULT,
    pool: List<String> = builtinLongs(builtins, versioned = true, metaOptions = true),
    valueSlots: Set<Int> = emptySet(),
    infer: Boolean = false,
): ColorMode {
    if (!builtins.color) return ColorMode.AUTO
    val scan = ArgvScan(pool, infer, valueSlots, this)
    val head = if (builtins.json) scan.strip("json") else scan
    val raw = head.value("color").getOrElse { null }
    // Same gate as parse(): an ambiguous prefix has no single mode to report here, so it falls back to AUTO.
    val resolved = raw?.let {
        if (infer) (resolveChoice("--color", it, COLOR_MODE_NAMES) as? Result.Success)?.value else it
    }
    return resolved?.let { ColorMode.fromOrNull(it) } ?: ColorMode.AUTO
}

/**
 * A boolean built-in (`--help`/`-h`, `--help-all`, `--json`, `--version`) given an inline `=value` before
 * the end-of-options marker takes no value, same as any other boolean flag/no-arg option; report that
 * precisely rather than let the token fall through to the generic unknown-option path. A built-in this tree
 * declined is not among the names checked, so its `=value` form belongs to whatever the app declared under
 * that name. The error names the spelling the user wrote, abbreviated or not.
 */
private fun Cli.builtinInlineValueError(scan: ArgvScan): CliError? {
    // A short never abbreviates, so `-h=` stays the literal scan it always was.
    if (builtins.helpShort && scan.openTokens.any { it.startsWith("-h=") }) {
        return CliError.FlagTakesNoValue("--help", null)
    }
    val inline = scan.openTokens.filter { '=' in it }
    // Which names are still klap's has to be asked here rather than read off the scan's pool: an app that
    // declined a built-in may declare its own input under that freed name, and the pool carries the app's
    // spellings too.
    val offered = builtinLongs(builtins, version != null, metaOptions)
    // Keyed on the built-in rather than on argv order, so which one a line with two offenders reports
    // does not depend on how the user ordered them.
    val offender = VALUELESS_BUILTIN_LONGS.filter { it in offered }.firstNotNullOfOrNull { long ->
        inline.firstOrNull { scan.matched(it) == long }
    }
    return offender?.let { CliError.FlagTakesNoValue(it.substringBefore('='), null) }
}

/**
 * Whether [scan] carries a help request; `-h` counts only while the root still offers that short.
 *
 * Alone among the `--help` lookups this resolves against the TREE-WIDE pool rather than the reached
 * command's, because it runs before the walk: it gates the `--completion`/`--docs` short-circuit, and those
 * are themselves matched before the tree knows its command. It decides precedence only, never what binds,
 * so the price is confined to a hybrid root given `--h` alongside `--completion <shell>` on one line, where
 * a sibling's long can make the abbreviation ambiguous here and let the meta-option run instead.
 */
private fun Cli.hasHelpRequest(scan: ArgvScan): Boolean =
    scan.names("help") || scan.names("help-all") || (builtins.helpShort && scan.namesShort("-h"))

/**
 * The same [CliError.UnknownSubcommand] `Command.bind`'s group branch raises for a group handed a leading
 * token it cannot resolve as a child, reached here too so `--help`/`--help-all` below never mask the miss:
 * `app zzz --help` must read exactly like `app zzz` (a usage error), not silently render `app`'s help for a
 * name the walk never actually matched.
 *
 * Only a GROUP's unresolved, non-flag leading token counts. A resolved LEAF's leftover is that leaf's own
 * positional business, never an unknown-subcommand question, so `rm abc --help` (a real command, a bad
 * value) still answers with help — which is what someone who mistyped a value wants.
 */
private fun Cli.unknownSubcommandBeforeHelp(cmd: Command, rest: List<String>): CliError.UnknownSubcommand? {
    val leadingToken = rest.firstOrNull() ?: return null
    if (!cmd.isGroup || leadingToken.isFlagLike()) return null
    if (cmd.resolveSubcommand(leadingToken, inference == Inference.All) !is SubcommandMatch.None) return null
    return CliError.UnknownSubcommand(cmd.name, leadingToken, suggest(leadingToken, cmd.subcommandCandidates()))
}

/**
 * [raw] resolved through [resolveChoice] when this tree infers values, or [raw] itself otherwise: the same
 * gate an app-declared `.choice()`/`.enum<E>()` value goes through, applied here to klap's own `--color`,
 * `completion <shell>` and `docs <format>`, none of which reach a `ValueSpec` of their own. A user cannot
 * tell a built-in from an option the app declared, so both must resolve by the same rule.
 */
// Reads this tree's own inference rather than globalAcc.inferNames: the meta-option checks that call this
// run before the accumulator is constructed, so there is no globalAcc yet to read it from.
private fun Cli.resolveBuiltinChoice(name: String, raw: String, choices: List<String>): Result<String, CliError> =
    if (inference != Inference.None) resolveChoice(name, raw, choices) else Result.Success(raw)

/** Parse [argv] against this root command. Pure: no output, no exit; the escape hatch. */
public fun Cli.parse(argv: Collection<String>): Result<Invocation, CliError> = parseTokens(argv.toList())

/**
 * The parse proper, over an indexable snapshot.
 *
 * [parse] widens its parameter to [Collection] so any collection a caller already holds goes straight in,
 * but the walk indexes and slices argv throughout, so it copies to a [List] once here rather than at every
 * use. Passing an unordered collection is the caller's error: argv is a sequence and the order is the input.
 */
private fun Cli.parseTokens(argv: List<String>): Result<Invocation, CliError> {
    val builtinPool = positionIndependentLongs()
    // Resolved once, ahead of every scan below and shared by all of them: a token standing in a
    // value-taking option's argument slot belongs to that option, so no built-in may claim it.
    val scan = builtinScan(argv, builtinPool)
    val json = builtins.json && scan.names("json")
    builtinInlineValueError(scan)?.let { return Result.Error(it) }

    // --json is position-independent and removed before the subcommand walk; strip it once up front so the
    // meta-option scans see the same token list the walk does, and a --json sitting in a completion/docs
    // value slot never mis-parses as that option's value. Declined, the token is left in place so it
    // reaches the app's own --json (or fails as unknown), rather than being silently swallowed.
    val withoutJson = if (builtins.json) scan.strip("json") else scan

    // --color is a position-independent, required-value modifier: unlike completion/docs below (gated behind
    // metaOptions), it is validated for EVERY tree, then stripped (flag and value) before the walk, so
    // `--color=never build` still resolves `build`. Unlike version/completion/docs it never short-circuits to
    // an Invocation; its effect (effectiveColor) is read back by `run()`, post-parse, against `terminal.ansi`.
    // Validated here, before the version/completion/docs checks below, so a malformed value is reported even
    // when --version is also present.
    val withoutColor = if (builtins.color) {
        val colorRaw = withoutJson.value("color").getOrElse { return Result.Error(it) }
        colorRaw?.let { raw ->
            val resolved = resolveBuiltinChoice("color", raw, COLOR_MODE_NAMES)
                .getOrElse { return Result.Error(it) }
            if (ColorMode.fromOrNull(resolved) == null) {
                return Result.Error(
                    CliError.InvalidChoice(
                        "color", raw, COLOR_MODE_NAMES,
                        suggest(raw, COLOR_MODE_NAMES)
                    )
                )
            }
        }
        withoutJson.stripValued("color")
    } else {
        withoutJson
    }

    if (version != null && scan.names("version")) {
        return Result.Success(Invocation.ShowVersion(this))
    }

    // Single-command roots expose completion/docs as position-independent, print-and-exit meta-options
    // (dispatchers use subcommands). Recognized here like --version, before the walk and binding.
    // --help / -h outrank the meta-options (design precedence: version, help, then completion/docs).
    if (metaOptions && !hasHelpRequest(scan)) {
        if (builtins.completion) {
            withoutColor.value("completion").getOrElse { return Result.Error(it) }
                ?.let { raw ->
                    val resolved = resolveBuiltinChoice("completion", raw, COMPLETION_SHELL_NAMES)
                        .getOrElse { return Result.Error(it) }
                    val shell = CompletionShell.fromOrNull(resolved)
                        ?: return Result.Error(
                            CliError.InvalidChoice(
                                "completion", raw, COMPLETION_SHELL_NAMES,
                                suggest(raw, COMPLETION_SHELL_NAMES)
                            )
                        )
                    return Result.Success(Invocation.ShowCompletion(this, shell))
                }
        }
        if (builtins.docs) {
            withoutColor.value("docs").getOrElse { return Result.Error(it) }?.let { raw ->
                val resolved = resolveBuiltinChoice("docs", raw, DOC_FORMAT_NAMES)
                    .getOrElse { return Result.Error(it) }
                val format = DocFormat.fromOrNull(resolved)
                    ?: return Result.Error(
                        CliError.InvalidChoice(
                            "docs", raw, DOC_FORMAT_NAMES,
                            suggest(raw, DOC_FORMAT_NAMES)
                        )
                    )
                return Result.Success(Invocation.ShowDocs(this, format))
            }
        }
    }

    // Globals are position-independent: pulled out before the subcommand walk, the same move --json already
    // makes, and stopping at a value slot for the same reason the built-in strips above do.
    val preStrip = globalSpecs.siftGlobals(withoutColor, builtinPool)
    val globalSift = preStrip.sift

    var cmd: Command = this
    var rest = preStrip.cleaned
    // Dropped in lockstep with `rest`, so the leaf segment still knows where each of its tokens sat in the
    // original argv: the strips above removed tokens outright, so no index into `rest` can say.
    var restPositions = preStrip.positions
    val path = mutableListOf(name)
    while (rest.isNotEmpty()) {
        when (val match = cmd.resolveSubcommand(rest.first(), inference == Inference.All)) {
            is SubcommandMatch.One -> {
                cmd = match.command
                path += match.command.name
                rest = rest.drop(1)
                restPositions = restPositions.drop(1)
            }

            is SubcommandMatch.Ambiguous ->
                return Result.Error(CliError.AmbiguousSubcommand(cmd.name, rest.first(), match.candidates))

            SubcommandMatch.None -> break
        }
    }
    val qualifiedName = path.joinToString(" ")

    // A global buried in a mixed short cluster (`-fv`, where `f` is local) survives the pre-strip, since the
    // cluster is left whole for the resolved command's own sift. That sift is global-aware and tops up this
    // accumulator, so globals are bound AFTER the command bind, over the pre-strip occurrences plus any the
    // segment added. A Required-but-absent global is deferred: whether it matters depends on where the walk
    // ended up (a bare group that only shows help doesn't need it; a leaf that executes does).
    val globalAcc = globalSift.accumulator(globalSpecs, version, builtins, metaOptions, declaredLongs, inference)

    // --help and --help-all are the only built-ins resolved after the walk, so they resolve against the pool
    // of the command it reached rather than the tree-wide one every pre-walk scan must settle for: a
    // `--header` on one command would otherwise take `--h` away from every other command in the tree. The
    // value slots come along unchanged, keyed on the original argv, so `sub -e --help f` is still `-e`'s.
    val segment = ArgvScan(
        cmd.resolvedLongPool(globalAcc),
        globalAcc.inferNames,
        scan.valueSlots,
        rest,
        restPositions,
    )
    // Gated on the help request: without one the same error already comes from cmd.bind() below, where a
    // global-sift error rightly outranks it. Firing unconditionally here would invert that precedence.
    if (hasHelpRequest(segment)) {
        unknownSubcommandBeforeHelp(cmd, rest)?.let { return Result.Error(it) }
    }

    // --help-all outranks --help (a more specific request for the same node); both sit below --version.
    if (segment.names("help-all")) {
        return Result.Success(
            Invocation.ShowHelp(
                cmd,
                qualifiedName,
                globalSpecs,
                version != null,
                recursive = true,
                builtins = builtins,
            )
        )
    }
    if ((builtins.helpShort && segment.namesShort("-h")) || segment.names("help")) {
        return Result.Success(
            Invocation.ShowHelp(cmd, qualifiedName, globalSpecs, version != null, builtins = builtins)
        )
    }

    // A hard global-sift error (dangling global value, or a value handed to a global boolean flag) is a
    // usage error regardless of where the walk ends up; raise it before the command bind sees the leftover.
    globalSift.error?.let { return Result.Error(it) }

    // A resolved built-in renders the whole tree via this root, so route it straight to its Show* invocation
    // using `this`, with no binding, no action, no self-reference. Its node exists only to be listed/documented.
    // Placed after --help (so `completion --help` shows the node's help) and after the hard global-sift error.
    cmd.builtinKind?.let { return routeBuiltin(it, rest) }

    // Every resolved input is recorded in this per-parse sink, never on the shared specs, so the tree stays
    // immutable during parsing (concurrent parses each own their own sink). It is frozen into the Execute's
    // ActionScope once the command and its globals are both bound.
    val sink = mutableMapOf<HolderSpec, Any?>()
    return when (val outcome = cmd.bind(rest, Globals(json), qualifiedName, globalAcc, sink, restPositions)) {
        is Result.Error -> outcome
        is Result.Success -> {
            val deferredGlobalErrors = bindGlobals(
                globalSpecs,
                globalAcc.toGlobalSift(),
                sink,
                globalAcc.inferNames,
            ).getOrElse { return Result.Error(it) }
            when (val invocation = outcome.value) {
                is Invocation.Execute -> {
                    // Freeze the completed sink (command inputs + globals) into the Execute's read snapshot.
                    val exec = invocation.copy(scope = ActionScope(sink))
                    deferredGlobalErrors.firstOrNull()?.let { Result.Error(it) } ?: Result.Success(
                        exec
                    )
                }

                is Invocation.ShowHelp -> Result.Success(
                    Invocation.ShowHelp(
                        invocation.command,
                        invocation.qualifiedName,
                        globalSpecs,
                        version != null,
                        builtins = builtins,
                    ),
                )
                // bind() only ever yields Execute or ShowHelp; the rest keep the when exhaustive.
                is Invocation.ShowVersion -> outcome
                is Invocation.ShowCompletion -> outcome
                is Invocation.ShowDocs -> outcome
                is Invocation.ShowCompleteCandidates -> outcome
            }
        }
    }
}

/**
 * Array overload of [parse]: lets an escape-hatch caller pass the `main`-shaped `Array<String>` directly.
 *
 * An `Array` is not a [Collection], so this cannot be folded into the widened parameter and has to stay a
 * separate overload.
 */
public fun Cli.parse(argv: Array<String>): Result<Invocation, CliError> = parseTokens(argv.toList())

/**
 * Route a resolved built-in to its render invocation, parsing its single argument from the raw tokens
 * [args] with the same `CompletionShell.fromOrNull`/`DocFormat.fromOrNull` the `--completion`/`--docs` use, so
 * both forms report an unknown value identically. The root is `this`, handed straight to the invocation.
 */
private fun Cli.routeBuiltin(kind: Builtin, args: List<String>): Result<Invocation, CliError> =
    when (kind) {
        Builtin.Completion -> {
            val raw = args.firstOrNull() ?: return Result.Error(CliError.MissingArgument("completion", "shell"))
            val resolved = when (val r = resolveBuiltinChoice("completion", raw, COMPLETION_SHELL_NAMES)) {
                is Result.Error -> return r
                is Result.Success -> r.value
            }
            val shell = CompletionShell.fromOrNull(resolved)
                ?: return Result.Error(
                    CliError.InvalidChoice(
                        "completion", raw, COMPLETION_SHELL_NAMES,
                        suggest(raw, COMPLETION_SHELL_NAMES)
                    )
                )
            // The node declares exactly one argument; reject a surplus operand instead of dropping it,
            // matching bindPositionals (a builtin routes before binding, so it enforces arity itself).
            if (args.size > 1) return Result.Error(CliError.TooManyArguments("completion", args.drop(1)))
            Result.Success(Invocation.ShowCompletion(this, shell))
        }

        Builtin.Docs -> {
            val raw = args.firstOrNull() ?: return Result.Error(CliError.MissingArgument("docs", "format"))
            val resolved = when (val r = resolveBuiltinChoice("docs", raw, DOC_FORMAT_NAMES)) {
                is Result.Error -> return r
                is Result.Success -> r.value
            }
            val format = DocFormat.fromOrNull(resolved)
                ?: return Result.Error(
                    CliError.InvalidChoice(
                        "docs", raw, DOC_FORMAT_NAMES,
                        suggest(raw, DOC_FORMAT_NAMES)
                    )
                )
            if (args.size > 1) return Result.Error(CliError.TooManyArguments("docs", args.drop(1)))
            Result.Success(Invocation.ShowDocs(this, format))
        }
        // The completion driver invokes `__complete -- <words>`; drop the leading end-of-options marker so
        // `words` is exactly what the user typed.
        Builtin.Complete -> Result.Success(
            Invocation.ShowCompleteCandidates(
                this,
                if (args.firstOrNull() == END_OF_OPTIONS) args.drop(1) else args
            ),
        )
    }
