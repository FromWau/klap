package com.fromwau.klap

/** How far klap resolves a partially typed name. */
public enum class Abbreviation {
    /** Nothing abbreviates: every name must be spelled in full, and a miss suggests the nearest spelling. */
    None,

    /**
     * Long option names abbreviate, and nothing else: pacman takes `--col` for `--color` but refuses
     * `--color=al`.
     */
    LongOptions,

    /**
     * Long options and their `.choice()`/`.enum<E>()` values abbreviate (GNU `ls --colo=al`); subcommand names do
     * not.
     */
    Options,

    /** Long options, their values, and subcommand names all abbreviate. */
    All,
}

/** Whether a declared choice value resolves by prefix under this mode. */
internal val Abbreviation.infersValues: Boolean get() = this == Abbreviation.Options || this == Abbreviation.All
