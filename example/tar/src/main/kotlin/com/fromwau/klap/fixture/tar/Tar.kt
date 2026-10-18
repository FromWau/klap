package com.fromwau.klap.fixture.tar

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.TypedCli
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.io.path
import com.fromwau.klap.projection

/**
 * The command-line surface of GNU `tar`, as a klap tree:
 *
 * ```
 * tar -c|-x|-t [-v] [-z|-j] -f ARCHIVE [--exclude PATTERN]... [FILE...]
 * ```
 *
 * Bodies are stubs on purpose; only the parsing surface is under study. Three things this shape asks of a
 * parser: two exclusivity rules (`exactlyOne`, `atMostOne`), bundled clusters whose last char takes a value
 * (`-cvf out.tar`), and an optional trailing operand list.
 */
public fun tarCli(): TypedCli<TarInputs> = cliOf("tar") {
    // GNU tar takes any unambiguous prefix of a long option: `tar --cr --file a.tar` is --create.
    abbreviation = Abbreviation.Options
    help = "Manipulate tape archives"
    version = Version.Of("1.35", text = TAR_VERSION_TEXT)
    epilogue = "The mode flags -c, -x and -t are mutually exclusive; exactly one must be given."

    // In a cluster only the LAST character may take a value, so `-cvf out.tar` binds `f`'s value and
    // `-f -` reads the lone dash as that value rather than as another option.
    example("tar -cvf backup.tar src docs", "create backup.tar from two directories")
    example("tar -tf backup.tar", "list every member of an archive")
    example("tar -tzf backup.tar.gz", "list a gzip-compressed archive")
    example("tar -xf backup.tar --exclude '*.log' --exclude '*.tmp'", "extract, skipping two glob patterns")
    example("tar -cf - src | ssh host 'cat > backup.tar'", "write the archive to stdout")

    val create = flag(short = 'c', long = "create", help = "create a new archive")
    val extract = flag(short = 'x', long = "extract", help = "extract files from an archive")
    val listContents = flag(short = 't', long = "list", help = "list the contents of an archive")

    exactlyOne(create, extract, listContents)

    // GNU tar's -v really is cumulative (-vv lists more per member), so .count() is the faithful shape and
    // it clusters exactly like a boolean flag does (`-cvvf out.tar` counts 2).
    val verbose = flag(short = 'v', long = "verbose", help = "list files processed; repeat for more detail").count()

    val gzip = flag(short = 'z', long = "gzip", help = "filter the archive through gzip")
    val bzip2 = flag(short = 'j', long = "bzip2", help = "filter the archive through bzip2")

    atMostOne(gzip, bzip2)

    // Real tar falls back to $TAPE/stdin when -f is absent; the studied surface declares it mandatory.
    val archive = option(short = 'f', long = "file", help = "use archive file ARCHIVE ('-' for stdin/stdout)")
        .path()
        .required()

    val exclude = option(long = "exclude", help = "exclude files matching PATTERN").multiple()

    // tar's trailing operands are zero-or-more, so `tar -tf a.tar` and `tar -xf a.tar` — the tool's two
    // most common invocations, both naming no FILE — bind an empty list rather than failing.
    val files = argument("file", "file or archive member to operate on")
        .path()
        .multiple()

    action {
        // exactlyOne guarantees one mode holds, and atMostOne that the two compression flags never both do,
        // so neither `when` needs a conflict branch.
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
    val archive: CliPath,
    val exclude: List<String>,
    val files: List<CliPath>,
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
    archive = CliPath(""),
    exclude = emptyList(),
    files = emptyList(),
)

/**
 * What `tar --version` prints for GNU tar 1.35, the version installed when it was pinned
 * (2026-10-10).
 */
internal val TAR_VERSION_TEXT: String = """tar (GNU tar) 1.35
Copyright (C) 2023 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by John Gilmore and Jay Fenlason."""
