package com.fromwau.klap.internal.parse

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.Command
import com.fromwau.klap.builtinLongTable
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.takesNextWord

/**
 * argv as the position-independent built-in scans see it: the [tokens] still in play, the index each one
 * holds in the ORIGINAL argv ([positions]), and the original indices a value-taking option claimed as its
 * argument ([valueSlots]).
 *
 * Every scan reads through this rather than off a raw list, so the rule that outranks all of them is
 * stated once instead of at each call site: a token standing in a value slot is that option's value,
 * however it is spelled. A strip returns a NEW view whose positions still index the original argv, so the
 * rule survives however many strips have already run over the line.
 *
 * [pools] gives, for an original argv index, the long surface a spelling there resolves against: the pool of
 * the command in scope at that token ([builtinScan]), which is the one that command's own sift resolves against
 * too. [infer] is the root's own mode reduced to the one question a scan asks: may a long spelling resolve by
 * prefix here. [scopes] gives the command whose segment an original argv index stands on, null where every token
 * is the root's.
 */
internal class ArgvScan(
    private val pools: (position: Int) -> LongPool,
    val infer: Boolean,
    val valueSlots: Set<Int>,
    val tokens: List<String>,
    val positions: List<Int> = tokens.indices.toList(),
    private val scopes: (position: Int) -> Command? = { null },
) {
    /** Where the options end: the marker's index, or the whole list when the line carries none. */
    private val headSize: Int = tokens.endOfOptions(positions, valueSlots).let { if (it < 0) tokens.size else it }

    private fun isOpen(i: Int): Boolean = i < headSize && positions[i] !in valueSlots

    /** The indices a scan may act on: before the end-of-options marker and outside every value slot. */
    private val open: List<Int> = (0 until headSize).filter { isOpen(it) }

    /** The long pool a spelling at the original argv [position] resolves against. */
    fun poolAt(position: Int): LongPool = pools(position)

    /** The command whose segment the token at [i] stands on, a subcommand's name standing on its parent's. */
    fun commandAt(i: Int): Command? = scopes(positions[i])

    /** Whether the token at [i] stands on the root's own segment, before the first subcommand's name. */
    fun atRoot(i: Int): Boolean = commandAt(i).let { it == null || it is Cli }

    /** The pool entry the token at [i] names, exactly or as an unambiguous abbreviation; null when it names none. */
    fun matched(i: Int): String? = matched(tokens[i], positions[i])

    /** The pool entry [token] names were it standing at the original argv [position]. */
    fun matched(token: String, position: Int): String? = matchedLong(token, pools(position), infer)

    /**
     * Whether a bare (valueless) token names the built-in [long]. The `=value` shape is deliberately not a
     * match: every built-in this answers for is boolean, and its `--<name>=value` form is reported before
     * any of these scans run.
     */
    fun names(long: String): Boolean = firstNaming(long) != null

    /** Where the first token a scan may act on that passes [test] stands, for ordering two built-ins; null if none. */
    fun firstOpen(test: (String) -> Boolean): Int? = open.firstOrNull { test(tokens[it]) }

    /** [firstOpen], handing [test] each token's index in [tokens] beside it. */
    fun firstOpenIndexed(test: (Int, String) -> Boolean): Int? = open.firstOrNull { test(it, tokens[it]) }

    /** Where the first bare token naming one of [longs] stands; see [firstOpen]. */
    fun firstNaming(vararg longs: String): Int? = open.firstOrNull { '=' !in tokens[it] && matched(it) in longs }

    /** Where the first `--<name>=value` token naming one of [longs] stands. */
    fun firstAttached(longs: Collection<String>): Int? = open.firstOrNull { '=' in tokens[it] && matched(it) in longs }

    /** Where the first token naming [long] stands, bare or with `=value`. */
    fun firstSpelling(long: String): Int? = open.firstOrNull { matched(it) == long }

    /** Where the last token naming [long] stands, bare or with `=value`. */
    fun lastSpelling(long: String): Int? = open.lastOrNull { matched(it) == long }

    /** The `=value` of the token at [i], or null when it carries none. */
    fun attached(i: Int): String? = tokens[i].takeIf { '=' in it }?.substringAfter('=')

    /**
     * The value of a position-independent `--<long> value` / `--<long>=value` meta-option that requires one:
     * the value, `null` when the option is absent, or MissingOptionValue when nothing follows it. The LAST
     * occurrence wins, the same rule value options follow generally. The word after it is the value whatever it
     * is spelled, as getopt_long takes a required argument (`--completion --json` asks for the shell `--json`).
     */
    fun value(long: String): Result<String?, CliError> {
        val at = lastSpelling(long) ?: return Result.Success(null)
        val value = attached(at) ?: tokens.getOrNull(at + 1)
        // [long] is the bare pool key; an error names an option the way every declared one is named, dashed.
        return value?.let { Result.Success(it) } ?: Result.Error(CliError.MissingOptionValue("--$long"))
    }

    /**
     * This view with every bare token naming [long] removed. A `--<long>=value` token is left in place,
     * since that shape is a usage error the caller has already reported.
     */
    fun strip(long: String): ArgvScan =
        keeping(open.filterTo(mutableSetOf()) { '=' !in tokens[it] && matched(it) == long })

    /** This view with every token naming [long] removed, bare or with `=value`: an optional-value meta-option. */
    fun stripSpellings(long: String): ArgvScan = keeping(open.filterTo(mutableSetOf()) { matched(it) == long })

    /**
     * This view with every `--<long>`/`--<long>=value` occurrence removed, along with its value token in the
     * space form, whatever that token is spelled: [value]'s reading of a required-value meta-option.
     */
    fun stripValued(long: String): ArgvScan {
        val dropped = mutableSetOf<Int>()
        for (i in open) {
            if (matched(i) != long) continue
            dropped += i
            if ('=' !in tokens[i] && i + 1 < tokens.size) dropped += i + 1
        }
        return keeping(dropped)
    }

    private fun keeping(dropped: Set<Int>): ArgvScan {
        val kept = tokens.indices.filterNot { it in dropped }
        return ArgvScan(pools, infer, valueSlots, kept.map { tokens[it] }, kept.map { positions[it] }, scopes)
    }
}

