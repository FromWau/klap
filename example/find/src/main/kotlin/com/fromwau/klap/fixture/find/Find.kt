package com.fromwau.klap.fixture.find

import com.fromwau.klap.Flag
import com.fromwau.klap.Ok
import com.fromwau.klap.Opt
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * find(1) as a klap surface study.
 *
 * find's argv is an expression grammar (predicates joined by `-a`/`-o`/`!`/parentheses), while klap
 * binds a fixed, unordered set of named holders. So this file reproduces the option-shaped subset
 * natively (the pre-path switches `-P -L -H`, `-D`, `-O`) and transliterates the predicates to `--`
 * spellings, which keeps each predicate's arity and value grammar but drops boolean structure.
 * Everywhere klap's surface diverges from real find, the comment is marked `KLAP-GAP:`.
 */

// find's value grammars. `.map { }` turns a thrown exception into a clean BadValue (`applyMap` in
// Converters.kt), so a hand-written mini-parser per predicate is a one-liner at the declaration site.
//
// The three result types are public only because the inputs holder at the foot of this file names
// them; nothing else about them is API.

/** `-mtime +7` / `-mtime -7` / `-mtime 7`: the leading sign is a COMPARISON, not a sign. */
public data class FindCount(val comparison: Char, val value: Long)

private fun findCount(raw: String): FindCount {
    val comparison = if (raw.firstOrNull() == '+' || raw.firstOrNull() == '-') raw.first() else '='
    val digits = if (comparison == '=') raw else raw.drop(1)
    val value = digits.toLongOrNull()
        ?: throw IllegalArgumentException("expected [+-]N, got '$raw'")
    return FindCount(comparison, value)
}

/** `-size +1M`: comparison, magnitude, and a case-SENSITIVE unit suffix (`b c w k M G`). */
public data class FindSize(val comparison: Char, val value: Long, val unit: Char)

private const val FIND_SIZE_UNITS = "bcwkMG"

private fun findSize(raw: String): FindSize {
    val comparison = if (raw.firstOrNull() == '+' || raw.firstOrNull() == '-') raw.first() else '='
    val body = if (comparison == '=') raw else raw.drop(1)
    val suffix = body.lastOrNull()
    val hasUnit = suffix != null && suffix in FIND_SIZE_UNITS
    val unit = if (hasUnit) body.last() else 'b'
    val digits = if (hasUnit) body.dropLast(1) else body
    val value = digits.toLongOrNull()
        ?: throw IllegalArgumentException("expected [+-]N[$FIND_SIZE_UNITS], got '$raw'")
    return FindSize(comparison, value, unit)
}

/** `-perm 644` (exact) / `-perm -644` (all bits) / `-perm /644` (any bit). */
public data class FindPerm(val match: Char, val mode: String)

private fun findPerm(raw: String): FindPerm {
    val match = if (raw.firstOrNull() == '-' || raw.firstOrNull() == '/') raw.first() else '='
    val mode = if (match == '=') raw else raw.drop(1)
    if (mode.isEmpty()) throw IllegalArgumentException("expected [-/]MODE, got '$raw'")
    return FindPerm(match, mode)
}

/**
 * `-type f`, `-type d`, and GNU's comma list `-type f,l`.
 *
 * KLAP-GAP: find's type letters are case-sensitive (`d` directory vs `D` door), but `.choice()`
 * matches case-insensitively and rejects a set colliding under case, so `.validate` is the fallback.
 */
private const val FIND_TYPE_LETTERS = "bcdpflsD"

private fun isFindTypeList(raw: String): Boolean =
    raw.isNotEmpty() && raw.split(",").all { it.length == 1 && it[0] in FIND_TYPE_LETTERS }

/**
 * The `find` command-line surface, as far as klap's model reaches.
 *
 * Shape: a single-command root (its own `action`, no user subcommands), so `--completion` and
 * `--docs` arrive as position-independent options rather than as subcommands.
 */
