package com.fromwau.klap.fixture.ls

import kotlin.test.Test

/** Cells whose word continues a short cluster of flags: ls's other letters, each once. */
private val CLUSTER_CONTINUATIONS: List<CompletionCell> = listOf(
    // K3, K7a, K7b, K7e2: ls counts no letter, so `-la` offers no `-laa` (`ls -ll a` runs, but reads one -l).
    slot("K3", "ls -a|"),
    slot("K3 words after", "ls -a| a b"),
    slot("K7a", "ls -l|"),
    slot("K7a words after", "ls -l| a b"),
    slot("K7b", "ls -la|"),
    slot("K7b inside", "ls -l|a"),
    slot("K7b words after", "ls -la| a b"),
    slot("K7e2", "ls -lr|"),
    // K7c, S16: `ls -l -l a` and `ls -ll a` list a long, so a letter of an earlier word is offered again.
    slot("K7c", "ls -l -a|"),
    slot("K7c words after", "ls -l -a| a b"),
    // K7i, K61: `ls -lh` is -l with ls's own -h, so `-h` continues a cluster as any letter does.
    slot("K7i", "ls -lh|"),
    slot("K61", "ls -h|"),
    slot("K61 words after", "ls -h| dir"),
    // K28, K7r: `ls -1l a` and `ls -l1 a` list a: 1 is a letter like any other.
    slot("K28", "ls -1|"),
    slot("K28 words after", "ls -1| dir"),
    slot("K7r", "ls -1l|"),
    slot("K7r inside", "ls -1|l"),
    slot("K7r", "ls -l1|"),
    // K7s inside: handed `-l`, the `z` after the cursor is not sent.
    slot("K7s inside", "ls -l|z"),
    // -F takes no value (`ls -Fla` lists long, all): it continues like any flag.
    slot("K3", "ls -F|"),
)

/** Cells whose word is a dash, or is handed to klap as one: every ls name, in ls's words, and nothing else. */
private val DASH_POOLS: List<CompletionCell> = listOf(
    slot("K3", "ls -|"),
    slot("K3 inside", "ls -|a"),
    slot("K3 words after", "ls -| a b"),
    slot("K2", "ls --|"),
    slot("K2 inside", "ls --|rec"),
    slot("K2 inside the dashes", "ls -|-rec"),
    slot("K5 inside", "ls --|form"),
    slot("K6 inside", "ls -|w"),
    slot("K7a inside", "ls -|l"),
    slot("K7b inside", "ls -|la"),
    slot("K7c inside", "ls -l -|a"),
    slot("K7d", "ls -la -|"),
    slot("K7d words after", "ls -la -| a b"),
    slot("K14a -- after the cursor", "ls -| -- x"),
    slot("K28 inside", "ls -|1"),
    slot("K29 inside", "ls -|5"),
    slot("K61 inside", "ls -|h"),
)

/** Hand-written cells whose answer holds `--dired`, `--hide-control-chars` or `--hyperlink` beside a long it shares. */
private val SHARED_PREFIX_SLOTS: List<CompletionCell> = listOf(
    slot("K15a inside", "ls --dir|ec"),
    slot("K13a inside", "ls --h|e"),
    slot("K20 inside", "ls --s|i"),
)

/** Hand-written cells whose answer holds the `ls --help` words of -w, --format, --sort, --color, --help, --version. */
private val WORDED_SLOTS: List<CompletionCell> = listOf(
    slot("K10 inside", "ls -w|4"),
    slot("K6", "ls -w|"),
    slot("K5", "ls --form|"),
    slot("K5", "ls --so|"),
    slot("K5", "ls --col|"),
    slot("K9", "ls --format=lo|"),
    slot("K13a", "ls --he|"),
    slot("K13d", "ls --vers|"),
)

