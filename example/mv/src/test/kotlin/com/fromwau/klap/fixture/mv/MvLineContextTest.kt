package com.fromwau.klap.fixture.mv

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.mv.MvLineContext.FILES
import com.fromwau.klap.fixture.mv.MvLineContext.assertOffersAll
import com.fromwau.klap.fixture.mv.MvLineContext.assertOffersNone
import com.fromwau.klap.fixture.mv.MvLineContext.at
import com.fromwau.klap.fixture.mv.MvLineContext.checkContext
import com.fromwau.klap.fixture.mv.MvLineContext.name
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * How the words around the cursor change what Tab offers on an mv line (L and S of the testing matrix): the words
 * after the cursor, and what the line already holds, from the target directory given to repeats, overrides and the
 * end of options. Every expectation is what real mv 9.12 accepts at the cursor.
 */
class MvLineContextTest {

    private val mv = mvCli().cli

    @Test
    fun `words after the cursor that bear on nothing leave the answer as at the end of the line`() = mv.checkContext(
        at("L1", "mv --verb|", name("--verbose")),
        at("L1", "mv -v |", FILES),
        at("L2a", "mv --verb| -f", name("--verbose")),
        at("L2a", "mv --verb| --suffix=x", name("--verbose")),
        at("L2a", "mv | -v", FILES),
        at("L2b", "mv --verb| a b", name("--verbose")),
        at("L2b", "mv | b", FILES),
        // `mv --zzz a b`: "unrecognized option '--zzz'"; `mv a b -S`: "option requires an argument -- 'S'".
        at("L21", "mv --verb| --zzz a b", name("--verbose")),
        at("L21", "mv --verb| a b -S", name("--verbose")),
        at("L21", "mv | a b -S", FILES),
    )

    @Test
    fun `every operand slot before or between operands is a source`() = mv.checkContext(
        // `mv a b d` with d a directory moves a and b into it, so a word before the last operand is a source.
        at("L3c L13", "mv | a b", FILES),
        at("L13", "mv a | b c", FILES),
        at("L13", "mv a b | c", FILES),
        at("L13", "mv |a b", FILES),
    )

    @Test
    fun `a target directory after the cursor in every spelling leaves the slot a source`() = mv.checkContext(
        // `mv --t d a`, `mv -td a` and `mv a -t d` move into d: --t abbreviates --target-directory.
        at("L3d L11", "mv | -t dir", FILES),
        at("L11", "mv | -tdir", FILES),
        at("L11", "mv | --target-directory=dir", FILES),
        at("L11 L22", "mv | --target dir", FILES),
        at("L11 L22", "mv | --t dir", FILES),
        at("L11", "mv | -vt dir", FILES),
        at("L11", "mv a | -t dir", FILES),
    )

    @Test
    fun `a lastWins partner after the cursor rules nothing out`() {
        // `mv --help`: "If you specify more than one of -i, -f, -n, only the final one takes effect."
        mv.checkContext(
            at("L7", "mv --inter| -n", name("--interactive")),
            at("L7", "mv --for| -i", name("--force")),
        )
        checkCells(listOf("mv -| -n" to listOf("-i", "--interactive", "-f"), "mv -| -f" to listOf("-n", "-i"))) {
            mv.assertOffersAll("L7", it.first, it.second)
        }
    }

    @Test
    fun `-T after the cursor is still offered since mv takes it twice`() = mv.checkContext(
        // `mv -T -T a b` renames a to b.
        at("L6", "mv --no-tar| -T", name("--no-target-directory")),
        at("L6", "mv --no-tar| --no-target-directory a b", name("--no-target-directory")),
    )

    @Test
    fun `words behind an end of options after the cursor are operands and never inputs`() = mv.checkContext(
        // `mv a -- -v` makes a file -v; `mv -- -foo bar`: "cannot stat '-foo'".
        at("L14", "mv --verb| -- -v", name("--verbose")),
        at("L14", "mv --tar| -- -T", name("--target-directory")),
        at("L14", "mv --no-tar| -- -t d", name("--no-target-directory")),
        at("L14", "mv | -- -t d", FILES),
        at("L14", "mv | -- -x", FILES),
        at("L14", "mv -T | -- a", FILES),
    )

    @Test
    fun `a dash-led word an option takes as its value is no input`() = mv.checkContext(
        // `mv -S -x -b a b` made b-x: the word after -S is the suffix, whatever it spells.
        at("L15", "mv --tar| -S -t", name("--target-directory")),
        at("L15", "mv --no-tar| -S -t a b", name("--no-target-directory")),
        at("L15", "mv | -S -t", FILES),
        at("S11", "mv -S -t --no-tar|", name("--no-target-directory")),
        at("S11", "mv -S -T --tar|", name("--target-directory")),
        at("S11 S28", "mv -S -t a |", FILES),
    )

    @Test
    fun `the rest of the word under the cursor is not sent`() = mv.checkContext(
        // The completion contract, not an mv run: the word is completed as far as it is typed.
        at("L26", "mv --verb|ose", name("--verbose")),
        at("L26", "mv --tar|get-directory=d a", name("--target-directory")),
        at("L26", "mv --str|ip-trailing-slashes", name("--strip-trailing-slashes")),
        at("L26", "mv --ex|change x", name("--exchange")),
    )

