package com.fromwau.klap

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class Point(val x: Int, val y: Int)

private fun geo(): Cli = cli("geo") {
    command("point") {
        action(human = { "point(${it.x}, ${it.y})" }) { Ok(Point(1, 2)) }
    }
    command("bare") {
        action { Ok(Point(3, 4)) }
    }
    command("greet") {
        action { Ok("hi") }
    }
}

class StructuredJsonTest {

    @Test
    fun json_emitsStructuredObject() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("point", "--json"), t)
        assertEquals(0, code)
        assertEquals("{\"x\":1,\"y\":2}\n", t.out.toString())
    }

    @Test
    fun plain_usesHumanRenderer() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("point"), t)
        assertEquals(0, code)
        assertEquals("point(1, 2)\n", t.out.toString())
    }

    @Test
    fun plain_fallsBackToToStringWhenHumanOmitted() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("bare"), t)
        assertEquals(0, code)
        assertEquals("Point(x=3, y=4)\n", t.out.toString())
    }

    @Test
    fun json_stringValueSerializesToBareJsonString() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("greet", "--json"), t)
        assertEquals(0, code)
        assertEquals("\"hi\"\n", t.out.toString())
    }
}
