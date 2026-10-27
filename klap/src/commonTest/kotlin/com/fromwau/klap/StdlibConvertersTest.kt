package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.OptionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun argSpec(): ArgumentSpec = ArgumentSpec("x", "", { Result.Success(it) })

private fun optSpec(): OptionSpec = OptionSpec(listOf("--opt"), "", { Result.Success(it) })

private const val DASHED = "550e8400-e29b-41d4-a716-446655440000"

class StdlibConvertersTest : ConverterScope() {

    // --- duration ---

    @Test
    fun `a duration reads Kotlin's format with its units in decreasing order`() {
        val spec = argSpec()
        Arg<String>(spec).duration()
        mapOf(
            "PT1H30M" to 90.minutes,
            "P1D" to 1.days,
            "1s" to 1.seconds,
            "1h30m" to 90.minutes,
            "1.5h" to 90.minutes,
            "5h 31m 2s 123ms" to 5.hours + 31.minutes + 2.seconds + 123.milliseconds,
            "2d 3h" to 2.days + 3.hours,
            "0s" to Duration.ZERO,
        ).forEach { (raw, expected) -> assertEquals(Result.Success(expected), spec.convert(raw), "`$raw`") }
    }

    @Test
    fun `a duration reads a bare number as seconds as GNU sleep does`() {
        val spec = argSpec()
        Arg<String>(spec).duration()
        mapOf(
            "5" to 5.seconds,
            "5.5" to 5.5.seconds,
            "0" to Duration.ZERO,
            ".5" to 0.5.seconds,
            "1e3" to 1000.seconds,
        ).forEach { (raw, expected) -> assertEquals(Result.Success(expected), spec.convert(raw), "`$raw`") }
    }

    @Test
    fun `a duration refuses a negative or infinite one and a malformed one`() {
        val spec = argSpec()
        Arg<String>(spec).duration()
        listOf(
            "-1h", "-(1h 30m)", "-5", "-0.5", "Infinity", "inf", "-inf", "nan", "1e400",
            "1H", "1 h", "30m 1h", "pt1h", "", " ", "5x", "1h 1h",
        ).forEach { raw -> assertEquals(Result.Error(ConversionError.NotADuration), spec.convert(raw), "`$raw`") }
    }

    @Test
    fun `an option duration converts its value`() {
        val spec = optSpec()
        Opt<String?>(spec).duration()
        assertEquals(Result.Success(90.seconds), spec.convert("1m 30s"))
        assertEquals(Result.Error(ConversionError.NotADuration), spec.convert("soon"))
    }

    @Test
    fun `a duration chains required multiple default and range`() {
        val tool = cliOf("app") {
            val timeout = option(long = "timeout").duration().required()
            val every = option(long = "every").duration().multiple()
            val grace = option(long = "grace").duration().default(10.seconds)
            val limit = option(long = "limit").duration().range(1.seconds..1.hours)
            val wait = argument("wait").duration().default(1.minutes)
            action { Ok("") }
            projection { listOf(timeout(), every(), grace(), limit(), wait()) }
        }
        assertEquals(
            listOf(5.seconds, listOf(1.hours, 2.minutes), 10.seconds, null, 3.seconds),
            tool.parse(listOf("--timeout", "5", "--every", "1h", "--every=2m", "3")).assertSuccess(),
        )
        assertEquals(
            listOf(1.seconds, emptyList<Duration>(), 1.days, 30.minutes, 1.minutes),
            tool.parse(listOf("--timeout=1s", "--grace", "P1D", "--limit", "30m")).assertSuccess(),
        )
        tool.parse(emptyList()).assertError<CliError.MissingRequiredOption>()
        assertEquals(
            "must be in 1s..1h",
            tool.parse(listOf("--timeout=1s", "--limit=2h")).assertError<CliError.BadValue>().reason,
        )
    }

    @Test
    fun `a duration that does not parse is refused with the forms it takes`() {
        val tool = cli("app") {
            option(long = "timeout").duration()
            action { Ok("") }
        }
        val refused = tool.parse(listOf("--timeout", "-1h")).assertError<CliError.BadValue>()
        assertEquals(ConversionError.NotADuration, refused.cause)
        assertEquals("-1h", refused.value)
        assertEquals("not a duration of zero or more, such as 90, 1h30m or PT1H30M", refused.reason)
    }

