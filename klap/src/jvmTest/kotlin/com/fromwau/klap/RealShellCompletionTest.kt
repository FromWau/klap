package com.fromwau.klap

import com.fromwau.kern.result.Ok
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

    init {
        // Each call is one line of its arguments, separated by the unit separator so an empty one shows.
        val stub = File(bin, "tool").apply {
            writeText(
                $$"""
                #!/bin/sh
                printf '%s\037' "$@" >> "$TOOL_LOG"
                echo >> "$TOOL_LOG"
                cat "$TOOL_REPLY"

                """.trimIndent(),
            )
            setExecutable(true)
        }
        // A launcher the way a JVM CLI installs one on Windows, which PowerShell calls differently.
        stub.copyTo(File(bin, "tool.bat")).setExecutable(true)
        File(dir, "conf.toml").writeText("")
    }

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** What one completion did: the arguments `tool` was called with, and the candidates the shell offered. */
    private data class Completed(val call: List<String>, val candidates: List<String>)

    /** Completes [line] with the cursor at [cursor] (its end by default), [answer] being what `tool` prints. */
    private fun complete(
        shell: CompletionShell,
        line: String,
        cursor: Int = line.length,
        answer: String = "",
        argumentPassing: String? = null,
        program: Cli = tool,
    ): Completed {
        val executable = shell.executable
        log.writeText("")
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
                environment().putAll(extraEnv)
            }
            .start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "$executable did not finish")
        // A script error prints here while the shell carries on, so an empty stderr is part of every check.
        assertEquals("", errors.readText(), "$executable wrote to standard error")
        val calls = log.readLines().filter { it.isNotEmpty() }
        assertEquals(1, calls.size, "$executable called tool ${calls.size} times: $calls")
        val call = calls.single().removeSuffix("\u001f").split('\u001f')
        return Completed(call, out.lines().filter { it.isNotEmpty() })
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
            // bash leaves it, and rebuilds everything else from COMP_LINE and COMP_POINT.
            CompletionShell.BASH -> listOf(
                "bash", "--norc", "--noprofile", "-c",
                $$"""
                source "$1"
                COMP_LINE="$LINE"; COMP_POINT=$POINT
                COMP_WORDS=("${LINE%% *}" "$CUR"); COMP_CWORD=1
                _tool
                printf '%s\n' "${COMPREPLY[@]}"
                """.trimIndent(),
                "bash", script.path,
            ) to env + ("CUR" to rawWordRanges(line).firstOrNull { cursor in it.first..it.last + 1 }
                ?.let { line.substring(it.first, cursor) }.orEmpty())
            // Without a terminal zsh has no completion system, so its three builtins the script calls are
            // stood in for: compadd prints each value with the display text it was given.
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
                val prefix = if (touched >= 0) line.substring(tokens[touched].first, cursor) else ""
                listOf(
                    "zsh", "-f", "-c",
                    $$"""
                    words=("${(@ps:\037:)WORDS}")
                    CURRENT=$CURRENT_WORD
                    PREFIX=$WORD_PREFIX
                    compset() { :; }
                    _files() { print -r -- FILES; }
                    compadd() {
                      local -a shown
                      [[ $1 == -d ]] && { shown=("${(@P)2}"); shift 2; }
                      [[ $1 == -- ]] && shift
                      local i
                      for (( i = 1; i <= $#; i++ )); do print -r -- "${shown[i]:-${@[i]}}"; done
                    }
                    source "$1"
                    """.trimIndent(),
                    "zsh", script.path,
                ) to env + mapOf(
                    "WORDS" to words.joinToString("\u001f"),
                    "CURRENT_WORD" to (current + 1).toString(),
                    "WORD_PREFIX" to prefix,
                )
            }
            // `complete -C` completes with the cursor at the end of the line, so fish cannot be asked about one
            // inside it.
            CompletionShell.FISH -> {
                require(cursor == line.length) { "fish completes only at the end of the line" }
                listOf(
                    "fish", "--no-config", "-c",
                    "set fish_complete_path ${dir.path}; complete -C \"\$LINE\"",
                ) to env
            }
            CompletionShell.POWERSHELL -> listOf(
                "pwsh", "-NoProfile", "-NonInteractive", "-Command",
                $$"""
                $${argumentPassing?.let { "\$PSNativeCommandArgumentPassing = '$it'" }.orEmpty()}
                . '$${script.path}'
                $r = TabExpansion2 -inputScript $env:LINE -cursorColumn ([int]$env:POINT)
                $r.CompletionMatches | ForEach-Object { $_.CompletionText + "`t" + $_.ToolTip }
                """.trimIndent(),
            ) to env
        }
    }

    /** [shells] that are installed, so a missing one's checks do not run. */
    private fun installed(shells: List<CompletionShell> = CompletionShell.entries): List<CompletionShell> =
        shells.filter { it in installedShells }

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

    private companion object {
        val CompletionShell.executable: String
            get() = when (this) {
                CompletionShell.BASH -> "bash"
                CompletionShell.ZSH -> "zsh"
                CompletionShell.FISH -> "fish"
                CompletionShell.POWERSHELL -> "pwsh"
            }

        // Looked up once per run, so each missing shell is reported once rather than by every test.
        val installedShells: Set<CompletionShell> by lazy {
            val path = System.getenv("PATH").split(File.pathSeparator)
            CompletionShell.entries.filterTo(mutableSetOf()) { shell ->
                val found = path.any { File(it, shell.executable).canExecute() }
                if (!found) println("tests skipped because ${shell.executable} shell not installed")
                found
            }
        }
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
                listOf("__complete", "--cursor", "1", "--", "get", ".ow", "conf.toml"),
                complete(shell, "tool get .ow conf.toml", cursor = "tool get .ow".length).call,
                "$shell",
            )
        }
    }

    @Test
    fun `bash zsh and powershell send an empty word for a cursor between two words`() {
        for (shell in installed(listOf(CompletionShell.BASH, CompletionShell.ZSH, CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", "", "conf.toml"),
                complete(shell, "tool get  conf.toml", cursor = "tool get ".length).call,
                "$shell",
            )
        }
    }

    @Test
    fun `bash zsh and powershell send the word under the cursor only as far as it is typed`() {
        for (shell in installed(listOf(CompletionShell.BASH, CompletionShell.ZSH, CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", ".o", "conf.toml"),
                complete(shell, "tool get .owner conf.toml", cursor = "tool get .o".length).call,
                "$shell",
            )
            // A cursor at the start of a word is in that word, with nothing of it typed yet.
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", ""),
                complete(shell, "tool get conf.toml", cursor = "tool get ".length).call,
                "$shell",
            )
        }
    }

    @Test
    fun `powershell sends an empty word in legacy argument passing too`() {
        for (shell in installed(listOf(CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", "", "conf.toml"),
                complete(shell, "tool get  conf.toml", cursor = "tool get ".length, argumentPassing = "Legacy").call,
            )
        }
    }

    @Test
    fun `powershell sends an empty word to a batch file launcher in windows argument passing`() {
        // Windows mode, PowerShell's default on Windows, passes arguments to a `.bat` the Legacy way.
        for (shell in installed(listOf(CompletionShell.POWERSHELL))) {
            assertEquals(
                listOf("__complete", "--cursor", "1", "--", "get", "", "conf.toml"),
                complete(
                    shell,
                    "tool.bat get  conf.toml",
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
            assertEquals(listOf("__complete", "--cursor", "1", "--", "get", ".al"), complete(shell, "tool get '.al").call)
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
    fun `bash and powershell keep the opening quote of the word they complete`() {
        for (shell in installed(listOf(CompletionShell.BASH, CompletionShell.POWERSHELL))) {
            val offered = complete(shell, "tool get '.al", answer = ".alpha\n").candidates
            assertTrue(offered.any { it.startsWith("'.alpha'") }, "$shell: $offered")
        }
    }

    // --- what each shell offers from the answer ---

    @Test
    fun `every shell offers the candidates tool answers with`() {
        for (shell in installed()) {
            val offered = complete(shell, "tool get .ow", answer = ".owner\tthe owner\n.ows\n").candidates
            assertTrue(offered.any { it.startsWith(".owner") }, "$shell: $offered")
            assertTrue(offered.any { it.startsWith(".ows") }, "$shell: $offered")
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
    fun `every shell completes paths for the file directive`() {
        for (shell in installed()) {
            val offered = complete(shell, "tool get . con", answer = "$COMPLETE_FILES\n").candidates
            // zsh's file completion is stood in for, so it shows the stand-in's marker instead of the file.
            val expected = if (shell == CompletionShell.ZSH) "FILES" else "conf.toml"
            assertTrue(offered.any { expected in it }, "$shell: $offered")
        }
    }
}
