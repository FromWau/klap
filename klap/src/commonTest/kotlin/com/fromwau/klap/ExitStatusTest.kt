package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A command's own exits, declared with [CommandBuilder.exits] and returned as the same [Exit]. */
class ExitStatusTest {

    private enum class ToolExit(
        override val code: Int,
        override val meaning: String,
        override val success: Boolean = false,
    ) : Exit {
        NoMatch(3, "no line matched the pattern"),
        Differ(1, "the files differ", success = true),
    }

    private val tool = cli("tool") {
        help = "Query files.\nEach answer prints on standard output."
        command("get") {
            help = "print what a path selects"
            exits(ToolExit.NoMatch)
            val path = argument("path", "the path")
            action {
                if (path() == "nope") Err(CliError.Failure("no match", exit = ToolExit.NoMatch)) else Ok("")
            }
        }
        command("find") {
            help = "find what a path selects"
            exits(ToolExit.NoMatch)
            action { Ok("") }
        }
        command("diff") {
            help = "compare two files"
            exits(ToolExit.Differ)
            val differ = flag(long = "differ")
            action(exit = { if (differ()) ToolExit.Differ else null }) { Ok("") }
        }
    }

    private fun run(vararg args: String): Pair<Int, RecordingTerminal> {
        val terminal = RecordingTerminal()
        return tool.run(args.toList(), terminal) to terminal
    }

    private fun help(vararg args: String): String = run(*args).second.out

    private val usage = "  2  a usage error, such as an unknown option or a value that does not parse\n"

    @Test
    fun `the man page has a synopsis of every command and the full description`() {
        val man = tool.renderManPage()
        val synopsis = man.substringAfter(".SH SYNOPSIS\n").substringBefore(".SH ")
        assertTrue("tool get <path>" in synopsis, synopsis)
        assertTrue("tool diff" in synopsis, synopsis)
        val description = man.substringAfter(".SH DESCRIPTION\n").substringBefore(".SH ")
        assertTrue("Query files.\nEach answer prints on standard output." in description, description)
        assertEquals(1, Regex(Regex.escape("tool <command>")).findAll(man).count(), man)
    }

    @Test
    fun `the man page lists each exit code once in order with klap's meaning first`() {
        val status = tool.renderManPage().substringAfter(".SH EXIT STATUS\n").substringBefore(".SH ")
        val codes = Regex("""\.B (\d+)\n""").findAll(status).map { it.groupValues[1].toInt() }.toList()
        assertEquals(listOf(0, 1, 2, 3, 141), codes, status)
        assertTrue(".B 1\na failure; the files differ (not a failure)\n" in status, status)
    }

    @Test
    fun `the man page lists an exit several commands declare once`() {
        val status = tool.renderManPage().substringAfter(".SH EXIT STATUS\n").substringBefore(".SH ")
        assertTrue(".B 3\nno line matched the pattern\n" in status, status)
    }

    private enum class StoreExit(override val code: Int, override val meaning: String, override val success: Boolean) :
        Exit {
        NotFound(3, "no task has that id", success = false),
        Differ(3, "the stores differ", success = true),
    }

    private fun store(vararg declared: Pair<String, Exit>): Cli = cli("todo") {
        for ((name, exit) in declared) {
            command(name) {
                exits(exit)
                action { Ok("") }
            }
        }
    }

    private fun Cli.manStatus(): String = renderManPage().substringAfter(".SH EXIT STATUS\n").substringBefore(".SH ")

    private fun Cli.markdownStatus(): String =
        renderMarkdownDocs().substringAfter("## Exit status\n").substringBefore("\n## ")

    @Test
    fun `a code two commands give different meanings names the command before each meaning`() {
        val tool = store("get" to StoreExit.NotFound, "cmp" to StoreExit.Differ)
        val meanings = "todo get: no task has that id; todo cmp: the stores differ (not a failure)"
        assertTrue(".B 3\n$meanings\n" in tool.manStatus(), tool.manStatus())
        assertTrue("| 3 | $meanings |" in tool.markdownStatus(), tool.markdownStatus())
    }

    @Test
    fun `one exit two commands declare on a code of its own names no command`() {
        val tool = store("get" to StoreExit.NotFound, "show" to StoreExit.NotFound)
        assertTrue(".B 3\nno task has that id\n" in tool.manStatus(), tool.manStatus())
        assertTrue("| 3 | no task has that id |" in tool.markdownStatus(), tool.markdownStatus())
    }

    @Test
    fun `a named meaning lists every command declaring that exit`() {
        val tool = store("get" to StoreExit.NotFound, "show" to StoreExit.NotFound, "cmp" to StoreExit.Differ)
        val meanings = "todo get, todo show: no task has that id; todo cmp: the stores differ (not a failure)"
        assertTrue(".B 3\n$meanings\n" in tool.manStatus(), tool.manStatus())
        assertTrue("| 3 | $meanings |" in tool.markdownStatus(), tool.markdownStatus())
    }

