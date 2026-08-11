package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.kern.terminal.green
import com.fromwau.kern.terminal.red
import com.fromwau.klap.internal.parse.sift
import com.fromwau.klap.internal.render.BuiltinOptionHelp
import com.fromwau.klap.internal.render.Candidate
import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun sampleTree(): Cli = cli("todo") {
    command("add") { argument("file").file(); action { Ok("") } }
    command("config") {
        command("get") { action { Ok("") } }
        command("set") { action { Ok("") } }
    }
}

/** Drives the same planner `__complete` answers from, for a tree with no subcommand to route through first. */
private fun Cli.completionsFor(vararg words: String): List<String> = completeCandidates(words.toList()).map { it.value }

private fun globalPrecedingSubcommandTree(): Cli = cli("myapp") {
    globalFlag("--verbose", "-v")
    command("issue") {
        command("show") {
            argument("state").choice("open", "closed")
            action { Ok("") }
        }
    }
}

class ShortClusterCompletionTest {

    private val cli = cli("tasks") {
        command("list") {
            flag("--reverse", "-r", help = "newest first")
            flag("--long", "-l", help = "show due date and tags")
            option("--limit", "-n", help = "show at most this many")
            action { Ok("") }
        }
    }

    @Test
    fun `a partial short cluster offers every remaining short as a continuation`() {
        // Guideline 5 bundles one-character options into one token, so a half-typed cluster has
        // continuations: `-r<TAB>` must offer them, not just `-r` itself.
        val candidates = cli.completionsFor("list", "-r")
        assertTrue("-rl" in candidates, "expected -rl among $candidates")
        assertTrue("-rn" in candidates, "expected -rn among $candidates")
        assertTrue("-r" in candidates, "the exact match should survive: $candidates")
        // Never itself twice: a cluster binds each option once.
        assertTrue("-rr" !in candidates, "a short should not repeat itself: $candidates")
    }

    @Test
    fun `clustering continues past the second flag`() {
        val candidates = cli.completionsFor("list", "-rl")
        assertTrue("-rln" in candidates, "expected -rln among $candidates")
        assertTrue("-rlr" !in candidates, "already-typed shorts stay out: $candidates")
    }

    @Test
    fun `a value taking short ends the cluster so nothing is offered after it`() {
        // `-n` consumes the rest of the token as its value, so no further short can follow it. Offering
        // one would suggest a line klap then rejects.
        assertTrue(cli.completionsFor("list", "-rn").none { it.length > 3 }, "nothing may follow a value-taker")
        assertEquals(emptyList(), cli.completionsFor("list", "-n").filter { it != "-n" })
    }

    @Test
    fun `a long option is never treated as a cluster`() {
        // `--r` is a prefix of one long name, not a bundle of shorts.
        assertEquals(listOf("--reverse"), cli.completionsFor("list", "--r"))
    }
}

class CompletionTest {

    @Test
    fun `candidate encodes value tab description and sanitizes`() {
        assertEquals("1", Candidate("1").toCompletionLine())
        assertEquals("1\tBuy Beer", Candidate("1", "Buy Beer").toCompletionLine())
        assertEquals("1\ta b c", Candidate("1", "a\tb\nc").toCompletionLine())   // tab/newline collapse to space
        assertEquals("1", Candidate("1", "   ").toCompletionLine())               // blank description -> bare value
    }

    @Test
    fun `shell of is case insensitive`() {
        assertEquals(CompletionShell.FISH, CompletionShell.fromOrNull("Fish"))
        assertEquals(CompletionShell.POWERSHELL, CompletionShell.fromOrNull("PowerShell"))
    }
}

/** The scripts klap emits per shell: every one delegates to `__complete` and renders what it returns. */
class GeneratedShellScriptTest {


    @Test
    fun `bash delegates every completion to complete`() {
        val script = sampleTree().renderCompletion(CompletionShell.BASH)
        // COMP_WORDBREAKS (default includes `=`) would split an attached `--opt=value` word before we
        // ever see it, so the words reaching __complete are reconstructed from COMP_LINE/COMP_POINT
        // (whitespace-split only) instead of trusted straight off COMP_WORDS.
        assertTrue($$"local line=\"${COMP_LINE:0:COMP_POINT}\"" in script, script)
        assertTrue($$"read -ra relWords <<< \"$line\"" in script, script)
        assertTrue(
            $$"mapfile -t lines < <(\"${COMP_WORDS[0]}\" __complete -- \"${relWords[@]}\" \"$fullCur\")" in script,
            script,
        )
        assertTrue("complete -F _todo todo" in script, script)
    }

    @Test
    fun `bash reattaches a prefix when the current word was not wordbreak split`() {
        // A glued short option (`-tr`) has no COMP_WORDBREAKS char, so bash's own $cur equals the whole
        // word and would replace all of it; the script must reconstruct just the flag prefix ("-t") to
        // re-prepend to a bare value candidate ("red"), not drop it or double it.
        val script = sampleTree().renderCompletion(CompletionShell.BASH)
        assertTrue($$"if [ \"$fullCur\" = \"$cur\" ]; then" in script, script)
        assertTrue($$"COMPREPLY+=(\"${flagPrefix}${candidate}\")" in script, script)
    }

    @Test
    fun `zsh delegates every completion to complete`() {
        val script = sampleTree().renderCompletion(CompletionShell.ZSH)
        assertTrue("#compdef todo" in script, script)
        assertTrue("__complete" in script, script)
        assertTrue($$"compadd -d descriptions -- \"${values[@]}\"" in script, script)
    }

    @Test
    fun `zsh moves the attached value prefix into iprefix before compadd`() {
        // zsh's $words keeps an attached `--tag=r` or glued `-tr` as ONE word, so compadd would match
        // "red" against the whole typed token unless compset -p first moves the attached prefix into
        // $IPREFIX; that reconciliation must happen before compadd runs for this ordering check to hold.
        val script = sampleTree().renderCompletion(CompletionShell.ZSH)
        val compsetIndex = script.indexOf("compset -p ")
        val compaddIndex = script.indexOf($$"compadd -d descriptions -- \"${values[@]}\"")
        assertTrue(compsetIndex >= 0, script)
        assertTrue(compsetIndex < compaddIndex, script)
    }

    @Test
    fun `fish delegates every completion to complete`() {
        val script = sampleTree().renderCompletion(CompletionShell.FISH)
        assertTrue("__complete" in script, script)
        assertTrue("complete -c todo -f -a '(__todo_klap_complete)'" in script, script)
    }

    @Test
    fun `fish emits candidates with printf not echo`() {
        // fish's `echo` treats a leading -e/-n/-s/-E as ITS OWN flag when it's the whole argument,
        // silently swallowing a candidate that happens to look like one; printf's first argument is
        // always its format string, so a candidate value can never be mistaken for one of its flags.
        val script = sampleTree().renderCompletion(CompletionShell.FISH)
        assertTrue("printf '%s\\n'" in script, script)
        assertTrue($$"echo $line" !in script, script)
        assertTrue($$"echo $response" !in script, script)
    }

    @Test
    fun `fish reattaches a prefix when the current token was not already a candidate prefix`() {
        // fish's `commandline -ct` never splits an attached word, so __complete already returns the bare
        // value ("red") for `--tag=r` or a glued `-tr`; fish's own pager still prefix-matches candidates
        // against the WHOLE current token, so the script must reconcile and re-prepend the flag/`=` part.
        val script = sampleTree().renderCompletion(CompletionShell.FISH)
        assertTrue($$"string sub -s -$k -- \"$current\"" in script, script)
        assertTrue($$"printf '%s\\n' \"$flagPrefix$line\"" in script, script)
    }

