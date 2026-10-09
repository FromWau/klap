package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Ignore
import kotlin.test.Test

/*
 * Every expected value is from pacman 7.1.0, unless a comment says the man page.
 */

/**
 * What the fixture makes of a typed pacman value (X of the testing matrix): the exact choice spellings of --color, the
 * debug levels pacman reads, and every occurrence of a single-valued option.
 */
class PacmanConverterTest {

    private val parity = ParitySuite(pacmanCli())

    @Test
    fun `a choice value outside its set is refused`() {
        parity.check(ParseCell(listOf("-Q", "--color=zzz"), null, "pacman: error: invalid argument 'zzz' for --color"))
        parity.check(ParseCell(listOf("-Q", "--color="), null, "pacman: error: invalid argument '' for --color"))
        parity.check(ParseCell(listOf("-Q", "--debug=3"), null, "pacman: error: '3' is not a valid debug level"))
        parity.check(ParseCell(listOf("-Q", "--debug=0"), null, "pacman: error: '0' is not a valid debug level"))
    }

    @Test
    fun `a color value outside its exact spellings is refused`() = parity.checkAll(
        listOf("a", " always", "always ", "x").map {
            cell(
                q("--color=$it", "pacman"),
                null,
                "pacman: error: invalid argument '$it' for --color",
                differs = if (it == "a") "klap refuses `a` as ambiguous, not as invalid (todo 37)" else null,
            )
        },
    )

    @Test
    fun `a debug level pacman cannot read as 1 or 2 is refused`() = parity.checkAll(
        listOf("2 ", "2x", "0x2", "-1", "1.0", "99999999999999999999").map {
            cell(q("--debug=$it", "pacman"), null, "pacman: error: '$it' is not a valid debug level")
        },
    )

    @Test
    fun `a debug level binds through an abbreviated name`() = parity.checkAll(
        listOf(cell(q("--deb=2", "pacman"), QUERY.copy(debug = "2", targets = listOf("pacman")), "function: lines")),
    )

    @Test
    fun `an invalid later occurrence of a single-valued option is refused`() = parity.checkAll(
        listOf(
            cell(q("--color=never", "--color=bogus", "pacman"), null, "pacman: invalid argument 'bogus' for --color"),
            cell(q("--debug=2", "--debug=3", "pacman"), null, "pacman: error: '3' is not a valid debug level"),
        ),
    )

    @Test
    fun `a debug level given last at level 2 binds level 2`() = parity.checkAll(
        // Both lines printed the function: lines of level 2.
        listOf(listOf("--debug", "--debug=2"), listOf("--debug=1", "--debug=2")).map { words ->
            cell(q(*words.toTypedArray(), "pacman"), QUERY.copy(debug = "2", targets = listOf("pacman")), "level 2")
        },
    )
}

/**
 * The typed pacman values pacman 7.1.0 reads one way and the fixture or klap another today (X of the testing matrix).
 * Each test states what real pacman 7.1.0 does and is ignored with `TDD target: <what must change>`; removing the
 * annotation shows it failing for that reason, and no other test pins today's behaviour, so a fix breaks nothing else.
 */
class PacmanConverterTargetsTest {

    private val parity = ParitySuite(pacmanCli())

    @Ignore("TDD target: klap must let a choice refuse a prefix of its values, as pacman's --color does")
    @Test
    fun `a color value is never abbreviated`() = parity.checkAll(
        listOf("al", "nev", "au", "alw").map {
            ParseCell(q("--color=$it", "pacman"), null, "pacman: error: invalid argument '$it' for --color")
        },
    )

    @Ignore("TDD target: the fixture must read --debug's level as a decimal number (01, +2), not as a word")
    @Test
    fun `a debug level binds every spelling pacman reads as a number`() = parity.checkAll(
        // `pacman -Q --debug=01` prints debug: lines only, `--debug=+2`, `--debug=002` and `--debug=' 2'` add the
        // function: lines of level 2.
        listOf("01" to "1", "002" to "2", "+2" to "2", " 2" to "2").map { (typed, level) ->
            ParseCell(q("--debug=$typed", "pacman"), QUERY.copy(debug = level, targets = listOf("pacman")), "exit 0")
        },
    )

