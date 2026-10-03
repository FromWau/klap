package com.fromwau.klap

import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Under `--json`, `--help` prints the help as data: each input's fields instead of its text row. */
class HelpJsonTest {

    private fun tool(): Cli = cli("tool") {
        description = "a tool"
        version = "1.0"
        command("get", "print a key") {
            argument("key", "the key")
            flag("--raw", "-r", help = "print it raw")
            option("--mode", help = "how to read it").choice("fast", "slow").default("fast")
            example("tool get name", "one key")
            action { Ok("") }
        }
    }

    private fun stdout(vararg args: String): Pair<Int, String> {
        val t = RecordingTerminal()
        val code = tool().run(arrayOf(*args), t)
        return code to t.out.toString()
    }

    @Test
    fun `a command's help under json lists its inputs as fields`() {
        val (code, out) = stdout("get", "--help", "--json")
        assertEquals(0, code)
        assertEquals(
            """{"command":"tool get","usage":"tool get <key> [options]","description":"print a key",""" +
                """"arguments":[{"name":"key","help":"the key","required":true,"repeatable":false}],""" +
                """"options":[{"names":["-r","--raw"],"help":"print it raw"},""" +
                """{"names":["--mode"],"help":"how to read it","value":"fast|slow","default":"fast",""" +
                """"choices":["fast","slow"]}],"commands":[],""" +
                """"globalOptions":[{"names":["-h","--help"],"help":"Show this help"},""" +
                """{"names":["--json"],"help":"Output as JSON"},""" +
                """{"names":["--color"],"help":"Colorize output: auto, always, or never",""" +
                """"value":"auto|always|never",""" +
                """"choices":["auto","always","never"]},{"names":["--version"],"help":"Show the version"}],""" +
                """"examples":[{"command":"tool get name","description":"one key"}]}""" + "\n",
            out,
        )
    }

    @Test
    fun `the root's help under json lists its subcommands and --help-all`() {
        val help = Json.parseToJsonElement(stdout("--help", "--json").second).jsonObject
        assertEquals(
            listOf("get", "completion", "docs"),
            help.getValue("commands").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        assertTrue(help.getValue("globalOptions").toString().contains("\"--help-all\""))
    }

    @Test
    fun `--help-all under json prints every command's help as an array`() {
        val all = Json.parseToJsonElement(stdout("--help-all", "--json").second).jsonArray
        assertEquals(
            listOf("tool", "tool get", "tool completion", "tool docs"),
            all.map { it.jsonObject.getValue("command").jsonPrimitive.content },
        )
    }

    @Test
    fun `--help without json is still text`() {
        assertTrue(stdout("get", "--help").second.startsWith("usage: tool get <key> [options]"))
    }

    @Test
    fun `an argument that a flag or option removes or relaxes names it`() {
        val conditional = cli("tool") {
            command("cp", "copy files") {
                val target = option("--target-directory", "-t", help = "copy into this directory")
                argument("source", "what to copy").multiple(min = 1)
                argument("dest", "where to copy it").absentWhen(target)
                action { Ok("") }
            }
            command("rm", "remove files") {
                val force = flag("--force", "-f", help = "ignore missing files")
                argument("file", "what to remove").multiple(min = 1).requiredUnless(force)
                action { Ok("") }
            }
        }
        fun arguments(command: String): String {
            val t = RecordingTerminal()
            conditional.run(arrayOf(command, "--help", "--json"), t)
            return Json.parseToJsonElement(t.out.toString()).jsonObject.getValue("arguments").toString()
        }
        assertEquals(
            """[{"name":"source","help":"what to copy","required":true,"repeatable":true},""" +
                """{"name":"dest","help":"where to copy it","required":true,"repeatable":false,""" +
                """"absentWith":"--target-directory"}]""",
            arguments("cp"),
        )
        assertEquals(
            """[{"name":"file","help":"what to remove","required":true,"repeatable":true,"optionalWith":"--force"}]""",
            arguments("rm"),
        )
    }
}
