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
import com.fromwau.klap.ConversionError
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
import com.fromwau.klap.internal.spec.constraintToken
import com.fromwau.klap.internal.spec.longs
import com.fromwau.klap.internal.spec.negativeLongs
import com.fromwau.klap.internal.spec.negativeShorts
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.internal.spec.token
import com.fromwau.klap.resolveSubcommand
import kotlin.coroutines.cancellation.CancellationException

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
 * (an unknown option, the default), a digit short it declares (`curl -4`), or its [Command.numericAlias]
 * (`head -5`). A genuinely negative operand is written after `--`, and an option VALUE needs no escape
 * at all.
 */
internal fun String.isFlagLike(): Boolean =
    startsWith("-") && this != END_OF_OPTIONS && this != "-"

/** The `-<digits>` shorthand [Command.numericAlias] claims, if [token] is one; the digits are its value. */
internal fun Command.numericAliasValue(
    token: String,
    globalAcc: GlobalAccumulator?,
): Pair<OptionSpec, String>? {
    val alias = numericAlias ?: return null
    val digits = token.removePrefix("-")
    if (digits.isEmpty() || !digits.all { it.isDigit() }) return null
    // POSIX.1 XBD 12.2 guideline 14: a token identifiable as an option, OR as a group of options behind
    // one '-', must be treated as one — so the alias may only claim a number that no complete cluster
    // reading covers. `-4` where `flag("-4")` is declared is that flag; `-40` where only `-4` is declared
    // is NOT a valid group (nothing declares `0`), so the alias takes it whole. A global's shorts count as
    // much as this command's own: a cluster mixing the two reaches this sift whole ([siftGlobals]).
    val shorts = shortsOf(namedInputs + globalAcc?.flagSpecs.orEmpty() + globalAcc?.optionSpecs.orEmpty())
    if (digits.all { it.toString() in shorts }) return null
    return alias to digits
}

