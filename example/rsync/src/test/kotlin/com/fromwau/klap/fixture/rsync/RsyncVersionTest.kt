package com.fromwau.klap.fixture.rsync

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of rsync installed when it was pinned. */
class RsyncVersionTest {

    private val parity = ParitySuite(rsyncCli())

    @Test
    fun `the version printed is the installed rsync's`() = assertEquals(
        """rsync  version 3.5.1-g04355d27  protocol version 33
Copyright (C) 1996-2026 by Andrew Tridgell, Wayne Davison, and others.
Web site: https://rsync.samba.org/
Capabilities:
    64-bit files, 64-bit inums, 64-bit timestamps, 64-bit long ints,
    socketpairs, symlinks, symtimes, hardlinks, hardlink-specials,
    hardlink-symlinks, IPv6, atimes, batchfiles, inplace, append, ACLs,
    ACL-at, ACL-libacl-at, xattrs, optional secluded-args, iconv, prealloc,
    stop-at, no crtimes, IDN
Optimizations:
    SIMD-roll, no asm-roll, openssl-crypto, no asm-MD5
Checksum list:
    xxh128 xxh3 xxh64 (xxhash) md5 md4 sha1 none
Compress list:
    zstd lz4 zlibx zlib none
Daemon auth list:
    sha512 sha256 sha1 md5 md4

rsync comes with ABSOLUTELY NO WARRANTY.  This is free software, and you
are welcome to redistribute it under certain conditions.  See the GNU
General Public Licence for details.
""",
        parity.versionText("--version", because = "rsync --version: rsync 3.5.1"),
    )

    @Test
    fun `-V prints the version as --version does wherever it stands`() {
        val version = parity.versionText("--version", because = "rsync --version: rsync 3.5.1")
        assertEquals(version, parity.versionText("-V", because = "rsync -V: rsync 3.5.1"))
        assertEquals(
            version,
            parity.versionText("-V", "--dry-run", "src/", "dst/", because = "rsync -V --dry-run src/ dst/"),
        )
    }
}
