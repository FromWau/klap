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
import com.fromwau.klap.internal.spec.negationTokens
import com.fromwau.klap.internal.spec.negativeShorts
import com.fromwau.klap.internal.spec.requireValidName
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.internal.spec.token

/**
 * Long names klap's own built-ins answer to on a command, the root when [atRoot]; a declined built-in frees its name,
 * and a `rootOnly` version frees its own below the root.
 */
private fun reservedLongNames(builtins: Builtins, atRoot: Boolean): Set<String> = buildSet {
    if (builtins.help) add("help")
    if (builtins.helpAll) add("help-all")
    if (atRoot || !builtins.versionRootOnly) builtins.versionLong?.let(::add)
    if (builtins.json) add("json")
    if (builtins.completion) add("completion")
    if (builtins.docs) add("docs")
    if (builtins.color) add("color")
}

/** Short names klap's own built-ins consume: `-h` while it is offered, and the version short a tool set. */
private fun reservedShortNames(builtins: Builtins, atRoot: Boolean): Set<String> = setOfNotNull(
    builtins.helpShort?.toString(),
    builtins.versionShort?.takeIf { atRoot || !builtins.versionRootOnly }?.toString(),
)

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
            it.hidden &&
                (cardinality == Cardinality.Required || (cardinality is Cardinality.Multiple && cardinality.min >= 1))
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
 * The two cardinalities a `lastOf` fold can never satisfy, both because the fold's own [OptionSpec]
 * never gets a raw occurrence (only its members do). `.required()` makes `bindFlagsAndOptions` take the
 * missing-required branch on every line, even one that supplied a member, and name the fold's synthetic
 * label, which nothing on the command line spells. `.multiple()` leaves `resolveFolds` writing
 * [com.fromwau.klap.internal.parse.absentValue] — null — into an accessor whose own type is a non-null
 * `List`, and the NPE lands inside user code.
 *
 * Checked over the finished spec list rather than inside `lastOf(...)`: both mutate the shared spec and
 * may legally run after the `lastOf(...)` line, so a check at the call site would pass or fail on
 * declaration order alone. Same reasoning as [validateLastWinsMembers].
 */
internal fun validateFoldCardinality(name: String, specs: List<HolderSpec>) {
    for (spec in specs.filterIsInstance<OptionSpec>().filter { it.folds.isNotEmpty() }) {
        val fold = "lastOf(${spec.folds.joinToString(", ") { it.constraintToken() }})"
        require(spec.cardinality != Cardinality.Required) {
            "command '$name': $fold cannot be .required(); the fold itself is never written, so require " +
                "one of its members instead"
        }
        require(spec.cardinality !is Cardinality.Multiple) {
            "command '$name': $fold cannot be .multiple(); the fold reports the one member written last " +
                "rather than accumulating occurrences, so collect them on a member instead"
        }
    }
}

/**
 * Two rules over a conditional operand, both checkable only once the spec list is finished.
 *
 * The trigger must be one of this command's own inputs, and `.absentWhen()`/`.optionalWhen()` write it
 * straight onto the shared [ArgumentSpec] from inside [com.fromwau.klap.ConverterScope], which never sees
 * that list (unlike [com.fromwau.klap.CommandBuilder.numberOption] or the `exactlyOne`/`lastWins`
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
        for ((trigger, n) in spec.capsWhen) {
            require(spec.cardinality is Cardinality.Multiple) {
                "command '$name': argument '${spec.name}' declares .atMostWhen(${trigger.constraintToken()}, $n) " +
                    "on a slot that is not .multiple(); only a variadic takes more than one operand to cap"
            }
            require(specs.any { it === trigger }) {
                "command '$name': argument '${spec.name}' declares .atMostWhen(${trigger.constraintToken()}, $n), " +
                    "which is not declared on '$name'; a trigger must be one of this command's own inputs"
            }
        }
        spec.optionalWhen?.let { trigger ->
            require(spec.cardinality is Cardinality.Multiple) {
                "command '$name': argument '${spec.name}' declares " +
                    ".optionalWhen(${trigger.constraintToken()}) on a slot that is not .multiple(); the " +
                    "call drops a declared minimum to zero, and only a variadic has one, so the slot would " +
                    "stay mandatory. Use .absentWhen(${trigger.constraintToken()}) to remove the slot instead"
            }
            require(specs.any { it === trigger }) {
                "command '$name': argument '${spec.name}' declares .optionalWhen(${trigger.constraintToken()}), " +
                    "which is not declared on '$name'; a trigger must be one of this command's own inputs"
            }
        }
    }
}

/**
 * A `dashLed(gather) { }` slot joins every word it takes into one value, so a command has one such slot and
 * it is not `.multiple()`. Checked over the finished specs, since `.multiple()` may come after it.
 */
