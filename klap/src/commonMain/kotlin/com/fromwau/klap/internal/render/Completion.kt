package com.fromwau.klap.internal.render

import com.fromwau.klap.COLOR_MODE_NAMES
import com.fromwau.klap.COMPLETE_FILES
import com.fromwau.klap.Cli
import com.fromwau.klap.Command
import com.fromwau.klap.CompletionScope
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.ShortOptions
import com.fromwau.klap.SubcommandMatch
import com.fromwau.klap.internal.parse.ArgvScan
import com.fromwau.klap.internal.parse.BoundLine
import com.fromwau.klap.internal.parse.END_OF_OPTIONS
import com.fromwau.klap.internal.parse.endOfOptions
import com.fromwau.klap.internal.parse.GlobalAccumulator
import com.fromwau.klap.internal.parse.isBuiltinShort
import com.fromwau.klap.internal.parse.NameMatch
import com.fromwau.klap.internal.parse.Sifted
import com.fromwau.klap.internal.parse.accumulator
import com.fromwau.klap.internal.parse.activeArguments
import com.fromwau.klap.internal.parse.completionValues
import com.fromwau.klap.internal.parse.flagCommandsNamed
import com.fromwau.klap.internal.parse.gathersOn
import com.fromwau.klap.internal.parse.hoistCommands
import com.fromwau.klap.internal.parse.isFlagLike
import com.fromwau.klap.internal.parse.lastWinsLosers
import com.fromwau.klap.internal.parse.builtinScan
import com.fromwau.klap.internal.parse.longMatchPool
import com.fromwau.klap.internal.parse.numberRunAt
import com.fromwau.klap.internal.parse.operandCap
import com.fromwau.klap.internal.parse.operandMin
import com.fromwau.klap.internal.parse.refusesCluster
import com.fromwau.klap.internal.parse.refusesRepeat
import com.fromwau.klap.internal.parse.resolveLong
import com.fromwau.klap.internal.parse.sift
import com.fromwau.klap.internal.parse.siftGlobals
import com.fromwau.klap.internal.parse.supplied
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.ConstraintArity
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.NamedSpec
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.ValueSpec
import com.fromwau.klap.internal.spec.longs
import com.fromwau.klap.internal.spec.negativeLongs
import com.fromwau.klap.internal.spec.negativeShorts
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.resolveSubcommand
import com.fromwau.klap.routesTransparently

/**
 * A single completion candidate: [value] is the text offered on the wire (and to the shell, which is what
 * gets inserted and what prefix-filtering matches); [description], when present, is display-only text a
 * shell can show alongside it (e.g. from a `.completeWith { candidate(value, description) }` provider).
 */
internal data class Candidate(val value: String, val description: String? = null)

/**
 * Whether these candidates are the lone [COMPLETE_FILES] directive every generated script maps to its own
 * native path completion. The single test for it, so the planner and the prefix filter cannot drift apart.
 */
internal fun List<Candidate>.isNativeFileRequest(): Boolean =
    singleOrNull()?.value?.startsWith(COMPLETE_FILES) == true

/**
 * The completion planner behind the hidden `__complete` subcommand, correct at any depth: given the [words] of
 * the line (mirroring `COMP_WORDS[@]:1`) and the index of the partial word under the [cursor], return every
 * candidate valid there. Per-slot rules are commented at their branches; testable without a shell.
 *
 * Which slot is under the cursor is read from the words up to it, counting how many operands follow it. What a
 * provider reads of the inputs is bound from the whole line, so an input typed further along counts.
 */
