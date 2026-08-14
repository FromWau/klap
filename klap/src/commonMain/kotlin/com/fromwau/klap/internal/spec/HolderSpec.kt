package com.fromwau.klap.internal.spec

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.klap.CompletionScope
import com.fromwau.klap.ConversionError

/**
 * The model behind a holder: one shared, immutable-after-build spec — transformers mutate it at build
 * time; a parse records the resolved value in the run's [com.fromwau.klap.ActionScope], never on the spec, so the tree
 * can be parsed concurrently. Split by capability — [ValueSpec] carries the convert/validate pipeline
 * (arguments, options), [NamedSpec] the dash-prefixed identity (options, flags) — so an impossible
 * state (a flag with choices, an argument with a `-x` spelling) is unrepresentable rather than merely unused.
 */
@PublishedApi
internal sealed interface HolderSpec {
    val name: String
    val help: String
    var cardinality: Cardinality

    // Help-only: hidden inputs still parse and bind normally, but are omitted from --help.
    var hidden: Boolean

    // Help-only: the "(one of -c, -x, -t; required)" note this input's row carries because it joined a
    // requireExactlyOne/requireAtMostOne set. Derived from [InputConstraint.hint] when the constraint is
    // declared, and kept here rather than looked up from the command so the render walk needs no access to
    // it; the constraints on the Command remain the only thing PARSING consults.
    var constraintHint: String?
}

/** Value-carrying inputs (arguments, options): the raw-string convert pipeline, validation, and help hints. */
@PublishedApi
internal sealed interface ValueSpec : HolderSpec {
    var convert: (String) -> Result<Any?, ConversionError>
    var choices: List<String>?

    // Runs after a successful convert(); null = pass, non-null = error message. Always yields BadValue,
    // even on a choices-backed spec, so a validation failure is never mislabeled as "choose from ...".
    var validate: ((Any?) -> String?)?

    // Set by .placeholder(): the name shown in place of the generic `value` in help and usage, e.g.
    // `--output <FILE>`. Display-only; it never affects parsing.
    var placeholder: String?

    // Extra help note (e.g. a range) rendered by metaHint alongside choices/cardinality.
    var valueHint: String?

    // Set by .completeWith(): a runtime provider for tab-completion candidates, run via the hidden
    // __complete subcommand since a static shell script cannot call back into Kotlin.
    var complete: (CompletionScope.() -> Unit)?

    // Set by .completeWith(filterByPrefix = false): opts the provider out of the default prefix filter.
    var completePrefixFilter: Boolean

    // Set by .file(): the value slot completes filesystem paths. The __complete path returns the
    // COMPLETE_FILES sentinel and each shell expands it natively; applies to arguments and options alike.
    var isPath: Boolean
}

/**
 * Dash-prefixed inputs (options, flags): every spelling the input answers to, plus a help-section heading.
 *
 * [names] is declaration order with the primary first, and each entry is written as the token it is:
 * `--verbose` is a long, `-v` a short. The dashes ARE the discriminator, so `--h` and `-h` are two
 * different spellings and neither is unreachable. [HolderSpec.name] stays the primary spelling — dashes
 * included — and remains the sink key and the identity used in error text.
 */
internal sealed interface NamedSpec : HolderSpec {
    val names: List<String>

    // Help-only: the group heading this input renders under; null = the default (untitled) block.
    var section: String?
}

/** The long spellings, dashes stripped, declaration order. */
internal val NamedSpec.longs: List<String> get() = names.filter { it.startsWith("--") }.map { it.removePrefix("--") }

/** The short spellings, dashes stripped, declaration order. */
internal val NamedSpec.shorts: List<String> get() = names.filterNot { it.startsWith("--") }.map { it.removePrefix("-") }

/** The primary spelling as a command-line token: a spelling already is one, so this only names the intent. */
internal fun NamedSpec.token(): String = name

/** The long spellings the negative half answers to, dashes stripped; empty when the flag is not negatable. */
internal val FlagSpec.negativeLongs: List<String>
    get() = when {
        !negatable -> emptyList()
        negativeNames.isEmpty() -> longs.map { "no-$it" }
        else -> negativeNames.filter { it.startsWith("--") }.map { it.removePrefix("--") }
    }

