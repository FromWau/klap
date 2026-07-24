package com.fromwau.klap

/** Globally-scoped, position-independent flags resolved before dispatch. */
data class Globals(val json: Boolean)

/** What a successful parse resolves to: run this command, or show help/version instead. */
sealed interface Invocation {
    data class Execute(val cli: Cli, val globals: Globals) : Invocation
    data class ShowHelp(val cli: Cli, val qualifiedName: String) : Invocation
    data class ShowVersion(val cli: Cli) : Invocation
}
