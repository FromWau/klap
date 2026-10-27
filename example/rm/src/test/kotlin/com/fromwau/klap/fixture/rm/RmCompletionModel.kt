package com.fromwau.klap.fixture.rm

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.marked
import com.fromwau.klap.fixture.rm.RmLineContext.assertTab
import com.fromwau.klap.fixture.rm.RmLineContext.assertValues
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter

/*
 * What Tab should offer on an rm line, computed from the table in RmModel.kt: every name described by rm's own help,
 * a value in rm's case only, and an operand slot handing the word to the shell's file completion. rm refuses no
 * repeat and has no options that exclude each other, so nothing given on the line rules a name out; it refuses every
 * line holding an ambiguous long, so nothing is offered after one.
 */

/** One entry rm would accept, and whether its description is one a test of its own checks whole ([WORDED_INPUTS]). */
private data class Offer(
    val suggestion: Suggestion,
    val worded: Boolean,
)

private val FILES_OFFER = Offer(filesAfter(), worded = false)

private fun Input.worded(spelled: String): Boolean = name in WORDED_INPUTS || spelled == "-R"

private fun Input.offer(spelled: String): Offer = Offer(Suggestion(spelled, help), worded(spelled))

/** Every name rm accepts after a dash, in rm's words, in `rm --help` order. */
private val POOL: List<Offer> by lazy {
    INPUTS.flatMap { input -> listOfNotNull(input.long) + input.shorts.map { "-$it" } }
        .map { spelled -> (inputOfLong(spelled) ?: inputFor(spelled[1])!!).offer(spelled) } +
        Offer(Suggestion("--help", HELP_HELP), worded = true) +
        Offer(Suggestion("--version", VERSION_HELP), worded = true)
}

/** Whether getopt has read every option of [words] and the next word is an operand whatever it spells. */
private fun endsOptions(words: List<String>): Boolean = "--" in words

/** What real rm would accept for the word [current] (as far as typed) after [head]. */
private fun rmOffers(
    head: List<String>,
    current: String,
): List<Offer> {
    val options = if (endsOptions(head)) head.takeWhile { it != "--" } else head
    // `rm --v a`: "option '--v' is ambiguous", whatever follows it.
    if (options.any { it.startsWith("--") && isAmbiguousLong(it.substringBefore('=')) }) return emptyList()
    if (endsOptions(head) || !current.startsWith("-")) return listOf(FILES_OFFER)
    if (current.startsWith("--") && '=' in current) {
        val eq = current.indexOf('=')
        val input = inputOfLong(current.take(eq)) ?: return emptyList()
        val kind = input.kind as? Kind.Value ?: return emptyList()
        val head = current.take(eq + 1)
        return kind.words
            .filter { it.startsWith(current.drop(eq + 1)) }
            .map { Offer(Suggestion(head + it, input.help), input.worded(input.long!!)) }
    }
    if (!current.startsWith("--") && current.length > 1) return clusterOffers(current)
    return POOL.filter { it.suggestion.value.startsWith(current) }
}

/** What rm continues [cluster] with: every flag letter not in it yet, since rm counts none of them. */
private fun clusterOffers(cluster: String): List<Offer> {
    val letters = cluster.drop(1)
    if (letters.any { inputFor(it) == null }) return emptyList()
    return INPUTS
        .flatMap { input -> input.shorts.map { it to input } }
        .filter { (letter, _) -> letter !in letters }
        .map { (letter, input) -> Offer(Suggestion(cluster + letter, input.help), input.worded("-$letter")) }
}

/** The words of [line] before the word under [cursor], the word as far as typed, and its start. */
private fun split(
    line: String,
    cursor: Int,
): Pair<List<String>, String> {
    val start = line.lastIndexOf(' ', cursor - 1) + 1
    val head = line.take(start).split(' ').filter { it.isNotEmpty() }.drop(1)
    return head to line.substring(start, cursor)
}

private fun offersAt(
    line: String,
    cursor: Int,
): List<Offer> {
    val (head, current) = split(line, cursor)
    return rmOffers(head, current)
}

