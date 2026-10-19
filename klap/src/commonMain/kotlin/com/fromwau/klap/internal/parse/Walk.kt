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

/** What the subcommand walk does with one word in a command's scope. */
internal sealed interface RouteStep {
    /** The word names [child], which the walk descends into. */
    class Descend(val child: Command) : RouteStep

    /** The word names two children at once, [match], and stops the walk. */
    class Ambiguous(val match: SubcommandMatch.Ambiguous) : RouteStep

    /** A word [routesTransparently] lets pass, which the walk steps over (`app --help build` reaches build). */
    data object StepOver : RouteStep

    /** A run of the command's own option words ending before [end], which stays on its segment. */
    class OwnRun(val end: Int) : RouteStep

    /** A word that stops the walk: the command reached reads it. */
    data object Stop : RouteStep
}

/**
 * Reads one word of a line for the subcommand walk: the step both the parse's walk and the built-in scan route by.
 * A word the command's own options refuse stops the walk, so it never reaches a child the parse would not.
 *
 * [freshGlobals] seeds the accumulator each trial read of an option run needs, since a read tops it up.
 */
internal class SubcommandRouter(
    private val cli: Cli,
    private val freshGlobals: () -> GlobalAccumulator,
) {
    private val globalFlagShorts = cli.globalSpecs.filterIsInstance<FlagSpec>().flatMap { it.shorts }.toSet()

    /** How the word at [at] of [words] routes in [cmd]'s scope; [firstWord] says whether it is [cmd]'s first. */
    fun step(
        cmd: Command,
        words: List<String>,
        at: Int,
        firstWord: Boolean,
    ): RouteStep {
        val token = words[at]
        return when (val match = cmd.resolveSubcommand(token, cli.abbreviationAt(cmd) == Abbreviation.All)) {
            is SubcommandMatch.One -> RouteStep.Descend(match.command)
            is SubcommandMatch.Ambiguous -> RouteStep.Ambiguous(match)
            SubcommandMatch.None -> if (cli.routesTransparently(token, globalFlagShorts, cmd)) {
                RouteStep.StepOver
            } else {
                cmd.ownOptionsEnd(words, at, freshGlobals, firstWord)?.let(RouteStep::OwnRun) ?: RouteStep.Stop
            }
        }
    }
}

/**
 * Walks [argv], the line without its globals, down the subcommand tree by [SubcommandRouter]: a run of the command's
 * own option words stays on its segment, so `git remote -v add` reaches add with `-v` remote's.
 *
 * [freshGlobals] seeds the accumulator each trial read of an option run needs, since a read tops it up.
 */
internal fun Cli.walkSubcommands(
    argv: List<String>,
    freshGlobals: () -> GlobalAccumulator,
): SubcommandWalk {
    val router = SubcommandRouter(this, freshGlobals)
    var cmd: Command = this
    val passed = mutableListOf<PassedCommand>()
    val names = mutableListOf<Int>()
    var own = mutableListOf<Int>()
    // Keep the while form: a for/withIndex loop reassigning `cmd` miscompiles on Kotlin/Native 2.4.10 debug
    // builds (KT-87261, fixed in 2.4.20-Beta2) and passes on the JVM; retest before converting.
    var idx = 0
    while (idx < argv.size) {
        when (val step = router.step(cmd, argv, idx, firstWord = own.isEmpty())) {
            is RouteStep.Descend -> {
                passed += PassedCommand(cmd, own)
                own = mutableListOf()
                cmd = step.child
                names += idx
                idx += 1
            }

            is RouteStep.Ambiguous ->
                return SubcommandWalk(cmd, passed, names, routing = false, ambiguous = argv[idx] to step.match)

            RouteStep.StepOver -> idx += 1

            is RouteStep.OwnRun -> {
                own += idx until step.end
                idx = step.end
            }

            RouteStep.Stop -> return SubcommandWalk(cmd, passed, names, routing = false)
        }
    }
    return SubcommandWalk(cmd, passed, names, routing = true)
}

/**
 * The end of the shortest run of [argv] from [from] that this command reads as its own options and nothing else, or
 * null when the word there is none of its options. A leaf has no child to route to, and a help request stays where
 * the help scan finds it, so neither yields a run. [firstWord] says whether the word at [from] is the command's first.
 */
internal fun Command.ownOptionsEnd(
    argv: List<String>,
    from: Int,
    freshGlobals: () -> GlobalAccumulator,
    firstWord: Boolean = true,
): Int? {
    if (subcommands.isEmpty()) return null
    if (namesHelpShort(argv[from], freshGlobals().at(this), firstWord = false)) return null
    return optionRunEnd(argv, from, freshGlobals, firstWord)
}

/**
 * The end of the shortest run of [argv] from [from] that this command's [sift] reads as options alone, or null when
 * none does. The run is the option and the value it takes from the next word, so `--workdir build` is one run
 * whatever the value is spelled. [firstWord] says whether the word at [from] is the command's first.
 */
internal fun Command.optionRunEnd(
    argv: List<String>,
    from: Int,
    freshGlobals: () -> GlobalAccumulator,
    firstWord: Boolean = true,
): Int? {
    val first = argv[from]
    if (first == END_OF_OPTIONS || !first.isFlagLike()) return null
    for (end in from + 1..argv.size) {
        val read = sift(argv.subList(from, end), freshGlobals().at(this), leadsSegment = firstWord)
        if (read.positionals.isNotEmpty()) return null
        when (read.error) {
            null -> return end
            is CliError.MissingOptionValue -> continue
            else -> return null
        }
    }
    return null
}
