package com.fromwau.klap.fixture.rm

import kotlin.test.fail

/*
 * A table of the inputs the installed GNU rm 9.12 documents, from which the matrix tests generate their cells. HELP is
 * `rm --help` (bold and hyperlink escapes stripped), each entry's lines joined with one space and otherwise verbatim.
 * How each option reads its value is the help's own `--opt[=X]` notation, checked with real rm runs inside a throwaway
 * directory; `--no-preserve-root` was read from the help, getopt and the strings of /usr/bin/rm only, as the oracle may
 * not run it in any spelling. The bindings are the fixture's record for what each word means to rm, kept in this one
 * table so a change of the record shape is made here.
 */

/** How an input reads the words after its name. */
internal sealed interface Kind {
    data object Flag : Kind

    /**
     * An optional value, attached only: [words] every value rm accepts, in the order rm's own "Valid arguments" list
     * gives them, [reads] the value a typed word names (null when rm refuses it), and [bare] what the spelling
     * without a value binds on top of the record so far.
     */
    data class Value(
        val words: List<String>,
        val reads: (String) -> String?,
        val bare: RmInputs.() -> RmInputs,
    ) : Kind
}

/**
 * One input of real rm: its [long] name (null for a short-only flag), its [shorts], rm's own [help], how it binds into
 * the fixture's record, and whether rm takes an abbreviation of the long ([abbreviates]).
 */
internal data class Input(
    val long: String?,
    val shorts: List<Char>,
    val help: String,
    val kind: Kind,
    val abbreviates: Boolean = true,
    val bind: RmInputs.(String?) -> RmInputs,
) {
    val name: String get() = long ?: "-${shorts.single()}"

    override fun toString(): String = name
}

private fun flag(
    long: String?,
    vararg shorts: Char,
    help: String,
    abbreviates: Boolean = true,
    bind: RmInputs.() -> RmInputs,
) = Input(long, shorts.toList(), help, Kind.Flag, abbreviates) { bind() }

/**
 * rm's `--interactive` values, in the order rm's "Valid arguments are" lists them: `never`, `no`, `none` / `once` /
 * `always`, `yes` (`rm --interactive=bogus a`).
 */
internal val WHEN_WORDS: List<String> = listOf("never", "no", "none", "once", "always", "yes")

/** The prompt mode each of [WHEN_WORDS] names. */
internal val WHEN_OF: Map<String, Prompt> = mapOf(
    "never" to Prompt.Never,
    "no" to Prompt.Never,
    "none" to Prompt.Never,
    "once" to Prompt.Once,
    "always" to Prompt.Always,
    "yes" to Prompt.Always,
)

/**
 * The prompt mode argmatch reads [typed] as: an exact word, or a prefix whose every match names one mode
 * (`rm --interactive=n a` removes without asking, `--interactive=` is "ambiguous argument ''").
 */
internal fun whenOf(typed: String): Prompt? {
    WHEN_OF[typed]?.let { return it }
    if (typed.isEmpty()) return null
    return WHEN_OF.filterKeys { it.startsWith(typed) }.values.toSet().singleOrNull()
}

/*
 * -f, -i, -I and --interactive set one prompt mode, the later winning (`rm -f -i a` prompts, `rm -i -f a` does not,
 * `rm --interactive=never -i a` prompts). -f also ignores missing operands and files, which a later -i, -I or
 * --interactive=once/always undoes and a later --interactive=never keeps (`rm -f -i` is "missing operand", `rm -f
 * --interactive=never nonexist` exits 0). The record holds the mode in [RmInputs.prompt] and the reading of missing
 * files in [RmInputs.ignoreMissing].
 */

/** `-f`: never prompt, and ignore missing files. */
internal fun RmInputs.forcing(): RmInputs = copy(prompt = Prompt.Never, ignoreMissing = true)

/** `--interactive=[mode]`, or the -i or -I it equals: never keeps an earlier -f's reading of missing files. */
internal fun RmInputs.promptingBy(mode: Prompt): RmInputs =
    copy(prompt = mode, ignoreMissing = ignoreMissing && mode == Prompt.Never)

private val INTERACTIVE = Input(
    long = "--interactive",
    shorts = emptyList(),
    help = "prompt according to WHEN: never, once (-I), or always (-i); without WHEN, prompt always",
    kind = Kind.Value(
        words = WHEN_WORDS,
        reads = { whenOf(it)?.name?.lowercase() },
        bare = { promptingBy(Prompt.Always) },
    ),
) { value -> promptingBy(whenOf(value!!)!!) }

private const val PRESERVE_HELP = "do not remove '/' (default); with 'all', reject any command line argument on a " +
    "separate device from its parent"

