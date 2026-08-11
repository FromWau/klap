package com.fromwau.klap.fixture.ssh

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class SshParityTest {

    private val parity = ParitySuite(sshCli())

    @Test
    fun `binds connection options and the optional remote command`() {
        parity.binds(
            "-p", "2222", "-i", "/home/u/.ssh/id_ed25519", "admin@web1",
            expected = NOTHING_BOUND.copy(
                port = 2222,
                identity = listOf("/home/u/.ssh/id_ed25519"),
                destination = "admin@web1",
            ),
        )
        // The shape the study is about: a required host, then an optional operand list that binds empty.
        parity.binds("web1", expected = NOTHING_BOUND.copy(destination = "web1"))
        parity.binds(
            "-vvv", "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=5", "web1",
            expected = NOTHING_BOUND.copy(
                verbose = 3,
                sshOptions = listOf("StrictHostKeyChecking" to "no", "ConnectTimeout" to "5"),
                destination = "web1",
            ),
        )
        // All four spellings of the assignment-shaped option, including the one whose VALUE contains '='.
        parity.binds(
            "-oConnectTimeout=5", "web1",
            expected = NOTHING_BOUND.copy(sshOptions = listOf("ConnectTimeout" to "5"), destination = "web1"),
        )
        parity.binds(
            "--option=ProxyCommand=nc %h %p", "web1",
            expected = NOTHING_BOUND.copy(sshOptions = listOf("ProxyCommand" to "nc %h %p"), destination = "web1"),
        )
        parity.binds("-tt", "web1", expected = NOTHING_BOUND.copy(forcePty = 2, destination = "web1"))
        parity.binds(
            "-L", "8080:localhost:80", "-L", "9090:localhost:90", "-J", "bastion", "web1",
            expected = NOTHING_BOUND.copy(
                localForward = listOf("8080:localhost:80", "9090:localhost:90"),
                jumpHosts = listOf("bastion"),
                destination = "web1",
            ),
        )
        parity.binds(
            "-qCN", "web1",
            expected = NOTHING_BOUND.copy(quiet = true, compression = true, noRemoteCommand = true, destination = "web1"),
        )
        parity.binds("-A", "web1", expected = NOTHING_BOUND.copy(forwardAgent = true, destination = "web1"))
        parity.binds("-a", "web1", expected = NOTHING_BOUND.copy(forwardAgent = false, destination = "web1"))
        parity.binds("-aA", "web1", expected = NOTHING_BOUND.copy(forwardAgent = true, destination = "web1"))
        parity.binds("-Aa", "web1", expected = NOTHING_BOUND.copy(forwardAgent = false, destination = "web1"))
        // The remote command passes through untouched: `ls -la` reaches the remote as itself rather than
        // being read as this CLI's own `-l` taking the attached value "a".
        parity.binds(
            "web1", "ls", "-la",
            expected = NOTHING_BOUND.copy(destination = "web1", remoteCommand = listOf("ls", "-la")),
        )
        // The headline claim itself, pinned against a DECLARED connected option: real ssh (probed) gives
        // no "Bad port" for `ssh doesnotexist.invalid ls -p abc`, so once an operand precedes it, `-p`
        // stays the remote's own token rather than being read as ssh's own port option.
        parity.binds(
            "web1", "ls", "-p", "abc",
            expected = NOTHING_BOUND.copy(destination = "web1", remoteCommand = listOf("ls", "-p", "abc")),
        )
    }

    @Test
    fun `rejects what real ssh rejects`() {
        parity.rejects("--zzz", "web1", because = "real ssh: unknown option -- -")
        parity.rejects("-Z", "web1", because = "real ssh: unknown option -- Z")
        parity.rejects(because = "real ssh: usage: ssh ... destination")
        parity.rejects("-p", because = "real ssh: option requires an argument -- p")
        parity.rejects("-p", "abc", "web1", because = "real ssh: Bad port 'abc'")
        parity.rejects("-p", "0", "web1", because = "real ssh: Bad port '0'")
        parity.rejects("-o", "novalue", "web1", because = "real ssh: no argument after keyword \"novalue\"")
        // ssh declares no `version =`, so klap injects no `--version` either and both tools answer the same.
        parity.rejects("--version", because = "real ssh: unknown option -- - (its version switch is -V)")
    }

    @Test
    fun `known divergence from real ssh`() {
        // `optionsEndAtFirstOperand` (set in sshCli) stops option parsing at the destination, so an
        // undeclared short reaches the remote instead of erroring, and a declared one is not stolen either.
        parity.binds(
            "web1", "grep", "-x", "pat",
            expected = NOTHING_BOUND.copy(destination = "web1", remoteCommand = listOf("grep", "-x", "pat")),
        )
        parity.binds(
            "web1", "tar", "-C", "/src",
            expected = NOTHING_BOUND.copy(destination = "web1", remoteCommand = listOf("tar", "-C", "/src")),
        )

        // klap's own `--json` is recognized before the tree knows which command it will reach at all, so
        // it is stripped from the remote command wherever it sits ahead of a literal `--` (see
        // `binds connection options and the optional remote command` for the line that shields it).
        parity.binds(
            "web1", "echo", "--json",
            expected = NOTHING_BOUND.copy(destination = "web1", remoteCommand = listOf("echo")),
        )

        // A `--` after the destination diverges from real ssh, even though both accept the line: probed
        // against OpenSSH, `ssh host -- -p abc` skips the "Bad port" that `ssh host -p abc` triggers, so
        // real ssh's own option scan restarts after the destination and reads that `--` as its own
        // end-of-options marker, consuming it rather than forwarding it. klap's switch instead treats
        // the destination as ending all further interpretation, so the `--` itself binds as a literal
        // token in the tail.
        parity.binds(
            "web1", "--", "ls", "-la", "/var/log",
            expected = NOTHING_BOUND.copy(
                destination = "web1",
                remoteCommand = listOf("--", "ls", "-la", "/var/log"),
            ),
        )
        // Same divergence: real ssh's restarted scan consumes this `--` too, forwarding only `echo
        // --json` to the remote. klap keeps the `--` in the bound tail; without it, this remote `--json`
        // would instead be stripped by klap's own `--json` built-in, per the line above.
        parity.binds(
            "web1", "--", "echo", "--json",
            expected = NOTHING_BOUND.copy(destination = "web1", remoteCommand = listOf("--", "echo", "--json")),
        )

        // `-4`/`-6` are ordinary short flags; without that, a dash-led digit would be read as a value and
        // land in the first positional slot instead, turning `destination` into "-4".
        parity.binds("-4", "web1", expected = NOTHING_BOUND.copy(ipv4 = true, destination = "web1"))
        parity.binds("-6", "web1", expected = NOTHING_BOUND.copy(ipv6 = true, destination = "web1"))

        // Real ssh prints its version for -V and exits 0. klap's `--version` built-in carries no short and its
        // long name is reserved tree-wide even for an unversioned root, so `-V` can only be an ordinary flag —
        // which leaves the required <destination> unsatisfied.
        parity.rejects("-V", because = "klap gap: no short reaches a print-and-exit built-in, NOT real-ssh behaviour")
    }

    @Test
    fun `accepts surface real ssh does not have`() {
        // Real ssh has no long options at all, so every long spelling below is invented and the real tool
        // answers `unknown option -- -` to all of them.
        parity.bindsLoosely(
            "--port", "2222", "web1",
            because = "real ssh: unknown option -- -",
            expected = NOTHING_BOUND.copy(port = 2222, destination = "web1"),
        )
        parity.bindsLoosely(
            "--verbose", "--quiet", "web1",
            because = "real ssh: unknown option -- -",
            expected = NOTHING_BOUND.copy(verbose = 1, quiet = true, destination = "web1"),
        )
        // The long form stays invented — real ssh has never had `--ipv4` — but `-4` is ssh's own spelling.
        parity.bindsLoosely(
            "--ipv4", "web1",
            because = "real ssh: unknown option -- - (it spells this -4)",
            expected = NOTHING_BOUND.copy(ipv4 = true, destination = "web1"),
        )

        // klap's own position-independent built-ins, none of which real ssh has; this fixture declines
        // none of them via `builtins { }`, so they all reach the line.
        parity.bindsLoosely(
            "--json", "web1",
            because = "real ssh: unknown option -- -",
            expected = NOTHING_BOUND.copy(destination = "web1"),
        )
        parity.bindsLoosely(
            "--color=never", "web1",
            because = "real ssh: unknown option -- -",
            expected = NOTHING_BOUND.copy(destination = "web1"),
        )

        parity.shortCircuits("--help", because = "real ssh: unknown option -- -")
        parity.shortCircuits("-h", "web1", because = "real ssh: unknown option -- h")
        parity.shortCircuits("--help-all", because = "real ssh: unknown option -- -")
        parity.shortCircuits("--completion", "bash", because = "real ssh: unknown option -- -")
        parity.shortCircuits("--docs", "markdown", because = "real ssh: unknown option -- -")
        // A host literally named `__complete` is unreachable: the hidden built-in wins the subcommand walk.
        parity.shortCircuits("__complete", "web1", because = "real ssh: would resolve __complete as the destination")
    }
}