    @Test
    fun `powershell generates and shell of resolves`() {
        assertEquals(CompletionShell.POWERSHELL, CompletionShell.fromOrNull("powershell"))
        assertEquals(CompletionShell.POWERSHELL, CompletionShell.fromOrNull("PowerShell"))
        val script = sampleTree().renderCompletion(CompletionShell.POWERSHELL)
        assertTrue("Register-ArgumentCompleter -Native -CommandName todo" in script, script)
    }

    @Test
    fun `powershell compensates cursor past last element`() {
        val script = sampleTree().renderCompletion(CompletionShell.POWERSHELL)
        assertTrue($$"$commandAst.CommandElements[-1].Extent.EndOffset -lt $cursorPosition" in script, script)
        assertTrue($$"$words += ''" in script, script)
    }

    @Test
    fun `powershell file completion preserves directory prefix`() {
        // `Get-ChildItem -Name` returns only the leaf, which would complete `src/ma<TAB>` to `main.kt`
        // and lose the `src/`, so the script re-prepends the typed prefix and marks a directory match to
        // tab-descend. commonTest cannot run PowerShell, so anchor the script instead.
        val script = sampleTree().renderCompletion(CompletionShell.POWERSHELL)
        assertTrue("Get-ChildItem -Name" !in script, script)
        assertTrue($$"$completion = \"$nonPath$prefix$($_.Name)\"" in script, script)
        assertTrue($$"$_.PSIsContainer" in script, script)
        assertTrue("[System.IO.Path]::DirectorySeparatorChar" in script, script)
    }

    @Test
    fun `bash drops the description column keeping value only`() {
        // Each __complete line is `value` or `value\tdescription`; bash's menu cannot show a per-candidate
        // description, so it must strip from the FIRST tab on and keep the value only (decision 3). A
        // COMPLETE_FILES line has no tab and is unchanged by the strip, so its sentinel mapping still fires.
        val script = sampleTree().renderCompletion(CompletionShell.BASH)
        assertTrue($$"lines[$i]=\"${lines[$i]%%$'\\t'*}\"" in script, script)
    }

    @Test
    fun `zsh decodes value tab description into compadd display array`() {
        // zsh splits each line into a $values array (matched/inserted) and a parallel $descriptions display
        // array rendered as "value  -- description", fed to compadd via -d; a description-less line shows bare.
        val script = sampleTree().renderCompletion(CompletionShell.ZSH)
        assertTrue($$"values+=(\"${line%%$'\\t'*}\")" in script, script)
        assertTrue($$"descriptions+=(\"${line%%$'\\t'*}  -- ${line#*$'\\t'}\")" in script, script)
        assertTrue($$"compadd -d descriptions -- \"${values[@]}\"" in script, script)
    }

    @Test
    fun `fish forwards value tab description line for native rendering`() {
        // fish's completion reads `value\tdescription` natively, so each raw response line is emitted WHOLE
        // (only the flag prefix is re-prepended to its head); fish then splits it into the inserted value
        // and the shown description. Contrast bash, which strips the description off.
        val script = sampleTree().renderCompletion(CompletionShell.FISH)
        assertTrue($$"for line in $response" in script, script)
        assertTrue($$"printf '%s\\n' \"$flagPrefix$line\"" in script, script)
    }

    @Test
    fun `powershell decodes value tab description into tool tip`() {
        // PowerShell splits each line on the FIRST tab: the value is the completion + list item, the
        // description becomes the tooltip; a description-less line tooltips the value.
        val script = sampleTree().renderCompletion(CompletionShell.POWERSHELL)
        assertTrue($$"$value, $tip = $_ -split \"`t\", 2" in script, script)
        assertTrue(
            $$"[System.Management.Automation.CompletionResult]::new($value, $value, 'ParameterValue', $tip)" in script,
            script,
        )
    }
}

/** The hidden `__complete` path end to end, and the shell-side reading of its output. */
class CompleteBuiltinRoundTripTest {


    @Test
    fun `complete subcommand returns provider candidates`() {
        val tree = cli("todo") {
            command("checkout") {
                argument("branch", help = "branch name").completeWith { candidates(listOf("main", "develop", "feature-x")) }
                action { Ok("") }
            }
        }
        val t = RecordingTerminal()
        val code = tree.run(arrayOf("__complete", "checkout", "fe"), t)
        assertEquals(0, code)
        assertEquals("feature-x", t.out.toString().trim())
    }

    @Test
    fun `complete through run emits value tab description line on stdout`() {
        val tree = cli("todo") {
            command("rm") {
                argument("id").completeWith { candidate("1", "Buy Beer") }
                action { Ok("") }
            }
        }
        val t = RecordingTerminal()
        val code = tree.run(arrayOf("__complete", "rm", ""), t)
        assertEquals(0, code)
        assertEquals("1\tBuy Beer", t.out.toString().trim())
    }

    @Test
    fun `bash uses mapfile not compgen reexpansion`() {
        val tree = cli("todo") {
            command("checkout") {
                argument("branch", help = "branch name").completeWith { candidates(listOf("main", "develop")) }
                action { Ok("") }
            }
        }
        val bash = tree.renderCompletion(CompletionShell.BASH)
        assertTrue("mapfile -t COMPREPLY" in bash, bash)
        // The re-expanding `compgen -W "$(...)"` form must be gone, so a runtime candidate cannot execute.
        assertTrue("compgen -W \"$(" !in bash, bash)
    }

    @Test
    fun `every shell consumes complete output literally`() {
        // The value-escaping guarantee in the delegated model: candidate text from __complete is offered
        // verbatim, never re-evaluated, so an attacker-influenced candidate cannot execute on Tab.
        val tree = sampleTree()

        val bash = tree.renderCompletion(CompletionShell.BASH)
        assertTrue("mapfile -t COMPREPLY < <(" in bash, bash)
        assertTrue("compgen -W \"$(" !in bash, bash)

        val zsh = tree.renderCompletion(CompletionShell.ZSH)
        assertTrue($$"compadd -d descriptions -- \"${values[@]}\"" in zsh, zsh)
        assertTrue("eval" !in zsh, zsh)

        val fish = tree.renderCompletion(CompletionShell.FISH)
        assertTrue($$"for line in $response" in fish, fish)
        assertTrue("eval" !in fish, fish)

        val pwsh = tree.renderCompletion(CompletionShell.POWERSHELL)
        assertTrue("CompletionResult" in pwsh, pwsh)
        assertTrue("Invoke-Expression" !in pwsh, pwsh)
        assertTrue("iex " !in pwsh, pwsh)
    }
}

/** Which subcommands a half-typed line offers, and which it must keep out of sight. */
class SubcommandCandidateTest {


    @Test
    fun `complete candidates returns nested subcommands`() {
        val app = cli("app") {
            command("rollout") {
                command("status") { action { Ok("") } }
                command("undo") { action { Ok("") } }
            }
        }
        val cands = app.completeCandidates(listOf("rollout", "")).map { it.value }
        assertTrue("status" in cands, cands.toString())
        assertTrue("undo" in cands, cands.toString())
    }

