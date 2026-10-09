package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What real pacman 7.1.0 does with a line where klap or the fixture does something else today. Each test states
 * pacman's behaviour and is ignored with the reason `TDD target: <what must change>`; removing the annotation
 * shows it failing until the fixture or klap is changed to match, and nothing else in the suite pins the wrong
 * behaviour, so a fix breaks no other test.
 */
class PacmanProjectionTargetsTest {

    private val parity = ParitySuite(pacmanCli())

    private fun offPageLongs(op: Op) {
        // Under -Q every one was run: `pacman -Q --needed pacman` says "error: invalid option '--needed'", and the
        // same for each long not on `pacman -Q --help`. Under -T likewise (`pacman -T --info`). For the other
        // operations the evidence is their `--help` page alone, since they are never run here.
        val cells = longsOffPage(op).map { input ->
            val kind = input.kind
            val word = if (kind is Kind.Value) "${input.long}=${kind.sample}" else input.long
            ParseCell(
                listOf("-${op.short}", word) + op.requiredTargets,
                null,
                "pacman: error: invalid option '${input.long}' (not on pacman -${op.short} --help)",
            )
        }
        checkCells(cells) { parity.check(it) }
    }

    private fun offPageLetters(op: Op) {
        // `pacman -Qy pacman`: "error: invalid option '-y'"; also -Qw, -Qx, and `pacman -Ti`, `-Tq`.
        val cells = lettersOffPage(op).map { letter ->
            val why = "pacman: error: invalid option '-$letter'"
            ParseCell(listOf("-${op.short}$letter") + op.requiredTargets, null, why)
        }
        checkCells(cells) { parity.check(it) }
    }

    private fun contestedLetters(op: Op) {
        val cells = op.page.filterNot { it.agreesWithFixture() }.flatMap { letterCells(op, it) }
        checkCells(cells) { parity.check(it) }
    }

    @Ignore("TDD target: klap must let an option belong to some operations only, refusing it under the others")
    @Test
    fun `a long of another operation is refused under -Q`() = offPageLongs(Op.QUERY)

    @Ignore("TDD target: klap must let an option belong to some operations only, refusing it under the others")
    @Test
    fun `a long of another operation is refused under -S`() = offPageLongs(Op.SYNC)

    @Ignore("TDD target: klap must let an option belong to some operations only, refusing it under the others")
    @Test
    fun `a long of another operation is refused under -R`() = offPageLongs(Op.REMOVE)

    @Ignore("TDD target: klap must let an option belong to some operations only, refusing it under the others")
    @Test
    fun `a long of another operation is refused under -U`() = offPageLongs(Op.UPGRADE)

    @Ignore("TDD target: klap must let an option belong to some operations only, refusing it under the others")
    @Test
    fun `a long of another operation is refused under -D`() = offPageLongs(Op.DATABASE)

    @Ignore("TDD target: klap must let an option belong to some operations only, refusing it under the others")
    @Test
    fun `a long of another operation is refused under -F`() = offPageLongs(Op.FILES)

    @Ignore("TDD target: klap must let an option belong to some operations only, refusing it under the others")
    @Test
    fun `a long of another operation is refused under -T`() = offPageLongs(Op.DEPTEST)

    @Ignore("TDD target: klap must let a letter belong to some operations only, refusing it under the others")
    @Test
    fun `a letter of another operation is refused under -Q`() = offPageLetters(Op.QUERY)

    @Ignore("TDD target: klap must let a letter belong to some operations only, refusing it under the others")
    @Test
    fun `a letter of another operation is refused under -S`() = offPageLetters(Op.SYNC)

    @Ignore("TDD target: klap must let a letter belong to some operations only, refusing it under the others")
    @Test
    fun `a letter of another operation is refused under -R`() = offPageLetters(Op.REMOVE)

    @Ignore("TDD target: klap must let a letter belong to some operations only, refusing it under the others")
    @Test
    fun `a letter of another operation is refused under -U`() = offPageLetters(Op.UPGRADE)

    @Ignore("TDD target: klap must let a letter belong to some operations only, refusing it under the others")
    @Test
    fun `a letter of another operation is refused under -D`() = offPageLetters(Op.DATABASE)

    @Ignore("TDD target: klap must let a letter belong to some operations only, refusing it under the others")
    @Test
    fun `a letter of another operation is refused under -F`() = offPageLetters(Op.FILES)

    @Ignore("TDD target: klap must let a letter belong to some operations only, refusing it under the others")
    @Test
    fun `a letter of another operation is refused under -T`() = offPageLetters(Op.DEPTEST)

