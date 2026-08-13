package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable

@Serializable
private data class Report(val failed: Int, val total: Int)

/** `diff`'s contract: the diff is the value, and a non-empty one exits 1 without being an error. */
private fun diffLike(hunks: List<String>) = cli("diff") {
    action(
        human = { it.joinToString("\n") },
        exitCode = { if (it.isEmpty()) 0 else 1 },
    ) { Ok(hunks) }
}

class ActionExitCodeTest {

    @Test
    fun `a success without the projection still exits zero`() {
        val t = RecordingTerminal()
        assertEquals(0, cli("app") { action { Ok("done") } }.run(emptyList(), t))
        assertEquals("done\n", t.out.toString())
        assertEquals("", t.err.toString())
    }

    @Test
    fun `a declared code reaches the exit while the value still reaches stdout`() {
        val t = RecordingTerminal()
        val tree = cli("app") {
            action(exitCode = { 3 }) { Ok("printed anyway") }
        }
        assertEquals(3, tree.run(emptyList(), t))
        assertEquals("printed anyway\n", t.out.toString())
        assertEquals("", t.err.toString())
    }

    @Test
    fun `the diff contract both ways`() {
        val differ = RecordingTerminal()
        assertEquals(1, diffLike(listOf("2c2", "< b", "> c")).run(emptyList(), differ))
        assertEquals("2c2\n< b\n> c\n", differ.out.toString())
        assertEquals("", differ.err.toString())

        val same = RecordingTerminal()
        assertEquals(0, diffLike(emptyList()).run(emptyList(), same))
        assertEquals("", same.out.toString())
        assertEquals("", same.err.toString())
    }

    @Test
    fun `a code outside the byte range is clamped`() {
        val high = RecordingTerminal()
        assertEquals(255, cli("app") { action(exitCode = { 900 }) { Ok("x") } }.run(emptyList(), high))

        val low = RecordingTerminal()
        assertEquals(0, cli("app") { action(exitCode = { -7 }) { Ok("x") } }.run(emptyList(), low))
    }

    @Test
    fun `an error keeps its own code and never consults the projection`() {
        var consulted = false
        val tree = cli("app") {
            action<String>(
                exitCode = {
                    consulted = true
                    3
                },
            ) { Err(CliError.Failure("boom", 4)) }
        }
        val t = RecordingTerminal()
        assertEquals(4, tree.run(emptyList(), t))
        assertEquals("error: boom\n", t.err.toString())
        assertEquals("", t.out.toString())
        assertFalse(consulted)
    }

    @Test
    fun `under json the value is the only document and the code still lands`() {
        val t = RecordingTerminal()
        val tree = cli("app") {
            action(
                human = { "${it.failed} of ${it.total} failed" },
                exitCode = { if (it.failed == 0) 0 else 1 },
            ) { Ok(Report(failed = 2, total = 5)) }
        }
        assertEquals(1, tree.run(listOf("--json"), t))
        assertEquals("""{"failed":2,"total":5}""" + "\n", t.out.toString())
        assertEquals("", t.err.toString())
    }

    @Test
    fun `the projection reads the scope so it can differ by render mode`() {
        val tree = cli("app") {
            action(exitCode = { if (json) 2 else 1 }) { Ok("x") }
        }
        assertEquals(1, tree.run(emptyList(), RecordingTerminal()))
        assertEquals(2, tree.run(listOf("--json"), RecordingTerminal()))
    }

    @Test
    fun `a suspending action declares its code the same way`() = runTest {
        val t = RecordingTerminal()
        val tree = cli("app") {
            actionSuspending(exitCode = { if (it.isEmpty()) 0 else 1 }) { Ok(listOf("drift")) }
        }
        assertEquals(1, tree.runSuspending(emptyArray(), t))
        assertEquals("[drift]\n", t.out.toString())
    }
}