    @Ignore("TDD target: the fixture must bind the highest --debug level on the line, not the last one")
    @Test
    fun `a lower debug level after level 2 keeps level 2`() = parity.checkAll(
        // Both lines printed the 3562 function: lines of level 2 beside the 274 debug: lines.
        listOf(listOf("--debug=2", "--debug"), listOf("--debug=2", "--debug=1")).map { words ->
            val line = q(*words.toTypedArray(), "pacman")
            ParseCell(line, QUERY.copy(debug = "2", targets = listOf("pacman")), "level 2")
        },
    )

    @Ignore("TDD target: klap must convert every occurrence of a single-valued option, not only the last")
    @Test
    fun `an invalid earlier occurrence of a single-valued option is refused`() = parity.checkAll(
        listOf(
            ParseCell(q("--color=bogus", "--color=never", "pacman"), null, "invalid argument 'bogus' for --color"),
            ParseCell(q("--debug=3", "--debug=2", "pacman"), null, "pacman: error: '3' is not a valid debug level"),
        ),
    )

    @Ignore("TDD target: the fixture must refuse an empty value for every path option but --logfile")
    @Test
    fun `an empty path is refused`() = parity.checkAll(
        // `pacman -Q --dbpath= pacman`: "error: 'failed to resolve path '' passed to '--dbpath'", and the same for
        // each of these; `--config=` says "config file  could not be read". `--logfile=` is accepted.
        listOf("--dbpath", "--root", "--cachedir", "--hookdir", "--gpgdir", "--config", "--sysroot").flatMap {
            listOf(
                ParseCell(q("$it=", "pacman"), null, "failed to resolve path '' passed to '$it'"),
                ParseCell(q(it, "", "pacman"), null, "failed to resolve path '' passed to '$it'"),
            )
        } + listOf(
            ParseCell(q("-b", "", "pacman"), null, "failed to resolve path '' passed to '--dbpath'"),
            ParseCell(q("-r", "", "pacman"), null, "failed to resolve path '' passed to '--root'"),
        ),
    )

    @Ignore("TDD target: the fixture must split --ignore --ignoregroup and --overwrite values at commas")
    @Test
    fun `a comma separates the packages groups and globs of an upgrade option`() {
        // From the man page only, as -S and -U are not run here: --ignore "Multiple packages can be specified by
        // separating them with a comma", --ignoregroup likewise for groups, --overwrite likewise for patterns.
        val sync = Op.SYNC.record().copy(targets = listOf("pkg"))
        parity.checkAll(
            listOf(
                ParseCell(listOf("-S", "--ignore", "a,b", "pkg"), sync.copy(ignore = listOf("a", "b")), "man pacman"),
                ParseCell(
                    listOf("-S", "--ignore=a,b", "--ignore", "c", "pkg"),
                    sync.copy(ignore = listOf("a", "b", "c")),
                    "man pacman",
                ),
                ParseCell(
                    listOf("-S", "--ignoregroup", "g,h", "pkg"),
                    sync.copy(ignoreGroup = listOf("g", "h")),
                    "man pacman",
                ),
                ParseCell(
                    listOf("-S", "--overwrite", "/a/*,/b/*", "pkg"),
                    sync.copy(overwrite = listOf("/a/*", "/b/*")),
                    "man pacman",
                ),
            ),
        )
    }

    @Ignore("TDD target: klap must let a choice match case-sensitively, as pacman's --color does")
    @Test
    fun `a color value in capitals is refused`() {
        // `pacman -Q --color=ALWAYS`: "error: invalid argument 'ALWAYS' for --color".
        checkCells(listOf("ALWAYS", "Never", "AUTO")) {
            parity.check(ParseCell(listOf("-Q", "--color=$it"), null, "pacman: error: invalid argument '$it'"))
        }
    }
}
