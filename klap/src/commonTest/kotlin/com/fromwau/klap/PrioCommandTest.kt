package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `prio = true` (todo 36b and 47f): a command that runs ahead of the rest of the line, as `mytool license` should
 * whatever else the tool requires. It beats its parent's required globals and `--completion` and `--docs`;
 * `--help` and `--version` keep their usual meaning beside it.
 */
class PrioCommandTest {

    private fun tool(prio: Boolean) = cli("mytool") {
        version = Version.Of("1.0")
        val token = globalOption(long = "token", help = "the api token").required()
        command("license") {
            this.prio = prio
            help = "print the license"
            action { Ok("MIT") }
        }
        command("sync") {
            action { Ok("synced with ${token()}") }
        }
        action { Ok("root with ${token()}") }
    }

    private fun Cli.invocation(vararg argv: String): Invocation = parse(argv.toList()).assertSuccess()

    @Test
    fun `a prio command runs without its parent's required globals`() {
        assertEquals("MIT", tool(prio = true).bindText("license"))
        tool(prio = false).parse(listOf("license")).assertError<CliError.MissingRequiredOption>()
        tool(prio = true).parse(listOf("sync")).assertError<CliError.MissingRequiredOption>()
    }

    @Test
    fun `help beside a prio command shows that command's help in either position`() {
        for (argv in listOf(arrayOf("license", "--help"), arrayOf("--help", "license"))) {
            val help = tool(prio = true).invocation(*argv)
            assertIs<Invocation.ShowHelp>(help)
            assertEquals("mytool license", help.qualifiedName, argv.toList().toString())
        }
    }

    @Test
    fun `version beside a prio command still prints the version`() {
        assertIs<Invocation.ShowVersion>(tool(prio = true).invocation("--version", "license"))
        assertIs<Invocation.ShowVersion>(tool(prio = true).invocation("license", "--version"))
    }

    @Test
    fun `a prio command runs ahead of --completion and --docs`() {
        assertEquals("MIT", tool(prio = true).bindText("--completion", "bash", "license"))
        assertEquals("MIT", tool(prio = true).bindText("license", "--docs", "markdown"))
        assertIs<Invocation.ShowCompletion>(tool(prio = false).invocation("--completion", "bash", "license"))
    }

    @Test
    fun `the parent's own option before a prio command binds on the parent's segment`() {
        val tool = cli("mytool") {
            val verbose = flag(short = 'v', long = "verbose")
            command("license") {
                prio = true
                action { Ok("MIT v=${verbose()}") }
            }
            command("sync") { action { Ok("synced v=${verbose()}") } }
            action { Ok("root") }
        }
        assertEquals("MIT v=true", tool.bindText("-v", "license"))
        assertEquals("synced v=true", tool.bindText("-v", "sync"))
        assertEquals("MIT v=true", tool.bindText("-v", "--completion", "bash", "license"))
        assertTrue("license" in tool.offered("-v", ""))
    }

    @Test
    fun `a prio command skips its parent's own required option`() {
        val tool = cli("mytool") {
            val token = option(long = "token").required()
            command("license") {
                prio = true
                action { Ok("MIT") }
            }
            command("sync") { action { Ok("synced with ${token()}") } }
        }
        assertEquals("MIT", tool.bindText("license"))
        assertEquals(CliError.MissingRequiredOption("--token"), tool.parse(listOf("sync")).assertError())
    }
}