internal fun Cli.completeCandidates(words: List<String>, cursor: Int = words.lastIndex): List<Candidate> {
    // Hold the cursor's own (maybe half-typed) word out of the strips below: stripping it would discard the
    // very context being completed.
    val current = words.getOrNull(cursor).orEmpty()
    val rawHead = words.take(cursor.coerceAtLeast(0))
    val after = words.drop(cursor + 1)
    val typed = words.take(cursor + 1)

    // A flag-spelled command the head has not named but the word under the cursor or a word after it does is the
    // command the cursor completes for, as the parse would route the whole line: `pacman -Q<TAB>` and
    // `pacman -i<TAB> -Q` complete -Q's letters. A line naming two, or one twice, is a line the parse refuses.
    if (hoistCommands(rawHead + after).error != null) return emptyList()
    val level = walkLine(rawHead).cmd
    val inCurrent = flagCommandsNamed(level, listOf(current).filterNot { it.startsWith("--") })
    if (inCurrent.size > 1) return emptyList()
    val pending = inCurrent.singleOrNull() ?: flagCommandsNamed(level, after).firstOrNull()
    val head = pending?.let { rawHead + it.flagSpellings.first() } ?: rawHead
    // The word under the cursor as the command reads it: its own letter only routes, so it is left out.
    val analysed = inCurrent.singleOrNull()?.let { current.withoutLettersOf(it) } ?: current

    val line = walkLine(head)
    val scan = line.scan
    // Where the word under the cursor stands among the walked tokens, which hoisting a command may have moved.
    val cursorAt = line.tokenCount
    if (builtins.color) {
        // Read with the cursor's word in place: in the value slot of the option before it, it is that value.
        val withCursor = builtinScan(hoistCommands(head).tokens + current)
        if (cursorAt !in withCursor.valueSlots) {
            colorValueCandidates(current, cursorAt, withCursor, builtins.colorText)?.let { return it }
        }
    }

    val cmd = line.cmd
    val segment = line.segment
    val globalAcc = line.globalAcc
    // The last token the walk kept, unless it was consumed as a value: `mygrep -e --tag <cur>` gave "--tag"
    // to `-e`, so `-e` is satisfied and the cursor is on an OPERAND, exactly as in `mygrep -e x <cur>`.
    // Nothing was dropped from the END of `cleaned`, so this token's argv index is the last one reported.
    val prev = segment.lastOrNull()?.takeUnless { line.lastKeptPosition in scan.valueSlots }
    // After `--` every word is an operand, so nothing there names an option or carries an option's value.
    val optionsEnded = segment.endOfOptions(line.segmentPositions, scan.valueSlots) >= 0

    // One segment walk: the flag-name branch reads which constraint members are already on the line, the
    // positional branch the positional count. Lazy for the value-completion branches, which need it only
    // when the option under the cursor has a provider that reads an accessor. Forcing it on a Tab press is
    // safe anywhere: sift is a pure token walk with no user code in it, unlike the bind behind [values].
    val sifted by lazy(LazyThreadSafetyMode.NONE) { cmd.sift(segment, globalAcc, line.segmentPositions) }
    // sifted.error is deliberately ignored: a line still being typed is expected to be malformed, and a Tab
    // press must never fail. Whatever the walk did collect around the offending token is what we complete.

    // The whole line, walked and sifted once: what the inputs bind from and what places the cursor among the
    // slots. A subcommand typed after the cursor would walk it to another command, whose inputs are not the ones
    // [cmd]'s provider reads, so then [cmd] sifts the line itself, not walked again from the cursor word.
    val wholeLine by lazy(LazyThreadSafetyMode.NONE) { walkLine(words).takeIf { it.cmd === cmd } }
    val wholeAcc by lazy(LazyThreadSafetyMode.NONE) { wholeLine?.globalAcc ?: globalAcc }
    val wholeSifted by lazy(LazyThreadSafetyMode.NONE) {
        val walked = wholeLine
        if (walked != null) {
            cmd.sift(walked.segment, wholeAcc, walked.segmentPositions)
        } else {
            cmd.sift(segment + analysed + after, wholeAcc, line.segmentPositions + (cursorAt..cursorAt + after.size))
        }
    }

    // The bind runs user converters and validators, so it must not happen on a Tab press whose slot has no
    // provider; a provider reading an accessor is what forces it.
    fun valuesFor(
        target: HolderSpec,
        typedValue: String = current,
    ) = lazy(LazyThreadSafetyMode.NONE) {
        // A variadic input holds the values already finished, not the one being completed, so a provider
        // leaving out what was given keeps offering it. Only that value goes: every word keeps its slot.
        val variadic = target.cardinality is Cardinality.Multiple
        val option = (target as? OptionSpec)?.takeIf { variadic }
        val global = option != null && option in globalAcc.optionSpecs
        val bound = if (option != null && !global) {
            val finished = sifted.options[option].orEmpty().count { it == typedValue }
            wholeSifted.withoutOccurrence(option, typedValue, finished)
        } else {
            wholeSifted
        }
        val skipOperand = sifted.positionals.size.takeIf { variadic && target is ArgumentSpec }
        // Read after [wholeSifted], which tops [wholeAcc] up with a global hidden in a short cluster.
        val globals = wholeAcc.toGlobalSift()
        val boundGlobals = if (option != null && global) {
            // [globalAcc] may hold more than the head's own globals by now, so the head is walked afresh.
            val headLine = walkLine(head)
            headLine.cmd.sift(headLine.segment, headLine.globalAcc, headLine.segmentPositions)
            val finished = headLine.globalAcc
                .toGlobalSift()
                .options[option]
                .orEmpty()
                .count { it.value == typedValue }
            // In the order of the line, as binding orders them too, so the finished ones come first.
            val occurrences = globals.options[option]
                .orEmpty()
                .sortedWith(compareBy(nullsLast()) { it.position })
            val inLineOrder = globals.options + (option to occurrences)
            globals.copy(options = inLineOrder.withoutOccurrence(option, typedValue, finished) { it.value })
        } else {
            globals
        }
        completionValues(cmd, bound, boundGlobals, wholeAcc.inferValues, skipOperand)
    }

    // An option value under the cursor: `prev` names a known value-taking option (a whole `--long`/`-s`, or
    // the trailing option char of a short cluster like `-vp`), so complete only that value.
    val valueOption =
        prev?.takeIf { !optionsEnded && it.isFlagLike() }?.let {
            cmd.trailingValueOption(it, globalAcc)
        }
    // A glued-only value is never the next word, and the bare occurrence before it refuses the line.
    if (valueOption?.gluedValue == true) return emptyList()
    if (valueOption != null) {
        // The line without this occurrence decides: one it refuses takes no value at all.
        if (valueOption in refusedBy(cmd, rawHead.dropLast(1) + after)) return emptyList()
        return valueOption.candidatesFor(current, typed, valuesFor(valueOption))
    }
    // getopt refuses the line at an ambiguous abbreviation, so the word after it is neither a value nor an operand.
    if (prev != null && !optionsEnded && cmd.resolvedLong(prev, globalAcc) is NameMatch.Ambiguous) return emptyList()

    // An ATTACHED option value: the name and its (partial) value share ONE word (`--opt=partial` or glued
    // `-opartial`). Complete the value, filtered by the partial after the `=`/short char, not the whole word.
    val attached = cmd.attachedValueOption(analysed, globalAcc).takeUnless { optionsEnded }
    if (attached != null) {
        val (attachedOption, partial) = attached
        if (attachedOption in refusedBy(cmd, rawHead + after)) return emptyList()
        val values = attachedOption.candidatesFor(partial, typed, valuesFor(attachedOption, partial))
        // A bare optional-value short with nothing to glue on is completed by its name below.
        val bareShort = partial.isEmpty() && !current.startsWith("--")
        if (!bareShort || values.isGluable()) {
            return values.asWholeWord(current.dropLast(partial.length), attachedOption.help)
        }
    }

    // A flag-shaped partial word (`-`, `--`, `-v`, `--verb`): offer this command's option/flag names plus
    // the globals and built-ins, so flag names complete like git/kubectl. Suppressed after `--`. Each
    // candidate carries the spec's own `help` as its description (blank -> bare value, per Candidate).
    if (current.startsWith("-") && !optionsEnded) {
        val names = mutableListOf<Candidate>()

        // An optional-value short takes its value only glued (`-O3`), so one with candidates is offered as them.
        fun shortNames(
            spec: NamedSpec,
            help: String?,
        ): List<Candidate> = spec.shorts.flatMap { short ->
            val glued = (spec as? OptionSpec)
                ?.takeIf { it.valueAttachedOnly }
                ?.let { it.candidatesFor("", typed, valuesFor(it, "")) }
                ?.takeIf { it.isGluable() }
            glued?.asWholeWord("-$short", spec.help) ?: listOf(Candidate("-$short", help))
        }
        // A member typed after the cursor rules the others out as one before it does, and so does a member in a
        // short cluster being continued (`-Q` in `-Q<TAB>`); a long name being typed is not on the line yet.
        val afterPositions = line.segmentPositions + ((cursorAt + 1)..cursorAt + after.size)
        val afterSifted = cmd.sift(segment + after, globalAcc, afterPositions)
        val clusterSifted = analysed.takeIf { !it.startsWith("--") && it.length > 1 }
            ?.let { cmd.sift(segment + it, globalAcc, line.segmentPositions + cursorAt) }
        val refused = refusedBy(cmd, rawHead + after)
        val excluded = cmd.membersRuledOutBy(afterSifted) +
            clusterSifted?.let(cmd::membersRuledOutBy).orEmpty() +
            refused.excluded
        (cmd.options + cmd.flags + globalSpecs).filterNot { it.hidden || it in refused.repeats }.forEach { spec ->
            val help = spec.help.takeIf { it.isNotBlank() }
            if (spec !in excluded) {
                spec.longs.forEach { long -> names += Candidate("--$long", help) }
                names += shortNames(spec, help)
            }
            if (spec is FlagSpec && spec.negatable) {
                val negativeHelp = spec.negativeHelp ?: help
                spec.negativeLongs.forEach { names += Candidate("--$it", negativeHelp) }
                spec.negativeShorts.forEach { names += Candidate("-$it", negativeHelp) }
            }
        }
        // A command spelled as a flag is offered where its parent's options are, until the line names one, and so
        // are its own options, which bind before it as after it; a name two commands share is described by the
        // first that declares it.
        val pendingCommands = cmd.flagSubcommands.filterNot { it.hidden }
        pendingCommands.forEach { sub ->
            val help = sub.description.takeIf { it.isNotBlank() }
            sub.flagSpellings.sortedBy { !it.startsWith("--") }.forEach { names += Candidate(it, help) }
        }
        pendingCommands.flatMap { it.options + it.flags }.filterNot { it.hidden }.forEach { spec ->
            val help = spec.help.takeIf { it.isNotBlank() }
            spec.longs.forEach { long -> names += Candidate("--$long", help) }
            names += shortNames(spec, help)
        }
        // The built-ins this command's help page lists, so the two cannot drift. The app's own specs are appended
        // above, so distinctBy below keeps THEIR description when a freed name (`-h`, `--json`) has since been
        // re-declared by the app.
        cmd.builtinOptions(version != null, metaOptions, builtins).forEach { builtin ->
            builtin.names.forEach { names += Candidate(it, builtin.help) }
        }
        // A short cluster in progress (`-r`, `-rl`): getopt bundles one-character options into a single token, so offer
        // each remaining short as a continuation of what has been typed, and only those, as `pacman -Q<TAB>` does.
        // Gated on every typed char being a flag or part of a digit run, because a value-taking option ends the cluster
        // by consuming the remainder of the token.
        val typedShorts = current.removePrefix("-")
        val inCluster = !current.startsWith("--") && typedShorts.isNotEmpty() &&
            globalAcc.shortOptions == ShortOptions.Clustered
        if (inCluster && cmd.firstNonFlagShort(analysed.removePrefix("-"), globalAcc) < 0) {
            // A cluster the rest of the line already refuses is refused whatever it goes on with.
            if (cmd.clusterRefused(analysed, refused, cmd.sift(segment + analysed + after, globalAcc), globalAcc)) {
                return emptyList()
            }
            // A counted short means more each time (`-vv`), so it continues a cluster that already holds it.
            val counted = cmd.flagLookup(globalAcc)
                .filter { it.isCount }
                .flatMap { it.shorts }
                .mapNotNull { it.singleOrNull() }
                .toSet()
            return names
                .filterNot { it.value.startsWith("--") }
                .filterNot { it.value[1] in typedShorts && it.value[1] !in counted }
                .map { Candidate(current + it.value.drop(1), it.description) }
                .distinctBy { it.value }
        }
        return names.distinctBy { it.value }.filter { it.value.startsWith(current) }
    }

    val positionalIndex = sifted.positionals.size
    // Operands after the cursor decide its slot too: in `cp a <TAB> b`, `b` is the target, so the word is a source.
    val operands = wholeSifted.positionals.size.coerceAtLeast(positionalIndex + 1)
    // The bind's own slot list, not cmd.arguments: a slot an .absentWhen() trigger removed, wherever on the line
    // the trigger stands, is not on this line at all, and offering its values would put completion at odds with
    // both the parse and --help.
    val slots = cmd.activeArguments(wholeSifted)
    val overridden = cmd.lastWinsLosers(wholeSifted)
    val positional = slots
        .slotForOperand(
            positionalIndex,
            operands,
            min = { spec -> cmd.operandMin(spec, wholeSifted, overridden) },
            cap = { spec -> cmd.operandCap(spec, wholeSifted, overridden) },
        )
        ?.takeUnless { it.hidden || it in refusedBy(cmd, rawHead + after) }

    val positionalCandidates = positional?.let { it.candidatesFor(current, typed, valuesFor(it)) }.orEmpty()
    // A `.file()` slot or a completeFiles() provider must reach the shell as the LONE directive line, even at
    // a hybrid parent's first positional (subcommands beside its own operand, git-style), where a
    // subcommand name would otherwise sit next to it and be read as literal candidate text instead of
    // triggering native file completion.
    if (positionalCandidates.isNativeFileRequest()) return positionalCandidates

    // A subcommand name can only occupy the first positional slot; past that, only positional values apply.
    // Each visible subcommand contributes its name AND its aliases, so `myapp l<TAB>` also offers `ls`; both
    // carry that command's own `help` as their description (blank -> bare value, per Candidate).
    val subcommands = if (positionalIndex == 0) {
        cmd.wordSubcommands
            .filterNot { it.hidden }
            .flatMap { sub ->
                val help = sub.description.takeIf { it.isNotBlank() }
                sub.words.map { Candidate(it, help) }
            }
            .filter { it.value.startsWith(current) }
    } else {
        emptyList()
    }
    return subcommands + positionalCandidates
}

