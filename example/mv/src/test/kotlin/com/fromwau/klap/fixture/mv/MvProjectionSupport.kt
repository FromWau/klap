package com.fromwau.klap.fixture.mv

import com.fromwau.kern.result.Result
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.parse
import kotlin.test.fail

/*
 * The cells of the projection tests, generated from the inputs real mv 9.12 documents (`INPUTS` in MvModel.kt). Every
 * expected record is how real mv reads the line, run inside a throwaway directory unless a cell's `because` says it
 * was read from `mv --help`.
 */

/** The projection cells of mv: one argv line each and what real mv makes of it. */
internal object MvProjectionSupport {

    /** [argv] binds [expected], or is refused by mv [because] where [expected] is null. */
    data class Cell(
        val input: String,
        val form: String,
        val argv: List<String>,
        val expected: MvInputs?,
        val because: String,
    )

    /** [line] binds the same record as [sameAs], both accepted: two spellings real mv reads as one setting. */
    data class Same(
        val line: List<String>,
        val sameAs: List<String>,
        val because: String,
    )

    /**
     * Under [environment], [line] binds what [sameAs] binds with no environment, or is refused where [sameAs] is
     * null: mv reads its backup defaults from `VERSION_CONTROL` and `SIMPLE_BACKUP_SUFFIX`.
     */
    data class UnderEnvironment(
        val environment: Map<String, String>,
        val line: List<String>,
        val sameAs: List<String>?,
        val because: String,
    )

    fun ParitySuite<MvInputs>.check(cell: Cell) {
        val argv = cell.argv.toTypedArray()
        val expected = cell.expected
        if (expected == null) {
            rejects(argv = argv, because = cell.because)
        } else {
            binds(argv = argv, expected = expected, because = cell.because)
        }
    }

    fun ParitySuite<MvInputs>.checkAll(cells: List<Cell>) = checkCells(cells) { check(it) }

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

    fun checkUnder(cells: List<UnderEnvironment>) = checkCells(cells) { cell ->
        val shownLine = "${cell.environment.entries.joinToString(" ")} ${shown(cell.line)}"
        val sameAs = cell.sameAs
        if (sameAs == null) {
            val parsed = mvCli(cell.environment).parse(cell.line)
            if (parsed is Result.Success) fail("expected `$shownLine` to be refused (${cell.because})")
        } else {
            val record = bound(cell.line, cell.environment)
            val other = bound(sameAs)
            if (record != other) {
                fail(
                    "expected `$shownLine` to bind what `${shown(sameAs)}` binds (${cell.because})\n" +
                        "  this:  $record\n  other: $other",
                )
            }
        }
    }

    private fun bound(line: List<String>, environment: Map<String, String> = emptyMap()): MvInputs =
        when (val parsed = mvCli(environment).parse(line)) {
            is Result.Error -> fail("expected `${shown(line)}` to bind, but it was rejected: ${parsed.error}")
            is Result.Success -> parsed.value ?: fail("expected `${shown(line)}` to bind, but no command ran")
        }

    private fun shown(line: List<String>): String =
        (listOf("mv") + line.map { if (it.isEmpty() || ' ' in it) "\"$it\"" else it }).joinToString(" ")

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
    const val GLUED = "a value glued to the short"
    const val CLUSTER_VALUE = "a value after the short ending a cluster"
    const val CLUSTER_SWALLOW = "the rest of a cluster after a value-taking short"
    const val MISSING = "the value missing at the end of the line"
    const val DASH_VALUE = "a value word that starts with a dash"
    const val END_VALUE = "a value word that is exactly --"
    const val BUILTIN_VALUE = "a value word spelling --help"
    const val EQUALS_VALUE = "a value holding an equals sign"
    const val EMPTY_VALUE = "an empty free text value"
    const val BARE = "an optional value left out"
    const val BARE_THEN_WORD = "an optional value left out before a word"
    const val BARE_AFTER_VALUE = "an optional value left out after a value"
    const val CHOICE_ABBREVIATED = "a choice abbreviated"
    const val CHOICE_AMBIGUOUS = "an ambiguous choice prefix"
    const val CHOICE_CAPITALS = "a choice in capitals"
    const val CHOICE_UNKNOWN = "a value outside the choices"
    const val CHOICE_EMPTY = "an empty choice"
    const val REPEATED = "the value given twice"

    private val AB = listOf("a", "b")

    private fun Input.cell(
        form: String,
        argv: List<String>,
        expected: MvInputs?,
        because: String = "mv --help: $name",
    ) = Cell(name, form, argv, expected, because)

