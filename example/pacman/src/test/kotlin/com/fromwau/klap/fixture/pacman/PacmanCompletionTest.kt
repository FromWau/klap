package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What Tab completes at each slot and cursor column of a pacman line (K and K7 of the testing matrix, with their cursor
 * columns and required cells), checked against what real pacman 7.1.0 would accept there: the exact suggestions with
 * their descriptions, the line once one is taken and the line after one Tab. The generated cells cover every input
 * under every operation and with none; a slot cell names its K row.
 */
class PacmanCompletionTest {

    private val pacman = pacmanCli().cli

    private val everywhere = NO_OPERATION + ANY_OPERATION

    @Test
    fun `no word offers nothing since targets have no candidates`() =
        pacman.checkPacman(cells("no word", everywhere, NOTHING))

    @Test
    fun `a word's start is that word typed as far as nothing with its rest not sent`() =
        pacman.checkPacman(cells("word start", everywhere, NOTHING))

    @Test
    fun `a long name's start offers what no word offers`() = pacman.checkPacman(
        cells(
            "long word start", everywhere, OPERATION, OPTION, BUILT_IN, SAME_WORDING, OTHER_WORDING, OFF_PAGE,
            SAME_OPERATION, SHARED_NAME, OTHER_OPERATION,
        ),
    )

    @Test
    fun `no name is offered after the end of options`() =
        pacman.checkPacman(cells("end of options", everywhere, NOTHING))

    @Test
    fun `a long name completes with no operation on the line`() = pacman.checkPacman(
        cells("long end", NO_OPERATION, OPERATION, OPTION, BUILT_IN) +
            cells("long inside", NO_OPERATION, OPERATION, OPTION, BUILT_IN),
    )

    @Test
    fun `an option on the operation's page completes where the fixture uses the page's words`() = pacman.checkPacman(
        cells("long end", ANY_OPERATION, SAME_WORDING) +
            cells("long inside", ANY_OPERATION, SAME_WORDING) +
            cells("long before the operation", ANY_OPERATION, SAME_WORDING),
    )

    @Test
    fun `another operation's name completes to nothing under an operation`() = pacman.checkPacman(
        cells("long end", ANY_OPERATION, OTHER_OPERATION) + cells("long inside", ANY_OPERATION, OTHER_OPERATION),
    )

    @Test
    fun `help and version complete under any operation`() = pacman.checkPacman(
        cells("long end", ANY_OPERATION, BUILT_IN) +
            cells("long inside", ANY_OPERATION, BUILT_IN) +
            cells("long before the operation", ANY_OPERATION, BUILT_IN),
    )

    @Test
    fun `a single letter continues with every letter when no operation is given`() =
        pacman.checkPacman(cells("short end", NO_OPERATION, OPTION, VERSION_LETTER))

    @Test
    fun `a letter taking a value ends its cluster`() = pacman.checkPacman(
        cells("short end", ANY_OPERATION, VALUE_LETTER) +
            cells("cluster end", ANY_OPERATION, VALUE_LETTER) +
            cells("cluster inside", ANY_OPERATION, VALUE_LETTER),
    )

    @Test
    fun `a path value completes through the shell in every form`() = pacman.checkPacman(
        listOf(
            "value no word", "value word start", "value spaced", "value attached", "value abbreviated",
            "value short", "value cluster",
        ).flatMap { cells(it, everywhere, PATH_VALUE) },
    )

    @Test
    fun `a choice value completes with the option's description when attached`() = pacman.checkPacman(
        listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")
            .flatMap { cells(it, everywhere, CHOICE) },
    )

    @Test
    fun `a free text value has no candidates`() = pacman.checkPacman(
        listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")
            .flatMap { cells(it, everywhere, TEXT_VALUE) },
    )

    @Test
    fun `an optional value completes only attached`() = pacman.checkPacman(
        listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")
            .flatMap { cells(it, everywhere, OPTIONAL) },
    )

    @Test
    fun `a value of another operation's option has no candidates`() = pacman.checkPacman(
        listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")
            .flatMap { cells(it, ANY_OPERATION, OFF_PAGE) },
    )

