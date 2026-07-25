package com.fromwau.klap.fixture.mv

import com.fromwau.klap.CliError
import com.fromwau.klap.Err
import com.fromwau.klap.Ok
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.11 `mv`, reproduced as a command-line surface only. The action never moves anything.
 *
 * Real usage:
 * ```
 * mv [OPTION]... [-T] SOURCE DEST
 * mv [OPTION]... SOURCE... DIRECTORY
 * mv [OPTION]... -t DIRECTORY SOURCE...
 * ```
 *     --backup[=CONTROL]         make a backup of each existing destination file
 * -b                             like --backup but does not accept an argument
 *     --debug                    explain how a file is copied; implies -v
 *     --exchange                 exchange source and destination
 * -f, --force                    do not prompt before overwriting
 * -i, --interactive              prompt before overwrite
 * -n, --no-clobber               do not overwrite an existing file
 *     --no-copy                  do not copy if renaming fails
 *     --strip-trailing-slashes   remove any trailing slashes from each SOURCE argument
 * -S, --suffix=SUFFIX            override the usual backup suffix
 * -t, --target-directory=DIR     move all SOURCE arguments into DIRECTORY
 * -T, --no-target-directory      treat DEST as a normal file
 *     --update[=UPDATE]          control which existing files are updated
 * -u                             equivalent to --update[=older]
 * -v, --verbose                  explain what is being done
 * -Z, --context                  set SELinux security context of destination to default type
 *     --help / --version
 */
