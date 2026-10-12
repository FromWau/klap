package com.fromwau.klap.fixture.find

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.find.FindCompletionSupport.FILES
import com.fromwau.klap.fixture.find.FindLineContext.NEWER
import com.fromwau.klap.fixture.find.FindLineContext.LEVELS
import com.fromwau.klap.fixture.find.FindLineContext.PRE_PATH
import com.fromwau.klap.fixture.find.FindLineContext.PR
import com.fromwau.klap.fixture.find.FindLineContext.assertOffersAll
import com.fromwau.klap.fixture.find.FindLineContext.at
import com.fromwau.klap.fixture.find.FindLineContext.checkContext
import com.fromwau.klap.fixture.find.FindLineContext.name
import kotlin.test.Test

/**
 * How the words around the cursor change what Tab offers on a find line (L and S of the testing matrix): the words
 * after the cursor, and what the line already holds, from repeats and the `-P -L -H` override set to the end of
 * options. Every expectation is what real find 4.11.0 accepts at the cursor, read through the fixture's respelling
 * of a predicate `-name` as `--name`; the records of what F1 keeps out, find's order among them, are in
 * [FindCompletionTargetsTest].
 */
class FindLineContextTest {

    private val find = findCli().cli

    @Test
    fun `words after the cursor that bear on nothing leave the answer as at the end of the line`() =
        find.checkContext(
            at("L1", "find --maxd|", name("--maxdepth")),
            at("L1", "find . |", FILES),
            at("L2a", "find --maxd| --print", name("--maxdepth")),
            at("L2a", "find --maxd| --name=x", name("--maxdepth")),
            at("L2a", "find | --print", FILES),
            at("L2b", "find --maxd| a b", name("--maxdepth")),
            at("L2b", "find | b", FILES),
            // `find . -zzz x`: "unknown predicate `-zzz'"; `find . -name`: "missing argument to `-name'".
            at("L21", "find --maxd| --zzz a", name("--maxdepth")),
            at("L21", "find --maxd| . --name", name("--maxdepth")),
            at("L21", "find | a --maxdepth", FILES),
            // find has no --json or --color, so after the cursor they are unknown predicates like --zzz (G11).
            at("L20 L21", "find --maxd| --json", name("--maxdepth")),
            at("L20 L21", "find | --json", FILES),
            at("L20 L21", "find | --color never", FILES),
        )

    @Test
    fun `every operand slot before or between starting points is a starting point`() = find.checkContext(
        // `find . a -maxdepth 0` lists both: every word before the first predicate is a starting point.
        at("L3c L13", "find | a b", FILES),
        at("L13", "find a | b", FILES),
        at("L13", "find |a b", FILES),
        at("L13", "find | a --print", FILES),
    )

    @Test
    fun `a lastWins partner after the cursor rules nothing out`() {
        // man find: "If more than one of -H, -L and -P is specified, each overrides the others; the last one
        // appearing on the command line takes effect". `find -L -P -H .` runs.
        checkCells(
            listOf(
                "find -| -L ." to listOf("-P", "-H", "-L"),
                "find -| -P ." to listOf("-L", "-H"),
                "find -| -H" to listOf("-P", "-L"),
            ),
        ) { find.assertOffersAll("L7", it.first, it.second) }
        find.checkContext(at("L7", "find --maxd| -L .", name("--maxdepth")))
    }

    @Test
    fun `an input find takes again after the cursor stays offered`() {
        // `find . -maxdepth 0 -maxdepth 1` and `find -files0-from l -files0-from l` run, the later value holding;
        // `find . -name x -name y` and `find . -empty -empty` run.
        find.checkContext(
            at("L8", "find --maxd| --maxdepth 1", name("--maxdepth")),
            at("L8", "find --files0| --files0-from l", name("--files0-from")),
            at("L10", "find --na| --name a", name("--name")),
            at("L10", "find --na| --name=a .", name("--name")),
            at("L8", "find --emp| --empty", name("--empty")),
        )
        find.assertOffersAll("L8", "find -| -O3 .", LEVELS)
        find.assertOffersAll("L8", "find -| -D tree .", listOf("-D"))
    }

    @Test
    fun `words behind an end of options after the cursor are operands and never inputs`() {
        // `find -- . -maxdepth 0` runs: a leading -- ends the pre-path switches; `find -- -L .`: "unknown predicate
        // `-L'". The fixture reads every word behind -- as an operand, so -L there is not given.
        find.checkContext(
            at("L14", "find --maxd| -- -name x", name("--maxdepth")),
            at("L14", "find | -- -name x", FILES),
            at("L14", "find | -- --print", FILES),
            at("L14", "find -L | -- a", FILES),
        )
        find.assertOffersAll("L14", "find -| -- -L", listOf("-P", "-L", "-H"))
    }

    @Test
    fun `a dash-led word an option takes as its value is no input`() {
        // `find . -newer -L`: "'-L': No such file or directory", the file; `find . -name -print` runs with -print
        // the pattern; `find -D -L .`: "Ignoring unrecognised debug flag '-L'".
        find.checkContext(
            at("L15", "find --maxd| --newer -L", name("--maxdepth")),
            at("L15", "find | --name -print", FILES),
            at("S11", "find --name --print --pri|", *PR.copyOfRange(0, 3)),
            at("S11", "find --newer -L --maxd|", name("--maxdepth")),
        )
        find.assertOffersAll("S11", "find -D -L -|", listOf("-P", "-L", "-H") + LEVELS)
    }

