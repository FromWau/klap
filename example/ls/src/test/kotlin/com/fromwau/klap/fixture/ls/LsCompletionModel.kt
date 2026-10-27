package com.fromwau.klap.fixture.ls

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.ls.LsLineContext.FILES
import com.fromwau.klap.fixture.ls.LsLineContext.assertTab
import com.fromwau.klap.fixture.ls.LsLineContext.assertValues
import com.fromwau.klap.fixture.marked
import com.fromwau.klap.testing.Suggestion

/*
 * What Tab should offer on an ls line, computed from the table in LsModel.kt: every name real ls 9.12 has, described
 * by `ls --help`, a value in ls's case only and in its argmatch order, and an operand slot handing the word to the
 * shell's file completion. ls refuses no repeat and has no options that exclude each other, so nothing given on the
 * line rules a name out. The word right after an ambiguous long offers nothing, since it cannot be read (K34); an
 * ambiguous long further back leaves the slot under the cursor its own answer (todo 123).
 */

/** Every name ls accepts after a dash, in ls's words, in `ls --help` order. */
internal val POOL: List<Suggestion> by lazy {
    ALL_INPUTS.flatMap { input ->
        (listOfNotNull(input.long) + input.shorts.map { "-$it" }).map { Suggestion(it, input.help) }
    } + Suggestion("--help", HELP_HELP) + Suggestion("--version", VERSION_HELP)
}

/** What a run of words gives: whether options have ended, a value is due, or the last word is an ambiguous long. */
private data class Scan(
    val endOfOptions: Boolean,
    val pending: Input?,
    val ambiguous: Boolean,
)

private fun Input.takesRequiredValue(): Boolean = (kind as? Kind.Value)?.optional == false

/** Reads [words] as ls's getopt_long does, as far as values and the end of options go. */
private fun scan(words: List<String>): Scan {
    var endOfOptions = false
    var pending: Input? = null
    var ambiguous = false
    for (word in words) {
        ambiguous = false
        when {
            pending != null -> pending = null
            endOfOptions -> Unit
            word == "--" -> endOfOptions = true
            word.startsWith("--") -> {
                val name = word.substringBefore('=')
                ambiguous = '=' !in word && isAmbiguousLong(name)
                val input = inputOfLong(name)
                if (input != null && '=' !in word && input.takesRequiredValue()) pending = input
            }
            word.startsWith("-") && word.length > 1 -> {
                for ((at, letter) in word.drop(1).withIndex()) {
                    val input = inputFor(letter) ?: break
                    if (input.kind is Kind.Value) {
                        if (at == word.length - 2) pending = input
                        break
                    }
                }
            }
        }
    }
    return Scan(endOfOptions, pending, ambiguous)
}

/** What real ls would accept for the word [current] (as far as typed) after [head]. */
private fun lsOffers(
    head: List<String>,
    current: String,
): List<Suggestion> {
    val before = scan(head)
    // `ls --r a`: "option '--r' is ambiguous"; the word after it is neither its value nor an operand.
    if (before.ambiguous) return emptyList()
    before.pending?.let { return valueOffers(it, current, head = null) }
    if (before.endOfOptions || !current.startsWith("-")) return listOf(FILES)
    if (current.startsWith("--") && '=' in current) {
        val eq = current.indexOf('=')
        val input = inputOfLong(current.take(eq)) ?: return emptyList()
        if (input.kind !is Kind.Value || isAmbiguousLong(current.take(eq))) return emptyList()
        return valueOffers(input, current.drop(eq + 1), head = current.take(eq + 1))
    }
    if (!current.startsWith("--") && current.length > 1) return clusterOffers(current)
    return POOL.filter { it.value.startsWith(current) }
}

private fun clusterOffers(current: String): List<Suggestion> {
    val letters = current.drop(1)
    val inputs = letters.map { inputFor(it) }
    val valueAt = inputs.indexOfFirst { it?.kind is Kind.Value }
    val known = if (valueAt < 0) inputs else inputs.take(valueAt + 1)
    if (known.any { it == null }) return emptyList()
    if (valueAt >= 0 && valueAt < letters.lastIndex) {
        return valueOffers(inputs[valueAt]!!, letters.drop(valueAt + 1), head = current.take(valueAt + 2))
    }
    if (valueAt >= 0) return if (letters.length == 1) POOL.filter { it.value == current } else emptyList()
    return ALL_INPUTS
        .flatMap { input -> input.shorts.map { it to input } }
        .filter { (letter, _) -> letter !in letters }
        .map { (letter, input) -> Suggestion(current + letter, input.help) }
}

