package com.fromwau.klap.fixture.find

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of find installed when it was pinned. */
class FindVersionTest {

    private val parity = ParitySuite(findCli())

    @Test
    fun `the version printed is the installed find's`() = assertEquals(
        """find (GNU findutils) 4.11.0-modified
Copyright (C) 2026 Free Software Foundation, Inc.
License GPLv3+: GNU GPL version 3 or later <https://gnu.org/licenses/gpl.html>.
This is free software: you are free to change and redistribute it.
There is NO WARRANTY, to the extent permitted by law.

Written by Eric B. Decker, James Youngman, Bernhard Voelker,
and Kevin Dalley.
Features enabled: D_TYPE O_NOFOLLOW(enabled) LEAF_OPTIMISATION FTS(FTS_CWDFD) CBO(level=1)${" "}
""",
        parity.versionText("--version", because = "find --version: GNU findutils 4.11.0"),
    )
}