public fun findCli(): TypedCli<FindInputs> = cliOf("find") {
    description = "Search for files in a directory hierarchy"
    version = "0.0-study"
    epilogue =
        "This is a klap surface study, not findutils. Predicates are spelled with two dashes; " +
            "pass a real find expression verbatim after `--` and parse it yourself."

    example("find . --name '*.kt' --type f --print", "the conjunction-only subset klap can bind")
    example("find . -- -name '*.kt' -o -size +1M -print", "the raw-token escape hatch, `--` mandatory")
    example("find -L -O3 /srv --maxdepth 3 --print0", "the only tokens spelled exactly as find spells them")
    example("find --type f", "no starting point: the operand list binds empty and the action applies `.`")

    // KLAP-GAP: find spells every predicate with a single dash and a multi-character name (`-name`,
    // `-type`, `-mtime`, ...); `requireValidSpelling` (HolderSpec.kt) rejects that shape outright, so
    // every predicate below is respelled with two dashes instead.

    // The pre-path switches. `-P`/`-L`/`-H`/`-D`/`-Olevel` are single-dash single-char, precisely
    // klap's short form and the only part of find that reproduces exactly; since find's own
    // predicates are all lowercase, none of them silently clusters into these uppercase shorts.
    val physical = flag("--physical", "-P", help = "never follow symbolic links (default)")
    val logical = flag("--logical", "-L", help = "follow symbolic links")
    val followArgs = flag("--follow-args", "-H", help = "follow symlinks only for command-line arguments")
    lastWins(physical, logical, followArgs)

    // `-D tree,search`. Reproduces exactly, including the space form `-D x` and the
    // attached form `-Dx` (Parser.kt).
    val debug = option("--debug", "-D", help = "print diagnostics (comma-separated)")
        .map { raw -> raw.split(",").filter { part -> part.isNotEmpty() } }

    // `-O3`. Reproduces exactly in the attached form; klap additionally accepts `-O 3`
    // and `--optimise=3`, which find does not.
    val optimise = option("--optimise", "-O", help = "query optimisation level").int().range(0..3)

    // find's "global options" (-maxdepth/-mindepth/-depth/-xdev/...) are the one family that
    // genuinely is position-independent in find, so klap's unordered binding is the right model for
    // them.
    //
    // KLAP-GAP: their real spelling is single-dash (`-maxdepth 3`), which klap cannot express here
    // any more than it can for the predicates below.
    val maxDepth: Opt<Int?>
    val minDepth: Opt<Int?>
    val depth: Flag
    val xdev: Flag
    val mount: Flag
    val noleaf: Flag
    val ignoreReaddirRace: Flag
    val regextype: Opt<String?>
    val files0From: Opt<String?>
    group("Traversal options (find's own position-independent set)") {
        maxDepth = option("--maxdepth", help = "descend at most N directory levels")
            .int()
            .range(0..Int.MAX_VALUE)

        minDepth = option("--mindepth", help = "do not apply any tests at levels less than N")
            .int()
            .range(0..Int.MAX_VALUE)

        depth = flag("--depth", help = "process a directory's contents before the directory itself")
        xdev = flag("--xdev", help = "don't descend directories on other filesystems")
        mount = flag("--mount", help = "synonym for --xdev (klap cannot alias a flag)")

        noleaf = flag("--noleaf", help = "do not optimise assuming Unix directory link counts")
        ignoreReaddirRace = flag("--ignore-readdir-race", help = "do not error on files removed during the scan")

        regextype = option("--regextype", help = "syntax used by --regex")
            .choice(
                "findutils-default",
                "gnu-awk",
                "posix-awk",
                "posix-basic",
                "posix-egrep",
                "posix-extended",
            )

        files0From = option("--files0-from", help = "read NUL-separated starting points from FILE").file()
    }

    // Tests: arity and value grammar reproduce; spelling and position do not. Each binds
    // independently, so the tree below expresses exactly one implicit conjunction of predicates and
    // nothing else — see the closing KLAP-GAP block.
    //
    // `.multiple()` collects occurrences of THAT option only: `--name a --name b` is recoverable,
    // but the interleaving of `--name a --size +1M --name b` is not.
    val namePattern = option("--name", help = "base name matches shell pattern").multiple()
    val inamePattern = option("--iname", help = "like --name, case-insensitive").multiple()
    val pathPattern = option("--path", help = "whole path matches shell pattern").multiple()
    val regexPattern = option("--regex", help = "whole path matches regular expression").multiple()

    // KLAP-GAP: see isFindTypeList; a case-sensitive choice set is not representable.
    val typeTest = option("--type", help = "file type: one or more of b c d p f l s D (comma-separated)")
        .validate("must be one or more of $FIND_TYPE_LETTERS, comma-separated") { isFindTypeList(it) }

    // `-size +1M` / `-size -100c` / `-size 3`. The `+`-led token is not dash-led at all so it
    // binds in the space form; a `-`-led NUMERIC token binds too, because `isDashLedValue`
    // (Parser.kt) classifies `-100c` as a value rather than an option. A genuinely good fit.
    val sizeTest = option("--size", help = "file uses N units of space ([+-]N[$FIND_SIZE_UNITS])")
        .map(::findSize)

    val mtime = option("--mtime", help = "data last modified [+-]N*24h ago").map(::findCount)
    val mmin = option("--mmin", help = "data last modified [+-]N minutes ago").map(::findCount)
    val ctime = option("--ctime", help = "status last changed [+-]N*24h ago").map(::findCount)
    val atime = option("--atime", help = "last accessed [+-]N*24h ago").map(::findCount)
    val linkCount = option("--links", help = "file has [+-]N hard links").map(::findCount)

    val permTest = option("--perm", help = "permission bits ([-/]MODE)").map(::findPerm)

    val newerThan = option("--newer", help = "modified more recently than FILE").file()
    val ownerUser = option("--user", help = "owned by USER")
    val ownerGroup = option("--group", help = "owned by GROUP")

    val emptyTest = flag("--empty", help = "file or directory is empty")
    val readableTest = flag("--readable", help = "readable by the current user")
    val writableTest = flag("--writable", help = "writable by the current user")
    val executableTest = flag("--executable", help = "executable/searchable by the current user")
    val nouserTest = flag("--nouser", help = "no user corresponds to the file's numeric uid")
    val nogroupTest = flag("--nogroup", help = "no group corresponds to the file's numeric gid")

    // KLAP-GAP (order): in find, an action's position is its meaning (`-print` fires per match,
    // `-prune` stops descent where it is reached, `-quit` stops immediately). klap binds actions as
    // unordered booleans, so all positional meaning is lost.
    val printAction = flag("--print", help = "print the full file name, then a newline")
    val print0Action = flag("--print0", help = "print the full file name, then a NUL")
    val printfAction = option("--printf", help = "print FORMAT, interpreting `%` directives")
    val lsAction = flag("--ls", help = "list the file in `ls -dils` format")
    val deleteAction = flag("--delete", help = "delete the file")
    val pruneAction = flag("--prune", help = "do not descend into this directory")
    val quitAction = flag("--quit", help = "exit immediately")

    // KLAP-GAP (terminator-delimited variadic): real `-exec COMMAND [ARG...] ;` (or `+`) swallows
    // every remaining token until the sentinel; `fixedAfter` in Parser.kt reserves trailing room for
    // later positionals but has no notion of a mid-stream sentinel, so the closest expressible thing
    // is one word per occurrence with no terminator: `-exec rm -rf {} ;` becomes
    // `--exec rm --exec=-rf --exec '{}'`.
    val execAction = option("--exec", help = "one word of the command to run (see KLAP-GAP above)")
        .multiple()

    // `validatePositionals` (BuilderValidation.kt) allows at most one variadic argument, so find's
    // PATH... list and its post-`--` raw expression cannot be two slots; they share this one list,
    // split by hand in the action. The escape hatch works and preserves source order (a token
    // starting with `--` ends option parsing, handing the rest over verbatim), so `find . -- -name
    // '*.kt' -o -size +1M -print` is recoverable by a hand-written parser, at the cost of that tail
    // being opted out of help, completion, docs and typed errors. `optionsEndAtFirstOperand` could
    // drop the mandatory `--` real find never wants, but this fixture keeps the declared shape
    // deliberately, because switching would model a different tool than the one measured. A
    // zero-token operand list binds as an empty list rather than failing, which is why bare `find`
    // can default to `.` in the action below, matching findutils' own documented default.
    val operand = argument(
        "operand",
        "starting points, then (after a literal `--`) a raw find expression",
    ).file().multiple()

    // KLAP-GAP (the whole expression grammar): find's boolean operators (`-o`/`-a`), grouping
    // (`\( \)`) and negation (`!`/`-not`) have no representation in klap's unordered-holder model, so
    // the precise boundary is a single flat conjunction of two-dash predicates with no operators, no
    // grouping and no negation — everything above is that conjunction, ANDed together in the action
    // below as an accidental stand-in for find's implicit `-a`.

    action<String>(human = { it }) {
        // The cost the escape hatch imposes: split find's PATH... from its expression by
        // hand, because klap handed both to the same variadic slot.
        val startingPoints = operand()
            .takeWhile { !it.startsWith("-") && it != "(" && it != "!" }
            .ifEmpty { listOf(".") }
        val rawExpression = operand().drop(startingPoints.size)

        // `lastWins` already collapsed the set to its winner, so this reads one true flag at most and
        // needs no precedence of its own — the rule find documents is the rule the handles carry.
        val symlinkMode = when {
            logical() -> "-L"
            followArgs() -> "-H"
            else -> "-P"
        }

        val tests = listOf(
            namePattern().size,
            inamePattern().size,
            pathPattern().size,
            regexPattern().size,
            listOfNotNull(
                typeTest(), sizeTest(), mtime(), mmin(), ctime(),
                atime(), linkCount(), permTest(), newerThan(),
                ownerUser(), ownerGroup(),
            ).size,
            listOf(
                emptyTest(), readableTest(), writableTest(),
                executableTest(), nouserTest(), nogroupTest(),
            ).count { it },
        ).sum()

        val actions = listOf(
            printAction(), print0Action(), lsAction(),
            deleteAction(), pruneAction(), quitAction(),
        ).count { it } +
            (if (printfAction() == null) 0 else 1) +
            (if (execAction().isEmpty()) 0 else 1)

        Ok(
            "would walk ${startingPoints.joinToString(" ")} " +
                "(symlink mode: $symlinkMode, maxdepth: ${maxDepth() ?: "unlimited"}, " +
                "debug keys: ${debug()?.size ?: 0}, -O${optimise() ?: 1}, " +
                "$tests tests, $actions actions, " +
                "${rawExpression.size} unparsed expression tokens)",
        )
    }

    projection {
        FindInputs(
            physical(), logical(), followArgs(), debug(), optimise(), maxDepth(), minDepth(), depth(),
            xdev(), mount(), noleaf(), ignoreReaddirRace(), regextype(), files0From(), namePattern(),
            inamePattern(), pathPattern(), regexPattern(), typeTest(), sizeTest(), mtime(), mmin(),
            ctime(), atime(), linkCount(), permTest(), newerThan(), ownerUser(), ownerGroup(),
            emptyTest(), readableTest(), writableTest(), executableTest(), nouserTest(), nogroupTest(),
            printAction(), print0Action(), printfAction(), lsAction(), deleteAction(), pruneAction(),
            quitAction(), execAction(), operand(),
        )
    }
}

