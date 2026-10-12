package com.fromwau.klap.internal.parse

import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.Command
import com.fromwau.klap.SubcommandMatch
import com.fromwau.klap.internal.spec.OptionSpec
import com.fromwau.klap.internal.spec.longs
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.resolveSubcommand
import com.fromwau.klap.takesNextWord

/**
 * The token a flag-spelled command stands as once [hoistCommands] has moved it, which the subcommand walk
 * resolves like a word. A NUL cannot reach argv, which is a list of C strings, so no typed word is ever one.
 */
internal val Command.hoistedToken: String get() = "\u0000$name"

/**
 * [argv] with its flag-spelled and `prio` commands moved, or the refusal the line earns, with what could still be
 * moved; [prio] says whether the line reaches a `prio` command.
 */
internal class Hoisted(val tokens: List<String>, val error: CliError?, val prio: Boolean)

/**
 * [argv] with each flag-spelled command moved to where its parent's subcommand word would stand, so the walk
 * routes `pacman -i -Q pkg` and `pacman -iQ pkg` as it routes a word: to `-Q`, with `-i pkg` its own segment.
 * The spelling leaves its token, a whole `-Q` or `--query` going and a letter leaving its cluster (`-iQ` is
 * `-i`), and the command's [hoistedToken] stands at the start of the parent's segment. Below it the same
 * happens one level down, and a word subcommand is followed as the walk follows it.
 *
 * A `prio` command's word moves to the front of its parent's segment too, past the options before it, so it
 * answers ahead of them: `mytool --completion bash license` runs license. An operand before it keeps it in place.
 *
 * A letter is the command's only where it does not sit in a value: past a value-taking letter, or in the word
 * after one, it is that option's. Two commands of one parent on a line are refused, keeping the first, and so is
 * one command named twice (`pacman -QQ`: "only one operation may be used at a time").
 */
internal fun Cli.hoistCommands(argv: List<String>): Hoisted {
    val tokens = argv.toMutableList()
    val globals = globalLookup()
    val infer = abbreviation != Abbreviation.None
    val inferSubcommands = abbreviation == Abbreviation.All
    var level: Command = this
    var start = 0
    var error: CliError? = null
    var prio = false
    while (true) {
        val scan = level.scanLevel(tokens, start, globals, infer, inferSubcommands)
        val chosen = scan.hits.firstOrNull()?.command
        if (chosen == null) {
            var word = scan.wordAt ?: break
            level = (level.resolveSubcommand(tokens[word], inferSubcommands) as SubcommandMatch.One).command
            if (level.prio && !scan.operandBefore) {
                tokens.add(start, tokens.removeAt(word))
                word = start
            }
            prio = prio || level.prio
            start = word + 1
            continue
        }
        prio = prio || chosen.prio
        val other = scan.hits.firstOrNull { it.command !== chosen }
        if (other != null && error == null) {
            error = CliError.MutuallyExclusive(listOf(chosen.token, other.command.token))
        }
        val valued = scan.hits.firstOrNull { it.letter == null && '=' in tokens[it.token] }
        if (valued != null && error == null) error = CliError.FlagTakesNoValue(valued.command.token)
        val parentOptions = level.options + globals.optionSpecs
        val own = scan.hits.filter { it.command === chosen && !chosen.holdsAsValue(tokens, it, globals, parentOptions) }
        if (own.isEmpty()) break
        if (own.size > 1 && error == null) error = CliError.RepeatedCommand(chosen.token)
        // From the end, so an index still to be edited is not shifted by an edit already made.
        for (hit in own.sortedWith(compareByDescending<Hit> { it.token }.thenByDescending { it.letter ?: -1 })) {
            val letter = hit.letter
            if (letter == null) {
                tokens.removeAt(hit.token)
            } else {
                val rest = tokens[hit.token].removeRange(letter + 1, letter + 2)
                if (rest == "-") tokens.removeAt(hit.token) else tokens[hit.token] = rest
            }
        }
        tokens.add(start, chosen.hoistedToken)
        level = chosen
        start += 1
    }
    return Hoisted(tokens, error, prio)
}

/** A flag-spelled command named at [token], by the whole token or by its cluster letter at [letter] after the dash. */
private class Hit(val command: Command, val token: Int, val letter: Int?)

/**
 * What one level's segment names: the flag-spelled commands found in it, where a word subcommand stands, and
 * whether an operand stands before that word.
 */
private class LevelScan(val hits: List<Hit>, val wordAt: Int?, val operandBefore: Boolean = false)

/**
 * Walks this level's segment from [start] for its flag-spelled children, stopping at `--` or at a word that
 * names a word subcommand (whose segment follows it). A word in the value slot of a global or of this level's
 * own option or of a valued built-in (`--completion bash`) is skipped, as is a cluster's rest past such an
 * option's letter.
 */
