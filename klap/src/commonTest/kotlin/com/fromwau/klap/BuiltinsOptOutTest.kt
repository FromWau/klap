package com.fromwau.klap

import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `--help` as a user sees it, rendered through the real runner so the built-in rows are the shipped ones. */
private fun Cli.helpOutput(vararg argv: String = arrayOf("--help")): String {
    val terminal = RecordingTerminal()
    run(arrayOf(*argv), terminal)
    return terminal.out.toString()
}

/** The `Global options:` block alone, where every built-in row renders. */
private fun Cli.globalOptionsBlock(): String = helpOutput().substringAfter("Global options:").trim()

private fun Cli.execute(vararg argv: String): Invocation.Execute =
    assertIs<Invocation.Execute>(assertIs<Result.Success<Invocation>>(parse(argv.toList())).value)

/** Runs the resolved action for its binding side effects, asserting it succeeded. */
private fun Invocation.Execute.run() {
    assertIs<Result.Success<Any?>>(runAction())
}

private fun Cli.parseError(vararg argv: String): CliError =
    assertIs<Result.Error<CliError>>(parse(argv.toList())).error

private fun Cli.candidates(vararg words: String): List<String> =
    completeCandidates(words.toList()).map { it.value }

class BuiltinsOptOutTest {

    // --- default: a CLI that never calls builtins { } offers all of them ---

