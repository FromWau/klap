package com.fromwau.klap.fixture.curl

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class CurlParityTest {

    private val parity = ParitySuite(curlCli())

    @Test
    fun `binds options and the url list`() {
        parity.binds(
            "https://example.com",
            expected = NOTHING_BOUND.copy(urls = listOf("https://example.com")),
        )
        parity.binds(
            "-X", "POST",
            "-H", "Content-Type: application/json",
            "-d", "{\"a\":1}",
            "https://api.example.com/v1/things",
            expected = NOTHING_BOUND.copy(
                method = "POST",
                headers = listOf("Content-Type: application/json"),
                postData = listOf("{\"a\":1}"),
                urls = listOf("https://api.example.com/v1/things"),
            ),
        )
        parity.binds(
            "-sL", "-o", "page.html", "--max-time", "5", "https://example.com",
            expected = NOTHING_BOUND.copy(
                silent = true,
                location = true,
                output = listOf("page.html"),
                maxTime = 5.0,
                urls = listOf("https://example.com"),
            ),
        )
        // `.choice()` matches case-insensitively and binds the declared spelling; real curl would put
        // the literal lowercase `post` on the wire.
        parity.binds(
            "-X", "post", "https://example.com",
            expected = NOTHING_BOUND.copy(method = "POST", urls = listOf("https://example.com")),
        )
        // curl's --url and a bare operand are the same list to curl, so this line carries no operand.
        parity.binds(
            "--url", "https://a.example.com",
            expected = NOTHING_BOUND.copy(urlOption = listOf("https://a.example.com")),
        )
        parity.binds(
            "--no-silent", "https://example.com",
            expected = NOTHING_BOUND.copy(urls = listOf("https://example.com")),
        )
        parity.binds(
            "-#", "-O", "https://example.com",
            expected = NOTHING_BOUND.copy(progressBar = true, remoteName = true, urls = listOf("https://example.com")),
        )
        parity.binds(
            "--ipv4", "https://example.com",
            expected = NOTHING_BOUND.copy(ipv4 = true, urls = listOf("https://example.com")),
        )
        parity.binds(
            "-4", "https://example.com",
            expected = NOTHING_BOUND.copy(ipv4 = true, urls = listOf("https://example.com")),
        )
        parity.binds(
            "-6", "https://example.com",
            expected = NOTHING_BOUND.copy(ipv6 = true, urls = listOf("https://example.com")),
        )
        // A dash-led parameter value (`-foo=1`), tested in the three spellings an option value can take.
        parity.binds(
            "-d", "-foo=1", "https://example.com",
            expected = NOTHING_BOUND.copy(postData = listOf("-foo=1"), urls = listOf("https://example.com")),
        )
        parity.binds(
            "-d-foo=1", "https://example.com",
            expected = NOTHING_BOUND.copy(postData = listOf("-foo=1"), urls = listOf("https://example.com")),
        )
        parity.binds(
            "--data=-foo=1", "https://example.com",
            expected = NOTHING_BOUND.copy(postData = listOf("-foo=1"), urls = listOf("https://example.com")),
        )
        // Real curl warns here and still takes `-X` as the header text; the binding is the same.
        parity.binds(
            "-H", "-X", "https://example.com",
            expected = NOTHING_BOUND.copy(headers = listOf("-X"), urls = listOf("https://example.com")),
        )
    }

    @Test
    fun `rejects what real curl rejects`() {
        parity.rejects("--zzz", because = "real curl: option --zzz: is unknown")
        parity.rejects("-X", because = "real curl: option -X: requires parameter")
        parity.rejects("-H", because = "real curl: option -H: requires parameter")
        parity.rejects("-o", because = "real curl: option -o: requires parameter")
        parity.rejects("-u", because = "real curl: option -u: requires parameter")
        parity.rejects("--url", because = "real curl: option --url: requires parameter")
        parity.rejects("--max-time", because = "real curl: option --max-time: requires parameter")
        parity.rejects(
            "--max-time", "abc", "https://example.com",
            because = "real curl: option --max-time: expected a proper numerical parameter",
        )
        parity.rejects(
            "--max-time", "-1", "https://example.com",
            because = "real curl: option --max-time: expected a proper numerical parameter",
        )
    }

    @Test
    fun `known divergence from real curl`() {
        // `--version` is always injected and `builtins { }` cannot decline it, so no short can be
        // attached to it; real curl prints its version banner for `-V`.
        parity.rejects("-V", because = "klap gap: --version has no short form, NOT real-curl behaviour")

        // `-X PURGE` is deliberately absent: real curl takes any method token, and the restriction to eight
        // verbs is this fixture's own study brief, not a klap limit.

        // The per-URL pairing gap above `output`: real curl writes URL1 to a.html and URL2 to b.html on
        // the first line and does the same on the second, but only because the ordinals line up. klap
        // keeps two independent lists, so the two lines are indistinguishable to any action.
        parity.binds(
            "-o", "a.html", "https://one.example.com", "-o", "b.html", "https://two.example.com",
            expected = NOTHING_BOUND.copy(
                output = listOf("a.html", "b.html"),
                urls = listOf("https://one.example.com", "https://two.example.com"),
            ),
        )
        parity.binds(
            "-o", "a.html", "-o", "b.html", "https://one.example.com", "https://two.example.com",
            expected = NOTHING_BOUND.copy(
                output = listOf("a.html", "b.html"),
                urls = listOf("https://one.example.com", "https://two.example.com"),
            ),
        )

        // The sectioner gap above `next`: real curl makes two independent requests here, each with its
        // own POST body. klap flattens both sections into one bind and only the count survives.
        parity.binds(
            "-d", "a", "https://one.example.com", "--next", "-d", "b", "https://two.example.com",
            expected = NOTHING_BOUND.copy(
                postData = listOf("a", "b"),
                urls = listOf("https://one.example.com", "https://two.example.com"),
                next = 1,
            ),
        )

        // klap strips its own `--json` before binding, so curl's JSON body lands in the URL list;
        // `builtins { json = false }` would free the name, but this fixture declines nothing.
        parity.binds(
            "--json", "{\"a\":1}", "https://api.example.com",
            expected = NOTHING_BOUND.copy(urls = listOf("{\"a\":1}", "https://api.example.com")),
        )
    }

    @Test
    fun `klap accepts what real curl rejects`() {
        // The cross-input rule the `--url` note describes: "at least one URL across two inputs" cannot be
        // declared, so a URL-less line is a complete parse and only the action objects.
        parity.bindsLoosely(because = "real curl: (2) no URL specified", expected = NOTHING_BOUND)
        parity.bindsLoosely(
            "--json",
            because = "real curl: option --json: requires parameter",
            expected = NOTHING_BOUND,
        )
        parity.bindsLoosely(
            "--color=never", "https://example.com",
            because = "real curl: option --color: is unknown",
            expected = NOTHING_BOUND.copy(urls = listOf("https://example.com")),
        )

        parity.rejects("--loc", "https://example.com", because = "real curl: option --loc: is unknown")
        parity.rejects("--sil", "https://example.com", because = "real curl: option --sil: is unknown")

        // curl's own help takes a category, and `--help` is the one built-in `builtins { }` cannot decline,
        // so klap answers the whole line with its own help and drops the subject.
        parity.shortCircuits("--help", "all", because = "real curl: --help all lists the 'all' category")
        parity.shortCircuits("--help-all", "https://example.com", because = "real curl: option --help-all: is unknown")
        parity.shortCircuits("--docs", "markdown", because = "real curl: option --docs: is unknown")
        parity.shortCircuits("--completion", "bash", because = "real curl: option --completion: is unknown")
        parity.shortCircuits("__complete", "https://ex", because = "real curl: '__complete' is a URL operand")
    }
}
