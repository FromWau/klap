package com.fromwau.klap.fixture.head

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.head.HeadLineContext.FILES
import com.fromwau.klap.fixture.head.HeadLineContext.assertTab
import com.fromwau.klap.fixture.head.HeadLineContext.assertValues
import com.fromwau.klap.fixture.marked
import com.fromwau.klap.testing.Suggestion

/*
 * What Tab should offer on a head line, computed from the table in HeadModel.kt: every name real head 9.12 documents,
 * described by `head --help`, a NUM left to the user (it is free text), and an operand slot handing the word to the
 * shell's file completion. head refuses no repeat and has no options that exclude each other, so nothing given on the
 * line rules a name out. The undocumented `---presume-input-pipe` is never offered, as `head --help` keeps it out. The
 * obsolete first word (`-5`, `-5c`) has cells of its own in HeadCompletionTest, since head's letters there have no help
 * line to describe them with.
 */

/** Every name head documents after a dash, in head's words, in `head --help` order. */
internal val POOL: List<Suggestion> by lazy {
    INPUTS.flatMap { input ->
        (input.longs + "-${input.short}").map { Suggestion(it, input.help) }
    } + Suggestion("--help", HELP_HELP) + Suggestion("--version", VERSION_HELP)
}

/** What a run of words gives: whether options have ended, a NUM is due, or the last word is an ambiguous long. */
private data class Scan(
    val endOfOptions: Boolean,
    val pending: Boolean,
    val ambiguous: Boolean,
)

/** Whether [word] is head's obsolete count, which head reads only as its first argument (`head -5 f`). */
internal fun isObsoleteCount(word: String): Boolean = word.length > 1 && word[0] == '-' && word[1].isDigit()

/** Reads [words] as head does, as far as values and the end of options go. */
private fun scan(words: List<String>): Scan {
    var endOfOptions = false
    var pending = false
    var ambiguous = false
    for ((at, word) in words.withIndex()) {
        ambiguous = false
        when {
            pending -> pending = false
            endOfOptions -> Unit
            word == "--" -> endOfOptions = true
            word.startsWith("--") -> {
                val name = word.substringBefore('=')
                ambiguous = '=' !in word && isAmbiguousLong(name)
                pending = '=' !in word && inputOfLong(name)?.kind is Kind.Value
            }
            at == 0 && isObsoleteCount(word) -> Unit
            word.startsWith("-") && word.length > 1 -> {
                for ((index, letter) in word.drop(1).withIndex()) {
                    val input = inputFor(letter) ?: break
                    if (input.kind is Kind.Value) {
                        pending = index == word.length - 2
                        break
                    }
                }
            }
        }
    }
    return Scan(endOfOptions, pending, ambiguous)
}

/** What real head would accept for the word [current] (as far as typed) after [head]; null for an obsolete count. */
private fun headOffers(
    head: List<String>,
    current: String,
): List<Suggestion>? {
    val before = scan(head)
    // `head --v f`: "option '--v' is ambiguous"; the word after it is neither a value nor an operand.
    if (before.ambiguous) return emptyList()
    if (before.pending) return emptyList()
    if (before.endOfOptions || !current.startsWith("-")) return listOf(FILES)
    // `head --quiet=x f`: "doesn't allow an argument"; a NUM after `=` is free text.
    if (current.startsWith("--") && '=' in current) return emptyList()
    if (!current.startsWith("--") && current.length > 1) {
        if (head.isEmpty() && isObsoleteCount(current)) return null
        return clusterOffers(current)
    }
    return POOL.filter { it.value.startsWith(current) }
}