    @Test
    fun defaultCliStillOffersEveryBuiltin() {
        val tree = cli("app") {
            command("go") { action { Ok("") } }
        }

        // 3. advertised
        val help = tree.helpOutput()
        assertTrue("-h, --help" in help, help)
        assertTrue("--json" in help, help)
        assertTrue("--color <auto|always|never>" in help, help)

        // 4. injected
        assertNotNull(tree.subcommand("completion"))
        assertNotNull(tree.subcommand("docs"))

        // 1. every name still reserved, tree-wide
        for (reserved in listOf("json", "color", "completion", "docs")) {
            assertFailsWith<IllegalArgumentException>(reserved) {
                cli("app") { command("go") { option(reserved); action { Ok("") } } }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("app") { command("go") { flag("--human", "-h"); action { Ok("") } } }
        }

        // 2. every one still parsed as a built-in
        assertTrue(tree.execute("--json", "go").globals.json)
        assertEquals(ColorMode.NEVER, listOf("--color=never", "go").colorMode())
        assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(tree.parse(listOf("-h"))).value)
        assertIs<Invocation.ShowCompletion>(
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("completion", "bash"))).value,
        )
        assertIs<Invocation.ShowDocs>(
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("docs", "man"))).value,
        )
    }

    // --- json ---

    @Test
    fun headlineCurlShapedJsonOptionBindsItsBodyAndTheUrl() {
        // F3's headline harm: klap's --json is position-independent and stripped before binding, so today
        // `curl --json '{"a":1}' https://x` switches the output format AND leaves the body to bind as the
        // URL, silently. With the built-in declined, both operands land where curl means them.
        var body: String? = null
        var url: String? = null
        val curl = cli("curl") {
            builtins { json = false }
            val json = option("--json", "-j", help = "post this JSON body")
            val target = argument("url")
            action {
                body = json()
                url = target()
                Ok("")
            }
        }

        val exec = curl.execute("--json", """{"a":1}""", "https://x")
        exec.run()

        assertEquals("""{"a":1}""", body)
        assertEquals("https://x", url)
        // The declined built-in must not leak into the action's view of the world either.
        assertFalse(exec.globals.json)

        // The attached form reaches the app's own option too, rather than klap's declined flag.
        curl.execute("""--json={"b":2}""", "https://x").run()
        assertEquals("""{"b":2}""", body)

        // And the freed name behaves like any other option: short form, and after the positional.
        curl.execute("https://x", "-j", """{"c":3}""").run()
        assertEquals("""{"c":3}""", body)
        assertEquals("https://x", url)
    }

    @Test
    fun disabledJsonIsNeitherParsedNorSwallowed() {
        val tree = cli("app") {
            builtins { json = false }
            command("go") { action { Ok("") } }
        }

        // Not stripped, not short-circuited: it reaches the command and fails as an unknown option.
        assertEquals(CliError.UnknownOption("--json", null), tree.parseError("go", "--json"))
        // The inline form is not klap's "this flag takes no value" either.
        assertIs<CliError.UnknownOption>(tree.parseError("go", "--json=1"))
        // A --json anywhere on the line does not flip Globals.json.
        assertFalse(tree.execute("go").globals.json)
    }

    @Test
    fun disabledJsonIsNotAdvertised() {
        val tree = cli("app") {
            builtins { json = false }
            command("go") { action { Ok("") } }
        }

        assertFalse("--json" in tree.helpOutput(), tree.helpOutput())
        assertFalse("--json" in tree.helpOutput("--help-all"), tree.helpOutput("--help-all"))
        // Both doc renderers assemble from the same helpSections(), so they follow --help automatically.
        assertFalse("--json" in tree.renderMarkdownDocs(), tree.renderMarkdownDocs())
        assertFalse("""\-\-json""" in tree.renderManPage(), tree.renderManPage())
        assertFalse("--json" in tree.candidates("--"))
        // The did-you-mean set drops it too, so a typo is never pointed at a flag this tree does not have.
        assertEquals(CliError.UnknownOption("--jsonn", null), tree.parseError("go", "--jsonn"))
    }

    // --- color ---

    @Test
    fun disabledColorFreesTheNameAndStopsBeingParsed() {
        var seen: String? = null
        val tree = cli("app") {
            builtins { color = false }
            val color = option("--color", help = "when to colorize")
            argument("path")
            action {
                seen = color()
                Ok("")
            }
        }

        tree.execute("--color", "never", "p").run()
        assertEquals("never", seen)

        // No choice validation and no rendering effect: the value is the app's business now.
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("--color=bogus", "p")))
        assertEquals(ColorMode.AUTO, listOf("--color=always").colorMode(Builtins(color = false)))
    }

    @Test
    fun disabledColorIsNotAdvertised() {
        val tree = cli("app") {
            builtins { color = false }
            command("go") { action { Ok("") } }
        }

        assertFalse("--color" in tree.helpOutput(), tree.helpOutput())
        assertFalse("--color" in tree.renderMarkdownDocs(), tree.renderMarkdownDocs())
        assertFalse("--color" in tree.candidates("--"))
    }

    // --- completion ---

    @Test
    fun disabledCompletionFreesTheNameForAnOptionAndASubcommand() {
        var seen: String? = null
        val tree = cli("app") {
            builtins { completion = false }
            command("shell") {
                val completion = option("--completion", help = "shell completion style")
                action {
                    seen = completion()
                    Ok("")
                }
            }
            // Cli's own built-in-subcommand reservation rejects this name by default.
            command("completion", help = "my own completion") { action { Ok("mine") } }
        }

        assertEquals("my own completion", tree.subcommand("completion")?.description)
        tree.execute("shell", "--completion", "fish").run()
        assertEquals("fish", seen)
    }

    @Test
    fun disabledCompletionMetaOptionIsNotParsedAndNotAdvertised() {
        val tree = cli("greet") {
            builtins { completion = false }
            argument("name")
            action { Ok("") }
        }

        assertEquals(CliError.UnknownOption("--completion", null), tree.parseError("--completion", "bash"))
        assertFalse("--completion" in tree.helpOutput(), tree.helpOutput())
        assertFalse("--completion" in tree.renderMarkdownDocs(), tree.renderMarkdownDocs())
        assertFalse("--completion" in tree.candidates("--"))
    }

    @Test
    fun disabledCompletionSubcommandIsGoneFromTheTreeAndItsListings() {
        val tree = cli("app") {
            builtins { completion = false }
            command("go") { action { Ok("") } }
        }

        assertNull(tree.subcommand("completion"))
        assertNotNull(tree.subcommand("docs"))
        assertIs<Result.Error<CliError>>(tree.parse(listOf("completion", "bash")))
        assertFalse("completion" in tree.helpOutput(), tree.helpOutput())
        assertFalse("completion" in tree.candidates(""))
    }

    // --- docs ---

    @Test
    fun disabledDocsFreesTheNameForAFlagAndASubcommand() {
        var seen: Boolean? = null
        val tree = cli("app") {
            builtins { docs = false }
            command("build") {
                val docs = flag("--docs", help = "also build the docs")
                action {
                    seen = docs()
                    Ok("")
                }
            }
            command("docs", help = "my own docs") { action { Ok("mine") } }
        }

        assertEquals("my own docs", tree.subcommand("docs")?.description)
        tree.execute("build", "--docs").run()
        assertEquals(true, seen)
    }

    @Test
    fun disabledDocsMetaOptionIsNotParsedAndNotAdvertised() {
        val tree = cli("greet") {
            builtins { docs = false }
            argument("name")
            action { Ok("") }
        }

        assertEquals(CliError.UnknownOption("--docs", null), tree.parseError("--docs", "man"))
        assertFalse("--docs" in tree.helpOutput(), tree.helpOutput())
        assertFalse("--docs" in tree.renderMarkdownDocs(), tree.renderMarkdownDocs())
        assertFalse("--docs" in tree.candidates("--"))
    }

    @Test
    fun disabledDocsSubcommandIsGoneFromTheTreeAndItsListings() {
        val tree = cli("app") {
            builtins { docs = false }
            command("go") { action { Ok("") } }
        }

        assertNull(tree.subcommand("docs"))
        assertNotNull(tree.subcommand("completion"))
        assertIs<Result.Error<CliError>>(tree.parse(listOf("docs", "man")))
        assertFalse("docs" in tree.helpOutput(), tree.helpOutput())
        assertFalse("docs" in tree.candidates(""))
    }

    // --- helpShort ---

    @Test
    fun disabledHelpShortFreesDashHForTheApp() {
        // `chmod -h` is the short of --no-dereference, unreachable while klap reserves -h tree-wide.
        var seen: Boolean? = null
        val chmod = cli("chmod") {
            builtins { helpShort = false }
            val noDereference = flag("--no-dereference", "-h", help = "affect symbolic links instead of referenced files")
            argument("mode")
            action {
                seen = noDereference()
                Ok("")
            }
        }

        chmod.execute("-h", "755").run()
        assertEquals(true, seen)
    }

    @Test
    fun disabledHelpShortLeavesLongHelpIntactAndUnadvertisesTheShort() {
        val tree = cli("app") {
            builtins { helpShort = false }
            command("go") { action { Ok("") } }
        }

        // --help itself is never declinable.
        assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(tree.parse(listOf("--help"))).value)
        // -h does not request help here; with nothing declared under it, it is simply unknown.
        assertIs<CliError.UnknownOption>(tree.parseError("go", "-h"))

        val help = tree.helpOutput()
        assertFalse("-h, --help" in help, help)
        assertTrue("--help" in help, help)
        assertFalse("-h" in tree.candidates("-"))
        assertTrue("--help" in tree.candidates("-"))
    }

    // --- cross-cutting ---

    @Test
    fun builtinsBlockIsOrderIndependentRelativeToSubcommandDeclarations() {
        // A subcommand's own build() runs at its `command(...)` call, which may precede the builtins block;
        // the reserved-name rule must still see the declined built-in.
        var seen: String? = null
        val tree = cli("app") {
            command("post") {
                val json = option("--json", help = "request body")
                action {
                    seen = json()
                    Ok("")
                }
            }
            builtins { json = false }
        }

        tree.execute("post", "--json", "{}").run()
        assertEquals("{}", seen)
    }

    @Test
    fun everyBuiltinCanBeDeclinedAtOnce() {
        val tree = cli("tool") {
            builtins {
                json = false
                color = false
                completion = false
                docs = false
                helpShort = false
            }
            option("--json")
            option("--color")
            option("--completion")
            option("--docs")
            flag("--human", "-h")
            action { Ok("") }
        }

        // --help is the only built-in row left, and it renders without its short.
        val globals = tree.globalOptionsBlock()
        assertEquals(1, globals.lines().size, globals)
        assertTrue("--help" in globals, globals)
        assertFalse("-h, --help" in globals, globals)

        assertNull(tree.subcommand("completion"))
        assertNull(tree.subcommand("docs"))
        // __complete stays: hidden plumbing for .completeWith providers, not part of the declinable surface.
        assertNotNull(tree.subcommand("__complete"))
    }

    @Test
    fun decliningOneBuiltinLeavesTheOthersIntact() {
        val tree = cli("app") {
            builtins { json = false }
            version = "1.0"
            command("go") { action { Ok("") } }
        }

        val help = tree.helpOutput()
        assertTrue("-h, --help" in help, help)
        assertTrue("--color <auto|always|never>" in help, help)
        assertTrue("--version" in help, help)
        assertNotNull(tree.subcommand("completion"))
        assertNotNull(tree.subcommand("docs"))
        assertEquals(ColorMode.ALWAYS, listOf("--color=always", "go").colorMode(Builtins(json = false)))
    }

    @Test
    fun helpAndHelpAllAndVersionStayReservedWhateverIsDeclined() {
        for (reserved in listOf("help", "help-all", "version")) {
            assertFailsWith<IllegalArgumentException>(reserved) {
                cli("app") {
                    builtins {
                        json = false
                        color = false
                        completion = false
                        docs = false
                        helpShort = false
                    }
                    option(reserved)
                    action { Ok("") }
                }
            }
        }
    }
}
