package com.fromwau.klap.fixture.curl

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.CliError
import com.fromwau.klap.CountFlag
import com.fromwau.klap.Flag
import com.fromwau.klap.Opt
import com.fromwau.klap.TypedCli
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.io.CliPath
import com.fromwau.klap.io.path
import com.fromwau.klap.projection

/**
 * A dry reproduction of curl(1)'s command-line surface (measured against curl 8.23.0):
 *
 *     curl [-X METHOD] [-H HEADER]... [-d DATA] [-o FILE] [-L] [-s] [--max-time SECONDS] URL...
 *
 * Nothing here transfers anything; the action is a stub. The parsing surface is the point.
 *
 * Every handle declared inside a `group { }` is hoisted as a pre-declared `val` with its converted
 * type written out, then assigned inside the block — legal because `group(title) { }` is generic and
 * its contract guarantees exactly one call (CommandBuilder.kt). That is the whole block of
 * declarations below — the price of using help sections on a flat tool this wide.
 */
public fun curlCli(): TypedCli<CurlInputs> = cliOf("curl") {
    // curl matches long options exactly: `--loc` and `--sil` really do answer "option --loc: is unknown".
    abbreviation = Abbreviation.None
    help = "Transfer a URL"
    version = Version.Of("8.23.0", long = "version", short = 'V', text = CURL_VERSION_TEXT)
    epilogue = "This is a parsing-surface study: no request is ever sent."

    example("curl https://example.com", "GET a URL to stdout")
    example("curl -X POST -H 'Content-Type: application/json' -d '{\"a\":1}' https://api.example.com/v1/things")
    example("curl -sL -o page.html --max-time 5 https://example.com", "quiet, follow redirects, 5s cap")

    val method: Opt<String?>
    val headers: Opt<List<String>>
    val postData: Opt<List<String>>
    val user: Opt<String?>
    val urlOption: Opt<List<String>>
    group("Request") {
        // Any word, as curl sends it verbatim (`-X post`, `-X PURGE`); Tab offers the common methods only.
        method = option(short = 'X', long = "request", help = "Specify request method to use")
            .completeWith { candidates(listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")) }

        // The clean case: repeatable option -> List<String>, in occurrence order.
        headers = option(short = 'H', long = "header", help = "Pass custom header(s) to server").multiple()

        // curl's `-d` is repeatable too (occurrences are joined with '&'), so `.multiple()` is the
        // faithful shape rather than the single-valued `[-d DATA]` of the sketch.
        postData = option(short = 'd', long = "data", help = "Post data").multiple()

        user = option(short = 'u', long = "user", help = "Server user and password")

        // KLAP-GAP: curl requires at least one URL across `--url` and the bare positional combined; klap
        // has no cross-input arity check, so the requirement is enforced by hand in `action { }` instead.
        urlOption = option(long = "url", help = "URL(s) to work with").multiple()
    }

    val output: Opt<List<CliPath>>
    val remoteName: Flag
    val silent: Flag
    val progressBar: Flag
    group("Output") {
        // KLAP-GAP: curl pairs each `-o` (and every other per-URL option) with the URL at the same
        // ordinal; klap's `sift` keeps options and positionals as two independent lists, losing that pairing.
        output = option(short = 'o', long = "output", help = "Write to file instead of stdout").path().multiple()

        // Same per-URL problem, one step worse: `-O` is a boolean, so klap collapses N occurrences
        // to a single Boolean (Parser.kt) and the per-URL pairing is gone entirely. `.count()`
        // would at least preserve "how many", but still not "which URL each one belonged to".
        remoteName = flag(short = 'O', long = "remote-name", help = "Write output to file named as remote file")

        // curl accepts `--no-silent` for every boolean option, which `.negatable()` mirrors exactly
        // (long-form negation only, matching curl). Renders as `--[no-]silent (default: off)`.
        silent = flag(short = 's', long = "silent", help = "Silent mode").negatable(default = false)

        // A short may be any visible character, `#` and `:` included.
        progressBar = flag(short = '#', long = "progress-bar", help = "Display transfer progress as a bar")
    }

    val location: Flag
    val maxTime: Opt<Double?>
    val ipv4: Flag
    val ipv6: Flag
    val next: CountFlag
    group("Connection") {
        location = flag(short = 'L', long = "location", help = "Follow redirects").negatable(default = false)

        // curl's --max-time takes fractional seconds, so .double() rather than .int(); the range
        // both validates and prints `(0.0..86400.0)` into the help row.
        maxTime = option(short = 'm', long = "max-time", help = "Maximum time allowed for transfer")
            .double()
            .range(0.0..86_400.0)

        ipv4 = flag(short = '4', long = "ipv4", help = "Resolve names to IPv4 addresses")
        ipv6 = flag(short = '6', long = "ipv6", help = "Resolve names to IPv6 addresses")

        // KLAP-GAP: curl's `-:, --next` restarts the option set mid-line into a new request; klap's parse
        // always produces one flat bind, so `.count()` recovers only how many sections, not their contents.
        next = flag(short = ':', long = "next", help = "Make next URL use separate options").count()
    }

    // Positionals are never sectioned: BuilderImpl.argument() ignores currentSection
    // (BuilderImpl.kt), so putting this inside a `group { }` would be a silent no-op. Declared
    // out here as a plain `val`, which is also why it needs no `lateinit var` hoist.
    //
    // min = 0, so `curl --url https://a` can carry no operand at all; the "at least one URL from
    // somewhere" rule is enforced in the action instead. See the `--url` note above.
    val urls = argument("url", "URL(s) to fetch").multiple()

    // KLAP-GAP: curl's `-h, --help <subject>` takes a category and klap's `--help` takes none, so `curl --help http`
    // prints the whole help here, not the http category. `Help.Off` would free both names, but an option of the
    // fixture's own under them prints no help.

    action<String>(human = { it }) {
        val effectiveUrls = urls() + urlOption()
        if (effectiveUrls.isEmpty()) return@action Err(CliError.MissingArgument("curl", "url"))
        val verb = method() ?: if (postData().isEmpty()) "GET" else "POST"
        val sink = output().firstOrNull()?.text ?: if (remoteName()) "<remote-name>" else "stdout"
        val notes = buildList {
            if (location()) add("following redirects")
            if (silent()) add("silent")
            if (progressBar()) add("progress bar")
            if (ipv4()) add("ipv4 only")
            if (ipv6()) add("ipv6 only")
            if (next() > 0) add("${next()} extra section(s) ignored")
            user()?.let { add("as $it") }
            maxTime()?.let { add("cap ${it}s") }
            if (headers().isNotEmpty()) add("${headers().size} header(s)")
        }
        Ok("would $verb ${effectiveUrls.joinToString(", ")} -> $sink ${notes.joinToString("; ")}")
    }

    projection {
        CurlInputs(
            method(),
            headers(),
            postData(),
            user(),
            urlOption(),
            output(),
            remoteName(),
            silent(),
            progressBar(),
            location(),
            maxTime(),
            ipv4(),
            ipv6(),
            next(),
            urls(),
        )
    }
}

/**
 * What one `curl` line binds, as values rather than as the handles that read them.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does
 * not name is pinned to its default too.
 */
public data class CurlInputs(
    val method: String?,
    val headers: List<String>,
    val postData: List<String>,
    val user: String?,
    val urlOption: List<String>,
    val output: List<CliPath>,
    val remoteName: Boolean,
    val silent: Boolean,
    val progressBar: Boolean,
    val location: Boolean,
    val maxTime: Double?,
    val ipv4: Boolean,
    val ipv6: Boolean,
    val next: Int,
    val urls: List<String>,
)

/** `curl` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_BOUND: CurlInputs = CurlInputs(
    method = null,
    headers = emptyList(),
    postData = emptyList(),
    user = null,
    urlOption = emptyList(),
    output = emptyList(),
    remoteName = false,
    silent = false,
    progressBar = false,
    location = false,
    maxTime = null,
    ipv4 = false,
    ipv6 = false,
    next = 0,
    urls = emptyList(),
)

/**
 * What `curl --version` prints for curl 8.23.0, the version installed when it was pinned
 * (2026-10-17).
 */
internal val CURL_VERSION_TEXT: String = """curl 8.23.0 (x86_64-pc-linux-gnu) libcurl/8.23.0 OpenSSL/3.6.5 zlib/1.3.2 brotli/1.2.0 zstd/1.5.7 libidn2/2.3.8 libpsl/0.21.5 libssh2/1.11.1 nghttp2/1.70.0 ngtcp2/1.25.0 nghttp3/1.18.0 mit-krb5/1.22.2
Release-Date: 2026-10-14
Protocols: dict file ftp ftps gopher gophers http https imap imaps ipfs ipns mqtt mqtts pop3 pop3s rtsp scp sftp smtp smtps telnet tftp ws wss
Features: alt-svc AsynchDNS brotli GSS-API HSTS HTTP2 HTTP3 HTTPS-proxy IDN IPv6 Kerberos Largefile libz PSL SPNEGO SSL threadsafe UnixSockets zstd"""
