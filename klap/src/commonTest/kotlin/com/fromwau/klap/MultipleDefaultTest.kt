package com.fromwau.klap

import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `check [file...]`: standard input when no file is given, as argparse's `nargs='*', default=[...]`. */
private fun checkLike(): Cli = cli("check") {
    val files = argument("file", "files to check").file().multiple().default(listOf("-"))
    action<String>(human = { it }) { Ok("files=${files()}") }
}

/** `grep -e PATTERN...` with patterns of its own when none is given. */
private fun grepLike(): Cli = cli("grep") {
    val patterns = option(short = 'e', long = "regexp", help = "a pattern").multiple().default(listOf("a", "b"))
    action<String>(human = { it }) { Ok("patterns=${patterns()}") }
}

class MultipleDefaultTest {

    @Test
    fun `a variadic operand binds its default when no operand is given`() {
        assertEquals("files=[-]", checkLike().bindText())
    }

    @Test
    fun `a variadic operand binds the operands given over its default`() {
        assertEquals("files=[a, b]", checkLike().bindText("a", "b"))
    }

    @Test
    fun `a repeatable option binds its default when it is absent`() {
        assertEquals("patterns=[a, b]", grepLike().bindText())
    }

    @Test
    fun `a repeatable option binds its occurrences over its default`() {
        assertEquals("patterns=[x]", grepLike().bindText("-e", "x"))
    }

    @Test
    fun `a default on a variadic operand with a minimum is refused naming the operand`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            cli("check") {
                argument("file").multiple(min = 1).default(listOf("-"))
                action { Ok("") }
            }
        }
        assertEquals(
            "argument 'file': .default() needs .multiple(min = 0), since a default never applies when a value " +
                "is required",
            refusal.message,
        )
    }

    @Test
    fun `a default on a repeatable option with a minimum is refused naming the option`() {
        val refusal = assertFailsWith<IllegalArgumentException> {
            cli("grep") {
                option(long = "regexp").multiple(min = 2).default(listOf("a", "b"))
                action { Ok("") }
            }
        }
        assertEquals(
            "option '--regexp': .default() needs .multiple(min = 0), since a default never applies when a value " +
                "is required",
            refusal.message,
        )
    }

    @Test
    fun `help shows a variadic operand's default`() {
        val help = checkLike().helpText()
        assertTrue("files to check (repeatable; default: -)" in help, help)
    }

    @Test
    fun `help shows a repeatable option's default with its values space separated`() {
        val help = grepLike().helpText()
        assertTrue("a pattern (repeatable; default: a b)" in help, help)
    }

    @Test
    fun `help under json gives a variadic operand's default`() {
        val help = Json.parseToJsonElement(checkLike().bindText("--help", "--json")).jsonObject
        val file = help.getValue("arguments").jsonArray.single().jsonObject
        assertEquals("-", file.getValue("default").jsonPrimitive.content)
        assertEquals(false, file.getValue("required").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `help under json gives a repeatable option's default`() {
        val help = Json.parseToJsonElement(grepLike().bindText("--help", "--json")).jsonObject
        val regexp = help.getValue("options").jsonArray.single {
            "--regexp" in it.jsonObject.getValue("names").toString()
        }
        assertEquals("a b", regexp.jsonObject.getValue("default").jsonPrimitive.content)
    }

    @Test
    fun `help quotes a default value that needs it as GNU would type it`() {
        val help = cli("grep") {
            option(short = 'e', long = "regexp", help = "a pattern").multiple().default(listOf("a b", "c"))
            action { Ok("") }
        }.helpText()
        assertTrue("a pattern (repeatable; default: 'a b' c)" in help, help)
    }

    @Test
    fun `a default before multiple is refused naming the order`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            cli("cat") {
                argument("file").default("-").multiple()
                action { Ok("") }
            }
        }
        assertEquals("argument 'file': put .default(list) after .multiple(), not before it", refused.message)
    }
}