    @Test
    fun `complete candidates descends to depth two not parent children`() {
        val app = cli("app") {
            command("rollout") {
                command("status") {
                    argument("name").completeWith { candidates(listOf("web", "worker").filter { it.startsWith(current) }) }
                    action { Ok("") }
                }
                command("undo") { action { Ok("") } }
            }
        }
        // The cursor is inside `status`, so its positional provider answers, not rollout's children.
        assertEquals(listOf("web", "worker"), app.completeCandidates(listOf("rollout", "status", "w")).map { it.value })
    }

    @Test
    fun `complete candidates excludes hidden subcommands and builtins`() {
        val app = cli("app") {
            command("visible") { action { Ok("") } }
            command("secret") {
                hidden = true
                action { Ok("") }
            }
        }
        val cands = app.completeCandidates(listOf("")).map { it.value }
        assertTrue("visible" in cands, cands.toString())
        assertTrue("secret" !in cands, cands.toString())
        assertTrue("__complete" !in cands, cands.toString())
    }
}

/** A slot marked for files answers with a sentinel the generated script turns into native completion. */
class FileCompletionSentinelTest {


    @Test
    fun `file slot yields sentinel and bash maps it to compgen f`() {
        val tree = cli("todo") {
            command("add") {
                argument("file", help = "target file").file()
                action { Ok("") }
            }
        }
        assertEquals(listOf(COMPLETE_FILES), tree.completeCandidates(listOf("add", "")).map { it.value })

        val bash = tree.renderCompletion(CompletionShell.BASH)
        assertTrue(COMPLETE_FILES in bash, bash)
        assertTrue($$"mapfile -t COMPREPLY < <(compgen -f -- \"$pathCur\")" in bash, bash)
    }

    @Test
    fun `bash file fallback marks compopt filenames so a directory gets a trailing slash`() {
        // Without -o filenames, bash treats a compgen -f match like any other plain value and appends a
        // trailing space on insertion, even for a directory — breaking Tab-to-descend into it.
        val tree = cli("todo") {
            command("add") {
                argument("file", help = "target file").file()
                action { Ok("") }
            }
        }
        val bash = tree.renderCompletion(CompletionShell.BASH)
        val compgenIndex = bash.indexOf($$"compgen -f -- \"$pathCur\"")
        val compoptIndex = bash.indexOf("compopt -o filenames")
        assertTrue(compgenIndex >= 0, bash)
        assertTrue(compoptIndex >= 0, bash)
        assertTrue(compgenIndex < compoptIndex, bash)
    }
}

/** Completing the value an option expects, rather than the positional that would otherwise be next. */
class OptionValueCandidateTest {


    @Test
    fun `complete candidates counts option value not as positional`() {
        val tree = cli("app") {
            command("deploy") {
                option("--tag", "-t", help = "tag").choice("t1", "t2")
                argument("env").choice("prod", "staging")
                argument("region").choice("us", "eu")
                action { Ok("") }
            }
        }
        // --tag consumes "foo"; the empty word is still the FIRST positional (env), not region.
        assertEquals(
            listOf("prod", "staging"),
            tree.completeCandidates(listOf("deploy", "--tag", "foo", "")).map { it.value },
        )
    }

    @Test
    fun `complete candidates completes global option value`() {
        val tree = cli("todo") {
            globalOption("--profile", "-p", help = "profile").choice("dev", "prod")
            command("run") { action { Ok("") } }
        }
        assertEquals(listOf("dev", "prod"), tree.completeCandidates(listOf("run", "--profile", "")).map { it.value })
    }

    @Test
    fun `option marked file yields the file sentinel for its value`() {
        // Opt.file() (the mirror of Arg.file()) makes an option's VALUE slot complete filesystem paths, so
        // a local `--out <TAB>` and a global `--file <TAB>` both delegate to the shell's native file completion.
        val tree = cli("app") {
            globalOption("--file", "-f", help = "store path").file()
            command("save") {
                val out = option("--out", "-o", help = "destination").file()
                action { Ok(out().orEmpty()) }
            }
        }
        assertEquals(listOf(COMPLETE_FILES), tree.completeCandidates(listOf("save", "--out", "")).map { it.value })
        assertEquals(listOf(COMPLETE_FILES), tree.completeCandidates(listOf("save", "--file", "")).map { it.value })
    }

    @Test
    fun `complete candidates completes short option value`() {
        val tree = cli("todo") {
            command("add") {
                option("--only", "-o", help = "restrict").choice("lines", "words")
                action { Ok("") }
            }
        }
        assertEquals(listOf("lines", "words"), tree.completeCandidates(listOf("add", "-o", "")).map { it.value })
    }
}

/** A dash-led word offers option and flag names, filtered by what is typed and what is visible. */
class FlagNameCandidateTest {


    @Test
    fun `complete candidates offers flag names for a flag shaped word`() {
        val tree = cli("app") {
            version = "1.0"
            globalFlag("--verbose", "-v")
            command("build") {
                option("--target", "-t")
                flag("--force").negatable()
                action { Ok("") }
            }
        }
        val flags = tree.completeCandidates(listOf("build", "--")).map { it.value }
        assertTrue("--target" in flags, flags.toString())
        assertTrue("--force" in flags && "--no-force" in flags, flags.toString())
        assertTrue("--verbose" in flags, flags.toString())
        assertTrue("--help" in flags && "--json" in flags && "--version" in flags, flags.toString())
        assertTrue(flags.all { it.startsWith("-") }, flags.toString())
    }

    @Test
    fun `complete candidates offers meta option names for a single command tool`() {
        // A single-command root (it carries its own action) advertises --completion / --docs in --help's
        // Global options, so flag-name completion must offer those names too, like --help / --json.
        val tree = cli("fmt") { action { Ok("") } }
        val completion = tree.completeCandidates(listOf("--com")).map { it.value }
        val docs = tree.completeCandidates(listOf("--do")).map { it.value }
        assertTrue("--completion" in completion, completion.toString())
        assertTrue("--docs" in docs, docs.toString())
    }

    @Test
    fun `complete candidates offers help all when the command has visible subcommands`() {
        // --help-all is a real, parser-recognized flag only where Help.kt itself advertises it (a command
        // with at least one visible subcommand), so completion must offer it under that same gate.
        val tree = cli("app") {
            command("build", "build things") { action { Ok("") } }
        }
        val dashDash = tree.completeCandidates(listOf("--")).map { it.value }
        assertTrue("--help-all" in dashDash, dashDash.toString())

        val partial = tree.completeCandidates(listOf("--help-a")).map { it.value }
        assertTrue("--help-all" in partial, partial.toString())
    }

    @Test
    fun `complete candidates omits help all for a leaf tool with no subcommands`() {
        // Mirrors Help.kt: a leaf tool with no subcommands never advertises --help-all in --help, so
        // completion must not offer it either.
        val tree = cli("app") { action { Ok("") } }
        val names = tree.completeCandidates(listOf("--")).map { it.value }
        assertTrue("--help-all" !in names, names.toString())
    }

    @Test
    fun `complete candidates prefix filters flag names and includes shorts`() {
        val tree = cli("app") {
            command("build") {
                flag("--force", "-f")
                action { Ok("") }
            }
        }
        assertEquals(listOf("--force"), tree.completeCandidates(listOf("build", "--fo")).map { it.value })
        assertTrue("-f" in tree.completeCandidates(listOf("build", "-")).map { it.value })
    }

    @Test
    fun `complete candidates offers no flag names after end of options`() {
        val tree = cli("app") {
            command("build") {
                flag("--force")
                argument("x")
                action { Ok("") }
            }
        }
        assertTrue(tree.completeCandidates(listOf("build", "--", "-")).none { it.value.startsWith("--force") })
    }

