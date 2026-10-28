package com.fromwau.klap.internal.parse

import com.fromwau.kern.fuzzy.didYouMean
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.klap.CheckRefused
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.Command
import com.fromwau.klap.ConversionError
import com.fromwau.klap.OptionalValue
import com.fromwau.klap.internal.render.reason
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.ConstraintArity
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.ValueSpec
import com.fromwau.klap.internal.spec.constraintToken
import com.fromwau.klap.internal.spec.token
import com.fromwau.klap.judge
import kotlin.coroutines.cancellation.CancellationException

// The bind half: turns a completed Sifted walk into bound values, enforcing arity and cross-input constraints.

/**
 * The first violated cross-input constraint on this command, in declaration order, or null.
 *
 * Reads [sifted], never the bound values: an option with a `.default()` ALWAYS binds, so the bound map
 * cannot tell "the user gave it" from "the default filled it in", while the sift only records what was
 * actually on the line. Called from [bind] after [Sifted.error] and before any bind, so a mode conflict
 * outranks the `missing required option --file` a later bind would raise, matching GNU tar. Globals never
 * take part: a constraint's members are all this command's own specs (enforced at construction).
 */
internal fun Command.checkConstraints(sifted: Sifted): CliError? {
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
 * Bind [flags]/[options] from their sifted occurrences: a flag misses to `false` unless negatable/count,
 * an option applies its default/null/error when absent. The single cardinality code path shared by a
 * leaf's own [bind], [bindGlobals] and [completionValues], so all three bind flags/options the same way.
 *
 * When [policy] is [BindPolicy.DeferRequired], an under-satisfied Required or Multiple(min) option is NOT
 * an immediate error; the error it would raise is returned in the result list instead, so the caller can
 * decide later whether the absence actually matters. [bind] never defers (its own required options always
 * fail fast); only globals do, since a missing required global should not block a bare group that only
 * ends up showing help. [BindPolicy.Lenient] never errors nor defers: whatever cannot be satisfied is
 * simply left out of [sink] (see [completionValues]).
 */
internal fun bindFlagsAndOptions(
    flags: List<FlagSpec>,
    options: List<OptionSpec>,
    flagHits: Map<FlagSpec, Int>,
    negations: Map<FlagSpec, Boolean>,
    optionValues: Map<OptionSpec, List<String?>>,
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
    optionValues: Map<OptionSpec, List<String?>>,
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
                if (raws.isEmpty() && c.default != null) {
                    sink[opt] = c.default
                } else if (raws.size >= c.min || policy == BindPolicy.Lenient) {
                    when (val converted = opt.convertOccurrences(raws, inferValues)) {
                        // Lenient leaves it unbound: one bad occurrence must not blank its siblings.
                        is Result.Error -> if (policy != BindPolicy.Lenient) return converted
                        is Result.Success -> sink[opt] = converted.value
                    }
                }
            }

            else -> {
                // The last occurrence binds, but every one is converted: GNU head refuses `head -n abc -n 5`
                // and ls refuses `ls --width abc --width 80`.
                if (policy != BindPolicy.Lenient) {
                    for (earlier in raws.dropLast(1)) {
                        val outcome = opt.convertOccurrence(earlier, inferValues)
                        if (outcome is Result.Error) return outcome
                    }
                }
                if (raws.isNotEmpty()) {
                    when (val outcome = opt.convertOccurrence(raws.last(), inferValues)) {
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
    // Defaulted for the completion caller, whose Lenient policy discards every error built here.
    qualifiedName: String = name,
    // The operand a variadic slot leaves out of its value while still counting it toward the slots: the word
    // completion is typing, which is not a finished value yet.
    skipOperand: Int? = null,
): Result<Unit, CliError> {
    val overridden = lastWinsLosers(sifted)
    val args = activeArguments(sifted, overridden)
    // A slot whose absentWhen trigger fired is not in [args] at all, so the operands after it keep their
    // own positions; binding it here rather than in the loop keeps the accessor total. The gathering slot
    // is not in [args] either once it took a word, and binds those words joined.
    for (spec in arguments.filterNot { it in args }) {
        val gather = spec.gather
        if (gather == null || !spec.gathersOn(sifted) || removedByTrigger(spec, sifted, overridden)) {
            sink[spec] = spec.absentValue()
            continue
        }
        when (val outcome = spec.convertOne(sifted.gathered.joinToString(gather.separator), inferValues)) {
            is Result.Error -> if (policy != BindPolicy.Lenient) return outcome
            is Result.Success -> sink[spec] = outcome.value ?: (spec.cardinality as? Cardinality.Default)?.value
        }
    }
    var i = 0
    for ((index, spec) in args.withIndex()) {
        val isLast = index == args.lastIndex
        when (val c = spec.cardinality) {
            is Cardinality.Multiple -> {
                val min = operandMin(spec, sifted, overridden)
                // Everything left EXCEPT what the fixed slots after this one still need. They are all
                // Required (BuilderValidation's rule), so the count is exact and `cp a b c` gives the
                // variadic [a, b] and the destination c. With the variadic last this is `values.drop(i)`
                // unchanged, which is why nothing about a trailing `[FILE...]` moves.
                val fixedAfter = args.size - index - 1
                val left = values.size - i
                // Short of satisfying both, this slot keeps its minimum and lets the starved fixed slot
                // report itself: `cp a` means "a is the source, the destination is missing", and blaming
                // the variadic instead would answer `cp a` and bare `cp` with the same sentence.
                val take = (left - fixedAfter)
                    .coerceAtLeast(0)
                    .coerceAtLeast(minOf(min, left))
                    .coerceAtMost(operandCap(spec, sifted, overridden))
                val slice = values
                    .subList(i, i + take)
                    .filterIndexed { offset, _ -> i + offset != skipOperand }
                // Rule 3: the sift is permissive (it admits when ANY argument on the command is marked), so
                // the slot a value actually lands in is only known here. Keyed on what the sift admitted
                // rather than on the token's shape, so a `--`-escaped operand still binds in an unmarked
                // slot. Reported as the unknown option it is, naming the whole word rather than a cluster
                // character the user never typed. Checked before the min count below, so a rejected token
                // is never also blamed for the slice coming up short.
                if (policy != BindPolicy.Lenient && !spec.dashLed) {
                    (i until i + take).firstOrNull { it in sifted.dashLedAdmitted }?.let {
                        return Result.Error(CliError.UnknownOption(values[it]))
                    }
                }
                // Keyed on min alone: multiple() defaults to min = 0, which means the operand list is
                // genuinely optional (`tar -tf a.tar` names no FILE). Erroring on any empty slice regardless
                // of min would make min = 0 behave as min = 1 and put every `[FILE...]` surface out of reach.
                // Zero given still reads as a fully-absent mandatory argument when a minimum IS declared;
                // short of a declared minimum gets the same count-aware error the analogous option's
                // Multiple branch reports.
                if (slice.size < min) {
                    val tooFew =
                        if (slice.isEmpty()) CliError.MissingArgument(qualifiedName, spec.name)
                        else CliError.TooFewOccurrences(spec.name, min, slice.size)
                    if (policy != BindPolicy.Lenient) return Result.Error(tooFew)
                }
                // Binds the slice even when empty, matching the option branch above: under Lenient
                // "nothing typed yet" is truthfully an empty list, and binding it keeps a provider that
                // reads this input alive instead of aborting it. Strict/DeferRequired already returned
                // above when the slice was empty or short.
                if (slice.isEmpty() && c.default != null) {
                    sink[spec] = c.default
                } else when (val converted = spec.convertAll(slice, inferValues)) {
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
                            return Result.Error(CliError.MissingArgument(qualifiedName, spec.name))
                        }
                    }
                } else {
                    // Rule 3, same as the Multiple branch above.
                    if (policy != BindPolicy.Lenient && i in sifted.dashLedAdmitted && !spec.dashLed) {
                        return Result.Error(CliError.UnknownOption(raw))
                    }
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
            return Result.Error(tooManyArguments(values.drop(i), qualifiedName))
        }
    }

    if (args.isEmpty() && values.isNotEmpty() && policy != BindPolicy.Lenient) {
        return Result.Error(tooManyArguments(values, qualifiedName))
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
private fun OptionSpec.convertOccurrences(raws: List<String?>, inferValues: Boolean): Result<List<Any>, CliError> {
    val converted = mutableListOf<Any>()
    for (raw in raws) {
        val value = convertOccurrence(raw, inferValues).getOrElse { return Result.Error(it) }
            ?: return Result.Error(CliError.BadValue(name, raw.orEmpty(), "conversion failed"))
        converted += value
    }
    return Result.Success(converted)
}

/**
 * What one occurrence of this option binds: [raw] converted, wrapped as an [OptionalValue] for an option declared
 * with `.optionalValue()`, where a null [raw] is its bare occurrence. A converter's null stays null, as absent.
 */
internal fun OptionSpec.convertOccurrence(raw: String?, inferValues: Boolean): Result<Any?, CliError> = when {
    raw == null -> Result.Success(OptionalValue.Bare)
    bareMarks -> when (val converted = convertOne(raw, inferValues)) {
        is Result.Error -> converted
        is Result.Success -> Result.Success(converted.value?.let { OptionalValue.Given(it) })
    }
    else -> convertOne(raw, inferValues)
}

/**
 * Convert one raw value through a spec, mapping a converter failure to the right CliError, then run
 * [ValueSpec.validate]. [inferValues] resolves a `.choice()`/`.enum<E>()` prefix before the converter ever
 * sees the raw token, since the converter closes over its choices at declaration time and cannot know the
 * root's [Abbreviation] mode itself.
 */
private fun ValueSpec.convertOne(raw: String, inferValues: Boolean): Result<Any?, CliError> {
    val resolved = if (!inferValues || !choiceAbbreviates || choices == null) {
        raw
    } else {
        resolveChoice(raw, choices!!).getOrElse { return Result.Error(it) }
    }
    // A converter chain that compiles but is misused (two type converters stacked, or a nullable map
    // feeding a later String-typed stage) can throw at parse; the parse-never-throws contract turns anything
    // a converter throws, an Error included, into a BadValue instead of crashing the process.
    val converted = try {
        convert(resolved)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        val threw = ConversionError.Threw(e)
        return Result.Error(CliError.BadValue(name, raw, threw.reason(), threw))
    }
    val value = converted.getOrElse { cause -> return Result.Error(refusal(raw, cause)) }
    // A converter that succeeds with null is treated as absent (see the bind-site "?: default"
    // substitution), and an absent value is never judged, so a non-null predicate never sees a null.
    val message = value?.let { checked -> validate?.let { judge(it, checked) } }
    message?.let { return Result.Error(CliError.BadValue(name, raw, it)) }
    return Result.Success(value)
}

/**
 * The [CliError] for [raw] refused by this spec's converters with [cause]. A check is always [CliError.BadValue]
 * carrying its message alone, never [CliError.InvalidChoice], even on a choices-backed spec.
 */
private fun ValueSpec.refusal(raw: String, cause: ConversionError): CliError = when {
    cause is ConversionError.Domain && cause.error == CheckRefused -> CliError.BadValue(name, raw, cause.detail)
    choices != null -> CliError.InvalidChoice(name, raw, choices!!, didYouMean(raw, choices!!))
    else -> CliError.BadValue(name, raw, cause.reason(), cause)
}

/** [resolveChoice], reading this spec's own [ValueSpec.name] as the error's [CliError.AmbiguousValue.name]. */
private fun ValueSpec.resolveChoice(raw: String, choices: List<String>): Result<String, CliError> =
    resolveChoice(name, raw, choices, choiceIgnoreCase, choiceEntries)

/**
 * A [CliError.TooManyArguments] whose first extra token, when it near-matches a visible subcommand,
 * carries a "did you mean the 'X' command?" hint. This catches the hybrid case where a command has both
 * its own action and user subcommands, so a typo of a real subcommand is otherwise silently consumed as
 * a stray positional for the action instead of being routed.
 */
private fun Command.tooManyArguments(extras: List<String>, qualifiedName: String): CliError {
    val commandNames = subcommands.filterNot { it.hidden }.flatMap { it.words }
    // A dash-led extra is never a mistyped subcommand: [requireValidName] forbids a leading '-' on every
    // command name and alias, so measuring one against them can only produce a wrong answer ('-rm' is one
    // edit from 'rm'). Such a token gets here past `--`, or past a `dashLed()` slot that already took one.
    val needle = extras.firstOrNull()?.takeUnless { it.startsWith("-") }
    // An exact command name here was never routed to, so "unexpected extra argument" would blame the one
    // token on the line that is a declared command. Visible names only: a hidden command should not be
    // revealed by an error message.
    needle?.takeIf { it in commandNames }?.let { return CliError.UnroutedSubcommand(it, qualifiedName) }
    return CliError.TooManyArguments(qualifiedName, extras, needle?.let { didYouMean(it, commandNames) })
}

/**
 * The first option value on the line, in line order, that its conversion or check refuses, read over a
 * command's own [sifted] values and every global in [globals]. getopt's loop meets a value as it reads its
 * option, so a bad one written ahead of `--help` refuses the line.
 */
internal fun firstBadValue(sifted: Sifted, globals: GlobalSift, inferValues: Boolean): Refusal? {
    // A bare occurrence (a null value) has nothing to convert.
    val local = sifted.options.flatMap { (spec, raws) ->
        raws.zip(sifted.optionValuePositions[spec].orEmpty()) { raw, at -> raw?.let { Triple(spec, it, at) } }
            .filterNotNull()
    }
    val global = globals.options.flatMap { (spec, occurrences) ->
        occurrences.mapNotNull { occurrence ->
            occurrence.position?.let { at -> occurrence.value?.let { Triple(spec, it, at) } }
        }
    }
    return (local + global)
        .sortedBy { it.third }
        .firstNotNullOfOrNull { (spec, raw, at) ->
            (spec.convertOne(raw, inferValues) as? Result.Error)?.let { Refusal(it.error, at) }
        }
}

/** These occurrences' values in argv order; an unpositioned one (see [Occurrence]) comes last. */
private fun List<Occurrence>.inArgvOrder(): List<String?> =
    sortedWith(compareBy(nullsLast()) { it.position }).map { it.value }

/**
 * The lenient counterpart to [com.fromwau.klap.parse]'s bind phase, for tab completion: bind everything the
 * half-typed [sifted] segment and [globals] can resolve for [cmd], and leave the rest unbound.
 *
 * Never fails, and [Sifted.error] is deliberately ignored — a malformed token loses only itself, not the
 * inputs around it. Reading an input this leaves unbound throws from the [com.fromwau.klap.CompletionScope]
 * accessor, which the provider seam turns into "no candidates".
 *
 * [globals] must be read off the accumulator `cmd.sift` filled while sifting [sifted], since that walk is what
 * records a global hiding in a mixed short cluster (`-fv`); the caller may leave an occurrence out of them.
 * [inferValues] is that accumulator's [GlobalAccumulator.inferValues].
 */
internal fun Cli.completionValues(
    cmd: Command,
    sifted: Sifted,
    globals: GlobalSift,
    inferValues: Boolean,
    skipOperand: Int? = null,
): BoundLine {
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
        inferValues,
        BindPolicy.Lenient,
    )
    cmd.bindPositionals(
        sifted.positionals,
        sink,
        sifted,
        inferValues,
        BindPolicy.Lenient,
        skipOperand = skipOperand,
    )
    bindGlobals(globalSpecs, globals, sink, inferValues, BindPolicy.Lenient)
    return BoundLine(sink, lineOrder(sifted, globals, inferValues))
}
