package com.fromwau.klap.fixture.find

import com.fromwau.kern.result.Result
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import kotlin.test.fail

/*
 * The cells of the projection tests, generated from the inputs real find 4.11.0 has (GNU findutils), read through the
 * fixture's respelling (owner decision F1): find's `-name x` is the fixture's `--name x`, and real find decides what
 * the value is and whether the line stands. Every `because` quotes the single-dash line run against /usr/bin/find
 * inside a throwaway directory, unless it says it was read from the man page.
 */

/** The projection cells of find: one argv line each and what real find makes of it. */
internal object FindProjectionSupport {

    /**
     * [argv] binds [expected], or is refused by find [because] where [expected] is null. A [loose] cell binds in klap
     * although real find refuses the line, a shape the fixture declares that find does not have.
     */
    data class Cell(
        val input: String,
        val form: String,
        val argv: List<String>,
        val expected: FindInputs?,
        val because: String,
        val loose: Boolean = false,
    )

    /** [line] and [other] bind different records, both accepted: find reads them differently. */
    data class Differ(
        val line: List<String>,
        val other: List<String>,
        val because: String,
    )

    fun ParitySuite<FindInputs>.check(cell: Cell) {
        val argv = cell.argv.toTypedArray()
        val expected = cell.expected
        when {
            expected == null -> rejects(argv = argv, because = cell.because)
            cell.loose -> bindsLoosely(argv = argv, because = cell.because, expected = expected)
            else -> binds(argv = argv, expected = expected, because = cell.because)
        }
    }

    fun ParitySuite<FindInputs>.checkAll(cells: List<Cell>) = checkCells(cells) { check(it) }

    fun checkDiffer(cells: List<Differ>) = checkCells(cells) { cell ->
        if (bound(cell.line) == bound(cell.other)) {
            fail("expected `${shown(cell.line)}` and `${shown(cell.other)}` to bind apart (${cell.because})")
        }
    }

    private fun bound(line: List<String>): FindInputs = when (val parsed = findCli().parse(line)) {
        is Result.Error -> fail("expected `${shown(line)}` to bind, but it was rejected: ${parsed.error}")
        is Result.Success -> parsed.value ?: fail("expected `${shown(line)}` to bind, but no command ran")
    }

    private fun shown(line: List<String>): String =
        (listOf("find") + line.map { if (it.isEmpty() || ' ' in it) "\"$it\"" else it }).joinToString(" ")

    // --- what a cell spells ---

    const val LONG = "the respelled name after the starting point"
    const val ALONE = "the respelled name with no starting point"
    const val LONG_REPEATED = "the respelled name twice"
    const val LONG_ATTACHED = "a flag's respelled name with a value attached"
    const val ABBREVIATED = "a prefix of the respelled name"
    const val AFTER = "after two starting points"
    const val AFTER_END = "after the end of options"
    const val SPACED = "a value in the next word"
    const val ATTACHED = "a value attached with ="
    const val MISSING = "the value missing at the end of the line"
    const val BAD = "a value find refuses"
    const val DASH_VALUE = "a value word that starts with a dash"
    const val END_VALUE = "a value word that is exactly --"
    const val BUILTIN_VALUE = "a value word spelling --help"
    const val EQUALS_VALUE = "a value holding an equals sign"
    const val EMPTY_VALUE = "an empty free text value"
    const val EMPTY_REFUSED = "an empty value find refuses"
    const val REPEATED = "the value given twice"
    const val REPEATED_BOTH = "a test given twice applies both"

    /** How real find reads a second occurrence of one input. */
    enum class Repeat {
        /** The later value replaces the earlier: `-maxdepth 3 -maxdepth 0` lists only the starting point. */
        LAST,

        /** Every occurrence is kept in order. */
        COLLECTS,

        /** Each occurrence is its own test, ANDed: `-type f -type d` matches nothing. */
        BOTH_APPLY,
    }

    sealed interface Kind {
        data object Flag : Kind

        /**
         * A predicate taking one word. [sample] binds [value] and [second] binds [secondValue]; [dash] is a dash-led
         * word find takes as the value, [bad] one it refuses.
         */
        data class Value(
            val sample: String,
            val value: Any,
            val second: String,
            val secondValue: Any,
            val repeat: Repeat,
            val bad: String? = null,
            val dash: Pair<String, Any>? = null,
            val freeText: Boolean = false,
        ) : Kind
    }

