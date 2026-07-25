package com.fromwau.klap.fixture.rsync

import com.fromwau.klap.Terminal
import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.run
import kotlin.test.Test
import kotlin.test.assertTrue

/** Collects rendered text so a test can assert on it without touching real stdio. */
private class RecordingTerminal : Terminal {
    val recorded = StringBuilder()
    override fun out(text: String) { recorded.append(text) }
    override fun err(text: String) { recorded.append(text) }
}

class RsyncParityTest {

    private val parity = ParitySuite(rsyncCli())

    @Test
    fun bindsFlagsAndTheOperandList() {
        parity.binds(
            "-avz", "src/", "dst/",
            expected = NOTHING_BOUND.copy(
                archive = true,
                verbose = 1,
                compress = true,
                paths = listOf("src/", "dst/"),
            ),
        )
        parity.binds(
            "--archive", "--recursive", "--compress", "--dry-run", "src/", "dst/",
            expected = NOTHING_BOUND.copy(
                archive = true,
                recursive = true,
                compress = true,
                dryRun = true,
                paths = listOf("src/", "dst/"),
            ),
        )
        parity.binds("-q", "src/", "dst/", expected = NOTHING_BOUND.copy(quiet = true, paths = listOf("src/", "dst/")))
        // Real rsync accepts -q and -v together in either order, and neither clears the other, so
        // there is no lastWins here: both read back set.
        parity.binds(
            "-q", "-v", "src/", "dst/",
            expected = NOTHING_BOUND.copy(quiet = true, verbose = 1, paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "-v", "-q", "src/", "dst/",
            expected = NOTHING_BOUND.copy(quiet = true, verbose = 1, paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--delete", "-r", "src/", "dst/",
            expected = NOTHING_BOUND.copy(delete = true, recursive = true, paths = listOf("src/", "dst/")),
        )
    }

    @Test
    fun countsRepeatedVerboseAndHumanReadable() {
        parity.binds("-v", "src/", "dst/", expected = NOTHING_BOUND.copy(verbose = 1, paths = listOf("src/", "dst/")))
        parity.binds("-vv", "src/", "dst/", expected = NOTHING_BOUND.copy(verbose = 2, paths = listOf("src/", "dst/")))
        parity.binds(
            "-vvv", "src/", "dst/",
            expected = NOTHING_BOUND.copy(verbose = 3, paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "-v", "-v", "-v", "src/", "dst/",
            expected = NOTHING_BOUND.copy(verbose = 3, paths = listOf("src/", "dst/")),
        )
        // Real rsync's -h is human-readable everywhere except when it is the entire command line, and
        // it is cumulative: `rsync -hh --dry-run src/ dst/` runs (verified). Declining klap's
        // `helpShort` built-in is what makes the character reachable at all.
        parity.binds(
            "-h", "src/", "dst/",
            expected = NOTHING_BOUND.copy(humanReadable = 1, paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "-hh", "src/", "dst/",
            expected = NOTHING_BOUND.copy(humanReadable = 2, paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "-vh", "src/", "dst/",
            expected = NOTHING_BOUND.copy(verbose = 1, humanReadable = 1, paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--human-readable", "--human-readable", "src/", "dst/",
            expected = NOTHING_BOUND.copy(humanReadable = 2, paths = listOf("src/", "dst/")),
        )
    }

    @Test
    fun bindsAWholeRemoteShellCommandAsOneValue() {
        // The whole point of -e: the value is one argv token however many words it contains, so
        // nothing here needs quoting rules or a re-join.
        parity.binds(
            "-e", "ssh -p 2222", "src/", "host:/dst",
            expected = NOTHING_BOUND.copy(rsh = "ssh -p 2222", paths = listOf("src/", "host:/dst")),
        )
        parity.binds(
            "--rsh=ssh -p 2222", "src/", "dst/",
            expected = NOTHING_BOUND.copy(rsh = "ssh -p 2222", paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--rsh", "ssh -p 2222", "src/", "dst/",
            expected = NOTHING_BOUND.copy(rsh = "ssh -p 2222", paths = listOf("src/", "dst/")),
        )
        // A value-taking short ends a cluster and takes the rest of the token, or the next one.
        parity.binds(
            "-essh", "src/", "dst/",
            expected = NOTHING_BOUND.copy(rsh = "ssh", paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "-ave", "ssh", "src/", "dst/",
            expected = NOTHING_BOUND.copy(archive = true, verbose = 1, rsh = "ssh", paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "-avessh", "src/", "dst/",
            expected = NOTHING_BOUND.copy(archive = true, verbose = 1, rsh = "ssh", paths = listOf("src/", "dst/")),
        )
        // Real rsync agrees exactly: `-e=ssh` binds the value "=ssh", because a short option's `=`
        // is part of the value rather than a separator (verified — exit 0, no complaint).
        parity.binds(
            "-e=ssh", "src/", "dst/",
            expected = NOTHING_BOUND.copy(rsh = "=ssh", paths = listOf("src/", "dst/")),
        )
    }

    @Test
    fun bindsRepeatableFiltersInOrder() {
        parity.binds(
            "--exclude=a", "--exclude=b", "src/", "dst/",
            expected = NOTHING_BOUND.copy(exclude = listOf("a", "b"), paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--exclude", "a", "--exclude", "b", "src/", "dst/",
            expected = NOTHING_BOUND.copy(exclude = listOf("a", "b"), paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--include=x", "--exclude=*", "src/", "dst/",
            expected = NOTHING_BOUND.copy(include = listOf("x"), exclude = listOf("*"), paths = listOf("src/", "dst/")),
        )
        // An option takes its next token whatever it looks like, which is how a pattern that starts
        // with a dash reaches the filter list. Real rsync binds `-foo` here too (verified).
        parity.binds(
            "--exclude", "-foo", "src/", "dst/",
            expected = NOTHING_BOUND.copy(exclude = listOf("-foo"), paths = listOf("src/", "dst/")),
        )
        // `--opt=` with nothing after it is an explicit empty string, and real rsync accepts it.
        parity.binds(
            "--exclude=", "src/", "dst/",
            expected = NOTHING_BOUND.copy(exclude = listOf(""), paths = listOf("src/", "dst/")),
        )
        parity.binds("src/", "dst/", expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")))
    }

    @Test
    fun togglesThePartialAndProgressPairs() {
        parity.binds("src/", "dst/", expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")))
        parity.binds(
            "--partial", "src/", "dst/",
            expected = NOTHING_BOUND.copy(partial = true, paths = listOf("src/", "dst/")),
        )
        parity.binds("--no-partial", "src/", "dst/", expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")))
        // Real rsync accepts both orders (verified); the later occurrence is the one that stands.
        parity.binds(
            "--partial", "--no-partial", "src/", "dst/",
            expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--no-partial", "--partial", "src/", "dst/",
            expected = NOTHING_BOUND.copy(partial = true, paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--progress", "src/", "dst/",
            expected = NOTHING_BOUND.copy(progress = true, paths = listOf("src/", "dst/")),
        )
        parity.binds("--no-progress", "src/", "dst/", expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")))
    }

    @Test
    fun bindsPortAndBwlimitTheWayRealRsyncParsesThem() {
        parity.binds(
            "--port=1234", "src/", "dst/",
            expected = NOTHING_BOUND.copy(port = 1234, paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--port", "1234", "src/", "dst/",
            expected = NOTHING_BOUND.copy(port = 1234, paths = listOf("src/", "dst/")),
        )
        // Real rsync range-checks neither end: both of these exit 0 (verified), so no .range() here.
        parity.binds(
            "--port=99999", "src/", "dst/",
            expected = NOTHING_BOUND.copy(port = 99999, paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--port=-1", "src/", "dst/",
            expected = NOTHING_BOUND.copy(port = -1, paths = listOf("src/", "dst/")),
        )

        parity.binds(
            "--bwlimit=100", "src/", "dst/",
            expected = NOTHING_BOUND.copy(bwlimit = "100", paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--bwlimit=1.5m", "src/", "dst/",
            expected = NOTHING_BOUND.copy(bwlimit = "1.5m", paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--bwlimit=10K", "src/", "dst/",
            expected = NOTHING_BOUND.copy(bwlimit = "10K", paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--bwlimit=1kb", "src/", "dst/",
            expected = NOTHING_BOUND.copy(bwlimit = "1kb", paths = listOf("src/", "dst/")),
        )
        parity.binds(
            "--bwlimit=0", "src/", "dst/",
            expected = NOTHING_BOUND.copy(bwlimit = "0", paths = listOf("src/", "dst/")),
        )
        parity.binds("src/", "dst/", expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")))
    }

    @Test
    fun bindsTheOperandsRealRsyncAccepts() {
        // Two or more operands: sources plus a destination.
        parity.binds("src/", "dst/", expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")))
        parity.binds("a", "b", "dst/", expected = NOTHING_BOUND.copy(paths = listOf("a", "b", "dst/")))
        // ONE operand is real rsync's local-listing form (`rsync --dry-run src/` exits 0 and lists),
        // which is why the operand minimum here is 1 rather than 2.
        parity.binds("src/", expected = NOTHING_BOUND.copy(paths = listOf("src/")))
        // klap permutes by default, and so does real rsync: `rsync src/ dst/ --dry-run` exits 0.
        parity.binds(
            "src/", "dst/", "--dry-run",
            expected = NOTHING_BOUND.copy(dryRun = true, paths = listOf("src/", "dst/")),
        )
        // `--` is the only way a dash-led path reaches the operand slot.
        parity.binds("--", "-foo", "dst/", expected = NOTHING_BOUND.copy(paths = listOf("-foo", "dst/")))
    }

    @Test
    fun rejectsWhatRealRsyncRejects() {
        parity.rejects(because = "real rsync: no operands prints the usage summary and exits 1")
        parity.rejects("--verbsoe", "src/", "dst/", because = "real rsync: --verbsoe: unknown option")
        parity.rejects("-Q", "src/", "dst/", because = "real rsync: -Q: unknown option")
        parity.rejects("--port=abc", "src/", "dst/", because = "real rsync: --port=abc: invalid numeric value")
        parity.rejects("--bwlimit=bogus", "src/", "dst/", because = "real rsync: --bwlimit=bogus is invalid")
        parity.rejects("--bwlimit=1x", "src/", "dst/", because = "real rsync: --bwlimit=1x is invalid")
        parity.rejects("--bwlimit=+1", "src/", "dst/", because = "real rsync: --bwlimit=+1 is invalid")
        // Real rsync's generic --no-OPTION rule does NOT reach these two: both are "unknown option".
        parity.rejects("--no-delete", "src/", "dst/", because = "real rsync: --no-delete: unknown option")
        parity.rejects("--no-exclude", "src/", "dst/", because = "real rsync: --no-exclude: unknown option")
        // A trailing option with no value cannot borrow a token that is not there.
        parity.rejects("--exclude", because = "real rsync: --exclude with no value has nothing to bind")
    }

    @Test
    fun klapAcceptsWhatRealRsyncRejects() {
        // Real rsync does NOT abbreviate long options — it matches the full spelling or a spelling it
        // declares itself. `--exc=x`, `--dele` and `--com` are all "unknown option" there (each
        // verified), while klap resolves any unambiguous prefix, GNU getopt_long style. This is klap's
        // documented behaviour rather than a bug, and it is the single largest divergence in this file.
        parity.bindsLoosely(
            "--exc=x", "src/", "dst/",
            because = "real rsync: --exc=x: unknown option",
            expected = NOTHING_BOUND.copy(exclude = listOf("x"), paths = listOf("src/", "dst/")),
        )
        parity.bindsLoosely(
            "--dele", "src/", "dst/",
            because = "real rsync: --dele: unknown option",
            expected = NOTHING_BOUND.copy(delete = true, paths = listOf("src/", "dst/")),
        )
        // `--compr`, not `--comp`: see the ambiguity note in knownDivergenceFromRealRsync below.
        parity.bindsLoosely(
            "--compr", "src/", "dst/",
            because = "real rsync: --compr: unknown option",
            expected = NOTHING_BOUND.copy(compress = true, paths = listOf("src/", "dst/")),
        )
        // The negative half abbreviates too.
        parity.bindsLoosely(
            "--no-part", "src/", "dst/",
            because = "real rsync: --no-part: unknown option",
            expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")),
        )

        // klap's own surface claims tokens real rsync has never heard of. rsync's operands are paths, so
        // each of these also shadows a file of that name.
        parity.bindsLoosely(
            "--json", "src/", "dst/",
            because = "real rsync: --json: unknown option",
            expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")),
        )
        parity.bindsLoosely(
            "--color=never", "src/", "dst/",
            because = "real rsync: --color=never: unknown option",
            expected = NOTHING_BOUND.copy(paths = listOf("src/", "dst/")),
        )
        parity.shortCircuits("--help-all", "src/", "dst/", because = "real rsync: --help-all: unknown option")
        parity.shortCircuits("--completion", "bash", "src/", because = "real rsync: --completion: unknown option")
        parity.shortCircuits("--docs", "markdown", "src/", because = "real rsync: --docs: unknown option")
        parity.shortCircuits("__complete", "src/", because = "real rsync: '__complete' is a path, not a node")
    }

    @Test
    fun knownDivergenceFromRealRsync() {
        // GAP 1 — `-h` alone. Real rsync documents "-h is help only on its own": `rsync -h` prints help
        // and exits 0, while `-h` anywhere alongside anything else is --human-readable (both verified).
        // klap resolves a spelling to exactly one input regardless of what else is on the line, so with
        // `helpShort = false` the character is human-readable everywhere and this line is a missing
        // operand instead. There is no declarative form for "this short means the built-in iff argv
        // has length 1".
        parity.rejects("-h", because = "real rsync: -h on its own prints help and exits 0")

        // GAP 2 — `-V`. Real rsync answers `-V` and `--version` alike, anywhere on the line (verified:
        // `rsync -V --dry-run src/ dst/` prints the version and exits 0). klap's `--version` built-in
        // arrives from `version = "3.4.4"` with no way to give it an extra spelling — `builtins { }`
        // only switches a built-in off — and declaring a plain `flag("-V")` would bind a flag nobody
        // reads rather than short-circuit, so the honest record is that `-V` is unreachable here.
        parity.rejects("-V", "src/", "dst/", because = "real rsync: -V prints the version and exits 0")

        // GAP 3 — the negative half of a counted flag. Real rsync's generic --no-OPTION rule covers
        // both of these (verified exit 0). `.count()` and `.negatable()` are mutually exclusive in klap,
        // and counting is the half that carries the tool's actual meaning.
        parity.rejects("--no-verbose", "src/", "dst/", because = "real rsync: --no-verbose is accepted")
        parity.rejects("--no-human-readable", "src/", "dst/", because = "real rsync: --no-human-readable is accepted")

        // GAP 4 — `--no-p`. Real rsync resolves it (to --no-perms) and exits 0; here it reaches both
        // `--no-partial` and `--no-progress`, so klap reports the ambiguity. Neither tool is wrong; they
        // simply disagree about what a prefix may reach.
        parity.rejects("--no-p", "src/", "dst/", because = "real rsync: --no-p resolves to --no-perms, exit 0")

        // Real rsync also rejects this, but for the opposite reason: it does not abbreviate at all,
        // where klap rejects it only because two spellings match. Recorded so the two are not confused.
        parity.rejects("--p", "src/", "dst/", because = "real rsync: --p: unknown option (klap: ambiguous)")

        // GAP 5 — klap's own injected built-ins share the abbreviation namespace with the tool's
        // options, so `--completion` and `--color` between them kill every short abbreviation of
        // `--compress`. `--com` reports
        //     AmbiguousOption(token=--com, candidates=[--compress, --completion])
        // and `--co` adds `--color` to that list. `--compr` is the shortest prefix that resolves.
        // Nothing rsync declares is involved; a user typing `rsync --comp src/ dst/` is refused on
        // account of a completion built-in they never asked for. Declining both built-ins would free
        // the namespace, at the cost of the features.
        parity.rejects("--com", "src/", "dst/", because = "klap: ambiguous against the --completion built-in")
        parity.rejects("--comp", "src/", "dst/", because = "klap: ambiguous against the --completion built-in")
        parity.rejects("--co", "src/", "dst/", because = "klap: ambiguous against --completion and --color")
    }

    @Test
    fun helpRendersEachDeclaredSection() {
        // showsHelp only pins that a line resolves to help, not what the rendered text says, so this
        // drives the real renderer and reads its output back — the point of grouping rsync's sixteen
        // handles into Output/Selection/Transfer/Connection instead of one flat wall of rows.
        val terminal = RecordingTerminal()
        rsyncCli().cli.run(listOf("--help"), terminal)
        val text = terminal.recorded.toString()
        for (heading in listOf("Output:", "Selection:", "Transfer:", "Connection:")) {
            assertTrue(heading in text, "expected `rsync --help` to contain a '$heading' section, in:\n$text")
        }
    }
}
