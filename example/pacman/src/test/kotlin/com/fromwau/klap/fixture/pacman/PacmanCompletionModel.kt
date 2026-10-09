package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.Cli
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.acceptSuggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import com.fromwau.klap.testing.tab
import kotlin.test.assertEquals

/*
 * What Tab should offer on a pacman line: exactly what real pacman 7.1.0 would accept at the cursor, computed
 * from its `--help` pages in PacmanModel.kt. Under an operation that is the rows of its page in the page's words,
 * never a second operation or the given one again, a letter again only where the page counts it (`-ii`), choice
 * values in pacman's own case, and an operation typed after the cursor counts as much as one before it. With no
 * operation on the line anything may still follow, so every option is offered, in the fixture's wording.
 */

private val VALUE_SHORTS: Set<Char> = INPUTS.filter { it.kind is Kind.Value }.mapNotNull { it.short }.toSet()

/** The letters a short cluster runs on through: every flag letter, the operations and `-V`. */
private val FLAG_LETTERS: Set<Char> =
    INPUTS.filter { it.kind !is Kind.Value }.mapNotNull { it.short }.toSet() + Op.entries.map { it.short } + 'V'

private fun opOfLetter(letter: Char): Op? = Op.entries.firstOrNull { it.short == letter }

/** The operations [words] supply, read as pacman reads them: a long name, or a letter of a cluster before its value. */
internal fun operationsIn(words: List<String>): List<Op> {
    val ops = mutableListOf<Op>()
    for (word in words) {
        if (word == "--") break
        Op.entries.firstOrNull { it.long == word }?.let { ops += it }
        if (word.startsWith("-") && !word.startsWith("--")) {
            for (letter in word.drop(1)) {
                opOfLetter(letter)?.let { ops += it }
                if (letter in VALUE_SHORTS) break
            }
        }
    }
    return ops
}

/** The long name [spelled] resolves to: itself, or the one long it is the unique prefix of. */
private fun resolveLong(spelled: String): String? =
    ALL_LONGS.firstOrNull { it == spelled } ?: ALL_LONGS.filter { it.startsWith(spelled) }.singleOrNull()

/** What pacman would accept for the word [current] (as far as typed) after [head], with [after] past the cursor. */
internal fun pacmanOffers(
    head: List<String>,
    current: String,
    after: List<String>,
): Set<Suggestion> {
    if ("--" in head) return emptySet()
    val inCluster = current.startsWith("-") && !current.startsWith("--")
    val inCurrent = if (inCluster) operationsIn(listOf(current)) else emptyList()
    val supplied = operationsIn(head) + operationsIn(after) + inCurrent
    // pacman refuses a line naming an operation twice, or two of them, whatever the cursor completes.
    if (supplied.size > 1) return emptySet()
    val op = supplied.singleOrNull()
    spacedValueInput(head.lastOrNull())?.let { return valueSuggestions(it, current, head = null, op = op) }
    attachedValue(current)?.let { (input, prefix, partial) ->
        return valueSuggestions(input, partial, head = prefix, op = op)
    }
    if (!current.startsWith("-")) return emptySet()
    val pool = pool(op)
    val typed = current.drop(1)
    if (inCluster && typed.isNotEmpty() && typed.all { it in FLAG_LETTERS }) {
        if (op != null && typed.any { !validLetter(op, it) }) return emptySet()
        return pool
            .filter { it.value.length == 2 && !it.value.startsWith("--") }
            .filter { suggestion -> again(op, suggestion.value[1], typed) }
            .map { Suggestion(current + it.value[1], it.description) }
            .toSet()
    }
    return pool.filter { it.value.startsWith(current) }.toSet()
}

/** Whether [letter] may follow [typed] in a cluster: once, or a second time where [op]'s page counts it. */
private fun again(
    op: Op?,
    letter: Char,
    typed: String,
): Boolean {
    val times = typed.count { it == letter }
    if (times == 0) return true
    val entry = op?.entryFor(letter) ?: return false
    return times == 1 && op.counts(entry)
}

private fun validLetter(
    op: Op,
    letter: Char,
): Boolean = letter == op.short || letter == 'V' || op.entryFor(letter) != null

