package com.fromwau.klap.internal.spec

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
    val section: String? = null,
    val hidden: Boolean = false,
)