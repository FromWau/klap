package com.fromwau.klap

import com.fromwau.kern.fuzzy.didYouMean
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
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
        help = "manage tasks"
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
                    flag(short = listOf('r', 'R'), long = "recursive", help = "recurse")
                    flag(short = 'R', long = "reverse", help = "reverse")
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
                    option(long = listOf("since", "after"), help = "lower bound")
                    option(short = 'a', long = "after", help = "the same thing again")
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
                    flag(short = 'Z', help = "zeta").negatable(default = true)
                    action { Ok("") }
                }
            }
        }
        assertTrue("no long spelling" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a long written with its dashes is rejected and names the bare word`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    flag(long = "--recursive", help = "recurse")
                    action { Ok("") }
                }
            }
        }
        assertTrue("long = \"recursive\"" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a long that is blank or carries an equals sign or a control character is rejected`() {
        for (spelling in listOf("", " ", "key=value", "na${Char(1)}me")) {
            assertFailsWith<IllegalArgumentException>("'$spelling' must not declare an option") {
                cli("app") {
                    command("go") {
                        option(long = listOf(spelling), help = "nope")
                        action { Ok("") }
                    }
                }
            }
            assertFailsWith<IllegalArgumentException>("'$spelling' must not declare a flag") {
                cli("app") {
                    command("go") {
                        flag(long = spelling, help = "nope")
                        action { Ok("") }
                    }
                }
            }
        }
    }

    @Test
    fun `a second number input on one command fails to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("head") {
                numberOption().int()
                numberOption().int()
                action { Ok("") }
            }
        }
        assertTrue("already declared" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a one character long is declarable and distinct from the short of the same letter`() {
        // Neither is expressible while a spelling's LENGTH decides its form: `--x` cannot be written at
        // all, and `--x` and `-x` cannot be told apart. Both fall out of writing the dashes down.
        lateinit var long: Opt<String?>
        lateinit var short: Flag
        val tree = cli("app") {
            long = option(long = "x", help = "the long one")
            short = flag(short = 'x', help = "the short one")
            action { Ok("") }
        }
        val parsed = tree.parse(listOf("--x", "value", "-x")).assertSuccess()
        with(assertIs<Invocation.Execute>(parsed).inputs) {
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
            host = option(short = 'H', long = "host")
            file = argument("file")
            verbose = flag(short = 'v', long = "verbose")
            loud = flag(short = 'l', long = "loud").count()
            action { Ok("") }
        }
        assertEquals("--host", host.name)
        assertEquals("file", file.name)
        assertEquals("--verbose", verbose.name)
        assertEquals("--loud", loud.name)
    }

    @Test
    fun `the did you mean helper agrees with the parser on the same token`() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
            command("add") { action { Ok("") } }
        }
        val error = tree.parse(listOf("biuld")).assertError<CliError.UnknownSubcommand>()
        assertEquals(
            didYouMean("biuld", listOf("build", "add")),
            error.suggestion,
        )
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