    @Test
    fun `complete candidates hides hidden flag names`() {
        val tree = cli("app") {
            command("build") {
                flag("--secret").hidden()
                flag("--shown")
                action { Ok("") }
            }
        }
        val flags = tree.completeCandidates(listOf("build", "--s")).map { it.value }
        assertTrue("--shown" in flags, flags.toString())
        assertTrue("--secret" !in flags, flags.toString())
    }

    @Test
    fun `complete candidates completes a hidden options own value not the next positionals choices`() {
        // A hidden option is still fully parseable; hidden only means "not advertised by name", so once its
        // name has been typed, its VALUE must complete from the option's own choices, not fall through to
        // the next positional's.
        val tree = cli("app") {
            command("run") {
                option("--secret", help = "restrict").choice("prod", "dev").hidden()
                argument("env").choice("staging", "live")
                action { Ok("") }
            }
        }
        assertEquals(
            listOf("prod", "dev"),
            tree.completeCandidates(listOf("run", "--secret", "")).map { it.value },
        )
        val names = tree.completeCandidates(listOf("run", "--")).map { it.value }
        assertTrue("--secret" !in names, names.toString())
    }
}

/** `.completeWith { }`: what your own provider returns, and the prefix filtering applied around it. */
class ProviderCandidateTest {


    @Test
    fun `complete with default prefix filters provider candidates`() {
        val tree = cli("app") {
            command("run") {
                argument("x").completeWith { candidates(listOf("alpha", "beta")) }
                action { Ok("") }
            }
        }
        assertEquals(listOf("beta"), tree.completeCandidates(listOf("run", "b")).map { it.value })
    }

    @Test
    fun `complete with filter by prefix false skips prefix filtering`() {
        val tree = cli("app") {
            command("run") {
                argument("x").completeWith(filterByPrefix = false) { candidates(listOf("alpha", "beta")) }
                action { Ok("") }
            }
        }
        assertEquals(listOf("alpha", "beta"), tree.completeCandidates(listOf("run", "b")).map { it.value })
    }

    @Test
    fun `complete with dsl produces described candidates filtered on value`() {
        val tree = cli("app") {
            command("rm") {
                argument("id").completeWith {
                    candidate("1", "Buy Beer")
                    candidate("2", "Write Report")
                }
                action { Ok("") }
            }
        }
        assertEquals(
            listOf(Candidate("1", "Buy Beer"), Candidate("2", "Write Report")),
            tree.completeCandidates(listOf("rm", "")),
        )
        // prefix filters on value, not description:
        assertEquals(listOf(Candidate("2", "Write Report")), tree.completeCandidates(listOf("rm", "2")))
    }
}

/** Which slot the cursor is actually in, once the options before it have taken their values. */
class CursorSlotTargetingTest {


    @Test
    fun `complete candidates targets first positional after clustered flag and option value`() {
        val tree = cli("app") {
            command("deploy") {
                flag("--verbose", "-v")
                option("--tag", "-p").choice("t1", "t2")
                argument("env").choice("prod", "staging")
                argument("region").choice("us", "eu")
                action { Ok("") }
            }
        }
        assertEquals(
            listOf("prod", "staging"),
            tree.completeCandidates(listOf("deploy", "-vp", "8080", "")).map { it.value },
        )
    }

    @Test
    fun `complete candidates completes clustered short option value`() {
        // `-vp <TAB>`: the flag `-v` is peeled off and the trailing option `-p` awaits the next word as its
        // value, exactly as parsing binds `-vp 8080`, so complete `-p`'s value here rather than a positional.
        val tree = cli("app") {
            command("deploy") {
                flag("--verbose", "-v")
                option("--tag", "-p").choice("t1", "t2")
                argument("env").choice("prod", "staging")
                action { Ok("") }
            }
        }
        assertEquals(listOf("t1", "t2"), tree.completeCandidates(listOf("deploy", "-vp", "")).map { it.value })
    }

    @Test
    fun `complete candidates targets first positional after inline option value`() {
        val tree = cli("app") {
            command("deploy") {
                option("--tag", "-p").choice("t1", "t2")
                argument("env").choice("prod", "staging")
                argument("region").choice("us", "eu")
                action { Ok("") }
            }
        }
        assertEquals(
            listOf("prod", "staging"),
            tree.completeCandidates(listOf("deploy", "--tag=t1", "")).map { it.value },
        )
    }

    @Test
    fun `complete candidates targets first positional after dash led numeric option value`() {
        val tree = cli("app") {
            command("deploy") {
                option("--count", "-c").int()
                argument("env").choice("prod", "staging")
                argument("region").choice("us", "eu")
                action { Ok("") }
            }
        }
        assertEquals(
            listOf("prod", "staging"),
            tree.completeCandidates(listOf("deploy", "--count", "-5", "")).map { it.value },
        )
    }

    @Test
    fun `complete candidates resolves the command past a leading global flag`() {
        // A leading global option/flag must not stop the subcommand walk: `myapp -v issue show <TAB>`
        // should offer the same candidates as `myapp issue show <TAB>` (no leading global).
        val tree = globalPrecedingSubcommandTree()
        val withoutGlobal = tree.completeCandidates(listOf("issue", "show", "")).map { it.value }
        val withLeadingGlobal = tree.completeCandidates(listOf("-v", "issue", "show", "")).map { it.value }
        assertEquals(listOf("open", "closed"), withoutGlobal)
        assertEquals(withoutGlobal, withLeadingGlobal)
    }

    @Test
    fun `complete candidates resolves the command past an interspersed global flag`() {
        val tree = globalPrecedingSubcommandTree()
        val withoutGlobal = tree.completeCandidates(listOf("issue", "show", "")).map { it.value }
        val withInterspersedGlobal = tree.completeCandidates(listOf("issue", "-v", "show", "")).map { it.value }
        assertEquals(withoutGlobal, withInterspersedGlobal)
    }

    @Test
    fun `complete candidates includes subcommand aliases`() {
        val tree = cli("app") {
            command("list") {
                aliases = listOf("ls")
                action { Ok("") }
            }
        }
        val cands = tree.completeCandidates(listOf("")).map { it.value }
        assertTrue("list" in cands, cands.toString())
        assertTrue("ls" in cands, cands.toString())
    }

    @Test
    fun `complete candidates excludes hidden subcommand aliases`() {
        val tree = cli("app") {
            command("visible") { action { Ok("") } }
            command("secret") {
                hidden = true
                aliases = listOf("shh")
                action { Ok("") }
            }
        }
        val cands = tree.completeCandidates(listOf("")).map { it.value }
        assertTrue("visible" in cands, cands.toString())
        assertTrue("shh" !in cands, cands.toString())
    }
}

/** Completing the value half of `--opt=` and `-o`, where the name and value share one word. */
class AttachedValueCompletionTest {


    @Test
    fun `complete candidates completes attached long option value`() {
        // zsh's $words array does not split a word at `=`, so `--tag=r` arrives as ONE word; this must
        // complete the option's value filtered by "r", exactly like the space form `--tag r` does.
        val tree = cli("todo") {
            command("add") {
                option("--tag", "-t", help = "tag").choice("red", "green")
                action { Ok("") }
            }
        }
        assertEquals(listOf("red"), tree.completeCandidates(listOf("add", "--tag=r")).map { it.value })
        assertEquals(listOf("red"), tree.completeCandidates(listOf("add", "--tag", "r")).map { it.value })
    }

