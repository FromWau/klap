package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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

private fun Cli.exec(argv: List<String>): String {
    val t = RecordingTerminal()
    run(argv.toTypedArray(), t)
    return t.out.toString()
}

class ParsePositionalsTest {

    @Test
    fun requiredAndOptionalPositional() {
        assertEquals("text=buy tag=null\n", posTree().exec(listOf("add", "buy")))
        assertEquals("text=buy tag=urgent\n", posTree().exec(listOf("add", "buy", "urgent")))
    }

    @Test
    fun variadicConvertsEach() {
        assertEquals("sum=6\n", posTree().exec(listOf("sum", "1", "2", "3")))
    }

    @Test
    fun missingRequiredArgument() {
        val err = assertIs<Result.Error<CliError>>(posTree().parse(listOf("add"))).error
        assertEquals(CliError.MissingArgument("add", "text"), err)
    }

    @Test
    fun variadicMinEnforced() {
        val err = assertIs<Result.Error<CliError>>(posTree().parse(listOf("sum"))).error
        assertEquals(CliError.MissingArgument("sum", "nums"), err)
    }

    @Test
    fun tooManyArgumentsRejected() {
        val err = assertIs<Result.Error<CliError>>(posTree().parse(listOf("ping", "extra"))).error
        assertEquals(CliError.TooManyArguments("ping", listOf("extra")), err)
    }

    @Test
    fun endOfOptionsMakesDashLedPositional() {
        assertEquals("text=-x tag=null\n", posTree().exec(listOf("add", "--", "-x")))
    }

    @Test
    fun badPositionalValueIsRejected() {
        val err = assertIs<Result.Error<CliError>>(posTree().parse(listOf("sum", "abc"))).error
        assertEquals(CliError.BadValue("nums", "abc", "not an integer"), err)
    }
}
