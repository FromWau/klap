package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.chmod.ChmodTab.Cell
import com.fromwau.klap.fixture.chmod.ChmodTab.FILES
import com.fromwau.klap.fixture.chmod.ChmodTab.assertOffersAll
import com.fromwau.klap.fixture.chmod.ChmodTab.at
import com.fromwau.klap.fixture.chmod.ChmodTab.checkTab
import com.fromwau.klap.fixture.chmod.ChmodTab.continuations
import com.fromwau.klap.fixture.chmod.ChmodTab.modes
import com.fromwau.klap.fixture.chmod.ChmodTab.name
import com.fromwau.klap.fixture.chmod.ChmodTab.names
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import kotlin.test.Test

/*
 * Every expectation is what real chmod 9.12 accepts at the cursor, typed with `|` there. A cell names its K row; the
 * generated cells cover every name and letter of chmod. The evidence is a run of real chmod in a throwaway directory
 * unless a comment says `chmod --help`.
 */

private val NOTHING: List<Suggestion> = emptyList()

/** A name cell on [line] with the cursor at [cursor], offering every chmod name the typed part starts. */
private fun nameCell(
    rows: String,
    line: String,
    cursor: Int,
): Cell {
    val start = line.lastIndexOf(' ', cursor - 1) + 1
    return Cell(rows, line.take(cursor) + "|" + line.drop(cursor), names(line.substring(start, cursor)))
}

/** The generated name cells: every long of chmod at the end, inside, abbreviated, and with words after it. */
private val NAME_CELLS: List<Cell> = CHMOD_LONGS.flatMap { long ->
    val line = "chmod $long"
    listOfNotNull(
        nameCell("K2 K5", line, line.length),
        nameCell("K2 K5 words after", "$line u+x f", line.length),
        nameCell("K2 K5 inside", line, line.length - 1),
        abbreviationOf(long)?.let { nameCell("K15a", "chmod $it", "chmod $it".length) },
    )
}

/** The generated letter cells: every short of chmod alone, after another letter, inside, with words after. */
private val LETTER_CELLS: List<Cell> = INPUTS.mapNotNull { it.short }.flatMap { letter ->
    val partner = if (letter == 'f') 'R' else 'f'
    listOf(
        at("K3 K7a", "chmod -$letter|", continuations("-$letter")),
        at("K3 K7a words after", "chmod -$letter| u+x f", continuations("-$letter")),
        at("K7b", "chmod -$partner$letter|", continuations("-$partner$letter")),
        at("K7b inside", "chmod -$partner$letter|z", continuations("-$partner$letter")),
        at("K7b words after", "chmod -$partner$letter| u+x f", continuations("-$partner$letter")),
    )
}

/** Cells where the cursor starts a word: the rest of the word is not sent, so the slot is the MODE operand. */
private val WORD_START_CELLS: List<Cell> = (CHMOD_LONGS + INPUTS.mapNotNull { it.short?.let { s -> "-$s" } })
    .map { at("K2 K3 K5 start", "chmod |$it", modes()) }

/**
 * What Tab completes at each slot and cursor column of a chmod line (K and K7 of the testing matrix, with their cursor
 * columns), checked against what real chmod 9.12 would accept there: the exact suggestions with their descriptions.
 * A slot cell names its K row; the generated cells cover every input of chmod.
 */
class ChmodCompletionTest {

    private val chmod = chmodCli().cli

    // --- generated cells, every input ---

    @Test
    fun `a long name completes to every long of chmod it starts in chmod's words`() = chmod.checkTab(NAME_CELLS)

    @Test
    fun `the start of a name word is the MODE operand's slot`() = chmod.checkTab(WORD_START_CELLS)

    // --- K2 to K6, K13, K15, K34, K45 to K47: names and what follows them ---

    @Test
    fun `names are not offered before a dash`() = chmod.checkTab(
        at("K2 no word", "chmod |", modes()),
        at("K2 no word", "chmod -R |", modes()),
        at("K3 start", "chmod |-v", modes()),
        at("K13a start", "chmod |--help", modes()),
    )