/**
 * The short spellings the negative half answers to, dashes stripped. Only an explicit spelling can produce
 * one: a generated negation is `--no-<long>`, and there is no short form to generate it from.
 */
internal val FlagSpec.negativeShorts: List<String>
    get() = if (!negatable) {
        emptyList()
    } else {
        negativeNames.filterNot { it.startsWith("--") }.map { it.removePrefix("-") }
    }

@PublishedApi
internal class ArgumentSpec(
    override val name: String,
    override val help: String,
    override var convert: (String) -> Result<Any?, ConversionError>,
    override var cardinality: Cardinality = Cardinality.Required,
) : ValueSpec {
    override var choices: List<String>? = null
    override var validate: ((Any?) -> String?)? = null
    override var valueHint: String? = null
    override var placeholder: String? = null
    override var complete: (CompletionScope.() -> Unit)? = null
    override var completePrefixFilter: Boolean = true
    override var hidden: Boolean = false
    override var isPath: Boolean = false
    override var constraintHint: String? = null

    // Only arguments carry this: an option's value slot already takes the next token whatever it looks
    // like, so there is nothing for a flag to opt into there.
    var dashLed: Boolean = false

    // Set by Arg.absentWhen(): the input whose presence removes this slot from the operand list entirely.
    // Deliberately not a Cardinality: the slot IS required in every other reading, and folding a condition
    // into the cardinality would make every `when` over it answer a question it cannot answer without the
    // parse. Same reasoning as OptionSpec.requiredWhen.
    var absentWhen: HolderSpec? = null

    // Set by Arg.requiredUnless(): the input whose presence drops this slot's declared minimum to zero.
    // Separate from [absentWhen] because they are different operations: this one keeps the slot and relaxes
    // its count, where absentWhen removes the slot so the operand after it does not slide into it.
    var relaxedWhen: HolderSpec? = null

    init {
        requireValidName("argument", name)
    }
}

/** The display name of the `-<NUM>` input: it has no spelling, so this is what help and errors name it by. */
internal const val NUMBER_LABEL: String = "-<NUM>"

@PublishedApi
internal class OptionSpec(
    override val names: List<String>,
    override val help: String,
    override var convert: (String) -> Result<Any?, ConversionError>,
    override var section: String? = null,
    // What an input with no spelling of its own is named by: `-<NUM>` for the number input, and the folded
    // members' own tokens for a lastOneWins handle. Both are read off a handle rather than typed, so there
    // is no spelling for [name] to take and every error and help row would otherwise name nothing.
    label: String? = null,
    // Set by numberOption(): this input binds a maximal run of digits rather than a spelling.
    val isNumber: Boolean = false,
    // Set by lastOneWins(): the members whose last-written occurrence this handle reports.
    val folds: List<OptionSpec> = emptyList(),
) : ValueSpec, NamedSpec {
    // Above `name` deliberately: initializers run in declaration order, so a nameless declaration must
    // reach this require() before names.first() turns it into a NoSuchElementException.
    init {
        require(names.isNotEmpty() || label != null) { "invalid option: at least one name is required" }
        names.forEach { requireValidSpelling("option", it) }
    }

    override val name: String = label ?: names.first()
    override var cardinality: Cardinality = Cardinality.Optional
    override var choices: List<String>? = null
    override var validate: ((Any?) -> String?)? = null
    override var valueHint: String? = null
    override var placeholder: String? = null
    override var complete: (CompletionScope.() -> Unit)? = null
    override var completePrefixFilter: Boolean = true
    override var hidden: Boolean = false
    override var isPath: Boolean = false
    override var constraintHint: String? = null

    // Set by Opt.requiredIf(): the flag whose presence makes this option required. Deliberately not a
    // Cardinality: the option IS optional in every other reading (it binds null when the flag is absent),
    // and folding a condition into the cardinality would make every `when` over it answer a question it
    // cannot answer without the parse.
    var requiredWhen: FlagSpec? = null

    // Set by Opt.optionalValue(): the raw value an occurrence binds when it carries no ATTACHED value, so
    // `--color` alone means `--color=always`. POSIX.1 XBD 12.2 guideline 7 says option-arguments should not
    // be optional, and this is the opt-in that steps outside it for one option; null, the default, is the
    // conforming mandatory-value behaviour. The space form deliberately never binds — see Parser's
    // consumption branch for why that is the only unambiguous reading.
    var bareValue: String? = null
}

