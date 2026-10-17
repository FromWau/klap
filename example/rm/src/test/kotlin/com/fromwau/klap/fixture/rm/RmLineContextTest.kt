package com.fromwau.klap.fixture.rm

import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.rm.RmLineContext.FILES
import com.fromwau.klap.fixture.rm.RmLineContext.assertOffersAll
import com.fromwau.klap.fixture.rm.RmLineContext.at
import com.fromwau.klap.fixture.rm.RmLineContext.checkContext
import com.fromwau.klap.fixture.rm.RmLineContext.name
import kotlin.test.Test

/*
 * rm reads no word after the cursor into what Tab offers: it has no option that excludes another, refuses no repeat,
 * declares no provider and no option taking a required value. So the L and S rows of exclusive sets, counted and
 * repeated inputs, absentWhen triggers and value words (L5, L6, L8 to L11, L15, L16, L23 to L25, S2, S6 to S15, S17 to
 * S21, S23 to S28, S30, S31, S33, S34, S36 to S43) have no rm cell; L17 to L19 have none either, as rm has no
 * subcommands. L27 is fish's rule and belongs to the live shell tests.
 */

/**
 * How the words around the cursor change what Tab offers on an rm line (L and S of the testing matrix): the words
 * after the cursor, and what the line already holds, from -f and its override partners to the end of options. Every
 * expectation is what real rm 9.12 accepts at the cursor.
 */
class RmLineContextTest {

    private val rm = rmCli().cli

    @Test
    fun `words after the cursor that bear on nothing leave the answer as at the end of the line`() = rm.checkContext(
        at("L1", "rm --verb|", name("--verbose")),
        at("L1", "rm -v |", FILES),
        at("L2a", "rm --verb| -f", name("--verbose")),
        at("L2a", "rm --verb| --interactive=never", name("--verbose")),
        at("L2a", "rm | -v", FILES),
        at("L2b", "rm --verb| a b", name("--verbose")),
        at("L2b L3c L13", "rm | b", FILES),
        at("L13", "rm a | b c", FILES),
        // `rm --zzz a`: "unrecognized option '--zzz'"; `rm a --v`: "option '--v' is ambiguous".
        at("L21", "rm --verb| --zzz a b", name("--verbose")),
        at("L21", "rm | a --zzz", FILES),
        at("L22", "rm --for| --v", name("--force")),
    )

    @Test
    fun `-f after the cursor leaves the operand slot with its files`() = rm.checkContext(
        // L12: `rm a -f` and `rm -f` both run; -f only lets the list be empty.
        at("L12", "rm | -f", FILES),
        at("L12", "rm | --force", FILES),
        at("L12", "rm a | -rf", FILES),
        at("L12", "rm | --f", FILES),
    )

    @Test
    fun `an override partner after the cursor rules nothing out`() {
        // L7: `rm -f -i a` prompts and `rm -i -f a` does not: the later wins, and rm accepts every order.
        rm.checkContext(
            at("L7", "rm --for| -i", name("--force")),
            at("L7", "rm --for| --interactive=always", name("--force")),
        )
        checkCells(listOf("rm -| -f" to listOf("-i", "-I", "-f", "--force"), "rm -| -i" to listOf("-f", "-i"))) {
            rm.assertOffersAll("L7", it.first, it.second)
        }
    }

    @Test
    fun `words behind an end of options after the cursor are operands and never inputs`() = rm.checkContext(
        // L14: `rm a -- -v`: "cannot remove '-v'"; `rm -- -f` the same for -f.
        at("L14", "rm --verb| -- -v", name("--verbose")),
        at("L14", "rm --for| -- -f", name("--force")),
        at("L14", "rm | -- -f", FILES),
        at("L14", "rm -v | -- -x", FILES),
    )

    @Test
    fun `the rest of the word under the cursor is not sent`() = rm.checkContext(
        // L26: the completion contract, not an rm run: the word is completed as far as it is typed.
        at("L26", "rm --verb|ose", name("--verbose")),
        at("L26", "rm --for|ce a", name("--force")),
        at("L26", "rm --rec|ursive dd", name("--recursive")),
    )

    @Test
    fun `after an end of options every word is an operand whatever it is spelled as`() = rm.checkContext(
        // L4a to L4c: `rm a -- -v` and `rm -- --interactive=never`: "cannot remove", each word a file.
        at("L4a", "rm -- |", FILES),
        at("L4a", "rm -- -|", FILES),
        at("L4b", "rm -- --interactive |", FILES),
        at("L4b", "rm -- -f |", FILES),
        at("L4c", "rm -- --interactive=al|", FILES),
        at("L4c", "rm -- --preserve-root=|", FILES),
        at("L4c", "rm -- -rf|", FILES),
    )