/**
 * One Tab press: which cursor position and form it is ([kind]), what is being completed ([subject]), the line and
 * cursor, and what real rm would accept there ([rm]); [worded] when one of them is an entry of [WORDED_INPUTS], -R,
 * --help or --version.
 */
internal data class CompletionCell(
    val kind: String,
    val subject: String,
    val line: String,
    val cursor: Int,
    val rm: Set<Suggestion>,
    val worded: Boolean,
) {
    override fun toString(): String = "$kind, $subject: ${marked(line, cursor)}"

    private val typed: String get() = split(line, cursor).second

    /** Whether the word typed so far is also the start of a klap built-in rm does not have. */
    val builtinPrefix: Boolean
        get() = typed.startsWith("-") && '=' !in typed && KLAP_ONLY_LONGS.any { it.startsWith(typed) }

    /** Whether the word typed so far is also the start of [INVENTED_LONG], a long rm does not have. */
    val inventedPrefix: Boolean
        get() = typed.startsWith("-") && '=' !in typed && INVENTED_LONG.startsWith(typed)

    /** Whether the word typed so far starts a name rm lacks. */
    val foreignPrefix: Boolean get() = builtinPrefix || inventedPrefix
}

internal fun cell(
    kind: String,
    subject: String,
    line: String,
    cursor: Int = line.length,
): CompletionCell {
    val offers = offersAt(line, cursor)
    return CompletionCell(kind, subject, line, cursor, offers.map { it.suggestion }.toSet(), offers.any { it.worded })
}

/** [marked] with its `|` taken out, the cursor where it stood, and what real rm accepts there. */
internal fun slot(
    row: String,
    marked: String,
): CompletionCell {
    val cursor = marked.indexOf('|')
    require(cursor >= 0 && marked.indexOf('|', cursor + 1) < 0) { "one | marks the cursor in `$marked`" }
    return cell(row, row, marked.removeRange(cursor, cursor + 1), cursor)
}

/** Tab on [cell] offers rm's set. */
internal fun Cli.assertRm(cell: CompletionCell) = assertTab("$cell", cell.line, cell.cursor, cell.rm)

internal fun Cli.checkSlots(cells: List<CompletionCell>) = checkCells(cells) { assertRm(it) }

internal fun Cli.checkSlots(vararg cells: CompletionCell) = checkSlots(cells.toList())

/** Checks only the values Tab offers, for cells whose descriptions are a target of their own. */
internal fun Cli.checkSlotValues(cells: List<CompletionCell>) =
    checkCells(cells) { assertValues("$it", it.line, it.cursor, it.rm) }

internal fun Cli.checkSlotValues(vararg cells: CompletionCell) = checkSlotValues(cells.toList())

// --- what a cell completes, in rm's terms ---

internal const val OPERAND = "an operand"
internal const val RM_NAME = "a long name rm has"
internal const val UNKNOWN_NAME = "a long name rm does not have"
internal const val LETTERS = "the letters a cluster continues with"
internal const val WHEN_VALUE = "a value of --interactive"
internal const val ALL_VALUE = "a value of --preserve-root"
internal const val INVENTED_PREFIX = "a prefix of --interactive that --interactive-once shares"
internal const val CAPITALS = "a value typed in capitals"

private const val AFTER = " a b"

private const val P = "rm "

private fun nameSubject(typed: String): String = if (RM_LONGS.any { it.startsWith(typed) }) RM_NAME else UNKNOWN_NAME

/** Every cell the matrix checks, for every input and cursor position. */
internal val CELLS: List<CompletionCell> by lazy { generateCells() }

/** The cells of [kind] completing one of [subjects]. */
internal fun cells(
    kind: String,
    vararg subjects: String,
): List<CompletionCell> = CELLS.filter { it.kind == kind && (subjects.isEmpty() || it.subject in subjects) }

