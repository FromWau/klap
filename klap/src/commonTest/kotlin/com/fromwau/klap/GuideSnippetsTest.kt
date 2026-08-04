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

    // --- "Inputs and converters": the two rendered error shapes ---

    @Test
    fun theGuidesRenderedValueErrorsNameTheDashedOption() {
        val tree = cli("app") {
            val port = option("--port").int().validate("must be 1..65535") { it in 1..65535 }
            val level = option("--level").choice("debug", "info", "warn", "error")
            action { Ok("${port()} ${level()}") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("--port", "70000"), t)
        assertEquals("error: invalid value '70000' for --port: must be 1..65535\n", t.err.toString())

        val t2 = RecordingTerminal()
        tree.run(arrayOf("--level", "bogus"), t2)
        assertEquals(
            "error: invalid value 'bogus' for --level (choose from debug, info, warn, error)\n",
            t2.err.toString(),
        )
    }

    // --- "Help output": group returns its block's value, so a plain val captures the handle ---

    @Test
    fun theGuidesGroupSnippetCapturesAHandleByPlainVal() {
        val tree = cli("deploy") {
            val host = group("Networking") {
                option("--host", "-H", help = "target host").required()
            }
            action { Ok("shipped to ${host()}") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("--host", "example.com"), t)
        assertEquals("shipped to example.com\n", t.out.toString())
    }

    @Test
    fun theGuidesMultiHandleGroupSnippetInfersEachType() {
        val tree = cli("build") {
            val jobs: Opt<Int>
            val tags: Opt<List<String>>
            group("Tuning") {
                jobs = option("--jobs", "-j", help = "parallelism").int().default(1)
                tags = option("--tag", "-t", help = "labels").multiple()
            }
            action { Ok("jobs=${jobs()} tags=${tags()}") }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("-j", "4", "-t", "a", "-t", "b"), t)
        assertEquals("jobs=4 tags=[a, b]\n", t.out.toString())
    }
}
