package com.fromwau.klap.internal.parse

import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.Command
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Result
import com.fromwau.klap.SubcommandMatch
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.resolveSubcommand

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
 * [pool] is the long surface a spelling resolves against, which is the tree-wide one before the subcommand
 * walk and the reached command's own after it. [infer] is the root's own mode reduced to the one question a
 * scan asks — may a long spelling resolve by prefix here.
 */
internal class ArgvScan(
    private val pool: List<String>,
    val infer: Boolean,
    val valueSlots: Set<Int>,
    val tokens: List<String>,
    val positions: List<Int> = tokens.indices.toList(),
) {
    /** Where the options end: the marker's index, or the whole list when the line carries none. */
    private val headSize: Int = tokens.indexOf(END_OF_OPTIONS).let { if (it < 0) tokens.size else it }

    private fun isOpen(i: Int): Boolean = i < headSize && positions[i] !in valueSlots

    /** The indices a scan may act on: before the end-of-options marker and outside every value slot. */
    private val open: List<Int> = (0 until headSize).filter { isOpen(it) }

    /** The tokens a scan may act on, in argv order. */
    val openTokens: List<String> get() = open.map { tokens[it] }

    /** The [pool] entry [token] names, exactly or as an unambiguous abbreviation; null when it names none. */
    fun matched(token: String): String? = matchedLong(token, pool, infer)

    /**
     * Whether a bare (valueless) token names the built-in [long]. The `=value` shape is deliberately not a
     * match: every built-in this answers for is boolean, and its `--<name>=value` form is reported before
     * any of these scans run.
     */
    fun names(long: String): Boolean = open.any { '=' !in tokens[it] && matched(tokens[it]) == long }

    /** Whether the literal short [token] is on the line. A short never abbreviates, so this stays literal. */
    fun namesShort(token: String): Boolean = open.any { tokens[it] == token }

    /**
     * The value of a position-independent `--<long> value` / `--<long>=value` meta-option: the value,
     * `null` when it is absent, or MissingOptionValue when it is present without one. The LAST occurrence
     * wins, the same rule value options follow generally.
     *
     * A flag-like next token is never swallowed: a meta-option is klap's own and is read before any command
     * resolves, so it keeps the strict reading the greedy rule dropped for a COMMAND's options, and
     * `--color --json` still means a missing `--color` value rather than the literal value "--json".
     */
    fun value(long: String): Result<String?, CliError> {
        val at = open.lastOrNull { matched(tokens[it]) == long } ?: return Result.Success(null)
        val token = tokens[at]
        val value = if ('=' in token) {
            token.substringAfter('=')
        } else {
            tokens.getOrNull(at + 1)?.takeIf { at + 1 < headSize && !it.isFlagLike() }
        }
        // [long] is the bare pool key; an error names an option the way every declared one is named, dashed.
        return value?.let { Result.Success(it) } ?: Result.Error(CliError.MissingOptionValue("--$long"))
    }

    /**
     * This view with every bare token naming [long] removed. A `--<long>=value` token is left in place,
     * since that shape is a usage error the caller has already reported.
     */
    fun strip(long: String): ArgvScan =
        keeping(open.filterTo(mutableSetOf()) { '=' !in tokens[it] && matched(tokens[it]) == long })

    /**
     * This view with every `--<long>`/`--<long>=value` occurrence removed, along with its value token in
     * the space form. Mirrors [strip], but a required-value meta-option like `--color` also needs its
     * consumed value gone, or it would resurface as a stray positional in the subcommand walk. A flag-like
     * token after `--<long>` is never swallowed (matching [value]'s own read), since that shape means the
     * value was missing, already rejected by the caller's validation before this ever runs.
     */
    fun stripValued(long: String): ArgvScan {
        val dropped = mutableSetOf<Int>()
        var i = 0
        while (i < headSize) {
            if (isOpen(i) && matched(tokens[i]) == long) {
                dropped += i
                if ('=' !in tokens[i] && i + 1 < headSize && !tokens[i + 1].isFlagLike()) {
                    dropped += i + 1
                    i += 1
                }
            }
            i += 1
        }
        return keeping(dropped)
    }

    private fun keeping(dropped: Set<Int>): ArgvScan {
        val kept = tokens.indices.filterNot { it in dropped }
        return ArgvScan(pool, infer, valueSlots, kept.map { tokens[it] }, kept.map { positions[it] })
    }
}

