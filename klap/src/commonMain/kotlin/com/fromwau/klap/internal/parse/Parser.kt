package com.fromwau.klap.internal.parse

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.ActionScope
import com.fromwau.klap.Builtins
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.Command
import com.fromwau.klap.Globals
import com.fromwau.klap.Invocation
import com.fromwau.klap.SubcommandMatch
import com.fromwau.klap.builtinLongs
import com.fromwau.klap.internal.render.reason
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.ConstraintArity
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.InputConstraint
import com.fromwau.klap.internal.spec.NamedSpec
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.ValueSpec
import com.fromwau.klap.internal.spec.longs
import com.fromwau.klap.internal.spec.negativeLongs
import com.fromwau.klap.internal.spec.negativeShorts
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.internal.spec.token
import com.fromwau.klap.resolveSubcommand

/** POSIX end-of-options: every token after it is positional, never a flag. */
internal const val END_OF_OPTIONS = "--"

/**
 * How a bind reacts to an input it cannot satisfy.
 *
 * [Strict] fails the parse — a leaf's own inputs, where a missing required value is a usage error.
 * [DeferRequired] returns the error for the caller to judge later — globals, whose absence may not matter
 * (a bare group that only shows help does not need them; a leaf that executes does).
 * [Lenient] leaves the input unbound and carries on — completion, where the line is still being typed, and
 * where one unsatisfiable input must never blank the ones around it.
 */
internal enum class BindPolicy { Strict, DeferRequired, Lenient }

/**
 * A token that reads as an option: it starts with '-' and is neither the end-of-options marker nor the
 * lone '-' that every tool spells stdin with.
 *
 * What follows the dash is not consulted, including a leading digit: exempting `-100` so it could reach a
 * numeric positional would make `ls -5` bind a FILE named `-5`, where real `ls` reports an unknown option
 * — a silent mis-binding no tool wants. A dash-led number means what a tool declares it to mean: nothing
 * (an unknown option, the default), a digit short it declares (`curl -4`), or a number input it declares
 * (`head -5`). A genuinely negative operand is written after `--`, and an option VALUE needs no escape
 * at all.
 */
internal fun String.isFlagLike(): Boolean =
    startsWith("-") && this != END_OF_OPTIONS && this != "-"

/**
 * Every short spelling [specs] answer to, a negatable flag's negative shorts included: `.negatable("-3")`
 * declares `-3` as surely as `flag("-4")` declares `-4`, and a run that swallowed it would leave a declared
 * spelling no line could reach.
 */
private fun declaredShortsOf(specs: List<NamedSpec>): Set<String> =
    specs.flatMapTo(mutableSetOf()) { spec ->
        if (spec is FlagSpec) spec.shorts + spec.negativeShorts else spec.shorts
    }

/**
 * The maximal run of digits at [chars] index [at] that binds this command's number input, or null when
 * nothing there is one: no number input is declared, the character is not a digit, or every character of
 * the run names a declared short.
 *
 * That last case is POSIX.1 XBD 12.2 guideline 14 — a token identifiable as a group of options must be
 * treated as one — scoped to the RUN rather than to the whole token. `-4` where `flag("-4")` is declared is
 * that flag; `-45` where only `-4` is declared is not a valid group, so it is the number 45. A global's
 * shorts count as much as this command's own, since a cluster mixing the two reaches this sift whole
 * ([siftGlobals]), and a flag's negative shorts count as much as its positive ones ([declaredShortsOf]).
 *
 * The run is cut out before the per-character walk reaches inside it, which is what settles `-25` beside a
 * value-taking `option("--two", "-2")`: the run wins and the number is 25, rather than `-2` taking `5` as
 * its value. Where the run IS fully covered (`-2 5`) this declines and the ordinary cluster reading holds.
 */
internal fun Command.numberRunAt(
    chars: String,
    at: Int,
    globalAcc: GlobalAccumulator?,
): String? {
    // Cheapest test first: [numberInput] and [declaredShortsOf] both walk spec lists, and this is asked once
    // per character of every short cluster the parser reads.
    if (at >= chars.length || !chars[at].isDigit()) return null
    if (numberInput == null) return null
    val run = chars.substring(at).takeWhile { it.isDigit() }
    val shorts = declaredShortsOf(namedInputs + globalAcc?.flagSpecs.orEmpty() + globalAcc?.optionSpecs.orEmpty())
    return if (run.all { it.toString() in shorts }) null else run
}

/**
 * Whether [ch] is klap's own help short here. Asked ahead of every declared lookup, which costs nothing:
 * the character is reserved at build time while the built-in is offered, so no spec can be holding it.
 */
internal fun GlobalAccumulator?.isHelpShort(ch: Char): Boolean =
    ch == 'h' && (this?.builtins ?: Builtins.DEFAULT).helpShort

/** Whether any operand slot is `dashLed()`; over [Command.specs] so the answer costs one pass and no list. */
private val Command.hasDashLedSlot: Boolean get() = specs.any { it is ArgumentSpec && it.dashLed }

internal fun Command.bind(
    segment: List<String>,
    globals: Globals,
    qualifiedName: String,
    globalAcc: GlobalAccumulator? = null,
    sink: MutableMap<HolderSpec, Any?>,
    positions: List<Int> = emptyList(),
): Result<Invocation, CliError> {
    if (isGroup) {
        val ddIndex = segment.indexOf(END_OF_OPTIONS)
        val head = if (ddIndex < 0) segment else segment.take(ddIndex)
        val tail = if (ddIndex < 0) emptyList() else segment.drop(ddIndex + 1)
        // Report the leftmost offender: a flag-shaped first token blames the option, otherwise the first
        // positional blames the subcommand. Post-`--` tokens are always positional, never options.
        val positionals = head.filterNot { it.isFlagLike() } + tail
        val firstToken = head.firstOrNull()
        return when {
            // Match a leaf sift's granularity: a short cluster names its first (offending) char, not the
            // whole token, so `-vz` at a group and `list -az` at a leaf both report `-z`; a long `--opt`
            // is named in full.
            firstToken != null && firstToken.isFlagLike() -> {
                val long = firstToken.takeIf { it.startsWith("--") }?.removePrefix("--")?.substringBefore('=')
                // A group binds nothing, so this token is an error whichever way it resolves; it is
                // resolved anyway, because "unknown option '--he'; did you mean '--help'?" is a false
                // diagnosis of a token that names several spellings, and a leaf would never give it.
                val ambiguous = long?.let {
                    resolveLong(it, longMatchPool(globalAcc), globalAcc?.inferNames ?: false) as? NameMatch.Ambiguous
                }
                if (ambiguous != null) {
                    Result.Error(ambiguousOrUnknown("--$long", ambiguous.candidates.map { "--$it" }, globalAcc))
                } else {
                    // Resolved rather than assumed to be firstToken[1]: a cluster reaching here may carry
                    // declared characters ahead of the offending one, and naming a declared global as
                    // unknown is worse than naming nothing.
                    val offending = if (long != null) firstToken
                    else firstUnresolvedShort(firstToken.removePrefix("-"), globalAcc) ?: "-${firstToken[1]}"
                    val suggestion = long?.let { suggest(offending, longOptionCandidates(globalAcc)) }
                    Result.Error(CliError.UnknownOption(offending, suggestion, firstToken.takeIf { it != offending }))
                }
            }

            positionals.isNotEmpty() -> {
                val token = positionals.first()
                // A real subcommand that only reached here because `--` preceded it (routing had already
                // stopped) is misplaced, not unknown; say so instead of "unknown subcommand".
                val infer = globalAcc?.inferSubcommands ?: false
                if (ddIndex >= 0 && resolveSubcommand(token, infer) is SubcommandMatch.One) {
                    Result.Error(CliError.SubcommandAfterSeparator(token, qualifiedName))
                } else {
                    Result.Error(
                        CliError.UnknownSubcommand(
                            qualifiedName, token,
                            suggest(token, subcommandCandidates()),
                        )
                    )
                }
            }

            else -> Result.Success(Invocation.ShowHelp(this, qualifiedName))
        }
    }

    val sifted = sift(segment, globalAcc, positions)
    // Raised before anything binds, so a malformed segment fails immediately rather than after a partial bind.
    sifted.error?.let { return Result.Error(it) }
    checkConstraints(sifted)?.let { return Result.Error(it) }
    bindFlagsAndOptions(
        flags,
        options,
        sifted.flags,
        sifted.negations,
        sifted.options,
        sink,
        globalAcc?.inferNames ?: false,
    ).getOrElse { return Result.Error(it) }
    // Ahead of resolveLastWins: a fold reads its winner's bound value, and an ENCLOSING set that overrode
    // the fold must then be free to reset it.
    resolveFolds(sifted, sink)
    resolveLastWins(sifted, sink)
    bindPositionals(sifted.positionals, sink, sifted, globalAcc?.inferNames ?: false, qualifiedName = qualifiedName)
        .getOrElse { return Result.Error(it) }
    checkConditionalRequirements(sifted, globalAcc)?.let { return Result.Error(it) }
    // Placeholder scope: parse() attaches the completed snapshot (command inputs + globals) via copy().
    // Unreachable: a group returned through the isGroup branch above, and a command with neither an
    // action nor subcommands is rejected at build. Help keeps parse() total rather than throwing.
    val resolved = action ?: return Result.Success(Invocation.ShowHelp(this, qualifiedName))
    return Result.Success(Invocation.Execute(this, globals, resolved, ActionScope(emptyMap())))
}