    @Test
    fun `klap's own meaning for a code stays first and unnamed beside two declared meanings`() {
        val differ = object : Exit {
            override val code = 1
            override val meaning = "the stores differ"
            override val success = true
        }
        val stale = object : Exit {
            override val code = 1
            override val meaning = "the store is stale"
        }
        val tool = store("cmp" to differ, "check" to stale)
        val meanings = "a failure; todo cmp: the stores differ (not a failure); todo check: the store is stale"
        assertTrue(".B 1\n$meanings\n" in tool.manStatus(), tool.manStatus())
        assertTrue("| 1 | $meanings |" in tool.markdownStatus(), tool.markdownStatus())
    }

    @Test
    fun `the markdown page lists each command's own exit codes in its section`() {
        val md = tool.renderMarkdownDocs()
        val get = md.substringAfter("## tool get").substringBefore("\n## ")
        val table = "**Exit status**\n\n| Code | Meaning |\n|---|---|\n| 3 | no line matched the pattern |"
        assertTrue(table in get, get)
        val diff = md.substringAfter("## tool diff").substringBefore("\n## ")
        assertTrue("| 1 | the files differ (not a failure) |" in diff, diff)
    }

    @Test
    fun `the markdown page lists klap's exit codes and each declared exit once as the man page does`() {
        val status = tool.renderMarkdownDocs().substringAfter("## Exit status\n").substringBefore("\n## ")
        val codes = Regex("""\| (\d+) \|""").findAll(status).map { it.groupValues[1].toInt() }.toList()
        assertEquals(listOf(0, 1, 2, 3, 141), codes, status)
        assertTrue("| 1 | a failure; the files differ (not a failure) |" in status, status)
    }

    @Test
    fun `a command's help lists klap's exit codes then its own`() {
        val get = help("get", "--help")
        val status = "Exit status:\n  0  success\n  1  a failure\n$usage  3  no line matched the pattern\n"
        assertTrue(status in get, get)
    }

    @Test
    fun `a code a command declares joins klap's meaning for it in its help as in the man page`() {
        val diff = help("diff", "--help")
        assertTrue("Exit status:\n  0  success\n  1  a failure; the files differ (not a failure)\n$usage" in diff, diff)
    }

    @Test
    fun `the help of a command declaring no exit and of the root lists klap's exit codes`() {
        val common = "Exit status:\n  0  success\n  1  a failure\n$usage"
        assertTrue(common in help("--help"), help("--help"))
        val plain = cli("plain") { action { Ok("") } }
        val out = RecordingTerminal().also { plain.run(listOf("--help"), it) }.out
        assertTrue(common in out, out)
    }

    @Test
    fun `help as JSON lists klap's exits then the command's own with whether each is a success`() {
        val exits = Json.parseToJsonElement(help("diff", "--help", "--json")).jsonObject.getValue("exits").jsonArray
        val expected = """[{"code":0,"meaning":"success","success":true},""" +
            """{"code":1,"meaning":"a failure","success":false},""" +
            """{"code":1,"meaning":"the files differ","success":true},""" +
            """{"code":2,"meaning":"a usage error, such as an unknown option or a value that does not parse",""" +
            """"success":false}]"""
        assertEquals(expected, exits.toString())
    }

    @Test
    fun `an action exits with the code of the exit it returns`() {
        assertEquals(1, run("diff", "--differ").first)
        assertEquals(0, run("diff").first)
    }

    @Test
    fun `an error exits with the code of the exit it carries`() {
        assertEquals(3, run("get", "nope").first)
        assertEquals(Exit.Failure, CliError.Failure("x").exit)
        assertEquals(Exit.Usage, CliError.Usage("x").exit)
    }

    @Test
    fun `klap's own exits carry its codes and meanings`() {
        val own = listOf(Exit.Success, Exit.Failure, Exit.Usage, Exit.BrokenPipe)
        assertEquals(listOf(0, 1, 2, 141), own.map { it.code })
        assertTrue(Exit.Success.success)
        assertEquals("a failure", Exit.Failure.meaning)
    }

    @Test
    fun `an exit out of range declared twice or one of klap's own is refused when the command is built`() {
        val outOfRange = object : Exit {
            override val code = 256
            override val meaning = "too big"
        }
        val zero = object : Exit {
            override val code = 0
            override val meaning = "success is no exit to declare"
        }
        val twin = object : Exit {
            override val code = 3
            override val meaning = "the same code again"
        }
        val refused = listOf(
            listOf(outOfRange),
            listOf(zero),
            listOf(ToolExit.NoMatch, twin),
            listOf(Exit.Failure),
        )
        for (declared in refused) {
            assertFailsWith<IllegalArgumentException>(declared.toString()) {
                cli("t") {
                    exits(*declared.toTypedArray())
                    action { Ok("") }
                }
            }
        }
    }
}