    @Test
    fun `an unknown long before the cursor claims no word`() = rm.checkContext(
        // S45: `rm --zzz a`: "unrecognized option '--zzz'"; its next word is no value.
        at("S45", "rm --zzz |", FILES),
        at("S45", "rm --zzz --verb|", name("--verbose")),
    )

    @Test
    fun `an input rm takes again stays offered once given`() {
        // S1, S16: `rm -v -v a`, `rm --verbose --verbose a`, `rm -rr dd` and `rm -f -f` run.
        rm.checkContext(
            at("S1", "rm --verb|", name("--verbose")),
            at("S16", "rm -v --verb|", name("--verbose")),
            at("S16", "rm --verbose -v --verb|", name("--verbose")),
            at("S16", "rm -f --for|", name("--force")),
            at("S16", "rm -r --rec|", name("--recursive")),
            at("S16", "rm --dir --di|", name("--dir")),
        )
        // S20: `rm --interactive=never --interactive a` prompts: the later --interactive is read.
        checkCells(listOf("rm --interactive=never -|" to listOf("--interactive"), "rm -v -|" to listOf("-v"))) {
            rm.assertOffersAll("S16 S20", it.first, it.second)
        }
    }

    @Test
    fun `an override partner given keeps the others offered`() {
        // S22: `rm -i -f a` removes without asking, `rm -f -i a` asks, `rm -I -i a` asks for a.
        rm.checkContext(
            at("S22", "rm -i --for|", name("--force")),
            at("S22", "rm --interactive=never --for|", name("--force")),
        )
        checkCells(listOf("rm -i -|" to listOf("-f", "-I"), "rm -f -|" to listOf("-i", "-I", "--interactive"))) {
            rm.assertOffersAll("S22", it.first, it.second)
        }
    }

    @Test
    fun `-f given leaves the operand slot with its files`() = rm.checkContext(
        // S29: `rm -f a` removes a; -f only lets the list be empty.
        at("S29", "rm -f |", FILES),
        at("S29", "rm -rf |", FILES),
        at("S29", "rm --force |", FILES),
        at("S29", "rm -f a |", FILES),
        at("S29", "rm -i -f |", FILES),
    )

    @Test
    fun `a lone dash is an operand`() = rm.checkContext(
        // S32: `rm -` is "cannot remove '-'", so - was a file.
        at("S32", "rm - |", FILES),
        at("S32", "rm -f - |", FILES),
        at("S32", "rm - --verb|", name("--verbose")),
    )

    @Test
    fun `options after an operand still complete since rm permutes`() = rm.checkContext(
        // S35: `rm a -v` printed "removed 'a'".
        at("S35", "rm a --verb| b", name("--verbose")),
        at("S35", "rm a b --verb|", name("--verbose")),
        at("S35", "rm a --for|", name("--force")),
    )

    @Test
    fun `an optional value given bare takes no word and the next one is an operand or an option`() = rm.checkContext(
        // S46: `rm --interactive never a` prompts for never and a; `rm --preserve-root all` removes all.
        at("S46", "rm --interactive |", FILES),
        at("S46", "rm --interactive never |", FILES),
        at("S46", "rm --interactive --verb|", name("--verbose")),
        at("S46", "rm --preserve-root |", FILES),
        at("S46", "rm --preserve-root --verb|", name("--verbose")),
    )

    @Test
    fun `klap's built-ins rm lacks bear on nothing around the cursor`() = rm.checkContext(
        // L20, S44, S45: `rm --json a`, `rm --color never a`, `rm --completion bash a`: "unrecognized option". After
        // the cursor they leave the answer alone; before it they claim no word, as `rm --zzz |` does (G11 decides L20).
        at("L20 L21", "rm --verb| --json", name("--verbose")),
        at("L20 L21", "rm | --json", FILES),
        at("L20 L21", "rm | --color never", FILES),
        at("S44 S45", "rm --json |", FILES),
        at("S44 S45", "rm --completion |", FILES),
        at("S44 S45", "rm --json --verb|", name("--verbose")),
        at("S44 S45", "rm --color=never --verb|", name("--verbose")),
    )

    @Test
    fun `an abbreviated --interactive before the cursor claims no word`() = rm.checkContext(
        // S45, S46: `rm --i a` and `rm --inter a` prompt as a bare --interactive, so the next word is a file or a name;
        // getopt reads --i as rm's one long starting so.
        at("S45 S46", "rm --i |", FILES),
        at("S45 S46", "rm --inter |", FILES),
        at("S45 S46", "rm --i --verb|", name("--verbose")),
        at("S45 S46", "rm --inter a |", FILES),
    )
}