private fun clusterOffers(current: String): List<Suggestion> {
    val letters = current.drop(1)
    val inputs = letters.map { inputFor(it) }
    val valueAt = inputs.indexOfFirst { it?.kind is Kind.Value }
    val known = if (valueAt < 0) inputs else inputs.take(valueAt + 1)
    // `head -qx f`: "invalid option -- 'x'"; `head -q5 f`: "invalid trailing option -- 5".
    if (known.any { it == null }) return emptyList()
    if (valueAt in 0 until letters.lastIndex) return emptyList()
    if (valueAt >= 0) return if (letters.length == 1) POOL.filter { it.value == current } else emptyList()
    return INPUTS.filter { it.short !in letters }.map { Suggestion(current + it.short, it.help) }
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

/** What real head would accept at [cursor] on [line], which starts with `head`; null for an obsolete count. */
internal fun headOffersAt(
    line: String,
    cursor: Int,
): List<Suggestion>? {
    val (head, current) = split(line, cursor)
    return headOffers(head, current)
}

/**
 * One Tab press: which cursor position and form it is ([kind]), what is being completed ([subject]), the line and
 * cursor, and what real head would accept there ([offers]).
 */
internal data class CompletionCell(
    val kind: String,
    val subject: String,
    val line: String,
    val cursor: Int,
    val offers: List<Suggestion>,
) {
    override fun toString(): String = "$kind, $subject: ${marked(line, cursor)}"

    /** Whether the word typed so far is also the start of a klap built-in head does not have. */
    val foreign: Boolean
        get() = split(line, cursor).let { (head, typed) ->
            !scan(head).endOfOptions && typed.startsWith("-") && '=' !in typed &&
                KLAP_ONLY_LONGS.any { it.startsWith(typed) }
        }

    /** Whether what head offers here carries a description only -n, -c, --help or --version give. */
    val wordedLikeKlap: Boolean
        get() = offers.any { it.description in listOf(BYTES_HELP, LINES_HELP, HELP_HELP, VERSION_HELP) }
}

internal fun cell(
    kind: String,
    subject: String,
    line: String,
    cursor: Int = line.length,
): CompletionCell {
    val offers = headOffersAt(line, cursor) ?: error("${marked(line, cursor)} is an obsolete count: a cell of its own")
    return CompletionCell(kind, subject, line, cursor, offers)
}

/** [marked] with its `|` taken out, the cursor where it stood, and what real head accepts there. */
internal fun slot(
    row: String,
    marked: String,
): CompletionCell {
    val cursor = marked.indexOf('|')
    require(cursor >= 0 && marked.indexOf('|', cursor + 1) < 0) { "one | marks the cursor in `$marked`" }
    return cell(row, row, marked.removeRange(cursor, cursor + 1), cursor)
}

/** Tab on [cell] offers head's set. */
internal fun Cli.assertHead(cell: CompletionCell) = assertTab("$cell", cell.line, cell.cursor, cell.offers)

internal fun Cli.checkSlots(cells: List<CompletionCell>) = checkCells(cells) { assertHead(it) }

internal fun Cli.checkSlots(vararg cells: CompletionCell) = checkSlots(cells.toList())

/** Checks only the values Tab offers, where a cell's claim is the names and not their descriptions. */
internal fun Cli.checkSlotValues(cells: List<CompletionCell>) =
    checkCells(cells) { assertValues("$it", it.line, it.cursor, it.offers) }

internal fun Cli.checkSlotValues(vararg cells: CompletionCell) = checkSlotValues(cells.toList())

// --- what a cell completes, in head's terms ---

internal const val OPERAND = "an operand"
internal const val HEAD_NAME = "a long name head has"
internal const val UNKNOWN_NAME = "a long name head does not have"
internal const val LETTERS = "the letters a cluster continues with"
internal const val VALUE_LETTER = "a letter taking a NUM"
internal const val NUM = "a NUM"

private const val AFTER = " a b"

private const val P = "head "

private fun nameSubject(typed: String): String =
    if (DOCUMENTED_LONGS.any { it.startsWith(typed) }) HEAD_NAME else UNKNOWN_NAME

/** Every cell the matrix checks, for every input and cursor position. */
internal val CELLS: List<CompletionCell> by lazy { generateCells() }

/** The cells of [kind] completing one of [subjects]. */
internal fun cells(
    kind: String,
    vararg subjects: String,
): List<CompletionCell> = CELLS.filter { it.kind == kind && (subjects.isEmpty() || it.subject in subjects) }

internal val NAME_KINDS = listOf("long end", "long inside", "long abbreviated")

internal val LETTER_KINDS = listOf("short end", "cluster end", "cluster inside")

internal val VALUE_KINDS = listOf(
    "value no word", "value word start", "value spaced", "value attached", "value abbreviated", "value short",
    "value cluster",
)

private fun generateCells(): List<CompletionCell> = buildList {
    add(cell("no word", OPERAND, P))
    add(cell("no word", OPERAND, P + AFTER, cursor = P.length))
    add(cell("no word", OPERAND, "head a b "))
    add(cell("no word", OPERAND, "head -q "))
    add(cell("no word", OPERAND, "head -qv "))
    add(cell("word start", OPERAND, "head abc", cursor = P.length))
    add(cell("word start", OPERAND, "head --quiet", cursor = P.length))
    add(cell("operand inside", OPERAND, "head abc", cursor = P.length + 1))
    add(cell("operand end", OPERAND, "head abc"))
    add(cell("operand end", OPERAND, "head abc a b", cursor = P.length + 3))
    add(cell("operand end", OPERAND, "head -q abc"))
    add(cell("end of options", OPERAND, "head -- "))
    add(cell("end of options", OPERAND, "head -- -"))
    add(cell("end of options", OPERAND, "head -- --qu"))
    add(cell("end of options", OPERAND, "head -q -- -v"))
    add(cell("dash", LETTERS, "head -"))
    add(cell("dash", LETTERS, "head --"))
    add(cell("dash", LETTERS, "head -" + AFTER, cursor = P.length + 1))
    for (long in DOCUMENTED_LONGS) {
        val subject = nameSubject(long)
        add(cell("long end", subject, P + long))
        add(cell("long end", subject, P + long + AFTER, cursor = P.length + long.length))
        add(cell("long inside", nameSubject(long.dropLast(1)), P + long, cursor = P.length + long.length - 1))
        abbreviationOf(long)?.let { add(cell("long abbreviated", nameSubject(it), P + it)) }
    }
    for (long in KLAP_ONLY_LONGS + "--help-all") {
        add(cell("long end", UNKNOWN_NAME, P + long))
        add(cell("long inside", nameSubject(long.dropLast(1)), P + long, cursor = P.length + long.length - 1))
    }
    for (prefix in listOf("--j", "--c", "--co", "--d")) add(cell("long abbreviated", UNKNOWN_NAME, P + prefix))
    for (input in INPUTS) {
        val letter = input.short
        val subject = if (input.kind is Kind.Value) VALUE_LETTER else LETTERS
        val partner = if (letter == 'q') 'z' else 'q'
        add(cell("short end", subject, "head -$letter"))
        add(cell("short end", subject, "head -$letter$AFTER", cursor = P.length + 2))
        add(cell("cluster end", subject, "head -$partner$letter"))
        add(cell("cluster end", subject, "head -$partner$letter$AFTER", cursor = P.length + 3))
        add(cell("cluster inside", subject, "head -$partner${letter}x", cursor = P.length + 3))
    }
    for (input in INPUTS) {
        val kind = input.kind as? Kind.Value ?: continue
        val long = input.longs.single()
        val partial = kind.sample
        val spaced = "$P$long "
        add(cell("value no word", NUM, spaced))
        add(cell("value no word", NUM, spaced + AFTER, cursor = spaced.length))
        add(cell("value word start", NUM, spaced + "12", cursor = spaced.length))
        add(cell("value spaced", NUM, spaced + partial))
        add(cell("value spaced", NUM, spaced + partial + "0", cursor = spaced.length + partial.length))
        add(cell("value spaced", NUM, spaced + "-"))
        add(cell("value attached", NUM, "$P$long="))
        add(cell("value attached", NUM, "$P$long=$partial"))
        add(cell("value attached", NUM, "$P$long=$partial$AFTER", cursor = P.length + long.length + 1 + partial.length))
        abbreviationOf(long)?.let { prefix ->
            add(cell("value abbreviated", NUM, "$P$prefix "))
            add(cell("value abbreviated", NUM, "$P$prefix=$partial"))
        }
        val letter = input.short
        add(cell("value short", NUM, "head -$letter "))
        add(cell("value short", NUM, "head -$letter $AFTER", cursor = P.length + 3))
        add(cell("value short", NUM, "head -$letter$partial"))
        add(cell("value short", NUM, "head -$letter$partial$AFTER", cursor = P.length + 2 + partial.length))
        add(cell("value cluster", NUM, "head -q$letter "))
        add(cell("value cluster", NUM, "head -q$letter$partial"))
    }
}
