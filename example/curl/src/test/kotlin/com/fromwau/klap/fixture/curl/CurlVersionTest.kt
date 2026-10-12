package com.fromwau.klap.fixture.curl

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

/** The fixture prints, word for word, the version of curl installed when it was pinned. */
class CurlVersionTest {

    private val parity = ParitySuite(curlCli())

    @Test
    fun `the version printed is the installed curl's`() = assertEquals(
        """curl 8.22.0 (x86_64-pc-linux-gnu) libcurl/8.22.0 OpenSSL/3.6.5 zlib/1.3.2 brotli/1.2.0 zstd/1.5.7 libidn2/2.3.8 libpsl/0.21.5 libssh2/1.11.1 nghttp2/1.70.0 ngtcp2/1.25.0 nghttp3/1.18.0 mit-krb5/1.22.2
Release-Date: 2026-09-02
Protocols: dict file ftp ftps gopher gophers http https imap imaps ipfs ipns mqtt mqtts pop3 pop3s rtsp scp sftp smtp smtps telnet tftp ws wss
Features: alt-svc AsynchDNS brotli GSS-API HSTS HTTP2 HTTP3 HTTPS-proxy IDN IPv6 Kerberos Largefile libz PSL SPNEGO SSL threadsafe UnixSockets zstd
""",
        parity.versionText("--version", because = "curl --version: curl 8.22.0"),
    )

    @Test
    fun `-V prints the version as --version does`() = assertEquals(
        parity.versionText("--version", because = "curl --version: curl 8.22.0"),
        parity.versionText("-V", because = "curl -V: curl 8.22.0"),
    )
}
