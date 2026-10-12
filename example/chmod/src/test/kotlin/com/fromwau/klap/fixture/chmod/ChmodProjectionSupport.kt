package com.fromwau.klap.fixture.chmod

import com.fromwau.kern.result.Result
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.parse
import kotlin.test.fail

/*
 * The cells of the projection tests, generated from the inputs real chmod 9.12 documents (`INPUTS` in ChmodModel.kt).
 * Every expected record is how real chmod reads the line, run inside a throwaway directory unless a cell's `because`
 * says it was read from `chmod --help` or the info manual.
 */

/** The projection cells of chmod: one argv line each and what real chmod makes of it. */
internal object ChmodProjectionSupport {

    /** [argv] binds [expected], or is refused by chmod [because] where [expected] is null. */
    data class Cell(
        val input: String,
        val form: String,
        val argv: List<String>,
        val expected: ChmodInputs?,
        val because: String,
        val differs: String? = null,
    ) {
        override fun toString(): String = "$input, $form: chmod ${argv.joinToString(" ")}"
    }

    /** [line] and [other] bind different records, both accepted: chmod reads them differently. */
    data class Differ(
        val line: List<String>,
        val other: List<String>,
        val because: String,
    )

    fun ParitySuite<ChmodInputs>.check(cell: Cell) {
        val argv = cell.argv.toTypedArray()
        val expected = cell.expected
        if (expected == null) {
            rejects(argv = argv, because = cell.because, differs = cell.differs)
        } else {
            binds(argv = argv, expected = expected, because = cell.because)
        }
    }

    fun ParitySuite<ChmodInputs>.checkAll(cells: List<Cell>) = checkCells(cells) { check(it) }

    fun checkDiffer(cells: List<Differ>) = checkCells(cells) { cell ->
        if (bound(cell.line) == bound(cell.other)) {
            fail("expected `${shown(cell.line)}` and `${shown(cell.other)}` to bind apart (${cell.because})")
        }
    }

    private fun bound(line: List<String>): ChmodInputs = when (val parsed = chmodCli().parse(line)) {
        is Result.Error -> fail("expected `${shown(line)}` to bind, but it was rejected: ${parsed.error}")
        is Result.Success -> parsed.value ?: fail("expected `${shown(line)}` to bind, but no command ran")
    }

    private fun shown(line: List<String>): String =
        (listOf("chmod") + line.map { if (it.isEmpty() || ' ' in it) "\"$it\"" else it }).joinToString(" ")

    // --- what a cell spells ---

    const val LONG = "the long name"
    const val LONG_REPEATED = "the long name twice"
    const val LONG_ATTACHED = "a flag's long name with a value attached"
    const val ABBREVIATED = "the shortest unique abbreviation"
    const val ABBREVIATED_SPACED = "the shortest unique abbreviation with its value in the next word"
    const val SHORT = "the short letter"
    const val SHORT_REPEATED = "the short letter doubled"
    const val CLUSTER = "the short letter in a cluster"
    const val BETWEEN = "between the operands"
    const val AFTER = "after the operands"
    const val AFTER_END = "after the end of options"
    const val ATTACHED = "a value attached"
    const val SPACED = "a value in the next word"
    const val MISSING = "the value missing at the end of the line"
    const val DASH_VALUE = "a value word that starts with a dash"
    const val END_VALUE = "a value word that is exactly --"
    const val BUILTIN_VALUE = "a value word spelling --help"
    const val EQUALS_VALUE = "a value holding an equals sign"
    const val EMPTY_VALUE = "an empty value"
    const val REPEATED = "the value given twice"
    const val MODE_AS_FILE = "a mode-like operand, which is a file once --reference is given"

    /** Every cell of every input. */
    val ALL: List<Cell> by lazy { INPUTS.flatMap(::cellsOf) }

    /** The cells of [inputs] spelled in one of [forms]. */
    fun cells(
        inputs: List<Input>,
        vararg forms: String,
    ): List<Cell> = ALL.filter { cell -> inputs.any { it.name == cell.input } && cell.form in forms }

    private val MODE_AND_FILE = listOf("u+x", "f")

    private fun cellsOf(input: Input): List<Cell> = if (input.value) valueCells(input) else flagCells(input)

