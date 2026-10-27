package com.fromwau.klap.fixture.ls

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.ls.LsLineContext.FILES
import com.fromwau.klap.fixture.ls.LsLineContext.assertOffersAll
import com.fromwau.klap.fixture.ls.LsLineContext.at
import com.fromwau.klap.fixture.ls.LsLineContext.checkContext
import com.fromwau.klap.fixture.ls.LsLineContext.checkContextValues
import com.fromwau.klap.fixture.ls.LsLineContext.name
import com.fromwau.klap.fixture.ls.LsLineContext.spaced
import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test

/*
 * ls reads no word after the cursor into what Tab offers: it has no option that excludes another, refuses no repeat,
 * declares no provider and no trigger, and ends options only at `--`. So the L and S rows of exclusive sets, refused
 * repeats, counted flags, triggers, providers and subcommands (L3a, L3b, L3d, L5, L6, L8, L9, L11, L12, L16 to L19,
 * L23 to L25, S2, S5 to S15, S17, S18, S21, S24 to S31, S33, S34, S36 to S43, S47) have no ls cell. L27 is fish's
 * rule and belongs to the live shell tests.
 */

private val NOTHING: Array<Suggestion> = emptyArray()

/**
 * How the words around the cursor change what Tab offers on an ls line (L and S of the testing matrix): the words
 * after the cursor, and what the line already holds, from the sort shorts and --sort to the end of options. Every
 * expectation is what real ls 9.12 accepts at the cursor.
 */
class LsLineContextTest {

    private val ls = lsCli().cli

    @Test
    fun `words after the cursor that bear on nothing leave the answer as at the end of the line`() = ls.checkContext(
        at("L1", "ls --rec|", name("--recursive")),
        at("L1", "ls -l |", FILES),
        at("L2a", "ls --rec| -l", name("--recursive")),
        at("L2a", "ls --rec| --sort=size", name("--recursive")),
        at("L2a", "ls | -l", FILES),
        at("L2b", "ls --rec| a b", name("--recursive")),
        at("L2b L3c L13", "ls | b", FILES),
        at("L13", "ls a | b c", FILES),
        // L21: `ls --zzz a`: "unrecognized option"; `ls a -w`: "option requires an argument -- 'w'"; `ls a --s`:
        // "option '--s' is ambiguous".
        at("L21", "ls --rec| --zzz a b", name("--recursive")),
        at("L21", "ls | a --zzz", FILES),
        at("L21", "ls --rec| -w", name("--recursive")),
        at("L21 L22", "ls --rec| --s", name("--recursive")),
        at("L22", "ls | --fo long", FILES),
    )

    @Test
    fun `a sort short after the cursor rules nothing out`() {
        // L7: `ls -S -t` sorts by time and `ls -t -S` by size: the later wins, and ls accepts every order.
        ls.checkContextValues(at("L7", "ls --so| -S", name("--sort")), at("L7", "ls --rev| -t", name("--reverse")))
        val lines = listOf("ls -| -S" to listOf("-t", "-S", "-U", "-X", "--sort"), "ls -| --sort=time" to listOf("-t"))
        checkCells(lines) {
            ls.assertOffersAll("L7", it.first, it.second)
        }
    }

    @Test
    fun `a pattern option after the cursor stays offered`() {
        // L10: `ls -I a -I b` hides both, so -I and --ignore stay offered with one already on the line.
        checkCells(listOf("ls -| -I a" to listOf("-I", "--ignore", "--hide"), "ls --| --hide a" to listOf("--hide"))) {
            ls.assertOffersAll("L10", it.first, it.second)
        }
    }

    @Test
    fun `words behind an end of options after the cursor are operands and never inputs`() = ls.checkContext(
        // L14: `ls a -- -l`: "cannot access '-l'".
        at("L14", "ls --rec| -- -l", name("--recursive")),
        at("L14", "ls --alm| -- -a", name("--almost-all")),
        at("L14", "ls | -- -l", FILES),
        at("L14", "ls -l | -- -x", FILES),
    )

    @Test
    fun `a dash-led word an option takes after the cursor is its value`() = ls.checkContext(
        // L15: `ls -m -I -l` hides the file -l: the -l after -I is no option.
        at("L15", "ls | -I -l", FILES),
        at("L15", "ls --rec| -I -a", name("--recursive")),
    )

    @Test
    fun `the rest of the word under the cursor is not sent`() = ls.checkContext(
        // L26: the completion contract, not an ls run: the word is completed as far as it is typed.
        at("L26", "ls --rec|ursive", name("--recursive")),
        at("L26", "ls --alm|ost-all a", name("--almost-all")),
        at("L26", "ls --rev|erse dd", name("--reverse")),
    )

    @Test
    fun `after an end of options every word is an operand whatever it is spelled as`() = ls.checkContext(
        // L4a to L4c: `ls -- -a` and `ls -- --format=long`: "cannot access", each word a file.
        at("L4a", "ls -- |", FILES),
        at("L4a", "ls -- -|", FILES),
        at("L4b", "ls -- --format |", FILES),
        at("L4b", "ls -- -w |", FILES),
        at("L4c", "ls -- --format=lo|", FILES),
        at("L4c", "ls -- --color=|", FILES),
        at("L4c", "ls -- -la|", FILES),
    )

    @Test
    fun `an unknown long before the cursor claims no word`() = ls.checkContext(
        // S45: `ls --zzz a`: "unrecognized option '--zzz'"; its next word is no value.
        at("S45", "ls --zzz |", FILES),
        at("S45", "ls --zzz --rec|", name("--recursive")),
    )

