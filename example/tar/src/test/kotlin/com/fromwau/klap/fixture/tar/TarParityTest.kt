package com.fromwau.klap.fixture.tar

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class TarParityTest {

    private val parity = ParitySuite(tarCli())

    @Test
    fun `binds bundled clusters and the optional operand list`() {
        parity.binds(
            "-cvf", "backup.tar", "src", "docs",
            expected = NOTHING_BOUND.copy(
                create = true,
                verbose = 1,
                archive = "backup.tar",
                files = listOf("src", "docs"),
            ),
        )
        // The last character of a cluster is the one that takes a value, attached or separate.
        parity.binds(
            "-cvfout.tar", "src",
            expected = NOTHING_BOUND.copy(create = true, verbose = 1, archive = "out.tar", files = listOf("src")),
        )
        parity.binds(
            "-cvvf", "out.tar",
            expected = NOTHING_BOUND.copy(create = true, verbose = 2, archive = "out.tar"),
        )
        parity.binds(
            "-tf", "backup.tar",
            expected = NOTHING_BOUND.copy(listContents = true, archive = "backup.tar"),
        )
        parity.binds(
            "-tzf", "backup.tar.gz",
            expected = NOTHING_BOUND.copy(listContents = true, gzip = true, archive = "backup.tar.gz"),
        )
        parity.binds(
            "-xf", "a.tar", "--exclude", "*.log", "--exclude", "*.tmp",
            expected = NOTHING_BOUND.copy(extract = true, archive = "a.tar", exclude = listOf("*.log", "*.tmp")),
        )
        // A dash-led PATTERN: a required-argument option takes the next token whatever it looks like.
        parity.binds(
            "-cf", "a.tar", "--exclude", "-foo", "src",
            expected = NOTHING_BOUND.copy(create = true, archive = "a.tar", exclude = listOf("-foo"), files = listOf("src")),
        )
        // A lone dash is a value, not an option: tar's own spelling for stdout.
        parity.binds("-cf", "-", "src", expected = NOTHING_BOUND.copy(create = true, archive = "-", files = listOf("src")))
        parity.binds(
            "--create", "--file", "a.tar",
            expected = NOTHING_BOUND.copy(create = true, archive = "a.tar"),
        )
        // `--cr` is an unambiguous abbreviation of `--create`, as it is for real tar.
        parity.binds(
            "--cr", "--file", "a.tar",
            expected = NOTHING_BOUND.copy(create = true, archive = "a.tar"),
        )
    }

    @Test
    fun `rejects what real tar rejects`() {
        parity.rejects("--zzz", because = "real tar: unrecognized option '--zzz'")
        parity.rejects("-f", because = "real tar: option requires an argument -- 'f'")
        parity.rejects("-cf", because = "real tar: option requires an argument -- 'f'")
        parity.rejects("-cQf", "a.tar", because = "real tar: invalid option -- 'Q'")
        parity.rejects("--exclude", because = "real tar: option '--exclude' requires an argument")
    }

    /**
     * The two exclusivity rules, enforced by `requireExactlyOne`/`requireAtMostOne` at parse time. The
     * mode conflict outranks the missing `--file` a later bind would raise, which is GNU tar's order too.
     */
    @Test
    fun `rejects the mode and compression conflicts`() {
        parity.rejects(
            "-c", "-x", "-f", "a.tar",
            because = "real tar: You may not specify more than one '-Acdtrux' option",
        )
        parity.rejects(
            "-f", "a.tar",
            because = "real tar: You must specify one of the '-Acdtrux' options",
        )
        parity.rejects("-c", "-x", because = "the mode conflict outranks the missing --file")
        parity.rejects("-czjf", "a.tar", because = "real tar: Conflicting compression options")
    }

    @Test
    fun `known divergence from real tar`() {
        // Ambiguity is judged against the spellings a tree actually declares, and this fixture declares
        // `--exclude` without real tar's `--exclude-from`, so the same prefix reaches one option here and
        // several there. The rule agrees; the surface it runs over does not.
        parity.bindsLoosely(
            "--excl", "*.log", "-cf", "a.tar",
            because = "real tar: option '--excl' is ambiguous; possibilities: '--exclude' '--exclude-from' ...",
            expected = NOTHING_BOUND.copy(create = true, archive = "a.tar", exclude = listOf("*.log")),
        )
    }
}
