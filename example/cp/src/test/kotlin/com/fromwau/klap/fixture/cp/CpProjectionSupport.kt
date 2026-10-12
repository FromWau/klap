package com.fromwau.klap.fixture.cp

import com.fromwau.kern.result.Result
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import kotlin.test.fail

/*
 * The cells of the projection tests, generated from the inputs real cp 9.12 documents (`INPUTS` in CpModel.kt). Every
 * expected record is how real cp reads the line, run inside a throwaway directory unless a cell's `because` says it
 * was read from `cp --help` or the info manual.
 */

/** The projection cells of cp: one argv line each and what real cp makes of it. */
internal object CpProjectionSupport {

    /** [argv] binds [expected], or is refused by cp [because] where [expected] is null. */
    data class Cell(
        val input: String,
        val form: String,
        val argv: List<String>,
        val expected: CpInputs?,
        val because: String,
    )

    /** [line] binds the same record as [sameAs], both accepted: two spellings real cp reads as one setting. */
    data class Same(
        val line: List<String>,
        val sameAs: List<String>,
        val because: String,
    )

    /** [line] and [other] bind different records, both accepted: cp reads them differently. */
    data class Differ(
        val line: List<String>,
        val other: List<String>,
        val because: String,
    )

    /**
     * Under [environment], [line] binds what [sameAs] binds with no environment, or is refused where [sameAs] is
     * null: cp reads its backup defaults from `VERSION_CONTROL` and `SIMPLE_BACKUP_SUFFIX`.
     */
    data class UnderEnvironment(
        val environment: Map<String, String>,
        val line: List<String>,
        val sameAs: List<String>?,
        val because: String,
    )

    fun ParitySuite<CpInputs>.check(cell: Cell) {
        val argv = cell.argv.toTypedArray()
        val expected = cell.expected
        if (expected == null) {
            rejects(argv = argv, because = cell.because)
        } else {
            binds(argv = argv, expected = expected, because = cell.because)
        }
    }

    fun ParitySuite<CpInputs>.checkAll(cells: List<Cell>) = checkCells(cells) { check(it) }

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

    fun checkDiffer(cells: List<Differ>) = checkCells(cells) { cell ->
        val record = bound(cell.line)
        if (record == bound(cell.other)) {
            fail("expected `${shown(cell.line)}` and `${shown(cell.other)}` to bind apart (${cell.because})")
        }
    }

    fun checkUnder(cells: List<UnderEnvironment>) = checkCells(cells) { cell ->
        val shownLine = "${cell.environment.entries.joinToString(" ")} ${shown(cell.line)}"
        val sameAs = cell.sameAs
        if (sameAs == null) {
            val parsed = cpCli(cell.environment).parse(cell.line)
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

    private fun bound(line: List<String>, environment: Map<String, String> = emptyMap()): CpInputs =
        when (val parsed = cpCli(environment).parse(line)) {
            is Result.Error -> fail("expected `${shown(line)}` to bind, but it was rejected: ${parsed.error}")
            is Result.Success -> parsed.value ?: fail("expected `${shown(line)}` to bind, but no command ran")
        }

    private fun shown(line: List<String>): String =
        (listOf("cp") + line.map { if (it.isEmpty() || ' ' in it) "\"$it\"" else it }).joinToString(" ")

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
    const val LIST = "a list of several names"
    const val LIST_MALFORMED = "a list with an empty or spaced name"
    const val REPEATED = "the value given twice"

    private val AB = listOf("a", "b")

    /** The one optional value whose bare and empty forms name cp's default control, so no generated cell binds it. */
    private const val DEFAULT_CONTROL_OPTION = "--backup"

    private fun Input.cell(
        form: String,
        argv: List<String>,
        expected: CpInputs?,
        because: String = "cp --help: $name",
    ) = Cell(name, form, argv, expected, because)

    private fun Input.bound(value: Any = true): CpInputs = A_TO_B.bind(value)

    private fun CpInputs.with(input: Input, value: Any = true): CpInputs = input.bind(this, value)

    private fun Kind.Value.wrap(value: String): Any = if (list) listOf(value) else value

    /** The word that names [input] most briefly: its first short, or its long name. */
    private fun Input.word(): String = shorts.firstOrNull()?.let { "-$it" } ?: long!!

    /** Every spelling of every input real cp has, in every position, each with what it binds or that cp refuses it. */
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
            add(input.cell(LONG_REPEATED, listOf(long, long) + AB, input.bound(), "`cp -v -v a b` exits 0"))
            add(input.cell(LONG_ATTACHED, listOf("$long=x") + AB, null, "cp: option '$long' doesn't allow an argument"))
            abbreviationOf(long)?.let { add(input.cell(ABBREVIATED, listOf(it) + AB, input.bound(), "getopt_long")) }
        }
        for (short in input.shorts) {
            add(input.cell(SHORT, listOf("-$short") + AB, input.bound()))
            add(input.cell(SHORT_REPEATED, listOf("-$short$short") + AB, input.bound(), "`cp -vv a b` exits 0"))
            val partner = inputFor(if (short == 'v') 'x' else 'v')!!
            add(input.cell(CLUSTER, listOf("-${partner.shorts.first()}$short") + AB, partner.bound().with(input)))
        }
    }

