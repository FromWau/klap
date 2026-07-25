package com.fromwau.klap

import com.fromwau.klap.internal.render.argSummary
import com.fromwau.klap.internal.render.helpText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GroupCapturesItsDeclarationsTest {

    @Test
    fun aHandleDeclaredInsideGroupBindsToAPlainVal() {
        // The point of the contract: no `lateinit var`, no hand-written type. Before this, capturing a
        // grouped handle meant hoisting it out and spelling Opt<List<String>> yourself.
        val tree = cli("app") {
            command("build") {
                val jobs: Opt<Int>
                val tags: Opt<List<String>>
                group("Tuning") {
                    jobs = option("--jobs", "-j", help = "parallelism").int().default(1)
                    tags = option("--tag", "-t", help = "labels").multiple()
                }
                action { Ok("jobs=${jobs()} tags=${tags()}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("build", "-j", "4", "-t", "a", "-t", "b"), t)
        assertEquals("jobs=4 tags=[a, b]\n", t.out.toString())
    }

    @Test
    fun groupReturnsItsBlocksValue() {
        val tree = cli("app") {
            command("build") {
                val jobs = group("Tuning") { option("--jobs", "-j", help = "parallelism").int().default(1) }
                action { Ok("jobs=${jobs()}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("build", "--jobs", "8"), t)
        assertEquals("jobs=8\n", t.out.toString())
    }

    @Test
    fun groupStillScopesTheHelpHeading() {
        val cmd = cli("app") {
            command("build") {
                group("Tuning") { option("--jobs", "-j", help = "parallelism") }
                option("--out", "-o", help = "output")
                action { Ok("") }
            }
        }.subcommand("build")!!
        val help = cmd.helpText("app build")
        assertContains(help, "Tuning:")
        assertContains(help, "--jobs")
    }

    @Test
    fun aThrowingGroupBlockDoesNotStrandLaterInputsInThatSection() {
        // try/finally: the section must be restored even if the block blows up mid-declaration.
        val cmd = cli("app") {
            command("build") {
                runCatching { group("Tuning") { error("boom") } }
                option("--out", "-o", help = "output")
                action { Ok("") }
            }
        }.subcommand("build")!!
        assertContains(cmd.helpText("app build"), "-o, --out")
    }
}

class MetavarTest {

    @Test
    fun placeholderNamesAnOptionsValueInHelp() {
        val cmd = cli("app") {
            command("run") {
                option("--out", "-o", help = "where to write").placeholder("FILE")
                action { Ok("") }
            }
        }.subcommand("run")!!
        assertContains(cmd.helpText("app run"), "--out <FILE>")
    }

    @Test
    fun placeholderOverridesAChoiceListInTheSignature() {
        // A long choice list in the signature column widens every other row; placeholder is the way out.
        val cmd = cli("app") {
            command("run") {
                option("--level", "-l", help = "verbosity").choice("trace", "debug", "info", "warn", "error")
                    .placeholder("LEVEL")
                action { Ok("") }
            }
        }.subcommand("run")!!
        val help = cmd.helpText("app run")
        assertContains(help, "--level <LEVEL>")
        // The choices are still discoverable, just not in the signature column.
        assertContains(help, "trace")
    }

    @Test
    fun placeholderNamesAPositionalInUsageAndItsRow() {
        val cmd = cli("app") {
            command("run") {
                argument("path", "what to run").placeholder("SCRIPT")
                action { Ok("") }
            }
        }.subcommand("run")!!
        assertEquals("<SCRIPT>", cmd.argSummary())
        assertContains(cmd.helpText("app run"), "<SCRIPT>")
    }

    @Test
    fun withoutMetavarNothingChanges() {
        val cmd = cli("app") {
            command("run") {
                option("--out", "-o", help = "where to write")
                argument("path", "what to run")
                action { Ok("") }
            }
        }.subcommand("run")!!
        assertContains(cmd.helpText("app run"), "--out <value>")
        assertEquals("<path>", cmd.argSummary())
    }
}

class HelpFollowsDeclarationOrderTest {

    private fun cmd() = cli("app") {
        command("run") {
            // Deliberately interleaved: an author groups related inputs by meaning, not by whether the
            // library happens to call them a flag or an option.
            flag("--verbose", "-v", help = "chatty")
            option("--out", "-o", help = "output")
            flag("--quiet", "-q", help = "silent")
            option("--level", "-l", help = "verbosity")
            action { Ok("") }
        }
    }.subcommand("run")!!

    @Test
    fun optionsAndFlagsInterleaveAsDeclared() {
        val help = cmd().helpText("app run")
        val order = listOf("--verbose", "--out", "--quiet", "--level").map { help.indexOf(it) }
        assertEquals(order.sorted(), order, "help reordered the author's declarations:\n$help")
        order.forEach { assertEquals(true, it >= 0) }
    }
}
