package com.fromwau.klap

import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
 * Executable reproductions of the open review findings. Each test asserts the behaviour klap's own docs
 * or POSIX promise, and each is @Ignore'd with the value it actually produces today; deleting the
 * annotation turns it back into the red test that proves the defect, and a fix flips it green for good.
 */

private enum class Shade { DARK, LIGHT }

private fun Cli.completionsFor(vararg words: String): List<String> = completeCandidates(words.toList()).map { it.value }

class GlobalOptionArgvOrderTest {

    @Test
    @Ignore // binds retries=5: occurrences merge by append order, so the mixed cluster always sorts last
    fun theLastOccurrenceInArgvWins() {
        val tree = cli("app") {
            val retries = globalOption("--retries", "-r").int().default(0)
            command("build") {
                flag("--force", "-f")
                action { Ok("retries=${retries()}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("build", "-fr", "5", "--retries", "9"), t)
        assertEquals("retries=9\n", t.out.toString())
    }

    @Test
    @Ignore // binds tags=[b, a]: the same append-order merge silently reorders an ordered repeatable
    fun aRepeatableGlobalKeepsArgvOrder() {
        val tree = cli("app") {
            val tags = globalOption("--tag", "-t").multiple()
            command("build") {
                flag("--force", "-f")
                action { Ok("tags=${tags()}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("build", "-ft", "a", "--tag", "b"), t)
        assertEquals("tags=[a, b]\n", t.out.toString())
    }
}

class RequiredIfTriggerReachTest {

    @Test
    @Ignore // succeeds with token=null: the check reads the leaf's own flags, where a global never lands
    fun aGlobalFlagTriggersTheRequirementItAdvertises() {
        val tree = cli("app") {
            val verbose = globalFlag("--verbose")
            command("c") {
                val token = option("--token").requiredIf(verbose)
                action { Ok("token=${token()}") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("c", "--verbose"))).error
        assertEquals(CliError.MissingRequiredOption("--token"), err)
    }

    @Test
    @Ignore // errors MissingRequiredOption: raw hit counts count both polarities, so opting out demands the value
    fun theNegativeSpellingDoesNotTriggerTheRequirement() {
        val tree = cli("app") {
            command("c") {
                val remote = flag("--remote", "-r").negatable()
                val token = option("--token").requiredIf(remote)
                action { Ok("token=${token()}") }
            }
        }
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("c", "--no-remote")))
    }
}

class NumericAliasClusterTest {

    @Test
    @Ignore // binds zero=false lines=20: the guideline-14 check sees only local shorts, so the global `2` is invisible
    fun aMixedGlobalLocalClusterOutranksTheAlias() {
        val tree = cli("app") {
            globalFlag("--two", "-2")
            command("head") {
                val zero = flag("--zero", "-0")
                val lines = option("--lines", "-n").int()
                numericAlias(lines)
                action { Ok("zero=${zero()} lines=${lines()}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("head", "-20"), t)
        assertEquals("zero=true lines=null\n", t.out.toString())
    }
}

class ArgDefaultTypeTest {

    @Test
    @Ignore // throws ClassCastException: the default is snapshotted as String and never runs the converter
    fun aDefaultDeclaredBeforeTheConverterIsConverted() {
        val tree = cli("app") {
            command("c") {
                val n = argument("n").default("0").int()
                action { Ok("n+1=${n() + 1}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("c"), t)
        assertEquals("n+1=1\n", t.out.toString())
    }

    @Test
    @Ignore // binds null into a non-null accessor: the removed slot is nulled regardless of Cardinality.Default
    fun absentWhenThenDefaultBindsTheDefaultNotNull() {
        val tree = cli("app") {
            command("c") {
                val ref = option("--reference")
                val mode = argument("mode").absentWhen(ref).default("755")
                val files = argument("file").multiple(min = 1)
                action { Ok("modeLen=${mode().length} files=${files()}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("c", "--reference=r", "a"), t)
        assertEquals("modeLen=3 files=[a]\n", t.out.toString())
    }
}

class RestatedChoiceSetTest {

    @Test
    @Ignore // rejects every input: the displayed set is overwritten while the matchers compose to an unsatisfiable AND
    fun aSecondChoiceSetReplacesTheFirst() {
        val tree = cli("app") {
            command("c") {
                val m = argument("m").choice("a", "b").enum<Shade>()
                action { Ok("m=${m()}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("c", "dark"), t)
        assertEquals("m=DARK\n", t.out.toString())
        assertEquals("", t.err.toString())
    }
}

class LastWinsCompletionTest {

    @Test
    @Ignore // offers only [--interactive, -i, ...]: the rule-out set ignores arity, which the parse gates on
    fun aLastWinsMemberStaysOfferedAfterItsSiblingIsTyped() {
        val tree = cli("rm") {
            command("go") {
                val interactive = flag("--interactive", "-i")
                val force = flag("--force", "-f")
                lastWins(interactive, force)
                action { Ok("") }
            }
        }
        val candidates = tree.completionsFor("go", "-i", "-")
        assertTrue("--force" in candidates, "expected --force among $candidates")
    }
}

class VariadicThenFixedCompletionTest {

    @Test
    @Ignore // offers nothing at all: the slot picker falls back only when the LAST slot is variadic
    fun operandsPastTheSlotCountStillComplete() {
        val tree = cli("cp") {
            command("go") {
                argument("source").multiple(min = 1)
                argument("dest")
                action { Ok("") }
            }
        }
        val candidates = tree.completionsFor("go", "a", "b", "")
        assertTrue(candidates.isNotEmpty(), "expected candidates for the trailing operand, got $candidates")
    }
}

class AbbreviatedOptionCompletionTest {

    @Test
    @Ignore // offers [A1, A2], the positional's: completion resolves the token exact-only, the parser by prefix
    fun anAbbreviatedOptionOffersItsOwnValues() {
        val tree = cli("tool") {
            inference = Inference.Options
            command("go") {
                option("--sort").choice("name", "size")
                argument("a").choice("A1", "A2")
                action { Ok("") }
            }
        }
        assertEquals(listOf("name", "size"), tree.completionsFor("go", "--sor", ""))
    }
}

class MetaOptionErrorTextTest {

    @Test
    @Ignore // renders "option color requires a value": the built-in pool key carries no dashes
    fun aMetaOptionMissingItsValueNamesTheDashedSpelling() {
        val tree = cli("app") {
            command("c") { action { Ok("") } }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("--color"), t)
        assertEquals("error: option --color requires a value\n", t.err.toString())
    }
}
