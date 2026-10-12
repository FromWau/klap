package com.fromwau.klap.fixture.mkdir

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of mkdir installed when it was pinned. */
class MkdirVersionTest {

    private val parity = ParitySuite(mkdirCli())

    @Test
    fun `the version printed is the installed mkdir's`() = assertEquals(
        """mkdir (GNU coreutils) 9.12
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by David MacKenzie.
""",
        parity.versionText("--version", because = "mkdir --version: GNU coreutils 9.12"),
    )
}
