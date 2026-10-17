package com.fromwau.klap.fixture.git

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells

/*
 * The projection cells of git's tree, generated from the root spellings and command lines in GitModel.kt. Each was
 * run with real git 2.56.0 in a throwaway repository: a line it ran (or failed only once running, such as a pathspec
 * matching no file) binds, and a line it answered with "unknown option", "unknown switch" or "unrecognized argument"
 * is refused.
 */

/** One argv line and what real git makes of it: [expected], or a refusal where that is null. */
internal data class ParseCell(
    val rows: String,
    val argv: List<String>,
    val expected: GitInputs?,
    val because: String,
)

internal fun ParitySuite<GitInputs>.checkAll(cells: List<ParseCell>) = checkCells(cells) { cell ->
    val argv = cell.argv.toTypedArray()
    val expected = cell.expected
    if (expected == null) {
        rejects(argv = argv, because = "${cell.rows}, real git: ${cell.because}")
    } else {
        binds(argv = argv, expected = expected, because = "${cell.rows}, real git: ${cell.because}")
    }
}

/** Whether the command [line] ends in takes [spelling]'s letter as its own option, with a meaning of its own. */
private fun takesLetterItself(
    line: TreeLine,
    spelling: RootSpelling,
): Boolean = when (line.path.first()) {
    // `git add -p` and `git stage -p` select hunks (`git add -h`).
    "add", "stage" -> spelling.letter == 'p'
    // `git commit -C <commit>` reuses a message, `-c <commit>` edits one and `-p` adds changes (`git commit -h`).
    "commit" -> spelling.letter == 'C' || spelling.letter == 'c' || spelling.letter == 'p'
    // `git log -1 -C d`, `-c`, `-P` and `-p` ran: copy detection, combined diff, Perl regexps and patches.
    "log" -> spelling.letter == 'C' || spelling.letter == 'c' || spelling.letter == 'P' || spelling.letter == 'p'
    else -> false
}

/** Every command bare, and with each root spelling before it: git reads them all. */
internal val BEFORE_THE_SUBCOMMAND: List<ParseCell> = TREE_LINES.flatMap { line ->
    listOf(ParseCell("P1a P1b P1c", line.words, line.record(NO_GLOBALS), "`git $line` runs")) +
        ROOT_SPELLINGS.map { spelling ->
            ParseCell(
                rows = "P4b P20 P21",
                argv = spelling.words + line.words,
                expected = line.record(spelling.globals),
                because = "`git $spelling $line` runs",
            )
        }
}

/** Each root spelling after the leaf's operands, where the leaf has no option of that spelling itself. */
internal val AFTER_THE_LEAF: List<ParseCell> = TREE_LINES.flatMap { line ->
    ROOT_SPELLINGS.filterNot { takesLetterItself(line, it) }.map { spelling ->
        ParseCell("P20", line.words + spelling.words, null, "an unknown option of ${line.path.joinToString(" ")}")
    }
}

/** Each root spelling between `remote` and its child. */
internal val BETWEEN_THE_LEVELS: List<ParseCell> = TREE_LINES.filter { it.path.size == 2 }.flatMap { line ->
    ROOT_SPELLINGS.map { spelling ->
        ParseCell(
            rows = "P20",
            argv = listOf(line.path[0]) + spelling.words + line.path[1] + line.operands,
            expected = null,
            because = "an unknown option of remote",
        )
    }
}

/** Each root spelling between `remote add`'s two operands. */
internal val BETWEEN_THE_OPERANDS: List<ParseCell> = ROOT_SPELLINGS.map { spelling ->
    ParseCell(
        rows = "P20",
        argv = listOf("remote", "add", "o") + spelling.words + "u",
        expected = null,
        because = "an unknown option of remote add",
    )
}