/**
 * The first option whose `.requiredIf(flag)` condition holds while the option itself is absent, in
 * declaration order, or null.
 *
 * Reads [sifted] rather than the bound values for the same reason [checkConstraints] does: a flag always
 * binds (false when absent), so only the sift can say whether the user actually wrote it. A global
 * condition never lands in a leaf's own sift, so [globalAcc] is asked the same question. Runs after the
 * binds so an outright malformed segment still reports first, and before the action so the rule is a
 * usage error rather than something the action has to re-check.
 */
private fun Command.checkConditionalRequirements(
    sifted: Sifted,
    globalAcc: GlobalAccumulator?,
): CliError? {
    for (opt in options) {
        val condition = opt.requiredWhen ?: continue
        val triggered = supplied(condition, sifted) || globalAcc?.supplied(condition) == true
        if (triggered && opt !in sifted.options) return CliError.MissingRequiredOption(opt.token())
    }
    return null
}

/**
 * Give each `lastOneWins` handle the value of whichever member the user wrote last, or its absent reading
 * when they wrote none.
 */
private fun Command.resolveFolds(sifted: Sifted, sink: MutableMap<HolderSpec, Any?>) {
    for (fold in options.filter { it.folds.isNotEmpty() }) {
        // lastPosition, not the raw sift map: a member that is itself a fold is never a key there (it is
        // never written on the line), which is exactly what lets a nested fold win here.
        val winner = fold.folds
            .mapNotNull { member -> member.lastPosition(sifted)?.let { member to it } }
            .maxByOrNull { it.second }
            ?.first
        sink[fold] = if (winner != null) sink[winner] else fold.absentValue()
    }
}

/**
 * Collapse each [ConstraintArity.LastWins] set to its single winner: the member written last on the command
 * line keeps what it bound, and every other member binds what it would have bound had the user not written
 * it at all ([absentValue]), whatever [bindFlagsAndOptions] just wrote. A loser is then indistinguishable
 * from an absent input, so an action reads the winner off its own handle and needs no precedence logic.
 *
 * Runs after the flag/option bind rather than instead of it, so a member keeps whatever the ordinary rules
 * gave it when the set is untouched, and so this stays one small pass over the declared sets. A set nobody
 * supplied has no positions and is left alone.
 */
private fun Command.resolveLastWins(sifted: Sifted, sink: MutableMap<HolderSpec, Any?>) {
    lastWinsLosers(sifted).forEach { sink[it] = it.absentValue() }
}

/**
 * Every member a [ConstraintArity.LastWins] set overrode: for each set the user wrote at least one member
 * of, all of its members but the winner.
 *
 * Recorded rather than derived by comparing a bound value against [absentValue], which would misread a user
 * who supplied a value equal to the default. The operand rules need it because they ask whether a trigger
 * actually HELD, and a loser holds nothing: `cp -t dir -T a b` must keep the destination slot, since `-T`
 * took the set and `-t` reads back absent.
 */
internal fun Command.lastWinsLosers(sifted: Sifted): Set<HolderSpec> = constraints
    .filter { it.arity == ConstraintArity.LastWins }
    .flatMapTo(mutableSetOf()) { it.losers(sifted) }

/** This set's members bar the one written last, or none at all when the user wrote none of them. */
private fun InputConstraint.losers(sifted: Sifted): List<HolderSpec> {
    val winner = members
        .mapNotNull { spec -> spec.lastPosition(sifted)?.let { spec to it } }
        .maxByOrNull { it.second }
        ?.first
        ?: return emptyList()
    return members.filterNot { it === winner }
}

/** Where [spec] sat on the line, or null when the user did not write it; the two maps the sift keeps. */
private fun HolderSpec.lastPosition(sifted: Sifted): ClusterPosition? = when (this) {
    is FlagSpec -> sifted.flagPositions[this]
    // A fold is never written itself, so it stands where its latest member stands. Recursive, because a
    // member may be a fold too, and reading the sift map directly would leave that one standing nowhere.
    is OptionSpec ->
        if (folds.isEmpty()) sifted.optionPositions[this]
        else folds.mapNotNull { it.lastPosition(sifted) }.maxOrNull()

    is ArgumentSpec -> null
}

/**
 * What this input binds when it is absent: what a loser must bind ([resolveLastWins]), and what an operand
 * slot removed by its `.absentWhen()` trigger binds ([bindPositionals]). Falling to null for an option
 * without a `.default()` is sound only because `validateLastWinsMembers` keeps `.required()` and
 * `.multiple()` options out of a set, and `validateFoldCardinality` off a fold ([resolveFolds] reaches here
 * too): their accessors are non-null, so a null here would NPE inside the action.
 */
internal fun HolderSpec.absentValue(): Any? = when {
    this is FlagSpec && isCount -> 0
    this is FlagSpec && negatable -> (cardinality as Cardinality.Default).value
    this is FlagSpec -> false
    else -> (cardinality as? Cardinality.Default)?.value
}

/**
 * Whether [spec] was actually GIVEN on this segment (see [checkConstraints] for why that is not "did it
 * bind"). Shared with the completion planner, which drops a constraint's other members once one is
 * supplied: one definition, so what completion offers and what the parse accepts cannot drift.
 *
 * [overridden] is the set of last-wins losers to read as absent, which the operand rules pass and the
 * constraint checks do not: those two ask different questions, and [checkConstraints] genuinely wants raw
 * presence.
 */
internal fun Command.supplied(
    spec: HolderSpec,
    sifted: Sifted,
    overridden: Set<HolderSpec> = emptySet(),
): Boolean = if (spec in overridden) false else when (spec) {
    // A negatable flag counts only in its positive form: `--no-create` asks to turn create OFF, so reading
    // it as "create was selected" would make `--no-create --extract` a conflict. sift records the last
    // polarity seen, which is the one the bind would have used.
    is FlagSpec -> if (spec.negatable) sifted.negations[spec] == true else (sifted.flags[spec] ?: 0) > 0
    is OptionSpec ->
        if (spec.folds.isEmpty()) sifted.options[spec]?.isNotEmpty() == true
        else spec.folds.any { supplied(it, sifted, overridden) }
    // Positionals fill left to right and a variadic must come last (validatePositionals), so the operand
    // at this spec's index exists exactly when the spec received one.
    is ArgumentSpec -> arguments.indexOf(spec).let { it >= 0 && sifted.positionals.size > it }
}

