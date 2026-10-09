package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.ParitySuite

/** One argv line and what real cp makes of it: the record it binds, or null when cp refuses the line. */
internal data class ParseCell(
    val input: Input,
    val form: String,
    val argv: List<String>,
    val expected: CpInputs?,
    val because: String,
)

internal fun ParitySuite<CpInputs>.check(cell: ParseCell) {
    val argv = cell.argv.toTypedArray()
    val expected = cell.expected
    if (expected == null) rejects(argv = argv, because = cell.because) else binds(argv = argv, expected = expected)
}

// --- what a cell spells, in cp's terms ---

internal const val LONG = "the long name"
internal const val LONG_REPEATED = "the long name twice"
internal const val LONG_ATTACHED = "a flag's long name with a value attached"
internal const val ABBREVIATED = "the shortest unique abbreviation"
internal const val SHORT = "the short letter"
internal const val SHORT_REPEATED = "the short letter doubled"
internal const val CLUSTER = "the short letter in a cluster"
internal const val ATTACHED = "a value attached"
internal const val SPACED = "a value in the next word"
internal const val GLUED = "a value glued to the short"
internal const val CLUSTER_VALUE = "a value after the short ending a cluster"
internal const val MISSING = "the value missing at the end of the line"
internal const val BARE = "an optional value left out"
internal const val BARE_THEN_WORD = "an optional value left out before a word"
internal const val BARE_AFTER_VALUE = "an optional value left out after a value"
internal const val EMPTY = "an empty attached value"
internal const val CHOICE_ABBREVIATED = "a choice abbreviated"
internal const val CHOICE_AMBIGUOUS = "an ambiguous choice prefix"
internal const val CHOICE_CAPITALS = "a choice in capitals"
internal const val CHOICE_UNKNOWN = "a value outside the choices"
internal const val LIST = "a list of several names"
internal const val LIST_MALFORMED = "a list with an empty or spaced name"
internal const val REPEATED = "the value given twice"

private val AB = listOf("a", "b")

/** A cell binding [argv] to [expected] or, with [expected] null, refused by cp [because]. */
private fun Input.cell(
    form: String,
    argv: List<String>,
    expected: CpInputs?,
    because: String = "cp --help: $name",
) = ParseCell(this, form, argv, expected, because)

private fun Input.bound(value: Any = true): CpInputs = A_TO_B.bind(value)

/** Every spelling of [input] real cp accepts or refuses, each with what it binds. */
internal fun cellsOf(input: Input): List<ParseCell> = when (val kind = input.kind) {
    Kind.Flag -> flagCells(input)
    is Kind.Value -> valueCells(input, kind)
}

private fun flagCells(input: Input): List<ParseCell> = buildList {
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
        add(input.cell(CLUSTER, listOf("-${partner.shorts.first()}$short") + AB, partner.bound().input(input)))
    }
}

private fun CpInputs.input(input: Input, value: Any = true): CpInputs = input.bind(this, value)

private fun Kind.Value.wrap(value: String): Any = if (list) listOf(value) else value

private fun valueCells(
    input: Input,
    kind: Kind.Value,
): List<ParseCell> = buildList {
    val long = input.long!!
    val sample = kind.sample
    val values = kind.choices ?: listOf(sample)
    for (value in values) {
        add(input.cell(ATTACHED, listOf("$long=$value") + AB, input.bound(kind.wrap(value))))
        if (!kind.optional) add(input.cell(SPACED, listOf(long, value) + AB, input.bound(kind.wrap(value))))
    }
    abbreviationOf(long)?.let { prefix ->
        add(input.cell(ABBREVIATED, listOf("$prefix=$sample") + AB, input.bound(kind.wrap(sample)), "getopt_long"))
    }
    val bare = kind.bare
    if (bare != null) {
        add(input.cell(BARE, listOf(long) + AB, A_TO_B.bare(), "cp --help: $long[=...]"))
        add(
            input.cell(
                BARE_THEN_WORD,
                listOf(long, sample) + AB,
                A_TO_B.copy(sources = listOf(sample, "a")).bare(),
                "`cp $long $sample src dst`: target 'dst': Not a directory, so $sample was a source",
            ),
        )
    } else {
        add(input.cell(MISSING, AB + long, null, "cp: option '$long' requires an argument"))
    }
    for (short in input.shorts) {
        val bound = input.bound(sample)
        val verbose = input("--verbose").bound().input(input, sample)
        add(input.cell(SPACED, listOf("-$short", sample) + AB, bound))
        add(input.cell(GLUED, listOf("-$short$sample") + AB, bound, "`cp -tdir src` exits 0"))
        add(input.cell(CLUSTER_VALUE, listOf("-v$short$sample") + AB, verbose, "`cp -vtdir src` exits 0"))
        add(input.cell(CLUSTER_VALUE, listOf("-v$short", sample) + AB, verbose, "`cp -vt dir src` exits 0"))
        add(input.cell(MISSING, AB + "-$short", null, "cp: option requires an argument -- '$short'"))
    }
    kind.choices?.let { choices -> addAll(choiceCells(input, kind, choices)) }
    addAll(repeatCells(input, kind))
}

