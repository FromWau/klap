package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorRenderingTest {

    @Test
    fun unknownOption_message() {
        assertEquals("unknown option '--nope'", CliError.UnknownOption("--nope").message())
    }

    @Test
    fun tooManyArguments_pluralizes() {
        assertEquals(
            "unexpected extra arguments: a b",
            CliError.TooManyArguments("add", listOf("a", "b")).message(),
        )
    }

    @Test
    fun invalidChoice_listsChoices() {
        assertEquals(
            "invalid value 'x' for level (choose from low, high)",
            CliError.InvalidChoice("level", "x", listOf("low", "high")).message(),
        )
    }

    @Test
    fun exitCode_defaultsToTwo() {
        assertEquals(2, CliError.UnknownOption("-z").exitCode)
    }

    @Test
    fun jsonEnvelope_escapesQuotes() {
        assertEquals(
            """{"error":"say \"hi\"","code":2}""",
            jsonErrorEnvelope("say \"hi\"", 2),
        )
    }
}
