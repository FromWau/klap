package com.fromwau.klap.fixture.chmod

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.CliError
import com.fromwau.klap.ConversionError
import com.fromwau.klap.Help
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.io.path
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.12 `chmod`, as a klap command tree.
 *
 *     chmod [OPTION]... MODE[,MODE]... FILE...
 *     chmod [OPTION]... OCTAL-MODE FILE...
 *     chmod [OPTION]... --reference=RFILE FILE...
 *
 * Bodies are stubs; only the parsing surface is under study.
 */

/** The MODE operand: `755` / `-0755` (octal) or `u+x,a-w` (symbolic clauses). */
public sealed interface ChmodMode {
    /** The operator an octal mode may carry: `chmod -755` clears the bits `chmod 755` sets. */
    public enum class OctalOp(public val symbol: Char) { ADD('+'), REMOVE('-'), ASSIGN('=') }

    /** [op] is null for the plain absolute spelling, `755`, which carries no operator at all. */
    public data class Octal(val bits: Int, val op: OctalOp? = null) : ChmodMode
    public data class Symbolic(val clauses: List<String>) : ChmodMode
}

/** `chmod --help`: "[-+=][0-7]+", plus the plain `755` / `0755` spelling with no operator. */
private val CHMOD_OCTAL = Regex("""[-+=]?[0-7]+""")

/** `chmod --help`: "[ugoa]*([-+=]([rwxXst]*|[ugo]))+", one comma-separated clause. */
private val CHMOD_SYMBOLIC_CLAUSE = Regex("""[ugoa]*(?:[-+=](?:[rwxXst]*|[ugo]))+""")

/** `chmod --help`'s "[-+=][0-7]+" as one clause of a comma list: `chmod u+x,=755 f` sets 755. */
private val CHMOD_OCTAL_CLAUSE = Regex("""[-+=][0-7]+""")

/** The bits of an octal [digits] run, or null past 07777: `chmod 000000755 f` sets 755, `chmod 17777 f` is invalid. */
private fun octalBits(digits: String): Int? {
    val significant = digits.trimStart('0')
    return if (significant.length > 4) null else significant.fold(0) { acc, c -> acc * 8 + (c - '0') }
}

private fun isClause(clause: String): Boolean = CHMOD_SYMBOLIC_CLAUSE.matches(clause) ||
    (CHMOD_OCTAL_CLAUSE.matches(clause) && octalBits(clause.drop(1)) != null)

/**
 * A named helper rather than an inline lambda so `.convert` infers `Arg<ChmodMode>` off a written-out
 * return type instead of the lambda body.
 */
private fun parseChmodMode(raw: String): Result<ChmodMode, ConversionError> {
    if (raw.isEmpty()) return Err(ConversionError.Domain(ChmodModeError.Empty, "mode must not be empty"))
    if (CHMOD_OCTAL.matches(raw)) {
        val op = ChmodMode.OctalOp.entries.firstOrNull { it.symbol == raw.first() }
        val bits = octalBits(if (op == null) raw else raw.drop(1))
        if (bits != null) return Ok(ChmodMode.Octal(bits, op))
    }
    val clauses = raw.split(',')
    if (clauses.all(::isClause)) {
        return Ok(ChmodMode.Symbolic(clauses))
    }
    return Err(
        ConversionError.Domain(ChmodModeError.Malformed, "not a mode; expected octal (755) or symbolic (u+x,a-w)"),
    )
}

private fun chmodModeLabel(mode: ChmodMode): String = when (mode) {
    is ChmodMode.Octal -> mode.op?.symbol?.toString().orEmpty() + mode.bits.toString(8).padStart(4, '0')
    is ChmodMode.Symbolic -> mode.clauses.joinToString(",")
}

