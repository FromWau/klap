package com.fromwau.klap.internal.parse

import com.fromwau.kern.fuzzy.didYouMean
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.infersValues
import com.fromwau.klap.ActionScope
import com.fromwau.klap.BuiltinLong
import com.fromwau.klap.Builtins
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.Command
import com.fromwau.klap.Globals
import com.fromwau.klap.Invocation
import com.fromwau.klap.RepeatedOptions
import com.fromwau.klap.ShortOptions
import com.fromwau.klap.SubcommandMatch
import com.fromwau.klap.builtinLongTable
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
import com.fromwau.klap.internal.spec.refusesRepeat
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.internal.spec.token
import com.fromwau.klap.resolveSubcommand

/** The end of options: every token after it is positional, unless an option takes it as its value. */
internal const val END_OF_OPTIONS = "--"

/**
 * Where the options end in these tokens: the first `--` no option takes as its value, as getopt reads it
 * (`ls --sort --` hands `--` to `--sort`), or -1 when none does. [positions] places each token on the scale of
 * [valueSlots].
 */
internal fun List<String>.endOfOptions(positions: List<Int> = indices.toList(), valueSlots: Set<Int>): Int =
    indices.firstOrNull { this[it] == END_OF_OPTIONS && positions[it] !in valueSlots } ?: -1

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
 * Every short spelling [specs] answer to, a negatable flag's negative shorts included:
 * `.negatable(short = '3')` declares `-3` as surely as `flag(short = '4')` declares `-4`, and a run
 * that swallowed it would leave a declared spelling no line could reach.
 */
private fun declaredShortsOf(specs: List<NamedSpec>): Set<String> =
    specs.flatMapTo(mutableSetOf()) { spec ->
        if (spec is FlagSpec) spec.shorts + spec.negativeShorts else spec.shorts
    }

/**
 * The run of digits at [chars] index [at] that binds this command's number input, or null when nothing there
 * is one: no number input is declared, the character is not a digit, the input is read only as its command's
 * first word and this is not the start of that word ([firstWord] says whether [chars] is it), or every digit of
 * the run names a declared short. Its value is the maximal run of digits plus each of the input's suffix
 * letters typed after them, up to the next digit, in the order typed; its width covers the digits and the
 * suffix letters right after them.
 *
 * The declared-short case reads a token identifiable as a group of options as one, as getopt does, scoped to the RUN
 * rather than to the whole token. `-4` where a flag `-4` is declared is
 * that flag; `-45` where only `-4` is declared is not a valid group, so it is the number 45. A global's
 * shorts count as much as this command's own, since a cluster mixing the two reaches this sift whole
 * ([siftGlobals]), and a flag's negative shorts count as much as its positive ones ([declaredShortsOf]).
 *
 * The run is cut out before the per-character walk reaches inside it, which is what settles `-25` beside a
 * value-taking `option(short = '2', long = "two")`: the run wins and the number is 25, rather than `-2` taking `5` as
 * its value. Where the run IS fully covered (`-2 5`) this declines and the ordinary cluster reading holds.
 */
internal fun Command.numberRunAt(
    chars: String,
    at: Int,
    globalAcc: GlobalAccumulator?,
    firstWord: Boolean,
): ShortRead.NumberRun? {
    // Cheapest test first: [numberInput] and [declaredShortsOf] both walk spec lists, and this is asked once
    // per character of every short cluster the parser reads.
    if (at >= chars.length || !chars[at].isDigit()) return null
    val shape = numberInput?.number ?: return null
    if (shape.firstWordOnly && (at != 0 || !firstWord)) return null
    val digits = chars.substring(at).takeWhile { it.isDigit() }
    val shorts = declaredShortsOf(namedInputs + globalAcc?.flagSpecs.orEmpty() + globalAcc?.optionSpecs.orEmpty())
    if (digits.all { it.toString() in shorts }) return null
    val letters = chars.substring(at + digits.length).takeWhile { !it.isDigit() }
    val glued = letters.takeWhile { it in shape.suffixes }
    return ShortRead.NumberRun(digits + letters.filter { it in shape.suffixes }, digits.length + glued.length)
}

/**
 * Whether the character at [at] follows a run of a number input that keeps suffix letters, with no digit
 * between them: such a cluster holds the run's own letters and flags, never a value-taking short.
 */
internal fun Command.followsSuffixRun(
    chars: String,
    at: Int,
    globalAcc: GlobalAccumulator?,
    firstWord: Boolean,
): Boolean {
    if (numberSuffixes.isEmpty()) return false
    val lastDigit = (at - 1 downTo 0).firstOrNull { chars[it].isDigit() } ?: return false
    var start = lastDigit
    while (start > 0 && chars[start - 1].isDigit()) start -= 1
    return numberRunAt(chars, start, globalAcc, firstWord) != null
}

/** The letters this command's number input keeps after its digits; empty without one. */
internal val Command.numberSuffixes: String get() = numberInput?.number?.suffixes.orEmpty()

/**
 * Whether [ch] is klap's own help short here. Asked ahead of every declared lookup, which costs nothing:
 * the character is reserved at build time while the built-in is offered, so no spec can be holding it.
 */
internal fun GlobalAccumulator?.isHelpShort(ch: Char): Boolean =
    ch == (this?.builtins ?: Builtins.DEFAULT).helpShort

/** Whether [ch] is the short alias a tool gave `--version`; reserved at build time like the help short. */
internal fun GlobalAccumulator?.isVersionShort(ch: Char): Boolean =
    ch == (this?.builtins ?: Builtins.DEFAULT).versionShort

/** Whether [ch] is one of klap's own shorts, which a cluster reads past like a declared flag. */
internal fun GlobalAccumulator?.isBuiltinShort(ch: Char): Boolean = isHelpShort(ch) || isVersionShort(ch)

/** Whether a second occurrence of [spec] refuses the line, by its own `.repeated()` or the CLI's policy. */
internal fun GlobalAccumulator?.refusesRepeat(spec: NamedSpec): Boolean =
    spec.refusesRepeat(this?.repeatedOptions ?: RepeatedOptions.LastWins)

/** Whether any operand slot is `dashLed()`; over [Command.specs] so the answer costs one pass and no list. */
private val Command.hasDashLedSlot: Boolean get() = specs.any { it is ArgumentSpec && it.dashLed }

/**
 * Which of this command's operands ends its options, counted from 0 among the operands, or null when only `--`
 * does: the first under [Command.optionsEndAtFirstOperand], else the first word of its `endsOptions()` slot. That
 * slot's place is fixed at build time, so its declared index is the operand that reaches it.
 */
internal val Command.optionsEndAt: Int?
    get() = if (optionsEndAtFirstOperand) 0 else arguments.indexOfFirst { it.endsOptions }.takeIf { it >= 0 }

/** The operand slot that gathers dash words, `dashLed(gather) { }`; a command has at most one. */
internal val Command.gatheringSlot: ArgumentSpec? get() = arguments.firstOrNull { it.gather != null }

/** Whether [spec] is the gathering slot and the line gave it a word. */
internal fun ArgumentSpec.gathersOn(sifted: Sifted): Boolean = gather != null && sifted.gathered.isNotEmpty()

/**
 * A command the walk passed on its way to the one it reached: its own segment read by [sifted], the line's
 * globals as it reads them in [globals].
 */
internal class PassedSift(val command: Command, val sifted: Sifted, val globals: GlobalAccumulator)

