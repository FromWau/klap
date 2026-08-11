package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.klap.internal.render.completeCandidates
import com.fromwau.klap.internal.render.helpText
import com.fromwau.klap.internal.render.message
import com.fromwau.klap.internal.render.usageLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `-c`/`-x`/`-t` are exactly-one, `-z`/`-j` at-most-one, and `-f` is required: tar's shape, minus the work. */
private fun tarTree(): Cli = cli("tar") {
    command("run") {
        val create = flag("--create", "-c", help = "create a new archive")
        val extract = flag("--extract", "-x", help = "extract files from an archive")
        val list = flag("--list", "-t", help = "list the contents of an archive")
        requireExactlyOne(create, extract, list)
        val gzip = flag("--gzip", "-z", help = "filter through gzip")
        val bzip2 = flag("--bzip2", "-j", help = "filter through bzip2")
        requireAtMostOne(gzip, bzip2)
        val file = option("--file", "-f", help = "archive to operate on").required()
        action { Ok("c=${create()} x=${extract()} t=${list()} z=${gzip()} j=${bzip2()} f=${file()}") }
    }
}

private fun Cli.err(argv: List<String>): CliError =
    assertIs<Result.Error<CliError>>(parse(argv)).error

private fun Cli.out(argv: List<String>): String {
    val term = RecordingTerminal()
    val code = run(argv.toTypedArray(), term)
    assertEquals(0, code, term.err.toString())
    return term.out.toString()
}

class RequireExactlyOneTest {

    @Test
    fun `none given is an error`() {
        val error = tarTree().err(listOf("run", "-f", "a.tar"))
        assertEquals(CliError.ExactlyOneRequired(listOf("--create", "--extract", "--list")), error)
        assertEquals("exactly one of --create, --extract, --list is required", error.message())
    }

    @Test
    fun `exactly one given parses`() {
        assertEquals(
            "c=false x=true t=false z=false j=false f=a.tar\n",
            tarTree().out(listOf("run", "-x", "-f", "a.tar")),
        )
    }

    @Test
    fun `two given is an error`() {
        val error = tarTree().err(listOf("run", "-c", "-x", "-f", "a.tar"))
        assertEquals(CliError.MutuallyExclusive(listOf("--create", "--extract")), error)
        assertEquals("--create and --extract are mutually exclusive", error.message())
    }

    @Test
    fun `three given is an error naming all three`() {
        val error = tarTree().err(listOf("run", "-c", "-x", "-t", "-f", "a.tar"))
        // Comma-then-"and", so a three-way conflict does not read as "a and b and c".
        assertEquals("--create, --extract and --list are mutually exclusive", error.message())
    }

    @Test
    fun `a violation exits with the usage error code`() {
        val term = RecordingTerminal()
        val code = tarTree().run(arrayOf("run", "-c", "-x", "-f", "a.tar"), term)
        assertEquals(USAGE_ERROR_EXIT, code)
        assertEquals(2, code)
        assertEquals("error: --create and --extract are mutually exclusive\n", term.err.toString())
    }
}

class RequireAtMostOneTest {

    @Test
    fun `none given is fine`() {
        assertEquals(
            "c=true x=false t=false z=false j=false f=a.tar\n",
            tarTree().out(listOf("run", "-c", "-f", "a.tar")),
        )
    }

    @Test
    fun `one given is fine`() {
        assertEquals(
            "c=true x=false t=false z=true j=false f=a.tar\n",
            tarTree().out(listOf("run", "-c", "-z", "-f", "a.tar")),
        )
    }

    @Test
    fun `two given is an error`() {
        val error = tarTree().err(listOf("run", "-c", "-z", "-j", "-f", "a.tar"))
        assertEquals(CliError.MutuallyExclusive(listOf("--gzip", "--bzip2")), error)
        assertEquals("--gzip and --bzip2 are mutually exclusive", error.message())
    }
}

class ConstraintOrderingTest {

    @Test
    fun `a mode conflict outranks a missing required option`() {
        // The whole reason the check runs before binding: `-f` is required and absent here, so the bind
        // would report `missing required option --file` and bury the real mistake. GNU tar reports the
        // mode conflict, and so does this.
        val error = tarTree().err(listOf("run", "-c", "-x"))
        assertEquals(CliError.MutuallyExclusive(listOf("--create", "--extract")), error)
    }

