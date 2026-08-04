package com.fromwau.klap

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
    fun helpFooterShowsTheRootAuthor() {
        val help = tree("Jane Doe").helpText()
        assertTrue("Author: Jane Doe" in help, help)
    }

    @Test
    fun authorIsRootOnlySoASubcommandHelpOmitsIt() {
        val app = tree("Jane Doe")
        assertTrue("Author: Jane Doe" in app.helpText(), "root shows it")
        val sub = app.subcommand("add")!!
        val subHelp = sub.helpText()
        assertFalse("Author:" in subHelp, "subcommand help should omit author: $subHelp")
    }

    @Test
    fun absentAuthorRendersNoFooter() {
        assertFalse("Author:" in tree(null).helpText())
    }

    @Test
    fun blankAuthorRendersNoFooter() {
        assertFalse("Author:" in tree("   ").helpText())
    }

    @Test
    fun manPageRendersAnAuthorSection() {
        val man = tree("Jane Doe").renderManPage()
        assertTrue(".SH AUTHOR" in man, man)
        assertTrue("Jane Doe" in man, man)
    }

    @Test
    fun markdownRendersAnAuthorSection() {
        val md = tree("Jane Doe").renderMarkdownDocs()
        assertTrue("## Author" in md, md)
        assertTrue("Jane Doe" in md, md)
    }

    @Test
    fun absentAuthorRendersNoManOrMarkdownSection() {
        assertFalse(".SH AUTHOR" in tree(null).renderManPage())
        assertFalse("## Author" in tree(null).renderMarkdownDocs())
    }

    @Test
    fun twoInputsSharingASecondarySpellingFailToBuild() {
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
    fun twoInputsSharingASecondaryLongFailToBuild() {
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
    fun anInputWithNoNamesFailsToBuild() {
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
    fun aNegatableFlagWithNoLongSpellingFailsToBuild() {
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
    fun helpTextPassedPositionallyIsRejectedWithAPointerToTheNamedParameter() {
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
    fun oneWordHelpTextPassedPositionallyIsRejectedTheSameWay() {
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
    fun aBareNameIsRejectedAndNamesTheTokenItShouldHaveBeen() {
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
    fun aBareOneCharacterNameIsRejectedAsAShortRatherThanALong() {
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
    fun aMultiCharacterShortIsRejectedAsTheClusterTheParserWouldReadIt() {
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
    fun aSpellingThatIsOnlyDashesOrCarriesThreeIsRejected() {
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
    fun asecondNumericAliasOnOneCommandFailsToBuild() {
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
    fun aNumericAliasNamingAnotherCommandsOptionFailsToBuild() {
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
    fun aOneCharacterLongIsDeclarableAndDistinctFromTheShortOfTheSameLetter() {
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
    fun everyHandleExposesItsPrimaryName() {
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
    fun theDidYouMeanHelperIsPublicAndMatchesTheParsersOwn() {
        // Qualified so this reaches the public re-export even if a future import in this file shadows it.
        assertEquals("list", com.fromwau.klap.suggest("lst", listOf("list", "add")))
        // Past the threshold: nothing is close enough, and an exact match is never "did you mean".
        assertNull(com.fromwau.klap.suggest("zzzzzzzz", listOf("list", "add")))
        assertNull(com.fromwau.klap.suggest("list", listOf("list", "add")))
        // ignoreCase folds both sides before the exact-match check, same as the internal helper it wraps.
        assertNull(com.fromwau.klap.suggest("FAST", listOf("fast"), ignoreCase = true))
    }

    @Test
    fun theDidYouMeanHelperAgreesWithTheParserOnTheSameToken() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
            command("add") { action { Ok("") } }
        }
        val error = assertIs<Result.Error<CliError>>(tree.parse(listOf("biuld"))).error
        assertEquals(suggest("biuld", listOf("build", "add")), (error as CliError.UnknownSubcommand).suggestion)
    }

    @Test
    fun aDomainErrorRendersItsDetailAndKeepsItsTypedPayloadRecoverable() {
        val err: CliError = CliError.Domain(StoreError.DiskFull, "out of space", exitCode = 6)
        assertEquals("out of space", err.message())
        assertEquals(StoreError.DiskFull, (err as CliError.Domain).error)

        val term = RecordingTerminal()
        assertEquals(6, renderError(err, json = false, terminal = term))
        assertEquals("error: out of space\n", term.err.toString())
    }

    @Test
    fun aDomainDetailIsTreatedAsAuthoredProseLikeFailureAndUsage() {
        // Two lines survive, exactly as they do for Failure: the consumer wrote the sentence.
        val term = RecordingTerminal()
        renderError(CliError.Domain(StoreError.DiskFull, "out of space\n  free some and retry"), json = false, terminal = term)
        assertTrue("\n  free some and retry" in term.err.toString(), term.err.toString())
    }

    @Test
    fun aDomainErrorSurvivesAnActionAndCarriesItsExitCode() {
        val tree = cli("app") {
            action<String> { Err(CliError.Domain(StoreError.DiskFull, "out of space", exitCode = 6)) }
        }
        val term = RecordingTerminal()
        assertEquals(6, tree.run(arrayOf(), term))
    }
}

private enum class StoreError { DiskFull, NotFound }
