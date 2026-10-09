package com.fromwau.klap

import java.io.File

/** The executable that runs this shell. */
internal val CompletionShell.executable: String
    get() = when (this) {
        CompletionShell.BASH -> "bash"
        CompletionShell.ZSH -> "zsh"
        CompletionShell.FISH -> "fish"
        CompletionShell.POWERSHELL -> "pwsh"
    }

private val found = mutableMapOf<CompletionShell, Boolean>()

/**
 * Whether [shell] is on the PATH. Looked up once per run, so a missing shell is reported once rather than by
 * every test. Windows names an executable with one of the extensions PATHEXT lists, `pwsh.exe` for one.
 */
@Synchronized
internal fun isInstalled(shell: CompletionShell): Boolean = found.getOrPut(shell) {
    val path = System.getenv("PATH").split(File.pathSeparator)
    val extensions = listOf("") + System.getenv("PATHEXT").orEmpty().split(';').filter { it.isNotEmpty() }
    val installed = path.any { dir -> extensions.any { File(dir, shell.executable + it).canExecute() } }
    if (!installed) println("tests skipped because ${shell.executable} shell not installed")
    installed
}
