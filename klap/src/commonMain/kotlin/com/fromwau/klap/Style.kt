package com.fromwau.klap

/** A composable ANSI SGR style: an ordered list of codes opened together and closed by a single reset. */
public class Style internal constructor(internal val codes: List<Int>)

/** Combines two styles into one that opens both codes and closes with a single reset. */
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

/**
 * The chrome's [ColorScope]: klap's own help and error output resolves a [Style] through this, exactly as a
 * consumer's action resolves one through [ActionScope]. Two surfaces, one mechanism — the alternative is a
 * second hand-rolled `Style.render(text, flag)` path that has to be kept in step by eye.
 *
 * Internal, and declared in this file because [ColorScope] is sealed: a subtype must sit in the same package.
 */
internal class Palette(private val enabled: Boolean) : ColorScope {
    override fun Style.invoke(block: () -> String): String = render(block(), enabled)
    override fun Style.invoke(text: String): String = render(text, enabled)
}

/**
 * DSL receiver capability for a scope that resolves a [Style] against a single enabled/disabled switch: the
 * [invoke] operators let a style wrap text (or a lazily built block) directly, e.g. `yellow("warn")` or
 * `red { message }`, so callers never branch on the switch themselves. An implementor delegates both to
 * `Style.render(text, switch)`; the block form always evaluates its block, and the switch decides only
 * whether ANSI codes are emitted around the result.
 *
 * A sealed interface with no state, not a base class: [ActionScope] must also extend [ValueScope] (which
 * carries an `internal` member, so it cannot itself be an interface) and Kotlin allows one superclass. The
 * switch stays off this type — an `internal` member is illegal in an interface, and a public one would
 * widen the API for nothing — so each implementor resolves the render against its own switch.
 */
@KlapDsl
public sealed interface ColorScope {
    public operator fun Style.invoke(block: () -> String): String
    public operator fun Style.invoke(text: String): String
}
