package com.fromwau.klap

import com.fromwau.klap.internal.parse.LongMatch
import com.fromwau.klap.internal.parse.resolveLong
import com.fromwau.klap.internal.render.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AbbreviationTest {

    private val pool = listOf("recursive", "reference", "verbose", "sort", "sort-by")

    @Test
    fun anExactSpellingResolvesToItself() {
        assertEquals(LongMatch.Exact("verbose"), resolveLong("verbose", pool))
    }

    @Test
    fun anUnambiguousPrefixResolves() {
        assertEquals(LongMatch.Prefix("verbose"), resolveLong("verb", pool))
        assertEquals(LongMatch.Prefix("recursive"), resolveLong("recu", pool))
    }

    @Test
    fun anAmbiguousPrefixNamesEveryPossibilityInDeclarationOrder() {
        val match = assertIs<LongMatch.Ambiguous>(resolveLong("re", pool))
        assertEquals(listOf("recursive", "reference"), match.candidates)
    }

    @Test
    fun anExactSpellingBeatsBeingAPrefixOfAnother() {
        // `sort` is a strict prefix of `sort-by`, so prefix matching alone would call it ambiguous.
        assertEquals(LongMatch.Exact("sort"), resolveLong("sort", pool))
    }

    @Test
    fun anUnmatchedSpellingIsNone() {
        assertEquals(LongMatch.None, resolveLong("nope", pool))
        assertEquals(LongMatch.None, resolveLong("", pool))
    }

    @Test
    fun aDuplicatedCandidateDoesNotFakeAnAmbiguity() {
        // The same spelling can reach the pool twice (a local spec and the built-in list both offering it);
        // two entries naming ONE option are not two possibilities.
        assertEquals(LongMatch.Prefix("verbose"), resolveLong("verb", listOf("verbose", "verbose")))
    }

    private fun tree() = cli("t") {
        val recursive = flag("--recursive", "-r")
        val reference = option("--reference")
        val verbose = flag("--verbose", "-v").negatable(default = false)
        argument("file").multiple()
        action<String>(human = { it }) {
            Ok("r=${recursive()} ref=${reference()} v=${verbose()}")
        }
    }

    @Test
    fun anUnambiguousPrefixBindsTheOption() {
        assertEquals("r=true ref=null v=false", tree().bindText("--recu", "f"))
        assertEquals("r=false ref=x v=false", tree().bindText("--refe", "x", "f"))
    }

    @Test
    fun anAmbiguousPrefixIsAUsageError() {
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("--re", "f"))).error
        assertEquals(CliError.AmbiguousOption("--re", listOf("--recursive", "--reference")), err)
    }

    @Test
    fun anAbbreviationCarriesAnInlineValue() {
        assertEquals("r=false ref=x v=false", tree().bindText("--refe=x", "f"))
    }

    @Test
    fun aGeneratedNegationAbbreviatesToo() {
        assertEquals("r=false ref=null v=false", tree().bindText("--no-verb", "f"))
    }

    @Test
    fun aBuiltinAbbreviates() {
        val versioned = cli("t") {
            version = "1.0"
            action<String>(human = { it }) { Ok("ran") }
        }
        assertIs<Invocation.ShowVersion>(assertIs<Result.Success<Invocation>>(versioned.parse(listOf("--vers"))).value)
    }

    @Test
    fun aBuiltinTakesPartInAmbiguity() {
        val withHeader = cli("t") {
            option("--header")
            action<String>(human = { it }) { Ok("ran") }
        }
        val err = assertIs<Result.Error<CliError>>(withHeader.parse(listOf("--he", "x"))).error
        assertEquals(CliError.AmbiguousOption("--he", listOf("--header", "--help")), err)
    }

    @Test
    fun theInjectedHelpAllAnswersToItsFullSpellingOnly() {
        val tree = cli("t") {
            command("build") { action<String>(human = { it }) { Ok("built") } }
        }
        // --help-all is klap's, not the author's, so it takes no part in prefix resolution: letting it claim
        // the space it shares with --help would cost every CLI in the world its `--h`.
        for (typed in listOf("--h", "--he", "--hel")) {
            val shown = assertIs<Result.Success<Invocation>>(tree.parse(listOf(typed))).value
            assertFalse(assertIs<Invocation.ShowHelp>(shown).recursive, typed)
        }
        // Spelled out, it still reaches the recursive form.
        val all = assertIs<Result.Success<Invocation>>(tree.parse(listOf("--help-all"))).value
        assertTrue(assertIs<Invocation.ShowHelp>(all).recursive)
    }

    @Test
    fun aHiddenInputMatchesButIsStillNeverSuggested() {
        val tree = cli("t") {
            val secret = flag("--secret").hidden()
            val send = flag("--send")
            action<String>(human = { it }) { Ok("secret=${secret()} send=${send()}") }
        }
        // Hiding an input removes it from help, not from the parser: an abbreviation that could reach it
        // must not resolve past it to the visible one, or the same line would bind differently on a tree
        // that hides nothing.
        assertEquals(
            CliError.AmbiguousOption("--se", listOf("--secret", "--send")),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--se"))).error,
        )
        assertEquals("secret=true send=false", tree.bindText("--sec"))
        // Did-you-mean stays blind to it, which is the whole point of hiding.
        assertEquals(
            CliError.UnknownOption("--secrte", null),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--secrte"))).error,
        )
    }

    @Test
    fun aGlobalAbbreviatesAndSharesOnePoolWithTheBuiltins() {
        val tree = cli("app") {
            val header = globalOption("--header")
            command("build") {
                action<String>(human = { it }) { Ok("header=${header()}") }
            }
        }
        assertEquals("header=x", tree.bindText("build", "--hea", "x"))
        // The pre-strip resolves a global before the walk knows its command, so it is the one place a
        // built-in could be shadowed by a global that abbreviates the same way.
        assertEquals(
            CliError.AmbiguousOption("--he", listOf("--header", "--help")),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--he", "x"))).error,
        )
    }

    @Test
    fun aGroupNodeReportsTheSameAmbiguityALeafWould() {
        val tree = cli("app") {
            globalOption("--header")
            command("build") { action<String>(human = { it }) { Ok("built") } }
        }
        // A group binds nothing, so the token is an error either way; it must not be an error that names
        // one of the spellings as if the other did not exist.
        assertEquals(
            CliError.AmbiguousOption("--he", listOf("--header", "--help")),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--he"))).error,
        )
    }

    private fun head() = cli("head") {
        version = "9.11"
        val verbose = flag("--verbose", "-v")
        argument("file").multiple()
        action<String>(human = { it }) { Ok("v=${verbose()}") }
    }

    @Test
    fun aCommandDeclaredLongTakesPartInTheBuiltinPreStrip() {
        // Real head: `head --ver f` reports "option '--ver' is ambiguous; possibilities: '--verbose'
        // '--version'". The pre-strip runs before the walk knows its command, so without every declared long
        // in its pool the built-in would win a spelling the command it reaches calls ambiguous.
        val head = head()
        assertEquals(
            CliError.AmbiguousOption("--ver", listOf("--verbose", "--version")),
            assertIs<Result.Error<CliError>>(head.parse(listOf("--ver", "f"))).error,
        )
        // Neither half is lost: both spellings still resolve on their own.
        assertEquals("v=true", head.bindText("--verb", "f"))
        assertIs<Invocation.ShowVersion>(assertIs<Result.Success<Invocation>>(head.parse(listOf("--vers"))).value)
    }

    @Test
    fun anAmbiguousAbbreviationRendersEveryPossibility() {
        // The head fixture pins this line as real-tool behaviour and quotes the wording in its `because`,
        // but a parity rejection only claims the line failed; the wording itself is pinned here.
        val err = assertIs<Result.Error<CliError>>(head().parse(listOf("--ver", "f"))).error
        assertEquals(CliError.AmbiguousOption("--ver", listOf("--verbose", "--version")), err)
        assertEquals("option '--ver' is ambiguous; possibilities: '--verbose' '--version'", err.message())
    }

    private fun dispatcher() = cli("app") {
        version = "1.0"
        command("fetch") {
            val header = option("--header")
            action<String>(human = { it }) { Ok("header=${header()}") }
        }
        command("build") {
            val xx = flag("--xx")
            action<String>(human = { it }) { Ok("xx=${xx()}") }
        }
    }

    @Test
    fun helpAbbreviatesAgainstTheCommandTheWalkReached() {
        // --help is resolved AFTER the walk, so it answers to the pool of the command actually reached: one
        // child's --header cannot take --h away from the root or from its siblings.
        assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(dispatcher().parse(listOf("--h"))).value)
        assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(dispatcher().parse(listOf("--he"))).value)
        val onBuild = assertIs<Result.Success<Invocation>>(dispatcher().parse(listOf("build", "--h"))).value
        assertEquals("app build", assertIs<Invocation.ShowHelp>(onBuild).qualifiedName)
        // At `fetch` the token really does reach two spellings, which is what GNU reports there.
        assertEquals(
            CliError.AmbiguousOption("--h", listOf("--header", "--help")),
            assertIs<Result.Error<CliError>>(dispatcher().parse(listOf("fetch", "--h"))).error,
        )
    }

    @Test
    fun anAbbreviationThePreStripDeclinedIsNeverUnknownAtTheCommand() {
        val tree = cli("app") {
            command("fetch") {
                val collate = option("--collate")
                action<String>(human = { it }) { Ok("collate=${collate()}") }
            }
            command("build") { action<String>(human = { it }) { Ok("built") } }
        }
        // --color is stripped before the walk, so `--col` is declined there against the tree's --collate.
        // `build` declares no --collate itself, and must still report what the pre-strip saw rather than
        // claiming a token klap has just refused to resolve does not exist.
        val expected = CliError.AmbiguousOption("--col", listOf("--collate", "--color"))
        assertEquals(expected, assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--col"))).error)
        assertEquals(expected, assertIs<Result.Error<CliError>>(tree.parse(listOf("fetch", "--col"))).error)
        assertEquals("collate=x", tree.bindText("fetch", "--colla", "x"))
    }

    @Test
    fun aGlobalAndACommandLongShareOnePoolInThePreStrip() {
        val tree = cli("app") {
            val sort = globalOption("--sort")
            command("sub") {
                val sortBy = option("--sort-by")
                val files = argument("file").multiple()
                action<String>(human = { it }) { Ok("sort=${sort()} by=${sortBy()} files=${files()}") }
            }
        }
        // The pre-strip BINDS globals, so a pool without the command-declared longs would hand `--sor` to
        // the global outright and leave the leaf's own --sort-by silently unset.
        assertEquals(
            CliError.AmbiguousOption("--sor", listOf("--sort-by", "--sort")),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("sub", "--sor", "x", "f"))).error,
        )
        // Neither half is lost: the exact global spelling and a prefix reaching only the leaf both resolve.
        assertEquals("sort=x by=null files=[f]", tree.bindText("sub", "--sort", "x", "f"))
        assertEquals("sort=null by=y files=[f]", tree.bindText("sub", "--sort-b", "y", "f"))
    }

    @Test
    fun aLocalLongIsNeverEatenByAnAbbreviatedMetaOption() {
        val tree = cli("app") {
            command("sub") {
                val collate = option("--collate")
                val files = argument("file").multiple()
                action<String>(human = { it }) { Ok("collate=${collate()} files=${files()}") }
            }
        }
        // --color, --completion and --docs are stripped before the walk, so an abbreviation of one used to
        // eat both the token AND the operand behind it while leaving the leaf's own option unset.
        assertEquals(
            CliError.AmbiguousOption("--col", listOf("--collate", "--color")),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("sub", "--col", "never"))).error,
        )
        // Ahead of the subcommand the walk stops at the group, which binds nothing either way. It still
        // reports the ambiguity rather than an unknown option: the tree declares `--collate` somewhere, so
        // saying no such option exists would contradict the pre-strip that just declined to resolve it.
        assertEquals(
            CliError.AmbiguousOption("--col", listOf("--collate", "--color")),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--col", "never", "sub"))).error,
        )
        assertEquals("collate=never files=[f]", tree.bindText("sub", "--colla", "never", "f"))
    }

    @Test
    fun anAbbreviatedGlobalThatCollidesWithABuiltinIsAmbiguousInBothPositions() {
        val tree = cli("app") {
            val collate = globalOption("--collate")
            command("sub") {
                action<String>(human = { it }) { Ok("collate=${collate()}") }
            }
        }
        // The pre-strip declines `--col` as ambiguous with the built-in --color, so nothing binds it there.
        // The command's own sift must reach the same verdict: resolving it to the global alone would name an
        // option that pass cannot bind, and the user would be told `--col` is simply unknown.
        val expected = CliError.AmbiguousOption("--col", listOf("--collate", "--color"))
        assertEquals(expected, assertIs<Result.Error<CliError>>(tree.parse(listOf("sub", "--col", "x"))).error)
        assertEquals(expected, assertIs<Result.Error<CliError>>(tree.parse(listOf("--col", "x", "sub"))).error)
        assertEquals("collate=x", tree.bindText("sub", "--colla", "x"))
    }

    @Test
    fun shortsNeverAbbreviate() {
        // A single-dash token is a cluster of one-character shorts, so there is nothing to abbreviate and
        // `-re` must stay two chars rather than resolving to `--recursive`.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("-re", "f"))).error
        assertEquals(CliError.UnknownOption("-e"), err)
    }

    @Test
    fun subcommandNamesNeverAbbreviate() {
        val dispatcher = cli("t") {
            command("status") { action<String>(human = { it }) { Ok("status") } }
        }
        val err = assertIs<Result.Error<CliError>>(dispatcher.parse(listOf("stat"))).error
        assertIs<CliError.UnknownSubcommand>(err)
    }
}