    @Test
    fun `complete candidates completes attached long option value empty`() {
        val tree = cli("todo") {
            command("add") {
                option("--tag", "-t", help = "tag").choice("red", "green")
                action { Ok("") }
            }
        }
        assertEquals(listOf("red", "green"), tree.completeCandidates(listOf("add", "--tag=")).map { it.value })
    }

    @Test
    fun `complete candidates completes glued short option value`() {
        // `-tr`: short option `-t` with a glued (attached) value "r", no space, no `=`.
        val tree = cli("todo") {
            command("add") {
                option("--tag", "-t", help = "tag").choice("red", "green")
                action { Ok("") }
            }
        }
        assertEquals(listOf("red"), tree.completeCandidates(listOf("add", "-tr")).map { it.value })
    }
}

/** Provider code runs on every keypress, so a throw must degrade to no candidates, never reach the terminal. */
class ProviderFailureTest {


    @Test
    fun `complete candidates returns empty when provider throws but sibling provider still works`() {
        // A `.completeWith { }` provider is user code invoked synchronously on every Tab press by the
        // hidden __complete path. If it throws, the exception must not propagate: the generated shell
        // scripts call __complete without redirecting stderr, so a raw stack trace would dump straight
        // into the user's terminal on a keypress. It must degrade to "no candidates" instead.
        val tree = cli("app") {
            command("run") {
                argument("bad").completeWith { throw RuntimeException("boom") }
                action { Ok("") }
            }
            command("good") {
                argument("ok").completeWith { candidates(listOf("alpha", "beta")) }
                action { Ok("") }
            }
        }
        assertEquals(emptyList(), tree.completeCandidates(listOf("run", "")).map { it.value })
        assertEquals(listOf("alpha", "beta"), tree.completeCandidates(listOf("good", "")).map { it.value })
    }

    @Test
    fun `complete candidates returns empty when provider throws an error`() {
        // Not just Exception: an Error (e.g. a broken invariant in third-party provider code) must be
        // contained the same way, since this is a keypress-time safety boundary, not routine control flow.
        val tree = cli("app") {
            command("run") {
                argument("bad").completeWith { throw AssertionError("broken invariant") }
                action { Ok("") }
            }
        }
        assertEquals(emptyList(), tree.completeCandidates(listOf("run", "")).map { it.value })
    }

    @Test
    fun `complete subcommand through hidden complete builtin ignores a leading global flag`() {
        // Reproduces the real `myapp __complete -- -v issue show ""` shell-completion path (see
        // `complete subcommand returns provider candidates` for the no-global baseline of this same builtin).
        val tree = globalPrecedingSubcommandTree()
        val t = RecordingTerminal()
        val code = tree.run(arrayOf("__complete", "--", "-v", "issue", "show", ""), t)
        assertEquals(0, code)
        assertEquals(
            listOf("open", "closed"),
            t.out
                .toString()
                .trim()
                .lines(),
        )
    }
}

/** Which candidates carry their help text as a description for the shell to display. */
class CandidateDescriptionTest {


    @Test
    fun `subcommand and option name completion carry their help as description`() {
        val tree = cli("app") {
            command("rm", "delete a task") { action { Ok("") } }
            command("list") { action { Ok("") } }        // no help -> bare value
        }
        val subs = tree.completeCandidates(listOf(""))
        assertTrue(Candidate("rm", "delete a task") in subs, subs.toString())
        assertTrue(Candidate("list", null) in subs, subs.toString())
    }

    @Test
    fun `subcommand aliases carry the same commands help as description`() {
        val tree = cli("app") {
            command("list", "show every task") {
                aliases = listOf("ls")
                action { Ok("") }
            }
        }
        val subs = tree.completeCandidates(listOf(""))
        assertTrue(Candidate("list", "show every task") in subs, subs.toString())
        assertTrue(Candidate("ls", "show every task") in subs, subs.toString())
    }

    @Test
    fun `option and flag name completion carry their help as description`() {
        val tree = cli("app") {
            command("build") {
                option("--target", "-t", help = "build target")
                flag("--force", help = "skip confirmation")
                flag("--quiet") // no help -> bare value
                action { Ok("") }
            }
        }
        val names = tree.completeCandidates(listOf("build", "--"))
        assertTrue(Candidate("--target", "build target") in names, names.toString())
        assertTrue(Candidate("--force", "skip confirmation") in names, names.toString())
        assertTrue(Candidate("--quiet", null) in names, names.toString())
    }

    @Test
    fun `short and negated option names carry their help as description`() {
        val tree = cli("app") {
            command("build") {
                option("--target", "-t", help = "build target")
                flag("--force", help = "skip confirmation").negatable()
                action { Ok("") }
            }
        }
        val names = tree.completeCandidates(listOf("build", "-"))
        assertTrue(Candidate("-t", "build target") in names, names.toString())
        assertTrue(Candidate("--no-force", "skip confirmation") in names, names.toString())
    }

    @Test
    fun `builtin option names carry their own help text`() {
        val tree = cli("app") {
            version = "1.0"
            command("build") { action { Ok("") } }
        }
        val shortNames = tree.completeCandidates(listOf("build", "-"))
        assertTrue(Candidate("-h", BuiltinOptionHelp.HELP) in shortNames, shortNames.toString())

        val names = tree.completeCandidates(listOf("build", "--"))
        assertTrue(Candidate("--help", BuiltinOptionHelp.HELP) in names, names.toString())
        assertTrue(Candidate("--json", BuiltinOptionHelp.JSON) in names, names.toString())
        assertTrue(Candidate("--version", BuiltinOptionHelp.VERSION) in names, names.toString())
        assertTrue(Candidate("--color", BuiltinOptionHelp.COLOR) in names, names.toString())
    }

    @Test
    fun `meta option names carry their own help text on a single command tool`() {
        val tree = cli("fmt") { action { Ok("") } }
        val names = tree.completeCandidates(listOf("--"))
        assertTrue(Candidate("--completion", BuiltinOptionHelp.COMPLETION) in names, names.toString())
        assertTrue(Candidate("--docs", BuiltinOptionHelp.DOCS) in names, names.toString())
    }

    @Test
    fun `choice value completion carries no description`() {
        val tree = cli("app") {
            command("deploy") {
                argument("env").choice("prod", "staging")
                action { Ok("") }
            }
        }
        assertEquals(
            listOf(Candidate("prod"), Candidate("staging")),
            tree.completeCandidates(listOf("deploy", "")),
        )
    }
}

/** `--color` is a meta-option rather than a declared spec, so its values complete from klap's own list. */
class BuiltinColorValueCompletionTest {


    @Test
    fun `complete candidates completes the builtin color option value space form`() {
        // --color is a built-in meta-option, not a user-declared OptionSpec, so its value must complete
        // from COLOR_MODE_NAMES directly instead of falling through to the next positional/subcommand once
        // its name has been typed. A tree with subcommands makes the difference visible: falling through
        // would return the subcommand list ("build"/"test") instead of the color choices.
        val tree = cli("app") {
            command("build") { action { Ok("") } }
            command("test") { action { Ok("") } }
        }
        assertEquals(
            listOf("auto", "always", "never"),
            tree.completeCandidates(listOf("--color", "")).map { it.value },
        )
    }

