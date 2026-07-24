package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HelpTest {

    @Test
    fun argSummary_marksCardinality() {
        val cmd = cli("add") {
            argument("text")
            argument("note").optional()
            option("priority", "p").int().default(0)
        }
        assertEquals("<text> [note]", cmd.argSummary())
    }

    @Test
    fun helpText_leafListsArgumentsAndOptions() {
        val cmd = cli("add") {
            description = "Add a task"
            argument("text", help = "the task text")
            flag("done", "d", help = "mark it done")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue(help.startsWith("usage: add <text>"), help)
        assertTrue("the task text" in help, help)
        assertTrue("-d, --done" in help, help)
        assertTrue("-h, --help" in help, help)
    }

    @Test
    fun helpText_listsBuiltinFlags() {
        val cmd = cli("todo") {
            version = "1.0.0"
            argument("text")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-h, --help" in help, help)
        assertTrue("--json" in help, help)
        assertTrue("--version" in help, help)
    }

    @Test
    fun helpText_omitsVersionFlagWhenUnversioned() {
        val cmd = cli("add") {
            argument("text")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-h, --help" in help, help)
        assertTrue("--version" !in help, help)
    }

    @Test
    fun helpText_groupListsSubcommands() {
        val cmd = cli("config") {
            description = "Manage config"
            command("get") {
                description = "read a key"
                action { Ok("") }
            }
            command("set") {
                description = "write a key"
                action { Ok("") }
            }
        }
        val help = cmd.helpText()
        assertTrue("get" in help && "read a key" in help, help)
        assertTrue("set" in help && "write a key" in help, help)
    }

    @Test
    fun helpText_rootActionToolStillListsCompletion() {
        // A single-command tool (root has an action) is a leaf, but its injected `completion` subcommand must stay discoverable.
        val cmd = cli("tally") {
            argument("files").multiple(min = 1)
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue(help.startsWith("usage: tally <files>"), help)
        assertTrue("completion" in help, help)
    }

    @Test
    fun helpShowsChoicesRequiredOptionalDefault() {
        val cmd = cli("cvt") {
            option("from").choice("celsius", "fahrenheit").required()
            option("to").choice("celsius", "fahrenheit")
            option("round").int().default(2)
            argument("value").optional()
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("--from <celsius|fahrenheit>" in help, help)
        assertTrue("(required)" in help, help)
        assertTrue("(default: 2)" in help, help)
        assertTrue("(optional)" in help, help)
    }

    @Test
    fun helpText_optionShowsValuePlaceholderFlagDoesNot() {
        val cmd = cli("run") {
            option("priority", "p", help = "prio")
            flag("verbose", "v", help = "loud")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-p, --priority <value>" in help, help)
        assertTrue("-v, --verbose" in help, help)
        assertTrue("-v, --verbose <value>" !in help, help)
        assertTrue("[options]" in help, help)
    }
}
