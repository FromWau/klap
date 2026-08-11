package com.fromwau.klap.fixture.find

import com.fromwau.kern.terminal.Terminal
import com.fromwau.klap.run
import kotlin.test.Test
import kotlin.test.assertTrue

/** Collects rendered text so a test can assert on it without touching real stdio. */
private class RecordingTerminal : Terminal {
    val recorded = StringBuilder()
    override fun out(text: String) { recorded.append(text) }
    override fun err(text: String) { recorded.append(text) }
}

class FindExpressionSplitTest {

    @Test
    fun `an expression with no starting point keeps all its tokens`() {
        val t = RecordingTerminal()
        findCli().cli.run(listOf("--", "-name", "*.kt", "-o", "-print"), t)
        assertTrue(
            "4 unparsed expression tokens" in t.recorded,
            "the whole expression should survive the split, got: ${t.recorded}",
        )
    }
}
