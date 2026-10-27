package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.chmod.ChmodTab.FILES
import com.fromwau.klap.fixture.chmod.ChmodTab.assertOffersNone
import com.fromwau.klap.fixture.chmod.ChmodTab.assertOffersValues
import com.fromwau.klap.fixture.chmod.ChmodTab.at
import com.fromwau.klap.fixture.chmod.ChmodTab.checkTab
import com.fromwau.klap.fixture.chmod.ChmodTab.modes
import com.fromwau.klap.fixture.chmod.ChmodTab.name
import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test

/*
 * Every expectation is what real chmod 9.12 accepts at the cursor, typed with `|` there. The evidence is a run of real
 * chmod in a throwaway directory unless a comment says `chmod --help`.
 */

private val NOTHING: List<Suggestion> = emptyList()

/**
 * [spellings] as chmod describes them: what a cell about which names stay offered looks for. ChmodCompletionTest pins
 * their descriptions, so these cells compare the values alone.
 */
private fun spelled(vararg spellings: String): List<String> = spellings.toList()

/**
 * How the words around the cursor change what Tab offers on a chmod line (L and S of the testing matrix): the words
 * after the cursor, and what the line already holds, from `--reference` and a dash-led mode to repeats, overrides and
 * the end of options. Every expectation is what real chmod 9.12 accepts at the cursor.
 */
class ChmodLineContextTest {

    private val chmod = chmodCli().cli

    @Test
    fun `words after the cursor that bear on nothing leave the answer as at the end of the line`() = chmod.checkTab(
        at("L1", "chmod --verb|", name("--verbose")),
        at("L1", "chmod u+x |", FILES),
        at("L2a", "chmod --verb| -R", name("--verbose")),
        at("L2a", "chmod u+x | -R", FILES),
        at("L2b", "chmod --verb| u+x f", name("--verbose")),
        // `chmod --zzz u+x f`: "unrecognized option '--zzz'"; `chmod u+x f --reference`: "option '--reference' requires
        // an argument".
        at("L21", "chmod --verb| --zzz u+x f", name("--verbose")),
        at("L21", "chmod | --zzz", modes()),
        at("L21", "chmod --verb| u+x f --reference", name("--verbose")),
        // chmod has no --json or --color, so after the cursor they are unknown options like --zzz (L20 decided by G11).
        at("L20 L21", "chmod --verb| --json", name("--verbose")),
        at("L20 L21", "chmod | --json", modes()),
        at("L20 L21", "chmod u+x | --color=never", FILES),
    )

    @Test
    fun `operands after the cursor leave the first slot the mode and move the others to files`() = chmod.checkTab(
        // L3c, L13: `chmod MODE f g` reads the first operand as the mode and the rest as files.
        at("L3c L13", "chmod | u+x f", modes()),
        at("L13", "chmod | f", modes()),
        at("L13", "chmod |u+x f", modes()),
        at("L13", "chmod u+x | f", FILES),
        at("L13", "chmod u+x f | g", FILES),
    )

    @Test
    fun `--reference after the cursor in every spelling turns the slot into a file`() = chmod.checkTab(
        // L3d, L11, L22: `chmod f --reference=r`, `chmod f --reference r`, `chmod f --ref=r` and `chmod f --ref r` give
        // f r's mode, so every operand is a file.
        at("L3d L11", "chmod | --reference=r", FILES),
        at("L11", "chmod | --reference r", FILES),
        at("L11 L22", "chmod | --ref=r", FILES),
        at("L11 L22", "chmod | --ref r", FILES),
        at("L11", "chmod 7| --reference=r", FILES),
        at("L11", "chmod f | --reference=r", FILES),
    )

    @Test
    fun `words after -- are files and never inputs`() = chmod.checkTab(
        // L14, S28: `chmod u+x -- --reference=r` says "cannot access '--reference=r'", and `chmod u+x -- -w` "cannot
        // access '-w'": after `--` neither is an option, so the cursor word before them is still the mode.
        at("L14", "chmod | -- -w", modes()),
        at("L14 S28", "chmod | -- --reference=r", modes()),
        at("L14 S28", "chmod -- --reference=r |", FILES),
        // L4b, L4c: after `--` a flag-shaped word is the mode, never --reference waiting for its file.
        at("L4b", "chmod -- --reference |", FILES),
        at("L4c", "chmod -- --reference=|", NOTHING),
    )