/**
 * What Tab completes at each slot and cursor column of an ls line (K and K7 of the testing matrix, with their cursor
 * columns), checked against what real ls 9.12 would accept there: the exact suggestions with their descriptions. A
 * slot cell names its K row; the generated cells cover every input of ls. ls has no subcommand, negation, global,
 * hidden input, number option, dash-led operand, file-valued option or provider, so the K rows of those (K1, K4,
 * K11c to K11e, K12c, K12d, K16, K17, K19, K21 to K25, K27, K30, K35, K37 to K39, K44, K48 to K51, K56, K57, K60,
 * K62 to K78, K81 to K93, K7j to K7q, K7u to K7ah) have no ls cell.
 */
class LsCompletionTest {

    private val ls = lsCli().cli

    // --- generated cells, every input ---

    @Test
    fun `an operand slot hands the word to the shell's file completion`() = ls.checkSlots(
        cells("no word", OPERAND) + cells("word start", OPERAND) + cells("operand inside", OPERAND) +
            cells("operand end", OPERAND),
    )

    @Test
    fun `after the end of options every word is an operand`() = ls.checkSlots(cells("end of options", OPERAND))

    @Test
    fun `a long name completes to every long of ls it starts in ls's words`() =
        ls.checkSlots(NAME_KINDS.flatMap { cells(it, LS_NAME) })

    @Test
    fun `a long name ls does not have offers nothing`() = ls.checkSlots(NAME_KINDS.flatMap { cells(it, UNKNOWN_NAME) })

    @Test
    fun `a letter taking a value ends its cluster`() = ls.checkSlots(LETTER_KINDS.flatMap { cells(it, VALUE_LETTER) })

    @Test
    fun `a value slot completes as ls reads the value`() = ls.checkSlots(VALUE_KINDS.flatMap { cells(it) })

    // --- K2 to K6, K13, K15, K20: names ---

    @Test
    fun `a long flag completes from the cursor wherever it stands in the word`() = ls.checkSlots(
        slot("K2", "ls --rec|"),
        slot("K2 inside", "ls --rec|ursive"),
        slot("K2 inside", "ls --re|c"),
        slot("K2 start", "ls |--rec"),
        slot("K2 words after", "ls --rec| a b"),
        slot("K2 a whole name", "ls --recursive|"),
        slot("K15a ambiguous", "ls --re|"),
        slot("K15a ambiguous inside", "ls --r|ec"),
        slot("K15a", "ls --alm|"),
        slot("K15a", "ls --al|"),
        slot("K15a", "ls --con|"),
    )

    @Test
    fun `a long option's name completes from the cursor wherever it stands in the word`() = ls.checkSlotValues(
        // K5: `--format=WORD`, `--sort=WORD`, `--width=COLS` and `--color[=WHEN]` are offered bare.
        slot("K5", "ls --form|"),
        slot("K5 inside", "ls --fo|rm"),
        slot("K5 start", "ls |--format"),
        slot("K5 words after", "ls --form| a b"),
        slot("K5 the value typed after it", "ls --form| long a"),
        slot("K5", "ls --wid|"),
        slot("K5", "ls --so|"),
        slot("K5", "ls --tab|"),
        slot("K5", "ls --col|"),
        slot("K5", "ls --cl|"),
    )

    @Test
    fun `a name typed in full that prefixes another offers both`() = ls.checkSlots(
        // K20: `ls --si a` and `ls --size a` run; --si is whole, so it is --si, and --size starts with it.
        slot("K20", "ls --si|"),
        slot("K20 start", "ls |--si"),
        slot("K20 words after", "ls --si| a"),
    )

    @Test
    fun `names are not offered before a dash`() = ls.checkSlots(
        slot("K2 no word", "ls |"),
        slot("K2 no word", "ls -l |"),
        slot("K3 start", "ls |-a"),
        slot("K13a start", "ls |--help"),
        slot("K28 start", "ls |-1"),
        slot("K61 start", "ls |-h"),
    )

    @Test
    fun `--help completes from the cursor`() = ls.checkSlotValues(
        // K13a: `ls --he` prints the usage; `--h` reaches every long starting so.
        slot("K13a", "ls --he|"),
        slot("K13a words after", "ls --he| a"),
        slot("K13d", "ls --vers|"),
        slot("K13d", "ls --v|"),
    )

    // --- K8 to K11, K32 to K34, K45 to K47, K52: value slots ---

