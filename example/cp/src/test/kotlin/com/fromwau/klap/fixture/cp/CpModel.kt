package com.fromwau.klap.fixture.cp

import kotlin.test.fail

/*
 * A table of the inputs the installed GNU cp 9.12 documents, from which the matrix tests generate their cells.
 * HELP is `cp --help`, each entry's lines joined with one space and otherwise verbatim. How each option reads its
 * value (required, optional, a choice, a list) is the help's own `--opt[=X]` notation, checked with real cp runs
 * inside a throwaway directory; the bindings are the fixture's record for what each word means to cp.
 */

/** How an input reads the words after its name. */
internal sealed interface Kind {
    data object Flag : Kind

    /**
     * A value: [choices] null for free text, [list] for cp's comma-separated ATTR_LIST of [choices], [path] for a
     * directory. [bare] is what a bare optional-value spelling binds, null when the value is required.
     */
    data class Value(
        val sample: String,
        val choices: List<String>? = null,
        val list: Boolean = false,
        val path: Boolean = false,
        val bare: (CpInputs.() -> CpInputs)? = null,
    ) : Kind {
        val optional: Boolean get() = bare != null
    }
}

/**
 * One input of real cp: its [long] name (null for a short-only flag), its [shorts], cp's own [help], and how it binds
 * into the fixture's record.
 */
internal data class Input(
    val long: String?,
    val shorts: List<Char>,
    val help: String,
    val kind: Kind,
    val bind: CpInputs.(Any) -> CpInputs,
) {
    val name: String get() = long ?: "-${shorts.single()}"

    override fun toString(): String = name
}

private fun flag(
    long: String?,
    vararg shorts: Char,
    help: String,
    bind: CpInputs.() -> CpInputs,
) = Input(long, shorts.toList(), help, Kind.Flag) { bind() }

private fun value(
    long: String,
    vararg shorts: Char,
    help: String,
    kind: Kind.Value,
    bind: CpInputs.(String) -> CpInputs,
) = Input(long, shorts.toList(), help, kind) { bind(it as String) }

@Suppress("UNCHECKED_CAST")
private fun list(
    long: String,
    help: String,
    kind: Kind.Value,
    bind: CpInputs.(List<String>) -> CpInputs,
) = Input(long, emptyList(), help, kind.copy(list = true)) { bind(it as List<String>) }

/** cp's ATTR_LIST names, from the paragraph under `cp --help`. */
internal val ATTRIBUTES: List<String> = listOf("mode", "ownership", "timestamps", "links", "context", "xattr", "all")

private val DEFAULT_ATTRIBUTES = listOf("mode", "ownership", "timestamps")

/**
 * This record with [names] preserved, or explicitly not when [on] is false, as an option naming them after
 * everything already bound leaves them: cp's option loop sets each attribute in turn, and `all` names every one.
 */
internal fun CpInputs.preserving(
    names: List<String>,
    on: Boolean = true,
): CpInputs {
    val named = names.flatMap { if (it == "all") ATTRIBUTES - "all" else listOf(it) }
    val kept = if (on) preserve.orEmpty() + named else preserve.orEmpty() - named.toSet()
    val dropped = if (on) noPreserve.orEmpty() - named.toSet() else noPreserve.orEmpty() + named
    fun inHelpOrder(attributes: List<String>) = ATTRIBUTES.filter { it in attributes }.ifEmpty { null }
    return copy(preserve = inHelpOrder(kept), noPreserve = inHelpOrder(dropped))
}

/**
 * Each backup control `cp --help` lists, by its every name, as the record holds it: by its first name. `--backup=t`
 * made b.~1~, `--backup=nil` and `--backup=never` made b~, `--backup=off` made none.
 */
private val CONTROLS: Map<String, String> = mapOf(
    "none" to "none",
    "off" to "none",
    "numbered" to "numbered",
    "t" to "numbered",
    "existing" to "existing",
    "nil" to "existing",
    "simple" to "simple",
    "never" to "simple",
)

/** Every name of a backup control, in `cp --help` order. */
internal val BACKUP_CONTROLS: List<String> = CONTROLS.keys.toList()

