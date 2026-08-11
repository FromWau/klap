package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.internal.render.helpText
import com.fromwau.klap.internal.render.message
import com.fromwau.klap.internal.render.renderError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorTest {

    private fun tree(author: String?) = cli("todo") {
        if (author != null) this.author = author
        description = "manage tasks"
        command("add") { action { Ok("") } }
    }

    @Test
    fun `help footer shows the root author`() {
        val help = tree("Jane Doe").helpText()
        assertTrue("Author: Jane Doe" in help, help)
    }

    @Test
    fun `author is root only so a subcommand help omits it`() {
        val app = tree("Jane Doe")
        assertTrue("Author: Jane Doe" in app.helpText(), "root shows it")
        val sub = app.subcommand("add")!!
        val subHelp = sub.helpText()
        assertFalse("Author:" in subHelp, "subcommand help should omit author: $subHelp")
    }

    @Test
    fun `absent author renders no footer`() {
        assertFalse("Author:" in tree(null).helpText())
    }

    @Test
    fun `blank author renders no footer`() {
        assertFalse("Author:" in tree("   ").helpText())
    }

    @Test
    fun `man page renders an author section`() {
        val man = tree("Jane Doe").renderManPage()
        assertTrue(".SH AUTHOR" in man, man)
        assertTrue("Jane Doe" in man, man)
    }

    @Test
    fun `markdown renders an author section`() {
        val md = tree("Jane Doe").renderMarkdownDocs()
        assertTrue("## Author" in md, md)
        assertTrue("Jane Doe" in md, md)
    }

    @Test
    fun `absent author renders no man or markdown section`() {
        assertFalse(".SH AUTHOR" in tree(null).renderManPage())
        assertFalse("## Author" in tree(null).renderMarkdownDocs())
    }

    @Test
    fun `two inputs sharing a secondary spelling fail to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    flag("--recursive", "-r", "-R", help = "recurse")
                    flag("--reverse", "-R", help = "reverse")
                    action { Ok("") }
                }
            }
        }
        assertTrue("-R" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `two inputs sharing a secondary long fail to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("log") {
                command("show") {
                    option("--since", "--after", help = "lower bound")
                    option("--after", "-a", help = "the same thing again")
                    action { Ok("") }
                }
            }
        }
        assertTrue("--after" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `an input with no names fails to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    option(help = "nameless")
                    action { Ok("") }
                }
            }
        }
        assertTrue("at least one name" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a negatable flag with no long spelling fails to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    flag("-Z", help = "zeta").negatable(default = true)
                    action { Ok("") }
                }
            }
        }
        assertTrue("no long spelling" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `help text passed positionally is rejected with a pointer to the named parameter`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    flag("--force", "-f", "ignore nonexistent files")
                    action { Ok("") }
                }
            }
        }
        assertTrue("help = " in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `one word help text passed positionally is rejected the same way`() {
        // The whitespace rule cannot see this one: "recurse" is a perfectly well-formed word. The missing
        // dashes are what catch it, which is the whole reason a spelling carries its own.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    flag("--recursive", "-r", "recurse")
                    action { Ok("") }
                }
            }
        }
        assertTrue("help = " in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a bare name is rejected and names the token it should have been`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    flag("recursive", help = "recurse")
                    action { Ok("") }
                }
            }
        }
        assertTrue("'--recursive'" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a bare one character name is rejected as a short rather than a long`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    flag("r", help = "recurse")
                    action { Ok("") }
                }
            }
        }
        assertTrue("'-r'" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a multi character short is rejected as the cluster the parser would read it`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    flag("-rf", help = "recurse and force")
                    action { Ok("") }
                }
            }
        }
        assertTrue("--rf" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a spelling that is only dashes or carries three is rejected`() {
        for (spelling in listOf("-", "--", "---x")) {
            assertFailsWith<IllegalArgumentException>("'$spelling' must not declare an input") {
                cli("app") {
                    command("go") {
                        option(spelling, help = "nope")
                        action { Ok("") }
                    }
                }
            }
        }
    }

    @Test
    fun `asecond numeric alias on one command fails to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("head") {
                val lines = option("--lines", "-n").int()
                val bytes = option("--bytes", "-c").int()
                numericAlias(lines)
                numericAlias(bytes)
                action { Ok("") }
            }
        }
        assertTrue("already declared" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a numeric alias naming another commands option fails to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val lines = globalOption("--lines", "-n").int()
                command("go") {
                    numericAlias(lines)
                    action { Ok("") }
                }
            }
        }
        assertTrue("not declared on 'go'" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a one character long is declarable and distinct from the short of the same letter`() {
        // Neither is expressible while a spelling's LENGTH decides its form: `--x` cannot be written at
        // all, and `--x` and `-x` cannot be told apart. Both fall out of writing the dashes down.
        lateinit var long: Opt<String?>
        lateinit var short: Flag
        val tree = cli("app") {
            long = option("--x", help = "the long one")
            short = flag("-x", help = "the short one")
            action { Ok("") }
        }
        val parsed = assertIs<Result.Success<Invocation>>(tree.parse(listOf("--x", "value", "-x")))
        with(assertIs<Invocation.Execute>(parsed.value).inputs) {
            assertEquals("value", long())
            assertTrue(short())
        }
    }
}

/** The three additive surfaces a consumer needs to build its own rules on top of klap's. */
class PublicSurfaceTest {

    @Test
    fun `every handle exposes its primary name`() {
        lateinit var host: Opt<String?>
        lateinit var file: Arg<String>
        lateinit var verbose: Flag
        lateinit var loud: CountFlag
        cli("app") {
            host = option("--host", "-H")
            file = argument("file")
            verbose = flag("--verbose", "-v")
            loud = flag("--loud", "-l").count()
            action { Ok("") }
        }
        assertEquals("--host", host.name)
        assertEquals("file", file.name)
        assertEquals("--verbose", verbose.name)
        assertEquals("--loud", loud.name)
    }

    @Test
    fun `the did you mean helper is public and matches the parsers own`() {
        // Qualified so this reaches the public re-export even if a future import in this file shadows it.
        assertEquals("list", com.fromwau.klap.suggest("lst", listOf("list", "add")))
        // Past the threshold: nothing is close enough, and an exact match is never "did you mean".
        assertNull(com.fromwau.klap.suggest("zzzzzzzz", listOf("list", "add")))
        assertNull(com.fromwau.klap.suggest("list", listOf("list", "add")))
        // ignoreCase folds both sides before the exact-match check, same as the internal helper it wraps.
        assertNull(com.fromwau.klap.suggest("FAST", listOf("fast"), ignoreCase = true))
    }

    @Test
    fun `the did you mean helper agrees with the parser on the same token`() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
            command("add") { action { Ok("") } }
        }
        val error = assertIs<Result.Error<CliError>>(tree.parse(listOf("biuld"))).error
        assertEquals(suggest("biuld", listOf("build", "add")), (error as CliError.UnknownSubcommand).suggestion)
    }

    @Test
    fun `a domain error renders its detail and keeps its typed payload recoverable`() {
        val err: CliError = CliError.Domain(StoreError.DiskFull, "out of space", exitCode = 6)
        assertEquals("out of space", err.message())
        assertEquals(StoreError.DiskFull, (err as CliError.Domain).error)

        val term = RecordingTerminal()
        assertEquals(6, renderError(err, json = false, terminal = term))
        assertEquals("error: out of space\n", term.err.toString())
    }

    @Test
    fun `a domain detail is treated as authored prose like failure and usage`() {
        // Two lines survive, exactly as they do for Failure: the consumer wrote the sentence.
        val term = RecordingTerminal()
        renderError(CliError.Domain(StoreError.DiskFull, "out of space\n  free some and retry"), json = false, terminal = term)
        assertTrue("\n  free some and retry" in term.err.toString(), term.err.toString())
    }

    @Test
    fun `a domain error survives an action and carries its exit code`() {
        val tree = cli("app") {
            action<String> { Err(CliError.Domain(StoreError.DiskFull, "out of space", exitCode = 6)) }
        }
        val term = RecordingTerminal()
        assertEquals(6, tree.run(arrayOf(), term))
    }
}

private enum class StoreError : IError { DiskFull, NotFound }