private fun choiceCells(
    input: Input,
    kind: Kind.Value,
    choices: List<String>,
): List<ParseCell> = buildList {
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
    // `cp --backup= a b` makes a simple backup, as the bare form does; every other choice calls '' ambiguous.
    val empty = if (long == "--backup") A_TO_B.(kind.bare!!)() else null
    add(input.cell(EMPTY, listOf("$long=") + AB, empty, "`cp $long=`"))
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
): List<ParseCell> = buildList {
    val long = input.long!!
    val (first, second) = kind.choices?.take(2) ?: listOf("x", "y")
    val twice = listOf("$long=$first", "$long=$second") + AB
    when {
        kind.path -> add(input.cell(REPEATED, twice, null, "cp: multiple target directories specified"))
        // `cp --preserve=mode --preserve=timestamps src out` keeps both the mode and the timestamps.
        kind.list -> add(input.cell(REPEATED, twice, input.bound(listOf(first)).input(input, listOf(second))))
        else -> add(input.cell(REPEATED, twice, input.bound(second), "the later value is the one cp uses"))
    }
    if (long == "--backup" || long == "--update") {
        val bare = kind.bare!!
        val sample = kind.sample
        // `cp --backup=numbered --backup` makes `dst.~1~`; `cp --update=none --update` replaces an older file.
        add(input.cell(BARE_AFTER_VALUE, listOf("$long=$sample", long) + AB, input.bound(sample).bare()))
    }
}

/** The general and operand lines of real cp, each with what cp makes of it. */
internal val OPERAND_CELLS: List<Pair<List<String>, CpInputs?>> = listOf(
    listOf("a", "b") to A_TO_B,
    listOf("a", "b", "c") to NOTHING_BOUND.copy(sources = listOf("a", "b"), dest = "c"),
    listOf("-t", "d", "a") to NOTHING_BOUND.copy(targetDirectory = "d", sources = listOf("a")),
    listOf("a", "-t", "d") to NOTHING_BOUND.copy(targetDirectory = "d", sources = listOf("a")),
    listOf("-T", "a", "b") to A_TO_B.copy(noTargetDirectory = true),
    listOf("a", "b", "-v") to A_TO_B.copy(verbose = true),
    listOf("--", "-foo", "bar") to NOTHING_BOUND.copy(sources = listOf("-foo"), dest = "bar"),
    listOf("a", "--", "-v") to NOTHING_BOUND.copy(sources = listOf("a"), dest = "-v"),
    listOf("-t", "d", "--", "-x") to NOTHING_BOUND.copy(targetDirectory = "d", sources = listOf("-x")),
    listOf("-", "b") to NOTHING_BOUND.copy(sources = listOf("-"), dest = "b"),
    emptyList<String>() to null,
    listOf("a") to null,
    listOf("-t", "d") to null,
    listOf("-T", "a") to null,
    listOf("--") to null,
)

/** Every cell of every input. */
internal val ALL_CELLS: List<ParseCell> by lazy { INPUTS.flatMap(::cellsOf) }

/** The cells of the inputs [names] spelled in one of [forms], or in any form when none is given. */
internal fun cells(
    names: Collection<String>,
    vararg forms: String,
): List<ParseCell> = ALL_CELLS.filter { it.input.name in names && (forms.isEmpty() || it.form in forms) }

/** The cells spelling `--recursive` as `-r`, or abbreviating it to a prefix `--recursive-r` shares. */
internal val LOWERCASE_R_CELLS: List<ParseCell> by lazy {
    cells(listOf("--recursive")).filter { cell ->
        val word = cell.argv.first()
        (!word.startsWith("--") && 'r' in word) || cell.form == ABBREVIATED
    }
}

internal val U_CELLS: List<ParseCell> by lazy { cells(listOf("-u")) }

internal val CHOICE_CAPITAL_CELLS: List<ParseCell> by lazy {
    cells(listOf("--backup", "--reflink", "--sparse", "--update"), CHOICE_CAPITALS)
}

private val ATTRIBUTE_LISTS = listOf("--preserve", "--no-preserve")

internal val ATTRIBUTE_NAME_CELLS: List<ParseCell> by lazy {
    cells(ATTRIBUTE_LISTS, CHOICE_ABBREVIATED, CHOICE_CAPITALS, CHOICE_UNKNOWN, EMPTY, LIST_MALFORMED)
}

internal val ATTRIBUTE_REPEAT_CELLS: List<ParseCell> by lazy { cells(ATTRIBUTE_LISTS, REPEATED) }

internal val OPTIONAL_VALUE_CELLS: List<ParseCell> by lazy {
    cells(listOf("--preserve", "--context"), BARE, BARE_THEN_WORD)
}

internal val BACKUP_CONTROL_CELLS: List<ParseCell> by lazy { cells(listOf("--backup"), EMPTY, BARE_AFTER_VALUE) }

internal val TARGET_DIRECTORY_TWICE_CELLS: List<ParseCell> by lazy {
    cells(listOf("--target-directory"), REPEATED)
}

/** The cells klap or the fixture reads otherwise than real cp today, each group the subject of one target. */
internal val TARGETED_CELLS: Set<ParseCell> by lazy {
    (
        LOWERCASE_R_CELLS + U_CELLS + CHOICE_CAPITAL_CELLS + ATTRIBUTE_NAME_CELLS + ATTRIBUTE_REPEAT_CELLS +
            OPTIONAL_VALUE_CELLS + BACKUP_CONTROL_CELLS + TARGET_DIRECTORY_TWICE_CELLS
        ).toSet()
}
