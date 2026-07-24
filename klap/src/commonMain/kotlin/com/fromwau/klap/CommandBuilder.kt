package com.fromwau.klap

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Identity converter: a raw string passes through unchanged until a type transformer replaces it. */
private val passthrough: (String) -> Result<Any?, String> = { Result.Success(it) }

/** Restricts implicit receivers so a nested block can't reach an outer builder's members. */
@DslMarker
annotation class KlapDsl

/** DSL receiver for a (sub)command. Has NO version — that is a root-only concern. */
@KlapDsl
interface CommandBuilder {
    var description: String
    var aliases: List<String>
    fun argument(name: String, help: String = ""): Arg<String>
    fun option(long: String, short: String? = null, help: String = ""): Opt<String?>
    fun flag(long: String, short: String? = null, help: String = ""): Flag
    fun command(name: String, block: CommandBuilder.() -> Unit)
}

/** DSL receiver for the root CLI: a [CommandBuilder] plus root-only settings. */
@KlapDsl
interface CliBuilder : CommandBuilder {
    var version: String?
}

/** The single implementation behind both interfaces. `@PublishedApi` so the inline [action] can cast to it. */
@PublishedApi
internal class BuilderImpl(private val name: String) : CliBuilder {
    override var description: String = ""
    override var aliases: List<String> = emptyList()
    override var version: String? = null

    private val specs = mutableListOf<HolderSpec>()
    private val subs = mutableListOf<Cli>()
    private var actionSpec: ActionSpec? = null

    override fun argument(name: String, help: String): Arg<String> {
        val spec = HolderSpec(
            name,
            null,
            help,
            InputKind.ARGUMENT,
            passthrough,
            Cardinality.Required,
            null,
            false
        )
        specs += spec
        return Arg(spec)
    }

    override fun option(long: String, short: String?, help: String): Opt<String?> {
        val spec = HolderSpec(
            long,
            short,
            help,
            InputKind.OPTION,
            passthrough,
            Cardinality.Optional,
            null,
            false
        )
        specs += spec
        return Opt(spec)
    }

    override fun flag(long: String, short: String?, help: String): Flag {
        val spec = HolderSpec(
            long,
            short,
            help,
            InputKind.FLAG,
            passthrough,
            Cardinality.Optional,
            null,
            false
        )
        specs += spec
        return Flag(spec)
    }

    override fun command(name: String, block: CommandBuilder.() -> Unit) {
        val b = BuilderImpl(name)
        b.block()
        subs += b.build()
    }

    /** Set by the reified [action] extension; it captures the block's serializer and optional human renderer. */
    @PublishedApi
    internal fun setActionSpec(spec: ActionSpec) {
        actionSpec = spec
    }

    internal fun build(): Cli {
        validatePositionals()
        return Cli(name, aliases, description, version, specs.toList(), subs.toList(), actionSpec)
    }

    /** Positionals must be `required* (optional|default)* multiple?`: a variadic is greedy so it must be last, and a required-after-optional is ambiguous. A violation fails loudly at build time. */
    private fun validatePositionals() {
        val positionals = specs.filter { it.kind == InputKind.ARGUMENT }
        require(positionals.count { it.cardinality is Cardinality.Multiple } <= 1) {
            "command '$name': at most one variadic (multiple) argument is allowed"
        }
        val multipleIndex = positionals.indexOfFirst { it.cardinality is Cardinality.Multiple }
        require(multipleIndex < 0 || multipleIndex == positionals.lastIndex) {
            "command '$name': a variadic (multiple) argument must be the last positional"
        }
        var sawOptional = false
        for (spec in positionals) {
            require(!(spec.cardinality == Cardinality.Required && sawOptional)) {
                "command '$name': required argument '${spec.name}' cannot follow an optional/default argument"
            }
            if (spec.cardinality !is Cardinality.Required && spec.cardinality !is Cardinality.Multiple) {
                sawOptional = true
            }
        }
    }
}

/** The built-in `completion <shell>` subcommand, added to every root command. */
private fun completionCommand(root: () -> Cli): Cli = command0("completion") {
    description = "Print a shell completion script (bash|zsh|fish)"
    // .enum makes an unknown shell a typed InvalidChoice (rendered at the edge), never a hand-rolled string.
    val shell = argument("shell", help = "Shell to generate the script for").enum<CompletionShell>()
    action { Ok(root().renderCompletion(shell())) }
}

/** Internal builder that does NOT inject builtins (prevents infinite recursion). */
private fun command0(
    @Suppress("SameParameterValue") name: String,
    block: CommandBuilder.() -> Unit,
): Cli = BuilderImpl(name).apply(block).build()

/** Entry point: build a command tree with the built-in `completion` subcommand injected into the root. */
fun cli(name: String, block: CliBuilder.() -> Unit): Cli {
    val base = BuilderImpl(name).apply(block).build()
    // Deliberate self-reference: completion must render the FULL tree (incl. itself), so it closes over `self`, set after build.
    var self: Cli? = null
    val root = Cli(
        name = base.name,
        aliases = base.aliases,
        description = base.description,
        version = base.version,
        specs = base.specs,
        subcommands = base.subcommands + completionCommand { self!! },
        action = base.action,
    )
    self = root
    return root
}

/**
 * A leaf command's action: [block] returns `Result<T, CliError>` where `T` is serializable. Under `--json`
 * the `Ok(value)` is rendered with `serializer<T>()`; otherwise via the optional [human] renderer, defaulting
 * to `toString()`. `T` is inferred from [block] and shared with [human], so `human`'s parameter is typed.
 *
 * Reified so it can capture `serializer<T>()`. Consumers returning an `@Serializable` type must apply the
 * `kotlin("plugin.serialization")` plugin; `String`/primitive returns need no plugin.
 */
inline fun <reified T> CommandBuilder.action(
    noinline human: ((T) -> String)? = null,
    noinline block: () -> Result<T, CliError>,
) {
    @Suppress("UNCHECKED_CAST")
    val spec = ActionSpec(
        block as () -> Result<Any?, CliError>,
        serializer<T>() as KSerializer<Any?>,
        human?.let { render -> { value: Any? -> render(value as T) } },
    )
    (this as BuilderImpl).setActionSpec(spec)
}
