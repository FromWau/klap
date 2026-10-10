package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.checkCells
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * What Tab should offer on a cp line where klap or the fixture offers something else today: exactly what real
 * cp 9.12 would accept at the cursor, in its own `--help` wording. Each test is ignored with the reason
 * `TDD target: <what must change>`; removing the annotation shows it failing until klap or the fixture is changed,
 * and no other test pins today's suggestions for these cells, so a fix breaks nothing else.
 */
class CpCompletionTargetsTest {

    private val cp = cpCli().cli

    private fun check(cells: List<CompletionCell>) =
        checkCells(cells.filterNot(HELP_IN_CLUSTER_AND_HELP_ALL)) { cp.assertCp(it) }

    private val nameKinds = listOf("long end", "long inside", "long abbreviated")

    private val letterKinds = listOf("short end", "cluster end", "cluster inside")

    private val valueKinds = listOf(
        "value no word", "value word start", "value spaced", "value attached", "value abbreviated", "value list",
    )

    @Ignore("TDD target: klap must offer no operand where -T leaves no room for one")
    @Test
    fun `-T leaves no room for a third operand`() {
        // `cp -T a b c`: extra operand 'c'; the same with -T after the cursor (`cp | -T a b`).
        check(cells("no word", FULL_OPERANDS) + cells("operand end", FULL_OPERANDS))
    }

    @Ignore("TDD target: a dash must offer cp's names only, in cp's help wording, without klap's built-ins")
    @Test
    fun `a dash offers cp's options in cp's words and nothing else`() {
        // `cp --help` lists every name with its wording; `cp --json`, `--color`, `--completion`, `--docs`,
        // `--help-all`, `--recursive-r` and `-h` are all refused.
        check(cells("dash", LETTERS))
    }

    @Ignore("TDD target: the fixture must declare -t and -T exclusive and -t not repeatable for completion to see it")
    @Test
    fun `-t and -T leave each other out and -t is not offered twice`() {
        // `cp -t a -T b c`: cannot combine; `cp -t a -t b x`: multiple target directories specified. Words after
        // the cursor count as much as words before it.
        val refused = (listOf("dash", "value no word", "value attached") + nameKinds + letterKinds)
            .flatMap { cells(it, REFUSED) }
            .filterNot { it.line == "cp -h" }
        check(refused)
    }

    @Ignore("TDD target: the fixture must describe each option in cp's own --help words")
    @Test
    fun `a long name is described in cp's words`() = check(nameKinds.flatMap { cells(it, OTHER_WORDING) })

    @Ignore("TDD target: klap must let a negation spelling carry its own help (-P is never follow links)")
    @Test
    fun `--no-dereference is described as never following links`() {
        // `cp --help`: "-P, --no-dereference  never follow symbolic links in SOURCE"; klap repeats --dereference's.
        check(nameKinds.flatMap { cells(it, NEGATION) })
    }

    @Ignore("TDD target: the fixture must drop --recursive-r, and klap word --help as cp does (todo 45)")
    @Test
    fun `a long name completes to cp's names only`() {
        // `cp --rec` is --recursive alone, `cp --h` is --help alone, `cp --j` and `cp --col`: unrecognized option.
        check(nameKinds.flatMap { cells(it, SHADOWED, KLAP_ONLY) } + CELLS.filter { it.line == "cp -h" })
    }

    @Ignore("TDD target: klap must let a CLI word its --help and --version as cp does")
    @Test
    fun `--help and --version are described in cp's words`() {
        // `cp --help`: "--help  display this help and exit", "--version  output version information and exit".
        check(nameKinds.flatMap { cells(it, BUILT_IN) })
    }

    @Ignore("TDD target: a cluster must continue with cp's letters in cp's words, -r and -P included")
    @Test
    fun `a cluster continues with cp's letters in cp's words`() {
        // fish's own `complete -C "cp -v"` lists -va to -vZ, -r among them; the descriptions here are cp --help's.
        check(letterKinds.flatMap { cells(it, LETTERS) })
    }

    @Ignore("TDD target: the fixture must give --preserve and --context an optional value as cp's help shows")
    @Test
    fun `--preserve and --context leave a spaced word to the operands`() {
        // `cp --preserve mode src dst`: target 'dst': Not a directory, so mode was a source.
        check(valueKinds.flatMap { cells(it, REQUIRED_HERE) })
    }

    @Ignore("TDD target: the fixture must complete cp's attribute names in --preserve and --no-preserve lists")
    @Test
    fun `an attribute list completes cp's attribute names after each comma`() {
        // `cp --help`: ATTR_LIST is mode, ownership, timestamps, links, context, xattr and all; a name already in
        // the list is not offered again.
        check(valueKinds.flatMap { cells(it, ATTRIBUTE) })
    }

    @Ignore("TDD target: the fixture must describe --update --reflink and --sparse in cp's own --help words")
    @Test
    fun `an attached choice is described by cp's help`() = check(valueKinds.flatMap { cells(it, VALUE_OTHER_WORDING) })
}