/**
 * The operand slots this line actually has: [Command.arguments] minus every slot whose `.absentWhen()`
 * trigger held. Shared with the completion planner, so the slot it offers candidates for and the slot the
 * bind fills are the same one; `chmod --reference=r <TAB>` must not offer a MODE the parse has removed.
 *
 * A trigger that LOST a `lastWins` set reads as absent here ([overridden]), because a loser binds as if it
 * had never been written and the slot must survive with it.
 */
internal fun Command.activeArguments(
    sifted: Sifted,
    overridden: Set<HolderSpec> = lastWinsLosers(sifted),
): List<ArgumentSpec> =
    arguments.filterNot { spec -> spec.absentWhen?.let { supplied(it, sifted, overridden) } == true }

/**
 * The declared choice [raw] names when a prefix may resolve one, or [raw] itself when none does — an
 * unmatched value is left for the converter to reject, so the InvalidChoice list and its suggestion are
 * still what the user sees.
 *
 * Matching is case-insensitive on both halves, because `.choice()` / `.enum<E>()` already are: the pool is
 * lowered only to compare, and the DECLARED spelling is what comes back, since that is what the converter
 * canonicalises against. A free function, taking [name] rather than a [ValueSpec] receiver, so klap's own
 * built-in choice values (`--color`, `completion <shell>`, `docs <format>`) share this implementation
 * without a spec of their own to be an extension on.
 */
internal fun resolveChoice(name: String, raw: String, choices: List<String>): Result<String, CliError> {
    val lowered = choices.map { it.lowercase() }
    return when (val match = resolveName(raw.lowercase(), lowered, infer = true)) {
        is NameMatch.Exact, NameMatch.None -> Result.Success(raw)
        is NameMatch.Prefix -> Result.Success(choices[lowered.indexOf(match.name)])
        is NameMatch.Ambiguous -> Result.Error(
            CliError.AmbiguousValue(name, raw, match.candidates.map { choices[lowered.indexOf(it)] }),
        )
    }
}

/**
 * Candidate `--name` tokens for did-you-mean on an unknown long option: this command's own visible
 * options/flags plus each negatable one's negative long spellings (generated `--no-<name>`, or the explicit
 * ones a `.negatable(vararg)` declared), the visible GLOBAL options/flags carried by [globalAcc] (falling
 * back to none when it is absent), and the position-independent built-ins the tree still offers (`--help`
 * always, `--json` unless declined, `--version` when the root declares one). A hidden option/flag, local or
 * global, is never a candidate: revealing it via a suggestion would defeat the point of hiding it, and
 * neither would suggesting a built-in the root opted out of.
 */
private fun Command.longOptionCandidates(globalAcc: GlobalAccumulator?): List<String> {
    val localFlags = flags.filterNot { it.hidden }
    val localOptions = options.filterNot { it.hidden }
    val globalFlags = globalAcc?.flagSpecs.orEmpty().filterNot { it.hidden }
    val globalOptions = globalAcc?.optionSpecs.orEmpty().filterNot { it.hidden }
    val negatable = (localFlags + globalFlags).filter { it.negatable }
    val offered = globalAcc?.builtins ?: Builtins.DEFAULT
    val builtins = listOfNotNull(
        "--help",
        "--json".takeIf { offered.json },
        "--version".takeIf { globalAcc?.rootVersion != null },
    )
    return (localOptions + localFlags + globalOptions + globalFlags).flatMap { spec -> spec.longs.map { "--$it" } } +
            negatable.flatMap { spec -> spec.negativeLongs.map { "--$it" } } +
            builtins
}

/**
 * Every long spelling THIS command answers to once the walk has reached it, dashes stripped.
 *
 * ONE pool, because ambiguity is a property of everything reachable from this token position: a `--he` that
 * could mean a declared `--header` or the built-in `--help` must be reported as ambiguous rather than
 * silently binding whichever list was consulted first. Hidden inputs take part, which is why this is not
 * [longOptionCandidates]: hiding an input removes it from help, not from the parser, and letting an
 * abbreviation resolve past a hidden spelling would bind a different option than the same line binds on a
 * tree where nothing is hidden.
 *
 * A SIBLING's longs are deliberately absent: this is the pool the built-ins resolved after the walk
 * ([parse]'s `--help`/`--help-all`) answer to, and a `--header` declared on one child must not cost every
 * other command in the tree its `--h`. The command is known here, so the honest answer is the one GNU would
 * give at that command.
 */
internal fun Command.resolvedLongPool(globalAcc: GlobalAccumulator?): List<String> =
    longPool(globalAcc, treeLongs = emptyList())

/**
 * The pool a command SEGMENT resolves a long token against: [resolvedLongPool] plus every long declared
 * anywhere in the tree ([Cli.declaredLongs], carried by [globalAcc]).
 *
 * The tree half is what makes this a SUPERSET of the pool every scan that runs before the walk resolves
 * against ([Cli.positionIndependentLongs]), and a superset can only ever report the same ambiguity, never
 * narrow it. That is the invariant to keep: those scans leave an abbreviation they find ambiguous whole,
 * so whatever they declined arrives here, and a narrower pool would resolve it to the one spelling this
 * segment happens to see and then call it unknown.
 *
 * The price is that one command's long declines an abbreviation on behalf of its siblings, the same price
 * [Cli.positionIndependentLongs] pays for the same reason; the built-ins matched after the walk escape it
 * by resolving against [resolvedLongPool] instead, which is where the command is finally known.
 */
internal fun Command.longMatchPool(globalAcc: GlobalAccumulator?): List<String> =
    longPool(globalAcc, treeLongs = globalAcc?.treeLongs.orEmpty())

// klap's injected built-ins come last so a reported possibility list leads with the names the author wrote,
// and so one token names its possibilities in the same order wherever in the tree it is reported.
private fun Command.longPool(globalAcc: GlobalAccumulator?, treeLongs: List<String>): List<String> {
    val globals = globalAcc?.optionSpecs.orEmpty() + globalAcc?.flagSpecs.orEmpty()
    val negatable = (flags + globalAcc?.flagSpecs.orEmpty()).filter { it.negatable }
    return (namedInputs + globals).flatMap { spec -> spec.longs } +
            negatable.flatMap { spec -> spec.negativeLongs } +
            treeLongs +
            globalAcc.offeredBuiltinLongs()
}

/**
 * The built-in long spellings this tree offers, dashes stripped. Shared by the pool that resolves a token
 * and by the sift that binds one, so the two cannot disagree about whether a name is klap's own.
 */
private fun GlobalAccumulator?.offeredBuiltinLongs(): List<String> = builtinLongs(
    this?.builtins ?: Builtins.DEFAULT,
    versioned = this?.rootVersion != null,
    metaOptions = this?.metaOptions ?: false,
)

/**
 * Narrows an ambiguity detected against [longMatchPool] to the spellings [this] command can actually reach.
 *
 * Detection stays tree-wide so a pre-strip's decline is never contradicted (see [longMatchPool]), but the
 * candidates it names can belong to a sibling, and reporting those sends the user to a spelling this command
 * calls unknown. [candidates] carry dashes (`--limit`); [resolvedLongPool] does not.
 */
private fun Command.ambiguousOrUnknown(
    token: String,
    candidates: List<String>,
    globalAcc: GlobalAccumulator?,
): CliError {
    val reachable = resolvedLongPool(globalAcc).toSet()
    val filtered = candidates.filter { it.removePrefix("--") in reachable }
    return when {
        filtered.size >= 2 -> CliError.AmbiguousOption(token, filtered)
        // One survivor still reports the whole list rather than binding it: the pre-strip already declined
        // this token, so resolving it here would bind what an earlier pass refused.
        filtered.size == 1 -> CliError.AmbiguousOption(token, candidates)
        else -> CliError.UnknownOption(token, suggest(token, longOptionCandidates(globalAcc)))
    }
}