    @Test
    fun `nothing completes after an ambiguous long`() = chmod.checkTab(
        // K34: `chmod --re 7 nofile`: "option '--re' is ambiguous; possibilities: '--recursive' '--reference'".
        at("K34", "chmod --re |", NOTHING),
        at("K34", "chmod --re 7|", NOTHING),
        at("K34 words after", "chmod --re | f", NOTHING),
        at("K34", "chmod --r |", NOTHING),
        at("K34", "chmod --no- |", NOTHING),
    )

    @Test
    fun `a flag or an unknown or ambiguous long has no value to complete`() = chmod.checkTab(
        // `chmod --verbose=x u+x f`: "option '--verbose' doesn't allow an argument"; `chmod --zzz=a`: "unrecognized
        // option"; `chmod --re=7`: "option '--re=7' is ambiguous".
        at("K45", "chmod --verbose=|", NOTHING),
        at("K45 inside", "chmod --verbose=|x", NOTHING),
        at("K45 words after", "chmod --verbose=| u+x f", NOTHING),
        at("K45", "chmod --no-dereference=|", NOTHING),
        at("K46", "chmod --zzz=|", NOTHING),
        at("K46", "chmod --zzz=a|", NOTHING),
        at("K46 ambiguous", "chmod --re=7|", NOTHING),
        at("K46 words after", "chmod --zzz=| f", NOTHING),
    )

    @Test
    fun `the word after an unknown long is the MODE operand`() = chmod.checkTab(
        // K47: `--zzz` claims no word (`chmod --zzz u+x f`: "unrecognized option '--zzz'"), so the next word is MODE.
        at("K47", "chmod --zzz |", modes()),
        at("K47", "chmod --zzz 7|", modes("7")),
        at("K47 words after", "chmod --zzz | f", modes()),
        // G68, K60: chmod has no --color or --json, so the word after either is MODE as after any unknown long.
        at("K54 G68", "chmod --color |", modes()),
        at("K60", "chmod --json |", modes()),
        at("K60", "chmod --color=never |", modes()),
    )

    @Test
    fun `a name chmod does not have offers nothing`() = chmod.checkTab(
        // `chmod --help-all`: "unrecognized option '--help-all'".
        at("K13c G65", "chmod --help-|", NOTHING),
        at("K24 G15", "chmod --zzz|", NOTHING),
    )

    // --- K9, K33, K8a, K40 to K42: --reference's value ---

    @Test
    fun `an attached --reference completes as a file wherever the cursor stands in the value`() = chmod.checkTab(
        // `chmod --reference=r f` and `chmod --ref=r f` give f r's mode.
        at("K9", "chmod --reference=r|", filesAfter("--reference=")),
        at("K9 inside", "chmod --reference=r|x", filesAfter("--reference=")),
        at("K9 right after the =", "chmod --reference=|r", filesAfter("--reference=")),
        at("K9 no word", "chmod --reference=|", filesAfter("--reference=")),
        at("K9 words after", "chmod --reference=| f", filesAfter("--reference=")),
        at("K9 K15b abbreviated", "chmod --ref=r|", filesAfter("--ref=")),
        at("K9 K15b abbreviated", "chmod --refer=|", filesAfter("--refer=")),
        at("K9 start", "chmod |--reference=r", modes()),
        // K43: `chmod --reference=a=b f`: "failed to get attributes of 'a=b'"; the path is all after the first `=`.
        at("K43", "chmod --reference=a=b|", filesAfter("--reference=")),
    )

    @Test
    fun `a spaced --reference completes as a file wherever the cursor stands`() = chmod.checkTab(
        // `chmod --reference r f` and `chmod --ref r f` give f r's mode.
        at("K8a", "chmod --reference r|", FILES),
        at("K8a inside", "chmod --reference r|x", FILES),
        at("K8a start", "chmod --reference |r", FILES),
        at("K8a no word", "chmod --reference |", FILES),
        at("K8a words after", "chmod --reference | f", FILES),
        at("K8a a dash-led word after", "chmod --reference | -R f", FILES),
        at("K33", "chmod --ref r|", FILES),
        at("K33 no word", "chmod --ref |", FILES),
        at("K33 words after", "chmod --ref | f", FILES),
    )

