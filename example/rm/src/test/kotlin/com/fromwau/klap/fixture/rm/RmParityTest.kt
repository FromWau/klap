package com.fromwau.klap.fixture.rm

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class RmParityTest {

    private val parity = ParitySuite(rmCli())

    @Test
    fun bindsFlagsAndTheOptionalOperandList() {
        parity.binds(
            "-rf", "build",
            expected = NOTHING_BOUND.copy(recursive = true, force = true, files = listOf("build")),
        )
        // `-R` is short-only, so this is the whole spelling real rm offers for it.
        parity.binds("-R", "d", expected = NOTHING_BOUND.copy(recursiveUpper = true, files = listOf("d")))
        parity.binds("--recursive", "d", expected = NOTHING_BOUND.copy(recursive = true, files = listOf("d")))
        parity.binds(
            "-i", "a", "b",
            expected = NOTHING_BOUND.copy(interactiveShort = true, files = listOf("a", "b")),
        )
        // -i and -f are both flags, so the cluster works exactly as real rm's own does.
        parity.binds(
            "-if", "a",
            expected = NOTHING_BOUND.copy(interactiveShort = true, force = true, files = listOf("a")),
        )
        parity.binds("-I", "a", expected = NOTHING_BOUND.copy(interactiveOnce = true, files = listOf("a")))
        // All three WHEN spellings bind through `--interactive`, on top of `-i`'s own flag form pinned
        // above.
        parity.binds(
            "--interactive=never", "a",
            expected = NOTHING_BOUND.copy(interactive = "never", files = listOf("a")),
        )
        parity.binds(
            "--interactive=once", "a", "b",
            expected = NOTHING_BOUND.copy(interactive = "once", files = listOf("a", "b")),
        )
        parity.binds(
            "--interactive=always", "a",
            expected = NOTHING_BOUND.copy(interactive = "always", files = listOf("a")),
        )
        parity.binds("-dv", "empty", expected = NOTHING_BOUND.copy(dir = true, verbose = true, files = listOf("empty")))
        parity.binds(
            "--one-file-system", "-r", "d",
            expected = NOTHING_BOUND.copy(oneFileSystem = true, recursive = true, files = listOf("d")),
        )
        parity.binds(
            "--no-preserve-root", "-rf", "/",
            expected = NOTHING_BOUND.copy(preserveRoot = false, recursive = true, force = true, files = listOf("/")),
        )
        // The escape rm's own epilogue documents for a dash-led filename.
        parity.binds("--", "-foo", expected = NOTHING_BOUND.copy(files = listOf("-foo")))
        // Real rm needs an operand unless -f is given, and .requiredUnless() expresses that rule at parse
        // time: `rm -f` binds an empty list, and bare `rm` never reaches the action at all (see
        // rejectsWhatRealRmRejects for the bare-`rm` line).
        parity.binds("-f", expected = NOTHING_BOUND.copy(force = true))
        // `--recur` reaches `--recursive` and no other spelling, as it does for real rm.
        parity.binds("--recur", "d", expected = NOTHING_BOUND.copy(recursive = true, files = listOf("d")))
        // real rm: `--interactive=n` removes without prompting, the only WHEN value starting with "n".
        parity.binds(
            "--interactive=n", "a",
            expected = NOTHING_BOUND.copy(interactive = "never", files = listOf("a")),
        )
    }

    @Test
    fun rejectsWhatRealRmRejects() {
        parity.rejects("--zzz", because = "real rm: unrecognized option '--zzz'")
        // `-R` is short-only; `--R` is not a spelling either tool has.
        parity.rejects("--R", "d", because = "real rm: unrecognized option '--R'")
        parity.rejects("-z", "f", because = "real rm: invalid option -- 'z'")
        parity.rejects("--force=yes", "f", because = "real rm: option '--force' doesn't allow an argument")
        // Bare `rm` fails to parse, matching real rm's own refusal. The exit code still diverges (real rm:
        // 1; klap's usage error: USAGE_ERROR_EXIT/2), which ParitySuite never compares.
        parity.rejects(because = "real rm: 'missing operand', exit 1; klap reports the same absence as a usage error at exit 2")
    }

    @Test
    fun knownDivergenceFromRealRm() {
        // `-I`'s long name is invented: real rm has no `--interactive-once` at all.
        parity.bindsLoosely(
            "--interactive-once", "a",
            because = "real rm: unrecognized option '--interactive-once'",
            expected = NOTHING_BOUND.copy(interactiveOnce = true, files = listOf("a")),
        )

        // klap cannot combine `.negatable()` with `.optionalValue()` on one holder, so `--preserve-root`
        // keeps negation and drops the `=all` spelling (see the KLAP-GAP note beside its declaration).
        parity.rejects(
            "--preserve-root=all", "-r", "d",
            because = "klap gap: --preserve-root stays negatable for --no-preserve-root, NOT real-rm behaviour",
        )
    }
}