    @Test
    fun `a missing mode outranks a missing required option`() {
        val error = tarTree().err(listOf("run"))
        assertIs<CliError.ExactlyOneRequired>(error)
    }

    @Test
    fun `a malformed token still outranks a constraint`() {
        // sifted.error keeps its place at the head of the queue: an unknown option is a syntax mistake,
        // and reporting a constraint against a segment we failed to read would be guesswork.
        val error = tarTree().err(listOf("run", "--nope"))
        assertIs<CliError.UnknownOption>(error)
    }

    @Test
    fun `the first declared constraint wins`() {
        val tree = cli("app") {
            command("go") {
                val a = flag("--alpha", "-a")
                val b = flag("--beta", "-b")
                val c = flag("--gamma", "-c")
                val d = flag("--delta", "-d")
                requireExactlyOne(a, b)
                requireExactlyOne(c, d)
                action { Ok("") }
            }
        }
        assertEquals(CliError.ExactlyOneRequired(listOf("--alpha", "--beta")), tree.err(listOf("go")))
    }
}

class ConstraintReadsSuppliedNessTest {

    /** `--format` always binds (it has a default), so only a sift can tell whether the user typed it. */
    private fun defaultedTree(): Cli = cli("app") {
        command("go") {
            val format = option("--format", "-f").default("json")
            val raw = flag("--raw", "-r")
            requireExactlyOne(format, raw)
            action { Ok("format=${format()} raw=${raw()}") }
        }
    }

    @Test
    fun `a defaulted option does not count as supplied when it is merely defaulted`() {
        // Reading the BOUND values would see format = "json" here and call the set satisfied.
        val error = defaultedTree().err(listOf("go"))
        assertEquals(CliError.ExactlyOneRequired(listOf("--format", "--raw")), error)
    }

    @Test
    fun `a defaulted option counts as supplied when it is actually given`() {
        assertEquals("format=yaml raw=false\n", defaultedTree().out(listOf("go", "--format", "yaml")))
    }

    @Test
    fun `the default still fills in when another member satisfies the set`() {
        // The constraint is satisfied by --raw, and --format still binds its default: the check never
        // touches binding, it only decides whether binding may proceed.
        assertEquals("format=json raw=true\n", defaultedTree().out(listOf("go", "-r")))
    }

    @Test
    fun `a defaulted option given alongside another member conflicts`() {
        val error = defaultedTree().err(listOf("go", "--format", "yaml", "-r"))
        assertEquals(CliError.MutuallyExclusive(listOf("--format", "--raw")), error)
    }

    @Test
    fun `a negated flag is not a selection`() {
        // `--no-fancy` asks to turn fancy OFF; counting it as "fancy was chosen" would make
        // `--no-fancy --plain` a conflict between an opt-out and an opt-in.
        val tree = cli("app") {
            command("go") {
                val fancy = flag("--fancy", "-y").negatable()
                val plain = flag("--plain", "-p")
                requireExactlyOne(fancy, plain)
                action { Ok("fancy=${fancy()} plain=${plain()}") }
            }
        }
        assertEquals("fancy=false plain=true\n", tree.out(listOf("go", "--no-fancy", "-p")))
        assertIs<CliError.ExactlyOneRequired>(tree.err(listOf("go", "--no-fancy")))
        assertEquals("fancy=true plain=false\n", tree.out(listOf("go", "--fancy")))
    }

    @Test
    fun `a positional counts only when its operand is given`() {
        // cp's `-T DEST` vs. a trailing DEST operand: the one shape a constraint over a positional buys.
        val tree = cli("app") {
            command("go") {
                val dest = argument("dest").optional()
                val destOption = option("--target", "-T")
                requireAtMostOne(dest, destOption)
                action { Ok("dest=${dest() ?: ""} target=${destOption() ?: ""}") }
            }
        }
        assertEquals("dest= target=\n", tree.out(listOf("go")))
        assertEquals("dest=out target=\n", tree.out(listOf("go", "out")))
        assertEquals("dest= target=out\n", tree.out(listOf("go", "-T", "out")))
        val error = tree.err(listOf("go", "out", "-T", "other"))
        assertEquals(CliError.MutuallyExclusive(listOf("<dest>", "--target")), error)
        assertEquals("<dest> and --target are mutually exclusive", error.message())
    }
}

