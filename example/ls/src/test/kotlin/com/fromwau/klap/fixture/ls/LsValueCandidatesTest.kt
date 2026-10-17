package com.fromwau.klap.fixture.ls

import com.fromwau.klap.fixture.ls.LsLineContext.FILES
import com.fromwau.klap.fixture.ls.LsLineContext.at
import com.fromwau.klap.fixture.ls.LsLineContext.attached
import com.fromwau.klap.fixture.ls.LsLineContext.checkContext
import com.fromwau.klap.fixture.ls.LsLineContext.checkContextValues
import com.fromwau.klap.fixture.ls.LsLineContext.name
import com.fromwau.klap.fixture.ls.LsLineContext.spaced
import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test

/*
 * Every expectation is what real ls 9.12 accepts at the cursor. A cell names its V rows (and the K, L or G rows it
 * meets) and is typed with `|` at the cursor. The evidence is a run of real ls inside a throwaway directory unless a
 * comment says `ls --help`. ls's one provider offers --time-style's fixed words (V4a, V4b), and ls declares no
 * file-valued option and no hidden input, so the V rows of other providers (V4c to V11, V31 to V94), of file
 * values (V3b to V3g, V22, V23, V25 to V29) and of hidden inputs (V29, V30) have no ls cell; its operands are files
 * (V3a, V24).
 */

private val NOTHING: Array<Suggestion> = emptyArray()

/**
 * Where the candidates for an ls value come from (V of the testing matrix): `--format`'s and `--sort`'s words in ls's
 * case, `--color`'s and `--classify`'s WHEN attached only, nothing for a pattern or a number, the shell's file
 * completion for every operand, and the operand slot's source wherever no option took the word.
 */
class LsValueCandidatesTest {

    private val ls = lsCli().cli

    @Test
    fun `every operand completes through the shell`() = ls.checkContext(
        // V3a, V24: a file is any word, as many as given (`ls a b` lists both).
        at("V3a V24", "ls |", FILES),
        at("V24", "ls a |", FILES),
        at("V24", "ls a b |", FILES),
        at("V24", "ls a b c|", FILES),
        at("V24 L2", "ls a | b", FILES),
        at("V24 K79", "ls -l |", FILES),
        at("V24 K79", "ls -l a|", FILES),
    )

    @Test
    fun `a value in its own word is a word of ls's with no description`() = ls.checkContext(
        // V2a, V2d, G7, G41: `ls --format long a` and `ls --sort time a` run; the spaced form carries no description.
        at("V2a V2d", "ls --format |", *spaced("--format")),
        at("V2a V2d", "ls --format c|", *spaced("--format", "c")),
        at("V2a V2d L2", "ls --format s| a b", *spaced("--format", "s")),
        at("V2a V2d", "ls --sort |", *spaced("--sort")),
        at("V2a", "ls --sort t|", *spaced("--sort", "t")),
        at("V2a", "ls --sort ve|", *spaced("--sort", "ve")),
        // V14: `ls --format v a`: "ambiguous argument 'v'"; Tab offers both words it starts.
        at("V14", "ls --format v|", *spaced("--format", "v")),
        at("V14", "ls --sort n|", *spaced("--sort", "n")),
    )

    @Test
    fun `an attached value is filtered on the part after the equals sign`() = ls.checkContextValues(
        // V2a, V2d, V13: `ls --format=long a` and `ls --sort=t a` run; the values come in ls's own case.
        at("V2a V2d", "ls --format=|", *attached("--format")),
        at("V2a V2d", "ls --format=l|", *attached("--format", "l")),
        at("V2a V2d L2", "ls --format=s| a b", *attached("--format", "s")),
        at("V2a", "ls --sort=|", *attached("--sort")),
        at("V2a", "ls --sort=e|", *attached("--sort", "e")),
        at("V2a", "ls --sort=ti|me", *attached("--sort", "ti")),
    )

