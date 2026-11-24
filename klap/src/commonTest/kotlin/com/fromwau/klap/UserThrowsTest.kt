package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** The throw a test's block raises, read by [ThrowingSerializer] and [ThrowingText]. */
private var planted: Throwable = IllegalStateException("unset")

@Serializable(with = ThrowingSerializer::class)
private class Encoded

private object ThrowingSerializer : KSerializer<Encoded> {
    override val descriptor = PrimitiveSerialDescriptor("Encoded", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Encoded): Unit = throw planted
    override fun deserialize(decoder: Decoder): Encoded = throw planted
}

private object ThrowingText {
    override fun toString(): String = throw planted
}

/** One way into klap, given the terminal it may write to. */
private class Entry(
    val name: String,
    val enter: suspend (Cli, RecordingTerminal) -> Unit,
)

private fun parse(vararg argv: String) = Entry("parse ${argv.toList()}") { cli, _ -> cli.parse(argv.toList()) }

private fun run(vararg argv: String) = Entry("run ${argv.toList()}") { cli, terminal ->
    cli.run(argv.toList(), terminal)
}

private fun runSuspending(vararg argv: String) = Entry("runSuspending ${argv.toList()}") { cli, terminal ->
    cli.runSuspending(argv.toList(), terminal)
}

private fun runAction(vararg argv: String) = Entry("runAction ${argv.toList()}") { cli, terminal ->
    assertIs<Invocation.Execute>(cli.parse(argv.toList()).assertSuccess()).runAction(terminal)
}

private fun runActionSuspending(vararg argv: String) = Entry("runActionSuspending ${argv.toList()}") { cli, terminal ->
    assertIs<Invocation.Execute>(cli.parse(argv.toList()).assertSuccess()).runActionSuspending(terminal)
}

private fun complete(vararg words: String) = Entry("completeCandidates ${words.toList()}") { cli, _ ->
    cli.offered(*words)
}

/** The line a shell's Tab runs: `__complete` over [words], the cursor on the last. */
private fun tab(vararg words: String) = run("__complete", "--cursor", "${words.lastIndex}", "--", *words)

private fun thrownKinds(): List<Throwable> = listOf(IllegalStateException("boom"), NotImplementedError("unwritten"))

/**
 * Code you hand klap that throws is your bug, so the throw reaches you unchanged: an `Exception` and an `Error`
 * alike, as the same instance with its own stack trace, and klap writes nothing on its way out.
 */
class UserThrowsTest {

    /**
     * Enters [build]'s CLI through each of [entries], once with an `Exception` and once with an `Error`, asserting
     * each comes out as thrown with nothing written to the terminal.
     */
    private fun propagatesSilently(
        vararg entries: Entry,
        build: (Throwable) -> Cli,
    ) = runTest {
        for (entry in entries) {
            for (thrown in thrownKinds()) {
                planted = thrown
                val terminal = RecordingTerminal()
                val caught = try {
                    entry.enter(build(thrown), terminal)
                    null
                } catch (e: Throwable) {
                    e
                }
                val what = "${thrown::class.simpleName} through ${entry.name}"
                assertSame(thrown, caught ?: fail("$what was not thrown"), what)
                assertEquals(emptyList(), terminal.writes, "$what wrote to the terminal")
            }
        }
    }

    @Test
    fun `an action block`() = propagatesSilently(run(), run("--json"), runAction()) { thrown ->
        cli("app") { action<String> { throw thrown } }
    }

    @Test
    fun `a suspending action block`() = propagatesSilently(
        runSuspending(),
        runSuspending("--json"),
        runActionSuspending(),
    ) { thrown ->
        cli("app") { actionSuspending<String> { throw thrown } }
    }

    @Test
    fun `a map converter`() = propagatesSilently(
        parse("--n", "1"),
        run("--n", "1"),
        run("--n", "1", "--json"),
    ) { thrown ->
        cli("app") {
            option(long = "n").map<Int> { throw thrown }
            action { Ok("") }
        }
    }

    @Test
    fun `a convert converter`() = propagatesSilently(
        parse("--n", "1"),
        run("--n", "1"),
        run("--n", "1", "--json"),
    ) { thrown ->
        cli("app") {
            option(long = "n").convert<Int> { throw thrown }
            action { Ok("") }
        }
    }

    @Test
    fun `a validate check after the converter`() = propagatesSilently(
        parse("--n", "1"),
        run("--n", "1"),
        run("--n", "1", "--json"),
    ) { thrown ->
        cli("app") {
            option(long = "n").int().validate("never") { throw thrown }
            action { Ok("") }
        }
    }

