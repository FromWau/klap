package com.fromwau.example

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.terminal.Style
import com.fromwau.kern.terminal.bold
import com.fromwau.kern.terminal.cyan
import com.fromwau.kern.terminal.dim
import com.fromwau.kern.terminal.green
import com.fromwau.kern.terminal.red
import com.fromwau.kern.terminal.yellow
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Cli
import com.fromwau.klap.CliError
import com.fromwau.klap.ColorScope
import com.fromwau.klap.CompletionScope
import com.fromwau.klap.Flag
import com.fromwau.klap.Opt
import com.fromwau.klap.ValueScope
import com.fromwau.klap.cli
import com.fromwau.klap.main
import com.fromwau.klap.name
import kotlin.time.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
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
    version = VERSION
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

    // Every tag already in use, for the inputs that take one: filtering by a tag nothing carries, or
    // spelling an existing tag a second way, are both mistakes Tab can prevent outright.
    fun CompletionScope.knownTagCandidates() {
        val tasks = taskStore().load().getOrElse { return }
        candidates(tasks.allTags())
    }

    command("add", "create a new task") {
        // Per-command, unlike the root's: `klapExample add --help` closes with this one, never the root's.
        epilogue = "Repeat --tag to attach several labels; --done records work that is already finished."

        // Required only guarantees the token is present: `add ""` satisfies the parser otherwise.
        // Validated before the trim so the rejection quotes what was typed.
        val title = argument("title", "what needs doing")
            .validate(MUST_NOT_BE_BLANK) { it.isNotBlank() }
            .map { it.trim() }

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

            // Checked and trimmed before .multiple(), so each repeat is validated on its own rather
            // than the assembled list.
            tags = option("--tag", "-t", help = "label the task")
                .validate(MUST_NOT_BE_BLANK) { it.isNotBlank() }
                .map { it.trim() }
                .multiple()

            // Shape only. Whether a past date is wrong depends on --done, which no per-value check can
            // read, so that half is decided by the validateInputs below.
            // No sample date in the help: any literal here is one that later stops being acceptable.
            due = option("--due", "-d", help = "when it's due, YYYY-MM-DD (past needs --done)")
                .validate(DUE_NOT_A_DATE) { it.asDate() != null }

            done = flag("--done", "-D", help = "record something you have already finished")
        }

        // Belongs to the pair, not to either input: a past date is a typo on work still to do and
        // ordinary on work already finished, so neither value is wrong on its own.
        validateInputs {
            val dueDate = due()
            if (dueDate != null && !done() && dueDate.isPastDue()) pastDue(dueDate) else null
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
        val namedLimit = option("--limit", "-n", help = "show at most this many").int().range(1..100)
        // Both spellings carry the range independently: the fold picks a winner, it does not share a
        // converter chain, so dropping it from either would let that spelling through unchecked.
        val directLimit = numberOption(help = "same as -n NUM").int().range(1..100)
        val limit = lastOneWins(namedLimit, directLimit)
        val reverse = flag("--reverse", "-r", help = "newest first")
        // Display-only, unlike --reverse: it never changes which tasks are chosen or their order,
        // only how much of each one the human renderer shows, so it is read in that renderer alongside
        // --verbose rather than here.
        val long = flag("--long", "-l", help = "show due date and tags regardless of -v")

        // Checked and trimmed exactly like `add --tag`: stored tags carry no padding, so a padded
        // filter would quietly match nothing instead of the tag that was plainly meant.
        val onlyTag = option("--tag", "-t", help = "filter by tag")
            .validate(MUST_NOT_BE_BLANK) { it.isNotBlank() }
            .map { it.trim() }
            .completeWith { knownTagCandidates() }

        action(
            human = { tasks ->
                val verbosity = verbose()
                val longForm = long()
                if (tasks.isEmpty()) "no tasks" else tasks.joinToString("\n") { render(it, verbosity, longForm) }
            },
        ) {
            val tasks = taskStore().load().getOrElse { return@action Err(it) }
            val byStatus = when (status()) {
                "pending" -> tasks.filterNot { it.done }
                "done" -> tasks.filter { it.done }
                else -> tasks
            }
            val filtered = onlyTag()?.let { tag -> byStatus.filter { tag in it.tags } } ?: byStatus
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
                .validate(MUST_NOT_BE_BLANK) { it.isNotBlank() }
                .map { it.trim() }
                .completeWith { knownTagCandidates() }

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
                    // Here rather than in validateInputs: a rule that reads the store has to hold the lock
                    // it checked under, and nothing holds between that block and this write.
                    if (tag() !in task.tags) return@withLock Err(CliError.BadValue(tag.name, tag(), NO_SUCH_TAG))
                    val updated = task.copy(tags = task.tags.filterNot { it == tag() })
                    store.save(tasks.map { if (it.id == task.id) updated else it })
                        .getOrElse { return@withLock Err(it) }
                    Ok(updated)
                }
            }
        }
    }

    // A sibling of `tag` rather than a `tag list` subcommand: this answers about the store as a whole,
    // where every `tag` subcommand acts on one task. Exact spellings beat prefixes, so both full names
    // stay reachable; the cost is that `t` and `ta` now name two commands and stop resolving.
    command("tags", "list every tag in use") {
        action(
            human = { tags -> if (tags.isEmpty()) "no tags" else tags.joinToString("\n") { cyan(it) } },
        ) {
            val tasks = taskStore().load().getOrElse { return@action Err(it) }
            Ok(tasks.allTags())
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

/** Sorted so `tags` and Tab agree on an order, and neither depends on which task was added first. */
private fun List<Task>.allTags(): List<String> = flatMap { it.tags }.distinct().sorted()

private const val MUST_NOT_BE_BLANK = "must not be blank"

private const val NO_SUCH_TAG = "task does not carry that tag"

private const val DUE_NOT_A_DATE = "must be a real date in YYYY-MM-DD form"

/** Parsing rather than shape-matching is what rejects 2026-13-45, which a `\d{4}-\d{2}-\d{2}` accepts. */
private fun String.asDate(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

// Reached only once the format check has passed, so an unparsable value is nobody's idea of overdue.
private fun String.isPastDue(): Boolean =
    asDate()?.let { it < Clock.System.todayIn(TimeZone.currentSystemDefault()) } ?: false

private fun pastDue(due: String) =
    CliError.Usage("--due $due is earlier than today; pass --done to record work already finished")

private fun notFound(id: Int) = CliError.Failure("no task with id $id", exitCode = EXIT_NOT_FOUND)

private fun alreadyDone(id: Int) =
    CliError.Failure("task $id is already done", exitCode = EXIT_ALREADY_DONE)
