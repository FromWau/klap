package com.fromwau.klap.internal.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `--json` error shape on stderr: the failure as data, and the exit code it ends the run with. JSON already
 * escapes the controls below 0x20; DEL is escaped too, so no token echoed from argv reaches the terminal raw.
 */
internal fun jsonErrorEnvelope(error: JsonElement, code: Int): String =
    Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("error", error)
            put("code", code)
        },
    ).replace("\u007F", "\\u007f")

/** The `--json` shape of `--version`: the same two values the plain line carries, as fields. */
internal fun jsonVersionEnvelope(name: String, version: String): String =
    Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("name", name)
            put("version", version)
        },
    )