internal fun Command.bind(
    segment: List<String>,
    globals: Globals,
    qualifiedName: String,
    globalAcc: GlobalAccumulator? = null,
    sink: MutableMap<HolderSpec, Any?>,
    positions: List<Int> = emptyList(),
    passed: List<PassedSift> = emptyList(),
): Result<Invocation, CliError> {
    val sifted = sift(segment, globalAcc, positions)
    val inferValues = globalAcc?.inferValues ?: false
    val globalSift = globalAcc?.toGlobalSift() ?: NO_GLOBALS
    // The line's first refusal wins, as getopt's loop meets each option and its value in turn, the passed commands'
    // segments included. A global's repeat is counted only once this sift has topped the globals up.
    val refusals = passed.flatMap { it.sifted.refusals(it.globals.inferValues) } +
        listOfNotNull(sifted.refusal, globalAcc?.repeatRefusal(), firstBadValue(sifted, globalSift, inferValues))
    // A group's first operand names no child it routed to, so it ends the group's options where it stands.
    val groupOperand = if (isGroup) unroutedOperand(sifted, segment, positions, qualifiedName, globalAcc) else null
    (refusals + listOfNotNull(groupOperand)).minByOrNull { it.at }?.let { return Result.Error(it.error) }
    for (above in passed) {
        above.command.bindOwnOptions(above.sifted, sink, above.globals, skipRequired = prio)
            ?.let { return Result.Error(it) }
    }
    // A group given only its own options shows its help, as `git -C .` prints git's usage.
    if (isGroup) return Result.Success(Invocation.ShowHelp(this, qualifiedName))
    bindOptions(sifted, sink, inferValues)?.let { return Result.Error(it) }
    bindPositionals(sifted.positionals, sink, sifted, inferValues, qualifiedName = qualifiedName)
        .getOrElse { return Result.Error(it) }
    checkConditionalRequirements(sifted, globalAcc)?.let { return Result.Error(it) }
    // Placeholder scope: parse() attaches the completed snapshot (command inputs + globals) via copy().
    // Unreachable: a group returned through the isGroup branch above, and a command with neither an
    // action nor subcommands is rejected at build. Help keeps parse() total rather than throwing.
    val resolved = action ?: return Result.Success(Invocation.ShowHelp(this, qualifiedName))
    val line = lineOrder(sifted, globalSift, inferValues)
    return Result.Success(Invocation.Execute(this, globals, resolved, ActionScope(emptyMap(), line = line)))
}

private val NO_GLOBALS = GlobalSift(emptyMap(), emptyMap(), emptyMap())

/**
 * The refusal of this group's first operand, which the walk could not route: a child's name the end of options
 * kept from routing is misplaced, any other word an unknown subcommand. Null when the group was given no operand.
 */
private fun Command.unroutedOperand(
    sifted: Sifted,
    segment: List<String>,
    positions: List<Int>,
    qualifiedName: String,
    globalAcc: GlobalAccumulator?,
): Refusal? {
    val at = sifted.firstPositionalAt ?: return null
    val token = sifted.positionals.first()
    val separator = segment.indexOf(END_OF_OPTIONS).takeIf { it >= 0 }?.let { positions.getOrNull(it) ?: it }
    val routable = resolveSubcommand(token, globalAcc?.inferSubcommands ?: false) is SubcommandMatch.One
    val error = if (separator != null && separator < at.token && routable) {
        CliError.SubcommandAfterSeparator(token, qualifiedName)
    } else {
        CliError.UnknownSubcommand(qualifiedName, token, didYouMean(token, subcommandCandidates()))
    }
    return Refusal(error, at)
}

/** This command's flags and options from [sifted] into [sink], after the checks that span several of them. */
private fun Command.bindOptions(
    sifted: Sifted,
    sink: MutableMap<HolderSpec, Any?>,
    inferValues: Boolean,
    policy: BindPolicy = BindPolicy.Strict,
): CliError? {
    checkConstraints(sifted)?.let { return it }
    bindFlagsAndOptions(
        flags,
        options,
        sifted.flags,
        sifted.negations,
        sifted.options,
        sink,
        inferValues,
        policy,
    ).getOrElse { return it }
    // Ahead of resolveLastWins: a fold reads its winner's bound value, and an ENCLOSING set that overrode
    // the fold must then be free to reset it.
    resolveFolds(sifted, sink)
    resolveLastWins(sifted, sink)
    return null
}

/** What refuses [this] sift of a passed command's option words, its globals left to the command reached. */
internal fun Sifted.refusals(inferValues: Boolean): List<Refusal> =
    listOfNotNull(refusal, firstBadValue(this, NO_GLOBALS, inferValues))

/**
 * Binds the option words [sifted] read on this command's own segment, for a command the walk passed on the way to
 * a child: its options are the child's to read, an absent one its default. Its refusals are the caller's to order
 * against the rest of the line's. [skipRequired] leaves its requirements unchecked, for a `prio` child that runs
 * without them, as it runs without its parent's required globals.
 */
private fun Command.bindOwnOptions(
    sifted: Sifted,
    sink: MutableMap<HolderSpec, Any?>,
    globalAcc: GlobalAccumulator,
    skipRequired: Boolean,
): CliError? {
    val policy = if (skipRequired) BindPolicy.DeferRequired else BindPolicy.Strict
    bindOptions(sifted, sink, globalAcc.inferValues, policy)?.let { return it }
    return if (skipRequired) null else checkConditionalRequirements(sifted, globalAcc)
}

/**
 * The first option whose `.requiredWhen(flag)` condition holds while the option itself is absent, in
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
 * Give each `lastOf` handle the value of whichever member the user wrote last, or its absent reading
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
    // Positionals fill the slots this line has left to right, and a variadic must come last
    // (validatePositionals), so the operand at this spec's index among them exists exactly when the spec
    // received one. A gathered word counts even where the slot's trigger removed it, so a rule refuses both.
    is ArgumentSpec -> spec.gathersOn(sifted) ||
        activeArguments(sifted, overridden).indexOf(spec).let { it >= 0 && sifted.positionals.size > it }
}

/** Whether an operand reached [spec]'s place among all of [Command.arguments], or its gathered word did. */
private fun Command.operandAtDeclaredIndex(spec: ArgumentSpec, sifted: Sifted): Boolean =
    spec.gathersOn(sifted) || arguments.indexOf(spec).let { it >= 0 && sifted.positionals.size > it }

/**
 * The operand slots this line actually has: [Command.arguments] minus every slot whose `.absentWhen()`
 * trigger held, and minus the gathering slot once a dash word reached it. Shared with the completion planner,
 * so the slot it offers candidates for and the slot the bind fills are the same one; `chmod --reference=r <TAB>`
 * must not offer a MODE the parse has removed.
 *
 * A trigger that LOST a `lastWins` set reads as absent here ([overridden]), because a loser binds as if it
 * had never been written and the slot must survive with it.
 */
internal fun Command.activeArguments(
    sifted: Sifted,
    overridden: Set<HolderSpec> = lastWinsLosers(sifted),
): List<ArgumentSpec> =
    arguments.filterNot { spec -> spec.gathersOn(sifted) || removedByTrigger(spec, sifted, overridden) }

/**
 * Whether [spec]'s `.absentWhen()` trigger is on this line. An operand trigger is read by its declared index,
 * since reading it through [supplied] would ask this question again.
 */
internal fun Command.removedByTrigger(
    spec: ArgumentSpec,
    sifted: Sifted,
    overridden: Set<HolderSpec>,
): Boolean = when (val trigger = spec.absentWhen) {
    null -> false
    is ArgumentSpec -> trigger !in overridden && operandAtDeclaredIndex(trigger, sifted)
    else -> supplied(trigger, sifted, overridden)
}

/**
 * The fewest operands the variadic [spec] takes on this line: its declared minimum, or zero while its
 * `.optionalWhen()` input is given. Shared with the completion planner, so Tab and the bind agree on how many
 * operands the slots behind it get.
 */
internal fun Command.operandMin(
    spec: ArgumentSpec,
    sifted: Sifted,
    overridden: Set<HolderSpec> = lastWinsLosers(sifted),
): Int {
    val relaxed = spec.optionalWhen?.let { supplied(it, sifted, overridden) } == true
    return if (relaxed) 0 else (spec.cardinality as? Cardinality.Multiple)?.min ?: 1
}

/**
 * The most operands the variadic [spec] takes on this line: its declared maximum, or the smallest
 * `.atMostWhen()` cap whose input was given. Shared with the completion planner, so Tab offers no operand the
 * bind would call extra.
 */
