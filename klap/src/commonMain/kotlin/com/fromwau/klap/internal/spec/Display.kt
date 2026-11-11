package com.fromwau.klap.internal.spec

import com.fromwau.klap.Exit
import com.fromwau.klap.internal.render.HelpExample

/**
 * Everything about a command that exists only to render it: its help/doc text and how a parent lists it.
 * Split out of [com.fromwau.klap.Command] so the node itself stays about structure (name, inputs, subcommands, action)
 * and all the presentation lives in one place.
 */
internal class Display(
    val description: String = "",
    val examples: List<HelpExample> = emptyList(),
    val epilogue: String = "",
    val exits: List<Exit> = emptyList(),
    val section: String? = null,
    val hidden: Boolean = false,
)

/** The meaning as help and the man page show it, a success other than 0 marked so it does not read as a failure. */
internal val Exit.shown: String get() = if (success && code != 0) "$meaning (not a failure)" else meaning