    @Test
    fun `after an end of options every word is an operand whatever it is spelled as`() = mv.checkContext(
        // `mv a -- -v` makes a file named -v; `mv -- a --` a file named --.
        at("L4a", "mv -- |", FILES),
        at("L4a", "mv -- -|", FILES),
        at("L4b", "mv -- --backup |", FILES),
        at("L4b", "mv -- -S |", FILES),
        at("L4b", "mv -- --update |", FILES),
        at("L4c", "mv -- --update=al|", FILES),
        at("L4c", "mv -- --backup=n|", FILES),
        at("L4c", "mv -- --target-directory=|", FILES),
        at("L4c", "mv -- -Sx|", FILES),
        at("L4c", "mv -- -t|", FILES),
    )

    @Test
    fun `klap's built-ins mv lacks bear on nothing around the cursor`() = mv.checkContext(
        // `mv --json a b`, `mv --color never a b`, `mv --completion bash a b`: "unrecognized option". After the cursor
        // they leave the answer alone; before it they claim no word, as `mv --zzz |` does (L20 decided by G11, S44,
        // S45).
        at("L20 L21", "mv --verb| --json", name("--verbose")),
        at("L20 L21", "mv | --json", FILES),
        at("L20 L21", "mv | --color never", FILES),
        at("S44 S45", "mv --json |", FILES),
        at("S44 S45", "mv --completion |", FILES),
    )

    @Test
    fun `an ambiguous or unknown long elsewhere on the line rules nothing out`() = mv.checkContext(
        // `mv --s a b`: "option '--s' is ambiguous; possibilities: '--strip-trailing-slashes' '--suffix'".
        at("L22", "mv --no-tar| --s", name("--no-target-directory")),
        // `mv --zzz a b`: "unrecognized option '--zzz'"; its next word is no value (S45).
        at("S45", "mv --zzz |", FILES),
    )

    @Test
    fun `an input mv takes again stays offered once given`() = mv.checkContext(
        // `mv -v -v a b` and `mv --verbose --verbose a b` run; `mv -S .x -S .y a b` made b.y, the later suffix.
        at("S1", "mv --exch|", name("--exchange")),
        at("S16", "mv -v --verb|", name("--verbose")),
        at("S16", "mv --verbose -v --verb|", name("--verbose")),
        at("S16", "mv --exchange --exch|", name("--exchange")),
        at("S20", "mv -S .x --suff|", name("--suffix")),
        at("S20", "mv --suffix=.x -S .y --suff|", name("--suffix")),
        // `mv -T -T a b` runs.
        at("S10", "mv -T --no-tar|", name("--no-target-directory")),
    )

    @Test
    fun `a lastWins partner given keeps the other offered`() {
        mv.checkContext(
            at("S22", "mv -i --for|", name("--force")),
            at("S22", "mv -f --inter|", name("--interactive")),
            at("S22", "mv -n --inter|", name("--interactive")),
        )
        checkCells(listOf("mv -i -|" to listOf("-n", "-f", "--force"), "mv -f -|" to listOf("-i", "-n"))) {
            mv.assertOffersAll("S22", it.first, it.second)
        }
    }

    @Test
    fun `-u and --update stay offered beside each other`() {
        // `mv -u --update=none a b` kept b and `mv --update=none -u a b` replaced it: one setting, the later deciding
        // (S23), so each stays offered once the other is given.
        checkCells(listOf("mv -u --upd|" to listOf("--update"), "mv --update=none -|" to listOf("-u", "--update"))) {
            mv.assertOffersAll("S23", it.first, it.second)
        }
    }

    @Test
    fun `with neither -t nor -T given both are offered`() = mv.checkContext(
        at("S13", "mv --tar|", name("--target-directory")),
        at("S13", "mv --no-tar|", name("--no-target-directory")),
    )

    @Test
    fun `a target directory given in any spelling leaves every operand a source`() = mv.checkContext(
        at("S27", "mv -t dir |", FILES),
        at("S27", "mv -tdir |", FILES),
        at("S27", "mv --target-directory=dir |", FILES),
        at("S27 L22", "mv --target dir |", FILES),
        at("S27", "mv -vt dir |", FILES),
        at("S27", "mv -t dir a |", FILES),
    )

    @Test
    fun `a -t written where it does not hold leaves the operand slots`() = mv.checkContext(
        // `mv -- -t d` reads -t as a source; `mv -S -t a b` renames a to b with -t the suffix.
        at("S28", "mv -- -t d |", FILES),
        at("S28", "mv -T -- -t |", FILES),
    )

    @Test
    fun `a lone dash is an operand`() = mv.checkContext(
        // `mv - b`: "cannot stat '-'", so - was a source.
        at("S32", "mv - |", FILES),
        at("S32", "mv -T - |", FILES),
        at("S32", "mv - --verb|", name("--verbose")),
    )