internal fun Command.operandCap(
    spec: ArgumentSpec,
    sifted: Sifted,
    overridden: Set<HolderSpec> = lastWinsLosers(sifted),
): Int {
    val declared = (spec.cardinality as? Cardinality.Multiple)?.max ?: 1
    val held = spec.capsWhen.filter { (trigger, _) -> supplied(trigger, sifted, overridden) }.map { it.second }
    return (held + declared).min()
}

/**
 * The declared choice [raw] names when a prefix may resolve one, or [raw] itself when none does — an
 * unmatched value is left for the converter to reject, so the InvalidChoice list and its suggestion are
 * still what the user sees.
 *
 * Matching is in exact case unless [ignoreCase], as the converter's own is: the pool is lowered only to
 * compare, and the DECLARED spelling is what comes back, since that is what the converter canonicalises
 * against. A free function, taking [name] rather than a [ValueSpec] receiver, so klap's own
 * built-in choice values (`--color`, `completion <shell>`, `docs <format>`) share this implementation
 * without a spec of their own to be an extension on.
 *
 * With [entries], a prefix reaching several words of one entry resolves to the first of them, as gnulib's
 * argmatch reads `rm --interactive=n` as `never`; one reaching words of two entries is still ambiguous.
 */
internal fun resolveChoice(
    name: String,
    raw: String,
    choices: List<String>,
    ignoreCase: Boolean = false,
    entries: List<List<String>>? = null,
): Result<String, CliError> {
    val lowered = if (ignoreCase) choices.map { it.lowercase() } else choices
    val typed = if (ignoreCase) raw.lowercase() else raw
    return when (val match = resolveName(typed, lowered, infer = true)) {
        is NameMatch.Exact, NameMatch.None -> Result.Success(raw)
        is NameMatch.Prefix -> Result.Success(choices[lowered.indexOf(match.name)])
        is NameMatch.Ambiguous -> {
            val reached = match.candidates.map { choices[lowered.indexOf(it)] }
            if (entries != null && reached.nameOneEntry(entries)) {
                Result.Success(reached.first())
            } else {
                Result.Error(CliError.AmbiguousValue(name, raw, reached))
            }
        }
    }
}

/** Whether every one of these words is a spelling of one entry of [entries]. */
private fun List<String>.nameOneEntry(entries: List<List<String>>): Boolean =
    map { word -> entries.indexOfFirst { word in it } }.distinct().size == 1

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
        "--help".takeIf { offered.help },
        "--json".takeIf { offered.json },
        offered.versionLong?.let { "--$it" }?.takeIf { globalAcc?.rootVersion != null },
    )
    return (localOptions + localFlags + globalOptions + globalFlags).flatMap { spec -> spec.longs.map { "--$it" } } +
            negatable.flatMap { spec -> spec.negativeLongs.map { "--$it" } } +
            builtins
}

/**
 * Every long spelling this command answers to, dashes stripped: its own inputs and their negations, the flag-spelled
 * commands below it, the longs its flag-spelled family shares ([Cli.sharedLongs]), the globals, and the built-ins.
 * A GNU tool hands getopt_long the table of the command it runs, so an abbreviation resolves against this pool alone
 * and a sibling's long never makes it ambiguous. A `.spelledInFull()` input's longs are its [LongPool.whole].
 *
 * Hidden inputs take part, which is why this is not [longOptionCandidates]: hiding an input removes it from help,
 * not from the parser, and letting an abbreviation resolve past a hidden spelling would bind a different option
 * than the same line binds on a tree where nothing is hidden.
 *
 * Every scan that runs before the walk resolves a token against the pool of the command in scope where the token
 * stands ([com.fromwau.klap.builtinScan]), so no pass resolves what another calls ambiguous.
 */
internal fun Command.longMatchPool(globalAcc: GlobalAccumulator?): LongPool {
    // klap's injected built-ins come last so a reported possibility list leads with the names the author wrote.
    val globals = globalAcc?.optionSpecs.orEmpty() + globalAcc?.flagSpecs.orEmpty()
    val negatable = (flags + globalAcc?.flagSpecs.orEmpty()).filter { it.negatable }
    val flagCommands = flagSubcommands.flatMap { sub -> sub.flagSpellings.filter { it.startsWith("--") } }
    val names = (namedInputs + globals).flatMap { spec -> spec.longs } +
            negatable.flatMap { spec -> spec.negativeLongs } +
            flagCommands.map { it.removePrefix("--") } +
            globalAcc?.sharedLongs?.get(this).orEmpty() +
            globalAcc.offeredBuiltinLongs()
    // A segment read without abbreviation matches every name whole, so the scans that resolve a token against this
    // pool before the walk read it as the command's own sift will.
    val whole = if (globalAcc?.inferNames == false) {
        names.toSet()
    } else {
        (namedInputs + globals)
            .filter { it.spelledInFull }
            .flatMapTo(mutableSetOf()) { spec -> spec.longs + ((spec as? FlagSpec)?.negativeLongs).orEmpty() }
    }
    return LongPool(names, whole)
}

/**
 * The built-in long spellings this tree offers, dashes stripped. Shared by the pool that resolves a token
 * and by the sift that binds one, so the two cannot disagree about whether a name is klap's own.
 */
internal fun GlobalAccumulator?.offeredBuiltinLongs(): List<String> = offeredBuiltins().map { it.name }

/** The built-in longs this tree offers, with whether each takes the next word. */
internal fun GlobalAccumulator?.offeredBuiltins(): List<BuiltinLong> = builtinLongTable(
    this?.builtins ?: Builtins.DEFAULT,
    versioned = this?.rootVersion != null,
    metaOptions = this?.metaOptions ?: false,
)

/**
 * Candidate names for did-you-mean on an unknown subcommand: each visible child's name plus its aliases.
 * Hidden subcommands are excluded so a typo suggestion never reveals a hidden command's name.
 */
internal fun Command.subcommandCandidates(): List<String> =
    subcommands.filterNot { it.hidden }.flatMap { it.words }

/**
 * A cluster char resolved to the spec it names. [globals] is non-null exactly when the char named a global
 * rather than one of this command's own inputs, which is what decides where the occurrence is recorded.
 */
internal class ClusterHit<T>(val spec: T, val globals: GlobalAccumulator?)

/** This command's own [local] match if there is one, else the same lookup against the globals. */
private fun <T> GlobalAccumulator?.clusterHit(
    local: T?,
    inGlobals: GlobalAccumulator.() -> T?,
): ClusterHit<T>? = when {
    local != null -> ClusterHit(local, null)
    this != null -> inGlobals()?.let { ClusterHit(it, this) }
    else -> null
}

/** What the character at one index of a short cluster reads as; every walk over a cluster asks this. */
internal sealed interface ShortRead {
    /** A run of this command's number input, which binds [value] whole and spans [width] characters. */
    class NumberRun(val value: String, val width: Int) : ShortRead

    /** A suffix letter of the number run earlier in the cluster, already part of that run's value. */
    data object NumberSuffix : ShortRead

    /** klap's help or version short, which binds nothing here. */
    data object Builtin : ShortRead

    /** A flag's short, [on] false for its negative short. */
    class Flag(val hit: ClusterHit<FlagSpec>, val on: Boolean) : ShortRead

    /** A value-taking option's short, which takes the rest of the cluster or the next word. */
    class Option(val hit: ClusterHit<OptionSpec>) : ShortRead

    /** Nothing this command or the globals declare, or a value-taking short after a run's suffix letters. */
    data object Unresolved : ShortRead
}

