package com.fromwau.klap.internal.platform

/** Exit the process. The only production call that terminates; kept out of run() so run() is testable. */
internal expect fun platformExit(code: Int): Nothing
