package com.fromwau.klap.fixture.mv

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.CliError
import com.fromwau.klap.Help
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.io.path
import com.fromwau.klap.OptionalValue
import com.fromwau.klap.RepeatedOptions
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.12 `mv`, reproduced as a command-line surface only. The action never moves anything.
 *
 * Real usage:
 * ```
 * mv [OPTION]... [-T] SOURCE DEST
 * mv [OPTION]... SOURCE... DIRECTORY
 * mv [OPTION]... -t DIRECTORY SOURCE...
 * ```
 *     --backup[=CONTROL]         make a backup of each existing destination file
 * -b                             like --backup but does not accept an argument
 *     --debug                    explain how a file is copied.  Implies -v
 *     --exchange                 exchange source and destination
 * -f, --force                    do not prompt before overwriting
 * -i, --interactive              prompt before overwrite
 * -n, --no-clobber               (deprecated) silently skip existing files.  See also --update
 *     --no-copy                  do not copy if renaming fails
 *     --strip-trailing-slashes   remove any trailing slashes from each SOURCE argument
 * -S, --suffix=SUFFIX            override the usual backup suffix
 * -t, --target-directory=DIRECTORY  move all SOURCE arguments into DIRECTORY
 * -T, --no-target-directory      treat DEST as a normal file
 *     --update[=UPDATE]          control which existing files are updated;
 *                                UPDATE={all,none,none-fail,older(default)}
 * -u                             equivalent to --update[=older].  See below
 * -v, --verbose                  explain what is being done
 * -Z, --context                  set SELinux security context of destination file to default type
 *     --help                     display this help and exit
 *     --version                  output version information and exit
 *
 * [environment] stands in for the process environment, where mv reads `VERSION_CONTROL` and
 * `SIMPLE_BACKUP_SUFFIX`; klap reads none, so a test hands the fixture its own.
 */
