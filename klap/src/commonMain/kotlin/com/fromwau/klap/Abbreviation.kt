package com.fromwau.klap

/** How far klap resolves a partially typed name. */
public enum class Abbreviation {
    /** Nothing abbreviates: every name must be spelled in full, and a miss suggests the nearest spelling. */
    None,

    /** Long options and their `.choice()`/`.enum<E>()` values abbreviate; subcommand names do not. */
    Options,

    /** Long options, their values, and subcommand names all abbreviate. */
    All,
}