class ConstraintConstructionTest {

    @Test
    fun `a single input is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    val a = flag("--alpha", "-a")
                    requireExactlyOne(a)
                    action { Ok("") }
                }
            }
        }
        assertEquals("command 'go': requireExactlyOne needs at least two inputs, got 1", e.message)
    }

    @Test
    fun `an empty set is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    flag("--alpha", "-a")
                    requireAtMostOne()
                    action { Ok("") }
                }
            }
        }
        assertEquals("command 'go': requireAtMostOne needs at least two inputs, got 0", e.message)
    }

    @Test
    fun `a repeated input is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    val a = flag("--alpha", "-a")
                    val b = flag("--beta", "-b")
                    requireExactlyOne(a, b, a)
                    action { Ok("") }
                }
            }
        }
        assertEquals("command 'go': requireExactlyOne lists '--alpha' more than once", e.message)
    }

    @Test
    fun `the same input reached through two handles is still a repeat`() {
        // A transformer returns a fresh handle around the SAME spec, so identity on the handle would miss
        // this; membership is compared on the spec for exactly that reason.
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    val port = option("--port", "-p")
                    val portInt = port.int()
                    val host = option("--host", "-H")
                    requireExactlyOne(port, portInt, host)
                    action { Ok("") }
                }
            }
        }
        assertEquals("command 'go': requireExactlyOne lists '--port' more than once", e.message)
    }

    @Test
    fun `an input declared on another command is rejected`() {
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                var foreign: Flag? = null
                command("one") {
                    foreign = flag("--alpha", "-a")
                    action { Ok("") }
                }
                command("two") {
                    val b = flag("--beta", "-b")
                    requireExactlyOne(b, foreign!!)
                    action { Ok("") }
                }
            }
        }
        assertTrue(
            e.message!!.startsWith("command 'two': requireExactlyOne lists '--alpha', which is not declared on 'two'"),
            e.message,
        )
    }

    @Test
    fun `a global cannot join a constraint`() {
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val verbose = globalFlag("--verbose", "-v")
                command("go") {
                    val b = flag("--beta", "-b")
                    requireExactlyOne(b, verbose)
                    action { Ok("") }
                }
            }
        }
        assertTrue(
            e.message!!.startsWith("command 'go': requireExactlyOne lists '--verbose', which is not declared on 'go'"),
            e.message,
        )
    }

    @Test
    fun `a constraint declared inside a group is accepted`() {
        // group() is a help heading and nothing else; it neither creates nor blocks a constraint.
        val tree = cli("app") {
            command("go") {
                group("Mode") {
                    val a = flag("--alpha", "-a")
                    val b = flag("--beta", "-b")
                    requireExactlyOne(a, b)
                }
                action { Ok("ok") }
            }
        }
        assertEquals("ok\n", tree.out(listOf("go", "-a")))
        assertIs<CliError.ExactlyOneRequired>(tree.err(listOf("go")))
    }

    @Test
    fun `a constraint on the roots own inputs works`() {
        val tree = cli("app") {
            val a = flag("--alpha", "-a")
            val b = flag("--beta", "-b")
            requireExactlyOne(a, b)
            action { Ok("a=${a()} b=${b()}") }
        }
        assertEquals("a=false b=true\n", tree.out(listOf("-b")))
        assertIs<CliError.ExactlyOneRequired>(tree.err(emptyList()))
    }
}

class ConstraintHelpTest {

    @Test
    fun `every exactly one member row names the whole set`() {
        val help = tarTree().subcommand("run")!!.helpText("tar run")
        assertTrue("create a new archive (one of -c, -x, -t; required)" in help, help)
        assertTrue("extract files from an archive (one of -c, -x, -t; required)" in help, help)
        assertTrue("list the contents of an archive (one of -c, -x, -t; required)" in help, help)
    }

    @Test
    fun `every at most one member row names the whole set`() {
        val help = tarTree().subcommand("run")!!.helpText("tar run")
        assertTrue("filter through gzip (at most one of -z, -j)" in help, help)
        assertTrue("filter through bzip2 (at most one of -z, -j)" in help, help)
    }

    @Test
    fun `an unconstrained row in the same command is untouched`() {
        val help = tarTree().subcommand("run")!!.helpText("tar run")
        assertTrue("archive to operate on (required)" in help, help)
    }

    @Test
    fun `a constrained option drops the contradictory optional word`() {
        val tree = cli("app") {
            command("go") {
                val a = option("--alpha", "-a", help = "the alpha")
                val b = option("--beta", "-b", help = "the beta")
                requireAtMostOne(a, b)
                action { Ok("") }
            }
        }
        val help = tree.subcommand("go")!!.helpText("app go")
        assertTrue("the alpha (at most one of -a, -b)" in help, help)
        assertTrue("(at most one of -a, -b; optional)" !in help, help)
    }

    @Test
    fun `a shortless member is named by its long form`() {
        val tree = cli("app") {
            command("go") {
                val a = flag("--alpha", help = "the alpha")
                val b = flag("--beta", "-b", help = "the beta")
                requireExactlyOne(a, b)
                action { Ok("") }
            }
        }
        val help = tree.subcommand("go")!!.helpText("app go")
        assertTrue("the alpha (one of --alpha, -b; required)" in help, help)
    }

    @Test
    fun `an input in two sets carries both notes`() {
        val tree = cli("app") {
            command("go") {
                val a = flag("--alpha", "-a", help = "the alpha")
                val b = flag("--beta", "-b")
                val c = flag("--gamma", "-c")
                requireExactlyOne(a, b)
                requireAtMostOne(a, c)
                action { Ok("") }
            }
        }
        val help = tree.subcommand("go")!!.helpText("app go")
        assertTrue("the alpha (one of -a, -b; required; at most one of -a, -c)" in help, help)
    }

    @Test
    fun `an unconstrained input carries no note but keeps its own annotations`() {
        val tree = cli("app") {
            command("go") {
                flag("--plain", "-p", help = "a plain flag")
                flag("--loud", "-l", help = "a counted flag").count()
                flag("--fancy", "-y", help = "a negatable flag").negatable()
                option("--out", "-o", help = "an option")
                option("--name", "-n", help = "a defaulted option").default("x")
                action { Ok("") }
            }
        }
        val help = tree.subcommand("go")!!.helpText("app go")
        assertTrue("a plain flag" in help, help)
        assertTrue("(" !in help.lineSequence().first { "a plain flag" in it }, help)
        assertTrue("a counted flag (repeatable)" in help, help)
        assertTrue("a negatable flag (default: on)" in help, help)
        assertTrue("an option (optional)" in help, help)
        assertTrue("a defaulted option (default: x)" in help, help)
    }

    @Test
    fun `the note reaches generated docs too`() {
        // markdown/man render from the same helpSections rows, so the annotation cannot drift from --help.
        val markdown = tarTree().renderDocs(DocFormat.MARKDOWN)
        assertTrue("(one of \\-c, \\-x, \\-t; required)" in markdown || "(one of -c, -x, -t; required)" in markdown, markdown)
    }
}

class ConstraintUsageLineTest {

    private fun Cli.runUsage(): String = subcommand("run")!!.usageLine("tar run")

    @Test
    fun `both arities render in declaration order`() {
        assertEquals("usage: tar run (-c|-x|-t) [-z|-j] --file <value> [options]", tarTree().runUsage())
    }

    @Test
    fun `the usage line still heads the help page`() {
        val help = tarTree().subcommand("run")!!.helpText("tar run")
        assertEquals("usage: tar run (-c|-x|-t) [-z|-j] --file <value> [options]", help.lineSequence().first())
    }

    @Test
    fun `groups precede the positionals`() {
        val tree = cli("tar") {
            command("run") {
                val create = flag("--create", "-c")
                val extract = flag("--extract", "-x")
                requireExactlyOne(create, extract)
                argument("file").multiple()
                action { Ok("") }
            }
        }
        assertEquals("usage: tar run (-c|-x) [file...] [options]", tree.runUsage())
    }

    @Test
    fun `a shortless member renders its long form`() {
        val tree = cli("tar") {
            command("run") {
                val create = flag("--create")
                val extract = flag("--extract", "-x")
                requireExactlyOne(create, extract)
                action { Ok("") }
            }
        }
        assertEquals("usage: tar run (--create|-x) [options]", tree.runUsage())
    }

    @Test
    fun `an all hidden group renders nothing`() {
        val tree = cli("tar") {
            command("run") {
                val create = flag("--create", "-c").hidden()
                val extract = flag("--extract", "-x").hidden()
                requireExactlyOne(create, extract)
                flag("--verbose", "-v")
                action { Ok("") }
            }
        }
        assertEquals("usage: tar run [options]", tree.runUsage())
    }

    @Test
    fun `a partly hidden group renders only its visible members`() {
        val tree = cli("tar") {
            command("run") {
                val create = flag("--create", "-c")
                val extract = flag("--extract", "-x").hidden()
                requireExactlyOne(create, extract)
                action { Ok("") }
            }
        }
        // Still bracketed with one member left: hiding a member changes what help shows, not the arity.
        assertEquals("usage: tar run (-c) [options]", tree.runUsage())
    }

    @Test
    fun `an unconstrained commands usage line carries no group`() {
        val tree = cli("app") {
            command("go") {
                argument("name")
                flag("--plain", "-p")
                action { Ok("") }
            }
        }
        assertEquals("usage: app go <name> [options]", tree.subcommand("go")!!.usageLine("app go"))
    }
}

/** tar's constrained shape plus an unrelated `-v`, so the planner's filtering has something to leave alone. */
private fun tarCompletionTree(): Cli = cli("tar") {
    command("run") {
        val create = flag("--create", "-c", help = "create a new archive")
        val extract = flag("--extract", "-x", help = "extract files from an archive")
        val list = flag("--list", "-t", help = "list the contents of an archive")
        requireExactlyOne(create, extract, list)
        val gzip = flag("--gzip", "-z", help = "filter through gzip")
        val bzip2 = flag("--bzip2", "-j", help = "filter through bzip2")
        requireAtMostOne(gzip, bzip2)
        flag("--verbose", "-v", help = "be loud")
        action { Ok("") }
    }
}

private fun Cli.names(vararg words: String): List<String> =
    completeCandidates(words.toList()).map { it.value }

class ConstraintCompletionTest {

    @Test
    fun `a supplied mode drops its siblings`() {
        val names = tarCompletionTree().names("run", "-c", "-")
        assertTrue("-x" !in names, "$names")
        assertTrue("-t" !in names, "$names")
    }

    @Test
    fun `the long forms are dropped too`() {
        val names = tarCompletionTree().names("run", "-c", "-")
        assertTrue("--extract" !in names, "$names")
        assertTrue("--list" !in names, "$names")
    }

    @Test
    fun `the supplied member itself is still offered`() {
        val names = tarCompletionTree().names("run", "-c", "-")
        assertTrue("-c" in names, "$names")
        assertTrue("--create" in names, "$names")
    }

    @Test
    fun `an unrelated flag is untouched`() {
        val names = tarCompletionTree().names("run", "-c", "-")
        assertTrue("-v" in names, "$names")
        assertTrue("--verbose" in names, "$names")
    }

    @Test
    fun `nothing supplied yet offers every member`() {
        val names = tarCompletionTree().names("run", "-")
        listOf("-c", "-x", "-t", "--create", "--extract", "--list").forEach {
            assertTrue(it in names, "$it missing from $names")
        }
    }

    @Test
    fun `a member supplied by its long form filters just the same`() {
        val names = tarCompletionTree().names("run", "--create", "-")
        assertTrue("-x" !in names, "$names")
        assertTrue("--extract" !in names, "$names")
    }

    @Test
    fun `at most one filters the same way`() {
        val names = tarCompletionTree().names("run", "-z", "-")
        assertTrue("-j" !in names, "$names")
        assertTrue("--bzip2" !in names, "$names")
        assertTrue("-z" in names, "$names")
        // The other set is untouched: none of ITS members is on the line.
        assertTrue("-c" in names, "$names")
    }

    @Test
    fun `a negated member is not a selection`() {
        // Filtering reads the same predicate the parse enforces with, so `--no-fancy` (an opt-out, not a
        // choice) must leave its siblings on offer, exactly as it leaves the constraint unsatisfied.
        val tree = cli("app") {
            command("go") {
                val fancy = flag("--fancy", "-y").negatable()
                val plain = flag("--plain", "-p")
                requireExactlyOne(fancy, plain)
                action { Ok("") }
            }
        }
        assertTrue("-p" in tree.names("go", "--no-fancy", "-"), "${tree.names("go", "--no-fancy", "-")}")
    }

    @Test
    fun `a command with no constraints offers exactly what it did before`() {
        val tree = cli("app") {
            command("go") {
                flag("--alpha", "-a")
                flag("--beta", "-b")
                action { Ok("") }
            }
        }
        val expected = listOf("--alpha", "-a", "--beta", "-b", "-h", "--help", "--json", "--color")
        assertEquals(expected, tree.names("go", "-"))
        assertEquals(expected, tree.names("go", "-a", "-"))
    }
}

/** `rm -i -f` forces and `rm -f -i` prompts — an override rule, resolved by position. */
class LastWinsTest {

    private fun tree(): Cli = cli("rm") {
        val interactive = flag("--interactive", "-i", help = "prompt before every removal")
        val force = flag("--force", "-f", help = "never prompt")
        lastWins(interactive, force)
        val files = argument("file").multiple(min = 0)
        action { Ok(if (force()) "force" else if (interactive()) "interactive" else "neither") }
    }

    private fun run(vararg argv: String): String = RecordingTerminal().let { term ->
        tree().run(argv.toList().toTypedArray(), term)
        term.out.toString().trim()
    }

    @Test
    fun `the last supplied member is the only one set`() {
        assertEquals("force", run("-i", "-f"))
        assertEquals("interactive", run("-f", "-i"))
        assertEquals("neither", run())
    }

    @Test
    fun `both spellings of a member carry the same position`() {
        assertEquals("force", run("--interactive", "--force"))
        assertEquals("interactive", run("--force", "--interactive"))
    }

    @Test
    fun `last wins resolves inside one cluster`() {
        // `-if` and `-fi` are one token each; the character order inside the cluster decides.
        assertEquals("force", run("-if"))
        assertEquals("interactive", run("-fi"))
    }

    @Test
    fun `a repeated member still loses to a later one`() {
        assertEquals("interactive", run("-f", "-f", "-i"))
        assertEquals("force", run("-i", "-i", "-f"))
    }

    @Test
    fun `a supplied set does not swallow the operands around it`() {
        lateinit var files: Arg<List<String>>
        lateinit var force: Flag
        lateinit var interactive: Flag
        val tree = cli("rm") {
            interactive = flag("--interactive", "-i")
            force = flag("--force", "-f")
            lastWins(interactive, force)
            files = argument("file").multiple(min = 0)
            action { Ok("") }
        }
        val parsed = assertIs<Result.Success<Invocation>>(tree.parse(listOf("-f", "a", "-i", "b")))
        with(assertIs<Invocation.Execute>(parsed.value).inputs) {
            assertEquals(listOf("a", "b"), files())
            assertTrue(interactive())
            assertFalse(force())
        }
    }

    @Test
    fun `every member row names the set and the usage line groups it`() {
        val help = tree().helpText()
        assertTrue("last of -i, -f wins" in help, help)
        assertTrue("[-i|-f]" in help, help)
    }

    @Test
    fun `a set of fewer than two flags fails at build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("rm") {
                val force = flag("--force", "-f")
                lastWins(force)
                action { Ok("") }
            }
        }
        assertTrue("at least two" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a global flag cannot join a set`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("rm") {
                val quiet = globalFlag("--quiet", "-q")
                val force = flag("--force", "-f")
                lastWins(quiet, force)
                action { Ok("") }
            }
        }
        assertTrue("not declared on 'rm'" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `last wins resolves two options`() {
        fun tree() = cli("head") {
            val lines = option("--lines", "-n")
            val bytes = option("--bytes", "-c")
            lastWins(lines, bytes)
            argument("file")
            action<String>(human = { it }) { Ok("n=${lines()} c=${bytes()}") }
        }
        assertEquals("n=3 c=null", tree().bindText("-c", "5", "-n", "3", "f"))
        assertEquals("n=null c=5", tree().bindText("-n", "3", "-c", "5", "f"))
    }

    @Test
    fun `a losing option falls back to its declared default`() {
        fun tree() = cli("t") {
            val a = option("--alpha").default("A")
            val b = option("--beta")
            lastWins(a, b)
            action<String>(human = { it }) { Ok("a=${a()} b=${b()}") }
        }
        assertEquals("a=A b=x", tree().bindText("--alpha", "z", "--beta", "x"))
        assertEquals("a=z b=null", tree().bindText("--beta", "x", "--alpha", "z"))
    }

    @Test
    fun `last wins resolves a mixed flag and option set`() {
        // ls spells one setting two ways: `-S` is a flag, `--sort=WORD` an option, last one wins.
        fun tree() = cli("ls") {
            val bySize = flag("--sort-size", "-S")
            val sort = option("--sort")
            lastWins(bySize, sort)
            action<String>(human = { it }) { Ok("S=${bySize()} sort=${sort()}") }
        }
        assertEquals("S=false sort=time", tree().bindText("-S", "--sort=time"))
        assertEquals("S=true sort=null", tree().bindText("--sort=time", "-S"))

        // The same mixed set inside ONE token, where nothing but the character offset separates the two.
        // An option char takes the rest of its cluster, so the option is always the tail of a mixed
        // cluster and the flag ahead of it must lose despite being the earlier member of the set.
        fun clustered() = cli("ls") {
            val bySize = flag("-S")
            val sort = option("-s")
            lastWins(bySize, sort)
            action<String>(human = { it }) { Ok("S=${bySize()} sort=${sort()}") }
        }
        assertEquals("S=false sort=time", clustered().bindText("-Ss", "time"))
        assertEquals("S=true sort=null", clustered().bindText("-s", "time", "-S"))
    }

    @Test
    fun `a losing count flag resets to zero`() {
        fun tree() = cli("t") {
            val v = flag("--verbose", "-v").count()
            val q = flag("--quiet", "-q")
            lastWins(v, q)
            action<String>(human = { it }) { Ok("v=${v()} q=${q()}") }
        }
        assertEquals("v=0 q=true", tree().bindText("-vv", "-q"))
        assertEquals("v=2 q=false", tree().bindText("-q", "-vv"))
    }

    @Test
    fun `a positional cannot join a last wins set`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                val f = flag("--force")
                val a = argument("file")
                lastWins(f, a)
                action<String>(human = { it }) { Ok("x") }
            }
        }
        assertTrue("<file>" in failure.message.orEmpty())
    }

    @Test
    fun `a required option cannot join a last wins set`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                val a = option("--alpha").required()
                val b = option("--beta")
                lastWins(a, b)
                action<String>(human = { it }) { Ok("a=${a()} b=${b()}") }
            }
        }
        assertTrue("'--alpha', which is required" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a multiple option cannot join a last wins set`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                val a = option("--alpha").multiple()
                val b = option("--beta")
                lastWins(a, b)
                action<String>(human = { it }) { Ok("a=${a()} b=${b()}") }
            }
        }
        assertTrue("'--alpha', which is multiple" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a cardinality declared after the set is rejected just the same`() {
        // .required()/.multiple() mutate the shared spec, so either may legally run below the lastWins
        // line; a check at the call site would pass here and leave the action reading a null.
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                val a = option("--alpha")
                val b = option("--beta")
                lastWins(a, b)
                val required = a.required()
                action<String>(human = { it }) { Ok("a=${required()} b=${b()}") }
            }
        }
        assertTrue("'--alpha', which is required" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a cardinality mutated from a sibling command is rejected just the same`() {
        // Unlike the case above, "a"'s own build() already ran and passed (alpha/beta were both still
        // Optional) the instant command("a") { } returned; "b" reaches alpha only through a captured
        // handle, after "a" is already frozen into a Command, so the guard above alone would miss this.
        lateinit var alpha: Opt<String?>
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("a") {
                    alpha = option("--alpha")
                    val beta = option("--beta")
                    lastWins(alpha, beta)
                    action { Ok("") }
                }
                command("b") {
                    alpha.required()
                    action { Ok("") }
                }
            }
        }
        assertTrue("'--alpha', which is required" in failure.message.orEmpty(), failure.message)
    }
}