    @Test
    fun `complete candidates prefix filters the builtin color option value`() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
            command("test") { action { Ok("") } }
        }
        assertEquals(
            listOf("auto", "always"),
            tree.completeCandidates(listOf("--color", "a")).map { it.value },
        )
    }

    @Test
    fun `complete candidates completes the builtin color option value attached form`() {
        // zsh's $words array (and fish's `commandline -ct`) never split a word at `=`, so `--color=` arrives
        // as ONE word; this must complete the same way the space form above does.
        val tree = cli("app") {
            command("build") { action { Ok("") } }
            command("test") { action { Ok("") } }
        }
        assertEquals(
            listOf("auto", "always", "never"),
            tree.completeCandidates(listOf("--color=")).map { it.value },
        )
    }
}

/** `completeFiles()`: handing the word off to the shell's own file completion, and what that discards. */
class CompleteFilesDirectiveTest {


    @Test
    fun `a provider can hand off to native file completion`() {
        val tree = cli("dd") {
            argument("operand").multiple().completeWith {
                if (current.startsWith("if=")) completeFiles("if=") else candidates(listOf("if=", "count="))
            }
            action<String>(human = { it }) { Ok("ran") }
        }
        assertEquals(listOf(" klap:files:if="), tree.completionsFor("if=/dev/ze"))
        assertEquals(listOf("if=", "count="), tree.completionsFor(""))
    }

    @Test
    fun `complete files carries the non path prefix on the directive line and defaults to none`() {
        // Everything after the marker IS the prefix, so a shell strips a fixed marker and takes the rest
        // verbatim; the default has to encode as the bare marker, which is what `.file()` also emits.
        val tree = cli("t") {
            argument("a").completeWith { completeFiles("if=") }
            argument("b").completeWith { completeFiles() }
            action<String>(human = { it }) { Ok("ran") }
        }
        assertEquals(listOf(" klap:files:if="), tree.completionsFor("if=/dev/ze"))
        assertEquals(listOf(" klap:files:"), tree.completionsFor("x", ""))
    }

    @Test
    fun `complete files stays exclusive of a sibling subcommand name at the first positional`() {
        // A hybrid parent (its own first positional beside subcommand children, git-style) filters
        // subcommand names by the same `current` prefix a completeFiles() provider sees, so a name like
        // "if" sitting next to an `if=<TAB>` provider could join the sentinel into a two-line answer no
        // shell script recognizes as the file-completion signal (each checks for exactly one line).
        val tree = cli("app") {
            command("ifconfig") { action { Ok("") } }
            argument("operand").multiple().completeWith { completeFiles() }
            action<String>(human = { it }) { Ok("ran") }
        }
        assertEquals(listOf(" klap:files:"), tree.completionsFor("if"))
    }

    @Test
    fun `complete files discards candidates collected before it`() {
        // The generated scripts map a LONE directive line to the shell's own file completion and treat any
        // other line as a literal candidate, so a directive sitting beside real candidates would be inserted
        // as the text " klap:files:". Making it exclusive is the only reading that works in every shell.
        val tree = cli("t") {
            argument("path").completeWith {
                candidate("ignored")
                completeFiles()
            }
            action<String>(human = { it }) { Ok("ran") }
        }
        assertEquals(listOf(" klap:files:"), tree.completionsFor(""))
    }

    @Test
    fun `complete files drops candidates added after it`() {
        // The mirror of the case above, and the one a provider is likelier to write: every shell gate tests
        // for a directive line ALONE, so an added candidate would make all four fall through and insert the
        // directive's own text into the command line.
        val tree = cli("t") {
            argument("path").completeWith {
                completeFiles("if=")
                candidate("x")
                candidates(listOf("y", "z"))
            }
            action<String>(human = { it }) { Ok("ran") }
        }
        assertEquals(listOf(" klap:files:if="), tree.completionsFor(""))
    }
}

private fun modifierTree(): Cli = cli("todo") {
    command("list") {
        option("--status", "-s", help = "filter by status")
        action { Ok("") }
    }
}

private fun Cli.candidateValuesFor(vararg words: String): List<String> =
    completeCandidates(words.toList()).map { it.value }

class CompletionModifierRoutingTest {

    @Test
    fun `position independent modifiers do not break subcommand routing`() {
        val tree = modifierTree()
        // Baseline: the walk reaches `list`, so its own --status is offered.
        val baseline = listOf("--status")
        assertEquals(baseline, tree.candidateValuesFor("list", "--st"))
        // parse() strips these before its walk; completion must too, or the walk breaks on the modifier at
        // token 0 and completes against the ROOT (which has no --st* option, hence an empty list).
        assertEquals(baseline, tree.candidateValuesFor("--json", "list", "--st"))
        assertEquals(baseline, tree.candidateValuesFor("--color", "never", "list", "--st"))
        assertEquals(baseline, tree.candidateValuesFor("--color=never", "list", "--st"))
        assertEquals(baseline, tree.candidateValuesFor("--json", "--color", "never", "list", "--st"))
        // ...and after the subcommand, too.
        assertEquals(baseline, tree.candidateValuesFor("list", "--json", "--st"))
    }

    @Test
    fun `color value still completes despite the strip`() {
        val tree = modifierTree()
        // The strip deletes the token the SPACE form matches on, so that branch must be answered from the
        // raw head before it runs; the attached form reads `current`, which is never stripped.
        assertEquals(listOf("auto", "always", "never"), tree.candidateValuesFor("--color", ""))
        assertEquals(listOf("always"), tree.candidateValuesFor("--color=al"))
        assertEquals(listOf("auto", "always", "never"), tree.candidateValuesFor("list", "--color", ""))
    }
}

/** Unlike modifierTree, this sets `abbreviation` explicitly, since the abbreviation these tests pin only fires above `Abbreviation.None`. */
private fun colorAbbreviationDispatcherTree(abbreviationMode: Abbreviation): Cli = cli("app") {
    abbreviation = abbreviationMode
    command("add") { action { Ok("") } }
}

class ColorAbbreviationCompletionTest {

    @Test
    fun `an abbreviated color option offers color modes under abbreviation all`() {
        // An unambiguous abbreviation binds --color at parse time, so completion has to offer its values
        // rather than falling through to the first-positional branch and naming subcommands.
        val tree = colorAbbreviationDispatcherTree(Abbreviation.All)
        assertEquals(listOf("auto", "always", "never"), tree.candidateValuesFor("--col", ""))
    }

    @Test
    fun `an abbreviated attached color option offers color modes under abbreviation all`() {
        // The attached form resolves the same way. matchingValueOption cannot cover it: --color is a
        // built-in, so there is no declared OptionSpec for it to find.
        val tree = colorAbbreviationDispatcherTree(Abbreviation.All)
        assertEquals(listOf("auto", "always", "never"), tree.candidateValuesFor("--col="))
        assertEquals(listOf("auto", "always"), tree.candidateValuesFor("--col=a"))
    }

    @Test
    fun `an abbreviated color option does not offer color modes under abbreviation none`() {
        // Abbreviation.None turns off scan.matched's prefix half, so "--col" resolves to nothing here,
        // exactly as the parser itself refuses the abbreviation.
        val tree = colorAbbreviationDispatcherTree(Abbreviation.None)
        assertEquals(listOf("add", "completion", "docs"), tree.candidateValuesFor("--col", ""))
    }

    @Test
    fun `an abbreviation ambiguous with another declared option offers no color modes`() {
        // Guards against resolving the abbreviation without checking ambiguity first: "--col" reaches
        // both the declared --collate and the built-in --color, so it must offer nothing rather than pick.
        val tree = cli("app") {
            abbreviation = Abbreviation.Options
            option("--collate")
            action { Ok("") }
        }
        assertEquals(emptyList(), tree.candidateValuesFor("--col", ""))
    }

