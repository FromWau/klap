package com.fromwau.klap

import com.fromwau.klap.internal.render.Candidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A result value whose toString() throws, to exercise the human-render fallback's error guard. */
private object ThrowingToString {
    override fun toString(): String = throw RuntimeException("kaboom")
}

private fun app(): Cli = cli("todo") {
    version = "1.0.0"
    command("ping") { action { Ok("pong") } }
    // A fail-only action can't infer T from Err (that would be Nothing); name it explicitly.
    command("fail") { action<String> { Err(CliError.Failure("fail", exitCode = 3)) } }
    command("add") {
        val text = argument("text")
        action { Ok("added ${text()}") }
    }
}

class RunnerTest {

    @Test
    fun successRunsBlockAndReturnsZero() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("ping"), t)
        assertEquals(0, code)
        assertEquals("pong\n", t.out.toString())
    }

    @Test
    fun exitPropagatesCode() {
        val code = app().run(arrayOf("fail"), RecordingTerminal())
        assertEquals(3, code)
    }

    @Test
    fun failureDetailIsRenderedToStderrOnHumanPath() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("fail"), t)
        assertEquals(3, code)
        assertEquals("error: fail\n", t.err.toString())
    }

    @Test
    fun failureDetailAndCodeAreRenderedToStderrOnJsonPath() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("fail", "--json"), t)
        assertEquals(3, code)
        val err = t.err.toString()
        assertTrue("\"error\":\"fail\"" in err, err)
        assertTrue("\"code\":3" in err, err)
    }

    @Test
    fun brokenPipeMapsSuccessTo141ButKeepsAFailureCode() {
        // A terminal reporting a write error (a downstream `| head` closed the pipe) turns a success into
        // 141 (128 + SIGPIPE) so scripts detect the truncation; a real failure code is kept as-is.
        val erroring = object : Terminal {
            override fun out(text: String) {}
            override fun err(text: String) {}
            override fun writeErrored(): Boolean = true
        }
        assertEquals(141, app().run(arrayOf("ping"), erroring))
        assertEquals(3, app().run(arrayOf("fail"), erroring))
    }

    @Test
    fun runActionReturnsTheActionsTypedResultWithoutRendering() {
        // The embedding escape hatch: parse, then run the resolved action yourself and get its own
        // Result<Any?, CliError> (Ok value or typed Failure) — no output, no exit code.
        val ping = assertIs<Invocation.Execute>(
            assertIs<Result.Success<Invocation>>(app().parse(listOf("ping"))).value,
        )
        assertEquals(Result.Success("pong"), ping.runAction())

        val fail = assertIs<Invocation.Execute>(
            assertIs<Result.Success<Invocation>>(app().parse(listOf("fail"))).value,
        )
        assertEquals(Result.Error(CliError.Failure("fail", exitCode = 3)), fail.runAction())
    }

    @Test
    fun throwingToStringOnResultRendersCleanErrorNotStackTrace() {
        // A value whose toString() throws must surface a clean error line + a failure exit, never a raw
        // stack trace escaping to the terminal.
        val tree = cli("app") { command("go") { action { Ok(ThrowingToString) } } }
        val t = RecordingTerminal()
        val code = tree.run(arrayOf("go"), t)
        assertEquals(1, code)
        assertTrue("could not render output" in t.err.toString(), t.err.toString())
        assertEquals("", t.out.toString())
    }

    @Test
    fun throwingActionBodyPropagatesAsAProgrammerError() {
        // By design, a thrown exception inside action { } is a programmer error and propagates uncaught: it
        // is NOT turned into a clean error: line (expected failures must be modeled as Err instead). Only
        // parse-time converters wrap a thrown exception; the action body deliberately does not.
        val tree = cli("app") { command("go") { action<String> { throw RuntimeException("boom") } } }
        assertFailsWith<RuntimeException> { tree.run(arrayOf("go"), RecordingTerminal()) }
    }

    @Test
    fun usageErrorPrintsToErrAndReturnsTwo() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("add"), t)
        assertEquals(2, code)
        assertTrue("missing required argument <text>" in t.err.toString(), t.err.toString())
    }

    @Test
    fun jsonErrorEnvelopeOnStderr() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("add", "--json"), t)
        assertEquals(2, code)
        assertTrue(t.err.toString().trim().startsWith("{\"error\":"), t.err.toString())
    }

    @Test
    fun failureExitCodeZeroCoercesToOne() {
        val code = cli("x") { action<String> { Err(CliError.Failure("boom", exitCode = 0)) } }.run(arrayOf(), RecordingTerminal())
        assertEquals(1, code)
    }

    @Test
    fun failureExitCodeAboveRangeCoercesTo255() {
        val code = cli("x") { action<String> { Err(CliError.Failure("boom", exitCode = 300)) } }.run(arrayOf(), RecordingTerminal())
        assertEquals(255, code)
    }

    @Test
    fun failureExitCodeBelowRangeCoercesToOne() {
        val code = cli("x") { action<String> { Err(CliError.Failure("boom", exitCode = -1)) } }.run(arrayOf(), RecordingTerminal())
        assertEquals(1, code)
    }

    @Test
    fun failureDefaultExitCodeIsUnchanged() {
        val code = cli("x") { action<String> { Err(CliError.Failure("boom")) } }.run(arrayOf(), RecordingTerminal())
        assertEquals(1, code)
    }

    @Test
    fun parserErrorExitCodeIsUnchanged() {
        val code = app().run(arrayOf("--nope"), RecordingTerminal())
        assertEquals(2, code)
    }

    @Test
    fun jsonEnvelopeCodeFieldMatchesTheCoercedExitCode() {
        val t = RecordingTerminal()
        val code = cli("x") { action<String> { Err(CliError.Failure("boom", exitCode = 0)) } }.run(arrayOf("--json"), t)
        assertEquals(1, code)
        assertTrue("\"code\":1" in t.err.toString(), t.err.toString())
    }

    @Test
    fun versionPrintsAndReturnsZero() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("--version"), t)
        assertEquals(0, code)
        assertTrue("1.0.0" in t.out.toString(), t.out.toString())
    }

    @Test
    fun helpPrintsAndReturnsZero() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("add", "--help"), t)
        assertEquals(0, code)
        assertTrue("usage: todo add <text>" in t.out.toString(), t.out.toString())
    }

    @Test
    fun controlCharsInBadValueAreSanitizedInHumanError() {
        val t = RecordingTerminal()
        val tool = cli("tool") {
            argument("n").int()
            action { Ok("") }
        }
        val code = tool.run(arrayOf("\u001B[31mred"), t)
        assertEquals(2, code)
        val err = t.err.toString()
        assertTrue('\u001B' !in err, err)
        assertTrue("invalid value" in err && "for n" in err, err)
    }

    @Test
    fun controlCharsInBadValueStillJsonEscapedUnderJson() {
        val t = RecordingTerminal()
        val tool = cli("tool") {
            argument("n").int()
            action { Ok("") }
        }
        val code = tool.run(arrayOf("\u001B[31mred", "--json"), t)
        assertEquals(2, code)
        val err = t.err.toString()
        // stripTerminalEscapes now turns the raw ESC (0x1B) into the literal text \x1B, which kotlinx then
        // JSON-escapes to \\x1B; no raw control byte survives on the --json path.
        assertTrue("\\\\x1b" in err.lowercase(), err)
    }

    @Test
    fun actionOutputColorsThroughTheScopePaletteAndIsPlainUnderJson() {
        val tree = cli("app") { command("go") { action { Ok(yellow { "ok" }) } } }
        val esc = Char(27).toString()
        // color-enabled terminal:
        val on = RecordingTerminal(/* ansi = true */); tree.run(arrayOf("--color=always", "go"), on)
        assertTrue("$esc[33mok$esc[0m" in on.out.toString(), on.out.toString())
        // forced off:
        val off = RecordingTerminal(); tree.run(arrayOf("--color=never", "go"), off)
        assertEquals("ok\n", off.out.toString())
        // json disables the action palette:
        val js = RecordingTerminal(); tree.run(arrayOf("--color=always", "--json", "go"), js)
        assertTrue("\"ok\"" in js.out.toString() && esc !in js.out.toString(), js.out.toString())
    }

    @Test
    fun nestedHelpShowsFullPath() {
        val vcs = cli("vcs") {
            command("remote") {
                command("add") {
                    argument("name")
                    argument("url")
                    action { Ok("") }
                }
            }
        }
        val t = RecordingTerminal()
        vcs.run(arrayOf("remote", "add", "-h"), t)
        assertTrue("usage: vcs remote add <name> <url>" in t.out.toString(), t.out.toString())
    }

    @Test
    fun candidateValueWithTabIsDroppedNotEmittedRaw() {
        // A shell splits the wire line on its first tab, so a value carrying one would be offered as a
        // truncated candidate matching nothing real. Dropping it is the only lossless answer.
        assertEquals(null, Candidate("evil\ttab").toCompletionLine())
        val joined = listOf(Candidate("evil\ttab"), Candidate("real")).mapNotNull { it.toCompletionLine() }
            .joinToString("\n")
        assertEquals("real", joined)
    }

    @Test
    fun candidateValueWithNewlineDoesNotProduceTwoRecords() {
        // Records are newline-separated, so a value carrying one would arrive as two bogus candidates.
        assertEquals(null, Candidate("a\nb").toCompletionLine())
        val joined = listOf(Candidate("first"), Candidate("a\nb"), Candidate("last")).mapNotNull {
            it.toCompletionLine()
        }.joinToString("\n")
        assertEquals(2, joined.lines().size)
        assertEquals("first\nlast", joined)
    }

    @Test
    fun candidateValueWithCarriageReturnIsDropped() {
        // Same unsanitized-value defect as the tab and newline cases, for \r.
        assertEquals(null, Candidate("a\rb").toCompletionLine())
        val joined = listOf(Candidate("a\rb"), Candidate("ok")).mapNotNull { it.toCompletionLine() }
            .joinToString("\n")
        assertEquals("ok", joined)
    }

    @Test
    fun ordinaryCandidateLineIsUnchanged() {
        assertEquals("tag1", Candidate("tag1").toCompletionLine())
        assertEquals("tag1\tBuy milk", Candidate("tag1", "Buy milk").toCompletionLine())
    }

    @Test
    fun descriptionWithTabOrNewlineStillCollapsesToSpaceRatherThanDropping() {
        // The value fix leaves the description path untouched: a description is display-only, never fed
        // back to the shell, so it keeps collapsing separators to a space instead of dropping the candidate.
        assertEquals("tag1\ta b", Candidate("tag1", "a\tb").toCompletionLine())
        assertEquals("tag1\ta b", Candidate("tag1", "a\nb").toCompletionLine())
    }
}
