package com.fromwau.klap.fixture.head

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.head.HeadLineContext.NOTHING
import com.fromwau.klap.fixture.head.HeadLineContext.assertOffersAll
import com.fromwau.klap.fixture.head.HeadLineContext.assertOffersNone
import com.fromwau.klap.fixture.head.HeadLineContext.at
import com.fromwau.klap.fixture.head.HeadLineContext.checkContext
import kotlin.test.Test

/** Cells whose word continues a short cluster of flags: head's other letters, each once. */
private val CLUSTER_CONTINUATIONS: List<CompletionCell> = listOf(
    // K3, K7a, K7b, K7e2: head counts no letter, so `-qv` offers no `-qvv` (`head -vv f` runs, but reads one -v).
    slot("K3", "head -q|"),
    slot("K3 words after", "head -q| a b"),
    slot("K7a", "head -z|"),
    slot("K7a words after", "head -z| a b"),
    slot("K7b", "head -qv|"),
    slot("K7b inside", "head -q|v"),
    slot("K7b words after", "head -qv| a b"),
    slot("K7e2", "head -qz|"),
    // K7c: `head -q -q a b` runs, so a letter of an earlier word is offered again.
    slot("K7c", "head -q -v|"),
    slot("K7c words after", "head -q -v| a b"),
    // K7s inside: handed `-q`, the `x` after the cursor is not sent.
    slot("K7s inside", "head -q|x"),
)

/** Cells whose word is a dash, or is handed to klap as one: every head name, in head's words, and nothing else. */
private val DASH_POOLS: List<CompletionCell> = listOf(
    slot("K3", "head -|"),
    slot("K3 inside", "head -|q"),
    slot("K3 words after", "head -| a b"),
    slot("K2", "head --|"),
    slot("K2 inside", "head --|qu"),
    slot("K2 inside the dashes", "head -|-qu"),
    slot("K5 inside", "head --|lines"),
    slot("K6 inside", "head -|n"),
    slot("K7b inside", "head -|qv"),
    slot("K7c inside", "head -q -|v"),
    slot("K7d", "head -qv -|"),
    slot("K7d words after", "head -qv -| a b"),
    slot("K14a -- after the cursor", "head -| -- x"),
    slot("K16a inside", "head -|20"),
    slot("K16a after a count", "head -5 -|"),
    slot("K29 inside", "head -v -|5"),
)

/** Hand-written cells whose answer holds the `head --help` words of -n, -c, --help or --version. */
private val WORDED_SLOTS: List<CompletionCell> = listOf(
    slot("K6", "head -n|"),
    slot("K6", "head -c|"),
    slot("K10 inside", "head -n|5"),
    slot("K5", "head --li|"),
    slot("K5", "head --by|"),
    slot("K9 inside the name", "head --li|nes=5"),
    slot("K9 before the =", "head --lines|=5"),
    slot("K13a", "head --he|"),
    slot("K13a", "head --h|"),
    slot("K13d", "head --vers|"),
    slot("K15a ambiguous", "head --ve|"),
    slot("K7a", "head -q|"),
)

/** The letters head's obsolete first word takes after its digits, as `head -5c f` reads them. */
private fun obsolete(count: String): List<String> = OBSOLETE_LETTERS.map { "$count$it" }

/**
 * What Tab completes at each slot and cursor column of a head line (K and K7 of the testing matrix, with their cursor
 * columns), checked against what real head 9.12 would accept there: the suggestions offered, by value, with the
 * descriptions asserted where a test says head's words. A slot cell names its K row; the generated cells cover every
 * input of head. head has no subcommand, negation, global, hidden input, choice, file-valued option, provider or
 * dash-led operand, and no short of its own spelled with a digit, so the K rows of those (K1, K4, K11, K17, K19, K21
 * to K25, K27, K28, K30, K35, K37 to K44, K48 to K54, K56, K57, K60 to K78, K81 to K87, K92, K93, K7i to K7q, K7u to
 * K7ah) have no head cell; K20 has none either, since no head long is a prefix of another.
 */
class HeadCompletionTest {

    private val head = headCli().cli

