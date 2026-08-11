package com.fromwau.klap

import com.fromwau.kern.terminal.Terminal

/** Collects written text so tests can assert output without touching real stdio. */
class RecordingTerminal : Terminal {
    val out = StringBuilder()
    val err = StringBuilder()
    override fun out(text: String) { out.append(text) }
    override fun err(text: String) { err.append(text) }
}
