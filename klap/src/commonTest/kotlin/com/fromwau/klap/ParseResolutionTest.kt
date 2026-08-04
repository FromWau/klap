package com.fromwau.klap

import com.fromwau.klap.internal.parse.suggest
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
        assertEquals("ping", assertIs<Invocation.Execute>(exec).command.name)
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
        assertEquals("config", help.command.name)
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
        assertEquals("config", help.command.name)
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
                option("--from")
                action { Ok("") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(app.parse(listOf("tempp", "5", "--from", "c"))).error
        // "tempp" is a one-edit near miss of the declared "temp" subcommand, so did-you-mean fires.
        assertEquals(CliError.UnknownSubcommand("app", "tempp", "temp"), err)
    }

    @Test
    fun unknownSubcommandSuggestsNearestName() {
        val out = tree().parse(listOf("cofnig"))
        val err = assertIs<Result.Error<CliError>>(out).error
        assertEquals(CliError.UnknownSubcommand("todo", "cofnig", "config"), err)
    }

    @Test
    fun suggestNeverReturnsAnExactMatch() {
        // A token equal to a candidate is not really unknown; "did you mean <the same word>" must never happen.
        assertEquals(null, suggest("config", listOf("config", "ping")))
        assertEquals("config", suggest("cofnig", listOf("config", "ping")))
    }

    @Test
    fun suggestFoldsCaseBeforeExcludingTheSelfMatch() {
        // Regression: the self-match check must compare against the folded needle, not the raw token, so an
        // ignoreCase call never suggests a candidate that is the same word under the very fold it applies.
        assertEquals(null, suggest("FAST", listOf("fast"), ignoreCase = true))
        // Case-sensitively "Fast" is genuinely not "fast", so the same pair still suggests without folding.
        assertEquals("fast", suggest("Fast", listOf("fast")))
        // A folded prefix still resolves to the one reachable candidate.
        assertEquals("fast", suggest("FA", listOf("fast"), ignoreCase = true))
        // The suggestion carries the candidate's declared spelling, never the lowered needle.
        assertEquals("FAST", suggest("fa", listOf("FAST"), ignoreCase = true))
    }

    @Test
    fun aBlankTokenPrefixesEveryCandidateAndSoAnswersOnlyWhenThereIsOne() {
        // Every string carries the empty prefix, so the prefix rule reaches a lone candidate and ties on
        // any larger pool; the distance rule cannot rescue it either, since 0 is outside its 1..n bound.
        assertEquals("only", suggest("", listOf("only")))
        assertEquals(null, suggest("", listOf("one", "two")))
        assertEquals(null, suggest("", emptyList()))
    }

    @Test
    fun suggestRejectsAWhollyDifferentShortToken() {
        // Regression: a short candidate must not be suggested for a token every character of which is an edit
        // (a 2-char alias no longer matches an unrelated 2-char word); a single-typo near-miss still fires.
        assertEquals(null, suggest("xy", listOf("ls", "rm")))
        assertEquals("ls", suggest("lx", listOf("ls", "rm")))
    }

    @Test
    fun unknownSubcommandNeverSuggestsAHiddenSubcommand() {
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
    fun badOptionBeforeValidSubcommandBlamesTheOption() {
        // Regression: a bad option ahead of a REAL subcommand must blame the option, not the subcommand.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("--wat", "ping"))).error
        assertEquals(CliError.UnknownOption("--wat"), err)
    }

    @Test
    fun leadingUnknownPositionalBlamesTheSubcommand() {
        // First token is a non-flag: it is the leftmost offender, reported as an unknown subcommand.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("bogus", "--wat"))).error
        assertEquals(CliError.UnknownSubcommand("todo", "bogus"), err)
    }

    @Test
    fun postEndOfOptionsFlagShapedTokenIsAPositionalSubcommand() {
        // After --, a flag-shaped token is positional, so it is an unknown subcommand, never an unknown option.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("--", "--x"))).error
        assertEquals(CliError.UnknownSubcommand("todo", "--x"), err)
    }
}
