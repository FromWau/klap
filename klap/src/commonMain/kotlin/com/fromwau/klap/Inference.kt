package com.fromwau.klap

/**
 * How far klap resolves a partially typed name.
 *
 * Abbreviating a long option is not a POSIX question — guideline 3 makes an option name one character, so
 * `--`-led names lie outside the guidelines entirely and no mode here is more or less conformant than
 * another. What a mode trades is forward compatibility: `--j` works until the day a `--jobs` is added.
 */
public enum class Inference {
    /** Nothing infers. A long option must be spelled in full; a miss still suggests. */
    None,

    /**
     * Long options and their `.choice()` / `.enum<E>()` values infer, the way GNU's `getopt_long` and
     * `argmatch` do. Subcommands do not. This is the shape of every GNU tool, and of `git`.
     */
    Options,

    /** Long options and subcommand names both infer, the way `ip` resolves `ip a`. */
    All,
}
