package com.fromwau.klap.fixture.mv

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.marked
import com.fromwau.klap.fixture.mv.MvLineContext.FILES
import com.fromwau.klap.fixture.mv.MvLineContext.assertTab
import com.fromwau.klap.fixture.mv.MvLineContext.assertValues
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter

/*
 * What Tab should offer on an mv line, computed from the table in MvModel.kt: every option described by mv's own help,
 * a choice value in mv's case only, and an operand slot handing the word to the shell's file completion. Of what real
 * mv 9.12 refuses on a line, the model leaves out a second -t, -t beside -T, a third operand under -T, and klap's own
 * built-ins. What only mv's check after the options refuses, a backup beside -n, --update=none, --update=none-fail or
 * --exchange, it still offers, as cp's model does (todo 89: Tab prunes only by declared rules).
 */

private val TARGET = input("--target-directory")
private val NO_TARGET = input("--no-target-directory")

/** What a run of words gives: the inputs named, the operands, and whether options have ended or a value is due. */
private data class Scan(
    val given: List<Input>,
    val operands: Int,
    val endOfOptions: Boolean,
    val pending: Input?,
)

private fun Input.takesRequiredValue(): Boolean = (kind as? Kind.Value)?.optional == false

/** Reads [words] as mv's getopt_long does, as far as naming inputs and counting operands goes. */
private fun scan(words: List<String>): Scan {
    val given = mutableListOf<Input>()
    var operands = 0
    var endOfOptions = false
    var pending: Input? = null
    for (word in words) {
        when {
            pending != null -> pending = null
            endOfOptions -> operands++
            word == "--" -> endOfOptions = true
            word.startsWith("--") -> {
                val input = inputOfLong(word.substringBefore('='))
                if (input != null) {
                    given += input
                    if ('=' !in word && input.takesRequiredValue()) pending = input
                }
            }
            word.startsWith("-") && word.length > 1 -> {
                for ((at, letter) in word.drop(1).withIndex()) {
                    val input = inputFor(letter) ?: break
                    given += input
                    if (input.kind is Kind.Value) {
                        if (at == word.length - 2) pending = input
                        break
                    }
                }
            }
            else -> operands++
        }
    }
    return Scan(given, operands, endOfOptions, pending)
}

/** The inputs real mv refuses on a line that already names [given]. */
private fun refused(given: List<Input>): Set<Input> = buildSet {
    // `mv -t d -t e a`: "multiple target directories specified"; `mv -t d -T a`: "cannot combine
    // --target-directory (-t) and --no-target-directory (-T)".
    if (TARGET in given) {
        add(TARGET)
        add(NO_TARGET)
    }
    if (NO_TARGET in given) add(TARGET)
}

private fun pool(refused: Set<Input>): List<Suggestion> = buildList {
    for (input in INPUTS - refused) {
        input.long?.let { add(Suggestion(it, input.help)) }
        input.shorts.forEach { add(Suggestion("-$it", input.help)) }
    }
    add(Suggestion("--help", HELP_HELP))
    add(Suggestion("--version", VERSION_HELP))
}

/** What real mv would accept for the word [current] (as far as typed) after [head], with [after] past the cursor. */
private fun mvOffers(
    head: List<String>,
    current: String,
    after: List<String>,
): Set<Suggestion> {
    val before = scan(head)
    val later = scan(after)
    val given = before.given + later.given
    before.pending?.let { input ->
        if (input in refused(given - input)) return emptySet()
        return valueOffers(input, current, head = null)
    }
    if (before.endOfOptions || !current.startsWith("-")) {
        val underT = NO_TARGET in given || NO_TARGET in scan(listOf(current)).given
        // `mv -T a b c`: "extra operand 'c'".
        return if (underT && before.operands + later.operands >= 2) emptySet() else setOf(FILES)
    }
    if (current.startsWith("--") && '=' in current) {
        val eq = current.indexOf('=')
        val input = inputOfLong(current.take(eq))
        if (input == null || input.kind !is Kind.Value || input in refused(given)) return emptySet()
        return valueOffers(input, current.drop(eq + 1), head = current.take(eq + 1))
    }
    if (!current.startsWith("--") && current.length > 1) return clusterOffers(current, given)
    return pool(refused(given)).filter { it.value.startsWith(current) }.toSet()
}

