package com.fromwau.klap.internal.parse

import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.Command
import com.fromwau.klap.SubcommandMatch
import com.fromwau.klap.abbreviationAt
import com.fromwau.klap.internal.spec.FlagSpec
import com.fromwau.klap.internal.spec.shorts
import com.fromwau.klap.resolveSubcommand
import com.fromwau.klap.routesTransparently

/** A command the walk passed on its way down, with the indices of the option words given on its own segment. */
internal class PassedCommand(val command: Command, val words: List<Int>)

/**
 * Where the subcommand walk stopped: [cmd] is the deepest command reached, [passed] the commands above it in line
 * order with their own option words, and [names] the indices of the words that named a subcommand. It is still
 * [routing] when no word stopped it, so the next word may name a child of [cmd]; [ambiguous] is the word, and its
 * match, that named two children at once and stopped it.
 */
internal class SubcommandWalk(
    val cmd: Command,
    val passed: List<PassedCommand>,
    val names: List<Int>,
    val routing: Boolean,
    val ambiguous: Pair<String, SubcommandMatch.Ambiguous>? = null,
) {
    /** The indices of a line of [size] words left for [cmd]: every word neither a name nor a passed command's. */
    fun kept(size: Int): List<Int> {
        val taken = names.toSet() + passed.flatMap { it.words }
        return (0 until size).filterNot { it in taken }
    }
}

/**
 * Walks [argv], the line without its globals, down the subcommand tree. A word naming a child of the command reached
 * descends into it; a word [routesTransparently] lets pass is stepped over (`app --help build` reaches build); a run
 * of the command's own option words stays on its segment, so `git remote -v add` reaches add with `-v` remote's. Any
 * other word stops the walk, and the command reached reads it.
 *
 * [freshGlobals] seeds the accumulator each trial read of an option run needs, since a read tops it up.
 */
internal fun Cli.walkSubcommands(
    argv: List<String>,
    freshGlobals: () -> GlobalAccumulator,
): SubcommandWalk {
    val globalFlagShorts = globalSpecs.filterIsInstance<FlagSpec>().flatMap { it.shorts }.toSet()
    var cmd: Command = this
    val passed = mutableListOf<PassedCommand>()
    val names = mutableListOf<Int>()
    var own = mutableListOf<Int>()
    // Keep the while form: a for/withIndex loop reassigning `cmd` miscompiles on Kotlin/Native 2.4.10 debug
    // builds (KT-87261, fixed in 2.4.20-Beta2) and passes on the JVM; retest before converting.
    var idx = 0
    while (idx < argv.size) {
        val token = argv[idx]
        when (val match = cmd.resolveSubcommand(token, abbreviationAt(cmd) == Abbreviation.All)) {
            is SubcommandMatch.One -> {
                passed += PassedCommand(cmd, own)
                own = mutableListOf()
                cmd = match.command
                names += idx
                idx += 1
            }

            is SubcommandMatch.Ambiguous ->
                return SubcommandWalk(cmd, passed, names, routing = false, ambiguous = token to match)

            SubcommandMatch.None -> {
                if (routesTransparently(token, globalFlagShorts, cmd)) {
                    idx += 1
                } else {
                    val end = cmd.ownOptionsEnd(argv, idx, freshGlobals)
                        ?: return SubcommandWalk(cmd, passed, names, routing = false)
                    own += idx until end
                    idx = end
                }
            }
        }
    }
    return SubcommandWalk(cmd, passed, names, routing = true)
}

/**
 * The end of the shortest run of [argv] from [from] that this command reads as its own options and nothing else, or
 * null when the word there is none of its options. The run is the option and the value it takes from the next word,
 * so `--workdir build` is one run whatever the value is spelled. A leaf has no child to route to, and a help request
 * stays where the help scan finds it, so neither yields a run.
 */
private fun Command.ownOptionsEnd(
    argv: List<String>,
    from: Int,
    freshGlobals: () -> GlobalAccumulator,
): Int? {
    val first = argv[from]
    if (subcommands.isEmpty() || first == END_OF_OPTIONS || !first.isFlagLike()) return null
    if (namesHelpShort(first, freshGlobals().at(this), firstWord = false)) return null
    for (end in from + 1..argv.size) {
        val read = sift(argv.subList(from, end), freshGlobals().at(this))
        if (read.positionals.isNotEmpty()) return null
        when (read.error) {
            null -> return end
            is CliError.MissingOptionValue -> continue
            else -> return null
        }
    }
    return null
}
