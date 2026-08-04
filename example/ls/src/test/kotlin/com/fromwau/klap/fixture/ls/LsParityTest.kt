package com.fromwau.klap.fixture.ls

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class LsParityTest {

    private val parity = ParitySuite(lsCli())

    @Test
    fun bindsClustersFormatOptionsAndTheOptionalOperandList() {
        parity.binds("-la", expected = NOTHING_BOUND.copy(all = true, longFormat = true))
        parity.binds(
            "-lhS", "src",
            expected = NOTHING_BOUND.copy(
                longFormat = true,
                humanReadable = true,
                sortBySize = true,
                files = listOf("src"),
            ),
        )
        // `-h` is real ls's --human-readable only because `builtins { helpShort = false }` handed the
        // short back; with the built-in left on, this line would print klap's help instead.
        parity.binds("-h", expected = NOTHING_BOUND.copy(humanReadable = true))
        parity.binds("-A", "-d", expected = NOTHING_BOUND.copy(almostAll = true, directory = true))
        parity.binds(
            "--format=single-column", "f",
            expected = NOTHING_BOUND.copy(format = "single-column", files = listOf("f")),
        )
        // Real ls's only digit short; it clusters like any other.
        parity.binds("-1", "f", expected = NOTHING_BOUND.copy(singleColumn = true, files = listOf("f")))
        parity.binds(
            "-1l", "f",
            expected = NOTHING_BOUND.copy(singleColumn = true, longFormat = true, files = listOf("f")),
        )
        parity.binds("--sort=size", "f", expected = NOTHING_BOUND.copy(sort = "size", files = listOf("f")))
        // `--color` reaches ls's own option rather than klap's built-in rendering switch.
        parity.binds("--color=never", "f", expected = NOTHING_BOUND.copy(color = "never", files = listOf("f")))
        // Bare `--color` binds "always" exactly as real ls does, and the space form leaves `src` as a
        // file operand rather than swallowing it as WHEN.
        parity.binds("--color", expected = NOTHING_BOUND.copy(color = "always"))
        parity.binds(
            "--color", "src",
            expected = NOTHING_BOUND.copy(color = "always", files = listOf("src")),
        )
        // The space form never binds, matching GNU exactly — `auto` is read as a FILE, not as WHEN.
        parity.binds(
            "--color", "auto", "f",
            expected = NOTHING_BOUND.copy(color = "always", files = listOf("auto", "f")),
        )
        // `--classify` takes the same three values as `--color`, and the space form never binds here
        // either.
        parity.binds(
            "--classify=never", "f",
            expected = NOTHING_BOUND.copy(classify = "never", files = listOf("f")),
        )
        parity.binds("--classify", expected = NOTHING_BOUND.copy(classify = "always"))
        parity.binds(
            "--classify", "auto", "f",
            expected = NOTHING_BOUND.copy(classify = "always", files = listOf("auto", "f")),
        )
        parity.binds(
            "-R", "--ignore", "*.o", "src",
            expected = NOTHING_BOUND.copy(recursive = true, ignore = listOf("*.o"), files = listOf("src")),
        )
        parity.binds(
            "-I", "a", "-I", "b", "src",
            expected = NOTHING_BOUND.copy(ignore = listOf("a", "b"), files = listOf("src")),
        )
        parity.binds(
            "--hide=*.tmp", "src",
            expected = NOTHING_BOUND.copy(hide = listOf("*.tmp"), files = listOf("src")),
        )
        parity.binds("-w", "40", "f", expected = NOTHING_BOUND.copy(width = 40, files = listOf("f")))
        // 0 means "no limit" to real ls, so it must bind as a value rather than read as absent.
        parity.binds("-w0", expected = NOTHING_BOUND.copy(width = 0))
        parity.binds("-T", "4", "f", expected = NOTHING_BOUND.copy(tabSize = 4, files = listOf("f")))
        // -F and -Z are both flags, so cluster order between them carries no meaning.
        parity.binds(
            "-ZF", "f",
            expected = NOTHING_BOUND.copy(classifyShort = true, context = true, files = listOf("f")),
        )
        parity.binds(
            "-FZ", "f",
            expected = NOTHING_BOUND.copy(classifyShort = true, context = true, files = listOf("f")),
        )
        parity.binds("-Fla", expected = NOTHING_BOUND.copy(classifyShort = true, longFormat = true, all = true))
        // The LAST sort short wins, inside a cluster as well as across tokens.
        parity.binds("-S", "-t", "f", expected = NOTHING_BOUND.copy(sortByTime = true, files = listOf("f")))
        parity.binds("-tS", "f", expected = NOTHING_BOUND.copy(sortBySize = true, files = listOf("f")))
        // `--sort` is the same setting spelled long, so it takes part in the same override: real ls
        // orders the first of these by time and the second by size (verified, coreutils 9.11).
        parity.binds("-S", "--sort=time", "f", expected = NOTHING_BOUND.copy(sort = "time", files = listOf("f")))
        parity.binds("--sort=time", "-S", "f", expected = NOTHING_BOUND.copy(sortBySize = true, files = listOf("f")))
        parity.binds(
            "-mxrU", "src",
            expected = NOTHING_BOUND.copy(
                commaSeparated = true,
                byLines = true,
                reverse = true,
                unsorted = true,
                files = listOf("src"),
            ),
        )
        // Real ls lists the current directory with no operand, so the list is genuinely zero-or-more.
        parity.binds(expected = NOTHING_BOUND)
        parity.binds("--", "-foo", expected = NOTHING_BOUND.copy(files = listOf("-foo")))
        // `--rec` reaches `--recursive` and no other spelling, as it does for real ls.
        parity.binds("--rec", "f", expected = NOTHING_BOUND.copy(recursive = true, files = listOf("f")))
        // real ls: `--color=al` lists in colour, the only value starting with "al".
        parity.binds("--color=al", "f", expected = NOTHING_BOUND.copy(color = "always", files = listOf("f")))
    }

    @Test
    fun rejectsWhatRealLsRejects() {
        parity.rejects("--zzz", because = "real ls: unrecognized option '--zzz'")
        parity.rejects("--colour=never", because = "real ls: unrecognized option '--colour=never'")
        parity.rejects("-Q", "f", because = "real ls: invalid option -- 'Q'")
        // The mirror of `head -5`: ls declares one digit short and no numericAlias, so every OTHER digit
        // is an unknown option here exactly as it is in real ls.
        parity.rejects("-5", because = "real ls: invalid option -- '5'")
        parity.rejects("-100", because = "real ls: invalid option -- '0'")
        parity.rejects("-w", "abc", because = "real ls: invalid line width: 'abc'")
        parity.rejects("-T", "abc", because = "real ls: invalid tab size: 'abc'")
        parity.rejects("--sort=zzz", because = "real ls: invalid argument 'zzz' for '--sort'")
        parity.rejects("--format=zzz", because = "real ls: invalid argument 'zzz' for '--format'")
        parity.rejects("--hide", because = "real ls: option '--hide' requires an argument")
        parity.rejects("-F=never", "f", because = "real ls: invalid option -- '='")
        // real ls: 'n' matches both 'none' and 'name', so argmatch calls it ambiguous.
        parity.rejects("--sort=n", because = "real ls: ambiguous argument 'n' for '--sort'")
    }

    @Test
    fun knownDivergenceFromRealLs() {
        // klap's --json is position-independent and stripped before the walk, so a tool that never had
        // it accepts the token silently. Real ls: "unrecognized option '--json'".
        parity.bindsLoosely(
            "--json", "f",
            because = "real ls: unrecognized option '--json'",
            expected = NOTHING_BOUND.copy(files = listOf("f")),
        )

        // Built-ins ls never had. `--help` agrees with real ls by luck; these two do not, and `--docs`
        // could not be declined without also losing the generated documentation the fixture suite wants.
        parity.shortCircuits("__complete", because = "klap's hidden __complete subcommand shadows a filename")
        parity.shortCircuits("--docs", "markdown", because = "real ls: unrecognized option '--docs'")
    }
}
