package com.fromwau.klap.internal.render

import com.fromwau.klap.COLOR_MODE_NAMES
import com.fromwau.klap.COMPLETE_FILES
import com.fromwau.klap.Cli
import com.fromwau.klap.Command
import com.fromwau.klap.CompletionScope
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.SubcommandMatch
import com.fromwau.klap.builtinScan
import com.fromwau.klap.internal.parse.ArgvScan
import com.fromwau.klap.internal.parse.END_OF_OPTIONS
import com.fromwau.klap.internal.parse.GlobalAccumulator
import com.fromwau.klap.internal.parse.NameMatch
import com.fromwau.klap.internal.parse.Sifted
import com.fromwau.klap.internal.parse.accumulator
import com.fromwau.klap.internal.parse.activeArguments
import com.fromwau.klap.internal.parse.completionValues
import com.fromwau.klap.internal.parse.isFlagLike
import com.fromwau.klap.internal.parse.longMatchPool
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
import com.fromwau.klap.positionIndependentLongs
import com.fromwau.klap.resolveSubcommand

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
 * The completion planner behind the hidden `__complete` subcommand, correct at any depth: given the
 * [words] typed so far (last = the partial word under the cursor, mirroring `COMP_WORDS[@]:1`), return
 * every candidate valid at that cursor. Per-slot rules are commented at their branches; testable without a shell.
 */