public fun chmodCli(): TypedCli<ChmodInputs> = cliOf("chmod") {
    // GNU chmod takes any unambiguous prefix of a long option: `chmod --re 700 d` is ambiguous.
    abbreviation = Abbreviation.Options
    // GNU chmod's -h is --no-dereference, and it has none of klap's other built-ins (`chmod --json`: "unrecognized
    // option"); --help stays.
    builtins {
        help = Help.ByCommand(description = "display this help and exit", short = null)
        json = Builtin.Off
        color = Builtin.Off
        completion = Builtin.Off
        docs = Builtin.Off
    }
    help = "Change the mode of each FILE to MODE."
    version = Version.Of("9.12", description = "output version information and exit", text = CHMOD_VERSION_TEXT)
    author = "David MacKenzie and Jim Meyering"
    epilogue = "Each MODE is of the form '[ugoa]*([-+=]([rwxXst]*|[ugo]))+|[-+=][0-7]+'."

    example("chmod 755 script.sh", "octal mode")
    example("chmod u+x,go-w -R src", "symbolic mode, recursively")
    example("chmod -w notes.txt", "a dash-led mode binds directly; `--` still works too")

    val changes = flag(short = 'c', long = "changes", help = "like verbose but report only when a change is made")
    val silent = flag(short = 'f', long = listOf("silent", "quiet"), help = "suppress most error messages")
    val verbose = flag(short = 'v', long = "verbose", help = "output a diagnostic for every file processed")

    // One verbosity: `chmod -v -c 644 f` prints nothing, `chmod -c -v 644 f` prints "retained".
    lastWins(changes, verbose)

    // Two flags rather than one `.negatable()`, since an absent --dereference is not chmod's follow: `chmod -R u+x d`
    // leaves a link inside d alone where `chmod -R --dereference u+x d` follows it.
    val dereference = flag(
        long = "dereference",
        help = "affect the referent of each symbolic link, rather than the symbolic link itself",
    )
    val noDereference =
        flag(short = 'h', long = "no-dereference", help = "affect each symbolic link, rather than the referent")
    lastWins(dereference, noDereference)

    fun ValueScope.dereferenceGiven(): Dereference = when {
        dereference() -> Dereference.Follow
        noDereference() -> Dereference.NoFollow
        else -> Dereference.Default
    }

    val preserveRoot = flag(long = "preserve-root", help = "fail to operate recursively on '/'")
        .negatable(default = false, help = "do not treat '/' specially (the default)")

    val reference = option(
        long = "reference",
        help = "use RFILE's mode instead of specifying MODE values. RFILE is always dereferenced if a symbolic link.",
    )
        .path()

    val recursive = flag(short = 'R', long = "recursive", help = "change files and directories recursively")

    val (traverseArgLinks, traverseAllLinks, traverseNoLinks) = group("Symlink traversal (only with -R)") {
        // `chmod --help`: "If more than one is specified, only the final one takes effect. -H is the default."
        val traverseArgLinks =
            flag(short = 'H', help = "if a command line argument is a symlink to a directory, traverse it")
        val traverseAllLinks = flag(short = 'L', help = "traverse every symbolic link to a directory encountered")
        val traverseNoLinks = flag(short = 'P', help = "do not traverse any symbolic links")
        lastWins(traverseArgLinks, traverseAllLinks, traverseNoLinks)
        Triple(traverseArgLinks, traverseAllLinks, traverseNoLinks)
    }

    // chmod exits 1 with no "Try 'chmod --help'" line here, a failure rather than a usage error.
    validateInputs {
        if (recursive() && dereference() && traverseNoLinks()) {
            CliError.Failure("-R --dereference requires either -H or -L")
        } else {
            null
        }
    }

    // bindPositionals walks the specs left to right, so `mode` takes values[0] before the trailing
    // Multiple claims the rest — the reverse shape (`SOURCE... DEST`, cp) is what that same greedy slice
    // makes impossible.
    //
    // chmod reads every dash word made of mode characters as part of the mode, wherever it stands before
    // `--`: `chmod -w -x f` is the mode `-w,-x`, and `chmod u+x -w f` the mode `-w` on the files u+x and f.
    // `-R` still parses as the flag it is, `chmod -z f` is "invalid option -- 'z'", and `chmod -- -w f`
    // still works.
    //
    // `chmod --reference=RFILE FILE...` REPLACES the MODE operand entirely, so the slot must disappear on
    // that line rather than merely go unread, or the first FILE slides into it; a dash-led mode beside it
    // is "cannot combine mode and --reference options".
    val modeChars = "rwxXstugoa01234567+=,".toSet()
    val mode = argument("mode", "the new mode: octal (755) or symbolic (u+x,a-w)")
        .dashLed(gather = ",") { word -> word.drop(1).all { it in modeChars } }
        .convert(::parseChmodMode)
        .completeWith { candidates(listOf("644", "755", "600", "700", "u+x", "a-w", "go-rwx")) }
        .absentWhen(reference)
    atMostOne(mode, reference)

    val files = argument("file", "files whose mode to change").path().multiple(min = 1)

    action {
        // Safe: reference and mode are mutually exclusive by construction (absentWhen), so whichever
        // branch runs here has its own operand bound.
        val target = reference()?.let { "the mode of $it" } ?: chmodModeLabel(mode()!!)
        val traversal = when {
            traverseNoLinks() -> "-P"
            traverseAllLinks() -> "-L"
            traverseArgLinks() -> "-H"
            else -> "-H (default)"
        }
        val notes = listOfNotNull(
            "recursive".takeIf { recursive() },
            "traversal=$traversal".takeIf { recursive() },
            "verbose".takeIf { verbose() },
            "changes-only".takeIf { changes() },
            "silent".takeIf { silent() },
            "dereference=${dereferenceGiven()}",
            "preserve-root".takeIf { preserveRoot() },
        )
        Ok("would set ${files().size} file(s) to $target [${notes.joinToString(", ")}]")
    }

    projection {
        ChmodInputs(
            changes(),
            silent(),
            verbose(),
            dereferenceGiven(),
            preserveRoot(),
            reference(),
            recursive(),
            traverseArgLinks(),
            traverseAllLinks(),
            traverseNoLinks(),
            mode(),
            files(),
        )
    }
}