private fun Command.scanLevel(
    tokens: List<String>,
    start: Int,
    globals: GlobalAccumulator,
    infer: Boolean,
    inferSubcommands: Boolean,
): LevelScan {
    val flagSubs = flagSubcommands
    val hits = mutableListOf<Hit>()
    val valueOptions = options + globals.optionSpecs
    val pool = longMatchPool(globals)
    var operandBefore = false
    var i = start
    while (i < tokens.size) {
        val token = tokens[i]
        if (token == END_OF_OPTIONS) break
        var takesNext = false
        when {
            token.startsWith("--") -> {
                val typed = token.drop(2).substringBefore('=')
                val long = when (val match = resolveLong(typed, pool, infer)) {
                    is NameMatch.Exact -> match.name
                    is NameMatch.Prefix -> match.name
                    else -> null
                }
                val command = long?.let { name -> flagSubs.firstOrNull { "--$name" in it.flagSpellings } }
                if (command != null) {
                    hits += Hit(command, i, null)
                } else if ('=' !in token) {
                    takesNext = valueOptions.any { long in it.longs && it.takesNextWord() } ||
                        long != null && globals.offeredBuiltins().takesNextWord(long)
                }
            }

            // A word Alone refuses names no command and takes no value.
            token.isFlagLike() && refusesCluster(token.drop(1), globals) -> Unit

            token.isFlagLike() -> {
                val letters = token.drop(1)
                for ((j, ch) in letters.withIndex()) {
                    val command = flagSubs.firstOrNull { "-$ch" in it.flagSpellings }
                    if (command != null) {
                        hits += Hit(command, i, j)
                        continue
                    }
                    val option = valueOptions.firstOrNull { ch.toString() in it.shorts } ?: continue
                    takesNext = j == letters.lastIndex && option.takesNextWord()
                    break
                }
            }

            hits.isEmpty() && resolveSubcommand(token, inferSubcommands) is SubcommandMatch.One ->
                return LevelScan(hits, wordAt = i, operandBefore = operandBefore)

            else -> operandBefore = true
        }
        i += if (takesNext) 2 else 1
    }
    return LevelScan(hits, wordAt = null)
}

/**
 * Whether the [hit] spelling of this command sits in the value of one of this command's own options: past its
 * value-taking letter in the same cluster (`-bQ`), or as the word after one (`--dbpath -Q`). Those are read once
 * the command is known, since a letter means what the command on the line gives it. [parentOptions] are the
 * value-taking letters the parent's scan already read, which end a cluster before any of this command's can.
 */
private fun Command.holdsAsValue(
    tokens: List<String>,
    hit: Hit,
    globals: GlobalAccumulator,
    parentOptions: List<OptionSpec>,
): Boolean {
    val own = options
    val letter = hit.letter
    if (letter != null) {
        val before = tokens[hit.token].drop(1).take(letter)
        if (before.any { ch -> own.any { ch.toString() in it.shorts } }) return true
    }
    if (letter != null && letter > 0) return false
    val previous = tokens.getOrNull(hit.token - 1) ?: return false
    if (previous.startsWith("--")) {
        if ('=' in previous) return false
        val long = matchedLong(previous, longMatchPool(globals), globals.inferNames) ?: return false
        return own.any { long in it.longs && it.takesNextWord() }
    }
    if (!previous.isFlagLike()) return false
    // The first value-taking letter takes the rest of its word, so only one ending the word takes the next.
    val letters = previous.drop(1)
    val first = letters.indexOfFirst { ch -> (own + parentOptions).any { ch.toString() in it.shorts } }
    if (first != letters.lastIndex) return false
    return own.any { letters[first].toString() in it.shorts && it.takesNextWord() }
}

/** Whether this option, given in full with no value attached, takes the next word as its value. */
private fun OptionSpec.takesNextWord(): Boolean = !valueAttachedOnly

/**
 * The flag-spelled children of [level] that [tokens] name, once per naming and in that order, read as
 * [hoistCommands] reads that level's segment. Completion asks it of the word under the cursor and of the words after
 * it, which the head's own hoisting does not see.
 */
internal fun Cli.flagCommandsNamed(level: Command, tokens: List<String>): List<Command> {
    if (level.flagSubcommands.isEmpty()) return emptyList()
    val scan = level.scanLevel(
        tokens,
        start = 0,
        globals = globalLookup(),
        infer = abbreviation != Abbreviation.None,
        inferSubcommands = abbreviation == Abbreviation.All,
    )
    return scan.hits.map { it.command }
}