internal class FlagSpec(
    override val names: List<String>,
    override val help: String,
    override var section: String? = null,
) : NamedSpec {
    // Above `name` deliberately: initializers run in declaration order, so a nameless declaration must
    // reach this require() before names.first() turns it into a NoSuchElementException.
    init {
        require(names.isNotEmpty()) { "invalid flag: at least one name is required" }
        names.forEach { requireValidSpelling("flag", it) }
    }

    override val name: String = names.first()
    override var cardinality: Cardinality = Cardinality.Optional
    override var hidden: Boolean = false
    override var constraintHint: String? = null

    // Set by Flag.count(): bind() reports the occurrence count instead of collapsing it to a boolean.
    var isCount: Boolean = false

    // Set by Flag.negatable(): sift() also recognizes a negative counterpart.
    var negatable: Boolean = false

    // Set by Flag.negatable(vararg): the spellings the negative half answers to. Empty means the generated
    // `--no-<long>` per long spelling. A non-empty list REPLACES that rather than adding to it, because a
    // tool whose negative half is spelled differently must also REJECT the generated form: git takes
    // `--paginate`/`--no-pager` and answers to neither `--pager` nor `--no-paginate`.
    var negativeNames: List<String> = emptyList()
}

/**
 * Shared by the bare-name identities — positionals, command names, subcommand aliases: a blank name renders
 * a broken help row, and a leading '-' is ambiguous with an option token / the '--' end-of-options
 * sentinel. Dotted/normal identifier names stay allowed. Option/flag spellings go through
 * [requireValidSpelling] instead, which demands the dashes this one forbids.
 */
internal fun requireValidName(label: String, name: String) {
    require(name.isNotBlank()) {
        "invalid $label name '$name': must not be blank"
    }
    require(name.none { it.isWhitespace() || it.code in 0..0x1F || it.code == 0x7F }) {
        "invalid $label name '$name': must not contain whitespace or control characters"
    }
    require(!name.startsWith("-")) {
        "invalid $label name '$name': must not start with '-' (would be confused with an option)"
    }
}

/**
 * An option/flag spelling is written as the token it is (`--verbose`, `-v`), so the dashes are declared
 * rather than inferred from the spelling's length. That is what makes the vararg's one real trap —
 * `flag("--force", "-f", "ignore nonexistent files")`, where the help text is silently accepted as a third
 * spelling — a construction error naming the mistake instead of a puzzle discovered at runtime.
 *
 * A short is exactly one character: the parser walks a single-dash token as a cluster of one-character
 * flags (`-xzf`), so a longer single-dash spelling could never be matched whole.
 */
internal fun requireValidSpelling(label: String, spelling: String) {
    require(spelling.isNotBlank()) {
        "invalid $label spelling '$spelling': must not be blank"
    }
    require(spelling.none { it.isWhitespace() || it.code in 0..0x1F || it.code == 0x7F }) {
        "invalid $label spelling '$spelling': must not contain whitespace or control characters$HELP_TEXT_POINTER"
    }
    require(spelling.startsWith("-")) {
        val suggestion = if (spelling.length == 1) "-$spelling" else "--$spelling"
        "invalid $label spelling '$spelling': a spelling carries its own dashes, so write it as " +
                "'$suggestion'$HELP_TEXT_POINTER"
    }
    require(!spelling.startsWith("---")) {
        "invalid $label spelling '$spelling': at most two leading dashes ('--long' or '-s')"
    }
    require(spelling != "-" && spelling != "--") {
        "invalid $label spelling '$spelling': nothing follows the dashes ('-' is a conventional operand " +
                "and '--' the end-of-options marker, so neither can name an input)"
    }
    require(spelling.startsWith("--") || spelling.length == 2) {
        "invalid $label spelling '$spelling': a one-dash spelling is a single-character short, and the " +
                "parser reads '$spelling' as the cluster ${spelling.drop(1).map { "-$it" }}; write " +
                "'--${spelling.removePrefix("-")}' for a long"
    }
}

/** Appended to the two spelling rules a positionally passed help string trips, which is why it trips them. */
private const val HELP_TEXT_POINTER =
    " (if this is help text, pass it as help = \"...\", which is a named-only parameter)"
