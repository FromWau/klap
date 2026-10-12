package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.klap.internal.render.Candidate
import com.fromwau.klap.internal.render.completeCandidates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What `--help` covers, set by one [Help] value. The GNU Coding Standards name `--help` and `--version` and no
 * `--help-all`, and git's `<cmd> --help` documents one command, so the default documents the command it reaches.
 */
class HelpSettingTest {

    private fun tree(help: Help? = null): Cli = cli("app") {
        if (help != null) builtins { this.help = help }
        command("remote") {
            command("add") {
                argument("url")
                action { Ok("") }
            }
        }
        command("status") { action { Ok("") } }
    }

    private fun leaf(help: Help): Cli = cli("greet") {
        builtins { this.help = help }
        argument("name")
        action { Ok("") }
    }

    private fun Cli.answer(vararg argv: String): Invocation.ShowHelp =
        assertIs<Invocation.ShowHelp>(parse(argv.toList()).assertSuccess())

    private fun Cli.out(vararg argv: String): String =
        RecordingTerminal().also { run(arrayOf(*argv), it) }.out.toString()

    // --- parse ---

    @Test
    fun `by default --help documents the command it reaches and there is no --help-all`() {
        val tree = tree()
        assertFalse(tree.answer("--help").recursive)
        assertEquals("app remote", tree.answer("remote", "--help").qualifiedName)
        assertEquals(CliError.UnknownOption("--help-all", null), tree.parse(listOf("--help-all")).assertError())
        assertIs<CliError.UnknownOption>(tree.parse(listOf("remote", "--help-all")).assertError())
    }

    @Test
    fun `ByCommand is the default`() {
        val explicit = tree(Help.ByCommand())
        assertEquals(tree().out("--help"), explicit.out("--help"))
        assertIs<CliError.UnknownOption>(explicit.parse(listOf("--help-all")).assertError())
    }

    @Test
    fun `ByTree adds --help-all for the tree from where it is typed`() {
        val tree = tree(Help.ByTree(short = 'h'))
        assertFalse(tree.answer("--help").recursive)
        assertFalse(tree.answer("-h").recursive)
        val all = tree.answer("--help-all")
        assertTrue(all.recursive)
        assertEquals("app", all.qualifiedName)
        val below = tree.answer("remote", "--help-all")
        assertTrue(below.recursive)
        assertEquals("app remote", below.qualifiedName)
    }

    @Test
    fun `All answers --help and its short with the tree from where it is typed and has no --help-all`() {
        val tree = tree(Help.All(short = 'h'))
        assertTrue(tree.answer("--help").recursive)
        assertTrue(tree.answer("-h").recursive)
        val below = tree.answer("remote", "--help")
        assertTrue(below.recursive)
        assertEquals("app remote", below.qualifiedName)
        assertIs<CliError.UnknownOption>(tree.parse(listOf("--help-all")).assertError())
    }

    @Test
    fun `a group run bare under All shows the tree from that group as --help does`() {
        val tree = tree(Help.All())
        val bare = tree.out("remote")
        assertTrue("usage: app remote add" in bare, bare)
        assertEquals(tree.out("remote", "--help"), bare)
    }

    @Test
    fun `Custom prints its page for --help and has no --help-all`() {
        val tree = tree(Help.Custom("usage: app <command>", short = 'h'))
        assertEquals("usage: app <command>", tree.out("--help").trim())
        assertEquals("usage: app <command>", tree.out("remote", "-h").trim())
        assertIs<CliError.UnknownOption>(tree.parse(listOf("--help-all")).assertError())
    }

    @Test
    fun `Off removes --help and has no --help-all`() {
        val tree = tree(Help.Off)
        assertIs<CliError.UnknownOption>(tree.parse(listOf("--help")).assertError())
        assertIs<CliError.UnknownOption>(tree.parse(listOf("--help-all")).assertError())
    }

    @Test
    fun `under ByTree the line's first of --help and --help-all answers`() {
        val tree = tree(Help.ByTree())
        assertTrue(tree.answer("--help-all", "--help").recursive)
        assertFalse(tree.answer("--help", "--help-all").recursive)
    }

    // --- help ---

    @Test
    fun `help lists --help-all only under ByTree in the words it is given`() {
        assertFalse("--help-all" in tree().out("--help"))
        assertFalse("--help-all" in tree(Help.All()).out("--help"))
        val byTree = tree(Help.ByTree(description = "show this command", allDescription = "show every command"))
        val help = byTree.out("--help")
        assertTrue(help.lines().any { "--help " in it && "show this command" in it }, help)
        assertTrue(help.lines().any { "--help-all" in it && "show every command" in it }, help)
    }

    @Test
    fun `All prints the help of every command from where --help is typed`() {
        val tree = tree(Help.All())
        val all = tree.out("--help")
        assertTrue("usage: app <command>" in all, all)
        assertTrue("usage: app remote add" in all, all)
        assertTrue("usage: app status" in all, all)
        val below = tree.out("remote", "--help")
        assertTrue("usage: app remote add" in below, below)
        assertFalse("usage: app status" in below, below)
    }

