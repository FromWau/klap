package com.fromwau.klap.fixture

import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test
import kotlin.test.assertNotEquals

class CompletionContextTest {

    @Test
    fun `files after a head never compare equal to a word spelled like their rendering`() {
        assertNotEquals(Suggestion.Word("--out=<files>").value, Suggestion.Files("--out=").value)
    }
}
