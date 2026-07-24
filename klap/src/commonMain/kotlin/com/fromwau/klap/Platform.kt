package com.fromwau.klap

/** Exit the process. The only production call that terminates; kept out of run() so run() is testable. */
internal expect fun platformExit(code: Int): Nothing

/** The platform's real stdout/stderr terminal. */
internal expect fun defaultTerminal(): Terminal
