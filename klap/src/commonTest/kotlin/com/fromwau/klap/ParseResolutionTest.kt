package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun tree(): Cli = cli("todo") {
    version = "1.0.0"
    command("ping") { action { Ok("") } }
    command("config") {
        command("get") { action { Ok("") } }
    }
}

class ParseResolutionTest {

    @Test
    fun resolvesLeafToExecute() {
        val out = tree().parse(listOf("ping"))
        val exec = assertIs<Result.Success<Invocation>>(out).value
        assertEquals("ping", assertIs<Invocation.Execute>(exec).cli.name)
    }

    @Test
    fun jsonFlagIsCapturedAnywhere() {
        val out = tree().parse(listOf("ping", "--json"))
        val exec = assertIs<Invocation.Execute>(assertIs<Result.Success<Invocation>>(out).value)
        assertEquals(true, exec.globals.json)
    }

    @Test
    fun helpFlagShowsResolvedCommandHelp() {
        val out = tree().parse(listOf("config", "-h"))
        val help = assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(out).value)
        assertEquals("config", help.cli.name)
    }

    @Test
    fun versionFlagShowsVersion() {
        val out = tree().parse(listOf("--version"))
        assertIs<Invocation.ShowVersion>(assertIs<Result.Success<Invocation>>(out).value)
    }

    @Test
    fun groupWithoutSubcommandShowsGroupHelp() {
        val out = tree().parse(listOf("config"))
        val help = assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(out).value)
        assertEquals("config", help.cli.name)
    }

    @Test
    fun unknownSubcommandIsAnError() {
        val out = tree().parse(listOf("config", "bogus"))
        val err = assertIs<Result.Error<CliError>>(out).error
        assertEquals(CliError.UnknownSubcommand("config", "bogus"), err)
    }

    @Test
    fun mistypedSubcommandWithFlagsReportsSubcommandNotOption() {
        val app = cli("app") {
            command("temp") {
                option("from")
                action { Ok("") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(app.parse(listOf("tempp", "5", "--from", "c"))).error
        assertEquals(CliError.UnknownSubcommand("app", "tempp"), err)
    }
}
