package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import kotlinx.serialization.Serializable

// A trimmed copy of kotoml's command tree (kotoml/app Main.kt): its builtins, spellings, help, examples, exits,
// constraints and Tab providers, with actions that answer from an in-memory document instead of reading TOML.
// Each command is a CommandSpec, as a daemon assembles one tree from the parts its modules contribute.

internal enum class TomlExit(
    override val code: Int,
    override val meaning: String,
    override val success: Boolean = false,
) : Exit {
    NothingFound(3, "nothing found where the filter or path looks"),
    NotFormatted(4, "--check finds the file not formatted", success = true),
    NotToml(1, "a file could not be read or is not valid TOML"),
}

internal enum class StyleChoice(val word: String) {
    Default("default"),
    Aligned("aligned"),
}

internal enum class LayoutChoice(val word: String) {
    Header("header"),
    Dotted("dotted"),
    Inline("inline"),
    MultilineInline("multiline-inline"),
}

/** The paths each file holds, as the providers and actions read them; a file not listed holds none. */
internal val tomlDocuments: Map<String, List<String>> = mapOf(
    "config.toml" to listOf(".server", ".server.host", ".server.port", ".servers", ".name"),
)

@Serializable
internal data class Selection(val path: String, val items: List<String>)

@Serializable
internal data class CheckedFile(val path: String, val valid: Boolean)

private fun CommandBuilder.specOption() = option(long = "toml", help = "the TOML version to accept")
    .choice("1.0", "1.1")
    .default("1.1")

private fun CommandBuilder.fileOption(help: String = "the TOML file") = option(
    short = 'f',
    long = "file",
    help = "$help; `-` is standard input, and a leading ~ is the home directory",
)
    .placeholder("file")
    .file()
    .required()

private fun CommandBuilder.inPlaceFlag() =
    flag(short = 'i', long = "in-place", help = "write the edited document back into the file")

private fun CommandBuilder.formatOption() = option(
    long = "format",
    help = "then restyle the whole document in kotoml's style: default, or aligned with each block of keys lined up",
)
    .placeholder("style")
    .enum<StyleChoice>(spelling = { it.word })
    .optionalValue(StyleChoice.Default.word)

private fun CommandBuilder.strictFlag() = flag(
    long = "strict",
    help = "change no layout to hold a comment, such as braces across lines or a table under a header; refuse instead",
)

private fun CommandBuilder.diffFlag() =
    flag(long = "diff", help = "print the change as diff -u does instead of the document; -i still writes the file")

private fun CompletionScope.offerPaths(file: String?) {
    candidates(tomlDocuments[file].orEmpty())
}

internal object GetSpec : CommandSpec {
    override val name = "get"

    override fun CommandBuilder.build() {
        help = "print what a jq path selects"
        example("kotoml get -f config.toml '.servers[0].port'", "one value")
        example(
            "kotoml get -f config.toml '.servers[] | .host' --json",
            "the host of every server, as one JSON array where jq prints one value per line",
        )
        example("kotoml get -f config.toml '.server.timeout // 30'", "the timeout, or 30 where the file sets none")
        val file = fileOption()
        specOption()
        val filter = argument(
            "filter",
            "a jq path: ., .a.b, .\"key\", .[0], .[], select(.a > 1), select(has(\"k\")), keys, | between them; " +
                "a // b gives b where a finds no key or index",
        )
            .completeWith { offerPaths(file.orNull()) }
        val raw = flag(
            short = 'r',
            long = "raw",
            help = "print strings without quotes, and TOML without comments; not with --json",
        )
        val jsonLines = flag(
            long = "json-lines",
            help = "print each result as compact JSON on a line of its own, where --json prints a stream as " +
                "one array; not with --json",
        )
        atMostOne(raw, jsonLines)
        exits(TomlExit.NothingFound)
        action(
            human = { it.items.joinToString("\n") },
            exit = { if (it.items.isEmpty()) TomlExit.NothingFound else null },
        ) {
            if (raw() && json) return@action Err(CliError.MutuallyExclusive(listOf("-r/--raw", "--json")))
            val items = tomlDocuments[file()].orEmpty().filter { it.startsWith(filter()) }
            Ok(Selection(file(), items))
        }
    }
}

internal object SetSpec : CommandSpec {
    override val name = "set"

