package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
private data class Point(val x: Int, val y: Int)

@Serializable
private data class Task(val id: Int, val title: String, val priority: String = "normal", val done: Boolean = false)

/** Deliberately not `@Serializable`, to exercise the lazy-serializer path in [nonSerializableTool]. */
private class Foo

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
    command("task") {
        action { Ok(Task(3, "x")) }
    }
}

/** A single-command tool whose action returns a non-`@Serializable` type; construction must still succeed. */
private fun nonSerializableTool(): Cli = cli("nstool") {
    action { Ok(Foo()) }
}

@Serializable
private data class Ratio(val value: Double)

@Serializable
private sealed class Shape

@Serializable
private data class Circle(val r: Int) : Shape()

@Serializable
private data class Holder(val shape: Shape, val any: Shape?)

/** A subclass the sealed hierarchy of [Shape] does not list, so its polymorphic encode has no serializer. */
private class Square : Shape()

@Serializable(with = RefusingSerializer::class)
private class Refused

private object RefusingSerializer : KSerializer<Refused> {
    override val descriptor = PrimitiveSerialDescriptor("Refused", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Refused): Unit = throw SerializationException("no")
    override fun deserialize(decoder: Decoder): Refused = throw SerializationException("no")
}

class StructuredJsonTest {

    private fun jsonRun(cli: Cli): Pair<Int, String> {
        val t = RecordingTerminal()
        return cli.run(arrayOf("--json"), t) to t.err
    }

    @Test
    fun `a NaN json cannot encode renders NotSerializable and exits one`() {
        val (code, err) = jsonRun(cli("app") { action { Ok(Ratio(Double.NaN)) } })
        assertEquals(1, code)
        assertEquals("""{"error":{"type":"NotSerializable"},"code":1}""" + "\n", err)
    }

    @Test
    fun `a serializer refusing with SerializationException renders NotSerializable and exits one`() {
        val (code, err) = jsonRun(cli("app") { action { Ok(Refused()) } })
        assertEquals(1, code)
        assertEquals("""{"error":{"type":"NotSerializable"},"code":1}""" + "\n", err)
    }

    @Test
    fun `a subtype without a serializer renders NotSerializable and exits one`() {
        val (code, err) = jsonRun(cli("app") { action { Ok(Holder(Circle(1), Square())) } })
        assertEquals(1, code)
        assertEquals("""{"error":{"type":"NotSerializable"},"code":1}""" + "\n", err)
    }

    @Test
    fun `json emits structured object`() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("point", "--json"), t)
        assertEquals(0, code)
        assertEquals("{\"x\":1,\"y\":2}\n", t.out)
    }

    @Test
    fun `plain uses human renderer`() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("point"), t)
        assertEquals(0, code)
        assertEquals("point(1, 2)\n", t.out)
    }

    @Test
    fun `plain falls back to toString when human omitted`() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("bare"), t)
        assertEquals(0, code)
        assertEquals("Point(x=3, y=4)\n", t.out)
    }

    @Test
    fun `an action that returns Unit prints nothing`() {
        val t = RecordingTerminal()
        val code = cli("quiet") { action { Ok(Unit) } }.run(emptyArray(), t)
        assertEquals(0, code)
        assertEquals("", t.out)
    }

    @Test
    fun `an action that returns Unit prints nothing under json either`() {
        val t = RecordingTerminal()
        val code = cli("quiet") { action { Ok(Unit) } }.run(arrayOf("--json"), t)
        assertEquals(0, code)
        assertEquals("", t.out)
    }

    @Test
    fun `an empty success value prints nothing plain but encodes as a json string`() {
        // Two different "nothings": run() suppresses the line for an empty rendering, but "" is a legal
        // value that --json must encode. So an action cannot lean on Ok("") to stay quiet on both paths.
        val quiet = cli("quiet") { action { Ok("") } }
        val plain = RecordingTerminal()
        assertEquals(0, quiet.run(emptyArray(), plain))
        assertEquals("", plain.out)

        val machine = RecordingTerminal()
        assertEquals(0, quiet.run(arrayOf("--json"), machine))
        assertEquals("\"\"\n", machine.out)
    }

    @Test
    fun `json string value serializes to bare json string`() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("greet", "--json"), t)
        assertEquals(0, code)
        assertEquals("\"hi\"\n", t.out)
    }

    @Test
    fun `json string value escapes quotes backslashes and newlines`() {
        // kotlinx.serialization encodes the String as a JSON string literal: `"` -> \", `\` -> \\, newline -> \n.
        val t = RecordingTerminal()
        val code = cli("esc") { action { Ok("a\"b\\c\nd") } }.run(arrayOf("--json"), t)
        assertEquals(0, code)
        assertEquals("\"a\\\"b\\\\c\\nd\"\n", t.out)
    }

    @Test
    fun `json includes fields left at their default value`() {
        // kotlinx.serialization's default Json instance omits a field left at its default
        // (encodeDefaults = false); for a CLI's structured output that is a silently dropped field.
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("task", "--json"), t)
        assertEquals(0, code)
        assertEquals("{\"id\":3,\"title\":\"x\",\"priority\":\"normal\",\"done\":false}\n", t.out)
    }

    @Test
    fun `non serializable action return type does not crash help`() {
        // serializer<T>() must resolve lazily, not at cli { } construction: reaching this line without
        // nonSerializableTool() throwing is itself part of what this test proves.
        val t = RecordingTerminal()
        val code = nonSerializableTool().run(arrayOf("--help"), t)
        assertEquals(0, code)
    }

    @Test
    fun `non serializable action runs normally without json`() {
        val t = RecordingTerminal()
        val code = nonSerializableTool().run(arrayOf(), t)
        assertEquals(0, code)
    }

    @Test
    fun `non serializable action json renders a clean error instead of crashing`() {
        // Since --json was requested, the error must be the structured JSON envelope, not plain text.
        val t = RecordingTerminal()
        val code = nonSerializableTool().run(arrayOf("--json"), t)
        assertEquals(1, code)
        assertEquals("""{"error":{"type":"NotSerializable"},"code":1}""" + "\n", t.err)
    }

}