/** The values of [input] starting with [partial]: bare in their own word, after [head] with the option's help. */
private fun valueOffers(
    input: Input,
    partial: String,
    head: String?,
): List<Suggestion> {
    val words = (input.kind as Kind.Value).words ?: return emptyList()
    return words
        .filter { it.startsWith(partial) }
        .map { word -> if (head == null) Suggestion(word) else Suggestion(head + word, input.help) }
}

/** The words of [line] before the word under [cursor], and that word as far as typed. */
private fun split(
    line: String,
    cursor: Int,
): Pair<List<String>, String> {
    val start = line.lastIndexOf(' ', cursor - 1) + 1
    val head = line.take(start).split(' ').filter { it.isNotEmpty() }.drop(1)
    return head to line.substring(start, cursor)
}

/** What real ls would accept at [cursor] on [line], which starts with `ls`. */
internal fun lsOffersAt(
    line: String,
    cursor: Int,
): List<Suggestion> {
    val (head, current) = split(line, cursor)
    return lsOffers(head, current)
}

/**
 * One Tab press: which cursor position and form it is ([kind]), what is being completed ([subject]), the line and
 * cursor, and what real ls would accept there ([ls]).
 */
internal data class CompletionCell(
    val kind: String,
    val subject: String,
    val line: String,
    val cursor: Int,
    val offers: List<Suggestion>,
) {
    val ls: Set<Suggestion> get() = offers.toSet()

    override fun toString(): String = "$kind, $subject: ${marked(line, cursor)}"

    /** Whether the word typed so far is also the start of a klap built-in ls does not have. */
    val foreign: Boolean
        get() = split(line, cursor).let { (head, typed) ->
            !scan(head).endOfOptions && typed.startsWith("-") && '=' !in typed &&
                KLAP_ONLY_LONGS.any { it.startsWith(typed) }
        }
}

internal fun cell(
    kind: String,
    subject: String,
    line: String,
    cursor: Int = line.length,
): CompletionCell = CompletionCell(kind, subject, line, cursor, lsOffersAt(line, cursor))

/** [marked] with its `|` taken out, the cursor where it stood, and what real ls accepts there. */
internal fun slot(
    row: String,
    marked: String,
): CompletionCell {
    val cursor = marked.indexOf('|')
    require(cursor >= 0 && marked.indexOf('|', cursor + 1) < 0) { "one | marks the cursor in `$marked`" }
    return cell(row, row, marked.removeRange(cursor, cursor + 1), cursor)
}

/** Tab on [cell] offers ls's set. */
internal fun Cli.assertLs(cell: CompletionCell) = assertTab("$cell", cell.line, cell.cursor, cell.ls)

internal fun Cli.checkSlots(cells: List<CompletionCell>) = checkCells(cells) { assertLs(it) }

internal fun Cli.checkSlots(vararg cells: CompletionCell) = checkSlots(cells.toList())

/** Checks only the values Tab offers, where a cell's claim is the names and not their descriptions. */
internal fun Cli.checkSlotValues(cells: List<CompletionCell>) =
    checkCells(cells) { assertValues("$it", it.line, it.cursor, it.ls) }

internal fun Cli.checkSlotValues(vararg cells: CompletionCell) = checkSlotValues(cells.toList())

// --- what a cell completes, in ls's terms ---

internal const val OPERAND = "an operand"
internal const val LS_NAME = "a long name ls has"
internal const val UNKNOWN_NAME = "a long name ls does not have"
internal const val LETTERS = "the letters a cluster continues with"
internal const val VALUE_LETTER = "a letter taking a value"
internal const val CHOICE = "a word of a value"
internal const val TEXT_VALUE = "a pattern, number or size"
internal const val OPTIONAL = "an optional WHEN"
internal const val CAPITALS = "a value typed in capitals"

private const val AFTER = " a b"

private const val P = "ls "

private fun nameSubject(typed: String): String = if (LS_LONGS.any { it.startsWith(typed) }) LS_NAME else UNKNOWN_NAME

private fun valueSubject(
    kind: Kind.Value,
    attached: Boolean,
): String = when {
    kind.optional && !attached -> OPERAND
    kind.optional -> OPTIONAL
    kind.groups != null -> CHOICE
    else -> TEXT_VALUE
}

/** Every cell the matrix checks, for every input and cursor position. */
internal val CELLS: List<CompletionCell> by lazy { generateCells() }

/** The cells of [kind] completing one of [subjects]. */
internal fun cells(
    kind: String,
    vararg subjects: String,
): List<CompletionCell> = CELLS.filter { it.kind == kind && (subjects.isEmpty() || it.subject in subjects) }

internal val NAME_KINDS = listOf("long end", "long inside", "long abbreviated")

internal val LETTER_KINDS = listOf("dash", "short end", "cluster end", "cluster inside")

internal val VALUE_KINDS = listOf(
    "value no word", "value word start", "value spaced", "value attached", "value abbreviated", "value short",
    "value cluster", "value capitals",
)

