package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

/** One argv line and what real pacman makes of it: the record it binds, or null when it refuses the line. */
internal data class ParseCell(
    val argv: List<String>,
    val expected: PacmanInputs?,
    val because: String,
)

internal fun ParitySuite<PacmanInputs>.check(cell: ParseCell) {
    val argv = cell.argv.toTypedArray()
    val expected = cell.expected
    if (expected == null) rejects(argv = argv, because = cell.because) else binds(argv = argv, expected = expected)
}

/** What [input] binds after [times] occurrences carrying [values] in turn (a flag ignores them). */
private fun Input.bound(
    record: PacmanInputs,
    times: Int = 1,
    values: List<String> = List(times) { (kind as? Kind.Value)?.sample ?: "" },
): PacmanInputs = when (val kind = kind) {
    Kind.Flag -> record.bind(true)
    Kind.Count -> record.bind(times)
    is Kind.Value -> record.bind(if (kind.multiple) values else values.last())
}

private fun Op.line(vararg words: String): List<String> = listOf("-$short") + words + requiredTargets

/** Every way [input]'s long name is written under [op], each with what pacman binds, for an input on its page. */
internal fun longCells(
    op: Op,
    input: Input,
): List<ParseCell> {
    val record = op.record()
    val why = "pacman -${op.short} --help lists ${input.long}"
    val long = input.long
    val cells = mutableListOf<ParseCell>()
    when (val kind = input.kind) {
        Kind.Flag, Kind.Count -> {
            cells += ParseCell(op.line(long), input.bound(record), why)
            val entry = op.entryFor(long)
            // A doubled letter counts where the page says so (`-ii`); any other flag may repeat (`-Qqq` exits 0).
            if (kind == Kind.Flag || (entry != null && op.counts(entry))) {
                cells += ParseCell(op.line(long, long), input.bound(record, times = 2), "$why, repeated")
            }
        }

        is Kind.Value -> {
            val sample = kind.sample
            for (value in kind.choices ?: listOf(sample)) {
                cells += ParseCell(op.line("$long=$value"), input.bound(record, values = listOf(value)), why)
                val bare = kind.bare
                cells += if (bare == null) {
                    ParseCell(op.line(long, value), input.bound(record, values = listOf(value)), why)
                } else {
                    ParseCell(
                        op.line(long, value),
                        input.bound(record, values = listOf(bare)).copy(targets = listOf(value) + op.requiredTargets),
                        "a bare optional value takes no next word: `pacman -Q --debug 2`: package '2' was not found",
                    )
                }
            }
            kind.bare?.let { bare ->
                val bound = input.bound(record, values = listOf(bare))
                cells += ParseCell(op.line(long), bound, "bare `--debug` prints as much as `--debug=1`")
            }
            if (kind.bare == null) {
                // `--cachedir=/tmp --cachedir /var/tmp` lists both under `pacman -Qv`; a second `--dbpath` wins.
                val (a, b) = kind.choices?.take(2) ?: listOf("/a", "/b")
                cells += ParseCell(
                    op.line("$long=$a", long, b),
                    input.bound(record, times = 2, values = listOf(a, b)),
                    "$why, repeated",
                )
                cells += ParseCell(
                    listOf("-${op.short}") + op.requiredTargets + long,
                    null,
                    "pacman: option '$long' requires an argument",
                )
            }
        }
    }
    abbreviationOf(long)?.let { prefix ->
        val first = cells.first()
        val abbreviated = first.argv.map { if (it.startsWith(long)) prefix + it.drop(long.length) else it }
        cells += first.copy(argv = abbreviated)
    }
    return cells
}

/** Every way the letter [entry] is written under [op], bound to the input pacman's page gives it there. */
internal fun letterCells(
    op: Op,
    entry: PageEntry,
): List<ParseCell> {
    val letter = entry.short ?: return emptyList()
    val input = input(entry.long)
    val record = op.record()
    val why = "pacman -${op.short} --help: -$letter, ${entry.long}"
    val o = op.short
    return when (val kind = input.kind) {
        Kind.Flag, Kind.Count -> listOfNotNull(
            ParseCell(op.line().let { listOf("-$o$letter") + it.drop(1) }, input.bound(record), why),
            ParseCell(op.line("-$letter"), input.bound(record), why),
            ParseCell(listOf("-$o$letter$letter") + op.requiredTargets, input.bound(record, times = 2), "$why, doubled")
                .takeIf { op.counts(entry) },
        )

        is Kind.Value -> {
            val sample = kind.sample
            val bound = input.bound(record, values = listOf(sample))
            listOf(
                ParseCell(listOf("-$o$letter", sample) + op.requiredTargets, bound, why),
                ParseCell(listOf("-$o$letter$sample") + op.requiredTargets, bound, "$why, glued (`pacman -Qb/tmp`)"),
                ParseCell(op.line("-$letter", sample), bound, why),
                ParseCell(op.line("-$letter$sample"), bound, "$why, glued"),
            )
        }
    }
}

/** The letters the fixture declares that [op]'s page does not list, which pacman refuses there. */
internal fun lettersOffPage(op: Op): List<Char> =
    INPUTS.mapNotNull { it.short }.filter { op.entryFor(it) == null }

/** The fixture's long names that [op]'s page does not list. */
internal fun longsOffPage(op: Op): List<Input> = INPUTS.filter { op.entryFor(it.long) == null }

