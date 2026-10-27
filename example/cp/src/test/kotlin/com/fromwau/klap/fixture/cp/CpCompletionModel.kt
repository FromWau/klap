package com.fromwau.klap.fixture.cp

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.cp.CpLineContext.FILES
import com.fromwau.klap.fixture.cp.CpLineContext.assertTab
import com.fromwau.klap.fixture.marked
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter

/*
 * What Tab should offer on a cp line, computed from the table in CpModel.kt: every option described by cp's own help,
 * a choice value in cp's case only, and an operand slot handing the word to the shell's file completion. Of what real
 * cp 9.12 refuses on a line, the model leaves out what the fixture declares: a second -t, -t beside -T, -l beside -s,
 * a third operand under -T, and klap's own built-ins. What only cp's validators refuse, --backup beside -n,
 * --update=none or --update=none-fail, and --reflink=always beside a --sparse other than auto, it still offers, as
 * Tab does.
 */

private val TARGET = input("--target-directory")
private val NO_TARGET = input("--no-target-directory")
private val HARD_LINK = input("--link")
private val SYMBOLIC_LINK = input("--symbolic-link")

/** What a run of words gives: the inputs named, the operands, and whether options have ended or a value is due. */
private data class Scan(
    val given: List<Input>,
    val operands: Int,
    val endOfOptions: Boolean,
    val pending: Input?,
)

private fun Input.takesRequiredValue(): Boolean = (kind as? Kind.Value)?.optional == false

/** Reads [words] as cp's getopt_long does, as far as naming inputs and counting operands goes. */
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

/** The inputs real cp refuses on a line that already names [given]. */
private fun refused(given: List<Input>): Set<Input> = buildSet {
    if (TARGET in given) {
        add(TARGET)
        add(NO_TARGET)
    }
    if (NO_TARGET in given) add(TARGET)
    // `cp -l -s a b`: "cannot make both hard and symbolic links".
    if (HARD_LINK in given) add(SYMBOLIC_LINK)
    if (SYMBOLIC_LINK in given) add(HARD_LINK)
}

private fun pool(refused: Set<Input>): List<Suggestion> = buildList {
    for (input in INPUTS - refused) {
        input.long?.let { add(Suggestion(it, input.help)) }
        input.shorts.forEach { add(Suggestion("-$it", input.help)) }
    }
    add(Suggestion("--help", HELP_HELP))
    add(Suggestion("--version", VERSION_HELP))
}

/** What real cp would accept for the word [current] (as far as typed) after [head], with [after] past the cursor. */
private fun cpOffers(
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
    val done = if (kind.list && ',' in partial) partial.substringBeforeLast(',') + "," else ""
    val typed = partial.drop(done.length)
    val named = done.split(',').filter { it.isNotEmpty() }
    return choices
        .filter { it.startsWith(typed) && it !in named }
        .map { value -> if (head == null) Suggestion(done + value) else Suggestion(head + done + value, input.help) }
        .toSet()
}

/**
 * One Tab press: which cursor position and form it is ([kind]), what is being completed ([subject]), the line and
 * cursor, and what real cp would accept there ([cp]).
 */
internal data class CompletionCell(
    val kind: String,
    val subject: String,
    val line: String,
    val cursor: Int,
    val cp: Set<Suggestion>,
) {
    override fun toString(): String = "$kind, $subject: ${marked(line, cursor)}"
}

/** What real cp would accept at [cursor] on [line], which starts with `cp`. */
internal fun cpOffersAt(
    line: String,
    cursor: Int,
): Set<Suggestion> {
    val start = line.lastIndexOf(' ', cursor - 1) + 1
    val head = line.take(start).split(' ').filter { it.isNotEmpty() }.drop(1)
    val current = line.substring(start, cursor)
    val after = line.drop(cursor).dropWhile { !it.isWhitespace() }.split(' ').filter { it.isNotEmpty() }
    return cpOffers(head, current, after)
}

private fun cell(
    kind: String,
    subject: String,
    line: String,
    cursor: Int = line.length,
): CompletionCell = CompletionCell(kind, subject, line, cursor, cpOffersAt(line, cursor))

/** Tab on [cell] offers cp's set. */
internal fun Cli.assertCp(cell: CompletionCell) = assertTab("$cell", cell.line, cell.cursor, cell.cp)

// --- what a cell completes, in cp's terms ---

internal const val OPERAND = "an operand"
internal const val FULL_OPERANDS = "an operand -T has no room for"
internal const val CP_NAME = "a long name cp has"
internal const val UNKNOWN_NAME = "a long name cp does not have"
internal const val REFUSED = "a name cp refuses on this line"
internal const val LETTERS = "the letters a cluster continues with"
internal const val VALUE_LETTER = "a letter taking a value"
internal const val PATH_VALUE = "a path value"
internal const val CHOICE = "a choice value"
internal const val ATTRIBUTE = "an attribute name"
internal const val TEXT_VALUE = "a free text value"
internal const val OPTIONAL = "an optional value"
internal const val CAPITALS = "a choice typed in capitals"

private const val AFTER = " a b"

private fun nameSubject(typed: String): String = if (CP_LONGS.any { it.startsWith(typed) }) CP_NAME else UNKNOWN_NAME

