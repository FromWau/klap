package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class ChmodParityTest {

    private val parity = ParitySuite(chmodCli())

    @Test
    fun bindsModeFlagsAndOperands() {
        parity.binds(
            "755", "script.sh",
            expected = NOTHING_BOUND.copy(mode = octal("755"), files = listOf("script.sh")),
        )
        parity.binds(
            "u+x,go-w", "-R", "src",
            expected = NOTHING_BOUND.copy(
                mode = ChmodMode.Symbolic(listOf("u+x", "go-w")),
                recursive = true,
                files = listOf("src"),
            ),
        )
        // The escape the other half needs, and the one chmod's own help documents.
        parity.binds(
            "--", "-w", "notes.txt",
            expected = NOTHING_BOUND.copy(mode = ChmodMode.Symbolic(listOf("-w")), files = listOf("notes.txt")),
        )
        parity.binds(
            "-cfv", "644", "a", "b",
            expected = NOTHING_BOUND.copy(
                changes = true,
                silent = true,
                verbose = true,
                mode = octal("644"),
                files = listOf("a", "b"),
            ),
        )
        parity.binds(
            "--no-dereference", "600", "link",
            expected = NOTHING_BOUND.copy(dereference = false, mode = octal("600"), files = listOf("link")),
        )
        parity.binds(
            "--reference=rfile", "700", "a",
            expected = NOTHING_BOUND.copy(reference = "rfile", files = listOf("700", "a")),
        )
        // `--reference=RFILE` REPLACES the MODE operand entirely, so notes.txt binds as a FILE, not as a
        // mode the converter would reject.
        parity.binds(
            "--reference=r", "notes.txt",
            expected = NOTHING_BOUND.copy(reference = "r", files = listOf("notes.txt")),
        )
        // Real chmod lets only the FINAL traversal flag take effect, and `lastWins` is that rule, so
        // these two lines bind differently, matching how real chmod behaves.
        parity.binds(
            "-R", "-H", "-L", "700", "d",
            expected = NOTHING_BOUND.copy(recursive = true, traverseAllLinks = true, mode = octal("700"), files = listOf("d")),
        )
        parity.binds(
            "-R", "-L", "-H", "700", "d",
            expected = NOTHING_BOUND.copy(recursive = true, traverseArgLinks = true, mode = octal("700"), files = listOf("d")),
        )
        // `--recu` reaches `--recursive` and no other spelling, as it does for real chmod.
        parity.binds(
            "--recu", "700", "d",
            expected = NOTHING_BOUND.copy(recursive = true, mode = octal("700"), files = listOf("d")),
        )
    }

    @Test
    fun rejectsWhatRealChmodRejects() {
        parity.rejects("--zzz", because = "real chmod: unrecognized option '--zzz'")
        parity.rejects(because = "real chmod: missing operand")
        parity.rejects("755", because = "real chmod: missing operand after '755'")
        parity.rejects("xyz", "f", because = "real chmod: invalid mode: 'xyz'")
        parity.rejects("-Q", "700", "d", because = "real chmod: invalid option -- 'Q'")
        parity.rejects("--reference", because = "real chmod: option '--reference' requires an argument")
        parity.rejects(
            "--re", "700", "d",
            because = "real chmod: option '--re' is ambiguous; possibilities: '--recursive' '--reference'",
        )
    }

    @Test
    fun knownDivergenceFromRealChmod() {
        // A dash-led MODE is a positional to real chmod, but klap reads any dash-led token as an option,
        // so it is never reached here; no general rule can serve both chmod and mkdir (`chmod -w f` wants
        // an operand exactly where `mkdir -w f` wants an error). `chmod -- -w f` is the escape, and it is
        // POSIX's own.
        parity.rejects("-w", "notes.txt", because = "permanent klap non-goal: dash-led operand; use `chmod -- -w f`")
        parity.rejects("-rwx", "notes.txt", because = "permanent klap non-goal: dash-led operand; use `chmod -- -rwx f`")
        parity.rejects("-R", "-w", "d", because = "permanent klap non-goal: dash-led operand; use `chmod -R -- -w d`")
        parity.rejects("-755", "f", because = "permanent klap non-goal: dash-led operand; use `chmod -- -755 f`")
    }

    private fun octal(digits: String) = ChmodMode.Octal(digits.toInt(radix = 8))
}
