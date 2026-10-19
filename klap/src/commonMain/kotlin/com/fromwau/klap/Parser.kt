package com.fromwau.klap

import com.fromwau.kern.fuzzy.didYouMean
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.klap.internal.parse.ArgvScan
import com.fromwau.klap.internal.parse.ClusterPosition
import com.fromwau.klap.internal.parse.END_OF_OPTIONS
import com.fromwau.klap.internal.parse.GlobalAccumulator
import com.fromwau.klap.internal.parse.GlobalSift
import com.fromwau.klap.internal.parse.Hoisted
import com.fromwau.klap.internal.parse.Refusal
import com.fromwau.klap.internal.parse.bind
import com.fromwau.klap.internal.parse.bindGlobals
import com.fromwau.klap.internal.parse.builtinScan
import com.fromwau.klap.internal.parse.firstBadValue
import com.fromwau.klap.internal.parse.globalLookup
import com.fromwau.klap.internal.parse.globalsFrom
import com.fromwau.klap.internal.parse.hoistCommands
import com.fromwau.klap.internal.parse.isFlagLike
import com.fromwau.klap.internal.parse.LongPool
import com.fromwau.klap.internal.parse.longMatchPool
import com.fromwau.klap.internal.parse.namesHelpShort
import com.fromwau.klap.internal.parse.namesVersionShort
import com.fromwau.klap.internal.parse.PassedSift
import com.fromwau.klap.internal.parse.refusals
import com.fromwau.klap.internal.parse.resolveChoice
import com.fromwau.klap.internal.parse.sift
import com.fromwau.klap.internal.parse.siftGlobals
import com.fromwau.klap.internal.parse.subcommandCandidates
import com.fromwau.klap.internal.parse.walkSubcommands
import com.fromwau.klap.internal.spec.BuiltinKind
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.shorts
import kotlin.coroutines.cancellation.CancellationException

/** How `--color` resolves; see [colorMode] (lenient extraction) and `parse` (the strict, error-reporting form). */
internal enum class ColorMode {
    AUTO, ALWAYS, NEVER,
    ;