/**
 * These occurrences, in the order of the line, without the one of [option] under the cursor: the first to supply
 * [value] after the [finished] ones before the cursor that do.
 */
private fun <T> Map<OptionSpec, List<T>>.withoutOccurrence(
    option: OptionSpec,
    value: String,
    finished: Int,
    valueOf: (T) -> String?,
): Map<OptionSpec, List<T>> {
    val under = underCursor(option, value, finished, valueOf) ?: return this
    return this + (option to this.getValue(option).filterIndexed { index, _ -> index != under })
}

/** [withoutOccurrence] over a command's own sift, whose positions of [option]'s values drop the same one. */
private fun Sifted.withoutOccurrence(
    option: OptionSpec,
    value: String,
    finished: Int,
): Sifted {
    val under = options.underCursor(option, value, finished) { it } ?: return this
    val positions = optionValuePositions[option].orEmpty().filterIndexed { index, _ -> index != under }
    return copy(
        options = options + (option to options.getValue(option).filterIndexed { index, _ -> index != under }),
        optionValuePositions = optionValuePositions + (option to positions),
    )
}

/** The index among [option]'s occurrences of the one under the cursor; see [withoutOccurrence]. */
private fun <T> Map<OptionSpec, List<T>>.underCursor(
    option: OptionSpec,
    value: String,
    finished: Int,
    valueOf: (T) -> String?,
): Int? {
    val occurrences = this[option] ?: return null
    return occurrences.indices.filter { valueOf(occurrences[it]) == value }.getOrNull(finished)
}