    @Test
    fun `a validate check before a later converter`() = propagatesSilently(
        parse("--n", "1"),
        run("--n", "1"),
        run("--n", "1", "--json"),
    ) { thrown ->
        cli("app") {
            option(long = "n").validate("never") { _: String -> throw thrown }.int()
            action { Ok("") }
        }
    }

    @Test
    fun `a validateInputs block`() = propagatesSilently(parse(), run(), run("--json")) { thrown ->
        cli("app") {
            validateInputs { throw thrown }
            action { Ok("") }
        }
    }

    @Test
    fun `a human renderer`() = propagatesSilently(run()) { thrown ->
        cli("app") { action(human = { throw thrown }) { Ok("x") } }
    }

    @Test
    fun `the toString of a value printed without a human renderer`() = propagatesSilently(run()) {
        cli("app") { action { Ok(ThrowingText) } }
    }

    @Test
    fun `an exit renderer`() = propagatesSilently(run(), run("--json")) { thrown ->
        cli("app") { action(exit = { throw thrown }) { Ok("x") } }
    }

    @Test
    fun `a serializer under json`() = propagatesSilently(run("--json")) {
        cli("app") { action { Ok(Encoded()) } }
    }

    @Test
    fun `a completion provider`() = propagatesSilently(complete(""), tab("")) { thrown ->
        cli("app") {
            argument("a").completeWith { throw thrown }
            action { Ok("") }
        }
    }

    @Test
    fun `the toString of a default shown in help`() = propagatesSilently(run("--help"), run("--help", "--json")) {
        cli("app") {
            argument("x").map { ThrowingText }.default(ThrowingText)
            action { Ok("") }
        }
    }

    @Test
    fun `a dash led predicate`() = propagatesSilently(
        parse("-w"),
        run("-w"),
        run("-w", "--json"),
        tab("-w"),
    ) { thrown ->
        cli("app") {
            argument("mode").dashLed { throw thrown }
            action { Ok("") }
        }
    }

    @Test
    fun `a dash led gather predicate`() = propagatesSilently(
        parse("-w"),
        run("-w"),
        run("-w", "--json"),
        tab("-w"),
    ) { thrown ->
        cli("app") {
            argument("mode").dashLed(gather = ",") { throw thrown }
            action { Ok("") }
        }
    }

    @Test
    fun `a dash led predicate asked about a word left over after the last operand`() = propagatesSilently(
        parse("x", "y", "-w"),
        run("x", "y", "-w"),
        run("x", "y", "-w", "--json"),
    ) { thrown ->
        cli("app") {
            argument("first").dashLed { it == "-w" }
            argument("second").dashLed { throw thrown }
            action { Ok("") }
        }
    }

    /** Tab asks the gather predicate about the word under the cursor first, so only that ask throws here. */
    @Test
    fun `a dash led gather predicate whose first ask throws on Tab`() = propagatesSilently(
        complete("x", "-w"),
        tab("x", "-w"),
    ) { thrown ->
        var asked = 0
        cli("app") {
            argument("mode").dashLed(gather = ",") { if (asked++ == 0) throw thrown else false }
            action { Ok("") }
        }
    }

    @Test
    fun `a cliOf projection`() = runTest {
        for (thrown in thrownKinds()) {
            val caught = runCatching {
                cliOf<String>("app") {
                    action { Ok("") }
                    projection { throw thrown }
                }.parse(emptyList())
            }.exceptionOrNull()
            assertSame(thrown, caught)
        }
    }
}

/** What klap detects itself is still a typed error, rendered with its exit code. */
class KlapErrorsRenderTest {

    private data object NotWhole : ResultError

    private fun app() = cli("app") {
        val n = argument("n").convert { raw ->
            raw.toIntOrNull()?.let { Ok(it) } ?: Err(ConversionError.Domain(NotWhole, "not a whole number"))
        }
        action { Ok("n=${n()}") }
    }

    private fun printed(vararg argv: String): Pair<Int, String> {
        val terminal = RecordingTerminal()
        return app().run(argv.toList(), terminal) to terminal.err
    }

    @Test
    fun `an unknown option exits two with its message`() {
        val (code, err) = printed("1", "--nope")
        assertEquals(2, code)
        assertTrue("error: unknown option '--nope'" in err, err)
    }

    @Test
    fun `a missing argument exits two with its message`() {
        val (code, err) = printed()
        assertEquals(2, code)
        assertTrue("error: missing required argument <n>" in err, err)
    }

    @Test
    fun `a value the converter refuses by returning an error exits two with its detail`() {
        val (code, err) = printed("x")
        assertEquals(2, code)
        assertTrue("error: invalid value 'x' for n: not a whole number" in err, err)
    }
}