    /** One find predicate or option as the fixture respells it, and the field it binds. */
    class Input(
        val long: String,
        val kind: Kind,
        /** The starting points a cell gives: none for `-files0-from`, which find refuses beside one. */
        val operands: List<String> = listOf("."),
        val bind: (FindInputs, Any) -> FindInputs,
    ) {
        val real: String = "-" + long.removePrefix("--")
    }

    private fun flag(
        long: String,
        bind: (FindInputs) -> FindInputs,
    ) = Input(long, Kind.Flag) { inputs, _ -> bind(inputs) }

    private fun list(
        long: String,
        bind: (FindInputs, List<String>) -> FindInputs,
    ) = Input(
        long,
        Kind.Value(
            sample = "*.kt",
            value = listOf("*.kt"),
            second = "b",
            secondValue = listOf("*.kt", "b"),
            repeat = Repeat.COLLECTS,
            dash = "-x" to listOf("-x"),
            freeText = true,
        ),
    ) { inputs, value ->
        @Suppress("UNCHECKED_CAST")
        bind(inputs, value as List<String>)
    }

    private fun count(
        long: String,
        bind: (FindInputs, List<FindCount>) -> FindInputs,
    ) = Input(
        long,
        Kind.Value(
            sample = "+7",
            value = FindCount('+', 7.0),
            second = "-7",
            secondValue = FindCount('-', 7.0),
            repeat = Repeat.BOTH_APPLY,
            bad = "x",
            dash = "-7" to FindCount('-', 7.0),
        ),
    ) { inputs, value -> bind(inputs, listOf(value as FindCount)) }

    private fun text(
        long: String,
        kind: Kind.Value,
        operands: List<String> = listOf("."),
        bind: (FindInputs, String) -> FindInputs,
    ) = Input(long, kind, operands) { inputs, value -> bind(inputs, value as String) }

    /** Every predicate and option the fixture declares, less the pre-path switches, `--exec` and its race flag. */
    val INPUTS: List<Input> = listOf(
        Input("--maxdepth", Kind.Value("3", 3, "0", 0, Repeat.LAST, bad = "-1")) { inputs, value ->
            inputs.copy(maxDepth = value as Int)
        },
        Input("--mindepth", Kind.Value("1", 1, "2", 2, Repeat.LAST, bad = "x")) { inputs, value ->
            inputs.copy(minDepth = value as Int)
        },
        flag("--depth") { it.copy(depth = true) },
        flag("--xdev") { it.copy(xdev = true) },
        flag("--mount") { it.copy(mount = true) },
        flag("--noleaf") { it.copy(noleaf = true) },
        text(
            "--regextype",
            Kind.Value("posix-egrep", "posix-egrep", "posix-basic", "posix-basic", Repeat.LAST, bad = "bogus"),
        ) { inputs, value -> inputs.copy(regextype = value) },
        text(
            "--files0-from",
            Kind.Value("b", "b", "c", "c", Repeat.LAST),
            operands = emptyList(),
        ) { inputs, value -> inputs.copy(files0From = value) },
        list("--name") { inputs, value -> inputs.copy(namePattern = value) },
        list("--iname") { inputs, value -> inputs.copy(inamePattern = value) },
        list("--path") { inputs, value -> inputs.copy(pathPattern = value) },
        list("--regex") { inputs, value -> inputs.copy(regexPattern = value) },
        text("--type", Kind.Value("f", "f", "d", "d", Repeat.BOTH_APPLY, bad = "x")) { inputs, value ->
            inputs.copy(typeTest = listOf(value))
        },
        Input(
            "--size",
            Kind.Value(
                sample = "+1M",
                value = FindSize('+', 1u, 'M'),
                second = "-100c",
                secondValue = FindSize('-', 100u, 'c'),
                repeat = Repeat.BOTH_APPLY,
                bad = "1Q",
                dash = "-100c" to FindSize('-', 100u, 'c'),
            ),
        ) { inputs, value -> inputs.copy(sizeTest = listOf(value as FindSize)) },
        count("--mtime") { inputs, value -> inputs.copy(mtime = value) },
        count("--mmin") { inputs, value -> inputs.copy(mmin = value) },
        count("--ctime") { inputs, value -> inputs.copy(ctime = value) },
        count("--atime") { inputs, value -> inputs.copy(atime = value) },
        Input(
            "--links",
            Kind.Value(
                sample = "+7",
                value = FindNumber('+', 7u),
                second = "-7",
                secondValue = FindNumber('-', 7u),
                repeat = Repeat.BOTH_APPLY,
                bad = "x",
                dash = "-7" to FindNumber('-', 7u),
            ),
        ) { inputs, value -> inputs.copy(linkCount = listOf(value as FindNumber)) },
        Input(
            "--perm",
            Kind.Value(
                sample = "644",
                value = FindPerm('=', "644"),
                second = "-u+w",
                secondValue = FindPerm('-', "u+w"),
                repeat = Repeat.BOTH_APPLY,
                bad = "/",
                dash = "-u+w" to FindPerm('-', "u+w"),
            ),
        ) { inputs, value -> inputs.copy(permTest = listOf(value as FindPerm)) },
        text("--newer", Kind.Value("b", "b", "a", "a", Repeat.BOTH_APPLY)) { inputs, value ->
            inputs.copy(newerThan = listOf(value))
        },
        text("--user", Kind.Value("root", "root", "0", "0", Repeat.BOTH_APPLY)) { inputs, value ->
            inputs.copy(ownerUser = listOf(value))
        },
        text("--group", Kind.Value("root", "root", "0", "0", Repeat.BOTH_APPLY)) { inputs, value ->
            inputs.copy(ownerGroup = listOf(value))
        },
        flag("--empty") { it.copy(emptyTest = true) },
        flag("--readable") { it.copy(readableTest = true) },
        flag("--writable") { it.copy(writableTest = true) },
        flag("--executable") { it.copy(executableTest = true) },
        flag("--nouser") { it.copy(nouserTest = true) },
        flag("--nogroup") { it.copy(nogroupTest = true) },
        flag("--print") { it.copy(printAction = true) },
        flag("--print0") { it.copy(print0Action = true) },
        text(
            "--printf",
            Kind.Value("%p", "%p", "%f", "%f", Repeat.BOTH_APPLY, dash = "-x" to "-x", freeText = true),
        ) { inputs, value -> inputs.copy(printfAction = listOf(value)) },
        flag("--ls") { it.copy(lsAction = true) },
        flag("--delete") { it.copy(deleteAction = true) },
        flag("--prune") { it.copy(pruneAction = true) },
        flag("--quit") { it.copy(quitAction = true) },
    )