    @Test
    fun `a value in its own word completes to ls's words wherever the cursor stands`() = ls.checkSlots(
        // K8a, K33: `ls --format long a`, `ls --form long -d .` and `ls --fo long a` list long; a dash-led word after
        // the cursor leaves the cursor word the value.
        slot("K8a", "ls --format lo|"),
        slot("K8a inside", "ls --format l|o"),
        slot("K8a start", "ls --format |long"),
        slot("K8a start words after", "ls --format |long dir"),
        slot("K8a no word", "ls --format |"),
        slot("K8a words after", "ls --format | dir"),
        slot("K8a a dash-led word after", "ls --format | -d dir"),
        slot("K33", "ls --form lo|"),
        slot("K33 no word", "ls --fo |"),
        slot("K33 words after", "ls --form | dir"),
        slot("K8a", "ls --sort ti|"),
        slot("K8a no word", "ls --sort |"),
    )

    @Test
    fun `a pattern or a number in its own word offers nothing`() = ls.checkSlots(
        // K32, K36, K7x: `ls -w 40 a`, `ls -I '*.o' a`, `ls -T 4 a`: free text, nothing to complete.
        slot("K32", "ls -w |"),
        slot("K32", "ls -w 4|"),
        slot("K32 words after", "ls -w | a"),
        slot("K32", "ls -I |"),
        slot("K32", "ls -I *|"),
        slot("K32", "ls -T |"),
        slot("K36", "ls -rw |"),
        slot("K36 words after", "ls -rw | a"),
        slot("K36", "ls -rI |"),
        slot("K8a", "ls --width |"),
        slot("K8a", "ls --hide |"),
        slot("K8a", "ls --ignore |"),
        slot("K33", "ls --wid |"),
    )

    @Test
    fun `an attached value completes from the cursor wherever it stands`() = ls.checkSlotValues(
        // K9: `ls --format=long a` and `ls --form=long a` list long; the typed spelling is the head.
        slot("K9", "ls --format=lo|"),
        slot("K9 abbreviated", "ls --form=lo|"),
        slot("K9 inside", "ls --format=l|o"),
        slot("K9 inside the name", "ls --fo|rmat=lo"),
        slot("K9 before the =", "ls --format|=lo"),
        slot("K9 start", "ls |--format=lo"),
        slot("K9 no word", "ls --format=|"),
        slot("K9 words after", "ls --format=| a b"),
        slot("K9", "ls --sort=s|"),
    )

    @Test
    fun `a value glued to its short offers nothing`() = ls.checkSlots(
        // K10, K7f, G89: `ls -w40 a`, `ls -lw40 a`, `ls -I*.o a`: the rest of the word is the value, free text.
        slot("K10", "ls -w4|"),
        slot("K10 words after", "ls -w4| a b"),
        slot("K10", "ls -I*.|"),
        slot("K7f", "ls -lw|"),
        slot("K7f", "ls -lw4|"),
        slot("K7f words after", "ls -lw4| a b"),
        slot("K7h", "ls -wl|"),
    )

    @Test
    fun `a value-taking short is offered by itself`() = ls.checkSlotValues(
        // K6, V99, G73: `ls -w` alone is "option requires an argument -- 'w'"; the word is a name until a value is
        // glued on.
        slot("K6", "ls -w|"),
        slot("K6 words after", "ls -w| 40 a"),
        slot("K6", "ls -I|"),
        slot("K6", "ls -T|"),
        slot("K6 start", "ls |-w"),
        // K10 inside: handed `-w`, the 4 after the cursor is not sent.
        slot("K10 inside", "ls -w|4"),
    )

    @Test
    fun `an optional value leaves a spaced word to the operands`() = ls.checkSlots(
        // K11b, S46: `ls --color always`: "cannot access 'always'"; `ls --classify never sub` lists never and sub.
        slot("K11b", "ls --color |"),
        slot("K11b words after", "ls --color | a"),
        slot("K11b", "ls --color al|"),
        slot("K11b", "ls --classify |"),
        slot("K11b abbreviated", "ls --col |"),
        slot("K54 ls's own --color", "ls --colo |"),
    )

