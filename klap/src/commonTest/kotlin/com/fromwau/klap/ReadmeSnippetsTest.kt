package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The README's load-bearing snippets, transcribed and executed.
 *
 * An ergonomics study found ten of ten agents reading klap's source rather than its README, and one
 * cause was a snippet that did not compile as printed: it opened with a `CliBuilder` member at what
 * read as file top level, so a reader copying it got an unresolved reference instead of a program.
 * Prose cannot catch that regressing. These tests can, so the README's compilability is a verified
 * property rather than a claim.
 *
 * Each block below names the README section it comes from. Keep them transcriptions: a snippet
 * "improved" here but not there proves nothing about the document a reader actually opens.
 */
class ReadmeSnippetsTest {

    // --- "A whole program": the front-page snippet, and each claim the paragraph under it makes ---

    @Test
    fun `the front page program repeats and shouts`() {
        assertEquals("HELLO, ADA!\nHELLO, ADA!", greetCli().render("Ada", "-n", "2", "--loud"))
    }

    @Test
    fun `the front page program defaults to one polite line`() {
        assertEquals("Hello, Ada.", greetCli().render("Ada"))
    }

    @Test
    fun `the front page program reports a missing operand and exits non zero`() {
        val terminal = RecordingTerminal()
        val exit = greetCli().run(emptyArray(), terminal)
        assertTrue(exit != 0, "expected a non-zero exit, got $exit")
        assertContains(terminal.err.toString(), "name")
    }

    @Test
    fun `the front page program suggests the closest option on a typo`() {
        val terminal = RecordingTerminal()
        greetCli().run(arrayOf("Ada", "--tmies", "2"), terminal)
        assertContains(terminal.err.toString(), "--times")
    }

    /**
     * The option form, not a `completion` subcommand: that one is injected only when the root has no
     * action (`CliBuilder.kt:52`), and this program acts at its root. The README says so in as many words,
     * because the first draft of it claimed `greet completion bash` and this test rejected it.
     */
    @Test
    fun `the front page program ships a completion script it never declared`() {
        assertContains(greetCli().render("--completion", "bash"), "_greet")
    }

    // --- "Reading the values from outside": the cliOf / projection snippet ---

    @Test
    fun `the projection snippet returns exactly what the readme says it does`() {
        // The README prints this call with its result in a trailing comment. Both are asserted here so the
        // comment cannot quietly stop being true.
        assertEquals(
            Ok(GreetArgs(name = "Ada", times = 2, loud = false)),
            greetProjection().parse(listOf("Ada", "-n", "2")),
        )
    }

    // --- "Everything lives in package com.fromwau.klap": factoring a declaration out of two commands ---

    @Test
    fun `a command builder extension shares one declaration between two subcommands`() {
        val cli = sharedDeclaration()
        // Each command gets its OWN input from the extension, which is the claim the README makes
        // about returning the handle rather than hoisting a shared `val`.
        assertEquals("listing [work]", cli.render("list", "-t", "work"))
        assertEquals("closing []", cli.render("done"))
    }

    // --- "Typed results and errors": Ok/Err build, Result.Success/Result.Error match ---

    @Test
    fun `a parse result is matched on the subtypes rather than the builders`() {
        when (val parsed = sharedDeclaration().parse(listOf("list", "-t", "work"))) {
            is Result.Success -> assertIs<Invocation.Execute>(parsed.value)
            is Result.Error -> throw AssertionError("expected a successful parse, got ${parsed.error}")
        }
    }

    @Test
    fun `map error turns a domain error into a cli error without unwrapping`() {
        val result: Result<String, CliError> = TaskStore().load()
            .mapError { CliError.Failure("cannot read the store: ${it.detail}") }
            .map { tasks -> "loaded ${tasks.size} task(s)" }
        assertEquals(Ok("loaded 2 task(s)"), result)
    }

