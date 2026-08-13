package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.klap.internal.render.argSummary
import com.fromwau.klap.internal.render.helpText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun posTree(): Cli = cli("todo") {
    command("add") {
        val text = argument("text")
        val tag = argument("tag").optional()
        action { Ok("text=${text()} tag=${tag()}") }
    }
    command("sum") {
        val nums = argument("nums").int().multiple(min = 1)
        action { Ok("sum=${nums().sum()}") }
    }
    command("ping") { action { Ok("pong") } }
}

private fun validateTree(): Cli = cli("todo") {
    command("add") {
        val text = argument("text").validate("must not be blank") { it.isNotBlank() }
        action { Ok(text()) }
    }
}

private fun Cli.exec(argv: List<String>): String {
    val t = RecordingTerminal()
    run(argv.toTypedArray(), t)
    return t.out.toString()
}

class ParsePositionalsTest {

    @Test
    fun `required and optional positional`() {
        assertEquals("text=buy tag=null\n", posTree().exec(listOf("add", "buy")))
        assertEquals("text=buy tag=urgent\n", posTree().exec(listOf("add", "buy", "urgent")))
    }

    @Test
    fun `variadic converts each`() {
        assertEquals("sum=6\n", posTree().exec(listOf("sum", "1", "2", "3")))
    }

    @Test
    fun `missing required argument`() {
        val err = assertIs<Result.Error<CliError>>(posTree().parse(listOf("add"))).error
        assertEquals(CliError.MissingArgument("todo add", "text"), err)
    }

    @Test
    fun `variadic min enforced`() {
        val err = assertIs<Result.Error<CliError>>(posTree().parse(listOf("sum"))).error
        assertEquals(CliError.MissingArgument("todo sum", "nums"), err)
    }

    @Test
    fun `too many arguments rejected`() {
        val err = assertIs<Result.Error<CliError>>(posTree().parse(listOf("ping", "extra"))).error
        assertEquals(CliError.TooManyArguments("todo ping", listOf("extra")), err)
    }

    @Test
    fun `end of options makes dash led positional`() {
        assertEquals("text=-x tag=null\n", posTree().exec(listOf("add", "--", "-x")))
    }

    @Test
    fun `bad positional value is rejected`() {
        val err = assertIs<Result.Error<CliError>>(posTree().parse(listOf("sum", "abc"))).error
        assertEquals(CliError.BadValue("nums", "abc", "not an integer", ConversionError.NotAnInteger), err)
    }

    @Test
    fun `validate failure on argument yields bad value`() {
        val tree = validateTree()
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("add", " "))).error
        assertEquals(CliError.BadValue("text", " ", "must not be blank"), err)
    }

    @Test
    fun `validate pass on argument binds value`() {
        val tree = validateTree()
        assertEquals("buy\n", tree.exec(listOf("add", "buy")))
    }

    @Test
    fun `range accepts and rejects on argument`() {
        val tree = cli("todo") {
            command("age") {
                val n = argument("n").int().range(0..120)
                action { Ok(n().toString()) }
            }
        }
        assertEquals("30\n", tree.exec(listOf("age", "30")))
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("age", "200"))).error
        assertEquals(CliError.BadValue("n", "200", "must be in 0..120"), err)
    }

    @Test
    fun `default bypasses validation on argument`() {
        // A .default value is trusted: it binds directly and is never routed through validate.
        val tree = cli("todo") {
            command("age") {
                val n = argument("n").int().range(0..120).default(999)
                action { Ok(n().toString()) }
            }
        }
        assertEquals("999\n", tree.exec(listOf("age")))
    }

    // --- "?: default" substitution semantics on positionals ---

    @Test
    fun `argument default non null absent uses default present uses value`() {
        val tree = cli("todo") {
            command("add") {
                val tag = argument("tag").default("d")
                action { Ok(tag()) }
            }
        }
        assertEquals("d\n", tree.exec(listOf("add")))
        assertEquals("urgent\n", tree.exec(listOf("add", "urgent")))
    }

    @Test
    fun `argument map to null default substitutes default instead of npe on bad input`() {
        val tree = cli("todo") {
            command("age") {
                val n = argument("n").map { it.toIntOrNull() }.default(0)
                action { Ok(n().toString()) }
            }
        }
        assertEquals("30\n", tree.exec(listOf("age", "30")))
        assertEquals("0\n", tree.exec(listOf("age", "abc")))
        assertEquals("0\n", tree.exec(listOf("age")))
    }

    @Test
    fun `reusing an arg handle for two type converters is rejected at construction`() {
        // Both .int() and .long() mutate the one shared spec, so the second stage would cast the first's
        // Int back to String for every input the user could type, leaving the argument unbindable.
        val thrown = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val a = argument("n")
                a.int()
                a.long()
                action { Ok("ok") }
            }
        }
        assertContains(thrown.message.orEmpty(), "type-changing converter")
    }

    // --- converter/validate chains must never throw at parse (never-throw contract) ---

    @Test
    fun `validate after multiple yields bad value instead of crashing`() {
        // validate runs per element (each a String), but the predicate expects the List; casting the
        // String element to List throws at parse, which the never-throw contract turns into BadValue.
        val tree = cli("app") {
            val files = argument("files").multiple().validate("need at least two") { it.size >= 2 }
            action { Ok(files().joinToString(",")) }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("a"))).error
        // reason is the platform-dependent cast exception message, so only the type and name are pinned.
        assertIs<CliError.BadValue>(err)
        assertEquals("files", err.name)
    }
}