private fun spacedValueInput(previous: String?): Input? {
    if (previous == null || !previous.startsWith("-")) return null
    val input = if (previous.startsWith("--")) {
        resolveLong(previous)?.let { long -> INPUTS.firstOrNull { it.long == long } }
    } else {
        val letters = previous.drop(1)
        val last = letters.lastOrNull() ?: return null
        if (letters.dropLast(1).any { it !in FLAG_LETTERS } || last !in VALUE_SHORTS) return null
        inputFor(last)
    }
    val kind = input?.kind as? Kind.Value ?: return null
    return input.takeIf { kind.bare == null }
}

private fun attachedValue(current: String): Triple<Input, String, String>? {
    if (current.startsWith("--")) {
        val eq = current.indexOf('=')
        if (eq < 0) return null
        val long = resolveLong(current.take(eq)) ?: return null
        val input = INPUTS.firstOrNull { it.long == long && it.kind is Kind.Value } ?: return null
        return Triple(input, current.take(eq + 1), current.drop(eq + 1))
    }
    if (!current.startsWith("-")) return null
    val letters = current.drop(1)
    val at = letters.indexOfFirst { it !in FLAG_LETTERS }
    if (at < 0 || letters[at] !in VALUE_SHORTS || at == letters.lastIndex) return null
    return Triple(inputFor(letters[at])!!, current.take(at + 2), letters.drop(at + 1))
}

private fun valueSuggestions(
    input: Input,
    partial: String,
    head: String?,
    op: Op?,
): Set<Suggestion> {
    // An option the operation refuses has no value pacman would accept either.
    if (op != null && op.entryFor(input.long) == null) return emptySet()
    val kind = input.kind as Kind.Value
    if (kind.path) return setOf(filesAfter(head.orEmpty()))
    val choices = kind.choices ?: return emptySet()
    val help = op?.entryFor(input.long)?.help ?: input.help
    return choices
        .filter { it.startsWith(partial) }
        .map { value -> if (head == null) Suggestion(value) else Suggestion(head + value, help) }
        .toSet()
}

private fun pool(op: Op?): List<Suggestion> = buildList {
    if (op != null) {
        for (entry in op.page) {
            add(Suggestion(entry.long, entry.help))
            entry.short?.let { add(Suggestion("-$it", entry.help)) }
        }
    } else {
        for (input in INPUTS) {
            add(Suggestion(input.long, input.help))
            input.short?.let { add(Suggestion("-$it", input.help)) }
        }
        for (each in Op.entries) {
            add(Suggestion(each.long, each.help))
            add(Suggestion("-${each.short}", each.help))
        }
    }
    add(Suggestion("-V", VERSION_SHORT_HELP))
    add(Suggestion("-h", HELP_TEXT))
    add(Suggestion("--help", HELP_TEXT))
    add(Suggestion("--version", VERSION_TEXT))
}

/**
 * One Tab press: which cursor position and form it is ([kind]), under which operation ([op], none for a line
 * without one), what is being completed ([subject]), the line and cursor, and what pacman would accept there.
 */
internal data class CompletionCell(
    val kind: String,
    val op: Op?,
    val subject: String,
    val line: String,
    val cursor: Int,
    val pacman: Set<Suggestion>,
    val named: String?,
) {
    override fun toString(): String = "$kind, $subject: ${marked(line, cursor)}"
}

private fun cell(
    kind: String,
    op: Op?,
    subject: String,
    line: String,
    cursor: Int = line.length,
    named: String? = null,
): CompletionCell {
    val start = line.lastIndexOfAny(charArrayOf(' ', '\t'), cursor - 1) + 1
    val head = line.take(start).split(' ').filter { it.isNotEmpty() }.drop(1)
    val current = line.substring(start, cursor)
    val after = line.drop(cursor).dropWhile { !it.isWhitespace() }.split(' ').filter { it.isNotEmpty() }
    return CompletionCell(kind, op, subject, line, cursor, pacmanOffers(head, current, after), named)
}

/** Tab on [cell] offers pacman's set, and taking [CompletionCell.named] (or the first) and one Tab give its lines. */
internal fun Cli.assertPacman(cell: CompletionCell) {
    val line = cell.line
    val cursor = cell.cursor
    val expected = cell.pacman
    val actual = suggestionsFor(line, cursor)
    assertEquals(expected.sortedBy { it.value }, actual.sortedBy { it.value }, "$cell: offered (pacman's set first)")
    assertEquals(actual.size, actual.toSet().size, "$cell: a suggestion is offered twice: $actual")
    val take = expected.firstOrNull { it.value == cell.named } ?: expected.minByOrNull { it.value }
    if (take != null && !take.value.startsWith(FILES.value)) {
        val start = line.lastIndexOfAny(charArrayOf(' ', '\t'), cursor - 1) + 1
        val taken = line.take(start) + take.value + line.drop(cursor)
        assertEquals(taken, acceptSuggestion(line, take, cursor), "$cell: once ${take.value} is taken")
    }
    assertEquals(tabbed(cell), tab(line, cursor), "$cell: after one Tab")
}

