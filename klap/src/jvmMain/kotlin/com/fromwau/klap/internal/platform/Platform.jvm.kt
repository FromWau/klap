package com.fromwau.klap.internal.platform

import com.fromwau.klap.Terminal

// Unlike the native actual (isatty(stdout) only), this probe is coupled to BOTH standard streams: a
// redirected/piped stdin can make it report false even when stdout is a real terminal. --color=always/
// never and FORCE_COLOR/CLICOLOR_FORCE override auto detection and are unaffected by this JVM quirk.
internal actual fun defaultTerminal(): Terminal = jvmTerminal(System.console()?.isTerminal == true)
