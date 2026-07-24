package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

private enum class Color { RED, GREEN }

private fun argSpec(): HolderSpec =
    HolderSpec("x", null, "", InputKind.ARGUMENT, { Result.Success(it) }, Cardinality.Required, null, false)

private fun optSpec(): HolderSpec =
    HolderSpec("opt", null, "", InputKind.OPTION, { Result.Success(it) }, Cardinality.Optional, null, false)

class ConvertersTest {

    @Test
    fun int_convertsThroughSharedSpec() {
        val spec = argSpec()
        val typed: Arg<Int> = Arg<String>(spec).int()
        // Same spec object is mutated in place, not replaced.
        assertEquals(spec, typed.spec)
        val converted = spec.convert("42")
        assertEquals(Result.Success(42), converted)
        assertIs<Result.Error<String>>(spec.convert("nope"))
    }

    @Test
    fun map_adaptsRawStringToDomainType() {
        val spec = optSpec()
        val opt = Opt<String?>(spec).map { it.toInt().seconds }
        assertEquals(Result.Success(5.seconds), spec.convert("5"))
        // A thrown parse exception becomes a clean error, not a crash.
        assertIs<Result.Error<String>>(spec.convert("abc"))
        spec.bind(5.seconds)
        assertEquals(5.seconds, opt())
    }

    @Test
    fun read_beforeBind_throws() {
        assertFailsWith<IllegalStateException> { Arg<String>(argSpec())() }
    }

    @Test
    fun read_afterBind_returnsTypedValue() {
        val spec = argSpec()
        val arg = Arg<String>(spec).int()
        spec.bind(42)
        assertEquals(42, arg())
    }

    @Test
    fun enum_setsChoicesAndConvertsCaseInsensitively() {
        val spec = argSpec()
        val arg = Arg<String>(spec).enum<Color>()
        // Choices display lowercase, but matching stays case-insensitive.
        assertEquals(listOf("red", "green"), spec.choices)
        assertEquals(Result.Success(Color.GREEN), spec.convert("green"))
        assertEquals(Result.Success(Color.GREEN), spec.convert("GREEN"))
        assertIs<Result.Error<String>>(spec.convert("blue"))
        spec.bind(Color.RED)
        assertEquals(Color.RED, arg())
    }

    @Test
    fun default_setsDefaultCardinality() {
        val spec = argSpec()
        Arg<String>(spec).int().default(5)
        assertEquals(Cardinality.Default(5), spec.cardinality)
    }

    @Test
    fun opt_boolean_converts() {
        val spec = optSpec()
        Opt<String?>(spec).boolean()
        assertEquals(Result.Success(true), spec.convert("true"))
        assertIs<Result.Error<String>>(spec.convert("yes"))
    }

    @Test
    fun opt_enum_setsChoicesAndBinds() {
        val spec = optSpec()
        val opt = Opt<String?>(spec).enum<Color>()
        assertEquals(listOf("red", "green"), spec.choices)
        assertEquals(Result.Success(Color.GREEN), spec.convert("green"))
        spec.bind(Color.RED)
        assertEquals(Color.RED, opt())
    }

    @Test
    fun opt_convert_customReason() {
        val spec = optSpec()
        Opt<String?>(spec).convert { s -> if (s == "ok") Result.Success(1) else Result.Error("bad") }
        assertEquals(Result.Success(1), spec.convert("ok"))
        assertIs<Result.Error<String>>(spec.convert("no"))
    }
}
