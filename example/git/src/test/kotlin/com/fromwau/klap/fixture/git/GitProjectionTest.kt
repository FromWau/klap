package com.fromwau.klap.fixture.git

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.io.CliPath
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

private fun cell(
    rows: String,
    vararg argv: String,
    expected: GitInputs?,
    because: String,
) = ParseCell(rows, argv.toList(), expected, because)

/** add's spellings, each with the record real git reads from it, run again under `stage` (P19). */
private val ADD_SPELLINGS: List<Pair<List<String>, GitInputs.Add>> = listOf(
    listOf("-n", "f") to NOTHING_ADD.copy(dryRun = true, pathspec = listOf("f")),
    listOf("--dry-run", "f") to NOTHING_ADD.copy(dryRun = true, pathspec = listOf("f")),
    listOf("-Anv", "f") to NOTHING_ADD.copy(addAll = true, dryRun = true, verbose = true, pathspec = listOf("f")),
    listOf("-N", "f") to NOTHING_ADD.copy(intentToAdd = true, pathspec = listOf("f")),
    listOf("--chmod=+x", "f") to NOTHING_ADD.copy(chmod = ExecutableBit.Set, pathspec = listOf("f")),
    listOf("--chmod", "-x", "f") to NOTHING_ADD.copy(chmod = ExecutableBit.Clear, pathspec = listOf("f")),
    listOf("--pathspec-from-file=p") to NOTHING_ADD.copy(pathspecFromFile = CliPath("p")),
    listOf("--", "-n") to NOTHING_ADD.copy(pathspec = listOf("-n")),
)

/**
 * The record a line of git's tree binds, or its refusal (P of the testing matrix, the rows about the tree and the
 * root options), checked against how real git 2.56.0 reads the line in a throwaway repository. This class holds what
 * klap and the fixture already do.
 */
class GitProjectionTest {

    private val parity = ParitySuite(gitCli())

    @Test
    fun `every command binds its own variant and carries each root option given before it`() =
        parity.checkAll(BEFORE_THE_SUBCOMMAND)

    @Test
    fun `an alias binds what its command binds in every spelling`() = parity.checkAll(
        // P19: `git stage -h` prints git-add's usage, and `git remote rm zz` answers as `git remote remove zz`.
        ADD_SPELLINGS.flatMap { (words, record) ->
            listOf("add", "stage").map { name ->
                ParseCell("P19", listOf(name) + words, record, "`git $name ${words.joinToString(" ")}` runs")
            }
        } + listOf("remove", "rm").map { name ->
            cell("P19", "remote", name, "o", expected = NOTHING_REMOTE_REMOVE.copy(name = "o"), because = "runs")
        },
    )

    @Test
    fun `remote binds its own variant bare and with its own option`() = parity.checkAll(
        listOf(
            // P12: `git remote -v`, `--verbose`, `--verb` and `-vv` list the URLs.
            cell("P12", "remote", "-v", expected = NOTHING_REMOTE.copy(verbose = true), because = "lists URLs"),
            cell("P12", "remote", "--verbose", expected = NOTHING_REMOTE.copy(verbose = true), because = "lists URLs"),
            cell("P12", "remote", "--verb", expected = NOTHING_REMOTE.copy(verbose = true), because = "lists URLs"),
            cell("P12", "remote", "-vv", expected = NOTHING_REMOTE.copy(verbose = true), because = "lists URLs"),
        ),
    )

