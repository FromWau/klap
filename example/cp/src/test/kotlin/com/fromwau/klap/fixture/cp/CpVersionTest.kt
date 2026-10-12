package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of cp installed when it was pinned. */
class CpVersionTest {

    private val parity = ParitySuite(cpCli())

    @Test
    fun `the version printed is the installed cp's`() = assertEquals(
        """cp (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Torbjörn Granlund, David MacKenzie, and Jim Meyering.
""",
        parity.versionText("--version", because = "cp --version: GNU coreutils 9.12"),
    )
}
