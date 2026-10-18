package com.fromwau.klap.fixture.head

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.io.CliPath

/*
 * The cells of the projection and converter tests, generated from the inputs real head 9.12 documents (`INPUTS` in
 * HeadModel.kt). Every expected outcome is how real head reads the line, run inside a throwaway directory holding the
 * files a, b and f, unless a cell's `because` says it was read from `head --help`, the info page or getopt.
 */

/** What real head makes of one line. */
internal sealed interface Outcome {
    /** The line binds [record]. */
    data class Binds(val record: HeadInputs) : Outcome

    /** head refuses the line; [differs] names how klap's refusal differs, where both refuse for other reasons. */
    data class Rejects(val differs: String? = null) : Outcome
}

/** The projection cells of head: one argv line each, the spelling under test ([key]) and what real head makes of it. */
internal object HeadProjectionSupport {

    data class Cell(
        val key: String,
        val form: String,
        val argv: List<String>,
        val outcome: Outcome,
        val because: String,
    ) {
        override fun toString(): String = "$form ${shown(argv)}"
    }

    fun ParitySuite<HeadInputs>.check(cell: Cell) {
        val argv = cell.argv.toTypedArray()
        when (val outcome = cell.outcome) {
            is Outcome.Binds -> binds(argv = argv, expected = outcome.record, because = cell.because)
            is Outcome.Rejects -> rejects(argv = argv, because = cell.because, differs = outcome.differs)
        }
    }

    fun ParitySuite<HeadInputs>.checkAll(cells: List<Cell>) = checkCells(cells) { check(it) }

    fun shown(line: List<String>): String =
        (listOf("head") + line.map { if (it.isEmpty() || ' ' in it) "\"$it\"" else it }).joinToString(" ")

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
    const val SPACED = "a value in its own word"
    const val DASH_LED = "a dash-led value"
    const val GLUED = "a value glued to the short"
    const val SHORT_SPACED = "a value after the short in its own word"
    const val CLUSTER_GLUED = "a value glued after a cluster"
    const val CLUSTER_SPACED = "a value after a cluster in its own word"
    const val SHORT_EQUALS = "a short with = before its value"
    const val MISSING_VALUE = "a required value left out"
    const val REPEATED = "the value given twice"

    private val AB = listOf("a", "b")

    private fun Input.bound(value: String? = null): HeadInputs = bind(A_AND_B, value)

    private fun Input.partner(): Input = inputFor(if (short == 'z') 'q' else 'z')!!

    /** Every spelling of every input real head has, in every position, each with what head makes of it. */
    val ALL: List<Cell> by lazy { INPUTS.flatMap(::cellsOf) }

    /** The cells of the spellings [keys] in one of [forms], or in any form when none is given. */
    fun cells(
        keys: Collection<String>,
        vararg forms: String,
    ): List<Cell> = ALL.filter { it.key in keys && (forms.isEmpty() || it.form in forms) }

    /** The cells of every key in one of [forms]. */
    fun cellsIn(vararg forms: String): List<Cell> = ALL.filter { it.form in forms }

    private fun cellsOf(input: Input): List<Cell> = when (val kind = input.kind) {
        Kind.Flag -> flagCells(input)
        is Kind.Value -> valueCells(input, kind)
    }

    private fun flagCells(input: Input): List<Cell> = buildList {
        val bound = input.bound()
        for (long in input.longs) {
            add(Cell(long, LONG, listOf(long) + AB, Outcome.Binds(bound), "head --help: $long"))
            add(Cell(long, LONG_REPEATED, listOf(long, long) + AB, Outcome.Binds(bound), "`head -q -q a b` as -q"))
            add(
                Cell(
                    long,
                    LONG_ATTACHED,
                    listOf("$long=x") + AB,
                    Outcome.Rejects(),
                    "head: option '$long' doesn't allow an argument",
                ),
            )
            abbreviationOf(long)?.let { prefix ->
                add(Cell(long, ABBREVIATED, listOf(prefix) + AB, Outcome.Binds(bound), "getopt reads $prefix as $long"))
            }
            addAll(positionCells(long, long, bound))
        }
        val key = "-${input.short}"
        val short = input.short
        add(Cell(key, SHORT, listOf(key) + AB, Outcome.Binds(bound), "head --help: $key"))
        add(Cell(key, SHORT_REPEATED, listOf("-$short$short") + AB, Outcome.Binds(bound), "`head -vv f` as -v"))
        val partner = input.partner()
        val clustered = input.bind(partner.bound(), null)
        add(
            Cell(
                key,
                CLUSTER,
                listOf("-${partner.short}$short") + AB,
                Outcome.Binds(clustered),
                "`head -qz` is `head -q -z`",
            ),
        )
        addAll(positionCells(key, key, bound))
    }

