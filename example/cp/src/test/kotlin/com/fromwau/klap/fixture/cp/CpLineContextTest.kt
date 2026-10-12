package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.cp.CpLineContext.FILES
import com.fromwau.klap.fixture.cp.CpLineContext.assertOffersAll
import com.fromwau.klap.fixture.cp.CpLineContext.assertOffersNone
import com.fromwau.klap.fixture.cp.CpLineContext.at
import com.fromwau.klap.fixture.cp.CpLineContext.checkContext
import com.fromwau.klap.fixture.cp.CpLineContext.name
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * How the words around the cursor change what Tab offers on a cp line (L and S of the testing matrix): the words
 * after the cursor, and what the line already holds, from the target directory given to repeats, overrides and the
 * end of options. Every expectation is what real cp 9.12 accepts at the cursor.
 */
class CpLineContextTest {

    private val cp = cpCli().cli

    @Test
    fun `words after the cursor that bear on nothing leave the answer as at the end of the line`() = cp.checkContext(
        at("L1", "cp --verb|", name("--verbose")),
        at("L1", "cp -v |", FILES),
        at("L2a", "cp --verb| -x", name("--verbose")),
        at("L2a", "cp --verb| --suffix=x", name("--verbose")),
        at("L2a", "cp | -v", FILES),
        at("L2b", "cp --verb| a b", name("--verbose")),
        at("L2b", "cp | b", FILES),
        // `cp --zzz a b`: "unrecognized option '--zzz'"; `cp a b -S`: "option requires an argument -- 'S'".
        at("L21", "cp --verb| --zzz a b", name("--verbose")),
        at("L21", "cp --verb| a b -S", name("--verbose")),
        at("L21", "cp | a b -S", FILES),
        // cp has no --json or --color, so after the cursor they are unknown options like --zzz (L20 decided by G11).
        at("L20 L21", "cp --verb| --json", name("--verbose")),
        at("L20 L21", "cp | --json", FILES),
        at("L20 L21", "cp | --color never", FILES),
    )

    @Test
    fun `every operand slot before or between operands is a source`() = cp.checkContext(
        // `cp a b c` with c a directory copies a and b into it, so a word before the last operand is a source.
        at("L3c L13", "cp | a b", FILES),
        at("L13", "cp a | b c", FILES),
        at("L13", "cp a b | c", FILES),
        at("L13", "cp |a b", FILES),
    )

    @Test
    fun `a target directory after the cursor in every spelling leaves the slot a source`() = cp.checkContext(
        // `cp --t d a`, `cp --target d b` and `cp -vtd a` copy into d: --t and --target abbreviate --target-directory.
        at("L3d L11", "cp | -t dir", FILES),
        at("L11", "cp | -tdir", FILES),
        at("L11", "cp | --target-directory=dir", FILES),
        at("L11 L22", "cp | --target dir", FILES),
        at("L11 L22", "cp | --t dir", FILES),
        at("L11", "cp | -vt dir", FILES),
        at("L11", "cp a | -t dir", FILES),
    )

    @Test
    fun `a lastWins partner after the cursor rules nothing out`() {
        // cp --help: -L, -P and -H set one way of following links, the last one holds; "-i, --interactive  prompt
        // before overwrite (overrides a previous -n option)", "-n, --no-clobber  (deprecated) silently skip existing
        // files.  See also --update". `cp -i -n` and `cp -n -i` both run, the later deciding (Cp.kt's override
        // comment).
        cp.checkContext(
            at("L7", "cp --deref| -H", name("--dereference")),
            at("L7", "cp --deref| -P", name("--dereference")),
            at("L7", "cp --deref| --no-dereference", name("--dereference")),
            at("L7", "cp --inter| -n", name("--interactive")),
            at("L7", "cp --no-c| -i", name("--no-clobber")),
        )
        checkCells(listOf("cp -| -n" to listOf("-i", "--interactive"), "cp -| -i" to listOf("-n", "--no-clobber"))) {
            cp.assertOffersAll("L7", it.first, it.second)
        }
    }

