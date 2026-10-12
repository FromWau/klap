package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.chmod.ChmodTab.FILES
import com.fromwau.klap.fixture.chmod.ChmodTab.assertOffersNone
import com.fromwau.klap.fixture.chmod.ChmodTab.at
import com.fromwau.klap.fixture.chmod.ChmodTab.checkTab
import com.fromwau.klap.fixture.chmod.ChmodTab.modes
import com.fromwau.klap.fixture.chmod.ChmodTab.name
import com.fromwau.klap.fixture.chmod.ChmodTab.names
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val NOTHING: List<Suggestion> = emptyList()

/** The flag names of chmod, every spelling, in `chmod --help` order. */
private val FLAG_NAMES: List<String> = INPUTS.filterNot { it.value }.flatMap { it.spellings }

/**
 * The suggestion list Tab gives on a chmod line and what every completion cell asserts about it (tables G and E of
 * the testing matrix): which names are offered and which not, their order, their descriptions, the line once a
 * suggestion is taken, and the wire. Every expectation is what real chmod 9.12 accepts at the cursor, in the words of
 * `chmod --help`; a list cell compares whole suggestions sorted by value, since the shell sorts, and the order klap
 * hands over is pinned on its own.
 */
class ChmodSuggestionListTest {

    private val chmod = chmodCli().cli

    private val parity = ParitySuite(chmodCli())

    @Test
    fun `a single dash offers both spellings and two dashes only the longs`() {
        // G4: `chmod --help` lists `-c, --changes` and the rest; a short is never a `--` word.
        val single = chmod.suggestionsFor("chmod -").map { it.value }
        val double = chmod.suggestionsFor("chmod --").map { it.value }
        assertEquals(single.filter { it.startsWith("--") }, double, "G4 `chmod --|` against the longs of `chmod -|`")
        val bothSpellings = listOf("-c", "--changes", "-f", "--silent", "-v", "--verbose", "-R", "--recursive")
        assertTrue(single.containsAll(bothSpellings), "G4 `chmod -|`: must offer $bothSpellings, offered $single")
    }

    @Test
    fun `names come grouped by kind and each input's spellings together`() {
        val names = chmod.suggestionsFor("chmod -").map { it.value }
        // G16: the fixture declares --reference after the flags; klap still lists every option before every flag. The
        // shell sorts the list again, so chmod has no order of its own here.
        val flagNames = FLAG_NAMES.filter { it in names }
        assertTrue(
            names.indexOf("--reference") < flagNames.minOf(names::indexOf),
            "G16 `chmod -|`: the option name before every flag name, got $names",
        )
        // G17: an input's longs, then its shorts, then its negative longs.
        val dereference = listOf("--dereference", "--no-dereference")
        val at = names.indexOf(dereference.first())
        assertEquals(dereference, names.subList(at, at + dereference.size), "G17 `chmod -|`: longs, then negations")
    }

    @Test
    fun `mode candidates come in the order the provider returns them`() =
        // G23, V55: klap keeps the provider's order through the prefix filter.
        assertEquals(modes(), chmod.suggestionsFor("chmod "), "G23 `chmod |`: the provider's order")

    @Test
    fun `descriptions are the help text alone`() {
        // G47, G48: no `(default: on)` or `(last of ... wins)` annotation, and the group heading "Symlink traversal
        // (only with -R)" describes no input.
        val descriptions = chmod.suggestionsFor("chmod -").mapNotNull { it.description }
        assertTrue(descriptions.none { "(default" in it || "wins)" in it }, "G47 `chmod -|`: $descriptions")
        assertTrue(descriptions.none { it.startsWith("Symlink traversal") }, "G48 `chmod -|`: $descriptions")
    }

    @Test
    fun `a short carries the same description alone and as a continuation`() {
        // G32: `-c` on `chmod -|` and `-Rc` on `chmod -R|` both carry -c's help.
        val alone = chmod.suggestionsFor("chmod -").associate { it.value to it.description }
        val continued = chmod.suggestionsFor("chmod -R").associate { it.value.drop(2) to it.description }
        checkCells(listOf('c', 'f', 'v')) { letter ->
            assertEquals(alone["-$letter"], continued["$letter"], "G32 `-$letter` alone and in `chmod -R$letter`")
        }
    }

    @Test
    fun `a name chmod does not have or a word only its help holds offers nothing`() = chmod.checkTab(
        // G15, E12: `chmod --zzz u+x f`: "unrecognized option '--zzz'"; G54: `--symbolic` appears only in help texts;
        // G77: `chmod --VERBOSE u+x f` is unrecognized, chmod's names are matched in their case.
        at("G15 E12", "chmod --zzz|", NOTHING),
        at("G54", "chmod --symb|", NOTHING),
        at("G54", "chmod --referent|", NOTHING),
        at("G77", "chmod --VERB|", NOTHING),
        at("G13 G76", "chmod U|", NOTHING),
        at("G90", "chmod --re |", NOTHING),
    )

