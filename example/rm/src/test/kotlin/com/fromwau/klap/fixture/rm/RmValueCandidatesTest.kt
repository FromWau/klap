package com.fromwau.klap.fixture.rm

import com.fromwau.klap.fixture.rm.RmLineContext.FILES
import com.fromwau.klap.fixture.rm.RmLineContext.at
import com.fromwau.klap.fixture.rm.RmLineContext.attached
import com.fromwau.klap.fixture.rm.RmLineContext.checkContext
import com.fromwau.klap.fixture.rm.RmLineContext.whenValues
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/*
 * Every expectation is what real rm 9.12 accepts at the cursor. A cell names its V rows (and the K, L or G rows it
 * meets) and is typed with `|` at the cursor. The evidence is a run of real rm inside a throwaway directory unless a
 * comment says `rm --help`. rm declares no completion provider, no value-taking short and no option whose value is
 * free text or a path, so the V rows of providers (V4 to V11, V31 to V94), of a glued or clustered short value (V3d,
 * V3e, V97, V99, V100), of free text (V1a) and of a directory value (V27) have no rm cell.
 */

private val NOTHING: Array<Suggestion> = emptyArray()

/** `--preserve-root`'s one value attached to [spelled], described by rm's help for it. */
private fun all(spelled: String = "--preserve-root"): Suggestion = attached(spelled, "all", "--preserve-root")

/**
 * Where the candidates for an rm value come from (V of the testing matrix): `--interactive`'s values in rm's case,
 * `--preserve-root`'s `all`, the shell's file completion for every operand, and the operand slot's source wherever
 * no option took the word. Every expectation is what real rm 9.12 accepts at the cursor.
 */
class RmValueCandidatesTest {

    private val rm = rmCli().cli

    @Test
    fun `every operand completes through the shell`() = rm.checkContext(
        // V3a, V24: a file is any word, as many as given (`rm a b` removes both).
        at("V3a V24", "rm |", FILES),
        at("V24", "rm a |", FILES),
        at("V24", "rm a b |", FILES),
        at("V24", "rm a b c|", FILES),
        at("V24 L2", "rm a | b", FILES),
        at("V24 K78", "rm -f |", FILES),
        at("V24 K78", "rm -f a|", FILES),
    )

    @Test
    fun `an attached --interactive value is filtered on the part after the equals sign`() = rm.checkContext(
        // V2a, V2d, V13: `rm --interactive=al a`, `=o` and `=nev` run; the values come in rm's own case.
        at("V2a V2d", "rm --interactive=a|", *whenValues("a")),
        at("V2a V2d", "rm --interactive=o|", *whenValues("o")),
        at("V2a V2d L2", "rm --interactive=al| a b", *whenValues("al")),
        at("V2a", "rm --interactive=nev|", *whenValues("nev")),
        at("V2a", "rm --interactive=ne|ver", *whenValues("ne")),
    )

    @Test
    fun `a value typed in capitals is not offered`() = rm.checkContext(
        // V13, V2c: `rm --interactive=NEVER a`, `=Never` and `=ALWAYS`: "invalid argument"; `rm --preserve-root=ALL a`:
        // "unrecognized --preserve-root argument: 'ALL'". rm matches its values in their own case.
        at("V13 V2c", "rm --interactive=NE|", *NOTHING),
        at("V13 V2c", "rm --interactive=Ne|", *NOTHING),
        at("V13 V2c", "rm --interactive=AL|", *NOTHING),
        at("V13 V2c", "rm --preserve-root=AL|", *NOTHING),
    )

    @Test
    fun `an optional value leaves the spaced word to the operands`() = rm.checkContext(
        // V18, V72, K11b: `rm --interactive never a` prompts for the files never and a; `rm --preserve-root all`
        // removes the file all.
        at("V18 K11b", "rm --interactive |", FILES),
        at("V18 K11b L2", "rm --interactive | a b", FILES),
        at("V18 K11b", "rm --interactive nev|", FILES),
        at("V18 K11b", "rm --preserve-root |", FILES),
        at("V18 K11b", "rm --preserve-root al|", FILES),
        at("V18 K11b", "rm --interactive -- |", FILES),
    )