    // --- generated cells, every input ---

    @Test
    fun `an operand slot hands the word to the shell's file completion`() = head.checkSlots(
        cells("no word", OPERAND) + cells("word start", OPERAND) + cells("operand inside", OPERAND) +
            cells("operand end", OPERAND),
    )

    @Test
    fun `after the end of options every word is an operand`() = head.checkSlots(cells("end of options", OPERAND))

    @Test
    fun `a long name completes to every long of head it starts`() =
        head.checkSlotValues(NAME_KINDS.flatMap { cells(it, HEAD_NAME) })

    @Test
    fun `a long name head does not have and klap does not have either offers nothing`() =
        head.checkSlots(NAME_KINDS.flatMap { cells(it, UNKNOWN_NAME) }.filterNot { it.foreign })

    @Test
    fun `a letter taking a NUM ends its cluster`() =
        head.checkSlotValues(LETTER_KINDS.flatMap { cells(it, VALUE_LETTER) })

    @Test
    fun `a cluster of flags continues with every letter head accepts after it`() =
        head.checkSlotValues(LETTER_KINDS.flatMap { cells(it, LETTERS) } + CLUSTER_CONTINUATIONS)

    @Test
    fun `a NUM slot offers nothing in every form`() = head.checkSlots(VALUE_KINDS.flatMap { cells(it) })

    @Test
    fun `-q -v and -z are described in head's words wherever the cursor sits`() = head.checkSlots(
        (NAME_KINDS + LETTER_KINDS).flatMap { cells(it) }.filterNot { it.wordedLikeKlap || it.foreign },
    )

    // --- K2 to K6, K13, K15: names ---

    @Test
    fun `a long flag completes from the cursor wherever it stands in the word`() = head.checkSlots(
        // `head --qu a b`, `head --q a b` and `head --s a b` print no headers: each is unique among head's longs.
        slot("K2", "head --qu|"),
        slot("K2 inside", "head --qu|iet"),
        slot("K2 inside", "head --q|u"),
        slot("K2 start", "head |--quiet"),
        slot("K2 words after", "head --qu| a b"),
        slot("K2 a whole name", "head --quiet|"),
        slot("K15a", "head --s|"),
        slot("K15a", "head --verb|"),
        slot("K15a", "head --z|"),
    )

    @Test
    fun `an ambiguous prefix offers every long it starts`() = head.checkSlotValues(
        // K15a: `head --v f` and `head --ve f`: "option '--ve' is ambiguous; possibilities: '--verbose' '--version'".
        slot("K15a ambiguous", "head --v|"),
        slot("K15a ambiguous", "head --ve|"),
        slot("K15a ambiguous inside", "head --v|er"),
    )

    @Test
    fun `a long option's name completes from the cursor wherever it stands in the word`() = head.checkSlotValues(
        // K5: `--lines=[-]NUM` and `--bytes=[-]NUM` are offered bare.
        slot("K5", "head --li|"),
        slot("K5 inside", "head --l|in"),
        slot("K5 start", "head |--lines"),
        slot("K5 words after", "head --li| a b"),
        slot("K5 the value typed after it", "head --li| 5 f"),
        slot("K5", "head --by|"),
        slot("K5", "head --b|"),
    )

    @Test
    fun `a value-taking short is offered by itself`() = head.checkSlotValues(
        // K6, V99: `head -n` alone is "option requires an argument -- 'n'"; the word is a name until a NUM is glued.
        slot("K6", "head -n|"),
        slot("K6 words after", "head -n| 5 f"),
        slot("K6", "head -c|"),
        slot("K6 start", "head |-n"),
        // K10 inside: handed `-n`, the 5 after the cursor is not sent.
        slot("K10 inside", "head -n|5"),
    )

    @Test
    fun `names are not offered before a dash`() = head.checkSlots(
        slot("K2 no word", "head |"),
        slot("K2 no word", "head -q |"),
        slot("K3 start", "head |-q"),
        slot("K13a start", "head |--help"),
        slot("K16a start", "head |-20"),
        slot("K88 start", "head |-5v"),
        slot("K89 start", "head |-v12"),
    )

