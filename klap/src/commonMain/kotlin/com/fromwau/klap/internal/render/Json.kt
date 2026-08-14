package com.fromwau.klap.internal.render

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The `--json` error shape: a flat object on stderr. kotlinx.serialization handles the string escaping. */
internal fun jsonErrorEnvelope(message: String, code: Int): String =
    Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("error", message)
            put("code", code)
        },
    )

/** The `--json` shape of `--version`: the same two values the plain line carries, as fields. */
internal fun jsonVersionEnvelope(name: String, version: String): String =
    Json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("name", name)
            put("version", version)
        },
    )