    @Test
    fun `a word that is no command of the tree is refused`() = parity.checkAll(
        listOf(
            // P18: git abbreviates no subcommand: "git: 'sta' is not a git command", "unknown subcommand: `ad'".
            cell("P18", "sta", expected = null, because = "'sta' is not a git command"),
            cell("P18", "st", expected = null, because = "'st' is not a git command"),
            cell("P18", "remote", "ad", "o", "u", expected = null, because = "unknown subcommand: `ad'"),
            cell("P18", "remote", "re", "o", expected = null, because = "unknown subcommand: `re'"),
            // P13: after `--` add is no subcommand, and remote takes no operand.
            cell("P13", "remote", "--", "add", "o", "u", expected = null, because = "unknown subcommand: `add'"),
            cell("P13", "remote", "zzz", expected = null, because = "unknown subcommand: `zzz'"),
            // P38: bare git is usage, and an unknown word or option is refused.
            cell("P38", "bogus", expected = null, because = "'bogus' is not a git command"),
            cell("P38", "--bogus", expected = null, because = "unknown option: --bogus"),
        ),
    )

    @Test
    fun `an option of one command written on another command's segment is refused`() = parity.checkAll(
        listOf(
            // P15: a sibling's option, a parent's after the child's name, a child's before it.
            cell("P15", "status", "--amend", expected = null, because = "unknown option `amend'"),
            cell("P15", "remote", "add", "-v", "o", "u", expected = null, because = "unknown switch `v'"),
            cell("P15", "remote", "rm", "-v", "o", expected = null, because = "unknown switch `v'"),
            cell("P15", "remote", "-f", "add", "o", "u", expected = null, because = "unknown switch `f'"),
            // P24: a cluster of a command's letter before the subcommand: "unknown option: -sP".
            cell("P24", "-sP", "status", expected = null, because = "unknown option: -sP"),
        ),
    )