    @Test
    fun `a value typed in capitals is not offered`() = ls.checkContext(
        // V13, V2c: `ls --color=ALWAYS sub`, `--color=Always` and `ls --sort=SIZE`: "invalid argument"; ls matches its
        // values in their own case.
        at("V13 V2c", "ls --color=AL|", *NOTHING),
        at("V13 V2c", "ls --color=Al|", *NOTHING),
        at("V13 V2c", "ls --sort=SI|", *NOTHING),
        at("V13 V2c", "ls --format=LO|", *NOTHING),
        at("V13 V2c", "ls --format LO|", *NOTHING),
        at("V13 V2c", "ls --classify=NE|", *NOTHING),
    )

    @Test
    fun `an optional value leaves the spaced word to the operands`() = ls.checkContext(
        // V18, V72, K11b: `ls --color always`: "cannot access 'always'"; `ls --classify never sub` lists never and sub.
        at("V18 K11b", "ls --color |", FILES),
        at("V18 K11b L2", "ls --color | a b", FILES),
        at("V18 K11b", "ls --color al|", FILES),
        at("V18 K11b", "ls --classify |", FILES),
        at("V18 K11b", "ls --classify ne|", FILES),
        at("V18 K11b", "ls --color -- |", FILES),
    )

    @Test
    fun `a pattern or a number offers nothing`() = ls.checkContext(
        // V1a: `ls -I '*.o' a`, `ls --hide=x a`, `ls -w 40 a` and `ls -T 4 a` take free text.
        at("V1a", "ls -I |", *NOTHING),
        at("V1a", "ls -I *.|", *NOTHING),
        at("V1a", "ls --ignore=|", *NOTHING),
        at("V1a", "ls --hide=*|", *NOTHING),
        at("V1a", "ls --hide |", *NOTHING),
        at("V1a", "ls -w |", *NOTHING),
        at("V1a", "ls --width=4|", *NOTHING),
        at("V1a", "ls -T |", *NOTHING),
        at("V1a", "ls --tabsize=|", *NOTHING),
        // V97: `ls -rw 40 a` lists a reversed in 40 columns: the spaced value after a cluster.
        at("V97", "ls -rw |", *NOTHING),
        at("V97", "ls -rI |", *NOTHING),
    )

    @Test
    fun `an ambiguous abbreviation leaves the next word without candidates`() = ls.checkContext(
        // V96, G90, K34: `ls --s a`: "option '--s' is ambiguous"; `ls --re a`: "option '--re' is ambiguous".
        at("V96 G90", "ls --s |", *NOTHING),
        at("V96 G90 L2", "ls --s | a", *NOTHING),
        at("V96 G90", "ls --s=|", *NOTHING),
        at("V96 G90", "ls --re=a|", *NOTHING),
    )

    @Test
    fun `an unknown option claims no word so the next one is an operand`() = ls.checkContext(
        // V96, K47: `ls --zzz a` and `ls --colour a`: "unrecognized option"; it claims no word.
        at("V96 K47", "ls --zzz |", FILES),
        at("V96 K47 L2", "ls --zzz | a b", FILES),
        at("V21 K47", "ls --colour |", FILES),
    )

    @Test
    fun `an abbreviated long keeps the typed spelling as its head`() = ls.checkContextValues(
        // V95: `ls --form=long a`, `ls --fo=c a` and `ls --so=t a` run.
        at("V95", "ls --form=lo|", *attached("--format", "lo", spelled = "--form")),
        at("V95", "ls --fo=c|", *attached("--format", "c", spelled = "--fo")),
        at("V95", "ls --so=t|", *attached("--sort", "t", spelled = "--so")),
    )

    @Test
    fun `an abbreviated long reaches its value in its own word`() = ls.checkContext(
        // V95, K33: `ls --fo long a` lists long; `ls --so size a` by size.
        at("V95 K33", "ls --fo |", *spaced("--format")),
        at("V95 K33", "ls --form lo|", *spaced("--format", "lo")),
        at("V95 K33", "ls --so |", *spaced("--sort")),
        at("V95 K33", "ls --col |", FILES),
    )

