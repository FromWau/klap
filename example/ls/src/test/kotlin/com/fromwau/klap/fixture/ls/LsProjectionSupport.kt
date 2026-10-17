package com.fromwau.klap.fixture.ls

import com.fromwau.kern.result.Result
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.parse
import kotlin.test.fail

/*
 * The cells of the projection and converter tests, generated from the inputs real ls 9.12 documents (`INPUTS` in
 * LsModel.kt). Every expected outcome is how real ls reads the line, run inside a throwaway directory holding the
 * files a and b with ls's environment unset, unless a cell's `because` says it was read from `ls --help` or getopt.
 */

/** What real ls makes of one line. */
internal sealed interface Outcome {
    /** The line binds [record]. */
    data class Binds(val record: LsInputs) : Outcome

    /** The line binds what [line] binds: two spellings real ls reads as one setting. */
    data class SameAs(val line: List<String>) : Outcome

    /** ls refuses the line. */
    data object Rejects : Outcome
}

/** The projection cells of ls: one argv line each, the spelling under test ([key]) and what real ls makes of it. */
internal object LsProjectionSupport {

    data class Cell(
        val key: String,
        val form: String,
        val argv: List<String>,
        val outcome: Outcome,
        val because: String,
    ) {
        override fun toString(): String = "$form ${shown(argv)}"
    }

    fun ParitySuite<LsInputs>.check(cell: Cell) {
        val argv = cell.argv.toTypedArray()
        when (val outcome = cell.outcome) {
            is Outcome.Binds -> binds(argv = argv, expected = outcome.record, because = cell.because)
            Outcome.Rejects -> rejects(argv = argv, because = cell.because)
            is Outcome.SameAs -> {
                val record = bound(cell.argv, cell.because)
                val other = bound(outcome.line, cell.because)
                if (record != other) {
                    fail(
                        "expected `${shown(cell.argv)}` to bind what `${shown(outcome.line)}` binds " +
                            "(${cell.because})\n  this:  $record\n  other: $other",
                    )
                }
            }
        }
    }

    fun ParitySuite<LsInputs>.checkAll(cells: List<Cell>) = checkCells(cells) { check(it) }

    private fun bound(
        line: List<String>,
        because: String,
    ): LsInputs = when (val parsed = lsCli().parse(line)) {
        is Result.Error -> fail("expected `${shown(line)}` to bind ($because), but it was rejected: ${parsed.error}")
        is Result.Success -> parsed.value ?: fail("expected `${shown(line)}` to bind ($because), but no command ran")
    }

    fun shown(line: List<String>): String =
        (listOf("ls") + line.map { if (it.isEmpty() || ' ' in it) "\"$it\"" else it }).joinToString(" ")

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
    const val GLUED = "a value glued to the short"
    const val SHORT_SPACED = "a value after the short in its own word"
    const val CLUSTER_GLUED = "a value glued after a cluster"
    const val CLUSTER_SPACED = "a value after a cluster in its own word"
    const val SHORT_EQUALS = "a short with = before its value"
    const val MISSING_VALUE = "a required value left out"
    const val BARE = "an optional value left out"
    const val BARE_THEN_WORD = "an optional value left out before a word"
    const val SYNONYM = "another word for the same value"
    const val CHOICE_ABBREVIATED = "a value abbreviated"
    const val SYNONYM_ABBREVIATED = "another word for a value abbreviated"
    const val CHOICE_AMBIGUOUS = "a value prefix reaching two values"
    const val CHOICE_CAPITALS = "a value in capitals"
    const val CHOICE_UNKNOWN = "a value ls does not know"
    const val CHOICE_EMPTY = "an empty value"
    const val REPEATED = "the value given twice"

    private val AB = listOf("a", "b")

    private val Input.flagBound: LsInputs get() = bind(A_AND_B, null)

    private fun Input.valueBound(value: String): LsInputs = bind(A_AND_B, value)

    private fun Input.partner(): Input = inputFor(if ('r' in shorts) 'i' else 'r')!!