/** [firstWord] says whether [chars] is its command's first word, where a first-word-only number may stand. */
internal fun Command.readShort(
    chars: String,
    at: Int,
    globalAcc: GlobalAccumulator?,
    firstWord: Boolean,
): ShortRead {
    numberRunAt(chars, at, globalAcc, firstWord)?.let { return it }
    val afterSuffixRun = followsSuffixRun(chars, at, globalAcc, firstWord)
    if (afterSuffixRun && chars[at] in numberSuffixes) return ShortRead.NumberSuffix
    if (globalAcc.isBuiltinShort(chars[at])) return ShortRead.Builtin
    val ch = chars[at].toString()
    globalAcc.clusterHit(findFlag("-$ch")) { flagSpecs.findFlag("-$ch") }
        ?.let { return ShortRead.Flag(it, on = true) }
    globalAcc.clusterHit(findNegatedShort(ch)) { flagSpecs.findNegatedShort(ch) }
        ?.let { return ShortRead.Flag(it, on = false) }
    globalAcc.clusterHit(findOption(null, ch)) { optionSpecs.findOption(null, ch) }
        ?.let { return if (afterSuffixRun) ShortRead.Unresolved else ShortRead.Option(it) }
    return ShortRead.Unresolved
}

/** How many characters [read] spans in its cluster. */
internal val ShortRead.width: Int get() = if (this is ShortRead.NumberRun) width else 1

/**
 * A `--name` or `--name=value` token split into the parts a long-option walk needs: [typed] is the name as
 * written, before an abbreviation resolves it, and [inlineValue] the attached value if there is one.
 */
private data class LongToken(val typed: String, val inlineValue: String?)

private fun longToken(token: String): LongToken {
    val body = token.removePrefix("--")
    val eq = body.indexOf('=')
    return LongToken(if (eq >= 0) body.take(eq) else body, if (eq >= 0) body.drop(eq + 1) else null)
}

/**
 * What an error names for a long [token], as getopt_long names it: the long it resolved to (`--al=x` is "option
 * '--alpha' doesn't allow an argument"), or the whole word as typed when it resolved to none (`--zeta=1`).
 */
private fun NameMatch.named(token: String): String = when (this) {
    is NameMatch.Exact -> "--$name"
    is NameMatch.Prefix -> "--$name"
    is NameMatch.Ambiguous, NameMatch.None -> token
}

/** The did-you-mean for an unknown long named [typed]; none for the empty name, which every spelling is near. */
private fun Command.suggestLong(typed: String, globalAcc: GlobalAccumulator?): String? =
    typed.takeIf { it.isNotEmpty() }?.let { didYouMean("--$it", longOptionCandidates(globalAcc)) }

/**
 * The value an option occurrence takes, and whether taking it consumed the token after it. [missing] is an
 * occurrence with no value where one is required; a null [value] that is not [missing] is a bare occurrence of
 * an `.optionalValue()` option, which names no value.
 */
private class TakenValue(val value: String?, val consumedNext: Boolean, val missing: Boolean = false)

/**
 * Resolve what this occurrence's value is and how far the walk therefore advances. The two are one
 * decision: advancing out of step with where the value came from is how a walk silently eats or re-reads a
 * token.
 *
 * [attached] is the `=value` of a long token or the rest of a short cluster. An optional-value option never
 * reaches for [next]: it cannot tell its own value from an operand, so a bare occurrence takes what it
 * declared, as getopt_long's optional argument does; a `.gluedValue()` one never does either, and a bare
 * occurrence of it is [TakenValue.missing]. [next] is consulted only when none of these applies, and takes the
 * next token whatever it is, `--` included, as getopt does.
 */
