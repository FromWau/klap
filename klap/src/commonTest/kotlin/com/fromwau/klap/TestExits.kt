package com.fromwau.klap

/** An [Exit] of any [code], out of range included, for tests that check what klap does with the number. */
internal fun exitOf(code: Int): Exit = object : Exit {
    override val code = code
    override val meaning = "exit $code"
}
