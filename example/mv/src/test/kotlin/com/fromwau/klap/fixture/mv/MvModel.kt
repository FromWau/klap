package com.fromwau.klap.fixture.mv

import kotlin.test.fail

/*
 * A table of the inputs the installed GNU mv 9.12 documents, from which the matrix tests generate their cells.
 * HELP is `mv --help`, each entry's lines joined with one space and otherwise verbatim. How each option reads its
 * value (required, optional, a choice) is the help's own `--opt[=X]` notation, checked with real mv runs inside a
 * throwaway directory; `-Z, --context` was read from the help only, as the oracle may not run it. The bindings are
 * the fixture's record for what each word means to mv, kept in this one table so a change of the record shape is
 * made here.
 */

/** How an input reads the words after its name. */
internal sealed interface Kind {
    data object Flag : Kind

    /**
     * A value: [choices] null for free text, [path] for a directory. [bare] is what a bare optional-value spelling
     * binds on top of the record so far, null when the value is required.
     */
    data class Value(
        val sample: String,
        val choices: List<String>? = null,
        val path: Boolean = false,
        val bare: (MvInputs.() -> MvInputs)? = null,
    ) : Kind {
        val optional: Boolean get() = bare != null
    }
}

/**
 * One input of real mv: its [long] name (null for a short-only flag), its [shorts], mv's own [help], and how it binds
 * into the fixture's record.
 */
internal data class Input(
    val long: String?,
    val shorts: List<Char>,
    val help: String,
    val kind: Kind,
    val bind: MvInputs.(Any) -> MvInputs,
) {
    val name: String get() = long ?: "-${shorts.single()}"

    override fun toString(): String = name
}

private fun flag(
    long: String?,
    vararg shorts: Char,
    help: String,
    bind: MvInputs.() -> MvInputs,
) = Input(long, shorts.toList(), help, Kind.Flag) { bind() }

private fun value(
    long: String,
    vararg shorts: Char,
    help: String,
    kind: Kind.Value,
    bind: MvInputs.(String) -> MvInputs,
) = Input(long, shorts.toList(), help, kind) { bind(it as String) }

/** Every name of a backup control, in `mv --help` order: "none, off / numbered, t / existing, nil / simple, never". */
internal val BACKUP_CONTROLS: List<String> =
    listOf("none", "off", "numbered", "t", "existing", "nil", "simple", "never")

/** Each backup control name with the control it names, as `mv --help` pairs them. */
internal val CONTROL_OF: Map<String, String> = mapOf(
    "none" to "none",
    "off" to "none",
    "numbered" to "numbered",
    "t" to "numbered",
    "existing" to "existing",
    "nil" to "existing",
    "simple" to "simple",
    "never" to "simple",
)

/** The control a backup control name binds, as [CONTROL_OF] pairs them. */
internal fun controlOf(name: String): BackupControl =
    BackupControl.entries.single { it.name.lowercase() == CONTROL_OF.getValue(name) }

/** `mv --help`: UPDATE={all,none,none-fail,older(default)}. */
internal val UPDATE_MODES: List<String> = listOf("all", "none", "none-fail", "older")

/** The update mode [name] binds. */
internal fun updateOf(name: String): Update = Update.entries.single { it.cli == name }

/**
 * A backup asked for with no control named (`-b`, a bare `--backup`, `-S`): the control already named, else mv's
 * default `existing`. The record cannot tell a named `none` from no backup, so no cell names `--backup=none` first.
 */
private fun MvInputs.askingBackup(): MvInputs =
    if (backup == BackupControl.None) copy(backup = BackupControl.Existing) else this

/** The one of `-f`, `-i` and `-n` on: mv keeps the last of them ("only the final one takes effect"). */
private fun MvInputs.prompting(
    force: Boolean = false,
    interactive: Boolean = false,
    noClobber: Boolean = false,
): MvInputs = copy(force = force, interactive = interactive, noClobber = noClobber)

