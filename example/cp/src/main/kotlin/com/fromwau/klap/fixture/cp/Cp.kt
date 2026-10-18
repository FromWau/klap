package com.fromwau.klap.fixture.cp

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.CliError
import com.fromwau.klap.CompletionScope
import com.fromwau.klap.ConversionError
import com.fromwau.klap.Flag
import com.fromwau.klap.Help
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.io.path
import com.fromwau.klap.Opt
import com.fromwau.klap.OptionalValue
import com.fromwau.klap.RepeatedOptions
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.12 `cp`, reproduced as a command-line surface only. Every action body is a stub.
 *
 * Real usage:
 *   cp [OPTION]... [-T] SOURCE DEST
 *   cp [OPTION]... SOURCE... DIRECTORY
 *   cp [OPTION]... -t DIRECTORY SOURCE...
 *
 * The assigned shape, `cp [-r] [-i] [-v] SOURCE... DEST`, is the second form: a variadic positional
 * followed by exactly one required positional, which is declared below as the two slots it is.
 *
 * [environment] stands in for the process environment, where cp reads `VERSION_CONTROL` and
 * `SIMPLE_BACKUP_SUFFIX`; klap reads none, so a test hands the fixture its own.
 */
public fun cpCli(environment: Map<String, String> = emptyMap()): TypedCli<CpInputs> = cliOf("cp") {
    // GNU cp takes any unambiguous prefix of a long option or a choice value: `cp --parent` is --parents.
    abbreviation = Abbreviation.Options
    // GNU cp has none of klap's built-ins but --help, which has no short (`cp -h` is "invalid option -- 'h'").
    builtins {
        help = Help.ByCommand(description = "display this help and exit")
        json = Builtin.Off
        color = Builtin.Off
        completion = Builtin.Off
        docs = Builtin.Off
    }
    help = "Copy SOURCE to DEST, or multiple SOURCE(s) to DIRECTORY."
    version = Version.Of("9.12", description = "output version information and exit", text = CP_VERSION_TEXT)
    author = "Torbjorn Granlund, David MacKenzie, and Jim Meyering"
    epilogue = "Report bugs to: bug-coreutils@gnu.org"

    example("cp -r src/ backup/", "copy a directory tree")
    example("cp -iv a.txt b.txt archive/", "two sources into a directory, prompting and reporting")
    example("cp -t archive/ a.txt b.txt", "same, with the destination named up front")

    // ---------------------------------------------------------------------------------------------
    // Copy behaviour
    // ---------------------------------------------------------------------------------------------

    val archive = flag(short = 'a', long = "archive", help = "same as -dR --preserve=all")

    val recursive = flag(short = listOf('R', 'r'), long = "recursive", help = "copy directories recursively")

    val copyContents = flag(long = "copy-contents", help = "copy contents of special files when recursive")

    val noDereferencePreserveLinks = flag(short = 'd', help = "same as --no-dereference --preserve=links")

    val debug = flag(long = "debug", help = "explain how a file is copied.  Implies -v")

    val attributesOnly = flag(long = "attributes-only", help = "don't copy the file data, just the attributes")

    val link = flag(short = 'l', long = "link", help = "hard link files instead of copying")

    val symbolicLink = flag(short = 's', long = "symbolic-link", help = "make symbolic links instead of copying")

    // `cp -l -s a b`: "cannot make both hard and symbolic links".
    atMostOne(link, symbolicLink)

    val oneFileSystem = flag(short = 'x', long = "one-file-system", help = "stay on this file system")

    val parents = flag(long = "parents", help = "use full source file name under DIRECTORY")

    val stripTrailingSlashes =
        flag(long = "strip-trailing-slashes", help = "remove any trailing slashes from each SOURCE argument")

    val verbose = flag(short = 'v', long = "verbose", help = "explain what is being done")

    // ---------------------------------------------------------------------------------------------
    // Overwrite policy
    //
    // Real cp keeps ONE prompt policy that -i and -n both write, so the last of the two on the line is
    // the one that holds: `cp -n -i` prompts, `cp -i -n` skips. -f and --update are separate settings
    // that apply ON TOP of that policy rather than overriding it, and their position makes no
    // difference: `cp -f -i` and `cp -i -f` both prompt, and `cp -i --update=none` and
    // `cp --update=none -i` both skip without prompting. So the -i/-n pair is one override set, and
    // -u with --update another, since -u is --update=older spelled short.
    // ---------------------------------------------------------------------------------------------

    val force = flag(
        short = 'f',
        long = "force",
        help = "if an existing destination file cannot be opened, remove it and try again " +
            "(this option is ignored when the -n option is also used)",
    )

    val interactive = flag(
        short = 'i',
        long = "interactive",
        help = "prompt before overwrite (overrides a previous -n option)",
    )

    val noClobber = flag(
        short = 'n',
        long = "no-clobber",
        help = "(deprecated) silently skip existing files.  See also --update",
    )

    lastWins(interactive, noClobber)

    val removeDestination = flag(
        long = "remove-destination",
        help = "remove each existing destination file before attempting to open it (contrast with --force)",
    )

    val update = option(
        long = "update",
        help = "control which existing files are updated; UPDATE={all,none,none-fail,older(default)}",
    )
        .enum<Update>(spelling = { it.cli })
        .placeholder("UPDATE")
        .optionalValue("older")

    val updateOlder = flag(short = 'u', help = "equivalent to --update[=older].  See below")

    lastWins(update, updateOlder)

    // ---------------------------------------------------------------------------------------------
    // Symbolic links
    //
    // Three flags rather than a negatable -L: a line naming none of them is a state of its own, as cp
    // then follows links unless it copies recursively (`cp lnk out` copies the file, `cp -R lnk out` the
    // link). They, -a and -d each set how cp follows links, so the last of them decides
    // ([replayLinksAndAttributes]).
    // ---------------------------------------------------------------------------------------------

    val dereference = flag(short = 'L', long = "dereference", help = "always follow symbolic links in SOURCE")

    val noDereference = flag(short = 'P', long = "no-dereference", help = "never follow symbolic links in SOURCE")

    val dereferenceArgs = flag(short = 'H', help = "follow command-line symbolic links in SOURCE")

    val keepDirectorySymlink =
        flag(long = "keep-directory-symlink", help = "follow existing symlinks to directories")

    // ---------------------------------------------------------------------------------------------
    // Backups (declared inside a group to exercise the help-section API)
    //
    // cp keeps the last control --backup names and reads it once every option is read, so an invalid
    // earlier one is never looked at: the words stay free text here and [resolveBackup] reads them.
    // ---------------------------------------------------------------------------------------------

    val backup: Opt<List<OptionalValue<String>>>
    val backupDefault: Flag
    val suffix: Opt<String?>

    group("Backup") {
        backup = option(long = "backup", help = "make a backup of each existing destination file")
            .placeholder("CONTROL")
            .optionalValue()
            .multiple()
            .completeWith { candidates(BACKUP_CONTROLS.keys) }

        backupDefault = flag(short = 'b', help = "like --backup but does not accept an argument")

        suffix = option(short = 'S', long = "suffix", help = "override the usual backup suffix").placeholder("SUFFIX")
    }

    // ---------------------------------------------------------------------------------------------
    // Attribute preservation
    //
    // -a, -d, -p, --preserve and --no-preserve each switch attributes on or off, so for each attribute the
    // last of them decides ([replayLinksAndAttributes]): `cp -p --no-preserve=mode` drops the mode, and
    // `cp --no-preserve=mode -p` keeps it.
    // ---------------------------------------------------------------------------------------------

    val preserve = option(long = "preserve", help = "preserve the specified attributes")
        .convert(::attributeList)
        .placeholder("ATTR_LIST")
        .optionalValue(DEFAULT_ATTRIBUTES.joinToString(","))
        .multiple()
        .completeWith(provider = completeAttributes)

    val noPreserve = option(long = "no-preserve", help = "don't preserve the specified attributes")
        .convert(::attributeList)
        .placeholder("ATTR_LIST")
        .multiple()
        .completeWith(provider = completeAttributes)

    // KLAP-GAP: this must NOT be spelled `flag(short = 'p', long = "preserve")`: `--preserve` is already an option
    // above, and `validateDuplicateOptionFlagNames` rejects two inputs sharing a long name at construction.
    val preserveDefaults = flag(short = 'p', help = "same as --preserve=mode,ownership,timestamps")

    val sparse = option(long = "sparse", help = "control creation of sparse files. See below")
        .enum<When>()
        .placeholder("WHEN")

    val reflink = option(long = "reflink", help = "control clone/CoW copies. See below")
        .enum<When>()
        .placeholder("WHEN")
        .optionalValue("always")

    val selinuxDefault = flag(short = 'Z', help = "set SELinux security context of destination file to default type")

    val context = option(
        long = "context",
        help = "like -Z, or if CTX is specified then set the SELinux or SMACK security context to CTX",
    )
        .placeholder("CTX")
        .optionalValue()
        .multiple()

    // ---------------------------------------------------------------------------------------------
    // Destination selection
    // ---------------------------------------------------------------------------------------------

    val targetDirectory = option(
        short = 't',
        long = "target-directory",
        help = "copy all SOURCE arguments into DIRECTORY",
    )
        .path()
        .placeholder("DIRECTORY")
        .repeated(RepeatedOptions.Refuse)

    val noTargetDirectory = flag(short = 'T', long = "no-target-directory", help = "treat DEST as a normal file")

    atMostOne(targetDirectory, noTargetDirectory)

    // ---------------------------------------------------------------------------------------------
    // Operands
    // ---------------------------------------------------------------------------------------------

    // Two slots, and the shape-dependent minimum comes with them: `cp a b c` binds sources = [a, b] and
    // dest = c, `-t DIR` removes the DEST slot so `cp -t d a` binds sources = [a] with no destination
    // operand, `-T` leaves one SOURCE so `cp -T a b c` is an extra operand, and `cp a` is a parse-level
    // "missing required argument <source>" rather than a rule the action re-implements.
    //
    // KLAP-GAP: `.path()` on DEST can't restrict to directories-only once more than one SOURCE is given.
    val sources = argument("source", "the files to copy")
        .placeholder("SOURCE")
        .path()
        .multiple(min = 1)
        .atMostWhen(noTargetDirectory, 1)

    val dest = argument("dest", "where to copy them")
        .placeholder("DEST")
        .path()
        .absentWhen(targetDirectory)

    fun ValueScope.backupMade(): Result<Backup?, CliError> = resolveBackup(
        typed = backup().lastGiven().orEmpty(),
        asked = backup().isNotEmpty() || backupDefault() || suffix() != null,
        suffix = suffix(),
        environment = environment,
    )

    validateInputs { backupMade().errorOrNull() }

    validateInputs {
        val skips = noClobber() || update() == Update.None || update() == Update.NoneFail
        val backs = backupMade().getOrNull() != null
        if (backs && skips) CliError.Usage("--backup is mutually exclusive with -n or --update=none-fail") else null
    }

    validateInputs {
        val sparseOther = sparse() != null && sparse() != When.Auto
        if (reflink() == When.Always && sparseOther) {
            CliError.Usage("--reflink can be used only with --sparse=auto")
        } else {
            null
        }
    }

    // cp's option loop assigns these settings one option at a time, so the line's order decides between them.
    fun ValueScope.replayLinksAndAttributes(): Replay {
        val replay = Replay()
        val occurrences = inOrder(
            archive,
            noDereferencePreserveLinks,
            preserveDefaults,
            dereference,
            noDereference,
            dereferenceArgs,
            preserve,
            noPreserve,
        )
        for ((input, value) in occurrences) {
            when (input) {
                archive -> replay.archive()
                noDereferencePreserveLinks -> replay.noDereferencePreserveLinks()
                preserveDefaults -> replay.preserve(DEFAULT_ATTRIBUTES, on = true)
                dereference -> replay.dereference = Dereference.ALWAYS
                noDereference -> replay.dereference = Dereference.NEVER
                dereferenceArgs -> replay.dereference = Dereference.COMMAND_LINE
                preserve -> replay.preserve(attributesOf(value), on = true)
                noPreserve -> replay.preserve(attributesOf(value), on = false)
            }
        }
        return replay
    }

    val read: ValueScope.() -> CpInputs = {
        val made = backupMade().getOrNull()
        val contexts = context()
        val replayed = replayLinksAndAttributes()
        CpInputs(
            archive = archive(),
            recursive = recursive() || archive(),
            copyContents = copyContents(),
            debug = debug(),
            attributesOnly = attributesOnly(),
            link = link(),
            symbolicLink = symbolicLink(),
            oneFileSystem = oneFileSystem(),
            parents = parents(),
            stripTrailingSlashes = stripTrailingSlashes(),
            verbose = verbose(),
            force = force(),
            interactive = interactive(),
            noClobber = noClobber(),
            removeDestination = removeDestination(),
            update = update() ?: Update.Older.takeIf { updateOlder() },
            dereference = replayed.dereference,
            keepDirectorySymlink = keepDirectorySymlink(),
            backup = made?.control,
            suffix = made?.suffix,
            preserve = replayed.preserved(),
            noPreserve = replayed.notPreserved(),
            sparse = sparse(),
            reflink = reflink(),
            selinuxDefault = selinuxDefault() || OptionalValue.Bare in contexts,
            context = contexts.lastGiven(),
            targetDirectory = targetDirectory(),
            noTargetDirectory = noTargetDirectory(),
            sources = sources(),
            dest = dest(),
        )
    }

    action<String>(human = { it }) {
        val bound = read()
        Ok("would copy ${bound.sources.size} source(s) to '${bound.targetDirectory ?: bound.dest}' [$bound]")
    }

    projection(read)
}

