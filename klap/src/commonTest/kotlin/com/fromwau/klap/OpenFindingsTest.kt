package com.fromwau.klap

import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/*
 * Reproductions of the review findings, each asserting the behaviour klap's own docs or POSIX promise
 * and each guarding the defect it was written red against.
 */

private enum class Shade { DARK, LIGHT }

private fun Cli.completionsFor(vararg words: String): List<String> = completeCandidates(words.toList()).map { it.value }

class GlobalOptionArgvOrderTest {

    @Test
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
    fun theCpShapeStillOffersFileCompletion() {
        val tree = cli("cp") {
            command("go") {
                argument("source").file().multiple(min = 1)
                argument("dest").file()
                action { Ok("") }
            }
        }
        assertEquals(listOf(COMPLETE_FILES), tree.completionsFor("go", "f1", "f2", ""))
    }

    private fun cpShape(): Cli = cli("cp") {
        command("go") {
            argument("source").choice("S1", "S2").multiple(min = 1)
            argument("dest").choice("D1", "D2")
            action { Ok("") }
        }
    }

    @Test
    fun operandsPastTheSlotCountResolveTheWayTheBindWould() {
        // The bind hands a line's last operand to the trailing fixed slot, so the cursor's word is dest.
        assertEquals(listOf("D1", "D2"), cpShape().completionsFor("go", "a", "b", ""))
    }

    @Test
    fun theFirstOperandStillFillsTheVariadicsMinimum() {
        // dest cannot claim the only operand while source still owes one, since that line does not parse.
        assertEquals(listOf("S1", "S2"), cpShape().completionsFor("go", ""))
    }
}

class AbbreviatedOptionCompletionTest {

    @Test
    fun anAbbreviatedOptionOffersItsOwnValues() {
        val tree = cli("tool") {
            abbreviation = Abbreviation.Options
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
    fun aMetaOptionMissingItsValueNamesTheDashedSpelling() {
        val tree = cli("app") {
            command("c") { action { Ok("") } }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("--color"), t)
        assertEquals("error: option --color requires a value\n", t.err.toString())
    }
}