private fun clusterOffers(
    current: String,
    given: List<Input>,
): Set<Suggestion> {
    val letters = current.drop(1)
    val inputs = letters.map { inputFor(it) }
    val valueAt = inputs.indexOfFirst { it?.kind is Kind.Value }
    val known = if (valueAt < 0) inputs else inputs.take(valueAt + 1)
    if (known.any { it == null }) return emptySet()
    val inCluster = known.filterNotNull()
    val refused = refused(given + inCluster)
    if (inCluster.any { it in refused(given + inCluster - it) }) return emptySet()
    if (valueAt >= 0 && valueAt < letters.lastIndex) {
        return valueOffers(inputs[valueAt]!!, letters.drop(valueAt + 1), head = current.take(valueAt + 2))
    }
    if (valueAt >= 0) {
        return if (letters.length == 1) pool(refused(given)).filter { it.value == current }.toSet() else emptySet()
    }
    return (INPUTS - refused)
        .flatMap { input -> input.shorts.map { it to input } }
        .filter { (letter, _) -> letter !in letters }
        .map { (letter, input) -> Suggestion(current + letter, input.help) }
        .toSet()
}

private fun valueOffers(
    input: Input,
    partial: String,
    head: String?,
): Set<Suggestion> {
    val kind = input.kind as Kind.Value
    if (kind.path) return setOf(filesAfter(head.orEmpty()))
    val choices = kind.choices ?: return emptySet()
    return choices
        .filter { it.startsWith(partial) }
        .map { value -> if (head == null) Suggestion(value) else Suggestion(head + value, input.help) }
        .toSet()
}

/**
 * One Tab press: which cursor position and form it is ([kind]), what is being completed ([subject]), the line and
 * cursor, and what real mv would accept there ([mv]).
 */
internal data class CompletionCell(
    val kind: String,
    val subject: String,
    val line: String,
    val cursor: Int,
    val mv: Set<Suggestion>,
) {
    override fun toString(): String = "$kind, $subject: ${marked(line, cursor)}"

    /** Whether mv's answer here holds one of [WORDED_HELP]. */
    val worded: Boolean get() = mv.any { it.description in WORDED_HELP }

    private val typed: String get() = line.substring(line.lastIndexOf(' ', cursor - 1) + 1, cursor)

    /** Whether the word typed so far is also the start of a klap built-in mv does not have. */
    val builtinPrefix: Boolean
        get() = typed.startsWith("-") && '=' !in typed && KLAP_ONLY_LONGS.any { it.startsWith(typed) }

    /** Whether the word typed so far is a short cluster holding -T, which leaves -t out. */
    val holdsNoTarget: Boolean get() = !typed.startsWith("--") && typed.startsWith("-") && 'T' in typed
}

/** What real mv would accept at [cursor] on [line], which starts with `mv`. */
internal fun mvOffersAt(
    line: String,
    cursor: Int,
): Set<Suggestion> {
    val start = line.lastIndexOf(' ', cursor - 1) + 1
    val head = line.take(start).split(' ').filter { it.isNotEmpty() }.drop(1)
    val current = line.substring(start, cursor)
    val after = line.drop(cursor).dropWhile { !it.isWhitespace() }.split(' ').filter { it.isNotEmpty() }
    return mvOffers(head, current, after)
}

private fun cell(
    kind: String,
    subject: String,
    line: String,
    cursor: Int = line.length,
): CompletionCell = CompletionCell(kind, subject, line, cursor, mvOffersAt(line, cursor))

/** Tab on [cell] offers mv's set. */
internal fun Cli.assertMv(cell: CompletionCell) = assertTab("$cell", cell.line, cell.cursor, cell.mv)

// --- what a cell completes, in mv's terms ---

