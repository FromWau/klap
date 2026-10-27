package com.fromwau.klap.fixture.git

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.io.CliPath
import kotlin.test.Test

class GitParityTest {

    private val parity = ParitySuite(gitCli())

    @Test
    fun `binds two level routing and the root options a command reads`() {
        parity.binds(
            "-C", "/tmp/repo", "status", "--short",
            expected = NOTHING_STATUS.copy(
                globals = NO_GLOBALS.copy(directory = listOf(CliPath("/tmp/repo"))),
                short = true,
            ),
        )
        parity.binds("status", "-sb", expected = NOTHING_STATUS.copy(short = true, branch = true))
        // Root options read two levels down, and the `=` spelling of one.
        parity.binds(
            "--git-dir=/tmp/g", "--bare", "-c", "user.name=x", "remote", "add", "o", "u",
            expected = NOTHING_REMOTE_ADD.copy(
                globals = NO_GLOBALS.copy(gitDir = CliPath("/tmp/g"), bare = true, config = listOf("user.name=x")),
                name = "o",
                url = "u",
            ),
        )
        // The attached form of the optional-value root option genuinely matches real git: it binds its own
        // value and routes to `status` normally (verified against 2.56.0).
        parity.binds(
            "--exec-path=/opt/gitcore", "status",
            expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(execPath = CliPath("/opt/gitcore"))),
        )
    }

    @Test
    fun `binds the paginate pair before the subcommand`() {
        // `.negatable()`'s explicit-spelling overload reproduces git's pager pair: `-p`, `--paginate` and both of the
        // negative half's spellings, `-P` and `--no-pager`, all bind.
        parity.binds("status", expected = NOTHING_STATUS)
        parity.binds("-p", "status", expected = NOTHING_STATUS)
        parity.binds("--paginate", "status", expected = NOTHING_STATUS)
        parity.binds(
            "--no-pager", "status",
            expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(paginate = false)),
        )
        parity.binds("-P", "status", expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(paginate = false)))
    }

    @Test
    fun `binds add and its alias`() {
        // Case matters inside a cluster: `-n` is --dry-run, `-N` is --intent-to-add. git gives the two
        // near-opposite meanings, so a cluster that confuses them stages files a dry run would not have.
        parity.binds(
            "add", "-Anv", "src",
            expected = NOTHING_ADD.copy(addAll = true, dryRun = true, verbose = true, pathspec = listOf("src")),
        )
        parity.binds(
            "add", "-AN", "src",
            expected = NOTHING_ADD.copy(addAll = true, intentToAdd = true, pathspec = listOf("src")),
        )
        // `git stage` is git's own synonym, which klap expresses as a command alias.
        parity.binds("stage", "a.txt", expected = NOTHING_ADD.copy(pathspec = listOf("a.txt")))
        // Real `git add` with zero operands exits 0 with "Nothing specified, nothing added."
        parity.binds("add", expected = NOTHING_ADD)
        parity.binds(
            "add", "--chmod=+x", "s.sh",
            expected = NOTHING_ADD.copy(chmod = ExecutableBit.Set, pathspec = listOf("s.sh")),
        )
        // Both spellings of `--chmod`'s value bind: real git's parse-options hands a required-value option
        // the next token regardless of its shape, so the space-separated `-x` is read as the value here too.
        parity.binds(
            "add", "--chmod", "-x", "f",
            expected = NOTHING_ADD.copy(chmod = ExecutableBit.Clear, pathspec = listOf("f")),
        )
    }

    @Test
    fun `binds commit messages and negations`() {
        parity.binds(
            "commit", "-m", "subject", "-m", "body", "--amend",
            expected = NOTHING_COMMIT.copy(message = listOf("subject", "body"), amend = true),
        )
        parity.binds(
            "commit", "-m", "x", "a.txt", "b.txt",
            expected = NOTHING_COMMIT.copy(message = listOf("x"), pathspec = listOf("a.txt", "b.txt")),
        )
        // .negatable() reproduces git's `--verify` / `--no-verify` pair exactly, default on.
        parity.binds("commit", "-m", "x", expected = NOTHING_COMMIT.copy(message = listOf("x")))
        parity.binds(
            "commit", "--no-verify", "--no-edit", "-m", "x",
            expected = NOTHING_COMMIT.copy(message = listOf("x"), verify = false, edit = false),
        )
        // git's real spelling is `-n, --no-verify` plus a separate `--verify`: the short belongs to
        // the NEGATIVE form, which `.negatable()`'s explicit-spelling overload now reaches directly.
        parity.binds(
            "commit", "-n", "-m", "x",
            expected = NOTHING_COMMIT.copy(message = listOf("x"), verify = false),
        )
        // The optional-value `-S[<keyid>]`, in the bare form that is the common one; -m x is untouched.
        parity.binds(
            "commit", "-S", "-m", "x",
            expected = NOTHING_COMMIT.copy(message = listOf("x"), gpgSign = "default"),
        )
        // The attached short form genuinely matches real git too: `-Sabc` signs with key "abc",
        // matching real git's own "stuck to the option without a space" rule (verified against 2.56.0).
        parity.binds(
            "commit", "-Sabc", "-m", "x",
            expected = NOTHING_COMMIT.copy(message = listOf("x"), gpgSign = "abc"),
        )
        parity.binds(
            "commit", "-F", "msg.txt", "--author=A U Thor",
            expected = NOTHING_COMMIT.copy(messageFile = CliPath("msg.txt"), author = "A U Thor"),
        )
        // `Abbreviation.Options` reaches a subcommand's long options, which is the shape real git itself has:
        // `git commit --am` resolves to `--amend` (verified against 2.56.0), and `--am` is unambiguous
        // against every other option this command declares.
        parity.binds(
            "commit", "--am", "-m", "x",
            expected = NOTHING_COMMIT.copy(message = listOf("x"), amend = true),
        )
    }

    @Test
    fun `binds logs two operand groups`() {
        parity.binds(
            "log", "-n", "5", "--oneline", "main..HEAD",
            expected = NOTHING_LOG.copy(maxCount = 5, oneline = true, revisionRange = "main..HEAD"),
        )
        parity.binds("log", expected = NOTHING_LOG)
        parity.binds(
            "log", "main..HEAD", "--", "src/",
            expected = NOTHING_LOG.copy(revisionRange = "main..HEAD", paths = listOf("src/")),
        )
        parity.binds(
            "log", "--no-decorate", "--graph",
            expected = NOTHING_LOG.copy(decorate = false, graph = true),
        )
    }

    @Test
    fun `binds remote as a hybrid parent`() {
        // `git remote` bare runs the parent's own action; `git remote add ...` routes past it.
        parity.binds("remote", expected = NOTHING_REMOTE)
        parity.binds("remote", "-v", expected = NOTHING_REMOTE.copy(verbose = true))
        parity.binds(
            "remote", "add", "-f", "--mirror=push", "-t", "main", "origin", "https://x/y.git",
            expected = NOTHING_REMOTE_ADD.copy(
                fetch = true,
                mirror = Mirror.Push,
                track = listOf("main"),
                name = "origin",
                url = "https://x/y.git",
            ),
        )
        parity.binds(
            "remote", "add", "--no-tags", "o", "u",
            expected = NOTHING_REMOTE_ADD.copy(tags = false, name = "o", url = "u"),
        )
        // `rm` is git's own alias for `git remote remove`.
        parity.binds("remote", "rm", "origin", expected = NOTHING_REMOTE_REMOVE.copy(name = "origin"))
    }

    @Test
    fun `rejects what real git rejects`() {
        parity.rejects("--zzz", because = "real git: unknown option: --zzz")
        // `git help -a` lists the commands; there is no --help-all, and klap's default Help.ByCommand offers none.
        parity.rejects("--help-all", because = "real git: unknown option: --help-all")
        parity.rejects("zzz", because = "real git: 'zzz' is not a git command")
        // klap's completion wire is off with its completion built-in.
        parity.rejects("__complete", "sta", because = "real git: '__complete' is not a git command")
        parity.rejects("-C", because = "real git: no directory given for '-C' option")
        parity.rejects("commit", "-m", because = "real git: switch 'm' requires a value")
        parity.rejects(
            "commit", "--cleanup=bogus", "-m", "x",
            because = "real git: --cleanup takes strip|whitespace|verbatim|scissors|default",
        )
        parity.rejects("add", "--chmod=zzz", "f", because = "real git: --chmod takes (+|-)x")
        parity.rejects("remote", "add", "origin", because = "real git: git remote add takes <name> <url>")
        parity.rejects("remote", "zzz", "origin", because = "real git: zzz is not a git remote subcommand")
    }

    @Test
    fun `known divergence from real git`() {
        // klap cannot combine `.negatable()` with `.optionalValue()` on one holder, so `--decorate` stayed
        // negatable (for `--no-decorate`) rather than gaining the `=WHEN` spellings; see the KLAP-GAP note
        // beside its declaration.
        parity.rejects(
            "log", "--decorate=full",
            because = "klap gap: --decorate stays negatable for --no-decorate, NOT real-git behaviour",
        )

        // `-S`/`--gpg-sign` took the opposite side of the same trade: it kept the value form and lost
        // negation, so `--no-gpg-sign` (verified accepted by real git 2.56.0, countermanding commit.gpgSign)
        // is unreachable here.
        parity.rejects(
            "commit", "--no-gpg-sign", "-m", "x",
            because = "klap gap: --gpg-sign took the value form for -S<keyid>, NOT real-git behaviour",
        )

        // The fixture's own shortfall, not klap's: klap can carry both spellings on one input, but this
        // tree only declares the primary, so real git's `--after` synonym for `--since` is unreached here.
        parity.rejects("log", "--after=2.weeks", because = "the fixture declares one spelling; real git takes both")

        // `-5` is git's shorthand for `-n 5`; the number input binds it like any other real invocation.
        parity.binds("log", "-5", expected = NOTHING_LOG.copy(maxCount = 5))
        // The two spellings are one quantity, so the last one written is the one that holds.
        parity.binds("log", "-n", "3", "-5", expected = NOTHING_LOG.copy(maxCount = 5))
        parity.binds("log", "-5", "-n", "3", expected = NOTHING_LOG.copy(maxCount = 3))
        // Two runs are two occurrences of ONE input, so the ordinary last-occurrence rule settles them.
        parity.binds("log", "-2", "-1", expected = NOTHING_LOG.copy(maxCount = 1))
        // The `--` separator between git's two operand groups is not expressible: `--` only ends option
        // parsing, and its tokens join the same flat positional list. Real git means "paths only" here.
        parity.binds("log", "--", "src/", expected = NOTHING_LOG.copy(revisionRange = "src/"))
        // A command may hold at most one variadic and it must be last, so git's multi-rev form cannot be
        // absorbed; real git shows commits from both refs, where klap binds one as the range, one as a path.
        parity.binds(
            "log", "main", "dev",
            expected = NOTHING_LOG.copy(revisionRange = "main", paths = listOf("dev")),
        )
    }

    @Test
    fun `the version is the root's and help answers at every level`() {
        // `git -v` and `git -v status` print `git version 2.56.0`; `git status --version` is "unknown option".
        parity.showsVersion("-v", because = "real git: git version 2.56.0")
        parity.showsVersion("-v", "status", because = "real git: git version 2.56.0")
        parity.rejects("status", "--version", because = "real git: unknown option `version'")
        parity.shortCircuits("-h", because = "real git: prints the top-level usage")
        parity.shortCircuits("status", "-h", because = "real git: prints git-status's usage")
    }
}
