package com.fromwau.klap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonConcurrencyTest {

    @Test
    fun `one tree serves forwarded runs in parallel as it serves them one at a time`() = runBlocking {
        val tree = tomlShapedCli()
        val runs = List(16) { everyOutputKind }.flatten()
        suspend fun forward(run: Pair<List<String>, String>) =
            tree.forwarded(run.first, colourTty, stdin = run.second.encodeToByteArray())
        val alone = runs.map { forward(it) }
        val together = withContext(Dispatchers.Default) {
            runs.map { async { forward(it) } }.awaitAll()
        }
        assertEquals(alone, together)
    }
}
