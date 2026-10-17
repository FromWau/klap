package com.fromwau.klap.internal.spec

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.klap.CompletionScope
import com.fromwau.klap.ConversionError
import com.fromwau.klap.RepeatedOptions

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

    // Help-only: the "(one of -c, -x, -t; required)" note this input's row carries because it joined an
    // exactlyOne/atMostOne set. Derived from [InputConstraint.hint] when the constraint is declared, and
    // kept here rather than looked up from the command so the render walk needs no access to it; the
    // constraints on the Command remain the only thing PARSING consults.
    var constraintHint: String?
}

/** Value-carrying inputs (arguments, options): the raw-string convert pipeline, validation, and help hints. */
@PublishedApi
internal sealed interface ValueSpec : HolderSpec {
    var convert: (String) -> Result<Any?, ConversionError>
    var choices: List<String>?

    // Set by .enum(spellings =): the words of each entry, in [choices] order. A prefix reaching several words of
    // one entry names that entry, and help lists each entry by its first word.
    var choiceEntries: List<List<String>>?

    // Set by .choice(ignoreCase = true) or .enum(ignoreCase = true): a value matches its choice in any case.
    // Off by default, as `ls --color=ALWAYS` and `cp --backup=NUMBERED` refuse a value in the wrong case.
    var choiceIgnoreCase: Boolean

    // Cleared by .enum(abbreviates = false): a value matches its choice only whole, whatever the CLI's Abbreviation,
    // as rm compares `--preserve-root=all`.
    var choiceAbbreviates: Boolean

    // The checks declared after the last converter, run on its value; null = pass, non-null = error message.
    // A converter declared after them folds them into [convert], so each check judges the value at its place.
    // Always yields BadValue, even on a choices-backed spec, so a refusal is never mislabeled "choose from ...".
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

    // Set by .repeated(): what a second occurrence does; null takes the CLI's own repeatedOptions.
    var repeated: RepeatedOptions?

    // Set by .spelledInFull(): no prefix reaches this input's longs, whatever the CLI's abbreviation.
    var spelledInFull: Boolean
}

/**
 * Whether a second occurrence of this input refuses the line: its own `.repeated()` decides, else [policy]. A
 * `.multiple()` option and a `.count()` flag collect their occurrences, so they never do.
 */
