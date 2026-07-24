package com.fromwau.klap

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

class CompletionTest {

    @Test
    fun shellOf_isCaseInsensitive() {
        assertEquals(CompletionShell.FISH, completionShellOf("Fish"))
        assertEquals(null, completionShellOf("powershell"))
    }

    @Test
    fun fish_offersTopLevelAndSubcommands() {
        val script = sampleTree().renderCompletion(CompletionShell.FISH)
        assertTrue("-a 'add'" in script, script)
        assertTrue("-a 'config'" in script, script)
        assertTrue("__fish_seen_subcommand_from config" in script, script)
    }

    @Test
    fun fish_reenablesFilesForFileArg() {
        val script = sampleTree().renderCompletion(CompletionShell.FISH)
        assertTrue("__fish_seen_subcommand_from add' -F" in script, script)
    }

    @Test
    fun bash_hasTopWordsAndCaseArms() {
        val script = sampleTree().renderCompletion(CompletionShell.BASH)
        assertTrue("local top=\"add config completion\"" in script, script)
        assertTrue("config)" in script, script)
    }

    @Test
    fun completionNodes_dedupesByNameShallowestFirst() {
        val tree = cli("root") {
            command("a") { command("dup") { action { Ok("") } } }
            command("dup") { action { Ok("") } }
        }
        assertEquals(1, tree.completionNodes().count { it.name == "dup" })
    }

    @Test
    fun completionNodes_keepsShallowestOnCollision() {
        val tree = cli("root") {
            command("a") {
                command("dup") { command("deep") { action { Ok("") } } }
            }
            command("dup") { command("shallowChild") { action { Ok("") } } }
        }
        val dup = tree.completionNodes().single { it.name == "dup" }
        // The depth-1 dup must win over the depth-2 one, so its child is shallowChild, not deep.
        assertEquals(listOf("shallowChild"), dup.subcommands.map { it.name })
    }
}
