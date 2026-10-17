package com.fromwau.klap.fixture.rm

import com.fromwau.kern.result.Result
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.parse
import kotlin.test.fail

/*
 * The cells of the projection tests, generated from the inputs real rm 9.12 documents (`INPUTS` in RmModel.kt). Every
 * expected record is how real rm reads the line, run inside a throwaway directory holding the files a and b unless a
 * cell's `because` says it was read from `rm --help` or getopt: no line with --no-preserve-root, in any spelling, was
 * run.
 */

/** The projection cells of rm: one argv line each and what real rm makes of it. */
internal object RmProjectionSupport {

    /**
     * [argv] binds [expected], or is refused by rm [because] where [expected] is null; [key] is the spelling under test
     * (`-R`, `--recursive`). Where klap refuses too, but for another reason, [differs] says how.
     */
    data class Cell(
        val key: String,
        val form: String,
        val argv: List<String>,
        val expected: RmInputs?,
        val because: String,
        val differs: String? = null,
    ) {
        /** Whether the value under test is one rm takes only as `no`, `none` or `yes`, or a prefix of only those. */
        val alias: Boolean
            get() {
                val value = argv.firstOrNull { it.startsWith("--") && '=' in it }?.substringAfter('=') ?: return false
                return key == "--interactive" && whenOf(value) != null &&
                    listOf("never", "once", "always").none { it.startsWith(value) }
            }
    }

    /** [line] binds the same record as [sameAs], both accepted: two spellings real rm reads as one setting. */
    data class Same(
        val line: List<String>,
        val sameAs: List<String>,
        val because: String,
    )

    fun ParitySuite<RmInputs>.check(cell: Cell) {
        val argv = cell.argv.toTypedArray()
        val expected = cell.expected
        if (expected == null) {
            rejects(argv = argv, because = cell.because, differs = cell.differs)
        } else {
            binds(argv = argv, expected = expected, because = cell.because)
        }
    }

    fun ParitySuite<RmInputs>.checkAll(cells: List<Cell>) = checkCells(cells) { check(it) }

    fun checkSame(cells: List<Same>) = checkCells(cells) { cell ->
        val record = bound(cell.line)
        val other = bound(cell.sameAs)
        if (record != other) {
            fail(
                "expected `${shown(cell.line)}` to bind what `${shown(cell.sameAs)}` binds (${cell.because})\n" +
                    "  this:  $record\n  other: $other",
            )
        }
    }

    private fun bound(line: List<String>): RmInputs = when (val parsed = rmCli().parse(line)) {
        is Result.Error -> fail("expected `${shown(line)}` to bind, but it was rejected: ${parsed.error}")
        is Result.Success -> parsed.value ?: fail("expected `${shown(line)}` to bind, but no command ran")
    }

    private fun shown(line: List<String>): String =
        (listOf("rm") + line.map { if (it.isEmpty() || ' ' in it) "\"$it\"" else it }).joinToString(" ")

    // --- what a cell spells ---

    const val LONG = "the long name"
    const val LONG_REPEATED = "the long name twice"
    const val LONG_ATTACHED = "a flag's long name with a value attached"
    const val ABBREVIATED = "the shortest unique abbreviation"
    const val SHORT = "the short letter"
    const val SHORT_REPEATED = "the short letter doubled"
    const val CLUSTER = "the short letter in a cluster"
    const val BETWEEN = "between the operands"
    const val AFTER = "after the operands"
    const val AFTER_END = "after the end of options"
    const val ATTACHED = "a value attached"
    const val BARE = "an optional value left out"
    const val BARE_THEN_WORD = "an optional value left out before a word"
    const val BARE_AFTER_VALUE = "an optional value left out after a value"
    const val CHOICE_ABBREVIATED = "a value abbreviated"
    const val CHOICE_CAPITALS = "a value in capitals"
    const val CHOICE_UNKNOWN = "a value rm does not know"
    const val CHOICE_EMPTY = "an empty value"
    const val REPEATED = "the value given twice"

    private val AB = listOf("a", "b")

    private const val NOT_RUN = "not run: read from getopt, rm --help and the strings of /usr/bin/rm"

    private fun Input.cell(
        key: String,
        form: String,
        argv: List<String>,
        expected: RmInputs?,
        because: String = "rm --help: $name",
        differs: String? = null,
    ) = Cell(key, form, argv, expected, if (long == "--no-preserve-root") "$because ($NOT_RUN)" else because, differs)

    private fun Input.bound(value: String? = null): RmInputs = A_AND_B.bind(value)

    private fun RmInputs.with(
        input: Input,
        value: String? = null,
    ): RmInputs = input.bind(this, value)

    /** Every spelling of every input real rm has, in every position, each with what it binds or that rm refuses it. */
    val ALL: List<Cell> by lazy { INPUTS.flatMap(::cellsOf) }

    /** The cells of the spellings [keys] in one of [forms], or in any form when none is given. */
    fun cells(
        keys: Collection<String>,
        vararg forms: String,
    ): List<Cell> = ALL.filter { it.key in keys && (forms.isEmpty() || it.form in forms) }