    @Test
    fun `-T after the cursor is still offered since cp takes it twice`() = cp.checkContext(
        // `cp -T -T a f1` copies a to f1.
        at("L6", "cp --no-tar| -T", name("--no-target-directory")),
        at("L6", "cp --no-tar| --no-target-directory a b", name("--no-target-directory")),
    )

    @Test
    fun `words behind an end of options after the cursor are operands and never inputs`() = cp.checkContext(
        // `cp -t d -- -T` and `cp -- -t d` read -T and -t as files: "cannot stat '-T'", "cannot stat '-t'".
        at("L14", "cp --verb| -- -v", name("--verbose")),
        at("L14", "cp --tar| -- -T", name("--target-directory")),
        at("L14", "cp --no-tar| -- -t d", name("--no-target-directory")),
        at("L14", "cp | -- -t d", FILES),
        at("L14", "cp | -- -x", FILES),
        at("L14", "cp -T | -- a", FILES),
    )

    @Test
    fun `a dash-led word an option takes as its value is no input`() = cp.checkContext(
        // `cp -S -t a c` copies a to c: -t is the suffix, not a target directory.
        at("L15", "cp --tar| -S -t", name("--target-directory")),
        at("L15", "cp --no-tar| -S -t a b", name("--no-target-directory")),
        at("L15", "cp | -S -t", FILES),
        at("S11", "cp -S -t --no-tar|", name("--no-target-directory")),
        at("S11", "cp -S -T --tar|", name("--target-directory")),
        at("S11 S28", "cp -S -t a |", FILES),
    )

    @Test
    fun `the rest of the word under the cursor is not sent`() = cp.checkContext(
        // The completion contract, not a cp run: the word is completed as far as it is typed.
        at("L26", "cp --verb|ose", name("--verbose")),
        at("L26", "cp --tar|get-directory=d a", name("--target-directory")),
        at("L26", "cp --sym|bolic-link", name("--symbolic-link")),
        at("L26", "cp --pa|rents x", name("--parents")),
    )

    @Test
    fun `after an end of options every word is an operand whatever it is spelled as`() = cp.checkContext(
        // `cp -- a -v` copies a to a file named -v.
        at("L4a", "cp -- |", FILES),
        at("L4a", "cp -- -|", FILES),
        at("L4b", "cp -- --backup |", FILES),
        at("L4b", "cp -- -S |", FILES),
        at("L4b", "cp -- --sparse |", FILES),
        at("L4c", "cp -- --sparse=al|", FILES),
        at("L4c", "cp -- --backup=n|", FILES),
        at("L4c", "cp -- --target-directory=|", FILES),
        at("L4c", "cp -- -Sx|", FILES),
        at("L4c", "cp -- -t|", FILES),
    )

    @Test
    fun `an ambiguous or unknown long elsewhere on the line rules nothing out`() = cp.checkContext(
        // `cp --re a b`: "option '--re' is ambiguous; possibilities: '--recursive' '--remove-destination' '--reflink'".
        at("L22", "cp --no-tar| --re", name("--no-target-directory")),
        // `cp --zzz a b`: "unrecognized option '--zzz'"; its next word is no value (S45), and --json is unknown too.
        at("S45", "cp --zzz |", FILES),
        at("S44 S45", "cp --json |", FILES),
    )

    @Test
    fun `an input cp takes again stays offered once given`() = cp.checkContext(
        // `cp -v -v a b` and `cp -v --verbose a f5` run; `cp -S .p -S .q -b a b` made b.q, the later suffix.
        at("S1", "cp --arch|", name("--archive")),
        at("S16", "cp -v --verb|", name("--verbose")),
        at("S16", "cp --verbose -v --verb|", name("--verbose")),
        at("S16", "cp -a --arch|", name("--archive")),
        at("S20", "cp -S .x --suff|", name("--suffix")),
        at("S20", "cp --suffix=.x -S .y --suff|", name("--suffix")),
        // `cp -T -T a f1` runs.
        at("S10", "cp -T --no-tar|", name("--no-target-directory")),
    )