/**
 * The [argv] indices holding a value-taking option's argument, read with full arity knowledge: the walk
 * descends the subcommand path as it goes, so a leaf's own options claim their values as surely as the
 * root's or a global's do.
 *
 * This is what every position-independent built-in scan skips. POSIX.1 XBD 12.2 guideline 6 gives the
 * token after `-e` to `-e` whatever it is spelled, and guideline 10 leaves `--` as the only thing that
 * ends option arguments, so `mygrep -e --json f.txt` must search for the literal `--json` and read
 * `f.txt` — no built-in, `--help` included, may step into that slot.
 *
 * Best-effort by design: an unknown option, an ambiguous abbreviation, or a malformed cluster claims
 * nothing, so a line klap is about to reject is scanned exactly as it was before, and the reached
 * command's own [sift] stays the one place that reports it.
 */
internal fun Cli.optionValueSlots(argv: List<String>): Set<Int> {
    val end = argv.indexOf(END_OF_OPTIONS)
    val head = if (end < 0) argv else argv.take(end)
    return ArityWalk(this).slots(head)
}

/**
 * What a scanned token does: whether it claims the token after it as a value ([takesNext]), whether it is
 * one of the tokens klap removes before the subcommand walk ([preStripped]), and whether it is a mixed
 * short cluster [siftGlobals] defers whole ([deferred]). The walk continues past either of the latter two:
 * a deferred cluster is unresolved here but still binds at the reached command's own [sift].
 */
private class Claim(val takesNext: Boolean, val preStripped: Boolean, val deferred: Boolean = false)

private val CLAIMS_NOTHING = Claim(takesNext = false, preStripped = false)
private val DEFERRED_CLUSTER = Claim(takesNext = false, preStripped = false, deferred = true)

/**
 * The left-to-right walk behind [optionValueSlots]. It tracks the command the tokens belong to as it goes,
 * so every option is resolved against the surface actually in scope at its position rather than against
 * the root's alone.
 */
private class ArityWalk(private val cli: Cli) {
    // A throwaway accumulator holding no occurrences: only its spec lists and the pool it builds are read,
    // and taking them from here is what keeps this walk's reading of a token identical to [sift]'s.
    private val globals =
        GlobalSift(emptyMap(), emptyMap(), emptyMap())
            .accumulator(
                cli.globalSpecs,
                cli.version,
                cli.builtins,
                cli.metaOptions,
                cli.declaredLongs,
                cli.abbreviation,
            )

    private var cmd: Command = cli
    private var pool: List<String> = cli.longMatchPool(globals)

    // The subcommand walk stops at the first token klap can neither strip nor defer as a global before it,
    // and every token after that belongs to `cmd`'s own segment; descending past one would resolve later
    // options against a command the parse never reaches.
    private var routing = true

    fun slots(head: List<String>): Set<Int> {
        val slots = mutableSetOf<Int>()
        var i = 0
        while (i < head.size) {
            val token = head[i]
            if (!token.isFlagLike()) {
                val child = if (routing) {
                    (cmd.resolveSubcommand(token, cli.abbreviation == Abbreviation.All) as? SubcommandMatch.One)?.command
                } else {
                    null
                }
                if (child == null) {
                    routing = false
                    // `optionsEndAtFirstOperand` deliberately does not reach klap's position-independent
                    // built-ins, so past the operand that fires it the scans see argv as they always did.
                    if (cmd.optionsEndAtFirstOperand) return slots
                } else {
                    enter(child)
                }
                i += 1
                continue
            }
            val next = head.getOrNull(i + 1)
            val claim = if (token.startsWith("--")) longClaim(token, next) else clusterClaim(token, next)
            if (claim.takesNext) slots += i + 1
            routing = routing && (claim.preStripped || claim.deferred)
            i += if (claim.takesNext) 2 else 1
        }
        return slots
    }

