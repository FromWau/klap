package com.fromwau.klap.internal.platform

import com.fromwau.klap.Terminal

/** Exit the process. The only production call that terminates; kept out of run() so run() is testable. */
internal expect fun platformExit(code: Int): Nothing

/** The platform's real stdout/stderr terminal. */
internal expect fun defaultTerminal(): Terminal

/** Detected terminal width in columns (ioctl/Win32), or null when undetectable or not a tty. */
internal expect fun terminalWidth(): Int?

/** Shared color precedence, one source of truth for every platform: `NO_COLOR` > `FORCE_COLOR`/`CLICOLOR_FORCE` > dumb `TERM` > real tty. */
internal fun ansiEnabled(isTty: Boolean, env: (String) -> String?): Boolean = when {
    // Per the NO_COLOR spec (no-color.org), only a present AND non-empty value disables color; an
    // empty value is treated as not-set, so it falls through to the rest of the ladder.
    !env("NO_COLOR").isNullOrEmpty() -> false
    env("FORCE_COLOR").forcesColor() || env("CLICOLOR_FORCE").forcesColor() -> true
    env("TERM") == "dumb" -> false
    else -> isTty
}

/** `FORCE_COLOR` / `CLICOLOR_FORCE` force color when set to anything other than the opt-out value `"0"`. */
private fun String?.forcesColor(): Boolean = this != null && this != "0"

/** Terminal width: `COLUMNS` env override wins, then the [detected] ioctl/Win32 width, then an 80 fallback. */
internal fun resolveColumns(env: (String) -> String?, detected: Int? = null): Int =
    env("COLUMNS")?.toIntOrNull()?.takeIf { it > 0 }
        ?: detected?.takeIf { it > 0 }
        ?: 80