    @Test
    fun `a lastWins partner given keeps the other offered`() {
        cp.checkContext(
            at("S22", "cp -L --deref|", name("--dereference")),
            at("S22", "cp -H --deref|", name("--dereference")),
            at("S22", "cp -P --deref|", name("--dereference")),
            at("S22", "cp -i --no-c|", name("--no-clobber")),
            at("S22", "cp -n --inter|", name("--interactive")),
        )
        checkCells(listOf("cp -i -|" to listOf("-n", "--no-clobber"), "cp -L -|" to listOf("-H", "-P"))) {
            cp.assertOffersAll("S22", it.first, it.second)
        }
    }

    @Test
    fun `-t and -T leave each other out wherever the line names one`() {
        // `cp -t d -T a`, `cp -T -t d a`, `cp -T --target-directory=d a`, `cp --no-target-directory --t d a`: "cannot
        // combine --target-directory (-t) and --no-target-directory (-T)".
        val target = listOf("-t", "--target-directory")
        val noTarget = listOf("-T", "--no-target-directory")
        checkCells(
            listOf(
                Triple("L5 L11", "cp --no-tar| -t d a", noTarget),
                Triple("L5 L11", "cp --no-tar| -tdir", noTarget),
                Triple("L5 L11", "cp --no-tar| --target-directory=dir", noTarget),
                Triple("L5 L11 L22", "cp --no-tar| --target dir", noTarget),
                Triple("L5 L11", "cp --no-tar| -vt dir", noTarget),
                Triple("L5", "cp --tar| -T a b", target),
                Triple("L5", "cp -| -T a b", target),
                Triple("L5", "cp -| --no-target-directory a b", target),
                Triple("L5", "cp -v| -T a b", listOf("-vt")),
                Triple("S6", "cp -T --tar|", target),
                Triple("S6", "cp -T -|", target),
                Triple("S6", "cp -t d -|", noTarget),
                Triple("S7", "cp -vT -|", target),
                Triple("S7", "cp -vtd --no-tar|", noTarget),
                Triple("S9", "cp --no-target-directory --tar|", target),
                Triple("S9", "cp --no-tar --tar|", target),
                Triple("S9", "cp -t d --no-tar|", noTarget),
                Triple("S9", "cp --target-directory=d --no-tar|", noTarget),
                Triple("S9", "cp --t d --no-tar|", noTarget),
                Triple("S8", "cp -T|", listOf("-Tt")),
                Triple("S8", "cp -Tv|", listOf("-Tvt")),
            ),
        ) { (rows, typed, refused) -> cp.assertOffersNone(rows, typed, refused) }
    }

    @Test
    fun `with neither -t nor -T given both are offered`() = cp.checkContext(
        at("S13", "cp --tar|", name("--target-directory")),
        at("S13", "cp --no-tar|", name("--no-target-directory")),
    )

    @Test
    fun `a target directory given in any spelling leaves every operand a source`() = cp.checkContext(
        at("S27", "cp -t dir |", FILES),
        at("S27", "cp -tdir |", FILES),
        at("S27", "cp --target-directory=dir |", FILES),
        at("S27 L22", "cp --target dir |", FILES),
        at("S27", "cp -vt dir |", FILES),
        at("S27", "cp -t dir a |", FILES),
    )

    @Test
    fun `a -t written where it does not hold leaves the operand slots`() = cp.checkContext(
        // `cp -- -t d` reads -t as a source; `cp -T -S -t a b` copies a to b with -t the suffix.
        at("S28", "cp -- -t d |", FILES),
        at("S28", "cp -T -- -t |", FILES),
        at("S28", "cp -T -S -t a |", FILES),
    )