    @Test
    fun `--help and --version complete from the cursor`() = head.checkSlotValues(
        // K13a: `head --h`, `--he` and `--hel` print the usage; K13d: `head --vers` prints the version.
        slot("K13a", "head --he|"),
        slot("K13a", "head --h|"),
        slot("K13a inside", "head --h|e"),
        slot("K13a words after", "head --he| f"),
        slot("K13d", "head --vers|"),
    )

    @Test
    fun `klap's help short and --help-all are not offered`() = head.checkSlots(
        // K13b, K13c: `head -h f`: "invalid option -- 'h'"; `head --help-all`: "unrecognized option '--help-all'".
        slot("K13b", "head -h|"),
        slot("K13c", "head --help-|"),
        slot("K13c", "head --help-all|"),
    )

    // --- K8 to K10, K32 to K34, K36, K45 to K47: value slots ---

    @Test
    fun `a NUM in its own word offers nothing wherever the cursor stands`() = head.checkSlots(
        // K8a, K32, K33, K36: `head -n 5 f`, `head --lines 5 f`, `head --li 5 f` and `head -qn 5 a b` read 5 as the
        // NUM.
        slot("K32", "head -n |"),
        slot("K32", "head -n 5|"),
        slot("K32 inside", "head -n 1|0"),
        slot("K32 start", "head -n |10"),
        slot("K32 words after", "head -n | f"),
        slot("K8a", "head --lines |"),
        slot("K8a a dash-led word after", "head --lines | -q f"),
        slot("K33", "head --li |"),
        slot("K33 words after", "head --li | f"),
        slot("K36", "head -qn |"),
        slot("K36 words after", "head -qn | f"),
        slot("K32", "head -c 5|"),
    )

    @Test
    fun `an attached NUM offers nothing wherever the cursor stands`() = head.checkSlots(
        // K9: `head --lines=5 f` and `head --li=5 f` print five lines; the NUM is free text.
        slot("K9", "head --lines=5|"),
        slot("K9 abbreviated", "head --li=5|"),
        slot("K9 inside", "head --lines=1|0"),
        slot("K9 start", "head |--lines=5"),
        slot("K9 no word", "head --lines=|"),
        slot("K9 words after", "head --lines=| f"),
        slot("K9", "head --bytes=-|"),
    )

    @Test
    fun `a NUM glued to its short offers nothing`() = head.checkSlots(
        // K10, K7f: `head -n5 f` and `head -qn5 a b`: the rest of the word is the NUM.
        slot("K10", "head -n5|"),
        slot("K10 words after", "head -n5| f"),
        slot("K10", "head -c-5|"),
        slot("K7f", "head -qn|"),
        slot("K7f", "head -qn5|"),
        slot("K7f inside", "head -qn|5"),
        slot("K7f words after", "head -qn5| a b"),
        // K7g: `head -qn=5 a b`: "invalid number of lines: '=5'"; the `=` is part of the NUM.
        slot("K7g", "head -qn=|"),
        // K7h: `head -nq f`: "invalid number of lines: 'q'"; the q is -n's NUM.
        slot("K7h", "head -nq|"),
    )

    @Test
    fun `a flag or an unknown or ambiguous long has no value to complete`() = head.checkSlots(
        // K45: `head --quiet=x f`: "option '--quiet' doesn't allow an argument"; K46: `head --zzz=x`: "unrecognized
        // option", `head --v=x f`: "option '--v=x' is ambiguous".
        slot("K45", "head --quiet=|"),
        slot("K45 inside", "head --verbose=|x"),
        slot("K45 words after", "head --zero-terminated=| a b"),
        slot("K46", "head --zzz=|"),
        slot("K46", "head --zzz=a|"),
        slot("K46 ambiguous", "head --v=|"),
        slot("K46 ambiguous", "head --ve=x|"),
    )

    @Test
    fun `the word after an unknown long is an operand`() = head.checkSlots(
        // K47: `head --zzz f`: "unrecognized option '--zzz'"; it claims no word.
        slot("K47", "head --zzz |"),
        slot("K47", "head --zzz fi|"),
        slot("K47 words after", "head --zzz | b"),
    )

