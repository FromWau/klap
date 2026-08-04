package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The guide's inference snippets, transcribed and executed. `docs/guide.md`'s abbreviation section makes
 * specific claims about what each `Inference` mode resolves and what a miss suggests; these pin those
 * claims against the real parser so a change that breaks one shows up here, not only in prose.
 */
class GuideSnippetsTest {

    // --- "Flags: boolean, counted, negatable": the inference table's own worked example ---

    private fun guideInferenceExample() = cli("tasks") {
        inference = Inference.All
        command("list") { action<String>(human = { it }) { Ok("list") } }
        command("listen") { action<String>(human = { it }) { Ok("listen") } }
    }

    @Test
    fun theGuidesInferenceTableIsWhatTheModesDo() {
        // The guide's table claims All resolves a subcommand prefix and that exact still wins outright.
        assertEquals("listen", guideInferenceExample().bindText("liste"))
        assertEquals("list", guideInferenceExample().bindText("list"))
    }

    @Test
    fun theGuidesStrictHelpSuggestionIsReal() {
        val strict = cli("tasks") {
            action<String>(human = { it }) { Ok("ran") }
        }
        assertEquals(
            CliError.UnknownOption("--h", "--help"),
            assertIs<Result.Error<CliError>>(strict.parse(listOf("--h"))).error,
        )
    }
}