    private val DECLARED_LONGS: Set<String> = INPUTS.map { it.long }.toSet() +
        setOf("--exec", "--ignore_readdir_race")

    private fun Input.cell(
        form: String,
        argv: List<String>,
        expected: FindInputs?,
        because: String,
        loose: Boolean = false,
    ) = Cell(long, form, argv, expected, because, loose)

    private fun Input.bound(value: Any = true): FindInputs = bind(NOTHING_BOUND.copy(operand = operands), value)

    /** The longest prefix of [long] that is not itself a declared long, which find, matching whole words, refuses. */
    private fun prefixOf(long: String): String? = long.dropLast(1).takeIf { it.length > 2 && it !in DECLARED_LONGS }

    /** Every spelling of every input, each with what it binds or that find refuses it. */
    val ALL: List<Cell> by lazy { INPUTS.flatMap(::cellsOf) }

    /** The cells of the inputs in one of [forms]. */
    fun cells(vararg forms: String): List<Cell> = ALL.filter { it.form in forms }

    private fun cellsOf(input: Input): List<Cell> = when (val kind = input.kind) {
        Kind.Flag -> flagCells(input)
        is Kind.Value -> valueCells(input, kind)
    } + commonCells(input)

    private fun flagCells(input: Input): List<Cell> = buildList {
        val long = input.long
        val real = input.real
        add(input.cell(LONG, listOf(".", long), input.bound(), "`find . $real` exits 0"))
        add(input.cell(LONG_REPEATED, listOf(".", long, long), input.bound(), "`find . $real $real` exits 0"))
        add(input.cell(LONG_ATTACHED, listOf(".", "$long=x"), null, "find: unknown predicate `$real=x'"))
        add(
            input.cell(
                AFTER,
                listOf("a", "b", long),
                input.bind(NOTHING_BOUND.copy(operand = listOf("a", "b")), true),
                "`find a b $real` walks both",
            ),
        )
    }

