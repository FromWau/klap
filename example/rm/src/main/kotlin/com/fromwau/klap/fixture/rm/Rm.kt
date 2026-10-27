package com.fromwau.klap.fixture.rm

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.Help
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.io.path
import com.fromwau.klap.OptionalValue
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * GNU coreutils 9.12 `rm`, reproduced as a command-line surface only. The action never removes anything.
 *
 * Real usage: `rm [OPTION]... [FILE]...`
 * ```
 * -f, --force               ignore nonexistent files and arguments, never prompt
 * -i                        prompt before every removal
 * -I                        prompt once before removing more than three files, or when removing recursively;
 *                           less intrusive than -i, while still giving protection against most mistakes
 *     --interactive[=WHEN]  prompt according to WHEN: never, once (-I), or always (-i); without WHEN, prompt always
 *     --one-file-system     when removing a hierarchy recursively, skip any directory that is on a file system
 *                           different from that of the corresponding command line argument
 *     --no-preserve-root    do not treat '/' specially
 *     --preserve-root[=all] do not remove '/' (default); with 'all', reject any command line argument on a
 *                           separate device from its parent
 * -r, -R, --recursive       remove directories and their contents recursively
 * -d, --dir                 remove empty directories
 * -v, --verbose             explain what is being done
 *     --help                display this help and exit
 *     --version             output version information and exit
 * ```
 */
public fun rmCli(): TypedCli<RmInputs> = cliOf("rm") {
    // GNU rm takes any unambiguous prefix of a long option: `rm --recur d` is --recursive.
    abbreviation = Abbreviation.Options
    // GNU rm has none of klap's built-ins but --help, which has no short (`rm -h` is "invalid option -- 'h'").
    builtins {
        help = Help.ByCommand(description = "display this help and exit")
        json = Builtin.Off
        color = Builtin.Off
        completion = Builtin.Off
        docs = Builtin.Off
    }
    help = "Remove (unlink) the FILE(s)"
    version = Version.Of("9.12", description = "output version information and exit", text = RM_VERSION_TEXT)
    epilogue = "By default, rm does not remove directories. Use --recursive (-r or -R) to remove each " +
            "listed directory too, along with all of its contents. To remove a file whose name starts " +
            "with a '-', for example '-foo', use 'rm -- -foo' or 'rm ./-foo'."

    example("rm -rf build", "remove a directory tree without prompting")
    example("rm -- -foo", "remove a file whose name starts with a dash")

    val force = flag(short = 'f', long = "force", help = "ignore nonexistent files and arguments, never prompt")
    val interactiveShort = flag(short = 'i', help = "prompt before every removal")
    val interactiveOnce = flag(
        short = 'I',
        help = "prompt once before removing more than three files, or when removing recursively; less intrusive " +
            "than -i, while still giving protection against most mistakes",
    )
    val interactive = option(
        long = "interactive",
        help = "prompt according to WHEN: never, once (-I), or always (-i); without WHEN, prompt always",
    ).enum<Interactive>(spellings = { it.words }).placeholder("WHEN").optionalValue()

    // The last of the four sets the prompt mode: `rm -f -i a` prompts and `rm -if a` does not.
    lastWins(force, interactiveShort, interactiveOnce, interactive)

    val oneFileSystem = flag(
        long = "one-file-system",
        help = "when removing a hierarchy recursively, skip any directory that is on a file system different from " +
            "that of the corresponding command line argument",
    )
    // rm refuses any abbreviation of this one long: "you may not abbreviate the --no-preserve-root option".
    val noPreserveRoot = flag(long = "no-preserve-root", help = "do not treat '/' specially").spelledInFull()
    val preserveRoot = option(
        long = "preserve-root",
        help = "do not remove '/' (default); with 'all', reject any command line argument on a separate device " +
            "from its parent",
    ).enum<RootScope>(abbreviates = false).optionalValue()

    lastWins(preserveRoot, noPreserveRoot)

    val recursive = flag(
        short = listOf('r', 'R'),
        long = "recursive",
        help = "remove directories and their contents recursively",
    )
    val dir = flag(short = 'd', long = "dir", help = "remove empty directories")
    val verbose = flag(short = 'v', long = "verbose", help = "explain what is being done")

    // Bare `rm` is "missing operand", `rm -f` exits 0, and `rm -f -i` needs an operand again.
    val files = argument("file", "the files to remove").path().multiple(min = 1).optionalWhen(force)

    fun ValueScope.preserveRootScope(): PreserveRoot = when {
        noPreserveRoot() -> PreserveRoot.Off
        preserveRoot() == OptionalValue.Given(RootScope.All) -> PreserveRoot.All
        else -> PreserveRoot.Root
    }

    // rm's option loop sets the prompt mode and the reading of missing files one option at a time.
    fun ValueScope.replayPrompt(): PromptReplay {
        val replay = PromptReplay()
        for ((input, value) in inOrder(force, interactiveShort, interactiveOnce, interactive)) {
            when (input) {
                force -> replay.force()
                interactiveShort -> replay.prompt(Prompt.Always)
                interactiveOnce -> replay.prompt(Prompt.Once)
                interactive -> replay.prompt(promptOf(value))
            }
        }
        return replay
    }

    val read: ValueScope.() -> RmInputs = {
        val replay = replayPrompt()
        RmInputs(
            prompt = replay.mode,
            ignoreMissing = replay.ignoreMissing,
            recursive = recursive(),
            dir = dir(),
            verbose = verbose(),
            oneFileSystem = oneFileSystem(),
            preserveRoot = preserveRootScope(),
            files = files(),
        )
    }

    action<String>(human = { it }) {
        val bound = read()
        Ok("would remove ${bound.files.size} path(s) [$bound]")
    }

    projection(read)
}

