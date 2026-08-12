package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The shape of the public surface: every entry point takes a [Collection], and every collection handed
 * back is a [List].
 *
 * Liberal in, specific out. A caller already holding some collection passes it straight in, and a caller
 * reading a bound value gets something ordered and indexable rather than having to re-establish that.
 */
class PublicCollectionApiTest {

    private data class Args(val lines: String?, val files: List<String>)

    private fun greet() = cliOf("head") {
        val lines = option("--lines", "-n")
        val files = argument("file").multiple()
        action { Ok("stub") }
        projection { Args(lines(), files()) }
    }

    @Test
    fun `parse takes any collection and the array shape main hands you`() {
        val expected = Ok(Args(lines = "20", files = listOf("f")))
        // A List, the usual case.
        assertEquals(expected, greet().parse(listOf("-n", "20", "f")))
        // A Set. `setOf` is a LinkedHashSet, so the insertion order argv depends on is preserved.
        assertEquals(expected, greet().parse(setOf("-n", "20", "f")))
        // An ArrayDeque, to show it is the interface that is accepted and not two blessed implementations.
        assertEquals(expected, greet().parse(ArrayDeque(listOf("-n", "20", "f"))))
        // An Array is not a Collection, so it stays a separate overload.
        assertIs<Result.Success<Invocation>>(greet().cli.parse(arrayOf("-n", "20", "f")))
    }

    @Test
    fun `run takes a collection and an array`() {
        val cli = greet().cli
        assertEquals(0, cli.run(listOf("f"), RecordingTerminal()))
        assertEquals(0, cli.run(setOf("f"), RecordingTerminal()))
        assertEquals(0, cli.run(arrayOf("f"), RecordingTerminal()))
    }

    @Test
    fun `runSuspending takes a collection and an array`() = runTest {
        val cli = greet().cli
        assertEquals(0, cli.runSuspending(listOf("f"), RecordingTerminal()))
        assertEquals(0, cli.runSuspending(setOf("f"), RecordingTerminal()))
        assertEquals(0, cli.runSuspending(arrayOf("f"), RecordingTerminal()))
    }

    /**
     * Not executed: [main] exits the process. Naming both overloads in a typed position is the assertion —
     * this only compiles while both exist.
     */
    @Suppress("unused")
    private fun bothMainOverloadsResolve(cli: Cli) {
        val fromCollection: (Collection<String>) -> Unit = { cli.main(it) }
        val fromArray: (Array<String>) -> Unit = { cli.main(it) }
    }

    @Test
    fun `aliases accepts a collection going in and reads back as a list`() {
        val cli = cli("git") {
            command("status") {
                aliases = setOf("st", "stat")
                action { Ok("") }
            }
        }
        val status = cli.subcommands.single { it.name == "status" }
        // Set in, List out.
        assertEquals(listOf("st", "stat"), status.aliases)
        assertIs<List<String>>(status.aliases)
        assertIs<List<Command>>(cli.subcommands)
    }

    @Test
    fun `a variadic binds back as a list`() {
        val parsed = greet().parse(listOf("a", "b", "c"))
        val files = (parsed as Result.Success).value?.files
        assertIs<List<String>>(files)
        assertEquals(listOf("a", "b", "c"), files)
    }
}