    @Test
    fun `a lone dash is an operand`() = cp.checkContext(
        // `cp - dash` copied the file named -.
        at("S32", "cp - |", FILES),
        at("S32", "cp -T - |", FILES),
        at("S32", "cp - --verb|", name("--verbose")),
    )

    @Test
    fun `an end of options taken as a value leaves the options open`() = cp.checkContext(
        // `cp -b -S -- -v a b` printed "'a' -> 'b' (backup: 'b--')": -- was the suffix and -v stayed an option.
        at("S33", "cp -S -- --verb|", name("--verbose")),
        at("S33", "cp -S -- |", FILES),
        at("S33", "cp --suffix -- --no-tar|", name("--no-target-directory")),
    )

    @Test
    fun `options after an operand still complete since cp permutes`() = cp.checkContext(
        // `cp a -v c2` printed "'a' -> 'c2'".
        at("S35", "cp a --verb| b", name("--verbose")),
        at("S35", "cp a b --verb|", name("--verbose")),
    )

    @Test
    fun `an optional value given bare takes no word and the next one is an operand or an option`() = cp.checkContext(
        // `cp --backup -v a b` backed b up as b~; `cp --backup numbered a b`: "target 'b': Not a directory".
        at("S46", "cp --backup |", FILES),
        at("S46", "cp --backup numbered |", FILES),
        at("S46", "cp --backup --verb|", name("--verbose")),
        at("S46", "cp --update |", FILES),
        at("S46", "cp --reflink --verb|", name("--verbose")),
    )

    @Test
    fun `a second target directory is not offered before or after the cursor`() {
        // Todo item 21. `cp -t d -t e a` and `cp -t d -t d a`: "multiple target directories specified". klap binds the
        // last of a repeated option and has no way to declare one cp refuses twice.
        val target = listOf("-t", "--target-directory")
        checkCells(
            listOf(
                "L8" to "cp --tar| -t d a",
                "L8" to "cp -| -t d",
                "L8" to "cp -| --target-directory=d a",
                "S10" to "cp -t d --tar|",
                "S10" to "cp -t d -|",
                "S10" to "cp -vtd -|",
            ),
        ) { (rows, typed) -> cp.assertOffersNone(rows, typed, target) }
    }

    @Test
    fun `-T leaves no operand slot once a source and a destination are on the line`() = cp.checkContext(
        // Todo item 21. `cp -T a b c`, `cp -T - b c`: "extra operand 'c'"; `cp -T --backup numbered a b`: "extra
        // operand 'b'", since a bare --backup takes no word.
        at("L13", "cp -T | a b"),
        at("L13", "cp | -T a b"),
        at("L13", "cp -T a | b"),
        at("L13 L14", "cp -T | -- a b"),
        at("S28", "cp -T -- -t d |"),
        at("S11 S28", "cp -T -S -t a b |"),
        at("S32", "cp -T - b |"),
        at("S46", "cp -T --backup numbered a |"),
    )
}

/**
 * The cells of [CpLineContextTest]'s tables the fixture does not meet, each a record of the owner's decision: it
 * states what real cp 9.12 refuses on the line, and its `@Ignore` names the decision.
 */
class CpLineContextTargetsTest {

    private val cp = cpCli().cli

    /** A record, not a target: cp's validator refuses the pair, and Tab prunes only by declared rules. */
    @Ignore("Will not do (todo 89): Tab prunes only by declared rules, and Enter on the line still gets cp's error")
    @Test
    fun `--backup is not offered beside -n`() {
        // `cp -n -b a b` and `cp -n --backup a b`: "--backup is mutually exclusive with -n or --update=none-fail";
        // CpProjectionTest's refusals pin that error on Enter.
        cp.assertOffersNone("S2 S6", "cp -n --b|", listOf("--backup"))
    }
}
