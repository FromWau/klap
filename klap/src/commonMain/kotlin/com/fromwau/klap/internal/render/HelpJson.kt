package com.fromwau.klap.internal.render

import com.fromwau.klap.Builtins
import com.fromwau.klap.Cli
import com.fromwau.klap.Command
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.NamedSpec
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.ValueSpec
import com.fromwau.klap.internal.spec.constraintToken
import com.fromwau.klap.internal.spec.longs
import com.fromwau.klap.internal.spec.negativeLongs
import com.fromwau.klap.internal.spec.negativeShorts
import com.fromwau.klap.internal.spec.shorts
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One command's help as `--help --json` prints it: the same inputs as text help, as fields instead of prose. */
@Serializable
internal data class HelpJson(
    val command: String,
    val usage: String,
    val description: String,
    val arguments: List<ArgumentHelp>,
    val options: List<OptionHelp>,
    val commands: List<CommandHelp>,
    val globalOptions: List<OptionHelp>,
    val examples: List<ExampleHelp>,
    val epilogue: String? = null,
    val author: String? = null,
)

@Serializable
internal data class ArgumentHelp(
    val name: String,
    val help: String,
    val required: Boolean,
    val repeatable: Boolean,
    val min: Int? = null,
    val max: Int? = null,
    val default: String? = null,
    val choices: List<String>? = null,
    val absentWhen: String? = null,
    val optionalWhen: String? = null,
    val atMostWhen: List<CapHelp>? = null,
)

/** One `.atMostWhen` cap: while [input] is given, the operand takes at most [max] values. */
@Serializable
internal data class CapHelp(
    val input: String,
    val max: Int,
)

@Serializable
internal data class OptionHelp(
    val names: List<String>,
    val help: String,
    val value: String? = null,
    val required: Boolean = false,
    val repeatable: Boolean = false,
    val min: Int? = null,
    val max: Int? = null,
    val default: String? = null,
    val choices: List<String>? = null,
    val section: String? = null,
)

@Serializable
internal data class CommandHelp(
    val name: String,
    val aliases: List<String>,
    val help: String,
    val section: String? = null,
)

@Serializable
internal data class ExampleHelp(
    val command: String,
    val description: String,
)

/** This node's help as data; hidden inputs and subcommands are left out, as text help leaves them out. */
internal fun Command.helpJson(
    qualifiedName: String,
    globalSpecs: List<NamedSpec>,
    rootVersioned: Boolean,
    rootMetaOptions: Boolean,
    builtins: Builtins,
): HelpJson = HelpJson(
    command = qualifiedName,
    usage = usageLine(qualifiedName).removePrefix("usage: "),
    description = description,
    arguments = arguments.filter { !it.hidden }.map { it.toHelp() },
    options = namedInputs.filter { !it.hidden }.map { it.toHelp() },
    commands = subcommands.filter { !it.hidden }.map {
        CommandHelp(it.token, it.aliases + it.flagSpellings.drop(1), it.description, it.section)
    },
    globalOptions = globalSpecs.filter { !it.hidden }.map { it.toHelp() } +
        builtinOptions(rootVersioned, rootMetaOptions, builtins).map { it.toHelp() },
    examples = examples.map { ExampleHelp(it.command, it.description) },
    epilogue = epilogue.ifEmpty { null },
    author = (this as? Cli)?.author?.takeUnless { it.isBlank() },
)

/** `--help --json`: this node's help, or with [recursive] an array of it and every visible descendant's. */
internal fun Command.helpJsonText(
    qualifiedName: String,
    globalSpecs: List<NamedSpec>,
    rootVersioned: Boolean,
    rootMetaOptions: Boolean,
    builtins: Builtins,
    recursive: Boolean,
): String {
    if (!recursive) {
        val page = helpJson(qualifiedName, globalSpecs, rootVersioned, rootMetaOptions, builtins)
        return Json.encodeToString(HelpJson.serializer(), page)
    }
    val all = visibleTree(qualifiedName).map { (node, path) ->
        node.helpJson(path, globalSpecs, rootVersioned, rootMetaOptions, builtins)
    }
    return Json.encodeToString(ListSerializer(HelpJson.serializer()), all)
}

private fun BuiltinOption.toHelp(): OptionHelp =
    OptionHelp(names, help, value = choices?.joinToString("|"), choices = choices)

private fun Cardinality.isRequired(): Boolean =
    this == Cardinality.Required || (this is Cardinality.Multiple && min > 0)

/** A `.multiple()` input's declared minimum, null when it is zero, as text help leaves it unsaid. */
private val Cardinality.minCount: Int? get() = (this as? Cardinality.Multiple)?.min?.takeIf { it > 0 }

/** A `.multiple()` input's declared maximum, null when it has none. */
private val Cardinality.maxCount: Int? get() = (this as? Cardinality.Multiple)?.max?.takeIf { it != Int.MAX_VALUE }

private val ValueSpec.shownDefault: String?
    get() = when (val c = cardinality) {
        is Cardinality.Default -> display(c.value)
        is Cardinality.Multiple -> c.default?.let { displayAll(it) }
        else -> null
    }

private fun ArgumentSpec.toHelp(): ArgumentHelp = ArgumentHelp(
    name = displayName(),
    help = help,
    required = cardinality.isRequired(),
    repeatable = cardinality is Cardinality.Multiple,
    min = cardinality.minCount,
    max = cardinality.maxCount,
    default = shownDefault,
    choices = listedChoices,
    absentWhen = absentWhen?.constraintToken(),
    optionalWhen = optionalWhen?.constraintToken(),
    atMostWhen = capsWhen.map { (trigger, n) -> CapHelp(trigger.constraintToken(), n) }.ifEmpty { null },
)

private fun NamedSpec.toHelp(): OptionHelp = when (this) {
    is OptionSpec -> OptionHelp(
        names = spellings(),
        help = help,
        value = resolvedPlaceholder(),
        required = cardinality.isRequired(),
        repeatable = cardinality is Cardinality.Multiple,
        min = cardinality.minCount,
        max = cardinality.maxCount,
        default = shownDefault,
        choices = listedChoices,
        section = section,
    )
    is FlagSpec -> OptionHelp(
        names = spellings(),
        help = help,
        repeatable = isCount,
        // A plain flag's default is always off; only a negatable one says which way it starts.
        default = if (negatable) (cardinality as? Cardinality.Default)?.value?.toString() else null,
        section = section,
    )
}

/** Every spelling, shorts first and each positive before its negative, as [words] lists them. */
private fun NamedSpec.spellings(): List<String> {
    if (this is OptionSpec && names.isEmpty()) return listOf(name)
    val negativeShorts = (this as? FlagSpec)?.negativeShorts.orEmpty()
    val negativeLongs = (this as? FlagSpec)?.negativeLongs.orEmpty()
    return (shorts + negativeShorts).map { "-$it" } + (longs + negativeLongs).map { "--$it" }
}