/**
 * Candidate names for did-you-mean on an unknown subcommand: each visible child's name plus its aliases.
 * Hidden subcommands are excluded so a typo suggestion never reveals a hidden command's name.
 */
internal fun Command.subcommandCandidates(): List<String> =
    subcommands.filterNot { it.hidden }.flatMap { listOf(it.name) + it.aliases }

/**
 * A cluster char resolved to the spec it names. [globals] is non-null exactly when the char named a global
 * rather than one of this command's own inputs, which is what decides where the occurrence is recorded.
 */
private class ClusterHit<T>(val spec: T, val globals: GlobalAccumulator?)

/** This command's own [local] match if there is one, else the same lookup against the globals. */
private fun <T> GlobalAccumulator?.clusterHit(
    local: T?,
    inGlobals: GlobalAccumulator.() -> T?,
): ClusterHit<T>? = when {
    local != null -> ClusterHit(local, null)
    this != null -> inGlobals()?.let { ClusterHit(it, this) }
    else -> null
}

/**
 * A `--name` or `--name=value` token split into the parts a long-option walk needs: [typed] is the name as
 * written, before an abbreviation resolves it, [inlineValue] the attached value if there is one, and
 * [spelled] what a diagnostic quotes. Errors name [spelled] rather than what it resolved to, since quoting
 * a spelling that was never on the line is worse than useless.
 */
private data class LongToken(val typed: String, val inlineValue: String?, val spelled: String)

private fun longToken(token: String): LongToken {
    val body = token.removePrefix("--")
    val eq = body.indexOf('=')
    val typed = if (eq >= 0) body.take(eq) else body
    return LongToken(typed, if (eq >= 0) body.drop(eq + 1) else null, spelled = "--$typed")
}

/** The value an option occurrence takes, and whether taking it consumed the token after it. */
private class TakenValue(val value: String?, val consumedNext: Boolean)

/**
 * Resolve what this occurrence's value is and how far the walk therefore advances. The two are one
 * decision: advancing out of step with where the value came from is how a walk silently eats or re-reads a
 * token.
 *
 * [attached] is the `=value` of a long token or the rest of a short cluster. An optional-value option never
 * reaches for [next]: it cannot tell its own value from an operand, so a bare occurrence takes what it
 * declared. That is GNU's rule, and the reason POSIX guideline 7 discourages the shape at all. [next] is
 * consulted only when neither applies, and a walk that must not read past an end-of-options marker returns
 * null from it.
 */
private fun OptionSpec?.valueFrom(attached: String?, next: () -> String?): TakenValue {
    val declared = attached ?: this?.bareValue
    if (declared != null) return TakenValue(declared, consumedNext = false)
    val taken = next()
    return TakenValue(taken, consumedNext = taken != null)
}

/**
 * Split a segment into flag counts, option->values map, and positionals. A short-cluster char that is not
 * local is tried against [globalAcc]'s global specs (so a mixed cluster like `-fv` binds the global too),
 * the global occurrence recorded there; without [globalAcc], only this command's own flags/options are
 * recognized.
 *
 * Never fails: an unrecognized or malformed dash token is recorded in [Sifted.error] (first one wins) and
 * the walk carries on, the same accumulate-and-record contract [siftGlobals] uses. [bind] raises that error
 * before it binds anything, so a malformed segment still fails immediately for parsing; completion ignores
 * the error instead, so a half-typed line still yields every input around the offending token.
 *
 * [positions] gives each [segment] token its index in the argv [siftGlobals] walked, so a global recorded
 * here can be ordered against one the pre-strip already resolved and removed (see [Polarity], [Occurrence]).
 * It is optional: a caller with no interest in ordering (completion) may omit it, and the observations this
 * records then simply win outright.
 */