    @Test
    fun `a dash-led word that --reference takes is no mode`() = chmod.checkTab(
        // L15, S11: `chmod f --reference -w`: "failed to get attributes of '-w'", so -w was RFILE.
        at("L15", "chmod | --reference -w", FILES),
        at("L15 S11", "chmod --reference -w |", FILES),
    )

    @Test
    fun `a -- that --reference takes as its file leaves the options going on`() =
        // S33: `chmod --reference -- f` reads `--` as RFILE ("failed to get attributes of '--'"), so -R after it is
        // still an option.
        chmod.assertOffersValues("S33", "chmod --reference -- -|", spelled("-R", "--recursive", "-c"))

    @Test
    fun `an ambiguous long after the cursor rules nothing out`() = chmod.checkTab(
        // L22: `chmod u+x f --re r`: "option '--re' is ambiguous", so it is no --reference and the slot stays the mode.
        at("L22", "chmod | --re r", modes()),
    )

    @Test
    fun `a lastWins partner before or after the cursor rules nothing out`() {
        // L7, S22, S4: `chmod -R -H -L u+x d` and `chmod -c -v u+x f` run, the later deciding.
        chmod.checkTab(
            at("L7", "chmod --verb| -c", name("--verbose")),
            at("L7", "chmod --chan| -v", name("--changes")),
        )
        checkCells(
            listOf(
                "chmod -| -P" to spelled("-H", "-L", "-P"),
                "chmod -H -|" to spelled("-L", "-P", "-H"),
                "chmod -| -c" to spelled("-v", "--verbose", "-c"),
                "chmod -c -|" to spelled("-v", "--verbose"),
            ),
        ) { (typed, wanted) -> chmod.assertOffersValues("L7 S22", typed, wanted) }
    }

    @Test
    fun `a flag given before is offered again as chmod takes a repeat`() = checkCells(
        // S16: `chmod -v -v u+x f` and `chmod -R -R u+x d` run.
        listOf("chmod -v -|" to spelled("-v", "--verbose"), "chmod -R -|" to spelled("-R", "--recursive")),
    ) { (typed, wanted) -> chmod.assertOffersValues("S16", typed, wanted) }

    @Test
    fun `--reference before the cursor in every spelling leaves no mode slot`() = chmod.checkTab(
        // S27, S20: `chmod --reference=r f`, `--reference r f`, `--ref=r f`, `--ref r f` give f r's mode; a second
        // --reference is taken, the later one winning (`chmod --reference=r --reference=g f` gives f g's 644).
        at("S27", "chmod --reference=r |", FILES),
        at("S27", "chmod --reference r |", FILES),
        at("S27", "chmod --ref=r |", FILES),
        at("S27", "chmod --ref r |", FILES),
        at("S27", "chmod --reference=r 7|", FILES),
    )

    @Test
    fun `a dash-led mode fills the mode slot and options go on`() {
        // S31, S35: `chmod -w f` makes f 444 and `chmod -R -w -v d` changes d; options permute among the operands.
        chmod.checkTab(
            at("S31", "chmod -w |", FILES),
            at("S31", "chmod -R -w |", FILES),
            at("S35", "chmod u+x f --verb|", name("--verbose")),
        )
        chmod.assertOffersValues("S31", "chmod -R -w -|", spelled("-c", "-f", "-v", "-R", "--recursive"))
    }

    @Test
    fun `an unknown or ambiguous long before the cursor`() = chmod.checkTab(
        // S45: an unknown long claims no word (K47); after an ambiguous one nothing is accepted (K34).
        at("S45", "chmod --zzz |", modes()),
        at("S45", "chmod --re |", NOTHING),
    )

    @Test
    fun `a dash-led mode after the cursor turns the slot into a file`() = chmod.checkTab(
        // L3, L13: `chmod u+x -w f` leaves f at 444 and says "cannot access 'u+x'": -w was the mode, u+x a file.
        at("L13", "chmod | -w", FILES),
        at("L13", "chmod | -w f", FILES),
        at("L13", "chmod 7| -w", FILES),
    )

    @Test
    fun `--reference is not offered after a dash-led mode`() = chmod.assertOffersNone(
        // G98, S5: `chmod -w --reference=r f` and `chmod --reference=r -w f`: "cannot combine mode and --reference
        // options".
        "G98 S5",
        "chmod -w --ref|",
        listOf("--reference"),
    )
}