    @Test
    fun `a dash-led word in --reference's slot is that file`() = chmod.checkTab(
        // K40, K41, K42: `chmod --reference -w f`, `--reference -- f` and `--reference --help f`: "failed to get
        // attributes of '-w'", of '--', of '--help': getopt gave the next word to --reference whatever it spelled.
        at("K40", "chmod --reference -w|", FILES),
        at("K40 inside", "chmod --reference -|w", FILES),
        at("K40 words after", "chmod --reference -w| f", FILES),
        at("K41", "chmod --reference --|", FILES),
        at("K41 no word", "chmod --reference -- |", FILES),
        at("K41 a name after", "chmod --reference -- --verb|", names("--verbose")),
        at("K42", "chmod --reference --help |", FILES),
        at("K42", "chmod --reference --verbose |", FILES),
    )

    // --- K12, K14, K17, K29, K58, K59, K79, K83, K85: operand slots ---

    @Test
    fun `the first operand completes the mode candidates wherever the cursor stands`() = chmod.checkTab(
        // `chmod --help`: "chmod [OPTION]... MODE[,MODE]... FILE..."; each candidate is a mode chmod accepts.
        at("K12a", "chmod 7|", modes("7")),
        at("K12a", "chmod u|", modes("u")),
        at("K12a a whole candidate", "chmod 755|", modes("755")),
        at("K12a inside", "chmod 7|55", modes("7")),
        at("K12a start", "chmod |755", modes()),
        at("K12a no word", "chmod |", modes()),
        at("K12a words after", "chmod | f", modes()),
        at("K12a words after", "chmod 7| f", modes("7")),
        at("K79", "chmod -R 7|", modes("7")),
        at("K79 no word", "chmod -R |", modes()),
        at("K79 no word", "chmod -v -c |", modes()),
    )

    @Test
    fun `every operand after the mode completes as a file`() = chmod.checkTab(
        at("K12b", "chmod u+x fi|", FILES),
        at("K12b inside", "chmod u+x f|i", FILES),
        at("K12b start", "chmod u+x |fi", FILES),
        at("K12b no word", "chmod u+x |", FILES),
        at("K12b words after", "chmod u+x | g", FILES),
        at("K12b both sides", "chmod u+x f | g", FILES),
        at("K79", "chmod -R u+x |", FILES),
        at("K79", "chmod u+x -R |", FILES),
    )

    @Test
    fun `every operand is a file once --reference takes the mode's place`() = chmod.checkTab(
        // K12d, K83: `chmod --reference=r f`, `chmod --ref r f`: f takes r's mode; no word is a mode.
        at("K12d1", "chmod --reference=r fi|", FILES),
        at("K12d1 inside", "chmod --reference=r f|i", FILES),
        at("K12d1 start", "chmod --reference=r |fi", FILES),
        at("K12d1 no word", "chmod --reference=r |", FILES),
        at("K12d2", "chmod | --reference=r", FILES),
        at("K12d2", "chmod 7| --reference=r", FILES),
        at("K12d3", "chmod --ref=r fi|", FILES),
        at("K12d3", "chmod --reference r fi|", FILES),
        at("K83 no word", "chmod --ref r |", FILES),
        at("K83 words after", "chmod | --ref=r", FILES),
    )

    @Test
    fun `after the end of options the first operand is the mode and the rest are files`() = chmod.checkTab(
        // K14a: `chmod -- -w f` makes f 444; `chmod -- -w -- ` reads the second `--` as a file.
        at("K14a no word", "chmod -- |", modes()),
        at("K14a", "chmod -- 7|", modes("7")),
        at("K14a", "chmod -- -w|", modes("-w")),
        at("K14a inside", "chmod -- -|w", modes("-")),
        at("K14a start", "chmod -- |-w", modes()),
        at("K14a words after", "chmod -- | f", modes()),
        at("K14a", "chmod -- -w |", FILES),
        at("K14a a second --", "chmod -- -- |", FILES),
        at("K14a V61", "chmod -- -|", modes("-")),
    )