/** [cell]'s line after one Tab, as every shell does it: the one suggestion, or what all of them share. */
private fun tabbed(cell: CompletionCell): String {
    val line = cell.line
    val cursor = cell.cursor
    val values = cell.pacman.map { it.value }
    if (values.isEmpty() || values.first().startsWith(FILES.value)) return line
    val shared = values.reduce { shared, value -> shared.commonPrefixWith(value) }
    val start = line.lastIndexOfAny(charArrayOf(' ', '\t'), cursor - 1) + 1
    val typed = line.substring(start, cursor)
    return if (shared.length > typed.length) line.take(start) + shared + line.drop(cursor) else line
}

// --- what a cell completes, in pacman's terms ---

internal const val OPERATION = "an operation"
internal const val SAME_OPERATION = "the operation already given"
internal const val SHARED_NAME = "another operation whose name as typed starts an option's"
internal const val OTHER_OPERATION = "another operation"
internal const val VALUE_LETTER = "a letter taking a value"
internal const val BUILT_IN = "help or version"
internal const val OPTION = "an option"
internal const val SAME_WORDING = "an option on the page in the fixture's wording"
internal const val OTHER_WORDING = "an option on the page in other words"
internal const val CONTESTED = "a letter the fixture gives another meaning"
internal const val OFF_PAGE = "an option of another operation"
internal const val VERSION_LETTER = "the -V letter"
internal const val PATH_VALUE = "a path value"
internal const val CHOICE = "a choice value"
internal const val TEXT_VALUE = "a free text value"
internal const val OPTIONAL = "an optional value"
internal const val NOTHING = "nothing"

private fun longSubject(
    op: Op?,
    long: String,
    typed: String = long,
): String {
    val named = Op.entries.firstOrNull { it.long == long }
    if (named != null) {
        return when {
            op == null -> OPERATION
            named == op -> SAME_OPERATION
            INPUTS.any { it.long.startsWith(typed) } -> SHARED_NAME
            else -> OTHER_OPERATION
        }
    }
    if (long == "--help" || long == "--version") return BUILT_IN
    if (op == null) return OPTION
    val entry = op.entryFor(long) ?: return OFF_PAGE
    return if (entry.help == input(long).help) SAME_WORDING else OTHER_WORDING
}

private fun letterSubject(
    op: Op?,
    letter: Char,
): String {
    if (opOfLetter(letter) != null) return OPERATION
    if (letter == 'V') return VERSION_LETTER
    if (op == null) return OPTION
    val entry = op.entryFor(letter) ?: return OFF_PAGE
    val declared = inputFor(letter)!!
    if (declared.long != entry.long) return CONTESTED
    if (declared.kind is Kind.Value) return VALUE_LETTER
    return if (entry.help == declared.help) SAME_WORDING else OTHER_WORDING
}

private fun valueSubject(
    op: Op?,
    input: Input,
): String {
    if (op != null && op.entryFor(input.long) == null) return OFF_PAGE
    val kind = input.kind as Kind.Value
    return when {
        kind.bare != null -> OPTIONAL
        kind.path -> PATH_VALUE
        kind.choices != null -> CHOICE
        else -> TEXT_VALUE
    }
}

private val CONTEXTS: List<Op?> = listOf(null) + Op.entries

private fun prefix(op: Op?): String = if (op == null) "pacman " else "pacman -${op.short} "

private const val AFTER = " pkg"

/** Every cell the matrix checks, for every input, operation and cursor position. */
internal val CELLS: List<CompletionCell> by lazy { generateCells() }

/** The cells of [kind] under [ops] completing one of [subjects]. */
internal fun cells(
    kind: String,
    ops: Collection<Op?>,
    vararg subjects: String,
): List<CompletionCell> = CELLS.filter { it.kind == kind && it.op in ops && it.subject in subjects }

internal val NO_OPERATION: List<Op?> = listOf(null)
internal val ANY_OPERATION: List<Op?> = Op.entries

private fun valuePartial(kind: Kind.Value): String = kind.choices?.first()?.take(1) ?: if (kind.path) "/tm" else "x"

