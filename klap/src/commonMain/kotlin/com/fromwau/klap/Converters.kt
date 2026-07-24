package com.fromwau.klap

private fun numeric(reason: String, parse: (String) -> Any?): (String) -> Result<Any?, String> =
    { raw -> parse(raw)?.let { Result.Success(it) } ?: Result.Error(reason) }

// --- Arg type converters ---

fun Arg<String>.int(): Arg<Int> {
    spec.convert = numeric("not an integer") { it.toIntOrNull() }
    return Arg(spec)
}

fun Arg<String>.long(): Arg<Long> {
    spec.convert = numeric("not a long") { it.toLongOrNull() }
    return Arg(spec)
}

fun Arg<String>.double(): Arg<Double> {
    spec.convert = numeric("not a number") { it.toDoubleOrNull() }
    return Arg(spec)
}

fun Arg<String>.boolean(): Arg<Boolean> {
    spec.convert = numeric("not a boolean (true/false)") { it.toBooleanStrictOrNull() }
    return Arg(spec)
}

inline fun <reified E : Enum<E>> Arg<String>.enum(): Arg<E> {
    val values = enumValues<E>()
    // Display choices CLI-style (lowercase); matching stays case-insensitive. Use .choice() for exact casing.
    spec.choices = values.map { it.name.lowercase() }
    spec.convert = { raw ->
        values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?.let { Result.Success(it) }
            ?: Result.Error("not one of ${values.joinToString(", ") { it.name.lowercase() }}")
    }
    return Arg(spec)
}

fun Arg<String>.choice(vararg choices: String): Arg<String> {
    val allowed = choices.toList()
    spec.choices = allowed
    spec.convert = { raw ->
        if (raw in allowed) Result.Success(raw) else Result.Error("not one of ${allowed.joinToString(", ")}")
    }
    return Arg(spec)
}

fun <T> Arg<String>.convert(transform: (String) -> Result<T, String>): Arg<T> {
    spec.convert = transform
    return Arg(spec)
}

/**
 * Generic input adapter: reads the raw string and returns a domain type. A thrown exception becomes a
 * clean parse error (`invalid value …`). e.g. `argument("timeout").map { it.toInt().seconds }`.
 * Use [convert] instead when you want to control the error message yourself.
 */
fun <T> Arg<String>.map(transform: (String) -> T): Arg<T> {
    spec.convert = { raw ->
        try {
            Result.Success(transform(raw))
        } catch (e: Exception) {
            Result.Error(e.message ?: "invalid value")
        }
    }
    return Arg(spec)
}

// --- Arg cardinality / hints ---

fun <T> Arg<T>.optional(): Arg<T?> {
    spec.cardinality = Cardinality.Optional
    return Arg(spec)
}

fun <T> Arg<T>.default(value: T): Arg<T> {
    spec.cardinality = Cardinality.Default(value)
    return Arg(spec)
}

fun <T> Arg<T>.multiple(min: Int = 0): Arg<List<T>> {
    spec.cardinality = Cardinality.Multiple(min)
    return Arg(spec)
}

fun <T> Arg<T>.file(): Arg<T> {
    spec.isPath = true
    return Arg(spec)
}

// --- Opt converters ---

fun Opt<String?>.int(): Opt<Int?> {
    spec.convert = numeric("not an integer") { it.toIntOrNull() }
    return Opt(spec)
}

fun Opt<String?>.long(): Opt<Long?> {
    spec.convert = numeric("not a long") { it.toLongOrNull() }
    return Opt(spec)
}

fun Opt<String?>.double(): Opt<Double?> {
    spec.convert = numeric("not a number") { it.toDoubleOrNull() }
    return Opt(spec)
}

fun Opt<String?>.choice(vararg choices: String): Opt<String?> {
    val allowed = choices.toList()
    spec.choices = allowed
    spec.convert = { raw ->
        if (raw in allowed) Result.Success(raw) else Result.Error("not one of ${allowed.joinToString(", ")}")
    }
    return Opt(spec)
}

fun Opt<String?>.boolean(): Opt<Boolean?> {
    spec.convert = numeric("not a boolean (true/false)") { it.toBooleanStrictOrNull() }
    return Opt(spec)
}

inline fun <reified E : Enum<E>> Opt<String?>.enum(): Opt<E?> {
    val values = enumValues<E>()
    // Display choices CLI-style (lowercase); matching stays case-insensitive. Use .choice() for exact casing.
    spec.choices = values.map { it.name.lowercase() }
    spec.convert = { raw ->
        values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?.let { Result.Success(it) }
            ?: Result.Error("not one of ${values.joinToString(", ") { it.name.lowercase() }}")
    }
    return Opt(spec)
}

fun <T> Opt<String?>.convert(transform: (String) -> Result<T, String>): Opt<T?> {
    spec.convert = transform
    return Opt(spec)
}

/**
 * Generic input adapter for an option: reads the raw string and returns a domain type; a thrown
 * exception becomes a clean parse error. e.g. `option("timeout", "t").map { it.toInt().seconds }`.
 */
fun <T> Opt<String?>.map(transform: (String) -> T): Opt<T?> {
    spec.convert = { raw ->
        try {
            Result.Success(transform(raw))
        } catch (e: Exception) {
            Result.Error(e.message ?: "invalid value")
        }
    }
    return Opt(spec)
}

fun <T> Opt<T?>.required(): Opt<T> {
    spec.cardinality = Cardinality.Required
    return Opt(spec)
}

fun <T> Opt<T?>.default(value: T): Opt<T> {
    spec.cardinality = Cardinality.Default(value)
    return Opt(spec)
}

/** Collects every occurrence into a list. Options are zero-or-more; enforce a minimum in your `action {}` if you need one. */
fun <T> Opt<T?>.multiple(): Opt<List<T>> {
    spec.cardinality = Cardinality.Multiple(0)
    return Opt(spec)
}