    @Test
    fun `a dash-led mode fills the MODE slot and the next word is a file`() = chmod.checkTab(
        // K17, K85: `chmod -w f` makes f 444. No mode candidate starts with a dash, so a dash-led mode word offers
        // nothing of its own, and the names the dash would start are no mode.
        at("K17a", "chmod -w|", NOTHING),
        at("K17a words after", "chmod -w| f", NOTHING),
        at("K17a start", "chmod |-w", modes()),
        at("K29", "chmod -5|", NOTHING),
        at("K29 words after", "chmod -5| f", NOTHING),
        at("K85", "chmod -w fi|", FILES),
        at("K85 no word", "chmod -w |", FILES),
        at("K85 words after", "chmod -w | g", FILES),
        at("K85", "chmod -755 |", FILES),
        at("K85", "chmod -R -w |", FILES),
        // K80, S32: `chmod - f` exits 0, so a lone `-` is the mode and the next word a file.
        at("K80 S32", "chmod - |", FILES),
        at("K80", "chmod u+x - |", FILES),
    )

    @Test
    fun `the word after a built-in chmod does not have is the MODE operand`() = chmod.checkTab(
        // K55: `chmod --completion bash`, `chmod --docs man`: "unrecognized option", so no word is their value.
        at("K55", "chmod --completion |", modes()),
        at("K55", "chmod --completion 7|", modes("7")),
        at("K55", "chmod --docs |", modes()),
        at("K55 words after", "chmod --completion | f", modes()),
        at("K55 attached", "chmod --completion=ba|", NOTHING),
    )

    @Test
    fun `a source named like klap's completion protocol completes as the mode`() = chmod.checkTab(
        // K58: chmod reads `__complete` as the mode (`chmod __complete f`: "invalid mode: '__complete'").
        at("K58", "chmod __c|", modes("__c")),
        at("K58 words after", "chmod __c| f", modes("__c")),
    )

    @Test
    fun `the words after --help complete as chmod reads them`() = chmod.checkTab(
        // K59: `chmod --help u+x f` prints the help; the words after it are still the operands.
        at("K59", "chmod --help |", modes()),
        at("K59", "chmod --help 7|", modes("7")),
        at("K59", "chmod --help u+x |", FILES),
    )

    // --- K7: clusters ---

    @Test
    fun `a cluster with a mode letter or a letter chmod does not know ends what it can become`() = chmod.checkTab(
        // K7u, K7s, K7t: `chmod -Rw f`: "invalid mode: '-Rw'"; `chmod -Rz f`: "invalid option -- 'z'"; `chmod -R= f`:
        // "invalid mode: '-R='"; `chmod -v= f`: "invalid mode: '-v='".
        at("K7u", "chmod -Rw|", NOTHING),
        at("K7u words after", "chmod -Rw| f", NOTHING),
        at("K7s", "chmod -Rz|", NOTHING),
        at("K7s", "chmod -zR|", NOTHING),
        at("K7t", "chmod -R=|", NOTHING),
        at("K7t", "chmod -v=|", NOTHING),
        at("G88", "chmod -cfvhRHLP|", NOTHING),
        // K7r, K27: `chmod -R7 f` is "invalid mode: '-R7'"; `chmod -+ f` and `chmod -= f` are modes, and no name or
        // candidate starts with them.
        at("K7r", "chmod -R7|", NOTHING),
        at("K27", "chmod -+|", NOTHING),
        at("K27", "chmod -=|", NOTHING),
    )

    @Test
    fun `the word after an unknown letter is the MODE operand`() = chmod.checkTab(
        // K7s, K47: `chmod -z f` and `chmod -Rz f`: "invalid option -- 'z'"; an unknown option claims no word, so the
        // next word is MODE, as after `chmod --zzz`.
        at("K7s K47", "chmod -z |", modes()),
        at("K7s K47", "chmod -Rz |", modes()),
        at("K7s K47", "chmod -z 7|", modes("7")),
    )

