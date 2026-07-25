package com.fromwau.klap.fixture.tar

import com.fromwau.klap.Ok
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * The command-line surface of GNU `tar`, as a klap tree:
 *
 * ```
 * tar -c|-x|-t [-v] [-z|-j] -f ARCHIVE [--exclude PATTERN]... [FILE...]
 * ```
 *
 * Bodies are stubs on purpose; only the parsing surface is under study. Three things this shape asks of a
 * parser: two exclusivity rules (`requireExactlyOne`, `requireAtMostOne`), bundled clusters whose last
 * char takes a value (`-cvf out.tar`), and an optional trailing operand list.
 */
public fun tarCli(): TypedCli<TarInputs> = cliOf("tar") {
    description = "Manipulate tape archives"
    version = "1.35"
    epilogue = "The mode flags -c, -x and -t are mutually exclusive; exactly one must be given."

    // In a cluster only the LAST character may take a value, so `-cvf out.tar` binds `f`'s value and
    // `-f -` reads the lone dash as that value rather than as another option.
    example("tar -cvf backup.tar src docs", "create backup.tar from two directories")
    example("tar -tf backup.tar", "list every member of an archive")
    example("tar -tzf backup.tar.gz", "list a gzip-compressed archive")
    example("tar -xf backup.tar --exclude '*.log' --exclude '*.tmp'", "extract, skipping two glob patterns")
    example("tar -cf - src | ssh host 'cat > backup.tar'", "write the archive to stdout")

    val create = flag("--create", "-c", help = "create a new archive")
    val extract = flag("--extract", "-x", help = "extract files from an archive")
    val listContents = flag("--list", "-t", help = "list the contents of an archive")

    requireExactlyOne(create, extract, listContents)

    // GNU tar's -v really is cumulative (-vv lists more per member), so .count() is the faithful shape and
    // it clusters exactly like a boolean flag does (`-cvvf out.tar` counts 2).
    val verbose = flag("--verbose", "-v", help = "list files processed; repeat for more detail").count()

    val gzip = flag("--gzip", "-z", help = "filter the archive through gzip")
    val bzip2 = flag("--bzip2", "-j", help = "filter the archive through bzip2")

    requireAtMostOne(gzip, bzip2)

    // Real tar falls back to $TAPE/stdin when -f is absent; the studied surface declares it mandatory.
    val archive = option("--file", "-f", help = "use archive file ARCHIVE ('-' for stdin/stdout)")
        .file()
        .required()

    val exclude = option("--exclude", help = "exclude files matching PATTERN").multiple()

    // tar's trailing operands are zero-or-more, so `tar -tf a.tar` and `tar -xf a.tar` — the tool's two
    // most common invocations, both naming no FILE — bind an empty list rather than failing.
    val files = argument("file", "file or archive member to operate on")
        .file()
        .multiple()

    action {
        // requireExactlyOne guarantees one mode holds, and requireAtMostOne that the two compression
        // flags never both do, so neither `when` needs a conflict branch.
        val mode = when {
            create() -> "-c"
            extract() -> "-x"
            else -> "-t"
        }
        val compression = when {
            gzip() -> "gzip"
            bzip2() -> "bzip2"
            else -> "none"
        }
        Ok(
            "would $mode ${archive()} " +
                "(compression=$compression, verbosity=${verbose()}, " +
                "excludes=${exclude().size}, operands=${files().size})",
        )
    }

    projection {
        TarInputs(create(), extract(), listContents(), verbose(), gzip(), bzip2(), archive(), exclude(), files())
    }
}

/**
 * What one `tar` line binds, as values rather than as the handles that read them.
 *
 * Being a plain data class is the point: a parity case asserts a whole invocation with one `assertEquals`
 * against a `copy()` of [NOTHING_BOUND], so every field it does *not* name is pinned to its default too.
 */
public data class TarInputs(
    val create: Boolean,
    val extract: Boolean,
    val listContents: Boolean,
    val verbose: Int,
    val gzip: Boolean,
    val bzip2: Boolean,
    val archive: String,
    val exclude: List<String>,
    val files: List<String>,
)

/**
 * Every optional field at the default its declaration gives it; `archive` has no default of its own — real
 * tar requires `--file` on every line — so every real case's `.copy()` supplies it.
 */
public val NOTHING_BOUND: TarInputs = TarInputs(
    create = false,
    extract = false,
    listContents = false,
    verbose = 0,
    gzip = false,
    bzip2 = false,
    archive = "",
    exclude = emptyList(),
    files = emptyList(),
)