    @Test
    fun `nothing completes after an ambiguous long`() = head.checkSlots(
        // K34: `head --v f` and `head --ver 5`: "option '--ver' is ambiguous; possibilities: '--verbose' '--version'".
        slot("K34", "head --v |"),
        slot("K34", "head --ve |"),
        slot("K34", "head --ver 5|"),
        slot("K34 words after", "head --v | f"),
        slot("K34", "head --ver -|"),
    )

    // --- K12, K14, K58, K59, K79, K80: operand slots ---

    @Test
    fun `an operand completes as a file wherever the cursor stands`() = head.checkSlots(
        slot("K12a", "head fi|"),
        slot("K12a inside", "head f|il"),
        slot("K12a start", "head |file"),
        slot("K12a no word", "head |"),
        slot("K12a words after", "head | b"),
        slot("K12b", "head a fi|"),
        slot("K12b inside", "head a f|il"),
        slot("K12b start", "head a |file"),
        slot("K12b no word", "head a |"),
        slot("K12b words after", "head a | b"),
        slot("K12b both sides", "head a | b c"),
    )

    @Test
    fun `an operand after an option or a NUM completes as a file`() = head.checkSlots(
        // K79: options and their NUMs never take an operand's place (`head -n 5 f` prints f).
        slot("K79", "head -q fi|"),
        slot("K79", "head -n 5 fi|"),
        slot("K79", "head --lines=5 fi|"),
        slot("K79", "head --lines 5 fi|"),
        slot("K79 no word", "head -n 5 |"),
        slot("K79 no word", "head -n5 |"),
        slot("K79 no word", "head --bytes=5 |"),
        slot("K79 words after", "head -n 5 | b"),
        slot("K79 after a count", "head -5 |"),
        slot("K79 after a count", "head -5v fi|"),
        // K80: `head - f` reads standard input, then f.
        slot("K80", "head - fi|"),
        slot("K80", "head - |"),
        slot("K80 words after", "head - | b"),
    )

    @Test
    fun `after the end of options a name is an operand`() = head.checkSlots(
        // K14a: `head -- -x` prints the file -x; `head -- -- f`: "cannot open '--'".
        slot("K14a", "head -- -q|"),
        slot("K14a inside", "head -- -|q"),
        slot("K14a start", "head -- |-q"),
        slot("K14a no word", "head -- |"),
        slot("K14a a second --", "head -- -- |"),
        slot("K14a words after", "head -- | b"),
        slot("K14a", "head -- --lines=|"),
        slot("K14a", "head -- --lines |"),
        slot("K14a", "head -- -5|"),
    )

    @Test
    fun `odd words in a name slot complete as head reads them`() {
        head.checkSlots(
            // K7s: `head -qx f` and `head -xq f`: "invalid option -- 'x'", the next word an operand; K7t: `head -q=x
            // f`: "invalid option -- '='".
            slot("K7s", "head -qx|"),
            slot("K7s", "head -xq|"),
            slot("K7s words after", "head -qx |"),
            slot("K7t", "head -q=|"),
            slot("K7t", "head -qv=x|"),
            slot("K7t words after", "head -q=| a b"),
        )
        // K91: `head -5n5 f`: "invalid trailing option -- n".
        head.checkContext(at("K91", "head -5n5|", *NOTHING), at("K91 inside", "head -5n|5", *NOTHING))
    }

    @Test
    fun `a word klap names a built-in after completes as head reads it`() = head.checkSlots(
        // K55: `head --completion bash`: "unrecognized option", which claims no word, so the next one is an operand.
        // K58: `head __c` starts a file name like any other.
        slot("K55", "head --completion |"),
        slot("K55", "head --completion ba|"),
        slot("K55 words after", "head --completion | a"),
        slot("K58", "head __c|"),
        slot("K58 words after", "head __c| a"),
    )

    @Test
    fun `the word after --help completes as head reads it`() = head.checkSlots(
        // K59: `head --help f` prints the usage; the words after it are still read as operands.
        slot("K59", "head --help |"),
        slot("K59", "head --help fi|"),
        slot("K59 words after", "head --help | b"),
        slot("K59", "head --version |"),
    )

