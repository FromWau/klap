package com.fromwau.klap

/** A resolved, immutable node in the command tree. Leaves carry an [action]; pure groups do not. */
class Cli internal constructor(
    val name: String,
    val aliases: List<String>,
    val description: String,
    val version: String?,
    internal val specs: List<HolderSpec>,
    val subcommands: List<Cli>,
    internal val action: ActionSpec?,
) {
    internal val arguments: List<HolderSpec> get() = specs.filter { it.kind == InputKind.ARGUMENT }
    internal val options: List<HolderSpec> get() = specs.filter { it.kind == InputKind.OPTION }
    internal val flags: List<HolderSpec> get() = specs.filter { it.kind == InputKind.FLAG }

    /** A group prints subcommand help when invoked: it has children and no own action. */
    internal val isGroup: Boolean get() = subcommands.isNotEmpty() && action == null

    fun subcommand(token: String): Cli? =
        subcommands.firstOrNull { it.name == token || token in it.aliases }
}