/** How cp follows symbolic links in SOURCE, as the last of `-L`, `-P` and `-H` on the line says. */
public enum class Dereference { ALWAYS, NEVER, COMMAND_LINE }

/** cp's `--update` modes, each typed as its [cli] name: `cp --help` lists UPDATE={all,none,none-fail,older}. */
public enum class Update(public val cli: String) {
    All("all"),
    None("none"),
    NoneFail("none-fail"),
    Older("older"),
}

/** A WHEN of `--sparse` and `--reflink`, typed as its lowercase name. */
public enum class When {
    Auto,
    Always,
    Never,
}

/** The backup cp makes: `cp --help` pairs "none, off / numbered, t / existing, nil / simple, never". */
public enum class BackupControl {
    None,
    Numbered,
    Existing,
    Simple,
}

/**
 * What one `cp` line binds, as cp reads it rather than as the inputs that spell it: `-r` and `-R` are one
 * [recursive], `-u` is an [update], and `-b`, `--backup` and `-S` are one [backup] with its [suffix]. The
 * shorthands are what they stand for: `-a` is `-dR --preserve=all`, `-d` is `--no-dereference --preserve=links`
 * and `-p` is `--preserve=mode,ownership,timestamps`, each in its place on the line.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does
 * not name is pinned to its default too.
 */