    /** [word] between and after the operands binds as before them; after `--` it is the first operand. */
    private fun positionCells(
        key: String,
        word: String,
        bound: HeadInputs,
    ): List<Cell> = listOf(
        Cell(key, BETWEEN, listOf("a", word, "b"), Outcome.Binds(bound), "`head a -q b` prints no headers"),
        Cell(key, AFTER, listOf("a", "b", word), Outcome.Binds(bound), "`head a b -v` prints headers"),
        Cell(
            key,
            AFTER_END,
            listOf("--", word, "b"),
            Outcome.Binds(NOTHING_BOUND.copy(files = listOf(CliPath(word), CliPath("b")))),
            "`head -- -x` prints the file -x",
        ),
    )

    private fun valueCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.longs.single()
        val sample = kind.sample
        val bound = Outcome.Binds(input.bound(sample))
        val help = "head --help: $long=[-]NUM"
        add(Cell(long, ATTACHED, listOf("$long=$sample") + AB, bound, help))
        add(Cell(long, SPACED, listOf(long, sample) + AB, bound, help))
        val negative = Outcome.Binds(input.bound("-$sample"))
        add(Cell(long, DASH_LED, listOf(long, "-$sample") + AB, negative, "`head --lines -5 f` prints all but five"))
        add(Cell(long, DASH_LED, listOf("$long=-$sample") + AB, negative, "`head --lines=-5 f` prints all but five"))
        abbreviationOf(long)?.let { prefix ->
            add(Cell(long, ABBREVIATED, listOf("$prefix=$sample") + AB, bound, "getopt reads $prefix as $long"))
            add(Cell(long, ABBREVIATED, listOf(prefix, sample) + AB, bound, "getopt reads $prefix as $long"))
        }
        val requires = "head: option '$long' requires an argument"
        add(Cell(long, MISSING_VALUE, listOf(long), Outcome.Rejects(), requires))
        add(Cell(long, MISSING_VALUE, AB + long, Outcome.Rejects(), requires))
        addAll(positionCells(long, "$long=$sample", input.bound(sample)))
        val twice = Outcome.Binds(input.bound(kind.second))
        add(
            Cell(
                long,
                REPEATED,
                listOf("$long=$sample", "$long=${kind.second}") + AB,
                twice,
                "`head -n 1 -n 2 f` prints two lines",
            ),
        )
        addAll(shortValueCells(input, sample))
    }

    private fun shortValueCells(
        input: Input,
        sample: String,
    ): List<Cell> {
        val short = input.short
        val key = "-$short"
        val bound = Outcome.Binds(input.bound(sample))
        val clustered = Outcome.Binds(input.bind(inputFor('q')!!.bound(), sample))
        val cluster = "-q${input.short}"
        val equals = "head: invalid number of ${if (input.short == 'n') "lines" else "bytes"}: '=$sample'"
        return listOf(
            Cell(key, GLUED, listOf("$key$sample") + AB, bound, "`head -n3 f` prints three lines"),
            Cell(key, SHORT_SPACED, listOf(key, sample) + AB, bound, "`head -n 3 f` prints three lines"),
            Cell(key, DASH_LED, listOf("$key-$sample") + AB, Outcome.Binds(input.bound("-$sample")), "`head -n-5 f`"),
            Cell(key, DASH_LED, listOf(key, "-$sample") + AB, Outcome.Binds(input.bound("-$sample")), "`head -n -5 f`"),
            Cell(key, CLUSTER_GLUED, listOf("$cluster$sample") + AB, clustered, "`head -qn5 a b` five lines each"),
            Cell(key, CLUSTER_SPACED, listOf(cluster, sample) + AB, clustered, "`head -qn 5 a b` five lines each"),
            Cell(key, SHORT_EQUALS, listOf("$key=$sample") + AB, Outcome.Rejects(), equals),
            Cell(key, MISSING_VALUE, listOf(key), Outcome.Rejects(), "head: option requires an argument -- '$short'"),
        )
    }

    /**
     * Every prefix of a head long that getopt calls ambiguous, each refused: `head --v f` is "option '--v' is
     * ambiguous; possibilities: '--verbose' '--version'".
     */
    val AMBIGUOUS_PREFIXES: List<Cell> by lazy {
        HEAD_LONGS
            .flatMap { long -> (3 until long.length).map(long::take) }
            .distinct()
            .filter(::isAmbiguousLong)
            .map { prefix ->
                val reached = HEAD_LONGS.filter { it.startsWith(prefix) }
                val because = "head: option '$prefix' is ambiguous; possibilities: ${reached.joinToString(" ")}"
                Cell(prefix, "an ambiguous prefix", listOf(prefix) + AB, Outcome.Rejects(), because)
            }
    }
}