    private fun Input.bound(value: Any = true): MvInputs = A_TO_B.bind(value)

    private fun MvInputs.with(input: Input, value: Any = true): MvInputs = input.bind(this, value)

    /** The word that names [input] most briefly: its first short, or its long name. */
    private fun Input.word(): String = shorts.firstOrNull()?.let { "-$it" } ?: long!!

    /** Every spelling of every input real mv has, in every position, each with what it binds or that mv refuses it. */
    val ALL: List<Cell> by lazy { INPUTS.flatMap(::cellsOf) }

    /** The cells of the inputs [names] in one of [forms], or in any form when none is given. */
    fun cells(
        names: Collection<String>,
        vararg forms: String,
    ): List<Cell> = ALL.filter { it.input in names && (forms.isEmpty() || it.form in forms) }

    private fun cellsOf(input: Input): List<Cell> = when (val kind = input.kind) {
        Kind.Flag -> flagCells(input) + positionCells(input, input.word(), input.bound())
        is Kind.Value -> valueCells(input, kind)
    }

    private fun flagCells(input: Input): List<Cell> = buildList {
        input.long?.let { long ->
            add(input.cell(LONG, listOf(long) + AB, input.bound()))
            add(input.cell(LONG_REPEATED, listOf(long, long) + AB, input.bound(), "`mv --verbose --verbose a b` runs"))
            add(input.cell(LONG_ATTACHED, listOf("$long=x") + AB, null, "mv: option '$long' doesn't allow an argument"))
            abbreviationOf(long)?.let { add(input.cell(ABBREVIATED, listOf(it) + AB, input.bound(), "getopt_long")) }
        }
        for (short in input.shorts) {
            add(input.cell(SHORT, listOf("-$short") + AB, input.bound()))
            add(input.cell(SHORT_REPEATED, listOf("-$short$short") + AB, input.bound(), "`mv -vv a b` runs"))
            val partner = inputFor(if (short == 'v') 'b' else 'v')!!
            add(input.cell(CLUSTER, listOf("-${partner.shorts.first()}$short") + AB, partner.bound().with(input)))
        }
    }

    /** [word] between and after the operands binds as before them; after `--` it is the first operand. */
    private fun positionCells(
        input: Input,
        word: String,
        bound: MvInputs,
    ): List<Cell> = listOf(
        input.cell(BETWEEN, listOf("a", word, "b"), bound, "`mv a -t d b` moves both into d"),
        input.cell(AFTER, listOf("a", "b", word), bound, "`mv a b -T` and `mv a b -t d` read the option"),
        input.cell(
            AFTER_END,
            listOf("--", word, "b"),
            NOTHING_BOUND.copy(sources = listOf(word), dest = "b"),
            "`mv a -- -v` makes a file named -v",
        ),
    )

