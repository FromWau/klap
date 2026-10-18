package com.fromwau.klap.datetime

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.klap.CliError
import com.fromwau.klap.ConversionError
import com.fromwau.klap.ConverterScope
import com.fromwau.klap.Opt
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private data class Read(val date: LocalDate?, val dateTime: LocalDateTime?, val zone: TimeZone?)

private val tool: TypedCli<Read> = cliOf("app") {
    val date = option(long = "date").localDate()
    val dateTime = option(long = "at").localDateTime()
    val zone = option(long = "zone").timeZone()
    action { Ok("") }
    projection { Read(date(), dateTime(), zone()) }
}

private fun date(raw: String): LocalDate? = tool.parse(listOf("--date", raw)).assertSuccess()!!.date

private fun dateTime(raw: String): LocalDateTime? = tool.parse(listOf("--at", raw)).assertSuccess()!!.dateTime

private fun zone(raw: String): TimeZone? = tool.parse(listOf("--zone", raw)).assertSuccess()!!.zone

private fun refusal(option: String, raw: String): CliError.BadValue =
    tool.parse(listOf(option, raw)).assertError<CliError.BadValue>()

class DateTimeConvertersTest {

    @Test
    fun `a local date reads the ISO calendar date`() {
        assertEquals(LocalDate(2026, 10, 18), date("2026-10-18"))
        assertEquals(LocalDate(2024, 2, 29), date("2024-02-29"))
    }

    @Test
    fun `a local date refuses another form and a day the calendar lacks`() {
        listOf("20261018", "2026-1-5", "2026-02-30", "18.10.2026", "2026-10-18T12:00", "").forEach { raw ->
            val refused = refusal("--date", raw)
            assertEquals(ConversionError.Domain(DateTimeError.NotALocalDate, refused.reason), refused.cause, "`$raw`")
        }
        assertEquals("not an ISO 8601 date, such as 2026-01-31", refusal("--date", "x").reason)
    }

    @Test
    fun `a local date time reads ISO with or without seconds and fractions`() {
        assertEquals(LocalDateTime(2026, 10, 18, 12, 0), dateTime("2026-10-18T12:00"))
        assertEquals(LocalDateTime(2026, 10, 18, 12, 0, 30), dateTime("2026-10-18T12:00:30"))
        assertEquals(LocalDateTime(2026, 10, 18, 12, 0, 30, 500_000_000), dateTime("2026-10-18T12:00:30.5"))
    }

    @Test
    fun `a local date time refuses a date alone an offset and a space for the T`() {
        listOf("2026-10-18", "2026-10-18T12:00Z", "2026-10-18T12:00+02:00", "2026-10-18 12:00", "").forEach { raw ->
            val refused = refusal("--at", raw)
            assertEquals(
                ConversionError.Domain(DateTimeError.NotALocalDateTime, refused.reason),
                refused.cause,
                "`$raw`",
            )
        }
        assertEquals("not an ISO 8601 date and time, such as 2026-01-31T12:00", refusal("--at", "x").reason)
    }

    @Test
    fun `a time zone reads a region id UTC and an offset`() {
        assertEquals(TimeZone.of("Europe/Vienna"), zone("Europe/Vienna"))
        assertEquals(TimeZone.UTC, zone("UTC"))
        assertEquals(UtcOffset(hours = 2).asTimeZone(), zone("+02:00"))
    }

    @Test
    fun `a time zone refuses an id the time zone database lacks`() {
        listOf("Mars/Olympus", "Europe/vienna ", "+25:00", "").forEach { raw ->
            val refused = refusal("--zone", raw)
            assertEquals(ConversionError.Domain(DateTimeError.NotATimeZone, refused.reason), refused.cause, "`$raw`")
        }
        assertEquals("not a time zone, such as Europe/Vienna, UTC or +02:00", refusal("--zone", "x").reason)
    }

    @Test
    fun `the converters chain required multiple and default on operands`() {
        val days = cliOf("app") {
            val zone = option(long = "zone").timeZone().required()
            val first = argument("first").localDate()
            val rest = argument("rest").localDateTime().multiple()
            action { Ok("") }
            projection { Triple(zone(), first(), rest()) }
        }
        assertEquals(
            Triple(TimeZone.UTC, LocalDate(2026, 1, 1), listOf(LocalDateTime(2026, 1, 2, 3, 4))),
            days.parse(listOf("--zone=UTC", "2026-01-01", "2026-01-02T03:04")).assertSuccess(),
        )
        days.parse(listOf("2026-01-01")).assertError<CliError.MissingRequiredOption>()

        val defaulted = cliOf("app") {
            val on = argument("on").localDate().default(LocalDate(2026, 1, 1))
            val at = option(long = "at").localDateTime().default(LocalDateTime(2026, 1, 1, 0, 0))
            action { Ok("") }
            projection { on() to at() }
        }
        assertEquals(
            LocalDate(2026, 1, 1) to LocalDateTime(2026, 1, 1, 0, 0),
            defaulted.parse(emptyList()).assertSuccess(),
        )
    }

    @Test
    fun `a converter through a handle kept past a date time converter is refused when the CLI is built`() {
        listOf<ConverterScope.(Opt<String?>) -> Unit>(
            { it.localDate() },
            { it.localDateTime() },
            { it.timeZone() },
        ).forEach { declare ->
            val refused = assertFailsWith<IllegalArgumentException> {
                cli("app") {
                    val on = option(long = "on")
                    declare(on)
                    on.int()
                    action { Ok("") }
                }
            }
            assertContains(refused.message.orEmpty(), "option '--on': cannot add another converter after")
        }
    }
}
