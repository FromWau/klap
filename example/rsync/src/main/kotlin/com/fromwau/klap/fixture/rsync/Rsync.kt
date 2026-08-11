package com.fromwau.klap.fixture.rsync

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.ConversionError
import com.fromwau.klap.CountFlag
import com.fromwau.klap.Flag
import com.fromwau.klap.Opt
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * `rsync` 3.4.4, reproduced as a command-line surface only. Every action body is a stub: nothing is
 * transferred, nothing is deleted.
 *
 * Real usage:
 *   rsync [OPTION]... SRC [SRC]... DEST
 *   rsync [OPTION]... SRC [SRC]... [USER@]HOST:DEST
 *   rsync [OPTION]... [USER@]HOST:SRC [DEST]
 *
 * The assigned subset is `-a -r -v -q -z -n --delete --exclude --include -e --bwlimit --port
 * --partial/--no-partial --progress -h --help --version`. Every line below was checked against the
 * installed 3.4.4 binary before it was encoded; the notable results are recorded at each declaration.
 *
 * Exit status: rsync answers every usage error with 1, not 2, so the suite never compares exit codes
 * with it (see ParitySuite's own note).
 *
 * Every handle declared inside a `group { }` below is hoisted as a pre-declared `val` with its converted
 * type written out, then assigned inside the block — legal because `group(title) { }` is generic and its
 * contract guarantees exactly one call (CommandBuilder.kt). That is the price of giving a tool this
 * wide (sixteen handles) sectioned `--help` output instead of one long wall of rows.
 */
public fun rsyncCli(): TypedCli<RsyncInputs> = cliOf("rsync") {
    // rsync matches long options exactly: `--compr` and `--dele` both answer "unknown option" (verified).
    abbreviation = Abbreviation.None
    description = "A fast, versatile, remote (and local) file-copying tool."
    version = "3.4.4"
    epilogue = "The ':' usages connect via remote shell, while '::' & 'rsync://' usages connect to an " +
        "rsync daemon, and require SRC or DEST to start with a module name."

    // Real rsync spells human-readable `-h` and reaches help by `-h` only when it is the WHOLE command
    // line ("--help, -h (*)  show this help (* -h is help only on its own)", verified: `rsync -h` prints
    // help and exits 0, `rsync -vh src/ dst/` and `rsync -hh --dry-run src/ dst/` both run). Declining the
    // built-in short is what frees the character; the "on its own" half has no home and is a gap below.
    builtins { helpShort = false }

    example("rsync -avz src/ backup/", "the usual archive-mode local copy")
    example("rsync -e \"ssh -p 2222\" src/ host:/dst", "a whole remote-shell command as one value")
    example("rsync --exclude '*.o' --exclude .git src/ backup/", "repeatable filters")

    val verbose: CountFlag
    val quiet: Flag
    val humanReadable: CountFlag
    val progress: Flag
    group("Output") {
        // Cumulative in real rsync (-vvv adds "send_file_list done" over -v, verified), so .count() is
        // the faithful shape, and it clusters: `-avz` binds all three.
        verbose = flag("--verbose", "-v", help = "increase verbosity").count()
        quiet = flag("--quiet", "-q", help = "suppress non-error messages")

        // KLAP-GAP: real rsync's generic --no-OPTION rule also accepts `--no-verbose`/`--no-human-readable`,
        // but `.count()` and `.negatable()` are mutually exclusive in klap, so the negative half is unreachable.
        humanReadable = flag("--human-readable", "-h", help = "output numbers in a human-readable format").count()

        // `--no-progress` IS accepted by real rsync (verified), so the pair is the faithful declaration.
        progress = flag("--progress", help = "show progress during transfer").negatable(default = false)
    }

    val archive: Flag
    val recursive: Flag
    val delete: Flag
    val exclude: Opt<List<String>>
    val include: Opt<List<String>>
    group("Selection") {
        archive = flag("--archive", "-a", help = "archive mode is -rlptgoD (no -A,-X,-U,-N,-H)")
        recursive = flag("--recursive", "-r", help = "recurse into directories")

        // Real rsync REJECTS `--no-delete` ("unknown option", verified) even though it accepts
        // `--no-partial` and `--no-progress`, so this one stays a plain flag rather than a negatable.
        delete = flag("--delete", help = "delete extraneous files from dest dirs")

        // Both repeat in real rsync, so .multiple() is the faithful cardinality.
        //
        // KLAP-GAP: real rsync builds one ordered filter list interleaving `--exclude`/`--include`, but klap
        // binds them as two separate ordered lists — the relative order between the two is not recoverable.
        exclude = option("--exclude", help = "exclude files matching PATTERN")
            .multiple()
            .placeholder("PATTERN")
        include = option("--include", help = "don't exclude files matching PATTERN")
            .multiple()
            .placeholder("PATTERN")
    }

    val compress: Flag
    val dryRun: Flag
    val partial: Flag
    val rsh: Opt<String?>
    group("Transfer") {
        compress = flag("--compress", "-z", help = "compress file data during the transfer")
        dryRun = flag("--dry-run", "-n", help = "perform a trial run with no changes made")
        partial = flag("--partial", help = "keep partially transferred files").negatable(default = false)

        // A whole command in one value. Nothing special is needed: klap takes the next token verbatim,
        // so `-e "ssh -p 2222"` arrives as one argv element and binds whole. Verified against real rsync,
        // which also accepts the attached-short form `-essh` and the in-cluster form `-ave ssh`.
        rsh = option("--rsh", "-e", help = "specify the remote shell to use").placeholder("COMMAND")
    }

    val port: Opt<Int?>
    val bwlimit: Opt<String?>
    group("Connection") {
        // Deliberately NOT .range(1..65535): real rsync accepts `--port=99999` and `--port=-1` without
        // complaint (both verified exit 0) and rejects only a non-numeric value ("invalid numeric value").
        // A range here would reject lines real rsync accepts.
        port = option("--port", help = "specify double-colon alternate port number")
            .int()
            .placeholder("PORT")

        // RATE is a size, not an int: `100`, `2.5`, `1.5m`, `10K`, `1kb` and the empty string all parse,
        // while `bogus`, `1x`, `-1` and `+1` are all "is invalid" (each verified). `.convert { }` rather
        // than `.map { }` so the message is rsync's own wording instead of a derived one.
        bwlimit = option("--bwlimit", help = "limit socket I/O bandwidth")
            .convert { raw ->
                if (RATE.matches(raw)) Ok(raw) else Err(ConversionError.Domain(RateInvalid, "is invalid"))
            }
            .placeholder("RATE")
    }

    // KLAP-GAP: real rsync's operand shape is `SRC... [DEST]` (one operand lists locally, two or more are
    // sources plus a destination), but that shape is not declarable as two arguments — a variadic `src`
    // followed by an optional `dest` fails at build time with:
    //   "argument 'dest' follows the variadic (multiple) argument 'src' and is not required, which is
    //    ambiguous: a single leftover token could feed either slot. Only required arguments may follow
    //    a variadic"
    // so one variadic argument is declared instead, and the SRC/DEST split happens in the action below.
    val paths = argument("path", "the source paths, and the destination when more than one is given")
        .placeholder("PATH")
        .file()
        .multiple(min = 1)

    action<String>(human = { it }) {
        val operands = paths()
        val sources = if (operands.size > 1) operands.dropLast(1) else operands
        val destination = if (operands.size > 1) operands.last() else null

        val notes = listOfNotNull(
            "archive".takeIf { archive() },
            "recursive".takeIf { recursive() },
            "compress".takeIf { compress() },
            "dry-run".takeIf { dryRun() },
            "delete".takeIf { delete() },
            "partial".takeIf { partial() },
            "progress".takeIf { progress() },
            "quiet".takeIf { quiet() },
            "verbose=${verbose()}".takeIf { verbose() > 0 },
            "human-readable=${humanReadable()}".takeIf { humanReadable() > 0 },
            rsh()?.let { "rsh=$it" },
            port()?.let { "port=$it" },
            bwlimit()?.let { "bwlimit=$it" },
            "${exclude().size} exclude(s)".takeIf { exclude().isNotEmpty() },
            "${include().size} include(s)".takeIf { include().isNotEmpty() },
        )
        val target = destination ?: "(listing only)"
        Ok("would sync ${sources.size} source(s) to $target [${notes.joinToString(", ")}]")
    }

    projection {
        RsyncInputs(
            verbose = verbose(),
            quiet = quiet(),
            humanReadable = humanReadable(),
            progress = progress(),
            archive = archive(),
            recursive = recursive(),
            delete = delete(),
            exclude = exclude(),
            include = include(),
            compress = compress(),
            dryRun = dryRun(),
            partial = partial(),
            rsh = rsh(),
            port = port(),
            bwlimit = bwlimit(),
            paths = paths(),
        )
    }
}

// rsync's RATE grammar: a decimal, an optional K/M/G/T/P scale, an optional trailing b/B, or empty.
private val RATE = Regex("""^$|^[0-9]+(\.[0-9]+)?[kKmMgGtTpP]?[bB]?$""")

/**
 * What one `rsync` line binds, as values rather than as the handles that read them.
 *
 * Being a plain data class is the point: a parity case asserts a whole invocation with one `assertEquals`
 * against a `copy()` of [NOTHING_BOUND], so every field it does *not* name is pinned to its default too.
 */
public data class RsyncInputs(
    val verbose: Int,
    val quiet: Boolean,
    val humanReadable: Int,
    val progress: Boolean,
    val archive: Boolean,
    val recursive: Boolean,
    val delete: Boolean,
    val exclude: List<String>,
    val include: List<String>,
    val compress: Boolean,
    val dryRun: Boolean,
    val partial: Boolean,
    val rsh: String?,
    val port: Int?,
    val bwlimit: String?,
    val paths: List<String>,
)

/** `rsync` with no options: every field at the default its declaration gives it. */
public val NOTHING_BOUND: RsyncInputs = RsyncInputs(
    verbose = 0,
    quiet = false,
    humanReadable = 0,
    progress = false,
    archive = false,
    recursive = false,
    delete = false,
    exclude = emptyList(),
    include = emptyList(),
    compress = false,
    dryRun = false,
    partial = false,
    rsh = null,
    port = null,
    bwlimit = null,
    paths = emptyList(),
)

/** `--bwlimit` was given something that is not an rsync size. */
private data object RateInvalid : IError