    private fun cellsOf(input: Input): List<Cell> = when (val kind = input.kind) {
        Kind.Flag -> flagCells(input)
        is Kind.Value -> valueCells(input, kind)
    }

    private fun flagCells(input: Input): List<Cell> = buildList {
        input.long?.let { long ->
            add(input.cell(long, LONG, listOf(long) + AB, input.bound()))
            add(input.cell(long, LONG_REPEATED, listOf(long, long) + AB, input.bound(), "`rm --verbose --verbose a`"))
            val noValue = "rm: option '$long' doesn't allow an argument"
            add(input.cell(long, LONG_ATTACHED, listOf("$long=x") + AB, null, noValue))
            abbreviationOf(long)?.let { prefix ->
                val expected = if (input.abbreviates) input.bound() else null
                val because = if (input.abbreviates) "getopt_long reads $prefix as $long" else
                    "strings /usr/bin/rm: you may not abbreviate the $long option"
                add(input.cell(long, ABBREVIATED, listOf(prefix) + AB, expected, because))
            }
            addAll(positionCells(input, long, long, input.bound()))
        }
        for (short in input.shorts) {
            val key = "-$short"
            add(input.cell(key, SHORT, listOf(key) + AB, input.bound()))
            add(input.cell(key, SHORT_REPEATED, listOf("-$short$short") + AB, input.bound(), "`rm -vv a`, `rm -rr dd`"))
            val partner = inputFor(if (short == 'v') 'd' else 'v')!!
            val clustered = partner.bound().with(input)
            add(input.cell(key, CLUSTER, listOf("-${partner.shorts.first()}$short") + AB, clustered, "`rm -dv ed`"))
            addAll(positionCells(input, key, key, input.bound()))
        }
    }

    /** [word] between and after the operands binds as before them; after `--` it is the first operand. */
    private fun positionCells(
        input: Input,
        key: String,
        word: String,
        bound: RmInputs,
    ): List<Cell> = listOf(
        input.cell(key, BETWEEN, listOf("a", word, "b"), bound, "`rm a -v` reads the option after an operand"),
        input.cell(key, AFTER, listOf("a", "b", word), bound, "`rm a -v` printed removed 'a'"),
        input.cell(
            key,
            AFTER_END,
            listOf("--", word, "b"),
            NOTHING_BOUND.copy(files = listOf(word, "b")),
            "`rm a -- -v`: cannot remove '-v'",
        ),
    )

    private fun valueCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long!!
        val sample = kind.words.first()
        for (value in kind.words) add(input.cell(long, ATTACHED, listOf("$long=$value") + AB, input.bound(value)))
        abbreviationOf(long)?.let { prefix ->
            add(input.cell(long, ABBREVIATED, listOf("$prefix=$sample") + AB, input.bound(sample), "getopt_long"))
            add(input.cell(long, ABBREVIATED, listOf(prefix) + AB, kind.bare(A_AND_B), "getopt_long"))
        }
        add(input.cell(long, BARE, listOf(long) + AB, kind.bare(A_AND_B), "rm --help: $long[=...]"))
        add(
            input.cell(
                long,
                BARE_THEN_WORD,
                listOf(long, sample) + AB,
                kind.bare(NOTHING_BOUND.copy(files = listOf(sample, "a", "b"))),
                "`rm --interactive never a` prompts for never, `rm --preserve-root all` removes all",
            ),
        )
        addAll(positionCells(input, long, "$long=$sample", input.bound(sample)))
        addAll(choiceCells(input, kind))
        add(input.cell(long, REPEATED, listOf("$long=$sample", "$long=$sample") + AB, input.bound(sample)))
    }

    private fun choiceCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long!!
        val refusal =
            if (long == "--interactive") "rm: invalid argument" else "rm: unrecognized --preserve-root argument"
        val prefixes = kind.words.flatMap { word -> (1 until word.length).map(word::take) }.distinct()
        for (prefix in prefixes - kind.words.toSet()) {
            val read = kind.reads(prefix)
            val why = if (read == null) "$refusal '$prefix'" else "`rm $long=$prefix a` reads $read"
            val expected = read?.let { input.bound(prefix) }
            add(input.cell(long, CHOICE_ABBREVIATED, listOf("$long=$prefix") + AB, expected, why))
        }
        val capitals = kind.words.first().uppercase()
        add(input.cell(long, CHOICE_CAPITALS, listOf("$long=$capitals") + AB, null, "$refusal '$capitals'"))
        add(input.cell(long, CHOICE_UNKNOWN, listOf("$long=bogus") + AB, null, "$refusal 'bogus'"))
        val empty = if (long == "--interactive") "rm: ambiguous argument '' for '--interactive'" else "$refusal ''"
        add(input.cell(long, CHOICE_EMPTY, listOf("$long=") + AB, null, empty))
        if (long == "--interactive") {
            // `rm --interactive=never --interactive a` prompts: a bare one after a value is always.
            add(input.cell(long, BARE_AFTER_VALUE, listOf("$long=never", long) + AB, input.bound("always")))
        }
    }
}
