package com.fromwau.example

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.ColorScope
import com.fromwau.klap.CompletionScope
import com.fromwau.klap.Flag
import com.fromwau.klap.Opt
import com.fromwau.klap.Style
import com.fromwau.klap.ValueScope
import com.fromwau.klap.bold
import com.fromwau.klap.cli
import com.fromwau.klap.cyan
import com.fromwau.klap.dim
import com.fromwau.klap.green
import com.fromwau.klap.main
import com.fromwau.klap.plus
import com.fromwau.klap.red
import com.fromwau.klap.yellow
import kotlinx.io.files.Path

fun main(args: Array<String>) {
    taskManagerCli().main(args)
}

/**
 * The whole command tree, named rather than inlined into [main]: a test drives it through
 * `Cli.run(argv, terminal)`, which needs the built [Cli], not the process-exiting `.main(args)` call below.
 */
internal fun taskManagerCli(): Cli = cli("klapExample") {
    // The showcase opts all the way in: `klapExample lis` reaches `list`, `klapExample list --j` reaches
    // `--json`, and `klapExample add --priority hi` reaches `high` (verified).
    abbreviation = Abbreviation.All
    description = "A tiny file-backed task manager"
    version = "1.0.0"
    author = "The klap example"
    epilogue =
        "Tasks live in a JSON file in the working directory; point --file elsewhere to keep separate lists."

    val storeFile = globalOption("--file", "-f", help = "path to the task store")
        .default(DEFAULT_STORE_FILE)
        .file()

    // Global rather than local to `list`: a verbosity switch is habitually typed before the
    // subcommand, and there it also clusters with -f (`-vvf other.json list`).
    val verbose = globalFlag("--verbose", "-v", help = "show more detail per task").count()

    example(
        "klapExample add \"Ship the release\" --priority high --tag work",
        "add a high-priority task"
    )
    example("klapExample list --status pending", "see what is left to do")
    // Both lines below cluster several short flags behind one dash, ending in the one that takes a
    // value (POSIX guideline 5): -D and -p bundle on add, -r/-l/-n on list.
    example("klapExample add \"Ship it\" -Dp high", "clustered shorts: mark done, then set priority")
    example("klapExample list -rln 5", "clustered shorts: newest first, long form, five of them")

    // One helper for both scopes: the action and the completion provider resolve --file identically.
    // Local, so it can close over the `storeFile` handle the builder just returned.
    fun ValueScope.taskStore() = TaskStore(Path(storeFile()))

    // Offers a candidate per matching task: the id as the value, its title as the description, so
    // `done`/`rm`/`tag` show what each id refers to. Any load failure (corrupt JSON) degrades to
    // no candidates, so a Tab press never crashes or prints an error.
    fun CompletionScope.taskIdCandidates(include: (Task) -> Boolean = { true }) {
        val tasks = taskStore().load().getOrElse { return }
        tasks.filter(include).forEach { candidate(it.id.toString(), it.title) }
    }

    command("add", "create a new task") {
        // Per-command, unlike the root's: `klapExample add --help` closes with this one, never the root's.
        epilogue = "Repeat --tag to attach several labels; --done records work that is already finished."

        val title = argument("title", "what needs doing")

        // Plain `val`s, not `lateinit var`s: group's callsInPlace contract makes assigning them inside
        // the block legal, so the compiler still guarantees each one is initialised exactly once.
        val priority: Opt<Priority>
        val tags: Opt<List<String>>
        val due: Opt<String?>
        val done: Flag

        group("Details") {
            priority = option("--priority", "-p", help = "how urgent")
                .enum<Priority>()
                .default(Priority.MEDIUM)

            tags = option("--tag", "-t", help = "label the task").multiple()

            due = option("--due", "-d", help = "when it's due, e.g. 2026-08-01")
                .validate("must look like YYYY-MM-DD") { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }

            done = flag("--done", "-D", help = "record something you have already finished")
        }

        action(human = { task -> "${green("added")} ${dim("#${task.id}:")} ${bold(task.title)}" }) {
            val store = taskStore()
            store.withLock {
                val tasks = store.load().getOrElse { return@withLock Err(it) }
                val task = Task(
                    id = store.nextId(tasks),
                    title = title(),
                    priority = priority(),
                    tags = tags().distinct(),
                    due = due(),
                    done = done(),
                )
                store.save(tasks + task).getOrElse { return@withLock Err(it) }
                Ok(task)
            }
        }
    }

    command("list", "show your tasks") {
        aliases = listOf("ls")
        val status = option("--status", "-s", help = "filter by status").choice("pending", "done")
        val limit = option("--limit", "-n", help = "show at most this many").int().range(1..100)
        val reverse = flag("--reverse", "-r", help = "newest first")
        // Display-only, unlike --reverse: it never changes which tasks are chosen or their order,
        // only how much of each one the human renderer shows, so it is read in that renderer alongside
        // --verbose rather than here.
        val long = flag("--long", "-l", help = "show due date and tags regardless of -v")

        action(
            human = { tasks ->
                val verbosity = verbose()
                val longForm = long()
                if (tasks.isEmpty()) "no tasks" else tasks.joinToString("\n") { render(it, verbosity, longForm) }
            },
        ) {
            val tasks = taskStore().load().getOrElse { return@action Err(it) }
            val filtered = when (status()) {
                "pending" -> tasks.filterNot { it.done }
                "done" -> tasks.filter { it.done }
                else -> tasks
            }
            // Newest first reorders before the trim below, so `-rn 5` means the five NEWEST rather than
            // the oldest five re-shown backwards. --reverse joins --limit here, not the human renderer,
            // for the same reason: it shapes the data `--json` emits.
            val ordered = if (reverse()) filtered.reversed() else filtered
            // Trim after filtering, so `-n 5 --status pending` means five pending tasks rather than
            // whatever is pending among the first five. --limit belongs here because it shapes the
            // data `--json` emits; --verbose stays in the human renderer because it must not.
            Ok(limit()?.let { ordered.take(it) } ?: ordered)
        }
    }

    command("done", "mark a task complete") {
        // Only pending tasks can be completed, so a done task is not a completion candidate.
        val id = argument("id", "task id").int()
            .completeWith { taskIdCandidates { task -> !task.done } }

        action(human = { task -> "${green("done")} ${dim("#${task.id}:")} ${bold(task.title)}" }) {
            val store = taskStore()
            store.withLock {
                val tasks = store.load().getOrElse { return@withLock Err(it) }
                val task = tasks.find { it.id == id() } ?: return@withLock Err(notFound(id()))
                if (task.done) return@withLock Err(alreadyDone(task.id))

                val updated = task.copy(done = true)
                store.save(tasks.map { if (it.id == task.id) updated else it })
                    .getOrElse { return@withLock Err(it) }
                Ok(updated)
            }
        }
    }

    command("rm", "delete a task") {
        val id = argument("id", "task id").int()
            .completeWith { taskIdCandidates() }

        action(human = { task -> "${yellow("removed")} ${dim("#${task.id}:")} ${bold(task.title)}" }) {
            val store = taskStore()
            store.withLock {
                val tasks = store.load().getOrElse { return@withLock Err(it) }
                val task = tasks.find { it.id == id() } ?: return@withLock Err(notFound(id()))
                store.save(tasks.filterNot { it.id == task.id }).getOrElse { return@withLock Err(it) }
                Ok(task)
            }
        }
    }

    command("tag", "manage a task's tags") {
        command("add", "attach a tag to a task") {
            val id = argument("id", "task id").int()
                .completeWith { taskIdCandidates() }

            // The mirror of `tag rm`'s provider: that one narrows to a single task's tags, this one
            // offers every tag already in use, so a second spelling of an existing tag is a Tab away.
            val tag = argument("tag", "tag to attach")
                .completeWith {
                    val tasks = taskStore().load().getOrElse { return@completeWith }
                    candidates(tasks.flatMap { it.tags }.distinct().sorted())
                }

            action(
                human = { task ->
                    val tags = if (task.tags.isEmpty()) dim("none") else cyan(task.tags.joinToString(", "))
                    "${dim("tags for #${task.id}:")} $tags"
                },
            ) {
                val store = taskStore()
                store.withLock {
                    val tasks = store.load().getOrElse { return@withLock Err(it) }
                    val task = tasks.find { it.id == id() } ?: return@withLock Err(notFound(id()))
                    val updated = task.copy(tags = (task.tags + tag()).distinct())
                    store.save(tasks.map { if (it.id == task.id) updated else it })
                        .getOrElse { return@withLock Err(it) }
                    Ok(updated)
                }
            }
        }

        command("rm", "remove a tag from a task") {
            val id = argument("id", "task id").int()
                .completeWith { taskIdCandidates() }

            // Reads its sibling id to offer only the tags that task actually carries. A malformed id
            // (`tag rm abc <TAB>`) leaves id unbound, and reading an unbound input aborts the provider
            // silently — so Tab offers nothing rather than every tag in the store.
            val tag = argument("tag", "tag to remove")
                .completeWith {
                    val taskId = id()
                    val tasks = taskStore().load().getOrElse { return@completeWith }
                    val tags = tasks.find { it.id == taskId }?.tags ?: return@completeWith
                    candidates(tags)
                }

            action(
                human = { task ->
                    val tags = if (task.tags.isEmpty()) dim("none") else cyan(task.tags.joinToString(", "))
                    "${dim("tags for #${task.id}:")} $tags"
                },
            ) {
                val store = taskStore()
                store.withLock {
                    val tasks = store.load().getOrElse { return@withLock Err(it) }
                    val task = tasks.find { it.id == id() } ?: return@withLock Err(notFound(id()))
                    val updated = task.copy(tags = task.tags.filterNot { it == tag() })
                    store.save(tasks.map { if (it.id == task.id) updated else it })
                        .getOrElse { return@withLock Err(it) }
                    Ok(updated)
                }
            }
        }
    }

    // Kept out of --help: this answers "which store did --file settle on?" when something looks
    // wrong, and is not part of the task workflow the help text is there to teach.
    command("where", "print the configured task-store path") {
        hidden = true
        action { Ok(storeFile()) }
    }
}


/**
 * Renders one task as a single display line, detailed to [verbosity] or forced open by [long].
 *
 * Declared on [ColorScope] — the narrowest receiver that can resolve a style — rather than on the
 * ActionScope the caller actually has: least privilege, so reaching an input like `verbose()` from a
 * formatter is a compile error. It costs nothing here, since `verbose` is a local val of the `cli { }`
 * block and has to arrive as a parameter either way.
 */
private fun ColorScope.render(task: Task, verbosity: Int, long: Boolean): String = buildList {
    add(dim(if (task.done) "[x]" else "[ ]"))
    add(dim("#${task.id}"))
    // A finished task recedes; a pending one is what the reader still has to act on.
    add(if (task.done) dim(task.title) else bold(task.title))

    val priority = task.priority
    add(priority.style("(${priority.name.lowercase()})"))

    // Rungs ordered by how actionable the field is: when it is due before what it is filed under.
    // --long forces both open regardless of --verbose, one flag rather than remembering `-vv`.
    if ((long || verbosity >= 1) && task.due != null) add(dim("(due ${task.due})"))
    if ((long || verbosity >= 2) && task.tags.isNotEmpty()) add(cyan("[${task.tags.joinToString(",")}]"))
}.joinToString(" ")

/** Combined with `+`, never nested: `bold(red(...))` would emit the inner reset and clear the bold again. */
private val Priority.style: Style
    get() = when (this) {
        Priority.HIGH -> bold + red
        Priority.MEDIUM -> yellow
        Priority.LOW -> green
    }

private fun notFound(id: Int) = CliError.Failure("no task with id $id", exitCode = EXIT_NOT_FOUND)

private fun alreadyDone(id: Int) =
    CliError.Failure("task $id is already done", exitCode = EXIT_ALREADY_DONE)
