package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.chmod.ChmodMode.OctalOp.ADD
import com.fromwau.klap.fixture.chmod.ChmodMode.OctalOp.ASSIGN
import com.fromwau.klap.fixture.chmod.ChmodMode.OctalOp.REMOVE
import kotlin.test.Test

/*
 * Every expected value is from GNU chmod 9.12, run inside a throwaway directory as `chmod MODE f` on a file f of mode
 * 644 (a mode word starting with a dash typed as it is, which chmod reads as the mode): an accepted mode is read off
 * the mode f was left with, a refusal is chmod's "invalid mode" before it touches f. `--reference` takes any word as
 * its file, which chmod only opens later, so every reference value binds.
 */

/** One mode word and what chmod reads it as, or null where chmod says "invalid mode". */
private class ModeCell(
    val word: String,
    val mode: ChmodMode?,
    val because: String,
)

private fun reads(
    word: String,
    mode: ChmodMode,
    because: String,
) = ModeCell(word, mode, because)

private fun invalid(word: String) = ModeCell(word, null, "chmod: invalid mode: '$word'")

private fun ParitySuite<ChmodInputs>.checkModes(cells: List<ModeCell>) = checkCells(cells) { cell ->
    val mode = cell.mode
    if (mode == null) {
        rejects(cell.word, "f", because = cell.because)
    } else {
        binds(cell.word, "f", expected = NOTHING_BOUND.copy(mode = mode, files = listOf("f")), because = cell.because)
    }
}

/** Octal modes as chmod reads them: up to 07777, with an operator in front or none. */
private val OCTALS: List<ModeCell> = listOf(
    reads("755", octal("755"), "f 755"),
    reads("0755", octal("755"), "f 755"),
    reads("7755", octal("7755"), "f 7755"),
    reads("7777", octal("7777"), "f 7777"),
    reads("0", octal("0"), "f 000"),
    reads("+755", octal("755", ADD), "f 755: 644 with 755 added"),
    reads("+110", octal("110", ADD), "f 754, the info manual's own example"),
    reads("+0755", octal("755", ADD), "f 755"),
    reads("=755", octal("755", ASSIGN), "f 755"),
    reads("=0", octal("0", ASSIGN), "f 000"),
    reads("-755", octal("755", REMOVE), "f 000"),
    reads("-022", octal("22", REMOVE), "f 644"),
    reads("-0", octal("0", REMOVE), "f 644"),
)

/** Symbolic modes as chmod reads them: comma-separated clauses of the help's `[ugoa]*([-+=]([rwxXst]*|[ugo]))+`. */
private val SYMBOLICS: List<ModeCell> = listOf(
    reads("u+x", symbolic("u+x"), "f 744"),
    reads("u+x,go-w", symbolic("u+x", "go-w"), "f 744"),
    reads("u=rwx,g=rx,o=", symbolic("u=rwx", "g=rx", "o="), "f 750"),
    reads("a=r,u+w", symbolic("a=r", "u+w"), "f 644, the info manual's own example"),
    reads("a=,+rwX", symbolic("a=", "+rwX"), "f 644, the info manual's own example"),
    reads("=,u+x", symbolic("=", "u+x"), "f 100"),
    reads("u=g", symbolic("u=g"), "f 444"),
    reads("o=u", symbolic("o=u"), "f 646"),
    reads("g-o", symbolic("g-o"), "f 604"),
    reads("a+X", symbolic("a+X"), "f 644"),
    reads("ug+rw-x", symbolic("ug+rw-x"), "f 664"),
    reads("u+x+w", symbolic("u+x+w"), "f 744"),
    reads("uu+x", symbolic("uu+x"), "f 744"),
    reads("ugoa+x", symbolic("ugoa+x"), "f 755"),
    reads("+t", symbolic("+t"), "f 1644"),
    reads("o+s", symbolic("o+s"), "f 644"),
    reads("=+x", symbolic("=+x"), "f 111"),
    reads("+", symbolic("+"), "f 644"),
    reads("u+", symbolic("u+"), "f 644"),
    reads("=", symbolic("="), "f 000"),
    reads("-", symbolic("-"), "f 644"),
    reads("+-=", symbolic("+-="), "f 000"),
    reads("go-rwx", symbolic("go-rwx"), "f 600"),
)