    @Test
    fun `on a cli with no subcommand the three generated settings print one page`() {
        val page = leaf(Help.ByCommand()).out("--help")
        assertEquals(page, leaf(Help.ByTree()).out("--help"))
        assertEquals(page, leaf(Help.All()).out("--help"))
        assertIs<CliError.UnknownOption>(leaf(Help.ByTree()).parse(listOf("--help-all")).assertError())
    }

    @Test
    fun `All and ByCommand describe --help in the words they are given`() {
        val words = "show the manual"
        for (help in listOf(Help.All(description = words), Help.ByCommand(description = words))) {
            val page = tree(help).out("--help")
            assertTrue(page.lines().any { "--help " in it && words in it }, "$help: $page")
        }
    }

    @Test
    fun `no setting gives --help a short unless it names one`() {
        // GNU's --help has none: `cp -h`, `mv -h` and `head -h` are "invalid option -- 'h'".
        val settings = listOf(null, Help.ByCommand(), Help.ByTree(), Help.All(), Help.Custom("usage: app"))
        for (help in settings) {
            val tree = tree(help)
            assertIs<Invocation.ShowHelp>(tree.parse(listOf("--help")).assertSuccess(), "$help")
            assertEquals("-h", assertIs<CliError.UnknownOption>(tree.parse(listOf("-h")).assertError()).token)
            assertFalse("-h" in tree.offered("-"), "$help")
        }
        assertIs<Invocation.ShowHelp>(tree(Help.ByCommand(short = 'h')).parse(listOf("-h")).assertSuccess())
    }

    @Test
    fun `each setting takes its own help short`() {
        for (help in listOf(Help.ByCommand(short = '?'), Help.ByTree(short = '?'), Help.All(short = '?'))) {
            val tree = tree(help)
            assertIs<Invocation.ShowHelp>(tree.parse(listOf("-?")).assertSuccess(), "$help")
            assertIs<CliError.UnknownOption>(tree.parse(listOf("-h")).assertError(), "$help")
        }
    }

    // --- json ---

    @Test
    fun `help under json lists --help-all only under ByTree`() {
        fun globals(cli: Cli) = Json
            .parseToJsonElement(cli.out("--help", "--json"))
            .jsonObject
            .getValue("globalOptions")
            .toString()
        assertFalse("\"--help-all\"" in globals(tree()))
        assertTrue("\"--help-all\"" in globals(tree(Help.ByTree())))
    }

    @Test
    fun `--help under json and All prints every command's help as an array`() {
        val all = Json.parseToJsonElement(tree(Help.All()).out("--help", "--json")).jsonArray
        assertEquals(
            listOf("app", "app remote", "app remote add", "app status", "app completion", "app docs"),
            all.map { it.jsonObject.getValue("command").jsonPrimitive.content },
        )
    }

    // --- completion ---

    @Test
    fun `completion offers --help-all only under ByTree in its words`() {
        assertFalse("--help-all" in tree().offered("--"))
        assertFalse("--help-all" in tree(Help.All()).offered("--"))
        assertFalse("--help-all" in tree(Help.Custom("usage")).offered("--"))
        val byTree = tree(Help.ByTree(allDescription = "show every command"))
        assertTrue(Candidate("--help-all", "show every command") in byTree.completeCandidates(listOf("--help-")))
        val byDefault = tree(Help.ByTree()).completeCandidates(listOf("--"))
        assertTrue(Candidate("--help-all", "Show help for every subcommand") in byDefault, "$byDefault")
    }

    // --- docs ---

    @Test
    fun `the docs list --help-all only under ByTree`() {
        val roff = """\-\-help\-all"""
        assertFalse("--help-all" in tree().renderMarkdownDocs())
        assertFalse(roff in tree().renderManPage())
        assertTrue("--help-all" in tree(Help.ByTree()).renderMarkdownDocs())
        assertTrue(roff in tree(Help.ByTree()).renderManPage())
    }

    // --- names ---

    @Test
    fun `without ByTree the name --help-all is the tool's own`() {
        for (help in listOf(Help.ByCommand(), Help.All(), Help.Custom("usage"))) {
            val tool = cli("app") {
                builtins { this.help = help }
                globalFlag(long = "help-all", help = "the tool's own")
                command("sync") { action { Ok("") } }
            }
            assertIs<Invocation.Execute>(tool.parse(listOf("sync", "--help-all")).assertSuccess(), "$help")
        }
    }

    @Test
    fun `ByTree reserves --help-all where a subcommand gives it something to expand`() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                builtins { help = Help.ByTree() }
                globalFlag(long = "help-all")
                command("sync") { action { Ok("") } }
            }
        }
        cli("greet") {
            builtins { help = Help.ByTree() }
            flag(long = "help-all")
            action { Ok("") }
        }
    }
}
