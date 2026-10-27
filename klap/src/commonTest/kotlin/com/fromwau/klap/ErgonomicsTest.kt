package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.internal.render.argSummary
import com.fromwau.klap.internal.render.helpText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class GroupCapturesItsDeclarationsTest {

    @Test
    fun `a handle declared inside group binds to a plain val`() {
        // The point of the contract: no `lateinit var`, no hand-written type. Before this, capturing a
        // grouped handle meant hoisting it out and spelling Opt<List<String>> yourself.
        val tree = cli("app") {
            command("build") {
                val jobs: Opt<Int>
                val tags: Opt<List<String>>
                group("Tuning") {
                    jobs = option(short = 'j', long = "jobs", help = "parallelism").int().default(1)
                    tags = option(short = 't', long = "tag", help = "labels").multiple()
                }
                action { Ok("jobs=${jobs()} tags=${tags()}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("build", "-j", "4", "-t", "a", "-t", "b"), t)
        assertEquals("jobs=4 tags=[a, b]\n", t.out)
    }

    @Test
    fun `group returns its blocks value`() {
        val tree = cli("app") {
            command("build") {
                val jobs = group("Tuning") { option(short = 'j', long = "jobs", help = "parallelism").int().default(1) }
                action { Ok("jobs=${jobs()}") }
            }
        }
        val t = RecordingTerminal()
        tree.run(arrayOf("build", "--jobs", "8"), t)
        assertEquals("jobs=8\n", t.out)
    }

    @Test
    fun `group still scopes the help heading`() {
        val cmd = cli("app") {
            command("build") {
                group("Tuning") { option(short = 'j', long = "jobs", help = "parallelism") }
                option(short = 'o', long = "out", help = "output")
                action { Ok("") }
            }
        }.subcommand("build")!!
        val help = cmd.helpText("app build")
        assertContains(help, "Tuning:")
        assertContains(help, "--jobs")
    }

    @Test
    fun `a throwing group block does not strand later inputs in that section`() {
        // try/finally: the section must be restored even if the block blows up mid-declaration.
        val cmd = cli("app") {
            command("build") {
                runCatching { group("Tuning") { error("boom") } }
                option(short = 'o', long = "out", help = "output")
                action { Ok("") }
            }
        }.subcommand("build")!!
        assertContains(cmd.helpText("app build"), "-o, --out")
    }
}

class MetavarTest {

    @Test
    fun `placeholder names an options value in help`() {
        val cmd = cli("app") {
            command("run") {
                option(short = 'o', long = "out", help = "where to write").placeholder("FILE")
                action { Ok("") }
            }
        }.subcommand("run")!!
        assertContains(cmd.helpText("app run"), "--out=FILE")
    }

    @Test
    fun `placeholder overrides a choice list in the signature`() {
        // A long choice list in the signature column widens every other row; placeholder is the way out.
        val cmd = cli("app") {
            command("run") {
                option(
                    short = 'l',
                    long = "level",
                    help = "verbosity",
                ).choice("trace", "debug", "info", "warn", "error")
                    .placeholder("LEVEL")
                action { Ok("") }
            }
        }.subcommand("run")!!
        val help = cmd.helpText("app run")
        assertContains(help, "--level=LEVEL")
        // The choices are still discoverable, just not in the signature column.
        assertContains(help, "trace")
    }

    @Test
    fun `placeholder names a positional in usage and its row`() {
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
    fun `without metavar nothing changes`() {
        val cmd = cli("app") {
            command("run") {
                option(short = 'o', long = "out", help = "where to write")
                argument("path", "what to run")
                action { Ok("") }
            }
        }.subcommand("run")!!
        assertContains(cmd.helpText("app run"), "--out=value")
        assertEquals("<path>", cmd.argSummary())
    }
}

class HelpFollowsDeclarationOrderTest {

    private fun cmd() = cli("app") {
        command("run") {
            // Deliberately interleaved: an author groups related inputs by meaning, not by whether the
            // library happens to call them a flag or an option.
            flag(short = 'v', long = "verbose", help = "chatty")
            option(short = 'o', long = "out", help = "output")
            flag(short = 'q', long = "quiet", help = "silent")
            option(short = 'l', long = "level", help = "verbosity")
            action { Ok("") }
        }
    }.subcommand("run")!!

    @Test
    fun `options and flags interleave as declared`() {
        val help = cmd().helpText("app run")
        val order = listOf("--verbose", "--out", "--quiet", "--level").map { help.indexOf(it) }
        assertEquals(order.sorted(), order, "help reordered the author's declarations:\n$help")
        order.forEach { assertEquals(true, it >= 0) }
    }
}