    @Test
    fun `a word an option took is no slot of its own`() = ls.checkContext(
        // V98: `ls -I --sort a` hides the file --sort and lists a, so the cursor is on an operand.
        at("V98", "ls -I --sort |", FILES),
        at("V98", "ls --hide --format |", FILES),
        at("V98", "ls -I -- |", FILES),
    )

    @Test
    fun `an option value typed with dashes answers from the option`() = ls.checkContext(
        // V63: `ls --format --h a` is "invalid argument '--h' for '--format'", `ls -I -l` hides -l: the option's own
        // source answers, never names.
        at("V63", "ls --format --h|", *NOTHING),
        at("V63", "ls --format -|", *NOTHING),
        at("V63", "ls -I -|", *NOTHING),
        at("V63", "ls --hide --|", *NOTHING),
    )

    @Test
    fun `after the end of options a dash-led word is an operand`() = ls.checkContext(
        // V61: `ls -- -a`: "cannot access '-a'".
        at("V61", "ls -- -a|", FILES),
        at("V61", "ls -- --format=|", FILES),
    )

    @Test
    fun `a glued short with nothing glued yet is a name`() = ls.checkContextValues(
        // V99: `ls -w` is "option requires an argument -- 'w'": `-w` is a name until a value is glued on.
        at("V99 K6", "ls -w|", name("-w")),
        at("V99 K6 L2", "ls -w| 40 a", name("-w")),
        at("V99 K6", "ls -I|", name("-I")),
    )

    @Test
    fun `every word ls reads as a WHEN is offered after the equals sign`() = ls.checkContextValues(
        // V2a, V14, V18: `ls --color=bogus`: "Valid arguments are: 'always', 'yes', 'force' / 'never', 'no', 'none' /
        // 'auto', 'tty', 'if-tty'"; `--color=y`, `=fo`, `=no`, `=tty` and `=if` run. `no` is a prefix of `none`, so it
        // offers both.
        at("V2a V18", "ls --color=|", *attached("--color")),
        at("V2a V18 L2", "ls --color=| a b", *attached("--color")),
        at("V14", "ls --color=n|", *attached("--color", "n")),
        at("V14", "ls --color=no|", *attached("--color", "no")),
        at("V2a", "ls --color=y|", *attached("--color", "y")),
        at("V2a", "ls --color=t|", *attached("--color", "t")),
        at("V2a", "ls --classify=|", *attached("--classify")),
        at("V2a", "ls --classify=i|", *attached("--classify", "i")),
        at("V95", "ls --col=f|", *attached("--color", "f", spelled = "--col")),
    )

    @Test
    fun `an attached value is described by its option's ls help`() = ls.checkContext(
        // V2d, G5: `ls --help`: --format "across,horizontal (-x), commas (-m), long (-l), single-column (-1), verbose
        // (-l), vertical (-C)", --sort "change default 'name' sort to WORD: none (-U), ...", --color "color the output
        // WHEN; more info below".
        at("V2d G5", "ls --format=|", *attached("--format")),
        at("V2d G5", "ls --sort=|", *attached("--sort")),
        at("V2d G5", "ls --color=al|", *attached("--color", "al")),
        at("V95 G5", "ls --form=lo|", *attached("--format", "lo", spelled = "--form")),
    )

    @Test
    fun `every value option ls has offers its words`() = ls.checkContextValues(
        // V2a: `ls --time=bogus`, `ls -l --time-style=bogus`, `ls --quoting-style=bogus`, `ls
        // --indicator-style=bogus` and `ls --hyperlink=bogus` list their valid arguments; each runs with any of them.
        at("V2a", "ls --time=|", *attached("--time")),
        at("V2a", "ls --time |", *spaced("--time")),
        at("V2a V4b", "ls --time-style=|", *attached("--time-style")),
        at("V4a", "ls --time-style l|", *spaced("--time-style", "l")),
        at("V2a V2b V12", "ls --quoting-style=sh|", *attached("--quoting-style", "sh")),
        at("V2a", "ls --indicator-style |", *spaced("--indicator-style")),
        at("V2a V18", "ls --hyperlink=|", *attached("--hyperlink")),
        at("V1a", "ls --block-size |", *NOTHING),
    )
}