    @Test
    fun `a long name is completed from the cursor wherever it stands in the word`() = pacman.checkPacman(
        // K2, K5, K15a: handed the name as far as typed, the rest of the word stays.
        slot("K2 inside", "pacman -Q --ve|rbose", named = "--verbose"),
        slot("K2 inside", "pacman --qu|ery"),
        slot("K5 words after", "pacman -Q --dbp| /tmp pkg"),
        slot("K9 inside the name", "pacman -Q --dbp|ath=/tm"),
        slot("K9 before the =", "pacman -Q --dbpath|=/tm"),
        slot("K15a ambiguous", "pacman --re|"),
        slot("K15a ambiguous", "pacman --re| pkg"),
        slot("K14a end of options after the cursor", "pacman -Q --verb| -- x"),
    )

    @Test
    fun `a word's start is an empty word with the rest of it not sent`() = pacman.checkPacman(
        slot("K2 start", "pacman -Q |--verbose"),
        slot("K3 start", "pacman -Q |-v"),
        slot("K6 start", "pacman -Q |-b"),
        slot("K7a start", "pacman |-Q"),
        slot("K7b start", "pacman |-Qv"),
        slot("K7d start", "pacman -Qi |-"),
        slot("K7i start", "pacman |-Qh"),
        slot("K9 start", "pacman -Q |--dbpath=/tm"),
        slot("K10 start", "pacman -Q |-b/tm"),
        slot("K11a start", "pacman -Q |--debug=1"),
        slot("K14a start", "pacman -Q -- |-v"),
    )

    @Test
    fun `a cluster ending in a value letter completes its value from the cursor`() = pacman.checkPacman(
        // K7f, K7g, K7h: the rest of the word after `-b` is its value, a `=` included (`pacman -Qb=/tmp`: "failed to
        // resolve path '=/tmp' passed to '--dbpath'"), so the shell completes a path after the cluster.
        slot("K7f inside", "pacman -Qb/|tm"),
        slot("K7f words after", "pacman -Qb/tm| pkg"),
        slot("K7f words after", "pacman -Qvb/tm| pkg"),
        slot("K7g", "pacman -Qb=|"),
        slot("K7g inside", "pacman -Qb=/|tm"),
        slot("K7g words after", "pacman -Qb=| pkg"),
        slot("K7h", "pacman -Qbv|"),
        slot("K7h words after", "pacman -Qbv| pkg"),
    )

    @Test
    fun `a letter pacman does not know or a stray = ends what a cluster can become`() = pacman.checkPacman(
        // `pacman -Qz`: "invalid option -- 'z'"; `pacman -Qv=`: "invalid option -- '='"; `pacman -Q -5`: "invalid
        // option -- '5'".
        slot("K7s", "pacman -Qz|"),
        slot("K7s", "pacman -zQ|"),
        slot("K7s words after", "pacman -Qz |"),
        slot("K7t", "pacman -Qv=|"),
        slot("K7t", "pacman -Qvi=x|"),
        slot("K7t words after", "pacman -Qv=| pkg"),
        slot("K29", "pacman -Q -5|"),
        slot("K29 words after", "pacman -Q -5| pkg"),
    )

    @Test
    fun `a value in its own word completes from the cursor wherever it stands`() = pacman.checkPacman(
        // K8a, K32, K33, K36, K7x: a path after -b, --dbpath or a cluster ending in b; the color choices spaced.
        slot("K8a dash-led word after", "pacman -Q --dbpath | -v"),
        slot("K8a dash-led word after", "pacman -Q --color | -v"),
        slot("K32", "pacman -Q -b /tm|"),
        slot("K32 inside", "pacman -Q -b /t|m"),
        slot("K32 start", "pacman -Q -b |/tm"),
        slot("K32 words after", "pacman -Q -b | pkg"),
        slot("K32", "pacman -Q -r |"),
        slot("K33", "pacman -Q --dbp /tm|"),
        slot("K33", "pacman -Q --col a|"),
        slot("K33 words after", "pacman -Q --col | pkg"),
        slot("K36", "pacman -Qb /tm|"),
        slot("K36 inside", "pacman -Qb /t|m"),
        slot("K36 start", "pacman -Qb |/tm"),
        slot("K36 words after", "pacman -Qb | pkg"),
        slot("K7x", "pacman -Qvb |"),
    )