    private fun valueCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long!!
        val sample = kind.sample
        for (value in kind.choices ?: listOf(sample)) {
            add(input.cell(ATTACHED, listOf("$long=$value") + AB, input.bound(value)))
            if (!kind.optional) add(input.cell(SPACED, listOf(long, value) + AB, input.bound(value)))
        }
        abbreviationOf(long)?.let { prefix ->
            add(input.cell(ABBREVIATED, listOf("$prefix=$sample") + AB, input.bound(sample), "getopt_long"))
            if (!kind.optional) add(input.cell(ABBREVIATED_SPACED, listOf(prefix, sample) + AB, input.bound(sample)))
        }
        val bare = kind.bare
        if (bare == null) {
            add(input.cell(MISSING, AB + long, null, "mv: option '$long' requires an argument"))
        } else {
            add(input.cell(BARE, listOf(long) + AB, A_TO_B.bare(), "mv --help: $long[=...]"))
            add(
                input.cell(
                    BARE_THEN_WORD,
                    listOf(long, sample) + AB,
                    A_TO_B.copy(sources = listOf(sample, "a")).bare(),
                    "`mv --backup numbered a b`: target 'b': Not a directory, so the word was a source",
                ),
            )
        }
        for (short in input.shorts) {
            val bound = input.bound(sample)
            val verbose = input("--verbose").bound().with(input, sample)
            add(input.cell(SPACED, listOf("-$short", sample) + AB, bound))
            add(input.cell(GLUED, listOf("-$short$sample") + AB, bound, "`mv -td a` and `mv -bS.z a b` run"))
            add(input.cell(CLUSTER_VALUE, listOf("-v$short$sample") + AB, verbose, "`mv -bS.z a b` makes b.z"))
            add(input.cell(CLUSTER_VALUE, listOf("-v$short", sample) + AB, verbose, "getopt: the next word"))
            add(input.cell(MISSING, AB + "-$short", null, "mv: option requires an argument -- '$short'"))
        }
        addAll(positionCells(input, "$long=$sample", input.bound(sample)))
        if (kind.choices == null) addAll(freeTextCells(input, kind))
        kind.choices?.let { choices -> addAll(choiceCells(input, choices)) }
        addAll(repeatCells(input, kind))
    }

    /** The edges of a free text value: a dash, `--`, a built-in's name, `=` and the empty word, read by getopt. */
    private fun freeTextCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long!!
        val spellings = buildList {
            if (!kind.optional) add(long)
            input.shorts.forEach { add("-$it") }
        }
        for (option in spellings) {
            add(input.cell(DASH_VALUE, listOf(option, "-x") + AB, input.bound("-x"), "`mv -S -x -b a b` makes b-x"))
            add(input.cell(END_VALUE, listOf(option, "--") + AB, input.bound("--"), "`mv -S -- -b a b` makes b--"))
            add(
                input.cell(
                    BUILTIN_VALUE,
                    listOf(option, "--help") + AB,
                    input.bound("--help"),
                    "`mv -S --help -b a b` makes b--help",
                ),
            )
            add(input.cell(EMPTY_VALUE, listOf(option, "") + AB, input.bound(""), "`mv -t \"\" a`: target ''"))
        }
        add(input.cell(DASH_VALUE, listOf("$long=-x") + AB, input.bound("-x"), "getopt: everything after the ="))
        add(input.cell(EQUALS_VALUE, listOf("$long=a=b") + AB, input.bound("a=b"), "`mv --suffix=a=b -b a b`: ba=b"))
        add(input.cell(EMPTY_VALUE, listOf("$long=") + AB, input.bound(""), "`mv --target-directory= a`: target ''"))
        for (short in input.shorts) {
            add(input.cell(EQUALS_VALUE, listOf("-$short=x") + AB, input.bound("=x"), "`mv -b -S= a b` makes b="))
            add(
                input.cell(
                    CLUSTER_SWALLOW,
                    listOf("-${short}vb") + AB,
                    input.bound("vb"),
                    "`mv -Svb a b` makes bvb and prints nothing",
                ),
            )
        }
    }

    private fun choiceCells(
        input: Input,
        choices: List<String>,
    ): List<Cell> = buildList {
        val long = input.long!!
        val invalid = "mv: invalid argument"
        for (value in choices) {
            choiceAbbreviation(value, choices)?.let { prefix ->
                val why = "`mv $long=$prefix` reaches '$value' through argmatch"
                add(input.cell(CHOICE_ABBREVIATED, listOf("$long=$prefix") + AB, input.bound(value), why))
            }
        }
        ambiguousChoice(choices)?.let { prefix ->
            add(input.cell(CHOICE_AMBIGUOUS, listOf("$long=$prefix") + AB, null, "mv: ambiguous argument '$prefix'"))
        }
        val capitals = choices.first().uppercase()
        add(input.cell(CHOICE_CAPITALS, listOf("$long=$capitals") + AB, null, "$invalid '$capitals' for '$long'"))
        add(input.cell(CHOICE_UNKNOWN, listOf("$long=bogus") + AB, null, "$invalid 'bogus' for '$long'"))
        if (long == "--backup") {
            // gnulib reads an empty control as none given: `mv --backup= a b` beside b.~1~ made b.~2~, as -b does.
            val existing = A_TO_B.copy(backup = BackupControl.Existing)
            add(input.cell(CHOICE_EMPTY, listOf("$long=") + AB, existing, "default control"))
        } else {
            add(input.cell(CHOICE_EMPTY, listOf("$long=") + AB, null, "mv: ambiguous argument '' for '$long'"))
        }
    }

    private fun repeatCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long!!
        val (first, second) = kind.choices?.take(2) ?: listOf("x", "y")
        val twice = listOf("$long=$first", "$long=$second") + AB
        if (kind.path) {
            add(input.cell(REPEATED, twice, null, "mv: multiple target directories specified"))
        } else {
            add(input.cell(REPEATED, twice, input.bound(second), "`mv -S .x -S .y a b` makes b.y"))
        }
        // `mv --update=none --update a b` replaced the older b; `mv --backup=simple --backup a b` made b~.
        val earlier = mapOf("--update" to "none", "--backup" to "simple")[long]
        val bare = kind.bare
        if (earlier != null && bare != null) {
            add(input.cell(BARE_AFTER_VALUE, listOf("$long=$earlier", long) + AB, input.bound(earlier).bare()))
        }
    }
}