private fun generateCells(): List<CompletionCell> = buildList {
    add(cell("no word", OPERAND, P))
    add(cell("no word", OPERAND, P + AFTER, cursor = P.length))
    add(cell("no word", OPERAND, "ls a b "))
    add(cell("no word", OPERAND, "ls -l "))
    add(cell("no word", OPERAND, "ls -la "))
    add(cell("word start", OPERAND, "ls abc", cursor = P.length))
    add(cell("word start", OPERAND, "ls --all", cursor = P.length))
    add(cell("operand inside", OPERAND, "ls abc", cursor = P.length + 1))
    add(cell("operand end", OPERAND, "ls abc"))
    add(cell("operand end", OPERAND, "ls abc a b", cursor = P.length + 3))
    add(cell("operand end", OPERAND, "ls -l abc"))
    add(cell("end of options", OPERAND, "ls -- "))
    add(cell("end of options", OPERAND, "ls -- -"))
    add(cell("end of options", OPERAND, "ls -- --al"))
    add(cell("end of options", OPERAND, "ls -l -- -a"))
    add(cell("dash", LETTERS, "ls -"))
    add(cell("dash", LETTERS, "ls --"))
    add(cell("dash", LETTERS, "ls -" + AFTER, cursor = P.length + 1))
    for (long in LS_LONGS) {
        val subject = nameSubject(long)
        add(cell("long end", subject, P + long))
        add(cell("long end", subject, P + long + AFTER, cursor = P.length + long.length))
        add(cell("long inside", nameSubject(long.dropLast(1)), P + long, cursor = P.length + long.length - 1))
        abbreviationOf(long)?.let { add(cell("long abbreviated", nameSubject(it), P + it)) }
    }
    for (long in KLAP_ONLY_LONGS + "--help-all" + "--colour") {
        add(cell("long end", UNKNOWN_NAME, P + long))
        add(cell("long inside", nameSubject(long.dropLast(1)), P + long, cursor = P.length + long.length - 1))
    }
    for (prefix in listOf("--j", "--com", "--do")) add(cell("long abbreviated", UNKNOWN_NAME, P + prefix))
    for (input in ALL_INPUTS) {
        for (letter in input.shorts) {
            val subject = if (input.kind is Kind.Value) VALUE_LETTER else LETTERS
            val partner = if (letter == 'r') 'i' else 'r'
            add(cell("short end", subject, "ls -$letter"))
            add(cell("short end", subject, "ls -$letter$AFTER", cursor = P.length + 2))
            add(cell("cluster end", subject, "ls -$partner$letter"))
            add(cell("cluster end", subject, "ls -$partner$letter$AFTER", cursor = P.length + 3))
            add(cell("cluster inside", subject, "ls -$partner${letter}z", cursor = P.length + 3))
        }
    }
    for (input in INPUTS) {
        val kind = input.kind as? Kind.Value ?: continue
        val long = input.long!!
        val spacedSubject = valueSubject(kind, attached = false)
        val joined = valueSubject(kind, attached = true)
        val partial = kind.words?.first()?.take(1) ?: kind.sample.take(1)
        val spaced = "$P$long "
        val typedEnd = spaced.length + partial.length
        add(cell("value no word", spacedSubject, spaced))
        add(cell("value no word", spacedSubject, spaced + AFTER, cursor = spaced.length))
        add(cell("value word start", spacedSubject, spaced + "zz", cursor = spaced.length))
        add(cell("value spaced", spacedSubject, spaced + partial))
        add(cell("value spaced", spacedSubject, spaced + partial + "zz", cursor = typedEnd))
        add(cell("value attached", joined, "$P$long="))
        add(cell("value attached", joined, "$P$long=$partial"))
        add(cell("value attached", joined, "$P$long=$partial$AFTER", cursor = typedEnd))
        add(cell("value attached", joined, "$P$long=${partial}zz", cursor = typedEnd))
        abbreviationOf(long)?.let { prefix ->
            add(cell("value abbreviated", spacedSubject, "$P$prefix "))
            add(cell("value abbreviated", joined, "$P$prefix=$partial"))
        }
        kind.words?.let { words ->
            add(cell("value capitals", CAPITALS, "$P$long=${words.first().take(2).uppercase()}"))
        }
        for (letter in input.shorts) {
            add(cell("value short", spacedSubject, "ls -$letter "))
            add(cell("value short", spacedSubject, "ls -$letter " + AFTER, cursor = P.length + 3))
            add(cell("value short", joined, "ls -$letter$partial"))
            add(cell("value short", joined, "ls -$letter$partial$AFTER", cursor = P.length + 2 + partial.length))
            add(cell("value cluster", spacedSubject, "ls -r$letter "))
            add(cell("value cluster", joined, "ls -r$letter$partial"))
        }
    }
}