    @Test
    fun `the undocumented ---presume-input-pipe is never offered`() = head.checkSlots(
        // G35: `head --help` leaves it out, though `head ---presume-input-pipe f` and `head --- f` print f.
        slot("K2", "head ---|"),
        slot("K2", "head ---p|"),
    )

    @Test
    fun `a dash offers no placeholder for the obsolete count`() {
        // K16a, G31: `head -5 f` prints five lines, but no name stands for it.
        val digits = ('0'..'9').map { "-$it" }
        head.assertOffersNone("K16a G31", "head -|", digits + "-NUM")
        head.assertOffersNone("K16a G31", "head -q|", ('0'..'9').map { "-q$it" })
    }

    // `head --json f`, `head --color f`, `head --completion bash` and `head --docs man`: "unrecognized option".
    @Test
    fun `a dash offers exactly head's names`() = head.checkSlotValues(cells("dash") + DASH_POOLS)

    @Test
    fun `klap's built-ins head lacks are not offered`() = head.checkSlotValues(
        CELLS.filter { it.foreign } +
            slot("K13e", "head --js|") +
            slot("K13f", "head --col|") +
            slot("K13g", "head --comp|") +
            slot("K13g", "head --do|"),
    )

    // `head --help`: "-c, --bytes=[-]NUM print the first NUM bytes of each file; with the leading '-', print all but
    // the last NUM bytes of each file", the same for -n with lines, "--help display this help and exit", "--version
    // output version information and exit".
    @Test
    fun `every name and letter is described in head's words`() = head.checkSlots(
        (NAME_KINDS + LETTER_KINDS).flatMap { cells(it) }.filter { it.wordedLikeKlap && !it.foreign } + WORDED_SLOTS,
    )

    // K58: `head __complete f`: "cannot open '__complete'", an operand like any other.
    @Test
    fun `__complete as the first word is a file`() = head.checkSlots(
        slot("K58 no word", "head __complete |"),
        slot("K58", "head __complete fi|"),
    )

    // K16a, K88: `head -5c f` prints five bytes, `-5b`, `-5k` and `-5m` count bytes in 512, 1024 and 1048576, `-5l`
    // lines, `-5q`, `-5v` and `-5z` as the letters' options; `head -5n f`: "invalid trailing option -- n".
    @Test
    fun `the obsolete count continues with head's own letters`() {
        val lines = listOf(
            "K16a" to "head -2|",
            "K16a" to "head -20|",
            "K16a inside" to "head -2|0",
            "K16a words after" to "head -2| f",
            "K88" to "head -5v|",
            "K88 inside" to "head -5|v",
            "K88 words after" to "head -5v| f",
        )
        checkCells(lines) { (rows, typed) ->
            val count = typed.substringAfter("head ").substringBefore('|')
            val wanted = obsolete(count).filter { it.drop(count.length).first() !in count.drop(2) }
            head.assertOffersAll(rows, typed, wanted)
            head.assertOffersNone(rows, typed, listOf("${count}n"))
        }
    }

    // K29, K89: `head -v -5 f`, `head f -5`, `head -v12 f` and `head -q5 f`: "invalid trailing option -- 5" (or 1);
    // head reads a count only as its first word, and getopt refuses a digit in a cluster.
    @Test
    fun `a dash-led count anywhere but first offers nothing`() = head.checkSlots(
        slot("K29", "head -v -5|"),
        slot("K29", "head f -5|"),
        slot("K29 words after", "head -q -5| f"),
        slot("K89", "head -v12|"),
        slot("K89 inside", "head -v1|2"),
        slot("K89 words after", "head -v12| f"),
        slot("K89", "head -q5|"),
    )

    // K90: `head -5n 3 f`: "invalid trailing option -- n"; the n takes no NUM, so the word after it is an operand.
    @Test
    fun `the word after a count ending in n is an operand`() = head.checkSlots(
        slot("K90", "head -5n |"),
        slot("K90 words after", "head -5n | f"),
    )
}