internal const val OPERAND = "an operand"
internal const val FULL_OPERANDS = "an operand -T has no room for"
internal const val MV_NAME = "a long name mv has"
internal const val UNKNOWN_NAME = "a long name mv does not have"
internal const val REFUSED = "a name mv refuses on this line"
internal const val LETTERS = "the letters a cluster continues with"
internal const val VALUE_LETTER = "a letter taking a value"
internal const val PATH_VALUE = "a path value"
internal const val TEXT_VALUE = "a free text value"
internal const val OPTIONAL = "an optional value"
internal const val CAPITALS = "a choice typed in capitals"

private const val AFTER = " a b"

private fun nameSubject(typed: String): String = if (MV_LONGS.any { it.startsWith(typed) }) MV_NAME else UNKNOWN_NAME

/** What a value cell completes. */
private fun valueSubject(input: Input): String {
    val kind = input.kind as Kind.Value
    return when {
        kind.optional -> OPTIONAL
        kind.path -> PATH_VALUE
        else -> TEXT_VALUE
    }
}

/** Every cell the matrix checks, for every input and cursor position. */
internal val CELLS: List<CompletionCell> by lazy { generateCells() }

/** The cells of [kind] completing one of [subjects]. */
internal fun cells(
    kind: String,
    vararg subjects: String,
): List<CompletionCell> = CELLS.filter { it.kind == kind && (subjects.isEmpty() || it.subject in subjects) }

private const val P = "mv "

