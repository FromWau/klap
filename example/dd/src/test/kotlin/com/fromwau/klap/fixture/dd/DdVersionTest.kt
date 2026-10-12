package com.fromwau.klap.fixture.dd

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of dd installed when it was pinned. */
class DdVersionTest {

    private val parity = ParitySuite(ddCli())

    @Test
    fun `the version printed is the installed dd's`() = assertEquals(
        """dd (coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Paul Rubin, David MacKenzie, and Stuart Kemp.
""",
        parity.versionText("--version", because = "dd --version: GNU coreutils 9.12"),
    )
}