/**
 * [argv] as every position-independent scan reads it: each token resolves against the pool of the command in
 * scope where it stands, and the value slots are read off the tree's full arity ([ArityWalk]).
 */
internal fun Cli.builtinScan(argv: List<String>): ArgvScan {
    val walk = ArityWalk(this).apply { walk(argv) }
    return ArgvScan(walk::poolAt, abbreviation != Abbreviation.None, walk.slots, argv, scopes = walk::commandAt)
}

/**
 * What a scanned token does: whether it claims the token after it as a value ([takesNext]), and whether the
 * subcommand walk carries on past it ([routesOn]) where only the scan sees the word: a global, `--json` or `--color`,
 * which klap strips before the walk, routes on, and klap's other built-ins stop it. [routesOn] is null for a word
 * the command in scope reads, which routes as [SubcommandRouter] reads it. [stripped] says the parse removes the word
 * before the command reads its own, so the word after it may still be the command's first.
 */
private class Claim(val takesNext: Boolean, val routesOn: Boolean?, val stripped: Boolean = routesOn == true)

private val CLAIMS_NOTHING = Claim(takesNext = false, routesOn = null)

/**
 * The left-to-right walk behind [builtinScan]. It tracks the command the tokens belong to as it goes, routing
 * as the subcommand walk routes, so every token is resolved against the surface actually in scope at its
 * position, and the argv indices holding a value-taking option's argument ([slots]) are read with full arity
 * knowledge: a leaf's own options claim their values as surely as the root's or a global's do.
 *
 * The slots are what every position-independent built-in scan skips. getopt gives the token after `-e` to `-e`
 * whatever it is spelled, `--` included (`grep -e -- -v` searches for `--`), so `mygrep -e --json f.txt` must
 * search for the literal `--json` and read `f.txt` — no built-in, `--help` included, may step into that slot.
 *
 * Best-effort by design: an unknown option, an ambiguous abbreviation, or a malformed cluster claims
 * nothing, so a line klap is about to reject is scanned exactly as it was before, and the reached
 * command's own [sift] stays the one place that reports it.
 */
private class ArityWalk(private val cli: Cli) {
    private val globals = cli.globalLookup()
    private val globalFlagShorts = globals.flagSpecs.flatMapTo(mutableSetOf()) { it.shorts }
    private val router = SubcommandRouter(cli, cli::globalLookup)

    private var cmd: Command = cli
    private var pool: LongPool = cli.longMatchPool(globals.at(cli))
    private val pools = mutableMapOf<Command, LongPool>(cli to pool)

    // The command in scope at each argv index the walk read; every later index is in [cmd]'s.
    private val scopes = mutableListOf<Command>()

    val slots = mutableSetOf<Int>()

    /** The long pool of the command in scope at argv [position]. */
    fun poolAt(position: Int): LongPool = pools.getValue(commandAt(position))

    /** The command in scope at argv [position]. */
    fun commandAt(position: Int): Command = scopes.getOrNull(position) ?: cmd

    // The subcommand walk stops at the first token that is neither a global nor an option of `cmd`, and every
    // token after that belongs to `cmd`'s own segment; descending past one would resolve later options against
    // a command the parse never reaches.
    private var routing = true

    // The index of [cmd]'s first word, the one place a first-word-only number may stand.
    private var firstWordAt = 0

    // How many of [cmd]'s operands the walk has passed, to find the one that ends its options.
    private var operands = 0

