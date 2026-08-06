package com.fromwau.klap

import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun app(): Cli = cli("todo") {
    command("ping") { action { Ok("pong") } }
}

private fun appWithRequiredGlobal(): Cli = cli("app") {
    val dsn = globalOption("--dsn").required()
    command("run") { action { Ok(dsn()) } }
}

private fun greet(): Cli = cli("greet") {
    argument("name")
    action { Ok("") }
}

/** A dispatcher with a leaf ("list"), a leaf that takes a value ("show"), and a nested group ("tag"). */
private fun taggedDispatcher(infer: Abbreviation = Abbreviation.None): Cli = cli("app") {
    abbreviation = infer
    command("list") { action { Ok("") } }
    command("show") {
        argument("id")
        action { Ok("") }
    }
    command("tag") {
        command("push") { action { Ok("") } }
        command("rm") { action { Ok("") } }
    }
}

class BuiltinsTest {

    @Test
    fun completionCommandIsAutoAdded() {
        assertNotNull(app().subcommand("completion"))
    }

    @Test
    fun completionPrintsFishScript() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("completion", "fish"), t)
        assertEquals(0, code)
        assertTrue("complete -c todo" in t.out.toString(), t.out.toString())
    }

    @Test
    fun completionRejectsUnknownShell() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("completion", "ksh"), t)
        assertEquals(2, code)
        // Named bare: the user reached this through the `completion` SUBCOMMAND, not the `--completion` option.
        assertTrue("invalid value 'ksh' for completion" in t.err.toString(), t.err.toString())
    }

    @Test
    fun completionScriptIncludesTheCompletionCommandItself() {
        // `completion` is self-referential and must offer itself for tab completion.
        val candidates = app().completeCandidates(listOf("")).map { it.value }
        assertTrue("completion" in candidates, candidates.toString())
    }

    @Test
    fun requiredGlobalDoesNotBlockBuiltinsButStillGuardsALeaf() {
        val tree = appWithRequiredGlobal()

        // Direct render escape hatches must not throw with the required global unset.
        tree.renderCompletion(CompletionShell.BASH)
        tree.renderMarkdownDocs()

        // The injected builtins route straight to their render invocations, never hitting the missing-global guard.
        assertIs<Invocation.ShowCompletion>(
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("completion", "bash"))).value,
        )
        assertIs<Invocation.ShowDocs>(
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("docs", "man"))).value,
        )
        assertIs<Invocation.ShowCompleteCandidates>(
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("__complete", "--", "x"))).value,
        )

        // A normal leaf in the same tree still enforces the required global when it is absent.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("run"))).error
        assertEquals(CliError.MissingRequiredOption("--dsn"), err)
    }

    @Test
    fun singleCommandToolCompletionMetaOptionPrintsScript() {
        val tree = greet()
        val t = RecordingTerminal()
        assertEquals(0, tree.run(arrayOf("--completion", "bash"), t))
        assertTrue("complete -F _greet greet" in t.out.toString(), t.out.toString())
    }

    @Test
    fun singleCommandToolDocsMetaOptionPrintsDocs() {
        val tree = greet()
        val t = RecordingTerminal()
        assertEquals(0, tree.run(arrayOf("--docs", "man"), t))
        assertTrue(".TH \"GREET\" 1" in t.out.toString(), t.out.toString())
    }

    @Test
    fun completionMetaOptionRejectsUnknownShellWithSuggestion() {
        val tree = greet()
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--completion", "bsh"))).error
        assertEquals(CliError.InvalidChoice("--completion", "bsh", COMPLETION_SHELL_NAMES, "bash"), err)
    }

    @Test
    fun completionMetaOptionMissingValueReportsMissingOptionValue() {
        val tree = greet()
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--completion"))).error
        assertEquals(CliError.MissingOptionValue("--completion"), err)
    }

    @Test
    fun completionMetaOptionAcceptsEqualsForm() {
        val tree = greet()
        val t = RecordingTerminal()
        assertEquals(0, tree.run(arrayOf("--completion=bash"), t))
        assertTrue("complete -F _greet greet" in t.out.toString(), t.out.toString())
    }

    @Test
    fun endOfOptionsEscapesTheCompletionMetaOption() {
        val tree = cli("greet") {
            argument("name").multiple(min = 1)
            action { Ok("") }
        }
        val inv = assertIs<Result.Success<Invocation>>(tree.parse(listOf("--", "--completion", "bash"))).value
        assertIs<Invocation.Execute>(inv)
    }

    @Test
    fun completionMetaOptionTreatsAFlagLikeNextTokenAsMissingValue() {
        val tree = greet()
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--completion", "--foo"))).error
        assertEquals(CliError.MissingOptionValue("--completion"), err)
    }

    @Test
    fun jsonInterleavedWithCompletionMetaOptionDoesNotConsumeTheShellValue() {
        // --json is position-independent and stripped before the meta-option scan, so a --json sitting
        // between --completion and its shell value must not be mistaken for that value; this resolves
        // identically to --json appearing before --completion.
        val tree = greet()
        val interleaved =
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("--completion", "--json", "bash"))).value
        val leading =
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("--json", "--completion", "bash"))).value
        assertIs<Invocation.ShowCompletion>(interleaved)
        assertEquals(CompletionShell.BASH, interleaved.shell)
        assertEquals(interleaved, leading)
    }

    @Test
    fun helpOutranksCompletionMetaOption() {
        val tree = greet()
        val inv = assertIs<Result.Success<Invocation>>(tree.parse(listOf("--help", "--completion", "bash"))).value
        assertIs<Invocation.ShowHelp>(inv)
    }

    @Test
    fun singleCommandToolHasNoCompletionOrDocsSubcommandButKeepsComplete() {
        val tree = cli("wc") {
            argument("files").multiple(min = 1)
            action { Ok("") }
        }
        assertNull(tree.subcommand("completion"))
        assertNull(tree.subcommand("docs"))
        assertNotNull(tree.subcommand("__complete"))
    }

    @Test
    fun dispatcherStillHasCompletionAndDocsSubcommands() {
        val tree = cli("app") { command("build") { action { Ok("") } } }
        assertNotNull(tree.subcommand("completion"))
        assertNotNull(tree.subcommand("docs"))
    }

    @Test
    fun singleCommandToolDoesNotShadowAPositionalNamedDocs() {
        var seen: List<String>? = null
        val tree = cli("wc") {
            val files = argument("files").multiple(min = 1)
            action {
                seen = files()
                Ok("")
            }
        }
        assertEquals(0, tree.run(arrayOf("docs", "README.md"), RecordingTerminal()))
        assertEquals(listOf("docs", "README.md"), seen)
    }

    @Test
    fun completionSubcommandRejectsExtraArguments() {
        // The injected `completion <shell>` declares exactly one argument; a surplus operand must be
        // rejected like any user command's, not silently dropped (a builtin routes before positional binding).
        val err = assertIs<Result.Error<CliError>>(app().parse(listOf("completion", "bash", "extra"))).error
        assertEquals(CliError.TooManyArguments("completion", listOf("extra")), err)
    }

    @Test
    fun docsSubcommandRejectsExtraArguments() {
        val tree = cli("app") { command("build") { action { Ok("") } } }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("docs", "man", "extra"))).error
        assertEquals(CliError.TooManyArguments("docs", listOf("extra")), err)
    }

    @Test
    fun badColorValueIsInvalidChoiceAndBareColorIsMissingValue() {
        val tree = cli("app") { command("go") { action { Ok("") } } }

        // Attached bad value.
        assertEquals(
            CliError.InvalidChoice("--color", "bogus", listOf("auto", "always", "never"), null),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--color=bogus", "go"))).error,
        )
        // The space form consumes the next token as the value (like --completion), so a bad space value
        // is InvalidChoice too, not MissingOptionValue.
        assertEquals(
            CliError.InvalidChoice("--color", "nope", listOf("auto", "always", "never"), null),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--color", "nope", "go"))).error,
        )
        // MissingOptionValue only when no value follows (--color at the end).
        assertEquals(
            CliError.MissingOptionValue("--color"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("go", "--color"))).error,
        )
    }

    @Test
    fun badColorValueOutranksVersionShortCircuit() {
        // A malformed rendering modifier is reported even when --version is present, matching
        // builtinInlineValueError's precedence over the version short-circuit.
        val tree = cli("app") {
            version = "1.0"
            command("go") { action { Ok("") } }
        }

        assertEquals(
            CliError.InvalidChoice("--color", "bogus", listOf("auto", "always", "never"), null),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--color=bogus", "--version"))).error,
        )
        assertEquals(
            CliError.MissingOptionValue("--color"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--color", "--version"))).error,
        )
        assertIs<Invocation.ShowVersion>(
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("--color=always", "--version"))).value,
        )
    }

    @Test
    fun jsonBetweenSpaceFormColorAndItsValueStillResolvesToThatValue() {
        // colorMode() must see the same token view parse() does: --json stripped first. Before the fix,
        // colorMode() read the raw argv, saw "--color --json always", treated "--json" as color's would-be
        // value, found it flag-like, and silently fell back to AUTO instead of ALWAYS.
        val tree = cli("app") {
            version = "1.0"
            command("go") { action { Ok("") } }
        }
        val argv = listOf("--color", "--json", "always", "go")

        assertIs<Invocation.Execute>(assertIs<Result.Success<Invocation>>(tree.parse(argv)).value)
        // Pins the fix: without stripping --json first, this would be ColorMode.AUTO, not ALWAYS.
        assertEquals(ColorMode.ALWAYS, argv.colorMode())
    }

    @Test
    fun colorGivenTwiceResolvesToTheLastOccurrence() {
        // --color follows the documented last-occurrence-wins rule for value options, same as
        // --completion/--docs.
        assertEquals(ColorMode.NEVER, listOf("--color=always", "--color=never").colorMode())
        assertEquals(ColorMode.ALWAYS, listOf("--color=never", "--color=always").colorMode())

        val tree = cli("app") { command("go") { action { Ok("") } } }
        val argv = listOf("--color=always", "--color=never", "go")

        assertIs<Invocation.Execute>(assertIs<Result.Success<Invocation>>(tree.parse(argv)).value)
        assertEquals(ColorMode.NEVER, argv.colorMode())
    }

    // --- A built-in's reach stops at a value-taking option's argument slot ---
    //
    // The rule and its conforming half live in PosixConformanceTest
    // (guideline10_aBuiltinSpellingInAnOptionArgumentSlotIsThatOptionsValue); these pin the shapes around
    // it, in both directions: what a value slot hides from the scans, and what it must NOT hide.

    @Test
    fun builtinInAValueSlotKeepsItsAttachedFormsLiteralToo() {
        val tree = grep()

        // `--json=x` on its own is a usage error (a boolean built-in takes no value); in a value slot it
        // is a string, and the error must not fire.
        assertEquals("e=--json=x files=[f.txt]", tree.bindText("-e", "--json=x", "f.txt"))
        assertEquals("e=--color=never files=[f.txt]", tree.bindText("-e", "--color=never", "f.txt"))
        // The option's OWN attached form was never at risk: the built-in spelling is not a token there.
        assertEquals("e=--json files=[f.txt]", tree.bindText("-e--json", "f.txt"))
    }

    @Test
    fun builtinAfterAClusterEndingInAValueTakingShortIsThatShortsValue() {
        // Guideline 5's group: the flags come first and the one option that takes an argument ends the
        // cluster, so `-ve` reaches for the next token exactly as a bare `-e` does.
        assertEquals("v=true e=--json files=[f.txt]", grepWithFlag().bindText("-ve", "--json", "f.txt"))
    }

    @Test
    fun aSubcommandsOwnOptionClaimsItsValueSlotToo() {
        // The scans run before the walk, so covering this needed the target command resolved first: the
        // options in scope at `--json` are `sub`'s, and only `sub` knows that `-e` takes a value.
        val tree = cliOf("app") {
            dispatch(
                command("sub") {
                    val regexp = option("--regexp", "-e")
                    val files = argument("file").multiple(min = 0)
                    action { Ok("") }
                    projection { "e=${regexp()} files=${files()}" }
                },
            )
        }
        assertEquals(Ok("e=--json files=[f.txt]"), tree.parse(listOf("sub", "-e", "--json", "f.txt")))
        assertEquals(false, executeOf(tree.cli, "sub", "-e", "--json", "f.txt").globals.json)
    }

    @Test
    fun builtinInAGlobalOptionsValueSlotIsThatGlobalsValue() {
        // The pre-strip that pulls globals out of argv runs AFTER the built-in strips, so `--json` used to
        // be gone by the time `--dsn` reached for its value and the subcommand token took its place.
        val tree = cliOf("app") {
            val dsn = globalOption("--dsn")
            dispatch(
                command("run") {
                    action { Ok("") }
                    projection { "dsn=${dsn()}" }
                },
            )
        }
        assertEquals(Ok("dsn=--json"), tree.parse(listOf("--dsn", "--json", "run")))
        assertEquals(false, executeOf(tree.cli, "--dsn", "--json", "run").globals.json)
    }

    @Test
    fun builtinOutsideAValueSlotStillAnswersAtEveryDepth() {
        val tree = cli("app") {
            command("sub") {
                option("--regexp", "-e")
                argument("file").multiple(min = 0)
                action { Ok("") }
            }
        }

        assertTrue(executeOf(tree, "--json", "sub", "f.txt").globals.json)
        assertTrue(executeOf(tree, "sub", "--json", "f.txt").globals.json)
        // A value slot hides a built-in from the scans; an EXHAUSTED one does not. `-e` already has its
        // value attached, so the `--json` after it is nobody's argument.
        assertTrue(executeOf(tree, "sub", "-ex", "--json", "f.txt").globals.json)
        assertIs<Invocation.ShowHelp>(parseOf(tree, "sub", "--help"))
    }

    @Test
    fun helpIsNoExceptionAndBindsAsAValueLikeAnyOtherToken() {
        // One rule, no carve-out: `grep -e --help` searches for the string "--help", as GNU grep does.
        assertEquals("e=--help files=[f.txt]", grep().bindText("-e", "--help", "f.txt"))
        assertEquals("e=-h files=[f.txt]", grep().bindText("-e", "-h", "f.txt"))
        // Outside the slot it is still help, at the same command.
        assertIs<Invocation.ShowHelp>(parseOf(grep(), "--help", "f.txt"))
    }

    @Test
    fun helpInASubcommandsValueSlotBindsThereToo() {
        // --help and --help-all are matched AFTER the walk, against the reached command's own pool, so the
        // slot has to reach them on their own path as well as on the pre-walk one.
        val tree = cliOf("app") {
            dispatch(
                command("sub") {
                    val regexp = option("--regexp", "-e")
                    val files = argument("file").multiple(min = 0)
                    action { Ok("") }
                    projection { "e=${regexp()} files=${files()}" }
                },
            )
        }
        assertEquals(Ok("e=--help files=[f.txt]"), tree.parse(listOf("sub", "-e", "--help", "f.txt")))
        assertEquals(Ok("e=-h files=[f.txt]"), tree.parse(listOf("sub", "-e", "-h", "f.txt")))
        assertEquals(Ok("e=--help-all files=[f.txt]"), tree.parse(listOf("sub", "-e", "--help-all", "f.txt")))
    }

    @Test
    fun anOptionalValueOptionClaimsNoSlotSoTheBuiltinAfterItStillAnswers() {
        // `.optionalValue()` never reaches for the next token — it cannot tell its value from an operand —
        // so the arity rule must not start skipping one on its behalf.
        val tree = cli("ls") {
            // `--color` is klap's own name until the tree declines it, as BuiltinsOptOutTest does.
            builtins { color = false }
            option("--color").optionalValue("always")
            argument("file").multiple(min = 0)
            action { Ok("") }
        }
        assertTrue(executeOf(tree, "--color", "--json", "f.txt").globals.json)
    }

    @Test
    fun aBuiltinSpellingAfterTheDelimiterIsStillAnOperand() {
        // Guideline 10 already put these out of every scan's reach; the arity rule leaves that alone.
        assertEquals("e=null files=[--json, f.txt]", grep().bindText("--", "--json", "f.txt"))
        assertEquals(false, executeOf(grep(), "--", "--json", "f.txt").globals.json)
    }

    // --- --help/--help-all must not mask a mistyped subcommand ---
    //
    // An unresolved subcommand at a GROUP errors even with --help appended; a resolved command's own
    // leftover, or an abbreviation, is untouched.

    @Test
    fun unknownSubcommandWithHelpErrorsIdenticallyToWithoutHelp() {
        val tree = app()
        val bare = assertIs<Result.Error<CliError>>(tree.parse(listOf("zzz"))).error
        val withHelp = assertIs<Result.Error<CliError>>(tree.parse(listOf("zzz", "--help"))).error
        assertEquals(bare, withHelp)
        assertEquals(CliError.UnknownSubcommand("todo", "zzz", null), bare)
    }

    @Test
    fun unknownSubcommandWithHelpAllErrorsTheSameWayAsHelp() {
        val tree = app()
        val withHelp = assertIs<Result.Error<CliError>>(tree.parse(listOf("zzz", "--help"))).error
        val withHelpAll = assertIs<Result.Error<CliError>>(tree.parse(listOf("zzz", "--help-all"))).error
        assertEquals(withHelp, withHelpAll)
    }

    @Test
    fun unknownSubcommandAtANestedGroupWithHelpErrorsTheSameWay() {
        val tree = taggedDispatcher()
        val bare = assertIs<Result.Error<CliError>>(tree.parse(listOf("tag", "zzz"))).error
        val withHelp = assertIs<Result.Error<CliError>>(tree.parse(listOf("tag", "zzz", "--help"))).error
        assertEquals(bare, withHelp)
        assertEquals(CliError.UnknownSubcommand("tag", "zzz", null), bare)
    }

    @Test
    fun groupReachedWithNoLeftoverStillShowsItsOwnHelp() {
        // Must-keep-working: a group with nothing left over is a genuine help request, not a typo.
        val tree = taggedDispatcher()
        val inv = assertIs<Result.Success<Invocation>>(tree.parse(listOf("tag", "--help"))).value
        assertEquals("tag", assertIs<Invocation.ShowHelp>(inv).command.name)
    }

    @Test
    fun leafReachedWithNoLeftoverStillShowsItsOwnHelp() {
        // Must-keep-working: the walk consumed every token reaching a leaf, so --help is that leaf's own.
        val tree = taggedDispatcher()
        val inv = assertIs<Result.Success<Invocation>>(tree.parse(listOf("list", "--help"))).value
        assertEquals("list", assertIs<Invocation.ShowHelp>(inv).command.name)
    }

    @Test
    fun anAbbreviatedSubcommandPlusHelpShowsThatCommandsHelp() {
        // Must-keep-working: an inferred prefix resolves to a real child during the walk itself, so it never
        // reaches the new unknown-subcommand check at all.
        val tree = taggedDispatcher(Abbreviation.All)
        val inv = assertIs<Result.Success<Invocation>>(tree.parse(listOf("li", "--help"))).value
        assertEquals("list", assertIs<Invocation.ShowHelp>(inv).command.name)
    }

    @Test
    fun aBadValueOnARealCommandPlusHelpStillShowsThatCommandsHelp() {
        // Scope boundary: "show" is a real command and "abc" is merely a value for it (its "id" argument),
        // not an unknown subcommand, so --help must still win here. "show" has no children, so it is never a
        // Command.isGroup and the new check never even looks at its leftover tokens.
        val tree = taggedDispatcher()
        val inv = assertIs<Result.Success<Invocation>>(tree.parse(listOf("show", "abc", "--help"))).value
        assertEquals("show", assertIs<Invocation.ShowHelp>(inv).command.name)
    }

    @Test
    fun unknownSubcommandWithHelpNeverSuggestsAHiddenChild() {
        // Mirrors ParseResolutionTest's unknownSubcommandNeverSuggestsAHiddenSubcommand through the --help
        // path: the fix's own candidate list must exclude hidden children too.
        val tree = cli("app") {
            command("secret") {
                hidden = true
                action { Ok("") }
            }
            command("status") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("secrt", "--help"))).error
        assertEquals(CliError.UnknownSubcommand("app", "secrt", null), err)
    }
}

private fun grep(): Cli = cli("mygrep") {
    val regexp = option("--regexp", "-e")
    val files = argument("file").multiple(min = 0)
    action { Ok("e=${regexp()} files=${files()}") }
}

private fun grepWithFlag(): Cli = cli("mygrep") {
    val invert = flag("--invert-match", "-v")
    val regexp = option("--regexp", "-e")
    val files = argument("file").multiple(min = 0)
    action { Ok("v=${invert()} e=${regexp()} files=${files()}") }
}

private fun parseOf(cli: Cli, vararg argv: String): Invocation =
    assertIs<Result.Success<Invocation>>(cli.parse(argv.toList())).value

private fun executeOf(cli: Cli, vararg argv: String): Invocation.Execute =
    assertIs<Invocation.Execute>(parseOf(cli, argv = argv))
