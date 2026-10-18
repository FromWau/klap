package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.klap.internal.render.BuiltinOptionHelp
import com.fromwau.klap.internal.render.HelpStyle
import com.fromwau.klap.internal.render.argSummary
import com.fromwau.klap.internal.render.helpSections
import com.fromwau.klap.internal.render.helpText
import com.fromwau.klap.internal.render.metaHint
import com.fromwau.klap.internal.render.usageLine
import com.fromwau.klap.internal.render.wrap
import com.fromwau.klap.internal.spec.ArgumentSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A value whose toString() throws, to exercise display()'s placeholder fallback (mirrors RunnerTest's ThrowingToString). */
private object ThrowingDefaultValue {
    override fun toString(): String = throw RuntimeException("boom")
}

class HelpTest {

    @Test
    fun `arg summary marks cardinality`() {
        val cmd = cli("add") {
            argument("text")
            argument("note").optional()
            option(short = 'p', long = "priority").int().default(0)
            action { Ok("") }
        }
        assertEquals("<text> [note]", cmd.argSummary())
    }

    @Test
    fun `help text leaf lists arguments and options`() {
        val cmd = cli("add") {
            help = "Add a task"
            argument("text", help = "the task text")
            flag(short = 'd', long = "done", help = "mark it done")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue(help.startsWith("usage: add <text>"), help)
        assertTrue("the task text" in help, help)
        assertTrue("-d, --done" in help, help)
        assertTrue("      --help" in help, help)
    }

    @Test
    fun `help text lists builtin flags`() {
        val cmd = cli("todo") {
            version = Version.Of("1.0.0")
            argument("text")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("      --help" in help, help)
        assertTrue("--json" in help, help)
        assertTrue("--version" in help, help)
    }

    @Test
    fun `help text omits version flag when unversioned`() {
        val cmd = cli("add") {
            argument("text")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("      --help" in help, help)
        assertTrue("--version" !in help, help)
    }

    @Test
    fun `help groups subcommands under commands heading`() {
        val cmd = cli("app") {
            command("build") {
                help = "build it"
                action { Ok("") }
            }
            command("test") {
                help = "test it"
                action { Ok("") }
            }
        }
        val help = cmd.helpText()
        assertTrue("Commands:" in help, help)
        assertTrue(help.indexOf("Commands:") < help.indexOf("build"), help)
        // The injected completion/docs are commands too, under the same heading, not floating above it.
        assertTrue("completion" in help && "docs" in help, help)
        assertTrue(help.indexOf("Commands:") < help.indexOf("completion"), help)
    }

    @Test
    fun `help text group lists subcommands`() {
        val cmd = cli("config") {
            help = "Manage config"
            command("get") {
                help = "read a key"
                action { Ok("") }
            }
            command("set") {
                help = "write a key"
                action { Ok("") }
            }
        }
        val help = cmd.helpText()
        assertTrue("get" in help && "read a key" in help, help)
        assertTrue("set" in help && "write a key" in help, help)
    }

    @Test
    fun `help text root action tool still lists completion`() {
        // A single-command tool exposes `--completion` as a meta-option, not a subcommand, so it
        // shows up in help only as a substring, not as a `Commands:` entry.
        val cmd = cli("tally") {
            argument("files").multiple(min = 1)
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue(help.startsWith("usage: tally <files>"), help)
        // Assert the option token, not the bare word: "completion" also occurs inside the built-in's
        // description ("Print a shell completion script"), so it would pass even if the signature row
        // were dropped. The `--completion` signature is emitted by helpSections for a metaOptions tool.
        assertTrue("--completion" in help, help)
    }

    @Test
    fun `single command tool help lists meta options`() {
        val cmd = cli("wc") {
            argument("files").multiple(min = 1)
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("--completion=bash|zsh|fish|powershell" in help, help)
        assertTrue("--docs=markdown|man" in help, help)
        assertFalse("Commands:" in help, help)
    }

    @Test
    fun `a subcommand page of a root with its own action lists the meta options under Global options`() {
        // `app sub --completion bash` parses, so sub's page lists --completion and --docs where it lists --help.
        val cmd = cli("app") {
            action { Ok("") }
            command("sub") { action { Ok("") } }
        }
        val term = RecordingTerminal()
        assertEquals(0, cmd.run(arrayOf("sub", "--help"), term))
        val globals = term.out.toString().substringAfter("Global options:")
        assertTrue("--completion=bash|zsh|fish|powershell" in globals, globals)
        assertTrue("--docs=markdown|man" in globals, globals)
    }

    @Test
    fun `help shows choices required optional default`() {
        val cmd = cli("cvt") {
            option(long = "from").choice("celsius", "fahrenheit").required()
            option(long = "to").choice("celsius", "fahrenheit")
            option(long = "round").int().default(2)
            argument("value").optional()
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("--from=celsius|fahrenheit" in help, help)
        assertTrue("(required)" in help, help)
        assertTrue("(default: 2)" in help, help)
        assertTrue("(optional)" in help, help)
    }

    @Test
    fun `help text option shows value placeholder flag does not`() {
        val cmd = cli("run") {
            option(short = 'p', long = "priority", help = "prio")
            flag(short = 'v', long = "verbose", help = "loud")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-p, --priority=value" in help, help)
        assertTrue("-v, --verbose" in help, help)
        assertTrue("-v, --verbose=value" !in help, help)
        assertTrue("[options]" in help, help)
    }

    @Test
    fun `help shows range hint`() {
        val cmd = cli("net") {
            option(short = 'p', long = "port", help = "port to use").int().range(1..65535)
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("1..65535" in help, help)
    }

    @Test
    fun `help shows count flag hint`() {
        val cmd = cli("run") {
            flag(short = 'v', long = "verbose", help = "increase verbosity").count()
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-v, --verbose" in help, help)
        assertTrue("(repeatable)" in help, help)
    }

    @Test
    fun `help shows negatable flag with default on`() {
        val cmd = cli("run") {
            flag(long = "tint", help = "apply a tint").negatable()
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("--[no-]tint" in help, help)
        assertTrue("(default: on)" in help, help)
    }

    @Test
    fun `help shows negatable flag with default off`() {
        val cmd = cli("run") {
            flag(long = "tint", help = "apply a tint").negatable(default = false)
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("--[no-]tint" in help, help)
        assertTrue("(default: off)" in help, help)
    }

    @Test
    fun `help shows negatable flag with short name`() {
        val cmd = cli("run") {
            flag(short = 'c', long = "tint", help = "apply a tint").negatable()
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-c, --[no-]tint" in help, help)
    }

    @Test
    fun `help does not indent a row whose only short is on the negative half`() {
        val cmd = cli("run") {
            flag(long = "verify", help = "verify the thing").negatable(short = 'n', long = "no-verify")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-n, --verify, --no-verify" in help, help)
        assertFalse("    -n, --verify, --no-verify" in help, help)
    }

    @Test
    fun `help advertises color option under global options`() {
        val cmd = cli("app") {
            command("build") { action { Ok("") } }
        }
        val term = RecordingTerminal()
        cmd.run(arrayOf("--help"), term)
        val help = term.out.toString()
        assertTrue("Global options:" in help, help)
        assertTrue("--color[=${COLOR_MODE_NAMES.joinToString("|")}]" in help, help)
        assertTrue(BuiltinOptionHelp.COLOR in help, help)
        assertTrue(help.indexOf("--json") < help.indexOf("--color"), help)
    }

    @Test
    fun `help shows global options section on root and subcommand`() {
        val cmd = cli("app") {
            globalFlag(short = 'v', long = "verbose", help = "enable verbose logging")
            command("build") {
                action { Ok("") }
            }
        }

        val term = RecordingTerminal()
        cmd.run(arrayOf("--help"), term)
        val rootHelp = term.out.toString()
        assertTrue("Global options:" in rootHelp, rootHelp)
        assertTrue("-v, --verbose" in rootHelp, rootHelp)
        assertTrue("enable verbose logging" in rootHelp, rootHelp)

        val subTerm = RecordingTerminal()
        cmd.run(arrayOf("build", "--help"), subTerm)
        val buildHelp = subTerm.out.toString()
        assertTrue("Global options:" in buildHelp, buildHelp)
        assertTrue("-v, --verbose" in buildHelp, buildHelp)
    }

    @Test
    fun `help renders group headings for its inputs`() {
        val cmd = cli("deploy") {
            help = "Ship a build"
            group("Networking") {
                option(short = 'H', long = "host", help = "target host").required()
                option(short = 'p', long = "port", help = "target port").int().default(22)
            }
            group("Advanced") {
                flag(short = 'f', long = "force", help = "skip safety checks")
            }
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("Networking:" in help, help)
        assertTrue("-H, --host=value" in help, help)
        assertTrue("Advanced:" in help, help)
        assertTrue("-f, --force" in help, help)
        // Grouped inputs live under their heading, not in the default block above `Networking:`.
        assertTrue(help.indexOf("Networking:") < help.indexOf("-H, --host"), help)
    }

    @Test
    fun `help renders grouped subcommands`() {
        val cmd = cli("app") {
            command("status") {
                help = "show status"
                action { Ok("") }
            }
            group("Danger") {
                command("destroy") {
                    help = "delete everything"
                    action { Ok("") }
                }
            }
        }
        val help = cmd.helpText()
        assertTrue("Danger:" in help, help)
        assertTrue("destroy" in help && "delete everything" in help, help)
        assertTrue(help.indexOf("Danger:") < help.indexOf("destroy"), help)
    }

    @Test
    fun `help renders examples and epilog`() {
        val cmd = cli("deploy") {
            option(short = 'H', long = "host", help = "target host")
            example("deploy --host web1", "deploy to web1")
            example("deploy -H web2")
            epilogue = "Exit codes: 0 ok, 1 failure."
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("Examples:" in help, help)
        assertTrue("deploy --host web1" in help, help)
        assertTrue("deploy to web1" in help, help)
        assertTrue("deploy -H web2" in help, help)
        assertTrue("Exit codes: 0 ok, 1 failure." in help, help)
    }

    @Test
    fun `subcommand help renders its own epilogue not the roots`() {
        val tree = cli("app") {
            epilogue = "Root trailer."
            command("run") {
                epilogue = "Exit codes: 0 ok, 1 failure."
                action { Ok("") }
            }
        }
        val term = RecordingTerminal()
        tree.run(arrayOf("run", "--help"), term)

        val help = term.out.toString()
        assertTrue("Exit codes: 0 ok, 1 failure." in help, help)
        assertFalse("Root trailer." in help, help)
    }

    @Test
    fun `hidden option omitted from help but still parses`() {
        var seen: String? = null
        val cmd = cli("run") {
            val token = option(long = "internal-token").hidden()
            action {
                seen = token()
                Ok("")
            }
        }
        val help = cmd.helpText()
        assertFalse("internal-token" in help, help)

        val term = RecordingTerminal()
        val code = cmd.run(arrayOf("--internal-token", "secret"), term)
        assertEquals(0, code)
        assertEquals("secret", seen)
    }

    @Test
    fun `hidden subcommand omitted from help but still executes`() {
        var ran = false
        val cmd = cli("app") {
            command("build") {
                help = "build it"
                action { Ok("") }
            }
            command("debug-dump") {
                hidden = true
                action {
                    ran = true
                    Ok("")
                }
            }
        }
        val help = cmd.helpText()
        assertFalse("debug-dump" in help, help)
        assertTrue("build" in help, help)

        val term = RecordingTerminal()
        val code = cmd.run(arrayOf("debug-dump"), term)
        assertEquals(0, code)
        assertTrue(ran)
    }

    @Test
    fun `help wraps long descriptions at explicit columns`() {
        val long = "This is a deliberately long option description that must exceed the wrap budget so it spills over."
        val cmd = cli("run") {
            option(short = 'm', long = "mode", help = long)
            action { Ok("") }
        }
        val wrapped = cmd.helpText(style = HelpStyle(columns = 60, color = false))
        assertTrue(wrapped.count { it == '\n' } > cmd.helpText().count { it == '\n' }, wrapped)
        wrapped.lines().forEach { assertTrue(it.length <= 60, "line over 60 cols: '$it'") }
    }

    @Test
    fun `help wraps multi line description per logical line without premature break`() {
        // A description with an explicit newline is two logical lines. Each must wrap to the width
        // independently: the newline must not fold into a "word" that miscounts the running length
        // and forces a ragged early break on the line that follows it.
        val cmd = cli("demo") {
            help = "alpha beta gamma\none two three four five six seven"
            action { Ok("") }
        }
        val help = cmd.helpText(style = HelpStyle(columns = 20, color = false))
        // First logical line (16 cols) fits and stays intact; the second wraps greedily to 20 cols.
        assertTrue("alpha beta gamma\none two three four\nfive six seven" in help, help)
        // Guard against the pre-fix behavior, where "one" was orphaned onto its own line.
        assertFalse("\none\n" in help, help)
    }

    @Test
    fun `help stacks description at narrow width without overflow`() {
        // At a narrow terminal the aligned description column can't fit, so the description stacks
        // under the signature and wraps to the full width instead of overflowing. Uses a subcommand so
        // no injected completion/docs rows (root-only, unbreakable tokens) intrude. The width here must
        // still fit the unconditional `--color[=auto|always|never]` global row's signature (an
        // unbreakable token, like a URL, that the wrapper never splits), while staying narrow enough to
        // exercise the stacking path rather than the aligned two-column layout.
        val long = "a long description that must wrap across several lines at this narrow width"
        val root = cli("app") {
            command("run") {
                option(short = 'm', long = "mode", help = long)
                action { Ok("") }
            }
        }
        val run = root.subcommand("run")!!
        val help = run.helpText("app run", style = HelpStyle(columns = 40, color = false))
        help.lines().forEach { assertTrue(it.length <= 40, "line over 40 cols: '$it'") }
        // The description text still shows up, just wrapped.
        assertTrue("description" in help, help)
        assertTrue("wrap" in help, help)
    }

    @Test
    fun `wrap leaves an overlong token intact on its own line`() {
        val url = "https://example.com/really/long/path/that/exceeds/any/reasonable/width"
        val lines = wrap("see $url now", width = 20)
        // An unbreakable token wider than the budget is not hard-split; it sits alone on its line.
        assertTrue(url in lines, lines.toString())
    }

    @Test
    fun `color escapes appear only when style enables color`() {
        val cmd = cli("run") {
            help = "do a thing"
            option(short = 'm', long = "mode", help = "the mode")
            action { Ok("") }
        }
        val esc = Char(27)
        assertFalse(esc in cmd.helpText(), "plain help must carry no escape codes")
        val colored = cmd.helpText(style = HelpStyle(columns = 0, color = true))
        assertTrue(esc in colored, "colored help must carry escape codes")
    }

    @Test
    fun `versioned dispatcher lists version on subcommand help`() {
        // --version works from any subcommand (Parser.kt recognizes it position-independently on the
        // root), so a subcommand's help should list it too, not just the root's.
        val cmd = cli("app") {
            version = Version.Of("1.0")
            command("build") { action { Ok("") } }
        }
        val term = RecordingTerminal()
        cmd.run(arrayOf("build", "--help"), term)
        assertTrue("--version" in term.out.toString(), term.out.toString())
    }

    @Test
    fun `non versioned dispatcher omits version on subcommand help`() {
        val cmd = cli("app") {
            command("build") { action { Ok("") } }
        }
        val term = RecordingTerminal()
        cmd.run(arrayOf("build", "--help"), term)
        assertTrue("--version" !in term.out.toString(), term.out.toString())
    }

    @Test
    fun `versioned dispatcher still lists version on root help`() {
        val cmd = cli("app") {
            version = Version.Of("1.0")
            command("build") { action { Ok("") } }
        }
        val term = RecordingTerminal()
        cmd.run(arrayOf("--help"), term)
        assertTrue("--version" in term.out.toString(), term.out.toString())
    }

    @Test
    fun `simple command default help layout`() {
        // Regression guard on the exact PLAIN layout: args/options in the default block, and the
        // position-independent built-ins (--help, --json) under a trailing `Global options` block.
        // Uses a subcommand so no injected `completion` row (root-only) perturbs the default block.
        val root = cli("app") {
            command("add") {
                help = "Add a task"
                argument("text", help = "the task text")
                flag(short = 'd', long = "done", help = "mark it done")
                action { Ok("") }
            }
        }
        val add = root.subcommand("add")!!
        val expected = buildString {
            append("usage: app add <text> [options]\n\n")
            append("Add a task\n\n")
            append("  <text>                           the task text (required)\n")
            append("  -d, --done                       mark it done\n\n")
            append("Global options:\n")
            append("      --help                       Show this help\n")
            append("      --json                       Output as JSON\n")
            append("      --color[=auto|always|never]  Colorize output: auto, always, or never (bare: always)")
        }
        assertEquals(expected, add.helpText("app add"))
    }

    @Test
    fun `help all renders every descendant help recursively`() {
        val tree = cli("app") {
            builtins { help = Help.ByTree() }
            command("build") {
                help = "build things"
                option(short = 't', long = "target", help = "what to build").choice("debug", "release")
                action { Ok("") }
            }
            command("remote") {
                help = "manage remotes"
                command("add") {
                    help = "add a remote"
                    argument("url", "the remote url")
                    action { Ok("") }
                }
            }
        }
        val all = RecordingTerminal()
        assertEquals(0, tree.run(arrayOf("--help-all"), all))
        val out = all.out.toString()
        assertTrue("usage: app build" in out, out)              // a depth-1 subcommand's own block
        assertTrue("--target" in out, out)                      // build's option, absent from the shallow root help
        assertTrue("usage: app remote add" in out, out)         // a depth-2 node
        assertTrue("<url>" in out, out)                         // remote add's argument

        // Contrast: the shallow --help does NOT descend into a subcommand's options.
        val shallow = RecordingTerminal()
        tree.run(arrayOf("--help"), shallow)
        assertFalse("--target" in shallow.out.toString(), shallow.out.toString())
    }

    @Test
    fun `help all scopes to the subtree it is invoked on`() {
        val tree = cli("app") {
            builtins { help = Help.ByTree() }
            command("build") {
                help = "build things"
                option(short = 't', long = "target", help = "what to build").choice("debug", "release")
                action { Ok("") }
            }
            command("remote") {
                help = "manage remotes"
                command("add") {
                    help = "add a remote"
                    argument("url")
                    action { Ok("") }
                }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(arrayOf("remote", "--help-all"), t))
        val out = t.out.toString()
        assertTrue("usage: app remote add" in out, out)         // inside the remote subtree
        assertFalse("usage: app build" in out, out)             // NOT the sibling subtree
        assertFalse("--target" in out, out)
    }

    @Test
    fun `help all row advertised for dispatcher not leaf`() {
        val tree = cli("app") {
            builtins { help = Help.ByTree() }
            command("build") { help = "build things"; action { Ok("") } }
        }
        val root = RecordingTerminal()
        tree.run(arrayOf("--help"), root)
        assertTrue("--help-all" in root.out.toString(), root.out.toString())

        val leaf = RecordingTerminal()
        tree.run(arrayOf("build", "--help"), leaf)
        assertFalse("--help-all" in leaf.out.toString(), leaf.out.toString())
    }

    @Test
    fun `help all is a reserved flag name`() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                builtins { help = Help.ByTree() }
                command("go") {
                    flag(long = "help-all")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun `help row has exactly one space before the required hint`() {
        // The single leading space is produced by metaHint(), which helpRows() emits verbatim as
        // help + metaHint(). Assert it here rather than on cmd.helpText(): the plain renderer
        // collapses whitespace in every row, so a double space regression is invisible once rendered.
        val spec = ArgumentSpec("unit", "source unit", { Result.Success(it) })
        assertEquals(" (required)", spec.metaHint())

        // The single-spaced hint also survives end to end in the rendered help.
        val cmd = cli("cvt") {
            option(long = "from", help = "source unit").required()
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("source unit (required)" in help, help)
    }

    @Test
    fun `help renders empty string default without orphaning the closing paren`() {
        // Reproduces the QA report at the width it broke on (COLUMNS=79, also the non-TTY fallback
        // width): an option with .default("") renders its meta-hint as "(default: )" (a dangling
        // space before the paren). Word-wrap treats "(default:" and ")" as two separate tokens, so at
        // this width the ')' wraps onto its own orphaned line, with nothing else on it.
        val root = cli("textkit") {
            command("head") {
                option(short = 'n', long = "lines", help = "how many lines").int().default(10)
                option(
                    long = "tag",
                    help = "attach a label to output for grouping runs together so operators can filter logs later during review",
                ).default("")
                flag(long = "number", help = "prefix each line with its number").negatable(default = false)
                action { Ok("") }
            }
        }
        val head = root.subcommand("head")!!
        val wrapped = head.helpText("textkit head", style = HelpStyle(columns = 79, color = false))
        // At this width the description still wraps (the help text is long), so the hint can still
        // split across lines; the point is that a bare, meaningless ")" never sits alone on a line.
        assertFalse(wrapped.lines().any { it.trim() == ")" }, wrapped)
        assertTrue("\"\"" in wrapped, wrapped)
    }

    @Test
    fun `help renders empty string default sensibly on the plain path`() {
        val cmd = cli("cvt") {
            option(long = "tag", help = "a label").default("")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("(default: \"\")" in help, help)
        assertFalse("(default: )" in help, help)
    }

    @Test
    fun `help collapses embedded newline in help text to one line`() {
        val cmd = cli("run") {
            option(short = 'm', long = "mode", help = "line one\nline two")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("line one line two" in help, help)
        val row = help.lines().firstOrNull { "line one" in it }
        assertTrue(row != null && "line two" in row, help)
    }

    @Test
    fun `help shows subcommand aliases in commands section`() {
        val cmd = cli("app") {
            command("list") {
                help = "list items"
                aliases = listOf("ls")
                action { Ok("") }
            }
        }
        val help = cmd.helpText()
        assertTrue("list, ls" in help, help)
    }

    @Test
    fun `a command's help is its row's description`() {
        val cmd = cli("app") {
            command("build") { help = "compile the project"; action { Ok("") } }
        }
        val help = cmd.helpText()
        assertTrue("compile the project" in help, help)
    }

    @Test
    fun `help renders placeholder when default value toString throws`() {
        // A positional's .default(v) whose toString() throws must not escape --help rendering: display()
        // guards it the same way Action.kt's renderHuman guards a throwing result on the action-output
        // path, falling back to a placeholder instead of letting the exception propagate.
        val cmd = cli("app") {
            argument("x").map { ThrowingDefaultValue }.default(ThrowingDefaultValue)
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("[x=<unprintable>]" in help, help)
    }

    @Test
    fun `help row lists every spelling shorts first`() {
        val cmd = cli("rm") {
            flag(short = listOf('r', 'R'), long = "recursive", help = "remove directories recursively")
            option(short = 'a', long = listOf("since", "after"), help = "lower time bound")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-r, -R, --recursive" in help, help)
        assertTrue("-a, --since, --after=value" in help, help)
    }

    @Test
    fun `help row keeps the longs in declaration order so the primary leads`() {
        // Sorting the row by spelling length would put the shorter secondary long first, and the
        // primary is what every error message and the constraint hint name.
        val cmd = cli("app") {
            option(short = 'v', long = listOf("verbose", "loud"), help = "how much to say")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-v, --verbose, --loud=value" in help, help)
    }

    @Test
    fun `help row of a short only input carries no long form`() {
        val cmd = cli("diff") {
            option(short = 'Z', help = "lines of context")
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-Z value" in help, help)
        assertFalse("--Z" in help, help)
    }

    @Test
    fun `help row of a multi long negatable flag brackets every long form`() {
        val cmd = cli("app") {
            flag(short = 't', long = listOf("tint", "colour"), help = "colourise").negatable(default = true)
            action { Ok("") }
        }
        val help = cmd.helpText()
        assertTrue("-t, --[no-]tint, --[no-]colour" in help, help)
    }

    @Test
    fun `hidden subcommand alias not shown in help`() {
        val cmd = cli("app") {
            command("visible") { action { Ok("") } }
            command("secret") {
                hidden = true
                aliases = listOf("shh")
                action { Ok("") }
            }
        }
        val help = cmd.helpText()
        assertFalse("shh" in help, help)
    }
}

/** A required option is part of how the command is called, so it belongs in the usage line. */
class RequiredOptionUsageTest {

    @Test
    fun `a required option appears in the usage line`() {
        val cmd = cli("app") {
            option(short = 'H', long = "host").required()
            option(short = 'p', long = "port")
            action { Ok("") }
        }
        assertEquals("usage: app --host=value [options]", cmd.usageLine())
    }

    @Test
    fun `an optional one stays folded into the options placeholder`() {
        val cmd = cli("app") {
            option(short = 'p', long = "port")
            action { Ok("") }
        }
        assertEquals("usage: app [options]", cmd.usageLine())
    }

    @Test
    fun `the value placeholder matches the ones the help row shows`() {
        val cmd = cli("app") {
            option(short = 'o', long = "out").placeholder("FILE").required()
            option(long = "mode").choice("fast", "slow").required()
            action { Ok("") }
        }
        assertEquals("usage: app --out=FILE --mode=fast|slow [options]", cmd.usageLine())
    }

    @Test
    fun `a required option sits after the constraint groups and before the positionals`() {
        val cmd = cli("tar") {
            val create = flag(short = 'c', long = "create")
            val extract = flag(short = 'x', long = "extract")
            exactlyOne(create, extract)
            option(short = 'f', long = "file").required()
            argument("path").multiple(min = 0)
            action { Ok("") }
        }
        assertEquals("usage: tar (-c|-x) --file=value [path...] [options]", cmd.usageLine())
    }

    @Test
    fun `a required option inside a constraint set is named only by its group`() {
        // Listing it twice would say the SET is required and that this one member is, which contradict.
        val cmd = cli("app") {
            val a = option(long = "alpha").required()
            val b = option(long = "beta").required()
            exactlyOne(a, b)
            action { Ok("") }
        }
        assertEquals("usage: app (--alpha|--beta) [options]", cmd.usageLine())
    }

    @Test
    fun `a hidden required option stays out of the usage line`() {
        val cmd = cli("app") {
            option(long = "secret").required().hidden()
            option(long = "port")
            action { Ok("") }
        }
        assertEquals("usage: app [options]", cmd.usageLine())
    }
}

/** An option's value is written as GNU writes it: cp's, od's and xargs's `--help` rows. */
class ValuePlaceholderHelpTest {

    private fun signatures(build: CliBuilder.() -> Unit): List<String> = cli("app") {
        builtins { color = Builtin.Off }
        build()
        action { Ok("") }
    }.helpSections().flatMap { section -> section.rows.map { it.signature.trim() } }

    @Test
    fun `a value follows the long spelling after an equals sign`() {
        val rows = signatures {
            option(short = 'S', long = "suffix", help = "override the usual backup suffix").placeholder("SUFFIX")
            option(long = "sparse", help = "control creation of sparse files").placeholder("WHEN")
        }
        assertEquals("-S, --suffix=SUFFIX", rows[0])
        assertEquals("--sparse=WHEN", rows[1])
    }

    @Test
    fun `an optional value is bracketed after an equals sign`() {
        val rows = signatures {
            option(long = "backup", help = "make a backup").placeholder("CONTROL").optionalValue("existing")
        }
        assertEquals("--backup[=CONTROL]", rows[0])
    }

    @Test
    fun `an optional value is bracketed after the short as well when the option has both`() {
        val rows = signatures {
            option(short = 'w', long = "width", help = "output BYTES bytes per line")
                .placeholder("BYTES")
                .optionalValue("32")
        }
        assertEquals("-w[BYTES], --width[=BYTES]", rows[0])
    }

    @Test
    fun `a short-only option takes its value after a space`() {
        val rows = signatures {
            option(short = 'E', help = "set logical EOF string").placeholder("END")
            option(short = 'l', help = "similar to -L").placeholder("MAX-LINES").optionalValue("1")
        }
        assertEquals("-E END", rows[0])
        assertEquals("-l[MAX-LINES]", rows[1])
    }

    @Test
    fun `a required option shows its value in the usage line as its help row does`() {
        val cmd = cli("app") {
            option(long = "host").placeholder("HOST").required()
            option(short = 't').placeholder("DIR").required()
            action { Ok("") }
        }
        assertEquals("usage: app --host=HOST -t DIR [options]", cmd.usageLine())
    }
}

/** An optional value is visible in the signature, or nobody discovers the bare form. */
class OptionalValueHelpTest {

    @Test
    fun `the help row brackets the optional value`() {
        val cmd = cli("ls") {
            // --color collides with klap's own built-in of the same name; free it the same way
            // BuiltinsOptOutTest does, so the option under test can use the name unchanged.
            builtins { color = Builtin.Off }
            option(long = "color", help = "colorize the output").optionalValue("always")
            action { Ok("") }
        }
        assertTrue("--color[=value]" in cmd.helpText(), cmd.helpText())
    }

    @Test
    fun `a metavar is used inside the brackets`() {
        val cmd = cli("ls") {
            builtins { color = Builtin.Off }
            option(long = "color", help = "colorize").placeholder("WHEN").optionalValue("always")
            action { Ok("") }
        }
        assertTrue("--color[=WHEN]" in cmd.helpText(), cmd.helpText())
    }

    @Test
    fun `a choice list is used inside the brackets`() {
        val cmd = cli("ls") {
            builtins { color = Builtin.Off }
            option(long = "color").choice("always", "auto", "never").optionalValue("always")
            action { Ok("") }
        }
        assertTrue("--color[=always|auto|never]" in cmd.helpText(), cmd.helpText())
    }

    @Test
    fun `a short spelling brackets it too`() {
        // od writes `-w[BYTES], --width[=BYTES]`: a short reads an optional value only glued, so it shows one too.
        val cmd = cli("git") {
            option(short = 'S', long = "gpg-sign", help = "sign it").placeholder("keyid").optionalValue("default")
            action { Ok("") }
        }
        assertTrue("-S[keyid], --gpg-sign[=keyid]" in cmd.helpText(), cmd.helpText())
    }

    @Test
    fun `a short-only spelling glues the optional value without an equals sign`() {
        // getopt reads `-S=abc` as the value `=abc`, so the attached form of a short is `-Sabc`, as sed's `-i[SUFFIX]`.
        val cmd = cli("git") {
            option(short = 'S', help = "sign it").placeholder("keyid").optionalValue("default")
            action { Ok("") }
        }
        assertTrue("-S[keyid]" in cmd.helpText(), cmd.helpText())
        val required = cli("app") {
            option(short = 'm').placeholder("M").optionalValue("fast").required()
            action { Ok("") }
        }
        assertEquals("usage: app -m[M] [options]", required.usageLine())
    }

    @Test
    fun `the color builtin is shown as an optional value option is`() {
        // ls --help shows `--color[=WHEN]`; klap's built-in takes the signature a declared `.optionalValue()` gets.
        val builtin = cli("ls") { action { Ok("") } }.helpText()
        val declared = cli("ls") {
            option(long = "colour", help = "colorize").choice("auto", "always", "never").optionalValue("always")
            action { Ok("") }
        }.helpText()
        fun row(help: String, long: String) = help
            .lines()
            .single { it.trim().startsWith(long) }
            .trim()
            .split(Regex(" {2,}"))
        assertEquals("--colour[=auto|always|never]", row(declared, "--colour").first())
        assertEquals(
            listOf("--color[=auto|always|never]", "Colorize output: auto, always, or never (bare: always)"),
            row(builtin, "--color"),
        )
    }

    @Test
    fun `the bare value is stated so the default is discoverable`() {
        val cmd = cli("ls") {
            builtins { color = Builtin.Off }
            option(long = "color", help = "colorize").placeholder("WHEN").optionalValue("always")
            action { Ok("") }
        }
        assertTrue("bare: always" in cmd.helpText(), cmd.helpText())
    }

    @Test
    fun `an option with a short renders both forms and its placeholder`() {
        val cmd = cli("app") {
            option(short = 'o', long = "out", help = "where to write")
            action { Ok("") }
        }
        assertTrue("-o, --out=value" in cmd.helpText(), cmd.helpText())
    }

    @Test
    fun `a required optional value option renders bracketed in the usage line`() {
        val cmd = cli("app") {
            option(long = "mode").placeholder("M").optionalValue("fast").required()
            action { Ok("") }
        }
        assertEquals("usage: app --mode[=M] [options]", cmd.usageLine())
    }
}