// rm compares the value with "all" whole: `rm --preserve-root=al a` is "unrecognized --preserve-root argument: 'al'".
private val PRESERVE_ROOT = Input(
    long = "--preserve-root",
    shorts = emptyList(),
    help = PRESERVE_HELP,
    kind = Kind.Value(
        words = listOf("all"),
        reads = { it.takeIf { it == "all" } },
        bare = { copy(preserveRoot = PreserveRoot.Root) },
    ),
) { copy(preserveRoot = PreserveRoot.All) }

/** Every input of real rm other than `--help` and `--version`, in `rm --help` order. */
internal val INPUTS: List<Input> = listOf(
    flag("--force", 'f', help = "ignore nonexistent files and arguments, never prompt") { forcing() },
    flag(null, 'i', help = "prompt before every removal") { promptingBy(Prompt.Always) },
    flag(
        null,
        'I',
        help = "prompt once before removing more than three files, or when removing recursively; less intrusive " +
            "than -i, while still giving protection against most mistakes",
    ) { promptingBy(Prompt.Once) },
    INTERACTIVE,
    flag(
        "--one-file-system",
        help = "when removing a hierarchy recursively, skip any directory that is on a file system different from " +
            "that of the corresponding command line argument",
    ) { copy(oneFileSystem = true) },
    // `strings /usr/bin/rm`: "you may not abbreviate the --no-preserve-root option".
    flag("--no-preserve-root", help = "do not treat '/' specially", abbreviates = false) {
        copy(preserveRoot = PreserveRoot.Off)
    },
    PRESERVE_ROOT,
    // `rm --help`: "-r, -R, --recursive"; `rm -R dd` and `rm -rR dd` remove dd as `rm -r dd` does.
    flag("--recursive", 'r', 'R', help = "remove directories and their contents recursively") {
        copy(recursive = true)
    },
    flag("--dir", 'd', help = "remove empty directories") { copy(dir = true) },
    flag("--verbose", 'v', help = "explain what is being done") { copy(verbose = true) },
)

internal const val HELP_HELP = "display this help and exit"
internal const val VERSION_HELP = "output version information and exit"

/**
 * rm's undocumented long, which `rm --=x a` lists among the possibilities and `rm ---presume-input-tty a` accepts: it
 * makes rm prompt as if standard input were a terminal.
 */
internal const val PRESUME_INPUT_TTY = "---presume-input-tty"

/** Every long name real rm documents. */
internal val RM_LONGS: List<String> = INPUTS.mapNotNull { it.long } + listOf("--help", "--version")

/** Every long name real rm's getopt table holds, the undocumented one included. */
private val GETOPT_LONGS: List<String> = RM_LONGS + PRESUME_INPUT_TTY

/** klap's built-in long names, which real rm refuses (`rm --json a`: "unrecognized option"). */
internal val KLAP_ONLY_LONGS: List<String> = listOf("--json", "--color", "--completion", "--docs")

/** A long name for -I, which real rm refuses (`rm --interactive-once a`: "unrecognized option"). */
internal const val INVENTED_LONG = "--interactive-once"

/** The inputs whose longer `rm --help` descriptions a test of their own checks whole. */
internal val WORDED_INPUTS: Set<String> = setOf("-I", "--one-file-system", "--no-preserve-root", "--preserve-root")

internal fun input(name: String): Input = INPUTS.firstOrNull { it.name == name } ?: fail("no rm input $name")

internal fun inputFor(short: Char): Input? = INPUTS.firstOrNull { short in it.shorts }

/** The shortest prefix of [long] that getopt reads as [long] alone, or null when only the whole name does. */
internal fun abbreviationOf(long: String): String? = (3 until long.length)
    .map { long.take(it) }
    .firstOrNull { prefix -> GETOPT_LONGS.count { it.startsWith(prefix) } == 1 }

/** The long name getopt reads [spelled] as: itself, or the one long it is the unique prefix of. */
private fun resolveLong(spelled: String): String? =
    GETOPT_LONGS.firstOrNull { it == spelled } ?: GETOPT_LONGS.filter { it.startsWith(spelled) }.singleOrNull()

/** Whether getopt calls [spelled] ambiguous: a prefix of two or more longs and none of them whole. */
internal fun isAmbiguousLong(spelled: String): Boolean =
    spelled !in GETOPT_LONGS && GETOPT_LONGS.count { it.startsWith(spelled) } > 1

/** The input real rm reads the long name [spelled] as, abbreviated or not, or null when rm refuses the spelling. */
internal fun inputOfLong(spelled: String): Input? = resolveLong(spelled)
    ?.let { long -> INPUTS.firstOrNull { it.long == long } }
    ?.takeIf { it.abbreviates || it.long == spelled }

/** What a line removing `a` and `b` binds before any option: the record every cell starts from. */
internal val A_AND_B: RmInputs = NOTHING_BOUND.copy(files = listOf("a", "b"))