    @Test
    fun `the rest of the word under the cursor is not sent`() = find.checkContext(
        // The completion contract, not a find run: the word is completed as far as it is typed.
        at("L26", "find --maxd|epth 3", name("--maxdepth")),
        at("L26", "find --pr|int", *PR),
        at("L26", "find --pr|une .", *PR),
        at("L26", "find --typ|e=f", name("--type")),
    )

    @Test
    fun `after an end of options every word is an operand whatever it is spelled as`() = find.checkContext(
        // The fixture declares a raw expression behind --, opted out of completion; `find -- .` and
        // `find -L -- . -maxdepth 0` run.
        at("L4a", "find -- |", FILES),
        at("L4a", "find -- -|", FILES),
        at("L4a", "find -L -- |", FILES),
        at("L4b", "find -- --maxdepth |", FILES),
        at("L4b", "find -- --regextype |", FILES),
        at("L4b", "find -- -D |", FILES),
        at("L4c", "find -- --regextype=po|", FILES),
        at("L4c", "find -- --newer=|", FILES),
        at("L4c", "find -- -Dtr|", FILES),
        at("L4c", "find -- -O|", FILES),
    )

    @Test
    fun `an ambiguous or unknown long after the cursor rules nothing out`() = find.checkContext(
        // `find . -re x`, `find . -maxd 0`: "unknown predicate"; find matches every predicate whole (F1b).
        at("L22", "find --maxd| --re", name("--maxdepth")),
        at("L22", "find --maxd| --max 3", name("--maxdepth")),
        at("L22", "find | --regext posix-egrep", FILES),
    )

    @Test
    fun `an unknown predicate before the cursor takes no value`() = find.checkContext(
        // `find . -zzz x`: "unknown predicate `-zzz'"; its next word is no value (S45), and --json is unknown too.
        at("S45", "find --zzz |", FILES),
        at("S44 S45", "find --json |", FILES),
        at("S44 S45", "find --json --maxd|", name("--maxdepth")),
    )

    @Test
    fun `an input find takes again stays offered once given`() {
        // `find . -print -print` prints each name twice; `find . -type f -type d` and `find . -name x -name y` run;
        // `find . -maxdepth 0 -maxdepth 1` lists depth 1, the later value; `find . -printf x -printf y` prints xy;
        // `find . -regextype posix-egrep -regextype emacs` runs.
        find.checkContext(
            at("S1", "find --maxd|", name("--maxdepth")),
            at("S16", "find --print --pri|", *PR.copyOfRange(0, 3)),
            at("S16", "find --empty --emp|", name("--empty")),
            at("S16", "find . --type f --typ|", name("--type")),
            at("S19", "find --name a --na|", name("--name")),
            at("S19", "find --name=a --name b --na|", name("--name")),
            at("S20", "find --maxdepth 1 --maxd|", name("--maxdepth")),
            at("S20", "find --maxdepth=1 --mindepth 0 --mind|", name("--mindepth")),
            at("S20", "find --printf x --printf|", name("--printf")),
            at("S20", "find --regextype posix-egrep --regext|", name("--regextype")),
            at("S20", "find --newer a --newe|", *NEWER),
        )
        // `find -L -L .`, `find -O1 -O2 .` and `find -D tree -D search .` run.
        checkCells(listOf("find -L -|", "find -O1 -|", "find -D tree -|")) {
            find.assertOffersAll("S16 S20", it, PRE_PATH)
        }
    }

    @Test
    fun `a lastWins partner given keeps the others offered`() {
        // `find -L -P -H .` and `find -H -L . -follow` run.
        checkCells(listOf("find -L -|", "find -P -|", "find -H -|", "find -L -P -|")) {
            find.assertOffersAll("S22", it, listOf("-P", "-L", "-H"))
        }
        find.checkContext(at("S22", "find -L --maxd|", name("--maxdepth")))
    }

    @Test
    fun `a lone dash is a starting point`() = find.checkContext(
        // `find - .` reads - as a starting point: "'-': No such file or directory", then lists `.`.
        at("S32", "find - |", FILES),
        at("S32", "find -L - |", FILES),
        at("S32", "find - --maxd|", name("--maxdepth")),
    )

    @Test
    fun `an end of options taken as a value leaves the options open`() {
        // `find . -name -- -print` runs with -- the pattern; `find -D -- -L .` says "Ignoring unrecognised debug
        // flag '--'" and follows links, so -L stayed a switch.
        find.checkContext(
            at("S33", "find --name -- --maxd|", name("--maxdepth")),
            at("S33", "find . --name -- --pri|", *PR.copyOfRange(0, 3)),
        )
        find.assertOffersAll("S33", "find -D -- -|", listOf("-P", "-L", "-H"))
    }

    @Test
    fun `predicates after a starting point still complete`() = find.checkContext(
        // `find . -name a` and `find . a -maxdepth 0` run: the expression follows the starting points.
        at("S35", "find . --na|", name("--name")),
        at("S35", "find . a --maxd|", name("--maxdepth")),
        at("S35", "find -L . --pr|", *PR),
    )

    @Test
    fun `an abbreviated predicate is unknown and takes no value`() = find.checkContext(
        // `find . -maxd 0`, `find . -regext posix-egrep`, `find . -re x`: "unknown predicate"; find takes no
        // abbreviation, so the next word is no value (S45) and the slot is the starting point's.
        at("S45", "find --max |", FILES),
        at("S45", "find --mind |", FILES),
        at("S45", "find --regext |", FILES),
        at("S45", "find --re |", FILES),
        at("S45 L22", "find --regext | --maxdepth 1", FILES),
    )

    @Test
    fun `--color before the cursor is an unknown predicate that takes no value`() = find.checkContext(
        // `find . --color x` would read like `find . --zzz x`: "unknown predicate"; find has no --color.
        at("S44 S45", "find --color |", FILES),
    )
}