    /** Every spelling of every input real ls has, in every position, each with what ls makes of it. */
    val ALL: List<Cell> by lazy { ALL_INPUTS.flatMap(::cellsOf) }

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
        val bound = input.flagBound
        input.long?.let { long ->
            add(Cell(long, LONG, listOf(long) + AB, Outcome.Binds(bound), "ls --help: $long"))
            add(Cell(long, LONG_REPEATED, listOf(long, long) + AB, Outcome.Binds(bound), "`ls -r -r` reverses once"))
            add(
                Cell(
                    long,
                    LONG_ATTACHED,
                    listOf("$long=x") + AB,
                    Outcome.Rejects,
                    "ls: option '$long' doesn't allow an argument",
                ),
            )
            abbreviationOf(long)?.let { prefix ->
                add(Cell(long, ABBREVIATED, listOf(prefix) + AB, Outcome.Binds(bound), "getopt reads $prefix as $long"))
            }
            addAll(positionCells(long, long, bound))
        }
        for (short in input.shorts) {
            val key = "-$short"
            add(Cell(key, SHORT, listOf(key) + AB, Outcome.Binds(bound), "ls --help: $key"))
            add(Cell(key, SHORT_REPEATED, listOf("-$short$short") + AB, Outcome.Binds(bound), "`ls -rr` reverses once"))
            val partner = input.partner()
            val clustered = input.bind(partner.flagBound, null)
            val cluster = "-${partner.shorts.first()}$short"
            add(Cell(key, CLUSTER, listOf(cluster) + AB, Outcome.Binds(clustered), "`ls -la` is `ls -l -a`"))
            addAll(positionCells(key, key, bound))
        }
    }

    /** [word] between and after the operands binds as before them; after `--` it is the first operand. */
    private fun positionCells(
        key: String,
        word: String,
        bound: LsInputs,
    ): List<Cell> = listOf(
        Cell(key, BETWEEN, listOf("a", word, "b"), Outcome.Binds(bound), "`ls a.y -l` lists a.y long"),
        Cell(key, AFTER, listOf("a", "b", word), Outcome.Binds(bound), "`ls a.y -l` lists a.y long"),
        Cell(
            key,
            AFTER_END,
            listOf("--", word, "b"),
            Outcome.Binds(NOTHING_BOUND.copy(files = listOf(word, "b"))),
            "`ls -- -a`: cannot access '-a'",
        ),
    )

    private fun valueCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long!!
        val groups = kind.groups
        val values = groups?.map { it.first() } ?: listOf(kind.sample)
        val sample = values.first()
        val help = "ls --help: $long${if (kind.optional) "[=...]" else "=..."}"
        for (value in values) {
            add(Cell(long, ATTACHED, listOf("$long=$value") + AB, Outcome.Binds(input.valueBound(value)), help))
            if (!kind.optional) {
                add(Cell(long, SPACED, listOf(long, value) + AB, Outcome.Binds(input.valueBound(value)), help))
            }
        }
        abbreviationOf(long)?.let { prefix ->
            val bound = Outcome.Binds(input.valueBound(sample))
            add(Cell(long, ABBREVIATED, listOf("$prefix=$sample") + AB, bound, "getopt reads $prefix as $long"))
            if (kind.optional) {
                val bare = Outcome.Binds(input.valueBound("always"))
                add(Cell(long, ABBREVIATED, listOf(prefix) + AB, bare, "getopt reads $prefix as $long"))
            } else {
                add(Cell(long, ABBREVIATED, listOf(prefix, sample) + AB, bound, "getopt reads $prefix as $long"))
            }
        }
        if (kind.optional) {
            val bare = Outcome.Binds(input.valueBound("always"))
            add(Cell(long, BARE, listOf(long) + AB, bare, "ls --help: $long[=WHEN]"))
            add(
                Cell(
                    long,
                    BARE_THEN_WORD,
                    listOf(long, "never") + AB,
                    Outcome.Binds(input.bind(NOTHING_BOUND.copy(files = listOf("never") + AB), "always")),
                    "`ls --color always`: cannot access 'always'",
                ),
            )
        } else {
            val requires = "ls: option '$long' requires an argument"
            add(Cell(long, MISSING_VALUE, listOf(long), Outcome.Rejects, requires))
            add(Cell(long, MISSING_VALUE, AB + long, Outcome.Rejects, requires))
        }
        addAll(positionCells(long, "$long=$sample", input.valueBound(sample)))
        if (groups != null && kind.checked) addAll(choiceCells(long, groups))
        addAll(repeatCells(input, long, kind))
        for (short in input.shorts) addAll(shortValueCells(input, short, sample))
    }

    private fun choiceCells(
        long: String,
        groups: List<List<String>>,
    ): List<Cell> = buildList {
        val words = groups.flatten()
        val invalid = "ls: invalid argument for '$long'"
        val ambiguous = "ls: ambiguous argument for '$long'"
        for (group in groups) {
            for (word in group.drop(1)) {
                add(
                    Cell(
                        long,
                        SYNONYM,
                        listOf("$long=$word") + AB,
                        Outcome.SameAs(listOf("$long=${group.first()}") + AB),
                        "`ls $long=bogus` lists '${group.joinToString("', '")}' as one value",
                    ),
                )
            }
        }
        val prefixes = words.flatMap { word -> (1 until word.length).map(word::take) }.distinct() - words.toSet()
        for (prefix in prefixes) {
            val read = argmatch(prefix, groups)
            if (read == null) {
                val line = listOf("$long=$prefix") + AB
                add(Cell(long, CHOICE_AMBIGUOUS, line, Outcome.Rejects, "$ambiguous '$prefix'"))
            } else {
                val line = listOf("$long=$read") + AB
                val reached = words.filter { it.startsWith(prefix) }
                val form = if (read in reached) CHOICE_ABBREVIATED else SYNONYM_ABBREVIATED
                add(Cell(long, form, listOf("$long=$prefix") + AB, Outcome.SameAs(line), "argmatch reads it as $read"))
            }
        }
        val capitals = words.first().uppercase()
        add(Cell(long, CHOICE_CAPITALS, listOf("$long=$capitals") + AB, Outcome.Rejects, "$invalid '$capitals'"))
        add(Cell(long, CHOICE_UNKNOWN, listOf("$long=bogus") + AB, Outcome.Rejects, "$invalid 'bogus'"))
        add(Cell(long, CHOICE_EMPTY, listOf("$long=") + AB, Outcome.Rejects, "$ambiguous ''"))
        add(Cell(long, CHOICE_UNKNOWN, listOf("$long=${words.first()}=") + AB, Outcome.Rejects, "$invalid"))
    }

    private fun repeatCells(
        input: Input,
        long: String,
        kind: Kind.Value,
    ): List<Cell> {
        val values = kind.groups?.map { it.first() }?.take(2) ?: listOf(kind.sample, kind.second)
        val (first, second) = values
        val twice = input.bind(input.valueBound(first), second)
        val because = when (long) {
            "--classify" -> "`ls -d --classify=always --classify=never sub` prints sub/"
            "--color" -> "`ls -d --color=always --color=never sub` is plain"
            "--time-style" -> "`ls -l --time-style=full-iso --time-style=long-iso a` shows 2003-01-01 00:00"
            "--block-size" -> "`ls -s --block-size=1K --block-size=M big` prints 3M"
            "--width", "--tabsize" -> "`ls -x -w 17 -w 18` lists four per line"
            "--ignore", "--hide" -> "`ls -m -I c* -I a*` hides both"
            else -> "`ls -m --sort=size --sort=time` lists by time"
        }
        return listOf(Cell(long, REPEATED, listOf("$long=$first", "$long=$second") + AB, Outcome.Binds(twice), because))
    }

    private fun shortValueCells(
        input: Input,
        short: Char,
        sample: String,
    ): List<Cell> {
        val key = "-$short"
        val bound = Outcome.Binds(input.valueBound(sample))
        val partner = input.partner()
        val clustered = Outcome.Binds(input.bind(partner.flagBound, sample))
        val cluster = "-${partner.shorts.first()}$short"
        val equals = if (input.long == "--ignore") Outcome.Binds(input.valueBound("=$sample")) else Outcome.Rejects
        return listOf(
            Cell(key, GLUED, listOf("$key$sample") + AB, bound, "`ls -x -w17` lists three per line"),
            Cell(key, SHORT_SPACED, listOf(key, sample) + AB, bound, "`ls -x -w 17` lists three per line"),
            Cell(key, CLUSTER_GLUED, listOf("$cluster$sample") + AB, clustered, "`ls -xw17` lists three per line"),
            Cell(key, CLUSTER_SPACED, listOf(cluster, sample) + AB, clustered, "`ls -xw 17` lists three per line"),
            Cell(key, SHORT_EQUALS, listOf("$key=$sample") + AB, equals, "`ls -w=17`: invalid line width '=17'"),
            Cell(key, MISSING_VALUE, listOf(key), Outcome.Rejects, "ls: option requires an argument -- '$short'"),
        )
    }

    /**
     * Every prefix of an ls long that getopt calls ambiguous, each refused: `ls --r a` is "option '--r' is ambiguous;
     * possibilities: '--reverse' '--recursive'".
     */
    val AMBIGUOUS_PREFIXES: List<Cell> by lazy {
        LS_LONGS
            .flatMap { long -> (3 until long.length).map(long::take) }
            .distinct()
            .filter(::isAmbiguousLong)
            .map { prefix ->
                val reached = LS_LONGS.filter { it.startsWith(prefix) }
                val because = "ls: option '$prefix' is ambiguous; possibilities: ${reached.joinToString(" ")}"
                Cell(prefix, "an ambiguous prefix", listOf(prefix) + AB, Outcome.Rejects, because)
            }
    }
}
