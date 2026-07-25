package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals

// ActionScope owns the style operators; an empty value map is enough, these tests never read an accessor.
private fun actionScope(colorEnabled: Boolean): ActionScope = ActionScope(emptyMap(), colorEnabled)
private val ESC = Char(27).toString()

class StyleTest {

    @Test
    fun paletteResolvesBothOperatorFormsThroughItsOwnSwitch() {
        // The chrome's ColorScope. It exists so klap's own output applies a Style the same way an action's
        // does; if it ever stopped honouring its flag, --color=never would leak escapes into help output.
        with(Palette(enabled = true)) {
            assertEquals("${ESC}[33mhi${ESC}[0m", yellow("hi"))
            assertEquals("${ESC}[1mhi${ESC}[0m", bold { "hi" })
        }
        with(Palette(enabled = false)) {
            assertEquals("hi", yellow("hi"))
            assertEquals("hi", bold { "hi" })
        }
    }

    @Test
    fun renderWrapsWhenEnabledAndPassesThroughWhenNot() {
        assertEquals("${ESC}[33mhi${ESC}[0m", yellow.render("hi", true))
        assertEquals("hi", yellow.render("hi", false))
    }

    @Test
    fun plusMergesCodesIntoOneOpenOneReset() {
        assertEquals("${ESC}[1;33mhi${ESC}[0m", (bold + yellow).render("hi", true))
    }

    @Test
    fun invokeBlockAndStringResolveThroughScopeEnabled() {
        with(actionScope(true)) {
            assertEquals("${ESC}[33mok${ESC}[0m", yellow { "ok" })
            assertEquals("${ESC}[1mok${ESC}[0m", bold("ok"))
        }
        with(actionScope(false)) {
            assertEquals("ok", yellow { "ok" })
        }
    }
}
