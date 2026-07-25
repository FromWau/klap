package com.fromwau.klap.internal.platform

import com.fromwau.klap.Terminal

internal actual fun defaultTerminal(): Terminal = jvmTerminal(System.console() != null)