internal fun NamedSpec.refusesRepeat(policy: RepeatedOptions): Boolean = when {
    this is OptionSpec && cardinality is Cardinality.Multiple -> false
    this is FlagSpec && isCount -> false
    else -> (repeated ?: policy) == RepeatedOptions.Refuse
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

/** The negative half's spellings as the tokens they are typed as (`--no-cache`, `-P`); empty when not negatable. */
internal val FlagSpec.negationTokens: List<String>
    get() = negativeLongs.map { "--$it" } + negativeShorts.map { "-$it" }

@PublishedApi
internal class ArgumentSpec(
    override val name: String,
    override val help: String,
    override var convert: (String) -> Result<Any?, ConversionError>,
    override var cardinality: Cardinality = Cardinality.Required,
) : ValueSpec {
    override var choices: List<String>? = null
    override var choiceEntries: List<List<String>>? = null
    override var choiceIgnoreCase: Boolean = false
    override var choiceAbbreviates: Boolean = true
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

    // Set by Arg.dashLed(gather) { }: this slot collects every accepted dash word on the line instead.
    var gather: DashLedGather? = null

    // Set by Arg.endsOptions(): the first word bound to this slot ends its command's options.
    var endsOptions: Boolean = false

    // Set by Arg.absentWhen(): the input whose presence removes this slot from the operand list entirely.
    // Deliberately not a Cardinality: the slot IS required in every other reading, and folding a condition
    // into the cardinality would make every `when` over it answer a question it cannot answer without the
    // parse. Same reasoning as OptionSpec.requiredWhen.
    var absentWhen: HolderSpec? = null

    // Set by Arg.optionalWhen(): the input whose presence drops this slot's declared minimum to zero.
    // Separate from [absentWhen] because they are different operations: this one keeps the slot and relaxes
    // its count, where absentWhen removes the slot so the operand after it does not slide into it.
    var optionalWhen: HolderSpec? = null

    // Set by Arg.atMostWhen(): each input whose presence caps this variadic, with the cap it sets.
    var capsWhen: List<Pair<HolderSpec, Int>> = emptyList()

    init {
        requireValidName("argument", name)
    }
}

/** How a gathering slot reads a dash word: whether [accepts] takes it, and the [separator] joining every one taken. */
internal class DashLedGather(val separator: String, val accepts: (String) -> Boolean)

/** The display name of the `-<NUM>` input: it has no spelling, so this is what help and errors name it by. */
internal const val NUMBER_LABEL: String = "-<NUM>"

/**
 * Where a run of the number input may stand and what it keeps: only as its command's first word when
 * [firstWordOnly], and with each of the [suffixes] letters typed after its digits kept in its value.
 */
internal class NumberShape(val firstWordOnly: Boolean, val suffixes: String)

@PublishedApi
internal class OptionSpec(
    override val names: List<String>,
    override val help: String,
    override var convert: (String) -> Result<Any?, ConversionError>,
    override var section: String? = null,
    // What an input with no spelling of its own is named by: `-<NUM>` for the number input, and the folded
    // members' own tokens for a lastOf handle. Both are read off a handle rather than typed, so there
    // is no spelling for [name] to take and every error and help row would otherwise name nothing.
    label: String? = null,
    // Set by numberOption(): this input binds a run of digits rather than a spelling, read in this shape.
    val number: NumberShape? = null,
    // Set by lastOf(): the members whose last-written occurrence this handle reports.
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
    override var choiceEntries: List<List<String>>? = null
    override var choiceIgnoreCase: Boolean = false
    override var choiceAbbreviates: Boolean = true
    override var validate: ((Any?) -> String?)? = null
    override var valueHint: String? = null
    override var placeholder: String? = null
    override var complete: (CompletionScope.() -> Unit)? = null
    override var completePrefixFilter: Boolean = true
    override var hidden: Boolean = false
    override var isPath: Boolean = false
    override var constraintHint: String? = null
    override var repeated: RepeatedOptions? = null
    override var spelledInFull: Boolean = false

    // Set by Opt.requiredWhen(): the flag whose presence makes this option required. Deliberately not a
    // Cardinality: the option IS optional in every other reading (it binds null when the flag is absent),
    // and folding a condition into the cardinality would make every `when` over it answer a question it
    // cannot answer without the parse.
    var requiredWhen: FlagSpec? = null

    // Set by Opt.optionalValue(): the raw value an occurrence binds when it carries no ATTACHED value, so
    // `--color` alone means `--color=always`, getopt_long's optional argument; null, the default, is a mandatory
    // value. The next word is never this value: it cannot be told apart from an operand.
    var bareValue: String? = null

    // Set by Opt.optionalValue() with no argument: a bare occurrence names no value and binds
    // OptionalValue.Bare, an attached one OptionalValue.Given.
    var bareMarks: Boolean = false

    // Set by Opt.gluedValue(): the value is only ever attached, and an occurrence without one is refused.
    var gluedValue: Boolean = false

    /** Whether the value is optional: an occurrence without one attached never takes the next word. */
    val valueOptional: Boolean get() = bareValue != null || bareMarks

    /** Whether only an attached value is this option's own: it never takes the next word. */
    val valueAttachedOnly: Boolean get() = valueOptional || gluedValue
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
    override var repeated: RepeatedOptions? = null
    override var spelledInFull: Boolean = false

    // Set by Flag.count(): bind() reports the occurrence count instead of collapsing it to a boolean.
    var isCount: Boolean = false

    // Set by Flag.negatable(): sift() also recognizes a negative counterpart.
    var negatable: Boolean = false

    // Set by Flag.negatable(vararg): the spellings the negative half answers to. Empty means the generated
    // `--no-<long>` per long spelling. A non-empty list REPLACES that rather than adding to it, because a
    // tool whose negative half is spelled differently must also REJECT the generated form: git takes
    // `--paginate`/`--no-pager` and answers to neither `--pager` nor `--no-paginate`.
    var negativeNames: List<String> = emptyList()

    // Set by Flag.negatable(help = ...): the negative half's own words, which give it a help row of its own.
    var negativeHelp: String? = null
}

/**
 * Shared by the bare-name identities — positionals, command names, subcommand aliases: a blank name renders
 * a broken help row, and a leading '-' is ambiguous with an option token / the '--' end-of-options
 * sentinel. Dotted/normal identifier names stay allowed. Option/flag spellings go through
 * [requireValidSpelling] instead.
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
 * The internal form of a declaration's spellings: each written as the token it is (`--since`, `-a`), the first
 * long first so it is the primary, then the other longs and the shorts in declaration order.
 */
internal fun spellings(short: List<Char>, long: List<String>): List<String> =
    long.map { "--$it" } + short.map { "-$it" }

/**
 * A short is a [Char], so its shape needs no check beyond being visible; a long is a word typed after `--`, so it
 * carries no dashes of its own and no `=`, which would end the name and start the value.
 */
internal fun requireValidSpelling(label: String, spelling: String) {
    val bare = if (spelling.startsWith("--")) spelling.drop(2) else spelling.drop(1)
    require(bare.isNotBlank()) {
        "invalid $label spelling '$bare': must not be blank"
    }
    require(bare.none { it.isWhitespace() || it.code in 0..0x1F || it.code == 0x7F }) {
        "invalid $label spelling '$bare': must not contain whitespace or control characters"
    }
    require(!bare.startsWith("-")) {
        "invalid $label spelling '$bare': write it without dashes, as long = \"${bare.trimStart('-')}\"; " +
                "a long is typed after '--' and a short after '-'"
    }
    require('=' !in bare) {
        "invalid $label spelling '$bare': must not contain '=', which separates a long from its attached value"
    }
}