    @Test
    fun `the exact color spelling still completes under every abbreviation mode`() {
        val tree = colorAbbreviationDispatcherTree(Abbreviation.All)
        assertEquals(listOf("auto", "always", "never"), tree.candidateValuesFor("--color", ""))
        assertEquals(listOf("auto", "always", "never"), tree.candidateValuesFor("--color="))
    }
}

private fun siftTree(): Command = cli("t") {
    command("go") {
        flag("--verbose", "-v", help = "chatty")
        option("--out", "-o", help = "output")
        argument("a", "first")
        argument("b", "second")
        action { Ok("") }
    }
}.subcommand("go")!!

class SiftAccumulationTest {

    @Test
    fun `walk continues past an offending token and keeps the first error`() {
        val sifted = siftTree().sift(
            listOf("--bogus", "one", "--out", "x", "--alsobogus", "two", "-v"),
        )

        // Everything around the two unknown options still lands. An unknown option is SKIPPED, not demoted
        // to a positional: demoting would shift every later positional into the wrong slot.
        assertEquals(listOf("one", "two"), sifted.positionals)
        assertEquals(listOf("x"), sifted.options.entries.single { it.key.name == "--out" }.value)
        assertEquals(1, sifted.flags.entries.single { it.key.name == "--verbose" }.value)
        // First error wins: the walk keeps going after recording one, but never replaces it.
        assertEquals("--bogus", (sifted.error as CliError.UnknownOption).token)
    }

    @Test
    fun `a dangling option value is recorded and the walk carries on`() {
        // The commonest completion shape: an option typed with its value not yet supplied. The option must
        // be LAST for that to happen — a following token, dash-led or not, is its value.
        val sifted = siftTree().sift(listOf("-v", "--out"))

        assertEquals(CliError.MissingOptionValue("--out"), sifted.error)
        assertEquals(1, sifted.flags.entries.single { it.key.name == "--verbose" }.value)
        assertTrue(sifted.options.isEmpty())
        assertTrue(sifted.positionals.isEmpty())
    }

    @Test
    fun `a bad short cluster is recorded and the rest of the segment still walks`() {
        val sifted = siftTree().sift(listOf("-vz", "one"))

        assertEquals(CliError.UnknownOption("-z"), sifted.error)
        assertEquals(listOf("one"), sifted.positionals)
        // The `v` before the bad char was already counted; a partial cluster is retained, not discarded.
        assertEquals(1, sifted.flags.entries.single { it.key.name == "--verbose" }.value)
    }

    @Test
    fun `a cluster option with no value is recorded and the walk carries on`() {
        // The second of the two cluster break sites: the cluster ends on `-o`, which needs a value that
        // neither the rest of the token nor a following one supplies.
        val sifted = siftTree().sift(listOf("-vo"))

        assertEquals(CliError.MissingOptionValue("--out"), sifted.error)
        assertEquals(1, sifted.flags.entries.single { it.key.name == "--verbose" }.value)
        assertTrue(sifted.options.isEmpty())
    }

    @Test
    fun `parse raises the first sift error before binding`() {
        val tree = cli("t") {
            command("go") {
                flag("--verbose", "-v", help = "chatty")
                option("--out", "-o", help = "output")
                argument("a", "first")
                argument("b", "second")
                action { Ok("") }
            }
        }
        // The tree's two required arguments are also unsatisfied, so this proves sift's recorded error is
        // raised BEFORE binding — not merely that some error comes back.
        val outcome = tree.parse(listOf("go", "--bogus", "--alsobogus"))
        val error = (outcome as Result.Error).error as CliError.UnknownOption
        assertEquals("--bogus", error.token)
    }
}

private fun slotTree(): Cli = cli("t") {
    globalOption("--region", "-r", help = "region")
    command("go") {
        flag("--verbose", "-v", help = "chatty")
        flag("--force", "-f", help = "force")
        option("--port", "-p", help = "port")
        argument("first", "first").completeWith { candidate("FIRST") }
        argument("second", "second").completeWith { candidate("SECOND") }
        action { Ok("") }
    }
}

class CompletionSlotCountingTest {

    @Test
    fun `options and their values never consume a positional slot`() {
        val tree = slotTree()
        assertEquals(listOf("FIRST"), tree.candidateValuesFor("go", ""))
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "a", ""))
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "-v", "a", ""))
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "-p", "8080", "a", ""))
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "-p8080", "a", ""))
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "--port=8080", "a", ""))
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "--port", "8080", "a", ""))
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "-vp", "8080", "a", ""))
        // The planner's one coupling to the accumulator: `f` is local, `r` is a GLOBAL option, so the
        // cluster takes the next token as r's value. Without the accumulator sift cannot see `r`, `us`
        // is miscounted as a positional, and the cursor lands on the wrong slot.
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "-fr", "us", "a", ""))
    }

    @Test
    fun `positional lookalikes and unknown options count correctly`() {
        val tree = slotTree()
        // A dash-led number is an option token like any other, so it is unknown here
        // and consumes no slot; the cursor is still on the FIRST one. Written after `--` it fills a slot,
        // which the line below covers.
        assertEquals(listOf("FIRST"), tree.candidateValuesFor("go", "-1m", ""))
        // Everything after the end-of-options marker is positional.
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "--", "a", ""))
        // An unknown option consumes no slot (it is skipped, not demoted to a positional).
        assertEquals(listOf("SECOND"), tree.candidateValuesFor("go", "--bogus", "a", ""))
        // A space-form --color is stripped before the walk, exactly as parse() strips it, so its VALUE
        // never fills a positional slot. Before that strip it did, and the cursor landed one slot late.
        assertEquals(listOf("FIRST"), tree.candidateValuesFor("go", "--color", "never", ""))
    }
}

private fun valueTree(): Cli = cli("todo") {
    val store = globalOption("--file", "-f", help = "task store").default("tasks.json")
    val verbose = globalFlag("--verbose", "-v", help = "chatty")

    command("show") {
        val id = argument("id", "task id").int()
        val loud = flag("--loud", "-l", help = "shout")
        val fmt = option("--fmt", "-m", help = "format").default("plain")
        // Reads every kind of input there is: a global option, a global flag, a local flag, a local option,
        // and an earlier positional of this same command.
        argument("what", "what to show").completeWith {
            candidate("${store()}|${id()}|${loud()}|${fmt()}|${verbose()}")
        }
        action { Ok("") }
    }

    command("global-only") {
        argument("x", "x").completeWith { candidate(store()) }
        action { Ok("") }
    }
}

class CompletionValueScopeTest {

    @Test
    fun `provider reads globals in every token shape`() {
        val tree = valueTree()
        // Absent, so the declared default applies — exactly as it would at runtime.
        assertEquals(listOf("tasks.json|3|false|plain|false"), tree.candidateValuesFor("show", "3", ""))

        val typed = listOf("other.json|3|false|plain|false")
        assertEquals(typed, tree.candidateValuesFor("--file", "other.json", "show", "3", ""))
        assertEquals(typed, tree.candidateValuesFor("--file=other.json", "show", "3", ""))
        assertEquals(typed, tree.candidateValuesFor("-f", "other.json", "show", "3", ""))
        assertEquals(typed, tree.candidateValuesFor("-fother.json", "show", "3", ""))
        // Position-independent: after the subcommand, too.
        assertEquals(typed, tree.candidateValuesFor("show", "--file", "other.json", "3", ""))
    }

