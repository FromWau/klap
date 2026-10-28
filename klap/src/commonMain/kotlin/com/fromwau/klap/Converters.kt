package com.fromwau.klap

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.klap.internal.render.reason
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.DashLedGather
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.ValueSpec
import com.fromwau.klap.internal.spec.requireValidSpelling
import com.fromwau.klap.internal.spec.spellings
import com.fromwau.klap.internal.spec.token
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmName
import kotlin.math.abs
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

private fun parsedOr(error: ConversionError, parse: (String) -> Any?): (String) -> Result<Any?, ConversionError> =
    { raw -> parse(raw)?.let { Result.Success(it) } ?: Result.Error(error) }

/** The blanks C's `isspace` names, which `strtol` skips ahead of a number. */
private const val C_BLANKS = " \t\n\u000B\u000C\r"

/**
 * [raw] as GNU tools read a decimal integer through `strtol`: leading blanks skipped, then an optional sign and
 * ASCII digits only, so `" +2"` is `+2` while `"2 "` and `"١٢"` are null.
 */
private fun gnuDecimal(raw: String): String? {
    val number = raw.trimStart { it in C_BLANKS }
    val digits = if (number.startsWith('+') || number.startsWith('-')) number.drop(1) else number
    return number.takeIf { digits.isNotEmpty() && digits.all { it in '0'..'9' } }
}

/** The part of a C `strtod` number after its sign: a decimal float, or a hex float and its binary exponent. */
private val STRTOD_DIGITS = Regex(
    "([0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?" +
        "|0[xX](?:[0-9a-fA-F]+\\.?[0-9a-fA-F]*|\\.[0-9a-fA-F]+)([pP][+-]?[0-9]+)?",
)

/**
 * [raw] as GNU tools read a floating-point number through `strtod`, with nothing left unread: leading blanks
 * skipped, so `" 1.5"` is 1.5, `0x1A` is 26 and `INF` is infinity, while `"1 "`, `1.5f` and `1e` are null.
 */
private fun strtodDouble(raw: String): Double? {
    val number = raw.trimStart { it in C_BLANKS }
    val negative = number.startsWith('-')
    val unsigned = if (negative || number.startsWith('+')) number.drop(1) else number
    when (unsigned.lowercase()) {
        "inf", "infinity" -> return if (negative) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        "nan" -> return Double.NaN
    }
    val (decimal, binaryExponent) = STRTOD_DIGITS.matchEntire(unsigned)?.destructured ?: return null
    // Kotlin reads a hex float only with its binary exponent, which strtod lets you leave out.
    return if (decimal.isNotEmpty() || binaryExponent.isNotEmpty()) number.toDouble() else "${number}p0".toDouble()
}

/**
 * [raw] as a duration of zero or more: a bare number read as [strtodDouble] reads one is that many seconds, as GNU
 * `sleep 1.5` reads it, and anything else is Kotlin's own format; null when negative, infinite or neither.
 */
private fun nonNegativeDuration(raw: String): Duration? {
    val duration = strtodDouble(raw)?.takeUnless { it.isNaN() }?.seconds ?: Duration.parseOrNull(raw)
    return duration?.takeIf { !it.isNegative() && it.isFinite() }
}

/** How help and a refusal name this progression: low to high, with its step when that is not 1. */
private fun IntProgression.label(): String {
    val bounds = "${minOf(first, last)}..${maxOf(first, last)}"
    return if (step == 1 || step == -1) bounds else "$bounds step ${abs(step)}"
}

/**
 * Wraps a composed [ValueSpec.convert] whose newest stage is type-changing (`.int()`, `.enum<E>()`, a
 * `.convert { }` or `.map { }` to anything but `String`, ...): its success value is no longer a `String`, so
 * [andThenConvert]'s inner cast would break if another converter composed on top of it. `.choice()` and a
 * `String` to `String` `.convert { }` or `.map { }` stay unmarked, since stacking those is legal.
 */
private class TypeChangedConverter(private val delegate: (String) -> Result<Any?, ConversionError>) :
    (String) -> Result<Any?, ConversionError> {
    override fun invoke(raw: String): Result<Any?, ConversionError> = delegate(raw)
}

/**
 * Composes [next] after whatever converter is already on this spec, and after the checks declared since,
 * instead of overwriting it, so chained String-input converters (`.choice().map { }`, `.map { }.map { }`,
 * `.choice().int()`, ...) stack. The inner cast to `String` holds only while every prior stage still yields
 * one, so a [typeChanging] stage closes the spec to further converters — which is what catches a second call made
 * through a leaked `Arg<String>`/`Opt<String?>` handle, whose static type never advances to reveal it.
 */
private fun ValueSpec.andThenConvert(typeChanging: Boolean = false, next: (String) -> Result<Any?, ConversionError>) {
    val label = if (this is OptionSpec) "option" else "argument"
    require(convert !is TypeChangedConverter) {
        "$label '$name': cannot add another converter after a type-changing converter " +
            "(.int()/.long()/.double()/.boolean()/.duration()/.instant()/.uuid()/.enum<E>(), or a " +
            ".convert { }/.map { } to a type other than String) has already run; " +
            "this usually means a converter was called through an Arg<String>/Opt<String?> handle kept alive " +
            "past that point"
    }
    val prior = convert.thenChecked(validate)
    validate = null
    val composed: (String) -> Result<Any?, ConversionError> = { raw ->
        when (val p = prior(raw)) {
            is Result.Success -> next(p.value as String)
            is Result.Error -> p
        }
    }
    convert = if (typeChanging) TypeChangedConverter(composed) else composed
    val declaredDefault = cardinality as? Cardinality.Default ?: return
    // Only a String-typed receiver reaches a converter, so a default already stored is the raw text the
    // author wrote; without this the parser would write that text into an accessor now typed otherwise.
    val converted = next(declaredDefault.value as String)
    require(converted is Result.Success) {
        "$label '$name': default value '${declaredDefault.value}' is invalid: " +
                (converted as Result.Error).error.reason()
    }
    cardinality = Cardinality.Default(converted.value)
}

/**
 * Two choices [ignoreCase] matches alike, or a constant spelled twice, would leave the later one permanently
 * unreachable while `--help` still advertises both. Fail loudly at construction instead, naming [owner].
 */
private fun requireDistinctChoices(owner: String, choices: List<String>, ignoreCase: Boolean) {
    val seen = mutableMapOf<String, String>()
    for (choice in choices) {
        val key = if (ignoreCase) choice.lowercase() else choice
        val previous = seen[key]
        require(previous == null) {
            if (previous == choice) {
                "$owner: duplicate choice '$choice'"
            } else {
                "$owner: choices '$previous' and '$choice' differ only in case, which ignoreCase = true " +
                    "cannot tell apart"
            }
        }
        seen[key] = choice
    }
}

/**
 * A declared [bareValue] runs through the option's own converter at parse time exactly like a typed value,
 * so a choice set that excludes it would only fail once a user typed the bare form — blaming them for a
 * value the AUTHOR wrote on a line `--help` renders as legal. Checked at construction instead, in both
 * declaration orders (`applyChoice`/`applyEnum` call this too), matching [choices] as the parser does so a
 * value the parser would accept is never rejected here.
 */