internal fun Cli.completeCandidates(words: List<String>): List<Candidate> {
    // Hold the cursor's own (maybe half-typed) word out of the strips below: stripping it would discard the
    // very context being completed.
    val current = words.lastOrNull().orEmpty()
    val head = words.dropLast(1)

    val builtinPool = positionIndependentLongs()
    val scan = builtinScan(head, builtinPool)
    // Whether the RAW head's last word was already consumed as the previous option's value. Any branch that
    // reads a word as an option still WAITING for one has to ask this of that word first, or completion
    // offers a binding parse will not make (see [optionValueSlots]); the `prev` branch below asks it of the
    // token IT reads, which is not always this one.
    val trailingIsConsumedValue = head.lastIndex in scan.valueSlots

    // Must run on the RAW head, before the strip below removes the very token the space form matches on.
    if (builtins.color && !trailingIsConsumedValue) {
        colorValueCandidates(head, current, scan)?.let { return it }
    }

    // Strip the position-independent modifiers exactly as parse() does before its own walk: --json first, so
    // it can't be mistaken for a space-form --color's value, then the user's globals, so a leading or
    // interspersed one doesn't stop the walk below (`-v issue show` resolves like `issue show`). Skipping this
    // breaks the walk ON the modifier at token 0 and completes the ROOT instead of the typed subcommand.
    val withoutJson = if (builtins.json) scan.strip("json") else scan
    val withoutColor = if (builtins.color) withoutJson.stripValued("color") else withoutJson
    // Its positions are read only to place `prev` below, never to order one global against another: the
    // sift below is the only pass here that records an order, so its observations win unconditionally.
    val preStrip = globalSpecs.siftGlobals(withoutColor, builtinPool)
    val strippedHead = preStrip.cleaned
    val globalSift = preStrip.sift

    val (cmd, segment) = walkTo(strippedHead)
    // The last token the walk kept, unless it was consumed as a value: `mygrep -e --tag <cur>` gave "--tag"
    // to `-e`, so `-e` is satisfied and the cursor is on an OPERAND, exactly as in `mygrep -e x <cur>`.
    // Nothing was dropped from the END of `cleaned`, so this token's argv index is the last one reported.
    val prev = segment.lastOrNull()?.takeUnless { preStrip.positions.last() in scan.valueSlots }

    // Built via accumulator() to stay in lockstep with parse(), but only its spec lists are read here: they
    // are what lets the segment sift below recognize a global hiding in a mixed short cluster (`-fr`).
    val globalAcc = globalSift.accumulator(globalSpecs, version, builtins, metaOptions, declaredLongs, abbreviation)
    // One segment walk: the flag-name branch reads which constraint members are already on the line, the
    // positional branch the positional count. Lazy for the value-completion branches, which need it only
    // when the option under the cursor has a provider that reads an accessor. Forcing it on a Tab press is
    // safe anywhere: sift is a pure token walk with no user code in it, unlike the bind behind [values].
    val sifted by lazy(LazyThreadSafetyMode.NONE) { cmd.sift(segment, globalAcc) }
    // sifted.error is deliberately ignored: a line still being typed is expected to be malformed, and a Tab
    // press must never fail. Whatever the walk did collect around the offending token is what we complete.

    // The bind runs user converters and validators, so it must not happen on a Tab press whose slot has no
    // provider; a provider reading an accessor is what forces it.
    val values = lazy(LazyThreadSafetyMode.NONE) { completionValues(cmd, sifted, globalAcc) }

    // An option value under the cursor: `prev` names a known value-taking option (a whole `--long`/`-s`, or
    // the trailing option char of a short cluster like `-vp`), so complete only that value.
    val valueOption =
        prev?.takeIf { it.isFlagLike() }?.let { cmd.trailingValueOption(it, globalAcc) }
    if (valueOption != null) return valueOption.candidatesFor(current, words, values)

    // An ATTACHED option value: the name and its (partial) value share ONE word (`--opt=partial` or glued
    // `-opartial`). Complete the value, filtered by the partial after the `=`/short char, not the whole word.
    val attached = cmd.attachedValueOption(current, globalAcc)
    if (attached != null) {
        val (attachedOption, partial) = attached
        return attachedOption.candidatesFor(partial, words, values)
    }

    // A flag-shaped partial word (`-`, `--`, `-v`, `--verb`): offer this command's option/flag names plus
    // the globals and built-ins, so flag names complete like git/kubectl. Suppressed after `--`. Each
    // candidate carries the spec's own `help` as its description (blank -> bare value, per Candidate).
    if (current.startsWith("-") && END_OF_OPTIONS !in segment) {
        val names = mutableListOf<Candidate>()
        val ruledOut = cmd.membersRuledOutBy(sifted)
        (cmd.options + cmd.flags + globalSpecs).filterNot { it.hidden || it in ruledOut }.forEach { spec ->
            val help = spec.help.takeIf { it.isNotBlank() }
            spec.longs.forEach { long -> names += Candidate("--$long", help) }
            spec.shorts.forEach { names += Candidate("-$it", help) }
            if (spec is FlagSpec && spec.negatable) {
                spec.negativeLongs.forEach { names += Candidate("--$it", help) }
                spec.negativeShorts.forEach { names += Candidate("-$it", help) }
            }
        }
        // Wording comes from BuiltinOptionHelp and each gate matches Help.kt's own switch, the same source its
        // "Global options" section renders, so --help and tab completion never disagree on what a built-in
        // does or whether it exists. The app's own specs are appended above, so distinctBy below keeps THEIR
        // description when a freed name (`-h`, `--json`) has since been re-declared by the app.
        if (builtins.helpShort) names += Candidate("-h", BuiltinOptionHelp.HELP)
        names += Candidate("--help", BuiltinOptionHelp.HELP)
        // --help-all is only advertised where the command has visible subcommands to expand, mirroring
        // Help.kt's own gate (`subcommands.any { !it.hidden }`) so the two never disagree on when it exists.
        if (cmd.subcommands.any { !it.hidden }) names += Candidate("--help-all", "Show help for every subcommand")
        if (builtins.json) names += Candidate("--json", BuiltinOptionHelp.JSON)
        if (builtins.color) names += Candidate("--color", BuiltinOptionHelp.COLOR)
        if (version != null) names += Candidate("--version", BuiltinOptionHelp.VERSION)
        // A single-command root advertises these in --help's Global options, so offer the names here too.
        if (metaOptions) {
            if (builtins.completion) names += Candidate("--completion", BuiltinOptionHelp.COMPLETION)
            if (builtins.docs) names += Candidate("--docs", BuiltinOptionHelp.DOCS)
        }
        // A short cluster in progress (`-r`, `-rl`): guideline 5 bundles one-character options into a
        // single token, so offer each remaining short as a continuation of what has been typed rather
        // than only the exact match. Gated on every typed char being a FLAG, because a value-taking
        // option ends the cluster by consuming the remainder of the token, so nothing may follow it.
        val typedShorts = current.removePrefix("-")
        if (!current.startsWith("--") && typedShorts.isNotEmpty()) {
            val flagSpecs = cmd.flagLookup(globalAcc)
            if (typedShorts.all { flagSpecs.byName("-$it") != null }) {
                names += names
                    .filter { it.value.length == 2 && !it.value.startsWith("--") }
                    .filterNot { it.value[1] in typedShorts }
                    .map { Candidate(current + it.value[1], it.description) }
            }
        }
        return names.distinctBy { it.value }.filter { it.value.startsWith(current) }
    }

    val positionalIndex = sifted.positionals.size
    // The bind's own slot list, not cmd.arguments: a slot an .absentWhen() trigger removed is not on this
    // line at all, and offering its values would put completion at odds with both the parse and --help.
    val slots = cmd.activeArguments(sifted)
    val positional = slots.slotForOperand(positionalIndex)?.takeUnless { it.hidden }

    val positionalCandidates = positional?.candidatesFor(current, words, values).orEmpty()
    // A `.file()` slot or a completeFiles() provider must reach the shell as the LONE directive line, even at
    // a hybrid parent's first positional (subcommands beside its own operand, git-style), where a
    // subcommand name would otherwise sit next to it and be read as literal candidate text instead of
    // triggering native file completion.
    if (positionalCandidates.isNativeFileRequest()) return positionalCandidates

    // A subcommand name can only occupy the first positional slot; past that, only positional values apply.
    // Each visible subcommand contributes its name AND its aliases, so `myapp l<TAB>` also offers `ls`; both
    // carry that command's own `help` as their description (blank -> bare value, per Candidate).
    val subcommands = if (positionalIndex == 0) {
        cmd.subcommands
            .filterNot { it.hidden }
            .flatMap { sub ->
                val help = sub.description.takeIf { it.isNotBlank() }
                listOf(Candidate(sub.name, help)) + sub.aliases.map { Candidate(it, help) }
            }
            .filter { it.value.startsWith(current) }
    } else {
        emptyList()
    }
    return subcommands + positionalCandidates
}