    @Test
    fun `a long name is described in chmod's help words`() = chmod.checkTab(
        // `chmod --help`: "-f, --silent, --quiet  suppress most error messages", "--dereference  affect the referent of
        // each symbolic link, rather than the symbolic link itself", "-h, --no-dereference  affect each symbolic link,
        // rather than the referent", "--no-preserve-root  do not treat '/' specially (the default)", "--reference=RFILE
        // use RFILE's mode instead of specifying MODE values. RFILE is always dereferenced if a symbolic link.",
        // "--help  display this help and exit", "--version  output version information and exit".
        at("K4a", "chmod --no-pres|", names("--no-pres")),
        at("L7 G62", "chmod --dereference --no-d|", names("--no-d")),
        at("K15a", "chmod --re|", names("--re")),
        at("K15a inside", "chmod --r|ec", names("--r")),
        at("K9 inside the name", "chmod --ref|erence=r", names("--ref")),
        at("K9 right before the =", "chmod --reference|=r", names("--reference")),
    )

    @Test
    fun `a cluster continues with the letters chmod accepts after it in chmod's words`() = chmod.checkTab(
        // K3, K7a, K7b, K7e2, K7i, K7j, K7u: `chmod -cfvhRHLP u+x d` exits 0, so every flag letter follows every
        // other once; chmod counts none, and `-Rh` is -R with --no-dereference.
        LETTER_CELLS +
            at("K7u", "chmod -R|", continuations("-R")) +
            at("K7u inside", "chmod -R|w", continuations("-R")) +
            at("K7s inside", "chmod -R|z", continuations("-R")) +
            at("K7e2", "chmod -cv|", continuations("-cv")) +
            at("K7c", "chmod -c -f|", continuations("-f")) +
            at("K7i K7j", "chmod -Rh|", continuations("-Rh")) +
            at("K4c K7j", "chmod -fh|", continuations("-fh")),
    )

    @Test
    fun `-h is offered as --no-dereference`() = checkCells(
        // K4c, K13b, G64, G101: `chmod --help`: "-h, --no-dereference  affect each symbolic link, rather than the
        // referent"; `chmod -Rh u+x d` exits 0.
        listOf(
            "chmod -|" to name("-h"),
            "chmod -R|" to Suggestion("-Rh", chmodHelp("-h")),
            "chmod -cf|" to Suggestion("-cfh", chmodHelp("-h")),
        ),
    ) { (typed, entry) -> chmod.assertOffersAll("K4c K13b", typed, listOf(entry)) }

    @Test
    fun `names chmod does not have are not offered`() = chmod.checkTab(
        // K13e to K13g, G11, G67: `chmod --json`, `--color`, `--completion bash`, `--docs`, `--traverse-arg-links`:
        // "unrecognized option"; `chmod --c u+x f` is --changes, `chmod --d u+x f` is --dereference.
        LONGS_CHMOD_LACKS.map { at("K13 G11", "chmod $it|", NOTHING) } +
            at("K13e", "chmod --js|", NOTHING) +
            at("K13f", "chmod --col|", NOTHING) +
            at("K13g", "chmod --comp|", NOTHING) +
            at("K13g", "chmod --do|", NOTHING) +
            at("K15a", "chmod --t|", NOTHING) +
            at("K53", "chmod --color=|", NOTHING) +
            at("K54", "chmod --col=|", NOTHING) +
            at("K3 K17c", "chmod -|", names("-")) +
            at("K2", "chmod --|", names("--")) +
            at("K2 inside", "chmod --|verb", names("--")) +
            at("K2 inside the dashes", "chmod -|-verb", names("-")) +
            at("K3 inside", "chmod -|v", names("-")) +
            at("K3 words after", "chmod -| u+x f", names("-")) +
            at("K7d", "chmod -cf -|", names("-")) +
            at("K7d words after", "chmod -cf -| u+x f", names("-")) +
            at("K14a -- after the cursor", "chmod -| -- x", names("-")) +
            at("K17a inside", "chmod -|w", names("-")),
    )

    @Test
    fun `the word after __complete is a file`() = chmod.checkTab(
        // K58: `chmod __complete f`: "invalid mode: '__complete'", so __complete took the MODE slot (todo 102).
        at("K58", "chmod __complete |", FILES),
        at("K58", "chmod __complete fi|", FILES),
    )
}