internal fun validateGatheringSlot(name: String, specs: List<HolderSpec>) {
    val gathering = specs.filterIsInstance<ArgumentSpec>().filter { it.gather != null }
    require(gathering.size <= 1) {
        "command '$name': arguments ${gathering.joinToString { "'${it.name}'" }} each declare dashLed(gather); " +
            "a dash word can gather into one slot only"
    }
    gathering.singleOrNull()?.let { spec ->
        require(spec.cardinality !is Cardinality.Multiple) {
            "command '$name': argument '${spec.name}' combines dashLed(gather) with .multiple(); the gathered " +
                "words are joined into one value"
        }
    }
}

/**
 * An `endsOptions()` slot ends its command's options at the operand that reaches it, so which operand that is
 * must not depend on the rest of the line: one such slot per command, no variadic before it nor after it when
 * it is the variadic, and no slot up to it that `.absentWhen()` removes or `dashLed(gather)` takes out of the
 * count. Checked over the finished specs, since `.multiple()` and `.absentWhen()` may come after it.
 */
internal fun validateEndsOptionsSlot(name: String, specs: List<HolderSpec>, endsAtFirstOperand: Boolean) {
    val positionals = specs.filterIsInstance<ArgumentSpec>()
    val ending = positionals.filter { it.endsOptions }
    require(!endsAtFirstOperand || ending.isEmpty()) {
        "command '$name': optionsEndAtFirstOperand and endsOptions() on '${ending.first().name}' both say where " +
            "options end; keep one"
    }
    require(ending.size <= 1) {
        "command '$name': arguments ${ending.joinToString { "'${it.name}'" }} each declare endsOptions(); " +
            "options end once, at one of them"
    }
    val spec = ending.singleOrNull() ?: return
    val index = positionals.indexOf(spec)
    val variadic = positionals.indexOfFirst { it.cardinality is Cardinality.Multiple }
    require(variadic < 0 || variadic > index || variadic == index && index == positionals.lastIndex) {
        "command '$name': argument '${spec.name}' declares endsOptions() beside the variadic " +
            "'${positionals[variadic].name}', which takes a count of operands only the whole line decides, so " +
            "the operand reaching '${spec.name}' would too"
    }
    val shifting = positionals.take(index + 1).firstOrNull { it.absentWhen != null || it.gather != null }
    require(shifting == null) {
        "command '$name': argument '${spec.name}' declares endsOptions(), and '${shifting?.name}', by its " +
            ".absentWhen() or dashLed(gather), is not an operand slot on every line, so which operand reaches " +
            "'${spec.name}' would depend on the line"
    }
}

/**
 * `.repeated()` sets what a second occurrence does, and a `.multiple()` option or a `.count()` flag takes every
 * occurrence instead, so the two together say nothing coherent. Checked over the finished specs, since either
 * call may come first.
 */
internal fun validateRepeatedPolicies(name: String, specs: List<HolderSpec>) {
    for (spec in specs.filterIsInstance<NamedSpec>().filter { it.repeated != null }) {
        val collects = (spec is OptionSpec && spec.cardinality is Cardinality.Multiple) ||
            (spec is FlagSpec && spec.isCount)
        require(!collects) {
            "command '$name': '${spec.token()}' declares .repeated() but collects every occurrence through " +
                ".multiple() or .count(), which no repeat policy applies to"
        }
    }
}

/**
 * A `.requiredWhen(flag)` trigger must be one of this command's own inputs or a global: the parse-time check
 * reads only those, so a flag from an unrelated command could never fire the rule `--help` advertises.
 */
