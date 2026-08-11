package com.fromwau.example

import com.fromwau.klap.USAGE_ERROR_EXIT
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.json.Json

/**
 * Short-flag clustering (POSIX guideline 5): several booleans and at most one value-taking option,
 * bundled behind one dash. klap's parser already enforces this; these tests pin that `list`'s `-r`/`-l`
 * and `add`'s `-D` compose the way a real invocation like `rsync -vauP ...` does.
 */
class ClusteredShortFlagsTest {

    @Test
    fun `a pure boolean cluster sets both reverse and long`() = withTempStore { path ->
        val cli = taskManagerCli()
        cli.captureWithFile(path, "add", "First task", "--due", "2026-01-01", "--tag", "alpha")
        cli.captureWithFile(path, "add", "Second task", "--due", "2026-02-02", "--tag", "beta")

        // `-rl` clusters two booleans with nothing left over: no value-taker, so no attached/next-token value.
        val result = cli.captureWithFile(path, "list", "-rl")
        assertEquals(0, result.exitCode, result.err)

        val lines = result.out.trim().lines()
        assertEquals(2, lines.size)
        // -r: newest first, so task 2 renders before task 1.
        assertContains(lines[0], "Second task")
        // -l: due date and tags shown even though -v was never given.
        assertContains(lines[0], "2026-02-02")
        assertContains(lines[0], "beta")
        assertContains(lines[1], "First task")
    }

    @Test
    fun `a cluster ending in a value taker binds the next token as that value`() = withTempStore { path ->
        val cli = taskManagerCli()
        for (n in 1..7) cli.captureWithFile(path, "add", "Task $n")

        // -n is LAST in the cluster, so "5" is its value, not a stray operand. `list` declares no
        // argument(...) at all, so if "5" were misread as an operand this would fail with too-many-arguments
        // instead of succeeding with exactly five lines.
        val result = cli.captureWithFile(path, "list", "-rln", "5")
        assertEquals(0, result.exitCode, result.err)

        val lines = result.out.trim().lines()
        assertEquals(5, lines.size, result.out)
        // Newest five, in reverse order: 7, 6, 5, 4, 3.
        assertContains(lines[0], "#7")
        assertContains(lines[4], "#3")
        assertFalse("#2" in result.out, result.out)
        assertFalse("#1" in result.out, result.out)
    }

    @Test
    fun `the same cluster shape on add binds a flag then an options value`() = withTempStore { path ->
        val cli = taskManagerCli()
        // -D takes no value; -p is last, so "high" is its value, exactly like -rln above but on `add`.
        val added = cli.captureWithFile(path, "add", "Ship it", "-Dp", "high")
        assertEquals(0, added.exitCode, added.err)

        val tasks = Json.decodeFromString<List<Task>>(cli.captureWithFile(path, "list", "--json").out.trim())
        val task = tasks.single()
        assertEquals(true, task.done)
        assertEquals(Priority.HIGH, task.priority)
    }

    @Test
    fun `a counted global before the subcommand and a clustered local after both bind`() = withTempStore { path ->
        val cli = taskManagerCli()
        cli.captureWithFile(path, "add", "First task", "--due", "2026-01-01")
        cli.captureWithFile(path, "add", "Second task", "--due", "2026-02-02")

        // -vv is global and precedes the subcommand; -rl is local and follows it. Neither cluster reaches
        // into the other's specs, so both must still bind from the same command line.
        val result = cli.captureWithFile(path, "-vv", "list", "-rl")
        assertEquals(0, result.exitCode, result.err)

        val lines = result.out.trim().lines()
        assertEquals(2, lines.size)
        assertContains(lines[0], "Second task")
        assertContains(lines[0], "2026-02-02")
        assertContains(lines[1], "First task")
    }

    @Test
    fun `a value taker not last in a cluster consumes the rest of the token as its value`() = withTempStore { path ->
        // "-rnl": -r is a flag, but -n is a value-taking option, so IT consumes the rest of the token ("l")
        // as ITS raw value rather than letting -l parse as a third flag. "l" is not an integer, so --limit
        // rejects it. This is the rule readers get wrong: only the LAST option in a cluster may take a value.
        val result = taskManagerCli().captureWithFile(path, "list", "-rnl")
        assertEquals(USAGE_ERROR_EXIT, result.exitCode, result.err)
        assertContains(result.err, "invalid value 'l' for --limit")
    }

    @Test
    fun `list help shows the new clusterable flags`() {
        val result = taskManagerCli().capture("list", "--help")
        assertEquals(0, result.exitCode, result.err)
        assertContains(result.out, "-r, --reverse")
        assertContains(result.out, "-l, --long")
    }

    @Test
    fun `add help shows the done short`() {
        val result = taskManagerCli().capture("add", "--help")
        assertEquals(0, result.exitCode, result.err)
        assertContains(result.out, "-D, --done")
    }

    @Test
    fun `root help shows a clustered example`() {
        val result = taskManagerCli().capture("--help")
        assertEquals(0, result.exitCode, result.err)
        assertContains(result.out, "-Dp high")
        assertContains(result.out, "-rln 5")
    }
}