/**
 * These value candidates as the whole word under the cursor, [head] (`--tag=`, `-vt`) in front of each, since a
 * shell replaces that word, and described by the option's [help] where they have no description of their own,
 * as `cp --backup=<TAB>` lists them. The file directive takes [head] as the start of its non-path head, which
 * each shell keeps off the path it completes.
 */
private fun List<Candidate>.asWholeWord(
    head: String,
    help: String,
): List<Candidate> =
    if (isNativeFileRequest()) {
        map { it.copy(value = COMPLETE_FILES + head + it.value.removePrefix(COMPLETE_FILES)) }
    } else {
        map { Candidate(head + it.value, it.description ?: help.takeIf { text -> text.isNotBlank() }) }
    }

/** Whether these value candidates can be glued to a short as words of their own: some, and not the file directive. */
private fun List<Candidate>.isGluable(): Boolean = isNotEmpty() && !isNativeFileRequest()

/** A line walked as parse() walks one: to the command it names, with what is left for that command to sift. */
private class WalkedLine(
    val scan: ArgvScan,
    val cmd: Command,
    val segment: List<String>,
    val globalAcc: GlobalAccumulator,
    /** The argv index of the last token the walk kept, or null when it kept none. */
    val lastKeptPosition: Int?,
    /** The argv index of each [segment] token, so a global the command's sift finds in a cluster is ordered. */
    val segmentPositions: List<Int>,
    /** How many tokens the line holds once its flag-spelled commands are hoisted: the index of the word after it. */
    val tokenCount: Int,
)