    @Test
    fun `a default a duration refuses fails when the CLI is built`() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                argument("timeout").default("-1s").duration()
                action { Ok("") }
            }
        }
    }

    // --- instant ---

    @Test
    fun `an instant reads ISO 8601 with an offset`() {
        val spec = argSpec()
        Arg<String>(spec).instant()
        val noon = Instant.fromEpochSeconds(1_792_324_800)
        mapOf(
            "2026-10-18T12:00:00Z" to noon,
            "2026-10-18T14:00:00+02:00" to noon,
            "2026-10-18T14:00:00+02" to noon,
            "2026-10-18t12:00:00z" to noon,
            "2026-10-18T12:00:00.5Z" to Instant.fromEpochSeconds(1_792_324_800, 500_000_000),
            "2026-10-18T12:00:00.123456789Z" to Instant.fromEpochSeconds(1_792_324_800, 123_456_789),
        ).forEach { (raw, expected) -> assertEquals(Result.Success(expected), spec.convert(raw), "`$raw`") }
    }

    @Test
    fun `an instant refuses one without seconds or an offset or with a space for the T`() {
        val spec = optSpec()
        Opt<String?>(spec).instant()
        listOf(
            "2026-10-18T12:00Z", "2026-10-18T12:00:00", "2026-10-18 12:00:00Z", "2026-10-18", "1792324800", "",
        ).forEach { raw -> assertEquals(Result.Error(ConversionError.NotAnInstant), spec.convert(raw), "`$raw`") }
    }

    @Test
    fun `an instant chains required and multiple and refuses a bad value with its words`() {
        val tool = cliOf("app") {
            val since = option(long = "since").instant().required()
            val at = argument("at").instant().multiple()
            action { Ok("") }
            projection { since() to at() }
        }
        val first = Instant.parse("2026-01-01T00:00:00Z")
        assertEquals(
            first to listOf(first, Instant.parse("2026-01-02T00:00:00Z")),
            tool.parse(listOf("--since=2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"))
                .assertSuccess(),
        )
        val refused = tool.parse(listOf("--since", "2026-01-01")).assertError<CliError.BadValue>()
        assertEquals(ConversionError.NotAnInstant, refused.cause)
        assertEquals("not an ISO 8601 instant with an offset, such as 2026-01-31T12:00:00Z", refused.reason)
    }

    // --- uuid ---

    @Test
    fun `a uuid reads the dashed and the plain hex form in either case`() {
        val spec = argSpec()
        Arg<String>(spec).uuid()
        val expected = Uuid.parse(DASHED)
        listOf(DASHED, DASHED.uppercase(), DASHED.replace("-", ""), DASHED.replace("-", "").uppercase())
            .forEach { raw -> assertEquals(Result.Success(expected), spec.convert(raw), "`$raw`") }
    }

    @Test
    fun `a uuid refuses braces and anything else`() {
        val spec = optSpec()
        Opt<String?>(spec).uuid()
        listOf("{$DASHED}", DASHED.drop(1), DASHED + "0", DASHED.replace('a', 'g'), "urn:uuid:$DASHED", "")
            .forEach { raw -> assertEquals(Result.Error(ConversionError.NotAUuid), spec.convert(raw), "`$raw`") }
    }

    @Test
    fun `a uuid shows dashed in lower case`() {
        val spec = argSpec()
        Arg<String>(spec).uuid()
        assertEquals(DASHED, spec.convert(DASHED.replace("-", "").uppercase()).assertSuccess().toString())
    }

    @Test
    fun `a uuid chains required and default and refuses a bad value with its words`() {
        val fallback = Uuid.parse(DASHED)
        val tool = cliOf("app") {
            val id = option(long = "id").uuid().required()
            val parent = argument("parent").uuid().default(fallback)
            action { Ok("") }
            projection { id() to parent() }
        }
        assertEquals(fallback to fallback, tool.parse(listOf("--id", DASHED)).assertSuccess())
        val refused = tool.parse(listOf("--id", "{$DASHED}")).assertError<CliError.BadValue>()
        assertEquals(ConversionError.NotAUuid, refused.cause)
        assertEquals("not a UUID (32 hex digits, dashed as 8-4-4-4-12 or not)", refused.reason)
    }

    @Test
    fun `a converter after a duration instant or uuid is refused`() {
        listOf<Arg<String>.() -> Unit>({ duration() }, { instant() }, { uuid() }).forEach { convert ->
            val handle = Arg<String>(argSpec())
            handle.convert()
            assertFailsWith<IllegalArgumentException> { handle.int() }
        }
    }
}
