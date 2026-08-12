package com.fromwau.klap.fixture.chmod

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.ConversionError
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.11 `chmod`, as a klap command tree.
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
private val CHMOD_OCTAL = Regex("""[-+=]?[0-7]{1,4}""")

/** `chmod --help`: "[ugoa]*([-+=]([rwxXst]*|[ugo]))+", one comma-separated clause. */
private val CHMOD_SYMBOLIC_CLAUSE = Regex("""[ugoa]*(?:[-+=](?:[rwxXst]*|[ugo]))+""")

/**
 * A named helper rather than an inline lambda so `.convert` infers `Arg<ChmodMode>` off a written-out
 * return type instead of the lambda body.
 */
private fun parseChmodMode(raw: String): Result<ChmodMode, ConversionError> {
    if (raw.isEmpty()) return Err(ConversionError.Domain(ChmodModeError.Empty, "mode must not be empty"))
    if (CHMOD_OCTAL.matches(raw)) {
        val op = ChmodMode.OctalOp.entries.firstOrNull { it.symbol == raw.first() }
        val digits = if (op == null) raw else raw.drop(1)
        return Ok(ChmodMode.Octal(digits.fold(0) { acc, c -> acc * 8 + (c - '0') }, op))
    }
    val clauses = raw.split(',')
    if (clauses.all { it.isNotEmpty() && CHMOD_SYMBOLIC_CLAUSE.matches(it) }) {
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
    description = "Change the mode of each FILE to MODE"
    version = "9.11"
    author = "David MacKenzie and Jim Meyering"
    epilogue = "Each MODE is of the form '[ugoa]*([-+=]([rwxXst]*|[ugo]))+|[-+=][0-7]+'."

    example("chmod 755 script.sh", "octal mode")
    example("chmod u+x,go-w -R src", "symbolic mode, recursively")
    example("chmod -w notes.txt", "a dash-led mode binds directly; `--` still works too")

    val changes = flag("--changes", "-c", help = "like verbose but report only when a change is made")
    val silent = flag("--silent", "-f", help = "suppress most error messages")

    val quiet = flag("--quiet", help = "suppress most error messages (alias of --silent)")

    val verbose = flag("--verbose", "-v", help = "output a diagnostic for every file processed")

    // --dereference / --no-dereference is exactly .negatable(); the default matches chmod's own.
    //
    // The negative half's short, `.negatable("--no-dereference", "-h")`, is blocked by the built-in `-h`
    // (reserved while `builtins.helpShort` is on), not by the negation model. This fixture keeps `-h` as
    // help deliberately, to pin what a tool pays for leaving the built-in on; `example/ls` declines it
    // instead.
    val dereference = flag("--dereference", help = "affect the referent of each symbolic link")
        .negatable(default = true)

    // --preserve-root / --no-preserve-root maps onto .negatable() cleanly, default off, as in chmod.
    val preserveRoot = flag("--preserve-root", help = "fail to operate recursively on '/'")
        .negatable(default = false)

    val reference = option("--reference", help = "use RFILE's mode instead of specifying MODE values")
        .file()

    val recursive = flag("--recursive", "-R", help = "change files and directories recursively")

    // group(title) { } returns whatever the block returns, so the three traversal flags come back
    // together as the block's own Triple rather than a hoisted lateinit var.
    val (traverseArgLinks, traverseAllLinks, traverseNoLinks) = group("Symlink traversal (only with -R)") {
        // The three are LAST-ONE-WINS in real chmod ("If more than one is specified, only the final one
        // takes effect. -H is the default."), an override rule rather than an exclusivity one —
        // `requireAtMostOne` would reject `chmod -R -H -L`, which real chmod accepts.
        val traverseArgLinks =
            flag("--traverse-arg-links", "-H", help = "traverse a command-line symlink to a directory")
        val traverseAllLinks =
            flag("--traverse-all-links", "-L", help = "traverse every symlink to a directory encountered")
        val traverseNoLinks =
            flag("--traverse-no-links", "-P", help = "do not traverse any symbolic links")
        lastWins(traverseArgLinks, traverseAllLinks, traverseNoLinks)
        Triple(traverseArgLinks, traverseAllLinks, traverseNoLinks)
    }

    // bindPositionals walks the specs left to right, so `mode` takes values[0] before the trailing
    // Multiple claims the rest — the reverse shape (`SOURCE... DEST`, cp) is what that same greedy slice
    // makes impossible.
    //
    // `dashLed()` is why `chmod -w f`, `chmod -rwx f` and `chmod -R -w d` bind here as GNU chmod binds
    // them. The rule that serves both this and mkdir (where `-w f` really is an error) is per-argument
    // opt-in: chmod marks its mode slot and mkdir does not. `-R` still parses as the flag it is, because
    // only a token resolving to nothing reaches the slot, and `chmod -- -w f` still works.
    //
    // `chmod --reference=RFILE FILE...` REPLACES the MODE operand entirely, so the slot must disappear on
    // that line rather than merely go unread, or the first FILE slides into it.
    val mode = argument("mode", "the new mode: octal (755) or symbolic (u+x,a-w)")
        .dashLed()
        .convert(::parseChmodMode)
        .completeWith { candidates(listOf("644", "755", "600", "700", "u+x", "a-w", "go-rwx")) }
        .absentWhen(reference)

    val files = argument("file", "files whose mode to change").file().multiple(min = 1)

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
            "silent".takeIf { silent() || quiet() },
            "follow-symlinks".takeIf { dereference() },
            "preserve-root".takeIf { preserveRoot() },
        )
        Ok("would set ${files().size} file(s) to $target [${notes.joinToString(", ")}]")
    }

    projection {
        ChmodInputs(
            changes(),
            silent(),
            quiet(),
            verbose(),
            dereference(),
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
 * What one `chmod` line binds, as values rather than as the handles that read them.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does
 * not name is pinned to its default too.
 */
public data class ChmodInputs(
    val changes: Boolean,
    val silent: Boolean,
    val quiet: Boolean,
    val verbose: Boolean,
    val dereference: Boolean,
    val preserveRoot: Boolean,
    val reference: String?,
    val recursive: Boolean,
    val traverseArgLinks: Boolean,
    val traverseAllLinks: Boolean,
    val traverseNoLinks: Boolean,
    val mode: ChmodMode?,
    val files: List<String>,
)

/** `chmod` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_BOUND: ChmodInputs = ChmodInputs(
    changes = false,
    silent = false,
    quiet = false,
    verbose = false,
    dereference = true,
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
