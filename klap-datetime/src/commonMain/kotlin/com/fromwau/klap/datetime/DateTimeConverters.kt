package com.fromwau.klap.datetime

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.Arg
import com.fromwau.klap.ConversionError
import com.fromwau.klap.ConverterScope
import com.fromwau.klap.Opt
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * Why a value is no date, date and time or time zone: the [ConversionError.Domain.error] of a refusal, whose
 * [ConversionError.Domain.detail] holds the words klap prints.
 */
public sealed interface DateTimeError : IError {
    /** `.localDate()` was given something `LocalDate.parse` refuses. */
    public data object NotALocalDate : DateTimeError

    /** `.localDateTime()` was given something `LocalDateTime.parse` refuses. */
    public data object NotALocalDateTime : DateTimeError

    /** `.timeZone()` was given something `TimeZone.of` refuses. */
    public data object NotATimeZone : DateTimeError
}

/** [parse] as a converter, its `IllegalArgumentException` refusing the value as [error] in the words [detail]. */
private fun <T> refusingAs(
    error: DateTimeError,
    detail: String,
    parse: (String) -> T,
): (String) -> Result<T, ConversionError> = { raw ->
    try {
        Ok(parse(raw))
    } catch (_: IllegalArgumentException) {
        Err(ConversionError.Domain(error, detail))
    }
}

private val readLocalDate = refusingAs(DateTimeError.NotALocalDate, "not an ISO 8601 date, such as 2026-01-31") {
    LocalDate.parse(it)
}

private val readLocalDateTime =
    refusingAs(DateTimeError.NotALocalDateTime, "not an ISO 8601 date and time, such as 2026-01-31T12:00") {
        LocalDateTime.parse(it)
    }

private val readTimeZone =
    refusingAs(DateTimeError.NotATimeZone, "not a time zone, such as Europe/Vienna, UTC or +02:00", TimeZone::of)

/**
 * Parses this operand as a [LocalDate] through `LocalDate.parse`: the ISO 8601 calendar date `2026-01-31`. Any
 * other form, or a day the calendar lacks such as `2026-02-30`, fails as an invalid value with
 * [DateTimeError.NotALocalDate].
 */
context(scope: ConverterScope)
public fun Arg<String>.localDate(): Arg<LocalDate> = with(scope) { convert(readLocalDate) }

/** Parses this option's value as a [LocalDate] through `LocalDate.parse`, as an operand's [localDate] is read. */
context(scope: ConverterScope)
public fun Opt<String?>.localDate(): Opt<LocalDate?> = with(scope) { convert(readLocalDate) }

/**
 * Parses this operand as a [LocalDateTime] through `LocalDateTime.parse`: ISO 8601 with no offset,
 * `2026-01-31T12:00`, seconds and a fraction optional. A date alone, an offset or a space for the `T` fails as an
 * invalid value with [DateTimeError.NotALocalDateTime].
 */
context(scope: ConverterScope)
public fun Arg<String>.localDateTime(): Arg<LocalDateTime> = with(scope) { convert(readLocalDateTime) }

/**
 * Parses this option's value as a [LocalDateTime] through `LocalDateTime.parse`, as an operand's [localDateTime]
 * is read.
 */
context(scope: ConverterScope)
public fun Opt<String?>.localDateTime(): Opt<LocalDateTime?> = with(scope) { convert(readLocalDateTime) }

/**
 * Parses this operand as a [TimeZone] through `TimeZone.of`: a region id of the time zone database
 * (`Europe/Vienna`), `UTC`, or an offset (`+02:00`). An id the database lacks fails as an invalid value with
 * [DateTimeError.NotATimeZone].
 */
context(scope: ConverterScope)
public fun Arg<String>.timeZone(): Arg<TimeZone> = with(scope) { convert(readTimeZone) }

/** Parses this option's value as a [TimeZone] through `TimeZone.of`, as an operand's [timeZone] is read. */
context(scope: ConverterScope)
public fun Opt<String?>.timeZone(): Opt<TimeZone?> = with(scope) { convert(readTimeZone) }