public data class CpInputs(
    /** `-a`, which beyond `-dR --preserve=all` keeps quiet about context and xattr cp cannot preserve. */
    val archive: Boolean,
    val recursive: Boolean,
    val copyContents: Boolean,
    val debug: Boolean,
    val attributesOnly: Boolean,
    val link: Boolean,
    val symbolicLink: Boolean,
    val oneFileSystem: Boolean,
    val parents: Boolean,
    val stripTrailingSlashes: Boolean,
    val verbose: Boolean,
    val force: Boolean,
    val interactive: Boolean,
    val noClobber: Boolean,
    val removeDestination: Boolean,
    val update: Update?,
    /** Null when the line names none of `-L`, `-P`, `-H`, `-a` and `-d`. */
    val dereference: Dereference?,
    val keepDirectorySymlink: Boolean,
    /** The control cp backs up with, or null when it makes no backup. */
    val backup: BackupControl?,
    /** The suffix a simple backup takes, from `-S` or `SIMPLE_BACKUP_SUFFIX`, or null for cp's `~`. */
    val suffix: String?,
    /**
     * The attributes cp preserves, in the order `cp --help` lists them, `all` read as every one; null for none.
     * Each is where the later of the options naming it put it, here or in [noPreserve].
     */
    val preserve: List<String>?,
    /** The attributes the line explicitly does not preserve, which for the mode is not cp's default either. */
    val noPreserve: List<String>?,
    val sparse: When?,
    val reflink: When?,
    val selinuxDefault: Boolean,
    val context: String?,
    val targetDirectory: CliPath?,
    val noTargetDirectory: Boolean,
    val sources: List<CliPath>,
    val dest: CliPath?,
)

