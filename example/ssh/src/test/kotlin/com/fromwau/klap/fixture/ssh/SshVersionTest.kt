package com.fromwau.klap.fixture.ssh

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of ssh installed when it was pinned. */
class SshVersionTest {

    private val parity = ParitySuite(sshCli())

    @Test
    fun `the version printed is the installed ssh's`() = assertEquals(
        """OpenSSH_10.6p1, OpenSSL 3.6.5 29 Sep 2026
""",
        parity.versionText("-V", because = "ssh -V: OpenSSH 10.6p1"),
    )
}