internal fun validateRequiredWhenTriggers(name: String, specs: List<HolderSpec>, globalSpecs: List<NamedSpec>) {
    for (spec in specs.filterIsInstance<OptionSpec>()) {
        val trigger = spec.requiredWhen ?: continue
        require(specs.any { it === trigger } || globalSpecs.any { it === trigger }) {
            "command '$name': option '${spec.token()}' declares .requiredWhen(${trigger.token()}), which is " +
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
    validateFoldCardinality(command.name, command.specs)
    validateConditionalOperandTriggers(command.name, command.specs)
    validateGatheringSlot(command.name, command.specs)
    validateEndsOptionsSlot(command.name, command.specs, command.optionsEndAtFirstOperand)
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
                    "'--no-...' from; pass the negative spellings explicitly, as .negatable(short = 'P')"
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

/** Every token this input answers to, a negatable flag's negative spellings included. */
private val NamedSpec.allSpellings: List<String>
    get() = if (this is FlagSpec) names + negationTokens else names

/**
 * A flag-spelled command is found by its spelling anywhere among its parent's options, so that spelling must
 * name nothing else there: not one of the parent's own inputs, not a global, not a sibling's, and not one of the
 * command's own inputs, whose spelling the parent would take first. It has no word to alias.
 */

internal fun validateFlagSpelledCommands(
    name: String,
    specs: List<HolderSpec>,
    globalSpecs: List<NamedSpec>,
    subs: List<Command>,
) {
    val flagSubs = subs.filter { it.isFlagSpelled }
    if (flagSubs.isEmpty()) return
    val taken = (specs.filterIsInstance<NamedSpec>() + globalSpecs).flatMap { it.allSpellings }.toSet()
    val duplicate = flagSubs.flatMap { it.flagSpellings }.firstDuplicate()
    require(duplicate == null) { "command '$name': two commands are spelled '$duplicate'" }
    for (sub in flagSubs) {
        require(sub.aliases.isEmpty()) {
            "command '$name': '${sub.token}' is reached by its spelling, so it takes no aliases"
        }
        for (spelling in sub.flagSpellings) {
            require(spelling !in taken) {
                "command '$name': command '${sub.token}' is spelled '$spelling', which an option or global already uses"
            }
            require(sub.namedInputs.none { spelling in it.allSpellings }) {
                "command '${sub.token}': one of its own inputs is spelled '$spelling', the command's own spelling"
            }
        }
    }
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
 * A command declaring neither an action nor subcommands can never do anything: nothing to run, and nothing
 * to dispatch to. It would otherwise parse cleanly and exit 0, which reads as success. Built-ins are exempt
 * because they carry no action by design and the parser answers them from its own `Invocation` cases.
 */
internal fun validateCanActOrDispatch(
    name: String,
    hasAction: Boolean,
    hasSubcommands: Boolean,
    isBuiltin: Boolean = false,
) {
    require(hasAction || hasSubcommands || isBuiltin) {
        "command '$name': declares neither an action nor subcommands, so it can never run; give " +
                "'$name' an action { } or actionSuspending { }, or a subcommand for it to dispatch to"
    }
}

/**
 * A command with no action only routes to subcommands or renders `--help`. Its options and flags stand on its own
 * segment and its children read them, as git's root options are read by every git command, but a positional could
 * never bind: a group's argument slot is consumed as a subcommand token.
 */
internal fun validateActionlessOperands(
    name: String,
    specs: List<HolderSpec>,
    hasAction: Boolean,
    isBuiltin: Boolean = false,
) {
    if (hasAction) return
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
        for (negation in spec.negationTokens) {
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
        .flatMap { spec -> spec.negationTokens }
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
                for (negation in spec.negationTokens) {
                    require(negation !in globalEffectiveNameSet) {
                        "command '$cmdName': flag '${spec.token()}' negation '$negation' collides with a " +
                            "global's name or a global's negation"
                    }
                }
            }
        }
    }

    fun walk(command: Command) {
        for (spelling in command.flagSpellings) {
            require(spelling !in globalEffectiveNameSet) {
                "command '${command.token}': its spelling '$spelling' collides with a global's name or negation"
            }
        }
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
    val rootLong = reservedLongNames(builtins, atRoot = true)
    val rootShort = reservedShortNames(builtins, atRoot = true)
    val belowLong = reservedLongNames(builtins, atRoot = false)
    val belowShort = reservedShortNames(builtins, atRoot = false)

    fun ownerOf(short: String): String =
        if (short == builtins.helpShort?.toString()) "--help" else builtins.versionLong?.let { "--$it" } ?: "version"

    fun check(
        commandName: String,
        specs: List<HolderSpec>,
        globals: List<NamedSpec>,
        reservedLong: Set<String>,
        reservedShort: Set<String>,
    ) {
        for (spec in specs.filterIsInstance<NamedSpec>() + globals) {
            for (long in spec.longs) {
                require(long !in reservedLong) {
                    "command '$commandName': option/flag '--$long' uses a name reserved by a klap built-in"
                }
            }
            for (short in spec.shorts) {
                require(short !in reservedShort) {
                    "command '$commandName': option/flag short '-$short' is reserved by the built-in ${ownerOf(short)}"
                }
            }
            if (spec is FlagSpec) {
                for (long in spec.negativeLongs) {
                    require(long !in reservedLong) {
                        "command '$commandName': flag '${spec.token()}' negation '--$long' uses a name reserved by " +
                            "a klap built-in"
                    }
                }
                for (short in spec.negativeShorts) {
                    require(short !in reservedShort) {
                        "command '$commandName': flag '${spec.token()}' negation short '-$short' is reserved by " +
                            "the built-in ${ownerOf(short)}"
                    }
                }
            }
        }
    }

    // A flag-spelled command stands among its parent's options, so its spelling is checked against the parent's names.
    fun walk(command: Command, parentLong: Set<String>, parentShort: Set<String>) {
        for (spelling in command.flagSpellings) {
            val reserved = if (spelling.startsWith("--")) {
                spelling.drop(2) in parentLong
            } else {
                spelling.drop(1) in parentShort
            }
            require(!reserved) { "command '${command.token}': its spelling '$spelling' is reserved by a klap built-in" }
        }
        check(command.name, command.specs, emptyList(), belowLong, belowShort)
        command.subcommands.forEach { walk(it, belowLong, belowShort) }
    }

    // The root's names hold every name reserved below it, so a global, which binds at every depth, is checked here.
    check(root.name, root.specs, globalSpecs, rootLong, rootShort)
    root.subcommands.forEach { walk(it, rootLong, rootShort) }
}

/**
 * A global OPTION with a digit short, on a tree where some command declares a `numberOption()`. The pre-strip
 * reads a short cluster against the globals alone, so it matches that option on the FIRST digit of a run and
 * takes the rest of the token as its value — `-25` binds `5` — where every other walk reads the run whole
 * and binds 25. The pre-strip runs before any command is resolved, so it cannot consult the number input to
 * settle the disagreement; the pair is refused here instead.
 *
 * A global FLAG is exempt: that arm is all-or-nothing over the cluster, so a run it does not cover in full
 * leaves the whole token to the command's own sift, which does know about the number input.
 *
 * Runs over the FINISHED tree from [com.fromwau.klap.cli] rather than per-node inside `build()`, like
 * [validateReservedNames]: the offending pair is one declaration at the root and another on a command whose
 * own build() ran when its `command(...)` was declared.
 */
internal fun validateGlobalDigitShorts(root: Command, globalSpecs: List<NamedSpec>) {
    val (spec, short) = globalSpecs
        .filterIsInstance<OptionSpec>()
        .firstNotNullOfOrNull { option ->
            option.shorts.firstOrNull { it.singleOrNull()?.isDigit() == true }?.let { option to it }
        } ?: return

    fun numberOwner(command: Command): Command? =
        command.takeIf { it.numberInput != null } ?: command.subcommands.firstNotNullOfOrNull(::numberOwner)

    val owner = numberOwner(root)
    require(owner == null) {
        "cli '${root.name}': global option '${spec.token()}' declares the digit short '-$short', and " +
            "command '${owner?.name}' declares a number input; the pre-strip claims '-$short' from the " +
            "first digit of a run and takes the rest of the token as its value, so '-${short}5' would bind " +
            "'5' instead of the number ${short}5. Give the global a non-digit short, or declare it with " +
            "globalFlag, which takes no value and so leaves the run whole"
    }
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
