package com.fromwau.klap.fixture.mv

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of mv installed when it was pinned. */
class MvVersionTest {

    private val parity = ParitySuite(mvCli())

    @Test
    fun `the version printed is the installed mv's`() = assertEquals(
        """mv (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Mike Parker, David MacKenzie, and Jim Meyering.
""",
        parity.versionText("--version", because = "mv --version: GNU coreutils 9.12"),
    )
}
