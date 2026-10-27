package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.internal.parse.Line
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.ValueSpec
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private enum class Color { RED, GREEN }
@Suppress("unused") private enum class CaseCollision { A, @Suppress("EnumEntryName") a }
private enum class NoConstants
private enum class DebugLevel(val cli: String) { @Suppress("unused") BASIC("1"), FUNCTIONS("2") }

private fun argSpec(): ArgumentSpec = ArgumentSpec("x", "", { Result.Success(it) })

private fun optSpec(): OptionSpec = OptionSpec(listOf("--opt"), "", { Result.Success(it) })

/** Run [block] against a one-holder parse snapshot, so accessors resolve inside it. */
private fun <R> boundTo(spec: HolderSpec, value: Any?, block: ValueScope.() -> R): R =
    BoundInputs(mapOf(spec to value), json = false, line = Line.EMPTY).block()

// Extends ConverterScope: the transformers under test are member-extensions of the build-time scope,
// so the test class IS a scope. Every call site stays a plain `Arg<String>(spec).int()`.
class ConvertersTest : ConverterScope() {

    @Test
    fun `int converts through shared spec`() {
        val spec = argSpec()
        val typed: Arg<Int> = Arg<String>(spec).int()
        // Same spec object is mutated in place, not replaced.
        assertEquals(spec, typed.spec)
        val converted = spec.convert("42")
        assertEquals(Result.Success(42), converted)
        spec.convert("nope").assertError<ConversionError.NotAnInteger>()
    }

    @Test
    fun `map adapts raw string to domain type`() {
        val spec = optSpec()
        val opt = Opt<String?>(spec).map { it.toInt().seconds }
        assertEquals(Result.Success(5.seconds), spec.convert("5"))
        // A thrown parse exception becomes a clean error, not a crash.
        spec.convert("abc").assertError<ConversionError.Threw>()
        boundTo(spec, 5.seconds) { assertEquals(5.seconds, opt()) }
    }

    @Test
    fun `an error thrown by a map refuses the value instead of escaping parse`() {
        val tree = cli("app") {
            option(long = "later").map<Int> { throw NotImplementedError("converter not written yet") }
            action { Ok("") }
        }
        val refused = tree.parse(listOf("--later", "z")).assertError<CliError.BadValue>()
        assertTrue(refused.cause is ConversionError.Threw, "cause ${refused.cause}")
    }

    @Test
    fun `an error thrown by a validate refuses the value instead of escaping parse`() {
        val tree = cli("app") {
            option(long = "before").validate("never") { _: String -> throw NotImplementedError("first check") }.int()
            option(long = "after").int().validate("never") { throw NotImplementedError("second check") }
            action { Ok("") }
        }
        assertEquals("first check", tree.parse(listOf("--before", "1")).assertError<CliError.BadValue>().reason)
        assertEquals("second check", tree.parse(listOf("--after", "1")).assertError<CliError.BadValue>().reason)
    }