internal fun Command.sift(
    segment: List<String>,
    globalAcc: GlobalAccumulator? = null,
    positions: List<Int> = emptyList(),
): Sifted {
    val flagCounts = mutableMapOf<FlagSpec, Int>()
    val negations = mutableMapOf<FlagSpec, Boolean>()
    val optionValues = mutableMapOf<OptionSpec, MutableList<String>>()
    val positionals = mutableListOf<String>()
    val dashLedAdmitted = mutableSetOf<Int>()
    var error: CliError? = null

    // A lambda, not a value: only the first call's build() actually executes, so suggest()'s edit-distance
    // scan runs at most once even though the walk keeps going after an error.
    fun record(build: () -> CliError) {
        if (error == null) error = build()
    }

    val flagPositions = mutableMapOf<FlagSpec, ClusterPosition>()
    val optionPositions = mutableMapOf<OptionSpec, ClusterPosition>()

    // Loop-invariant, but built only once a long token actually needs it: a segment of operands and short
    // clusters must not pay for a list it never reads.
    val longPool by lazy(LazyThreadSafetyMode.NONE) { longMatchPool(globalAcc) }

    // Loop-invariant; the property behind it walks the specs on every read.
    val dashLedSlot = hasDashLedSlot

    fun hit(flag: FlagSpec, polarity: Boolean, at: ClusterPosition) {
        flagCounts[flag] = (flagCounts[flag] ?: 0) + 1
        flagPositions[flag] = at
        if (flag.negatable) negations[flag] = polarity
    }

    var i = 0
    var optionsEnded = false
    // `--` makes every later token literal by POSIX contract, so the refusal below must not reach past one.
    var endedBySeparator = false
    while (i < segment.size) {
        val token = segment[i]
        // This token's place in the ORIGINAL argv, which is what every position recorded below is
        // measured in; a segment the caller did not map falls back to its own index.
        val tokenIndex = positions.getOrNull(i) ?: i
        when {
            optionsEnded -> {
                // Refused rather than kept: neither reading survives here (see MixedClusterAfterOperands),
                // and keeping it whole is the one that loses the global without saying so. Gated on the
                // cluster resolving in FULL, so a tail word that merely happens to carry a global's letter
                // (`tar -cvf x`) stays the operand it is.
                val chars = token.removePrefix("-")
                val mixedGlobal =
                    if (endedBySeparator || !token.isFlagLike() || token.startsWith("--")) null
                    else if (!shortClusterResolvesInFull(chars, globalAcc)) null
                    else globalAcc?.let { firstGlobalShort(chars, it.flagSpecs, it.optionSpecs) }
                if (mixedGlobal != null) {
                    record { CliError.MixedClusterAfterOperands(token, mixedGlobal) }
                } else {
                    positionals += token
                }
                i += 1
            }

            token == END_OF_OPTIONS -> {
                optionsEnded = true
                endedBySeparator = true
                i += 1
            }

            !token.isFlagLike() -> {
                positionals += token
                i += 1
                // POSIX guideline 9 puts every option before the operands, so a command that opted into the
                // conforming reading treats the first operand as the end of options; klap's default is GNU's
                // permutation, which keeps looking.
                if (optionsEndAtFirstOperand) optionsEnded = true
            }

            token.startsWith("--") -> {
                val (typed, inlineValue, spelled) = longToken(token)
                // Resolved against ONE pool so an abbreviation reaching two spellings is reported as
                // ambiguous rather than binding whichever lookup ran first; see [longMatchPool].
                val resolved = resolveLong(typed, longPool, globalAcc?.inferNames ?: false)
                val long = when (resolved) {
                    is NameMatch.Exact -> resolved.name
                    is NameMatch.Prefix -> resolved.name
                    else -> typed
                }
                // An exact spelling would have resolved, so nothing below can match once this is ambiguous.
                val flag = findFlag("--$long")
                val negated = if (flag == null) findNegatedFlag(long) else null
                when {
                    resolved is NameMatch.Ambiguous -> {
                        record { ambiguousOrUnknown(spelled, resolved.candidates.map { "--$it" }, globalAcc) }
                        i += 1
                    }

                    flag != null -> {
                        // hit() still runs, matching siftGlobals: the flag WAS present, and a caller that
                        // ignores the error should see it. Invisible at parse time, where bind raises first.
                        if (inlineValue != null) {
                            record {
                                CliError.FlagTakesNoValue(
                                    spelled,
                                    if (flag.negatable) flag.negativeLongs.firstOrNull() else null,
                                )
                            }
                        }
                        hit(flag, true, ClusterPosition(tokenIndex))
                        i += 1
                    }

                    negated != null -> {
                        if (inlineValue != null) record { CliError.FlagTakesNoValue(spelled) }
                        hit(negated, false, ClusterPosition(tokenIndex))
                        i += 1
                    }

                    else -> {
                        val opt = findOption(long, null)
                        val taken = opt.valueFrom(inlineValue) {
                            segment.getOrNull(i + 1)?.takeUnless { it == END_OF_OPTIONS }
                        }
                        when {
                            // klap's own, resolved by the built-in ladder rather than bound here. In the
                            // pool since it is a legal spelling on every command, so without this the
                            // binder would call a name its own pool accepts unknown.
                            opt == null && long in globalAcc.offeredBuiltinLongs() -> i += 1

                            opt == null -> {
                                record {
                                    CliError.UnknownOption(
                                        spelled,
                                        suggest(spelled, longOptionCandidates(globalAcc)),
                                    )
                                }
                                // Skipped, never demoted to a positional: the tokens after it still fill
                                // their own slots, but the unknown option itself must not occupy one.
                                i += 1
                            }

                            taken.value == null -> {
                                record { CliError.MissingOptionValue(spelled) }
                                i += 1
                            }

                            else -> {
                                optionValues.getOrPut(opt) { mutableListOf() } += taken.value
                                optionPositions[opt] = ClusterPosition(tokenIndex)
                                i += if (taken.consumedNext) 2 else 1
                            }
                        }
                    }
                }
            }

            else -> {
                // Short cluster: each char is a flag until one names an option, which takes the rest (`-p8080`)
                // or the next token. A char that is not local is tried against the globals ([globalAcc]) so a
                // mixed cluster like `-fv` binds the global too; only a char that is neither is recorded.
                val chars = token.removePrefix("-")
                var advance = 1

                // Decided before the walk, because the walk records flag hits as it goes and a token that
                // turns out to be an operand would have to unwind them. A `--` token cannot arrive here at
                // all, the arm above claims it, so long-option typos keep their did-you-mean.
                if (dashLedSlot && !shortClusterResolvesInFull(chars, globalAcc)) {
                    dashLedAdmitted += positionals.size
                    positionals += token
                    i += 1
                    // It is an operand, so it ends options under the POSIX reading exactly as the
                    // not-flag-like route above does; "the first operand" cannot depend on its spelling.
                    if (optionsEndAtFirstOperand) optionsEnded = true
                    continue
                }

                var j = 0
                while (j < chars.length) {
                    // One position per character, shared by the local record and the global top-up below, which
                    // must agree about where this character sat.
                    val at = ClusterPosition(tokenIndex, j)
                    val run = numberRunAt(chars, j, globalAcc)
                    if (run != null) {
                        val spec = numberInput!!
                        optionValues.getOrPut(spec) { mutableListOf() } += run
                        optionPositions[spec] = at
                        j += run.length
                        continue
                    }

                    if (globalAcc.isHelpShort(chars[j])) {
                        // Klap's own, so it binds nothing here; the help ladder answers it. An inline
                        // `=value` on it is the same usage error a declared boolean short reports.
                        if (chars.getOrNull(j + 1) == '=') record { CliError.FlagTakesNoValue("--help", null) }
                        j += 1
                        continue
                    }

                    val ch = chars[j].toString()
                    val flagHit = globalAcc.clusterHit(findFlag("-$ch")) { flagSpecs.findFlag("-$ch") }
                    if (flagHit != null) {
                        val globals = flagHit.globals
                        if (globals != null) globals.hitFlag(flagHit.spec, at)
                        else hit(flagHit.spec, true, at)
                        j += 1
                        continue
                    }

                    val negatedHit = globalAcc.clusterHit(findNegatedShort(ch)) { flagSpecs.findNegatedShort(ch) }
                    if (negatedHit != null) {
                        val globals = negatedHit.globals
                        if (globals != null) globals.hitFlag(negatedHit.spec, at, on = false)
                        else hit(negatedHit.spec, false, at)
                        j += 1
                        continue
                    }

                    // No suggestion here: a single-letter short option is too noisy to edit-distance against.
                    val optHit = globalAcc.clusterHit(findOption(null, ch)) { optionSpecs.findOption(null, ch) }
                    if (optHit == null) {
                        record { clusterCharError(token, chars, j, globalAcc) }
                        break
                    }
                    val opt = optHit.spec
                    val attached = chars.substring(j + 1).ifEmpty { null }
                    val taken = opt.valueFrom(attached) {
                        segment.getOrNull(i + 1)?.takeUnless { it == END_OF_OPTIONS }
                    }
                    if (taken.value == null) {
                        record { CliError.MissingOptionValue(opt.token()) }
                        break
                    }
                    val globals = optHit.globals
                    if (globals != null) {
                        globals.addOptionValue(opt, taken.value, at)
                    } else {
                        optionValues.getOrPut(opt) { mutableListOf() } += taken.value
                        optionPositions[opt] = at
                    }
                    advance = if (taken.consumedNext) 2 else 1
                    j = chars.length
                }
                i += advance
            }
        }
    }
    return Sifted(
        flagCounts,
        negations,
        optionValues,
        positionals,
        error,
        flagPositions,
        optionPositions,
        dashLedAdmitted,
    )
}

internal fun List<FlagSpec>.findFlag(token: String): FlagSpec? = when {
    token.startsWith("--") -> firstOrNull { token.removePrefix("--") in it.longs }
    token.startsWith("-") -> firstOrNull { token.removePrefix("-") in it.shorts }
    else -> null
}

/** The negatable flag whose negative half is spelled `--<long>`, if [long] names one. */
internal fun List<FlagSpec>.findNegatedFlag(long: String): FlagSpec? =
    firstOrNull { spec -> long in spec.negativeLongs }

/** The negatable flag whose negative half is spelled `-<short>`, if [short] names one. */
internal fun List<FlagSpec>.findNegatedShort(short: String): FlagSpec? =
    firstOrNull { spec -> short in spec.negativeShorts }

/**
 * [findFlag], but also matching a dash-prefixed short by its NEGATED spelling: a short cluster's
 * preceding char (see [clusterCharError]) may have been consumed as either half, and only this combined
 * lookup lets a negative short (`-P` in `.negatable("-P")`) be blamed by name instead of falling through
 * to a phantom [CliError.UnknownOption].
 */
private fun List<FlagSpec>.findFlagOrNegatedShort(token: String): FlagSpec? =
    findFlag(token) ?: findNegatedShort(token.removePrefix("-"))

internal fun List<OptionSpec>.findOption(long: String?, short: String?): OptionSpec? =
    firstOrNull { (long != null && long in it.longs) || (short != null && short in it.shorts) }

/**
 * The leftmost character of [cluster] (a short-cluster token with its leading dash already stripped) that
 * names a global flag, its negated short, or a global option, dash included; null when none does. The
 * per-char lookup is [siftGlobals]'s own, so a caller that has not reached the command owning the cluster's
 * OTHER chars still tells "carries a global" from "carries none" the way the sift would.
 */
private fun firstGlobalShort(
    cluster: String,
    flagSpecs: List<FlagSpec>,
    optionSpecs: List<OptionSpec>,
): String? = cluster.firstOrNull { char ->
    val ch = char.toString()
    flagSpecs.findFlag("-$ch") != null ||
        flagSpecs.findNegatedShort(ch) != null ||
        optionSpecs.findOption(null, ch) != null
}?.let { "-$it" }