public fun mvCli(): TypedCli<MvInputs> = cliOf("mv") {
    description = "Rename SOURCE to DEST, or move SOURCE(s) to DIRECTORY."
    version = "9.11"
    epilogue = "The backup suffix is '~', unless set with --suffix or SIMPLE_BACKUP_SUFFIX. " +
        "If you specify more than one of -i, -f, -n, only the final one takes effect."

    example("mv old.txt new.txt", "rename a single file")
    example("mv a.txt b.txt archive/", "move two files into a directory")
    example("mv -t archive/ a.txt b.txt", "the same move, with the destination named first")
    example("mv -T src dest", "treat DEST as a normal file, never as a directory to move into")

    // `--backup[=CONTROL]` is optional-value: bare `--backup` binds "existing" (GNU's own documented
    // default when CONTROL is omitted and VERSION_CONTROL is unset), matching real mv's `-b` exactly, and
    // the space form still leaves the operands alone.
    val backup = option("--backup", help = "make a backup of each existing destination file")
        .choice("none", "off", "numbered", "t", "existing", "nil", "simple", "never")
        .optionalValue("existing")

    // Short-only, with no invented `--b` alongside it.
    val backupSimple = flag("-b", help = "like --backup but does not accept an argument")

    val force = flag("--force", "-f", help = "do not prompt before overwriting")
    val interactive = flag("--interactive", "-i", help = "prompt before overwrite")
    val noClobber = flag("--no-clobber", "-n", help = "do not overwrite an existing file")

    lastWins(force, interactive, noClobber)

    val targetDirectory = option("--target-directory", "-t", help = "move all SOURCE arguments into DIRECTORY")
        .file()
        .placeholder("DIRECTORY")
    val noTargetDirectory = flag("--no-target-directory", "-T", help = "treat DEST as a normal file")

    val suffix = option("--suffix", "-S", help = "override the usual backup suffix").placeholder("SUFFIX")

    // mv spells --update two ways: the long form takes a value, the short form (`-u`) takes none. klap
    // has no way to express that as one input, so they stay independent holders the action reconciles by
    // hand — `mv -u --update=none a b` is legal here, where real mv simply resolves to `none`.
    val update = option("--update", help = "control which existing files are updated")
        .choice("all", "none", "none-fail", "older")
        .optionalValue("older")
    val updateOlder = flag("-u", help = "equivalent to --update=older")

    val verbose = flag("--verbose", "-v", help = "explain what is being done")
    val debug = flag("--debug", help = "explain how a file is copied; implies -v")
    val exchange = flag("--exchange", help = "exchange source and destination")
    val noCopy = flag("--no-copy", help = "do not copy if renaming fails")
    val stripTrailingSlashes = flag(
        "--strip-trailing-slashes",
        help = "remove any trailing slashes from each SOURCE argument",
    )
    val context = flag("--context", "-Z", help = "set the SELinux security context of DEST to the default type")

    // Two slots, and the shape-dependent minimum comes with them: `mv a b c` binds sources = [a, b] and
    // dest = c, `-t DIR` removes the DEST slot so `mv -t d a` binds sources = [a] with no destination
    // operand, and `mv a` is a parse-level "missing required argument <source>" rather than a rule the
    // action re-implements.
    //
    // KLAP-GAP: `-T` forces exactly two operands, but a variadic argument only declares a minimum, so the
    // cap is hand-written below; `.file()` on DEST also can't restrict to directories-only, unlike real mv.
    val sources = argument("source", "the files to move")
        .placeholder("SOURCE")
        .file()
        .multiple(min = 1)

    val dest = argument("dest", "where to move them")
        .placeholder("DEST")
        .file()
        .absentWhen(targetDirectory)

    action<String>(human = { it }) {
        val target = targetDirectory()

        if (target != null && noTargetDirectory()) {
            return@action Err(
                CliError.Failure("cannot combine --target-directory (-t) and --no-target-directory (-T)"),
            )
        }

        val sources = sources()
        // Non-null exactly when -t is absent: absentWhen empties the slot on those lines and only those,
        // and the slot is Required in every other reading.
        val destination = target ?: dest()!!
        // Named off the rejoined operand list so the token reported is real mv's own third operand,
        // rather than the second source the split leaves in that position.
        if (noTargetDirectory() && sources.size > 1) {
            return@action Err(
                CliError.Failure("extra operand '${(sources + destination)[2]}' with --no-target-directory (-T)"),
            )
        }

        // lastWins (declared above) guarantees at most one of these three reads true; order below only
        // matters for the fallback.
        val onCollision = when {
            noClobber() -> "skip"
            interactive() -> "prompt"
            force() -> "overwrite"
            else -> "overwrite"
        }
        val updateMode = update() ?: "older".takeIf { updateOlder() } ?: "all"
        val backupMode = backup() ?: "existing".takeIf { backupSimple() }
        val backupSuffix = suffix() ?: "~"
        val notes = listOfNotNull(
            "on-collision=$onCollision",
            "update=$updateMode",
            backupMode?.let { "backup=$it (suffix=$backupSuffix)" },
            "exchange".takeIf { exchange() },
            "no-copy".takeIf { noCopy() },
            "strip-trailing-slashes".takeIf { stripTrailingSlashes() },
            "context".takeIf { context() },
            "verbose".takeIf { verbose() || debug() },
        )
        Ok("would move ${sources.size} source(s) to $destination [${notes.joinToString(", ")}]")
    }

    projection {
        MvInputs(
            backup(),
            backupSimple(),
            force(),
            interactive(),
            noClobber(),
            targetDirectory(),
            noTargetDirectory(),
            suffix(),
            update(),
            updateOlder(),
            verbose(),
            debug(),
            exchange(),
            noCopy(),
            stripTrailingSlashes(),
            context(),
            sources(),
            dest(),
        )
    }
}

/**
 * What one `mv` line binds, as values rather than as the handles that read them.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does
 * not name is pinned to its default too.
 */
public data class MvInputs(
    val backup: String?,
    val backupSimple: Boolean,
    val force: Boolean,
    val interactive: Boolean,
    val noClobber: Boolean,
    val targetDirectory: String?,
    val noTargetDirectory: Boolean,
    val suffix: String?,
    val update: String?,
    val updateOlder: Boolean,
    val verbose: Boolean,
    val debug: Boolean,
    val exchange: Boolean,
    val noCopy: Boolean,
    val stripTrailingSlashes: Boolean,
    val context: Boolean,
    val sources: List<String>,
    val dest: String?,
)

/** `mv` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_BOUND: MvInputs = MvInputs(
    backup = null,
    backupSimple = false,
    force = false,
    interactive = false,
    noClobber = false,
    targetDirectory = null,
    noTargetDirectory = false,
    suffix = null,
    update = null,
    updateOlder = false,
    verbose = false,
    debug = false,
    exchange = false,
    noCopy = false,
    stripTrailingSlashes = false,
    context = false,
    sources = emptyList(),
    dest = null,
)
