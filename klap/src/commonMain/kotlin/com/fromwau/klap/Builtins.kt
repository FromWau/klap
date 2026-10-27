package com.fromwau.klap

import com.fromwau.klap.internal.render.BuiltinOptionHelp

/**
 * What `--help` covers. The GNU Coding Standards name `--help` and `--version` and no `--help-all`, so [ByCommand],
 * the default, documents the command `--help` is typed at. [ByTree] adds `--help-all` for the tree from there,
 * [All] makes `--help` itself print that tree, [Custom] prints the tool's own page, and [Off] removes `--help` and
 * its short, so the tool may claim both names.
 *
 * `--help` has no short unless you give one, as GNU's has none (`cp -h` is "invalid option -- 'h'"). A tool whose
 * `-h` is its help, as git's and pacman's is, names it: `Help.ByCommand(short = 'h')`.
 *
 * ```kotlin
 * cli("fleet") {
 *     builtins { help = Help.ByTree(allDescription = "show every command") }
 *     command("disk") { /* ... */ }
 * }
 * ```
 *
 * Only [ByTree] offers `--help-all`, and only on a tree with a visible subcommand; elsewhere the name is the tool's
 * own. On a CLI with no subcommand, [ByCommand], [ByTree] and [All] print the same one page.
 */
public sealed interface Help {
    /**
     * `--help` prints the help of the command it is typed at, as `git <cmd> --help` and coreutils do.
     *
     * @param description the words beside `--help` in help and Tab, klap's own when null.
     * @param short the one-letter alias of `--help`, none by default.
     */
    public data class ByCommand(val description: String? = null, val short: Char? = null) : Help

    /**
     * `--help` prints the command it is typed at, and `--help-all` that command and every one under it.
     *
     * @param description the words beside `--help` in help and Tab, klap's own when null.
     * @param short the one-letter alias of `--help`, none by default; `--help-all` has none.
     * @param allDescription the words beside `--help-all` in help and Tab, klap's own when null.
     */
    public data class ByTree(
        val description: String? = null,
        val short: Char? = null,
        val allDescription: String? = null,
    ) : Help

    /**
     * `--help` prints the command it is typed at and every one under it, as does a group run bare.
     *
     * @param description the words beside `--help` in help and Tab, klap's own when null.
     * @param short the one-letter alias of `--help`, none by default.
     */
    public data class All(val description: String? = null, val short: Char? = null) : Help

    /**
     * `--help` prints [page] as written at every command, as does a group run bare; `--help --json` still prints the
     * generated help as data.
     *
     * @param description the words beside `--help` in help and Tab, klap's own when null.
     * @param short the one-letter alias of `--help`, none by default.
     */
    public data class Custom(val page: String, val description: String? = null, val short: Char? = null) : Help

    /** No `--help` at all. */
    public data object Off : Help
}

/**
 * Whether one of klap's built-ins that only reports the tree is offered: `--json`, `--color`, `completion` and
 * `docs`. [On.description] is the words beside it in help and Tab, klap's own when null; [Off] removes it, so the
 * tool may claim its name.
 */
public sealed interface Builtin {
    /** Offered, described by [description] or by klap's words. */
    public data class On(val description: String? = null) : Builtin

    /** Not offered, and its name free for the tool's own input. */
    public data object Off : Builtin
}

/**
 * The tool's version, and with it `--version`. [Off], the default, means the tool has none and so no `--version`.
 * [Of] gives the [Of.number] the docs and `--json` read, the spellings that print it, the words beside them
 * ([Of.description], klap's own when null), and what they print: [Of.text] as written, or `<name> <number>` when null
 * (`pacman --version` prints a banner of its own).
 *
 * The spellings follow a flag's: one short alone is that short only (`Version.Of("10.6p1", short = 'V')`, ssh's
 * `-V`), a long and a short are both (`Version.Of("7.1.0", long = "version", short = 'V')`, pacman's), and no
 * spelling given is `--version`. They answer at every depth of the tree; [Of.rootOnly] keeps them on the root's own
 * segment, before the first subcommand, where git reads its `-v` and `--version`, and frees them for the commands
 * below it (`git add -v` is add's verbose).
 */
public sealed interface Version {
    /** A version, printed by `--version`. */
    public data class Of(
        val number: String,
        val long: String? = "version",
        val short: Char? = null,
        val description: String? = null,
        val text: String? = null,
        val rootOnly: Boolean = false,
    ) : Version {
        /** A version printed by its [short] alone, with no long: ssh's `-V`. */
        public constructor(
            number: String,
            short: Char,
            description: String? = null,
            text: String? = null,
            rootOnly: Boolean = false,
        ) : this(number, null, short, description, text, rootOnly)
    }

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
    /** What `--help` covers, [Help.ByCommand] by default; see [Help]. */
    public var help: Help = Help.ByCommand()

    /** The `--json` output switch, and the `Globals.json` an action reads it back through. */
    public var json: Builtin = Builtin.On()

    /**
     * The `--color[=auto|always|never]` rendering modifier, GNU's `--color[=WHEN]` as ls declares it: a bare
     * `--color` means `always`, and the word after it is no value of it.
     */
    public var color: Builtin = Builtin.On()

    /**
     * The `completion <shell>` subcommand (a dispatcher) or `--completion <shell>` option (a single-command root),
     * and the hidden `__complete` command a completion script calls on each Tab. [Builtin.Off] removes both, so a
     * first word `__complete` is your tool's own, as `head __complete` reads a file of that name. With it off, a
     * generated script, also one from [Cli.renderCompletion], has nothing to call: its `__complete` line reaches
     * your tool as ordinary words.
     */
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
    // Help.All: --help prints the tree from where it is typed.
    val helpTree: Boolean = false,
    val helpShort: Char? = null,
    // Null for a version printed by its short alone.
    val versionLong: String? = "version",
    val versionShort: Char? = null,
    // A Version.Of text, printed in place of `<name> <number>`.
    val versionPrint: String? = null,
    // Version.Of.rootOnly: the version's spellings belong to the root's own segment.
    val versionRootOnly: Boolean = false,
    // Help.ByTree's --help-all, off on a tree with no visible subcommand, which has nothing for it to expand.
    val helpAll: Boolean = false,
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
    val versioned = version as? Version.Of
    fun Builtin.text(default: String) = (this as? Builtin.On)?.description ?: default
    val (helpShort, helpDescription) = when (val help = help) {
        is Help.ByCommand -> help.short to help.description
        is Help.ByTree -> help.short to help.description
        is Help.All -> help.short to help.description
        is Help.Custom -> help.short to help.description
        Help.Off -> null to null
    }
    return Builtins(
        json = json is Builtin.On,
        color = color is Builtin.On,
        completion = completion is Builtin.On,
        docs = docs is Builtin.On,
        help = help !is Help.Off,
        helpPage = (help as? Help.Custom)?.page,
        helpTree = help is Help.All,
        helpShort = helpShort,
        versionLong = if (versioned != null) versioned.long else "version",
        versionShort = versioned?.short,
        versionPrint = versioned?.text,
        versionRootOnly = versioned?.rootOnly ?: false,
        helpAll = help is Help.ByTree,
        helpText = helpDescription ?: BuiltinOptionHelp.HELP,
        helpAllText = (help as? Help.ByTree)?.allDescription ?: BuiltinOptionHelp.HELP_ALL,
        versionText = versioned?.description ?: BuiltinOptionHelp.VERSION,
        jsonText = json.text(BuiltinOptionHelp.JSON),
        colorText = color.text(BuiltinOptionHelp.COLOR),
        completionText = completion.text(BuiltinOptionHelp.COMPLETION),
        docsText = docs.text(BuiltinOptionHelp.DOCS),
    )
}