    override fun CommandBuilder.build() {
        help = "store a TOML value at a path, keeping every comment, and print the document"
        example("kotoml set -f config.toml .server.port 9000", "the document with the new port")
        example("kotoml set -i -f config.toml .name '\"api\"'", "a string, written back into the file")
        val file = fileOption()
        specOption()
        inPlaceFlag()
        val format = formatOption()
        val strict = strictFlag()
        atMostOne(format, strict)
        diffFlag()
        flag(long = "force", help = "store the value or layout even when the comments of what it replaces go")
        val raw = flag(short = 'r', long = "raw", help = "store the value as a string as typed, no quotes needed")
        option(
            short = 'c',
            long = "comment",
            help = "end the line with this comment, `# ` written before it unless it starts with `#`; on an array of " +
                "tables or a dotted table, a comment line above it; '' removes it",
        )
            .placeholder("text")
        val layout = option(
            long = "layout",
            help = "print a table or array so; without it, as what it replaces printed, and new ones inline",
        )
            .placeholder("layout")
            .enum<LayoutChoice>(spelling = { it.word })
        atMostOne(raw, layout)
        val path = argument("path", "a jq path to one item: .a.b, .\"key\", .[0]; missing tables are made")
            .completeWith { offerPaths(file.orNull()) }
        val value = argument(
            "value",
            "a TOML value: 8080, -5, true, '\"text\"', '[1, 2]', '{ a = 1 }'; without one, --layout lays out what is " +
                "there and -c comments it",
        )
            .dashLed()
            .optional()
        layout.completeWith {
            val tables = path.orNull()?.let { it == ".server" } ?: false
            candidates(if (tables) LayoutChoice.entries.map { it.word } else listOf("inline"))
        }
        exits(TomlExit.NothingFound)
        action(endsLine = false) { Ok("${path()} = ${value() ?: "?"}\n") }
    }
}

internal object DeleteSpec : CommandSpec {
    override val name = "delete"

    override fun CommandBuilder.build() {
        help = "remove the item at each path with its comments, and print the document"
        example("kotoml delete -f config.toml .server.debug", "the document without the key")
        val file = fileOption()
        specOption()
        inPlaceFlag()
        val format = formatOption()
        val strict = strictFlag()
        atMostOne(format, strict)
        diffFlag()
        flag(long = "force", help = "remove the item even when comments go with it")
        val paths = argument(
            "path",
            "a jq path to one item: .a.b, .\"key\", .[0]; several are deleted together, or none when one fails",
        )
            .completeWith { offerPaths(file.orNull()) }
            .multiple(min = 1)
        exits(TomlExit.NothingFound)
        action { Ok("deleted ${paths().joinToString(" ")}") }
    }
}

internal object FormatSpec : CommandSpec {
    override val name = "format"

    override fun CommandBuilder.build() {
        help = "restyle a whole TOML document and print it"
        example("kotoml format --check --diff -f config.toml", "what format would change, exit 4 if anything")
        fileOption()
        specOption()
        val inPlace = inPlaceFlag()
        option(long = "style", help = "default, kotoml's style; aligned, with each block of keys lined up")
            .enum<StyleChoice>(spelling = { it.word })
            .default(StyleChoice.Default)
        diffFlag()
        val check = flag(
            long = "check",
            help = "write nothing; name the file and exit ${TomlExit.NotFormatted.code} when format would restyle it",
        )
        atMostOne(inPlace, check)
        exits(TomlExit.NotFormatted)
        action(exit = { if (check()) TomlExit.NotFormatted else null }) {
            Ok(if (check()) "config.toml: not formatted" else "name = \"api\"")
        }
    }
}

internal object CheckSpec : CommandSpec {
    override val name = "check"

    override fun CommandBuilder.build() {
        help = "print each file with OK when it is valid TOML, or print why it is not"
        example("kotoml check a.toml b.toml", "each file named with OK or its error, exit 1 if any is not TOML")
        val files = argument("file", "the TOML files; standard input when absent or `-`")
            .file()
            .multiple()
            .default(listOf("-"))
        specOption()
        val quiet = flag(short = 'q', long = "quiet", help = "print no OK line, only why a file is not TOML")
        exits(TomlExit.NotToml)
        // Each file's line is written as it is checked, so nothing is left to print at the end.
        action(human = { "" }, exit = { checked -> if (checked.all { it.valid }) null else TomlExit.NotToml }) {
            Ok(
                files().map { path ->
                    val valid = if (path == "-") {
                        terminal.readIn().map { it.decodeToString().contains('=') }.getOrElse { false }
                    } else {
                        path in tomlDocuments
                    }
                    val name = if (path == "-") "<stdin>" else path
                    when {
                        json -> Unit
                        !valid -> terminal.err("error: $name is not TOML\n")
                        !quiet() -> terminal.out("$name: OK\n")
                    }
                    CheckedFile(path, valid)
                },
            )
        }
    }
}

internal object DecodeSpec : CommandSpec {
    override val name = "decode"

    override fun CommandBuilder.build() {
        help = "TOML in, JSON out with each value's TOML type, as the toml-test suite reads it"
        fileOption()
        specOption()
        action { Ok("{}") }
    }
}

/** kotoml's tree: its root settings, then each command mounted from its spec. */
internal fun tomlShapedCli(): Cli = cli("kotoml") {
    help = "Query and edit TOML like jq, keeping every comment and spelling of what it prints and writes"
    version = Version.Of("0.4.0")
    manDate = "2026-11-19"
    abbreviation = Abbreviation.None
    repeatedOptions = RepeatedOptions.Refuse
    builtins {
        help = Help.ByTree(short = 'h')
        color = Builtin.Off
    }
    listOf(GetSpec, SetSpec, DeleteSpec, FormatSpec, CheckSpec, DecodeSpec).forEach { command(it) }
}