/** `.requiredIf(flag)`: an option only the presence of another input makes mandatory. */
class RequiredIfTest {

    private fun tree(): Cli = cli("app") {
        val remote = flag("--remote", "-r", help = "operate against the remote")
        val token = option("--token", help = "credential for the remote").requiredIf(remote)
        action { Ok(token() ?: "local") }
    }

    @Test
    fun `the option is optional while the condition is absent`() {
        assertEquals("local\n", tree().out(listOf()))
    }

    @Test
    fun `the option binds normally when both are given`() {
        assertEquals("abc\n", tree().out(listOf("--remote", "--token", "abc")))
    }

    @Test
    fun `the condition without the option is a usage error`() {
        val error = tree().err(listOf("--remote"))
        assertEquals(CliError.MissingRequiredOption("--token"), error)
        assertEquals("missing required option --token", error.message())
    }

    @Test
    fun `the help row states the rule rather than leaving it to be discovered`() {
        val help = tree().helpText()
        assertTrue("required when --remote" in help, help)
    }

    @Test
    fun `a defaulted option satisfies the rule only when actually given`() {
        // Same supplied-ness reading the constraint checks use: a default always binds, so reading the
        // BOUND value here would call the rule satisfied on every line.
        val tree = cli("app") {
            val remote = flag("--remote")
            val token = option("--token").default("anon").requiredIf(remote)
            action { Ok(token()) }
        }
        assertEquals("anon\n", tree.out(listOf()))
        assertEquals(CliError.MissingRequiredOption("--token"), tree.err(listOf("--remote")))
        assertEquals("abc\n", tree.out(listOf("--remote", "--token", "abc")))
    }