    @Test
    fun `a value glued or attached completes from the cursor wherever it stands`() = pacman.checkPacman(
        slot("K10 inside", "pacman -Q -b/|tm"),
        slot("K10 inside", "pacman -Q -b|/tm"),
        slot("K43", "pacman -Q --dbpath=a=b|"),
        slot("K43 inside", "pacman -Q --dbpath=a=|b"),
        slot("K43", "pacman -S --assume-installed foo=1|"),
        slot("K43", "pacman -S --assume-installed=foo=|"),
        // K52 L3: the operation after the cursor words --debug by its page.
        slot("K52 words after", "pacman --debug=| -Q"),
    )

    @Test
    fun `a dash-led word in a value slot is that value`() = pacman.checkPacman(
        // getopt hands the next word to a value-taking option whatever it looks like: `pacman -Q --dbpath -v` is
        // "failed to resolve path '-v' passed to '--dbpath'", `pacman -Q -b -` the same for '-'.
        slot("K40", "pacman -Q --dbpath -v|"),
        slot("K40 inside", "pacman -Q --dbpath -|v"),
        slot("K40 words after", "pacman -Q --dbpath -v| pkg"),
        slot("K40", "pacman -S --ignore -v|"),
        slot("K81", "pacman -Q -b -|"),
        slot("K81", "pacman -Qb -|"),
        slot("K81 words after", "pacman -Q -b -| pkg"),
    )

    @Test
    fun `the end of options marker in a value slot is that value`() = pacman.checkPacman(
        // `pacman -Q -b --`: "failed to resolve path '--' passed to '--dbpath'", so the `--` is the path.
        slot("K41", "pacman -Q -b --|"),
        slot("K41 words after", "pacman -Q -b --| pkg"),
        slot("K41 no word", "pacman -Q -b -- |"),
    )

    @Test
    fun `a value that looks like an operation is that value`() = pacman.checkPacman(
        // `pacman -Q --arch -Q --quiet` lists the packages: the second -Q is the architecture, one operation is given.
        // With no operation before it, `pacman --arch -Q --sy` may still become any of the three.
        slot(
            "K42",
            "pacman --arch -Q --sy|",
            expected = setOf(Suggestion("--sync", Op.SYNC.help), name("--sysroot"), name("--sysupgrade")),
        ),
        slot("K42", "pacman -Q --arch -Q | pkg"),
        slot("K42", "pacman -Q --arch --help |"),
    )

    @Test
    fun `a flag or an unknown or ambiguous long has no value to complete`() = pacman.checkPacman(
        // `pacman -Q --verbose= pacman`: "option '--verbose' doesn't allow an argument"; `--zzz=a`: "unrecognized
        // option"; `--conf=x` and `--conf x`: "option '--conf' is ambiguous; possibilities: '--confirm' '--config'".
        slot("K45", "pacman -Q --verbose=|"),
        slot("K45 inside", "pacman -Q --verbose=|x"),
        slot("K45 words after", "pacman -Q --verbose=| pkg"),
        slot("K46", "pacman -Q --zzz=|"),
        slot("K46", "pacman -Q --zzz=a|"),
        slot("K46 words after", "pacman -Q --zzz=| pkg"),
        slot("K46 ambiguous", "pacman -Q --conf=|"),
        slot("K34", "pacman -Q --conf |"),
        slot("K34", "pacman -Q --conf x|"),
        slot("K34 words after", "pacman -Q --conf | pkg"),
        slot("K47", "pacman -Q --zzz |"),
        slot("K47", "pacman -Q --zzz pk|"),
        slot("K47 words after", "pacman -Q --zzz | pkg"),
    )

    @Test
    fun `a target has no candidates wherever it stands`() = pacman.checkPacman(
        // K12a, K12b, K79, K80: pacman's targets are packages, files or URLs it does not complete.
        slot("K12a", "pacman -Q pk|"),
        slot("K12a inside", "pacman -Q p|kg"),
        slot("K79", "pacman -Q -v |"),
        slot("K79", "pacman -Q -v pk|"),
        slot("K79", "pacman -Q -b /tmp |"),
        slot("K79", "pacman -Q -b /tmp pk|"),
        slot("K79", "pacman -Q --dbpath=/tmp |"),
        slot("K79 words after", "pacman -Q -b /tmp | pkg"),
        slot("K12b", "pacman -Q a pk|"),
        slot("K12b inside", "pacman -Q a p|kg"),
        slot("K12b start", "pacman -Q a |pkg"),
        slot("K12b", "pacman -Q a |"),
        slot("K12b words after", "pacman -Q a | b c"),
        slot("K80", "pacman -Q - pk|"),
        slot("K80", "pacman -Q - |"),
        slot("K80 words after", "pacman -Q - | pkg"),
        slot("K80 a name after", "pacman -Q - --verb|"),
    )