/** `cp` with no arguments at all: every field at the default the declaration gives it. */
public val NOTHING_BOUND: CpInputs = CpInputs(
    archive = false,
    recursive = false,
    copyContents = false,
    debug = false,
    attributesOnly = false,
    link = false,
    symbolicLink = false,
    oneFileSystem = false,
    parents = false,
    stripTrailingSlashes = false,
    verbose = false,
    force = false,
    interactive = false,
    noClobber = false,
    removeDestination = false,
    update = null,
    dereference = null,
    keepDirectorySymlink = false,
    backup = null,
    suffix = null,
    preserve = null,
    noPreserve = null,
    sparse = null,
    reflink = null,
    selinuxDefault = false,
    context = null,
    targetDirectory = null,
    noTargetDirectory = false,
    sources = emptyList(),
    dest = null,
)

/** Every spelling of a backup control `cp --help` lists, with the control it names. */
private val BACKUP_CONTROLS: Map<String, BackupControl> = mapOf(
    "none" to BackupControl.None,
    "off" to BackupControl.None,
    "numbered" to BackupControl.Numbered,
    "t" to BackupControl.Numbered,
    "existing" to BackupControl.Existing,
    "nil" to BackupControl.Existing,
    "simple" to BackupControl.Simple,
    "never" to BackupControl.Simple,
)

