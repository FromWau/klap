package com.fromwau.klap.internal.platform

internal actual fun processWorkingDirectory(): String = System.getProperty("user.dir")

internal actual fun processEnvironment(): Map<String, String> = System.getenv().toMap()
