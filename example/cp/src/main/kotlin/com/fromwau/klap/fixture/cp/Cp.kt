package com.fromwau.klap.fixture.cp

import com.fromwau.klap.CliError
import com.fromwau.klap.Err
import com.fromwau.klap.Flag
import com.fromwau.klap.Ok
import com.fromwau.klap.Opt
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.11 `cp`, reproduced as a command-line surface only. Every action body is a stub.
 *
 * Real usage:
 *   cp [OPTION]... [-T] SOURCE DEST
 *   cp [OPTION]... SOURCE... DIRECTORY
 *   cp [OPTION]... -t DIRECTORY SOURCE...
 *
 * The assigned shape, `cp [-r] [-i] [-v] SOURCE... DEST`, is the second form: a variadic positional
 * followed by exactly one required positional, which is declared below as the two slots it is.
 */
public fun cpCli(): TypedCli<CpInputs> = cliOf("cp") {
    description = "Copy SOURCE to DEST, or multiple SOURCE(s) to DIRECTORY."
    version = "9.11"
    author = "Torbjorn Granlund, David MacKenzie, and Jim Meyering"
    epilogue = "Report bugs to: bug-coreutils@gnu.org"

    example("cp -r src/ backup/", "copy a directory tree")
    example("cp -iv a.txt b.txt archive/", "two sources into a directory, prompting and reporting")
    example("cp -t archive/ a.txt b.txt", "same, with the destination named up front")

    // ---------------------------------------------------------------------------------------------
    // Copy behaviour
    // ---------------------------------------------------------------------------------------------

    val archive = flag("--archive", "-a", help = "same as -dR --preserve=all")

    val recursive = flag("--recursive", "-R", help = "copy directories recursively")
    val recursiveLowercase = flag("--recursive-r", "-r", help = "alias of -R (klap cannot give one flag two shorts)")

    val copyContents = flag("--copy-contents", help = "copy contents of special files when recursive")

    val noDereferencePreserveLinks = flag("-d", help = "same as --no-dereference --preserve=links")

    val debug = flag("--debug", help = "explain how a file is copied; implies -v")

    val attributesOnly = flag("--attributes-only", help = "don't copy the file data, just the attributes")

    val link = flag("--link", "-l", help = "hard link files instead of copying")

    val symbolicLink = flag("--symbolic-link", "-s", help = "make symbolic links instead of copying")

    val oneFileSystem = flag("--one-file-system", "-x", help = "stay on this file system")

    val parents = flag("--parents", help = "use full source file name under DIRECTORY")

    val stripTrailingSlashes =
        flag("--strip-trailing-slashes", help = "remove any trailing slashes from each SOURCE argument")

    val verbose = flag("--verbose", "-v", help = "explain what is being done")

    // ---------------------------------------------------------------------------------------------
    // Overwrite policy
    //
    // Real cp keeps ONE prompt policy that -i and -n both write, so the last of the two on the line is
    // the one that holds: `cp -n -i` prompts, `cp -i -n` skips (verified, coreutils 9.11). -f and
    // --update are separate settings that apply ON TOP of that policy rather than overriding it, and
    // their position makes no difference: `cp -f -i` and `cp -i -f` both prompt and both override the
    // destination mode, and `cp -i --update=none` and `cp --update=none -i` both skip without prompting
    // (all verified). So the override set below is the -i/-n pair alone.
    // ---------------------------------------------------------------------------------------------

    val force =
        flag("--force", "-f", help = "if an existing destination file cannot be opened, remove it and try again")

    val interactive = flag("--interactive", "-i", help = "prompt before overwrite")

    val noClobber = flag("--no-clobber", "-n", help = "(deprecated) silently skip existing files")

    lastWins(interactive, noClobber)

    val removeDestination =
        flag("--remove-destination", help = "remove each existing destination file before opening it")

    val update = option("--update", help = "control which existing files are updated")
        .choice("all", "none", "none-fail", "older")
        .optionalValue("older")

    val updateOlder = flag("-u", help = "equivalent to --update=older")

    // ---------------------------------------------------------------------------------------------
    // Symbolic links
    //
    // cp documents `-L, --dereference` and `-P, --no-dereference` as one setting with `-H` as a third
    // state, last one wins. One negatable holder carries both halves of the pair with their real
    // spellings; `-H` is a separate flag because it is a third state rather than the negation of either.
    // ---------------------------------------------------------------------------------------------

    val dereference = flag("--dereference", "-L", help = "always follow symbolic links in SOURCE")
        .negatable("--no-dereference", "-P", default = false)

    val dereferenceArgs = flag("-H", help = "follow command-line symbolic links in SOURCE")

    lastWins(dereference, dereferenceArgs)

    val keepDirectorySymlink =
        flag("--keep-directory-symlink", help = "follow existing symlinks to directories")

    // ---------------------------------------------------------------------------------------------
    // Backups (declared inside a group to exercise the help-section API)
    // ---------------------------------------------------------------------------------------------

    val backup: Opt<String?>
    val backupSimple: Flag
    val suffix: Opt<String?>

    group("Backup") {
        backup = option("--backup", help = "make a backup of each existing destination file")
            .choice("none", "off", "numbered", "t", "existing", "nil", "simple", "never")
            .optionalValue("existing")

        backupSimple = flag("-b", help = "like --backup but does not accept an argument")

        suffix = option("--suffix", "-S", help = "override the usual backup suffix")
    }

    // ---------------------------------------------------------------------------------------------
    // Attribute preservation
    // ---------------------------------------------------------------------------------------------

    // `--preserve` could carry `.optionalValue()` too, like `--update`/`--backup`/`--reflink` above; it
    // stayed value-required on purpose, not for lack of an expressible form.
    val preserve = option("--preserve", help = "preserve the specified attributes (comma-separated)")
        .map { raw -> raw.split(",").map { it.trim() } }

    // Required-value in real cp too, so this one is exact.
    val noPreserve = option("--no-preserve", help = "don't preserve the specified attributes")
        .map { raw -> raw.split(",").map { it.trim() } }

    // KLAP-GAP: this must NOT be spelled `flag("preserve", "p")` — `--preserve` is already an option above,
    // and `validateDuplicateOptionFlagNames` rejects two inputs sharing a long name at construction.
    val preserveDefaults = flag("-p", help = "same as --preserve=mode,ownership,timestamps")

    val sparse = option("--sparse", help = "control creation of sparse files")
        .choice("auto", "always", "never")

    val reflink = option("--reflink", help = "control clone/CoW copies")
        .choice("auto", "always", "never")
        .optionalValue("always")

    val selinuxDefault = flag("-Z", help = "set the SELinux security context of the destination to the default type")

    // `--context` could carry `.optionalValue()` too, like `--preserve` above; it stayed value-required on
    // purpose, not for lack of an expressible form.
    val context = option("--context", help = "like -Z, or set the SELinux or SMACK security context to CTX")

    // ---------------------------------------------------------------------------------------------
    // Destination selection
    // ---------------------------------------------------------------------------------------------

    val targetDirectory = option("--target-directory", "-t", help = "copy all SOURCE arguments into DIRECTORY").file()

    val noTargetDirectory = flag("--no-target-directory", "-T", help = "treat DEST as a normal file")

    // ---------------------------------------------------------------------------------------------
    // Operands
    // ---------------------------------------------------------------------------------------------

    // Two slots, and the shape-dependent minimum comes with them: `cp a b c` binds sources = [a, b] and
    // dest = c, `-t DIR` removes the DEST slot so `cp -t d a` binds sources = [a] with no destination
    // operand, and `cp a` is a parse-level "missing required argument <source>" rather than a rule the
    // action re-implements.
    //
    // KLAP-GAP: `-T`'s exactly-two-operand cap has no declarative form (a variadic's `min` has no matching
    // max), and `.file()` on DEST can't restrict to directories-only once more than one SOURCE is given.
    val sources = argument("source", "the files to copy")
        .placeholder("SOURCE")
        .file()
        .multiple(min = 1)

    val dest = argument("dest", "where to copy them")
        .placeholder("DEST")
        .file()
        .absentWhen(targetDirectory)

    action<String>(human = { it }) {
        val target = targetDirectory()

        if (target != null && noTargetDirectory()) {
            return@action Err(CliError.Failure("cannot combine --target-directory (-t) and --no-target-directory (-T)"))
        }

        val sourcesBound = sources()
        // Non-null exactly when -t is absent: absentWhen empties the slot on those lines and only those,
        // and the slot is Required in every other reading.
        val destination = target ?: dest()!!
        // Named off the rejoined operand list so the token reported is real cp's own third operand,
        // rather than the second source the split leaves in that position.
        if (noTargetDirectory() && sourcesBound.size > 1) {
            return@action Err(
                CliError.Failure("extra operand '${(sourcesBound + destination)[2]}' with --no-target-directory (-T)"),
            )
        }

        // The override set already leaves at most one of -i/-n true; the rest of this chain only picks
        // which of real cp's independent settings gets to name the policy, since one label fits one.
        val overwrite = when {
            noClobber() -> "skip"
            interactive() -> "prompt"
            force() -> "force"
            update() != null || updateOlder() -> "update"
            else -> "overwrite"
        }

        val notes = buildList {
            if (archive()) add("archive")
            if (recursive() || recursiveLowercase()) add("recursive")
            if (copyContents()) add("copy-contents")
            if (noDereferencePreserveLinks()) add("d")
            if (debug()) add("debug")
            if (attributesOnly()) add("attributes-only")
            if (link()) add("link")
            if (symbolicLink()) add("symbolic-link")
            if (oneFileSystem()) add("one-file-system")
            if (parents()) add("parents")
            if (stripTrailingSlashes()) add("strip-trailing-slashes")
            if (removeDestination()) add("remove-destination")
            if (dereference()) add("dereference")
            if (!dereference()) add("no-dereference")
            if (dereferenceArgs()) add("dereference-args")
            if (keepDirectorySymlink()) add("keep-directory-symlink")
            if (backupSimple()) add("backup")
            if (preserveDefaults()) add("preserve-defaults")
            if (selinuxDefault()) add("selinux-default")
            if (verbose()) add("verbose")
            backup()?.let { add("backup=$it") }
            suffix()?.let { add("suffix=$it") }
            preserve()?.let { add("preserve=${it.joinToString(",")}") }
            noPreserve()?.let { add("no-preserve=${it.joinToString(",")}") }
            sparse()?.let { add("sparse=$it") }
            reflink()?.let { add("reflink=$it") }
            context()?.let { add("context=$it") }
            update()?.let { add("update=$it") }
        }

        Ok(
            "would copy ${sourcesBound.size} source(s) to '$destination' " +
                    "[$overwrite]${if (notes.isEmpty()) "" else " " + notes.joinToString(" ")}",
        )
    }

    projection {
        CpInputs(
            archive(),
            recursive(),
            recursiveLowercase(),
            copyContents(),
            noDereferencePreserveLinks(),
            debug(),
            attributesOnly(),
            link(),
            symbolicLink(),
            oneFileSystem(),
            parents(),
            stripTrailingSlashes(),
            verbose(),
            force(),
            interactive(),
            noClobber(),
            removeDestination(),
            update(),
            updateOlder(),
            dereference(),
            dereferenceArgs(),
            keepDirectorySymlink(),
            backup(),
            backupSimple(),
            suffix(),
            preserve(),
            noPreserve(),
            preserveDefaults(),
            sparse(),
            reflink(),
            selinuxDefault(),
            context(),
            targetDirectory(),
            noTargetDirectory(),
            sources(),
            dest(),
        )
    }
}