/** The [NamedSpec.shorts] of every spec given, bare of their dash and deduplicated. */
internal fun shortsOf(specs: List<NamedSpec>): Set<String> = specs.flatMapTo(mutableSetOf()) { it.shorts }

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
            // whole token, so `-hv` at a group and `list -ah` at a leaf both report `-h`; a long `--opt`
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
                    val offending = if (long != null) firstToken else "-${firstToken[1]}"
                    val suggestion = long?.let { suggest(offending, longOptionCandidates(globalAcc)) }
                    Result.Error(CliError.UnknownOption(offending, suggestion))
                }
            }

            positionals.isNotEmpty() -> {
                val token = positionals.first()
                // A real subcommand that only reached here because `--` preceded it (routing had already
                // stopped) is misplaced, not unknown; say so instead of "unknown subcommand".
                val infer = globalAcc?.inferSubcommands ?: false
                if (ddIndex >= 0 && resolveSubcommand(token, infer) is SubcommandMatch.One) {
                    Result.Error(CliError.SubcommandAfterSeparator(token, name))
                } else {
                    Result.Error(
                        CliError.UnknownSubcommand(
                            name, token,
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
    resolveLastWins(sifted, sink)
    bindPositionals(sifted.positionals, sink, sifted, globalAcc?.inferNames ?: false)
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
 * Collapse each [ConstraintArity.LastWins] set to its single winner: the member written last on the command
 * line keeps what it bound, and every other member binds what it would have bound had the user not written
 * it at all, whatever [bindFlagsAndOptions] just wrote.
 *
 * "What it would have bound" is per kind rather than a single `false`: a plain flag misses to `false`, a
 * count flag to `0`, a negatable flag to its declared default, and an option to its `.default()` or null.
 * That keeps a loser indistinguishable from an absent input, which is the whole point of the rule: an
 * action reads the winner off its own handle and needs no precedence logic.
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
private fun HolderSpec.lastPosition(sifted: Sifted): Int? = when (this) {
    is FlagSpec -> sifted.flagPositions[this]
    is OptionSpec -> sifted.optionPositions[this]
    is ArgumentSpec -> null
}

/**
 * What this input binds when it is absent: what a loser must bind ([resolveLastWins]), and what an operand
 * slot removed by its `.absentWhen()` trigger binds ([bindPositionals]). Falling to null for an option
 * without a `.default()` is sound only because `validateLastWinsMembers` keeps `.required()` and
 * `.multiple()` options out of a set: their accessors are non-null, so a null here would NPE inside the
 * action.
 */
private fun HolderSpec.absentValue(): Any? = when {
    this is FlagSpec && isCount -> 0
    this is FlagSpec && negatable -> (cardinality as Cardinality.Default).value
    this is FlagSpec -> false
    else -> (cardinality as? Cardinality.Default)?.value
}

/**
 * The first violated cross-input constraint on this command, in declaration order, or null.
 *
 * Reads [sifted], never the bound values: an option with a `.default()` ALWAYS binds, so the bound map
 * cannot tell "the user gave it" from "the default filled it in", while the sift only records what was
 * actually on the line. Called from [bind] after [Sifted.error] and before any bind, so a mode conflict
 * outranks the `missing required option --file` a later bind would raise, matching GNU tar. Globals never
 * take part: a constraint's members are all this command's own specs (enforced at construction).
 */
private fun Command.checkConstraints(sifted: Sifted): CliError? {
    for (constraint in constraints) {
        // A last-wins set is an override rule: any number of members is legal, so it has nothing to check
        // here and is resolved after binding instead (see [resolveLastWins]).
        if (constraint.arity == ConstraintArity.LastWins) continue
        val given = constraint.members.filter { supplied(it, sifted) }
        when {
            given.size > 1 -> return CliError.MutuallyExclusive(given.map { it.constraintToken() })
            given.isEmpty() && constraint.arity == ConstraintArity.ExactlyOne ->
                return CliError.ExactlyOneRequired(constraint.members.map { it.constraintToken() })

            else -> Unit
        }
    }
    return null
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
    is OptionSpec -> sifted.options[spec]?.isNotEmpty() == true
    // Positionals fill left to right and a variadic must come last (validatePositionals), so the operand
    // at this spec's index exists exactly when the spec received one.
    is ArgumentSpec -> arguments.indexOf(spec).let { it >= 0 && sifted.positionals.size > it }
}

/**
 * Bind [flags]/[options] from their sifted occurrences: a flag misses to `false` unless negatable/count,
 * an option applies its default/null/error when absent. The single cardinality code path shared by a
 * leaf's own [bind] and [bindGlobals], so both bind flags/options the same way.
 *
 * When [policy] is [BindPolicy.DeferRequired], an under-satisfied Required or Multiple(min) option is NOT
 * an immediate error; the error it would raise is returned in the result list instead, so the caller can
 * decide later whether the absence actually matters. [bind] never defers (its own required options always
 * fail fast); only globals do, since a missing required global should not block a bare group that only
 * ends up showing help. [BindPolicy.Lenient] never errors nor defers: whatever cannot be satisfied is
 * simply left out of [sink] (see [completionValues]).
 */
private fun bindFlagsAndOptions(
    flags: List<FlagSpec>,
    options: List<OptionSpec>,
    flagHits: Map<FlagSpec, Int>,
    negations: Map<FlagSpec, Boolean>,
    optionValues: Map<OptionSpec, List<String>>,
    sink: MutableMap<HolderSpec, Any?>,
    inferValues: Boolean,
    policy: BindPolicy = BindPolicy.Strict,
): Result<List<CliError>, CliError> {
    bindFlags(flags, flagHits, negations, sink)
    return bindOptions(options, optionValues, sink, inferValues, policy)
}

/** Flags always bind: a count reads its occurrences, a negatable one its polarity, the rest presence. */
private fun bindFlags(
    flags: List<FlagSpec>,
    flagHits: Map<FlagSpec, Int>,
    negations: Map<FlagSpec, Boolean>,
    sink: MutableMap<HolderSpec, Any?>,
) {
    flags.forEach { spec ->
        val hits = flagHits[spec] ?: 0
        when {
            spec.isCount -> sink[spec] = hits
            spec.negatable -> sink[spec] =
                negations[spec] ?: (spec.cardinality as Cardinality.Default).value

            else -> sink[spec] = hits > 0
        }
    }
}

/**
 * Options are where [policy] earns its keep: a missing or unconvertible value is fatal under
 * [BindPolicy.Strict], collected under [BindPolicy.DeferRequired], and simply left unbound under
 * [BindPolicy.Lenient], which is what a half-typed completion line needs.
 */
private fun bindOptions(
    options: List<OptionSpec>,
    optionValues: Map<OptionSpec, List<String>>,
    sink: MutableMap<HolderSpec, Any?>,
    inferValues: Boolean,
    policy: BindPolicy,
): Result<List<CliError>, CliError> {
    val deferred = mutableListOf<CliError>()
    for (opt in options) {
        val raws = optionValues[opt].orEmpty()
        when (val c = opt.cardinality) {
            is Cardinality.Multiple -> {
                if (raws.size < c.min) {
                    val tooFew = CliError.TooFewOccurrences(opt.name, c.min, raws.size)
                    when (policy) {
                        BindPolicy.Strict -> return Result.Error(tooFew)
                        BindPolicy.DeferRequired -> deferred += tooFew
                        // Bind whatever IS on the line rather than nothing: a provider reading a
                        // half-filled multiple() wants the occurrences typed so far.
                        BindPolicy.Lenient -> Unit
                    }
                }
                if (raws.size >= c.min || policy == BindPolicy.Lenient) {
                    when (val converted = opt.convertOccurrences(raws, inferValues)) {
                        // Lenient leaves it unbound: one bad occurrence must not blank its siblings.
                        is Result.Error -> if (policy != BindPolicy.Lenient) return converted
                        is Result.Success -> sink[opt] = converted.value
                    }
                }
            }

            else -> {
                val raw = raws.lastOrNull()
                if (raw != null) {
                    when (val outcome = opt.convertOne(raw, inferValues)) {
                        // Lenient leaves it unbound: a wrong or half-typed value must not blank its siblings.
                        is Result.Error -> if (policy != BindPolicy.Lenient) return outcome
                        // A converter ERROR is handled above; only a converter that SUCCEEDS with null (e.g.
                        // `.map { it.toIntOrNull() }` on bad input) is "?: default"-substituted here, same as
                        // an absent option.
                        is Result.Success -> sink[opt] =
                            if (c is Cardinality.Default) outcome.value ?: c.value else outcome.value
                    }
                } else when (c) {
                    is Cardinality.Default -> sink[opt] = c.value
                    Cardinality.Required -> when (policy) {
                        BindPolicy.Strict -> return Result.Error(CliError.MissingRequiredOption(opt.token()))
                        BindPolicy.DeferRequired -> deferred += CliError.MissingRequiredOption(opt.token())
                        // Left unbound; reading it from a CompletionScope throws, which the provider seam
                        // turns into no candidates.
                        BindPolicy.Lenient -> Unit
                    }

                    else -> sink[opt] = null
                }
            }
        }
    }
    return Result.Success(deferred)
}

/**
 * Bind every global spec from its position-independent occurrences. What an under-satisfied required or
 * `multiple(min)` global does depends on [policy]: the default [BindPolicy.DeferRequired] returns the error
 * it would raise in the result list, left for [parse] to judge against where the walk actually ended up,
 * while [BindPolicy.Lenient] (completion) leaves it unbound and returns nothing.
 */
internal fun bindGlobals(
    globalSpecs: List<HolderSpec>,
    globalSift: GlobalSift,
    sink: MutableMap<HolderSpec, Any?>,
    inferValues: Boolean,
    policy: BindPolicy = BindPolicy.DeferRequired,
): Result<List<CliError>, CliError> {
    val flags = globalSpecs.filterIsInstance<FlagSpec>()
    val options = globalSpecs.filterIsInstance<OptionSpec>()
    return bindFlagsAndOptions(
        flags,
        options,
        globalSift.flags,
        // Every occurrence has been seen by now, so the winning polarity is fixed and its position spent.
        globalSift.negations.mapValues { (_, polarity) -> polarity.on },
        globalSift.options.mapValues { (_, occurrences) -> occurrences.inArgvOrder() },
        sink,
        inferValues,
        policy,
    )
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
 * Assign [values] to this command's argument specs; enforce required/variadic/extra rules per [policy].
 * A positional has no deferral case, so [policy] only distinguishes [BindPolicy.Lenient] from the two
 * strict policies: [BindPolicy.DeferRequired] behaves exactly like [BindPolicy.Strict] here.
 */
internal fun Command.bindPositionals(
    values: List<String>,
    sink: MutableMap<HolderSpec, Any?>,
    sifted: Sifted,
    inferValues: Boolean,
    policy: BindPolicy = BindPolicy.Strict,
): Result<Unit, CliError> {
    val overridden = lastWinsLosers(sifted)
    val args = activeArguments(sifted, overridden)
    // A slot whose absentWhen trigger fired is not in [args] at all, so the operands after it keep their
    // own positions; binding it here rather than in the loop keeps the accessor total.
    arguments.filterNot { it in args }.forEach { sink[it] = it.absentValue() }
    var i = 0
    for ((index, spec) in args.withIndex()) {
        val isLast = index == args.lastIndex
        when (val c = spec.cardinality) {
            is Cardinality.Multiple -> {
                // A relaxedWhen trigger drops the declared minimum to zero; the slot itself is untouched,
                // so nothing else about the slice changes.
                val min = if (spec.relaxedWhen?.let { supplied(it, sifted, overridden) } == true) 0 else c.min
                // Everything left EXCEPT what the fixed slots after this one still need. They are all
                // Required (BuilderValidation's rule), so the count is exact and `cp a b c` gives the
                // variadic [a, b] and the destination c. With the variadic last this is `values.drop(i)`
                // unchanged, which is why nothing about a trailing `[FILE...]` moves.
                val fixedAfter = args.size - index - 1
                val take = (values.size - i - fixedAfter).coerceAtLeast(0)
                val slice = values.subList(i, i + take)
                // Keyed on min alone: multiple() defaults to min = 0, which means the operand list is
                // genuinely optional (`tar -tf a.tar` names no FILE). Erroring on any empty slice regardless
                // of min would make min = 0 behave as min = 1 and put every `[FILE...]` surface out of reach.
                // Zero given still reads as a fully-absent mandatory argument when a minimum IS declared;
                // short of a declared minimum gets the same count-aware error the analogous option's
                // Multiple branch reports.
                if (slice.size < min) {
                    val tooFew =
                        if (slice.isEmpty()) CliError.MissingArgument(name, spec.name)
                        else CliError.TooFewOccurrences(spec.name, min, slice.size)
                    if (policy != BindPolicy.Lenient) return Result.Error(tooFew)
                }
                // Binds the slice even when empty, matching the option branch above: under Lenient
                // "nothing typed yet" is truthfully an empty list, and binding it keeps a provider that
                // reads this input alive instead of aborting it. Strict/DeferRequired already returned
                // above when the slice was empty or short.
                when (val converted = spec.convertAll(slice, inferValues)) {
                    is Result.Error -> if (policy != BindPolicy.Lenient) return converted
                    is Result.Success -> sink[spec] = converted.value
                }
                i += take
            }

            else -> {
                val raw = values.getOrNull(i)
                if (raw == null) {
                    when (c) {
                        is Cardinality.Default -> sink[spec] = c.value
                        Cardinality.Optional -> sink[spec] = null
                        else -> if (policy != BindPolicy.Lenient) {
                            return Result.Error(CliError.MissingArgument(name, spec.name))
                        }
                    }
                } else {
                    when (val outcome = spec.convertOne(raw, inferValues)) {
                        is Result.Error -> if (policy != BindPolicy.Lenient) return outcome
                        // Same "?: default" substitution as the option bind: only a converter that SUCCEEDS
                        // with null falls back to c.value.
                        is Result.Success -> sink[spec] =
                            if (c is Cardinality.Default) outcome.value ?: c.value else outcome.value
                    }
                    // Advances even when the value was rejected: the token belonged to this slot either way.
                    i += 1
                }
            }
        }
        if (isLast && i < values.size && policy != BindPolicy.Lenient) {
            return Result.Error(tooManyArguments(values.drop(i)))
        }
    }

    if (args.isEmpty() && values.isNotEmpty() && policy != BindPolicy.Lenient) {
        return Result.Error(tooManyArguments(values))
    }
    return Result.Success(Unit)
}

/** Convert every raw a variadic slot received, failing on the first that does not convert. */
private fun ValueSpec.convertAll(raws: List<String>, inferValues: Boolean): Result<List<Any?>, CliError> {
    val converted = mutableListOf<Any?>()
    for (raw in raws) converted += convertOne(raw, inferValues).getOrElse { return Result.Error(it) }
    return Result.Success(converted)
}

/**
 * [convertAll] for a `multiple()` option, which additionally rejects a converter that SUCCEEDS with null
 * (`.map { it.toIntOrNull() }` on bad input): `Opt<T?>.multiple()` narrows the accessor to a non-null
 * `List<T>`, leaving no slot to bind such a value into. The positional form keeps `List<T?>` and allows it.
 */
private fun OptionSpec.convertOccurrences(raws: List<String>, inferValues: Boolean): Result<List<Any>, CliError> {
    val converted = mutableListOf<Any>()
    for (raw in raws) {
        val value = convertOne(raw, inferValues).getOrElse { return Result.Error(it) }
            ?: return Result.Error(CliError.BadValue(name, raw, "conversion failed"))
        converted += value
    }
    return Result.Success(converted)
}

/**
 * Convert one raw value through a spec, mapping a converter failure to the right CliError, then run
 * [ValueSpec.validate]. [inferValues] resolves a `.choice()`/`.enum<E>()` prefix before the converter ever
 * sees the raw token, since the converter closes over its choices at declaration time and cannot know the
 * root's [Abbreviation] mode itself.
 */
private fun ValueSpec.convertOne(raw: String, inferValues: Boolean): Result<Any?, CliError> {
    val resolved = if (!inferValues || choices == null) {
        raw
    } else {
        resolveChoice(raw, choices!!).getOrElse { return Result.Error(it) }
    }
    // A converter chain that compiles but is misused (two type converters stacked, or a nullable map
    // feeding a later String-typed stage) can throw at parse; the parse-never-throws contract turns any
    // converter exception into a BadValue instead of crashing the process.
    val converted = try {
        convert(resolved)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val threw = ConversionError.Threw(e)
        return Result.Error(CliError.BadValue(name, raw, threw.reason(), threw))
    }
    val value = converted.getOrElse { cause ->
        return Result.Error(
            if (choices != null) {
                // Choice-restricted matching is case-insensitive (.choice()/.enum()), so the "did you
                // mean" suggestion ignores case too, rather than penalizing a near-miss for wrong case.
                CliError.InvalidChoice(
                    name, raw, choices!!,
                    suggest(raw, choices!!, ignoreCase = true),
                )
            } else {
                CliError.BadValue(name, raw, cause.reason(), cause)
            },
        )
    }
    // A converter that succeeds with null is treated as absent (see the bind-site "?: default"
    // substitution), and an absent value never runs validate, so skip it here rather than hand a
    // non-null predicate a null. Guard validate itself too, upholding the never-throw contract.
    if (value != null) {
        // A validate failure is always BadValue, never InvalidChoice, even on a choices-backed spec.
        val message = try {
            validate?.invoke(value)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: "conversion failed"
            return Result.Error(CliError.BadValue(name, raw, reason))
        }
        message?.let { return Result.Error(CliError.BadValue(name, raw, it)) }
    }
    return Result.Success(value)
}

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

/** [resolveChoice], reading this spec's own [ValueSpec.name] as the error's [CliError.AmbiguousValue.name]. */
private fun ValueSpec.resolveChoice(raw: String, choices: List<String>): Result<String, CliError> =
    resolveChoice(name, raw, choices)

/**
 * A [CliError.TooManyArguments] whose first extra token, when it near-matches a visible subcommand,
 * carries a "did you mean the 'X' command?" hint. This catches the hybrid case where a command has both
 * its own action and user subcommands, so a typo of a real subcommand is otherwise silently consumed as
 * a stray positional for the action instead of being routed.
 */
private fun Command.tooManyArguments(extras: List<String>): CliError.TooManyArguments {
    val commandNames = subcommands.filterNot { it.hidden }.flatMap { listOf(it.name) + it.aliases }
    return CliError.TooManyArguments(name, extras, extras.firstOrNull()?.let { suggest(it, commandNames) })
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
 * Every long spelling THIS command answers to once the walk has reached it, dashes stripped, in [longPool]'s
 * order: its own options and flags in declaration order, then the globals, then every negatable flag's
 * negative longs (its own before the globals'), then the built-ins the tree still offers.
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
            builtinLongs(
                globalAcc?.builtins ?: Builtins.DEFAULT,
                versioned = globalAcc?.rootVersion != null,
                metaOptions = globalAcc?.metaOptions ?: false,
            )
}

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

internal fun Command.sift(
    segment: List<String>,
    globalAcc: GlobalAccumulator? = null,
    positions: List<Int> = emptyList(),
): Sifted {
    val flagCounts = mutableMapOf<FlagSpec, Int>()
    val negations = mutableMapOf<FlagSpec, Boolean>()
    val optionValues = mutableMapOf<OptionSpec, MutableList<String>>()
    val positionals = mutableListOf<String>()
    var error: CliError? = null

    // A lambda, not a value: only the first call's build() actually executes, so suggest()'s edit-distance
    // scan runs at most once even though the walk keeps going after an error.
    fun record(build: () -> CliError) {
        if (error == null) error = build()
    }

    val flagPositions = mutableMapOf<FlagSpec, Int>()
    val optionPositions = mutableMapOf<OptionSpec, Int>()

    // Loop-invariant, but built only once a long token actually needs it: a segment of operands and short
    // clusters must not pay for a list it never reads.
    val longPool by lazy(LazyThreadSafetyMode.NONE) { longMatchPool(globalAcc) }

    fun hit(flag: FlagSpec, polarity: Boolean, at: Int) {
        flagCounts[flag] = (flagCounts[flag] ?: 0) + 1
        flagPositions[flag] = at
        if (flag.negatable) negations[flag] = polarity
    }

    var i = 0
    var optionsEnded = false
    while (i < segment.size) {
        val token = segment[i]
        val aliasHit = numericAliasValue(token, globalAcc)
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
                        hit(flag, true, clusterPosition(positions.getOrNull(i) ?: i))
                        i += 1
                    }

                    negated != null -> {
                        if (inlineValue != null) record { CliError.FlagTakesNoValue(spelled) }
                        hit(negated, false, clusterPosition(positions.getOrNull(i) ?: i))
                        i += 1
                    }

                    else -> {
                        val opt = findOption(long, null)
                        val taken = opt.valueFrom(inlineValue) {
                            segment.getOrNull(i + 1)?.takeUnless { it == END_OF_OPTIONS }
                        }
                        when {
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
                                optionPositions[opt] = clusterPosition(positions.getOrNull(i) ?: i)
                                i += if (taken.consumedNext) 2 else 1
                            }
                        }
                    }
                }
            }

            // `-<NUM>` where the command declared what NUM means: the digits are the aliased option's
            // value. Ahead of the cluster walk, which would otherwise read them as one flag char each.
            aliasHit != null -> {
                optionValues.getOrPut(aliasHit.first) { mutableListOf() } += aliasHit.second
                optionPositions[aliasHit.first] = clusterPosition(positions.getOrNull(i) ?: i)
                i += 1
            }

            else -> {
                // Short cluster: each char is a flag until one names an option, which takes the rest (`-p8080`)
                // or the next token. A char that is not local is tried against the globals ([globalAcc]) so a
                // mixed cluster like `-fv` binds the global too; only a char that is neither is recorded.
                val chars = token.removePrefix("-")
                var advance = 1
                var j = 0
                while (j < chars.length) {
                    val ch = chars[j].toString()
                    val flagHit = globalAcc.clusterHit(findFlag("-$ch")) { flagSpecs.findFlag("-$ch") }
                    if (flagHit != null) {
                        val globals = flagHit.globals
                        if (globals != null) globals.hitFlag(flagHit.spec, positions.getOrNull(i))
                        else hit(flagHit.spec, true, clusterPosition(positions.getOrNull(i) ?: i, j))
                        j += 1
                        continue
                    }

                    val negatedHit = globalAcc.clusterHit(findNegatedShort(ch)) { flagSpecs.findNegatedShort(ch) }
                    if (negatedHit != null) {
                        val globals = negatedHit.globals
                        if (globals != null) globals.hitFlag(negatedHit.spec, positions.getOrNull(i), on = false)
                        else hit(negatedHit.spec, false, clusterPosition(positions.getOrNull(i) ?: i, j))
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
                        globals.addOptionValue(opt, taken.value, positions.getOrNull(i))
                    } else {
                        optionValues.getOrPut(opt) { mutableListOf() } += taken.value
                        optionPositions[opt] = clusterPosition(positions.getOrNull(i) ?: i, j)
                    }
                    advance = if (taken.consumedNext) 2 else 1
                    j = chars.length
                }
                i += advance
            }
        }
    }
    return Sifted(flagCounts, negations, optionValues, positionals, error, flagPositions, optionPositions)
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
 * Whether [cluster] (a short-cluster token with its leading dash already stripped) contains at least one
 * char that resolves against a declared global flag, its negated short, or a global option — the same
 * per-char lookup [siftGlobals]'s cluster branch performs. Shared so a walk that has not yet reached the
 * command owning the cluster's OTHER chars can still tell "carries a global" from "carries none" without
 * reimplementing the resolution rule: the subcommand-routing walk and the arity walk behind
 * [optionValueSlots] both ask this before deciding whether an unresolved char stops their walk outright or
 * is left for the reached command's own [sift] to claim.
 */
internal fun clusterTouchesGlobal(
    cluster: String,
    flagSpecs: List<FlagSpec>,
    optionSpecs: List<OptionSpec>,
): Boolean = cluster.any { char ->
    val ch = char.toString()
    flagSpecs.findFlag("-$ch") != null ||
        flagSpecs.findNegatedShort(ch) != null ||
        optionSpecs.findOption(null, ch) != null
}

private fun Command.findFlag(token: String): FlagSpec? = flags.findFlag(token)
private fun Command.findNegatedFlag(long: String): FlagSpec? = flags.findNegatedFlag(long)
private fun Command.findNegatedShort(short: String): FlagSpec? = flags.findNegatedShort(short)
private fun Command.findOption(long: String?, short: String?): OptionSpec? =
    options.findOption(long, short)

/**
 * The error for an unrecognized char at `chars[j]` inside a short cluster (the chars before it, `chars[0
 * until j]`, all matched flags, since an option match would have ended the loop already). A `=` right
 * after a flag char is the short form of `--flag=value`, so it reports the same no-value error the long
 * form does, naming the preceding flag (found via [findFlag], which both callers back with
 * [findFlagOrNegatedShort] so a preceding NEGATIVE short is named too, not just a positive one) rather
 * than the phantom `-=` a bare `"-$ch"` would fabricate. Any other stray non-alphanumeric char (e.g. the
 * `-` in `-f-y`) blames the whole original [token] instead of fabricating `"-$ch"` (which for `ch == '-'`
 * would misreport as the end-of-options marker `--`). A genuinely unknown LETTER/digit still names just
 * that char, matching a leaf's existing granularity. Shared by [Cli.clusterCharError] (the per-command
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

        ch.isLetterOrDigit() -> CliError.UnknownOption("-$ch")
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
 * Seed a mutable [GlobalAccumulator] from this pre-strip result, ready for the segment sift to top up.
 * [rootVersion] is the root's own [Cli.version], [builtins] its resolved built-in surface, [metaOptions]
 * whether it offers `--completion`/`--docs`, and [treeLongs] its [Cli.declaredLongs]; all four are threaded
 * through so a nested command's [longOptionCandidates] and [longMatchPool] see the same surface the root's
 * own pre-walk scans did.
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
 * Walk [scan]'s tokens before the end-of-options marker, pull out those naming a global (long form, or a
 * short cluster whose chars are ALL globals, e.g. `-vc`), and return the cleaned argv for the normal
 * subcommand walk alongside the sifted occurrences. A short cluster that mixes a global with a non-global
 * char is left whole for the resolved command's global-aware [sift] to split (so `-fv` binds both, in any
 * order).
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

    fun hit(flag: FlagSpec, polarity: Boolean, at: Int) {
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
                        hit(flag, true, at)
                        i += 1
                    }

                    negated != null -> {
                        if (inlineValue != null && error == null) error =
                            CliError.FlagTakesNoValue(spelled)
                        hit(negated, false, at)
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
                            optionValues.getOrPut(opt) { mutableListOf() } += Occurrence(taken.value, at)
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
                val pendingFlags = mutableListOf<FlagSpec>()
                val pendingNegatedFlags = mutableListOf<FlagSpec>()
                var pendingOption: Pair<OptionSpec, String>? = null
                var pendingDangling: OptionSpec? = null
                var pendingEqError: CliError? = null
                var fullyGlobal = true
                var advance = 1
                var j = 0
                while (j < chars.length) {
                    val ch = chars[j].toString()
                    val flag = flagSpecs.findFlag("-$ch")
                    if (flag != null) {
                        pendingFlags += flag
                        j += 1
                        continue
                    }
                    val negated = flagSpecs.findNegatedShort(ch)
                    if (negated != null) {
                        pendingNegatedFlags += negated
                        j += 1
                        continue
                    }
                    val opt = optionSpecs.findOption(null, ch)
                    when {
                        opt != null -> {
                            val attached = chars.substring(j + 1).ifEmpty { null }
                            val taken = opt.valueFrom(attached) { head.getOrNull(i + 1) }
                            if (taken.value == null) pendingDangling = opt else {
                                pendingOption = opt to taken.value
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
                    pendingFlags.forEach { hit(it, true, at) }
                    pendingNegatedFlags.forEach { hit(it, false, at) }
                    pendingOption?.let { (opt, value) ->
                        optionValues.getOrPut(opt) { mutableListOf() } += Occurrence(value, at)
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
 * Collected option/flag occurrences plus leftover positionals for a command segment.
 * [flags] counts occurrences per spec (a plain flag binds `count > 0`, a count flag binds the count itself).
 * [negations] carries the last-seen polarity per negatable flag: true for `--x`, false for `--no-x`.
 * [error] is the first hard syntax error hit while walking, mirroring [GlobalSift.error]: [bind] raises it
 * before binding anything, while the completion planner ignores it and uses whatever the walk did collect.
 */
internal class Sifted(
    val flags: Map<FlagSpec, Int>,
    val negations: Map<FlagSpec, Boolean>,
    val options: Map<OptionSpec, List<String>>,
    val positionals: List<String>,
    val error: CliError? = null,
    // Where each flag/option was LAST seen, for the one rule that needs order between two different inputs
    // ([ConstraintArity.LastWins]). Encoded so a position inside a short cluster is comparable with a
    // whole-token one: see [clusterPosition].
    val flagPositions: Map<FlagSpec, Int> = emptyMap(),
    val optionPositions: Map<OptionSpec, Int> = emptyMap(),
)

/**
 * A comparable position for a flag occurrence: the token's own index, times a stride wide enough that the
 * character index within a short cluster orders inside it without ever reaching the next token. So `-if`
 * and `-i -f` compare the same way, which is what makes `lastWins` mean the same thing in both spellings.
 */
internal fun clusterPosition(tokenIndex: Int, charIndex: Int = 0): Int = tokenIndex * 1000 + charIndex

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
internal class Polarity(val on: Boolean, val position: Int?)

/** Keep whichever polarity sits LAST in argv; an unordered observation (see [Polarity]) always wins. */
private fun MutableMap<FlagSpec, Polarity>.recordPolarity(flag: FlagSpec, on: Boolean, at: Int?) {
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
internal class Occurrence(val value: String, val position: Int?)

/** These occurrences' values in argv order; an unpositioned one (see [Occurrence]) comes last. */
private fun List<Occurrence>.inArgvOrder(): List<String> =
    sortedBy { it.position ?: Int.MAX_VALUE }.map { it.value }

/**
 * A mutable running tally of global occurrences: seeded from [siftGlobals]' position-independent pass, then
 * topped up by a command segment's own [sift] when a global char is buried in a mixed short cluster (`-fv`).
 * [flagSpecs]/[optionSpecs] let that sift recognize a global char after it has ruled out a local one.
 * [rootVersion] carries the root's own [Cli.version], [builtins] its resolved built-in surface,
 * [metaOptions] its `--completion`/`--docs` gate, [treeLongs] its [Cli.declaredLongs], and [abbreviation] its
 * [Cli.abbreviation], down to a nested command's [sift]: the only way any of them reaches the did-you-mean
 * candidate set and the abbreviation pool (see [longOptionCandidates], [longMatchPool]) without threading
 * five separate parameters through every call between [parse] and [sift].
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
    fun hitFlag(spec: FlagSpec, position: Int? = null, on: Boolean = true) {
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
    fun addOptionValue(spec: OptionSpec, value: String, position: Int?) {
        options.getOrPut(spec) { mutableListOf() } += Occurrence(value, position)
    }

    /** The merged occurrences, ready for [bindGlobals]. Segment syntax errors surface via [Sifted.error]. */
    fun toGlobalSift(): GlobalSift = GlobalSift(flags, negations, options)
}

/**
 * The lenient counterpart to [com.fromwau.klap.parse]'s bind phase, for tab completion: bind everything the
 * half-typed [sifted] segment and [globalAcc] can resolve for [cmd], and leave the rest unbound.
 *
 * Never fails, and [Sifted.error] is deliberately ignored — a malformed token loses only itself, not the
 * inputs around it. Reading an input this leaves unbound throws from the [com.fromwau.klap.CompletionScope]
 * accessor, which the provider seam turns into "no candidates".
 *
 * [sifted] must be the result of `cmd.sift(segment, globalAcc)` with this same [globalAcc]: that walk is
 * what records a global hiding in a mixed short cluster (`-fv`), and the [bindGlobals] below reads them
 * back off the accumulator.
 */
internal fun Cli.completionValues(
    cmd: Command,
    sifted: Sifted,
    globalAcc: GlobalAccumulator,
): Map<HolderSpec, Any?> {
    val sink = mutableMapOf<HolderSpec, Any?>()
    // Same order parse() binds in: the command's own inputs, then the globals the segment sift topped up.
    // Every Result is discarded: Lenient never errors, and it defers nothing.
    bindFlagsAndOptions(
        cmd.flags,
        cmd.options,
        sifted.flags,
        sifted.negations,
        sifted.options,
        sink,
        globalAcc.inferNames,
        BindPolicy.Lenient,
    )
    cmd.bindPositionals(sifted.positionals, sink, sifted, globalAcc.inferNames, BindPolicy.Lenient)
    bindGlobals(globalSpecs, globalAcc.toGlobalSift(), sink, globalAcc.inferNames, BindPolicy.Lenient)
    return sink
}