private fun OptionSpec.requireBareValueInChoices(bareValue: String, choices: List<String>) {
    require(choices.any { it.equals(bareValue, ignoreCase = choiceIgnoreCase) }) {
        "option '$name': the bare value '$bareValue' from .optionalValue() is not one of ${choices.joinToString(", ")}"
    }
}

/** Fails when [otherForm] is already declared: a bare occurrence binds a value or [OptionalValue.Bare], not both. */
private fun OptionSpec.requireOneBareReading(otherForm: Boolean) {
    require(!otherForm) {
        "option '$name': .optionalValue(whenBare) and .optionalValue() are two readings of a bare occurrence; " +
            "declare one"
    }
    requireNotGluedAndOptional(gluedValue)
}

/** Fails when [conflicting] holds: `.gluedValue()` refuses the bare occurrence an optional value reads. */
private fun OptionSpec.requireNotGluedAndOptional(conflicting: Boolean) {
    require(!conflicting) { "option '$name': .gluedValue() refuses a bare occurrence, so its value cannot be optional" }
}

/**
 * A `.default()` outside [ValueSpec.choices] would be a value `--help` advertises but no input can produce.
 * [andThenConvert] already covers the `.default()`-then-`.choice()` order; this covers the reverse, matching
 * as the parser does and storing the declared spelling so an absent input binds what a matching one would.
 * A default that is no longer a raw `String` never reached [choices], so it passes through unchecked.
 */
private fun ValueSpec.canonicalDefaultOrThrow(value: Any?): Any? {
    val current = choices ?: return value
    if (value !is String) return value
    val label = if (this is OptionSpec) "option" else "argument"
    val canonical = current.firstOrNull { it.equals(value, ignoreCase = choiceIgnoreCase) }
    require(canonical != null) {
        "$label '$name': default value '$value' is invalid: not one of ${current.joinToString(", ")}"
    }
    return canonical
}

private fun ValueSpec.multipleWithDefault(multiple: Cardinality.Multiple, value: List<*>): Cardinality.Multiple {
    val label = if (this is OptionSpec) "option" else "argument"
    require(multiple.min == 0) {
        "$label '$name': .default() needs .multiple(min = 0), since a default never applies when a value is required"
    }
    // An empty default is what `.multiple()` binds anyway, and help would show it as `default: ` with nothing.
    return multiple.copy(default = value.ifEmpty { null }?.map { canonicalDefaultOrThrow(it) })
}

// Shared bodies behind the mirrored Arg/Opt converter pairs (which differ only in their typed wrapper).

private fun ValueSpec.applyChoice(choices: List<String>, ignoreCase: Boolean) {
    require(choices.isNotEmpty()) { "choice() requires at least one choice" }
    requireDistinctChoices("'$name'", choices, ignoreCase)
    choiceIgnoreCase = ignoreCase
    choiceAbbreviates = true
    (this as? OptionSpec)?.let { spec -> spec.bareValue?.let { spec.requireBareValueInChoices(it, choices) } }
    this.choices = choices
    andThenConvert { raw ->
        // Reads the live field, not this call's [choices], so a later choice()/enum() overwrite retargets it too.
        val current = this.choices!!
        current.firstOrNull { it.equals(raw, ignoreCase = choiceIgnoreCase) }
            ?.let { Result.Success(it) }
            ?: Result.Error(ConversionError.NotOneOf(current))
    }
}

/**
 * Whether a `.convert { }` or `.map { }` to [T] leaves anything but a `String` for a later converter to read; a
 * `String?` leaves the chain open, as `Opt<String?>` still offers every converter.
 */
@PublishedApi
internal inline fun <reified T> changesType(): Boolean = typeOf<T>().classifier != String::class

/** The body behind `.convert { }`, `@PublishedApi` so the inline wrappers that read its result type can call it. */
@PublishedApi
internal fun ValueSpec.applyConvert(
    typeChanging: Boolean,
    transform: (String) -> Result<Any?, ConversionError>,
) {
    andThenConvert(typeChanging, transform)
}

/** The body behind `.map { }`, `@PublishedApi` so the inline wrappers that read its result type can call it. */
@PublishedApi
internal fun ValueSpec.applyMap(
    typeChanging: Boolean,
    transform: (String) -> Any?,
) {
    andThenConvert(typeChanging) { raw ->
        try {
            Result.Success(transform(raw))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.Error(ConversionError.Threw(e))
        }
    }
}

/**
 * The [ConversionError.Domain.error] of a `.validate()`/`.range()` declared before a later converter, which
 * judges the value inside the chain; the refusal is its [ConversionError.Domain.detail].
 */
internal data object CheckRefused : ResultError

/** What [check] says of [value]: null to pass, else the refusal, a check that throws refusing with its message. */
internal fun judge(check: (Any?) -> String?, value: Any): String? = try {
    check(value)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    ConversionError.Threw(e).reason()
}

/**
 * This chain followed by [check] on its value, so a check declared before a later converter still judges the
 * value converted so far; a null value, which binds as absent, is never judged.
 */
private fun ((String) -> Result<Any?, ConversionError>).thenChecked(
    check: ((Any?) -> String?)?,
): (String) -> Result<Any?, ConversionError> {
    check ?: return this
    return { raw ->
        val converted = this(raw)
        val refusal = (converted as? Result.Success)?.value?.let { judge(check, it) }
        if (refusal == null) converted else Result.Error(ConversionError.Domain(CheckRefused, refusal))
    }
}

private fun <T> ValueSpec.applyValidate(message: String, predicate: (T) -> Boolean) {
    val prior = validate
    // A check declared after `.optionalValue()` is typed over the OptionalValue it binds, while it runs on
    // the converted attached value; a bare occurrence is never checked.
    val given = this is OptionSpec && bareMarks
    validate = { value ->
        val judged = if (given) OptionalValue.Given(value) else value
        @Suppress("UNCHECKED_CAST")
        prior?.invoke(value) ?: if (predicate(judged as T)) null else message
    }
}

/**
 * The enum body behind every `.enum<E>()` overload, each entry typed as any of its [words]. `@PublishedApi` (not
 * `private`) because those are `inline`/`reified` and must call it; keeping the reified `enumValues<E>()` in the
 * wrappers and passing [values] in means the inline sites touch only this one member, so [andThenConvert] and
 * [requireDistinctChoices] can both stay `private`.
 */