    @Test
    fun `after the end of options every word is a target`() = pacman.checkPacman(
        // `pacman -Q -- -v`: "package '-v' was not found"; `pacman -Q -- -- pacman`: "package '--' was not found".
        slot("K14a", "pacman -Q -- -v|"),
        slot("K14a inside", "pacman -Q -- -|v"),
        slot("K14a", "pacman -Q -- |"),
        slot("K14a a second --", "pacman -Q -- -- |"),
        slot("K14a words after", "pacman -Q -- | pkg"),
    )

    @Test
    fun `the word after a built-in pacman does not have is a target`() = pacman.checkPacman(
        // K55: pacman has no --completion or --docs, so the next word is no value of theirs.
        slot("K55", "pacman --completion |"),
        slot("K55", "pacman --completion ba|"),
        slot("K55", "pacman --completion=ba|"),
        slot("K55", "pacman --docs ma|"),
        slot("K55 words after", "pacman --completion | pkg"),
        slot("K55", "pacman -Q --completion |"),
    )

    @Test
    fun `a target named __complete is an ordinary target`() = pacman.checkPacman(
        // K58: `pacman -Q __complete`: "package '__complete' was not found".
        slot("K58", "pacman __c|"),
        slot("K58", "pacman __complete |"),
        slot("K58", "pacman -Q __complete --verb|", expected = setOf(name("--verbose", Op.QUERY))),
    )

    @Test
    fun `the words after help or version still complete as pacman reads them`() = pacman.checkPacman(
        // K59: `pacman -Q --help --verbose` and `pacman -Q -h --verbose` print the -Q page.
        slot("K59", "pacman --help |"),
        slot("K59", "pacman -h |"),
        slot("K59", "pacman --version |"),
        slot("K59", "pacman --help pk|"),
        slot("K59 words after", "pacman -Q -h | pkg"),
        slot("K59 a name after", "pacman -Q --help --verb|", expected = setOf(name("--verbose", Op.QUERY))),
    )
}

/**
 * The slots and cursor columns of a pacman line (K and K7 of the testing matrix) where klap offers something other than
 * what real pacman 7.1.0 would accept. Each test states what real pacman 7.1.0 does and is ignored with `TDD target:
 * <what must change>`; removing the annotation shows it failing for that reason, and no other test pins today's
 * behaviour, so a fix breaks nothing else.
 */
class PacmanCompletionTargetsTest {

    private val pacman = pacmanCli().cli

    private val longKinds = listOf("long end", "long inside")
    private val letterKinds = listOf("short end", "cluster end", "cluster inside")

    @Ignore("TDD target: scope names by operation, word them by its page, drop --json --completion --docs")
    @Test
    fun `a dash inside a word or before an operation offers what a bare dash under it offers`() = pacman.checkPacman(
        // Klap is handed `-` or `--` and the rest of the word stays; `pacman -Q -i` and `pacman -i -Q` alike read -Q.
        slot("K2 inside the dashes", "pacman -Q -|-verbose"),
        slot("K2 inside the dashes", "pacman -|-query"),
        slot("K2 inside the dashes", "pacman -Q --|verbose"),
        slot("K5 inside the dashes", "pacman -Q -|-dbpath"),
        slot("K3 inside", "pacman -Q -|v"),
        slot("K6 inside", "pacman -Q -|b"),
        slot("K7a inside", "pacman -|Q"),
        slot("K7b inside", "pacman -|Qv"),
        slot("K29 inside", "pacman -Q -|5"),
        slot("K7d", "pacman -Qi -|"),
        slot("K7d words after", "pacman -Qi -| pkg"),
        slot("K3 set member after", "pacman -| -Q"),
        slot("K3 set member after", "pacman --| -Q"),
    )

    @Ignore("TDD target: klap must offer under an operation, wherever it stands on the line, only its page's longs")
    @Test
    fun `a long name typed in part completes to the longs of the operation's page`() = pacman.checkPacman(
        // `pacman -Q --dbonly`: "invalid option '--dbonly'"; `pacman -Q --regex`: "invalid option '--regex'"; in
        // `pacman --sy -Q` --sync would be a second operation and --sysupgrade is not on the -Q page.
        slot("K5 inside", "pacman -Q --db|path"),
        slot("K15a inside", "pacman -Q --r|e"),
        slot("K2 constraint after", "pacman --sy| -Q"),
    )

