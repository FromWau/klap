package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.klap.internal.render.completeCandidates
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
        val tag = globalOption("--tag", "-t").choice("TAG1", "TAG2")
        val invert = flag("--invert-match", "-v")
        val regexp = option("--regexp", "-e").choice("alpha", "beta")
        val files = argument("file").multiple(min = 0).completeWith { candidate("FILE") }
        action { Ok("${tag()} ${invert()} ${regexp()} ${files()}") }
    }

    private fun dispatcher(): Cli = cli("app") {
        globalOption("--tag", "-t").choice("TAG1", "TAG2")
        command("sub") {
            option("--regexp", "-e").choice("alpha", "beta")
            argument("file").multiple(min = 0).completeWith { candidate("FILE") }
            action { Ok("") }
        }
    }

    @Test
    fun aGlobalInAValueSlotLeavesTheCursorOnAnOperand() {
        // parse: `-e` binds the literal "--tag" and f.txt stays an operand, so the next word is an operand
        // too — offering --tag's values would advertise a binding the parser will not make.
        assertEquals(listOf("FILE"), grep().candidatesFor("-e", "--tag", ""))
    }

    @Test
    fun aBuiltinInAValueSlotLeavesTheCursorOnAnOperand() {
        // --color is answered by its own preemption ahead of every other branch, so it needs its own case.
        assertEquals(listOf("FILE"), grep().candidatesFor("-e", "--color", ""))
        assertEquals(listOf("FILE"), grep().candidatesFor("-e", "--json", ""))
    }

    @Test
    fun theAttachedFormInAValueSlotLeavesTheCursorOnAnOperand() {
        assertEquals(listOf("FILE"), grep().candidatesFor("-e", "--tag=v", ""))
    }

    @Test
    fun aClusterEndingInAValueTakingShortShieldsTheSlotToo() {
        // `-ve`: the flag peels off and `-e` takes the next token, so "--tag" is a value here as well.
        assertEquals(listOf("FILE"), grep().candidatesFor("-ve", "--tag", ""))
    }

    @Test
    fun theSlotIsShieldedInsideASubcommandToo() {
        assertEquals(listOf("FILE"), dispatcher().candidatesFor("sub", "-e", "--tag", ""))
        assertEquals(listOf("FILE"), dispatcher().candidatesFor("sub", "-e", "--color", ""))
    }

    // --- Controls: an option that really is waiting for a value still completes it ---

    @Test
    fun aDanglingLocalOptionStillCompletesItsOwnValues() {
        assertEquals(listOf("alpha", "beta"), grep().candidatesFor("-e", ""))
        assertEquals(listOf("alpha", "beta"), grep().candidatesFor("-ve", ""))
    }

    @Test
    fun aDanglingGlobalStillCompletesItsOwnValues() {
        assertEquals(listOf("TAG1", "TAG2"), grep().candidatesFor("--tag", ""))
        assertEquals(listOf("TAG1", "TAG2"), grep().candidatesFor("-t", ""))
        assertEquals(listOf("TAG1", "TAG2"), dispatcher().candidatesFor("sub", "--tag", ""))
    }

    @Test
    fun aDanglingColorStillCompletesItsChoices() {
        assertEquals(listOf("auto", "always", "never"), grep().candidatesFor("--color", ""))
    }
}

private fun Cli.candidatesFor(vararg words: String): List<String> =
    completeCandidates(words.toList()).map { it.value }
