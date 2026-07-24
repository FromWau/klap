package com.fromwau.klap

/** Minimal JSON string-body escaper (no dependency). Escapes quotes, backslash, and control chars. */
internal fun jsonEscape(raw: String): String = buildString {
    for (c in raw) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
        }
    }
}

/** The `--json` error shape: a flat object on stderr. */
internal fun jsonErrorEnvelope(message: String, code: Int): String =
    """{"error":"${jsonEscape(message)}","code":$code}"""