    private fun valueCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long
        val real = input.real
        val at = input.operands
        val sample = kind.sample
        add(input.cell(SPACED, at + listOf(long, sample), input.bound(kind.value), "`find $real $sample` exits 0"))
        // F1a: the attached form is the respelling's GNU long option, read as the spaced one.
        add(input.cell(ATTACHED, at + "$long=$sample", input.bound(kind.value), "F1a: as `find $real $sample`"))
        add(input.cell(MISSING, at + long, null, "find: missing argument to `$real'"))
        kind.bad?.let { bad ->
            add(input.cell(BAD, at + listOf(long, bad), null, "`find . $real $bad` is refused"))
        }
        kind.dash?.let { (word, value) ->
            add(input.cell(DASH_VALUE, at + listOf(long, word), input.bound(value), "`find . $real $word` exits 0"))
        }
        if (input.operands.isNotEmpty()) {
            add(
                input.cell(
                    AFTER,
                    listOf("a", "b", long, sample),
                    input.bind(NOTHING_BOUND.copy(operand = listOf("a", "b")), kind.value),
                    "`find a b $real $sample` walks both",
                ),
            )
        }
        if (kind.freeText) {
            addAll(freeTextCells(input, kind))
        } else {
            add(input.cell(EMPTY_REFUSED, at + listOf(long, ""), null, "`find . $real \"\"` is refused"))
        }
        val twice = at + listOf(long, sample, long, kind.second)
        when (kind.repeat) {
            Repeat.LAST -> add(input.cell(REPEATED, twice, input.bound(kind.secondValue), "the later one counts"))
            Repeat.COLLECTS -> add(input.cell(REPEATED, twice, input.bound(kind.secondValue), "both are tests"))
            Repeat.BOTH_APPLY -> Unit
        }
    }

    private fun Kind.Value.wrap(word: String): Any = if (repeat == Repeat.COLLECTS) listOf(word) else word

    /** The edges of a free text value find takes as given: `--`, a built-in's name, `=` and the empty word. */
    private fun freeTextCells(
        input: Input,
        kind: Kind.Value,
    ): List<Cell> = buildList {
        val long = input.long
        val real = input.real
        add(input.cell(END_VALUE, listOf(".", long, "--"), input.bound(kind.wrap("--")), "`find . $real --` exits 0"))
        add(
            input.cell(
                BUILTIN_VALUE,
                listOf(".", long, "--help"),
                input.bound(kind.wrap("--help")),
                "`find . $real --help` runs, printing no usage",
            ),
        )
        add(input.cell(EQUALS_VALUE, listOf(".", long, "a=b"), input.bound(kind.wrap("a=b")), "`find . $real a=b`"))
        add(input.cell(EQUALS_VALUE, listOf(".", "$long=a=b"), input.bound(kind.wrap("a=b")), "F1a, as the spaced"))
        add(input.cell(EMPTY_VALUE, listOf(".", long, ""), input.bound(kind.wrap("")), "`find . $real \"\"` exits 0"))
    }

    private fun commonCells(input: Input): List<Cell> = buildList {
        val long = input.long
        val real = input.real
        val word = if (input.kind is Kind.Value) "$long=${input.kind.sample}" else long
        val alone = if (input.kind is Kind.Value) listOf(long, input.kind.sample) else listOf(long)
        val aloneBound = input.bind(NOTHING_BOUND, (input.kind as? Kind.Value)?.value ?: true)
        add(input.cell(ALONE, alone, aloneBound, "`find $real ...` walks ., the default starting point"))
        prefixOf(long)?.let { prefix ->
            val word = if (input.kind is Kind.Value) listOf(prefix, input.kind.sample) else listOf(prefix)
            val argv = input.operands + word
            add(input.cell(ABBREVIATED, argv, null, "F1b: find matches whole words, `-${prefix.drop(2)}` is unknown"))
        }
        add(
            input.cell(
                AFTER_END,
                listOf(".", "--", word),
                NOTHING_BOUND.copy(operand = listOf(".", word)),
                "find: unknown predicate `--' (the fixture's raw expression tail)",
                loose = true,
            ),
        )
    }

    /** The tests real find ANDs when given twice, each line against the one with only the later value. */
    val BOTH_APPLY: List<Differ> by lazy {
        INPUTS.mapNotNull { input ->
            val kind = input.kind as? Kind.Value ?: return@mapNotNull null
            if (kind.repeat != Repeat.BOTH_APPLY) return@mapNotNull null
            Differ(
                line = listOf(".", input.long, kind.sample, input.long, kind.second),
                other = listOf(".", input.long, kind.second),
                because = "`find . ${input.real} ${kind.sample} ${input.real} ${kind.second}` ANDs both tests",
            )
        }
    }
}