/**
 * Which of `--dereference` and `-h`/`--no-dereference` the line gave last, or [Default] for neither.
 *
 * [Default] is not [Follow]: `info coreutils 'chmod invocation'` says dereferencing "is the default for command line
 * arguments, but not for symbolic links encountered when recursing".
 */
public enum class Dereference {
    /** Neither spelling: chmod follows a link it is given and, under `-R`, none it meets inside the tree. */
    Default,

    /** `--dereference`: chmod acts on what each symbolic link points to, under `-R` too. */
    Follow,

    /** `-h`/`--no-dereference`: chmod acts on each symbolic link itself. */
    NoFollow,
}

/**
 * What one `chmod` line binds, as values rather than as the handles that read them.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does
 * not name is pinned to its default too.
 */
public data class ChmodInputs(
    val changes: Boolean,
    val silent: Boolean,
    val verbose: Boolean,
    val dereference: Dereference,
    val preserveRoot: Boolean,
    val reference: CliPath?,
    val recursive: Boolean,
    val traverseArgLinks: Boolean,
    val traverseAllLinks: Boolean,
    val traverseNoLinks: Boolean,
    val mode: ChmodMode?,
    val files: List<CliPath>,
)

/** `chmod` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_BOUND: ChmodInputs = ChmodInputs(
    changes = false,
    silent = false,
    verbose = false,
    dereference = Dereference.Default,
    preserveRoot = false,
    reference = null,
    recursive = false,
    traverseArgLinks = false,
    traverseAllLinks = false,
    traverseNoLinks = false,
    mode = null,
    files = emptyList(),
)

/** The two ways a chmod mode operand can be rejected. */
private sealed interface ChmodModeError : IError {
    data object Empty : ChmodModeError

    data object Malformed : ChmodModeError
}

/**
 * What `chmod --version` prints for GNU coreutils 9.12, the version installed when it was pinned
 * (2026-10-10).
 */
internal val CHMOD_VERSION_TEXT: String = """chmod (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by David MacKenzie and Jim Meyering."""
