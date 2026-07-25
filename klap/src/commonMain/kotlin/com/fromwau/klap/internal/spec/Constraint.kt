package com.fromwau.klap.internal.spec

/** How many members of an [InputConstraint] the user may supply, or how a conflict between them resolves. */
internal enum class ConstraintArity {
    /** One member must be supplied, and never two. */
    ExactlyOne,

    /** Zero members is fine; two is not. */
    AtMostOne,

    /**
     * Any number is fine and the LAST one on the line is the one that holds — an override rule, not an
     * exclusivity rule. Alone among the arities it can never fail, so the parse-time check skips it and
     * the resolution happens after binding instead.
     */
    LastWins,
}

/**
 * A rule relating two or more of ONE command's own inputs, declared through
 * [com.fromwau.klap.CommandBuilder.requireExactlyOne] / [com.fromwau.klap.CommandBuilder.requireAtMostOne]
 * and enforced by `Parser.kt`'s bind before any input binds.
 *
 * [members] keeps the order they were passed in, which is the order both the error text and the help hint
 * list them in, so declaration and documentation cannot drift.
 */
internal class InputConstraint(
    val arity: ConstraintArity,
    val members: List<HolderSpec>,
)

/**
 * How a constraint's error text names a member: its primary spelling for an option/flag, `<name>` for a positional.
 * Those are the forms [com.fromwau.klap.CliError.MissingRequiredOption] and
 * [com.fromwau.klap.CliError.MissingArgument] already render, so a constraint error reads like the rest.
 */
internal fun HolderSpec.constraintToken(): String = when (this) {
    is ArgumentSpec -> "<$name>"
    is OptionSpec, is FlagSpec -> token()
}

/**
 * How the help hint names a member: its first short spelling when it has one, else [constraintToken]. The
 * row's own signature column already spells the long name out, and the hint repeats on every member's row,
 * so the compact form keeps a three-way set from tripling the width of three descriptions.
 */
internal fun HolderSpec.constraintHintToken(): String =
    (this as? NamedSpec)?.names?.firstOrNull { !it.startsWith("--") } ?: constraintToken()

/**
 * The `(...)`-note fragment `--help` renders on every member's row (see `Help.kt`'s `metaHint`), computed
 * once here at declaration time so the rule and the sentence describing it come from the same place.
 */
internal fun InputConstraint.hint(): String {
    val list = members.joinToString(", ") { it.constraintHintToken() }
    return when (arity) {
        // Two fragments, `;`-joined by metaHint's own list: "required" then reads alongside the
        // cardinality words every other row uses, instead of inventing a second vocabulary for it.
        ConstraintArity.ExactlyOne -> "one of $list; required"
        ConstraintArity.AtMostOne -> "at most one of $list"
        // One shared sentence rather than a per-member "overrides the others": the set is what the reader
        // needs to see, and naming it the same way on every row makes the membership obvious at a glance.
        ConstraintArity.LastWins -> "last of $list wins"
    }
}
