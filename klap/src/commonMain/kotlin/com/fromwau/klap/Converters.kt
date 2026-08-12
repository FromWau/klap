package com.fromwau.klap

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.klap.internal.render.reason
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.ValueSpec
import com.fromwau.klap.internal.spec.requireValidSpelling
import com.fromwau.klap.internal.spec.token
import kotlin.coroutines.cancellation.CancellationException
import kotlin.jvm.JvmName

private fun numeric(error: ConversionError, parse: (String) -> Any?): (String) -> Result<Any?, ConversionError> =
    { raw -> parse(raw)?.let { Result.Success(it) } ?: Result.Error(error) }

/**
 * Wraps a composed [ValueSpec.convert] whose newest stage is type-changing (`.int()`, `.long()`,
 * `.double()`, `.boolean()`, `.enum<E>()`): its success value is no longer a `String`, so
 * [andThenConvert]'s inner cast would break if another converter composed on top of it. `.choice()`,
 * `.map { }`, and `.convert { }` stay unmarked, since stacking those is legal however often they chain.
 */
private class TypeChangedConverter(private val delegate: (String) -> Result<Any?, ConversionError>) :
    (String) -> Result<Any?, ConversionError> {
    override fun invoke(raw: String): Result<Any?, ConversionError> = delegate(raw)
}

/**
 * Composes [next] after whatever converter is already on this spec, instead of overwriting it, so
 * chained String-input converters (`.choice().map { }`, `.map { }.map { }`, `.choice().int()`, ...)
 * stack. The inner cast to `String` holds only while every prior stage still yields one, so a
 * [typeChanging] stage closes the spec to further converters — which is what catches a second call made
 * through a leaked `Arg<String>`/`Opt<String?>` handle, whose static type never advances to reveal it.
 */