/** Every input of real cp other than `--help` and `--version`, in `cp --help` order. */
internal val INPUTS: List<Input> = listOf(
    flag("--archive", 'a', help = "same as -dR --preserve=all") {
        copy(archive = true, recursive = true, dereference = Dereference.NEVER).preserving(listOf("all"))
    },
    flag("--attributes-only", help = "don't copy the file data, just the attributes") {
        copy(attributesOnly = true)
    },
    value(
        "--backup",
        help = "make a backup of each existing destination file",
        kind = Kind.Value(
            sample = "numbered",
            choices = BACKUP_CONTROLS,
            bare = { copy(backup = backup ?: "existing") },
        ),
    ) { copy(backup = CONTROLS.getValue(it)) },
    flag(null, 'b', help = "like --backup but does not accept an argument") { copy(backup = backup ?: "existing") },
    flag("--copy-contents", help = "copy contents of special files when recursive") { copy(copyContents = true) },
    flag(null, 'd', help = "same as --no-dereference --preserve=links") {
        copy(dereference = Dereference.NEVER).preserving(listOf("links"))
    },
    flag("--debug", help = "explain how a file is copied.  Implies -v") { copy(debug = true) },
    flag(
        "--force",
        'f',
        help = "if an existing destination file cannot be opened, remove it and try again " +
            "(this option is ignored when the -n option is also used)",
    ) { copy(force = true) },
    flag("--interactive", 'i', help = "prompt before overwrite (overrides a previous -n option)") {
        copy(interactive = true)
    },
    flag(null, 'H', help = "follow command-line symbolic links in SOURCE") {
        copy(dereference = Dereference.COMMAND_LINE)
    },
    flag("--dereference", 'L', help = "always follow symbolic links in SOURCE") {
        copy(dereference = Dereference.ALWAYS)
    },
    flag("--no-dereference", 'P', help = "never follow symbolic links in SOURCE") {
        copy(dereference = Dereference.NEVER)
    },
    flag("--keep-directory-symlink", help = "follow existing symlinks to directories") {
        copy(keepDirectorySymlink = true)
    },
    flag("--link", 'l', help = "hard link files instead of copying") { copy(link = true) },
    flag("--no-clobber", 'n', help = "(deprecated) silently skip existing files.  See also --update") {
        copy(noClobber = true)
    },
    flag(null, 'p', help = "same as --preserve=mode,ownership,timestamps") { preserving(DEFAULT_ATTRIBUTES) },
    list(
        "--preserve",
        help = "preserve the specified attributes",
        kind = Kind.Value(
            sample = "mode",
            choices = ATTRIBUTES,
            bare = { preserving(DEFAULT_ATTRIBUTES) },
        ),
    ) { preserving(it) },
    list(
        "--no-preserve",
        help = "don't preserve the specified attributes",
        kind = Kind.Value(sample = "mode", choices = ATTRIBUTES),
    ) { preserving(it, on = false) },
    flag("--parents", help = "use full source file name under DIRECTORY") { copy(parents = true) },
    // `cp --help`: "-R, -r, --recursive"; `cp --rec` reaches --recursive.
    flag("--recursive", 'R', 'r', help = "copy directories recursively") { copy(recursive = true) },
    value(
        "--reflink",
        help = "control clone/CoW copies. See below",
        kind = Kind.Value(
            sample = "auto",
            choices = listOf("auto", "always", "never"),
            bare = { copy(reflink = "always") },
        ),
    ) { copy(reflink = it) },
    flag(
        "--remove-destination",
        help = "remove each existing destination file before attempting to open it (contrast with --force)",
    ) { copy(removeDestination = true) },
    value(
        "--sparse",
        help = "control creation of sparse files. See below",
        kind = Kind.Value(sample = "always", choices = listOf("auto", "always", "never")),
    ) { copy(sparse = it) },
    flag("--strip-trailing-slashes", help = "remove any trailing slashes from each SOURCE argument") {
        copy(stripTrailingSlashes = true)
    },
    flag("--symbolic-link", 's', help = "make symbolic links instead of copying") { copy(symbolicLink = true) },
    // `cp -S .x a b` makes b.x: a suffix asks for a backup, as -b does.
    value("--suffix", 'S', help = "override the usual backup suffix", kind = Kind.Value(sample = ".bak")) {
        copy(suffix = it, backup = backup ?: "existing")
    },
    value(
        "--target-directory",
        't',
        help = "copy all SOURCE arguments into DIRECTORY",
        kind = Kind.Value(sample = "dir", path = true),
    ) { copy(targetDirectory = it, sources = sources + listOfNotNull(dest), dest = null) },
    flag("--no-target-directory", 'T', help = "treat DEST as a normal file") { copy(noTargetDirectory = true) },
    value(
        "--update",
        help = "control which existing files are updated; UPDATE={all,none,none-fail,older(default)}",
        kind = Kind.Value(
            sample = "all",
            choices = listOf("all", "none", "none-fail", "older"),
            bare = { copy(update = "older") },
        ),
    ) { copy(update = it) },
    flag(null, 'u', help = "equivalent to --update[=older].  See below") { copy(update = "older") },
    flag("--verbose", 'v', help = "explain what is being done") { copy(verbose = true) },
    flag("--one-file-system", 'x', help = "stay on this file system") { copy(oneFileSystem = true) },
    flag(null, 'Z', help = "set SELinux security context of destination file to default type") {
        copy(selinuxDefault = true)
    },
    value(
        "--context",
        help = "like -Z, or if CTX is specified then set the SELinux or SMACK security context to CTX",
        kind = Kind.Value(sample = "ctx", bare = { copy(selinuxDefault = true) }),
    ) { copy(context = it) },
)

internal const val HELP_HELP = "display this help and exit"
internal const val VERSION_HELP = "output version information and exit"

/** Every long name real cp accepts. */
internal val CP_LONGS: List<String> = INPUTS.mapNotNull { it.long } + listOf("--help", "--version")

/** Long names real cp refuses that a klap CLI could offer: klap's built-ins, and `--recursive-r` beside -r. */
internal val KLAP_ONLY_LONGS: List<String> =
    listOf("--recursive-r", "--help-all", "--json", "--color", "--completion", "--docs")

internal fun input(name: String): Input = INPUTS.firstOrNull { it.name == name } ?: fail("no cp input $name")

internal fun inputFor(short: Char): Input? = INPUTS.firstOrNull { short in it.shorts }

/** The shortest prefix of [long] that real cp reads as [long] alone, or null when only the whole name does. */
internal fun abbreviationOf(long: String): String? = (3 until long.length)
    .map { long.take(it) }
    .firstOrNull { prefix -> CP_LONGS.count { it.startsWith(prefix) } == 1 }

/** The long name real cp reads [spelled] as: itself, or the one long it is the unique prefix of. */
private fun resolveLong(spelled: String): String? =
    CP_LONGS.firstOrNull { it == spelled } ?: CP_LONGS.filter { it.startsWith(spelled) }.singleOrNull()

/** The input real cp reads the long name [spelled] as, abbreviated or not. */
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

/** What a line copying `a` to `b` binds before any option: the record every cell starts from. */
internal val A_TO_B: CpInputs = NOTHING_BOUND.copy(sources = listOf("a"), dest = "b")
