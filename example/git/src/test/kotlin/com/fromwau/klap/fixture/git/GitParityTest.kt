package com.fromwau.klap.fixture.git

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class GitParityTest {

    private val parity = ParitySuite(gitCli())

    @Test
    fun bindsTwoLevelRoutingAndGlobalsAtAnyDepth() {
        parity.binds(
            "-C", "/tmp/repo", "status", "--short",
            expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(directory = listOf("/tmp/repo")), short = true),
        )
        parity.binds("status", "-sb", expected = NOTHING_STATUS.copy(short = true, branch = true))
        // A global read from two levels down, and the `=` spelling of another.
        parity.binds(
            "--git-dir=/tmp/g", "--bare", "-c", "user.name=x", "remote", "add", "o", "u",
            expected = NOTHING_REMOTE_ADD.copy(
                globals = NO_GLOBALS.copy(gitDir = "/tmp/g", bare = true, config = listOf("user.name=x")),
                name = "o",
                url = "u",
            ),
        )
        // The attached form of the optional-value global genuinely matches real git: it binds its own
        // value and routes to `status` normally (verified against 2.55.0).
        parity.binds(
            "--exec-path=/opt/gitcore", "status",
            expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(execPath = "/opt/gitcore")),
        )
    }

    @Test
    fun bindsThePaginatePairAtAnyDepth() {
        // `.negatable()`'s explicit-spelling overload reproduces git's real pager pair: `--paginate`
        // and both of the negative half's real spellings, `-P` and `--no-pager`, all bind. Only the
        // SHORT `-p` is unclaimed, because `add`/`commit`/`log` each spell `-p` as `--patch`; the
        // KLAP-GAP block above the global's declaration records that.
        parity.binds("status", expected = NOTHING_STATUS)
        parity.binds("--paginate", "status", expected = NOTHING_STATUS)
        parity.binds(
            "--no-pager", "status",
            expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(paginate = false)),
        )
        parity.binds("-P", "status", expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(paginate = false)))
    }

    @Test
    fun bindsAddAndItsAlias() {
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
            expected = NOTHING_ADD.copy(chmod = "+x", pathspec = listOf("s.sh")),
        )
        // Both spellings of `--chmod`'s value bind: real git's parse-options hands a required-value option
        // the next token regardless of its shape, so the space-separated `-x` is read as the value here too.
        parity.binds(
            "add", "--chmod", "-x", "f",
            expected = NOTHING_ADD.copy(chmod = "-x", pathspec = listOf("f")),
        )
    }

    @Test
    fun bindsCommitMessagesAndNegations() {
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
        // matching real git's own "stuck to the option without a space" rule (verified against 2.55.0).
        parity.binds(
            "commit", "-Sabc", "-m", "x",
            expected = NOTHING_COMMIT.copy(message = listOf("x"), gpgSign = "abc"),
        )
        parity.binds(
            "commit", "-F", "msg.txt", "--author=A U Thor",
            expected = NOTHING_COMMIT.copy(messageFile = "msg.txt", author = "A U Thor"),
        )
    }

    @Test
    fun bindsLogsTwoOperandGroups() {
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
    fun bindsRemoteAsAHybridParent() {
        // `git remote` bare runs the parent's own action; `git remote add ...` routes past it.
        parity.binds("remote", expected = NOTHING_REMOTE)
        parity.binds("remote", "-v", expected = NOTHING_REMOTE.copy(verbose = true))
        parity.binds(
            "remote", "add", "-f", "--mirror=push", "-t", "main", "origin", "https://x/y.git",
            expected = NOTHING_REMOTE_ADD.copy(
                fetch = true,
                mirror = "push",
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
    fun rejectsWhatRealGitRejects() {
        parity.rejects("--zzz", because = "real git: unknown option: --zzz")
        parity.rejects("zzz", because = "real git: 'zzz' is not a git command")
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
    fun knownDivergenceFromRealGit() {
        // klap cannot combine `.negatable()` with `.optionalValue()` on one holder, so `--decorate` stayed
        // negatable (for `--no-decorate`) rather than gaining the `=WHEN` spellings; see the KLAP-GAP note
        // beside its declaration.
        parity.rejects(
            "log", "--decorate=full",
            because = "klap gap: --decorate stays negatable for --no-decorate, NOT real-git behaviour",
        )

        // `-S`/`--gpg-sign` took the opposite side of the same trade: it kept the value form and lost
        // negation, so `--no-gpg-sign` (verified accepted by real git 2.55.0, countermanding commit.gpgSign)
        // is unreachable here.
        parity.rejects(
            "commit", "--no-gpg-sign", "-m", "x",
            because = "klap gap: --gpg-sign took the value form for -S<keyid>, NOT real-git behaviour",
        )

        // The subcommand walk stops at the first token that does not name a child, so an option cannot sit
        // between a parent and its subcommand. git's own help says "-v must be placed before a subcommand",
        // and `git remote -v add up <url>` really does work (verified against 2.55.0). Only a GLOBAL survives
        // that position in klap, which is why the `-C` line in the binding test above does.
        parity.rejects(
            "remote", "-v", "add", "up", "https://x/y.git",
            because = "klap gap: an option cannot precede a subcommand, NOT real-git behaviour",
        )

        // A global reserves its short across the WHOLE tree, so one letter cannot mean different things at
        // different levels the way git's `-p` (paginate at the root, patch below it) does. Real git accepts
        // both of these; `-v` is git's short for --version, which klap's built-in cannot carry.
        parity.rejects("-p", "log", because = "klap gap: globals reserve a short tree-wide, NOT real-git behaviour")
        parity.rejects("-v", because = "klap gap: the --version built-in has no short, NOT real-git behaviour")

        // The fixture's own shortfall, not klap's: klap can carry both spellings on one input, but this
        // tree only declares the primary, so real git's `--after` synonym for `--since` is unreached here.
        parity.rejects("log", "--after=2.weeks", because = "the fixture declares one spelling; real git takes both")

        // Accepted by both, bound differently — the divergences that no reject/accept comparison can see.
        // Real git's bare `--exec-path` is a print-and-exit special case ("If no path is given, git
        // will print the current setting and then exit", verified against 2.55.0: neither `status`
        // nor `log` after it is ever reached). klap cannot express a print-and-exit special case for a
        // single option, so it binds the compiled default below and continues on to the subcommand.
        parity.binds(
            "--exec-path", "status",
            expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(execPath = "/usr/lib/git-core")),
        )

        // `-5` is git's shorthand for `-n 5`; numericAlias binds it to maxCount like any other real invocation.
        parity.binds("log", "-5", expected = NOTHING_LOG.copy(maxCount = 5))
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
    fun acceptsSurfaceRealGitDoesNotHave() {
        // The fixture declares `-C`/`-c` with an invented long spelling (`--directory`/`--config`) rather
        // than short-only, though klap can do short-only now; these pin what real git rejects as a result.
        parity.bindsLoosely(
            "--directory", "/tmp/repo", "status",
            because = "real git: unknown option: --directory",
            expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(directory = listOf("/tmp/repo"))),
        )
        parity.bindsLoosely(
            "--config", "user.name=x", "status",
            because = "real git: unknown option: --config",
            expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(config = listOf("user.name=x"))),
        )

        // klap's own position-independent built-ins, all of which real git answers `unknown option` to.
        // `builtins { }` could decline json/color/completion/docs/-h; this fixture declines none of them.
        parity.bindsLoosely("--json", "status", because = "real git: unknown option: --json", expected = NOTHING_STATUS)
        parity.bindsLoosely(
            "--color=never", "status",
            because = "real git: unknown option: --color",
            expected = NOTHING_STATUS,
        )

        parity.shortCircuits("--help-all", because = "real git: unknown option: --help-all")
        // Injected as SUBCOMMANDS rather than options, git's root having no action of its own.
        parity.shortCircuits("completion", "bash", because = "real git: 'completion' is not a git command")
        parity.shortCircuits("docs", "markdown", because = "real git: 'docs' is not a git command")
        parity.shortCircuits("__complete", "sta", because = "real git: '__complete' is not a git command")

        // Both tools treat these as a help request; only the exit codes differ (git 129, klap 0), which is
        // exactly the comparison this suite never makes.
        parity.shortCircuits("-h", because = "real git: prints the top-level usage")
        parity.shortCircuits("status", "-h", because = "real git: prints git-status's usage")
    }
}
