package com.fromwau.klap

import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.OptionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private enum class Color { RED, GREEN }
private enum class CaseCollision { A, a }
private enum class NoConstants

private fun argSpec(): ArgumentSpec = ArgumentSpec("x", "", { Result.Success(it) })

private fun optSpec(): OptionSpec = OptionSpec(listOf("--opt"), "", { Result.Success(it) })

/** Run [block] against a one-holder parse snapshot, so accessors resolve inside it. */
private fun <R> boundTo(spec: HolderSpec, value: Any?, block: ActionScope.() -> R): R =
    ActionScope(mapOf(spec to value)).block()

// Extends ConverterScope: the transformers under test are member-extensions of the build-time scope,
// so the test class IS a scope. Every call site stays a plain `Arg<String>(spec).int()`.
class ConvertersTest : ConverterScope() {

    @Test
    fun int_convertsThroughSharedSpec() {
        val spec = argSpec()
        val typed: Arg<Int> = Arg<String>(spec).int()
        // Same spec object is mutated in place, not replaced.
        assertEquals(spec, typed.spec)
        val converted = spec.convert("42")
        assertEquals(Result.Success(42), converted)
        assertIs<Result.Error<ConversionError>>(spec.convert("nope"))
    }

    @Test
    fun map_adaptsRawStringToDomainType() {
        val spec = optSpec()
        val opt = Opt<String?>(spec).map { it.toInt().seconds }
        assertEquals(Result.Success(5.seconds), spec.convert("5"))
        // A thrown parse exception becomes a clean error, not a crash.
        assertIs<Result.Error<ConversionError>>(spec.convert("abc"))
        boundTo(spec, 5.seconds) { assertEquals(5.seconds, opt()) }
    }