private fun Cli.walkLine(typed: List<String>): WalkedLine {
    val line = hoistCommands(typed).tokens
    val scan = builtinScan(line)
    // Strip the position-independent modifiers exactly as parse() does before its own walk: --json and --color,
    // then the user's globals, so a leading or interspersed one doesn't stop the walk below (`-v issue show` resolves like `issue show`). Skipping this
    // breaks the walk ON the modifier at token 0 and completes the ROOT instead of the typed subcommand.
    val withoutJson = if (builtins.json) scan.strip("json") else scan
    val withoutColor = if (builtins.color) withoutJson.stripSpellings("color") else withoutJson
    // Its positions are read only to place `prev` in the planner, never to order one global against another:
    // the command's own sift is the only pass that records an order, so its observations win unconditionally.
    val preStrip = globalSpecs.siftGlobals(withoutColor, shortOptions)
    val (cmd, kept) = walkTo(preStrip.cleaned)
    val segment = preStrip.cleaned.slice(kept)
    // Built via accumulator() to stay in lockstep with parse(), but only its spec lists are read here: they
    // are what lets the segment sift recognize a global hiding in a mixed short cluster (`-fr`).
    val globalAcc = preStrip.sift.accumulator(
        globalSpecs,
        version,
        builtins,
        metaOptions,
        abbreviation,
        repeatedOptions,
        shortOptions,
        sharedLongs,
    )
    val segmentPositions = preStrip.positions.slice(kept)
    return WalkedLine(scan, cmd, segment, globalAcc, preStrip.positions.lastOrNull(), segmentPositions, line.size)
}

/** This cluster without [command]'s letters, which route the line rather than name an input; `-` when none is left. */
private fun String.withoutLettersOf(command: Command): String {
    val letters = command.flagSpellings.filterNot { it.startsWith("--") }.map { it[1] }.toSet()
    return "-" + drop(1).filterNot { it in letters }
}

/**
 * The inputs a name completion must not offer: every OTHER member of an [ConstraintArity.ExactlyOne] or
 * [ConstraintArity.AtMostOne] set that already has one supplied, since a second member is a usage error
 * under both. A [ConstraintArity.LastWins] set rules out nothing — it is an override rule where any number
 * of members is legal (`rm -i -f`), which is why the parse's own constraint check skips it too. The
 * supplied member itself stays offered — some tools accept a repeat, and dropping it would change the
 * list's shape mid-typing. Supplied-ness comes from [supplied], the same predicate the parse enforces the
 * constraint with, read for the line the member would join: an operand slot whose `.absentWhen()` trigger is
 * that member loses its operand to the slots after it there, so only a gathered word of it rules the member out.
 */
private fun Command.membersRuledOutBy(sifted: Sifted): Set<HolderSpec> = constraints
    .filterNot { constraint -> constraint.arity == ConstraintArity.LastWins }
    .flatMapTo(mutableSetOf()) { constraint ->
        constraint.members.filter { member ->
            !supplied(member, sifted) && constraint.members.any { other -> rulesOut(other, member, sifted) }
        }
    }

/** Whether [other], given on the line, leaves no room for [member] beside it; see [membersRuledOutBy]. */
private fun Command.rulesOut(
    other: HolderSpec,
    member: HolderSpec,
    sifted: Sifted,
): Boolean = when {
    other === member -> false
    other is ArgumentSpec && other.absentWhen === member -> other.gathersOn(sifted)
    else -> supplied(other, sifted)
}