    @Test
    fun `nothing completes after an ambiguous long before the cursor`() = ls.checkContext(
        // S45, K34: `ls --s a`: "option '--s' is ambiguous", so no word after it completes.
        at("S45", "ls --s |", *NOTHING),
        at("S45", "ls --s --rec|", *NOTHING),
    )

    @Test
    fun `an input ls takes again stays offered once given`() {
        // S1, S16, S19, S20: `ls -r -r a`, `ls --reverse --reverse a`, `ls -I a -I b` and `ls -w 40 -w 80 a` run.
        ls.checkContext(
            at("S1", "ls --rec|", name("--recursive")),
            at("S16", "ls -R --rec|", name("--recursive")),
            at("S16", "ls --recursive -R --rec|", name("--recursive")),
            at("S16", "ls -a --alm|", name("--almost-all")),
            // `ls --dire a`: "option '--dire' is ambiguous; possibilities: '--directory' '--dired'".
            at("S16", "ls -d --dire|c", name("--directory"), name("--dired")),
        )
        ls.checkContextValues(
            at("S20", "ls -w 40 --wid|", name("--width")),
            at("S20", "ls --sort=size --so|", name("--sort")),
        )
        checkCells(listOf("ls -r -|" to listOf("-r", "--reverse"), "ls -I a -|" to listOf("-I", "--ignore"))) {
            ls.assertOffersAll("S16 S19", it.first, it.second)
        }
    }

    @Test
    fun `a sort short given keeps the others offered`() {
        // S22, S23: `ls -S -t` sorts by time, `ls -S --sort=time` by time and `ls --sort=time -S` by size.
        ls.checkContextValues(at("S23", "ls -S --so|", name("--sort")), at("S23", "ls -t --so|", name("--sort")))
        val lines = listOf("ls -S -|" to listOf("-t", "-U", "-X", "--sort"), "ls --sort=time -|" to listOf("-S", "-t"))
        checkCells(lines) {
            ls.assertOffersAll("S22 S23", it.first, it.second)
        }
    }

    @Test
    fun `a lone dash is an operand`() = ls.checkContext(
        // S32: `ls -` is "cannot access '-'", so - was a file.
        at("S32", "ls - |", FILES),
        at("S32", "ls -l - |", FILES),
        at("S32", "ls - --rec|", name("--recursive")),
    )

    @Test
    fun `options after an operand still complete since ls permutes`() = ls.checkContext(
        // S35: `ls a.y -l` lists a.y long (POSIXLY_CORRECT unset; with it set, -l is a file).
        at("S35", "ls a --rec| b", name("--recursive")),
        at("S35", "ls a b --rec|", name("--recursive")),
        at("S35", "ls a --alm|", name("--almost-all")),
    )

    @Test
    fun `an optional value given bare takes no word and the next one is an operand or an option`() = ls.checkContext(
        // S46: `ls --color always`: "cannot access 'always'"; `ls --classify --rec` is --classify then --recursive.
        at("S46", "ls --color |", FILES),
        at("S46", "ls --color never |", FILES),
        at("S46", "ls --color --rec|", name("--recursive")),
        at("S46", "ls --classify |", FILES),
        at("S46", "ls --classify --rec|", name("--recursive")),
    )

    @Test
    fun `klap's --json before or after the cursor claims no word`() = ls.checkContext(
        // L20, S44, S45: `ls --json a`: "unrecognized option '--json'". After the cursor it leaves the answer alone;
        // before it, it claims no word, as `ls --zzz |` does (G11 decides L20).
        at("L20 L21", "ls --rec| --json", name("--recursive")),
        at("L20 L21", "ls | --json", FILES),
        at("S44 S45", "ls --json |", FILES),
        at("S44 S45", "ls --json --rec|", name("--recursive")),
        at("L22", "ls --rec| --js", name("--recursive")),
    )

    @Test
    fun `a value in its own word after the cursor leaves the slot to the option`() = ls.checkContext(
        // K8a, L2b: `ls --format long dir` lists dir long; the cursor word is still the value.
        at("L2b K8a", "ls --format | dir", *spaced("--format")),
        at("L2b K8a", "ls --sort t| dir", *spaced("--sort", "t")),
    )

    @Test
    fun `an ambiguous long earlier on the line leaves the slot under the cursor its own answer`() = ls.checkContext(
        // S45, K34, K46: `ls --re a`, `ls --s=x a` and `ls --re -l a` are "option ... is ambiguous" whatever follows,
        // and klap's parse refuses them too. Tab still answers the slot under the cursor, and goes quiet only for the
        // word right after the ambiguous long (`ls --s |` above): a design choice, where ls would run nothing.
        at("S45", "ls --re a |", FILES),
        at("S45", "ls --re -l |", FILES),
        at("S45", "ls --re -l --rec|", name("--recursive")),
        at("S45 K46", "ls --s=x |", FILES),
        at("S45 K46", "ls --s=x --rec|", name("--recursive")),
        at("S45 K8a", "ls --re --format |", *spaced("--format")),
    )

    @Test
    fun `klap's --json is no name of ls`() = ls.checkContext(
        // G11, L20: `ls --json a`: "unrecognized option '--json'", so a --json under the cursor is no name of ls.
        at("G11", "ls --js| a", *NOTHING),
    )
}
