package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Under `--json`, `--help` prints the help as data: each input's fields instead of its text row. */
class HelpJsonTest {

    private fun tool(builtinHelp: Help = Help.ByCommand()): Cli = cli("tool") {
        help = "a tool"
        builtins { this.help = builtinHelp }
        version = Version.Of("1.0")
        command("get") {
            help = "print a key"
            argument("key", "the key")
            flag(short = 'r', long = "raw", help = "print it raw")
            option(long = "mode", help = "how to read it").choice("fast", "slow").default("fast")
            example("tool get name", "one key")
            action { Ok("") }
        }
    }

    private fun stdout(vararg args: String, tool: Cli = tool()): Pair<Int, String> {
        val t = RecordingTerminal()
        val code = tool.run(arrayOf(*args), t)
        return code to t.out
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
                """"globalOptions":[{"names":["--help"],"help":"Show this help"},""" +
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
        val help = Json.parseToJsonElement(stdout("--help", "--json", tool = tool(Help.ByTree())).second).jsonObject
        assertEquals(
            listOf("get", "completion", "docs"),
            help.getValue("commands").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        assertTrue(help.getValue("globalOptions").toString().contains("\"--help-all\""))
    }

    @Test
    fun `--help-all under json prints every command's help as an array`() {
        val all = Json.parseToJsonElement(stdout("--help-all", "--json", tool = tool(Help.ByTree())).second).jsonArray
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
            command("cp") {
                help = "copy files"
                val target = option(short = 't', long = "target-directory", help = "copy into this directory")
                argument("source", "what to copy").multiple(min = 1)
                argument("dest", "where to copy it").absentWhen(target)
                action { Ok("") }
            }
            command("rm") {
                help = "remove files"
                val force = flag(short = 'f', long = "force", help = "ignore missing files")
                argument("file", "what to remove").multiple(min = 1).optionalWhen(force)
                action { Ok("") }
            }
        }
        fun arguments(command: String): String {
            val t = RecordingTerminal()
            conditional.run(arrayOf(command, "--help", "--json"), t)
            return Json.parseToJsonElement(t.out).jsonObject.getValue("arguments").toString()
        }
        assertEquals(
            """[{"name":"source","help":"what to copy","required":true,"repeatable":true,"min":1},""" +
                """{"name":"dest","help":"where to copy it","required":true,"repeatable":false,""" +
                """"absentWhen":"--target-directory"}]""",
            arguments("cp"),
        )
        assertEquals(
            """[{"name":"file","help":"what to remove","required":true,"repeatable":true,"min":1,""" +
                """"optionalWhen":"--force"}]""",
            arguments("rm"),
        )
    }

    private fun page(command: String, tool: Cli): JsonObject {
        val t = RecordingTerminal()
        tool.run(arrayOf(command, "--help", "--json"), t)
        return Json.parseToJsonElement(t.out).jsonObject
    }

    @Test
    fun `an operand states its count bounds and the inputs that cap it`() {
        val tool = cli("tool") {
            command("cp") {
                val noTarget = flag(short = 'T', long = "no-target-directory", help = "treat DEST as a normal file")
                argument("source", "what to copy").multiple(min = 1).atMostWhen(noTarget, 1)
                argument("dest", "where to copy it")
                action { Ok("") }
            }
            command("pick") {
                argument("item", "what to pick").multiple(max = 3)
                action { Ok("") }
            }
        }
        assertEquals(
            """{"name":"source","help":"what to copy","required":true,"repeatable":true,"min":1,""" +
                """"atMostWhen":[{"input":"--no-target-directory","max":1}]}""",
            page("cp", tool).getValue("arguments").jsonArray.first().toString(),
        )
        assertEquals(
            """{"name":"item","help":"what to pick","required":false,"repeatable":true,"max":3}""",
            page("pick", tool).getValue("arguments").jsonArray.single().toString(),
        )
    }

    @Test
    fun `a repeatable option states its minimum`() {
        val tool = cli("tool") {
            command("tag") {
                option(short = 't', long = "tag", help = "a tag to add").multiple(min = 2)
                option(long = "label", help = "a label").multiple()
                action { Ok("") }
            }
        }
        val options = page("tag", tool).getValue("options").jsonArray.map { it.toString() }
        assertEquals(
            """{"names":["-t","--tag"],"help":"a tag to add","value":"value","required":true,"repeatable":true,""" +
                """"min":2}""",
            options[0],
        )
        assertEquals("""{"names":["--label"],"help":"a label","value":"value","repeatable":true}""", options[1])
    }
}