/**
 * The inputs a name completion must not offer: every OTHER member of an [ConstraintArity.ExactlyOne] or
 * [ConstraintArity.AtMostOne] set that already has one supplied, since a second member is a usage error
 * under both. A [ConstraintArity.LastWins] set rules out nothing — it is an override rule where any number
 * of members is legal (`rm -i -f`), which is why the parse's own constraint check skips it too. The
 * supplied member itself stays offered — some tools accept a repeat, and dropping it would change the
 * list's shape mid-typing. Supplied-ness comes from [supplied], the same predicate the parse enforces the
 * constraint with.
 */
private fun Command.membersRuledOutBy(sifted: Sifted): Set<HolderSpec> = constraints
    .filterNot { constraint -> constraint.arity == ConstraintArity.LastWins }
    .filter { constraint -> constraint.members.any { supplied(it, sifted) } }
    .flatMapTo(mutableSetOf()) { constraint -> constraint.members.filterNot { supplied(it, sifted) } }

/**
 * The slot the operand at [index] would bind to, computed the way `bindPositionals` assigns one, on a line
 * whose LAST operand is the word under the cursor. That assumption is what gives the `SRC... DEST` shape
 * its destination back: a variadic hands the fixed slots after it what they still need, so `cp a b <TAB>`
 * lands on DEST rather than on a third SOURCE, exactly where the parse would put that word. Null once no
 * slot can hold it, which is where the parse reports too many arguments.
 */
private fun List<ArgumentSpec>.slotForOperand(index: Int): ArgumentSpec? {
    val operands = index + 1
    var filled = 0
    for ((slotIndex, spec) in withIndex()) {
        // Every fixed slot after a variadic is Required (BuilderValidation's rule), so what they claim is exact.
        val take = when (val cardinality = spec.cardinality) {
            // Floored at the variadic's own minimum: a line too short to meet it is one the parse rejects,
            // so handing those operands to the slots behind it would complete toward a rejected reading.
            is Cardinality.Multiple ->
                (operands - filled - (size - slotIndex - 1)).coerceAtLeast(cardinality.min)

            else -> 1
        }
        if (index < filled + take) return spec
        filled += take
    }
    return null
}

/**
 * Walks [argv] down the subcommand tree as far as it routes, returning the deepest command reached and the
 * tokens left over for it. Extracted so the planner binds the resolved command to a `val`: two lazy blocks
 * below close over it, and while the walk always completes before either is forced, a captured `var` would
 * leave that a matter of reading order rather than of the type.
 */
private fun Cli.walkTo(argv: List<String>): Pair<Command, List<String>> {
    var cmd: Command = this
    var rest = argv
    while (rest.isNotEmpty()) {
        val child = (cmd.resolveSubcommand(rest.first(), abbreviation == Abbreviation.All) as? SubcommandMatch.One)
            ?.command
            ?: break
        cmd = child
        rest = rest.drop(1)
    }
    return cmd to rest
}

/**
 * `--color`'s own value candidates, for its space form (`--color <cur>`) and its attached form
 * (`--color=<cur>`, which zsh and fish never split), or null when the cursor is not in either.
 *
 * Answered here because `--color` is a meta-option rather than a declared spec, so the value-option
 * branches below never resolve it and the cursor would fall through to completing the NEXT token.
 * [ArgvScan.matched] rather than a string comparison, so an abbreviated `--col` binds here exactly when
 * the parser would bind it.
 */