    private fun flagCells(input: Input): List<Cell> = buildList {
        val set = U_X_F.(input.bind)("")
        fun add(form: String, argv: List<String>, expected: ChmodInputs? = set, because: String = "exit 0") =
            add(Cell(input.name, form, argv, expected, because))
        for (long in input.longs) {
            add(LONG, listOf(long) + MODE_AND_FILE)
            add(LONG_REPEATED, listOf(long, long) + MODE_AND_FILE)
            add(
                LONG_ATTACHED,
                listOf("$long=x") + MODE_AND_FILE,
                expected = null,
                because = "chmod: option '$long' doesn't allow an argument",
            )
            abbreviationOf(long)?.let { add(ABBREVIATED, listOf(it) + MODE_AND_FILE) }
            add(BETWEEN, listOf("u+x", long, "f"))
            add(AFTER, MODE_AND_FILE + long)
            add(
                AFTER_END,
                MODE_AND_FILE + listOf("--", long),
                expected = U_X_F.copy(files = listOf("f", long)),
                because = "chmod: cannot access '$long'",
            )
        }
        input.short?.let { letter ->
            val short = "-$letter"
            add(SHORT, listOf(short) + MODE_AND_FILE)
            add(SHORT_REPEATED, listOf("-$letter$letter") + MODE_AND_FILE)
            // -f binds nothing another letter undoes, so it partners every letter in a cluster.
            val partner = if (letter == 'f') 'R' else 'f'
            add(CLUSTER, listOf("-$partner$letter") + MODE_AND_FILE, expected = set.(inputFor(partner)!!.bind)(""))
            add(BETWEEN, listOf("u+x", short, "f"))
            add(AFTER, MODE_AND_FILE + short)
            add(
                AFTER_END,
                MODE_AND_FILE + listOf("--", short),
                expected = U_X_F.copy(files = listOf("f", short)),
                because = "chmod: cannot access '$short'",
            )
        }
    }

    private fun valueCells(input: Input): List<Cell> = buildList {
        val long = input.longs.single()
        val abbreviation = abbreviationOf(long)!!
        fun referencing(value: String, vararg files: String) =
            NOTHING_BOUND.copy(reference = value, files = files.toList())
        fun add(form: String, argv: List<String>, expected: ChmodInputs?, because: String) =
            add(Cell(input.name, form, argv, expected, because))
        add(ATTACHED, listOf("$long=r", "f"), referencing("r", "f"), "f takes r's mode 600")
        add(SPACED, listOf(long, "r", "f"), referencing("r", "f"), "f takes r's mode 600")
        add(ABBREVIATED, listOf("$abbreviation=r", "f"), referencing("r", "f"), "`chmod --ref=r f`: f takes 600")
        add(ABBREVIATED_SPACED, listOf(abbreviation, "r", "f"), referencing("r", "f"), "`chmod --ref r f`")
        add(MISSING, listOf("f", long), null, "chmod: option '$long' requires an argument")
        add(BETWEEN, listOf("f", "$long=r", "g"), referencing("r", "f", "g"), "f and g take r's mode")
        add(AFTER, listOf("f", "$long=r"), referencing("r", "f"), "f takes r's mode")
        add(
            AFTER_END,
            MODE_AND_FILE + listOf("--", "$long=r"),
            U_X_F.copy(files = listOf("f", "$long=r")),
            "chmod: cannot access '$long=r'",
        )
        add(DASH_VALUE, listOf(long, "-w", "f"), referencing("-w", "f"), "chmod: failed to get attributes of '-w'")
        add(END_VALUE, listOf(long, "--", "f"), referencing("--", "f"), "chmod: failed to get attributes of '--'")
        add(
            BUILTIN_VALUE,
            listOf(long, "--help", "f"),
            referencing("--help", "f"),
            "chmod: failed to get attributes of '--help'",
        )
        add(EQUALS_VALUE, listOf("$long=a=b", "f"), referencing("a=b", "f"), "failed to get attributes of 'a=b'")
        add(EMPTY_VALUE, listOf("$long=", "f"), referencing("", "f"), "chmod: failed to get attributes of ''")
        add(REPEATED, listOf("$long=r", "$long=g", "f"), referencing("g", "f"), "f takes g's mode 644, not r's 600")
        add(MODE_AS_FILE, listOf("$long=r", "u+x", "f"), referencing("r", "u+x", "f"), "cannot access 'u+x'")
    }
}