/** Mode words that start with a dash, typed without `--`: chmod reads each as the mode. */
private val DASH_LED: List<ModeCell> = listOf(
    reads("-w", symbolic("-w"), "f 444"),
    reads("-rwx", symbolic("-rwx"), "f 000"),
    reads("-w,u+x", symbolic("-w", "u+x"), "f 544"),
    reads("-X", symbolic("-X"), "f 644"),
    reads("-s", symbolic("-s"), "f 644"),
    reads("-t", symbolic("-t"), "f 644"),
    reads("-u", symbolic("-u"), "f 000"),
    reads("-g", symbolic("-g"), "f 200"),
    reads("-o", symbolic("-o"), "f 200"),
    reads("-+", symbolic("-+"), "f 644"),
    reads("-=", symbolic("-="), "f 000"),
)

/** Words chmod refuses as a mode. */
private val INVALID: List<ModeCell> = listOf(
    "17755", "10000", "077777", "u+x,=17777", "8", "0x1ff", "u+x,755", "755,u+x", "u+7", "u", "u+rwg", "u+gw",
    "u+x,", ",u+x", "u+x,,g+w", "U+x", "u+W", "", "xyz", "-a", "-,",
).map(::invalid)

/** The values `--reference` binds as typed: chmod opens the file only after the line is read. */
private val REFERENCES = listOf("r", "", "-", "--", "-w", "a=b", "a b", "~", "ü", "--help")

/**
 * What the fixture makes of each typed chmod value (X of the testing matrix): the MODE operand's octal and symbolic
 * grammar, with and without an operator or a leading dash, and `--reference`'s file, bound verbatim.
 */
class ChmodConverterTest {

    private val parity = ParitySuite(chmodCli())

    @Test
    fun `an octal mode binds its bits and its operator`() = parity.checkModes(OCTALS)

    @Test
    fun `a symbolic mode binds its clauses`() = parity.checkModes(SYMBOLICS)

    @Test
    fun `a mode starting with a dash binds as the mode`() = parity.checkModes(DASH_LED)

    @Test
    fun `a word outside chmod's mode grammar is refused`() = parity.checkModes(INVALID)

    @Test
    fun `a mode after the end of options binds as typed`() = checkCells(DASH_LED + OCTALS.take(3)) { cell ->
        parity.binds("--", cell.word, "f", expected = NOTHING_BOUND.copy(mode = cell.mode, files = listOf("f")))
    }

    @Test
    fun `--reference binds its file as typed`() = checkCells(REFERENCES) { value ->
        val expected = NOTHING_BOUND.copy(reference = value, files = listOf("f"))
        parity.binds("--reference=$value", "f", expected = expected, because = "chmod opens '$value' only later")
        parity.binds("--reference", value, "f", expected = expected, because = "chmod opens '$value' only later")
    }

    @Test
    fun `an octal mode with leading zeros binds its bits`() = parity.checkModes(
        // `chmod 00755 f` and `chmod 000000755 f` leave f at 755; `chmod 17755 f` is "invalid mode".
        listOf(
            reads("00755", octal("755"), "f 755"),
            reads("000000755", octal("755"), "f 755"),
            reads("+00755", octal("755", ADD), "f 755"),
            reads("=00755", octal("755", ASSIGN), "f 755"),
            reads("-00755", octal("755", REMOVE), "f 000"),
        ),
    )

    @Test
    fun `an operator with octal digits is a clause of a symbolic mode`() = parity.checkModes(
        // `chmod u+x,=755 f` leaves f at 755; `chmod u+x,755 f` is "invalid mode": a clause needs the operator.
        listOf(
            reads("u+x,=755", symbolic("u+x", "=755"), "f 755"),
            reads("=644,u+x", symbolic("=644", "u+x"), "f 744"),
            reads("u+x,+0755", symbolic("u+x", "+0755"), "f 755"),
            reads("+7,u+x", symbolic("+7", "u+x"), "f 747"),
            reads("=755,=644", symbolic("=755", "=644"), "f 644"),
        ),
    )
}
