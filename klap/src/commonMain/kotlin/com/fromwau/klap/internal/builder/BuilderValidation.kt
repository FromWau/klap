package com.fromwau.klap.internal.builder

import com.fromwau.klap.Builtins
import com.fromwau.klap.Command
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.Cardinality
import com.fromwau.klap.internal.spec.ConstraintArity
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.InputConstraint
import com.fromwau.klap.internal.spec.NamedSpec
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.constraintToken
import com.fromwau.klap.internal.spec.longs
import com.fromwau.klap.internal.spec.negativeLongs
import com.fromwau.klap.internal.spec.negativeShorts
import com.fromwau.klap.internal.spec.requireValidName
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.internal.spec.token

/**
 * Long names silently consumed by klap's pre-strip/help injection at every command level, for a root whose
 * [builtins] resolved this way. `help`/`help-all`/`version` are always injected — `builtins { }` cannot
 * decline them — so they are reserved unconditionally; the rest free their name when declined.
 */
private fun reservedLongNames(builtins: Builtins): Set<String> = buildSet {
    add("help")
    add("help-all")
    add("version")
    if (builtins.json) add("json")
    if (builtins.completion) add("completion")
    if (builtins.docs) add("docs")
    if (builtins.color) add("color")
}

/** Short names silently consumed by klap's pre-strip/help injection; only `-h`, and only while it is offered. */
private fun reservedShortNames(builtins: Builtins): Set<String> =
    if (builtins.helpShort) setOf("h") else emptySet()

/** Section headings `--help` emits on its own (see `Help.kt`); a group titled with one renders a duplicate heading. */
private val RESERVED_SECTIONS = setOf("Commands", "Global options", "Examples")

// The well-formedness rules [BuilderImpl.build] runs before it produces a [Command]. Each throws on a
// violation, so a malformed tree fails loudly at construction (`cli { }`) rather than misbehaving at parse
// time. Kept as free functions over the accumulated specs so build() reads as a flat checklist and the
// accumulation logic stays separate from the rules.

/**
 * Positionals must be `required* (optional|default)* multiple? required*`: one variadic at most, and after
 * it only REQUIRED slots, which bind from the end (`cp SOURCE... DEST`). An optional slot after the greedy
 * one is genuinely ambiguous — with one token left there is no rule saying which of the two it feeds — and
 * a required-after-optional is ambiguous the same way. A violation fails loudly at build time.
 */
internal fun validatePositionals(name: String, specs: List<HolderSpec>) {
    val positionals = specs.filterIsInstance<ArgumentSpec>()
    require(positionals.count { it.cardinality is Cardinality.Multiple } <= 1) {
        "command '$name': at most one variadic (multiple) argument is allowed"
    }
    val multipleIndex = positionals.indexOfFirst { it.cardinality is Cardinality.Multiple }
    if (multipleIndex >= 0) {
        val after = positionals.drop(multipleIndex + 1)
        val ambiguous = after.firstOrNull { it.cardinality != Cardinality.Required }
        require(ambiguous == null) {
            "command '$name': argument '${ambiguous?.name}' follows the variadic (multiple) argument " +
                    "'${positionals[multipleIndex].name}' and is not required, which is ambiguous: a " +
                    "single leftover token could feed either slot. Only required arguments may follow a variadic"
        }
    }
    // A hidden `.multiple(min >= 1)` is just as mandatory as a hidden Required positional (the user
    // must supply at least one value for a slot they cannot see in --help), so it is rejected the same way.
    require(
        positionals.none {
            val cardinality = it.cardinality
            it.hidden && (cardinality == Cardinality.Required || (cardinality is Cardinality.Multiple && cardinality.min >= 1))
        }
    ) {
        "command '$name': a mandatory positional cannot be hidden (the user must supply an argument they cannot see)"
    }
    var sawOptional = false
    for (spec in positionals) {
        require(!(spec.cardinality == Cardinality.Required && sawOptional)) {
            "command '$name': required argument '${spec.name}' cannot follow an optional/default argument"
        }
        if (spec.cardinality !is Cardinality.Required && spec.cardinality !is Cardinality.Multiple) {
            sawOptional = true
        }
    }
}

/**
 * A `lastWins` member must have an absent form its loser can be reset to, and two option cardinalities have
 * none. A `.required()` option's absence IS the usage error, so there is no value that stands for "the user
 * never wrote it". A `.multiple()` option accumulates every occurrence, which is the opposite shape from a
 * set that collapses to one winner, and its accessor is a non-null `List<T>` only the bind path fills. Left
 * unchecked, either one hands the action a null its own type forbids and the NPE lands inside user code.
 *
 * Checked over the finished constraint list rather than inside `lastWins(...)` itself: `.required()` and
 * `.multiple()` mutate the shared spec and may legally run after the `lastWins(...)` line, so a check at the
 * call site would pass or fail on declaration order alone.
 */
