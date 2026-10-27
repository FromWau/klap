package com.fromwau.klap.fixture.git

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of git installed when it was pinned. */
class GitVersionTest {

    private val parity = ParitySuite(gitCli())

    @Test
    fun `the version printed is the installed git's`() = assertEquals(
        """git version 2.56.0
""",
        parity.versionText("--version", because = "git --version: git 2.56.0"),
    )
}
