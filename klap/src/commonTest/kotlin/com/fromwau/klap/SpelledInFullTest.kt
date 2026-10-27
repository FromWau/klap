package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `.spelledInFull()`: a long that matches only whole, whatever the CLI's [Abbreviation], as rm's
 * `--no-preserve-root` ("you may not abbreviate the --no-preserve-root option").
 */
class SpelledInFullTest {

    private fun rm(mode: Abbreviation = Abbreviation.Options) = cli("rm") {
        abbreviation = mode
        val noPreserveRoot = flag(long = "no-preserve-root").spelledInFull()
        val verbose = flag(long = "verbose")
        val output = option(long = "output").spelledInFull()
        action<String>(human = { it }) { Ok("unsafe=${noPreserveRoot()} verbose=${verbose()} output=${output()}") }
    }

    @Test
    fun `a long spelled in full binds`() {
        assertEquals("unsafe=true verbose=false output=null", rm().bindText("--no-preserve-root"))
        assertEquals("unsafe=false verbose=false output=x", rm().bindText("--output=x"))
        assertEquals("unsafe=false verbose=false output=x", rm().bindText("--output", "x"))
    }

    @Test
    fun `an abbreviation of a long spelled in full is unknown in every mode`() {
        for (mode in listOf(Abbreviation.LongOptions, Abbreviation.Options, Abbreviation.All)) {
            for (typed in listOf("--n", "--no-p", "--no-preserve-roo")) {
                assertEquals(
                    CliError.UnknownOption(typed, "--no-preserve-root"),
                    rm(mode).parse(listOf(typed)).assertError<CliError.UnknownOption>(),
                    "${mode.name} $typed",
                )
            }
            assertEquals("--out=x", rm(mode).parse(listOf("--out=x")).assertError<CliError.UnknownOption>().token)
        }
    }

    @Test
    fun `the other longs still abbreviate`() {
        assertEquals("unsafe=false verbose=true output=null", rm().bindText("--verb"))
    }

    @Test
    fun `a prefix it shares with another long is ambiguous`() {
        // getopt_long reports the ambiguity before rm's own check of the spelling runs.
        val tree = cli("t") {
            flag(long = "no-preserve-root").spelledInFull()
            flag(long = "no-pager")
            action { Ok("") }
        }
        assertEquals(
            CliError.AmbiguousOption("--no-p", listOf("--no-preserve-root", "--no-pager")),
            tree.parse(listOf("--no-p")).assertError<CliError.AmbiguousOption>(),
        )
        tree.parse(listOf("--no-pa")).assertSuccess()
    }

    @Test
    fun `a negatable flag's negation is spelled in full too`() {
        val tree = cli("t") {
            val cache = flag(long = "cache").negatable().spelledInFull()
            action<String>(human = { it }) { Ok("cache=${cache()}") }
        }
        assertEquals("cache=false", tree.bindText("--no-cache"))
        tree.parse(listOf("--no-c")).assertError<CliError.UnknownOption>()
        tree.parse(listOf("--ca")).assertError<CliError.UnknownOption>()
    }

    @Test
    fun `completion still offers the whole word`() {
        assertEquals(listOf("--no-preserve-root"), rm().offered("--no-p"))
    }

    @Test
    fun `an abbreviation before the cursor takes no value`() {
        // `--out` is no spelling of --output, so the word after it is an operand rather than its value.
        val tree = cli("t") {
            abbreviation = Abbreviation.Options
            option(long = "output").spelledInFull().completeWith { candidate("VALUE") }
            argument("file").multiple(min = 0).completeWith { candidate("FILE") }
            action { Ok("") }
        }
        assertEquals(listOf("VALUE"), tree.offered("--output", ""))
        assertEquals(listOf("FILE"), tree.offered("--out", ""))
    }
}