    @Ignore("TDD target: the fixture must decline --json --completion --docs, which pacman does not have")
    @Test
    fun `a built-in pacman does not have is not offered`() = pacman.checkPacman(
        // `pacman -Q --json`, `pacman -Q --completion bash`, `pacman -Q --docs man`: "unrecognized option"; `pacman
        // -Q --co`: "option '--co' is ambiguous; possibilities: '--confirm' '--config' '--color'".
        slot("K13e", "pacman --js|"),
        slot("K13e", "pacman -Q --json|"),
        slot("K13g", "pacman --compl|"),
        slot("K13g", "pacman -Q --doc|"),
        slot("K15a ambiguous", "pacman -Q --co|"),
    )

    @Ignore("TDD target: klap must continue an operation's cluster with its page letters, a counted one once more")
    @Test
    fun `a cluster continues from the cursor with its operation's page letters`() = pacman.checkPacman(
        // Handed the cluster as far as typed: `-Q` of `-Qv`, `-Qi` of `-Qiv` and `-Qii`, `-S` of `-Sy`.
        slot("K7b inside", "pacman -Q|v"),
        slot("K7b inside", "pacman -Qi|v"),
        slot("K7e1 inside", "pacman -Q|i"),
        slot("K7p", "pacman -Qii|"),
        slot("K7p inside", "pacman -Qi|i"),
        slot("K7p words after", "pacman -Qii| pkg"),
        slot("K7p", "pacman -Syy|"),
        slot("K7q inside", "pacman -S|y"),
    )

    @Ignore("TDD target: klap must go on with a cluster after the help short, which takes no value")
    @Test
    fun `the help short alone or in a cluster continues with the letters pacman reads after it`() = pacman.checkPacman(
        // K13b, K7i: `pacman -Qhi` and `pacman -Qh -i` print the -Q page, `pacman -Qhz` is "invalid option -- 'z'",
        // so pacman reads every letter after -h.
        slot("K13b", "pacman -h|", expected = continuations("-h", null)),
        slot("K13b", "pacman -Q -h|", expected = continuations("-h", Op.QUERY)),
        slot("K13b words after", "pacman -Q -h| pkg", expected = continuations("-h", Op.QUERY)),
        slot("K7i", "pacman -Qh|", expected = continuations("-Qh", Op.QUERY)),
        slot("K7i words after", "pacman -Qh| pkg", expected = continuations("-Qh", Op.QUERY)),
    )

    @Ignore("TDD target: klap must complete a first word __complete as the target it is on a line, not route into it")
    @Test
    fun `a first target named __complete is an ordinary target`() = pacman.checkPacman(
        // K58: `pacman -Q __complete`: "package '__complete' was not found"; with no operation yet, every name.
        slot("K58", "pacman __complete --verb|", expected = setOf(name("--verbose"))),
    )

    @Ignore("TDD target: klap must not end options at a -- that a value-taking option took as its value")
    @Test
    fun `names go on after a -- that was an option's value`() = pacman.checkPacman(
        // K41: `pacman -Q -b -- --verbose`: "failed to resolve path '--' passed to '--dbpath'", so getopt gave
        // `--` to -b and options did not end.
        slot("K41 a name after", "pacman -Q -b -- --verb|", expected = setOf(name("--verbose", Op.QUERY))),
    )

    @Ignore("TDD target: klap must not continue a cluster with another member of an exclusive set the cluster holds")
    @Test
    fun `a cluster holding an operation offers no other operation`() {
        // `pacman -QS pacman`: "error: only one operation may be used at a time". `pacman -Q -<TAB>` already
        // leaves -S out; the cluster under the cursor must count the same way.
        val offered = pacman.suggestionsFor("pacman -Q").map { it.value }
        val others = listOf("-QS", "-QR", "-QU", "-QD", "-QF", "-QT")
        assertTrue(others.none { it in offered }, "`pacman -Q|` offers another operation: $offered")
    }

    @Ignore("TDD target: klap must offer under an operation only the options that operation's page lists")
    @Test
    fun `an option of another operation is not offered`() {
        // `pacman -Q --needed pacman`: "error: invalid option '--needed'".
        assertEquals(emptyList<Suggestion>(), pacman.suggestionsFor("pacman -Q --ne"), "`pacman -Q --ne|`")
    }

