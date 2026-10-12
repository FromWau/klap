package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.acceptSuggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The suggestion list Tab gives on a pacman line and what every completion cell asserts about it (G and E of the
 * testing matrix): names pacman lacks, order, descriptions, what one Tab inserts, the line once a suggestion is taken,
 * and the wire. Every expectation is what real pacman 7.1.0 accepts at the cursor.
 */
class PacmanSuggestionListTest {

    private val pacman = pacmanCli().cli

    private val parity = ParitySuite(pacmanCli())

    @Test
    fun `a name pacman does not have offers nothing`() = pacman.checkContext(
        // `pacman -Q --help-all`: "unrecognized option '--help-all'".
        at("G11 G65", "pacman -Q --help-|"),
        at("G15", "pacman -Q --zzz|"),
        // `pacman -Q --colorize pacman`: unrecognized, though --color's help says "colorize".
        at("G54", "pacman -Q --colori|"),
        // `pacman -Q --QUIET`: "unrecognized option '--QUIET'".
        at("G77", "pacman -Q --QUI|"),
    )

    @Test
    fun `a value holding an equals sign splits at the first one`() = pacman.checkContext(
        // `pacman -Q --dbpath=<dir>/a=b pacman` reads the path `a=b`; `pacman -Q --color=always=x pacman`:
        // "error: invalid argument 'always=x' for --color".
        at("G84", "pacman -Q --dbpath=a=b|", filesAfter("--dbpath=")),
        at("G84", "pacman -Q --color=always=|"),
    )

    @Test
    fun `choices come in the order pacman documents them`() {
        // `man pacman`: "Valid options are always, never, or auto"; --debug takes 1 and 2.
        assertEquals(
            listOf("always", "never", "auto").map { attached("--color", it) },
            pacman.suggestionsFor("pacman -Q --color="),
            "G2 `pacman -Q --color=|`",
        )
        assertEquals(
            listOf("1", "2").map { attached("--debug", it) },
            pacman.suggestionsFor("pacman -Q --debug="),
            "G2 `pacman -Q --debug=|`",
        )
    }

    @Test
    fun `tab puts in what the suggestions share and no further`() = pacman.checkContext(
        at(
            "E10",
            "pacman -Q --disable-s|",
            onQuery("--disable-sandbox"),
            onQuery("--disable-sandbox-filesystem"),
            onQuery("--disable-sandbox-syscalls"),
        ),
        at(
            "E10 E17",
            "pacman -Q --disable-sandbox|",
            onQuery("--disable-sandbox"),
            onQuery("--disable-sandbox-filesystem"),
            onQuery("--disable-sandbox-syscalls"),
        ),
        // Tab gives `--confi`, which pacman refuses as ambiguous ('--confirm' '--config') if run as it stands.
        at("E10 E18", "pacman -Q --conf|", onQuery("--config"), onQuery("--confirm")),
    )

    @Test
    fun `a finished word asked again offers itself`() = pacman.checkContext(
        at("E17", "pacman -Q --color=always|", attached("--color", "always")),
        at("E17", "pacman -Q --verbose|", onQuery("--verbose")),
    )

    @Test
    fun `a line once a suggestion is taken binds as pacman reads it`() {
        // `pacman -Q --col=never` exits 0 (an unambiguous prefix); `pacman -Q --debug --check pacman` reads a
        // bare --debug; `pacman -Q --check --check pacman` counts.
        val query = NOTHING_BOUND.copy(query = true)
        val cells = listOf(
            Triple("pacman -Q --col=al|", "--col=always", query.copy(color = "always")),
            Triple("pacman -Q --deb|", "--debug", query.copy(debug = "1")),
            Triple("pacman -Q --chec|", "--check", query.copy(check = 1)),
            Triple("pacman --que| pacman", "--query", query.copy(targets = listOf("pacman"))),
        )
        checkCells(cells) { (typed, value, expected) ->
            val line = typed.replace("|", "")
            val cursor = typed.indexOf('|')
            val taken = pacman.suggestionsFor(line, cursor).firstOrNull { it.value == value }
            assertTrue(taken != null, "E15 `$typed`: must offer $value")
            val accepted = acceptSuggestion(line, taken, cursor)
            parity.binds(*accepted.split(' ').drop(1).toTypedArray(), expected = expected, because = "E15 `$typed`")
        }
    }

    @Test
    fun `a name taken and then its value taken bind the value`() {
        val named = pacman.suggestionsFor("pacman -Q --colo").single()
        val line = acceptSuggestion("pacman -Q --colo", named) + " "
        val values = pacman.suggestionsFor(line)
        assertEquals(listOf("always", "never", "auto").map { Suggestion(it) }, values, "E16 `$line|`")
        val chosen = acceptSuggestion(line, values[1])
        parity.binds(
            *chosen.split(' ').drop(1).toTypedArray(),
            expected = NOTHING_BOUND.copy(query = true, color = "never"),
            because = "E16 `$chosen`",
        )
    }

    @Test
    fun `a completion script's call reaches pacman as its own words`() {
        // pacman declines klap's completion, so there is no __complete for a script to call.
        val because = "real pacman: unrecognized option '--cursor'"
        parity.rejects("__complete", "--cursor", "1", "--", "-", because = because)
    }

    @Test
    fun `a name's description is the text its help row shows`() {
        // With no operation on the line a name is described in the words of the first operation's page listing it,
        // so that page is where its row stands.
        checkCells(INPUTS) { input ->
            val op = Op.entries.first { it.entryFor(input.long) != null }
            val page = parity.helpPage("-${op.short}", "--help", because = "pacman -${op.short} --help")
            val flat = page.lines().joinToString(" ") { it.trim() }.replace(Regex(" +"), " ")
            val offered = pacman.suggestionsFor("pacman ${input.long}").first { it.value == input.long }
            val text = offered.description.orEmpty()
            assertTrue(
                text.isNotEmpty() && text in flat,
                "E20 `pacman ${input.long}|`: \"$text\" is not on -${op.short} --help",
            )
        }
    }

    @Test
    fun `asking twice gives the same answer`() {
        val lines = listOf("pacman -Q --col=", "pacman -Q --dbpath ", "pacman -Q -", "pacman --qu")
        checkCells(lines) { line ->
            assertEquals(pacman.suggestionsFor(line), pacman.suggestionsFor(line), "E24 `$line|`")
        }
    }

    @Test
    fun `help and version are described in pacman's own words`() = pacman.checkContext(
        // `man pacman`: "-V, --version  Display version and exit." and "-h, --help  Display syntax for the given
        // operation." The fixture words -V the same way.
        at("G45 G6b", "pacman --vers|", Suggestion("--version", VERSION_SHORT_HELP)),
        at("G45 G6b", "pacman -Q --vers|", Suggestion("--version", VERSION_SHORT_HELP)),
        at("G45 G6b", "pacman --hel|", Suggestion("--help", HELP_TEXT)),
        at("G45 G6b", "pacman -Q --hel|", Suggestion("--help", HELP_TEXT)),
    )

    @Test
    fun `a built in pacman does not have is not offered`() = pacman.checkContext(
        // `pacman -Q --json`, `pacman -Q --completion bash`, `pacman -Q --docs man`: "unrecognized option" each.
        at("G11 G67", "pacman -Q --js|"),
        at("G11 G67", "pacman --js|"),
        at("G11 G67", "pacman -Q --comp|"),
        at("G11 G67", "pacman -Q --doc|"),
    )
}