private fun generateCells(): List<CompletionCell> = buildList {
    add(cell("no word", OPERAND, P))
    add(cell("no word", OPERAND, P + AFTER, cursor = P.length))
    add(cell("no word", OPERAND, "rm a b "))
    add(cell("no word", OPERAND, "rm -f "))
    add(cell("no word", OPERAND, "rm -rf "))
    add(cell("word start", OPERAND, "rm abc", cursor = P.length))
    add(cell("word start", OPERAND, "rm --verbose", cursor = P.length))
    add(cell("operand inside", OPERAND, "rm abc", cursor = P.length + 1))
    add(cell("operand end", OPERAND, "rm abc"))
    add(cell("operand end", OPERAND, "rm abc a b", cursor = P.length + 3))
    add(cell("operand end", OPERAND, "rm -f abc"))
    add(cell("end of options", OPERAND, "rm -- "))
    add(cell("end of options", OPERAND, "rm -- -"))
    add(cell("end of options", OPERAND, "rm -- --verb"))
    add(cell("end of options", OPERAND, "rm -f -- -r"))
    add(cell("dash", LETTERS, "rm -"))
    add(cell("dash", LETTERS, "rm --"))
    add(cell("dash", LETTERS, "rm -" + AFTER, cursor = P.length + 1))
    for (long in RM_LONGS) {
        val subject = nameSubject(long)
        add(cell("long end", subject, P + long))
        add(cell("long end", subject, P + long + AFTER, cursor = P.length + long.length))
        add(cell("long inside", nameSubject(long.dropLast(1)), P + long, cursor = P.length + long.length - 1))
        abbreviationOf(long)?.let { add(cell("long abbreviated", nameSubject(it), P + it)) }
    }
    for (long in KLAP_ONLY_LONGS + "--help-all" + INVENTED_LONG) {
        add(cell("long end", UNKNOWN_NAME, P + long))
        add(cell("long inside", nameSubject(long.dropLast(1)), P + long, cursor = P.length + long.length - 1))
    }
    for (prefix in listOf("--j", "--col", "--com", "--do", "--interactive-")) {
        add(cell("long abbreviated", UNKNOWN_NAME, P + prefix))
    }
    for (input in INPUTS) {
        for (letter in input.shorts) {
            add(cell("short end", LETTERS, "rm -$letter"))
            add(cell("short end", LETTERS, "rm -$letter$AFTER", cursor = P.length + 2))
            val partner = if (letter == 'v') 'd' else 'v'
            add(cell("cluster end", LETTERS, "rm -$partner$letter"))
            add(cell("cluster end", LETTERS, "rm -$partner$letter$AFTER", cursor = P.length + 3))
            add(cell("cluster inside", LETTERS, "rm -$partner${letter}z", cursor = P.length + 3))
        }
    }
    for (input in INPUTS) {
        val kind = input.kind as? Kind.Value ?: continue
        val long = input.long!!
        val subject = if (long == "--interactive") WHEN_VALUE else ALL_VALUE
        val partial = kind.words.first().take(1)
        val typedEnd = P.length + long.length + 1 + partial.length
        add(cell("value bare", OPERAND, "$P$long "))
        add(cell("value bare", OPERAND, "$P$long $AFTER", cursor = P.length + long.length + 1))
        add(cell("value bare", OPERAND, "$P$long $partial"))
        add(cell("value attached", subject, "$P$long="))
        add(cell("value attached", subject, "$P$long=$partial"))
        add(cell("value attached", subject, "$P$long=$partial$AFTER", cursor = typedEnd))
        add(cell("value attached", subject, "$P$long=${partial}zz", cursor = typedEnd))
        abbreviationOf(long)?.let { prefix ->
            add(cell("value abbreviated", if (long == "--interactive") INVENTED_PREFIX else OPERAND, "$P$prefix "))
        }
        add(cell("value capitals", CAPITALS, "$P$long=${kind.words.first().take(2).uppercase()}"))
    }
}

internal val NAME_KINDS = listOf("long end", "long inside", "long abbreviated")

internal val LETTER_KINDS = listOf("dash", "short end", "cluster end", "cluster inside")

internal val VALUE_KINDS = listOf("value bare", "value attached", "value abbreviated", "value capitals")

/** The cells whose answer holds no [CompletionCell.worded] entry and which start no name rm lacks. */
internal fun List<CompletionCell>.plain(): List<CompletionCell> = filterNot { it.worded || it.foreignPrefix }

/** The cells whose answer holds a [CompletionCell.worded] entry, and which start no name rm lacks. */
internal fun List<CompletionCell>.wordedOnly(): List<CompletionCell> = filter { it.worded && !it.foreignPrefix }
