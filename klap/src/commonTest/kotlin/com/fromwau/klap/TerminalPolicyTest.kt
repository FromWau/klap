package com.fromwau.klap

import com.fromwau.klap.internal.platform.ansiEnabled
import com.fromwau.klap.internal.platform.resolveColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun envOf(vars: Map<String, String>): (String) -> String? = { vars[it] }

class TerminalPolicyTest {

    @Test
    fun ansi_noColorWinsOverForceColorAndTty() {
        // NO_COLOR beats everything, even on a real tty with FORCE_COLOR set.
        val env = envOf(mapOf("NO_COLOR" to "1", "FORCE_COLOR" to "1"))
        assertFalse(ansiEnabled(isTty = true, env = env))
    }

    @Test
    fun ansi_forceColorEnablesWithoutTty() {
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("FORCE_COLOR" to "1"))))
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("CLICOLOR_FORCE" to "1"))))
    }

    @Test
    fun ansi_dumbTermDisablesEvenOnTty() {
        assertFalse(ansiEnabled(isTty = true, env = envOf(mapOf("TERM" to "dumb"))))
    }

    @Test
    fun ansi_followsTtyByDefault() {
        assertTrue(ansiEnabled(isTty = true, env = envOf(emptyMap())))
        assertFalse(ansiEnabled(isTty = false, env = envOf(emptyMap())))
    }

    @Test
    fun ansi_forceValueZeroDoesNotForce() {
        // "0" is the opt-out value for both conventions, so it must not inject color into piped output.
        assertFalse(ansiEnabled(isTty = false, env = envOf(mapOf("CLICOLOR_FORCE" to "0"))))
        assertFalse(ansiEnabled(isTty = false, env = envOf(mapOf("FORCE_COLOR" to "0"))))
    }

    @Test
    fun ansi_forceNonZeroStillForcesWithoutTty() {
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("CLICOLOR_FORCE" to "1"))))
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("FORCE_COLOR" to "1"))))
    }

    @Test
    fun ansi_noColorWinsOverCliColorForce() {
        assertFalse(ansiEnabled(isTty = false, env = envOf(mapOf("NO_COLOR" to "1", "CLICOLOR_FORCE" to "1"))))
    }

    @Test
    fun ansi_emptyNoColorNoLongerDisables() {
        // Per the NO_COLOR spec (no-color.org), the variable disables color only when present AND a
        // non-empty string; an empty value must be treated as not-set, so auto still follows the tty.
        assertTrue(ansiEnabled(isTty = true, env = envOf(mapOf("NO_COLOR" to ""))))
    }

    @Test
    fun ansi_nonEmptyNoColorStillDisables() {
        assertFalse(ansiEnabled(isTty = true, env = envOf(mapOf("NO_COLOR" to "1"))))
    }

    @Test
    fun columns_fromEnvWhenPositive() {
        assertEquals(120, resolveColumns(envOf(mapOf("COLUMNS" to "120"))))
    }

    @Test
    fun columns_fallsBackTo80WhenUnsetMalformedOrNonPositive() {
        assertEquals(80, resolveColumns(envOf(emptyMap())))
        assertEquals(80, resolveColumns(envOf(mapOf("COLUMNS" to ""))))
        assertEquals(80, resolveColumns(envOf(mapOf("COLUMNS" to "abc"))))
        assertEquals(80, resolveColumns(envOf(mapOf("COLUMNS" to "0"))))
    }

    @Test
    fun columns_usesDetectedWhenEnvUnset() {
        assertEquals(100, resolveColumns(envOf(emptyMap()), detected = 100))
    }

    @Test
    fun columns_envOverridesDetected() {
        assertEquals(120, resolveColumns(envOf(mapOf("COLUMNS" to "120")), detected = 100))
    }

    @Test
    fun columns_fallsBackTo80WhenDetectedIsBadOrAbsent() {
        assertEquals(80, resolveColumns(envOf(emptyMap()), detected = 0))
        assertEquals(80, resolveColumns(envOf(emptyMap()), detected = null))
    }

    @Test
    fun colorModeExtractionCoversFormsAndDefaults() {
        assertEquals(ColorMode.AUTO, listOf("build").colorMode())
        assertEquals(ColorMode.NEVER, listOf("--color=never", "build").colorMode())
        assertEquals(ColorMode.NEVER, listOf("--color", "never", "build").colorMode())
        assertEquals(ColorMode.ALWAYS, listOf("--color=always").colorMode())
        // lenient: parse() is the one place that reports an invalid/missing --color value.
        assertEquals(ColorMode.AUTO, listOf("--color=bogus").colorMode())
        assertEquals(ColorMode.AUTO, listOf("--color").colorMode())
    }

    @Test
    fun colorAlwaysAndNeverBeatTheEnvLadder() {
        // RecordingTerminal always reports ansi=false, so --color=always proves the mode overrides the
        // terminal's own reading; --color=never confirms the off path is equally explicit, not incidental.
        val tree = cli("app") { command("go") { action { Ok("") } } }
        val esc = Char(27)

        val never = RecordingTerminal()
        tree.run(arrayOf("--color=never", "--help"), never)
        assertFalse(esc in never.out.toString(), never.out.toString())

        val always = RecordingTerminal()
        tree.run(arrayOf("--color=always", "--help"), always)
        assertTrue(esc in always.out.toString(), always.out.toString())
    }
}
