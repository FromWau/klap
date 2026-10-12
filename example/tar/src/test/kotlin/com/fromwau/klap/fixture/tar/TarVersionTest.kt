package com.fromwau.klap.fixture.tar

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of tar installed when it was pinned. */
class TarVersionTest {

    private val parity = ParitySuite(tarCli())

    @Test
    fun `the version printed is the installed tar's`() = assertEquals(
        """tar (GNU tar) 1.35
Copyright (C) 2023 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by John Gilmore and Jay Fenlason.
""",
        parity.versionText("--version", because = "tar --version: GNU tar 1.35"),
    )
}