private fun Command.findFlag(token: String): FlagSpec? = flags.findFlag(token)
private fun Command.findNegatedFlag(long: String): FlagSpec? = flags.findNegatedFlag(long)
private fun Command.findNegatedShort(short: String): FlagSpec? = flags.findNegatedShort(short)
private fun Command.findOption(long: String?, short: String?): OptionSpec? =
    options.findOption(long, short)

/** Whether every character of a short cluster resolves; see [firstUnresolvedShort]. */
private fun Command.shortClusterResolvesInFull(
    chars: String,
    globalAcc: GlobalAccumulator?,
): Boolean = firstUnresolvedShort(chars, globalAcc) == null

/**
 * The leftmost character of [chars] naming nothing this command or [globalAcc] declares, dash included, or
 * null when the cluster resolves in full. Answers without recording a hit, which is the point: [sift]'s
 * cluster walk mutates as it goes, so a token that turns out to be an operand has to be recognised before
 * the walk starts rather than unwound afterwards.
 *
 * The loop mirrors that walk, early exit included, which is what makes "declared wins" all-or-nothing up
 * to the option that ends it. Sharing only the lookups would not be enough: `-p8080` resolves there, where
 * requiring every character to resolve would demand the same of `8080` and hand a declared option's own
 * token to an operand slot.
 *
 * Recognition lives INSIDE this function rather than ahead of its callers: the dash-led admission asks this
 * before the binding walk starts, so there is no "before" left to put it in.
 */
private fun Command.firstUnresolvedShort(
    chars: String,
    globalAcc: GlobalAccumulator?,
): String? {
    var j = 0
    while (j < chars.length) {
        val run = numberRunAt(chars, j, globalAcc)
        if (run != null) {
            j += run.length
            continue
        }
        val c = chars[j]
        if (globalAcc.isHelpShort(c)) {
            j += 1
            continue
        }
        val ch = c.toString()
        if (globalAcc.clusterHit(findFlag("-$ch")) { flagSpecs.findFlag("-$ch") } != null) {
            j += 1
            continue
        }
        if (globalAcc.clusterHit(findNegatedShort(ch)) { flagSpecs.findNegatedShort(ch) } != null) {
            j += 1
            continue
        }
        // An option takes the rest of the cluster as its value, so nothing after it has to resolve.
        val option = globalAcc.clusterHit(findOption(null, ch)) { optionSpecs.findOption(null, ch) }
        return if (option != null) null else "-$ch"
    }
    return null
}

/**
 * Whether [token] asks for help through klap's short: the reading [sift] gives that token, asked without
 * recording anything, so the help ladder and the bind cannot disagree about one cluster.
 *
 * The short takes part in a cluster like a declared one, which is what confines it: past a character
 * naming a value-taking option the `h` is that option's value (`-ph`), past one naming nothing at all it
 * sits in a cluster already refused (`-xh`), and on a `dashLed()` command a cluster that resolves to
 * nothing is an operand (`-1h`).
 */
internal fun Command.namesHelpShort(token: String, globalAcc: GlobalAccumulator?): Boolean {
    if (!token.isFlagLike() || token.startsWith("--")) return false
    val chars = token.removePrefix("-")
    if (hasDashLedSlot && !shortClusterResolvesInFull(chars, globalAcc)) return false
    var j = 0
    while (j < chars.length) {
        val run = numberRunAt(chars, j, globalAcc)
        if (run != null) {
            j += run.length
            continue
        }
        // An `=value` on a boolean built-in is a usage error, raised where the sift walks the cluster.
        if (globalAcc.isHelpShort(chars[j])) return chars.getOrNull(j + 1) != '='
        val ch = chars[j].toString()
        if (globalAcc.clusterHit(findFlag("-$ch")) { flagSpecs.findFlag("-$ch") } != null) {
            j += 1
            continue
        }
        if (globalAcc.clusterHit(findNegatedShort(ch)) { flagSpecs.findNegatedShort(ch) } != null) {
            j += 1
            continue
        }
        return false
    }
    return false
}

/** Whether any token [this] scan may act on names klap's help short; see [Command.namesHelpShort]. */
internal fun ArgvScan.namesHelpShort(cmd: Command, globalAcc: GlobalAccumulator?): Boolean =
    openTokens.any { cmd.namesHelpShort(it, globalAcc) }

/**
 * The error for an unrecognized char at `chars[j]` inside a short cluster (the chars before it, `chars[0
 * until j]`, were all consumed by the walk — a flag, a negative short, klap's help short, or a digit run
 * the number input claimed — since an option match would have ended the loop already). A `=` right
 * after a flag char is the short form of `--flag=value`, so it reports the same no-value error the long
 * form does. It names the preceding flag rather than the phantom `-=` a bare `"-$ch"` would fabricate;
 * both callers back [findFlag] with [findFlagOrNegatedShort], so a preceding NEGATIVE short is named too.
 * That lookup is per-character, so where a run was consumed the char before the `=` is its last DIGIT, and
 * `-12=x` beside `flag("-2")` blames `-2` — a spelling the run swallowed. Left as it is: the `=` is a usage
 * error under either reading.
 * Any other stray non-alphanumeric char (e.g. the
 * `-` in `-f-y`) blames the whole original [token] instead of fabricating `"-$ch"` (which for `ch == '-'`
 * would misreport as the end-of-options marker `--`). A genuinely unknown LETTER/digit still names just
 * that char, matching a leaf's existing granularity. Shared by [Command.clusterCharError] (the per-command
 * sift, [findFlag] backed by local + global specs) and [siftGlobals] (the position-independent pre-strip,
 * [findFlag] backed by global specs alone), so both short-cluster walks report a boolean flag's `=value`
 * identically regardless of whether the cluster precedes or follows the subcommand.
 */
private fun clusterCharError(
    findFlag: (String) -> FlagSpec?,
    token: String,
    chars: String,
    j: Int,
): CliError {
    val ch = chars[j]
    val prevChar = chars.getOrNull(j - 1)
    val prevFlag = prevChar?.let { prev -> findFlag("-$prev") }
    return when {
        ch == '=' && prevFlag != null ->
            CliError.FlagTakesNoValue(
                "-$prevChar",
                if (prevFlag.negatable) prevFlag.negativeLongs.firstOrNull() else null,
            )

        // The cluster only earns a mention when it is not the whole story: `-z` reports itself, while
        // `-1m` splits and would otherwise report a `-1` the user cannot find in what they typed.
        ch.isLetterOrDigit() -> CliError.UnknownOption("-$ch", cluster = token.takeIf { it != "-$ch" })
        else -> CliError.UnknownOption(token)
    }
}

private fun Command.clusterCharError(
    token: String,
    chars: String,
    j: Int,
    globalAcc: GlobalAccumulator?,
): CliError =
    clusterCharError(
        { s -> flags.findFlagOrNegatedShort(s) ?: globalAcc?.flagSpecs?.findFlagOrNegatedShort(s) },
        token,
        chars,
        j,
    )

/**
 * Seed a mutable [GlobalAccumulator] from this pre-strip result, ready for the segment sift to top up. The
 * root's surface is threaded through so a nested command's [longOptionCandidates] and [longMatchPool] see
 * the same surface the root's own pre-walk scans did.
 */
internal fun GlobalSift.accumulator(
    globalSpecs: List<HolderSpec>,
    rootVersion: String? = null,
    builtins: Builtins = Builtins.DEFAULT,
    metaOptions: Boolean = false,
    treeLongs: List<String> = emptyList(),
    abbreviation: Abbreviation = Abbreviation.None,
): GlobalAccumulator = GlobalAccumulator(
    flagSpecs = globalSpecs.filterIsInstance<FlagSpec>(),
    optionSpecs = globalSpecs.filterIsInstance<OptionSpec>(),
    rootVersion = rootVersion,
    builtins = builtins,
    metaOptions = metaOptions,
    treeLongs = treeLongs,
    abbreviation = abbreviation,
    flags = flags.toMutableMap(),
    negations = negations.toMutableMap(),
    options = options.mapValues { it.value.toMutableList() }.toMutableMap(),
)