    @Ignore("TDD target: klap must offer a counted letter again in its cluster where the operation's page counts it")
    @Test
    fun `a counted letter is offered again in its cluster`() {
        // The pages: -Q -ii -kk -tt, -S -cc -dd -gg -ii -uu -yy, -R -dd -ss, -U -dd, -D -kk, -F -yy.
        val cells = Op.entries.flatMap { op -> op.page.filter { op.counts(it) }.map { op to it } }
        checkCells(cells) { (op, entry) ->
            val cluster = "pacman -${op.short}${entry.short}"
            val again = Suggestion("${cluster.drop(7)}${entry.short}", entry.help)
            val offered = pacman.suggestionsFor(cluster)
            assertTrue(again in offered, "${marked(cluster, cluster.length)} must offer $again: $offered")
        }
    }

    @Ignore("TDD target: scope names by operation, word them by its page, drop --json --completion --docs")
    @Test
    fun `a dash offers the operation's page and nothing pacman refuses`() =
        pacman.checkPacman(cells("dash", NO_OPERATION + ANY_OPERATION, NOTHING))

    @Ignore("TDD target: klap must offer under an operation only the long names its page lists")
    @Test
    fun `a long of another operation completes to nothing`() =
        pacman.checkPacman(longKinds.flatMap { cells(it, ANY_OPERATION, OFF_PAGE) })

    @Ignore("TDD target: klap must describe an option by the page of the operation on the line")
    @Test
    fun `a long on the page is described in that page's words`() =
        pacman.checkPacman(longKinds.flatMap { cells(it, ANY_OPERATION, OTHER_WORDING) })

    @Ignore("TDD target: klap must not offer a requireExactlyOne member already given")
    @Test
    fun `the operation already given is not offered again`() =
        pacman.checkPacman(longKinds.flatMap { cells(it, ANY_OPERATION, SAME_OPERATION) })

    @Ignore("TDD target: klap must complete another operation's name to this operation's options only, in its words")
    @Test
    fun `another operation's name as typed completes to this operation's options`() =
        pacman.checkPacman(longKinds.flatMap { cells(it, ANY_OPERATION, SHARED_NAME) })

    @Ignore("TDD target: klap must read an operation typed after the cursor when it scopes the names it offers")
    @Test
    fun `a long name completes to what an operation after the cursor accepts`() = pacman.checkPacman(
        cells(
            "long before the operation", ANY_OPERATION, OFF_PAGE, OTHER_WORDING, SAME_OPERATION, SHARED_NAME,
            OTHER_OPERATION,
        ),
    )

    @Ignore("TDD target: klap must refuse a cluster letter the operation's page does not list")
    @Test
    fun `a letter of another operation offers nothing after it`() =
        pacman.checkPacman(letterKinds.flatMap { cells(it, ANY_OPERATION, OFF_PAGE) })

    @Ignore("TDD target: klap must resolve a letter by the operation's page")
    @Test
    fun `a contested letter continues by its meaning on the operation's page`() =
        pacman.checkPacman(letterKinds.flatMap { cells(it, ANY_OPERATION, CONTESTED) })

    @Ignore("TDD target: klap must continue a cluster with its operation's page letters only, in the page's words")
    @Test
    fun `a letter on the page continues with that page's letters`() =
        pacman.checkPacman(
            letterKinds.flatMap { cells(it, ANY_OPERATION, SAME_WORDING, OTHER_WORDING, VERSION_LETTER) },
        )

    @Ignore("TDD target: klap must continue a cluster holding an operation with that operation's letters only")
    @Test
    fun `a letter of an operation continues with that operation's letters or with nothing`() =
        pacman.checkPacman(letterKinds.flatMap { cells(it, NO_OPERATION + ANY_OPERATION, OPERATION) })

    @Ignore("TDD target: klap must continue an operation's own cluster with its page's letters only")
    @Test
    fun `an operation's own cluster continues with that operation's letters`() =
        pacman.checkPacman(cells("operation cluster", ANY_OPERATION, OPERATION))

    @Ignore("TDD target: klap must read an operation typed after the cursor when it continues a cluster")
    @Test
    fun `a cluster continues with the letters of an operation after the cursor`() =
        pacman.checkPacman(cells("operation cluster after the cursor", ANY_OPERATION, OPERATION))
}
