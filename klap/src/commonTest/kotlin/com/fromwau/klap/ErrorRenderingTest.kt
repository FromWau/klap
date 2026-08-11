package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.internal.render.jsonErrorEnvelope
import com.fromwau.klap.internal.render.message
import com.fromwau.klap.internal.render.renderError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** `@Serializable`, but throws mid-encode with a DEL (0x7F) embedded in the message. */
@Serializable(with = ExplodingWithControlCharSerializer::class)
private class ExplodingWithControlChar

private object ExplodingWithControlCharSerializer : KSerializer<ExplodingWithControlChar> {
    override val descriptor = PrimitiveSerialDescriptor("ExplodingWithControlChar", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ExplodingWithControlChar): Unit =
        throw IllegalStateException("boom${Char(127)}boom")

    override fun deserialize(decoder: Decoder): ExplodingWithControlChar =
        throw IllegalStateException("boom${Char(127)}boom")
}

class ErrorRenderingTest {

    @Test
    fun `unknown option message`() {
        assertEquals("unknown option '--nope'", CliError.UnknownOption("--nope").message())
    }

    @Test
    fun `unknown option message with suggestion`() {
        assertEquals(
            "unknown option '--verbos'. Did you mean --verbose?",
            CliError.UnknownOption("--verbos", "--verbose").message(),
        )
    }

    @Test
    fun `ambiguous option lists the possibilities like gnu`() {
        assertEquals(
            "option '--re' is ambiguous; possibilities: '--recursive' '--reference'",
            CliError.AmbiguousOption("--re", listOf("--recursive", "--reference")).message(),
        )
    }

    @Test
    fun `an ambiguous subcommand reads like an ambiguous option`() {
        assertEquals(
            "subcommand 'st' is ambiguous; possibilities: 'stash' 'status'",
            CliError.AmbiguousSubcommand("app", "st", listOf("stash", "status")).message(),
        )
    }

    @Test
    fun `unknown subcommand message with suggestion`() {
        assertEquals(
            "unknown subcommand 'confi' for 'app'. Did you mean config?",
            CliError.UnknownSubcommand("app", "confi", "config").message(),
        )
    }

    @Test
    fun `subcommand after separator message`() {
        assertEquals(
            "'build' is a command of 'app', but '--' ends command parsing; put '--' after the command",
            CliError.SubcommandAfterSeparator("build", "app").message(),
        )
    }

    @Test
    fun `too many arguments pluralizes`() {
        assertEquals(
            "unexpected extra arguments: a b",
            CliError.TooManyArguments("add", listOf("a", "b")).message(),
        )
    }

    @Test
    fun `too many arguments singular with command suggestion`() {
        assertEquals(
            "unexpected extra argument: biuld. Did you mean the 'build' command?",
            CliError.TooManyArguments("app", listOf("biuld"), "build").message(),
        )
    }

    @Test
    fun `invalid choice lists choices`() {
        assertEquals(
            "invalid value 'x' for level (choose from low, high)",
            CliError.InvalidChoice("level", "x", listOf("low", "high")).message(),
        )
    }

    @Test
    fun `an ambiguous value names the option it belongs to`() {
        assertEquals(
            "value 'h' for --priority is ambiguous; possibilities: 'high' 'highest'",
            CliError.AmbiguousValue("--priority", "h", listOf("high", "highest")).message(),
        )
    }

    @Test
    fun `flag takes no value names the short form as typed`() {
        assertEquals("flag '-v' does not take a value", CliError.FlagTakesNoValue("-v").message())
    }

    @Test
    fun `flag takes no value names the long form as typed`() {
        assertEquals("flag '--verbose' does not take a value", CliError.FlagTakesNoValue("--verbose").message())
    }

    @Test
    fun `flag takes no value negation hint is prefixed and correct`() {
        assertEquals(
            "flag '--color' does not take a value; use --no-color to turn it off",
            CliError.FlagTakesNoValue("--color", "no-color").message(),
        )
    }

    @Test
    fun `missing required option message`() {
        assertEquals("missing required option --host", CliError.MissingRequiredOption("--host").message())
    }

    @Test
    fun `missing option value message`() {
        assertEquals("option --host requires a value", CliError.MissingOptionValue("--host").message())
    }

    @Test
    fun `too few occurrences pluralizes min`() {
        assertEquals(
            "'header' must be given at least 2 times (got 1)",
            CliError.TooFewOccurrences("header", 2, 1).message(),
        )
    }

    @Test
    fun `too few occurrences singular for min one`() {
        assertEquals(
            "'tag' must be given at least 1 time (got 0)",
            CliError.TooFewOccurrences("tag", 1, 0).message(),
        )
    }

    @Test
    fun `exit code defaults to two`() {
        assertEquals(2, CliError.UnknownOption("-z").exitCode)
    }

    @Test
    fun `json envelope escapes quotes`() {
        assertEquals(
            """{"error":"say \"hi\"","code":2}""",
            jsonErrorEnvelope("say \"hi\"", 2),
        )
    }

