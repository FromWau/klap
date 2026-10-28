package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.terminal.escapeControls
import com.fromwau.kern.terminal.shellQuoted
import com.fromwau.kern.terminal.test.RecordingTerminal
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
    fun `an unknown short split out of a cluster names the word it came from`() {
        // Without the cluster the message names a token absent from the command line: someone typing
        // `-1m` is told about `-1`, and looks for it in vain.
        assertEquals(
            "unknown option '-1' (in '-1m')",
            CliError.UnknownOption("-1", cluster = "-1m").message(),
        )
        assertEquals("unknown option '-z'", CliError.UnknownOption("-z").message())
    }

    @Test
    fun `json envelope escapes quotes`() {
        val t = RecordingTerminal()
        renderError(CliError.Usage("say \"hi\""), json = true, terminal = t)
        assertEquals("""{"error":{"type":"Usage","detail":"say \"hi\""},"code":2}""" + "\n", t.err)
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
            """{"error":{"type":"EncodeFailed","message":"boom\u007fboom"},"code":1}""" + "\n",
            t.err,
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
        assertEquals("error: --from and --to must differ\n", t.err)
    }

    @Test
    fun `a consumer's detail is printed as the consumer wrote it`() {
        // As GNU's error() prints its message: the consumer quotes or escapes the input it puts in a detail.
        val esc = Char(27)
        val t = RecordingTerminal()
        val code = renderError(CliError.Failure("$esc[31mboom$esc[0m"), json = false, terminal = t)
        assertEquals(1, code)
        assertEquals("error: $esc[31mboom$esc[0m\n", t.err)
    }

    @Test
    fun `a piece the consumer escaped is printed once`() {
        val t = RecordingTerminal()
        val detail = "bad operand ${"\u001B[31m".escapeControls()} in ${"my file".shellQuoted()}"
        val code = renderError(CliError.Usage(detail), json = false, terminal = t)
        assertEquals(2, code)
        assertEquals("error: bad operand \\033[31m in 'my file'\n", t.err)
    }

    @Test
    fun `authored detail keeps its own newline on both paths`() {
        // A `hint:` continuation line is a layout choice the consumer made, so it survives as a real
        // newline on the human path and as a proper JSON string escape under --json, rather than being
        // mangled into the literal four characters \x0A on one path and not the other.
        val detail = "no such environment 'prd'\nhint: did you mean 'prod'?"
        val human = RecordingTerminal()
        renderError(CliError.Failure(detail), json = false, terminal = human)
        assertEquals("error: $detail\n", human.err)

        val structured = RecordingTerminal()
        renderError(CliError.Usage(detail), json = true, terminal = structured)
        assertEquals(
            """{"error":{"type":"Usage","detail":"no such environment 'prd'\nhint: did you mean 'prod'?"},"code":2}""" +
                "\n",
            structured.err,
        )
    }

    @Test
    fun `echoed token newline is quoted so a caller cannot forge a second error line`() {
        // The counterpart to the test above: an argv token is not authored prose, so its newline is quoted as GNU
        // quotes one. Otherwise `app --n=$'x\nerror: disk wiped'` prints a second, fabricated error line.
        val t = RecordingTerminal()
        renderError(CliError.BadValue("n", "x\nerror: forged", "bad"), json = false, terminal = t)
        assertEquals("error: invalid value 'x'\$'\\n''error: forged' for n: bad\n", t.err)
    }

    @Test
    fun `a token is quoted as GNU quotes an option in its errors`() {
        fun rendered(error: CliError): String = RecordingTerminal().also { renderError(error, false, it) }.err

        assertEquals("error: unknown option '--nope'\n", rendered(CliError.UnknownOption("--nope")))
        assertEquals("error: unknown option '--a b'\n", rendered(CliError.UnknownOption("--a b")))
        assertEquals("error: unknown option \"--it's\"\n", rendered(CliError.UnknownOption("--it's")))
        assertEquals("error: unknown option '--'\$'\\033''[31mx'\n", rendered(CliError.UnknownOption("--\u001B[31mx")))
    }

    @Test
    fun `a quoted token is not escaped a second time on the human path`() {
        // klap's own sentence quotes its token, which leaves only printable text; escaping the sentence again would
        // turn `$'\033'` into `$'\\033'`, which no shell or reader decodes back to the escape that was typed.
        val t = RecordingTerminal()
        renderError(CliError.UnknownSubcommand(parent = "git", token = "\u001B"), json = false, terminal = t)
        assertEquals("error: unknown subcommand ''\$'\\033' for 'git'\n", t.err)
    }

    @Test
    fun `a converter's reason is escaped once beside a quoted value`() {
        val t = RecordingTerminal()
        renderError(CliError.BadValue("n", "a\u001Bb", "saw \u001B"), json = false, terminal = t)
        assertEquals("error: invalid value 'a'\$'\\033''b' for n: saw \\033\n", t.err)
    }

    @Test
    fun `an extra operand is quoted only when it needs it as GNU names a file`() {
        val t = RecordingTerminal()
        renderError(CliError.TooManyArguments("cp", listOf("c", "my file")), json = false, terminal = t)
        assertEquals("error: unexpected extra arguments: c 'my file'\n", t.err)
    }

    @Test
    fun `bad value real escape sequence in the echoed value is quoted on the human path`() {
        // BadValue echoes a user-supplied value straight from argv, so an embedded ESC is quoted as GNU quotes one,
        // `$'\033'`, and shows instead of acting on the terminal.
        val esc = Char(27)
        val t = RecordingTerminal()
        val code = renderError(CliError.BadValue("n", "$esc[31m", "bad"), json = false, terminal = t)
        assertEquals(2, code)
        assertEquals("error: invalid value ''\$'\\033''[31m' for n: bad\n", t.err)
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
        val err = tree.parse(listOf("go", "somevalue")).assertError<CliError.BadValue>()
        assertEquals("conversion failed", err.reason)
        assertIs<IllegalStateException>(assertIs<ConversionError.Threw>(err.cause).thrown)
        assertEquals("invalid value 'somevalue' for n: conversion failed", err.message())
    }
}