@PublishedApi
internal fun <E : Enum<E>> ValueSpec.applyEnum(
    enumName: String?,
    values: Array<out E>,
    ignoreCase: Boolean,
    abbreviates: Boolean,
    words: (E) -> List<String>,
) {
    require(values.isNotEmpty()) { "enum $enumName has no constants to choose from" }
    val entries = values.map(words)
    values.zip(entries).forEach { (value, spelled) ->
        require(spelled.isNotEmpty()) { "enum $enumName spells $value with no word" }
    }
    val spellings = entries.flatten()
    spellings.forEach { require(it.isNotBlank()) { "enum $enumName spells a constant as a blank value" } }
    requireDistinctChoices("enum $enumName", spellings, ignoreCase)
    choiceIgnoreCase = ignoreCase
    choiceAbbreviates = abbreviates
    (this as? OptionSpec)?.let { spec -> spec.bareValue?.let { spec.requireBareValueInChoices(it, spellings) } }
    choices = spellings
    choiceEntries = entries
    andThenConvert(true) { raw ->
        values.indices.firstOrNull { i -> entries[i].any { it.equals(raw, ignoreCase = ignoreCase) } }
            ?.let { Result.Success(values[it]) }
            ?: Result.Error(ConversionError.NotOneOf(spellings))
    }
}

/**
 * Everything you can chain onto a declaration: type converters, how many values it takes, validation, and
 * how it looks in help.
 *
 * ```kotlin
 * val port = option(short = 'p', long = "port").int().range(1..65535).default(8080)
 * ```
 *
 * These only compile inside a builder block, so a handle you kept hold of cannot be reshaped after the CLI
 * is built.
 */
@KlapDsl
public abstract class ConverterScope internal constructor() {

    // --- Arg type converters ---

