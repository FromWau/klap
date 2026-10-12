package com.fromwau.klap

/** What an option given a second time on one line does. */
public enum class RepeatedOptions {
    /**
     * The last occurrence binds, as getopt_long tools read one: `cp -S .x -S .y` backs up with `.y`, and
     * `head -n 1 -n 2` prints two lines.
     */
    LastWins,

    /**
     * The second occurrence refuses the line with [CliError.RepeatedOption], as `cp -t d -t e a` and
     * `mv -t d -t e a` refuse theirs: "multiple target directories specified".
     */
    Refuse,
}