    @Test
    fun `a repeated root option keeps the order of the line`() = parity.checkAll(
        listOf(
            // P22: `git -c a.b=1 -c a.b=2 config --get-all a.b` printed 1 then 2; `git -C sub -C .. rev-parse
            // --show-prefix` read the second relative to the first.
            cell(
                "P22",
                "-c", "a.b=1", "-c", "a.b=2", "status",
                expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(config = listOf("a.b=1", "a.b=2"))),
                because = "both apply in order",
            ),
            cell(
                "P22",
                "-C", "sub", "-C", "..", "status",
                expected = NOTHING_STATUS.copy(
                    globals = NO_GLOBALS.copy(directory = listOf(CliPath("sub"), CliPath(".."))),
                ),
                because = "both apply in order",
            ),
            // P23: git accepts both polarities before the subcommand; which one holds shows only on a terminal, so
            // the last-written rule here could not be observed.
            cell(
                "P23",
                "--no-pager", "--paginate", "status",
                expected = NOTHING_STATUS,
                because = "both accepted",
            ),
            cell(
                "P23",
                "--paginate", "-P", "status",
                expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(paginate = false)),
                because = "both accepted",
            ),
        ),
    )

    @Test
    fun `a value-taking root option takes the next word whatever it looks like`() = parity.checkAll(
        listOf(
            // P26: `git -C status status` is "cannot change to 'status'", and `git -C --bare rev-parse
            // --show-prefix` printed `--bare/` in a directory of that name.
            cell(
                "P26",
                "-C", "status", "status",
                expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(directory = listOf(CliPath("status")))),
                because = "-C takes status",
            ),
            cell(
                "P26",
                "-C", "--bare", "status",
                expected = NOTHING_STATUS.copy(globals = NO_GLOBALS.copy(directory = listOf(CliPath("--bare")))),
                because = "-C takes --bare",
            ),
            // P25: after `--` a root spelling is a pathspec: `git add -- --bare` ran.
            cell(
                "P25",
                "add", "--", "--bare",
                expected = NOTHING_ADD.copy(pathspec = listOf("--bare")),
                because = "a pathspec",
            ),
            cell(
                "P25",
                "commit", "-m", "x", "--", "-C", "d",
                expected = NOTHING_COMMIT.copy(message = listOf("x"), pathspec = listOf("-C", "d")),
                because = "pathspecs",
            ),
            // P45: `git commit --dry-run -m status` and `git commit -m -C --dry-run` ran with those messages.
            cell(
                "P45",
                "commit", "-m", "status",
                expected = NOTHING_COMMIT.copy(message = listOf("status")),
                because = "the message",
            ),
            cell(
                "P45",
                "commit", "-m", "-C",
                expected = NOTHING_COMMIT.copy(message = listOf("-C")),
                because = "the message",
            ),
            // P47: unique among add's own longs; `git remote add --ma main o8 u8` ran (owner decision K35 for --f).
            cell(
                "P47",
                "remote", "add", "--ma", "main", "o", "u",
                expected = NOTHING_REMOTE_ADD.copy(master = "main", name = "o", url = "u"),
                because = "--master",
            ),
            cell(
                "P47",
                "remote", "add", "--f", "o", "u",
                expected = NOTHING_REMOTE_ADD.copy(fetch = true, name = "o", url = "u"),
                because = "--fetch",
            ),
        ),
    )

    @Test
    fun `a line naming no command shows the usage`() {
        // P2, P37: bare `git` prints the usage (exit 1, which the suite never compares); `git -h` and `git --version`.
        parity.showsHelp(because = "bare git prints the usage")
        parity.showsHelp("-h", because = "git -h prints the usage")
        parity.showsVersion("--version", because = "git version 2.56.0")
        // P26: -c takes `status` as its name=value, so no command is named: `git -c status` prints the usage.
        parity.showsHelp("-c", "status", because = "git -c status prints the usage")
    }

    @Test
    fun `help shows the page of the command named before it`() = checkCells(
        listOf(
            // P35: `git remote add --help` and `-h` print add's usage, `git remote rm -h` remove's, `git status -h`
            // status's.
            listOf("remote", "add", "--help") to "git remote add",
            listOf("remote", "add", "-h") to "git remote add",
            listOf("remote", "rm", "-h") to "git remote remove",
            listOf("status", "-h") to "git status",
            listOf("remote", "--help") to "git remote",
        ),
    ) { (argv, page) ->
        val line = argv.joinToString(" ")
        assertEquals(page, parity.helpFor(argv = argv.toTypedArray(), because = "git $line"), "P35 `git $line`")
    }

    @Test
    fun `help before a command's name or after its parent's option shows that command's page`() = checkCells(
        listOf(
            // P35: `git -h status` opened git-status(1); `git remote -v add -h` printed add's usage.
            listOf("-h", "status") to "git status",
            listOf("remote", "-v", "add", "-h") to "git remote add",
        ),
    ) { (argv, page) ->
        val line = argv.joinToString(" ")
        assertEquals(page, parity.helpFor(argv = argv.toTypedArray(), because = "git $line"), "P35 `git $line`")
    }

    @Test
    fun `a root option after the leaf is refused`() = parity.checkAll(
        AFTER_THE_LEAF + listOf(
            // P17: `git -C a remote add -f o u -C b` is "unknown switch `C'" at its last -C.
            cell("P17", "-C", "a", "remote", "add", "-f", "o", "u", "-C", "b", expected = null, because = "-C"),
            // P22, P23, P24: `git status -c x.y=z`, `git status --paginate` and `git status -P` are refused.
            cell("P22", "-c", "a=1", "status", "-c", "b=2", expected = null, because = "unknown switch `c'"),
            cell("P23", "--no-pager", "status", "--paginate", expected = null, because = "unknown option"),
            cell("P24", "status", "-sP", expected = null, because = "unknown switch `P'"),
            // P51: `git status --bar` abbreviates nothing of status.
            cell("P51", "status", "--bar", expected = null, because = "unknown option `bar'"),
        ),
    )

    @Test
    fun `a root option between remote and its child is refused`() = parity.checkAll(BETWEEN_THE_LEVELS)

    @Test
    fun `a root option between remote add's operands is refused`() = parity.checkAll(BETWEEN_THE_OPERANDS)

    @Test
    fun `-C and -c take their value as the next word only`() = parity.checkAll(
        listOf(
            // todo 27, K39: `git -Csub status`, `git -C. status` and `git -cx.y=z status` are "unknown option".
            cell("P4b", "-Cd", "status", expected = null, because = "unknown option: -Cd"),
            cell("P4b", "-ca.b=1", "status", expected = null, because = "unknown option: -ca.b=1"),
            // `git --directory sub status`, `--directory=sub` and `--config x.y=z` are "unknown option".
            cell("P4b", "--directory", "d", "status", expected = null, because = "unknown option: --directory"),
            cell("P4b", "--directory=d", "status", expected = null, because = "unknown option: --directory=d"),
            cell("P4b", "--config", "a.b=1", "status", expected = null, because = "unknown option: --config"),
            cell("P4b", "--config=a.b=1", "status", expected = null, because = "unknown option: --config=a.b=1"),
        ),
    )

    @Test
    fun `root shorts in one word are refused`() = parity.checkAll(
        listOf(
            // P24: `git -PC . status` and `git -pP status` are "unknown option".
            cell("P24", "-PC", "d", "status", expected = null, because = "unknown option: -PC"),
            cell("P24", "-Pc", "a.b=1", "status", expected = null, because = "unknown option: -Pc"),
        ),
    )

    @Test
    fun `a root long is matched whole`() = parity.checkAll(
        // P51: each of these is "unknown option: --<word>" in real git.
        listOf("--bar", "--pag", "--no-pa", "--git-d=.git", "--work=.", "--names=n", "--exec", "--no-rep", "--no-opt")
            .map { word -> cell("P51", word, "status", expected = null, because = "unknown option: $word") } +
            cell("P51", "--vers", expected = null, because = "unknown option: --vers"),
    )

    @Test
    fun `a letter means the root's option before the subcommand and the command's after it`() = parity.checkAll(
        listOf(
            // P29: `git -p log` and `git -p status` run paginated, `git log -p` shows patches.
            cell("P29", "-p", "log", expected = NOTHING_LOG, because = "paginates"),
            cell("P29", "-p", "status", expected = NOTHING_STATUS, because = "paginates"),
            cell("P29", "-P", "-p", "status", expected = NOTHING_STATUS, because = "both accepted"),
            cell("P29", "-p", "log", "-p", expected = NOTHING_LOG.copy(patch = true), because = "paginated patches"),
            // `git commit -C HEAD --dry-run` ran: commit's -C is --reuse-message.
            cell("P29", "commit", "-C", "HEAD", expected = NOTHING_COMMIT.copy(reuseMessage = "HEAD"), because = "-C"),
            cell(
                "P29",
                "-C", "d", "commit", "-C", "HEAD",
                expected = NOTHING_COMMIT.copy(
                    globals = NO_GLOBALS.copy(directory = listOf(CliPath("d"))),
                    reuseMessage = "HEAD",
                ),
                because = "the root's -C, then commit's",
            ),
        ),
    )

    @Test
    fun `remote's own option before a child's name routes to the child`() = parity.checkAll(
        listOf(
            // P14: `git remote -v add o2 u2`, `--verbose add`, `--verb add o7 u7`, `-v remove o3` and `-v rm o2` ran.
            cell("P14", "remote", "-v", "add", "o", "u", expected = remoteAdd(), because = "added"),
            cell("P14", "remote", "--verbose", "add", "o", "u", expected = remoteAdd(), because = "added"),
            cell("P14", "remote", "--verb", "add", "o", "u", expected = remoteAdd(), because = "added"),
            cell("P14", "remote", "-v", "remove", "o", expected = remoteRemove(), because = "removed"),
            cell("P14", "remote", "-v", "rm", "o", expected = remoteRemove(), because = "removed"),
            // `git remote -v -- add` is "unknown subcommand: `add'", and `git remote -v zzz` too.
            cell("P14", "remote", "-v", "--", "add", "o", "u", expected = null, because = "unknown subcommand"),
            cell("P14", "remote", "-v", "zzz", expected = null, because = "unknown subcommand: `zzz'"),
        ),
    )

    @Test
    fun `remote's verbose has a negative form`() = parity.checkAll(
        listOf(
            // P12: `git remote --no-verbose` and `git remote -v --no-verbose show` list names only.
            cell("P12", "remote", "--no-verbose", expected = NOTHING_REMOTE, because = "names only"),
            cell("P12", "remote", "-v", "--no-verbose", expected = NOTHING_REMOTE, because = "names only"),
        ),
    )

    @Test
    fun `a built-in git does not have is refused`() = parity.checkAll(
        listOf(
            // P2: `git --json`, `--color` are "unknown option"; `git completion` and `git docs` are no git command.
            cell("P2", "--json", "status", expected = null, because = "unknown option: --json"),
            cell("P2", "--color=never", "status", expected = null, because = "unknown option: --color=never"),
            cell("P2", "status", "--json", expected = null, because = "unknown option `json'"),
            cell("P2", "completion", "bash", expected = null, because = "'completion' is not a git command"),
            cell("P2", "docs", "markdown", expected = null, because = "'docs' is not a git command"),
        ),
    )

    @Test
    fun `git var needs -l or a variable`() {
        // P16: bare `git var` prints "usage: git var (-l | <variable>)" and exits 129; `git var GIT_EDITOR` ran.
        parity.rejects("var", because = "real git: usage: git var (-l | <variable>)")
        parity.accepts("var", "-l", because = "real git lists the variables")
        parity.accepts("var", "GIT_EDITOR", because = "real git prints the editor")
        // `git var -l GIT_EDITOR` prints the same usage, exit 129.
        parity.rejects("var", "-l", "GIT_EDITOR", because = "real git: usage: git var (-l | <variable>)")
    }

    @Test
    fun `commit's -c reedits a message`() = parity.checkAll(
        listOf(
            // P29: `git commit -c HEAD --dry-run` ran: commit's -c is --reedit-message, not the root's -c.
            cell("P29", "commit", "-c", "HEAD", expected = NOTHING_COMMIT.copy(reeditMessage = "HEAD"), because = "-c"),
        ),
    )
}