internal fun validateLastWinsMembers(name: String, constraints: List<InputConstraint>) {
    for (constraint in constraints.filter { it.arity == ConstraintArity.LastWins }) {
        for (spec in constraint.members.filterIsInstance<OptionSpec>()) {
            val cardinality = spec.cardinality
            require(cardinality != Cardinality.Required) {
                "command '$name': lastWins lists '${spec.constraintToken()}', which is required; a loser " +
                        "binds what it would have bound had the user never written it, and a required " +
                        "option has no such value, since its absence is itself the usage error"
            }
            require(cardinality !is Cardinality.Multiple) {
                "command '$name': lastWins lists '${spec.constraintToken()}', which is multiple; a loser " +
                        "binds what it would have bound had the user never written it, and a repeatable " +
                        "option accumulates its occurrences rather than collapsing to one, so there is " +
                        "nothing for the set to override"
            }
        }
    }
}

/**
 * Two rules over a conditional operand, both checkable only once the spec list is finished.
 *
 * The trigger must be one of this command's own inputs, and `.absentWhen()`/`.requiredUnless()` write it
 * straight onto the shared [ArgumentSpec] from inside [com.fromwau.klap.ConverterScope], which never sees
 * that list (unlike [com.fromwau.klap.CommandBuilder.numericAlias] or the `requireExactlyOne`/`lastWins`
 * family, both implemented on [BuilderImpl] itself, where the list lives).
 *
 * Each also pairs with exactly one cardinality, and `.multiple()` mutates the shared spec and may legally
 * run after the conditional line, so a check at the DSL call site would pass or fail on declaration order
 * alone: `argument("file").absentWhen(ref).multiple(min = 1)` would slip past one and then lose the whole
 * variadic at parse time. Same reasoning as [validateLastWinsMembers].
 */
internal fun validateConditionalOperandTriggers(name: String, specs: List<HolderSpec>) {
    for (spec in specs.filterIsInstance<ArgumentSpec>()) {
        spec.absentWhen?.let { trigger ->
            require(spec.cardinality !is Cardinality.Multiple) {
                "command '$name': argument '${spec.name}' combines .absentWhen(${trigger.constraintToken()}) " +
                    "with .multiple(); a variadic slot already binds an empty list when nothing reaches it"
            }
            require(specs.any { it === trigger }) {
                "command '$name': argument '${spec.name}' declares .absentWhen(${trigger.constraintToken()}), " +
                    "which is not declared on '$name'; a trigger must be one of this command's own inputs"
            }
        }
        spec.relaxedWhen?.let { trigger ->
            require(spec.cardinality is Cardinality.Multiple) {
                "command '$name': argument '${spec.name}' declares " +
                    ".requiredUnless(${trigger.constraintToken()}) on a slot that is not .multiple(); the " +
                    "call drops a declared minimum to zero, and only a variadic has one, so the slot would " +
                    "stay mandatory. Use .absentWhen(${trigger.constraintToken()}) to remove the slot instead"
            }
            require(specs.any { it === trigger }) {
                "command '$name': argument '${spec.name}' declares .requiredUnless(${trigger.constraintToken()}), " +
                    "which is not declared on '$name'; a trigger must be one of this command's own inputs"
            }
        }
    }
}

/**
 * A `.requiredIf(flag)` trigger must be one of this command's own inputs or a global: the parse-time check
 * reads only those, so a flag from an unrelated command could never fire the rule `--help` advertises.
 */
internal fun validateRequiredIfTriggers(name: String, specs: List<HolderSpec>, globalSpecs: List<NamedSpec>) {
    for (spec in specs.filterIsInstance<OptionSpec>()) {
        val trigger = spec.requiredWhen ?: continue
        require(specs.any { it === trigger } || globalSpecs.any { it === trigger }) {
            "command '$name': option '${spec.token()}' declares .requiredIf(${trigger.token()}), which is " +
                "not declared on '$name' or as a global; a trigger must be one of this command's own " +
                "inputs or a global"
        }
    }
}

/**
 * Re-runs the cardinality-sensitive rules over an already-built subtree. A [HolderSpec] is live and shared,
 * so a command declared later can call `.required()`/`.multiple()` on a handle captured from an earlier one,
 * after that command's own `build()` already checked it; only the root's `build()` runs late enough to see
 * the final state.
 */
