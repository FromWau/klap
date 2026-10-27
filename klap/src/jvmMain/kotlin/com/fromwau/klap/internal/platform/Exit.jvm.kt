package com.fromwau.klap.internal.platform

import kotlin.system.exitProcess

internal actual fun platformExit(code: Int): Nothing = exitProcess(code)
