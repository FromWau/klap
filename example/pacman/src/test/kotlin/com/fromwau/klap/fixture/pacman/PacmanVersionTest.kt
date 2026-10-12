package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of pacman installed when it was pinned. */
class PacmanVersionTest {

    private val parity = ParitySuite(pacmanCli())

    @Test
    fun `the version printed is the installed pacman's`() = assertEquals(
        """
 .--.                  Pacman v7.1.0 - libalpm v16.0.1
/ _.-' .-.  .-.  .-.   Copyright (C) 2006-2025 Pacman Development Team
\  '-. '-'  '-'  '-'   Copyright (C) 2002-2006 Judd Vinet
 '--'
                       This program may be freely redistributed under
                       the terms of the GNU General Public License.

""",
        parity.versionText("--version", because = "pacman --version: pacman 7.1.0"),
    )
}