/**
 * What one `find` line binds, as values rather than as the handles that read them.
 *
 * Being a plain data class is the point: a parity case asserts a whole invocation with one `assertEquals`
 * against a `copy()` of [NOTHING_BOUND], so every field it does *not* name is pinned to its default too.
 *
 * Wider than the study's own `val`s: the traversal options were declared and never read in the action, and
 * a fixture has to be able to assert that they bound.
 */
public data class FindInputs(
    val physical: Boolean,
    val logical: Boolean,
    val followArgs: Boolean,
    val debug: List<String>?,
    val optimise: Int?,
    val maxDepth: Int?,
    val minDepth: Int?,
    val depth: Boolean,
    val xdev: Boolean,
    val mount: Boolean,
    val noleaf: Boolean,
    val ignoreReaddirRace: Boolean,
    val regextype: String?,
    val files0From: String?,
    val namePattern: List<String>,
    val inamePattern: List<String>,
    val pathPattern: List<String>,
    val regexPattern: List<String>,
    val typeTest: String?,
    val sizeTest: FindSize?,
    val mtime: FindCount?,
    val mmin: FindCount?,
    val ctime: FindCount?,
    val atime: FindCount?,
    val linkCount: FindCount?,
    val permTest: FindPerm?,
    val newerThan: String?,
    val ownerUser: String?,
    val ownerGroup: String?,
    val emptyTest: Boolean,
    val readableTest: Boolean,
    val writableTest: Boolean,
    val executableTest: Boolean,
    val nouserTest: Boolean,
    val nogroupTest: Boolean,
    val printAction: Boolean,
    val print0Action: Boolean,
    val printfAction: String?,
    val lsAction: Boolean,
    val deleteAction: Boolean,
    val pruneAction: Boolean,
    val quitAction: Boolean,
    val execAction: List<String>,
    val operand: List<String>,
)

/** `find` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_BOUND: FindInputs = FindInputs(
    physical = false,
    logical = false,
    followArgs = false,
    debug = null,
    optimise = null,
    maxDepth = null,
    minDepth = null,
    depth = false,
    xdev = false,
    mount = false,
    noleaf = false,
    ignoreReaddirRace = false,
    regextype = null,
    files0From = null,
    namePattern = emptyList(),
    inamePattern = emptyList(),
    pathPattern = emptyList(),
    regexPattern = emptyList(),
    typeTest = null,
    sizeTest = null,
    mtime = null,
    mmin = null,
    ctime = null,
    atime = null,
    linkCount = null,
    permTest = null,
    newerThan = null,
    ownerUser = null,
    ownerGroup = null,
    emptyTest = false,
    readableTest = false,
    writableTest = false,
    executableTest = false,
    nouserTest = false,
    nogroupTest = false,
    printAction = false,
    print0Action = false,
    printfAction = null,
    lsAction = false,
    deleteAction = false,
    pruneAction = false,
    quitAction = false,
    execAction = emptyList(),
    operand = emptyList(),
)
