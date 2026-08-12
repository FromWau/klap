package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SuspendingActionTest {

    @Test
    fun `a suspending action runs through runSuspending and resumes after a real suspension`() = runTest {
        val tree = cli("app") {
            actionSuspending {
                delay(10)
                Ok("resumed")
            }
        }
        val terminal = RecordingTerminal()
        assertEquals(0, tree.runSuspending(emptyArray(), terminal))
        assertEquals("resumed\n", terminal.out.toString())
    }

    @Test
    fun `an ordinary action runs fine under runSuspending`() = runTest {
        val tree = cli("app") { action { Ok("plain") } }
        val terminal = RecordingTerminal()
        assertEquals(0, tree.runSuspending(emptyArray(), terminal))
        assertEquals("plain\n", terminal.out.toString())
    }

    @Test
    fun `runActionSuspending hands back the action's own typed result`() = runTest {
        val tree = cli("app") {
            actionSuspending {
                delay(10)
                Ok("value")
            }
        }
        val execute = assertIs<Invocation.Execute>(
            assertIs<Result.Success<Invocation>>(tree.parse(emptyList())).value,
        )
        assertEquals(Result.Success("value"), execute.runActionSuspending())
    }

    @Test
    fun `a suspending action renders through json and through a human renderer`() = runTest {
        val tree = cli("app") {
            actionSuspending(human = { "human $it" }) {
                delay(10)
                Ok("v")
            }
        }
        val human = RecordingTerminal()
        assertEquals(0, tree.runSuspending(emptyArray(), human))
        assertEquals("human v\n", human.out.toString())

        val machine = RecordingTerminal()
        assertEquals(0, tree.runSuspending(arrayOf("--json"), machine))
        assertEquals("\"v\"\n", machine.out.toString())
    }

    @Test
    fun `the sync run refuses a cli that declares a suspending action`() {
        val tree = cli("app") { actionSuspending { Ok("x") } }
        val error = assertFailsWith<IllegalArgumentException> {
            tree.run(emptyArray(), RecordingTerminal())
        }
        // A root-level actionSuspending must not also be reported as a "command", since the path IS the
        // cli's own name here.
        assertEquals(
            "cli 'app' uses actionSuspending { }, which the synchronous entry points cannot drive; " +
                "call runSuspending(argv, terminal) from a coroutine instead",
            error.message,
        )
    }

    @Test
    fun `the refusal names the offending command rather than the root`() {
        val tree = cli("app") {
            command("plain") { action { Ok("a") } }
            command("fetch") { actionSuspending { Ok("b") } }
        }
        val error = assertFailsWith<IllegalArgumentException> {
            tree.run(arrayOf("plain"), RecordingTerminal())
        }
        assertEquals(
            "cli 'app': command 'app fetch' uses actionSuspending { }, which the synchronous entry " +
                "points cannot drive; call runSuspending(argv, terminal) from a coroutine instead",
            error.message,
        )
    }

    @Test
    fun `main refuses a cli that declares a suspending action rather than exiting`() {
        // The guard fires inside run() before platformExit ever runs, so this is testable without
        // actually terminating the test process.
        val tree = cli("app") { actionSuspending { Ok("x") } }
        assertFailsWith<IllegalArgumentException> { tree.main(emptyArray()) }
    }

    @Test
    fun `runActionSuspending drives a plain action just as well as a suspending one`() = runTest {
        val tree = cli("app") { action { Ok("sync") } }
        val execute = assertIs<Invocation.Execute>(
            assertIs<Result.Success<Invocation>>(tree.parse(emptyList())).value,
        )
        assertEquals(Result.Success("sync"), execute.runActionSuspending())
    }

    @Test
    fun `the refusal reports the full path through a depth three tree`() {
        val tree = cli("app") {
            command("remote") {
                command("add") { actionSuspending { Ok("a") } }
            }
        }
        val error = assertFailsWith<IllegalArgumentException> {
            tree.run(emptyArray(), RecordingTerminal())
        }
        assertTrue("app remote add" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `runAction refuses only the action in hand rather than the whole tree`() {
        // The aggregate would refuse this: the tree contains a suspending command. The resolved one is
        // sync and perfectly drivable, which is the shape a command-by-command migration lives in.
        val tree = cli("app") {
            command("plain") { action { Ok("a") } }
            command("fetch") { actionSuspending { Ok("b") } }
        }
        val execute = assertIs<Invocation.Execute>(
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("plain"))).value,
        )
        assertEquals(Result.Success("a"), execute.runAction())
    }

    @Test
    fun `runAction refuses a resolved suspending command`() {
        val tree = cli("app") {
            command("plain") { action { Ok("a") } }
            command("fetch") { actionSuspending { Ok("b") } }
        }
        val execute = assertIs<Invocation.Execute>(
            assertIs<Result.Success<Invocation>>(tree.parse(listOf("fetch"))).value,
        )
        val error = assertFailsWith<IllegalArgumentException> { execute.runAction() }
        assertTrue("fetch" in error.message.orEmpty(), error.message)
        assertTrue("runActionSuspending" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a suspending action that returns a CliError renders to stderr and exits non-zero`() = runTest {
        val tree = cli("app") {
            // A fail-only action can't infer T from Err (that would be Nothing); name it explicitly.
            actionSuspending<String> {
                delay(10)
                Err(CliError.Failure("fail", exitCode = 3))
            }
        }
        val t = RecordingTerminal()
        val code = tree.runSuspending(emptyArray(), t)
        assertEquals(3, code)
        assertEquals("error: fail\n", t.err.toString())
    }

    @Test
    fun `runSuspending renders a non-Execute invocation the same as the sync run`() = runTest {
        val tree = cli("app") {
            command("add") {
                argument("text")
                action { Ok("added") }
            }
        }

        val sync = RecordingTerminal()
        val syncCode = tree.run(arrayOf("add", "--help"), sync)

        val suspending = RecordingTerminal()
        val suspendingCode = tree.runSuspending(arrayOf("add", "--help"), suspending)

        assertEquals(0, syncCode)
        assertEquals(syncCode, suspendingCode)
        assertEquals(sync.out.toString(), suspending.out.toString())
        assertTrue("usage: app add <text>" in sync.out.toString(), sync.out.toString())
    }

    @Test
    fun `cancelling the caller's scope cancels a suspended action`() = runTest {
        val started = CompletableDeferred<Unit>()
        var completed = false
        val tree = cli("app") {
            actionSuspending {
                started.complete(Unit)
                delay(10_000)
                completed = true
                Ok("never")
            }
        }

        val job = launch { tree.runSuspending(emptyArray(), RecordingTerminal()) }
        started.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertFalse(completed, "the action resumed past a cancelled scope")
    }
}