internal fun revalidateAgainstLaterMutation(command: Command) {
    validatePositionals(command.name, command.specs)
    validateLastWinsMembers(command.name, command.constraints)
    validateConditionalOperandTriggers(command.name, command.specs)
    command.subcommands.forEach(::revalidateAgainstLaterMutation)
}

/**
 * The one spelling the parser could never honour: a negatable flag relying on generation (no explicit
 * negative spellings given) with no long to generate `--no-...` from. Checked here rather than in
 * [FlagSpec]'s init because `.negatable()` is a post-construction transformer. A flag with explicit
 * negative spellings needs no long of its own to negate: that is the whole point of `-P`/`-a`-style pairs.
 */
internal fun validateSpellings(
    name: String,
    specs: List<HolderSpec>,
    globalSpecs: List<NamedSpec>,
) {
    for (spec in specs.filterIsInstance<NamedSpec>() + globalSpecs) {
        if (spec is FlagSpec && spec.negatable && spec.negativeNames.isEmpty()) {
            require(spec.longs.isNotEmpty()) {
                "command '$name': flag '${spec.token()}' is negatable but has no long spelling to generate " +
                    "'--no-...' from; pass the negative spellings explicitly, as .negatable(\"-P\")"
            }
        }
    }
}

/**
 * Two options/flags at THIS level sharing any spelling: the parser checks [com.fromwau.klap.findFlag]
 * before [com.fromwau.klap.findOption] (see `Parser.kt`), so a flag would silently shadow a
 * same-named option, and either kind shadows an earlier declaration of its own kind — the shadowed
 * one becomes permanently unreachable while `--help` still lists it. Distinct from
 * [validateGlobalCollisions] (globals vs. locals) and [validateReservedNames] (a built-in name);
 * this only compares this command's own declarations against each other.
 */
internal fun validateDuplicateOptionFlagNames(name: String, specs: List<HolderSpec>) {
    val named = specs.filterIsInstance<NamedSpec>()
    val duplicate = named.flatMap { it.names }.firstDuplicate()
    require(duplicate == null) {
        "command '$name': two options/flags share the name '$duplicate'"
    }
}

/** Every rule over one level's `command(...)` declarations, checked once the sibling list is complete. */
internal fun validateSubcommands(name: String, subs: List<Command>) {
    requireDistinctSubcommandNames(name, subs)
    requireWellFormedAliases(name, subs)
    requireNoAliasCollisions(name, subs)
    subs.forEach { requireUnreservedSectionTitle(name, "subcommand '${it.name}'", it.section) }
}

/**
 * [Command.subcommand] matches by first hit, so a repeated name leaves the later claimant permanently
 * unreachable while `--help` still lists it.
 */
private fun requireDistinctSubcommandNames(name: String, subs: List<Command>) {
    val duplicate = subs.map { it.name }.firstDuplicate()
    require(duplicate == null) {
        "command '$name': two subcommands are named '$duplicate'"
    }
}

/**
 * The two self-collisions a sibling-vs-sibling walk cannot see, since it only ever compares DIFFERENT
 * subcommands: an alias equal to its own command's name (renders "foo, foo"), and a repeated alias within
 * one command's own list ("foo, x, x"). Aliases also face [requireValidName], the check a command's own name
 * already gets, since an alias is just as much an invocation token.
 */
private fun requireWellFormedAliases(name: String, subs: List<Command>) {
    for (sub in subs) {
        require(sub.name !in sub.aliases) {
            "command '$name': subcommand '${sub.name}' has an alias equal to its own name"
        }
        val duplicateAlias = sub.aliases.firstDuplicate()
        require(duplicateAlias == null) {
            "command '$name': subcommand '${sub.name}' repeats alias '$duplicateAlias'"
        }
        sub.aliases.forEach { requireValidName("alias", it) }
    }
}

/**
 * An alias colliding with a DIFFERENT sibling's name or alias. Seeded with every subcommand's own name;
 * a self-collision is already rejected by [requireWellFormedAliases], so it never reaches this map.
 */
private fun requireNoAliasCollisions(name: String, subs: List<Command>) {
    val claimedBy = subs.associateTo(mutableMapOf()) { it.name to it.name }
    for (sub in subs) {
        for (alias in sub.aliases.distinct()) {
            val claimant = claimedBy[alias]
            require(claimant == null || claimant == sub.name) {
                "command '$name': subcommand '${sub.name}' alias '$alias' collides with subcommand '$claimant'"
            }
            claimedBy[alias] = sub.name
        }
    }
}

