package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConditionalOperandTest {

    private fun chmodLike() = cli("chmod") {
        val reference = option("--reference")
        val mode = argument("mode").absentWhen(reference)
        val files = argument("file").multiple(min = 1)
        action<String>(human = { it }) { Ok("mode=${mode()} files=${files()}") }
    }

    @Test
    fun theSlotBindsNormallyWhenTheTriggerIsAbsent() {
        assertEquals("mode=755 files=[a, b]", chmodLike().bindText("755", "a", "b"))
    }

    @Test
    fun theSlotDisappearsWhenTheTriggerIsGiven() {
        assertEquals("mode=null files=[a, b]", chmodLike().bindText("--reference=r", "a", "b"))
    }

    @Test
    fun theFirstOperandIsNotSwallowedIntoTheAbsentSlot() {
        // The bug .optional() produces: it builds cleanly and then eats the first FILE.
        assertEquals("mode=null files=[notes.txt]", chmodLike().bindText("--reference=r", "notes.txt"))
    }

    @Test
    fun aMissingRequiredSlotStillErrorsWhenTheTriggerIsAbsent() {
        // "a" fills mode, so the file operand is left an EMPTY slice rather than a short one, which klap
        // reports as MissingArgument (see bindPositionals' own "keyed on min alone" note).
        val err = assertIs<Result.Error<CliError>>(chmodLike().parse(listOf("a"))).error
        assertEquals(CliError.MissingArgument("chmod", "file"), err)
    }

    private fun rmLike() = cli("rm") {
        val force = flag("--force", "-f")
        val files = argument("file").multiple(min = 1).requiredUnless(force)
        action<String>(human = { it }) { Ok("files=${files()}") }
    }

    @Test
    fun aMinimumHoldsWhenTheTriggerIsAbsent() {
        val err = assertIs<Result.Error<CliError>>(rmLike().parse(emptyList())).error
        assertEquals(CliError.MissingArgument("rm", "file"), err)
        assertEquals(USAGE_ERROR_EXIT, err.exitCode)
    }

    @Test
    fun aMinimumRelaxesToZeroWhenTheTriggerIsGiven() {
        assertEquals("files=[]", rmLike().bindText("-f"))
    }

    /** Help lines with their alignment padding collapsed, so a row assertion pins wording, not column width. */
    private fun Cli.helpLines(): List<String> =
        helpText().lines().map { it.trim().replace(Regex(" {2,}"), "  ") }

    private fun List<String>.rowMentioning(word: String): String =
        first { word in it && !it.startsWith("usage:") }

    @Test
    fun theUsageLineAndTheArgumentRowBothSayWhenTheSlotDisappears() {
        val lines = chmodLike().helpLines()
        assertEquals("usage: chmod [<mode>] <file>... [options]", lines.first())
        assertEquals("[<mode>]  (absent with --reference)", lines.rowMentioning("mode"))
    }

    @Test
    fun theUsageLineAndTheArgumentRowBothSayWhenTheMinimumRelaxes() {
        val lines = rmLike().helpLines()
        // `[file...]` is real rm's own synopsis (`rm [OPTION]... [FILE]...`), so the relaxed minimum has to
        // reach the usage line for the two to agree.
        assertEquals("usage: rm [file...] [options]", lines.first())
        assertEquals("<file>  (optional with --force; repeatable, min 1)", lines.rowMentioning("file"))
    }

    @Test
    fun aVariadicSlotCannotDisappear() {
        // Declared AFTER .absentWhen(), when the cardinality it rejects is not set yet: the rule has to
        // hold at build time, or the whole variadic silently vanishes on a line that names the trigger.
        val e = assertFailsWith<IllegalArgumentException> {
            cli("chmod") {
                val reference = option("--reference")
                val files = argument("file").absentWhen(reference).multiple(min = 1)
                action<String>(human = { it }) { Ok("files=${files()}") }
            }
        }
        assertTrue("combines .absentWhen(--reference) with .multiple()" in e.message.orEmpty(), e.message)
    }

    @Test
    fun aSlotWithNoDeclaredMinimumCannotRelaxOne() {
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val force = flag("--force", "-f")
                val out = argument("out").requiredUnless(force)
                action<String>(human = { it }) { Ok("out=${out()}") }
            }
        }
        assertTrue(".requiredUnless(--force) on a slot that is not .multiple()" in e.message.orEmpty(), e.message)
    }

    private fun cpLike() = cli("cp") {
        val targetDirectory = option("--target-directory", "-t")
        val noTargetDirectory = flag("--no-target-directory", "-T")
        lastWins(targetDirectory, noTargetDirectory)
        val source = argument("source").multiple(min = 1)
        val dest = argument("dest").absentWhen(targetDirectory)
        action<String>(human = { it }) {
            Ok("t=${targetDirectory()} T=${noTargetDirectory()} src=${source()} dest=${dest()}")
        }
    }

    @Test
    fun aTriggerThatLostItsOverrideSetLeavesTheSlotInPlace() {
        // -T overrode -t, so -t reads back absent; a slot removed by a trigger that no longer holds would
        // silently swallow `b` into the variadic.
        assertEquals("t=null T=true src=[a] dest=b", cpLike().bindText("-t", "dir", "-T", "a", "b"))
    }

    @Test
    fun aTriggerThatWonItsOverrideSetStillRemovesTheSlot() {
        assertEquals("t=dir T=false src=[a, b] dest=null", cpLike().bindText("-T", "-t", "dir", "a", "b"))
    }

    private fun rmLikeWithOverride() = cli("rm") {
        val force = flag("--force", "-f")
        val interactive = flag("--interactive", "-i")
        lastWins(force, interactive)
        val files = argument("file").multiple(min = 1).requiredUnless(force)
        action<String>(human = { it }) { Ok("force=${force()} files=${files()}") }
    }

    @Test
    fun aTriggerThatLostItsOverrideSetLeavesTheMinimumStanding() {
        // The same shape as the removed slot above: relaxing the minimum here would accept `rm -f -i`
        // with no operand while force() reads false, a line neither reading of it allows.
        val err = assertIs<Result.Error<CliError>>(rmLikeWithOverride().parse(listOf("-f", "-i"))).error
        assertEquals(CliError.MissingArgument("rm", "file"), err)
    }

    @Test
    fun aTriggerThatWonItsOverrideSetStillRelaxesTheMinimum() {
        assertEquals("force=true files=[]", rmLikeWithOverride().bindText("-i", "-f"))
    }

    @Test
    fun aTriggerDeclaredOnAnotherCommandIsRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                var foreign: Opt<String?>? = null
                command("one") {
                    foreign = option("--reference")
                    action { Ok("") }
                }
                command("two") {
                    argument("mode").absentWhen(foreign!!)
                    action { Ok("") }
                }
            }
        }
        assertTrue("not declared on 'two'" in e.message.orEmpty(), e.message)
    }

    @Test
    fun aGlobalTriggerIsRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val force = globalFlag("--force", "-f")
                command("go") {
                    argument("file").multiple(min = 1).requiredUnless(force)
                    action { Ok("") }
                }
            }
        }
        assertTrue("not declared on 'go'" in e.message.orEmpty(), e.message)
    }
}
