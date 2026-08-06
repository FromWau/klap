package com.fromwau.klap.fixture.curl

import com.fromwau.klap.CliError
import com.fromwau.klap.CountFlag
import com.fromwau.klap.Err
import com.fromwau.klap.Flag
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Ok
import com.fromwau.klap.Opt
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * A dry reproduction of curl(1)'s command-line surface (measured against curl 8.21.0):
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
    description = "Transfer a URL"
    version = "8.21.0"
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
        // KLAP-GAP: real curl's `-X` takes an arbitrary method token; declaring it via `.choice()` gains
        // validation but rejects custom verbs and matches case-insensitively (`-X post` binds `POST`).
        method = option("--request", "-X", help = "Specify request method to use")
            .choice("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE")

        // The clean case: repeatable option -> List<String>, in occurrence order.
        headers = option("--header", "-H", help = "Pass custom header(s) to server").multiple()

        // curl's `-d` is repeatable too (occurrences are joined with '&'), so `.multiple()` is the
        // faithful shape rather than the single-valued `[-d DATA]` of the sketch.
        postData = option("--data", "-d", help = "HTTP POST data").multiple()

        user = option("--user", "-u", help = "Server user and password")

        // KLAP-GAP: curl requires at least one URL across `--url` and the bare positional combined; klap
        // has no cross-input arity check, so the requirement is enforced by hand in `action { }` instead.
        urlOption = option("--url", help = "URL(s) to work with").multiple()
    }

    val output: Opt<List<String>>
    val remoteName: Flag
    val silent: Flag
    val progressBar: Flag
    group("Output") {
        // KLAP-GAP: curl pairs each `-o` (and every other per-URL option) with the URL at the same
        // ordinal; klap's `sift` keeps options and positionals as two independent lists, losing that pairing.
        output = option("--output", "-o", help = "Write to file instead of stdout").file().multiple()

        // Same per-URL problem, one step worse: `-O` is a boolean, so klap collapses N occurrences
        // to a single Boolean (Parser.kt) and the per-URL pairing is gone entirely. `.count()`
        // would at least preserve "how many", but still not "which URL each one belonged to".
        remoteName = flag("--remote-name", "-O", help = "Write output to file named as remote file")

        // curl accepts `--no-silent` for every boolean option, which `.negatable()` mirrors exactly
        // (long-form negation only, matching curl). Renders as `--[no-]silent (default: off)`.
        silent = flag("--silent", "-s", help = "Silent mode").negatable(default = false)

        // A non-alphanumeric short works: validateShortNames only rejects a digit, '.', or '-'
        // (BuilderValidation.kt), and sift's short-cluster walk matches on the raw char.
        progressBar = flag("--progress-bar", "-#", help = "Display transfer progress as a bar")
    }

    val location: Flag
    val maxTime: Opt<Double?>
    val ipv4: Flag
    val ipv6: Flag
    val next: CountFlag
    group("Connection") {
        location = flag("--location", "-L", help = "Follow redirects").negatable(default = false)

        // curl's --max-time takes fractional seconds, so .double() rather than .int(); the range
        // both validates and prints `(0.0..86400.0)` into the help row.
        maxTime = option("--max-time", "-m", help = "Maximum time allowed for transfer")
            .double()
            .range(0.0..86_400.0)

        ipv4 = flag("--ipv4", "-4", help = "Resolve names to IPv4 addresses")
        ipv6 = flag("--ipv6", "-6", help = "Resolve names to IPv6 addresses")

        // KLAP-GAP: curl's `-:, --next` restarts the option set mid-line into a new request; klap's parse
        // always produces one flat bind, so `.count()` recovers only how many sections, not their contents.
        next = flag("--next", "-:", help = "Make next URL use separate options").count()
    }

    // Positionals are never sectioned: BuilderImpl.argument() ignores currentSection
    // (BuilderImpl.kt), so putting this inside a `group { }` would be a silent no-op. Declared
    // out here as a plain `val`, which is also why it needs no `lateinit var` hoist.
    //
    // min = 0, so `curl --url https://a` can carry no operand at all; the "at least one URL from
    // somewhere" rule is enforced in the action instead. See the `--url` note above.
    val urls = argument("url", "URL(s) to fetch").multiple()

    // KLAP-GAP: `-h, --help <subject>` (curl's help takes a value) and `-V, --version` cannot be
    // declared with those spellings — klap's help/version built-ins are reserved unconditionally.

    action<String>(human = { it }) {
        val effectiveUrls = urls() + urlOption()
        if (effectiveUrls.isEmpty()) return@action Err(CliError.MissingArgument("curl", "url"))
        val verb = method() ?: if (postData().isEmpty()) "GET" else "POST"
        val sink = output().firstOrNull() ?: if (remoteName()) "<remote-name>" else "stdout"
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
    val output: List<String>,
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
