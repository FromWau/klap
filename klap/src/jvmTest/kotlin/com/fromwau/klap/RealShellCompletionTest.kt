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

    private val tool = cli("tool") {
        command("get") {
            argument("filter")
            argument("file").optional()
            action { Ok("") }
        }
    }

    private val dir: File = Files.createTempDirectory("klap-shell").toFile()
    private val bin = File(dir, "bin").apply { mkdirs() }
    private val log = File(dir, "calls")
    private val reply = File(dir, "reply")

    init {
        // Each call is one line of its arguments, separated by the unit separator so an empty one shows.
        File(bin, "tool").apply {
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
    ): Completed {
        val executable = shell.executable
        log.writeText("")
        reply.writeText(answer)
        val script = File(dir, if (shell == CompletionShell.POWERSHELL) "tool.ps1" else "tool.${executable}")
        script.writeText(tool.renderCompletion(shell))
        val (command, extraEnv) = driver(shell, script, line, cursor)
        val process = ProcessBuilder(command)
            .directory(dir)
            .apply {
                environment()["PATH"] = "${bin.path}:${System.getenv("PATH")}"
                environment()["TOOL_LOG"] = log.path
                environment()["TOOL_REPLY"] = reply.path
                environment().putAll(extraEnv)
            }
            .start()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "$executable did not finish")
        // A script error prints here while the shell carries on, so an empty stderr is part of every check.
        assertEquals("", err, "$executable wrote to standard error")
        val calls = log.readLines().filter { it.isNotEmpty() }
        assertEquals(1, calls.size, "$executable called tool ${calls.size} times: $calls")
        return Completed(calls.single().removeSuffix("\u001f").split('\u001f'), out.lines().filter { it.isNotEmpty() })
    }

    private fun driver(
        shell: CompletionShell,
        script: File,
        line: String,
        cursor: Int,
    ): Pair<List<String>, Map<String, String>> {
        val env = mapOf("LINE" to line, "POINT" to cursor.toString())
        return when (shell) {
            // COMP_WORDS as bash splits the words up to the cursor, with an empty one when the cursor starts
            // a new word; the script rebuilds everything else from COMP_LINE and COMP_POINT.
            CompletionShell.BASH -> listOf(
                "bash", "--norc", "--noprofile", "-c",
                $$"""
                source "$1"
                COMP_LINE="$LINE"; COMP_POINT=$POINT
                before="${LINE:0:POINT}"
                read -ra COMP_WORDS <<< "$before"
                [[ -z "$before" || "$before" == *[[:space:]] ]] && COMP_WORDS+=("")
                COMP_CWORD=$(( ${#COMP_WORDS[@]} - 1 ))
                _tool
                printf '%s\n' "${COMPREPLY[@]}"
                """.trimIndent(),
                "bash", script.path,
            ) to env
            // Without a terminal zsh has no completion system, so its three builtins the script calls are
            // stood in for: compadd prints each value with the display text it was given.
            CompletionShell.ZSH -> {
                val before = line.take(cursor)
                val words = Regex("\\S+").findAll(line).toMutableList()
                val index = words.indexOfFirst { cursor in it.range.first..it.range.last + 1 }
                val wordList = words.map { it.value }.toMutableList()
                val current = if (index >= 0) index else {
                    val at = words.count { it.range.last < cursor }
                    wordList.add(at, "")
                    at
                }
                val prefix = if (index >= 0) before.substring(words[index].range.first) else ""
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
                    "WORDS" to wordList.joinToString("\u001f"),
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
                . '$${script.path}'
                $r = TabExpansion2 -inputScript $env:LINE -cursorColumn ([int]$env:POINT)
                $r.CompletionMatches | ForEach-Object { $_.CompletionText + "`t" + $_.ToolTip }
                """.trimIndent(),
            ) to env
        }
    }

    private val CompletionShell.executable: String
        get() = when (this) {
            CompletionShell.BASH -> "bash"
            CompletionShell.ZSH -> "zsh"
            CompletionShell.FISH -> "fish"
            CompletionShell.POWERSHELL -> "pwsh"
        }

    /** [shells] that are installed; each missing one is reported and left out, so its checks do not run. */
    private fun installed(shells: List<CompletionShell> = CompletionShell.entries): List<CompletionShell> =
        shells.filter { shell ->
            val found = System.getenv("PATH").split(File.pathSeparator).any { File(it, shell.executable).canExecute() }
            if (!found) println("tests skipped because ${shell.executable} shell not installed")
            found
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
            val expected = if (shell == CompletionShell.ZSH) "FILES" else "conf.toml"
            assertTrue(offered.any { it.endsWith(expected) || it.startsWith(expected) }, "$shell: $offered")
        }
    }
}
