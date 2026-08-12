package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.chmod.ChmodMode.OctalOp.ADD
import com.fromwau.klap.fixture.chmod.ChmodMode.OctalOp.ASSIGN
import com.fromwau.klap.fixture.chmod.ChmodMode.OctalOp.REMOVE
import kotlin.test.Test

class ChmodParityTest {

    private val parity = ParitySuite(chmodCli())

    @Test
    fun `binds mode flags and operands`() {
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
        // The POSIX escape works independently of `dashLed()`: a post-`--` operand never enters the
        // admitted set, so it binds in a marked and an unmarked slot alike.
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
    fun `rejects what real chmod rejects`() {
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
    fun `a leading dash mode binds the way real chmod binds it`() {
        // GNU chmod reads a dash-led MODE as the operand it is. The rule that serves both this and mkdir
        // is a per-argument opt-in: chmod marks its mode slot and mkdir does not.
        parity.binds(
            "-w", "notes.txt",
            expected = NOTHING_BOUND.copy(mode = ChmodMode.Symbolic(listOf("-w")), files = listOf("notes.txt")),
        )
        parity.binds(
            "-rwx", "notes.txt",
            expected = NOTHING_BOUND.copy(mode = ChmodMode.Symbolic(listOf("-rwx")), files = listOf("notes.txt")),
        )
        parity.binds(
            "-R", "-w", "d",
            expected = NOTHING_BOUND.copy(
                recursive = true,
                mode = ChmodMode.Symbolic(listOf("-w")),
                files = listOf("d"),
            ),
        )
        parity.binds(
            "-755", "f",
            expected = NOTHING_BOUND.copy(mode = octal("755", REMOVE), files = listOf("f")),
        )
    }

    @Test
    fun `an octal mode's operator is part of what it means`() {
        // Verified against GNU chmod 9.11: `-755` clears the bits `755` sets, and `+755` and `=755` differ
        // again, so the four spellings are four modes rather than one written four ways.
        parity.binds("755", "f", expected = NOTHING_BOUND.copy(mode = octal("755"), files = listOf("f")))
        parity.binds("+755", "f", expected = NOTHING_BOUND.copy(mode = octal("755", ADD), files = listOf("f")))
        parity.binds("=755", "f", expected = NOTHING_BOUND.copy(mode = octal("755", ASSIGN), files = listOf("f")))
    }

    private fun octal(digits: String, op: ChmodMode.OctalOp? = null) =
        ChmodMode.Octal(digits.toInt(radix = 8), op)
}
