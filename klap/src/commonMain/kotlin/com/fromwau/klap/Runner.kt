package com.fromwau.klap

import kotlinx.serialization.json.Json

/** Parse, dispatch, and render to [terminal]; return the exit code. Never exits the process. */
fun Cli.run(argv: Array<String>, terminal: Terminal): Int {
    val argList = argv.toList()
    val json = argList.hasGlobalJson()
    return when (val outcome = parse(argList)) {
        is Result.Error -> renderError(outcome.error, json, terminal)
        is Result.Success -> when (val invocation = outcome.value) {
            is Invocation.ShowHelp -> {
                terminal.out(invocation.cli.helpText(invocation.qualifiedName) + "\n")
                0
            }
            is Invocation.ShowVersion -> {
                terminal.out("${invocation.cli.name} ${invocation.cli.version}\n")
                0
            }
            is Invocation.Execute -> {
                val action = invocation.cli.action
                if (action == null) {
                    0
                } else when (val outcome = action.block()) {
                    is Result.Success -> {
                        val value = outcome.value
                        if (invocation.globals.json) {
                            terminal.out(Json.encodeToString(action.serializer, value) + "\n")
                        } else {
                            val text = action.human?.invoke(value) ?: value?.toString().orEmpty()
                            if (text.isNotEmpty()) terminal.out(text + "\n")
                        }
                        0
                    }
                    is Result.Error -> renderError(outcome.error, invocation.globals.json, terminal)
                }
            }
        }
    }
}

private fun renderError(error: CliError, json: Boolean, terminal: Terminal): Int {
    if (json) {
        terminal.err(jsonErrorEnvelope(error.message(), error.exitCode) + "\n")
    } else {
        terminal.err("error: ${error.message()}\n")
    }
    return error.exitCode
}

/** Convenience overload using the platform terminal. */
fun Cli.run(argv: Array<String>): Int = run(argv, defaultTerminal())

/** Full drop-in entry point: parse, dispatch, render, and exit with the resulting code. */
fun Cli.main(argv: Array<String>) {
    platformExit(run(argv, defaultTerminal()))
}