    @Test
    fun `provider reads the commands own typed inputs`() {
        val tree = valueTree()
        assertEquals(
            listOf("tasks.json|3|true|json|false"),
            tree.candidateValuesFor("show", "-l", "--fmt", "json", "3", ""),
        )
    }

    @Test
    fun `a global hiding in a mixed short cluster resolves`() {
        val tree = valueTree()
        // `-lv`: `l` is the command's own flag, `v` a global. siftGlobals leaves the mixed cluster whole for
        // the command sift, which tops the accumulator up — the shape the example's word-scanner got wrong.
        assertEquals(
            listOf("tasks.json|3|true|plain|true"),
            tree.candidateValuesFor("show", "-lv", "3", ""),
        )
    }

    @Test
    fun `reading an unresolved input yields no candidates`() {
        val tree = valueTree()
        // `abc` fails the .int() conversion, so `id` is left unbound; reading it aborts the provider and
        // Tab offers nothing rather than crashing or printing a stack trace into the terminal.
        assertEquals(emptyList(), tree.candidateValuesFor("show", "abc", ""))
    }

    @Test
    fun `one bad token does not blank the inputs around it`() {
        val tree = valueTree()
        // An unknown option is recorded by sift and skipped; the globals still bind.
        assertEquals(
            listOf("x.json"),
            tree.candidateValuesFor("--file", "x.json", "global-only", "--bogus", ""),
        )
    }

    @Test
    fun `lenient cardinality binds what is typed so far`() {
        val tree = cli("todo") {
            command("show") {
                val tags = option("--tag", "-t", help = "tags").multiple(min = 2)
                val loudness = flag("--verbose", "-v", help = "chatty").count()
                argument("what", "what").completeWith { candidate("${tags()}|${loudness()}") }
                action { Ok("") }
            }
        }
        // multiple(min = 2) with one occurrence typed binds the one, rather than failing the whole bind...
        assertEquals(listOf("[work]|0"), tree.candidateValuesFor("show", "--tag", "work", ""))
        // ...and a count flag reports the occurrences seen so far.
        assertEquals(listOf("[work]|2"), tree.candidateValuesFor("show", "--tag", "work", "-vv", ""))
    }

    @Test
    fun `surplus positionals do not blank the inputs that did bind`() {
        val tree = cli("todo") {
            command("show") {
                val what = argument("what", "what")
                option("--fmt", "-m", help = "format").completeWith { candidate(what()) }
                action { Ok("") }
            }
        }
        // Two positionals for one declared argument: strict binding would reject the line, lenient ignores
        // the extra and `what` still reads back.
        assertEquals(listOf("first"), tree.candidateValuesFor("show", "first", "second", "--fmt", ""))
    }

    @Test
    fun `a required option not yet typed leaves the provider with nothing`() {
        val tree = cli("todo") {
            command("show") {
                val region = option("--region", "-r", help = "region").required()
                argument("what", "what").completeWith { candidate(region()) }
                action { Ok("") }
            }
        }
        // The commonest real shape: a provider on one slot reads a required option the user has not reached.
        assertEquals(emptyList(), tree.candidateValuesFor("show", ""))
        assertEquals(listOf("eu"), tree.candidateValuesFor("show", "--region", "eu", ""))
    }

    @Test
    fun `a scalar option that fails to convert leaves only itself unbound`() {
        val tree = cli("todo") {
            command("show") {
                val port = option("--port", "-p", help = "port").int()
                val fmt = option("--fmt", "-m", help = "format").default("plain")
                argument("what", "what").completeWith { candidate("${fmt()}|${port()}") }
                action { Ok("") }
            }
        }
        // `zzz` fails .int(), so `port` is unbound and the provider aborts — but `fmt` beside it is
        // untouched, which is what the lenient bind exists to guarantee.
        assertEquals(emptyList(), tree.candidateValuesFor("show", "--port", "zzz", "--fmt", "json", ""))
        assertEquals(listOf("json|8080"), tree.candidateValuesFor("show", "--port", "8080", "--fmt", "json", ""))
    }

    @Test
    fun `an absent variadic reads back empty whichever kind it is`() {
        val tree = cli("todo") {
            command("opt") {
                val tags = option("--tag", "-t", help = "tags").multiple(min = 2)
                argument("what", "what").completeWith { candidate("opt=${tags()}") }
                action { Ok("") }
            }
            command("arg") {
                val files = argument("file", "files").multiple(min = 1)
                option("--fmt", "-m", help = "format").completeWith { candidate("arg=${files()}") }
                action { Ok("") }
            }
        }
        // A variadic option and a variadic argument must agree: nothing typed reads back as an empty list,
        // not as an unbound input that aborts the provider.
        assertEquals(listOf("opt=[]"), tree.candidateValuesFor("opt", ""))
        assertEquals(listOf("arg=[]"), tree.candidateValuesFor("arg", "--fmt", ""))
    }

    @Test
    fun `a provider on an option value still reads everything else`() {
        val tree = cli("todo") {
            val store = globalOption("--file", "-f", help = "task store").default("tasks.json")
            command("show") {
                option("--fmt", "-m", help = "format").completeWith { candidate(store()) }
                action { Ok("") }
            }
        }
        // The cursor sits on --fmt's value, so --fmt is dangling with no value. sift records that and
        // carries on, so the global is still readable.
        assertEquals(listOf("tasks.json"), tree.candidateValuesFor("show", "--fmt", ""))
    }
}

/** A bare optional-value option takes no next word, so completion must not offer its values there. */
class OptionalValueCompletionTest {

    private fun tree(): Cli = cli("ls") {
        // --color collides with klap's own built-in of the same name; free it the same way
        // BuiltinsOptOutTest does, so the option under test can use the name unchanged.
        builtins { color = false }
        val color = option("--color").placeholder("WHEN")
            .choice("always", "auto", "never")
            .optionalValue("always")
        val files = argument("file").multiple(min = 0).completeWith { candidate("FILE") }
        action { Ok("${color()} ${files()}") }
    }

    @Test
    fun `the word after a bare occurrence completes as an operand`() {
        // A bare optional-value option consumes no following token, so the parser leaves it an
        // operand; completion must agree or it advertises a binding that cannot happen.
        assertEquals(listOf("FILE"), tree().candidateValuesFor("--color", ""))
    }

    @Test
    fun `the attached form still completes its value`() {
        assertEquals(listOf("always", "auto", "never"), tree().candidateValuesFor("--color="))
    }

    @Test
    fun `an ordinary options next word still completes its value`() {
        val ordinary = cli("app") {
            option("--fmt").choice("json", "text")
            action { Ok("") }
        }
        assertEquals(listOf("json", "text"), ordinary.candidateValuesFor("--fmt", ""))
    }
}

class CompletionConditionalOperandTest {

    private fun chmodLike(): Cli = cli("chmod") {
        val reference = option("--reference").file()
        argument("mode").choice("644", "755").absentWhen(reference)
        argument("file").file().multiple(min = 1)
        action { Ok("") }
    }

    @Test
    fun `the slot is offered when its trigger is absent`() {
        assertEquals(listOf("644", "755"), chmodLike().candidateValuesFor(""))
    }

    @Test
    fun `a slot the trigger removed is not offered`() {
        // The same line parses as `mode=null files=[a]` and `--help` renders `[<mode>]`, so offering the
        // mode values here would make completion the only one of the three that still sees the slot.
        assertEquals(listOf(COMPLETE_FILES), chmodLike().candidateValuesFor("--reference=r", ""))
    }
}
