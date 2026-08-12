package com.fromwau.klap.fixture.rm

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.11 `rm`, reproduced as a command-line surface only.
 *
 * Real synopsis: `rm [OPTION]... [FILE]...`, i.e.
 * `rm [-f] [-i] [-I] [-r|-R] [-d] [-v] [--] FILE...`
 *
 * Every action body is a stub; only the token-level shape is under study.
 */
public fun rmCli(): TypedCli<RmInputs> = cliOf("rm") {
    // GNU rm takes any unambiguous prefix of a long option or a choice value: `rm --recur d` is --recursive.
    abbreviation = Abbreviation.Options
    description = "Remove (unlink) the FILE(s)"
    version = "9.11"
    epilogue = "By default, rm does not remove directories. Use --recursive (-r or -R) to remove each " +
            "listed directory too, along with all of its contents. To remove a file whose name starts " +
            "with a '-', for example '-foo', use 'rm -- -foo' or 'rm ./-foo'."

    example("rm -rf build", "remove a directory tree without prompting")
    example("rm -- -foo", "remove a file whose name starts with a dash")

    val force = flag("--force", "-f", help = "ignore nonexistent files and arguments, never prompt")

    val interactiveShort = flag("-i", help = "prompt before every removal")

    // Real rm's `-i` never takes an attached value — only the long spelling does (verified: `rm -inever f`
    // fails "invalid option -- 'n'", while `rm -iv` and `rm -irf` both succeed, reading the rest of the
    // cluster as separate shorts). `-i` is declared short-only above and `--interactive` long-only with
    // its own optional value, mirroring the `-u`/`--update` split in Mv.kt for the same reason. Real rm
    // also resolves `-i` and `--interactive` by ORDER (`rm -i --interactive=never f` does not prompt, but
    // `rm --interactive=never -i f` does) — klap cannot express that either, since the two spellings are
    // separate holders with no order between them, so the action below ORs them as the closest expressible
    // reading.
    val interactive = option(
        "--interactive",
        help = "prompt according to WHEN: never, once (-I), or always (-i); without WHEN, prompt always",
    )
        .choice("never", "once", "always")
        .optionalValue("always")

    // The invented long name `--interactive-once` is a fixture choice pinned by
    // RmParityTest's `known divergence from real rm`, not a klap limitation — real `-I` has no long form at all.
    val interactiveOnce = flag(
        "--interactive-once", "-I",
        help = "prompt once before removing more than three files, or when removing recursively",
    )

    val recursive = flag("--recursive", "-r", help = "remove directories and their contents recursively")
    val recursiveUpper = flag("-R", help = "same as --recursive")

    val dir = flag("--dir", "-d", help = "remove empty directories")
    val verbose = flag("--verbose", "-v", help = "explain what is being done")
    val oneFileSystem = flag(
        "--one-file-system",
        help = "when removing a hierarchy recursively, skip any directory on a different file system",
    )

    // KLAP-GAP: rm's `--preserve-root[=all]` plus `--no-preserve-root` needs BOTH `.negatable()` and
    // `.optionalValue()` on one holder, but the first lives on `Flag` and the second on `Opt<T>`.
    // Keeping `.negatable()` matches `--no-preserve-root` exactly and drops the `=all` spelling.
    val preserveRoot = flag("--preserve-root", help = "do not remove '/' (default)").negatable(default = true)

    // `.requiredUnless(inputs.force)` drops the declared minimum of 1 to 0 exactly when -f fired, so bare
    // `rm` still fails to parse (real rm: "missing operand", exit 1; klap reports the same absence as a
    // usage error at exit 2 instead, see RmParityTest) while `rm -f` parses an empty list and reaches the
    // action. `--help` and completion see the real conditional rule too, not an unconditionally optional
    // list.
    //
    // KLAP-GAP: rm's operands are arbitrary filenames, so a file named `__complete` is unreachable as the
    // first operand — the hidden `__complete` subcommand claims it first; `rm -- <name>` is the escape.
    val files = argument("file", "the files to remove").file().multiple(min = 1).requiredUnless(force)

    action<String>(human = { it }) {
        val paths = files()
        // Reachable only when -f relaxed the minimum to 0 (the parse itself rejects an empty list
        // otherwise), so an empty list here always means "forced, nothing to report".
        if (paths.isEmpty()) return@action Ok("")

        // Reading every holder so nothing is declared-but-unread; the body is deliberately dry.
        val recursively = recursive() || recursiveUpper()
        val prompt = interactiveShort() ||
            (interactive() ?: "never") != "never" ||
            interactiveOnce()
        val summary = buildList {
            add("would remove ${paths.size} path(s)")
            if (recursively) add("recursively")
            if (dir()) add("including empty directories")
            if (force()) add("forced")
            if (prompt) add("after prompting")
            if (oneFileSystem()) add("staying on one file system")
            if (!preserveRoot()) add("without protecting '/'")
            if (verbose()) add("verbosely")
        }
        Ok(summary.joinToString(", "))
    }

    projection {
        RmInputs(
            force(),
            interactiveShort(),
            interactive(),
            interactiveOnce(),
            recursive(),
            recursiveUpper(),
            dir(),
            verbose(),
            oneFileSystem(),
            preserveRoot(),
            files(),
        )
    }
}

/**
 * What one `rm` line binds, as values rather than as the handles that read them.
 *
 * Being a plain data class is the point: a parity case asserts a whole invocation with one `assertEquals`
 * against a `copy()` of [NOTHING_BOUND], so every field it does *not* name is pinned to its default too.
 */
public data class RmInputs(
    val force: Boolean,
    val interactiveShort: Boolean,
    val interactive: String?,
    val interactiveOnce: Boolean,
    val recursive: Boolean,
    val recursiveUpper: Boolean,
    val dir: Boolean,
    val verbose: Boolean,
    val oneFileSystem: Boolean,
    val preserveRoot: Boolean,
    val files: List<String>,
)

/** `rm` with no arguments at all: every field at the default the declaration gives it. */
public val NOTHING_BOUND: RmInputs = RmInputs(
    force = false,
    interactiveShort = false,
    interactive = null,
    interactiveOnce = false,
    recursive = false,
    recursiveUpper = false,
    dir = false,
    verbose = false,
    oneFileSystem = false,
    preserveRoot = true,
    files = emptyList(),
)
