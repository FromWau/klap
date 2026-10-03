package com.fromwau.klap.internal.render

import com.fromwau.klap.CliError
import com.fromwau.klap.ConversionError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import kotlinx.serialization.json.putJsonArray

/**
 * A failure as `--json` writes it: the variant's name under `type`, then its data. No sentence is part of it, so
 * a program reading the output branches on `type` and the fields, never on wording.
 */
internal fun CliError.toJson(): JsonElement = when (this) {
    is CliError.UnknownSubcommand -> typed("UnknownSubcommand") {
        put("parent", parent)
        put("token", token)
        putIfPresent("suggestion", suggestion)
    }
    is CliError.MissingSubcommand -> typed("MissingSubcommand") {
        put("parent", parent)
    }
    is CliError.AmbiguousSubcommand -> typed("AmbiguousSubcommand") {
        put("parent", parent)
        put("token", token)
        putStrings("candidates", candidates)
    }
    is CliError.UnknownOption -> typed("UnknownOption") {
        put("token", token)
        putIfPresent("suggestion", suggestion)
        putIfPresent("cluster", cluster)
    }
    is CliError.AmbiguousOption -> typed("AmbiguousOption") {
        put("token", token)
        putStrings("candidates", candidates)
    }
    is CliError.SubcommandAfterSeparator -> typed("SubcommandAfterSeparator") {
        put("command", command)
        put("parent", parent)
    }
    is CliError.UnroutedSubcommand -> typed("UnroutedSubcommand") {
        put("command", command)
        put("parent", parent)
    }
    is CliError.MissingArgument -> typed("MissingArgument") {
        put("command", command)
        put("argument", argument)
    }
    is CliError.MissingRequiredOption -> typed("MissingRequiredOption") { put("option", option) }
    is CliError.MissingOptionValue -> typed("MissingOptionValue") { put("option", option) }
    is CliError.FlagTakesNoValue -> typed("FlagTakesNoValue") {
        put("flag", flag)
        putIfPresent("negationHint", negationHint)
    }
    is CliError.BadValue -> typed("BadValue") {
        put("name", name)
        put("value", value)
        cause?.let { put("cause", it.toJson()) }
    }
    is CliError.InvalidChoice -> typed("InvalidChoice") {
        put("name", name)
        put("value", value)
        putStrings("choices", choices)
        putIfPresent("suggestion", suggestion)
    }
    is CliError.AmbiguousValue -> typed("AmbiguousValue") {
        put("name", name)
        put("value", value)
        putStrings("candidates", candidates)
    }
    is CliError.TooManyArguments -> typed("TooManyArguments") {
        put("command", command)
        putStrings("extras", extras)
        putIfPresent("suggestion", suggestion)
    }
    is CliError.MixedClusterAfterOperands -> typed("MixedClusterAfterOperands") {
        put("cluster", cluster)
        put("global", global)
    }
    is CliError.TooFewOccurrences -> typed("TooFewOccurrences") {
        put("option", option)
        put("min", min)
        put("actual", actual)
    }
    is CliError.ExactlyOneRequired -> typed("ExactlyOneRequired") { putStrings("inputs", inputs) }
    is CliError.MutuallyExclusive -> typed("MutuallyExclusive") { putStrings("inputs", inputs) }
    is CliError.Usage -> typed("Usage") { put("detail", detail) }
    is CliError.Failure -> typed("Failure") { put("detail", detail) }
    is CliError.Domain -> json ?: typed("Domain") { put("detail", detail) }
}

internal fun ConversionError.toJson(): JsonElement = when (this) {
    ConversionError.NotAnInteger -> typed("NotAnInteger") {}
    ConversionError.NotALong -> typed("NotALong") {}
    ConversionError.NotADouble -> typed("NotADouble") {}
    ConversionError.NotABoolean -> typed("NotABoolean") {}
    is ConversionError.NotOneOf -> typed("NotOneOf") { putStrings("choices", choices) }
    // The throwable's own account is the only data a crashed transform leaves behind.
    is ConversionError.Threw -> typed("Threw") { putIfPresent("message", thrown.message) }
    is ConversionError.Domain -> json ?: typed("Domain") { put("detail", detail) }
}

/** klap's own failure to write an action's value under `--json`. */
internal fun encodeFailureJson(type: String, message: String?): JsonElement =
    typed(type) { putIfPresent("message", message) }

private fun typed(type: String, fields: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("type", type)
    fields()
}

private fun JsonObjectBuilder.putIfPresent(key: String, value: String?) {
    if (value != null) put(key, value)
}

private fun JsonObjectBuilder.putStrings(key: String, values: List<String>) {
    putJsonArray(key) { values.forEach { add(JsonPrimitive(it)) } }
}
