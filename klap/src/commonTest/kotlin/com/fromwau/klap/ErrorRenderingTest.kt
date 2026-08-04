package com.fromwau.klap

import com.fromwau.klap.internal.render.jsonErrorEnvelope
import com.fromwau.klap.internal.render.message
import com.fromwau.klap.internal.render.renderError
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    fun unknownOption_message() {
        assertEquals("unknown option '--nope'", CliError.UnknownOption("--nope").message())
    }

    @Test
    fun unknownOption_messageWithSuggestion() {
        assertEquals(
            "unknown option '--verbos'. Did you mean --verbose?",
            CliError.UnknownOption("--verbos", "--verbose").message(),
        )
    }

    @Test
    fun ambiguousOptionListsThePossibilitiesLikeGnu() {
        assertEquals(
            "option '--re' is ambiguous; possibilities: '--recursive' '--reference'",
            CliError.AmbiguousOption("--re", listOf("--recursive", "--reference")).message(),
        )
    }

    @Test
    fun anAmbiguousSubcommandReadsLikeAnAmbiguousOption() {
        assertEquals(
            "subcommand 'st' is ambiguous; possibilities: 'stash' 'status'",
            CliError.AmbiguousSubcommand("app", "st", listOf("stash", "status")).message(),
        )
    }

    @Test
    fun unknownSubcommand_messageWithSuggestion() {
        assertEquals(
            "unknown subcommand 'confi' for 'app'. Did you mean config?",
            CliError.UnknownSubcommand("app", "confi", "config").message(),
        )
    }

    @Test
    fun subcommandAfterSeparator_message() {
        assertEquals(
            "'build' is a command of 'app', but '--' ends command parsing; put '--' after the command",
            CliError.SubcommandAfterSeparator("build", "app").message(),
        )
    }

    @Test
    fun tooManyArguments_pluralizes() {
        assertEquals(
            "unexpected extra arguments: a b",
            CliError.TooManyArguments("add", listOf("a", "b")).message(),
        )
    }

    @Test
    fun tooManyArguments_singularWithCommandSuggestion() {
        assertEquals(
            "unexpected extra argument: biuld. Did you mean the 'build' command?",
            CliError.TooManyArguments("app", listOf("biuld"), "build").message(),
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
    fun anAmbiguousValueNamesTheOptionItBelongsTo() {
        assertEquals(
            "value 'h' for --priority is ambiguous; possibilities: 'high' 'highest'",
            CliError.AmbiguousValue("--priority", "h", listOf("high", "highest")).message(),
        )
    }

    @Test
    fun flagTakesNoValue_namesTheShortFormAsTyped() {
        assertEquals("flag '-v' does not take a value", CliError.FlagTakesNoValue("-v").message())
    }

    @Test
    fun flagTakesNoValue_namesTheLongFormAsTyped() {
        assertEquals("flag '--verbose' does not take a value", CliError.FlagTakesNoValue("--verbose").message())
    }

    @Test
    fun flagTakesNoValue_negationHintIsPrefixedAndCorrect() {
        assertEquals(
            "flag '--color' does not take a value; use --no-color to turn it off",
            CliError.FlagTakesNoValue("--color", "no-color").message(),
        )
    }

    @Test
    fun missingRequiredOption_message() {
        assertEquals("missing required option --host", CliError.MissingRequiredOption("--host").message())
    }

    @Test
    fun missingOptionValue_message() {
        assertEquals("option --host requires a value", CliError.MissingOptionValue("--host").message())
    }

    @Test
    fun tooFewOccurrences_pluralizesMin() {
        assertEquals(
            "'header' must be given at least 2 times (got 1)",
            CliError.TooFewOccurrences("header", 2, 1).message(),
        )
    }

    @Test
    fun tooFewOccurrences_singularForMinOne() {
        assertEquals(
            "'tag' must be given at least 1 time (got 0)",
            CliError.TooFewOccurrences("tag", 1, 0).message(),
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

    @Test
    fun encodeFailed_jsonEnvelopeStripsEmbeddedControlCharacters() {
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
    fun missingArgument_messagePinsTheFullStringIncludingTheForCommandClause() {
        assertEquals(
            "missing required argument <text> for 'add'",
            CliError.MissingArgument("add", "text").message(),
        )
    }

    @Test
    fun usage_exitsTwoSoAHandWrittenRuleMatchesTheBuiltInOnes() {
        // The whole point of the variant: Failure would exit 1 here, disagreeing with every parse-level
        // error over the same class of mistake.
        val t = RecordingTerminal()
        assertEquals(2, renderError(CliError.Usage("--from and --to must differ"), json = false, terminal = t))
        assertEquals("error: --from and --to must differ\n", t.err.toString())
    }

    @Test
    fun failure_embeddedEscapeSequenceIsStrippedOnTheHumanPath() {
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
    fun usage_embeddedEscapeSequenceIsStrippedOnTheHumanPath() {
        val esc = Char(27)
        val t = RecordingTerminal()
        val code = renderError(CliError.Usage("bad operand '$esc[31m'"), json = false, terminal = t)
        assertEquals(2, code)
        assertEquals("error: bad operand '\\x1B[31m'\n", t.err.toString())
    }

    @Test
    fun authoredDetail_keepsItsOwnNewlineOnBothPaths() {
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
    fun echoedToken_newlineIsEscapedSoACallerCannotForgeASecondErrorLine() {
        // The counterpart to the test above: an argv token is not authored prose, so its newline stays
        // escaped. Otherwise `app --n=$'x\nerror: disk wiped'` prints a second, fabricated error line.
        val t = RecordingTerminal()
        renderError(CliError.BadValue("n", "x\nerror: forged", "bad"), json = false, terminal = t)
        assertEquals("error: invalid value 'x\\x0Aerror: forged' for n: bad\n", t.err.toString())
    }

    @Test
    fun badValue_realEscapeSequenceInTheEchoedValueIsStillStrippedOnTheHumanPath() {
        // BadValue echoes a user-supplied value straight from argv, so an embedded ESC is neutralized to
        // the literal string "\x1B" here, exactly as in an authored detail but without the newline carve-out.
        val esc = Char(27)
        val t = RecordingTerminal()
        val code = renderError(CliError.BadValue("n", "$esc[31m", "bad"), json = false, terminal = t)
        assertEquals(2, code)
        assertEquals("error: invalid value '\\x1B[31m' for n: bad\n", t.err.toString())
    }

    @Test
    fun badValue_converterExceptionWithNullMessageDoesNotDoubleTheFallbackReason() {
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
        assertEquals(CliError.BadValue("n", "somevalue", "conversion failed"), err)
        assertEquals("invalid value 'somevalue' for n: conversion failed", err.message())
    }
}
