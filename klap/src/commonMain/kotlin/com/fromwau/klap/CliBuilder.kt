package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.internal.builder.BuilderImpl
import com.fromwau.klap.internal.builder.validateReservedNames
import com.fromwau.klap.internal.spec.Builtin

/** DSL receiver for the root CLI: a [CommandBuilder] plus root-only settings. */
@KlapDsl
public abstract class CliBuilder internal constructor() : CommandBuilder() {
    /** What `--version` reports. Leaving it null removes the `--version` option altogether. */
    public abstract var version: String?

    /** The tool's author, rendered in `--help`'s footer, the man page's `AUTHOR` section, and generated docs. */
    public abstract var author: String?

    /** How far klap resolves a partially typed name; see [Abbreviation]. */
    public abstract var abbreviation: Abbreviation

    /**
     * A position-independent option shared by every subcommand: recognized before, between, or
     * after the subcommand path, bound once, and readable from any nested action by closing over the
     * returned [Opt] as a `val`.
     *
     * @see [CommandBuilder.option] for how [names] and [help] are read.
     */
    public abstract fun globalOption(vararg names: String, help: String = ""): Opt<String?>

    /** The flag counterpart of [globalOption]. */
    public abstract fun globalFlag(vararg names: String, help: String = ""): Flag

    /**
     * Declines one or more of klap's injected built-ins; see [BuiltinsBuilder] for the switches and what
     * each one frees up. Root-only, and order-independent: a disabled built-in's name is freed for the
     * whole tree, whether the block sits above or below the `command(...)` that claims it.
     */
    public abstract fun builtins(block: BuiltinsBuilder.() -> Unit)
}

/**
 * Builds a CLI named [name] from the declarations in [block]. This is where most tools start.
 *
 * ```kotlin
 * val app = cli("greet") {
 *     description = "say hello"
 *     val name = argument("name", "who to greet")
 *     action { Ok("hello, ${name()}") }
 * }
 * ```
 *
 * klap adds `--help`, and the `completion` and `docs` built-ins, unless a `builtins { }` block declines
 * them. Run the result with `main(args)`, or with `run(args, terminal)` when you want the exit code back
 * instead of a terminated process.
 */
public fun cli(name: String, block: CliBuilder.() -> Unit): Cli = build(name, block).first

/**
 * A [Cli] whose parses come back as [T], a type of your own made of ordinary values.
 *
 * With a plain [Cli] you hold the declaration handles and read them inside a scope. Here that read has
 * already happened: [parse] hands you [T].
 */
public class TypedCli<T> internal constructor(
    public val cli: Cli,
    private val readers: Map<Command?, ValueScope.() -> T>,
) {
    /**
     * Parses [argv] and projects the values the resolved command bound into [T].
     *
     * `null` means the line resolved to a built-in rather than to a command, so there is nothing to
     * project: `--help`, `--version`, completion and docs all answer without reaching an action.
     */
    public fun parse(argv: Collection<String>): Result<T?, CliError> = when (val parsed = cli.parse(argv)) {
        is Result.Error -> Err(parsed.error)
        is Result.Success -> when (val invocation = parsed.value) {
            // The root's own reader is keyed null, and is the fallback for a root that acts: a projection
            // written before the Cli exists cannot name the command it will belong to.
            is Invocation.Execute -> Ok(invocation.inputs.readerFor(invocation.command))
            else -> Ok(null)
        }
    }

    private fun ValueScope.readerFor(command: Command): T {
        val read = readers[command] ?: readers[null] ?: error(
            "no projection declared for command '${command.name}'; cliOf requires one per executable command",
        )
        return read()
    }
}

/**
 * [cli], but the block ends in a projection from the parsed values into a type of your own.
 *
 * The projection runs once per parse, and what you receive is [T]: plain values, comparable and printable.
 *
 * ```kotlin
 * data class HeadArgs(val lines: String?, val quiet: Boolean, val files: List<String>)
 *
 * val head = cliOf("head") {
 *     val lines = option("--lines", "-n")
 *     val quiet = flag("--quiet", "-q")
 *     val files = argument("file").multiple()
 *     projection { HeadArgs(lines(), quiet(), files()) }
 * }
 * ```
 */
public fun <T> cliOf(name: String, block: CliBuilder.() -> Projection<T>): TypedCli<T> {
    val (cli, projection) = build(name, block)
    val readers = projection.readers()
    validateEveryExecutableCommandProjects(cli, readers)
    return TypedCli(cli, readers)
}

/**
 * Rejects a tree where some command can execute but nothing says how to read it, at construction rather
 * than on the argv that happens to reach it. A root-level reader (key `null`) covers everything, which is
 * the flat single-command case.
 */
private fun validateEveryExecutableCommandProjects(cli: Cli, readers: Map<Command?, *>) {
    if (readers.containsKey(null)) return
    val unprojected = mutableListOf<String>()
    fun walk(command: Command, path: String) {
        // A built-in node carries no action and is answered by the renderer, never projected.
        if (command.builtinKind == null && command.action != null && !readers.containsKey(command)) {
            unprojected += path
        }
        command.subcommands.forEach { walk(it, if (path.isEmpty()) it.name else "$path ${it.name}") }
    }
    walk(cli, "")
    require(unprojected.isEmpty()) {
        "cli '${cli.name}': no projection for ${unprojected.joinToString(", ") { "'$it'" }}. Every command " +
            "with an action must end its block in projection { }, and the root must combine them with " +
            "dispatch(...)"
    }
}