/**
 * A command with no action only routes to subcommands or renders `--help`; it never reads its own
 * inputs. A locally declared option/flag on such a command would render in `--help` yet stay forever
 * unparseable, and reading its accessor would crash at runtime ("holder read before parse"). A declared
 * positional is just as unreadable: a group's argument slot is consumed as a subcommand token, so the
 * positional could never bind. Globals stay exempt — they are shared across the whole tree and read
 * from a descendant's action instead.
 */
internal fun validateActionlessLocalOptions(
    name: String,
    specs: List<HolderSpec>,
    hasAction: Boolean,
    isBuiltin: Boolean = false,
) {
    if (hasAction) return
    val locals = specs.filterIsInstance<NamedSpec>()
    require(locals.isEmpty()) {
        val offender = locals.first()
        "command '$name': option/flag '${offender.token()}' is declared but '$name' has no action to " +
                "read it; give '$name' an action, or declare '${offender.token()}' via globalOption/globalFlag " +
                "if it should be shared across subcommands"
    }
    // A klap builtin (completion/docs/__complete) is action-less by design and routes its positional
    // through routeBuiltin rather than binding it via an action, so the "positional can never bind" rule
    // below does not apply to it.
    if (isBuiltin) return
    val positionals = specs.filterIsInstance<ArgumentSpec>()
    require(positionals.isEmpty()) {
        val offender = positionals.first()
        "command '$name': positional argument '${offender.name}' is declared but '$name' has no action " +
                "to read it; a group only routes to subcommands, so the argument's slot is read as a " +
                "subcommand name and can never bind. Give '$name' an action, or remove the positional"
    }
}

/**
 * A negatable flag's negative half, whether generated (`--no-<long>`, one per long spelling) or explicit
 * (`.negatable(vararg)`'s own spellings), must not collide with another declared option/flag spelling on
 * this command, nor with another flag's own negative half. Compared as full dash-prefixed tokens, since a
 * `--x` and `-x` are distinct spellings ([NamedSpec]'s own rule) and an explicit negation may be a short.
 */
internal fun validateNegationCollisions(name: String, specs: List<HolderSpec>) {
    val declaredNames = specs.filterIsInstance<NamedSpec>().flatMap { it.names }.toSet()
    val negatedNamesSeen = mutableSetOf<String>()
    for (spec in specs.filterIsInstance<FlagSpec>().filter { it.negatable }) {
        val negations = spec.negativeLongs.map { "--$it" } + spec.negativeShorts.map { "-$it" }
        for (negation in negations) {
            require(negation !in declaredNames) {
                "command '$name': flag '${spec.token()}' negation '$negation' collides with a declared option/flag name"
            }
            require(negatedNamesSeen.add(negation)) {
                "command '$name': two negatable flags both claim '$negation' as their negation"
            }
        }
    }
}

/**
 * A global's spellings, and its negation if it is negatable, must not collide with another global's, nor
 * with the root's own or any descendant command's own option/flag (including that command's own
 * negations). No shadowing: the pre-strip parser consumes a global's token before a same-named local ever
 * sees it, so a same-named local option/flag could never actually receive a value. Only meaningful for the
 * root (only [com.fromwau.klap.CliBuilder] can declare globals), so it is a no-op for every subcommand's
 * own build().
 */
internal fun validateGlobalCollisions(
    name: String,
    specs: List<HolderSpec>,
    globalSpecs: List<NamedSpec>,
    subs: List<Command>,
) {
    if (globalSpecs.isEmpty()) return
    val globalNames = globalSpecs.flatMap { it.names }
    val duplicateName = globalNames.firstDuplicate()
    require(duplicateName == null) {
        "cli '$name': two globals declare the same name '$duplicateName'"
    }
    // A negatable global also claims its negative half for each spelling; that must join the collision set too.
    val globalNegations = globalSpecs
        .filterIsInstance<FlagSpec>()
        .filter { it.negatable }
        .flatMap { spec -> spec.negativeLongs.map { "--$it" } + spec.negativeShorts.map { "-$it" } }
    // A plain global name colliding with another global's negation (or two negations colliding with each
    // other) is just as unreachable as two plain names colliding, so check the combined set here too.
    val globalEffectiveNames = globalNames + globalNegations
    val duplicateEffective = globalEffectiveNames.firstDuplicate()
    require(duplicateEffective == null) {
        "cli '$name': global name/negation '$duplicateEffective' is claimed by more than one global"
    }
    val globalEffectiveNameSet = globalEffectiveNames.toSet()

    fun checkSpecs(cmdName: String, specList: List<HolderSpec>) {
        for (spec in specList.filterIsInstance<NamedSpec>()) {
            for (spelling in spec.names) {
                require(spelling !in globalEffectiveNameSet) {
                    "command '$cmdName': option/flag '$spelling' collides with a global's name or a global's negation"
                }
            }
            if (spec is FlagSpec && spec.negatable) {
                val negations = spec.negativeLongs.map { "--$it" } + spec.negativeShorts.map { "-$it" }
                for (negation in negations) {
                    require(negation !in globalEffectiveNameSet) {
                        "command '$cmdName': flag '${spec.token()}' negation '$negation' collides with a global's name or a global's negation"
                    }
                }
            }
        }
    }

    fun walk(command: Command) {
        checkSpecs(command.name, command.specs)
        command.subcommands.forEach(::walk)
    }
    // The root's own local options/flags collide too: siftGlobals strips a global's token before the
    // root's own sift ever sees it, so a same-named local could never receive a value.
    checkSpecs(name, specs)
    subs.forEach(::walk)
}