private fun colorValueCandidates(head: List<String>, current: String, scan: ArgvScan): List<Candidate>? {
    if (head.lastOrNull()?.let(scan::matched) == "color") return colorModeCandidates(current)
    val eq = current.indexOf('=')
    if (eq >= 0 && scan.matched(current.take(eq)) == "color") return colorModeCandidates(current.substring(eq + 1))
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
    values: Lazy<Map<HolderSpec, Any?>>,
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

    // ignoreCase because [resolveChoice] lowers both sides to match, so `--sort=SIZE` binds; a
    // case-sensitive filter here would leave Tab dead on a spelling the parser accepts. The provider
    // branch above stays case-sensitive: its candidates go through an arbitrary converter whose own
    // matching klap does not know.
    choices != null -> choices!!.filter { it.startsWith(current, ignoreCase = true) }.map { Candidate(it) }
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
    val resolved = resolveLong(
        token.removePrefix("--"),
        longMatchPool(globalAcc),
        globalAcc.inferNames,
    )
    // An ambiguous abbreviation binds nothing at all, so completing one possibility's values here would
    // offer the very reading the parse refuses to pick.
    val long = when (resolved) {
        is NameMatch.Exact -> resolved.name
        is NameMatch.Prefix -> resolved.name
        is NameMatch.Ambiguous, NameMatch.None -> return null
    }
    return pool.byName("--$long")
}

/**
 * The value-taking option a completed [prev] token would consume the NEXT word for: a whole `--long`/`-s`
 * name, or a short cluster whose trailing char is a value option with nothing glued after it (`-vp` -> `p`),
 * peeling leading flag chars exactly like [sift]'s short-cluster walk. Globals are included since they are
 * position-independent. Null if [prev] is not such a token or if [prev] names an option with optional value.
 */
private fun Command.trailingValueOption(prev: String, globalAcc: GlobalAccumulator): OptionSpec? {
    val resolved = matchingValueOption(prev, globalAcc) ?: run {
        // Short cluster only: a whole `--opt` is already resolved above, and `--`/`-` are not clusters.
        if (!prev.startsWith("-") || prev.startsWith("--")) return null
        val chars = prev.removePrefix("-")
        val flagSpecs = flagLookup(globalAcc)
        val optIndex = chars.indexOfFirst { flagSpecs.byName("-$it") == null }
        // The option must be the cluster's LAST char: sift only lets a trailing bare option char take the
        // following token, since a glued value (`-vp8`) ends the option itself.
        if (optIndex < 0 || optIndex != chars.lastIndex) return null
        matchingValueOption("-${chars[optIndex]}", globalAcc)
    }
    // A bare optional-value option consumes no following word (see Parser's consumption branch), so the
    // cursor after it is on an OPERAND. Offering the option's values here would advertise a binding the
    // parser will not make. The attached `--opt=` path is handled by attachedValueOption and is unaffected.
    return resolved?.takeUnless { it.bareValue != null }
}

/**
 * This command's flags plus position-independent globals, for peeling leading flag chars off a short
 * cluster in [trailingValueOption] and [attachedValueOption].
 */
private fun Command.flagLookup(globalAcc: GlobalAccumulator): List<FlagSpec> = flags + globalAcc.flagSpecs

/**
 * The (option, partial-value) an ATTACHED word under the cursor names, if any: `--opt=partial` glues the
 * long option and its still-being-typed value into one word, and a short cluster's own option char takes
 * the word's remainder as its glued value (`-tr`), peeling any leading flag chars first exactly like
 * [sift]'s short-cluster walk (so `-vtr` still resolves `-t`'s value). Needed because zsh's `$words` array
 * (and fish's `commandline -ct`) never split a word at `=`, so the space-separated `--opt value` handling in
 * [completeCandidates] never sees this shape; a bare `-o` (no attached remainder) falls through to ordinary
 * flag-name completion.
 */
private fun Command.attachedValueOption(
    current: String,
    globalAcc: GlobalAccumulator,
): Pair<OptionSpec, String>? {
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
    val flagSpecs = flagLookup(globalAcc)
    val chars = current.removePrefix("-")
    val optIndex = chars.indexOfFirst { flagSpecs.byName("-$it") == null }
    if (optIndex < 0) return null

    val partial = chars.substring(optIndex + 1)
    if (partial.isEmpty()) return null

    return matchingValueOption("-${chars[optIndex]}", globalAcc)?.let { it to partial }
}

/** The spec answering to a whole `--long`/`-s` token, if any. */
private fun <S : NamedSpec> List<S>.byName(token: String): S? = when {
    token.startsWith("--") -> firstOrNull { token.removePrefix("--") in it.longs }
    token.startsWith("-") -> firstOrNull { token.removePrefix("-") in it.shorts }
    else -> null
}