private fun ValueSpec.andThenConvert(typeChanging: Boolean = false, next: (String) -> Result<Any?, ConversionError>) {
    val label = if (this is OptionSpec) "option" else "argument"
    require(convert !is TypeChangedConverter) {
        "$label '$name': cannot add another converter after a type-changing converter " +
            "(.int()/.long()/.double()/.boolean()/.enum<E>()) has already run; this usually means a " +
            "converter was called through an Arg<String>/Opt<String?> handle kept alive past that point"
    }
    require(!(typeChanging && validate != null)) {
        "$label '$name': call .validate()/.range() after every type-changing converter " +
            "(.int()/.long()/.double()/.boolean()/.enum<E>()), not before"
    }
    val prior = convert
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
 * Matching is case-insensitive, so two choices equal ignoring case (including exact duplicates)
 * would leave the later one permanently unreachable while `--help` still advertises both. Fail
 * loudly at construction instead.
 */
private fun requireNoCaseInsensitiveDuplicateChoices(choices: List<String>) {
    val seen = mutableSetOf<String>()
    for (choice in choices) {
        require(seen.add(choice.lowercase())) {
            "duplicate choice '$choice' (choices must be distinct, ignoring case)"
        }
    }
}

/**
 * `.enum<E>()` matches names case-insensitively, so two constants equal ignoring case would leave
 * the later one permanently unreachable while `--help` still advertises both. Fail loudly at
 * construction instead, naming the enum and both colliding constants.
 */
private fun requireNoCaseInsensitiveDuplicateEnumNames(enumName: String?, names: List<String>) {
    val seen = mutableMapOf<String, String>()
    for (name in names) {
        val key = name.lowercase()
        val previous = seen[key]
        require(previous == null) {
            "enum $enumName has case-colliding constant names '$previous' and '$name' (constant names " +
                "must be distinct, ignoring case)"
        }
        seen[key] = name
    }
}

/**
 * A declared [bareValue] runs through the option's own converter at parse time exactly like a typed value,
 * so a choice set that excludes it would only fail once a user typed the bare form — blaming them for a
 * value the AUTHOR wrote on a line `--help` renders as legal. Checked at construction instead, in both
 * declaration orders (`applyChoice`/`applyEnum` call this too), matching [choices] case-insensitively so a
 * value the parser would accept is never rejected here.
 */
private fun requireBareValueInChoices(name: String, bareValue: String, choices: List<String>) {
    require(choices.any { it.equals(bareValue, ignoreCase = true) }) {
        "option '$name': the bare value '$bareValue' from .optionalValue() is not one of ${choices.joinToString(", ")}"
    }
}

/**
 * A `.default()` outside [ValueSpec.choices] would be a value `--help` advertises but no input can produce.
 * [andThenConvert] already covers the `.default()`-then-`.choice()` order; this covers the reverse, matching
 * case-insensitively and storing the declared spelling so an absent input binds what a matching one would.
 * A default that is no longer a raw `String` never reached [choices], so it passes through unchecked.
 */
private fun ValueSpec.canonicalDefaultOrThrow(value: Any?): Any? {
    val current = choices ?: return value
    if (value !is String) return value
    val label = if (this is OptionSpec) "option" else "argument"
    val canonical = current.firstOrNull { it.equals(value, ignoreCase = true) }
    require(canonical != null) {
        "$label '$name': default value '$value' is invalid: not one of ${current.joinToString(", ")}"
    }
    return canonical
}

// Shared bodies behind the mirrored Arg/Opt converter pairs (which differ only in their typed wrapper).

private fun ValueSpec.applyChoice(choices: List<String>) {
    require(choices.isNotEmpty()) { "choice() requires at least one choice" }
    requireNoCaseInsensitiveDuplicateChoices(choices)
    (this as? OptionSpec)?.bareValue?.let { requireBareValueInChoices(name, it, choices) }
    this.choices = choices
    andThenConvert { raw ->
        // Reads the live field, not this call's [choices], so a later choice()/enum() overwrite retargets it too.
        val current = this.choices!!
        current.firstOrNull { it.equals(raw, ignoreCase = true) }
            ?.let { Result.Success(it) }
            ?: Result.Error(ConversionError.NotOneOf(current))
    }
}

private fun ValueSpec.applyMap(transform: (String) -> Any?) {
    andThenConvert { raw ->
        try {
            Result.Success(transform(raw))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.Error(ConversionError.Threw(e))
        }
    }
}

private fun <T> ValueSpec.applyValidate(message: String, predicate: (T) -> Boolean) {
    val prior = validate
    validate = { value ->
        @Suppress("UNCHECKED_CAST")
        prior?.invoke(value) ?: if (predicate(value as T)) null else message
    }
}

/**
 * The enum body behind both `.enum<E>()` overloads. `@PublishedApi` (not `private`) because those are
 * `inline`/`reified` and must call it; keeping the reified `enumValues<E>()` in the wrappers and passing
 * [values] in means the inline sites touch only this one member, so [andThenConvert] and
 * [requireNoCaseInsensitiveDuplicateEnumNames] can both stay `private`.
 */
@PublishedApi
internal fun <E : Enum<E>> ValueSpec.applyEnum(enumName: String?, values: Array<out E>) {
    require(values.isNotEmpty()) { "enum $enumName has no constants to choose from" }
    requireNoCaseInsensitiveDuplicateEnumNames(enumName, values.map { it.name })
    // Display choices CLI-style (lowercase); matching stays case-insensitive, same as .choice().
    val displayNames = values.map { it.name.lowercase() }
    (this as? OptionSpec)?.bareValue?.let { requireBareValueInChoices(name, it, displayNames) }
    choices = displayNames
    andThenConvert(true) { raw ->
        values.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?.let { Result.Success(it) }
            ?: Result.Error(ConversionError.NotOneOf(values.map { it.name.lowercase() }))
    }
}

/**
 * Everything you can chain onto a declaration: type converters, how many values it takes, validation, and
 * how it looks in help.
 *
 * ```kotlin
 * val port = option("--port", "-p").int().range(1..65535).default(8080)
 * ```
 *
 * These only compile inside a builder block, so a handle you kept hold of cannot be reshaped after the CLI
 * is built.
 */
@KlapDsl
public abstract class ConverterScope internal constructor() {

    // --- Arg type converters ---

    /** Parses this operand as an `Int`; anything else fails as an invalid value before your action runs. */
    public fun Arg<String>.int(): Arg<Int> {
        spec.andThenConvert(true, numeric(ConversionError.NotAnInteger) { it.toIntOrNull() })
        return Arg(spec)
    }

    /** Parses this operand as a `Long`; anything else fails as an invalid value before your action runs. */
    public fun Arg<String>.long(): Arg<Long> {
        spec.andThenConvert(true, numeric(ConversionError.NotALong) { it.toLongOrNull() })
        return Arg(spec)
    }

    /** Parses this operand as a `Double`; anything else fails as an invalid value before your action runs. */
    public fun Arg<String>.double(): Arg<Double> {
        spec.andThenConvert(true, numeric(ConversionError.NotADouble) { it.toDoubleOrNull() })
        return Arg(spec)
    }

    /** Parses this operand as `true` or `false`; any other spelling is an invalid value. */
    public fun Arg<String>.boolean(): Arg<Boolean> {
        spec.andThenConvert(true, numeric(ConversionError.NotABoolean) { it.toBooleanStrictOrNull() })
        return Arg(spec)
    }

    /** Parses this operand as one of [E]'s entries, matched case-insensitively and listed in `--help`. */
    public inline fun <reified E : Enum<E>> Arg<String>.enum(): Arg<E> {
        spec.applyEnum(E::class.simpleName, enumValues<E>())
        return Arg(spec)
    }

    /** Matches [choices] case-insensitively; the converted value is always the declared (canonical) spelling. */
    public fun Arg<String>.choice(vararg choices: String): Arg<String> {
        spec.applyChoice(choices.toList())
        return Arg(spec)
    }

    /**
     * Converts the raw string yourself, returning `Ok(value)` or a [ConversionError] to control the error.
     * Name your failure and pair it with the words klap prints,
     * `Err(ConversionError.Domain(NotAPort, "not a port"))`; the error itself arrives intact on
     * [CliError.BadValue.cause] for a `parse` caller to match on.
     */
    public fun <T> Arg<String>.convert(transform: (String) -> Result<T, ConversionError>): Arg<T> {
        spec.andThenConvert(next = transform)
        return Arg(spec)
    }

    /**
     * Generic input adapter: reads the raw string and returns a domain type. A thrown exception becomes a
     * clean parse error (`invalid value …`). e.g. `argument("timeout").map { it.toInt().seconds }`.
     * Use [convert] instead when you want to control the error message yourself.
     */
    public fun <T> Arg<String>.map(transform: (String) -> T): Arg<T> {
        spec.applyMap(transform)
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
     */
    public fun <T : Any> Arg<T>.default(value: T): Arg<T> {
        // Enforces at build time the no-combine invariant the type system cannot express for Arg (unlike Opt).
        require(spec.cardinality !is Cardinality.Multiple) {
            "argument '${spec.name}': .default() cannot be combined with .multiple()"
        }
        spec.cardinality = Cardinality.Default(spec.canonicalDefaultOrThrow(value))
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

    /** Takes every remaining operand rather than one, requiring at least [min] of them. */
    public fun <T> Arg<T>.multiple(min: Int = 0): Arg<List<T>> {
        // Enforces at build time the no-combine invariant the type system cannot express for Arg (unlike Opt).
        require(spec.cardinality !is Cardinality.Default && spec.cardinality !is Cardinality.Optional) {
            "argument '${spec.name}': .multiple() cannot be combined with .default()/.optional()"
        }
        spec.cardinality = Cardinality.Multiple(min)
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
     * Drops this operand's declared minimum to zero when [input] is supplied: `rm` errors with no operand
     * and `rm -f` exits 0 with none. The slot itself stays, so nothing shifts position; only the count
     * rule relaxes. Only a `.multiple()` operand has a minimum to relax, so this is rejected on any other.
     */
    public fun <T> Arg<T>.requiredUnless(input: Input): Arg<T> {
        spec.relaxedWhen = input.holderSpec()
        return this
    }

    // --- Arg validation ---

    /**
     * Checks [predicate] on each converted value; call before `.multiple()`. Failure renders as
     * CliError.BadValue, never InvalidChoice.
     */
    public fun <T> Arg<T>.validate(message: String, predicate: (T) -> Boolean): Arg<T> {
        spec.applyValidate(message, predicate)
        return Arg(spec)
    }

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

    /** Omit this argument from `--help`; it still parses and binds normally. Intended for optional/default args. */
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
     * Anything the tree declares still wins: a flag, a short cluster, a long option, an abbreviation, a
     * `numericAlias`, or a built-in like `-h`. Only a token that resolves to none of those reaches this
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

    /** Parses this option's value as an `Int`; anything else fails as an invalid value. */
    public fun Opt<String?>.int(): Opt<Int?> {
        spec.andThenConvert(true, numeric(ConversionError.NotAnInteger) { it.toIntOrNull() })
        return Opt(spec)
    }

    /** Parses this option's value as a `Long`; anything else fails as an invalid value. */
    public fun Opt<String?>.long(): Opt<Long?> {
        spec.andThenConvert(true, numeric(ConversionError.NotALong) { it.toLongOrNull() })
        return Opt(spec)
    }

    /** Parses this option's value as a `Double`; anything else fails as an invalid value. */
    public fun Opt<String?>.double(): Opt<Double?> {
        spec.andThenConvert(true, numeric(ConversionError.NotADouble) { it.toDoubleOrNull() })
        return Opt(spec)
    }

    /** Matches [choices] case-insensitively; the converted value is always the declared (canonical) spelling. */
    public fun Opt<String?>.choice(vararg choices: String): Opt<String?> {
        spec.applyChoice(choices.toList())
        return Opt(spec)
    }

    /** Parses this option's value as `true` or `false`; any other spelling is an invalid value. */
    public fun Opt<String?>.boolean(): Opt<Boolean?> {
        spec.andThenConvert(true, numeric(ConversionError.NotABoolean) { it.toBooleanStrictOrNull() })
        return Opt(spec)
    }

    /** Parses this option's value as one of [E]'s entries, matched case-insensitively and listed in `--help`. */
    public inline fun <reified E : Enum<E>> Opt<String?>.enum(): Opt<E?> {
        spec.applyEnum(E::class.simpleName, enumValues<E>())
        return Opt(spec)
    }

    /**
     * Converts the raw string yourself, returning `Ok(value)` or a [ConversionError] to control the error.
     * Name your failure and pair it with the words klap prints,
     * `Err(ConversionError.Domain(NotAPort, "not a port"))`; the error itself arrives intact on
     * [CliError.BadValue.cause] for a `parse` caller to match on.
     */
    public fun <T> Opt<String?>.convert(transform: (String) -> Result<T, ConversionError>): Opt<T?> {
        spec.andThenConvert(next = transform)
        return Opt(spec)
    }

    /**
     * Generic input adapter for an option: reads the raw string and returns a domain type; a thrown
     * exception becomes a clean parse error. e.g. `option("--timeout", "-t").map { it.toInt().seconds }`.
     */
    public fun <T> Opt<String?>.map(transform: (String) -> T): Opt<T?> {
        spec.applyMap(transform)
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
     * Requires this option only when [flag] was given: `option("--token").requiredIf(remote)` is optional on
     * its own and a usage error alongside `--remote`. The rule reads what your user actually typed.
     *
     * Because it takes the [Flag] handle rather than a condition of your own, `--help` can state the rule as
     * `(required when --remote)` instead of leaving it to be discovered by hitting it.
     *
     * The accessor stays nullable, since the option really does bind null on every line where [flag] is
     * absent.
     */
    public fun <T> Opt<T>.requiredIf(flag: Flag): Opt<T> {
        require(spec.cardinality !is Cardinality.Required) {
            "option '${spec.name}': .requiredIf() is pointless on an option that is always .required()"
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
     * which is what GNU does and the only unambiguous reading available — an optional-value option cannot
     * tell its own value from the next operand, which is exactly why POSIX.1 XBD 12.2 guideline 7 says
     * option-arguments should not be optional. Declaring one takes this option outside that guideline
     * knowingly; every option that does not call this stays conforming. Reach for `.negatable()` first if
     * the tool's real shape is a two-state switch — it costs no conformance.
     *
     * [whenBare] is a RAW value: it runs through this option's own converter and validation, so
     * `.optionalValue("0").int()` binds the Int `0`. When a choice set is already declared (`.choice()` or
     * `.enum<E>()`), [whenBare] must be one of them. That is rejected when the CLI is built rather than
     * when someone types it, matched case-insensitively like the choice set itself, and it holds in either
     * declaration order.
     */
    public fun <T> Opt<T>.optionalValue(whenBare: String): Opt<T> {
        require(whenBare.isNotBlank()) {
            "option '${spec.name}': .optionalValue() needs a non-blank value to bind when the option is " +
                    "given bare; a blank one is indistinguishable from the option being absent"
        }
        require(spec.cardinality !is Cardinality.Multiple) {
            "option '${spec.name}': .optionalValue() cannot be combined with .multiple()"
        }
        spec.choices?.let { requireBareValueInChoices(spec.name, whenBare, it) }
        spec.bareValue = whenBare
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
     * Collects every occurrence into a list. min = 0 (default) stays zero-or-more; min >= 1 is
     * enforced in bind as TooFewOccurrences.
     */
    public fun <T> Opt<T?>.multiple(min: Int = 0): Opt<List<T>> {
        // Same aliasing hole again: a sibling wrapper may have already set .required()/.default() on the
        // shared spec before this call.
        require(spec.cardinality !is Cardinality.Default && spec.cardinality !is Cardinality.Required) {
            "option '${spec.name}': .multiple() cannot be combined with .default()/.required()"
        }
        require(spec.bareValue == null) {
            "option '${spec.name}': .multiple() cannot be combined with .optionalValue()"
        }
        spec.cardinality = Cardinality.Multiple(min)
        return Opt(spec)
    }

    // --- Opt validation ---

    /** Checks [predicate] on the converted value; failure renders as CliError.BadValue, never InvalidChoice. */
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

    /** Names this option's value in help and usage, e.g. `--output <FILE>`. Display-only; overrides a choice list. */
    public fun <T> Opt<T>.placeholder(name: String): Opt<T> {
        require(name.isNotBlank()) { "placeholder must not be blank" }
        spec.placeholder = name
        return Opt(spec)
    }

    /** Omit this option from `--help`; it still parses and binds normally. */
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
     * Recognizes an auto-generated `--no-<long>` counterpart, one per long spelling. Absent binds to
     * [default]; last occurrence wins between the positive and negative forms.
     */
    public fun Flag.negatable(default: Boolean = true): Flag {
        require(!spec.isCount) { "flag '${spec.name}': .count() and .negatable() are mutually exclusive" }
        spec.negatable = true
        spec.cardinality = Cardinality.Default(default)
        return Flag(spec)
    }

    /**
     * Recognizes [negativeSpellings] as the negative half instead of the generated `--no-<long>`, so a
     * short can turn the flag off (`cp -L`/`-P`, `ssh -A`/`-a`) and an asymmetric pair keeps both real
     * names (`git --paginate`/`--no-pager`).
     *
     * The list REPLACES the generated form rather than adding to it, so `--no-<long>` stops being
     * recognized. Write it out yourself when you want it kept, as `.negatable("--no-dereference", "-P")`
     * does.
     *
     * Each spelling carries its own dashes and is validated exactly like a positive one, and none of them
     * may collide with a declared option/flag spelling or with another flag's negation.
     */
    public fun Flag.negatable(vararg negativeSpellings: String, default: Boolean = true): Flag {
        require(!spec.isCount) { "flag '${spec.name}': .count() and .negatable() are mutually exclusive" }
        require(negativeSpellings.isNotEmpty()) {
            "flag '${spec.name}': .negatable() with an empty spelling list says nothing; call .negatable() " +
                "with no arguments for the generated '--no-...' form"
        }
        negativeSpellings.forEach { requireValidSpelling("flag negation", it) }
        require(negativeSpellings.none { it in spec.names }) {
            "flag '${spec.name}': negative spelling '${negativeSpellings.first { it in spec.names }}' is " +
                "already one of this flag's own spellings"
        }
        spec.negatable = true
        spec.negativeNames = negativeSpellings.toList()
        spec.cardinality = Cardinality.Default(default)
        return Flag(spec)
    }

    /** Omit this flag from `--help`; it still parses and binds normally. */
    public fun Flag.hidden(): Flag {
        spec.hidden = true
        return Flag(spec)
    }
}