    /**
     * Parses this operand as an `Int`, read as GNU tools read a number (`head -n`, `ls -w`): leading blanks, an
     * optional sign, then ASCII digits, so ` +2` is 2. Anything else, `2 ` and `١٢` included, fails as an invalid
     * value before your action runs.
     */
    public fun Arg<String>.int(): Arg<Int> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotAnInteger) { gnuDecimal(it)?.toIntOrNull() })
        return Arg(spec)
    }

    /** Parses this operand as a `Long`, read as [int] reads an `Int`; anything else fails as an invalid value. */
    public fun Arg<String>.long(): Arg<Long> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotALong) { gnuDecimal(it)?.toLongOrNull() })
        return Arg(spec)
    }

    /**
     * Parses this operand as a `Double`, read as GNU tools read one through `strtod` (`timeout 1.5`,
     * `timeout 0x1p3`): leading blanks, an optional sign, then a decimal or hex float or `inf`/`infinity`/`nan` in
     * any case, with nothing after it. Anything else, `1.5f` and `1 ` included, fails as an invalid value; a
     * number too large for a `Double` is infinity.
     */
    public fun Arg<String>.double(): Arg<Double> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotADouble, ::strtodDouble))
        return Arg(spec)
    }

    /** Parses this operand as `true` or `false`; any other spelling is an invalid value. */
    public fun Arg<String>.boolean(): Arg<Boolean> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotABoolean) { it.toBooleanStrictOrNull() })
        return Arg(spec)
    }

    /**
     * Parses this operand as a [Duration] of zero or more, in Kotlin's format (`PT1H30M`, `1h30m`, `1.5h`,
     * `5h 31m 2s 123ms`, units in decreasing order) or as a bare number of seconds read as [double] reads one, the
     * way GNU `sleep 5.5` does. A negative or infinite duration (`-1h`, `Infinity`, `inf`) and any other text
     * (`1H`, `1 h`, `30m 1h`) fail as an invalid value.
     */
    public fun Arg<String>.duration(): Arg<Duration> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotADuration, ::nonNegativeDuration))
        return Arg(spec)
    }

    /**
     * Parses this operand as an [Instant] through `Instant.parse`: ISO 8601 with seconds and an offset,
     * `2026-01-31T12:00:00Z`, `2026-01-31T14:00:00+02:00` or `+02`, fractions and a lowercase `t` or `z` allowed.
     * A time without seconds or an offset, or with a space for the `T`, fails as an invalid value.
     */
    public fun Arg<String>.instant(): Arg<Instant> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotAnInstant, Instant::parseOrNull))
        return Arg(spec)
    }

    /**
     * Parses this operand as a [Uuid] through `Uuid.parse`: 32 hex digits in either case, dashed as 8-4-4-4-12 or
     * plain. Braces and anything else fail as an invalid value; the value prints dashed in lower case.
     */
    public fun Arg<String>.uuid(): Arg<Uuid> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotAUuid, Uuid::parseOrNull))
        return Arg(spec)
    }

    /**
     * Parses this operand as one of [E]'s entries, each typed as its [spelling] (the lower-cased name unless
     * given) and listed in `--help`. A value matches in its exact case unless [ignoreCase] is set.
     */
    public inline fun <reified E : Enum<E>> Arg<String>.enum(
        ignoreCase: Boolean = false,
        noinline spelling: (E) -> String = { it.name.lowercase() },
    ): Arg<E> {
        spec.applyEnum(E::class.simpleName, enumValues<E>(), ignoreCase, abbreviates = true) { listOf(spelling(it)) }
        return Arg(spec)
    }

    /**
     * Takes one of [choices], in its exact case unless [ignoreCase] is set; the converted value is always the
     * declared spelling.
     */
    public fun Arg<String>.choice(vararg choices: String, ignoreCase: Boolean = false): Arg<String> {
        spec.applyChoice(choices.toList(), ignoreCase)
        return Arg(spec)
    }

    /**
     * Converts the raw string yourself, returning `Ok(value)` or a [ConversionError] to control the error.
     * Name your failure and pair it with the words klap prints,
     * `Err(ConversionError.Domain(NotAPort, "not a port"))`; the error itself arrives intact on
     * [CliError.BadValue.cause] for a `parse` caller to match on.
     *
     * A [T] other than `String` ends the converter chain: a converter called afterwards through a handle kept
     * from before this one fails when the CLI is built. `.convert { Ok(it.trim()) }.choice("a", "b")` still
     * chains. A [transform] that only ever fails needs [T] spelled out, `.convert<Int> { Err(...) }`.
     */
    public inline fun <reified T> Arg<String>.convert(
        noinline transform: (String) -> Result<T, ConversionError>,
    ): Arg<T> {
        spec.applyConvert(changesType<T>(), transform)
        return Arg(spec)
    }

    /**
     * Generic input adapter: reads the raw string and returns a domain type. A thrown exception becomes a
     * clean parse error (`invalid value …`). e.g. `argument("timeout").map { it.toInt().seconds }`.
     * Use [convert] instead when you want to control the error message yourself.
     *
     * A [T] other than `String` ends the converter chain, as [convert]'s does; `.map { it.lowercase() }` still
     * chains.
     */
    public inline fun <reified T> Arg<String>.map(noinline transform: (String) -> T): Arg<T> {
        spec.applyMap(changesType<T>(), transform)
        return Arg(spec)
    }

    // --- Arg cardinality / hints ---

    /** Lets this operand be left out; the accessor widens to nullable and reads null when it is. */
    public fun <T> Arg<T>.optional(): Arg<T?> {
        // Enforces at build time the no-combine invariant the type system cannot express for Arg (unlike Opt).
        require(spec.cardinality !is Cardinality.Multiple) {
            "argument '${spec.name}': .optional() cannot be combined with .multiple()"
        }
        spec.cardinality = Cardinality.Optional
        return Arg(spec)
    }

    /**
     * Common case: [Arg] is already non-null (straight from [CommandBuilder.argument]/a non-nullable
     * converter); switches Required to a default without changing nullability.
     *
     * After `.multiple()`, [value] is the list bound when no operand is given, so
     * `argument("file").multiple().default(listOf("-"))` binds `[-]` with no operands and `[a, b]` with two.
     * Only a `.multiple()` of `min = 0` takes one, since any minimum means an operand is always given.
     */
    public fun <T : Any> Arg<T>.default(value: T): Arg<T> {
        val multiple = spec.cardinality as? Cardinality.Multiple
        spec.cardinality = if (multiple == null) {
            Cardinality.Default(spec.canonicalDefaultOrThrow(value))
        } else {
            // A handle kept from before `.multiple()` still types [value] as one element.
            require(value is List<*>) {
                "argument '${spec.name}': .default() after .multiple() takes a list of values"
            }
            spec.multipleWithDefault(multiple, value)
        }
        return Arg(spec)
    }

    /**
     * The nullable counterpart, for an [Arg] made optional by [optional] or a nullable [map]: the accessor
     * narrows back to non-null, and both an absent operand and a converter that yields null fall back to
     * [value].
     */
    @JvmName("defaultOptionalNarrowing")
    public fun <T : Any> Arg<T?>.default(value: T): Arg<T> {
        // Enforces at build time the no-combine invariant the type system cannot express for Arg (unlike Opt).
        require(spec.cardinality !is Cardinality.Multiple) {
            "argument '${spec.name}': .default() cannot be combined with .multiple()"
        }
        spec.cardinality = Cardinality.Default(spec.canonicalDefaultOrThrow(value))
        return Arg(spec)
    }

    /**
     * Takes every remaining operand rather than one, at least [min] of them and at most [max]. An operand past
     * [max] is [CliError.TooManyArguments], so `multiple(min = 1, max = 2)` refuses a third operand as
     * `diff a b c` refuses its "extra operand 'c'". Unbounded by default, as GNU's operand lists are.
     *
     * @param max at least 1 and at least [min].
     */
    public fun <T> Arg<T>.multiple(min: Int = 0, max: Int = Int.MAX_VALUE): Arg<List<T>> {
        // Enforces at build time the no-combine invariant the type system cannot express for Arg (unlike Opt).
        require(spec.cardinality !is Cardinality.Default) {
            "argument '${spec.name}': put .default(list) after .multiple(), not before it"
        }
        require(spec.cardinality !is Cardinality.Optional) {
            "argument '${spec.name}': .multiple() cannot be combined with .optional()"
        }
        require(max >= 1 && max >= min) {
            "argument '${spec.name}': .multiple(min = $min, max = $max) needs a max of at least 1 and at least min"
        }
        spec.cardinality = Cardinality.Multiple(min, max)
        return Arg(spec)
    }

    /** This argument's value completes filesystem paths in shell completion; the mirror of [Opt.file]. */
    public fun <T> Arg<T>.file(): Arg<T> {
        spec.isPath = true
        return Arg(spec)
    }

    /**
     * Removes this operand slot entirely when [input] is supplied, so the operands after it keep their own
     * positions: `chmod --reference=RFILE FILE...` has no MODE operand at all, and the first FILE must not
     * slide into it.
     *
     * Not the same as `.optional()`, which is the trap this exists to close: an optional slot still EXISTS,
     * so `chmod --reference=r notes.txt` binds `notes.txt` as the mode and silently loses the file. The
     * accessor widens to nullable, since the slot genuinely binds nothing on the lines where it is gone.
     */
    public fun <T> Arg<T>.absentWhen(input: Input): Arg<T?> {
        spec.absentWhen = input.holderSpec()
        return Arg(spec)
    }

    /**
     * Caps this `.multiple()` operand at [n] values while [input] is given, so the operand past them is
     * [CliError.TooManyArguments]: under `cp -T` a SOURCE and a DEST are all there is, and `cp -T a b c` is
     * "extra operand 'c'".
     *
     * ```kotlin
     * val noTarget = flag(short = 'T', long = "no-target-directory")
     * val sources = argument("source").multiple(min = 1).atMostWhen(noTarget, 1)
     * val dest = argument("dest")
     * ```
     *
     * Several caps may be declared, and the smallest one whose input is given holds. [input] must be an input
     * of this command, and a slot that is not `.multiple()` throws when the CLI is built.
     *
     * @param n the most operands the slot takes while [input] is given, zero or more.
     */
    public fun <T> Arg<T>.atMostWhen(input: Input, n: Int): Arg<T> {
        require(n >= 0) { "argument '${spec.name}': .atMostWhen(${input.name}, $n) needs a cap of zero or more" }
        spec.capsWhen += input.holderSpec() to n
        return this
    }

    /**
     * Drops this operand's declared minimum to zero when [input] is supplied: `rm` errors with no operand
     * and `rm -f` exits 0 with none. The slot itself stays, so nothing shifts position; only the count
     * rule relaxes. Only a `.multiple()` operand has a minimum to relax, so this is rejected on any other.
     */
    public fun <T> Arg<T>.optionalWhen(input: Input): Arg<T> {
        spec.optionalWhen = input.holderSpec()
        return this
    }

    // --- Arg validation ---

    /**
     * Checks [predicate] on each value as converted so far: `.validate { it.isNotBlank() }.int()` judges the text,
     * `.int().validate { it > 0 }` the number. Call it before `.multiple()`. Failure renders as CliError.BadValue,
     * never InvalidChoice.
     *
     * Judges the value alone: it runs during conversion, so no sibling input is readable yet. For a rule
     * weighing one input against another, see [CommandBuilder.validateInputs].
     */
    public fun <T> Arg<T>.validate(message: String, predicate: (T) -> Boolean): Arg<T> {
        spec.applyValidate(message, predicate)
        return Arg(spec)
    }

    /** Sugar over [validate] for an integer progression, including stepped and descending ranges. */
    public fun Arg<Int>.range(range: IntProgression): Arg<Int> {
        require(!range.isEmpty()) { "range must not be empty: ${range.first}..${range.last}" }
        spec.valueHint = range.label()
        return validate("must be in ${range.label()}") { it in range }
    }

    /** Resolves the overlap between IntRange, ClosedRange<Int>, and IntProgression. */
    public fun Arg<Int>.range(range: IntRange): Arg<Int> = range(range as IntProgression)

    /** Sugar over [validate] for a bounded value; also adds a "1..65535"-style hint to the help row. */
    public fun <T : Comparable<T>> Arg<T>.range(range: ClosedRange<T>): Arg<T> {
        require(!range.isEmpty()) { "range must not be empty: ${range.start}..${range.endInclusive}" }
        spec.valueHint = "${range.start}..${range.endInclusive}"
        return validate("must be in ${range.start}..${range.endInclusive}") { it in range }
    }

    /**
     * Names this argument's value in help and usage, e.g. `<FILE>` instead of the generic
     * placeholder. Display-only.
     */
    public fun <T> Arg<T>.placeholder(name: String): Arg<T> {
        require(name.isNotBlank()) { "placeholder must not be blank" }
        spec.placeholder = name
        return Arg(spec)
    }

    /**
     * Leaves this argument out of `--help`, completion and the generated docs; it still parses and binds. Intended
     * for an optional or defaulted argument, which a user can leave out without knowing it exists.
     */
    public fun <T> Arg<T>.hidden(): Arg<T> {
        spec.hidden = true
        return Arg(spec)
    }

    /**
     * Lets this operand accept a single-dash token such as `-1m`, which klap otherwise reads as an option.
     *
     * ```kotlin
     * command("seek") {
     *     val position = argument("position", "1-9, or +/-N with a unit").dashLed()
     *     action { Ok(seekTo(position())) }
     * }
     * ```
     *
     * Anything the tree declares still wins: a flag, a short cluster, a long option, an abbreviation,
     * a number input, or a built-in short such as `-V`. Only a token that resolves to none of those reaches this
     * slot, and `--` remains the escape for a value that genuinely collides. Marking one slot does not
     * change how any other slot behaves: a single-dash token that reaches an operand slot without this
     * modifier is still reported as an unknown option, naming the whole word.
     *
     * In exchange, a single-dash **typo** on this command binds here instead of being reported as an
     * unknown option. Long options are unaffected and keep their did-you-mean. Prefer this on a command
     * whose own value error names the grammar it accepts, since that error is what a mistyped short now
     * produces.
     */
    public fun <T> Arg<T>.dashLed(): Arg<T> {
        spec.dashLed = true
        return Arg(spec)
    }

    /**
     * Makes this operand the one value of every single-dash word [accepts] takes, wherever on the line it
     * stands before `--`, as GNU chmod reads `chmod -w -x f` as the mode `-w,-x` and `chmod u+x -w f` as the
     * mode `-w` on the files `u+x` and `f`.
     *
     * ```kotlin
     * val mode = argument("mode").dashLed(gather = ",") { word ->
     *     word.drop(1).all { it in "rwxXstugoa01234567+=," }
     * }
     * val files = argument("file").multiple(min = 1)
     * ```
     *
     * Anything the tree declares still wins, as for [dashLed]: `-R` stays a flag, and only a word that resolves
     * to nothing is offered to [accepts]. A word it refuses is an unknown option, as `chmod -z f` is "invalid
     * option -- 'z'". Once one word is taken this slot takes no plain operand, so every operand goes to the
     * slots after it; with none taken it binds its operand as usual, so `chmod -- -w f` still works. The
     * slot counts as given for a rule such as `atMostOne` whenever a word was taken, even where its
     * `.absentWhen()` trigger removed it, so `chmod -w --reference=r f` is refused.
     *
     * One command has at most one such slot, and it holds one value, not `.multiple()`.
     *
     * @param gather the text joining the words taken, in line order, into the value your converter reads.
     * @param accepts whether a dash word, written whole with its dash (`-w`, `-755`), belongs to this slot.
     *   Called while the line is parsed and on every Tab press, so keep it a pure test of the word.
     */
    public fun <T> Arg<T>.dashLed(gather: String, accepts: (word: String) -> Boolean): Arg<T> {
        spec.gather = DashLedGather(gather, accepts)
        return Arg(spec)
    }

    /**
     * Ends this command's options at the first word bound to this operand, so that word and every word after it
     * reach your action as written, while options before it still permute with the operands as getopt's do.
     * OpenSSH reads `ssh web1 -p 2222` as port 2222, and `ssh web1 ls -p 2222` as the remote command `ls -p 2222`.
     *
     * ```kotlin
     * argument("destination")
     * val command = argument("command").multiple().endsOptions()
     * ```
     *
     * A `--` before that word ends the options as it always does and is not bound. klap's own built-ins and the
     * globals still reach past it, as they do past [CommandBuilder.optionsEndAtFirstOperand], which is this
     * call on the first operand.
     *
     * Which operand reaches this slot must not depend on the rest of the line, so building the command fails
     * when a `.multiple()` operand comes before it, or after it when this slot is the variadic, or when this
     * slot or one before it is removed by `.absentWhen()` or gathers dash words with `dashLed(gather) { }`. One
     * command has at most one such slot, and none beside [CommandBuilder.optionsEndAtFirstOperand].
     */
    public fun <T> Arg<T>.endsOptions(): Arg<T> {
        spec.endsOptions = true
        return Arg(spec)
    }

    /**
     * Supplies tab-completion candidates for this value at the moment the user presses Tab, so they can be
     * anything your program can compute: branch names, task ids, rows from a file.
     *
     * ```kotlin
     * argument("branch").completeWith { branches().forEach { candidate(it) } }
     * ```
     *
     * Call `candidate(value, description)` or `candidates(values)` on the [CompletionScope] receiver.
     *
     * @param filterByPrefix keeps only the candidates starting with the word being typed. Pass `false` to
     *   match some other way, filtering against `current` yourself.
     */
    public fun <T> Arg<T>.completeWith(filterByPrefix: Boolean = true, provider: CompletionScope.() -> Unit): Arg<T> {
        spec.complete = provider
        spec.completePrefixFilter = filterByPrefix
        return Arg(spec)
    }

    // --- Opt converters ---

    /**
     * Parses this option's value as an `Int`, read as GNU tools read a number (`head -n ' 2'`, `ls -w +80`):
     * leading blanks, an optional sign, then ASCII digits. Anything else, `2 ` and `١٢` included, fails as an
     * invalid value.
     */
    public fun Opt<String?>.int(): Opt<Int?> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotAnInteger) { gnuDecimal(it)?.toIntOrNull() })
        return Opt(spec)
    }

    /** Parses this option's value as a `Long`, read as [int] reads an `Int`; anything else is an invalid value. */
    public fun Opt<String?>.long(): Opt<Long?> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotALong) { gnuDecimal(it)?.toLongOrNull() })
        return Opt(spec)
    }

    /** Parses this option's value as a `Double`, read through `strtod` as an operand's is; anything else is invalid. */
    public fun Opt<String?>.double(): Opt<Double?> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotADouble, ::strtodDouble))
        return Opt(spec)
    }

    /** Parses this option's value as a [Duration] of zero or more, read as an operand's [Arg.duration] is. */
    public fun Opt<String?>.duration(): Opt<Duration?> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotADuration, ::nonNegativeDuration))
        return Opt(spec)
    }

    /** Parses this option's value as an [Instant] through `Instant.parse`, read as an operand's [Arg.instant] is. */
    public fun Opt<String?>.instant(): Opt<Instant?> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotAnInstant, Instant::parseOrNull))
        return Opt(spec)
    }

    /** Parses this option's value as a [Uuid] through `Uuid.parse`, read as an operand's [Arg.uuid] is. */
    public fun Opt<String?>.uuid(): Opt<Uuid?> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotAUuid, Uuid::parseOrNull))
        return Opt(spec)
    }

    /**
     * Takes one of [choices], in its exact case unless [ignoreCase] is set (`ls --color=ALWAYS` is refused); the
     * converted value is always the declared spelling.
     */
    public fun Opt<String?>.choice(vararg choices: String, ignoreCase: Boolean = false): Opt<String?> {
        spec.applyChoice(choices.toList(), ignoreCase)
        return Opt(spec)
    }

    /** Parses this option's value as `true` or `false`; any other spelling is an invalid value. */
    public fun Opt<String?>.boolean(): Opt<Boolean?> {
        spec.andThenConvert(true, parsedOr(ConversionError.NotABoolean) { it.toBooleanStrictOrNull() })
        return Opt(spec)
    }

    /**
     * Parses this option's value as one of [E]'s entries, each typed as its [spelling] (the lower-cased name
     * unless given) and listed in `--help`: `.enum<Level>(spelling = { it.cli })`. A value matches in its exact
     * case unless [ignoreCase] is set.
     *
     * @param abbreviates lets a prefix of a value name it where the CLI's [Abbreviation] abbreviates values. Pass
     *   false for a value that must be typed whole in every mode, as rm's `--preserve-root=all`: a prefix such as
     *   `=al` is then an invalid value ("unrecognized --preserve-root argument: 'al'"), while the option's long
     *   still abbreviates and Tab still completes `=a` to `=all`.
     */
    public inline fun <reified E : Enum<E>> Opt<String?>.enum(
        ignoreCase: Boolean = false,
        abbreviates: Boolean = true,
        noinline spelling: (E) -> String = { it.name.lowercase() },
    ): Opt<E?> {
        spec.applyEnum(E::class.simpleName, enumValues<E>(), ignoreCase, abbreviates) { listOf(spelling(it)) }
        return Opt(spec)
    }

    /**
     * Parses this option's value as one of [E]'s entries, each typed as any of its [spellings], as gnulib's
     * argmatch reads rm's `--interactive` (`never`, `no`, `none` / `once` / `always`, `yes`).
     *
     * ```kotlin
     * enum class Interactive(val words: List<String>) {
     *     Never(listOf("never", "no", "none")), Once(listOf("once")), Always(listOf("always", "yes")),
     * }
     * val interactive = option(long = "interactive").enum<Interactive>(spellings = { it.words })
     * // rm --interactive=none a  -> Never
     * // rm --interactive=n a     -> Never, under Abbreviation.Options or All
     * ```
     *
     * Where the CLI's [Abbreviation] abbreviates values, a prefix is accepted when every word it starts is a
     * spelling of one entry, and refused as ambiguous when the words it starts belong to different entries. Tab
     * offers every word in declared order, and an unknown value lists them all. `--help` names each entry by
     * its first word, `--interactive=never|once|always`.
     *
     * @param ignoreCase matches every word in any case.
     * @param abbreviates lets a prefix of a word name its entry where the CLI's [Abbreviation] abbreviates values.
     *   Pass false to accept only whole words in every mode; a prefix is then an invalid value.
     * @param spellings gives each entry's words, its first word the one `--help` shows. Every entry needs at
     *   least one word, and no word may belong to two entries; either mistake throws when the CLI is built.
     */
    @JvmName("enumOfSpellings")
    public inline fun <reified E : Enum<E>> Opt<String?>.enum(
        ignoreCase: Boolean = false,
        abbreviates: Boolean = true,
        noinline spellings: (E) -> List<String>,
    ): Opt<E?> {
        spec.applyEnum(E::class.simpleName, enumValues<E>(), ignoreCase, abbreviates, spellings)
        return Opt(spec)
    }

    /**
     * Matches this option's longs only when spelled in full, whatever the CLI's [Abbreviation]: rm's
     * `--no-preserve-root` ("you may not abbreviate the --no-preserve-root option"). A prefix of one is an
     * unknown option, the other inputs still abbreviate, and Tab still offers the whole word.
     */
    public fun <T> Opt<T>.spelledInFull(): Opt<T> {
        spec.spelledInFull = true
        return this
    }

    /**
     * Converts the raw string yourself, returning `Ok(value)` or a [ConversionError] to control the error.
     * Name your failure and pair it with the words klap prints,
     * `Err(ConversionError.Domain(NotAPort, "not a port"))`; the error itself arrives intact on
     * [CliError.BadValue.cause] for a `parse` caller to match on.
     *
     * A [T] other than `String` ends the converter chain: a converter called afterwards through a handle kept
     * from before this one fails when the CLI is built. `.convert { Ok(it.trim()) }.choice("a", "b")` still
     * chains. A [transform] that only ever fails needs [T] spelled out, `.convert<Int> { Err(...) }`.
     */
    public inline fun <reified T> Opt<String?>.convert(
        noinline transform: (String) -> Result<T, ConversionError>,
    ): Opt<T?> {
        spec.applyConvert(changesType<T>(), transform)
        return Opt(spec)
    }

    /**
     * Generic input adapter for an option: reads the raw string and returns a domain type; a thrown
     * exception becomes a clean parse error. e.g. `option(short = 't', long = "timeout").map { it.toInt().seconds }`.
     *
     * A [T] other than `String` ends the converter chain, as [convert]'s does; `.map { it.lowercase() }` still
     * chains.
     */
    public inline fun <reified T> Opt<String?>.map(noinline transform: (String) -> T): Opt<T?> {
        spec.applyMap(changesType<T>(), transform)
        return Opt(spec)
    }

    /** Makes this option mandatory: leaving it out is a usage error, and the accessor narrows to non-null. */
    public fun <T> Opt<T?>.required(): Opt<T> {
        // Opt's narrowing return types normally block a bad chain (.multiple()/.default() are not declared
        // on the Opt<T> this returns), but aliasing one pre-narrowed Opt<T?> into two cardinality calls
        // bypasses that narrowing, since the original reference's static type never advances. Guard here
        // too, closing the same shared-holder aliasing hole the Arg cardinality guards above close.
        require(spec.cardinality !is Cardinality.Multiple && spec.cardinality !is Cardinality.Default) {
            "option '${spec.name}': .required() cannot be combined with .multiple()/.default()"
        }
        spec.cardinality = Cardinality.Required
        return Opt(spec)
    }

    /**
     * Requires this option only when [flag] was given: `option(long = "token").requiredWhen(remote)` is
     * optional on its own and a usage error alongside `--remote`. The rule reads what your user actually typed.
     *
     * Because it takes the [Flag] handle rather than a condition of your own, `--help` can state the rule as
     * `(required when --remote)` instead of leaving it to be discovered by hitting it.
     *
     * The accessor stays nullable, since the option really does bind null on every line where [flag] is
     * absent.
     */
    public fun <T> Opt<T>.requiredWhen(flag: Flag): Opt<T> {
        require(spec.cardinality !is Cardinality.Required) {
            "option '${spec.name}': .requiredWhen() is pointless on an option that is always .required()"
        }
        spec.requiredWhen = flag.spec
        spec.valueHint = listOfNotNull(spec.valueHint, "required when ${flag.spec.token()}").joinToString("; ")
        return this
    }

    /**
     * Makes this option's value optional: `--color=never` binds `never`, and a bare `--color` binds
     * [whenBare]. `git commit -S[<keyid>]` and `ls --color[=WHEN]` are the shape.
     *
     * **The space form never binds.** `--color auto` binds [whenBare] and leaves `auto` as an operand,
     * which is what getopt_long does with an optional argument and the only unambiguous reading available: an
     * optional-value option cannot tell its own value from the next operand. Reach for `.negatable()` first if
     * the tool's real shape is a two-state switch.
     *
     * [whenBare] is a RAW value: it runs through this option's own converter and validation, so
     * `.optionalValue("0").int()` binds the Int `0`. When a choice set is already declared (`.choice()` or
     * `.enum<E>()`), [whenBare] must be one of them. That is rejected when the CLI is built rather than
     * when someone types it, matched with the choice set's own case rule, and it holds in either
     * declaration order.
     */
    public fun <T> Opt<T>.optionalValue(whenBare: String): Opt<T> {
        require(whenBare.isNotBlank()) {
            "option '${spec.name}': .optionalValue() needs a non-blank value to bind when the option is " +
                    "given bare; a blank one is indistinguishable from the option being absent"
        }
        spec.requireOneBareReading(spec.bareMarks)
        spec.choices?.let { spec.requireBareValueInChoices(whenBare, it) }
        spec.bareValue = whenBare
        return this
    }

    /**
     * Makes this option's value optional, for a bare occurrence that names no value of its own: `cp --backup`
     * takes its control from `VERSION_CONTROL`, and `cp --context` is "like -Z". A bare occurrence binds
     * [OptionalValue.Bare], an attached value binds [OptionalValue.Given], and an absent option binds null.
     *
     * ```kotlin
     * val backup = option(long = "backup").placeholder("CONTROL").optionalValue()
     * // cp --backup a b           -> OptionalValue.Bare
     * // cp --backup=numbered a b  -> OptionalValue.Given("numbered")
     * // cp --backup numbered b    -> OptionalValue.Bare, and `numbered` is an operand
     * ```
     *
     * As with `.optionalValue(whenBare)`, the space form never binds. Declare converters and choices before it:
     * they read the attached value, so `.int().optionalValue()` binds `Given(5)` for `--depth=5`. Chain
     * `.multiple()` after it to keep every occurrence, `[Bare, Given("x")]` for `--context --context=x`. A
     * `.validate { }` after it receives the [OptionalValue.Given] of each attached value. Help shows the option as
     * `--backup[=CONTROL]`, with no note of a bare value.
     */
    public fun <T> Opt<T?>.optionalValue(): Opt<OptionalValue<T>?> {
        spec.requireOneBareReading(spec.bareValue != null)
        spec.bareMarks = true
        return Opt(spec)
    }

    /**
     * Makes this option take its value only glued to the short (`-O3`) or attached to the long with `=`
     * (`--optimise=3`); the option without one is refused as [CliError.MissingOptionValue], and so is
     * `-O 3`, whose `3` stays a word of its own.
     *
     * ```kotlin
     * val level = option(short = 'O', help = "optimisation level").placeholder("LEVEL").gluedValue().int()
     * // find -O3 .   -> 3
     * // find -O .    -> refused: option -O requires a value
     * // find -O 3 .  -> refused the same way
     * ```
     *
     * This is not a getopt form. getopt's required value (`O:`) also takes the next word, `-O 3`, and its optional
     * value (`O::`) takes a bare `-O`; GNU find reads `-O` with its own parser, which refuses both ("The -O option
     * must be immediately followed by a decimal integer"). Reach for it only to match such a tool. The type stays
     * the plain value, so converters, `.multiple()` and `.completeWith { }` chain before or after it, a glued value
     * may end a short cluster as a required value does, and [ShortOptions.Alone] lets it stay glued. Completion
     * offers its candidates glued (`-O0`) and nothing after a bare `-O`, and help shows it as `-OLEVEL`, or
     * `-O, --optimise=LEVEL` when it has a long name. It cannot be combined with `.optionalValue()`.
     */
    public fun <T> Opt<T>.gluedValue(): Opt<T> {
        spec.requireNotGluedAndOptional(spec.valueOptional)
        spec.gluedValue = true
        return this
    }

    /**
     * Gives this option a value for when it is absent, and keeps the accessor non-null. A converter that
     * yields null (a `.map { it.toIntOrNull() }` on bad input, say) falls back to [value] too.
     */
    public fun <T : Any> Opt<T?>.default(value: T): Opt<T> {
        // Same aliasing hole as .required(): a sibling wrapper over the same shared spec may have already
        // set an incompatible cardinality.
        require(spec.cardinality !is Cardinality.Multiple && spec.cardinality !is Cardinality.Required) {
            "option '${spec.name}': .default() cannot be combined with .multiple()/.required()"
        }
        spec.cardinality = Cardinality.Default(spec.canonicalDefaultOrThrow(value))
        return Opt(spec)
    }

    /**
     * Gives this `.multiple()` option the list it binds when it is absent: `option(long = "regexp").multiple()
     * .default(listOf("a"))` binds `[a]` without `--regexp` and `[x]` for `--regexp x`. Only a `.multiple()` of
     * `min = 0` takes one, since any minimum means the option is always given.
     */
    public fun <T> Opt<List<T>>.default(value: List<T>): Opt<List<T>> {
        // An Opt<List<T>> is also what `.map { listOf(it) }.required()` returns.
        val multiple = spec.cardinality as? Cardinality.Multiple
        require(multiple != null) { "option '${spec.name}': this .default() is for a .multiple() option" }
        spec.cardinality = spec.multipleWithDefault(multiple, value)
        return Opt(spec)
    }

    /**
     * Collects every occurrence into a list. min = 0 (default) stays zero-or-more; min >= 1 is
     * enforced in bind as TooFewOccurrences.
     */
    public fun <T> Opt<T?>.multiple(min: Int = 0): Opt<List<T>> {
        // Same aliasing hole again: a sibling wrapper may have already set .required()/.default() on the
        // shared spec before this call.
        require(spec.cardinality !is Cardinality.Default) {
            "option '${spec.name}': put .default(list) after .multiple(), not before it"
        }
        require(spec.cardinality !is Cardinality.Required) {
            "option '${spec.name}': .multiple() cannot be combined with .required()"
        }
        spec.cardinality = Cardinality.Multiple(min)
        return Opt(spec)
    }

    // --- Opt validation ---

    /**
     * Checks [predicate] on the value as converted so far, at its place in the chain as for an operand; failure
     * renders as CliError.BadValue, never InvalidChoice.
     * Judges the value alone; for one weighed against another input, see [CommandBuilder.validateInputs].
     */
    public fun <T> Opt<T?>.validate(message: String, predicate: (T) -> Boolean): Opt<T?> {
        spec.applyValidate(message, predicate)
        return Opt(spec)
    }

    /** Sugar over [validate] for a bounded value; also adds a "1..65535"-style hint to the help row. */
    public fun <T : Comparable<T>> Opt<T?>.range(range: ClosedRange<T>): Opt<T?> {
        require(!range.isEmpty()) { "range must not be empty: ${range.start}..${range.endInclusive}" }
        spec.valueHint = "${range.start}..${range.endInclusive}"
        return validate("must be in ${range.start}..${range.endInclusive}") { it in range }
    }

    /** Sugar over [validate] for an integer progression, including stepped and descending ranges. */
    public fun Opt<Int?>.range(range: IntProgression): Opt<Int?> {
        require(!range.isEmpty()) { "range must not be empty: ${range.first}..${range.last}" }
        spec.valueHint = range.label()
        return validate("must be in ${range.label()}") { it in range }
    }

    /** Resolves the overlap between IntRange, ClosedRange<Int>, and IntProgression. */
    public fun Opt<Int?>.range(range: IntRange): Opt<Int?> = range(range as IntProgression)

    /** Names this option's value in help and usage, e.g. `--output=FILE`. Display-only; overrides a choice list. */
    public fun <T> Opt<T>.placeholder(name: String): Opt<T> {
        require(name.isNotBlank()) { "placeholder must not be blank" }
        spec.placeholder = name
        return Opt(spec)
    }

    /** Leaves this option out of `--help`, completion and the generated docs; it still parses and binds. */
    public fun <T> Opt<T>.hidden(): Opt<T> {
        spec.hidden = true
        return Opt(spec)
    }

    /**
     * The option's VALUE completes filesystem paths in shell completion, e.g. `--file <TAB>`; the
     * mirror of [Arg.file].
     */
    public fun <T> Opt<T>.file(): Opt<T> {
        spec.isPath = true
        return Opt(spec)
    }

    /**
     * The option's counterpart of [Arg.completeWith]: supplies this option's tab-completion candidates when
     * the user presses Tab, computed there and then.
     *
     * @param filterByPrefix keeps only the candidates starting with the word being typed. Pass `false` to
     *   match some other way, filtering against `current` yourself.
     */
    public fun <T> Opt<T>.completeWith(filterByPrefix: Boolean = true, provider: CompletionScope.() -> Unit): Opt<T> {
        spec.complete = provider
        spec.completePrefixFilter = filterByPrefix
        return Opt(spec)
    }

    /**
     * Sets what a second occurrence of this option does, over the CLI's own [CliBuilder.repeatedOptions]:
     * `cp -t d -t e a` refuses its second target directory while `cp -S .x -S .y` takes the last suffix.
     *
     * ```kotlin
     * option(short = 't', long = "target-directory").repeated(RepeatedOptions.Refuse)
     * ```
     *
     * Not for a `.multiple()` option, which collects every occurrence; that throws when the CLI is built.
     */
    public fun <T> Opt<T>.repeated(policy: RepeatedOptions): Opt<T> {
        spec.repeated = policy
        return this
    }

    // --- Flag ---

    /**
     * Counts occurrences instead of collapsing to a boolean: `-vvv`, `-v -v -v`, and
     * `--verbose --verbose` all yield 3.
     */
    public fun Flag.count(): CountFlag {
        require(!spec.negatable) { "flag '${spec.name}': .count() and .negatable() are mutually exclusive" }
        spec.isCount = true
        return CountFlag(spec)
    }

    /**
     * Recognizes a negative half that turns the flag off. With no spellings it is the generated `--no-<long>`, one
     * per long spelling. Absent binds to [default]; last occurrence wins between the positive and negative forms.
     *
     * Given [short] or [long], those are the negative half instead, so a short can turn the flag off (`cp -L`/`-P`,
     * `ssh -A`/`-a`) and an asymmetric pair keeps both real names (`git --paginate`/`--no-pager`). They REPLACE
     * the generated form rather than adding to it, so `--no-<long>` stops being recognized; write it out yourself
     * when you want it kept, as `.negatable(short = 'P', long = "no-dereference")` does. None of them may
     * collide with a declared option/flag spelling or with another flag's negation.
     *
     * [help] describes the negative half in its own words, on a `--help` row of its own and in Tab, as pacman's
     * `--confirm` against `--noconfirm`; without it the negation shares the flag's row and words.
     */
    public fun Flag.negatable(
        short: List<Char> = emptyList(),
        long: List<String> = emptyList(),
        default: Boolean = true,
        help: String? = null,
    ): Flag {
        require(!spec.isCount) { "flag '${spec.name}': .count() and .negatable() are mutually exclusive" }
        val negativeSpellings = spellings(short, long)
        negativeSpellings.forEach { requireValidSpelling("flag negation", it) }
        require(negativeSpellings.none { it in spec.names }) {
            "flag '${spec.name}': negative spelling '${negativeSpellings.first { it in spec.names }}' is " +
                "already one of this flag's own spellings"
        }
        spec.negatable = true
        spec.negativeNames = negativeSpellings
        spec.negativeHelp = help
        spec.cardinality = Cardinality.Default(default)
        return Flag(spec)
    }

    /** A negative half with one short and one long spelling; see the list form for how spellings are read. */
    public fun Flag.negatable(short: Char, long: String, default: Boolean = true, help: String? = null): Flag =
        negatable(listOf(short), listOf(long), default, help)

    /** A negative half with one long spelling and no short; see the list form for how spellings are read. */
    public fun Flag.negatable(long: String, default: Boolean = true, help: String? = null): Flag =
        negatable(emptyList(), listOf(long), default, help)

    /** A negative half with one short spelling and no long; see the list form for how spellings are read. */
    public fun Flag.negatable(short: Char, default: Boolean = true, help: String? = null): Flag =
        negatable(listOf(short), emptyList(), default, help)

    /** A negative half with several shorts and one long; see the list form for how spellings are read. */
    public fun Flag.negatable(short: List<Char>, long: String, default: Boolean = true, help: String? = null): Flag =
        negatable(short, listOf(long), default, help)

    /** A negative half with one short and several longs; see the list form for how spellings are read. */
    public fun Flag.negatable(short: Char, long: List<String>, default: Boolean = true, help: String? = null): Flag =
        negatable(listOf(short), long, default, help)

    /**
     * Sets what a second occurrence of this flag does, over the CLI's own [CliBuilder.repeatedOptions]. Its
     * negative form is the same input, so `--cache --no-cache` repeats it. Not for a `.count()` flag, which
     * counts every occurrence; that throws when the CLI is built.
     */
    public fun Flag.repeated(policy: RepeatedOptions): Flag {
        spec.repeated = policy
        return this
    }

    /**
     * Matches this flag's longs, its negation's included, only when spelled in full, whatever the CLI's
     * [Abbreviation]: rm refuses `--no-p` for `--no-preserve-root`. The other inputs still abbreviate, and Tab
     * still offers the whole word.
     *
     * ```kotlin
     * val noPreserveRoot = flag(long = "no-preserve-root").spelledInFull()
     * // rm --no-preserve-root -r d  -> true
     * // rm --no-p -r d              -> refused: unknown option '--no-p'
     * ```
     *
     * A prefix it shares with another long is still ambiguous, as getopt_long reports it.
     */
    public fun Flag.spelledInFull(): Flag {
        spec.spelledInFull = true
        return this
    }

    /** Leaves this flag out of `--help`, completion and the generated docs; it still parses and binds. */
    public fun Flag.hidden(): Flag {
        spec.hidden = true
        return Flag(spec)
    }
}
