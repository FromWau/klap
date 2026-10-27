package com.fromwau.klap.io

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Arg
import com.fromwau.klap.ConverterScope
import com.fromwau.klap.Opt
import kotlinx.io.files.Path

/**
 * A path as the user typed it, for a tool that tells `cp -r link/` (the directory) from `cp -r link` (the link).
 *
 * ```kotlin
 * val source = argument("source").path()
 * action {
 *     val from = source()
 *     if (from.endsWithSlash) copyContents(from.path) else copyEntry(from.path)
 * }
 * ```
 *
 * Two paths are equal when their [text] is, so `a/` differs from `a` and `a//b` from `a/b`; it prints as [text].
 *
 * @property text the word exactly as given on the command line.
 */
public class CliPath(public val text: String) {
    /** [text] as a kotlinx-io [Path], which drops a trailing slash (`a/` is `a`) and on the JVM a doubled one. */
    public val path: Path = Path(text)

    /** Whether [text] ends with `/`, as `a/`, `./a/` and `/` do; a Windows `\` does not count. */
    public val endsWithSlash: Boolean get() = text.endsWith('/')

    override fun equals(other: Any?): Boolean = other is CliPath && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text
}

/**
 * Reads this operand as a [CliPath], keeping the word as typed, trailing slash included, and completing it as a
 * file in every shell, as `.file()` does. Every word is a path, so nothing is refused here; whether the file
 * exists is for your action to find out.
 */
context(scope: ConverterScope)
public fun Arg<String>.path(): Arg<CliPath> = with(scope) { file().convert { Ok(CliPath(it)) } }

/** Reads this option's value as a [CliPath], as an operand's [path] is read, completing it as a file. */
context(scope: ConverterScope)
public fun Opt<String?>.path(): Opt<CliPath?> = with(scope) { file().convert { Ok(CliPath(it)) } }
