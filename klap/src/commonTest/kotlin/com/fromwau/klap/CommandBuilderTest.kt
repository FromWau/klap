package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandBuilderTest {

    @Test
    fun builder_registersSpecsInOrder() {
        val cmd = cli("add") {
            description = "Add a task"
            argument("text")
            option("priority", "p")
            flag("done", "d")
        }
        assertEquals("add", cmd.name)
        assertEquals("Add a task", cmd.description)
        assertEquals(listOf("text"), cmd.arguments.map { it.name })
        assertEquals(listOf("priority"), cmd.options.map { it.name })
        assertEquals(listOf("done"), cmd.flags.map { it.name })
    }

    @Test
    fun group_hasNoActionBlockAndResolvesSubcommands() {
        val cmd = cli("config") {
            command("get") { action { Ok("") } }
            command("set") { action { Ok("") } }
        }
        assertTrue(cmd.isGroup)
        assertEquals("get", cmd.subcommand("get")?.name)
        assertNull(cmd.subcommand("missing"))
    }

    @Test
    fun aliasResolves() {
        val cmd = cli("root") {
            command("scan") {
                aliases = listOf("index")
                action { Ok("") }
            }
        }
        assertEquals("scan", cmd.subcommand("index")?.name)
    }

    @Test
    fun variadicMustBeLastPositional() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                argument("files").multiple()
                argument("tail")
                action { Ok("") }
            }
        }
    }

    @Test
    fun requiredCannotFollowOptional() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                argument("a").optional()
                argument("b")
                action { Ok("") }
            }
        }
    }
}