    companion object {
        fun fromOrNull(raw: String): ColorMode? = when (raw) {
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
 * A long spelling the built-in layer answers to, dashes stripped, and whether it [takesNextWord] as its value
 * when given in full with none attached, as getopt_long's required argument does. `--color`'s value is optional,
 * so it takes none.
 */
internal class BuiltinLong(val name: String, val takesNextWord: Boolean = false)

/**
 * Every built-in long a tree resolved this way actually offers, gated exactly as [parse] gates them.
 * [versioned] is `version != null` and [metaOptions] the single-command root's `--completion`/`--docs` surface.
 */
internal fun builtinLongTable(
    builtins: Builtins,
    versioned: Boolean,
    metaOptions: Boolean,
): List<BuiltinLong> = listOfNotNull(
    BuiltinLong("help").takeIf { builtins.help },
    BuiltinLong("help-all").takeIf { builtins.helpAll },
    BuiltinLong("json").takeIf { builtins.json },
    BuiltinLong("color").takeIf { builtins.color },
    builtins.versionLong?.let { BuiltinLong(it) }?.takeIf { versioned },
    BuiltinLong("completion", takesNextWord = true).takeIf { metaOptions && builtins.completion },
    BuiltinLong("docs", takesNextWord = true).takeIf { metaOptions && builtins.docs },
)

/** The built-in longs this root offers, as [builtinLongTable] gates them. */
internal fun Cli.builtinLongTable(): List<BuiltinLong> = builtinLongTable(builtins, version != null, metaOptions)

/**
 * The names of [builtinLongTable]. Used both to resolve an abbreviated built-in and to let a built-in take part
 * in an app option's ambiguity, so `--he` against a declared `--header` reports both possibilities rather than
 * silently choosing.
 */
internal fun builtinLongs(
    builtins: Builtins,
    versioned: Boolean,
    metaOptions: Boolean,
): List<String> = builtinLongTable(builtins, versioned, metaOptions).map { it.name }

/** Whether [long] is one of these built-ins and takes the next word as its value. */
internal fun List<BuiltinLong>.takesNextWord(long: String): Boolean = any { it.name == long && it.takesNextWord }

/** The built-ins that are boolean, so an inline `=value` on one is always a usage error. */
private fun Builtins.valuelessLongs(): List<String> = listOfNotNull("help", "help-all", "json", versionLong)

/**
 * The effective `--color` mode read leniently from raw [argv]: absent or an unrecognized value resolve to
 * [ColorMode.AUTO] rather than error, since [parse] is the one place that reports a specific `--color` mistake;
 * [run] only ever needs a mode to resolve against, never a reason to fail. A bare `--color` is
 * [ColorMode.ALWAYS], as GNU's `--color[=WHEN]` reads it ("The WHEN argument defaults to 'always'", `ls --help`),
 * and the word after it is no value of it. A tree that declined either built-in reads neither token:
 * `--color` is then the app's own (or unknown), and AUTO is the only mode klap still has an opinion about.
 *
 * [scan] is the root's own [builtinScan] of [argv] and [abbreviation] the root's own [Cli.abbreviation], so
 * `--col` abbreviates, `-e --color` stays `-e`'s value, and `al` resolves to `always` here exactly as they do
 * in [parse]; the defaults stand in for a caller with no root at hand, mirroring [Builtins.DEFAULT] and
 * [Abbreviation.None].
 */
internal fun List<String>.colorMode(
    builtins: Builtins = Builtins.DEFAULT,
    abbreviation: Abbreviation = Abbreviation.None,
    scan: ArgvScan = ArgvScan(
        { LongPool(builtinLongs(builtins, versioned = true, metaOptions = true)) },
        abbreviation != Abbreviation.None,
        emptySet(),
        this,
    ),
): ColorMode {
    if (!builtins.color) return ColorMode.AUTO
    val head = if (builtins.json) scan.strip("json") else scan
    val at = head.lastSpelling("color") ?: return ColorMode.AUTO
    val raw = head.attached(at) ?: return ColorMode.ALWAYS
    // Same gate as parse(): an ambiguous prefix has no single mode to report here, so it falls back to AUTO.
    val resolved = if (abbreviation.infersValues) {
        (resolveChoice("--color", raw, COLOR_MODE_NAMES) as? Result.Success)?.value
    } else {
        raw
    }
    return resolved?.let { ColorMode.fromOrNull(it) } ?: ColorMode.AUTO
}

/**
 * A boolean built-in (`--help`/`-h`, `--help-all`, `--json`, `--version`) given an inline `=value` before
 * the end-of-options marker takes no value, same as any other boolean flag/no-arg option; report that
 * precisely rather than let the token fall through to the generic unknown-option path. A built-in this tree
 * declined is not among the names checked, so its `=value` form belongs to whatever the app declared under
 * that name. The error names the spelling the user wrote, abbreviated or not, and the line's first offender
 * is the one reported.
 */
private fun Cli.builtinInlineValueRefusal(scan: ArgvScan): Refusal? {
    // Which names are still klap's has to be asked here rather than read off the scan's pool: an app that
    // declined a built-in may declare its own input under that freed name, and the pool carries the app's
    // spellings too.
    val offered = builtins.valuelessLongs().filter { it in builtinLongs(builtins, version != null, metaOptions) }
    // The whole-token form of the short only, and literal since a short never abbreviates: inside a cluster the
    // same shape is reported by the sift that resolves the character (`-vh=x`).
    val at = listOfNotNull(
        builtins.helpShort?.let { short -> scan.firstOpen { it.startsWith("-$short=") } },
        scan.firstAttached(offered),
    ).minOrNull() ?: return null
    // The long resolved, as getopt_long names it: `--hel=x` is "option '--help' doesn't allow an argument".
    val named = scan.matched(at)?.let { "--$it" } ?: "--help"
    return Refusal(CliError.FlagTakesNoValue(named, null), ClusterPosition(scan.positions[at]))
}

/** The `--color=WHEN` value refused, and where it stood: ambiguous, or none of the modes. A bare one is `always`. */
private fun Cli.colorRefusal(scan: ArgvScan): Refusal? {
    val at = scan.lastSpelling("color") ?: return null
    val refused = scan.attached(at)?.let { colorModeError(it) } ?: return null
    return Refusal(refused, ClusterPosition(scan.positions[at]))
}

private fun Cli.colorModeError(raw: String): CliError? {
    val resolved = resolveBuiltinChoice("--color", raw, COLOR_MODE_NAMES).getOrElse { return it }
    if (ColorMode.fromOrNull(resolved) != null) return null
    return CliError.InvalidChoice("--color", raw, COLOR_MODE_NAMES, didYouMean(raw, COLOR_MODE_NAMES))
}

/**
 * Whether [scan] carries a help request. `-h` counts only while the root still offers that short, and is
 * read as [cmd] would read the token, so it takes part in a cluster exactly as a declared short does.
 *
 * Asked once before the walk and once after it. The pre-walk ask can only offer the ROOT as [cmd] for a short
 * cluster, because it gates the `--completion`/`--docs` short-circuit and those are themselves matched before the
 * walk. It decides precedence only, never what binds, so the price is confined to a hybrid root given one line
 * carrying `--completion <shell>` alongside a cluster whose other chars belong to a subcommand.
 */
private fun Cli.hasHelpRequest(scan: ArgvScan, cmd: Command, globalAcc: GlobalAccumulator): Boolean =
    helpLongs().any { scan.names(it) } || scan.namesHelpShort(cmd, globalAcc)

/** The help longs this tree still offers; a declined one is the tool's own name. */
private fun Cli.helpLongs(): List<String> =
    listOfNotNull("help".takeIf { builtins.help }, "help-all".takeIf { builtins.helpAll })

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
/**
 * Whether the subcommand walk may step over [token] instead of stopping at it: an exact help spelling, or
 * a short cluster whose every character is one of [globalFlagShorts] or the help short. Those all resolve
 * before the walk knows which command it reaches, so stepping over cannot change what follows.
 *
 * Exact long spellings only. An abbreviation resolves against the pool of the command the walk reaches,
 * which is precisely what is not known yet here.
 */
internal fun Cli.routesTransparently(
    token: String,
    globalFlagShorts: Set<String>,
    cmd: Command,
): Boolean = when {
    (token == "--help" && builtins.help) || (token == "--help-all" && builtins.helpAll) -> true
    token.startsWith("--") || !token.startsWith("-") || token.length < 2 -> false
    shortOptionsAt(cmd) == ShortOptions.Alone && token.length > 2 -> false
    else -> {
        val versionShort = builtins.versionShort.takeIf { cmd === this || !builtins.versionRootOnly }
        token.drop(1).all { it.toString() in globalFlagShorts || it == builtins.helpShort || it == versionShort }
    }
}

private fun Cli.unknownSubcommandBeforeHelp(
    cmd: Command,
    rest: List<String>,
    qualifiedName: String,
    globalFlagShorts: Set<String>,
): CliError.UnknownSubcommand? {
    // Past whatever the walk stepped over, since those tokens survive into [rest]: the name the user meant
    // as a subcommand sits behind the help request in `app --help bogus`.
    val leadingToken = rest.firstOrNull { !routesTransparently(it, globalFlagShorts, cmd) } ?: return null
    if (!cmd.isGroup || leadingToken.isFlagLike()) return null
    if (cmd.resolveSubcommand(leadingToken, abbreviationAt(cmd) == Abbreviation.All) !is SubcommandMatch.None) return null
    return CliError.UnknownSubcommand(
        qualifiedName,
        leadingToken,
        didYouMean(leadingToken, cmd.subcommandCandidates()),
    )
}

/**
 * [raw] resolved through [resolveChoice] when this tree infers values, or [raw] itself otherwise: the same
 * gate an app-declared `.choice()`/`.enum<E>()` value goes through, applied here to klap's own `--color`,
 * `completion <shell>` and `docs <format>`, none of which reach a `ValueSpec` of their own. A user cannot
 * tell a built-in from an option the app declared, so both must resolve by the same rule.
 */
// Reads this tree's own abbreviation mode rather than globalAcc.inferNames: the meta-option checks that call this
// run before the accumulator is constructed, so there is no globalAcc yet to read it from.
private fun Cli.resolveBuiltinChoice(name: String, raw: String, choices: List<String>): Result<String, CliError> =
    if (abbreviation.infersValues) resolveChoice(name, raw, choices) else Result.Success(raw)

/**
 * Parses [argv] and hands back what it resolved to, without printing anything or exiting. Use it when you
 * want to inspect or reroute the result yourself; use `run` when you want klap to render and return a code.
 */
public fun Cli.parse(argv: Collection<String>): Result<Invocation, CliError> = parseTokens(argv.toList())

/**
 * The parse proper, over an indexable snapshot.
 *
 * [parse] widens its parameter to [Collection] so any collection a caller already holds goes straight in,
 * but the walk indexes and slices argv throughout, so it copies to a [List] once here rather than at every
 * use. Passing an unordered collection is the caller's error: argv is a sequence and the order is the input.
 */
private fun Cli.parseTokens(typed: List<String>): Result<Invocation, CliError> {
    val line = readBuiltins(typed)
    refusalBeforeWalk(line)?.let { return Result.Error(it) }
    metaOptionAnswer(line)?.let { return it }
    val walked = walkToCommand(line).getOrElse { return Result.Error(it) }
    helpOrVersionAnswer(line, walked, readHelpRequest(line, walked))?.let { return it }
    refusalBeforeBind(line, walked)?.let { return Result.Error(it) }
    // A resolved built-in renders the whole tree via this root, so route it straight to its Show* invocation
    // using `this`, with no binding, no action, no self-reference. Its node exists only to be listed/documented.
    // Placed after --help (so `completion --help` shows the node's help) and after the hard global-sift error.
    walked.cmd.builtinKind?.let { return routeBuiltin(it, walked.rest, walked.qualifiedName) }
    return bindCommand(line, walked)
}

/** The built-ins a line carries, read before the walk, and the line left for the walk once they are stripped. */
private class BuiltinLine(
    val hoisted: Hoisted,
    /** Shared by every scan of the line: a token in a value-taking option's argument slot is that option's. */
    val scan: ArgvScan,
    /** The root's segment as the root reads it, its own `ownOptions` included, for the shorts read before the walk. */
    val rootReading: GlobalAccumulator,
    val json: Boolean,
    /** [scan] without the built-in `--json` and `--color`. */
    val withoutColor: ArgvScan,
    /** [withoutColor] without `--completion` and `--docs`, which name nothing ahead of a `prio` command. */
    val withoutMeta: ArgvScan,
    /** The line's first version request, or null. */
    val versionAt: Int?,
    /** [versionAt] when no help request stands ahead of it, so the version answers; null otherwise. */
    val versionAnswerAt: Int?,
    /** The line's first bad built-in value, held back while --help or --version may stand ahead of it. */
    val builtinRefusal: Refusal?,
) {
    val versionFirst: Boolean get() = versionAnswerAt != null
}

/** Reads [typed]'s built-ins once its flag-spelled and prio commands are hoisted. */
private fun Cli.readBuiltins(typed: List<String>): BuiltinLine {
    // A flag-spelled command stands where its word would from here on, and a prio command at the front of its
    // parent's segment, so every scan below routes both as words.
    val hoisted = hoistCommands(typed)
    // Resolved once, ahead of every scan below and shared by all of them: a token standing in a
    // value-taking option's argument slot belongs to that option, so no built-in may claim it.
    val scan = builtinScan(hoisted.tokens)
    val rootReading = globalLookup().at(this)
    val json = builtins.json && scan.names("json")
    val inlineValueRefusal = builtinInlineValueRefusal(scan)

    // --json is position-independent and removed before the subcommand walk; strip it once up front so the
    // meta-option scans see the same token list the walk does, and a --json sitting in a completion/docs
    // value slot never mis-parses as that option's value. Declined, the token is left in place so it
    // reaches the app's own --json (or fails as unknown), rather than being silently swallowed.
    val withoutJson = if (builtins.json) scan.strip("json") else scan

    // --color is a position-independent modifier with an optional value: unlike completion/docs below (gated
    // behind metaOptions), it is validated for EVERY tree, then stripped before the walk, so
    // `--color=never build` still resolves `build`. Unlike version/completion/docs it never short-circuits to
    // an Invocation; its effect (effectiveColor) is read back by `run()`, post-parse, against `terminal.ansi`.
    val colorRefusal = if (builtins.color) colorRefusal(withoutJson) else null
    val withoutColor = if (builtins.color) withoutJson.stripSpellings("color") else withoutJson

    val versionAt = versionRequestAt(scan, rootReading)
    // Answered after the walk, which tells whether a bad option or value stands ahead of it.
    val versionAnswerAt = versionAt?.takeIf { it < (helpRequestAt(scan, rootReading) ?: Int.MAX_VALUE) }
    return BuiltinLine(
        hoisted,
        scan,
        rootReading,
        json,
        withoutColor,
        withoutMetaOptions(withoutColor),
        versionAt,
        versionAnswerAt,
        listOfNotNull(inlineValueRefusal, colorRefusal).minByOrNull { it.at },
    )
}

/**
 * The line's first `--version` or version short, or null when this tree has no version.
 *
 * --help and --version answer whichever the line gives first, as GNU tools do: `ls --help --version` prints
 * the usage, `ls --version --help` the version. --help-all sits with them.
 */
private fun Cli.versionRequestAt(scan: ArgvScan, rootReading: GlobalAccumulator): Int? {
    if (version == null) return null
    return listOfNotNull(
        builtins.versionLong?.let { scan.firstNaming(it) },
        scan.firstOpenIndexed { i, token ->
            (!builtins.versionRootOnly || scan.atRoot(i)) &&
                namesVersionShort(token, rootReading, firstWord = i == 0)
        },
    ).minOrNull()
}

/** The line's first help request as the root reads it before the walk, or null. */
private fun Cli.helpRequestAt(scan: ArgvScan, rootReading: GlobalAccumulator): Int? = listOfNotNull(
    scan.firstNaming(*helpLongs().toTypedArray()),
    scan.firstOpenIndexed { i, token -> namesHelpShort(token, rootReading, firstWord = i == 0) },
).minOrNull()

/** [withoutColor] without the meta-options this root offers. A `prio` command runs ahead of them. */
private fun Cli.withoutMetaOptions(withoutColor: ArgvScan): ArgvScan = if (metaOptions) {
    withoutColor
        .let { if (builtins.completion) it.stripValued("completion") else it }
        .let { if (builtins.docs) it.stripValued("docs") else it }
} else {
    withoutColor
}

/**
 * The bad built-in value that refuses the line before the walk, unless --help or --version may stand ahead of
 * it, which only the walk can tell.
 */
private fun Cli.refusalBeforeWalk(line: BuiltinLine): CliError? {
    val refusal = line.builtinRefusal ?: return null
    if (line.versionAt != null || hasHelpRequest(line.scan, this, line.rootReading)) return null
    return refusal.error
}

/**
 * What `--completion <shell>` or `--docs <format>` answers, or null when the line asks for neither.
 *
 * Single-command roots expose completion/docs as position-independent, print-and-exit meta-options
 * (dispatchers use subcommands). Recognized here like --version, before the walk and binding.
 * --help / -h outrank the meta-options, and between the two the line's first one answers.
 */
private fun Cli.metaOptionAnswer(line: BuiltinLine): Result<Invocation, CliError>? {
    if (!metaOptions || line.hoisted.prio || line.versionFirst) return null
    if (hasHelpRequest(line.scan, this, line.rootReading)) return null
    val withoutColor = line.withoutColor
    val docsAt = withoutColor.firstSpelling("docs")
    val completionAt = withoutColor.firstSpelling("completion")
    val docsFirst = builtins.docs && docsAt != null && (completionAt == null || docsAt < completionAt)
    if (builtins.completion && !docsFirst) completionAnswer(withoutColor)?.let { return it }
    if (builtins.docs) docsAnswer(withoutColor)?.let { return it }
    return null
}

/** The completion script `--completion <shell>` asks for, or null when the line gives no `--completion`. */
private fun Cli.completionAnswer(withoutColor: ArgvScan): Result<Invocation, CliError>? {
    val raw = withoutColor.value("completion").getOrElse { return Result.Error(it) } ?: return null
    val resolved = resolveBuiltinChoice("--completion", raw, COMPLETION_SHELL_NAMES)
        .getOrElse { return Result.Error(it) }
    val shell = CompletionShell.fromOrNull(resolved)
        ?: return Result.Error(
            CliError.InvalidChoice(
                "--completion",
                raw,
                COMPLETION_SHELL_NAMES,
                didYouMean(raw, COMPLETION_SHELL_NAMES),
            ),
        )
    return Result.Success(Invocation.ShowCompletion(this, shell))
}

/** The documentation `--docs <format>` asks for, or null when the line gives no `--docs`. */
private fun Cli.docsAnswer(withoutColor: ArgvScan): Result<Invocation, CliError>? {
    val raw = withoutColor.value("docs").getOrElse { return Result.Error(it) } ?: return null
    val resolved = resolveBuiltinChoice("--docs", raw, DOC_FORMAT_NAMES)
        .getOrElse { return Result.Error(it) }
    val format = DocFormat.fromOrNull(resolved)
        ?: return Result.Error(
            CliError.InvalidChoice(
                "--docs",
                raw,
                DOC_FORMAT_NAMES,
                didYouMean(raw, DOC_FORMAT_NAMES),
            ),
        )
    return Result.Success(Invocation.ShowDocs(this, format))
}

/** The line walked to the command it names, with what is left for that command to read. */
private class WalkedCommand(
    val cmd: Command,
    val rest: List<String>,
    /** The argv index of each [rest] token. */
    val restPositions: List<Int>,
    val qualifiedName: String,
    val globalSift: GlobalSift,
    /** The globals as [cmd] reads them, topped up by every sift of the line. */
    val globalAcc: GlobalAccumulator,
    val globalFlagShorts: Set<String>,
    val passedSifts: List<PassedSift>,
)

/** Pulls the globals out of the line and walks it to the command it names. */
private fun Cli.walkToCommand(line: BuiltinLine): Result<WalkedCommand, CliError> {
    // Globals are position-independent: pulled out before the subcommand walk, the same move --json already
    // makes, and stopping at a value slot for the same reason the built-in strips do.
    val preStrip = globalSpecs.siftGlobals(if (line.hoisted.prio) line.withoutMeta else line.withoutColor, shortOptions)
    val globalSift = preStrip.sift

    // Valueless globals only: the walk cannot tell a value-taking global's argument from a subcommand
    // name, so stepping over one would misread the token after it.
    val globalFlagShorts = globalSpecs.filterIsInstance<FlagSpec>().flatMap { it.shorts }.toSet()

    val walk = walkSubcommands(preStrip.cleaned) { globalsFrom(globalSift) }
    walk.ambiguous?.let { (token, match) ->
        return Result.Error(CliError.AmbiguousSubcommand(walk.cmd.name, token, match.candidates))
    }
    val cmd = walk.cmd
    // Filtered together so a surviving token and its original argv index stay paired: the strips above
    // removed some tokens outright, so no index into `rest` alone can say where a kept one sat. A help token
    // the walk stepped over stays in `rest`, in argv order.
    val kept = walk.kept(preStrip.cleaned.size)
    val rest = preStrip.cleaned.slice(kept)
    val restPositions = preStrip.positions.slice(kept)
    val qualifiedName = (listOf(name) + (walk.passed.map { it.command } + cmd).drop(1).map { it.token })
        .joinToString(" ")

    // A global buried in a mixed short cluster (`-fv`, where `f` is local) survives the pre-strip, since the
    // cluster is left whole for the resolved command's own sift. That sift is global-aware and tops up this
    // accumulator, so globals are bound AFTER the command bind, over the pre-strip occurrences plus any the
    // segment added. A Required-but-absent global is deferred: whether it matters depends on where the walk
    // ended up (a bare group that only shows help doesn't need it; a leaf that executes does).
    val tally = globalsFrom(globalSift)
    val globalAcc = tally.at(cmd)
    // Each command the walk passed reads its own option words, in line order, ahead of the one it reached.
    val passedSifts = walk.passed.map { passed ->
        val own = tally.at(passed.command)
        val words = preStrip.cleaned.slice(passed.words)
        PassedSift(passed.command, passed.command.sift(words, own, preStrip.positions.slice(passed.words)), own)
    }
    return Result.Success(
        WalkedCommand(cmd, rest, restPositions, qualifiedName, globalSift, globalAcc, globalFlagShorts, passedSifts),
    )
}

/** Where the segment the walk reached asks for `--help-all` and for `--help` or its short. */
private class HelpRequest(
    val requested: Boolean,
    val helpAllAt: Int?,
    val plainHelpAt: Int?,
    /** The segment as the reached command reads it; the two indices above are into it. */
    val segment: ArgvScan,
)

/** Reads the help request off the segment the walk reached, against that command's own pool. */
private fun Cli.readHelpRequest(line: BuiltinLine, walked: WalkedCommand): HelpRequest {
    val cmd = walked.cmd
    val globalAcc = walked.globalAcc
    // --help and --help-all are the only built-ins resolved after the walk, against the pool of the command it
    // reached. The value slots come along unchanged, keyed on the original argv, so `sub -e --help f` is still
    // `-e`'s.
    val segmentPool = cmd.longMatchPool(globalAcc)
    val segment = ArgvScan(
        { segmentPool },
        globalAcc.inferNames,
        line.scan.valueSlots,
        walked.rest,
        walked.restPositions,
    )
    val requested = hasHelpRequest(segment, cmd, globalAcc)
    // --help-all and --help, like --version, answer whichever the line gives first.
    val helpAllAt = if (builtins.helpAll) segment.firstNaming("help-all") else null
    val plainHelpAt = listOfNotNull(
        segment.firstNaming("help").takeIf { builtins.help },
        segment.firstOpenIndexed { i, token -> cmd.namesHelpShort(token, globalAcc, firstWord = i == 0) },
    ).minOrNull()
    return HelpRequest(requested, helpAllAt, plainHelpAt, segment)
}

/** The version or help page the line asks for, or what refuses it; null when the line asks for neither. */
private fun Cli.helpOrVersionAnswer(
    line: BuiltinLine,
    walked: WalkedCommand,
    help: HelpRequest,
): Result<Invocation, CliError>? {
    // Gated on the help request: without one the same error already comes from cmd.bind(), where a
    // global-sift error rightly outranks it. Firing unconditionally here would invert that precedence.
    if (help.requested && !line.versionFirst) {
        unknownSubcommandBeforeHelp(walked.cmd, walked.rest, walked.qualifiedName, walked.globalFlagShorts)
            ?.let { return Result.Error(it) }
    }
    if (!help.requested && !line.versionFirst) return null
    refusalAheadOfAnswer(line, walked, help)?.let { return Result.Error(it) }
    if (line.versionFirst) return Result.Success(Invocation.ShowVersion(this, line.json))

    val helpAllFirst = help.helpAllAt != null && (help.plainHelpAt == null || help.helpAllAt < help.plainHelpAt)
    return Result.Success(
        Invocation.ShowHelp(
            walked.cmd,
            walked.qualifiedName,
            globalSpecs,
            version != null,
            metaOptions,
            recursive = helpAllFirst || builtins.helpTree,
            builtins = builtins,
            json = line.json,
        )
    )
}

/**
 * The bad option or value that refuses a help or version request standing behind it, or null.
 *
 * The line's first of the built-in and a bad option or value decides, as GNU coreutils reads its options
 * in getopt's loop: `cp --help --bogus` prints the usage, `cp --bogus --help` is refused. Operands and
 * requirements are checked after that loop, so they never refuse an answer.
 */
private fun refusalAheadOfAnswer(
    line: BuiltinLine,
    walked: WalkedCommand,
    help: HelpRequest,
): CliError? {
    val answerAt = line.versionAnswerAt?.let { line.scan.positions[it] }
        ?: listOfNotNull(help.helpAllAt, help.plainHelpAt).minOrNull()?.let { help.segment.positions[it] }
    val globalAcc = walked.globalAcc
    val sifted = walked.cmd.sift(walked.rest, globalAcc, walked.restPositions)
    val passedRefusals = walked.passedSifts.flatMap { it.sifted.refusals(it.globals.inferValues) }
    val refusal = listOfNotNull(
        line.builtinRefusal,
        sifted.refusal,
        walked.globalSift.refusal,
        globalAcc.repeatRefusal(),
        firstBadValue(sifted, globalAcc.toGlobalSift(), globalAcc.inferValues),
    ).plus(passedRefusals).minByOrNull { it.at } ?: return null
    // Token by token: the built-in's own token refuses it only when the cluster is refused whole, as one
    // mixing it with a local short past the first operand is; otherwise a short cluster can only carry a
    // refusal behind the built-in's character, since one ahead of it leaves the cluster no help request.
    val ahead = answerAt == null || refusal.at.token < answerAt || refusal.refusesWhole(answerAt)
    return refusal.error.takeIf { ahead }
}

/** The refusal the line carries whatever command it reaches, raised before that command binds. */
private fun refusalBeforeBind(line: BuiltinLine, walked: WalkedCommand): CliError? =
    // Held back for a help request the walk then did not confirm.
    line.builtinRefusal?.error
        // A hard global-sift error (dangling global value, or a value handed to a global boolean flag) is a
        // usage error regardless of where the walk ends up; raise it before the command bind sees the leftover.
        ?: walked.globalSift.error
        ?: line.hoisted.error

/** Binds the reached command and then the globals, and runs its input validations. */
private fun Cli.bindCommand(line: BuiltinLine, walked: WalkedCommand): Result<Invocation, CliError> {
    val cmd = walked.cmd
    val json = line.json
    val globalAcc = walked.globalAcc
    // Every resolved input is recorded in this per-parse sink, never on the shared specs, so the tree stays
    // immutable during parsing (concurrent parses each own their own sink). It is frozen into the Execute's
    // ActionScope once the command and its globals are both bound.
    val sink = mutableMapOf<HolderSpec, Any?>()
    val outcome = cmd.bind(
        walked.rest,
        Globals(json),
        walked.qualifiedName,
        globalAcc,
        sink,
        walked.restPositions,
        walked.passedSifts,
    )
    return when (outcome) {
        is Result.Error -> outcome
        is Result.Success -> {
            val deferredGlobalErrors = bindGlobals(
                globalSpecs,
                globalAcc.toGlobalSift(),
                sink,
                globalAcc.inferValues,
            ).getOrElse { return Result.Error(it) }
            when (val invocation = outcome.value) {
                is Invocation.Execute -> {
                    // Freeze the completed sink (command inputs + globals) into the Execute's read snapshot.
                    // Output mode resolves here, not in run() where the color switch waits for a terminal, so
                    // an embedder driving parse() + runAction() reads the same mode the terminal path gives.
                    val exec = invocation.copy(
                        scope = ActionScope(sink, json = invocation.globals.json, line = invocation.scope.line),
                    )
                    // A prio command runs without its parent's required globals.
                    if (!cmd.prio) deferredGlobalErrors.firstOrNull()?.let { return Result.Error(it) }
                    // Only this branch: a validateInputs block reads bound values, which --help, --version,
                    // completion and docs never produce, and must not refuse to render over an input they
                    // were never going to use.
                    runValidations(exec)?.let { Result.Error(it) } ?: Result.Success(exec)
                }

                // A group run without a subcommand: help for a person, but text is not data under --json.
                is Invocation.ShowHelp -> if (json || !builtins.help) {
                    Result.Error(CliError.MissingSubcommand(invocation.qualifiedName))
                } else {
                    Result.Success(
                        Invocation.ShowHelp(
                            invocation.command,
                            invocation.qualifiedName,
                            globalSpecs,
                            version != null,
                            metaOptions,
                            recursive = builtins.helpTree,
                            builtins = builtins,
                        ),
                    )
                }
                // bind() only ever yields Execute or ShowHelp; the rest keep the when exhaustive.
                is Invocation.ShowVersion -> outcome
                is Invocation.ShowCompletion -> outcome
                is Invocation.ShowDocs -> outcome
                is Invocation.ShowCompleteCandidates -> outcome
            }
        }
    }
}

/** Whether this refuses the whole word at [token], the built-in's character in it included. */
private fun Refusal.refusesWhole(token: Int): Boolean = at.token == token && error is CliError.MixedClusterAfterOperands

/** [parse] over the `Array` that Kotlin's own `main` hands you. */
public fun Cli.parse(argv: Array<String>): Result<Invocation, CliError> = parseTokens(argv.toList())

/**
 * Route a resolved built-in to its render invocation, parsing its single argument from the raw tokens
 * [args] with the same `CompletionShell.fromOrNull`/`DocFormat.fromOrNull` the `--completion`/`--docs` use, so
 * both forms report an unknown value identically. The root is `this`, handed straight to the invocation.
 */
private fun Cli.routeBuiltin(
    kind: BuiltinKind,
    args: List<String>,
    qualifiedName: String,
): Result<Invocation, CliError> =
    when (kind) {
        BuiltinKind.Completion -> {
            val raw = args.firstOrNull() ?: return Result.Error(CliError.MissingArgument(qualifiedName, "shell"))
            val resolved = when (val r = resolveBuiltinChoice("completion", raw, COMPLETION_SHELL_NAMES)) {
                is Result.Error -> return r
                is Result.Success -> r.value
            }
            val shell = CompletionShell.fromOrNull(resolved)
                ?: return Result.Error(
                    CliError.InvalidChoice(
                        "completion",
                        raw,
                        COMPLETION_SHELL_NAMES,
                        didYouMean(raw, COMPLETION_SHELL_NAMES),
                    ),
                )
            // The node declares exactly one argument; reject a surplus operand instead of dropping it,
            // matching bindPositionals (a builtin routes before binding, so it enforces arity itself).
            if (args.size > 1) return Result.Error(CliError.TooManyArguments(qualifiedName, args.drop(1)))
            Result.Success(Invocation.ShowCompletion(this, shell))
        }

        BuiltinKind.Docs -> {
            val raw = args.firstOrNull() ?: return Result.Error(CliError.MissingArgument(qualifiedName, "format"))
            val resolved = when (val r = resolveBuiltinChoice("docs", raw, DOC_FORMAT_NAMES)) {
                is Result.Error -> return r
                is Result.Success -> r.value
            }
            val format = DocFormat.fromOrNull(resolved)
                ?: return Result.Error(
                    CliError.InvalidChoice(
                        "docs",
                        raw,
                        DOC_FORMAT_NAMES,
                        didYouMean(raw, DOC_FORMAT_NAMES),
                    ),
                )
            if (args.size > 1) return Result.Error(CliError.TooManyArguments(qualifiedName, args.drop(1)))
            Result.Success(Invocation.ShowDocs(this, format))
        }
        BuiltinKind.Complete -> Result.Success(completeInvocation(args))
    }

/**
 * Runs the command's `validateInputs {}` blocks in declaration order, stopping at the first failure. They
 * read bound values, so this is the earliest point they can run — and, being before the action, still early
 * enough that a refused line does nothing.
 *
 * Guarded like `.validate()`, the counterpart these docs point authors at: both are consumer code reached
 * from [parse], so a rule moved from one to the other must not lose the never-throw boundary on the way.
 */
private fun runValidations(exec: Invocation.Execute): CliError? =
    exec.command.validations.firstNotNullOfOrNull { rule ->
        try {
            rule(exec.scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            CliError.Failure("validateInputs failed: ${e.message?.takeIf { it.isNotBlank() } ?: "no message"}")
        }
    }

/**
 * `__complete [--cursor <k>] -- <words>`, the end-of-options marker dropped so `words` is exactly what was typed.
 * Without a usable cursor the last word is the one under it, which is what an installed older script sends.
 */
private fun Cli.completeInvocation(args: List<String>): Invocation.ShowCompleteCandidates {
    // `--cursor` always takes the word after it, so a malformed value is not mistaken for a word of the line.
    val hasCursor = args.firstOrNull() == "--cursor"
    val cursor = if (hasCursor) args.getOrNull(1)?.toIntOrNull() else null
    val rest = if (hasCursor) args.drop(2) else args
    val words = if (rest.firstOrNull() == END_OF_OPTIONS) rest.drop(1) else rest
    return Invocation.ShowCompleteCandidates(this, words, cursor?.takeIf { it in words.indices } ?: words.lastIndex)
}
