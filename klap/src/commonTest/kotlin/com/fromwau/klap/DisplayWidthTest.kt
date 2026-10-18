package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.klap.internal.render.HelpStyle
import com.fromwau.klap.internal.render.displayWidth
import com.fromwau.klap.internal.render.helpText
import com.fromwau.klap.internal.render.wrap
import kotlin.test.Test
import kotlin.test.assertEquals

/** Help measures text in terminal columns: a wide character takes two, a combining mark none. */
class DisplayWidthTest {

    private val acute = Char(0x301).toString()

    // U+1D167, a combining mark outside the basic plane, written as its surrogate pair.
    private val astralCombining = charArrayOf(Char(0xD834), Char(0xDD67)).concatToString()

    @Test
    fun `ascii text takes a column a character`() {
        assertEquals(6, displayWidth("--help"))
    }

    @Test
    fun `a wide or full-width character takes two columns`() {
        assertEquals(4, displayWidth("日本"))
        assertEquals(4, displayWidth("ＡＢ"))
    }

    @Test
    fun `an emoji takes two columns`() {
        assertEquals(2, displayWidth("😀"))
        assertEquals(2, displayWidth("✅"))
    }

    @Test
    fun `a combining mark takes no column`() {
        assertEquals(4, displayWidth("cafe$acute"))
        assertEquals(1, displayWidth("e$astralCombining"))
    }

    @Test
    fun `a zero-width character takes no column`() {
        assertEquals(2, displayWidth("a${Char(0x200B)}b"))
    }

    @Test
    fun `wrap counts a wide character as two columns`() {
        assertEquals(listOf("日本", "語"), wrap("日本 語", width = 4))
    }

    @Test
    fun `wrap counts an emoji as two columns`() {
        assertEquals(listOf("✅✅", "ab"), wrap("✅✅ ab", width = 5))
    }

    @Test
    fun `wrap counts a combining mark as no column`() {
        assertEquals(listOf("cafe$acute bar"), wrap("cafe$acute bar", width = 8))
    }

    /** The column each row's description starts at, where the help of [placeholders]' options begins. */
    private fun descriptionColumns(style: HelpStyle, vararg placeholders: String): Set<Int> {
        val cmd = cli("app") {
            placeholders.forEachIndexed { i, placeholder ->
                option(long = "o$i", help = "row$i").placeholder(placeholder)
            }
            action { Ok("") }
        }
        val esc = Regex("${Char(27)}\\[[0-9;]*m")
        return cmd
            .helpText(style = style)
            .lines()
            .filter { Regex("row[0-9]") in it }
            .map { line -> displayWidth(line.replace(esc, "").substringBefore("row")) }
            .toSet()
    }

    @Test
    fun `the signature column pads a wide placeholder to the columns of the others`() {
        assertEquals(1, descriptionColumns(HelpStyle.PLAIN, "日本", "ABCDEF").size)
    }

    @Test
    fun `the signature column pads a combining placeholder and an emoji one`() {
        assertEquals(1, descriptionColumns(HelpStyle.PLAIN, "CAFE$acute", "✅✅", "ABCD").size)
    }

    @Test
    fun `wrapped and colored help pad by columns too`() {
        assertEquals(1, descriptionColumns(HelpStyle(columns = 80, color = false), "日本", "ABCDEF").size)
        assertEquals(1, descriptionColumns(HelpStyle(columns = 0, color = true), "日本", "ABCDEF").size)
    }
}