/** cp's ATTR_LIST names, from the paragraph under `cp --help`. */
private val ATTRIBUTES: List<String> = listOf("mode", "ownership", "timestamps", "links", "context", "xattr", "all")

private val DEFAULT_ATTRIBUTES: List<String> = listOf("mode", "ownership", "timestamps")

private val EVERY_ATTRIBUTE: List<String> = ATTRIBUTES - "all"

/** How cp follows links and which attributes it preserves, as its option loop has assigned them so far. */
private class Replay {
    var dereference: Dereference? = null
    private val preserved = mutableSetOf<String>()
    private val notPreserved = mutableSetOf<String>()

    fun archive() {
        noDereferencePreserveLinks()
        preserve(EVERY_ATTRIBUTE, on = true)
    }

    fun noDereferencePreserveLinks() {
        dereference = Dereference.NEVER
        preserve(listOf("links"), on = true)
    }

    fun preserve(names: List<String>, on: Boolean) {
        for (name in names.flatMap { if (it == "all") EVERY_ATTRIBUTE else listOf(it) }) {
            if (on) preserved += name else preserved -= name
            if (on) notPreserved -= name else notPreserved += name
        }
    }

    fun preserved(): List<String>? = EVERY_ATTRIBUTE.filter { it in preserved }.ifEmpty { null }