    @Test
    fun `encode failed json envelope strips embedded control characters`() {
        // Every other message-bearing path (RenderFailed, both CliError paths) runs through
        // stripTerminalEscapes; EncodeFailed's --json envelope must match, or a throwing custom
        // serializer could leak a raw control character into structured output.
        val t = RecordingTerminal()
        val code = cli("encodeboom") { action { Ok(ExplodingWithControlChar()) } }.run(arrayOf("--json"), t)
        assertEquals(1, code)
        assertEquals(
            jsonErrorEnvelope("--json encoding failed: boom\\x7Fboom", 1) + "\n",
            t.err.toString(),
        )
    }

    @Test
    fun `missing argument message pins the full string including the for command clause`() {
        assertEquals(
            "missing required argument <text> for 'add'",
            CliError.MissingArgument("add", "text").message(),
        )
    }

    @Test
    fun `usage exits two so a hand written rule matches the built in ones`() {
        // The whole point of the variant: Failure would exit 1 here, disagreeing with every parse-level
        // error over the same class of mistake.
        val t = RecordingTerminal()
        assertEquals(2, renderError(CliError.Usage("--from and --to must differ"), json = false, terminal = t))
        assertEquals("error: --from and --to must differ\n", t.err.toString())
    }

    @Test
    fun `failure embedded escape sequence is stripped on the human path`() {
        // A detail is written by the consumer but almost always interpolates an argv token, so klap cannot
        // tell a color the author applied from one a caller injected, and neutralizes both. This overturns
        // the earlier trust-boundary exemption, which let any Failure detail reach the terminal verbatim.
        val esc = Char(27)
        val t = RecordingTerminal()
        val code = renderError(CliError.Failure("$esc[31mboom$esc[0m"), json = false, terminal = t)
        assertEquals(1, code)
        assertEquals("error: \\x1B[31mboom\\x1B[0m\n", t.err.toString())
    }

    @Test
    fun `usage embedded escape sequence is stripped on the human path`() {
        val esc = Char(27)
        val t = RecordingTerminal()
        val code = renderError(CliError.Usage("bad operand '$esc[31m'"), json = false, terminal = t)
        assertEquals(2, code)
        assertEquals("error: bad operand '\\x1B[31m'\n", t.err.toString())
    }

    @Test
    fun `authored detail keeps its own newline on both paths`() {
        // A `hint:` continuation line is a layout choice the consumer made, so it survives as a real
        // newline on the human path and as a proper JSON string escape under --json, rather than being
        // mangled into the literal four characters \x0A on one path and not the other.
        val detail = "no such environment 'prd'\nhint: did you mean 'prod'?"
        val human = RecordingTerminal()
        renderError(CliError.Failure(detail), json = false, terminal = human)
        assertEquals("error: $detail\n", human.err.toString())

        val structured = RecordingTerminal()
        renderError(CliError.Usage(detail), json = true, terminal = structured)
        assertEquals(
            """{"error":"no such environment 'prd'\nhint: did you mean 'prod'?","code":2}""" + "\n",
            structured.err.toString(),
        )
    }

    @Test
    fun `echoed token newline is escaped so a caller cannot forge a second error line`() {
        // The counterpart to the test above: an argv token is not authored prose, so its newline stays
        // escaped. Otherwise `app --n=$'x\nerror: disk wiped'` prints a second, fabricated error line.
        val t = RecordingTerminal()
        renderError(CliError.BadValue("n", "x\nerror: forged", "bad"), json = false, terminal = t)
        assertEquals("error: invalid value 'x\\x0Aerror: forged' for n: bad\n", t.err.toString())
    }

    @Test
    fun `bad value real escape sequence in the echoed value is still stripped on the human path`() {
        // BadValue echoes a user-supplied value straight from argv, so an embedded ESC is neutralized to
        // the literal string "\x1B" here, exactly as in an authored detail but without the newline carve-out.
        val esc = Char(27)
        val t = RecordingTerminal()
        val code = renderError(CliError.BadValue("n", "$esc[31m", "bad"), json = false, terminal = t)
        assertEquals(2, code)
        assertEquals("error: invalid value '\\x1B[31m' for n: bad\n", t.err.toString())
    }

    @Test
    fun `bad value converter exception with null message does not double the fallback reason`() {
        // .convert { }, not .map { }: a thrown .map { } exception is caught inside its own applyMap
        // wrapper before it ever reaches convertOne's catch, so it can never carry a null message through
        // to that fallback. A raw .convert { } lambda that throws instead of returning Result.Error is
        // the misuse convertOne's own catch guards against, and IllegalStateException() has a null
        // message on every platform, making the repro deterministic here.
        val tree = cli("app") {
            command("go") {
                val n = argument("n").convert<Int> { throw IllegalStateException() }
                action { Ok(n().toString()) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("go", "somevalue"))).error
        val bad = assertIs<CliError.BadValue>(err)
        assertEquals("conversion failed", bad.reason)
        assertIs<IllegalStateException>(assertIs<ConversionError.Threw>(bad.cause).thrown)
        assertEquals("invalid value 'somevalue' for n: conversion failed", err.message())
    }
}
