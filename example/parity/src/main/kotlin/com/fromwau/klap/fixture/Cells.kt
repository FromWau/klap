package com.fromwau.klap.fixture

import kotlin.test.fail

/** [line] with `|` at [cursor], for a failure message. */
public fun marked(
    line: String,
    cursor: Int = line.length,
): String = "`" + line.take(cursor) + "|" + line.drop(cursor) + "`"

/** Runs [check] on every cell and fails once, naming every cell that failed and how many did. */
public fun <T> checkCells(
    cells: List<T>,
    check: (T) -> Unit,
) {
    if (cells.isEmpty()) fail("no cells to check")
    val failures = cells.mapNotNull { cell ->
        try {
            check(cell)
            null
        } catch (e: AssertionError) {
            e.message
        }
    }
    if (failures.isNotEmpty()) {
        fail("${failures.size} of ${cells.size} cells failed:\n" + failures.joinToString("\n"))
    }
}