    /** [word] between and after the operands binds as before them; after `--` it is the first operand. */
    private fun positionCells(
        input: Input,
        word: String,
        bound: CpInputs,
    ): List<Cell> = listOf(
        input.cell(BETWEEN, listOf("a", word, "b"), bound, "`cp a -v b d` copies both, verbosely"),
        input.cell(AFTER, listOf("a", "b", word), bound, "`cp a b d -v` copies both, verbosely"),
        input.cell(
            AFTER_END,
            listOf("--", word, "b"),
            NOTHING_BOUND.copy(sources = listOf(word), dest = "b"),
            "`cp a -- -v` makes a file named -v",
        ),
    )

    private fun valueCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long!!
        val sample = kind.sample
        for (value in kind.choices ?: listOf(sample)) {
            add(input.cell(ATTACHED, listOf("$long=$value") + AB, input.bound(kind.wrap(value))))
            if (!kind.optional) add(input.cell(SPACED, listOf(long, value) + AB, input.bound(kind.wrap(value))))
        }
        abbreviationOf(long)?.let { prefix ->
            add(input.cell(ABBREVIATED, listOf("$prefix=$sample") + AB, input.bound(kind.wrap(sample)), "getopt_long"))
            if (!kind.optional) {
                add(input.cell(ABBREVIATED_SPACED, listOf(prefix, sample) + AB, input.bound(kind.wrap(sample))))
            }
        }
        val bare = kind.bare
        if (bare == null) {
            add(input.cell(MISSING, AB + long, null, "cp: option '$long' requires an argument"))
        } else if (long != DEFAULT_CONTROL_OPTION) {
            add(input.cell(BARE, listOf(long) + AB, A_TO_B.bare(), "cp --help: $long[=...]"))
            add(
                input.cell(
                    BARE_THEN_WORD,
                    listOf(long, sample) + AB,
                    A_TO_B.copy(sources = listOf(sample, "a")).bare(),
                    "`cp $long $sample a b`: target 'b': Not a directory, so $sample was a source",
                ),
            )
        }
        for (short in input.shorts) {
            val bound = input.bound(sample)
            val verbose = input("--verbose").bound().with(input, sample)
            add(input.cell(SPACED, listOf("-$short", sample) + AB, bound))
            add(input.cell(GLUED, listOf("-$short$sample") + AB, bound, "`cp -tdir a` exits 0"))
            add(input.cell(CLUSTER_VALUE, listOf("-v$short$sample") + AB, verbose, "`cp -vtd a` exits 0"))
            add(input.cell(CLUSTER_VALUE, listOf("-v$short", sample) + AB, verbose, "`cp -vt d a` exits 0"))
            add(input.cell(MISSING, AB + "-$short", null, "cp: option requires an argument -- '$short'"))
        }
        addAll(positionCells(input, "$long=$sample", input.bound(kind.wrap(sample))))
        if (kind.choices == null) addAll(freeTextCells(input, kind))
        kind.choices?.let { choices -> addAll(choiceCells(input, kind, choices)) }
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
            add(input.cell(DASH_VALUE, listOf(option, "-x") + AB, input.bound("-x"), "`cp -t -x a`: target '-x'"))
            add(input.cell(END_VALUE, listOf(option, "--") + AB, input.bound("--"), "`cp -b -S -- a b` makes b--"))
            add(
                input.cell(
                    BUILTIN_VALUE,
                    listOf(option, "--help") + AB,
                    input.bound("--help"),
                    "`cp -b -S --help a b` makes b--help",
                ),
            )
            add(input.cell(EMPTY_VALUE, listOf(option, "") + AB, input.bound(""), "`cp -t \"\" a`: target ''"))
        }
        add(input.cell(DASH_VALUE, listOf("$long=-x") + AB, input.bound("-x"), "`cp --context=-x a c` exits 0"))
        add(input.cell(EQUALS_VALUE, listOf("$long=a=b") + AB, input.bound("a=b"), "`cp -b --suffix=a=b a b`: ba=b"))
        add(input.cell(EMPTY_VALUE, listOf("$long=") + AB, input.bound(""), "`cp --target-directory= a`: target ''"))
        for (short in input.shorts) {
            add(input.cell(EQUALS_VALUE, listOf("-$short=x") + AB, input.bound("=x"), "`cp -b -S=x a b` makes b=x"))
            add(
                input.cell(
                    CLUSTER_SWALLOW,
                    listOf("-${short}vb") + AB,
                    input.bound("vb"),
                    "`cp -b -Svb a b` makes bvb and prints nothing",
                ),
            )
        }
    }

    private fun choiceCells(
        input: Input,
        kind: Kind.Value,
        choices: List<String>,
    ): List<Cell> = buildList {
        val long = input.long!!
        val invalid = "cp: invalid argument"
        for (value in choices) {
            choiceAbbreviation(value, choices)?.let { prefix ->
                val why = "`cp $long=$prefix` reaches '$value' through argmatch"
                add(input.cell(CHOICE_ABBREVIATED, listOf("$long=$prefix") + AB, input.bound(kind.wrap(value)), why))
            }
        }
        ambiguousChoice(choices)?.let { prefix ->
            add(input.cell(CHOICE_AMBIGUOUS, listOf("$long=$prefix") + AB, null, "cp: ambiguous argument '$prefix'"))
        }
        val capitals = choices.first().uppercase()
        add(input.cell(CHOICE_CAPITALS, listOf("$long=$capitals") + AB, null, "$invalid '$capitals' for '$long'"))
        add(input.cell(CHOICE_UNKNOWN, listOf("$long=bogus") + AB, null, "$invalid 'bogus' for '$long'"))
        if (long != DEFAULT_CONTROL_OPTION) {
            add(input.cell(CHOICE_EMPTY, listOf("$long=") + AB, null, "cp: ambiguous argument '' for '$long'"))
        }
        if (kind.list) {
            add(input.cell(LIST, listOf("$long=mode,links") + AB, input.bound(listOf("mode", "links"))))
            add(input.cell(LIST, listOf("$long=mode,all") + AB, input.bound(listOf("mode", "all"))))
            for (malformed in listOf("mode, links", "mode,,links", "mode,")) {
                add(input.cell(LIST_MALFORMED, listOf("$long=$malformed") + AB, null, "$invalid in '$malformed'"))
            }
        }
    }

    private fun repeatCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long!!
        val (first, second) = kind.choices?.take(2) ?: listOf("x", "y")
        val twice = listOf("$long=$first", "$long=$second") + AB
        when {
            kind.path -> add(input.cell(REPEATED, twice, null, "cp: multiple target directories specified"))
            kind.list -> add(
                input.cell(
                    REPEATED,
                    twice,
                    input.bound(listOf(first)).with(input, listOf(second)),
                    "`cp --preserve=mode --preserve=timestamps a out` keeps both",
                ),
            )
            else -> add(input.cell(REPEATED, twice, input.bound(second), "`cp -b -S .1 -S .2 a b` makes b.2"))
        }
        // `cp --update=none --update a b` replaces an older b; `cp --reflink=never --reflink a b` fails to clone.
        val earlier = mapOf("--update" to "none", "--reflink" to "never")[long]
        val bare = kind.bare
        if (earlier != null && bare != null) {
            add(input.cell(BARE_AFTER_VALUE, listOf("$long=$earlier", long) + AB, input.bound(earlier).bare()))
        }
    }
}