/**
 * What a line leaves no room for: [excluded], the other members of a set it gives one of, in their positive half
 * only, since a negation selects nothing; and [repeats], the inputs it already gives that refuse a repeat, in
 * either half.
 */
private class Refusals(val excluded: Set<HolderSpec>, val repeats: Set<HolderSpec>) {
    operator fun contains(spec: HolderSpec): Boolean = spec in excluded || spec in repeats
}

/** What [rest], the line without the word being completed, leaves no room for on [cmd]. */
private fun Cli.refusedBy(cmd: Command, rest: List<String>): Refusals {
    val walked = walkLine(rest)
    if (walked.cmd !== cmd) return Refusals(emptySet(), emptySet())
    val sifted = cmd.sift(walked.segment, walked.globalAcc, walked.segmentPositions)
    val acc = walked.globalAcc
    val repeats = (cmd.options + cmd.flags).filter { acc.refusesRepeat(it) && sifted.holds(it) } +
        globalSpecs.filter { acc.refusesRepeat(it) && acc.holds(it) }
    return Refusals(cmd.membersRuledOutBy(sifted), repeats.toSet())
}

/** Whether the line gave [spec] in any of its forms. */
private fun Sifted.holds(spec: NamedSpec): Boolean = when (spec) {
    is FlagSpec -> (flags[spec] ?: 0) > 0
    is OptionSpec -> options[spec]?.isNotEmpty() == true
}

/**
 * Whether the short [cluster] of flags is refused on its line: a letter the rest of the line already [refused],
 * a letter that refuses a repeat named twice, or two members of one exclusive set, read off [line], the line
 * with the cluster in it.
 */
private fun Command.clusterRefused(
    cluster: String,
    refused: Refusals,
    line: Sifted,
    globalAcc: GlobalAccumulator,
): Boolean {
    val lookup = flagLookup(globalAcc)
    // Each letter's flag and whether the letter is its positive half, looked up in the parse's order.
    val hits = cluster.drop(1).mapNotNull { ch ->
        val short = ch.toString()
        lookup.firstOrNull { short in it.shorts }?.let { it to true }
            ?: lookup.firstOrNull { short in it.negativeShorts }?.let { it to false }
    }
    if (hits.any { (spec, positive) -> spec in refused.repeats || positive && spec in refused.excluded }) return true
    val letters = hits.map { it.first }
    if (letters.groupingBy { it }.eachCount().any { (spec, n) -> n > 1 && globalAcc.refusesRepeat(spec) }) return true
    return constraints
        .filterNot { it.arity == ConstraintArity.LastWins }
        .any { set -> set.members.count { supplied(it, line) } > 1 && set.members.any { it in letters } }
}

/**
 * The slot the operand at [index] would bind to on a line of [operands] operands in all, computed the way
 * `bindPositionals` assigns one: a variadic hands the fixed slots after it what they still need, so
 * `cp a b <TAB>` lands on DEST rather than on a third SOURCE, while `cp a <TAB> b` lands on a SOURCE, exactly
 * where the parse would put that word. Null once the line holds more operands than the slots take, [min] and [cap]
 * giving a variadic's least and most on this line, which is where the parse reports too many arguments whatever
 * the word becomes.
 */
private fun List<ArgumentSpec>.slotForOperand(
    index: Int,
    operands: Int,
    min: (ArgumentSpec) -> Int,
    cap: (ArgumentSpec) -> Int,
): ArgumentSpec? {
    val room = sumOf { spec -> if (spec.cardinality is Cardinality.Multiple) cap(spec).toLong() else 1L }
    if (operands > room) return null
    var filled = 0
    for ((slotIndex, spec) in withIndex()) {
        // Every fixed slot after a variadic is Required (BuilderValidation's rule), so what they claim is exact.
        val take = when (spec.cardinality) {
            // Floored at the variadic's own minimum: a line too short to meet it is one the parse rejects,
            // so handing those operands to the slots behind it would complete toward a rejected reading.
            is Cardinality.Multiple ->
                (operands - filled - (size - slotIndex - 1)).coerceAtLeast(min(spec)).coerceAtMost(cap(spec))

            else -> 1
        }
        if (index < filled + take) return spec
        filled += take
    }
    return null
}

/**
 * Walks [argv] down the subcommand tree as parse() walks it, stepping over the words [routesTransparently] lets
 * it pass (`g --help build` reaches build), returning the deepest command reached and the indices of the
 * tokens left over for it.
 */
private fun Cli.walkTo(argv: List<String>): Pair<Command, List<Int>> {
    val infer = abbreviation == Abbreviation.All
    val globalFlagShorts = globalSpecs.filterIsInstance<FlagSpec>().flatMap { it.shorts }.toSet()
    // Keep the while form: a for/withIndex loop reassigning `cmd` miscompiles on Kotlin/Native 2.4.10 debug
    // builds (KT-87261, fixed in 2.4.20-Beta2) and passes on the JVM; retest before converting.
    var cmd: Command = this
    val kept = argv.indices.toMutableList()
    var idx = 0
    while (idx < argv.size) {
        val child = (cmd.resolveSubcommand(argv[idx], infer) as? SubcommandMatch.One)?.command
        if (child != null) {
            cmd = child
            kept -= idx
        } else if (!routesTransparently(argv[idx], globalFlagShorts)) {
            break
        }
        idx += 1
    }
    return cmd to kept
}