/**
 * The P cells the owner decided klap will not follow: each is ignored as a record of what real git 2.56.0 does there,
 * and its annotation names the decision.
 */
class GitProjectionTargetsTest {

    private val parity = ParitySuite(gitCli())

    @Ignore("Will not do (todo 172): an app acts on a bare --exec-path before dispatch; klap binds it and routes on")
    @Test
    fun `a bare exec-path answers before any command`() {
        // P27: `git --exec-path status` printed /usr/lib/git-core and nothing of status (man git: "If no path is
        // given, git will print the current setting and then exit").
        parity.shortCircuits("--exec-path", "status", because = "real git prints the exec path and exits")
        parity.shortCircuits("--exec-path", "log", because = "real git prints the exec path and exits")
    }

    @Ignore("Will not do (todo 171): klap shows the deepest command named, wherever the help request sits")
    @Test
    fun `a help request shows the page git shows for its position`() = checkCells(
        listOf(
            // P35: `git --help remote add` opened git-remote(1); `git remote -h add` printed remote's usage.
            listOf("--help", "remote", "add") to "git remote",
            listOf("remote", "-h", "add") to "git remote",
        ),
    ) { (argv, page) ->
        val line = argv.joinToString(" ")
        assertEquals(page, parity.helpFor(argv = argv.toTypedArray(), because = "git $line"), "P35 `git $line`")
    }
}

private fun remoteAdd(): GitInputs.RemoteAdd = NOTHING_REMOTE_ADD.copy(name = "o", url = "u")

private fun remoteRemove(): GitInputs.RemoteRemove = NOTHING_REMOTE_REMOVE.copy(name = "o")