public fun mvCli(environment: Map<String, String> = emptyMap()): TypedCli<MvInputs> = cliOf("mv") {
    // GNU mv takes any unambiguous prefix of a long option: `mv --strip` reaches --strip-trailing-slashes.
    abbreviation = Abbreviation.Options
    // GNU mv has none of klap's built-ins but --help, which has no short (`mv -h` is "invalid option -- 'h'").
    builtins {
        help = Help.ByCommand(description = "display this help and exit")
        json = Builtin.Off
        color = Builtin.Off
        completion = Builtin.Off
        docs = Builtin.Off
    }
    help = "Rename SOURCE to DEST, or move SOURCE(s) to DIRECTORY."
    version = Version.Of("9.12", description = "output version information and exit", text = MV_VERSION_TEXT)
    epilogue = "The backup suffix is '~', unless set with --suffix or SIMPLE_BACKUP_SUFFIX. " +
        "If you specify more than one of -i, -f, -n, only the final one takes effect."

    example("mv old.txt new.txt", "rename a single file")
    example("mv a.txt b.txt archive/", "move two files into a directory")
    example("mv -t archive/ a.txt b.txt", "the same move, with the destination named first")
    example("mv -T src dest", "treat DEST as a normal file, never as a directory to move into")

    // mv keeps the last control --backup names and reads it once every option is read, so an invalid earlier one
    // is never looked at: the words stay free text here and [resolveBackup] reads them.
    val backup = option(long = "backup", help = "make a backup of each existing destination file")
        .placeholder("CONTROL")
        .optionalValue()
        .multiple()
        .completeWith { candidates(BACKUP_CONTROL_NAMES.keys) }

    val backupDefault = flag(short = 'b', help = "like --backup but does not accept an argument")

    val force = flag(short = 'f', long = "force", help = "do not prompt before overwriting")
    val interactive = flag(short = 'i', long = "interactive", help = "prompt before overwrite")
    val noClobber = flag(
        short = 'n',
        long = "no-clobber",
        help = "(deprecated) silently skip existing files.  See also --update",
    )

    lastWins(force, interactive, noClobber)

    val targetDirectory = option(
        short = 't',
        long = "target-directory",
        help = "move all SOURCE arguments into DIRECTORY",
    )
        .path()
        .placeholder("DIRECTORY")
        .repeated(RepeatedOptions.Refuse)
    val noTargetDirectory = flag(short = 'T', long = "no-target-directory", help = "treat DEST as a normal file")

    atMostOne(targetDirectory, noTargetDirectory)

    val suffix = option(short = 'S', long = "suffix", help = "override the usual backup suffix").placeholder("SUFFIX")

    val update = option(
        long = "update",
        help = "control which existing files are updated; UPDATE={all,none,none-fail,older(default)}",
    )
        .enum<Update>(spelling = { it.cli })
        .placeholder("UPDATE")
        .optionalValue()
    val updateOlder = flag(short = 'u', help = "equivalent to --update[=older].  See below")

    lastWins(update, updateOlder)

    val verbose = flag(short = 'v', long = "verbose", help = "explain what is being done")
    val debug = flag(long = "debug", help = "explain how a file is copied.  Implies -v")
    val exchange = flag(long = "exchange", help = "exchange source and destination")
    val noCopy = flag(long = "no-copy", help = "do not copy if renaming fails")
    val stripTrailingSlashes = flag(
        long = "strip-trailing-slashes",
        help = "remove any trailing slashes from each SOURCE argument",
    )
    val context = flag(
        short = 'Z',
        long = "context",
        help = "set SELinux security context of destination file to default type",
    )

    // Two slots, and the shape-dependent minimum comes with them: `mv a b c` binds sources = [a, b] and
    // dest = c, `-t DIR` removes the DEST slot so `mv -t d a` binds sources = [a] with no destination
    // operand, `-T` leaves one SOURCE so `mv -T a b c` is an extra operand, and `mv a` is a parse-level
    // "missing required argument <source>" rather than a rule the action re-implements.
    //
    // KLAP-GAP: `.path()` on DEST can't restrict to directories-only, unlike real mv.
    val sources = argument("source", "the files to move")
        .placeholder("SOURCE")
        .path()
        .multiple(min = 1)
        .atMostWhen(noTargetDirectory, 1)

    val dest = argument("dest", "where to move them")
        .placeholder("DEST")
        .path()
        .absentWhen(targetDirectory)

    fun ValueScope.backupAsked(): Boolean = backup().isNotEmpty() || backupDefault() || suffix() != null

    fun ValueScope.backupMade(): Result<BackupControl, CliError> = resolveBackup(
        typed = backup().lastGiven().orEmpty(),
        asked = backupAsked(),
        environment = environment,
    )

    fun ValueScope.updateMode(): Update = when (val given = update()) {
        is OptionalValue.Given -> given.value
        OptionalValue.Bare -> Update.Older
        null -> if (updateOlder()) Update.Older else Update.All
    }

    validateInputs { backupMade().errorOrNull() }

    // A requested backup counts here whatever its control: `mv --backup=none -n a b` is refused too.
    validateInputs {
        val skips = noClobber() || updateMode() == Update.None || updateMode() == Update.NoneFail
        if (backupAsked() && (skips || exchange())) {
            CliError.Usage("cannot combine --backup with --exchange, -n, or --update=none-fail")
        } else {
            null
        }
    }

    val read: ValueScope.() -> MvInputs = {
        val asked = backupAsked()
        MvInputs(
            backup = backupMade().getOrNull() ?: BackupControl.None,
            force = force(),
            interactive = interactive(),
            noClobber = noClobber(),
            targetDirectory = targetDirectory(),
            noTargetDirectory = noTargetDirectory(),
            suffix = resolveSuffix(suffix(), asked, environment),
            update = updateMode(),
            verbose = verbose() || debug(),
            debug = debug(),
            exchange = exchange(),
            noCopy = noCopy(),
            stripTrailingSlashes = stripTrailingSlashes(),
            context = context(),
            sources = sources(),
            dest = dest(),
        )
    }

    action<String>(human = { it }) {
        val bound = read()
        Ok("would move ${bound.sources.size} source(s) to '${bound.targetDirectory ?: bound.dest}' [$bound]")
    }

    projection(read)
}

/** mv's `--update` modes, each typed as its [cli] name: `mv --help` lists UPDATE={all,none,none-fail,older}. */
public enum class Update(public val cli: String) {
    All("all"),
    None("none"),
    NoneFail("none-fail"),
    Older("older"),
}

/** The backup mv makes: `mv --help` pairs "none, off / numbered, t / existing, nil / simple, never". */
public enum class BackupControl {
    None,
    Numbered,
    Existing,
    Simple,
}

