package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.test.RecordingTerminal
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each generated completion script, run by its own shell against a stub `tool` that records the `__complete`
 * call it receives and answers with canned candidates. A shell that is not installed is left out, with a note.
 */
class RealShellCompletionTest {

    private fun cliNamed(name: String) = cli(name) {
        command("get") {
            argument("filter")
            argument("file").optional()
            action { Ok("") }
        }
    }

    private val tool = cliNamed("tool")

    private val dir: File = Files.createTempDirectory("klap-shell").toFile()
    private val bin = File(dir, "bin").apply { mkdirs() }
    private val log = File(dir, "calls")
    private val reply = File(dir, "reply")
    private val errors = File(dir, "stderr")
    private val compopts = File(dir, "set-options")
    private val status = File(dir, "status")

    init {
        // Each call is one line of its arguments, separated by the unit separator so an empty one shows.
        val stub = File(bin, "tool").apply {
            writeText(
                $$"""
                #!/bin/sh
                printf '%s\037' "$@" >> "$TOOL_LOG"
                echo >> "$TOOL_LOG"
                [ -n "$TOOL_STDERR" ] && echo "$TOOL_STDERR" >&2
                cat "$TOOL_REPLY"

                """.trimIndent(),
            )
            setExecutable(true)
        }
        // A launcher the way a JVM CLI installs one on Windows, which PowerShell calls differently.
        stub.copyTo(File(bin, "tool.bat")).setExecutable(true)
        File(dir, "conf.conf").writeText("")
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /**
     * What one completion did: the arguments `tool` was called with, the candidates the shell offered, and each
     * `compopt` call bash made, and the status zsh's completion function returned (null for the other shells).
     */
    private data class Completed(
        val call: List<String>,
        val candidates: List<String>,
        val compopts: List<String>,
        val status: Int?,
    )

    /**
     * Completes [line] with the cursor at [cursor] (its end by default), [answer] being what `tool` prints and
     * [stderr] what it writes to standard error.
     */
    private fun complete(
        shell: CompletionShell,
        line: String,
        cursor: Int = line.length,
        answer: String = "",
        stderr: String = "",
        argumentPassing: String? = null,
        program: Cli = tool,
        environment: Map<String, String> = emptyMap(),
    ): Completed {
        val executable = shell.executable
        log.writeText("")
        compopts.writeText("")
        status.writeText("")
        reply.writeText(answer)
        // fish autoloads only `<command>.fish` from its complete path, and pwsh dot-sources only a `.ps1`.
        val script = File(dir, if (shell == CompletionShell.POWERSHELL) "tool.ps1" else "tool.$executable")
        script.writeText(program.renderCompletion(shell))
        val (command, extraEnv) = driver(shell, script, line, cursor, argumentPassing)
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectError(errors)
            .apply {
                environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}"
                environment()["TOOL_LOG"] = log.path
                environment()["TOOL_REPLY"] = reply.path
                environment()["TOOL_STDERR"] = stderr
                environment()["COMPOPT_LOG"] = compopts.path
                environment()["STATUS_LOG"] = status.path
                environment().putAll(extraEnv)
                environment().putAll(environment)
            }
            .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "$executable did not finish")
        // A script error prints here while the shell carries on, so an empty stderr is part of every check.
        assertEquals("", errors.readText(), "$executable wrote to standard error")
        val calls = log.readLines().filter { it.isNotEmpty() }
        assertEquals(1, calls.size, "$executable called tool ${calls.size} times: $calls")
        val call = calls.single().removeSuffix("\u001f").split('\u001f')
        return Completed(
            call,
            out.lines().filter { it.isNotEmpty() },
            compopts.readLines(),
            status.readText().trim().toIntOrNull(),
        )
    }

    private fun driver(
        shell: CompletionShell,
        script: File,
        line: String,
        cursor: Int,
        argumentPassing: String?,
    ): Pair<List<String>, Map<String, String>> {
        val env = mapOf("LINE" to line, "POINT" to cursor.toString())
        return when (shell) {
            // The script reads only the program and the word under the cursor from COMP_WORDS, raw and quoted as
            // bash leaves it, whole past the cursor and split after a COMP_WORDBREAKS `=` or `:`, and rebuilds
            // everything else from COMP_LINE and COMP_POINT. compopt works only inside a completion bash runs, so
            // its calls are recorded instead.
            CompletionShell.BASH -> listOf(
                "bash", "--norc", "--noprofile", "-c",
                $$"""
                compopt() { printf '%s\n' "$*" >> "$COMPOPT_LOG"; }
                source "$1"
                COMP_LINE="$LINE"; COMP_POINT=$POINT
                COMP_WORDS=("${LINE%% *}" "$CUR"); COMP_CWORD=1
                _tool
                printf '%s\n' "${COMPREPLY[@]}"
                """.trimIndent(),
                "bash", script.path,
            ) to env + ("CUR" to bashCurrentWord(line, cursor))
            // Without a terminal zsh has no completion system, so its three builtins the script calls are
            // stood in for: compadd prints each value it inserts, a tab and its display text where that differs,
            // and any other flag first, returning 0 only when it was given a value, as compadd does when it adds a
            // match; compset prints its call.
            CompletionShell.ZSH -> {
                val tokens = rawWordRanges(line)
                val touched = tokens.indexOfFirst { cursor in it.first..it.last + 1 }
                val words = tokens.map { line.substring(it) }.toMutableList()
                // zsh's $words holds an empty word where the cursor sits between two.
                val current = if (touched >= 0) {
                    touched
                } else {
                    tokens.count { it.last < cursor }.also { words.add(it, "") }
                }
                val typed = if (touched >= 0) line.substring(tokens[touched].first, cursor) else ""
                // zsh holds an open quote aside in compstate[quote], out of $PREFIX, which inside a double quote
                // has each backslash doubled.
                val quote = typed
                    .firstOrNull()
                    ?.takeIf { it == '\'' || it == '"' }
                    ?.takeIf { opening -> typed.count { it == opening } == 1 }
                val prefix = when (quote) {
                    '\'' -> typed.drop(1)
                    '"' -> typed.drop(1).replace("\\", "\\\\")
                    else -> typed
                }
                listOf(
                    "zsh", "-f", "-c",
                    $$"""
                    words=("${(@ps:\037:)WORDS}")
                    CURRENT=$CURRENT_WORD
                    PREFIX=$WORD_PREFIX
                    typeset -A compstate
                    compstate[quote]=$WORD_QUOTE
                    compset() { print -r -- "compset $*"; }
                    _files() { print -r -- FILES; }
                    compadd() {
                      local -a shown flags
                      while [[ $1 == -* && $1 != -- ]]; do
                        if [[ $1 == -d ]]; then
                          shown=("${(@P)2}"); shift 2
                        elif [[ $1 == -S ]]; then
                          flags+=("$1" "${(qq)2}"); shift 2
                        else
                          flags+=("$1"); shift
                        fi
                      done
                      (( $#flags )) && print -r -- "compadd $flags"
                      [[ $1 == -- ]] && shift
                      local i
                      for (( i = 1; i <= $#; i++ )); do
                        if [[ -n ${shown[i]} && ${shown[i]} != ${@[i]} ]]; then
                          print -r -- "${@[i]}"$'\t'"${shown[i]}"
                        else
                          print -r -- "${@[i]}"
                        fi
                      done
                      (( $# ))
                    }
                    compdef() { :; }
                    source "$1"
                    _tool
                    print -r -- $? > $STATUS_LOG
                    """.trimIndent(),
                    "zsh", script.path,
                ) to env + mapOf(
                    "WORDS" to words.joinToString("\u001f"),
                    "CURRENT_WORD" to (current + 1).toString(),
                    "WORD_PREFIX" to prefix,
                    "WORD_QUOTE" to quote?.toString().orEmpty(),
                )
            }
            // `complete -C` completes with the cursor at the end of the line, so fish cannot be asked about one
            // inside it.
            CompletionShell.FISH -> {
                require(cursor == line.length) { "fish completes only at the end of the line" }
                listOf(
                    "fish", "--no-config", "-c",
                    $$"set fish_complete_path $${dir.path}; complete -C \"$LINE\"",
                ) to env
            }
            CompletionShell.POWERSHELL -> listOf(
                "pwsh", "-NoProfile", "-NonInteractive", "-Command",
                $$"""
                $${argumentPassing?.let { $$"$PSNativeCommandArgumentPassing = '$$it'" }.orEmpty()}
                . '$${script.path}'
                $r = TabExpansion2 -inputScript $env:LINE -cursorColumn ([int]$env:POINT)
                $r.CompletionMatches | ForEach-Object { $_.CompletionText + "`t" + $_.ToolTip }
                """.trimIndent(),
            ) to env
        }
    }

    /** [shells] that are installed, so a missing one's checks do not run. */
    private fun installed(shells: List<CompletionShell> = CompletionShell.entries): List<CompletionShell> =
        shells.filter { isInstalled(it) }

    /**
     * What bash puts in COMP_WORDS for the word under [cursor] in [line]: from after the last COMP_WORDBREAKS `=`
     * or `:` outside quotes, or that separator itself right before the cursor.
     */
    private fun bashCurrentWord(
        line: String,
        cursor: Int,
    ): String {
        val range = rawWordRanges(line).firstOrNull { cursor in it.first..it.last + 1 } ?: return ""
        var start = range.first
        var quote: Char? = null
        var index = range.first
        while (index < cursor) {
            val char = line[index]
            when {
                char == '\\' && quote != '\'' -> index++
                quote != null -> if (char == quote) quote = null
                char == '\'' || char == '"' -> quote = char
                char == '=' || char == ':' -> start = if (index == cursor - 1) index else index + 1
            }
            index++
        }
        return line.substring(start, range.last + 1)
    }

    /** The words of [line] as a shell splits them: raw, quotes included, a quoted space inside the word. */
    private fun rawWordRanges(line: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var start = -1
        var quote: Char? = null
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                quote != null -> if (char == quote) quote = null else if (char == '\\' && quote == '"') index++
                char.isWhitespace() -> if (start >= 0) {
                    ranges += start until index
                    start = -1
                }
                else -> {
                    if (start < 0) start = index
                    if (char == '\'' || char == '"') quote = char else if (char == '\\') index++
                }
            }
            index++
        }
        if (start >= 0) ranges += start until line.length
        return ranges
    }


    // --- the call each shell makes ---

    @Test
    fun `every shell sends the words up to the cursor at the end of the line`() {
        for (shell in installed()) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", ".ow"),
                complete(shell, "tool get .ow").call,
                "$shell",
            )
        }
    }

    @Test
    fun `every shell sends an empty word under a cursor that starts a new one`() {
        for (shell in installed()) {
            assertEquals(listOf("__complete", "--cursor", "0", "--", ""), complete(shell, "tool ").call, "$shell")
        }
    }

    @Test
    fun `bash zsh and powershell send the words after the cursor`() {
        for (shell in installed(listOf(CompletionShell.BASH, CompletionShell.ZSH, CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", ".ow", "conf.conf"),
                complete(shell, "tool get .ow conf.conf", cursor = "tool get .ow".length).call,
                "$shell",
            )
        }
    }

    @Test
    fun `bash zsh and powershell send an empty word for a cursor between two words`() {
        for (shell in installed(listOf(CompletionShell.BASH, CompletionShell.ZSH, CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", "", "conf.conf"),
                complete(shell, "tool get  conf.conf", cursor = "tool get ".length).call,
                "$shell",
            )
        }
    }

    @Test
    fun `bash and zsh send the word under the cursor as far as it is typed and powershell sends it whole`() {
        for (shell in installed(listOf(CompletionShell.BASH, CompletionShell.ZSH, CompletionShell.POWERSHELL))) {
            // PowerShell hands its own completers the whole word wherever the cursor sits in it.
            val word = if (shell == CompletionShell.POWERSHELL) ".owner" else ".o"
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", word, "conf.conf"),
                complete(shell, "tool get .owner conf.conf", cursor = "tool get .o".length).call,
                "$shell",
            )
            // A cursor at the start of a word is in that word, with nothing of it typed yet.
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", ""),
                complete(shell, "tool get conf.conf", cursor = "tool get ".length).call,
                "$shell",
            )
        }
    }

    @Test
    fun `powershell sends an empty word in legacy argument passing too`() {
        for (shell in installed(listOf(CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", "", "conf.conf"),
                complete(shell, "tool get  conf.conf", cursor = "tool get ".length, argumentPassing = "Legacy").call,
            )
        }
    }

    @Test
    fun `powershell sends an empty word to a batch file launcher in windows argument passing`() {
        // Windows mode, PowerShell's default on Windows, passes arguments to a `.bat` the Legacy way.
        for (shell in installed(listOf(CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", "", "conf.conf"),
                complete(
                    shell,
                    "tool.bat get  conf.conf",
                    cursor = "tool.bat get ".length,
                    argumentPassing = "Windows",
                    program = cliNamed("tool.bat"),
                ).call,
            )
        }
    }

    @Test
    fun `bash fish and powershell send a quoted word under the cursor without its quotes`() {
        // zsh hands its script that word unquoted already, which its stand-ins here do not model.
        for (shell in installed(listOf(CompletionShell.BASH, CompletionShell.FISH, CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", ".al"),
                complete(shell, "tool get '.al").call,
                "$shell",
            )
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", ".a | .b"),
                complete(shell, "tool get \".a | .b").call,
                "$shell",
            )
        }
    }

    @Test
    fun `every shell sends a quoted word before the cursor without its quotes`() {
        for (shell in installed()) {
            assertEquals(
                listOf("__complete", "--cursor", "3", "--", "get", "-f", "my file", "."),
                complete(shell, "tool get -f 'my file' .").call,
                "$shell",
            )
        }
    }

    @Test
    fun `every shell sends a backslash inside double quotes as a run receives it`() {
        for (shell in installed()) {
            assertEquals(
                listOf("__complete", "--cursor", "2", "--", "get", "C:\\dir", ".o"),
                complete(shell, "tool get \"C:\\dir\" .o").call,
                "$shell",
            )
        }
    }

    @Test
    fun `every shell sends a backslash in a single quoted word under the cursor as typed`() {
        for (shell in installed()) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", "a\\b"),
                complete(shell, "tool get 'a\\b").call,
                "$shell",
            )
        }
    }

    @Test
    fun `powershell sends a closed double quoted word under the cursor without its quotes`() {
        for (shell in installed(listOf(CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", $$"a$x"),
                complete(shell, $$"tool get \"a$x\"").call,
            )
        }
    }

    @Test
    fun `bash keeps an option name before the value it completes`() {
        for (shell in installed(listOf(CompletionShell.BASH))) {
            assertEquals(listOf("-tred"), complete(shell, "tool get -tr", answer = "-tred\n").candidates)
            val quoted = complete(shell, "tool get \"--tag=r", answer = "--tag=red\n").candidates
            assertEquals(listOf("\"--tag=red\""), quoted)
            // readline replaces only from the opening quote on, so what stands before it stays on the line.
            assertEquals(listOf("'red'"), complete(shell, "tool get -t'r", answer = "-tred\n").candidates)
            assertEquals(listOf("'cdef'"), complete(shell, "tool get ab'c", answer = "abcdef\n").candidates)
            // An escaped quote inside the word does not open it.
            assertEquals(listOf("\"a\\\"bc\""), complete(shell, "tool get \"a\\\"b", answer = "a\"bc\n").candidates)
        }
    }

    @Test
    fun `bash replaces only what readline replaces`() {
        for (shell in installed(listOf(CompletionShell.BASH))) {
            // COMP_WORDBREAKS leaves what stands before a `=` or `:` on the line, out of the word bash replaces.
            val defined = complete(shell, "tool get --define=key=v", answer = "--define=key=val\n").candidates
            assertEquals(listOf("val"), defined)
            assertEquals(listOf("port"), complete(shell, "tool get host:po", answer = "host:port\n").candidates)
            // Right after the separator COMP_WORDS holds the separator itself, though readline replaces nothing.
            assertEquals(listOf("red"), complete(shell, "tool get --tag=", answer = "--tag=red\n").candidates)
            assertEquals(listOf("port"), complete(shell, "tool get host:", answer = "host:port\n").candidates)
            // A candidate that does not extend what was typed replaces it, as a fuzzy provider's does.
            assertEquals(listOf("red"), complete(shell, "tool get --tag=rd", answer = "--tag=red\n").candidates)
            assertEquals(listOf("red"), complete(shell, "tool get rd", answer = "red\n").candidates)
            // The rest of a word after the cursor stays on the line.
            assertEquals(listOf("red"), complete(shell, "tool get rd", cursor = 10, answer = "red\n").candidates)
        }
    }

    @Test
    fun `powershell quotes a candidate a run would not receive as offered`() {
        for (shell in installed(listOf(CompletionShell.POWERSHELL))) {
            val offered = complete(shell, "tool get ", answer = "a b\nprice$5\nplain\ndon’t\n").candidates
            // PowerShell reads a typographic quote as it does `'`, so it is doubled the same way.
            assertEquals(listOf("'a b'\ta b", "'price$5'\tprice$5", "plain\tplain", "'don’’t'\tdon’t"), offered)
        }
    }

    @Test
    fun `powershell inserts each candidate as the whole word`() {
        for (shell in installed(listOf(CompletionShell.POWERSHELL))) {
            val glued = complete(shell, "tool get --tag=r", answer = "--tag=red\n").candidates
            assertEquals(listOf("--tag=red\t--tag=red"), glued)
            val spaced = complete(shell, "tool get --tag=a", answer = "--tag=a b\n").candidates
            assertEquals(listOf("'--tag=a b'\t--tag=a b"), spaced)
            assertEquals(listOf("red\tred"), complete(shell, "tool get rd", answer = "red\n").candidates)
        }
    }

    @Test
    fun `powershell reads a typographic quote as a quote`() {
        for (shell in installed(listOf(CompletionShell.POWERSHELL))) {
            val completed = complete(shell, "tool get ‘ab", answer = "abc\n")
            assertEquals(listOf("__complete", "--cursor", "1", "--", "get", "ab"), completed.call)
            assertEquals(listOf("'abc'\tabc"), completed.candidates)
        }
    }

    @Test
    fun `fish inserts each candidate as the whole word`() {
        for (shell in installed(listOf(CompletionShell.FISH))) {
            val offered = complete(shell, "tool get --tag=r", answer = "--tag=red\n").candidates
            assertTrue(offered.any { it.startsWith("--tag=red") }, "$offered")
        }
    }

    @Test
    fun `bash zsh and fish send an escaped space under the cursor unescaped`() {
        // PowerShell escapes with a backtick, so a backslash there is a plain character.
        for (shell in installed(listOf(CompletionShell.BASH, CompletionShell.ZSH, CompletionShell.FISH))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", "my fi"),
                complete(shell, "tool get my\\ fi").call,
                "$shell",
            )
        }
    }

    @Test
    fun `every shell sends a closed quoted word under the cursor without its quotes`() {
        for (shell in installed()) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", "abc"),
                complete(shell, "tool get 'abc'").call,
                "$shell",
            )
        }
        for (shell in installed(listOf(CompletionShell.POWERSHELL))) {
            val offered = complete(shell, "tool get 'abc'", answer = "abcdef\n").candidates
            assertEquals(listOf("'abcdef'\tabcdef"), offered)
        }
    }

    @Test
    fun `zsh offers each candidate as the whole word`() {
        for (shell in installed(listOf(CompletionShell.ZSH))) {
            assertEquals(listOf("--tag=red"), complete(shell, "tool get --tag=r", answer = "--tag=red\n").candidates)
            // An escaped word is matched as a run receives it, so the candidate covers all of it.
            assertEquals(listOf("my file"), complete(shell, "tool get my\\ fi", answer = "my file\n").candidates)
        }
    }

    @Test
    fun `zsh inserts a candidate inside a double quote as it is typed there`() {
        for (shell in installed(listOf(CompletionShell.ZSH))) {
            // zsh keeps what was typed and adds only what a value has past it, so each goes in as typed there.
            val offered = complete(shell, "tool get \"C:\\di", answer = "C:\\dir\nxyz\n").candidates
            // A value that does not extend the typed word would be added to it, so it is left out.
            assertEquals(listOf("compadd -U -Q", "C:\\dir"), offered.map { it.substringBefore('\t') })
            val escaped = complete(shell, "tool get \"", answer = $$"a\"b$5\nx\\$y\n").candidates
            assertEquals(
                listOf("compadd -U -Q", "a\\\"b\\$5", $$"x\\\\\\$y"),
                escaped.map { it.substringBefore('\t') },
            )
            val glued = complete(shell, "tool get \"--tag=r", answer = "--tag=red\n").candidates
            assertEquals(listOf("compadd -U -Q", "--tag=red"), glued.map { it.substringBefore('\t') })
            // History expansion reads a `!` inside double quotes unless a backslash keeps it out.
            val bang = complete(shell, "tool get \"h", answer = "hi!x\n").candidates
            assertEquals(listOf("compadd -U -Q", "hi\\!x"), bang.map { it.substringBefore('\t') })
        }
    }

    @Test
    fun `bash inserts a candidate so that a run receives it as offered`() {
        for (shell in installed(listOf(CompletionShell.BASH))) {
            val answer = "price$5\nx`y`\na b\nhi!x\n"
            assertEquals(
                // History expansion reads a `!` inside double quotes, and no backslash keeps it from there.
                listOf("\"price\\$5\"", "\"x\\`y\\`\"", "\"a b\"", "\"hi\"\\!\"x\""),
                complete(shell, "tool get \"", answer = answer).candidates,
            )
            assertEquals(
                listOf("price\\$5", "x\\`y\\`", "a\\ b", "hi\\!x"),
                complete(shell, "tool get ", answer = answer).candidates,
            )
        }
    }

    @Test
    fun `bash and powershell keep the opening quote of the word they complete`() {
        for (shell in installed(listOf(CompletionShell.BASH, CompletionShell.POWERSHELL))) {
            val offered = complete(shell, "tool get '.al", answer = ".alpha\n").candidates
            assertTrue(offered.any { it.startsWith("'.alpha'") }, "$shell: $offered")
        }
    }

    // --- what each shell offers from the answer ---

    @Test
    fun `bash leaves no space after an attached option name`() {
        for (shell in installed(listOf(CompletionShell.BASH))) {
            val completed = complete(shell, "tool --color", answer = "$ATTACHED_NAME--color=\tcolor\n")
            assertEquals(listOf("--color="), completed.candidates)
            assertEquals(listOf("-o nospace"), completed.compopts)
        }
    }

    @Test
    fun `bash keeps the space after a value ending in an equals sign`() {
        for (shell in installed(listOf(CompletionShell.BASH))) {
            val completed = complete(shell, "tool get ", answer = "dGVzdA==\nKEY=\n")
            assertEquals(listOf("dGVzdA==", "KEY="), completed.candidates)
            assertEquals(emptyList(), completed.compopts)
        }
    }

    @Test
    fun `bash keeps the space for a reply holding more than attached option names`() {
        for (shell in installed(listOf(CompletionShell.BASH))) {
            val completed = complete(shell, "tool --col", answer = "--color\tcolor\n$ATTACHED_NAME--color=\tcolor\n")
            assertEquals(listOf("--color", "--color="), completed.candidates)
            assertEquals(emptyList(), completed.compopts)
        }
    }

    @Test
    fun `zsh leaves no space after an attached option name and keeps it after a value ending in an equals sign`() {
        for (shell in installed(listOf(CompletionShell.ZSH))) {
            val answer = "KEY=\tkey\n$ATTACHED_NAME--color=\tcolor\n"
            val offered = complete(shell, "tool ", answer = answer).candidates
            assertEquals(listOf("KEY=", "compadd -S ''", "--color="), offered.map { it.substringBefore('\t') })
            val quoted = complete(shell, "tool \"", answer = "KEY=\n$ATTACHED_NAME--color=\n").candidates
            assertEquals(listOf("compadd -U -Q", "KEY=", "compadd -U -Q -S ''", "--color="), quoted)
        }
    }

    @Test
    fun `bash and zsh leave no space after a glued short without candidates`() {
        val glued = cli("tool") {
            option(short = 'O', help = "level").gluedValue()
            action { Ok("") }
        }
        val terminal = RecordingTerminal()
        glued.run(arrayOf("__complete", "--cursor", "0", "--", "-O"), terminal)
        for (shell in installed(listOf(CompletionShell.BASH))) {
            val completed = complete(shell, "tool -O", answer = terminal.out, program = glued)
            assertEquals(listOf("-O"), completed.candidates)
            assertEquals(listOf("-o nospace"), completed.compopts)
        }
        for (shell in installed(listOf(CompletionShell.ZSH))) {
            val offered = complete(shell, "tool -O", answer = terminal.out, program = glued).candidates
            assertEquals(listOf("compadd -S ''", "-O"), offered.map { it.substringBefore('\t') })
        }
    }

    @Test
    fun `zsh reports matches added whether or not a candidate is an attached option name`() {
        val answers = listOf(
            "KEY=\tkey\n",
            "$ATTACHED_NAME--color=\tcolor\n",
            "KEY=\tkey\n$ATTACHED_NAME--color=\tcolor\n",
        )
        for (shell in installed(listOf(CompletionShell.ZSH))) {
            for (answer in answers) {
                assertEquals(0, complete(shell, "tool ", answer = answer).status, "unquoted: $answer")
                assertEquals(0, complete(shell, "tool \"", answer = answer).status, "quoted: $answer")
            }
        }
    }

    @Test
    fun `bash and zsh leave no space after a cluster continuing into a glued short`() {
        val glued = cli("tool") {
            flag(short = 'v', help = "be loud")
            option(short = 'O', help = "level").gluedValue()
            action { Ok("") }
        }
        val terminal = RecordingTerminal()
        glued.run(arrayOf("__complete", "--cursor", "0", "--", "-v"), terminal)
        for (shell in installed(listOf(CompletionShell.BASH))) {
            val completed = complete(shell, "tool -v", answer = terminal.out, program = glued)
            assertEquals(listOf("-vO"), completed.candidates)
            assertEquals(listOf("-o nospace"), completed.compopts)
        }
        for (shell in installed(listOf(CompletionShell.ZSH))) {
            val offered = complete(shell, "tool -v", answer = terminal.out, program = glued).candidates
            assertEquals(listOf("compadd -S ''", "-vO"), offered.map { it.substringBefore('\t') })
        }
    }

    @Test
    fun `bash and zsh complete a path glued to a short whose value is a path`() {
        val glued = cli("tool") {
            option(short = 'O', help = "output").gluedValue().file()
            action { Ok("") }
        }
        File(dir, "src").mkdirs()
        val terminal = RecordingTerminal()
        glued.run(arrayOf("__complete", "--cursor", "0", "--", "-O"), terminal)
        for (shell in installed(listOf(CompletionShell.BASH))) {
            val completed = complete(shell, "tool -O", answer = terminal.out, program = glued)
            // readline would stat `-Osrc` as a whole, so the script ends each match itself: `/` or a space.
            assertTrue("-Osrc/" in completed.candidates, "${completed.candidates}")
            assertTrue("-Oconf.conf " in completed.candidates, "${completed.candidates}")
            assertTrue(completed.candidates.all { it.startsWith("-O") }, "${completed.candidates}")
            assertEquals(listOf("-o nospace"), completed.compopts)
        }
        for (shell in installed(listOf(CompletionShell.ZSH))) {
            val offered = complete(shell, "tool -O", answer = terminal.out, program = glued).candidates
            assertEquals(listOf("compset -p 2", "FILES"), offered)
        }
    }

    @Test
    fun `bash ends a glued path under a leading tilde by what it names in HOME`() {
        val glued = cli("tool") {
            option(short = 'O', help = "output").gluedValue().file()
            action { Ok("") }
        }
        val home = File(dir, "home")
        File(home, "dir").mkdirs()
        File(home, "file").writeText("")
        val terminal = RecordingTerminal()
        glued.run(arrayOf("__complete", "--cursor", "0", "--", "-O"), terminal)
        for (shell in installed(listOf(CompletionShell.BASH))) {
            val completed = complete(
                shell,
                "tool -O~/",
                answer = terminal.out,
                program = glued,
                environment = mapOf("HOME" to home.path),
            )
            assertEquals(listOf("-O~/dir/", "-O~/file "), completed.candidates.sorted())
            assertEquals(listOf("-o nospace"), completed.compopts)
        }
    }

    @Test
    fun `fish and powershell offer an attached option name without its mark`() {
        for (shell in installed(listOf(CompletionShell.FISH, CompletionShell.POWERSHELL))) {
            val answer = "--color\tcolor\n$ATTACHED_NAME--color=\tcolor\n"
            val offered = complete(shell, "tool --col", answer = answer).candidates
            assertEquals(listOf("--color", "--color="), offered.map { it.substringBefore('\t') }, "$shell")
        }
    }

    @Test
    fun `every shell offers the candidates tool answers with`() {
        for (shell in installed()) {
            val offered = complete(shell, "tool get .ow", answer = ".owner\tthe owner\n.ows\n").candidates
            assertTrue(offered.any { it.startsWith(".owner") }, "$shell: $offered")
            assertTrue(offered.any { it.startsWith(".ows") }, "$shell: $offered")
        }
    }

    @Test
    fun `every shell hides what tool writes to standard error and offers nothing from it`() {
        for (shell in installed()) {
            val trace = "Exception in thread main java.lang.IllegalStateException: boom"
            assertEquals(emptyList(), complete(shell, "tool get .ow", stderr = trace).candidates, "$shell")
        }
    }

    @Test
    fun `zsh fish and powershell show a candidates description and bash does not`() {
        for (shell in installed(listOf(CompletionShell.ZSH, CompletionShell.FISH, CompletionShell.POWERSHELL))) {
            val offered = complete(shell, "tool get .ow", answer = ".owner\tthe owner\n").candidates
            assertEquals(1, offered.size, "$shell: $offered")
            assertTrue(offered.single().contains("the owner"), "$shell: $offered")
        }
        for (shell in installed(listOf(CompletionShell.BASH))) {
            assertEquals(listOf(".owner"), complete(shell, "tool get .ow", answer = ".owner\tthe owner\n").candidates)
        }
    }

    @Test
    fun `bash completes a path only as far as it is typed`() {
        for (shell in installed(listOf(CompletionShell.BASH))) {
            // The rest of the word after the cursor stays on the line, as bash's own file completion leaves it.
            val typed = "tool get . co".length
            val inside = complete(shell, "tool get . cox", cursor = typed, answer = "$COMPLETE_FILES\n")
            assertEquals(listOf("conf.conf"), inside.candidates)
            // Right after `=` COMP_WORDS holds the `=` itself, which is no part of the path.
            val attached = complete(shell, "tool get --file=", answer = "$COMPLETE_FILES--file=\n").candidates
            assertTrue("conf.conf" in attached, "$attached")
            assertTrue(attached.none { it.startsWith("=") || it.startsWith("-") }, "$attached")
        }
    }

    @Test
    fun `every shell completes paths for the file directive`() {
        for (shell in installed()) {
            val offered = complete(shell, "tool get . con", answer = "$COMPLETE_FILES\n").candidates
            // zsh's file completion is stood in for, so it shows the stand-in's marker instead of the file.
            val expected = if (shell == CompletionShell.ZSH) "FILES" else "conf.conf"
            assertTrue(offered.any { expected in it }, "$shell: $offered")
        }
    }

    // --- loading the script ---

    @Test
    fun `zsh registers the completion whether the script is sourced or autoloaded from fpath`() {
        if (!isInstalled(CompletionShell.ZSH)) return
        val functions = File(dir, "functions").apply { mkdirs() }
        File(functions, "_tool").writeText(tool.renderCompletion(CompletionShell.ZSH))
        // compinit finds an autoloaded script on $fpath as it starts, and a sourced one needs the compdef it defines.
        val loads = mapOf(
            "autoloaded" to ($$"fpath=($${functions.path} $fpath)" to ""),
            "sourced" to ("" to "source ${File(functions, "_tool").path}"),
            "sourced by its bare name" to ("" to "cd ${functions.path}; source _tool"),
        )
        for ((how, load) in loads) {
            val (beforeCompinit, afterCompinit) = load
            val script = $$"""
                $$beforeCompinit
                autoload -U compinit
                compinit -u -D
                $$afterCompinit
                print -r -- "$_comps[tool]"
            """.trimIndent()
            val process = ProcessBuilder("zsh", "-f", "-c", script)
                .directory(dir)
                .redirectError(errors)
                .start()
            val out = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "zsh did not finish")
            assertEquals("", errors.readText(), "zsh wrote to standard error when the script was $how")
            assertEquals("_tool", out.trim(), "the script $how")
        }
    }
}