    @Test
    fun `a flag or an unknown or ambiguous long has no value to complete`() = ls.checkSlots(
        // K45: `ls --all=x`: "option '--all' doesn't allow an argument"; K46: `ls --zzz=x`: "unrecognized option",
        // `ls --s=x`: "option '--s=x' is ambiguous".
        slot("K45", "ls --all=|"),
        slot("K45 inside", "ls --all=|x"),
        slot("K45 words after", "ls --all=| a b"),
        slot("K45", "ls --recursive=|"),
        slot("K46", "ls --zzz=|"),
        slot("K46", "ls --zzz=a|"),
        slot("K46 words after", "ls --zzz=| a b"),
        slot("K46 ambiguous", "ls --s=|"),
        slot("K46 ambiguous", "ls --re=a|"),
    )

    @Test
    fun `the word after an unknown long is an operand`() = ls.checkSlots(
        // K47: `ls --zzz a`: "unrecognized option '--zzz'"; it claims no word.
        slot("K47", "ls --zzz |"),
        slot("K47", "ls --zzz sr|"),
        slot("K47 words after", "ls --zzz | b"),
    )

    @Test
    fun `nothing completes after an ambiguous long`() = ls.checkSlots(
        // K34: `ls --s a`: "option '--s' is ambiguous; possibilities: '--size' '--si' '--show-control-chars' '--sort'",
        // `ls --re a`: "option '--re' is ambiguous", whatever follows.
        slot("K34", "ls --s |"),
        slot("K34", "ls --s 7|"),
        slot("K34 words after", "ls --s | file"),
        slot("K34", "ls --re |"),
        slot("K34", "ls --re -|"),
        slot("K34", "ls --c a|"),
        // `ls --ig a`: "option '--ig' is ambiguous; possibilities: '--ignore-backups' '--ignore'".
        slot("K34", "ls --ig |"),
    )

    @Test
    fun `a value in capitals is not offered`() = ls.checkSlots(
        // `ls --color=ALWAYS sub` and `--color=Always`: "invalid argument"; ls matches its values in their own case.
        cells("value capitals", CAPITALS) +
            slot("V13", "ls --format=LO|") +
            slot("V13", "ls --format LO|") +
            slot("V13", "ls --color=Al|"),
    )

    // --- K12, K14, K58, K59, K79, K80: operand slots ---

    @Test
    fun `an operand completes as a file wherever the cursor stands`() = ls.checkSlots(
        slot("K12a", "ls fi|"),
        slot("K12a inside", "ls f|il"),
        slot("K12a start", "ls |file"),
        slot("K12a no word", "ls |"),
        slot("K12a words after", "ls | b"),
        slot("K12b", "ls a fi|"),
        slot("K12b inside", "ls a f|il"),
        slot("K12b start", "ls a |file"),
        slot("K12b no word", "ls a |"),
        slot("K12b words after", "ls a | b"),
        slot("K12b both sides", "ls a | b c"),
    )

    @Test
    fun `an operand after an option completes as a file`() = ls.checkSlots(
        // K79: options and their values never take an operand's place (`ls -w 40 a` lists a).
        slot("K79", "ls -l fi|"),
        slot("K79", "ls -w 40 fi|"),
        slot("K79", "ls --format=long fi|"),
        slot("K79", "ls --format long fi|"),
        slot("K79 no word", "ls -w 40 |"),
        slot("K79 no word", "ls --sort=size |"),
        slot("K79 no word", "ls -w40 |"),
        slot("K79 words after", "ls -w 40 | b"),
        // K80: `ls -` is "cannot access '-'", an operand like any other.
        slot("K80", "ls - fi|"),
        slot("K80", "ls - |"),
        slot("K80 words after", "ls - | b"),
    )

    @Test
    fun `after the end of options a name is an operand`() = ls.checkSlots(
        // K14a: `ls -- -a`: "cannot access '-a'"; `ls a -- -l`: "cannot access 'a'" and '-l'.
        slot("K14a", "ls -- -a|"),
        slot("K14a inside", "ls -- -|a"),
        slot("K14a start", "ls -- |-a"),
        slot("K14a no word", "ls -- |"),
        slot("K14a a second --", "ls -- -- |"),
        slot("K14a words after", "ls -- | b"),
        slot("K14a", "ls -- --format=|"),
        slot("K14a", "ls -- --format |"),
    )

