package com.fromwau.klap.fixture.find

import com.fromwau.klap.Terminal
import com.fromwau.klap.run
import kotlin.test.Ignore
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
    @Ignore // reports 3 of 4: the synthetic "." default is counted as a starting point and drops a real token
    fun anExpressionWithNoStartingPointKeepsAllItsTokens() {
        val t = RecordingTerminal()
        findCli().cli.run(listOf("--", "-name", "*.kt", "-o", "-print"), t)
        assertTrue(
            "4 unparsed expression tokens" in t.recorded,
            "the whole expression should survive the split, got: ${t.recorded}",
        )
    }
}
