package com.fromwau.klap

import kotlin.system.exitProcess

internal actual fun platformExit(code: Int): Nothing = exitProcess(code)

internal actual fun defaultTerminal(): Terminal = object : Terminal {
    override fun out(text: String) = print(text)
    override fun err(text: String) = System.err.print(text)
}
