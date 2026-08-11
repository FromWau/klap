package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TerminalPolicyTest {

    @Test
    fun `color mode extraction covers forms and defaults`() {
        assertEquals(ColorMode.AUTO, listOf("build").colorMode())
        assertEquals(ColorMode.NEVER, listOf("--color=never", "build").colorMode())
        assertEquals(ColorMode.NEVER, listOf("--color", "never", "build").colorMode())
        assertEquals(ColorMode.ALWAYS, listOf("--color=always").colorMode())
        // lenient: parse() is the one place that reports an invalid/missing --color value.
        assertEquals(ColorMode.AUTO, listOf("--color=bogus").colorMode())
        assertEquals(ColorMode.AUTO, listOf("--color").colorMode())
    }

    @Test
    fun `color mode value prefix resolves only under abbreviation`() {
        // Unambiguous prefix, abbreviation on: resolves through the same resolveChoice parse() uses.
        assertEquals(ColorMode.ALWAYS, listOf("--color", "al").colorMode(infer = true))
        // "a" reaches both "auto" and "always"; lenient, so ambiguity falls back to AUTO, never errors.
        assertEquals(ColorMode.AUTO, listOf("--color", "a").colorMode(infer = true))
        // Abbreviation off: a prefix is just an unrecognized value, so it falls back to AUTO too.
        assertEquals(ColorMode.AUTO, listOf("--color", "al").colorMode(infer = false))
        // An exact spelling resolves regardless of the flag.
        assertEquals(ColorMode.ALWAYS, listOf("--color", "always").colorMode(infer = true))
        assertEquals(ColorMode.ALWAYS, listOf("--color", "always").colorMode(infer = false))
    }

    @Test
    fun `color always and never beat the env ladder`() {
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

    @Test
    fun `color value prefix agrees between parse and run`() {
        // parse() binds "al" to always via resolveChoice; run() must render with that same mode rather than
        // recompute AUTO from the still-abbreviated raw value, or the one line would bind one mode and paint
        // another.
        val tree = cli("app") {
            abbreviation = Abbreviation.Options
            command("go") { action { Ok("") } }
        }
        assertIs<Result.Success<Invocation>>(tree.parse(listOf("--color", "al", "--help")))

        val esc = Char(27)
        val recorder = RecordingTerminal()
        tree.run(arrayOf("--color", "al", "--help"), recorder)
        assertTrue(esc in recorder.out.toString(), recorder.out.toString())
    }
}
