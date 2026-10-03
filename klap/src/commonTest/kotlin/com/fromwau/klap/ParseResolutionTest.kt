package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
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
    fun `resolves leaf to execute`() {
        val out = tree().parse(listOf("ping"))
        val exec = assertIs<Result.Success<Invocation>>(out).value
        assertEquals("ping", assertIs<Invocation.Execute>(exec).command.name)
    }

    @Test
    fun `json flag is captured anywhere`() {
        val out = tree().parse(listOf("ping", "--json"))
        val exec = assertIs<Invocation.Execute>(assertIs<Result.Success<Invocation>>(out).value)
        assertEquals(true, exec.globals.json)
    }

    @Test
    fun `help flag shows resolved command help`() {
        val out = tree().parse(listOf("config", "-h"))
        val help = assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(out).value)
        assertEquals("config", help.command.name)
    }

    @Test
    fun `version flag shows version`() {
        val out = tree().parse(listOf("--version"))
        assertIs<Invocation.ShowVersion>(assertIs<Result.Success<Invocation>>(out).value)
    }

    @Test
    fun `group without subcommand shows group help`() {
        val out = tree().parse(listOf("config"))
        val help = assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(out).value)
        assertEquals("config", help.command.name)
    }

    @Test
    fun `unknown subcommand is an error`() {
        val out = tree().parse(listOf("config", "bogus"))
        val err = assertIs<Result.Error<CliError>>(out).error
        assertEquals(CliError.UnknownSubcommand("todo config", "bogus"), err)
    }

    @Test
    fun `mistyped subcommand with flags reports subcommand not option`() {
        val app = cli("app") {
            command("temp") {
                option("--from")
                action { Ok("") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(app.parse(listOf("tempp", "5", "--from", "c"))).error
        // "tempp" is a one-edit near miss of the declared "temp" subcommand, so did-you-mean fires.
        assertEquals(CliError.UnknownSubcommand("app", "tempp", "temp"), err)
    }

    @Test
    fun `unknown subcommand suggests nearest name`() {
        val out = tree().parse(listOf("cofnig"))
        val err = assertIs<Result.Error<CliError>>(out).error
        assertEquals(CliError.UnknownSubcommand("todo", "cofnig", "config"), err)
    }

    @Test
    fun `unknown subcommand never suggests a hidden subcommand`() {
        // A hidden subcommand is omitted from help/completion; a typo suggestion must not reveal its name either.
        val tree = cli("app") {
            command("secret") {
                hidden = true
                action { Ok("") }
            }
            command("status") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("secrt"))).error
        // "secrt" is edit-distance 1 from the hidden "secret" but must resolve to nothing (or a visible name), never "secret".
        assertEquals(CliError.UnknownSubcommand("app", "secrt", null), err)
    }

    @Test
    fun `bad option before valid subcommand blames the option`() {
        // Regression: a bad option ahead of a REAL subcommand must blame the option, not the subcommand.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("--wat", "ping"))).error
        assertEquals(CliError.UnknownOption("--wat"), err)
    }

    @Test
    fun `a local option of the parent stops the walk so its subcommand never routes`() {
        val app = cli("app") {
            val workdir = option("--workdir")
            command("build") { action { Ok("built") } }
            action { Ok("root ${workdir()}") }
        }
        // globalOption is the mechanism for an option usable alongside a subcommand; a local one ends
        // routing, so "build" arrives as an operand of a root that declares no slot for it.
        val err = assertIs<Result.Error<CliError>>(app.parse(listOf("--workdir", "/tmp", "build"))).error
        assertEquals(CliError.UnroutedSubcommand("build", "app"), err)
    }

    @Test
    fun `leading unknown positional blames the subcommand`() {
        // First token is a non-flag: it is the leftmost offender, reported as an unknown subcommand.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("bogus", "--wat"))).error
        assertEquals(CliError.UnknownSubcommand("todo", "bogus"), err)
    }

    @Test
    fun `post end of options flag shaped token is a positional subcommand`() {
        // After --, a flag-shaped token is positional, so it is an unknown subcommand, never an unknown option.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("--", "--x"))).error
        assertEquals(CliError.UnknownSubcommand("todo", "--x"), err)
    }
}