/** rm's `--interactive` modes, each named by any of its [words]: "'never', 'no', 'none' / 'once' / 'always', 'yes'". */
public enum class Interactive(public val words: List<String>) {
    Never(listOf("never", "no", "none")),
    Once(listOf("once")),
    Always(listOf("always", "yes")),
}

/** The one value `--preserve-root` takes: `rm --help` writes it `--preserve-root[=all]`. */
public enum class RootScope {
    All,
}

/** When rm prompts before a removal, as its option loop leaves it once every option is read. */
public enum class Prompt {
    /** No prompt option given: rm asks only before removing a write-protected file. */
    WriteProtected,

    /** `-f` or `--interactive=never`. */
    Never,

    /** `-I` or `--interactive=once`: one question before removing more than three files or recursing. */
    Once,

    /** `-i`, a bare `--interactive` or `--interactive=always`. */
    Always,
}

/** What rm refuses to remove: `rm --help` reads "do not remove '/' (default)". */
public enum class PreserveRoot {
    /** `--no-preserve-root`: "do not treat '/' specially". */
    Off,

    /** `/` itself, rm's default and a bare `--preserve-root`. */
    Root,

    /** `--preserve-root=all`: `/`, and any operand on another device than its parent. */
    All,
}

/**
 * What one `rm` line binds, as rm reads it rather than as the inputs that spell it: `-f`, `-i`, `-I` and
 * `--interactive` are one [prompt] with its [ignoreMissing], and `-r` and `-R` are one [recursive].
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does not name
 * is pinned to its default too.
 */
public data class RmInputs(
    val prompt: Prompt,
    /** Whether a missing file is no error: set by `-f`, cleared by a later `-i`, `-I`, `=once` or `=always`. */
    val ignoreMissing: Boolean,
    val recursive: Boolean,
    val dir: Boolean,
    val verbose: Boolean,
    val oneFileSystem: Boolean,
    val preserveRoot: PreserveRoot,
    val files: List<CliPath>,
)

/** `rm` with no arguments at all: every field at the default rm gives it. */
public val NOTHING_BOUND: RmInputs = RmInputs(
    prompt = Prompt.WriteProtected,
    ignoreMissing = false,
    recursive = false,
    dir = false,
    verbose = false,
    oneFileSystem = false,
    preserveRoot = PreserveRoot.Root,
    files = emptyList(),
)

/** The prompt mode and the reading of missing files, as rm's option loop assigns them. */
private class PromptReplay {
    var mode: Prompt = Prompt.WriteProtected
        private set
    var ignoreMissing: Boolean = false
        private set

    fun force() {
        mode = Prompt.Never
        ignoreMissing = true
    }

    /** `--interactive=never` keeps an earlier `-f`'s [ignoreMissing]; every other prompt option clears it. */
    fun prompt(next: Prompt) {
        mode = next
        if (next != Prompt.Never) ignoreMissing = false
    }
}

/** The prompt one `--interactive` occurrence sets: a bare one is `always`, as `rm --help` says. */
private fun promptOf(occurrence: Any?): Prompt {
    val mode = if (occurrence is OptionalValue.Given<*>) occurrence.value as Interactive else Interactive.Always
    return when (mode) {
        Interactive.Never -> Prompt.Never
        Interactive.Once -> Prompt.Once
        Interactive.Always -> Prompt.Always
    }
}

/**
 * What `rm --version` prints for GNU coreutils 9.12, the version installed when it was pinned
 * (2026-10-10).
 */
internal val RM_VERSION_TEXT: String = """rm (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Paul Rubin, David MacKenzie, Richard M. Stallman,
and Jim Meyering."""
