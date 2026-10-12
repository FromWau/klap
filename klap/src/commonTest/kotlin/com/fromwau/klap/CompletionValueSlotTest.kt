package com.fromwau.klap

import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Completion must read a value slot the way [parse] does: a token an option already consumed is a VALUE,
 * not an option still waiting for one, so the cursor after it sits on an operand.
 *
 * Each case names the parse-side answer it has to agree with. The controls at the end are the other half of
 * the claim — a genuinely dangling option still completes its own values, or the rule would have bought
 * agreement by breaking value completion outright.
 */
class CompletionValueSlotTest {

    // Every candidate set below is distinct, so a failure names which branch answered instead of only
    // saying "not that": TAG1/TAG2 is the global's, alpha/beta the local option's, FILE the operand's.
    private fun grep(): Cli = cli("mygrep") {
        val tag = globalOption(short = 't', long = "tag").choice("TAG1", "TAG2")
        val invert = flag(short = 'v', long = "invert-match")
        val regexp = option(short = 'e', long = "regexp").choice("alpha", "beta")
        val files = argument("file").multiple(min = 0).completeWith { candidate("FILE") }
        action { Ok("${tag()} ${invert()} ${regexp()} ${files()}") }
    }

    private fun dispatcher(): Cli = cli("app") {
        globalOption(short = 't', long = "tag").choice("TAG1", "TAG2")
        command("sub") {
            option(short = 'e', long = "regexp").choice("alpha", "beta")
            argument("file").multiple(min = 0).completeWith { candidate("FILE") }
            action { Ok("") }
        }
    }

    @Test
    fun `a global in a value slot leaves the cursor on an operand`() {
        // parse: `-e` binds the literal "--tag" and f.txt stays an operand, so the next word is an operand
        // too — offering --tag's values would advertise a binding the parser will not make.
        assertEquals(listOf("FILE"), grep().offered("-e", "--tag", ""))
    }

    @Test
    fun `a builtin in a value slot leaves the cursor on an operand`() {
        // --color is answered by its own preemption ahead of every other branch, so it needs its own case.
        assertEquals(listOf("FILE"), grep().offered("-e", "--color", ""))
        assertEquals(listOf("FILE"), grep().offered("-e", "--json", ""))
    }

    @Test
    fun `the attached form in a value slot leaves the cursor on an operand`() {
        assertEquals(listOf("FILE"), grep().offered("-e", "--tag=v", ""))
    }

    @Test
    fun `a cluster ending in a value taking short shields the slot too`() {
        // `-ve`: the flag peels off and `-e` takes the next token, so "--tag" is a value here as well.
        assertEquals(listOf("FILE"), grep().offered("-ve", "--tag", ""))
    }

    @Test
    fun `the slot is shielded inside a subcommand too`() {
        assertEquals(listOf("FILE"), dispatcher().offered("sub", "-e", "--tag", ""))
        assertEquals(listOf("FILE"), dispatcher().offered("sub", "-e", "--color", ""))
    }

    // --- Controls: an option that really is waiting for a value still completes it ---

    @Test
    fun `a dangling local option still completes its own values`() {
        assertEquals(listOf("alpha", "beta"), grep().offered("-e", ""))
        assertEquals(listOf("alpha", "beta"), grep().offered("-ve", ""))
    }

    @Test
    fun `a dangling global still completes its own values`() {
        assertEquals(listOf("TAG1", "TAG2"), grep().offered("--tag", ""))
        assertEquals(listOf("TAG1", "TAG2"), grep().offered("-t", ""))
        assertEquals(listOf("TAG1", "TAG2"), dispatcher().offered("sub", "--tag", ""))
    }

    @Test
    fun `an attached color completes its choices`() {
        assertEquals(listOf("--color=auto", "--color=always", "--color=never"), grep().offered("--color="))
    }
}