private fun variadicTree(): Cli = cli("tar") {
    // `tar -tf a.tar` lists the whole archive: the FILE operands are genuinely optional, which is what
    // multiple()'s own `min = 0` default is supposed to mean.
    command("list") {
        val files = argument("file", "files").multiple()
        action { Ok("files=${files()}") }
    }
    command("strict") {
        val files = argument("file", "files").multiple(min = 1)
        action { Ok("files=${files()}") }
    }
}

class VariadicPositionalArityTest {

    @Test
    fun `multiple with min zero accepts zero operands`() {
        val outcome = variadicTree().parse(listOf("list"))
        assertIs<Result.Success<Invocation>>(outcome)
    }

    @Test
    fun `multiple with min zero binds an empty list`() {
        assertEquals("files=[]\n", variadicTree().exec(listOf("list")))
        assertEquals("files=[a, b]\n", variadicTree().exec(listOf("list", "a", "b")))
    }

    @Test
    fun `multiple with min one still rejects zero operands`() {
        // The guard must key on min, not on emptiness: a declared minimum is still enforced.
        val outcome = variadicTree().parse(listOf("strict"))
        val error = assertIs<Result.Error<CliError>>(outcome).error
        assertIs<CliError.MissingArgument>(error)
        assertEquals("file", error.argument)
    }

    @Test
    fun `help row agrees with the usage line about optionality`() {
        // The usage line says arity in brackets, the Arguments row says it in words. They must not
        // disagree: a bare "(repeatable)" beside a "[file...]" usage leaves zero-allowed unstated.
        val help = variadicTree().subcommand("list")!!.helpText("tar list")
        assertContains(help, "[file...]")
        assertContains(help, "optional; repeatable")

        val strict = variadicTree().subcommand("strict")!!.helpText("tar strict")
        assertContains(strict, "<file>...")
        assertContains(strict, "repeatable, min 1")
    }

    @Test
    fun `help distinguishes an optional variadic from a mandatory one`() {
        // The usage line has to advertise which of the two it is, or it documents a shape it cannot parse.
        val tree = variadicTree()
        assertEquals("[file...]", tree.subcommand("list")!!.argSummary())
        assertEquals("<file>...", tree.subcommand("strict")!!.argSummary())
    }
}

/** `cp SOURCE... DEST` — a variadic may be followed by required slots, which bind from the end. */
class NonLastVariadicTest {

    private fun cpTree(): Cli = cli("cp") {
        val sources = argument("source").multiple(min = 1)
        val dest = argument("dest")
        action { Ok("${sources()} -> ${dest()}") }
    }

    private fun run(tree: Cli, vararg argv: String): String = RecordingTerminal().let { term ->
        tree.run(argv.toList().toTypedArray(), term)
        term.out.toString().trim()
    }

    @Test
    fun `a variadic followed by a required positional splits from the end`() {
        assertEquals("[a] -> b", run(cpTree(), "a", "b"))
        assertEquals("[a, b] -> c", run(cpTree(), "a", "b", "c"))
    }

    @Test
    fun `the trailing slot is filled before the variadic takes anything`() {
        // One token cannot satisfy both. It feeds the variadic's minimum and the starved destination is
        // the one blamed, as GNU `cp a` does; blaming the variadic would answer `cp a` and bare `cp`
        // with the same sentence.
        val one = assertIs<Result.Error<CliError>>(cpTree().parse(listOf("a"))).error
        assertEquals(CliError.MissingArgument("cp", "dest"), one)
        val none = assertIs<Result.Error<CliError>>(cpTree().parse(emptyList())).error
        assertEquals(CliError.MissingArgument("cp", "source"), none)
    }

    @Test
    fun `two trailing required slots both bind from the end`() {
        val tree = cli("app") {
            val mid = argument("mid").multiple(min = 0)
            val a = argument("a")
            val b = argument("b")
            action { Ok("${mid()} ${a()} ${b()}") }
        }
        assertEquals("[] x y", run(tree, "x", "y"))
        assertEquals("[1, 2] x y", run(tree, "1", "2", "x", "y"))
    }

    @Test
    fun `a leading required slot still binds from the front`() {
        val tree = cli("app") {
            val first = argument("first")
            val rest = argument("rest").multiple(min = 0)
            val last = argument("last")
            action { Ok("${first()} ${rest()} ${last()}") }
        }
        assertEquals("a [] b", run(tree, "a", "b"))
        assertEquals("a [x, y] b", run(tree, "a", "x", "y", "b"))
    }

    @Test
    fun `a trailing variadic takes every operand and allows none`() {
        val tree = cli("app") {
            val files = argument("file").multiple(min = 0)
            action { Ok(files().toString()) }
        }
        assertEquals("[]", run(tree))
        assertEquals("[a, b]", run(tree, "a", "b"))
    }

    @Test
    fun `an optional slot after a variadic is rejected at build`() {
        // Genuinely ambiguous: with one token left there is no rule saying whether it feeds the greedy
        // slot or the optional one.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                argument("rest").multiple(min = 0)
                argument("tail").optional()
                action { Ok("") }
            }
        }
        assertTrue("ambiguous" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `the usage line renders the variadic where it was declared`() {
        assertEquals("<source>... <dest>", cpTree().argSummary())
    }
}