    @Test
    fun `an end of options taken as a value leaves the options open`() = mv.checkContext(
        // `mv -S -- -b a b` made b--: -- was the suffix and -b stayed an option.
        at("S33", "mv -S -- --verb|", name("--verbose")),
        at("S33", "mv -S -- |", FILES),
        at("S33", "mv --suffix -- --no-tar|", name("--no-target-directory")),
    )

    @Test
    fun `options after an operand still complete since mv permutes`() = mv.checkContext(
        // `mv a b -t d` moved both into d.
        at("S35", "mv a --verb| b", name("--verbose")),
        at("S35", "mv a b --verb|", name("--verbose")),
    )

    @Test
    fun `an optional value given bare takes no word and the next one is an operand or an option`() = mv.checkContext(
        // `mv --backup numbered a b`: "target 'b': Not a directory".
        at("S46", "mv --backup |", FILES),
        at("S46", "mv --backup numbered |", FILES),
        at("S46", "mv --backup --verb|", name("--verbose")),
        at("S46", "mv --update |", FILES),
        at("S46", "mv --update --verb|", name("--verbose")),
    )

    @Test
    fun `a second target directory is not offered before or after the cursor`() {
        // `mv -t d -t e a` and `mv -t d -t d a`: "multiple target directories specified".
        val target = listOf("-t", "--target-directory")
        checkCells(
            listOf(
                "L8" to "mv --tar| -t d a",
                "L8" to "mv -| -t d",
                "L8" to "mv -| --target-directory=d a",
                "S10" to "mv -t d --tar|",
                "S10" to "mv -t d -|",
                "S10" to "mv -vtd -|",
            ),
        ) { (rows, typed) -> mv.assertOffersNone(rows, typed, target) }
    }

    @Test
    fun `-t and -T leave each other out wherever the line names one`() {
        // `mv -t d -T a`, `mv -T -t d a`: "cannot combine --target-directory (-t) and --no-target-directory (-T)".
        val target = listOf("-t", "--target-directory")
        val noTarget = listOf("-T", "--no-target-directory")
        checkCells(
            listOf(
                Triple("L5 L11", "mv --no-tar| -t d a", noTarget),
                Triple("L5 L11", "mv --no-tar| -tdir", noTarget),
                Triple("L5 L11", "mv --no-tar| --target-directory=dir", noTarget),
                Triple("L5 L11 L22", "mv --no-tar| --target dir", noTarget),
                Triple("L5 L11", "mv --no-tar| -vt dir", noTarget),
                Triple("L5", "mv --tar| -T a b", target),
                Triple("L5", "mv -| -T a b", target),
                Triple("L5", "mv -| --no-target-directory a b", target),
                Triple("L5", "mv -v| -T a b", listOf("-vt")),
                Triple("S6", "mv -T --tar|", target),
                Triple("S6", "mv -T -|", target),
                Triple("S6", "mv -t d -|", noTarget),
                Triple("S7", "mv -vT -|", target),
                Triple("S7", "mv -vtd --no-tar|", noTarget),
                Triple("S9", "mv --no-target-directory --tar|", target),
                Triple("S9", "mv --no-tar --tar|", target),
                Triple("S9", "mv -t d --no-tar|", noTarget),
                Triple("S9", "mv --target-directory=d --no-tar|", noTarget),
                Triple("S9", "mv --t d --no-tar|", noTarget),
                Triple("S8", "mv -T|", listOf("-Tt")),
                Triple("S8", "mv -Tv|", listOf("-Tvt")),
            ),
        ) { (rows, typed, refused) -> mv.assertOffersNone(rows, typed, refused) }
    }

    @Test
    fun `-T leaves no operand slot once a source and a destination are on the line`() = mv.checkContext(
        // `mv -T a b c`, `mv a b c -T`: "extra operand 'c'"; `mv -T --backup numbered a b` leaves numbered a source, so
        // b is the extra operand.
        at("L13", "mv -T | a b"),
        at("L13", "mv | -T a b"),
        at("L13", "mv -T a | b"),
        at("L13 L14", "mv -T | -- a b"),
        at("S28", "mv -T -- -t d |"),
        at("S11 S28", "mv -T -S -t a b |"),
        at("S32", "mv -T - b |"),
        at("S46", "mv -T --backup numbered a |"),
    )
}

/**
 * The cells of [MvLineContextTest]'s tables the fixture does not meet, each a record of the owner's decision: it
 * states what real mv 9.12 refuses on the line, and its `@Ignore` names the decision.
 */
class MvLineContextTargetsTest {

    private val mv = mvCli().cli

    /** A record, not a target: mv's validator refuses the pair, and Tab prunes only by declared rules. */
    @Ignore("Will not do (todo 89): Tab prunes only by declared rules, and Enter on the line still gets mv's error")
    @Test
    fun `--backup is not offered beside -n`() {
        // `mv -n -b a b` and `mv -n --backup a b`: "cannot combine --backup with --exchange, -n, or
        // --update=none-fail"; MvProjectionTest's refusals pin that error on Enter.
        mv.assertOffersNone("S2 S6", "mv -n --b|", listOf("--backup"))
    }
}