    @Test
    fun `the bare value of --interactive is not put first`() {
        // `rm --help`: "prompt according to WHEN: never, once (-I), or always (-i); without WHEN, prompt always": the
        // bare value always comes last of the three.
        val offered = rm.suggestionsFor("rm --interactive=").map { it.value }
        assertTrue("--interactive=always" in offered, "V18 `rm --interactive=|`: always is offered: $offered")
        assertNotEquals("--interactive=always", offered.first(), "V18 `rm --interactive=|`: always is put first")
    }

    @Test
    fun `an ambiguous abbreviation leaves the next word without candidates`() = rm.checkContext(
        // V96, G90, K34: `rm --v a` and `rm --ver a`: "option '--v' is ambiguous; possibilities: '--verbose'
        // '--version'", whatever follows.
        at("V96 G90", "rm --v |", *NOTHING),
        at("V96 G90 L2", "rm --ver | a", *NOTHING),
        at("V96 G90", "rm --v=|", *NOTHING),
        at("V96 G90", "rm --ve=a|", *NOTHING),
    )

    @Test
    fun `an unknown option claims no word so the next one is an operand`() = rm.checkContext(
        // V20, V21, V96, K47: `rm --color a`, `rm --completion bash a`, `rm --docs a` and `rm --zzz a`: "unrecognized
        // option"; an unknown option claims no word, so no color mode or shell name is offered after it.
        at("V96 K47", "rm --zzz |", FILES),
        at("V96 K47 L2", "rm --zzz | a b", FILES),
        at("V20 K47", "rm --color |", FILES),
        at("V21 K47", "rm --completion |", FILES),
        at("V21 K47", "rm --completion b|", FILES),
        at("V21 K47", "rm --docs |", FILES),
    )

    @Test
    fun `--preserve-root offers all attached in every spelling`() = rm.checkContext(
        // V2a, V2d, V95: `rm --preserve-root=all a`, `rm --p=all a` and `rm --pre=all a` run, described by rm's help.
        at("V2a V2d", "rm --preserve-root=|", all()),
        at("V2a", "rm --preserve-root=a|", all()),
        at("V2a L2", "rm --preserve-root=al| a", all()),
        at("V95", "rm --p=|", all("--p")),
        at("V95", "rm --pre=a|", all("--pre")),
    )

    @Test
    fun `an abbreviated --interactive keeps the typed spelling as its head`() = rm.checkContext(
        // V95: `rm --inter=al a` prompts, `rm --i=o a b c e` asks once.
        at("V95", "rm --inter=al|", *whenValues("al", "--inter")),
        at("V95", "rm --i=o|", *whenValues("o", "--i")),
        at("V95 K11b", "rm --inter |", FILES),
    )

    @Test
    fun `a built-in rm does not have takes no attached value`() = rm.checkContext(
        // V20, K46: `rm --color=never a` and `rm --col=never a`: "unrecognized option".
        at("V20 K46", "rm --color=|", *NOTHING),
        at("V20 K46", "rm --color=al|", *NOTHING),
        at("V20 K46", "rm --col=|", *NOTHING),
    )

    @Test
    fun `every word rm reads as a WHEN is offered after --interactive=`() = rm.checkContext(
        // V2a, V14, V18: `rm --interactive=bogus a`: "Valid arguments are: 'never', 'no', 'none' / 'once' / 'always',
        // 'yes'"; `=no` and `=none` remove without asking, `=yes` prompts. `no` is a prefix of `none`, so it offers
        // itself and none.
        at("V2a V18", "rm --interactive=|", *whenValues("")),
        at("V2a V18 L2", "rm --interactive=| a b", *whenValues("")),
        at("V14", "rm --interactive=n|", *whenValues("n")),
        at("V14", "rm --interactive=no|", *whenValues("no")),
        at("V2a", "rm --interactive=y|", *whenValues("y")),
    )
}