/** How to read one parse into [T]: either a single reader, or a [dispatch] over per-command readers. */
public class Projection<out T> internal constructor(
    internal val read: (ValueScope.() -> T)?,
    internal val parts: List<Projection<T>>,
) {
    /**
     * The command whose values this reads, or null while it is still the value of a block that has not
     * been attached to one. `command(...)` sets it as the block returns, which is the only moment both
     * the built [Command] and the projection over its handles exist together.
     */
    internal var owner: Command? = null
        private set

    internal fun claim(command: Command) {
        if (owner == null) owner = command
        // A command that both acts and nests returns `dispatch(child, ..., projection { })`. Its children
        // were claimed by their own `command(...)` calls as those returned, so a part still unclaimed and
        // carrying a reader is this command's own, not a descendant's.
        parts.forEach { if (it.owner == null && it.read != null) it.claim(command) }
    }

    /** Every reader in this subtree, keyed by the command it reads; a null key is the root's own. */
    internal fun readers(): Map<Command?, ValueScope.() -> T> = buildMap {
        read?.let { put(owner, it) }
        parts.forEach { putAll(it.readers()) }
    }
}

/** Reads the values one command bound into [T]. The last expression of a `command { }` or `cliOf { }` block. */
public fun <T> projection(read: ValueScope.() -> T): Projection<T> = Projection(read, emptyList())

/**
 * Combines the per-command projections of a tree, so `parse` reads whichever command actually ran.
 *
 * [T] comes out as the common supertype of the parts, so a sealed result type fits naturally:
 * `dispatch(commit, status)` over `Projection<Commit>` and `Projection<Status>` gives a `Projection<GitArgs>`
 * your `when` can cover exhaustively.
 *
 * Nesting flattens: a part that is itself a `dispatch` contributes its leaves, since a line resolves to one
 * leaf command.
 */
public fun <T> dispatch(vararg parts: Projection<T>): Projection<T> = Projection(null, parts.toList())

/** Runs [block] against a fresh builder and returns the finished [Cli] beside whatever the block ended in. */
private fun <T> build(name: String, block: CliBuilder.() -> T): Pair<Cli, T> {
    val builder = BuilderImpl(name)
    // Not `apply`: that returns the receiver and drops the block's value, which is the whole point here.
    val handles = builder.block()
    require(builder.aliases.isEmpty()) {
        "cli '$name': the root cannot have aliases; aliases apply to command(...) subcommands"
    }
    val base = builder.build()
    val builtins = builder.builtBuiltins()
    // Deferred to here, unlike every other well-formedness rule: which names a built-in reserves depends on
    // `builtins { }`, and a subcommand's own build() ran back when its `command(...)` was declared, possibly
    // before that block was reached. Walking the finished tree makes the rule independent of that order.
    validateReservedNames(base, builder.builtGlobals(), builtins)

    // __complete is unconditional: it is hidden plumbing for `.completeWith` providers, reachable through
    // the public renderCompletion() escape hatch even when the `completion` built-in itself is declined.
    val injected = buildList {
        add(completeCommand())
        if (base.action == null) {
            if (builtins.completion) add(completionCommand())
            if (builtins.docs) add(docsCommand())
        }
    }

    return Cli(
        name = base.name,
        author = builder.author,
        version = builder.version,
        specs = base.specs,
        constraints = base.constraints,
        subcommands = base.subcommands + injected,
        action = base.action,
        numericAlias = base.numericAlias,
        globalSpecs = builder.builtGlobals(),
        display = base.display,
        builtins = builtins,
        abbreviation = builder.abbreviation,
        optionsEndAtFirstOperand = base.optionsEndAtFirstOperand,
    ) to handles
}

/**
 * The built-in `completion <shell>` subcommand. An action-less declaration: it exists so `--help`, the
 * docs, and completion can list it, but the parser routes it to [Cli.renderCompletion] (see `routeBuiltin`)
 * rather than executing it, so the node needs no reference to the root it renders.
 */
private fun completionCommand(): Command = builtin("completion", Builtin.Completion) {
    description = "Print a shell completion script (bash|zsh|fish|powershell)"
    // .enum surfaces the shell choices in help/docs; the parser does the actual parsing (via CompletionShell.fromOrNull).
    argument("shell", help = "Shell to generate the script for").enum<CompletionShell>()
}

/**
 * The built-in, hidden `__complete` subcommand: given the words typed so far, the parser routes it to
 * [Cli.completeCandidates], which prints one candidate per line for the word under the cursor. Backs a
 * `.completeWith { }` provider a static shell script cannot call back into Kotlin for.
 */
private fun completeCommand(): Command = builtin("__complete", Builtin.Complete) {
    hidden = true
    description = "Internal: print completion candidates for the given words"
    argument("words", help = "words typed so far").multiple()
}

/** The built-in `docs <format>` subcommand; the parser routes it to [Cli.renderDocs] (see [completionCommand]). */
private fun docsCommand(): Command = builtin("docs", Builtin.Docs) {
    description = "Print generated documentation (markdown|man)"
    argument("format", help = "Doc format to generate").enum<DocFormat>()
}

private fun builtin(
    name: String,
    kind: Builtin,
    block: CommandBuilder.() -> Unit,
): Command = BuilderImpl(name).apply(block).build(builtinKind = kind)
