package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AbbreviationModeTest {

    private fun tree(mode: Abbreviation) = cli("t") {
        abbreviation = mode
        val recursive = flag("--recursive", "-r")
        val reference = option("--reference")
        argument("file").multiple()
        action<String>(human = { it }) { Ok("r=${recursive()} ref=${reference()}") }
    }

    @Test
    fun noneRefusesALongOptionPrefix() {
        val err = assertIs<Result.Error<CliError>>(tree(Abbreviation.None).parse(listOf("--recu", "f"))).error
        assertEquals(CliError.UnknownOption("--recu", "--recursive"), err)
    }

    @Test
    fun noneStillBindsTheFullSpelling() {
        assertEquals("r=true ref=null", tree(Abbreviation.None).bindText("--recursive", "f"))
        assertEquals("r=false ref=x", tree(Abbreviation.None).bindText("--reference", "x", "f"))
    }

    @Test
    fun noneNeverReportsAnAmbiguity() {
        // Nothing infers, so a prefix reaching two spellings is simply not a spelling.
        val err = assertIs<Result.Error<CliError>>(tree(Abbreviation.None).parse(listOf("--re", "f"))).error
        assertIs<CliError.UnknownOption>(err)
    }

    @Test
    fun longOptionsInfersALongOptionPrefix() {
        assertEquals("r=true ref=null", tree(Abbreviation.Options).bindText("--recu", "f"))
        assertEquals(
            CliError.AmbiguousOption("--re", listOf("--recursive", "--reference")),
            assertIs<Result.Error<CliError>>(tree(Abbreviation.Options).parse(listOf("--re", "f"))).error,
        )
    }

    @Test
    fun noneKeepsTheShortAndTheFullBuiltinSpellings() {
        val strict = cli("t") {
            abbreviation = Abbreviation.None
            version = "1.0"
            action<String>(human = { it }) { Ok("ran") }
        }
        assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(strict.parse(listOf("-h"))).value)
        assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(strict.parse(listOf("--help"))).value)
        assertIs<Invocation.ShowVersion>(
            assertIs<Result.Success<Invocation>>(strict.parse(listOf("--version"))).value,
        )
        assertIs<CliError.UnknownOption>(
            assertIs<Result.Error<CliError>>(strict.parse(listOf("--vers"))).error,
        )
    }

    @Test
    fun noneRefusesAGlobalPrefixInEveryPosition() {
        val tree = cli("app") {
            abbreviation = Abbreviation.None
            val header = globalOption("--header")
            command("build") { action<String>(human = { it }) { Ok("header=${header()}") } }
        }
        // The pre-strip runs before the walk and must obey the mode too, or a global would infer where a
        // local option does not.
        assertEquals(
            CliError.UnknownOption("--hea", "--header"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--hea", "x"))).error,
        )
        assertEquals(
            CliError.UnknownOption("--hea", "--header"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--hea", "x", "build"))).error,
        )
        assertEquals("header=x", tree.bindText("build", "--header", "x"))
    }

    @Test
    fun theDefaultIsWhateverTheRootDeclares() {
        // Two roots differing only in the switch must disagree on the same line.
        assertEquals("r=true ref=null", tree(Abbreviation.Options).bindText("--recu", "f"))
        assertIs<Result.Error<CliError>>(tree(Abbreviation.None).parse(listOf("--recu", "f")))
    }

    private fun head(mode: Abbreviation) = cli("head") {
        abbreviation = mode
        val lines = option("--lines", "-n")
        numericAlias(lines)
        argument("file").multiple()
        action<String>(human = { it }) { Ok("n=${lines()}") }
    }

    @Test
    fun aNumericAliasIsUntouchedByEveryMode() {
        // The alias is short-side, so no mode may reach it: `-5` is `-n 5` in all three.
        for (mode in Abbreviation.entries) {
            assertEquals("n=5", head(mode).bindText("-5", "f"), mode.name)
            assertEquals("n=5", head(mode).bindText("-n", "5", "f"), mode.name)
        }
    }

    private fun dispatcher(mode: Abbreviation) = cli("app") {
        abbreviation = mode
        command("status") { action<String>(human = { it }) { Ok("status") } }
        command("stash") { action<String>(human = { it }) { Ok("stash") } }
        command("list") {
            aliases = listOf("ls")
            action<String>(human = { it }) { Ok("list") }
        }
        command("listen") { action<String>(human = { it }) { Ok("listen") } }
    }

    @Test
    fun onlyAllInfersASubcommandPrefix() {
        assertEquals("listen", dispatcher(Abbreviation.All).bindText("liste"))
        for (mode in listOf(Abbreviation.None, Abbreviation.Options)) {
            assertEquals(
                CliError.UnknownSubcommand("app", "liste", "listen"),
                assertIs<Result.Error<CliError>>(dispatcher(mode).parse(listOf("liste"))).error,
                mode.name,
            )
        }
    }

    @Test
    fun anAmbiguousSubcommandPrefixNamesEveryPossibility() {
        assertEquals(
            CliError.AmbiguousSubcommand("app", "st", listOf("status", "stash")),
            assertIs<Result.Error<CliError>>(dispatcher(Abbreviation.All).parse(listOf("st"))).error,
        )
    }

    @Test
    fun anExactSubcommandBeatsBeingAPrefixOfAnother() {
        // `list` is a strict prefix of `listen`, so prefix matching alone would call it ambiguous.
        assertEquals("list", dispatcher(Abbreviation.All).bindText("list"))
    }

    @Test
    fun anAliasTakesPartInThePoolWithoutFakingAnAmbiguity() {
        // `l` reaches `list`, `ls` and `listen`. The first two name ONE command, so the only real
        // possibilities are two, not three; and `ls` on its own still resolves exactly.
        assertEquals("list", dispatcher(Abbreviation.All).bindText("ls"))
        assertEquals(
            CliError.AmbiguousSubcommand("app", "l", listOf("list", "ls", "listen")),
            assertIs<Result.Error<CliError>>(dispatcher(Abbreviation.All).parse(listOf("l"))).error,
        )
        // A prefix reaching a name AND its own alias is not ambiguous: both name the same command.
        val aliased = cli("app") {
            abbreviation = Abbreviation.All
            command("list") {
                aliases = listOf("listing")
                action<String>(human = { it }) { Ok("list") }
            }
        }
        assertEquals("list", aliased.bindText("lis"))
    }

    @Test
    fun aMissThatIsNoPrefixStillSuggests() {
        // Abbreviation rescues prefixes; suggestion rescues transpositions. They are complementary.
        val err = assertIs<Result.Error<CliError>>(dispatcher(Abbreviation.All).parse(listOf("lsit"))).error
        assertEquals(CliError.UnknownSubcommand("app", "lsit", "list"), err)
    }

    @Test
    fun anInferredSubcommandIsWhereItsOptionsResolve() {
        val tree = cli("app") {
            abbreviation = Abbreviation.All
            command("status") {
                val short = flag("--short", "-s")
                val files = argument("file").multiple()
                action<String>(human = { it }) { Ok("short=${short()} files=${files()}") }
            }
        }
        // The arity pre-pass and the routing walk must reach the same command, or an option declared only
        // on `status` is resolved against the root's surface and its value slot is lost.
        assertEquals("short=true files=[f]", tree.bindText("stat", "-s", "f"))
    }

    private fun priority(mode: Abbreviation) = cli("t") {
        abbreviation = mode
        val level = option("--priority").choice("low", "high", "highest")
        action<String>(human = { it }) { Ok("p=${level()}") }
    }

    @Test
    fun aChoiceValueInfersWhereverAnOptionNameDoes() {
        // GNU couples the two: `ls --color=al` works for the same reason `--colo` does.
        for (mode in listOf(Abbreviation.Options, Abbreviation.All)) {
            assertEquals("p=low", priority(mode).bindText("--priority", "lo"), mode.name)
        }
        assertIs<CliError.InvalidChoice>(
            assertIs<Result.Error<CliError>>(priority(Abbreviation.None).parse(listOf("--priority", "lo"))).error,
        )
    }

    @Test
    fun anAmbiguousChoiceValueNamesEveryPossibility() {
        assertEquals(
            CliError.AmbiguousValue("--priority", "hi", listOf("high", "highest")),
            assertIs<Result.Error<CliError>>(priority(Abbreviation.Options).parse(listOf("--priority", "hi"))).error,
        )
    }

    @Test
    fun anExactChoiceBeatsBeingAPrefixOfAnother() {
        assertEquals("p=high", priority(Abbreviation.Options).bindText("--priority", "high"))
    }

    @Test
    fun choiceAbbreviationComposesWithCaseInsensitiveMatching() {
        // `.choice()` already matches case-insensitively; prefixing is layered on top, not instead of it.
        assertEquals("p=low", priority(Abbreviation.Options).bindText("--priority", "LO"))
        assertEquals("p=high", priority(Abbreviation.Options).bindText("--priority", "HIGH"))
        assertEquals(
            CliError.AmbiguousValue("--priority", "HI", listOf("high", "highest")),
            assertIs<Result.Error<CliError>>(priority(Abbreviation.Options).parse(listOf("--priority", "HI"))).error,
        )
    }

    private enum class Level { LOW, HIGH }

    @Test
    fun anEnumValueInfersToo() {
        val tree = cli("t") {
            abbreviation = Abbreviation.Options
            val level = option("--level").enum<Level>()
            action<String>(human = { it }) { Ok("l=${level()}") }
        }
        assertEquals("l=HIGH", tree.bindText("--level", "h"))
    }

    @Test
    fun aBuiltinChoiceValueFollowsTheSameRule() {
        // A user cannot tell klap's own --color from an option the app declared, so it must not resolve
        // by a different rule; real `ls --color=al` binds `always`.
        val tree = cli("t") {
            abbreviation = Abbreviation.Options
            action<String>(human = { it }) { Ok("ran") }
        }
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("--color", "al")))
        assertIs<CliError.AmbiguousValue>(
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--color", "a"))).error,
        )
    }

    @Test
    fun anUnmatchedValueStillReportsTheChoicesAndSuggests() {
        // Abbreviation rescues prefixes; the existing InvalidChoice suggestion rescues near-misses.
        val err = assertIs<Result.Error<CliError>>(priority(Abbreviation.Options).parse(listOf("--priority", "hgih")))
            .error
        assertEquals(CliError.InvalidChoice("--priority", "hgih", listOf("low", "high", "highest"), "high"), err)
    }

    @Test
    fun aValueInAnOperandSlotInfersThroughTheSameConverter() {
        val tree = cli("t") {
            abbreviation = Abbreviation.Options
            val mode = argument("mode").choice("fast", "slow")
            action<String>(human = { it }) { Ok("m=${mode()}") }
        }
        assertEquals("m=fast", tree.bindText("fa"))
    }

    @Test
    fun aRefusedPrefixStillPointsAtTheOneSpellingItReached() {
        val strict = cli("t") {
            abbreviation = Abbreviation.None
            action<String>(human = { it }) { Ok("ran") }
        }
        // Every one of these is further from --help than the edit-distance bound allows, and under None
        // none of them resolves; a prefix reaching exactly one spelling is a certainty, not a guess.
        for (typed in listOf("--h", "--he", "--hel")) {
            assertEquals(
                CliError.UnknownOption(typed, "--help"),
                assertIs<Result.Error<CliError>>(strict.parse(listOf(typed))).error,
                typed,
            )
        }
    }

    @Test
    fun aPrefixReachingTwoSpellingsSuggestsTheNearest() {
        val strict = cli("t") {
            abbreviation = Abbreviation.None
            option("--header")
            action<String>(human = { it }) { Ok("ran") }
        }
        // A tie is not resolvable, but the nearest spelling is still the most useful hint: excluding both
        // tied candidates from the scan would hand the answer to something unrelated instead.
        assertEquals(
            CliError.UnknownOption("--he", "--help"),
            assertIs<Result.Error<CliError>>(strict.parse(listOf("--he"))).error,
        )
    }

    @Test
    fun aTiedPrefixNeverSurrendersToAnUnrelatedCandidate() {
        val strict = cli("t") {
            abbreviation = Abbreviation.None
            option("--sort")
            option("--sort-by")
            action<String>(human = { it }) { Ok("ran") }
        }
        // "--sor" prefixes both --sort and --sort-by; excluding the tie from the scan would let the
        // unrelated built-in --json (distance 2) win over --sort (distance 1).
        assertEquals(
            CliError.UnknownOption("--sor", "--sort"),
            assertIs<Result.Error<CliError>>(strict.parse(listOf("--sor"))).error,
        )
    }

    @Test
    fun aRefusedSubcommandPrefixSuggestsToo() {
        val strict = cli("app") {
            abbreviation = Abbreviation.None
            command("status") { action<String>(human = { it }) { Ok("status") } }
        }
        assertEquals(
            CliError.UnknownSubcommand("app", "st", "status"),
            assertIs<Result.Error<CliError>>(strict.parse(listOf("st"))).error,
        )
    }
}