/**
 * What one `cp` line binds, as values rather than as the handles that read them.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does
 * not name is pinned to its default too.
 */
public data class CpInputs(
    val archive: Boolean,
    val recursive: Boolean,
    val recursiveLowercase: Boolean,
    val copyContents: Boolean,
    val noDereferencePreserveLinks: Boolean,
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
    val update: String?,
    val updateOlder: Boolean,
    val dereference: Boolean,
    val dereferenceArgs: Boolean,
    val keepDirectorySymlink: Boolean,
    val backup: String?,
    val backupSimple: Boolean,
    val suffix: String?,
    val preserve: List<String>?,
    val noPreserve: List<String>?,
    val preserveDefaults: Boolean,
    val sparse: String?,
    val reflink: String?,
    val selinuxDefault: Boolean,
    val context: String?,
    val targetDirectory: String?,
    val noTargetDirectory: Boolean,
    val sources: List<String>,
    val dest: String?,
)

/** `cp` with no arguments at all: every field at the default the declaration gives it. */
public val NOTHING_BOUND: CpInputs = CpInputs(
    archive = false,
    recursive = false,
    recursiveLowercase = false,
    copyContents = false,
    noDereferencePreserveLinks = false,
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
    updateOlder = false,
    dereference = false,
    dereferenceArgs = false,
    keepDirectorySymlink = false,
    backup = null,
    backupSimple = false,
    suffix = null,
    preserve = null,
    noPreserve = null,
    preserveDefaults = false,
    sparse = null,
    reflink = null,
    selinuxDefault = false,
    context = null,
    targetDirectory = null,
    noTargetDirectory = false,
    sources = emptyList(),
    dest = null,
)
