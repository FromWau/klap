package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.fixture.chmod.Dereference.Follow
import com.fromwau.klap.fixture.chmod.Dereference.NoFollow
import com.fromwau.klap.io.CliPath
import kotlin.test.fail

/*
 * A table of the inputs the installed GNU chmod 9.12 documents, from which the matrix tests generate their cells.
 * HELP is `chmod --help`, each entry's lines joined with one space and otherwise verbatim. What each input binds is
 * how real chmod reads the word, checked with runs of /usr/bin/chmod inside a throwaway directory: -c and -v set one
 * verbosity and the later one holds (`chmod -v -c 644 f` prints nothing, `chmod -c -v 644 f` prints "retained"), -H,
 * -L and -P set one traversal, --dereference and -h one polarity, --preserve-root and --no-preserve-root one switch,
 * and --silent and --quiet are two names of -f.
 */

/**
 * One input of real chmod: its [longs] (empty for -H, -L and -P, which have none), its [short], chmod's own [help],
 * whether it takes a [value], and how it binds into the fixture's record when its word is read.
 */
internal data class Input(
    val longs: List<String>,
    val short: Char?,
    val help: String,
    val value: Boolean = false,
    val bind: ChmodInputs.(String) -> ChmodInputs,
) {
    val name: String get() = longs.firstOrNull() ?: "-$short"

    val spellings: List<String> get() = longs + listOfNotNull(short?.let { "-$it" })

    override fun toString(): String = name
}

private fun flag(
    vararg longs: String,
    short: Char? = null,
    help: String,
    bind: ChmodInputs.() -> ChmodInputs,
) = Input(longs.toList(), short, help) { bind() }

/** Every input of real chmod other than `--help` and `--version`, in `chmod --help` order. */
internal val INPUTS: List<Input> = listOf(
    flag("--changes", short = 'c', help = "like verbose but report only when a change is made") {
        copy(changes = true, verbose = false)
    },
    flag("--silent", "--quiet", short = 'f', help = "suppress most error messages") { copy(silent = true) },
    flag("--verbose", short = 'v', help = "output a diagnostic for every file processed") {
        copy(verbose = true, changes = false)
    },
    flag(
        "--dereference",
        help = "affect the referent of each symbolic link, rather than the symbolic link itself",
    ) { copy(dereference = Follow) },
    flag("--no-dereference", short = 'h', help = "affect each symbolic link, rather than the referent") {
        copy(dereference = NoFollow)
    },
    flag("--no-preserve-root", help = "do not treat '/' specially (the default)") { copy(preserveRoot = false) },
    flag("--preserve-root", help = "fail to operate recursively on '/'") { copy(preserveRoot = true) },
    Input(
        listOf("--reference"),
        null,
        "use RFILE's mode instead of specifying MODE values. RFILE is always dereferenced if a symbolic link.",
        value = true,
    ) { copy(reference = CliPath(it)) },
    flag("--recursive", short = 'R', help = "change files and directories recursively") { copy(recursive = true) },
    flag(short = 'H', help = "if a command line argument is a symlink to a directory, traverse it") {
        copy(traverseArgLinks = true, traverseAllLinks = false, traverseNoLinks = false)
    },
    flag(short = 'L', help = "traverse every symbolic link to a directory encountered") {
        copy(traverseArgLinks = false, traverseAllLinks = true, traverseNoLinks = false)
    },
    flag(short = 'P', help = "do not traverse any symbolic links") {
        copy(traverseArgLinks = false, traverseAllLinks = false, traverseNoLinks = true)
    },
)

internal const val HELP_HELP = "display this help and exit"
internal const val VERSION_HELP = "output version information and exit"

/** Every long name real chmod accepts. */
internal val CHMOD_LONGS: List<String> = INPUTS.flatMap { it.longs } + listOf("--help", "--version")

/** Long names real chmod refuses that a klap CLI could offer: long spellings of -H, -L and -P, and klap's built-ins. */
internal val LONGS_CHMOD_LACKS: List<String> = listOf(
    "--traverse-arg-links",
    "--traverse-all-links",
    "--traverse-no-links",
    "--json",
    "--color",
    "--completion",
    "--docs",
)

internal fun input(spelled: String): Input =
    INPUTS.firstOrNull { spelled in it.spellings } ?: fail("no chmod input $spelled")

internal fun inputFor(short: Char): Input? = INPUTS.firstOrNull { it.short == short }

/** The words `chmod --help` gives [spelled], a long or a one-letter short. */
internal fun chmodHelp(spelled: String): String = when (spelled) {
    "--help" -> HELP_HELP
    "--version" -> VERSION_HELP
    else -> input(spelled).help
}

/** The shortest prefix of [long] that real chmod reads as [long] alone, or null when only the whole name does. */
internal fun abbreviationOf(long: String): String? = (3 until long.length)
    .map { long.take(it) }
    .firstOrNull { prefix -> CHMOD_LONGS.count { it.startsWith(prefix) } == 1 }

/** The candidates the fixture's mode provider offers, in its order; real chmod accepts each as a MODE. */
internal val MODE_CANDIDATES: List<String> = listOf("644", "755", "600", "700", "u+x", "a-w", "go-rwx")

internal fun symbolic(vararg clauses: String): ChmodMode = ChmodMode.Symbolic(clauses.toList())

internal fun octal(
    digits: String,
    op: ChmodMode.OctalOp? = null,
): ChmodMode = ChmodMode.Octal(digits.toInt(radix = 8), op)

/** What `chmod u+x f` binds before any option: the record most cells start from. */
internal val U_X_F: ChmodInputs = NOTHING_BOUND.copy(mode = symbolic("u+x"), files = listOf(CliPath("f")))