    fun walk(head: List<String>) {
        var i = 0
        while (i < head.size) {
            val token = head[i]
            scopes += cmd
            // Past an option's value, so this one ends the options; a `--` an option takes is skipped below.
            if (token == END_OF_OPTIONS) return
            val step = if (routing) router.step(cmd, head, i, firstWord = i == firstWordAt) else RouteStep.Stop
            if (!token.isFlagLike()) {
                val child = (step as? RouteStep.Descend)?.child
                if (child == null) {
                    routing = false
                    operands += 1
                    // The operand that ends a command's options deliberately does not reach klap's
                    // position-independent built-ins, so past it the scans see argv as they always did.
                    if (cmd.optionsEndAt?.let { operands > it } == true) return
                } else {
                    enter(child)
                    firstWordAt = i + 1
                    operands = 0
                }
                i += 1
                continue
            }
            // The subcommand walk steps over these, so the tokens after them belong to the command it reaches.
            if (step == RouteStep.StepOver) {
                if (i == firstWordAt && token.drop(1).all { it.toString() in globalFlagShorts }) firstWordAt = i + 1
                i += 1
                continue
            }
            val next = head.getOrNull(i + 1)
            val claim = if (token.startsWith("--")) {
                longClaim(token, next)
            } else {
                clusterClaim(token, next, firstWord = i == firstWordAt)
            }
            if (claim.takesNext) {
                slots += i + 1
                scopes += cmd
            }
            routing = routing && (claim.routesOn ?: (step is RouteStep.OwnRun))
            val width = if (claim.takesNext) 2 else 1
            // A global is stripped before the command reads its words, so the word after it is still the first.
            if (i == firstWordAt && claim.stripped) firstWordAt = i + width
            i += width
        }
    }

    private fun enter(child: Command) {
        cmd = child
        pool = pools.getOrPut(child) { child.longMatchPool(globals.at(child)) }
    }

    private fun longClaim(token: String, next: String?): Claim {
        val typed = token.removePrefix("--").substringBefore('=')
        val inline = '=' in token
        val long = when (val resolved = resolveLong(typed, pool, globals.at(cmd).inferNames)) {
            is NameMatch.Exact -> resolved.name
            is NameMatch.Prefix -> resolved.name
            is NameMatch.Ambiguous, NameMatch.None -> return CLAIMS_NOTHING
        }
        // A declared spec always outranks a built-in of the same name: a reserved long is only free to be
        // declared once the tree has declined the built-in behind it (validateReservedNames).
        cmd.options.findOption(long, null)?.let { return Claim(takesValue(it, inline, next), routesOn = null) }
        globals.optionSpecs.findOption(long, null)
            ?.let { return Claim(takesValue(it, inline, next), routesOn = true) }
        if (cmd.flags.findFlag("--$long") != null || cmd.flags.findNegatedFlag(long) != null) return CLAIMS_NOTHING
        val globalFlags = globals.flagSpecs
        if (globalFlags.findFlag("--$long") != null || globalFlags.findNegatedFlag(long) != null) {
            return Claim(takesNext = false, routesOn = true)
        }
        return builtinClaim(long, inline, next)
    }

    /**
     * What a built-in spelling claims. Only `--json` and `--color` are removed before the walk; the rest
     * either short-circuit the parse outright or, like `--help`, survive into the walk and stop it.
     * `--completion` and `--docs` take the next word whatever it is, as getopt_long's required argument does;
     * `--color`'s value is optional, so it takes none.
     */
    private fun builtinClaim(long: String, inline: Boolean, next: String?): Claim = when {
        long == "json" && cli.builtins.json -> Claim(takesNext = false, routesOn = true)
        long == "color" && cli.builtins.color -> Claim(takesNext = false, routesOn = true)
        cli.builtinLongTable().takesNextWord(long) -> Claim(takesNext = !inline && next != null, routesOn = false)

        else -> Claim(takesNext = false, routesOn = false)
    }

    private fun clusterClaim(
        token: String,
        next: String?,
        firstWord: Boolean,
    ): Claim {
        val chars = token.removePrefix("-")
        val here = globals.at(cmd)
        if (cmd.refusesCluster(chars, here, firstWord)) return CLAIMS_NOTHING
        var routesOn: Boolean? = null
        // siftGlobals strips a cluster only when every letter is a global's.
        var allGlobal = true
        var j = 0
        while (j < chars.length) {
            val read = cmd.readShort(chars, j, here, firstWord)
            allGlobal = allGlobal && when (read) {
                is ShortRead.Flag -> read.hit.globals != null
                is ShortRead.Option -> read.hit.globals != null
                else -> false
            }
            when (read) {
                // klap's own short stops the routing, as the subcommand walk stops there, while a value-taking
                // short later in the word still claims the next one. A number run is the command's own input.
                ShortRead.Builtin -> routesOn = false
                is ShortRead.NumberRun, ShortRead.NumberSuffix, is ShortRead.Flag -> Unit
                is ShortRead.Option -> {
                    val attached = chars.length > j + 1
                    return Claim(takesValue(read.hit.spec, inline = attached, next = next), routesOn, allGlobal)
                }

                ShortRead.Unresolved -> return CLAIMS_NOTHING
            }
            j += read.width
        }
        return Claim(takesNext = false, routesOn, allGlobal)
    }

    /**
     * Whether [option] reaches for the token after it. An `.optionalValue(...)` option never does — it
     * cannot tell its own value from an operand — nor does a `.gluedValue()` one or one whose value is already
     * attached.
     */
    private fun takesValue(option: OptionSpec, inline: Boolean, next: String?): Boolean =
        !inline && !option.valueAttachedOnly && next != null
}
