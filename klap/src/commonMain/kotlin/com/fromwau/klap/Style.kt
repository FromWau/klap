package com.fromwau.klap

/**
 * A colour or attribute you can apply to text inside an `action { }`. Combine them with `+`, and apply one
 * by calling it: `(bold + red)("failed")`.
 *
 * The palette is [black], [red], [green], [yellow], [blue], [magenta], [cyan], [white], plus the attributes
 * [bold], [dim], [italic] and [underline]. Styling resolves against the same colour switch as klap's own
 * help, so a piped or `--color=never` run prints plain text without your action branching on it.
 */
public class Style internal constructor(internal val codes: List<Int>)

/** Combines two [Style]s into one that opens both codes and closes with a single reset. */
public operator fun Style.plus(other: Style): Style = Style(codes + other.codes)

public val black: Style = Style(listOf(30))
public val red: Style = Style(listOf(31))
public val green: Style = Style(listOf(32))
public val yellow: Style = Style(listOf(33))
public val blue: Style = Style(listOf(34))
public val magenta: Style = Style(listOf(35))
public val cyan: Style = Style(listOf(36))
public val white: Style = Style(listOf(37))

public val bold: Style = Style(listOf(1))
public val dim: Style = Style(listOf(2))
public val italic: Style = Style(listOf(3))
public val underline: Style = Style(listOf(4))

private val ESC = Char(27).toString()

/** Wraps [text] in this style's ANSI SGR codes when [enabled] and there is something to apply; passes it through unchanged otherwise. */
internal fun Style.render(text: String, enabled: Boolean): String =
    if (enabled && codes.isNotEmpty()) "$ESC[${codes.joinToString(";")}m$text$ESC[0m" else text

/** The chrome's [ColorScope]: klap's own help and error output resolves a [Style] through this */
internal class Palette(private val enabled: Boolean) : ColorScope {
    override fun Style.invoke(block: () -> String): String = render(block(), enabled)
    override fun Style.invoke(text: String): String = render(text, enabled)
}

/**
 * The ability to apply a [Style], which [ActionScope] has. Take this as a receiver instead of the whole
 * action scope when a helper only formats text:
 *
 * ```kotlin
 * private fun ColorScope.warn(text: String) = (bold + yellow)("warning: $text")
 * ```
 */
@KlapDsl
public sealed interface ColorScope {
    public operator fun Style.invoke(block: () -> String): String
    public operator fun Style.invoke(text: String): String
}
