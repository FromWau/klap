package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.klap.internal.render.jsonErrorEnvelope
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

/** `@Serializable`, but throws mid-encode: reported distinctly, not mislabeled "not @Serializable". */
@Serializable(with = ExplodingSerializer::class)
private class Exploding

private object ExplodingSerializer : KSerializer<Exploding> {
    override val descriptor = PrimitiveSerialDescriptor("Exploding", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Exploding): Unit = throw SerializationException("boom")
    override fun deserialize(decoder: Decoder): Exploding = throw SerializationException("boom")
}

/** Throws a non-[SerializationException] mid-encode, to prove the catch handles more than that. */
@Serializable(with = ExplodingRuntimeSerializer::class)
private class ExplodingRuntime

private object ExplodingRuntimeSerializer : KSerializer<ExplodingRuntime> {
    override val descriptor = PrimitiveSerialDescriptor("ExplodingRuntime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ExplodingRuntime): Unit = throw IllegalStateException("boom")
    override fun deserialize(decoder: Decoder): ExplodingRuntime = throw IllegalStateException("boom")
}

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

class StructuredJsonTest {

    @Test
    fun `json emits structured object`() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("point", "--json"), t)
        assertEquals(0, code)
        assertEquals("{\"x\":1,\"y\":2}\n", t.out.toString())
    }

    @Test
    fun `plain uses human renderer`() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("point"), t)
        assertEquals(0, code)
        assertEquals("point(1, 2)\n", t.out.toString())
    }

    @Test
    fun `plain falls back to toString when human omitted`() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("bare"), t)
        assertEquals(0, code)
        assertEquals("Point(x=3, y=4)\n", t.out.toString())
    }

    @Test
    fun `an empty success value prints nothing plain but encodes as a json string`() {
        // Two different "nothings": run() suppresses the line for an empty rendering, but "" is a legal
        // value that --json must encode. So an action cannot lean on Ok("") to stay quiet on both paths.
        val quiet = cli("quiet") { action { Ok("") } }
        val plain = RecordingTerminal()
        assertEquals(0, quiet.run(emptyArray(), plain))
        assertEquals("", plain.out.toString())

        val machine = RecordingTerminal()
        assertEquals(0, quiet.run(arrayOf("--json"), machine))
        assertEquals("\"\"\n", machine.out.toString())
    }

    @Test
    fun `json string value serializes to bare json string`() {
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("greet", "--json"), t)
        assertEquals(0, code)
        assertEquals("\"hi\"\n", t.out.toString())
    }

    @Test
    fun `json string value escapes quotes backslashes and newlines`() {
        // kotlinx.serialization encodes the String as a JSON string literal: `"` -> \", `\` -> \\, newline -> \n.
        val t = RecordingTerminal()
        val code = cli("esc") { action { Ok("a\"b\\c\nd") } }.run(arrayOf("--json"), t)
        assertEquals(0, code)
        assertEquals("\"a\\\"b\\\\c\\nd\"\n", t.out.toString())
    }

    @Test
    fun `json includes fields left at their default value`() {
        // kotlinx.serialization's default Json instance omits a field left at its default
        // (encodeDefaults = false); for a CLI's structured output that is a silently dropped field.
        val t = RecordingTerminal()
        val code = geo().run(arrayOf("task", "--json"), t)
        assertEquals(0, code)
        assertEquals("{\"id\":3,\"title\":\"x\",\"priority\":\"normal\",\"done\":false}\n", t.out.toString())
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
        assertEquals(
            jsonErrorEnvelope(
                "--json is not available: the command's return type is not @Serializable",
                1
            ) + "\n",
            t.err.toString(),
        )
    }

    @Test
    fun `json encode failure reports its own error not mislabeled as not serializable`() {
        // The type IS @Serializable (its serializer resolves), but throws mid-encode. That is a
        // different failure from "no serializer for the type", so it must not be reported as
        // "not @Serializable" and must surface its own diagnostic, still as a JSON envelope.
        val t = RecordingTerminal()
        val code = cli("boom") { action { Ok(Exploding()) } }.run(arrayOf("--json"), t)
        assertEquals(1, code)
        val err = t.err.toString()
        assertTrue("--json encoding failed" in err, err)
        assertTrue("boom" in err, err)
        assertTrue("not @Serializable" !in err, err)
        assertTrue(err.trim().startsWith("{\"error\":"), err)
        assertTrue("\"code\":1" in err, err)
    }

    @Test
    fun `json encode failure from non serialization exception does not escape as a stack trace`() {
        // A custom KSerializer can throw anything mid-encode, not just SerializationException. The
        // encode catch must be broad enough to still render a clean envelope instead of an uncaught
        // exception reaching the caller.
        val t = RecordingTerminal()
        val code = cli("boom2") { action { Ok(ExplodingRuntime()) } }.run(arrayOf("--json"), t)
        assertEquals(1, code)
        val err = t.err.toString()
        assertTrue("--json encoding failed" in err, err)
        assertTrue("boom" in err, err)
        assertEquals(
            jsonErrorEnvelope("--json encoding failed: boom", 1) + "\n",
            err,
        )
    }
}