/** Every input of real mv other than `--help` and `--version`, in `mv --help` order. */
internal val INPUTS: List<Input> = listOf(
    // `mv --backup=simple --backup a b` made b~: a bare --backup keeps the control already named.
    value(
        "--backup",
        help = "make a backup of each existing destination file",
        kind = Kind.Value(
            sample = "numbered",
            choices = BACKUP_CONTROLS,
            bare = { askingBackup() },
        ),
    ) { copy(backup = controlOf(it)) },
    flag(null, 'b', help = "like --backup but does not accept an argument") { askingBackup() },
    flag("--debug", help = "explain how a file is copied.  Implies -v") { copy(debug = true, verbose = true) },
    flag("--exchange", help = "exchange source and destination") { copy(exchange = true) },
    flag("--force", 'f', help = "do not prompt before overwriting") { prompting(force = true) },
    flag("--interactive", 'i', help = "prompt before overwrite") { prompting(interactive = true) },
    flag("--no-clobber", 'n', help = "(deprecated) silently skip existing files.  See also --update") {
        prompting(noClobber = true)
    },
    flag("--no-copy", help = "do not copy if renaming fails") { copy(noCopy = true) },
    flag("--strip-trailing-slashes", help = "remove any trailing slashes from each SOURCE argument") {
        copy(stripTrailingSlashes = true)
    },
    value("--suffix", 'S', help = "override the usual backup suffix", kind = Kind.Value(sample = ".bak")) {
        askingBackup().copy(suffix = it)
    },
    value(
        "--target-directory",
        't',
        help = "move all SOURCE arguments into DIRECTORY",
        kind = Kind.Value(sample = "dir", path = true),
    ) { copy(targetDirectory = it, sources = sources + listOfNotNull(dest), dest = null) },
    flag("--no-target-directory", 'T', help = "treat DEST as a normal file") { copy(noTargetDirectory = true) },
    value(
        "--update",
        help = "control which existing files are updated; UPDATE={all,none,none-fail,older(default)}",
        kind = Kind.Value(sample = "all", choices = UPDATE_MODES, bare = { copy(update = Update.Older) }),
    ) { copy(update = updateOf(it)) },
    flag(null, 'u', help = "equivalent to --update[=older].  See below") { copy(update = Update.Older) },
    flag("--verbose", 'v', help = "explain what is being done") { copy(verbose = true) },
    flag("--context", 'Z', help = "set SELinux security context of destination file to default type") {
        copy(context = true)
    },
)

internal const val HELP_HELP = "display this help and exit"
internal const val VERSION_HELP = "output version information and exit"

/** Every long name real mv accepts. */
internal val MV_LONGS: List<String> = INPUTS.mapNotNull { it.long } + listOf("--help", "--version")

/** Long names real mv refuses that a klap CLI offers by default: klap's built-ins. */
internal val KLAP_ONLY_LONGS: List<String> = listOf("--json", "--color", "--completion", "--docs")

/** mv's help for --debug, -n, --update, -u, -Z, --help and --version, whose cells a test of mv's words checks whole. */
internal val WORDED_HELP: Set<String> =
    listOf("--debug", "--no-clobber", "--update", "-u", "--context").map { input(it).help }.toSet() +
        setOf(HELP_HELP, VERSION_HELP)

internal fun input(name: String): Input = INPUTS.firstOrNull { it.name == name } ?: fail("no mv input $name")

internal fun inputFor(short: Char): Input? = INPUTS.firstOrNull { short in it.shorts }

/** The shortest prefix of [long] that real mv reads as [long] alone, or null when only the whole name does. */
internal fun abbreviationOf(long: String): String? = (3 until long.length)
    .map { long.take(it) }
    .firstOrNull { prefix -> MV_LONGS.count { it.startsWith(prefix) } == 1 }

/** The long name real mv reads [spelled] as: itself, or the one long it is the unique prefix of. */
private fun resolveLong(spelled: String): String? =
    MV_LONGS.firstOrNull { it == spelled } ?: MV_LONGS.filter { it.startsWith(spelled) }.singleOrNull()

/** The input real mv reads the long name [spelled] as, abbreviated or not. */
internal fun inputOfLong(spelled: String): Input? =
    resolveLong(spelled)?.let { long -> INPUTS.firstOrNull { it.long == long } }

/** The shortest prefix argmatch reads as [value] alone among [choices], or null when only the whole value does. */
internal fun choiceAbbreviation(
    value: String,
    choices: List<String>,
): String? = (1 until value.length)
    .map { value.take(it) }
    .firstOrNull { prefix -> choices.count { it.startsWith(prefix) } == 1 }

/** A prefix argmatch calls ambiguous among [choices], or null when there is none. */
internal fun ambiguousChoice(choices: List<String>): String? = choices
    .map { it.take(1) }
    .firstOrNull { prefix -> prefix !in choices && choices.count { it.startsWith(prefix) } > 1 }

/** What a line moving `a` to `b` binds before any option: the record every cell starts from. */
internal val A_TO_B: MvInputs = NOTHING_BOUND.copy(sources = listOf("a"), dest = "b")