/**
 * What one `mv` line binds, as mv reads it rather than as the inputs that spell it: `-b`, `--backup` and `-S` are
 * one [backup] with its [suffix], `-u` is an [update], and `--debug` is [verbose] too.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does
 * not name is pinned to its default too.
 */
public data class MvInputs(
    /** The control mv backs up with, [BackupControl.None] when it makes no backup. */
    val backup: BackupControl,
    val force: Boolean,
    val interactive: Boolean,
    val noClobber: Boolean,
    val targetDirectory: CliPath?,
    val noTargetDirectory: Boolean,
    /** The suffix a simple backup takes, from `-S` or `SIMPLE_BACKUP_SUFFIX`, or null for mv's `~`. */
    val suffix: String?,
    /** The update mode, [Update.All] when the line names none, as `mv --help` says. */
    val update: Update,
    val verbose: Boolean,
    val debug: Boolean,
    val exchange: Boolean,
    val noCopy: Boolean,
    val stripTrailingSlashes: Boolean,
    val context: Boolean,
    val sources: List<CliPath>,
    val dest: CliPath?,
)

/** `mv` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_BOUND: MvInputs = MvInputs(
    backup = BackupControl.None,
    force = false,
    interactive = false,
    noClobber = false,
    targetDirectory = null,
    noTargetDirectory = false,
    suffix = null,
    update = Update.All,
    verbose = false,
    debug = false,
    exchange = false,
    noCopy = false,
    stripTrailingSlashes = false,
    context = false,
    sources = emptyList(),
    dest = null,
)

/** Every spelling of a backup control `mv --help` lists, with the control it names. */
private val BACKUP_CONTROL_NAMES: Map<String, BackupControl> = mapOf(
    "none" to BackupControl.None,
    "off" to BackupControl.None,
    "numbered" to BackupControl.Numbered,
    "t" to BackupControl.Numbered,
    "existing" to BackupControl.Existing,
    "nil" to BackupControl.Existing,
    "simple" to BackupControl.Simple,
    "never" to BackupControl.Simple,
)

/** The last value attached to an `.optionalValue()` option, or null when every occurrence was bare. */
private fun List<OptionalValue<String>>.lastGiven(): String? =
    filterIsInstance<OptionalValue.Given<String>>().lastOrNull()?.value

/**
 * The backup mv makes, read as mv reads it once the options are done: the last control a `--backup` names
 * ([typed], empty for none), else `VERSION_CONTROL`, else `existing`. [BackupControl.None] when nothing on the line
 * asks for a backup ([asked]), and then the variable is not read.
 */
private fun resolveBackup(
    typed: String,
    asked: Boolean,
    environment: Map<String, String>,
): Result<BackupControl, CliError> {
    if (!asked) return Ok(BackupControl.None)
    val fromEnvironment = environment["VERSION_CONTROL"].orEmpty()
    return when {
        typed.isNotEmpty() -> argmatch("backup type", typed, BACKUP_CONTROL_NAMES)
        fromEnvironment.isNotEmpty() -> argmatch("\$VERSION_CONTROL", fromEnvironment, BACKUP_CONTROL_NAMES)
        else -> Ok(BackupControl.Existing)
    }
}

/**
 * The suffix a backup takes: [typed] from `-S`, else a `SIMPLE_BACKUP_SUFFIX` that is not empty and holds no slash,
 * read only when the line [asked] for a backup.
 */
private fun resolveSuffix(
    typed: String?,
    asked: Boolean,
    environment: Map<String, String>,
): String? = typed ?: environment["SIMPLE_BACKUP_SUFFIX"]?.takeIf { asked && it.isNotEmpty() && '/' !in it }

/** [word] as gnulib's argmatch reads it against [names]: a name, or a prefix of names that mean one thing. */
private fun <T> argmatch(
    context: String,
    word: String,
    names: Map<String, T>,
): Result<T, CliError> {
    names[word]?.let { return Ok(it) }
    val reached = names.filterKeys { it.startsWith(word) }
    return when (reached.values.toSet().size) {
        1 -> Ok(reached.values.first())
        0 -> Err(CliError.InvalidChoice(context, word, names.keys.toList()))
        else -> Err(CliError.AmbiguousValue(context, word, reached.keys.toList()))
    }
}

/**
 * What `mv --version` prints for GNU coreutils 9.12, the version installed when it was pinned
 * (2026-10-10).
 */
internal val MV_VERSION_TEXT: String = """mv (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Mike Parker, David MacKenzie, and Jim Meyering."""