    @Test
    fun `the file directive is the whole answer whatever is typed after its head`() = chmod.checkTab(
        // G12, G72, G75: chmod opens RFILE as any path.
        at("G12 G72", "chmod --reference=|", filesAfter("--reference=")),
        at("G75", "chmod --reference=/no/such/pre|", filesAfter("--reference=")),
        at("G72", "chmod --reference |", FILES),
    )

    @Test
    fun `a cluster never offers a mode letter or itself`() {
        // E17, E22: `chmod -Rw f`: "invalid mode: '-Rw'", so no mode letter continues a flag cluster; a cluster offers
        // only its continuations.
        chmod.assertOffersNone("E22 K7u", "chmod -R|", listOf("-Rw", "-Rr", "-Rx", "-R7", "-R+", "-R"))
        chmod.assertOffersNone("E22", "chmod -cf|", listOf("-cf", "-cfc", "-cff"))
    }

    @Test
    fun `a completed word asked again offers itself`() = chmod.checkTab(
        // E17: a whole name and a whole candidate stay what they are.
        at("E17", "chmod --recursive|", name("--recursive")),
        at("E17", "chmod --verbose|", name("--verbose")),
        at("E17", "chmod 755|", modes("755")),
    )

    @Test
    fun `a line once a suggestion is taken is one chmod reads that way`() {
        // E15: `chmod --recursive u+x d`, `chmod 755 f` and `chmod --reference r f` run as completed.
        chmod.checkTab(
            at("E15", "chmod --rec|", name("--recursive")),
            at("E15", "chmod 75|", modes("75")),
        )
        parity.binds(
            "--recursive", "u+x", "d",
            expected = NOTHING_BOUND.copy(recursive = true, mode = symbolic("u+x"), files = listOf("d")),
        )
        parity.binds("755", "f", expected = NOTHING_BOUND.copy(mode = octal("755"), files = listOf("f")))
        parity.binds("--reference", "r", "f", expected = NOTHING_BOUND.copy(reference = "r", files = listOf("f")))
    }

    @Test
    fun `the next word after a taken suggestion completes its own slot`() = chmod.checkTab(
        // E16: --recursive leaves the MODE operand next, --reference its file, a mode the first FILE.
        at("E16", "chmod --recursive |", modes()),
        at("E16", "chmod --reference |", FILES),
        at("E16", "chmod 755 |", FILES),
    )

    @Test
    fun `a completion script's call reaches chmod as its own words`() {
        // chmod declines klap's completion, so there is no __complete for a script to call.
        parity.rejects("__complete", "--cursor", "1", "--", "-", because = "real chmod: unrecognized option '--cursor'")
    }

    @Test
    fun `each name is described as its own help row describes it`() {
        // E20: the completion description is the help text the fixture's --help row shows, less klap's annotations.
        val page = parity.helpPage("--help", because = "chmod --help prints the help")
        checkCells(chmod.suggestionsFor("chmod -").mapNotNull { it.description }.distinct()) { description ->
            assertTrue(description in page, "E20 `$description` is on no row of the help page:\n$page")
        }
    }

    @Test
    fun `a dash offers chmod's names in chmod's words and nothing else`() = chmod.checkTab(
        // G1a, G1b, G4, G6a, G45, G101, E5, E6: `chmod --help` lists every name with its words; `chmod --json`,
        // `--color`, `--completion`, `--docs` and `--traverse-arg-links`: "unrecognized option".
        at("G1a G1b G4 G6a G45 E5 E6", "chmod -|", names("-")),
        at("G1a G1b G4 G6a G45 E5 E6", "chmod --|", names("--")),
        at("G1a G4 E21", "chmod -| u+x f", names("-")),
    )

    @Test
    fun `a built-in is described in chmod's words`() = chmod.checkTab(
        // G6b, G45, G66: `chmod --help`: "--help  display this help and exit", "--version  output version information
        // and exit".
        at("G6b G45", "chmod --he|", name("--help")),
        at("G45 G66", "chmod --vers|", name("--version")),
    )

    @Test
    fun `both longs of -f carry one description`() = chmod.checkTab(
        // G28: `chmod --help`: "-f, --silent, --quiet  suppress most error messages".
        at("G28", "chmod --q|", name("--quiet")),
        at("G28", "chmod --quiet|", name("--quiet")),
    )

    @Test
    fun `the built-ins come last and are only chmod's`() {
        // G18, G11: `chmod --help` ends with "--help" and "--version"; klap's -h, --json, --color, --completion and
        // --docs are not chmod's.
        val names = chmod.suggestionsFor("chmod -").map { it.value }
        assertEquals(listOf("--help", "--version"), names.takeLast(2), "G18 `chmod -|`: the built-ins come last")
        assertTrue(names.none { it in LONGS_CHMOD_LACKS }, "G11 `chmod -|`: $names")
    }

    @Test
    fun `the help page describes every input in chmod's words`() {
        // E20, G6a, G50: each row of `chmod --help`, its wrapped lines joined with one space.
        val page = parity.helpPage("--help", because = "chmod --help prints the help")
        checkCells(INPUTS.map { it.help } + listOf(HELP_HELP, VERSION_HELP)) { help ->
            assertTrue(help in page, "E20 `$help` is not on the help page:\n$page")
        }
    }
}