/**
 * An option/flag long name of `help`/`version`/`json`, or short `h`, is silently consumed by the
 * parser's pre-strip/help injection before a same-named local ever sees it (see
 * [validateGlobalCollisions] for the same shadowing hazard with globals). A positional named
 * `json` is harmless, since only `--json`/`-h` tokens are stripped, so `ARGUMENT` is exempt.
 *
 * Which names those are depends on [builtins], so this is the one rule that runs over the FINISHED tree
 * from [com.fromwau.klap.cli] rather than per-node inside `build()`: a subcommand's build() already ran when
 * its `command(...)` was declared, which may have been before the root reached its `builtins { }` block.
 * [globalSpecs] belongs to [root] alone; a nested node is checked on its own specs.
 */
internal fun validateReservedNames(
    root: Command,
    globalSpecs: List<NamedSpec>,
    builtins: Builtins,
) {
    val reservedLong = reservedLongNames(builtins)
    val reservedShort = reservedShortNames(builtins)

    fun check(commandName: String, specs: List<HolderSpec>, globals: List<NamedSpec>) {
        for (spec in specs.filterIsInstance<NamedSpec>() + globals) {
            for (long in spec.longs) {
                require(long !in reservedLong) {
                    "command '$commandName': option/flag '--$long' uses a name reserved by a klap built-in"
                }
            }
            for (short in spec.shorts) {
                require(short !in reservedShort) {
                    "command '$commandName': option/flag short '-$short' is reserved by the built-in --help"
                }
            }
            if (spec is FlagSpec) {
                for (long in spec.negativeLongs) {
                    require(long !in reservedLong) {
                        "command '$commandName': flag '${spec.token()}' negation '--$long' uses a name reserved by a klap built-in"
                    }
                }
                for (short in spec.negativeShorts) {
                    require(short !in reservedShort) {
                        "command '$commandName': flag '${spec.token()}' negation short '-$short' is reserved by the built-in --help"
                    }
                }
            }
        }
    }

    fun walk(command: Command) {
        check(command.name, command.specs, emptyList())
        command.subcommands.forEach(::walk)
    }

    check(root.name, root.specs, globalSpecs)
    root.subcommands.forEach(::walk)
}

/**
 * Rejects an option/flag whose `group(...)` heading equals a section `--help` emits itself
 * ([RESERVED_SECTIONS]), which would otherwise render a duplicate heading (see [requireUnreservedSectionTitle]).
 */
internal fun validateSectionTitles(name: String, specs: List<HolderSpec>) {
    for (spec in specs.filterIsInstance<NamedSpec>()) {
        requireUnreservedSectionTitle(name, "option/flag '${spec.token()}'", spec.section)
    }
}

/**
 * A `group(...)` title equal to a heading `--help` emits on its own ([RESERVED_SECTIONS]) renders a second,
 * duplicate heading in the output. [title] is null for the untitled default block, which is always allowed.
 * Shared by [validateReservedNames] (option/flag sections) and [validateSubcommands] (subcommand sections).
 */
private fun requireUnreservedSectionTitle(commandName: String, owner: String, title: String?) {
    require(title !in RESERVED_SECTIONS) {
        "command '$commandName': $owner is placed under group '$title', which collides with the built-in " +
                "'--help' section heading of the same name; rename the group"
    }
}

/** The first value that occurs more than once (in first-encounter order), or null if all are distinct. */
private fun <T> List<T>.firstDuplicate(): T? =
    groupingBy { it }
        .eachCount()
        .entries
        .firstOrNull { it.value > 1 }
        ?.key