private fun generateCells(): List<CompletionCell> = buildList {
    add(cell("no word", OPERAND, P))
    add(cell("no word", OPERAND, P + AFTER, cursor = P.length))
    add(cell("no word", OPERAND, "mv a b "))
    add(cell("no word", OPERAND, "mv -t d "))
    add(cell("no word", FULL_OPERANDS, "mv -T a b "))
    add(cell("no word", FULL_OPERANDS, "mv  -T a b", cursor = P.length))
    add(cell("word start", OPERAND, "mv abc", cursor = P.length))
    add(cell("word start", OPERAND, "mv --verbose", cursor = P.length))
    add(cell("operand inside", OPERAND, "mv abc", cursor = P.length + 1))
    add(cell("operand end", OPERAND, "mv abc"))
    add(cell("operand end", OPERAND, "mv abc a b", cursor = P.length + 3))
    add(cell("operand end", FULL_OPERANDS, "mv -T a b c"))
    add(cell("end of options", OPERAND, "mv -- "))
    add(cell("end of options", OPERAND, "mv -- -"))
    add(cell("end of options", OPERAND, "mv -- --verb"))
    add(cell("dash", LETTERS, "mv -"))
    add(cell("dash", LETTERS, "mv --"))
    add(cell("dash", LETTERS, "mv -" + AFTER, cursor = P.length + 1))
    add(cell("dash", REFUSED, "mv -t d -"))
    add(cell("dash", REFUSED, "mv - -t d", cursor = P.length + 1))
    add(cell("dash", REFUSED, "mv -T --"))
    for (long in MV_LONGS) {
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
    for (prefix in listOf("--j", "--col", "--com", "--do")) add(cell("long abbreviated", UNKNOWN_NAME, P + prefix))
    add(cell("long end", REFUSED, "mv -t d --no-target-directory"))
    add(cell("long inside", REFUSED, "mv --no-target-directory -t d", cursor = P.length + 20))
    add(cell("long end", REFUSED, "mv -T --target-directory"))
    add(cell("long end", REFUSED, "mv -t d --target-directory"))
    for (input in INPUTS) {
        for (letter in input.shorts) {
            val subject = if (input.kind is Kind.Value) VALUE_LETTER else LETTERS
            add(cell("short end", subject, "mv -$letter"))
            add(cell("short end", subject, "mv -$letter$AFTER", cursor = P.length + 2))
            val partner = if (letter == 'v') 'b' else 'v'
            add(cell("cluster end", subject, "mv -$partner$letter"))
            add(cell("cluster end", subject, "mv -$partner$letter$AFTER", cursor = P.length + 3))
            add(cell("cluster inside", subject, "mv -$partner${letter}z", cursor = P.length + 3))
        }
    }
    add(cell("cluster end", REFUSED, "mv -t d -vT"))
    add(cell("cluster end", REFUSED, "mv -Tv -t d", cursor = P.length + 3))
    add(cell("short end", REFUSED, "mv -h"))
    add(cell("cluster end", REFUSED, "mv -vh"))
    for (input in INPUTS) {
        val kind = input.kind as? Kind.Value ?: continue
        val subject = valueSubject(input)
        val long = input.long!!
        val partial = kind.choices?.first()?.take(1) ?: kind.sample.take(1)
        val spaced = P + long + " "
        val typedEnd = spaced.length + partial.length
        add(cell("value no word", subject, spaced))
        add(cell("value no word", subject, spaced + AFTER, cursor = spaced.length))
        add(cell("value word start", subject, spaced + "zz", cursor = spaced.length))
        add(cell("value spaced", subject, spaced + partial))
        add(cell("value spaced", subject, spaced + partial + "zz", cursor = typedEnd))
        add(cell("value attached", subject, "$P$long="))
        add(cell("value attached", subject, "$P$long=$partial"))
        add(cell("value attached", subject, "$P$long=$partial$AFTER", cursor = typedEnd))
        add(cell("value attached", subject, "$P$long=${partial}zz", cursor = typedEnd))
        abbreviationOf(long)?.let { prefix ->
            add(cell("value abbreviated", subject, "$P$prefix "))
            add(cell("value abbreviated", subject, "$P$prefix=$partial"))
        }
        kind.choices?.let { choices ->
            add(cell("value capitals", CAPITALS, "$P$long=${choices.first().take(2).uppercase()}"))
        }
        for (letter in input.shorts) {
            add(cell("value short", subject, "mv -$letter "))
            add(cell("value short", subject, "mv -$letter$partial"))
            add(cell("value short", subject, "mv -$letter$partial$AFTER", cursor = P.length + 2 + partial.length))
            add(cell("value cluster", subject, "mv -v$letter "))
            add(cell("value cluster", subject, "mv -v$letter$partial"))
        }
    }
    add(cell("value no word", REFUSED, "mv -t d -t "))
    add(cell("value no word", REFUSED, "mv -T -t "))
    add(cell("value no word", REFUSED, "mv -T --target-directory "))
    add(cell("value attached", REFUSED, "mv -T --target-directory="))
}

/** [marked] with its `|` taken out, the cursor where it stood, and what real mv accepts there. */
internal fun slot(
    row: String,
    marked: String,
    expected: Set<Suggestion>? = null,
): CompletionCell {
    val cursor = marked.indexOf('|')
    require(cursor >= 0 && marked.indexOf('|', cursor + 1) < 0) { "one | marks the cursor in `$marked`" }
    val line = marked.removeRange(cursor, cursor + 1)
    return CompletionCell(row, row, line, cursor, expected ?: mvOffersAt(line, cursor))
}

internal fun Cli.checkSlots(cells: List<CompletionCell>) = checkCells(cells) { assertMv(it) }

internal fun Cli.checkSlots(vararg cells: CompletionCell) = checkSlots(cells.toList())

/** Checks only the values Tab offers, for cells whose descriptions are a target of their own. */
internal fun Cli.checkSlotValues(cells: List<CompletionCell>) =
    checkCells(cells) { assertValues("$it", it.line, it.cursor, it.mv) }

internal val NAME_KINDS = listOf("long end", "long inside", "long abbreviated")

internal val LETTER_KINDS = listOf("short end", "cluster end", "cluster inside")

internal val VALUE_KINDS =
    listOf("value no word", "value word start", "value spaced", "value attached", "value abbreviated")

/** The cells whose answer holds none of [WORDED_HELP], and no prefix of a built-in mv lacks. */
internal fun List<CompletionCell>.plain(): List<CompletionCell> = filterNot { it.worded || it.builtinPrefix }

/** The cells whose answer holds one of [WORDED_HELP], and no prefix of a built-in mv lacks. */
internal fun List<CompletionCell>.wordedOnly(): List<CompletionCell> = filter { it.worded && !it.builtinPrefix }