/**
 * `--color`'s own value candidates for the attached form under the cursor (`--color=<cur>`, which zsh and fish
 * never split), or null when the cursor is not in it. The word after a bare `--color` is no value of it, as
 * getopt_long reads an optional argument, so that word completes as whatever it is.
 *
 * Answered here because `--color` is a meta-option rather than a declared spec, so the value-option
 * branches below never resolve it. [ArgvScan.matched] at the cursor's position rather than a string
 * comparison, so an abbreviated `--col` binds here exactly when the parser would bind it.
 */
private fun colorValueCandidates(
    current: String,
    cursorAt: Int,
    scan: ArgvScan,
    description: String,
): List<Candidate>? {
    val eq = current.indexOf('=')
    if (eq >= 0 && scan.matched(current.take(eq), cursorAt) == "color") {
        return colorModeCandidates(current.substring(eq + 1)).asWholeWord(current.take(eq + 1), description)
    }
    return null
}

/**
 * COLOR_MODE_NAMES filtered by the partial value under the cursor, mirroring a user `.choice()` option's
 * own value candidates (see [candidatesFor]'s `choices != null` branch).
 */
private fun colorModeCandidates(current: String): List<Candidate> =
    COLOR_MODE_NAMES.filter { it.startsWith(current) }.map { Candidate(it) }

/**
 * This spec's candidates for the word under the cursor, prefix-filtered by [current] by default (matching
 * on each candidate's [Candidate.value] only, never its description). The raw path sentinel
 * ([COMPLETE_FILES]) is exempt, since the shell expands it natively.
 *
 * A `.completeWith` provider is arbitrary user code run synchronously on every Tab press, and the generated
 * scripts call it without redirecting stderr — so [runCatching] here must catch [Throwable], not just
 * [Exception], and treat a failure as no candidates, the same graceful-degradation contract an aborted
 * [CompletionScope] read already has.
 */
private fun ValueSpec.candidatesFor(
    current: String,
    words: List<String>,
    values: Lazy<BoundLine>,
): List<Candidate> = when {
    isPath -> listOf(Candidate(COMPLETE_FILES))
    complete != null -> runCatching { CompletionScope(current, words, values).apply(complete!!) }
        .getOrNull()
        ?.let { scope ->
            // The directive starts with a space so it can never collide with a real candidate, which also
            // means it never matches a non-empty current word; completeFiles() already made the collected
            // list exclusive, so the prefix filter would only ever discard the very thing it just set up.
            if (completePrefixFilter && !scope.collected.isNativeFileRequest()) {
                scope.collected.filter { it.value.startsWith(current) }
            } else {
                scope.collected
            }
        }
        .orEmpty()

    // In the case the parser matches in: with ignoreCase `--sort=SIZE` binds, so Tab must offer `size` there too.
    // The provider branch above stays case-sensitive: its candidates go through an arbitrary converter whose own
    // matching klap does not know.
    choices != null -> choices!!.filter { it.startsWith(current, ignoreCase = choiceIgnoreCase) }.map { Candidate(it) }
    else -> emptyList()
}

/**
 * The long option/short whose value [token] (a whole `--long`/`-s` word) is being completed, if any;
 * globals included since they are position-independent.
 *
 * A long resolves through [resolveLong] against [longMatchPool], the same call and the same pool [sift]
 * binds one with, so an abbreviation the parse accepts completes THAT option's values instead of falling
 * through to the next operand slot. Prefix resolution is the long half alone: a short is one character,
 * which is already its full spelling. Hidden options are NOT filtered out here (unlike the name-completion
 * block above): hidden only suppresses a NAME from `--<TAB>` suggestions, but the option itself is still
 * fully parseable, so its value must still resolve and complete once its name has been typed.
 */
private fun Command.matchingValueOption(token: String, globalAcc: GlobalAccumulator): OptionSpec? {
    val pool = options + globalAcc.optionSpecs
    if (!token.startsWith("--")) return pool.byName(token)
    // An ambiguous abbreviation binds nothing at all, so completing one possibility's values here would
    // offer the very reading the parse refuses to pick.
    val long = when (val resolved = resolvedLong(token, globalAcc)) {
        is NameMatch.Exact -> resolved.name
        is NameMatch.Prefix -> resolved.name
        is NameMatch.Ambiguous, NameMatch.None -> return null
    }
    return pool.byName("--$long")
}