    @Test
    fun `combining it with an unconditional required fails at build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val remote = flag("--remote")
                option("--token").required().requiredIf(remote)
                action { Ok("") }
            }
        }
        assertTrue("pointless" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a negatable global condition fires only on its positive spelling`() {
        // The one cell where the accumulator reads polarity rather than a hit count: a global condition and
        // a negatable one at once. Both halves of that read are exercised here, since nothing else is.
        val tree = cli("app") {
            val remote = globalFlag("--remote").negatable()
            command("c") {
                val token = option("--token").requiredIf(remote)
                action { Ok(token() ?: "local") }
            }
        }
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("c", "--no-remote")))
        assertEquals(
            CliError.MissingRequiredOption("--token"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("c", "--remote"))).error,
        )
    }

    @Test
    fun `a trigger declared on another command fails at build`() {
        // checkConditionalRequirements only ever asks THIS command's own sift plus globals whether the
        // trigger fired, so a flag belonging to sibling "a" is invisible to "b"'s check and the rule could
        // never fire, no matter what --help renders for it.
        lateinit var otherFlag: Flag
        val e = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("a") {
                    otherFlag = flag("--verbose")
                    action { Ok("") }
                }
                command("b") {
                    option("--token").requiredIf(otherFlag)
                    action { Ok("") }
                }
            }
        }
        assertTrue(
            e.message!!.startsWith(
                "command 'b': option '--token' declares .requiredIf(--verbose), which is not declared on 'b'",
            ),
            e.message,
        )
    }

    @Test
    fun `a trigger declared as a global still builds and fires`() {
        val tree = cli("app") {
            val remote = globalFlag("--remote")
            command("push") {
                val token = option("--token").requiredIf(remote)
                action { Ok(token() ?: "local") }
            }
        }
        assertEquals("local\n", tree.out(listOf("push")))
        assertEquals(CliError.MissingRequiredOption("--token"), tree.err(listOf("push", "--remote")))
    }
}