/**
 * This root's spec lists and pools in a [GlobalAccumulator] holding no occurrences: what a pass that must
 * READ the global surface before [siftGlobals] has produced one asks its questions through, so its reading
 * of a token is identical to the one [sift] will give.
 */
internal fun Cli.globalLookup(): GlobalAccumulator =
    GlobalSift(emptyMap(), emptyMap(), emptyMap())
        .accumulator(globalSpecs, version, builtins, metaOptions, declaredLongs, abbreviation)

/**
 * Walk [scan]'s tokens before the end-of-options marker, pull out those naming a global (long form, or a
 * short cluster whose chars are ALL globals, e.g. `-vc`), and return the cleaned argv for the normal
 * subcommand walk alongside the sifted occurrences. A short cluster that mixes a global with a non-global
 * char is left whole for the resolved command's global-aware [sift] to split (so `build -fv` binds both,
 * in any order); it does not route, so the command that splits it is whatever the walk had reached.
 * Unlike [sift], an unrecognized dash token is left in place rather than erroring: it may belong to
 * whatever command the walk resolves to, and only that command's own [sift] is allowed to reject it.
 *
 * [reachableLongs] is every long spelling reachable at this token position, whether or not this pass binds
 * it: the built-ins, and every long declared anywhere in the tree. Pass the caller's own pre-walk pool
 * ([Cli.positionIndependentLongs]) and the two can never drift. They take part in resolving an abbreviation
 * without this pass ever binding one of them, so a `--sor` that could mean a global `--sort` or a command's
 * `--sort-by` is left whole for the ambiguity to be reported against the full pool, instead of being bound
 * here as the only spelling this pass can see.
 *
 * Position-independent stops at a value-taking option's argument slot, the one place a global has no claim
 * on ([ArgvScan.valueSlots]): `sub -e --tag f` gives `-e` the literal `--tag`, and the global keeps its
 * default. That is why this takes a [scan] rather than a bare list — the slots and the argv-scale positions
 * they are keyed on travel together, and neither is derivable from the tokens this pass can see.
 */
internal fun List<HolderSpec>.siftGlobals(
    scan: ArgvScan,
    reachableLongs: List<String>,
): GlobalPreStrip {
    val argv = scan.tokens
    val positions = scan.positions
    val flagSpecs = filterIsInstance<FlagSpec>()
    val optionSpecs = filterIsInstance<OptionSpec>()
    val globalPool = filterIsInstance<NamedSpec>().flatMap { it.longs } +
            flagSpecs.filter { it.negatable }.flatMap { it.negativeLongs } +
            reachableLongs
    val flagCounts = mutableMapOf<FlagSpec, Int>()
    val negations = mutableMapOf<FlagSpec, Polarity>()
    val optionValues = mutableMapOf<OptionSpec, MutableList<Occurrence>>()
    val cleaned = mutableListOf<String>()
    val keptPositions = mutableListOf<Int>()
    var error: CliError? = null

    // The only way to append to `cleaned`: the parallel position list must never drift from it.
    fun keep(token: String, at: Int) {
        cleaned += token
        keptPositions += at
    }

    fun hit(flag: FlagSpec, polarity: Boolean, at: ClusterPosition?) {
        flagCounts[flag] = (flagCounts[flag] ?: 0) + 1
        if (flag.negatable) negations.recordPolarity(flag, polarity, at)
    }

    val end = argv.indexOf(END_OF_OPTIONS)
    val head = if (end < 0) argv else argv.take(end)
    val tail = if (end < 0) emptyList() else argv.drop(end)
    var i = 0
    while (i < head.size) {
        val token = head[i]
        // Reported on the caller's argv scale, not this pass's own; see [ArgvScan.positions].
        val at = positions[i]
        when {
            // The token before it takes a value, so this one IS that value, whatever it names. Handed on
            // whole for the reached command's own sift to consume, never read as a global here.
            at in scan.valueSlots -> {
                keep(token, at)
                i += 1
            }

            !token.isFlagLike() -> {
                keep(token, at)
                i += 1
            }

            token.startsWith("--") -> {
                val (typed, inlineValue, spelled) = longToken(token)
                // An abbreviation resolving to anything but a global (an ambiguity, or one of the built-ins
                // in the pool) leaves the token whole, exactly as an unrecognized one does: this pass runs
                // before the walk knows its command, so only that command's own sift sees the full pool.
                val long = when (val resolved = resolveLong(typed, globalPool, scan.infer)) {
                    is NameMatch.Exact -> resolved.name
                    is NameMatch.Prefix -> resolved.name
                    else -> typed
                }
                val flag = flagSpecs.findFlag("--$long")
                val negated = if (flag == null) flagSpecs.findNegatedFlag(long) else null
                val opt = if (flag == null && negated == null) optionSpecs.findOption(long, null) else null
                when {
                    flag != null -> {
                        if (inlineValue != null && error == null) {
                            error = CliError.FlagTakesNoValue(
                                spelled,
                                if (flag.negatable) flag.negativeLongs.firstOrNull() else null,
                            )
                        }
                        hit(flag, true, ClusterPosition(at))
                        i += 1
                    }

                    negated != null -> {
                        if (inlineValue != null && error == null) error =
                            CliError.FlagTakesNoValue(spelled)
                        hit(negated, false, ClusterPosition(at))
                        i += 1
                    }

                    opt != null -> {
                        // `head` stops at the end-of-options marker, so an existing next token is a value.
                        val taken = opt.valueFrom(inlineValue) { head.getOrNull(i + 1) }
                        if (taken.value == null) {
                            // A recognized global with no value is a hard error: capture it so parse() reports
                            // "requires a value" instead of the command's own sift calling the leftover unknown.
                            if (error == null) error = CliError.MissingOptionValue(opt.token())
                            keep(token, at)
                            i += 1
                        } else {
                            optionValues.getOrPut(opt) { mutableListOf() } += Occurrence(taken.value, ClusterPosition(at))
                            i += if (taken.consumedNext) 2 else 1
                        }
                    }

                    else -> {
                        keep(token, at)
                        i += 1
                    }
                }
            }

            else -> {
                // A short cluster is stripped here only if EVERY char is a global (all-or-nothing): global
                // flags, optionally ending in a global option that takes the attached/next value. The moment
                // a non-global char appears the whole token is left untouched for the resolved command's own
                // (global-aware) sift, so a local-then-global cluster like `-fv` still binds the global there.
                // `-vc`, `-vp8080`.
                val chars = token.removePrefix("-")
                // Paired with the character each sat at, so two globals in one cluster order against
                // each other exactly as two of a command's own do.
                val pendingFlags = mutableListOf<Pair<FlagSpec, Int>>()
                val pendingNegatedFlags = mutableListOf<Pair<FlagSpec, Int>>()
                var pendingOption: Triple<OptionSpec, String, Int>? = null
                var pendingDangling: OptionSpec? = null
                var pendingEqError: CliError? = null
                var fullyGlobal = true
                var advance = 1
                var j = 0
                while (j < chars.length) {
                    val ch = chars[j].toString()
                    val flag = flagSpecs.findFlag("-$ch")
                    if (flag != null) {
                        pendingFlags += flag to j
                        j += 1
                        continue
                    }
                    val negated = flagSpecs.findNegatedShort(ch)
                    if (negated != null) {
                        pendingNegatedFlags += negated to j
                        j += 1
                        continue
                    }
                    val opt = optionSpecs.findOption(null, ch)
                    when {
                        opt != null -> {
                            val attached = chars.substring(j + 1).ifEmpty { null }
                            val taken = opt.valueFrom(attached) { head.getOrNull(i + 1) }
                            if (taken.value == null) pendingDangling = opt else {
                                pendingOption = Triple(opt, taken.value, j)
                                if (taken.consumedNext) advance = 2
                            }
                        }
                        // `=` right after a matched global boolean flag, positive or explicit negative, is
                        // the short form of `--flag=value`: the same unambiguous FlagTakesNoValue the
                        // per-command sift's clusterCharError reports for this pattern, regardless of
                        // whether the cluster precedes or follows the subcommand (see clusterCharError above).
                        ch == "=" && (pendingFlags.isNotEmpty() || pendingNegatedFlags.isNotEmpty()) ->
                            pendingEqError = clusterCharError(flagSpecs::findFlagOrNegatedShort, token, chars, j)

                        else -> fullyGlobal = false
                    }
                    break
                }
                if (fullyGlobal) {
                    pendingFlags.forEach { (flag, charAt) -> hit(flag, true, ClusterPosition(at, charAt)) }
                    pendingNegatedFlags.forEach { (flag, charAt) -> hit(flag, false, ClusterPosition(at, charAt)) }
                    pendingOption?.let { (opt, value, charAt) ->
                        optionValues.getOrPut(opt) { mutableListOf() } += Occurrence(value, ClusterPosition(at, charAt))
                    }
                    pendingDangling?.let { opt ->
                        // A recognized value-taking global with no value is a hard error, same as the long form.
                        if (error == null) error = CliError.MissingOptionValue(opt.token())
                        keep(token, at)
                    }
                    pendingEqError?.let { e ->
                        if (error == null) error = e
                        keep(token, at)
                    }
                } else {
                    // Mixed cluster: hand the whole token to the command sift; record none of it here.
                    keep(token, at)
                    advance = 1
                }
                i += advance
            }
        }
    }
    // Post-`--` tokens are positionals, never flags, so their positions matter to nothing; they are carried
    // anyway to keep the list indexable at every offset a segment can reach.
    val tailPositions = if (end < 0) emptyList() else positions.drop(end)
    return GlobalPreStrip(
        cleaned + tail,
        keptPositions + tailPositions,
        GlobalSift(flagCounts, negations, optionValues, error),
    )
}