private fun generateCells(): List<CompletionCell> = buildList {
    for (op in CONTEXTS) {
        val p = prefix(op)
        add(cell("no word", op, NOTHING, p))
        add(cell("no word", op, NOTHING, p + AFTER, cursor = p.length))
        add(cell("word start", op, NOTHING, p + "pkg", cursor = p.length))
        add(cell("dash", op, NOTHING, p + "-"))
        add(cell("dash", op, NOTHING, p + "--"))
        add(cell("dash", op, NOTHING, p + "-" + AFTER, cursor = p.length + 1))
        add(cell("end of options", op, NOTHING, p + "-- -"))
        add(cell("end of options", op, NOTHING, p + "-- --d"))
        val names = INPUTS.map { it.long } + Op.entries.map { it.long } + listOf("--help", "--version")
        for (long in names) {
            val subject = longSubject(op, long)
            add(cell("long end", op, subject, p + long, named = long))
            add(cell("long end", op, subject, p + long + AFTER, cursor = p.length + long.length, named = long))
            val inside = longSubject(op, long, typed = long.dropLast(1))
            add(cell("long inside", op, inside, p + long, cursor = p.length + long.length - 1, named = long))
            add(cell("long word start", op, subject, p + long, cursor = p.length))
            if (op != null) {
                val line = "pacman $long -${op.short}"
                add(cell("long before the operation", op, subject, line, cursor = 7 + long.length, named = long))
            }
        }
        val letters = INPUTS.mapNotNull { it.short } + Op.entries.map { it.short } + 'V'
        for (letter in letters) {
            val subject = letterSubject(op, letter)
            add(cell("short end", op, subject, "$p-$letter", named = "-$letter"))
            add(cell("short end", op, subject, "$p-$letter$AFTER", cursor = p.length + 2, named = "-$letter"))
            if (op != null) {
                val cluster = "pacman -${op.short}$letter"
                add(cell("cluster end", op, subject, cluster))
                add(cell("cluster end", op, subject, cluster + AFTER, cursor = cluster.length))
                add(cell("cluster inside", op, subject, cluster + "z", cursor = cluster.length))
            }
        }
        if (op != null) {
            val cluster = "pacman -${op.short}"
            add(cell("operation cluster", op, OPERATION, cluster, named = "${cluster.drop(7)}v"))
            add(cell("operation cluster", op, OPERATION, cluster + AFTER, cursor = cluster.length))
            add(cell("operation cluster after the cursor", op, OPERATION, "pacman -v -${op.short}", cursor = 9))
        }
        for (input in INPUTS) {
            val kind = input.kind as? Kind.Value ?: continue
            val subject = valueSubject(op, input)
            val long = input.long
            val partial = valuePartial(kind)
            val typedEnd = p.length + long.length + 1 + partial.length
            add(cell("value no word", op, subject, "$p$long "))
            add(cell("value no word", op, subject, "$p$long $AFTER", cursor = p.length + long.length + 1))
            add(cell("value word start", op, subject, "$p$long pkg", cursor = p.length + long.length + 1))
            add(cell("value spaced", op, subject, "$p$long $partial"))
            add(cell("value spaced", op, subject, "$p$long ${partial}zz", cursor = typedEnd))
            add(cell("value attached", op, subject, "$p$long="))
            add(cell("value attached", op, subject, "$p$long=$partial"))
            add(cell("value attached", op, subject, "$p$long=$partial$AFTER", cursor = typedEnd))
            add(cell("value attached", op, subject, "$p$long=${partial}zz", cursor = typedEnd))
            abbreviationOf(long)?.let { short ->
                add(cell("value abbreviated", op, subject, "$p$short "))
                add(cell("value abbreviated", op, subject, "$p$short=$partial"))
            }
            val letter = input.short ?: continue
            add(cell("value short", op, subject, "$p-$letter "))
            add(cell("value short", op, subject, "$p-$letter$partial"))
            add(cell("value short", op, subject, "$p-$letter$partial$AFTER", cursor = p.length + 2 + partial.length))
            if (op != null) {
                add(cell("value cluster", op, subject, "pacman -${op.short}$letter "))
                add(cell("value cluster", op, subject, "pacman -${op.short}$letter$partial"))
                add(cell("value cluster", op, subject, "pacman -${op.short}v$letter$partial"))
            }
        }
        add(cell("value capitals", op, CHOICE, p + "--color=AL"))
        add(cell("value capitals", op, CHOICE, p + "--color AL"))
    }
}