/** How the parse resolves the whole `--long` [token], against the same pool and with the same inference. */
private fun Command.resolvedLong(token: String, globalAcc: GlobalAccumulator): NameMatch =
    if (token.startsWith("--")) {
        resolveLong(token.removePrefix("--"), longMatchPool(globalAcc), globalAcc.inferNames)
    } else {
        NameMatch.None
    }

/**
 * The value-taking option a completed [prev] token would consume the NEXT word for: a whole `--long`/`-s`
 * name, or a short cluster whose trailing char is a value option with nothing glued after it (`-vp` -> `p`),
 * peeling leading flag chars exactly like [sift]'s short-cluster walk. Globals are included since they are
 * position-independent. Null if [prev] is not such a token or if [prev] names an option with optional value; a
 * `.gluedValue()` option is returned, though the word after it is no value of it.
 */
private fun Command.trailingValueOption(prev: String, globalAcc: GlobalAccumulator): OptionSpec? {
    val resolved = matchingValueOption(prev, globalAcc) ?: run {
        // Short cluster only: a whole `--opt` is already resolved above, and `--`/`-` are not clusters.
        if (!prev.startsWith("-") || prev.startsWith("--")) return null
        val chars = prev.removePrefix("-")
        if (refusesCluster(chars, globalAcc)) return null
        val optIndex = firstNonFlagShort(chars, globalAcc)
        // The option must be the cluster's LAST char: sift only lets a trailing bare option char take the
        // following token, since a glued value (`-vp8`) ends the option itself.
        if (optIndex < 0 || optIndex != chars.lastIndex) return null
        matchingValueOption("-${chars[optIndex]}", globalAcc)
    }
    // A bare optional-value option consumes no following word (see Parser's consumption branch), so the
    // cursor after it is on an OPERAND. Offering the option's values here would advertise a binding the
    // parser will not make. The attached `--opt=` path is handled by attachedValueOption and is unaffected.
    return resolved?.takeUnless { it.valueOptional }
}

/**
 * The index of the first character of [chars] that is neither a flag or its negative short, one of klap's own
 * shorts, nor part of a digit run the number input claims, or -1 when every character is one.
 */
private fun Command.firstNonFlagShort(chars: String, globalAcc: GlobalAccumulator): Int {
    val flagSpecs = flagLookup(globalAcc)
    var j = 0
    while (j < chars.length) {
        val run = numberRunAt(chars, j, globalAcc)
        if (run != null) {
            j += run.length
            continue
        }
        val short = chars[j].toString()
        val isFlag = flagSpecs.any { short in it.shorts || short in it.negativeShorts }
        if (!isFlag && !globalAcc.isBuiltinShort(chars[j])) return j
        j += 1
    }
    return -1
}

/**
 * This command's flags plus position-independent globals, and the flags of a flag-spelled child the line may
 * still name, for peeling leading flag chars off a short cluster in [firstNonFlagShort].
 */
private fun Command.flagLookup(globalAcc: GlobalAccumulator): List<FlagSpec> =
    flags + globalAcc.flagSpecs + flagSubcommands.flatMap { it.flags }

/**
 * The (option, partial-value) an ATTACHED word under the cursor names, if any: `--opt=partial` glues the
 * long option and its still-being-typed value into one word, and a short cluster's own option char takes
 * the word's remainder as its glued value (`-tr`), peeling any leading flag chars first exactly like
 * [sift]'s short-cluster walk (so `-vtr` still resolves `-t`'s value). Needed because zsh's `$words` array
 * (and fish's `commandline -ct`) never split a word at `=`, so the space-separated `--opt value` handling in
 * [completeCandidates] never sees this shape. A bare `-o` names its option with an empty value only when the
 * value is optional, as it is then glued or absent; otherwise it is left to flag-name completion.
 */
private fun Command.attachedValueOption(current: String, globalAcc: GlobalAccumulator): Pair<OptionSpec, String>? {
    if (!current.isFlagLike()) return null

    // Long form: `--opt=partial` glues the option name and its value at the `=`; split there.
    if (current.startsWith("--")) {
        val body = current.removePrefix("--")
        val eq = body.indexOf('=')
        if (eq < 0) return null
        return matchingValueOption(
            "--${body.take(eq)}",
            globalAcc,
        )?.let { it to body.drop(eq + 1) }
    }

    // Short cluster: peel leading flag chars; the first non-flag char is the option, the rest its glued value.
    val chars = current.removePrefix("-")
    if (refusesCluster(chars, globalAcc)) return null
    val optIndex = firstNonFlagShort(chars, globalAcc)
    if (optIndex < 0) return null

    val partial = chars.substring(optIndex + 1)
    val option = matchingValueOption("-${chars[optIndex]}", globalAcc) ?: return null
    return (option to partial).takeIf { partial.isNotEmpty() || option.valueAttachedOnly }
}

/** The spec answering to a whole `--long`/`-s` token, if any. */
private fun <S : NamedSpec> List<S>.byName(token: String): S? = when {
    token.startsWith("--") -> firstOrNull { token.removePrefix("--") in it.longs }
    token.startsWith("-") -> firstOrNull { token.removePrefix("-") in it.shorts }
    else -> null
}