/** What a value cell completes; [attached] when the value shares its word with the option. */
private fun valueSubject(
    input: Input,
    attached: Boolean,
): String {
    val kind = input.kind as Kind.Value
    return when {
        kind.optional && !attached -> OPTIONAL
        kind.list -> ATTRIBUTE
        kind.optional -> OPTIONAL
        kind.path -> PATH_VALUE
        kind.choices != null -> CHOICE
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

private const val P = "cp "

private fun generateCells(): List<CompletionCell> = buildList {
    add(cell("no word", OPERAND, P))
    add(cell("no word", OPERAND, P + AFTER, cursor = P.length))
    add(cell("no word", OPERAND, "cp a b "))
    add(cell("no word", OPERAND, "cp -t d "))
    add(cell("no word", FULL_OPERANDS, "cp -T a b "))
    add(cell("no word", FULL_OPERANDS, "cp  -T a b", cursor = P.length))
    add(cell("word start", OPERAND, "cp abc", cursor = P.length))
    add(cell("word start", OPERAND, "cp --verbose", cursor = P.length))
    add(cell("operand inside", OPERAND, "cp abc", cursor = P.length + 1))
    add(cell("operand end", OPERAND, "cp abc"))
    add(cell("operand end", OPERAND, "cp abc a b", cursor = P.length + 3))
    add(cell("operand end", FULL_OPERANDS, "cp -T a b c"))
    add(cell("end of options", OPERAND, "cp -- "))
    add(cell("end of options", OPERAND, "cp -- -"))
    add(cell("end of options", OPERAND, "cp -- --verb"))
    add(cell("dash", LETTERS, "cp -"))
    add(cell("dash", LETTERS, "cp --"))
    add(cell("dash", LETTERS, "cp -" + AFTER, cursor = P.length + 1))
    add(cell("dash", REFUSED, "cp -t d -"))
    add(cell("dash", REFUSED, "cp - -t d", cursor = P.length + 1))
    add(cell("dash", REFUSED, "cp -T --"))
    for (long in CP_LONGS) {
        val subject = nameSubject(long)
        add(cell("long end", subject, P + long))
        add(cell("long end", subject, P + long + AFTER, cursor = P.length + long.length))
        add(cell("long inside", nameSubject(long.dropLast(1)), P + long, cursor = P.length + long.length - 1))
        abbreviationOf(long)?.let { add(cell("long abbreviated", nameSubject(it), P + it)) }
    }
    for (long in KLAP_ONLY_LONGS) {
        add(cell("long end", UNKNOWN_NAME, P + long))
        add(cell("long inside", nameSubject(long.dropLast(1)), P + long, cursor = P.length + long.length - 1))
    }
    for (prefix in listOf("--j", "--col", "--com", "--do")) add(cell("long abbreviated", UNKNOWN_NAME, P + prefix))
    add(cell("long end", REFUSED, "cp -t d --no-target-directory"))
    add(cell("long inside", REFUSED, "cp --no-target-directory -t d", cursor = P.length + 20))
    add(cell("long end", REFUSED, "cp -T --target-directory"))
    add(cell("long end", REFUSED, "cp -t d --target-directory"))
    for (input in INPUTS) {
        for (letter in input.shorts) {
            val subject = if (input.kind is Kind.Value) VALUE_LETTER else LETTERS
            add(cell("short end", subject, "cp -$letter"))
            add(cell("short end", subject, "cp -$letter$AFTER", cursor = P.length + 2))
            val partner = if (letter == 'v') 'x' else 'v'
            add(cell("cluster end", subject, "cp -$partner$letter"))
            add(cell("cluster end", subject, "cp -$partner$letter$AFTER", cursor = P.length + 3))
            add(cell("cluster inside", subject, "cp -$partner${letter}z", cursor = P.length + 3))
        }
    }
    add(cell("cluster end", REFUSED, "cp -t d -vT"))
    add(cell("cluster end", REFUSED, "cp -Tv -t d", cursor = P.length + 3))
    add(cell("short end", REFUSED, "cp -h"))
    add(cell("cluster end", REFUSED, "cp -vh"))
    for (input in INPUTS) {
        val kind = input.kind as? Kind.Value ?: continue
        val subject = valueSubject(input, attached = false)
        val joined = valueSubject(input, attached = true)
        val long = input.long!!
        val partial = kind.choices?.first()?.take(1) ?: kind.sample.take(1)
        val spaced = P + long + " "
        val typedEnd = spaced.length + partial.length
        add(cell("value no word", subject, spaced))
        add(cell("value no word", subject, spaced + AFTER, cursor = spaced.length))
        add(cell("value word start", subject, spaced + "zz", cursor = spaced.length))
        add(cell("value spaced", subject, spaced + partial))
        add(cell("value spaced", subject, spaced + partial + "zz", cursor = typedEnd))
        add(cell("value attached", joined, "$P$long="))
        add(cell("value attached", joined, "$P$long=$partial"))
        add(cell("value attached", joined, "$P$long=$partial$AFTER", cursor = typedEnd))
        add(cell("value attached", joined, "$P$long=${partial}zz", cursor = typedEnd))
        abbreviationOf(long)?.let { prefix ->
            add(cell("value abbreviated", subject, "$P$prefix "))
            add(cell("value abbreviated", joined, "$P$prefix=$partial"))
        }
        if (kind.list) {
            add(cell("value list", joined, "$P$long=mode,"))
            add(cell("value list", joined, "$P$long=mode,o"))
        }
        kind.choices?.let { choices ->
            add(cell("value capitals", CAPITALS, "$P$long=${choices.first().take(2).uppercase()}"))
        }
        for (letter in input.shorts) {
            add(cell("value short", subject, "cp -$letter "))
            add(cell("value short", subject, "cp -$letter$partial"))
            add(cell("value short", subject, "cp -$letter$partial$AFTER", cursor = P.length + 2 + partial.length))
            add(cell("value cluster", subject, "cp -v$letter "))
            add(cell("value cluster", subject, "cp -v$letter$partial"))
        }
    }
    add(cell("value no word", REFUSED, "cp -t d -t "))
    add(cell("value no word", REFUSED, "cp -T -t "))
    add(cell("value no word", REFUSED, "cp -T --target-directory "))
    add(cell("value attached", REFUSED, "cp -T --target-directory="))
}