    @Test
    fun `read of a holder the command did not bind fails with a clear message`() {
        // Reading outside action { } is a compile error now (accessors live on ValueScope), so the one
        // surviving runtime misuse is a foreign accessor (e.g. a sibling command's option closed over
        // by mistake). It must fail fast naming the misuse.
        val foreign = Arg<String>(argSpec())
        val ex = assertFailsWith<IllegalStateException> { boundTo(optSpec(), "v") { foreign() } }
        assertTrue("not bound by the current command" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `read after bind returns typed value`() {
        val spec = argSpec()
        val arg = Arg<String>(spec).int()
        boundTo(spec, 42) { assertEquals(42, arg()) }
    }

    @Test
    fun `enum sets choices and converts in exact case`() {
        val spec = argSpec()
        val arg = Arg<String>(spec).enum<Color>()
        assertEquals(listOf("red", "green"), spec.choices)
        assertEquals(Result.Success(Color.GREEN), spec.convert("green"))
        spec.convert("GREEN").assertError<ConversionError.NotOneOf>()
        // The case carries the declared choices, so a caller can offer them without re-parsing the message.
        assertEquals(Result.Error(ConversionError.NotOneOf(listOf("red", "green"))), spec.convert("blue"))
        boundTo(spec, Color.RED) { assertEquals(Color.RED, arg()) }
    }

    @Test
    fun `arg enum rejects case insensitive duplicate at construction`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).enum<CaseCollision>()
        }
        assertTrue("CaseCollision" in ex.message.orEmpty(), ex.message)
        assertTrue("a" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `choice matches in exact case by default`() {
        val spec = argSpec()
        Arg<String>(spec).choice("fast", "slow")
        assertEquals(Result.Success("fast"), spec.convert("fast"))
        spec.convert("FAST").assertError<ConversionError.NotOneOf>()
        spec.convert("Fast").assertError<ConversionError.NotOneOf>()
    }

    @Test
    fun `choice with ignoreCase matches any case and returns canonical spelling`() {
        val spec = argSpec()
        val arg = Arg<String>(spec).choice("fast", "slow", ignoreCase = true)
        assertEquals(listOf("fast", "slow"), spec.choices)
        assertEquals(Result.Success("fast"), spec.convert("FAST"))
        assertEquals(Result.Success("fast"), spec.convert("Fast"))
        assertEquals(Result.Success("fast"), spec.convert("fast"))
        spec.convert("quick").assertError<ConversionError.NotOneOf>()
        boundTo(spec, "fast") { assertEquals("fast", arg()) }
    }

    @Test
    fun `opt choice with ignoreCase matches any case and returns canonical spelling`() {
        val spec = optSpec()
        val opt = Opt<String?>(spec).choice("fast", "slow", ignoreCase = true)
        assertEquals(listOf("fast", "slow"), spec.choices)
        assertEquals(Result.Success("fast"), spec.convert("FAST"))
        spec.convert("quick").assertError<ConversionError.NotOneOf>()
        boundTo(spec, "fast") { assertEquals("fast", opt()) }
    }

    @Test
    fun `opt choice with ignoreCase rejects a duplicate in another case at construction`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).choice("a", "A", ignoreCase = true)
        }
        assertTrue("A" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `opt choice rejects exact duplicate at construction`() {
        assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).choice("a", "b", "a")
        }
    }

    @Test
    fun `arg choice with ignoreCase rejects a duplicate in another case at construction`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).choice("a", "A", ignoreCase = true)
        }
        assertTrue("A" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `choices that differ only in case are two choices in exact case`() {
        val spec = argSpec()
        Arg<String>(spec).choice("a", "A")
        assertEquals(Result.Success("a"), spec.convert("a"))
        assertEquals(Result.Success("A"), spec.convert("A"))
    }

    @Test
    fun `arg choice rejects exact duplicate at construction`() {
        assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).choice("a", "b", "a")
        }
    }

    @Test
    fun `opt choice rejects empty choice list at construction`() {
        // A zero-choice option is permanently unsatisfiable (every input errors "not one of "); reject it at
        // construction like range() rejects an empty range, rather than shipping a degenerate holder.
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).choice()
        }
        assertTrue("at least one" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `arg choice rejects empty choice list at construction`() {
        assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).choice()
        }
    }

    @Test
    fun `opt enum rejects constantless enum at construction`() {
        // An enum with no constants is the enum-shaped twin of an empty choice list: unsatisfiable, so fail
        // loudly at construction and name the enum.
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).enum<NoConstants>()
        }
        assertTrue("NoConstants" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `default sets default cardinality`() {
        val spec = argSpec()
        Arg<String>(spec).int().default(5)
        assertEquals(Cardinality.Default(5), spec.cardinality)
    }

    // --- .default(nonNull) on an optional narrows the accessor back to non-null ---

    @Test
    fun `opt default non null still narrows accessor to non null`() {
        val spec = optSpec()
        val opt: Opt<String> = Opt<String?>(spec).default("d")
        assertEquals(Cardinality.Default("d"), spec.cardinality)
        boundTo(spec, "d") {
            val value: String = opt()
            assertEquals("d", value)
        }
    }

    @Test
    fun `arg optional default non null still narrows accessor to non null`() {
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
    fun `arg multiple and default or optional are rejected at build time`() {
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
    fun `opt required default multiple are rejected at build time`() {
        // Unlike Arg, Opt's cardinality methods each narrow their return type (.required() -> Opt<T>,
        // .multiple() -> Opt<List<T>>), which blocks a bad PROPER chain at compile time. But aliasing the
        // same pre-narrowed Opt<T?> into two calls bypasses that narrowing, since the original reference's
        // type never advances.
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("c") {
                    val o = option(short = 'v')
                    o.required()
                    o.multiple()
                    action { Ok("") }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("c") {
                    val o = option(short = 'v')
                    o.multiple()
                    o.default("d")
                    action { Ok("") }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("c") {
                    val o = option(short = 'v')
                    o.default("d")
                    o.required()
                    action { Ok("") }
                }
            }
        }
        // Ordinary single-cardinality usage is unaffected.
        val cmd = cli("x") {
            command("c") {
                val o = option(short = 'v').int().required()
                action { Ok(o().toString()) }
            }
        }
        assertEquals("c", cmd.subcommand("c")?.name)
    }

    @Test
    fun `opt boolean converts`() {
        val spec = optSpec()
        Opt<String?>(spec).boolean()
        assertEquals(Result.Success(true), spec.convert("true"))
        spec.convert("yes").assertError<ConversionError.NotABoolean>()
    }

    @Test
    fun `arg boolean converts strictly and rejects non boolean`() {
        val spec = argSpec()
        Arg<String>(spec).boolean()
        assertEquals(Result.Success(true), spec.convert("true"))
        assertEquals(Result.Success(false), spec.convert("false"))
        // toBooleanStrictOrNull accepts only exact lowercase "true"/"false".
        assertEquals(Result.Error(ConversionError.NotABoolean), spec.convert("TRUE"))
        assertEquals(Result.Error(ConversionError.NotABoolean), spec.convert("1"))
    }

    @Test
    fun `arg long converts to long and rejects overflow and decimal`() {
        val spec = argSpec()
        val typed: Arg<Long> = Arg<String>(spec).long()
        assertEquals(spec, typed.spec)
        assertEquals(Result.Success(42L), spec.convert("42"))
        // Overflows Long, and a decimal is not an integer: both fail toLongOrNull.
        assertEquals(Result.Error(ConversionError.NotALong), spec.convert("99999999999999999999"))
        assertEquals(Result.Error(ConversionError.NotALong), spec.convert("1.5"))
    }

    @Test
    fun `opt long converts to long and rejects overflow and decimal`() {
        val spec = optSpec()
        Opt<String?>(spec).long()
        assertEquals(Result.Success(42L), spec.convert("42"))
        assertEquals(Result.Error(ConversionError.NotALong), spec.convert("99999999999999999999"))
        assertEquals(Result.Error(ConversionError.NotALong), spec.convert("1.5"))
    }

    /** `.int()` and `.long()` on an operand and on an option, each with its error and the value it reads [n] as. */
    private fun integerSpecs(): List<Triple<ValueSpec, ConversionError, (Int) -> Any>> = listOf(
        Triple(Arg<String>(argSpec()).int().spec, ConversionError.NotAnInteger) { n -> n },
        Triple(Arg<String>(argSpec()).long().spec, ConversionError.NotALong) { n -> n.toLong() },
        Triple(optSpec().also { Opt<String?>(it).int() }, ConversionError.NotAnInteger) { n -> n },
        Triple(optSpec().also { Opt<String?>(it).long() }, ConversionError.NotALong) { n -> n.toLong() },
    )

    @Test
    fun `an integer takes leading blanks and a plus sign as strtol does`() {
        // `head -n ' 2'`, `head -n $'\t2'`, `head -n $'\n2'`, `head -n +2`, `ls -w ' 80'` and `nice -n ' -0'` run.
        val read = mapOf(" 2" to 2, "\t2" to 2, "\n\u000B\u000C\r2" to 2, "+2" to 2, " +2" to 2, " -0" to 0, "-7" to -7)
        for ((spec, _, typed) in integerSpecs()) {
            read.forEach { (raw, n) -> assertEquals(Result.Success(typed(n)), spec.convert(raw), "`$raw`") }
        }
    }

    @Test
    fun `an integer refuses a trailing blank and a sign with no digits after it`() {
        // `head -n '2 '`, `head -n ' '`, `head -n +` and `head -n '+ 2'`: "invalid number of lines"; `ls -w '80 '`:
        // "invalid line width".
        for ((spec, error, _) in integerSpecs()) {
            listOf("2 ", "2\n", " ", "", "+", "-", "+ 2", " 2 ").forEach { raw ->
                assertEquals(Result.Error(error), spec.convert(raw), "`$raw`")
            }
        }
    }

    @Test
    fun `an integer reads ASCII decimal digits only`() {
        // `head -n ١`, `head -n １２`, `ls -w ١` and `nice -n ١` are refused; a no-break space is no blank to strtol
        // (`head -n $' 2'` is refused).
        for ((spec, error, _) in integerSpecs()) {
            listOf("١٢", "１２", "१२", "1٢", " 2").forEach { raw ->
                assertEquals(Result.Error(error), spec.convert(raw), "`$raw`")
            }
        }
    }

    @Test
    fun `arg double converts to double and rejects non number`() {
        val spec = argSpec()
        val typed: Arg<Double> = Arg<String>(spec).double()
        assertEquals(spec, typed.spec)
        assertEquals(Result.Success(3.14), spec.convert("3.14"))
        assertEquals(Result.Error(ConversionError.NotADouble), spec.convert("x"))
    }

    @Test
    fun `opt double converts to double and rejects non number`() {
        val spec = optSpec()
        Opt<String?>(spec).double()
        assertEquals(Result.Success(3.14), spec.convert("3.14"))
        assertEquals(Result.Error(ConversionError.NotADouble), spec.convert("x"))
    }

    @Test
    fun `a double refuses a type suffix and a trailing blank`() {
        // coreutils 9.12 `timeout 1.5f true` and `timeout '1 ' true` are "invalid time interval".
        val spec = argSpec()
        Arg<String>(spec).double()
        listOf("1.5f", "1.5F", "1.5d", "1.5D", "1 ").forEach { raw ->
            assertEquals(Result.Error(ConversionError.NotADouble), spec.convert(raw), "`$raw`")
        }
    }

    @Test
    fun `a double reads infinity nan and a hex float as strtod does`() {
        // glibc strtod as coreutils 9.12 reads it: `timeout inf true`, `timeout 0x1A true` and `timeout ' 1' true` run.
        val spec = argSpec()
        Arg<String>(spec).double()
        assertEquals(Result.Success(Double.POSITIVE_INFINITY), spec.convert("inf"))
        assertEquals(Result.Success(Double.NEGATIVE_INFINITY), spec.convert("-Infinity"))
        assertEquals(Result.Success(Double.NaN), spec.convert("NAN"))
        assertEquals(Result.Success(8.0), spec.convert("0x1p3"))
        assertEquals(Result.Success(26.0), spec.convert("0x1A"))
        assertEquals(Result.Success(0.5), spec.convert("+.5"))
        assertEquals(Result.Success(1.5), spec.convert(" 1.5"))
        assertEquals(Result.Success(Double.POSITIVE_INFINITY), spec.convert("1e400"))
    }

    @Test
    fun `a double refuses a word strtod reads only in part`() {
        // coreutils 9.12 `timeout 1e true`, `timeout 0x true` and `timeout infx true` are "invalid time interval".
        val spec = argSpec()
        Arg<String>(spec).double()
        listOf("1e", "0x", "infx", "infin", "1.5e+", "", "+", ".", "١٫٥").forEach { raw ->
            assertEquals(Result.Error(ConversionError.NotADouble), spec.convert(raw), "`$raw`")
        }
    }

    @Test
    fun `opt enum sets choices and binds`() {
        val spec = optSpec()
        val opt = Opt<String?>(spec).enum<Color>()
        assertEquals(listOf("red", "green"), spec.choices)
        assertEquals(Result.Success(Color.GREEN), spec.convert("green"))
        boundTo(spec, Color.RED) { assertEquals(Color.RED, opt()) }
    }

    @Test
    fun `opt enum spells each constant as its spelling gives it`() {
        // pacman's debug levels are digits, which no constant can be named.
        val spec = optSpec()
        Opt<String?>(spec).enum<DebugLevel>(spelling = { it.cli })
        assertEquals(listOf("1", "2"), spec.choices)
        assertEquals(Result.Success(DebugLevel.FUNCTIONS), spec.convert("2"))
        assertTrue(spec.convert("functions") is Result.Error, "the constant's name is no longer a spelling")
    }

    @Test
    fun `opt enum rejects case insensitive duplicate at construction`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).enum<CaseCollision>()
        }
        assertTrue("CaseCollision" in ex.message.orEmpty(), ex.message)
        assertTrue("a" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `enum with ignoreCase matches all case variants to canonical constant`() {
        val spec = argSpec()
        Arg<String>(spec).enum<Color>(ignoreCase = true)
        assertEquals(Result.Success(Color.RED), spec.convert("RED"))
        assertEquals(Result.Success(Color.RED), spec.convert("red"))
        assertEquals(Result.Success(Color.RED), spec.convert("Red"))
    }

    @Test
    fun `opt convert custom reason`() {
        val spec = optSpec()
        Opt<String?>(spec).convert { s ->
            if (s == "ok") Result.Success(1) else Result.Error(ConversionError.Domain(Rejected, "bad"))
        }
        assertEquals(Result.Success(1), spec.convert("ok"))
        spec.convert("no").assertError<ConversionError.Domain>()
    }

    @Test
    fun `validate passes then fails`() {
        val spec = argSpec()
        Arg<String>(spec).validate("must not be blank") { it.isNotBlank() }
        assertEquals(null, spec.validate?.invoke("ok"))
        assertEquals("must not be blank", spec.validate?.invoke(""))
    }

    @Test
    fun `validate composes first failure wins`() {
        val spec = optSpec()
        Opt<String?>(spec).int()
            .validate("must be positive") { it > 0 }
            .validate("must be even") { it % 2 == 0 }
        assertEquals("must be even", spec.validate?.invoke(3))
        assertEquals("must be positive", spec.validate?.invoke(-2))
        assertEquals(null, spec.validate?.invoke(4))
    }

    @Test
    fun `range sets hint and validates bounds on arg`() {
        val spec = argSpec()
        Arg<String>(spec).int().range(1..65535)
        assertEquals("1..65535", spec.valueHint)
        assertEquals(null, spec.validate?.invoke(8080))
        assertEquals("must be in 1..65535", spec.validate?.invoke(0))
    }

    @Test
    fun `range sets hint and validates bounds on opt`() {
        val spec = optSpec()
        Opt<String?>(spec).int().range(1..65535)
        assertEquals("1..65535", spec.valueHint)
        assertEquals(null, spec.validate?.invoke(1))
        assertEquals("must be in 1..65535", spec.validate?.invoke(70000))
    }

    @Test
    fun `range rejects empty range at construction`() {
        assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).int().range(5..1)
        }
    }

    @Test
    fun `range normal range still constructs`() {
        val spec = argSpec()
        Arg<String>(spec).int().range(1..10)
        assertEquals("1..10", spec.valueHint)
    }

    @Test
    fun `a stepped range names its step in the hint and the refusal`() {
        val spec = optSpec()
        Opt<String?>(spec).int().range(1..9 step 3)
        assertEquals("1..7 step 3", spec.valueHint)
        assertEquals(null, spec.validate?.invoke(4))
        assertEquals("must be in 1..7 step 3", spec.validate?.invoke(2))
    }

    @Test
    fun `a descending range reads from its low end`() {
        val spec = argSpec()
        Arg<String>(spec).int().range(10 downTo 1)
        assertEquals("1..10", spec.valueHint)
        assertEquals("must be in 1..10", spec.validate?.invoke(11))
    }

    // --- Converter composition: chained String-input converters must compose, not overwrite ---

    @Test
    fun `choice then map composes instead of overwriting and keeps choice validation`() {
        val spec = argSpec()
        Arg<String>(spec).choice("a", "b", ignoreCase = true).map { it.uppercase() }
        assertEquals(Result.Success("A"), spec.convert("a"))
        // The choice still matches before the map stage runs.
        assertEquals(Result.Success("A"), spec.convert("A"))
        // Choice validation must not be bypassed by the later .map().
        spec.convert("z").assertError<ConversionError.NotOneOf>()
    }

    @Test
    fun `map then map composes both stages in order`() {
        val spec = argSpec()
        Arg<String>(spec).map { it.trim() }.map { it.length }
        // Both stages must run, in order: trim first, then length of the trimmed string.
        assertEquals(Result.Success(2), spec.convert("  hi  "))
    }

    @Test
    fun `choice then int enforces choice before int conversion`() {
        val spec = argSpec()
        Arg<String>(spec).choice("1", "2").int()
        assertEquals(Result.Success(1), spec.convert("1"))
        // Not one of the choices: rejected before int-parsing ever runs.
        spec.convert("9").assertError<ConversionError.NotOneOf>()
    }

    @Test
    fun `convert then map composes both stages`() {
        val spec = argSpec()
        Arg<String>(spec).convert { Result.Success(it.trim()) }.map { it.length }
        assertEquals(Result.Success(2), spec.convert("  hi  "))
    }

    // --- Converter ordering: a .validate()/.range() judges the value converted so far at its place in the chain,
    // and a type-changing converter cannot be stacked on top of another one, even through a stale handle ---

    @Test
    fun `a validate before a type changing converter judges the raw value`() {
        val tree = cli("app") {
            val n = argument("n").validate("must not be blank") { it.isNotBlank() }.int()
            action { Ok("n=${n()}") }
        }
        assertEquals("n=7", tree.bindText("7"))
        assertEquals("must not be blank", tree.parse(listOf(" ")).assertError<CliError.BadValue>().reason)
        assertEquals(ConversionError.NotAnInteger, tree.parse(listOf("x")).assertError<CliError.BadValue>().cause)
    }

    @Test
    fun `a range before a type changing converter bounds the raw value`() {
        // The range compares strings, so `10` sorts inside "0".."5" and `7` outside it, whatever their Int.
        val tree = cli("app") {
            val n = argument("n").range("0".."5").int()
            action { Ok("n=${n()}") }
        }
        assertEquals("n=10", tree.bindText("10"))
        assertEquals("must be in 0..5", tree.parse(listOf("7")).assertError<CliError.BadValue>().reason)
    }

    @Test
    fun `a validate before a map never judges the mapped value`() {
        val tree = cli("head") {
            val n = option(short = 'n')
                .validate("must not be empty") { s: String -> s.isNotEmpty() }
                .map { it.length }
            action { Ok("n=${n()}") }
        }
        assertEquals("n=3", tree.bindText("-n", "abc"))
        assertEquals("must not be empty", tree.parse(listOf("-n", "")).assertError<CliError.BadValue>().reason)
    }

    @Test
    fun `checks on both sides of a converter each judge the value at their place`() {
        val tree = cli("app") {
            val n = option(long = "n")
                .validate("must be short") { s: String -> s.length < 4 }
                .int()
                .validate("must be even") { it % 2 == 0 }
            action { Ok("n=${n()}") }
        }
        assertEquals("n=12", tree.bindText("--n", "12"))
        assertEquals("must be short", tree.parse(listOf("--n", "1234")).assertError<CliError.BadValue>().reason)
        assertEquals("must be even", tree.parse(listOf("--n", "13")).assertError<CliError.BadValue>().reason)
    }

    @Test
    fun `a validate before a choice refuses as a bad value not as a choice`() {
        val tree = cli("app") {
            val mode = option(long = "mode").validate("must be lowercase") { s: String -> s == s.lowercase() }
                .choice("fast", "Slow")
            action { Ok("mode=${mode()}") }
        }
        assertEquals("mode=fast", tree.bindText("--mode", "fast"))
        val refused = tree.parse(listOf("--mode", "Slow")).assertError<CliError.BadValue>()
        assertEquals("must be lowercase", refused.reason)
        assertNull(refused.cause)
    }

    @Test
    fun `aliased handle second type changing converter is rejected at construction`() {
        // Keeping the Arg<String> handle alive past .int() and reusing it still statically offers .long(),
        // since the handle's own type never advanced; the shared spec must reject the second call instead.
        val spec = argSpec()
        val a = Arg<String>(spec)
        a.int()
        val ex = assertFailsWith<IllegalArgumentException> { a.long() }
        assertEquals(
            "argument 'x': cannot add another converter after a type-changing converter " +
                "(.int()/.long()/.double()/.boolean()/.duration()/.instant()/.uuid()/.enum<E>(), or a " +
                ".convert { }/.map { } to a type other than String) has already run; " +
                "this usually means a converter was called through an Arg<String>/Opt<String?> handle kept alive " +
                "past that point",
            ex.message,
        )
    }

    @Test
    fun `a converter through an arg handle kept past a convert to another type is refused at construction`() {
        val a = Arg<String>(argSpec())
        a.convert { Ok(it.length) }
        val ex = assertFailsWith<IllegalArgumentException> { a.int() }
        assertContains(ex.message.orEmpty(), "argument 'x': cannot add another converter after a type-changing")
    }

    @Test
    fun `a converter through an arg handle kept past a map to another type is refused at construction`() {
        val a = Arg<String>(argSpec())
        a.map { it.length }
        assertFailsWith<IllegalArgumentException> { a.map { it.trim() } }
    }

    @Test
    fun `a converter through an opt handle kept past a convert to another type is refused at construction`() {
        val o = Opt<String?>(optSpec())
        o.convert { Ok(it.length) }
        val ex = assertFailsWith<IllegalArgumentException> { o.choice("a") }
        assertContains(ex.message.orEmpty(), "option '--opt': cannot add another converter after a type-changing")
    }

    @Test
    fun `a converter through an opt handle kept past a map to another type is refused at construction`() {
        val o = Opt<String?>(optSpec())
        o.map { it.length }
        assertFailsWith<IllegalArgumentException> { o.convert { Ok(it.trim()) } }
    }

    @Test
    fun `a convert from string to string still chains a choice`() {
        val spec = argSpec()
        Arg<String>(spec).convert { Ok(it.trim()) }.choice("a", "b")
        assertEquals(Result.Success("a"), spec.convert(" a "))
        spec.convert(" z ").assertError<ConversionError.NotOneOf>()
    }

    @Test
    fun `an opt map from string to string still chains another map`() {
        val spec = optSpec()
        Opt<String?>(spec).map { it.lowercase() }.map { it.length }
        assertEquals(Result.Success(2), spec.convert("Hi"))
    }

    @Test
    fun `int then range safe order converts and validates end to end`() {
        val spec = argSpec()
        val arg = Arg<String>(spec).int().range(1..100)
        assertEquals(Result.Success(42), spec.convert("42"))
        assertEquals(null, spec.validate?.invoke(50))
        assertEquals("must be in 1..100", spec.validate?.invoke(200))
        boundTo(spec, 50) { assertEquals(50, arg()) }
    }

    @Test
    fun `map then map is not treated as a type changing stack`() {
        // The first .map() returns a String, so it leaves the chain open for the second.
        val spec = argSpec()
        Arg<String>(spec).map { it.trim() }.map { it.length }
        assertEquals(Result.Success(2), spec.convert("  hi  "))
    }

    @Test
    fun `choice then map is not treated as a type changing stack`() {
        val spec = argSpec()
        Arg<String>(spec).choice("a", "b").map { it.uppercase() }
        assertEquals(Result.Success("A"), spec.convert("a"))
    }

    @Test
    fun `opt choice then map composes instead of overwriting and keeps choice validation`() {
        val spec = optSpec()
        Opt<String?>(spec).choice("a", "b").map { it.uppercase() }
        assertEquals(Result.Success("A"), spec.convert("a"))
        spec.convert("z").assertError<ConversionError.NotOneOf>()
    }

    @Test
    fun `optional value records the bare value on the spec`() {
        val spec = optSpec()
        Opt<String?>(spec).optionalValue("always")
        assertEquals("always", spec.bareValue)
    }

    @Test
    fun `optional value leaves the spec unchanged by default`() {
        assertNull(optSpec().bareValue)
    }

    @Test
    fun `optional value returns the same holder so the chain continues`() {
        val spec = optSpec()
        val typed: Opt<Int?> = Opt<String?>(spec).optionalValue("0").int()
        assertEquals(spec, typed.spec)
        // The bare value is a RAW string: it goes through the converter like any other occurrence, so a
        // later .int() applies to it too.
        assertEquals(Result.Success(0), spec.convert("0"))
    }

    @Test
    fun `an invalid earlier occurrence of a single-valued option refuses the line`() {
        // GNU `head -n abc -n 5` is "invalid number of lines", though the last value is fine.
        val tool = cli("tool") {
            val lines = option(short = 'n', long = "lines").int()
            action { Ok("${lines()}") }
        }
        assertEquals("abc", tool.parse(listOf("-n", "abc", "-n", "5")).assertError<CliError.BadValue>().value)
        tool.parse(listOf("-n", "3", "-n", "5")).assertSuccess()
    }

    @Test
    fun `an optional value option keeps every occurrence in line order when it is multiple`() {
        // `pacman --debug=2 --debug` keeps level 2: the action needs every occurrence, the bare one included.
        for (order in listOf("optionalValue first", "multiple first")) {
            val debug = cli("tool") {
                val level = option(long = "debug").let {
                    if (order == "optionalValue first") {
                        it.optionalValue("1").multiple()
                    } else {
                        it.multiple().optionalValue("1")
                    }
                }
                action { Ok("${level()}") }
            }
            val term = RecordingTerminal()
            debug.run(arrayOf("--debug=2", "--debug"), term)
            assertEquals("[2, 1]", term.out.trim(), order)
        }
    }

    @Test
    fun `optional value rejects a blank bare value`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).optionalValue("  ")
        }
        assertTrue("blank" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `optional value rejects a bare value not in an already declared choice set`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).choice("a", "b").optionalValue("c")
        }
        assertTrue("c" in ex.message.orEmpty(), ex.message)
        assertTrue("a, b" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `choice rejects a choice set excluding an already declared bare value`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).optionalValue("c").choice("a", "b")
        }
        assertTrue("c" in ex.message.orEmpty(), ex.message)
        assertTrue("a, b" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `optional value accepts a bare value matching an ignoreCase choice in another case`() {
        val spec = optSpec()
        Opt<String?>(spec).choice("Fast", "Slow", ignoreCase = true).optionalValue("fast")
        assertEquals("fast", spec.bareValue)
        // The guard does not over-reject: the mismatched case is legal, same as the parser's own matching.
        assertEquals(Result.Success("Fast"), spec.convert("fast"))
    }

    @Test
    fun `optional value rejects a bare value not in an already declared enum set`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).enum<Color>().optionalValue("blue")
        }
        assertTrue("blue" in ex.message.orEmpty(), ex.message)
        assertTrue("red, green" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `enum rejects an enum excluding an already declared bare value`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(optSpec()).optionalValue("blue").enum<Color>()
        }
        assertTrue("blue" in ex.message.orEmpty(), ex.message)
        assertTrue("red, green" in ex.message.orEmpty(), ex.message)
    }

    // --- .default() validates against an already-declared choice set, matching the reverse declaration
    // order (.default() then .choice()), which andThenConvert's own default re-validation already covers ---

    @Test
    fun `choice then default rejects an out of set default at construction`() {
        val spec = argSpec()
        val ex = assertFailsWith<IllegalArgumentException> {
            Arg<String>(spec).choice("fast", "slow").default("bogus")
        }
        assertEquals("argument 'x': default value 'bogus' is invalid: not one of fast, slow", ex.message)
    }

    @Test
    fun `default then choice rejects an out of set default at construction same message shape`() {
        // The reverse declaration order already worked before this fix, via andThenConvert's own default
        // re-validation; asserted here so both orders are pinned to the identical message shape.
        val spec = argSpec()
        val ex = assertFailsWith<IllegalArgumentException> {
            Arg<String>(spec).default("bogus").choice("fast", "slow")
        }
        assertEquals("argument 'x': default value 'bogus' is invalid: not one of fast, slow", ex.message)
    }

    @Test
    fun `choice then default refuses a default in another case at construction`() {
        assertFailsWith<IllegalArgumentException> {
            Arg<String>(argSpec()).choice("fast", "slow").default("FAST")
        }
    }

    @Test
    fun `ignoreCase choice then default canonicalizes a case differing valid default at construction`() {
        val spec = argSpec()
        Arg<String>(spec).choice("fast", "slow", ignoreCase = true).default("FAST")
        // The guard does not over-reject: the mismatched case is legal, same as the parser's own matching,
        // and the stored default is canonicalized so an absent argument binds the same value a typed
        // "FAST"/"fast"/"Fast" would have.
        assertEquals(Cardinality.Default("fast"), spec.cardinality)
    }

    @Test
    fun `opt choice then default rejects an out of set default at construction`() {
        val spec = optSpec()
        val ex = assertFailsWith<IllegalArgumentException> {
            Opt<String?>(spec).choice("fast", "slow").default("bogus")
        }
        assertEquals("option '--opt': default value 'bogus' is invalid: not one of fast, slow", ex.message)
    }
}

/** A converter failure with no payload beyond the fact that it happened. */
private data object Rejected : ResultError
