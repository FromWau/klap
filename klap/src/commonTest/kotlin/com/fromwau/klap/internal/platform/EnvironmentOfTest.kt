package com.fromwau.klap.internal.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentOfTest {

    @Test
    fun `an entry without an equals sign is no variable and the first of a name set twice wins`() {
        val entries = sequenceOf("NOEQ", "A=b=c", "A=second", "E=")
        assertEquals(mapOf("A" to "b=c", "E" to ""), environmentOf(entries))
    }
}