/** Whether the fixture's letter on [entry] means what pacman's page says it means under its operation. */
internal fun PageEntry.agreesWithFixture(): Boolean = short?.let { inputFor(it)?.long == long } ?: true

/**
 * The fixture's whole surface, one cell per input and spelling, as real pacman 7.1.0 reads it: every cell here is
 * one klap already gets right. The cells where it does not are the targets in [PacmanProjectionTargetsTest].
 */
class PacmanProjectionMatrixTest {

    private val parity = ParitySuite(pacmanCli())

    @Test
    fun `every operation binds by its long its short and its abbreviation`() {
        checkCells(Op.entries) { op ->
            parity.check(ParseCell(op.line(), op.record(), "pacman -h lists ${op.long}"))
            parity.check(ParseCell(listOf(op.long) + op.requiredTargets, op.record(), "pacman -h lists ${op.long}"))
            abbreviationOf(op.long)?.let { prefix ->
                parity.check(ParseCell(listOf(prefix) + op.requiredTargets, op.record(), "`pacman --quer` exits 0"))
            }
        }
    }

    @Test
    fun `two different operations and none at all are refused`() {
        parity.check(ParseCell(emptyList(), null, "pacman: error: no operation specified (use -h for help)"))
        val pairs = Op.entries.flatMap { a -> Op.entries.filter { it > a }.map { a to it } }
        checkCells(pairs) { (a, b) ->
            val why = "pacman: error: only one operation may be used at a time (`pacman -Q --sync pacman`)"
            parity.check(ParseCell(listOf("-${a.short}${b.short}"), null, why))
            parity.check(ParseCell(listOf(a.long, b.long), null, why))
        }
    }

    @Test
    fun `every long on an operation's page binds in every form`() {
        val cells = Op.entries.flatMap { op ->
            op.page.map { input(it.long) }.flatMap { longCells(op, it) }
        }
        checkCells(cells) { parity.check(it) }
    }

    @Test
    fun `every letter whose fixture meaning is pacman's binds in every form`() {
        val cells = Op.entries.flatMap { op ->
            op.page.filter { it.agreesWithFixture() }.flatMap { letterCells(op, it) }
        }
        checkCells(cells) { parity.check(it) }
    }

    @Test
    fun `a flag refuses an attached value`() {
        // `pacman -Q --owns=/x` and `--file=x`: "option '--owns' doesn't allow an argument", getopt's answer
        // for every option declared without one.
        val flags = Op.QUERY.page.map { input(it.long) }.filter { it.kind !is Kind.Value }
        checkCells(flags) { parity.check(ParseCell(listOf("-Q", "${it.long}=x"), null, "doesn't allow an argument")) }
    }

    @Test
    fun `a choice value outside its set is refused`() {
        parity.check(ParseCell(listOf("-Q", "--color=zzz"), null, "pacman: error: invalid argument 'zzz' for --color"))
        parity.check(ParseCell(listOf("-Q", "--color="), null, "pacman: error: invalid argument '' for --color"))
        parity.check(ParseCell(listOf("-Q", "--debug=3"), null, "pacman: error: '3' is not a valid debug level"))
        parity.check(ParseCell(listOf("-Q", "--debug=0"), null, "pacman: error: '0' is not a valid debug level"))
    }

    @Test
    fun `everything after the end of options is a target`() {
        // `pacman -Q -- -Q`: "error: package '-Q' was not found".
        val words = listOf("-Q", "--dbpath", "-b", "--", "pkg")
        checkCells(Op.entries) { op ->
            parity.check(ParseCell(listOf("-${op.short}", "--") + words, op.record().copy(targets = words), "--"))
        }
    }

    @Test
    fun `an unknown letter or long is refused`() {
        parity.check(ParseCell(listOf("-Qa"), null, "pacman: invalid option -- 'a'"))
        parity.check(ParseCell(listOf("-Qz"), null, "pacman: invalid option -- 'z'"))
        parity.check(ParseCell(listOf("-Qf"), null, "pacman: error: invalid option '-f'"))
        parity.check(ParseCell(listOf("-Q", "--zzz"), null, "pacman: unrecognized option '--zzz'"))
        parity.check(ParseCell(listOf("-Q", "--colour=never"), null, "pacman: unrecognized option '--colour=never'"))
        parity.check(ParseCell(listOf("--docs"), null, "pacman: unrecognized option '--docs'"))
    }

    @Test
    fun `an ambiguous abbreviation is refused`() {
        val cells = listOf("--re", "--qu", "--c", "--conf", "--dis", "--fil", "--no")
        checkCells(cells) { parity.check(ParseCell(listOf("-Q", it), null, "pacman: option '$it' is ambiguous")) }
    }

    @Test
    fun `help and version print as pacman's do`() {
        parity.showsHelp("-h", because = "pacman -h prints its usage")
        parity.showsHelp("--help", because = "pacman --help prints its usage")
        parity.showsHelp("-Qh", because = "pacman -Qh prints the -Q page")
        parity.showsHelp("-hQ", because = "pacman -hQ prints the -Q page")
        parity.showsHelp("-Q", "--help", because = "pacman -Q --help prints the -Q page")
        parity.showsVersion("--version", because = "pacman --version prints its banner, exit 0")
        parity.showsVersion("-Qq", "--version", because = "pacman -Qq --version prints its banner, exit 0")
    }
}