private fun OptionSpec?.valueFrom(attached: String?, next: () -> String?): TakenValue {
    val declared = attached ?: this?.bareValue
    if (declared != null) return TakenValue(declared, consumedNext = false)
    if (this?.bareMarks == true) return TakenValue(null, consumedNext = false)
    if (this?.gluedValue == true) return TakenValue(null, consumedNext = false, missing = true)
    val taken = next()
    return TakenValue(taken, consumedNext = taken != null, missing = taken == null)
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
 * Without it each token's position is its index in [segment], which orders nothing against the pre-strip, so
 * every caller that binds globals passes it, completion included.
 *
 * [leadsSegment] is false for a trial read of words further into the command's segment, whose first word is then
 * not the command's first, where a first-word-only number may stand.
 */
internal fun Command.sift(
    segment: List<String>,
    globalAcc: GlobalAccumulator? = null,
    positions: List<Int> = emptyList(),
    leadsSegment: Boolean = true,
): Sifted = SegmentSift(this, segment, globalAcc, positions, leadsSegment).run()

/** One walk of [cmd]'s [segment], tallying what [sift] returns as it goes. */
private class SegmentSift(
    private val cmd: Command,
    private val segment: List<String>,
    private val globalAcc: GlobalAccumulator?,
    private val positions: List<Int>,
    private val leadsSegment: Boolean,
) {
    private val flagCounts = mutableMapOf<FlagSpec, Int>()
    private val negations = mutableMapOf<FlagSpec, Boolean>()
    private val optionValues = mutableMapOf<OptionSpec, MutableList<String?>>()
    private val positionals = mutableListOf<String>()
    private var firstPositionalAt: ClusterPosition? = null
    private val dashLedAdmitted = mutableSetOf<Int>()
    private val gathered = mutableListOf<String>()
    private var error: CliError? = null
    private var errorAt: ClusterPosition? = null
    private val flagPositions = mutableMapOf<FlagSpec, ClusterPosition>()
    private val optionPositions = mutableMapOf<OptionSpec, ClusterPosition>()
    private val optionValuePositions = mutableMapOf<OptionSpec, MutableList<ClusterPosition>>()
    private val flagHits = mutableListOf<FlagHit>()

    // Built only once a long token actually needs it: a segment of operands and short clusters must not pay for
    // a list it never reads.
    private val longPool by lazy(LazyThreadSafetyMode.NONE) { cmd.longMatchPool(globalAcc) }

    // Read once per walk; the properties behind them walk the specs on every read.
    private val dashLedSlot = cmd.hasDashLedSlot
    private val gather = cmd.gatheringSlot?.gather
    private val endAt = cmd.optionsEndAt

    private var optionsEnded = false

    // `--` makes every later token literal, so the refusal after the end of options must not reach past one.
    private var endedBySeparator = false

    fun run(): Sifted {
        var i = 0
        while (i < segment.size) i += readToken(i)
        return Sifted(
            flagCounts,
            negations,
            optionValues,
            positionals,
            error,
            flagPositions,
            optionPositions,
            dashLedAdmitted,
            errorAt,
            optionValuePositions,
            flagHits,
            gathered,
            firstPositionalAt,
        )
    }

    /** Reads the token at [i] and returns how many tokens it took, a value in the next one included. */
    private fun readToken(i: Int): Int {
        val token = segment[i]
        // This token's place in the ORIGINAL argv, which is what every position recorded below is
        // measured in; a segment the caller did not map falls back to its own index.
        val tokenIndex = positions.getOrNull(i) ?: i
        val tokenAt = ClusterPosition(tokenIndex)
        val firstWord = i == 0 && leadsSegment
        return when {
            optionsEnded -> readAfterOptionsEnd(token, tokenAt, firstWord)

            token == END_OF_OPTIONS -> {
                optionsEnded = true
                endedBySeparator = true
                1
            }

            !token.isFlagLike() -> readOperand(token, tokenAt)
            token.startsWith("--") -> readLong(token, i, tokenAt)
            else -> readShortCluster(token, i, tokenIndex, firstWord)
        }
    }

    // A lambda, not a value: only the first call's build() actually executes, so didYouMean's edit-distance
    // scan runs at most once even though the walk keeps going after an error.
    private fun record(at: ClusterPosition, build: () -> CliError) {
        if (error == null) {
            error = build()
            errorAt = at
        }
    }

    private fun addValue(opt: OptionSpec, value: String?, at: ClusterPosition) {
        if (opt in optionValues && globalAcc.refusesRepeat(opt)) record(at) { CliError.RepeatedOption(opt.name) }
        optionValues.getOrPut(opt) { mutableListOf() } += value
        optionValuePositions.getOrPut(opt) { mutableListOf() } += at
        optionPositions[opt] = at
    }

    private fun addPositional(token: String, at: ClusterPosition) {
        if (positionals.isEmpty()) firstPositionalAt = at
        positionals += token
    }

    private fun hit(flag: FlagSpec, polarity: Boolean, at: ClusterPosition) {
        if (flag in flagCounts && globalAcc.refusesRepeat(flag)) record(at) { CliError.RepeatedOption(flag.name) }
        flagCounts[flag] = (flagCounts[flag] ?: 0) + 1
        flagPositions[flag] = at
        flagHits += FlagHit(flag, polarity, at)
        if (flag.negatable) negations[flag] = polarity
    }

    /** A token past the end of options: an operand, unless it is a cluster that would lose a global. */
    private fun readAfterOptionsEnd(token: String, tokenAt: ClusterPosition, firstWord: Boolean): Int {
        // Refused rather than kept: neither reading survives here (see MixedClusterAfterOperands),
        // and keeping it whole is the one that loses the global without saying so. Gated on the
        // cluster resolving in FULL, so a tail word that merely happens to carry a global's letter
        // (`tar -cvf x`) stays the operand it is.
        val chars = token.removePrefix("-")
        val mixedGlobal =
            if (endedBySeparator || !token.isFlagLike() || token.startsWith("--")) null
            else if (!cmd.shortClusterResolvesInFull(chars, globalAcc, firstWord)) null
            else globalAcc?.let { cmd.mixedGlobalShort(chars, it, firstWord) }
        if (mixedGlobal != null) {
            record(tokenAt) { CliError.MixedClusterAfterOperands(token, mixedGlobal) }
        } else {
            addPositional(token, tokenAt)
        }
        return 1
    }

    private fun readOperand(token: String, tokenAt: ClusterPosition): Int {
        addPositional(token, tokenAt)
        // klap's default is getopt's permutation, which keeps looking past an operand; a command that opted
        // out (getopt's leading `+`, POSIXLY_CORRECT, or ssh's restart after its destination) ends them here.
        if (endAt != null && positionals.size > endAt) optionsEnded = true
        return 1
    }

    /** A `--name` or `--name=value` token at [i]: a flag, its negation, or an option and its value. */
    private fun readLong(token: String, i: Int, tokenAt: ClusterPosition): Int {
        val (typed, inlineValue) = longToken(token)
        // Resolved against ONE pool so an abbreviation reaching two spellings is reported as
        // ambiguous rather than binding whichever lookup ran first; see [longMatchPool].
        val resolved = resolveLong(typed, longPool, globalAcc?.inferNames ?: false)
        val named = resolved.named(token)
        val long = when (resolved) {
            is NameMatch.Exact -> resolved.name
            is NameMatch.Prefix -> resolved.name
            else -> typed
        }
        // An exact spelling would have resolved, so nothing below can match once this is ambiguous.
        val flag = cmd.findFlag("--$long")
        val negated = if (flag == null) cmd.findNegatedFlag(long) else null
        return when {
            resolved is NameMatch.Ambiguous -> {
                record(tokenAt) { CliError.AmbiguousOption(named, resolved.candidates.map { "--$it" }) }
                1
            }

            flag != null -> {
                // hit() still runs, matching siftGlobals: the flag WAS present, and a caller that
                // ignores the error should see it. Invisible at parse time, where bind raises first.
                if (inlineValue != null) {
                    record(tokenAt) {
                        CliError.FlagTakesNoValue(
                            named,
                            if (flag.negatable) flag.negativeLongs.firstOrNull() else null,
                        )
                    }
                }
                hit(flag, true, tokenAt)
                1
            }

            negated != null -> {
                if (inlineValue != null) record(tokenAt) { CliError.FlagTakesNoValue(named) }
                hit(negated, false, tokenAt)
                1
            }

            else -> readLongOption(long, typed, named, inlineValue, i, tokenAt)
        }
    }

    /** A long that names no flag: an option of [cmd] or the globals, klap's own, or unknown. */
    private fun readLongOption(
        long: String,
        typed: String,
        named: String,
        inlineValue: String?,
        i: Int,
        tokenAt: ClusterPosition,
    ): Int {
        val opt = cmd.findOption(long, null)
        val taken = opt.valueFrom(inlineValue) {
            segment.getOrNull(i + 1)
        }
        return when {
            // klap's own, resolved by the built-in ladder rather than bound here. In the
            // pool since it is a legal spelling on every command, so without this the
            // binder would call a name its own pool accepts unknown.
            opt == null && long in globalAcc.offeredBuiltinLongs() -> 1

            opt == null -> {
                record(tokenAt) { CliError.UnknownOption(named, cmd.suggestLong(typed, globalAcc)) }
                // Skipped, never demoted to a positional: the tokens after it still fill
                // their own slots, but the unknown option itself must not occupy one.
                1
            }

            taken.missing -> {
                record(tokenAt) { CliError.MissingOptionValue(named) }
                1
            }

            else -> {
                addValue(opt, taken.value, tokenAt)
                if (taken.consumedNext) 2 else 1
            }
        }
    }

    /**
     * A single-dash token at [i]: a gathered or dash-led operand when its letters do not all resolve, else a
     * short cluster.
     */
    private fun readShortCluster(token: String, i: Int, tokenIndex: Int, firstWord: Boolean): Int {
        val chars = token.removePrefix("-")
        val tokenAt = ClusterPosition(tokenIndex)
        // Decided before the walk, because the walk records flag hits as it goes and a token that
        // turns out to be an operand would have to unwind them. A `--` token cannot arrive here at
        // all, the arm above claims it, so long-option typos keep their did-you-mean.
        val resolvesInFull = cmd.shortClusterResolvesInFull(chars, globalAcc, firstWord)
        if (gather != null && !resolvesInFull && gather.accepts(token)) {
            gathered += token
            return 1
        }
        if (dashLedSlot && !resolvesInFull) {
            dashLedAdmitted += positionals.size
            // It is an operand, so it ends options where the command opted out of permutation, exactly as
            // the not-flag-like route does; which operand ends them cannot depend on its spelling.
            return readOperand(token, tokenAt)
        }
        if (cmd.refusesCluster(chars, globalAcc, firstWord)) {
            record(tokenAt) { CliError.UnknownOption(token) }
            return 1
        }
        return readClusterChars(token, chars, i, tokenIndex, firstWord)
    }

    /**
     * Short cluster: each char is a flag until one names an option, which takes the rest (`-p8080`)
     * or the next token. A char that is not local is tried against the globals ([globalAcc]) so a
     * mixed cluster like `-fv` binds the global too; only a char that is neither is recorded.
     */
    private fun readClusterChars(token: String, chars: String, i: Int, tokenIndex: Int, firstWord: Boolean): Int {
        var j = 0
        while (j < chars.length) {
            // One position per character, shared by the local record and the global top-up below, which
            // must agree about where this character sat.
            val at = ClusterPosition(tokenIndex, j)
            val read = cmd.readShort(chars, j, globalAcc, firstWord)
            when (read) {
                is ShortRead.NumberRun -> addValue(cmd.numberInput!!, read.value, at)

                ShortRead.NumberSuffix -> Unit

                // Klap's own, so it binds nothing here; the help or version ladder answers it. An inline
                // `=value` on it is the same usage error a declared boolean short reports.
                ShortRead.Builtin -> if (chars.getOrNull(j + 1) == '=') record(at) {
                    val offered = globalAcc?.builtins ?: Builtins.DEFAULT
                    val long = if (globalAcc.isHelpShort(chars[j])) "help" else offered.versionLong
                    CliError.FlagTakesNoValue(long?.let { "--$it" } ?: "-${chars[j]}", null)
                }

                is ShortRead.Flag -> {
                    val globals = read.hit.globals
                    if (globals != null) globals.hitFlag(read.hit.spec, at, on = read.on)
                    else hit(read.hit.spec, read.on, at)
                }

                is ShortRead.Option -> return readClusterOption(read.hit, chars, j, i, at)

                // No suggestion here: a single-letter short option is too noisy to edit-distance against.
                ShortRead.Unresolved -> {
                    record(at) { cmd.clusterCharError(token, chars, j, globalAcc) }
                    return 1
                }
            }
            j += read.width
        }
        return 1
    }

    /** The option short at [j] of the cluster, taking the rest of it or the token after [i] as its value. */
    private fun readClusterOption(
        hit: ClusterHit<OptionSpec>,
        chars: String,
        j: Int,
        i: Int,
        at: ClusterPosition,
    ): Int {
        val opt = hit.spec
        val attached = chars.substring(j + 1).ifEmpty { null }
        val taken = opt.valueFrom(attached) {
            segment.getOrNull(i + 1)
        }
        if (taken.missing) {
            record(at) { CliError.MissingOptionValue("-${chars[j]}") }
            return 1
        }
        val globals = hit.globals
        if (globals != null) globals.addOptionValue(opt, taken.value, at)
        else addValue(opt, taken.value, at)
        return if (taken.consumedNext) 2 else 1
    }
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
 * lookup lets a negative short (`-P` in `.negatable(short = 'P')`) be blamed by name instead of falling through
 * to a phantom [CliError.UnknownOption].
 */
private fun List<FlagSpec>.findFlagOrNegatedShort(token: String): FlagSpec? =
    findFlag(token) ?: findNegatedShort(token.removePrefix("-"))

internal fun List<OptionSpec>.findOption(long: String?, short: String?): OptionSpec? =
    firstOrNull { (long != null && long in it.longs) || (short != null && short in it.shorts) }

/**
 * The first character of [chars], a short cluster without its dash, that the sift would bind to a global or that
 * is klap's help or version short, dash included, when the cluster also holds a character of this command's own;
 * null otherwise. Read as [sift] walks it, so a letter past a local option is that option's value and no global.
 */
private fun Command.mixedGlobalShort(
    chars: String,
    globalAcc: GlobalAccumulator,
    firstWord: Boolean,
): String? {
    var global: String? = null
    var local = false
    var j = 0
    while (j < chars.length) {
        val read = readShort(chars, j, globalAcc, firstWord)
        when (read) {
            ShortRead.Unresolved -> Unit
            is ShortRead.NumberRun, ShortRead.NumberSuffix -> local = true
            ShortRead.Builtin -> global = global ?: "-${chars[j]}"
            is ShortRead.Flag -> if (read.hit.globals != null) global = global ?: "-${chars[j]}" else local = true
            is ShortRead.Option -> {
                if (read.hit.globals != null) global = global ?: "-${chars[j]}" else local = true
                break
            }
        }
        j += read.width
    }
    return global?.takeIf { local }
}

private fun Command.findFlag(token: String): FlagSpec? = flags.findFlag(token)
private fun Command.findNegatedFlag(long: String): FlagSpec? = flags.findNegatedFlag(long)
private fun Command.findNegatedShort(short: String): FlagSpec? = flags.findNegatedShort(short)
private fun Command.findOption(long: String?, short: String?): OptionSpec? =
    options.findOption(long, short)

/**
 * Whether [ShortOptions.Alone] refuses [chars], a one-dash word without its dash, as one unknown word: it holds
 * more than one short, and is neither an `.optionalValue()` or `.gluedValue()` short with its value glued (`-O3`)
 * nor a run of the number input (`-25`).
 */
internal fun Command.refusesCluster(
    chars: String,
    globalAcc: GlobalAccumulator?,
    firstWord: Boolean,
): Boolean {
    if (globalAcc?.shortOptions != ShortOptions.Alone || chars.length < 2) return false
    val first = chars.take(1)
    val option = globalAcc.clusterHit(findOption(null, first)) { optionSpecs.findOption(null, first) }?.spec
    return option?.valueAttachedOnly != true && numberRunAt(chars, 0, globalAcc, firstWord)?.width != chars.length
}

/**
 * Whether [token] names only globals and klap's own names, the ones a line still reads past the operand that
 * ends this command's options ([optionsEndAt]); there any other dash word is an operand.
 */
internal fun Command.namesOnlyGlobals(
    token: String,
    globalAcc: GlobalAccumulator,
    firstWord: Boolean,
): Boolean {
    if (token.startsWith("--")) {
        val typed = token.removePrefix("--").substringBefore('=')
        val long = when (val resolved = resolveLong(typed, longMatchPool(globalAcc), globalAcc.inferNames)) {
            is NameMatch.Exact -> resolved.name
            is NameMatch.Prefix -> resolved.name
            is NameMatch.Ambiguous, NameMatch.None -> return false
        }
        val globalLongs = globalAcc.optionSpecs.flatMap { it.longs } +
            globalAcc.flagSpecs.flatMap { it.longs + it.negativeLongs }
        return long in globalLongs || long in globalAcc.offeredBuiltinLongs()
    }
    val chars = token.removePrefix("-")
    var j = 0
    while (j < chars.length) {
        when (val read = readShort(chars, j, globalAcc, firstWord)) {
            ShortRead.Builtin -> j += 1
            is ShortRead.Flag -> if (read.hit.globals != null) j += 1 else return false
            // The rest of the cluster is the option's value.
            is ShortRead.Option -> return read.hit.globals != null
            is ShortRead.NumberRun, ShortRead.NumberSuffix, ShortRead.Unresolved -> return false
        }
    }
    return true
}

/** Whether every character of a short cluster resolves; see [firstUnresolvedShort]. */
internal fun Command.shortClusterResolvesInFull(
    chars: String,
    globalAcc: GlobalAccumulator?,
    firstWord: Boolean,
): Boolean = firstUnresolvedShort(chars, globalAcc, firstWord) == null

/**
 * The leftmost character of [chars] naming nothing this command or [globalAcc] declares, dash included, or
 * null when the cluster resolves in full. A value-taking option ends the read, since the rest is its value
 * (`-p8080`). Records nothing, so [sift] can ask it before a walk that would otherwise have to be unwound.
 */
private fun Command.firstUnresolvedShort(
    chars: String,
    globalAcc: GlobalAccumulator?,
    firstWord: Boolean,
): String? {
    if (refusesCluster(chars, globalAcc, firstWord)) return "-$chars"
    var j = 0
    while (j < chars.length) {
        val read = readShort(chars, j, globalAcc, firstWord)
        when (read) {
            is ShortRead.NumberRun, ShortRead.NumberSuffix, ShortRead.Builtin, is ShortRead.Flag -> Unit
            // An option takes the rest of the cluster as its value, so nothing after it has to resolve.
            is ShortRead.Option -> return null
            ShortRead.Unresolved -> return "-${chars[j]}"
        }
        j += read.width
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
internal fun Command.namesHelpShort(
    token: String,
    globalAcc: GlobalAccumulator?,
    firstWord: Boolean,
): Boolean = namesBuiltinShort(token, globalAcc, firstWord) { globalAcc.isHelpShort(it) }

/** Whether [token] asks for the version through the short a tool gave it; read as [namesHelpShort] reads `-h`. */
internal fun Command.namesVersionShort(
    token: String,
    globalAcc: GlobalAccumulator?,
    firstWord: Boolean,
): Boolean = namesBuiltinShort(token, globalAcc, firstWord) { globalAcc.isVersionShort(it) }

private fun Command.namesBuiltinShort(
    token: String,
    globalAcc: GlobalAccumulator?,
    firstWord: Boolean,
    asked: (Char) -> Boolean,
): Boolean {
    if (!token.isFlagLike() || token.startsWith("--")) return false
    val chars = token.removePrefix("-")
    if (refusesCluster(chars, globalAcc, firstWord)) return false
    if (hasDashLedSlot && !shortClusterResolvesInFull(chars, globalAcc, firstWord)) return false
    var j = 0
    while (j < chars.length) {
        val read = readShort(chars, j, globalAcc, firstWord)
        when (read) {
            // An `=value` on a boolean built-in is a usage error, raised where the sift walks the cluster.
            ShortRead.Builtin -> if (asked(chars[j])) return chars.getOrNull(j + 1) != '='
            is ShortRead.NumberRun, ShortRead.NumberSuffix, is ShortRead.Flag -> Unit
            is ShortRead.Option, ShortRead.Unresolved -> return false
        }
        j += read.width
    }
    return false
}

/** Whether any token [this] scan may act on names klap's help short; see [Command.namesHelpShort]. */
internal fun ArgvScan.namesHelpShort(cmd: Command, globalAcc: GlobalAccumulator?): Boolean =
    firstOpenIndexed { i, token -> cmd.namesHelpShort(token, globalAcc, firstWord = i == 0) } != null

/** Whether any token [this] scan may act on names the version short; see [Command.namesVersionShort]. */
internal fun ArgvScan.namesVersionShort(cmd: Command, globalAcc: GlobalAccumulator?): Boolean =
    firstOpenIndexed { i, token -> cmd.namesVersionShort(token, globalAcc, firstWord = i == 0) } != null

/**
 * The error for an unrecognized char at `chars[j]` inside a short cluster (the chars before it, `chars[0
 * until j]`, were all consumed by the walk — a flag, a negative short, klap's help short, or a digit run
 * the number input claimed — since an option match would have ended the loop already). A `=` right
 * after a flag char is the short form of `--flag=value`, so it reports the same no-value error the long
 * form does. It names the preceding flag rather than the phantom `-=` a bare `"-$ch"` would fabricate;
 * both callers back [findFlag] with [findFlagOrNegatedShort], so a preceding NEGATIVE short is named too.
 * That lookup is per-character, so where a run was consumed the char before the `=` is its last DIGIT, and
 * `-12=x` beside a flag `-2` blames `-2` — a spelling the run swallowed. Left as it is: the `=` is a usage
 * error under either reading.
 * Any other char is named alone, as getopt_long names it, with [token] as its cluster: the `-` in `-f-y` is
 * `--` in `-f-y`. Shared by [Command.clusterCharError] (the per-command
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

        // The letter alone, as getopt_long names it (`-a-` is "invalid option -- '-'"). The cluster only earns a
        // mention when it is not the whole story: `-1m` would otherwise report a `-1` the user cannot find.
        else -> CliError.UnknownOption("-$ch", cluster = token.takeIf { it != "-$ch" })
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

/** [sift] seeded as this tree's accumulator, the one form parse and completion both read the globals through. */
internal fun Cli.globalsFrom(sift: GlobalSift): GlobalAccumulator = sift.accumulator(
    globalSpecs,
    version,
    builtins,
    metaOptions,
    abbreviation,
    repeatedOptions,
    shortOptions,
    sharedLongs,
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
    abbreviation: Abbreviation = Abbreviation.None,
    repeatedOptions: RepeatedOptions = RepeatedOptions.LastWins,
    shortOptions: ShortOptions = ShortOptions.Clustered,
    sharedLongs: Map<Command, List<String>> = emptyMap(),
): GlobalAccumulator = GlobalAccumulator(
    flagSpecs = globalSpecs.filterIsInstance<FlagSpec>(),
    optionSpecs = globalSpecs.filterIsInstance<OptionSpec>(),
    rootVersion = rootVersion,
    builtins = builtins,
    metaOptions = metaOptions,
    abbreviation = abbreviation,
    repeatedOptions = repeatedOptions,
    shortOptions = shortOptions,
    sharedLongs = sharedLongs,
    flags = flags.toMutableMap(),
    negations = negations.toMutableMap(),
    options = options.mapValues { it.value.toMutableList() }.toMutableMap(),
    flagPositions = flagPositions.mapValues { it.value.toMutableList() }.toMutableMap(),
    flagHits = flagHits.toMutableList(),
)

/**
 * This root's spec lists and pools in a [GlobalAccumulator] holding no occurrences: what a pass that must
 * READ the global surface before [siftGlobals] has produced one asks its questions through, so its reading
 * of a token is identical to the one [sift] will give.
 */
internal fun Cli.globalLookup(): GlobalAccumulator = globalsFrom(NO_GLOBALS)

/**
 * Walk [scan]'s tokens before the end-of-options marker, pull out those naming a global (long form, or a
 * short cluster whose chars are ALL globals, e.g. `-vc`), and return the cleaned argv for the normal
 * subcommand walk alongside the sifted occurrences. A short cluster that mixes a global with a non-global
 * char is left whole for the resolved command's global-aware [sift] to split (so `build -fv` binds both,
 * in any order); it does not route, so the command that splits it is whatever the walk had reached.
 * Unlike [sift], an unrecognized dash token is left in place rather than erroring: it may belong to
 * whatever command the walk resolves to, and only that command's own [sift] is allowed to reject it.
 *
 * A long resolves against [scan]'s pool where it stands, the pool of the command in scope there, so a `--sor` that
 * could mean a global `--sort` or that command's `--sort-by` is left whole for its sift to report as ambiguous,
 * instead of being bound here as the only spelling this pass binds.
 *
 * Position-independent stops at a value-taking option's argument slot, the one place a global has no claim
 * on ([ArgvScan.valueSlots]): `sub -e --tag f` gives `-e` the literal `--tag`, and the global keeps its
 * default. That is why this takes a [scan] rather than a bare list — the slots and the argv-scale positions
 * they are keyed on travel together, and neither is derivable from the tokens this pass can see.
 */
internal fun List<HolderSpec>.siftGlobals(
    scan: ArgvScan,
    shortOptions: ShortOptions,
): GlobalPreStrip {
    val argv = scan.tokens
    val positions = scan.positions
    val flagSpecs = filterIsInstance<FlagSpec>()
    val optionSpecs = filterIsInstance<OptionSpec>()
    val flagCounts = mutableMapOf<FlagSpec, Int>()
    val negations = mutableMapOf<FlagSpec, Polarity>()
    val optionValues = mutableMapOf<OptionSpec, MutableList<Occurrence>>()
    val flagPositions = mutableMapOf<FlagSpec, MutableList<ClusterPosition>>()
    val cleaned = mutableListOf<String>()
    val keptPositions = mutableListOf<Int>()
    var error: CliError? = null
    var errorAt: ClusterPosition? = null

    fun record(at: Int, refusal: CliError) {
        if (error == null) {
            error = refusal
            errorAt = ClusterPosition(at)
        }
    }

    // The only way to append to `cleaned`: the parallel position list must never drift from it.
    fun keep(token: String, at: Int) {
        cleaned += token
        keptPositions += at
    }

    val flagHits = mutableListOf<FlagHit>()

    fun hit(flag: FlagSpec, polarity: Boolean, at: ClusterPosition) {
        flagCounts[flag] = (flagCounts[flag] ?: 0) + 1
        flagPositions.getOrPut(flag) { mutableListOf() } += at
        flagHits += FlagHit(flag, polarity, at)
        if (flag.negatable) negations.recordPolarity(flag, polarity, at)
    }

    val end = argv.endOfOptions(positions, scan.valueSlots)
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
                val (typed, inlineValue) = longToken(token)
                // An abbreviation resolving to anything but a global (an ambiguity, or a command's own long or a
                // built-in) leaves the token whole, exactly as an unrecognized one does, for the command's sift.
                val long = when (val resolved = resolveLong(typed, scan.poolAt(at), scan.infer)) {
                    is NameMatch.Exact -> resolved.name
                    is NameMatch.Prefix -> resolved.name
                    else -> typed
                }
                val flag = flagSpecs.findFlag("--$long")
                val negated = if (flag == null) flagSpecs.findNegatedFlag(long) else null
                val opt = if (flag == null && negated == null) optionSpecs.findOption(long, null) else null
                when {
                    flag != null -> {
                        if (inlineValue != null) {
                            record(
                                at,
                                CliError.FlagTakesNoValue(
                                    "--$long",
                                    if (flag.negatable) flag.negativeLongs.firstOrNull() else null,
                                ),
                            )
                        }
                        hit(flag, true, ClusterPosition(at))
                        i += 1
                    }

                    negated != null -> {
                        if (inlineValue != null) record(at, CliError.FlagTakesNoValue("--$long"))
                        hit(negated, false, ClusterPosition(at))
                        i += 1
                    }

                    opt != null -> {
                        // `head` stops at the end-of-options marker, so an existing next token is a value.
                        val taken = opt.valueFrom(inlineValue) { head.getOrNull(i + 1) }
                        if (taken.missing) {
                            // A recognized global with no value is a hard error: capture it so parse() reports
                            // "requires a value" instead of the command's own sift calling the leftover unknown.
                            record(at, CliError.MissingOptionValue("--$long"))
                            keep(token, at)
                            i += 1
                        } else {
                            val occurrence = Occurrence(taken.value, ClusterPosition(at))
                            optionValues.getOrPut(opt) { mutableListOf() } += occurrence
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
                var pendingOption: Triple<OptionSpec, String?, Int>? = null
                // The letter of a value-taking global left without its value.
                var pendingDangling: Char? = null
                var pendingEqError: CliError? = null
                // A word Alone refuses is left whole, for the command's own sift to refuse.
                var fullyGlobal = shortOptions == ShortOptions.Clustered || chars.length == 1 ||
                    optionSpecs.findOption(null, chars.take(1))?.valueAttachedOnly == true
                var advance = 1
                var j = 0
                while (fullyGlobal && j < chars.length) {
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
                            if (taken.missing) pendingDangling = chars[j] else {
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
                    pendingDangling?.let { letter ->
                        // A recognized value-taking global with no value is a hard error, same as the long form.
                        record(at, CliError.MissingOptionValue("-$letter"))
                        keep(token, at)
                    }
                    pendingEqError?.let { e ->
                        record(at, e)
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
        GlobalSift(flagCounts, negations, optionValues, error, errorAt, flagPositions, flagHits),
    )
}

/**
 * Global option/flag occurrences pulled out of argv, ahead of and independent from the subcommand walk.
 * [error] carries the first hard syntax error hit while sifting a recognized global (a dangling
 * value-taking option, or a `--flag=value` given to a boolean flag), which [parse] raises before the
 * command bind rather than letting the leftover token be misreported as an unknown option. [errorAt] is
 * where it stood.
 */
internal data class GlobalSift(
    val flags: Map<FlagSpec, Int>,
    val negations: Map<FlagSpec, Polarity>,
    val options: Map<OptionSpec, List<Occurrence>>,
    val error: CliError? = null,
    val errorAt: ClusterPosition? = null,
    // Where each flag was given, every occurrence, so a second one of a flag that refuses a repeat is placed.
    val flagPositions: Map<FlagSpec, List<ClusterPosition>> = emptyMap(),
    // Every flag occurrence in the order the passes met it, for [com.fromwau.klap.ValueScope.inOrder].
    val flagHits: List<FlagHit> = emptyList(),
) {
    /** [error] with where it stood. */
    val refusal: Refusal? get() = error?.let { e -> errorAt?.let { Refusal(e, it) } }
}

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
 * A null [position] means the caller tracked none; the later write then simply wins outright.
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
 * A null [position] means the caller tracked none, and such an occurrence sorts after every positioned one, so
 * the later pass's observation simply wins.
 */
internal class Occurrence(val value: String?, val position: ClusterPosition?)

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
    val abbreviation: Abbreviation = Abbreviation.None,
    val repeatedOptions: RepeatedOptions = RepeatedOptions.LastWins,
    val shortOptions: ShortOptions = ShortOptions.Clustered,
    // The longs a command's flag-spelled family shares with it ([Cli.sharedLongs]).
    val sharedLongs: Map<Command, List<String>> = emptyMap(),
    private val flags: MutableMap<FlagSpec, Int>,
    private val negations: MutableMap<FlagSpec, Polarity>,
    private val options: MutableMap<OptionSpec, MutableList<Occurrence>>,
    private val flagPositions: MutableMap<FlagSpec, MutableList<ClusterPosition>>,
    private val flagHits: MutableList<FlagHit> = mutableListOf(),
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
        position?.let { flagPositions.getOrPut(spec) { mutableListOf() } += it }
        flagHits += FlagHit(spec, on, position)
        if (spec.negatable) negations.recordPolarity(spec, on = on, at = position)
    }

    /**
     * This tally as [cmd] reads it, the occurrences shared: by the command's own [Command.ownReading] where it has
     * one, and below the root without a `rootOnly` version ([Builtins.versionRootOnly]), whose letter and long are
     * then free for the command's own inputs.
     */
    fun at(cmd: Command): GlobalAccumulator {
        val own = cmd.ownReading
        val versionless = cmd !is Cli && builtins.versionRootOnly
        if (own == null && !versionless) return this
        return GlobalAccumulator(
            flagSpecs,
            optionSpecs,
            rootVersion = rootVersion.takeUnless { versionless },
            if (versionless) builtins.copy(versionLong = null, versionShort = null) else builtins,
            metaOptions,
            own?.abbreviation ?: abbreviation,
            repeatedOptions,
            own?.shortOptions ?: shortOptions,
            sharedLongs,
            flags,
            negations,
            options,
            flagPositions,
            flagHits,
        )
    }

    /** Whether a long option name resolves by prefix here; an exact spelling always binds. */
    val inferNames: Boolean get() = abbreviation != Abbreviation.None

    /** Whether a declared choice value resolves by prefix here: [Abbreviation.Options] and [Abbreviation.All]. */
    val inferValues: Boolean get() = abbreviation.infersValues

    /** Whether a subcommand name resolves by prefix here; only [Abbreviation.All] reaches this far. */
    val inferSubcommands: Boolean get() = abbreviation == Abbreviation.All

    /**
     * Whether [spec] was GIVEN in its positive form, [Command.supplied]'s question asked of a global: a
     * negatable flag's `--no-x` is an opt-out, so it must not read as a selection of `x`.
     */
    fun supplied(spec: FlagSpec): Boolean =
        if (spec.negatable) negations[spec]?.on == true else (flags[spec] ?: 0) > 0

    /** Record an option occurrence; [position] orders it against the pass before, see [Occurrence]. */
    fun addOptionValue(spec: OptionSpec, value: String?, position: ClusterPosition?) {
        options.getOrPut(spec) { mutableListOf() } += Occurrence(value, position)
    }

    /** The merged occurrences, ready for [bindGlobals]. Segment syntax errors surface via [Sifted.error]. */
    fun toGlobalSift(): GlobalSift =
        GlobalSift(flags, negations, options, flagPositions = flagPositions, flagHits = flagHits)

    /** Whether the line has given the global [spec] in either of its forms. */
    fun holds(spec: NamedSpec): Boolean = when (spec) {
        is FlagSpec -> (flags[spec] ?: 0) > 0
        is OptionSpec -> options[spec]?.isNotEmpty() == true
    }

    /**
     * The earliest second occurrence of a global that refuses a repeat, or null. Read once the command's own
     * sift has topped this up, since a global in a mixed cluster (`-fq`) is only counted there.
     */
    fun repeatRefusal(): Refusal? {
        val flagRepeats = flagPositions.filterKeys { it.refusesRepeat(repeatedOptions) }.mapNotNull { (spec, at) ->
            at.sorted().getOrNull(1)?.let { Refusal(CliError.RepeatedOption(spec.name), it) }
        }
        val optionRepeats = options.filterKeys { it.refusesRepeat(repeatedOptions) }.mapNotNull { (spec, occurrences) ->
            occurrences.mapNotNull { it.position }.sorted().getOrNull(1)
                ?.let { Refusal(CliError.RepeatedOption(spec.name), it) }
        }
        return (flagRepeats + optionRepeats).minByOrNull { it.at }
    }
}