    // `pacman -Q --help`: -c --changelog, -d --deps, -n --native, -p --file, -u --upgrades.
    @Ignore("TDD target: klap must resolve a letter by the operation on the line (-Qc is --changelog, -Sc --clean)")
    @Test
    fun `a contested letter means what -Q gives it`() = contestedLetters(Op.QUERY)

    // `pacman -R --help`: -c --cascade, -s --recursive (-ss), -u --unneeded.
    @Ignore("TDD target: klap must resolve a letter by the operation on the line (-Rs is --recursive, -Ss --search)")
    @Test
    fun `a contested letter means what -R gives it`() = contestedLetters(Op.REMOVE)

    @Ignore("TDD target: klap must refuse a second occurrence of a requireExactlyOne member, not only another member")
    @Test
    fun `the operation given twice is refused`() {
        // `pacman -QQ pacman`, `pacman -Q -Q pacman`, `pacman -Q --query pacman`: all "error: only one operation
        // may be used at a time".
        val cells = Op.entries.flatMap { op ->
            val why = "pacman: error: only one operation may be used at a time"
            listOf(
                ParseCell(listOf("-${op.short}${op.short}") + op.requiredTargets, null, why),
                ParseCell(listOf("-${op.short}", "-${op.short}") + op.requiredTargets, null, why),
                ParseCell(listOf("-${op.short}", op.long) + op.requiredTargets, null, why),
            )
        }
        checkCells(cells) { parity.check(it) }
    }

    @Ignore("TDD target: klap must let a choice match case-sensitively, as pacman's --color does")
    @Test
    fun `a color value in capitals is refused`() {
        // `pacman -Q --color=ALWAYS`: "error: invalid argument 'ALWAYS' for --color".
        checkCells(listOf("ALWAYS", "Never", "AUTO")) {
            parity.check(ParseCell(listOf("-Q", "--color=$it"), null, "pacman: error: invalid argument '$it'"))
        }
    }

    @Ignore("TDD target: the fixture must decline klap's --json --completion and --docs built-ins, and klap --help-all")
    @Test
    fun `an option pacman does not have is refused`() {
        // `pacman --json`, `pacman --docs`, `pacman --completion bash`, `pacman --help-all`: all "unrecognized
        // option". klap's completion scripts are installed through --completion, so declining it needs another way.
        val cells = listOf(
            listOf("-Q", "--json"), listOf("--json", "-Q"), listOf("--docs", "man"), listOf("-Q", "--docs", "man"),
            listOf("--completion", "bash"), listOf("-Q", "--completion", "bash"), listOf("--help-all"),
            listOf("-Q", "--help-all"),
        )
        checkCells(cells) { words ->
            val option = words.first { it.startsWith("--") }
            parity.check(ParseCell(words, null, "pacman: unrecognized option '$option'"))
        }
    }

    @Ignore("TDD target: klap must let -V print the version like --version, with or without an operation")
    @Test
    fun `-V prints the version`() {
        // `pacman -V` and `pacman -QV` both print the banner and exit 0.
        parity.showsVersion("-V", because = "pacman -V prints its banner, exit 0")
        parity.showsVersion("-QV", because = "pacman -QV prints its banner, exit 0")
        parity.showsVersion("-Q", "-V", because = "pacman -Q -V prints its banner, exit 0")
    }

    @Ignore("TDD target: klap must print an operation's own help page for -Qh and -Q --help")
    @Test
    fun `an operation's help lists that operation's options only`() {
        // `pacman -Qh` prints "usage:  pacman {-Q --query} [options] [package(s)]" and the -Q rows only.
        checkCells(Op.entries) { op ->
            val page = parity.helpPage("-${op.short}h", because = "pacman -${op.short}h prints the -${op.short} page")
            val missing = op.page.map { it.long }.filterNot { it in page }
            val foreign = longsOffPage(op)
                .map { it.long }
                .filter { long -> Regex("$long\\b(?!-)").containsMatchIn(page) }
            assertTrue(missing.isEmpty() && foreign.isEmpty(), "-${op.short}h page: missing $missing, foreign $foreign")
        }
    }

    @Ignore("TDD target: klap must let an operand be required under some operations only (-R and -U need targets)")
    @Test
    fun `an operation whose usage requires targets refuses a line without them`() {
        // From the usage lines only, as these operations are never run here: `pacman {-R --remove} [options]
        // <package(s)>` and `pacman {-U --upgrade} [options] <file(s)>`.
        checkCells(Op.entries.filter { it.requiredTargets.isNotEmpty() }) { op ->
            parity.check(ParseCell(listOf("-${op.short}"), null, "pacman ${op.long} requires <package(s)>"))
        }
    }
}