    @Test
    fun `map error leaves a success alone and map leaves an error alone`() {
        val failing: Result<List<StoredTask>, TaskStoreError> = Err(TaskStoreError.DiskFull)
        val mapped: Result<String, CliError> = failing
            .mapError { CliError.Failure("cannot read the store: ${it.detail}") }
            .map { "unreachable" }
        assertEquals(Err(CliError.Failure("cannot read the store: disk full")), mapped)
    }

    // --- "Completion": one helper shared by an action and a completion provider ---

    @Test
    fun `a local value scope extension inside cli closes over a global handle`() {
        // The snippet's whole point: `globalOption` is a CliBuilder member, so the helper has to live
        // inside the `cli { }` lambda to see the handle it returned. At file scope it does not compile.
        assertEquals("tagged 3 in tasks.json", valueScopeHelper().render("tag", "3", "urgent"))
    }

    @Test
    fun `the shared helper resolves the global the same way from either scope`() {
        val candidates = valueScopeHelper().completeCandidates(listOf("tag", "3", ""))
        assertEquals(listOf("x", "y"), candidates.map { it.value })
    }

    private fun Cli.render(vararg argv: String): String = RecordingTerminal().let { terminal ->
        run(argv.toList().toTypedArray(), terminal)
        terminal.out.toString().trim()
    }
}

/**
 * The README's front-page program, verbatim but for the entry point: the snippet ends `}.main(args)`
 * inside `fun main`, which would run the process rather than hand back something to assert on.
 */
private fun greetCli() = cli("greet") {
    version = "1.0.0"

    val name = argument("name", "who to greet")
    val times = option("--times", "-n", help = "repeat count").int().default(1)
    val loud = flag("--loud", "-l", help = "shout it")

    action {
        val line = if (loud()) "HELLO, ${name().uppercase()}!" else "Hello, ${name()}."
        Ok(List(times()) { line }.joinToString("\n"))
    }
}

/** Stands in for the README's `Task`; only the two fields its snippets read are modelled. */
private class StoredTask(val id: Int, val tags: List<String>)

/** Stands in for the guide's `StoreError`: the store's own failure type, rooted in kern's [IError]. */
private sealed interface TaskStoreError : IError {
    val detail: String

    data object DiskFull : TaskStoreError {
        override val detail: String = "disk full"
    }
}

/** Stands in for the README's `TaskStore`, whose `load()` returns a typed-error result. */
private class TaskStore {
    fun load(): Result<List<StoredTask>, TaskStoreError> =
        Ok(listOf(StoredTask(1, listOf("a")), StoredTask(3, listOf("x", "y"))))
}

private fun CommandBuilder.tagOption() =
    option("--tag", "-t", help = "Filter by tag").multiple()

private fun sharedDeclaration() = cli("tasks") {
    command("list") {
        val tags = tagOption()
        action { Ok("listing ${tags()}") }
    }
    command("done") {
        val tags = tagOption()
        action { Ok("closing ${tags()}") }
    }
}

private fun valueScopeHelper() = cli("tasks") {
    val store = globalOption("--file", "-f").default("tasks.json")

    fun ValueScope.taskStore() = TaskStore()

    command("tag") {
        val id = argument("id").int()
        argument("tag").completeWith {
            val taskId = id()
            val tasks = taskStore().load().getOrElse { return@completeWith }
            candidates(tasks.find { it.id == taskId }?.tags ?: return@completeWith)
        }
        action { Ok("tagged ${id()} in ${store()}") }
    }
}

/** The README's "Reading the values from outside" snippet, verbatim. */
private data class GreetArgs(val name: String, val times: Int, val loud: Boolean)

private fun greetProjection() = cliOf("greet") {
    val name = argument("name")
    val times = option("--times", "-n").int().default(1)
    val loud = flag("--loud", "-l")
    action { Ok("...") }
    projection { GreetArgs(name(), times(), loud()) }
}
