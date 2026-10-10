package com.fromwau.klap

import com.fromwau.klap.internal.render.BuiltinOptionHelp

/**
 * What `--help` does. [Generate], the default, prints the page klap builds from the tree; [Custom] prints the tool's
 * own [Custom.page] as written; [Off] removes `--help` and its short, so the tool may claim both names.
 *
 * [Generate.description] and [Custom.description] are the words beside `--help` in help and Tab, klap's own when
 * null, and `short` is its one-letter alias, none when null.
 */
public sealed interface Help {
    /** Print the help klap generates. */
    public data class Generate(val description: String? = null, val short: Char? = 'h') : Help

    /** Print [page] as written, for every `--help` on the line. */
    public data class Custom(val page: String, val description: String? = null, val short: Char? = 'h') : Help

    /** No `--help` at all. */
    public data object Off : Help
}

/**
 * Whether one of klap's built-ins that only reports the tree is offered: `--help-all`, `--json`, `--color`,
 * `completion` and `docs`. [On.description] is the words beside it in help and Tab, klap's own when null; [Off]
 * removes it, so the tool may claim its name.
 */
public sealed interface Builtin {
    /** Offered, described by [description] or by klap's words. */
    public data class On(val description: String? = null) : Builtin

    /** Not offered, and its name free for the tool's own input. */
    public data object Off : Builtin
}

/**
 * The tool's version, and with it `--version`. [Off], the default, means the tool has none and so no `--version`.
 * [Of] gives the [Of.number] the docs and `--json` read, the spellings `--version` answers to ([Of.long], and
 * [Of.short] when set), the words beside it ([Of.description], klap's own when null), and what it prints:
 * [Of.text] as written, or `<name> <number>` when null (`pacman --version` prints a banner of its own).
 */
public sealed interface Version {
    /** A version, printed by `--version`. */
    public data class Of(
        val number: String,
        val long: String = "version",
        val short: Char? = null,
        val description: String? = null,
        val text: String? = null,
    ) : Version

    /** No version and no `--version`. */
    public data object Off : Version
}

/**
 * Which of klap's built-ins this CLI offers, one value each; every one defaults to on. An `Off` removes it entirely:
 * its name stops being reserved (so the app can declare its own input under it), your CLI stops recognizing it, and
 * `--help`, the generated docs and tab completion stop advertising it.
 *
 * ```kotlin
 * cli("curl") {
 *     builtins { json = Builtin.Off }
 *     option(short = 'j', long = "json", help = "post this JSON body")
 * }
 * ```
 *
 * The version is the root's own `version`, a [Version], since it holds the number as well as the built-in.
 */
@KlapDsl
public class BuiltinsBuilder internal constructor() {
    /** What `--help` does; see [Help]. */
    public var help: Help = Help.Generate()

    /** `--help-all`, the help of every command in the tree; offered only where there is a subcommand to expand. */
    public var helpAll: Builtin = Builtin.On()

    /** The `--json` output switch, and the `Globals.json` an action reads it back through. */
    public var json: Builtin = Builtin.On()

    /** The `--color <auto|always|never>` rendering modifier. */
    public var color: Builtin = Builtin.On()

    /** The `completion <shell>` subcommand (a dispatcher) or `--completion <shell>` option (a single-command root). */
    public var completion: Builtin = Builtin.On()

    /** The `docs <format>` subcommand (a dispatcher) or `--docs <format>` option (a single-command root). */
    public var docs: Builtin = Builtin.On()
}

/**
 * The resolved, immutable form of a [BuiltinsBuilder], carried by the [Cli] and threaded to every parse and
 * render seam that would otherwise inject a built-in unconditionally. [DEFAULT] is what a CLI that never
 * calls `builtins { }` resolves to, and the fallback wherever a seam is reached without a root to ask.
 */
internal data class Builtins(
    val json: Boolean = true,
    val color: Boolean = true,
    val completion: Boolean = true,
    val docs: Boolean = true,
    // Off with Help.Off, which frees --help and its short.
    val help: Boolean = true,
    // A Help.Custom page, printed in place of the generated one.
    val helpPage: String? = null,
    val helpShort: Char? = 'h',
    val versionLong: String = "version",
    val versionShort: Char? = null,
    // A Version.Of text, printed in place of `<name> <number>`.
    val versionPrint: String? = null,
    // Off where declined, and also on a tree with no visible subcommand, which has nothing for it to expand.
    val helpAll: Boolean = true,
    val helpText: String = BuiltinOptionHelp.HELP,
    val helpAllText: String = BuiltinOptionHelp.HELP_ALL,
    val versionText: String = BuiltinOptionHelp.VERSION,
    val jsonText: String = BuiltinOptionHelp.JSON,
    val colorText: String = BuiltinOptionHelp.COLOR,
    val completionText: String = BuiltinOptionHelp.COMPLETION,
    val docsText: String = BuiltinOptionHelp.DOCS,
) {
    companion object {
        val DEFAULT: Builtins = Builtins()
    }
}

internal fun BuiltinsBuilder.resolve(version: Version): Builtins {
    val generated = help as? Help.Generate
    val custom = help as? Help.Custom
    val versioned = version as? Version.Of
    fun Builtin.text(default: String) = (this as? Builtin.On)?.description ?: default
    return Builtins(
        json = json is Builtin.On,
        color = color is Builtin.On,
        completion = completion is Builtin.On,
        docs = docs is Builtin.On,
        help = help !is Help.Off,
        helpPage = custom?.page,
        helpShort = generated?.short ?: custom?.short,
        versionLong = versioned?.long ?: "version",
        versionShort = versioned?.short,
        versionPrint = versioned?.text,
        helpAll = helpAll is Builtin.On,
        helpText = generated?.description ?: custom?.description ?: BuiltinOptionHelp.HELP,
        helpAllText = helpAll.text(BuiltinOptionHelp.HELP_ALL),
        versionText = versioned?.description ?: BuiltinOptionHelp.VERSION,
        jsonText = json.text(BuiltinOptionHelp.JSON),
        colorText = color.text(BuiltinOptionHelp.COLOR),
        completionText = completion.text(BuiltinOptionHelp.COMPLETION),
        docsText = docs.text(BuiltinOptionHelp.DOCS),
    )
}