/**
 * Global option/flag occurrences pulled out of argv, ahead of and independent from the subcommand walk.
 * [error] carries the first hard syntax error hit while sifting a recognized global (a dangling
 * value-taking option, or a `--flag=value` given to a boolean flag), which [parse] raises before the
 * command bind rather than letting the leftover token be misreported as an unknown option.
 */
internal class GlobalSift(
    val flags: Map<FlagSpec, Int>,
    val negations: Map<FlagSpec, Polarity>,
    val options: Map<OptionSpec, List<Occurrence>>,
    val error: CliError? = null,
)

/**
 * What [siftGlobals] hands the subcommand walk: the tokens it did not consume ([cleaned]), each one's index
 * in the caller's own argv ([positions], parallel to [cleaned]), and the globals it pulled out ([sift]).
 *
 * [positions] exists because a token [siftGlobals] CONSUMED leaves no slot in [cleaned] at all, so where a
 * kept token sat relative to a removed one is only expressible on the original argv scale — an index into
 * [cleaned], or into a segment cut from it, cannot say it. See [Polarity] for what needs the comparison.
 */
internal class GlobalPreStrip(
    val cleaned: List<String>,
    val positions: List<Int>,
    val sift: GlobalSift,
)

/**
 * A negatable flag's observed polarity ([on]) and the argv index it was observed at ([position]).
 *
 * The position is what keeps last-occurrence-wins holding for a GLOBAL, whose two forms are resolved by two
 * different passes: [siftGlobals] reads `--no-x` in argv order, but a global short buried in a mixed
 * local+global cluster (`-fx`) is unresolvable there — it holds no local specs, so it cannot tell a flag
 * char from a glued value — and only the command's own [sift] resolves it, one pass later. Without a
 * position that later pass wins wherever the cluster sat.
 *
 * A null [position] means the caller tracks none (completion, which never orders anything); the later write
 * then simply wins outright.
 */
internal class Polarity(val on: Boolean, val position: ClusterPosition?)

/** Keep whichever polarity sits LAST in argv; an unordered observation (see [Polarity]) always wins. */
private fun MutableMap<FlagSpec, Polarity>.recordPolarity(flag: FlagSpec, on: Boolean, at: ClusterPosition?) {
    val seenAt = this[flag]?.position
    if (seenAt != null && at != null && at < seenAt) return
    this[flag] = Polarity(on, at)
}

/**
 * One occurrence of a value-taking option: the raw [value] and the argv index it was written at
 * ([position]). [Polarity]'s counterpart for a global's values, there for the same reason — a global's
 * occurrences are resolved by two passes ([siftGlobals], then the command's own [sift]), so the order they
 * are collected in is not the order they were written in, and only an argv-scale index can say which of
 * them came last.
 *
 * A null [position] means the caller tracks none (completion), and such an occurrence sorts after every
 * positioned one, so the later pass's observation simply wins.
 */
internal class Occurrence(val value: String, val position: ClusterPosition?)

/**
 * A mutable running tally of global occurrences: seeded from [siftGlobals]' position-independent pass, then
 * topped up by a command segment's own [sift] when a global char is buried in a mixed short cluster (`-fv`).
 * It also carries the root's own surface down to a nested [sift], the only way that surface reaches the
 * did-you-mean candidate set and the abbreviation pool (see [longOptionCandidates], [longMatchPool])
 * without threading five separate parameters through every call between [parse] and [sift].
 */
internal class GlobalAccumulator(
    val flagSpecs: List<FlagSpec>,
    val optionSpecs: List<OptionSpec>,
    val rootVersion: String?,
    val builtins: Builtins = Builtins.DEFAULT,
    val metaOptions: Boolean = false,
    val treeLongs: List<String> = emptyList(),
    val abbreviation: Abbreviation = Abbreviation.None,
    private val flags: MutableMap<FlagSpec, Int>,
    private val negations: MutableMap<FlagSpec, Polarity>,
    private val options: MutableMap<OptionSpec, MutableList<Occurrence>>,
) {
    /**
     * Record a short-flag occurrence at argv index [position], [on] true for the plain short and false for
     * an explicit negative short (a generated `--no-<long>` negation has no short form to begin with, so
     * only an explicit spelling reaches this with `on = false`). The count always rises, since the flag WAS
     * on the line, but the polarity only sticks if [position] is at or past the one already recorded, so a
     * later-in-argv occurrence survives this pass; see [Polarity].
     */
    fun hitFlag(spec: FlagSpec, position: ClusterPosition? = null, on: Boolean = true) {
        flags[spec] = (flags[spec] ?: 0) + 1
        if (spec.negatable) negations.recordPolarity(spec, on = on, at = position)
    }

    /** Whether a long option or a declared choice value resolves by prefix here; an exact spelling always binds. */
    val inferNames: Boolean get() = abbreviation != Abbreviation.None

    /** Whether a subcommand name resolves by prefix here; only [Abbreviation.All] reaches this far. */
    val inferSubcommands: Boolean get() = abbreviation == Abbreviation.All

    /**
     * Whether [spec] was GIVEN in its positive form, [Command.supplied]'s question asked of a global: a
     * negatable flag's `--no-x` is an opt-out, so it must not read as a selection of `x`.
     */
    fun supplied(spec: FlagSpec): Boolean =
        if (spec.negatable) negations[spec]?.on == true else (flags[spec] ?: 0) > 0

    /** Record an option occurrence; [position] orders it against the pass before, see [Occurrence]. */
    fun addOptionValue(spec: OptionSpec, value: String, position: ClusterPosition?) {
        options.getOrPut(spec) { mutableListOf() } += Occurrence(value, position)
    }

    /** The merged occurrences, ready for [bindGlobals]. Segment syntax errors surface via [Sifted.error]. */
    fun toGlobalSift(): GlobalSift = GlobalSift(flags, negations, options)
}
