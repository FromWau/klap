package com.fromwau.klap.fixture.head

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of head installed when it was pinned. */
class HeadVersionTest {

    private val parity = ParitySuite(headCli())

    @Test
    fun `the version printed is the installed head's`() = assertEquals(
        """head (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by David MacKenzie and Jim Meyering.
""",
        parity.versionText("--version", because = "head --version: GNU coreutils 9.12"),
    )
}