    private fun enter(child: Command) {
        cmd = child
        pool = cmd.longMatchPool(globals)
    }

    private fun longClaim(token: String, next: String?): Claim {
        val typed = token.removePrefix("--").substringBefore('=')
        val inline = '=' in token
        val long = when (val resolved = resolveLong(typed, pool, globals.inferNames)) {
            is NameMatch.Exact -> resolved.name
            is NameMatch.Prefix -> resolved.name
            is NameMatch.Ambiguous, NameMatch.None -> return CLAIMS_NOTHING
        }
        // A declared spec always outranks a built-in of the same name: a reserved long is only free to be
        // declared once the tree has declined the built-in behind it (validateReservedNames).
        cmd.options.findOption(long, null)?.let { return Claim(takesValue(it, inline, next), preStripped = false) }
        globals.optionSpecs.findOption(long, null)
            ?.let { return Claim(takesValue(it, inline, next), preStripped = true) }
        if (cmd.flags.findFlag("--$long") != null || cmd.flags.findNegatedFlag(long) != null) return CLAIMS_NOTHING
        val globalFlags = globals.flagSpecs
        if (globalFlags.findFlag("--$long") != null || globalFlags.findNegatedFlag(long) != null) {
            return Claim(takesNext = false, preStripped = true)
        }
        return builtinClaim(long, inline, next)
    }

    /**
     * What a built-in spelling claims. Only `--json` and `--color` are removed before the walk; the rest
     * either short-circuit the parse outright or, like `--help`, survive into the walk and stop it.
     */
    private fun builtinClaim(long: String, inline: Boolean, next: String?): Claim = when {
        long == "json" && cli.builtins.json -> Claim(takesNext = false, preStripped = true)
        // `--color` is stripped WITH its value, so the walk has to step over that token too. Recording it
        // as a slot costs nothing: a value klap accepts here is never flag-like, so no built-in spelling
        // can be sitting in it.
        long == "color" && cli.builtins.color ->
            Claim(takesNext = !inline && next != null && !next.isFlagLike(), preStripped = true)

        else -> CLAIMS_NOTHING
    }

    private fun clusterClaim(token: String, next: String?): Claim {
        // `-<NUM>` under a numericAlias carries its own value in the digits, and no pass strips it.
        if (cmd.numericAliasValue(token, globals) != null) return CLAIMS_NOTHING
        val chars = token.removePrefix("-")
        // A cluster is pre-stripped only when EVERY char is a global; one local char leaves it whole for
        // the reached command's own sift, exactly as siftGlobals does.
        var allGlobal = true
        var j = 0
        while (j < chars.length) {
            val ch = chars[j].toString()
            if (cmd.flags.findFlag("-$ch") != null || cmd.flags.findNegatedShort(ch) != null) {
                allGlobal = false
                j += 1
                continue
            }
            if (globals.flagSpecs.findFlag("-$ch") != null || globals.flagSpecs.findNegatedShort(ch) != null) {
                j += 1
                continue
            }
            val local = cmd.options.findOption(null, ch)
            val option = local ?: globals.optionSpecs.findOption(null, ch)
            if (option == null) {
                // `cmd` is only the command reached so far, so a char resolving nowhere here may still sit
                // in a cluster siftGlobals deferred; the reached leaf's own sift is where that binds.
                return if (clusterTouchesGlobal(chars, globals.flagSpecs, globals.optionSpecs)) {
                    DEFERRED_CLUSTER
                } else {
                    CLAIMS_NOTHING
                }
            }
            if (local != null) allGlobal = false
            val attached = chars.length > j + 1
            return Claim(takesValue(option, inline = attached, next = next), preStripped = allGlobal)
        }
        return Claim(takesNext = false, preStripped = allGlobal)
    }

    /**
     * Whether [option] reaches for the token after it. An `.optionalValue(...)` option never does — it
     * cannot tell its own value from an operand — and neither does one whose value is already attached.
     */
    private fun takesValue(option: OptionSpec, inline: Boolean, next: String?): Boolean =
        !inline && option.bareValue == null && next != null
}