    fun notPreserved(): List<String>? = EVERY_ATTRIBUTE.filter { it in notPreserved }.ifEmpty { null }
}

/** The attribute list an occurrence of `--preserve` or `--no-preserve` gave. */
private fun attributesOf(value: Any?): List<String> = (value as List<*>).map { it as String }

/** The last value attached to an `.optionalValue()` option, or null when every occurrence was bare. */
private fun List<OptionalValue<String>>.lastGiven(): String? =
    filterIsInstance<OptionalValue.Given<String>>().lastOrNull()?.value

/** The backup a line makes: its [control] and the [suffix] a simple one takes. */
private data class Backup(
    val control: BackupControl,
    val suffix: String?,
)

/**
 * The backup cp makes, read as cp reads it once the options are done: the last control a `--backup` names
 * ([typed], empty for none), else `VERSION_CONTROL`, else `existing`; the suffix from `-S`, else a
 * `SIMPLE_BACKUP_SUFFIX` that is not empty and holds no slash. Null when nothing on the line asks for a backup
 * ([asked]), and then neither variable is read.
 */
private fun resolveBackup(
    typed: String,
    asked: Boolean,
    suffix: String?,
    environment: Map<String, String>,
): Result<Backup?, CliError> {
    if (!asked) return Ok(null)
    val fromEnvironment = environment["VERSION_CONTROL"].orEmpty()
    val control = when {
        typed.isNotEmpty() -> argmatch("backup type", typed, BACKUP_CONTROLS)
        fromEnvironment.isNotEmpty() -> argmatch("\$VERSION_CONTROL", fromEnvironment, BACKUP_CONTROLS)
        else -> Ok(BackupControl.Existing)
    }
    val environmentSuffix = environment["SIMPLE_BACKUP_SUFFIX"]?.takeIf { it.isNotEmpty() && '/' !in it }
    return control.map { Backup(it, suffix ?: environmentSuffix) }
}

/** [word] as gnulib's argmatch reads it against [names]: a name, or a prefix of names that mean one thing. */
private fun argmatch(
    context: String,
    word: String,
    names: Map<String, BackupControl>,
): Result<BackupControl, CliError> {
    names[word]?.let { return Ok(it) }
    val reached = names.filterKeys { it.startsWith(word) }
    return when (reached.values.toSet().size) {
        1 -> Ok(reached.values.first())
        0 -> Err(CliError.InvalidChoice(context, word, names.keys.toList()))
        else -> Err(CliError.AmbiguousValue(context, word, reached.keys.toList()))
    }
}

/** An ATTR_LIST: each comma-separated word one of cp's attribute names or the unique prefix of one. */
private fun attributeList(raw: String): Result<List<String>, ConversionError> {
    val names = raw.split(',').map { word -> ATTRIBUTES.singleOrNull { it.startsWith(word) && word.isNotEmpty() } }
    return if (null in names) Err(ConversionError.NotOneOf(ATTRIBUTES)) else Ok(names.filterNotNull())
}

/** The attribute names a list can go on with after its last comma, leaving out the ones it already names. */
private val completeAttributes: CompletionScope.() -> Unit = {
    val done = current.substringBeforeLast(',', missingDelimiterValue = "")
    val named = done.split(',')
    val head = if (done.isEmpty()) "" else "$done,"
    ATTRIBUTES.filterNot { it in named }.forEach { candidate(head + it) }
}

/**
 * What `cp --version` prints for GNU coreutils 9.12, the version installed when it was pinned
 * (2026-10-10).
 */
internal val CP_VERSION_TEXT: String = """cp (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Torbjörn Granlund, David MacKenzie, and Jim Meyering."""
