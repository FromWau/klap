package com.fromwau.klap

/** The sole I/O seam. Production uses the platform default; tests pass a recording fake. */
interface Terminal {
    fun out(text: String)
    fun err(text: String)
}