    @Test
    fun `odd words in a name slot complete as ls reads them`() = ls.checkSlots(
        // K29: `ls -5`: "invalid option -- '5'"; K7s: `ls -lz` and `ls -zl`: "invalid option -- 'z'"; K7t: `ls -l=x`:
        // "invalid option -- '='".
        slot("K29", "ls -5|"),
        slot("K29 words after", "ls -5| dir"),
        slot("K7s", "ls -lz|"),
        slot("K7s", "ls -zl|"),
        slot("K7s words after", "ls -lz |"),
        slot("K7t", "ls -l=|"),
        slot("K7t", "ls -la=x|"),
        slot("K7t words after", "ls -l=| a b"),
    )

    @Test
    fun `a word klap names a built-in after completes as ls reads it`() = ls.checkSlots(
        // K55: `ls --completion bash` and `ls --docs a`: "unrecognized option", which claims no word, so the next one
        // is an operand. K58: `ls __c` starts a file name like any other.
        slot("K55", "ls --completion |"),
        slot("K55", "ls --completion ba|"),
        slot("K55 words after", "ls --completion | a"),
        slot("K55", "ls --docs |"),
        slot("K58", "ls __c|"),
        slot("K58 words after", "ls __c| a"),
    )

    @Test
    fun `the word after --help completes as ls reads it`() = ls.checkSlots(
        // K59: `ls --help a b` prints the usage; the words after it are still read as operands.
        slot("K59", "ls --help |"),
        slot("K59", "ls --help fi|"),
        slot("K59 words after", "ls --help | b"),
        slot("K59", "ls --version |"),
    )

    @Test
    fun `every name and word real ls has is offered`() = ls.checkSlots(SHARED_PREFIX_SLOTS)

    @Test
    fun `a name is described in ls's words wherever the cursor sits`() {
        // `ls --help`: --hide "do not list implied entries matching shell PATTERN (overridden by -a or -A)", --format
        // "across,horizontal (-x), ...", -t "sort by time, newest first; see --time", --sort "change default 'name'
        // sort to WORD: none (-U), ...", -h "... 2G etc.", -w "set output width to COLS.  0 means no limit", --color
        // "color the output WHEN; more info below", -F the --classify row, "display this help and exit", "output
        // version information and exit".
        ls.checkSlots(WORDED_SLOTS)
    }

    @Test
    fun `klap's built-ins ls lacks are not offered`() = ls.checkSlotValues(
        // K13e, K13g: `ls --json a`, `ls --completion bash`, `ls --docs a`, `ls --j a`, `ls --com a`, `ls --do a`:
        // "unrecognized option".
        CELLS.filter { it.foreign } +
            slot("K13e", "ls --js|") +
            slot("K13g", "ls --comp|") +
            slot("K13g", "ls --do|"),
    )

    @Test
    fun `nothing completes after a prefix --directory or --format shares with another long`() = ls.checkSlots(
        // K34: `ls --di a`: "option '--di' is ambiguous; possibilities: '--directory' '--dired'"; `ls --f a`: "...
        // '--full-time' '--file-type' '--format'".
        slot("K34", "ls --f |"),
        slot("K34", "ls --f lo|"),
        slot("K34", "ls --di -|"),
        slot("K46", "ls --f=|"),
    )

    @Test
    fun `__complete as the first word is a file`() = ls.checkSlots(
        // K58: `ls __complete a`: "cannot access '__complete'", an operand like any other.
        slot("K58 no word", "ls __complete |"),
        slot("K58", "ls __complete fi|"),
    )

    @Test
    fun `a cluster continues with every letter ls accepts after it`() = ls.checkSlotValues(CLUSTER_CONTINUATIONS)

    @Test
    fun `a dash offers exactly ls's names`() = ls.checkSlotValues(DASH_POOLS)
}
