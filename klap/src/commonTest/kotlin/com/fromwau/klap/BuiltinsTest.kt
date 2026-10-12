package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.map
import com.fromwau.klap.internal.render.Candidate
import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun app(): Cli = cli("todo") {
    command("ping") { action { Ok("pong") } }
}

private fun appWithRequiredGlobal(): Cli = cli("app") {
    val dsn = globalOption(long = "dsn").required()
    command("run") { action { Ok(dsn()) } }
}

private fun greet(): Cli = cli("greet") {
    argument("name")
    action { Ok("") }
}

/** A single-command root that offers every rung of the built-in ladder at once. */
private fun ladderRoot(): Cli = cli("greet") {
    version = Version.Of("1.0.0")
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
    fun `completion command is auto added`() {
        assertNotNull(app().subcommand("completion"))
    }

    @Test
    fun `completion prints fish script`() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("completion", "fish"), t)
        assertEquals(0, code)
        assertTrue("complete -c todo" in t.out.toString(), t.out.toString())
    }

    @Test
    fun `completion rejects unknown shell`() {
        val t = RecordingTerminal()
        val code = app().run(arrayOf("completion", "ksh"), t)
        assertEquals(2, code)
        // Named bare: the user reached this through the `completion` SUBCOMMAND, not the `--completion` option.
        assertTrue("invalid value 'ksh' for completion" in t.err.toString(), t.err.toString())
    }

    @Test
    fun `completion script includes the completion command itself`() {
        // `completion` is self-referential and must offer itself for tab completion.
        val candidates = app().completeCandidates(listOf("")).map { it.value }
        assertTrue("completion" in candidates, candidates.toString())
    }

    @Test
    fun `required global does not block builtins but still guards a leaf`() {
        val tree = appWithRequiredGlobal()

        // Direct render escape hatches must not throw with the required global unset.
        tree.renderCompletion(CompletionShell.BASH)
        tree.renderMarkdownDocs()

        // The injected builtins route straight to their render invocations, never hitting the missing-global guard.
        assertIs<Invocation.ShowCompletion>(
            tree.parse(listOf("completion", "bash")).assertSuccess(),
        )
        assertIs<Invocation.ShowDocs>(
            tree.parse(listOf("docs", "man")).assertSuccess(),
        )
        assertIs<Invocation.ShowCompleteCandidates>(
            tree.parse(listOf("__complete", "--", "x")).assertSuccess(),
        )

        // A normal leaf in the same tree still enforces the required global when it is absent.
        val err = tree.parse(listOf("run")).assertError<CliError.MissingRequiredOption>()
        assertEquals(CliError.MissingRequiredOption("--dsn"), err)
    }

    @Test
    fun `single command tool completion meta option prints script`() {
        val tree = greet()
        val t = RecordingTerminal()
        assertEquals(0, tree.run(arrayOf("--completion", "bash"), t))
        assertTrue("complete -F _greet greet" in t.out.toString(), t.out.toString())
    }

    @Test
    fun `single command tool docs meta option prints docs`() {
        val tree = greet()
        val t = RecordingTerminal()
        assertEquals(0, tree.run(arrayOf("--docs", "man"), t))
        assertTrue(".TH \"GREET\" 1" in t.out.toString(), t.out.toString())
    }

    @Test
    fun `completion meta option rejects unknown shell with suggestion`() {
        val tree = greet()
        val err = tree.parse(listOf("--completion", "bsh")).assertError<CliError.InvalidChoice>()
        assertEquals(CliError.InvalidChoice("--completion", "bsh", COMPLETION_SHELL_NAMES, "bash"), err)
    }

    @Test
    fun `completion meta option missing value reports missing option value`() {
        val tree = greet()
        val err = tree.parse(listOf("--completion")).assertError<CliError.MissingOptionValue>()
        assertEquals(CliError.MissingOptionValue("--completion"), err)
    }

    @Test
    fun `completion meta option accepts equals form`() {
        val tree = greet()
        val t = RecordingTerminal()
        assertEquals(0, tree.run(arrayOf("--completion=bash"), t))
        assertTrue("complete -F _greet greet" in t.out.toString(), t.out.toString())
    }

    @Test
    fun `end of options escapes the completion meta option`() {
        val tree = cli("greet") {
            argument("name").multiple(min = 1)
            action { Ok("") }
        }
        val inv = tree.parse(listOf("--", "--completion", "bash")).assertSuccess()
        assertIs<Invocation.Execute>(inv)
    }

    @Test
    fun `completion meta option takes a flag like next token as its shell`() {
        // getopt -o '' -l completion: -- --completion --foo gives " --completion '--foo'".
        val tree = greet()
        val err = tree.parse(listOf("--completion", "--foo")).assertError<CliError.InvalidChoice>()
        assertEquals("--foo", err.value)
    }

    @Test
    fun `json beside the completion meta option is the json builtin`() {
        // --json ahead of --completion or after its shell is the built-in; between them it is the shell asked
        // for, as getopt_long takes a required argument (getopt -o '' -l completion:,json -- --completion --json).
        val tree = greet()
        val leading = tree.parse(listOf("--json", "--completion", "bash")).assertSuccess()
        assertIs<Invocation.ShowCompletion>(leading)
        assertEquals(CompletionShell.BASH, leading.shell)
        assertEquals(leading, tree.parse(listOf("--completion", "bash", "--json")).assertSuccess())
    }

    @Test
    fun `a line naming two built-ins answers by bucket then by order`() {
        // Owner, 2026-10-10: --version, --help and --help-all form the first bucket and --completion and --docs the
        // second; the first bucket beats the second, and inside a bucket the line's first one answers, as GNU
        // tools answer --help and --version. --help-all needs a subcommand to expand, and --completion as an option
        // needs a root without one, so the first bucket is read on a dispatcher.
        val dispatcher = cli("greet") {
            version = Version.Of("1.0.0")
            builtins { help = Help.ByTree() }
            command("hello") { action { Ok("") } }
        }
        assertIs<Invocation.ShowVersion>(parseOf(dispatcher, "--version", "--help-all"))
        assertTrue(assertIs<Invocation.ShowHelp>(parseOf(dispatcher, "--help-all", "--version")).recursive)
        assertTrue(assertIs<Invocation.ShowHelp>(parseOf(dispatcher, "--help-all", "--help")).recursive)
        assertFalse(assertIs<Invocation.ShowHelp>(parseOf(dispatcher, "--help", "--help-all")).recursive)
        assertIs<Invocation.ShowHelp>(parseOf(dispatcher, "--help", "--version"))
        assertIs<Invocation.ShowVersion>(parseOf(dispatcher, "--version", "--help"))
        val tree = ladderRoot()
        assertFalse(assertIs<Invocation.ShowHelp>(parseOf(tree, "--help", "--completion", "bash")).recursive)
        assertFalse(assertIs<Invocation.ShowHelp>(parseOf(tree, "--completion", "bash", "--help")).recursive)
        assertEquals(
            CompletionShell.BASH,
            assertIs<Invocation.ShowCompletion>(parseOf(tree, "--completion", "bash", "--docs", "man")).shell,
        )
        assertIs<Invocation.ShowDocs>(parseOf(tree, "--docs", "man", "--completion", "bash"))
    }

    @Test
    fun `a version short prints the version alone or in a cluster`() {
        // `pacman -V` and `pacman -QV` print the banner; curl and ssh take -V too.
        val tool = cli("pkg") {
            version = Version.Of("7.1.0", long = "version", short = 'V')
            flag(short = 'Q', long = "query")
            action { Ok("") }
        }
        assertIs<Invocation.ShowVersion>(parseOf(tool, "-V"))
        assertIs<Invocation.ShowVersion>(parseOf(tool, "-QV"))
        val help = RecordingTerminal().also { tool.run(arrayOf("--help"), it) }.out.toString()
        assertTrue("-V, --version" in help, help)
        // Without a version there is no --version, so nothing for a short to name: `Version.Off` holds no short.
        assertIs<CliError.UnknownOption>(cli("pkg") { action { Ok("") } }.parse(listOf("-V")).assertError())
    }

    @Test
    fun `a built-in is described in the words the tool gives it`() {
        // GNU cp: "--help  display this help and exit", "--version  output version information and exit".
        val tool = cli("cp") {
            version = Version.Of("9.12", description = "output version information and exit")
            builtins {
                help = Help.ByCommand(description = "display this help and exit")
            }
            argument("file")
            action { Ok("") }
        }
        val help = RecordingTerminal().also { tool.run(arrayOf("--help"), it) }.out.toString()
        assertTrue("display this help and exit" in help && "output version information and exit" in help, help)
        val offered = tool.completeCandidates(listOf("--"))
        assertTrue(Candidate("--help", "display this help and exit") in offered, "$offered")
        assertTrue(Candidate("--version", "output version information and exit") in offered, "$offered")
    }

    @Test
    fun `a dispatcher's completion and docs commands are described in the words the tool gives them`() {
        val tool = cli("tool") {
            builtins {
                completion = Builtin.On("emit the script")
                docs = Builtin.On("emit the manual")
            }
            command("sync") { action { Ok("") } }
        }
        val help = RecordingTerminal().also { tool.run(arrayOf("--help"), it) }.out.toString()
        assertTrue("emit the script" in help && "emit the manual" in help, help)
        val offered = tool.completeCandidates(listOf(""))
        assertTrue(Candidate("completion", "emit the script") in offered, "$offered")
        assertTrue(Candidate("docs", "emit the manual") in offered, "$offered")
    }

    @Test
    fun `a cli with no subcommand has no help-all`() {
        // Nothing for it to expand, and real single-command tools refuse it (`dd --help-all`: "unrecognized option").
        assertIs<CliError.UnknownOption>(ladderRoot().parse(listOf("--help-all")).assertError())
        cli("greet") {
            option(long = "help-all", help = "the tool's own")
            action { Ok("") }
        }
    }

    @Test
    fun `a built-in short is held to the shape of every other short`() {
        assertFailsWith<IllegalArgumentException> {
            cli("tool") {
                version = Version.Of("1.0", short = ' ')
                action { Ok("") }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("tool") {
                builtins { help = Help.ByCommand(short = '-') }
                action { Ok("") }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("tool") {
                builtins { help = Help.Custom("usage: tool", short = '=') }
                action { Ok("") }
            }
        }
    }

    @Test
    fun `a tool's own --help-all ahead of --version leaves the version to answer`() {
        val tool = cli("tool") {
            version = Version.Of("1.0")
            flag(long = "help-all", help = "the tool's own")
            action { Ok("") }
        }
        assertIs<Invocation.ShowVersion>(parseOf(tool, "--help-all", "--version"))
        assertIs<Invocation.ShowVersion>(parseOf(tool, "--version", "--help-all"))
    }

    @Test
    fun `help off removes --help and its short and frees both names`() {
        val tree = cli("tool") {
            builtins { help = Help.Off }
            flag(short = 'h', long = "help", help = "the tool's own")
            action { Ok("") }
        }
        assertIs<Invocation.Execute>(parseOf(tree, "--help"))
        assertIs<Invocation.Execute>(parseOf(tree, "-h"))
        val group = cli("tool") {
            builtins { help = Help.Off }
            command("sync") { action { Ok("") } }
        }
        assertIs<CliError.MissingSubcommand>(group.parse(emptyList()).assertError())
        assertIs<CliError.UnknownOption>(group.parse(listOf("--help")).assertError())
    }

    @Test
    fun `a custom help page is printed as written with its own short and words`() {
        val tree = cli("tool") {
            builtins { help = Help.Custom("usage: tool [-?] file", description = "show usage", short = '?') }
            argument("file")
            action { Ok("") }
        }
        for (argv in listOf(arrayOf("--help"), arrayOf("-?"))) {
            val out = RecordingTerminal().also { tree.run(argv, it) }.out.toString()
            assertEquals("usage: tool [-?] file", out.trim(), argv.toList().toString())
        }
        assertIs<CliError.UnknownOption>(tree.parse(listOf("-h")).assertError())
        assertTrue(Candidate("--help", "show usage") in tree.completeCandidates(listOf("--h")))
    }

    @Test
    fun `a version given only a short has no long`() {
        // ssh: `-V` prints the version, `--version` is "unknown option -- -".
        val tree = cli("ssh") {
            version = Version.Of("10.6p1", short = 'V')
            action { Ok("") }
        }
        assertIs<Invocation.ShowVersion>(parseOf(tree, "-V"))
        assertIs<CliError.UnknownOption>(tree.parse(listOf("--version")).assertError())
        val help = RecordingTerminal().also { tree.run(arrayOf("--help"), it) }.out.toString()
        assertTrue(help.lines().any { it.trim().startsWith("-V ") }, help)
        assertTrue("--version" !in help, help)
        val noSpelling = assertFailsWith<IllegalArgumentException> {
            cli("none") {
                version = Version.Of("1.0", long = null)
                action { Ok("") }
            }
        }
        assertTrue("needs a long or a short" in noSpelling.message.orEmpty(), noSpelling.message)
    }

    @Test
    fun `a version is printed by its own long short and text`() {
        val tree = cli("pkg") {
            version = Version.Of(
                "7.1.0",
                long = "ver",
                short = 'V',
                description = "show it",
                text = "pkg v7.1.0 (banner)",
            )
            action { Ok("") }
        }
        for (argv in listOf(arrayOf("--ver"), arrayOf("-V"))) {
            val out = RecordingTerminal().also { tree.run(argv, it) }.out.toString()
            assertEquals("pkg v7.1.0 (banner)", out.trim(), argv.toList().toString())
        }
        assertIs<CliError.UnknownOption>(tree.parse(listOf("--version")).assertError())
        assertEquals("7.1.0", tree.version)
        assertTrue(Candidate("--ver", "show it") in tree.completeCandidates(listOf("--v")))
    }

    @Test
    fun `a cli with subcommands has no help-all by default and its name is free`() {
        // pacman, whose operations are commands, has no --help-all ("unrecognized option '--help-all'").
        val tree = cli("pac") {
            command("sync") { action { Ok("") } }
        }
        assertIs<CliError.UnknownOption>(tree.parse(listOf("--help-all")).assertError())
        assertTrue("--help-all" !in tree.completeCandidates(listOf("--help")).map { it.value })
        val help = RecordingTerminal().also { tree.run(arrayOf("--help"), it) }.out.toString()
        assertTrue("--help-all" !in help, help)
        cli("pac") {
            globalFlag(long = "help-all", help = "the tool's own")
            command("sync") { action { Ok("") } }
        }
    }

    @Test
    fun `a bad value on a lower rung is not reported when a higher one answers`() {
        // `--completion bsh` alone is an InvalidChoice; under a help request it is never looked at.
        assertIs<Invocation.ShowHelp>(parseOf(ladderRoot(), "--help", "--completion", "bsh"))
    }

    @Test
    fun `single command tool has no completion or docs subcommand but keeps complete`() {
        val tree = cli("wc") {
            argument("files").multiple(min = 1)
            action { Ok("") }
        }
        assertNull(tree.subcommand("completion"))
        assertNull(tree.subcommand("docs"))
        assertNotNull(tree.subcommand("__complete"))
    }

    @Test
    fun `dispatcher still has completion and docs subcommands`() {
        val tree = cli("app") { command("build") { action { Ok("") } } }
        assertNotNull(tree.subcommand("completion"))
        assertNotNull(tree.subcommand("docs"))
    }

    @Test
    fun `single command tool does not shadow a positional named docs`() {
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
    fun `completion subcommand rejects extra arguments`() {
        // The injected `completion <shell>` declares exactly one argument; a surplus operand must be
        // rejected like any user command's, not silently dropped (a builtin routes before positional binding).
        val err = app().parse(listOf("completion", "bash", "extra")).assertError<CliError.TooManyArguments>()
        assertEquals(CliError.TooManyArguments("todo completion", listOf("extra")), err)
    }

    @Test
    fun `docs subcommand rejects extra arguments`() {
        val tree = cli("app") { command("build") { action { Ok("") } } }
        val err = tree.parse(listOf("docs", "man", "extra")).assertError<CliError.TooManyArguments>()
        assertEquals(CliError.TooManyArguments("app docs", listOf("extra")), err)
    }

    @Test
    fun `a bad attached color value is an invalid choice`() {
        // `ls --color=bogus` is "invalid argument 'bogus' for '--color'" (coreutils 9.12).
        val tree = cli("app") { command("go") { action { Ok("") } } }
        assertEquals(
            CliError.InvalidChoice("--color", "bogus", listOf("auto", "always", "never"), null),
            tree.parse(listOf("--color=bogus", "go")).assertError<CliError.InvalidChoice>(),
        )
    }

    @Test
    fun `a bare color means always and the word after it is an operand`() {
        // ls --help: "--color[=WHEN] ... The WHEN argument defaults to 'always'"; `ls --color always` lists the
        // file `always` (coreutils 9.12), and getopt -o '' -l color:: -- --color always gives
        // " --color '' -- 'always'".
        val tree = cli("app") {
            val files = argument("file").multiple(min = 0)
            action<String>(human = { it }) { Ok("files=${files()}") }
        }
        assertEquals("files=[always, f]", tree.bindText("--color", "always", "f"))
        assertEquals("files=[f]", tree.bindText("f", "--color"))
        assertEquals(ColorMode.ALWAYS, listOf("--color").colorMode())
        assertEquals(ColorMode.ALWAYS, listOf("--color", "never").colorMode())
        assertEquals(ColorMode.NEVER, listOf("--color=never").colorMode())
    }

    @Test
    fun `a bad color value ahead of --version refuses the line`() {
        // `ls --color=bogus --version` is "invalid argument 'bogus' for '--color'" (coreutils 9.12, todo 61).
        val tree = cli("app") {
            version = Version.Of("1.0")
            command("go") { action { Ok("") } }
        }

        assertEquals(
            CliError.InvalidChoice("--color", "bogus", listOf("auto", "always", "never"), null),
            tree.parse(listOf("--color=bogus", "--version")).assertError<CliError.InvalidChoice>(),
        )
        // `ls --color --version` prints the version: a bare --color is always, so nothing is refused.
        assertIs<Invocation.ShowVersion>(tree.parse(listOf("--color", "--version")).assertSuccess())
        assertIs<Invocation.ShowVersion>(
            tree.parse(listOf("--color=always", "--version")).assertSuccess(),
        )
    }

    @Test
    fun `completion and docs take the next word whatever it is`() {
        // getopt -o '' -l completion:,json -- --completion --json gives " --completion '--json'".
        val tree = cli("app") { action { Ok("") } }
        assertEquals(
            CliError.InvalidChoice("--completion", "--x", COMPLETION_SHELL_NAMES, null),
            tree.parse(listOf("--completion", "--x")).assertError<CliError.InvalidChoice>(),
        )
        assertEquals(
            "--json",
            tree.parse(listOf("--completion", "--json")).assertError<CliError.InvalidChoice>().value,
        )
        assertEquals(
            "--",
            tree.parse(listOf("--docs", "--")).assertError<CliError.InvalidChoice>().value,
        )
        // getopt -o '' -l completion: -- --completion: "option '--completion' requires an argument".
        assertEquals(
            CliError.MissingOptionValue("--completion"),
            tree.parse(listOf("--completion")).assertError<CliError.MissingOptionValue>(),
        )
    }

    @Test
    fun `the version print honours the json modifier`() {
        // --version outranks every other token, so --json can never reach an action here; it still has to
        // decide the shape of what --version itself prints, or a caller piping the line gets prose.
        val tree = cli("todo") {
            version = Version.Of("1.0")
            action { Ok("ran") }
        }

        val plain = RecordingTerminal()
        assertEquals(0, tree.run(listOf("--version"), plain))
        assertEquals("todo 1.0\n", plain.out.toString())

        val structured = RecordingTerminal()
        assertEquals(0, tree.run(listOf("--version", "--json"), structured))
        assertEquals("""{"name":"todo","version":"1.0"}""" + "\n", structured.out.toString())
    }

    @Test
    fun `the version print ignores json when the tree declined the builtin`() {
        val tree = cli("todo") {
            builtins { json = Builtin.Off }
            version = Version.Of("1.0")
            val own = flag(long = "json", help = "the app's own")
            action { Ok("own=${own()}") }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("--version", "--json"), t))
        assertEquals("todo 1.0\n", t.out.toString())
    }

    @Test
    fun `json after a bare color is still json`() {
        // A bare --color takes no word, so the --json after it is the built-in, read by parse and run alike.
        val tree = cli("app") {
            version = Version.Of("1.0")
            command("go") { action { Ok("") } }
        }
        val argv = listOf("--color", "--json", "go")

        assertTrue(assertIs<Invocation.Execute>(tree.parse(argv).assertSuccess()).globals.json)
        assertEquals(ColorMode.ALWAYS, argv.colorMode())
    }

    @Test
    fun `color given twice resolves to the last occurrence`() {
        // --color follows the documented last-occurrence-wins rule for value options, same as
        // --completion/--docs.
        assertEquals(ColorMode.NEVER, listOf("--color=always", "--color=never").colorMode())
        assertEquals(ColorMode.ALWAYS, listOf("--color=never", "--color=always").colorMode())

        val tree = cli("app") { command("go") { action { Ok("") } } }
        val argv = listOf("--color=always", "--color=never", "go")

        assertIs<Invocation.Execute>(tree.parse(argv).assertSuccess())
        assertEquals(ColorMode.NEVER, argv.colorMode())
    }

    // --- A built-in's reach stops at a value-taking option's argument slot ---
    //
    // The rule lives in GnuConformanceTest (`a value word may look like an option`); these pin the shapes around
    // it, in both directions: what a value slot hides from the scans, and what it must NOT hide.

    @Test
    fun `builtin in a value slot keeps its attached forms literal too`() {
        val tree = grep()

        // `--json=x` on its own is a usage error (a boolean built-in takes no value); in a value slot it
        // is a string, and the error must not fire.
        assertEquals("e=--json=x files=[f.txt]", tree.bindText("-e", "--json=x", "f.txt"))
        assertEquals("e=--color=never files=[f.txt]", tree.bindText("-e", "--color=never", "f.txt"))
        // The option's OWN attached form was never at risk: the built-in spelling is not a token there.
        assertEquals("e=--json files=[f.txt]", tree.bindText("-e--json", "f.txt"))
    }

    @Test
    fun `builtin after a cluster ending in a value taking short is that shorts value`() {
        // A getopt group: the flags come first and the one option that takes a value ends the cluster, so `-ve` reaches
        // for the next token exactly as a bare `-e` does.
        assertEquals("v=true e=--json files=[f.txt]", grepWithFlag().bindText("-ve", "--json", "f.txt"))
    }

    @Test
    fun `the help short clusters with a declared short in either order`() {
        // Klap's own short is a short: `-vh` reads as `-v -h`, at whichever end of the cluster it sits.
        assertIs<Invocation.ShowHelp>(parseOf(grepWithFlag(), "-vh", "f.txt"))
        assertIs<Invocation.ShowHelp>(parseOf(grepWithFlag(), "-hv", "f.txt"))
    }

    @Test
    fun `the help short standing in a cluster's value position is that value`() {
        // `-e` ends the cluster and takes the rest of the token, so the `h` is the pattern to search for.
        assertEquals("v=false e=h files=[f.txt]", grepWithFlag().bindText("-eh", "f.txt"))
        assertEquals("v=true e=h files=[f.txt]", grepWithFlag().bindText("-veh", "f.txt"))
    }

    @Test
    fun `an undeclared cluster char refuses the line only ahead of the help short`() {
        // getopt reads a cluster character by character: `gzip -xh` is "invalid option -- 'x'" and `gzip -hx`
        // prints the usage (gzip 1.15, todo 61).
        val before = grepWithFlag().parse(listOf("-xh", "f.txt")).assertError<CliError.UnknownOption>()
        assertEquals(CliError.UnknownOption("-x", cluster = "-xh"), before)
        assertIs<Invocation.ShowHelp>(grepWithFlag().parse(listOf("-hx", "f.txt")).assertSuccess())
    }

    @Test
    fun `an inline value on the help short is refused inside a cluster too`() {
        assertEquals(
            CliError.FlagTakesNoValue("--help", null),
            grepWithFlag().parse(listOf("-vh=x")).assertError<CliError.FlagTakesNoValue>(),
        )
    }

    @Test
    fun `a subcommands own option claims its value slot too`() {
        // The scans run before the walk, so covering this needed the target command resolved first: the
        // options in scope at `--json` are `sub`'s, and only `sub` knows that `-e` takes a value.
        val tree = cliOf("app") {
            dispatch(
                command("sub") {
                    val regexp = option(short = 'e', long = "regexp")
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
    fun `builtin in a global options value slot is that globals value`() {
        // The pre-strip that pulls globals out of argv runs AFTER the built-in strips, so `--json` used to
        // be gone by the time `--dsn` reached for its value and the subcommand token took its place.
        val tree = cliOf("app") {
            val dsn = globalOption(long = "dsn")
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
    fun `builtin outside a value slot still answers at every depth`() {
        val tree = cli("app") {
            command("sub") {
                option(short = 'e', long = "regexp")
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
    fun `help is no exception and binds as a value like any other token`() {
        // One rule, no carve-out: `grep -e --help` searches for the string "--help", as GNU grep does.
        assertEquals("e=--help files=[f.txt]", grep().bindText("-e", "--help", "f.txt"))
        assertEquals("e=-h files=[f.txt]", grep().bindText("-e", "-h", "f.txt"))
        // Outside the slot it is still help, at the same command.
        assertIs<Invocation.ShowHelp>(parseOf(grep(), "--help", "f.txt"))
    }

    @Test
    fun `help in a subcommands value slot binds there too`() {
        // --help and --help-all are matched AFTER the walk, against the reached command's own pool, so the
        // slot has to reach them on their own path as well as on the pre-walk one.
        val tree = cliOf("app") {
            dispatch(
                command("sub") {
                    val regexp = option(short = 'e', long = "regexp")
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
    fun `an optional value option claims no slot so the builtin after it still answers`() {
        // `.optionalValue()` never reaches for the next token — it cannot tell its value from an operand —
        // so the arity rule must not start skipping one on its behalf.
        val tree = cli("ls") {
            // `--color` is klap's own name until the tree declines it, as BuiltinsOptOutTest does.
            builtins { color = Builtin.Off }
            option(long = "color").optionalValue("always")
            argument("file").multiple(min = 0)
            action { Ok("") }
        }
        assertTrue(executeOf(tree, "--color", "--json", "f.txt").globals.json)
    }

    @Test
    fun `a builtin spelling after the delimiter is still an operand`() {
        // The end of options already put these out of every scan's reach; the arity rule leaves that alone.
        assertEquals("e=null files=[--json, f.txt]", grep().bindText("--", "--json", "f.txt"))
        assertEquals(false, executeOf(grep(), "--", "--json", "f.txt").globals.json)
    }

    // --- --help/--help-all must not mask a mistyped subcommand or option ---
    //
    // An unresolved subcommand at a GROUP errors even with --help appended; a resolved command's own
    // leftover, or an abbreviation, is untouched.

    @Test
    fun `unknown subcommand with help errors identically to without help`() {
        val tree = app()
        val bare = tree.parse(listOf("zzz")).assertError<CliError.UnknownSubcommand>()
        val withHelp = tree.parse(listOf("zzz", "--help")).assertError<CliError.UnknownSubcommand>()
        assertEquals(bare, withHelp)
        assertEquals(CliError.UnknownSubcommand("todo", "zzz", null), bare)
    }

    @Test
    fun `unknown option with help errors identically to without help`() {
        val tree = grep()
        val bare = tree.parse(listOf("--zzz")).assertError<CliError.UnknownOption>()
        val withHelp = tree.parse(listOf("--zzz", "--help")).assertError<CliError.UnknownOption>()
        assertEquals(bare, withHelp)
        assertEquals(CliError.UnknownOption("--zzz"), bare)
    }

    @Test
    fun `unknown subcommand with help all errors the same way as help`() {
        val tree = app()
        val withHelp = tree.parse(listOf("zzz", "--help")).assertError<CliError.UnknownSubcommand>()
        val withHelpAll = tree.parse(listOf("zzz", "--help-all")).assertError<CliError.UnknownSubcommand>()
        assertEquals(withHelp, withHelpAll)
    }

    @Test
    fun `unknown subcommand at a nested group with help errors the same way`() {
        val tree = taggedDispatcher()
        val bare = tree.parse(listOf("tag", "zzz")).assertError<CliError.UnknownSubcommand>()
        val withHelp = tree.parse(listOf("tag", "zzz", "--help")).assertError<CliError.UnknownSubcommand>()
        assertEquals(bare, withHelp)
        assertEquals(CliError.UnknownSubcommand("app tag", "zzz", null), bare)
    }

    @Test
    fun `group reached with no leftover still shows its own help`() {
        // Must-keep-working: a group with nothing left over is a genuine help request, not a typo.
        val tree = taggedDispatcher()
        val inv = tree.parse(listOf("tag", "--help")).assertSuccess()
        assertEquals("tag", assertIs<Invocation.ShowHelp>(inv).command.name)
    }

    @Test
    fun `leaf reached with no leftover still shows its own help`() {
        // Must-keep-working: the walk consumed every token reaching a leaf, so --help is that leaf's own.
        val tree = taggedDispatcher()
        val inv = tree.parse(listOf("list", "--help")).assertSuccess()
        assertEquals("list", assertIs<Invocation.ShowHelp>(inv).command.name)
    }

    @Test
    fun `an abbreviated subcommand plus help shows that commands help`() {
        // Must-keep-working: an inferred prefix resolves to a real child during the walk itself, so it never
        // reaches the new unknown-subcommand check at all.
        val tree = taggedDispatcher(Abbreviation.All)
        val inv = tree.parse(listOf("li", "--help")).assertSuccess()
        assertEquals("list", assertIs<Invocation.ShowHelp>(inv).command.name)
    }

    @Test
    fun `a bad value on a real command plus help still shows that commands help`() {
        // Scope boundary: "show" is a real command and "abc" is merely a value for it (its "id" argument),
        // not an unknown subcommand, so --help must still win here. "show" has no children, so it is never a
        // Command.isGroup and the new check never even looks at its leftover tokens.
        val tree = taggedDispatcher()
        val inv = tree.parse(listOf("show", "abc", "--help")).assertSuccess()
        assertEquals("show", assertIs<Invocation.ShowHelp>(inv).command.name)
    }

    @Test
    fun `unknown subcommand with help never suggests a hidden child`() {
        // Mirrors ParseResolutionTest's unknownSubcommandNeverSuggestsAHiddenSubcommand through the --help
        // path: the fix's own candidate list must exclude hidden children too.
        val tree = cli("app") {
            command("secret") {
                hidden = true
                action { Ok("") }
            }
            command("status") { action { Ok("") } }
        }
        val err = tree.parse(listOf("secrt", "--help")).assertError<CliError.UnknownSubcommand>()
        assertEquals(CliError.UnknownSubcommand("app", "secrt", null), err)
    }
}

private fun grep(): Cli = cli("mygrep") {
    val regexp = option(short = 'e', long = "regexp")
    val files = argument("file").multiple(min = 0)
    action { Ok("e=${regexp()} files=${files()}") }
}

private fun grepWithFlag(): Cli = cli("mygrep") {
    builtins { help = Help.ByCommand(short = 'h') }
    val invert = flag(short = 'v', long = "invert-match")
    val regexp = option(short = 'e', long = "regexp")
    val files = argument("file").multiple(min = 0)
    action { Ok("v=${invert()} e=${regexp()} files=${files()}") }
}

private fun parseOf(cli: Cli, vararg argv: String): Invocation =
    cli.parse(argv.toList()).assertSuccess()

private fun executeOf(cli: Cli, vararg argv: String): Invocation.Execute =
    assertIs<Invocation.Execute>(parseOf(cli, argv = argv))