    @Test
    fun read_ofAHolderTheCommandDidNotBind_failsWithAClearMessage() {
        // Reading outside action { } is a compile error now (accessors live on ActionScope), so the one
        // surviving runtime misuse is a foreign accessor (e.g. a sibling command's option closed over
        // by mistake). It must fail fast naming the misuse.
        val foreign = Arg<String>(argSpec())
        val ex = assertFailsWith<IllegalStateException> { boundTo(optSpec(), "v") { foreign() } }
        assertTrue("not bound by the current command" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun read_afterBind_returnsTypedValue() {
        val spec = argSpec()
        val arg = Arg<String>(spec).int()
        boundTo(spec, 42) { assertEquals(42, arg()) }
    }

    @Test
    fun enum_setsChoicesAndConvertsCaseInsensitively() {
        val spec = argSpec()
        val arg = Arg<String>(spec).enum<Color>()
        // Choices display lowercase, but matching stays case-insensitive.
        assertEquals(listOf("red", "green"), spec.choices)
        assertEquals(Result.Success(Color.GREEN), spec.convert("green"))
        assertEquals(Result.Success(Color.GREEN), spec.convert("GREEN"))
        // The case carries the declared choices, so a caller can offer them without re-parsing the message.
        assertEquals(Result.Error(ConversionError.NotOneOf(listOf("red", "green"))), spec.convert("blue"))
        boundTo(spec, Color.RED) { assertEquals(Color.RED, arg()) }
    }

    @Test
    fun arg_enum_rejectsCaseInsensitiveDuplicateAtConstruction() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).enum<CaseCollision>()
        }
        assertTrue("CaseCollision" in ex.message.orEmpty(), ex.message)
        assertTrue("a" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun choice_matchesCaseInsensitivelyAndReturnsCanonicalSpelling() {
        val spec = argSpec()
        val arg = Arg<String>(spec).choice("fast", "slow")
        assertEquals(listOf("fast", "slow"), spec.choices)
        assertEquals(Result.Success("fast"), spec.convert("FAST"))
        assertEquals(Result.Success("fast"), spec.convert("Fast"))
        assertEquals(Result.Success("fast"), spec.convert("fast"))
        assertIs<Result.Error<ConversionError>>(spec.convert("quick"))
        boundTo(spec, "fast") { assertEquals("fast", arg()) }
    }

    @Test
    fun opt_choice_matchesCaseInsensitivelyAndReturnsCanonicalSpelling() {
        val spec = optSpec()
        val opt = Opt<String?>(spec).choice("fast", "slow")
        assertEquals(listOf("fast", "slow"), spec.choices)
        assertEquals(Result.Success("fast"), spec.convert("FAST"))
        assertIs<Result.Error<ConversionError>>(spec.convert("quick"))
        boundTo(spec, "fast") { assertEquals("fast", opt()) }
    }

    @Test
    fun opt_choice_rejectsCaseInsensitiveDuplicateAtConstruction() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).choice("a", "A")
        }
        assertTrue("A" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun opt_choice_rejectsExactDuplicateAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).choice("a", "b", "a")
        }
    }

    @Test
    fun arg_choice_rejectsCaseInsensitiveDuplicateAtConstruction() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).choice("a", "A")
        }
        assertTrue("A" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun arg_choice_rejectsExactDuplicateAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).choice("a", "b", "a")
        }
    }

    @Test
    fun opt_choice_rejectsEmptyChoiceListAtConstruction() {
        // A zero-choice option is permanently unsatisfiable (every input errors "not one of "); reject it at
        // construction like range() rejects an empty range, rather than shipping a degenerate holder.
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).choice()
        }
        assertTrue("at least one" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun arg_choice_rejectsEmptyChoiceListAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).choice()
        }
    }

    @Test
    fun opt_enum_rejectsConstantlessEnumAtConstruction() {
        // An enum with no constants is the enum-shaped twin of an empty choice list: unsatisfiable, so fail
        // loudly at construction and name the enum.
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).enum<NoConstants>()
        }
        assertTrue("NoConstants" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun default_setsDefaultCardinality() {
        val spec = argSpec()
        Arg<String>(spec).int().default(5)
        assertEquals(Cardinality.Default(5), spec.cardinality)
    }

    // --- .default(nonNull) on an optional narrows the accessor back to non-null ---

    @Test
    fun opt_default_nonNull_stillNarrowsAccessorToNonNull() {
        val spec = optSpec()
        val opt: Opt<String> = Opt<String?>(spec).default("d")
        assertEquals(Cardinality.Default("d"), spec.cardinality)
        boundTo(spec, "d") {
            val value: String = opt()
            assertEquals("d", value)
        }
    }

    @Test
    fun arg_optionalDefault_nonNull_stillNarrowsAccessorToNonNull() {
        val spec = argSpec()
        val arg: Arg<String> = Arg<String>(spec).optional().default("d")
        assertEquals(Cardinality.Default("d"), spec.cardinality)
        boundTo(spec, "d") {
            val value: String = arg()
            assertEquals("d", value)
        }
    }

    // --- Arg cardinality setters reject illegal combos at build time (unenforceable by the type system for Arg) ---

    @Test
    fun argMultipleAndDefaultOrOptionalAreRejectedAtBuildTime() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("c") {
                    argument("t").multiple().default(listOf("a"))
                    action { Ok("") }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("c") {
                    argument("t").multiple().optional()
                    action { Ok("") }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("c") {
                    argument("t").default("a").multiple()
                    action { Ok("") }
                }
            }
        }
        // Legal narrowing chain (README: argument(...).optional().default(v)) must still build.
        val cmd = cli("x") {
            command("c") {
                val t = argument("t").optional().default("a")
                action { Ok(t()) }
            }
        }
        assertEquals("c", cmd.subcommand("c")?.name)
    }

    // --- Opt cardinality setters reject illegal combos at build time (aliasing bypasses Opt's own narrowing) ---

    @Test
    fun optRequiredDefaultMultipleAreRejectedAtBuildTime() {
        // Unlike Arg, Opt's cardinality methods each narrow their return type (.required() -> Opt<T>,
        // .multiple() -> Opt<List<T>>), which blocks a bad PROPER chain at compile time. But aliasing the
        // same pre-narrowed Opt<T?> into two calls bypasses that narrowing, since the original reference's
        // type never advances.
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("c") {
                    val o = option("-v")
                    o.required()
                    o.multiple()
                    action { Ok("") }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("c") {
                    val o = option("-v")
                    o.multiple()
                    o.default("d")
                    action { Ok("") }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("c") {
                    val o = option("-v")
                    o.default("d")
                    o.required()
                    action { Ok("") }
                }
            }
        }
        // Ordinary single-cardinality usage is unaffected.
        val cmd = cli("x") {
            command("c") {
                val o = option("-v").int().required()
                action { Ok(o().toString()) }
            }
        }
        assertEquals("c", cmd.subcommand("c")?.name)
    }

    @Test
    fun opt_boolean_converts() {
        val spec = optSpec()
        Opt<String?>(spec).boolean()
        assertEquals(Result.Success(true), spec.convert("true"))
        assertIs<Result.Error<ConversionError>>(spec.convert("yes"))
    }

    @Test
    fun arg_boolean_convertsStrictlyAndRejectsNonBoolean() {
        val spec = argSpec()
        Arg<String>(spec).boolean()
        assertEquals(Result.Success(true), spec.convert("true"))
        assertEquals(Result.Success(false), spec.convert("false"))
        // toBooleanStrictOrNull accepts only exact lowercase "true"/"false".
        assertEquals(Result.Error(ConversionError.NotABoolean), spec.convert("TRUE"))
        assertEquals(Result.Error(ConversionError.NotABoolean), spec.convert("1"))
    }

    @Test
    fun arg_long_convertsToLongAndRejectsOverflowAndDecimal() {
        val spec = argSpec()
        val typed: Arg<Long> = Arg<String>(spec).long()
        assertEquals(spec, typed.spec)
        assertEquals(Result.Success(42L), spec.convert("42"))
        // Overflows Long, and a decimal is not an integer: both fail toLongOrNull.
        assertEquals(Result.Error(ConversionError.NotALong), spec.convert("99999999999999999999"))
        assertEquals(Result.Error(ConversionError.NotALong), spec.convert("1.5"))
    }

    @Test
    fun opt_long_convertsToLongAndRejectsOverflowAndDecimal() {
        val spec = optSpec()
        Opt<String?>(spec).long()
        assertEquals(Result.Success(42L), spec.convert("42"))
        assertEquals(Result.Error(ConversionError.NotALong), spec.convert("99999999999999999999"))
        assertEquals(Result.Error(ConversionError.NotALong), spec.convert("1.5"))
    }

    @Test
    fun arg_double_convertsToDoubleAndRejectsNonNumber() {
        val spec = argSpec()
        val typed: Arg<Double> = Arg<String>(spec).double()
        assertEquals(spec, typed.spec)
        assertEquals(Result.Success(3.14), spec.convert("3.14"))
        assertEquals(Result.Error(ConversionError.NotADouble), spec.convert("x"))
    }

    @Test
    fun opt_double_convertsToDoubleAndRejectsNonNumber() {
        val spec = optSpec()
        Opt<String?>(spec).double()
        assertEquals(Result.Success(3.14), spec.convert("3.14"))
        assertEquals(Result.Error(ConversionError.NotADouble), spec.convert("x"))
    }

    @Test
    fun opt_enum_setsChoicesAndBinds() {
        val spec = optSpec()
        val opt = Opt<String?>(spec).enum<Color>()
        assertEquals(listOf("red", "green"), spec.choices)
        assertEquals(Result.Success(Color.GREEN), spec.convert("green"))
        boundTo(spec, Color.RED) { assertEquals(Color.RED, opt()) }
    }

    @Test
    fun opt_enum_rejectsCaseInsensitiveDuplicateAtConstruction() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).enum<CaseCollision>()
        }
        assertTrue("CaseCollision" in ex.message.orEmpty(), ex.message)
        assertTrue("a" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun enum_matchesAllCaseVariantsToCanonicalConstant() {
        val spec = argSpec()
        Arg<String>(spec).enum<Color>()
        assertEquals(Result.Success(Color.RED), spec.convert("RED"))
        assertEquals(Result.Success(Color.RED), spec.convert("red"))
        assertEquals(Result.Success(Color.RED), spec.convert("Red"))
    }

    @Test
    fun opt_convert_customReason() {
        val spec = optSpec()
        Opt<String?>(spec).convert { s ->
            if (s == "ok") Result.Success(1) else Result.Error(ConversionError.Domain(Rejected, "bad"))
        }
        assertEquals(Result.Success(1), spec.convert("ok"))
        assertIs<Result.Error<ConversionError>>(spec.convert("no"))
    }

    @Test
    fun validate_passesThenFails() {
        val spec = argSpec()
        Arg<String>(spec).validate("must not be blank") { it.isNotBlank() }
        assertEquals(null, spec.validate?.invoke("ok"))
        assertEquals("must not be blank", spec.validate?.invoke(""))
    }

    @Test
    fun validate_composesFirstFailureWins() {
        val spec = optSpec()
        Opt<String?>(spec).int()
            .validate("must be positive") { it > 0 }
            .validate("must be even") { it % 2 == 0 }
        assertEquals("must be even", spec.validate?.invoke(3))
        assertEquals("must be positive", spec.validate?.invoke(-2))
        assertEquals(null, spec.validate?.invoke(4))
    }

    @Test
    fun range_setsHintAndValidatesBoundsOnArg() {
        val spec = argSpec()
        Arg<String>(spec).int().range(1..65535)
        assertEquals("1..65535", spec.valueHint)
        assertEquals(null, spec.validate?.invoke(8080))
        assertEquals("must be in 1..65535", spec.validate?.invoke(0))
    }

    @Test
    fun range_setsHintAndValidatesBoundsOnOpt() {
        val spec = optSpec()
        Opt<String?>(spec).int().range(1..65535)
        assertEquals("1..65535", spec.valueHint)
        assertEquals(null, spec.validate?.invoke(1))
        assertEquals("must be in 1..65535", spec.validate?.invoke(70000))
    }

    @Test
    fun range_rejectsEmptyRangeAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).int().range(5..1)
        }
    }

    @Test
    fun range_normalRangeStillConstructs() {
        val spec = argSpec()
        Arg<String>(spec).int().range(1..10)
        assertEquals("1..10", spec.valueHint)
    }

    // --- Converter composition: chained String-input converters must compose, not overwrite ---

    @Test
    fun choice_then_map_composesInsteadOfOverwritingAndKeepsChoiceValidation() {
        val spec = argSpec()
        Arg<String>(spec).choice("a", "b").map { it.uppercase() }
        assertEquals(Result.Success("A"), spec.convert("a"))
        // Case-insensitive choice still applies before the map stage runs.
        assertEquals(Result.Success("A"), spec.convert("A"))
        // Choice validation must not be bypassed by the later .map().
        assertIs<Result.Error<ConversionError>>(spec.convert("z"))
    }

    @Test
    fun map_then_map_composesBothStagesInOrder() {
        val spec = argSpec()
        Arg<String>(spec).map { it.trim() }.map { it.length }
        // Both stages must run, in order: trim first, then length of the trimmed string.
        assertEquals(Result.Success(2), spec.convert("  hi  "))
    }

    @Test
    fun choice_then_int_enforcesChoiceBeforeIntConversion() {
        val spec = argSpec()
        Arg<String>(spec).choice("1", "2").int()
        assertEquals(Result.Success(1), spec.convert("1"))
        // Not one of the choices: rejected before int-parsing ever runs.
        assertIs<Result.Error<ConversionError>>(spec.convert("9"))
    }

    @Test
    fun convert_then_map_composesBothStages() {
        val spec = argSpec()
        Arg<String>(spec).convert { Result.Success(it.trim()) }.map { it.length }
        assertEquals(Result.Success(2), spec.convert("  hi  "))
    }

    // --- Converter ordering: .validate()/.range() must be called after every type-changing converter,
    // and a type-changing converter cannot be stacked on top of another one, even through a stale handle ---

    @Test
    fun validate_beforeATypeChangingConverter_isRejectedAtConstruction() {
        val spec = argSpec()
        Arg<String>(spec).validate("must not be blank") { it.isNotBlank() }
        val ex = assertFailsWith<IllegalArgumentException> { Arg<String>(spec).int() }
        assertEquals(
            "argument 'x': call .validate()/.range() after every type-changing converter " +
                "(.int()/.long()/.double()/.boolean()/.enum<E>()), not before",
            ex.message,
        )
    }

    @Test
    fun range_beforeATypeChangingConverter_isRejectedAtConstruction() {
        val spec = argSpec()
        Arg<String>(spec).range("a".."z")
        val ex = assertFailsWith<IllegalArgumentException> { Arg<String>(spec).int() }
        assertEquals(
            "argument 'x': call .validate()/.range() after every type-changing converter " +
                "(.int()/.long()/.double()/.boolean()/.enum<E>()), not before",
            ex.message,
        )
    }

    @Test
    fun aliasedHandle_secondTypeChangingConverter_isRejectedAtConstruction() {
        // Keeping the Arg<String> handle alive past .int() and reusing it still statically offers .long(),
        // since the handle's own type never advanced; the shared spec must reject the second call instead.
        val spec = argSpec()
        val a = Arg<String>(spec)
        a.int()
        val ex = assertFailsWith<IllegalArgumentException> { a.long() }
        assertEquals(
            "argument 'x': cannot add another converter after a type-changing converter " +
                "(.int()/.long()/.double()/.boolean()/.enum<E>()) has already run; this usually means a " +
                "converter was called through an Arg<String>/Opt<String?> handle kept alive past that point",
            ex.message,
        )
    }

    @Test
    fun int_then_range_safeOrderConvertsAndValidatesEndToEnd() {
        val spec = argSpec()
        val arg = Arg<String>(spec).int().range(1..100)
        assertEquals(Result.Success(42), spec.convert("42"))
        assertEquals(null, spec.validate?.invoke(50))
        assertEquals("must be in 1..100", spec.validate?.invoke(200))
        boundTo(spec, 50) { assertEquals(50, arg()) }
    }

    @Test
    fun map_then_map_isNotTreatedAsATypeChangingStack() {
        // Neither .map() call sets the type-changing marker, so the guard above must not fire here even
        // though the two stack, proving it targets only .int()/.long()/.double()/.boolean()/.enum<E>().
        val spec = argSpec()
        Arg<String>(spec).map { it.trim() }.map { it.length }
        assertEquals(Result.Success(2), spec.convert("  hi  "))
    }

    @Test
    fun choice_then_map_isNotTreatedAsATypeChangingStack() {
        val spec = argSpec()
        Arg<String>(spec).choice("a", "b").map { it.uppercase() }
        assertEquals(Result.Success("A"), spec.convert("a"))
    }

    @Test
    fun opt_choice_then_map_composesInsteadOfOverwritingAndKeepsChoiceValidation() {
        val spec = optSpec()
        Opt<String?>(spec).choice("a", "b").map { it.uppercase() }
        assertEquals(Result.Success("A"), spec.convert("a"))
        assertIs<Result.Error<ConversionError>>(spec.convert("z"))
    }

    @Test
    fun optionalValue_recordsTheBareValueOnTheSpec() {
        val spec = optSpec()
        Opt<String?>(spec).optionalValue("always")
        assertEquals("always", spec.bareValue)
    }

    @Test
    fun optionalValue_leavesTheSpecUnchangedByDefault() {
        assertNull(optSpec().bareValue)
    }

    @Test
    fun optionalValue_returnsTheSameHolderSoTheChainContinues() {
        val spec = optSpec()
        val typed: Opt<Int?> = Opt<String?>(spec).optionalValue("0").int()
        assertEquals(spec, typed.spec)
        // The bare value is a RAW string: it goes through the converter like any other occurrence, so a
        // later .int() applies to it too.
        assertEquals(Result.Success(0), spec.convert("0"))
    }

    @Test
    fun optionalValue_rejectsCombiningWithMultiple() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).multiple().optionalValue("x")
        }
        assertTrue("multiple" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun multiple_rejectsCombiningWithOptionalValue() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).optionalValue("x").multiple()
        }
        assertTrue("optionalValue" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun optionalValue_rejectsABlankBareValue() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).optionalValue("  ")
        }
        assertTrue("blank" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun optionalValue_rejectsABareValueNotInAnAlreadyDeclaredChoiceSet() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).choice("a", "b").optionalValue("c")
        }
        assertTrue("c" in ex.message.orEmpty(), ex.message)
        assertTrue("a, b" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun choice_rejectsAChoiceSetExcludingAnAlreadyDeclaredBareValue() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).optionalValue("c").choice("a", "b")
        }
        assertTrue("c" in ex.message.orEmpty(), ex.message)
        assertTrue("a, b" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun optionalValue_acceptsABareValueMatchingAChoiceCaseInsensitively() {
        val spec = optSpec()
        Opt<String?>(spec).choice("Fast", "Slow").optionalValue("fast")
        assertEquals("fast", spec.bareValue)
        // The guard does not over-reject: the mismatched case is legal, same as the parser's own matching.
        assertEquals(Result.Success("Fast"), spec.convert("fast"))
    }

    @Test
    fun optionalValue_rejectsABareValueNotInAnAlreadyDeclaredEnumSet() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).enum<Color>().optionalValue("blue")
        }
        assertTrue("blue" in ex.message.orEmpty(), ex.message)
        assertTrue("red, green" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun enum_rejectsAnEnumExcludingAnAlreadyDeclaredBareValue() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).optionalValue("blue").enum<Color>()
        }
        assertTrue("blue" in ex.message.orEmpty(), ex.message)
        assertTrue("red, green" in ex.message.orEmpty(), ex.message)
    }

    // --- .default() validates against an already-declared choice set, matching the reverse declaration
    // order (.default() then .choice()), which andThenConvert's own default re-validation already covers ---

    @Test
    fun choice_then_default_rejectsAnOutOfSetDefaultAtConstruction() {
        val spec = argSpec()
        val ex = assertFailsWith<IllegalArgumentException> {
            Arg<String>(spec).choice("fast", "slow").default("bogus")
        }
        assertEquals("argument 'x': default value 'bogus' is invalid: not one of fast, slow", ex.message)
    }

    @Test
    fun default_then_choice_rejectsAnOutOfSetDefaultAtConstruction_sameMessageShape() {
        // The reverse declaration order already worked before this fix, via andThenConvert's own default
        // re-validation; asserted here so both orders are pinned to the identical message shape.
        val spec = argSpec()
        val ex = assertFailsWith<IllegalArgumentException> {
            Arg<String>(spec).default("bogus").choice("fast", "slow")
        }
        assertEquals("argument 'x': default value 'bogus' is invalid: not one of fast, slow", ex.message)
    }

    @Test
    fun choice_then_default_canonicalizesACaseDifferingValidDefaultAtConstruction() {
        val spec = argSpec()
        Arg<String>(spec).choice("fast", "slow").default("FAST")
        // The guard does not over-reject: the mismatched case is legal, same as the parser's own matching,
        // and the stored default is canonicalized so an absent argument binds the same value a typed
        // "FAST"/"fast"/"Fast" would have.
        assertEquals(Cardinality.Default("fast"), spec.cardinality)
    }

    @Test
    fun opt_choice_then_default_rejectsAnOutOfSetDefaultAtConstruction() {
        val spec = optSpec()
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(spec).choice("fast", "slow").default("bogus")
        }
        assertEquals("option '--opt': default value 'bogus' is invalid: not one of fast, slow", ex.message)
    }
}

/** A converter failure with no payload beyond the fact that it happened. */
private data object Rejected : IError
