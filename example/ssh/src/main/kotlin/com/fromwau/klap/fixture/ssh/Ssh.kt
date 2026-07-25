package com.fromwau.klap.fixture.ssh

import com.fromwau.klap.Err
import com.fromwau.klap.Ok
import com.fromwau.klap.Result
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * One `-o KEY=VALUE` occurrence. Split at the FIRST `=` so a value may itself contain `=`
 * (`-o ProxyCommand=nc -X connect %h %p` style values do).
 */
private fun sshOptionKeyValue(raw: String): Result<Pair<String, String>, String> {
    val split = raw.indexOf('=')
    return if (split <= 0) Err("expected KEY=VALUE, got '$raw'")
    else Ok(raw.substring(0, split) to raw.substring(split + 1))
}

/**
 * `ssh [-p PORT] [-i KEYFILE] [-o KEY=VALUE]... [-v]... USER@HOST [COMMAND ARG...]`
 *
 * A study of OpenSSH's client surface expressed in klap. Every action body is a stub.
 */
public fun sshCli(): TypedCli<SshInputs> = cliOf("ssh") {
    // Real ssh is a wrapper: everything after the destination is the remote's own command line, not ssh's
    // to interpret. Without this, a remote flag this CLI happens to also declare (`-l`, `-C`, ...) was read
    // locally instead of reaching the remote host.
    optionsEndAtFirstOperand = true
    description = "OpenSSH remote login client"
    epilogue = "Everything after the destination is handed to the remote shell verbatim."

    example(
        "ssh -p 2222 -i ~/.ssh/id_ed25519 admin@web1",
        "connect on a non-default port with an explicit key",
    )
    example(
        "ssh -vvv -o StrictHostKeyChecking=no -o ConnectTimeout=5 web1",
        "counted -v, repeatable -o whose value is itself KEY=VALUE",
    )
    example(
        "ssh web1",
        "no remote command: the trailing operand list is optional and binds empty",
    )
    example(
        "ssh web1 ls -la /var/log",
        "the remote command passes through untouched, dash-led tokens included",
    )

    // ---------------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------------

    val port = option("--port", "-p", help = "port to connect to on the remote host").int().range(1..65535)
    val login = option("--login", "-l", help = "user to log in as on the remote machine")
    val bindAddress = option("--bind-address", "-b", help = "address of the local interface to bind")
    val configFile = option("--config", "-F", help = "per-user configuration file").file()
    val logFile = option("--log-file", "-E", help = "append debug logs here instead of stderr").file()

    // Repeatable in real ssh: most-preferred key first.
    val identity = option("--identity", "-i", help = "identity (private key) file").file().multiple()

    // The assignment-shaped repeatable option. `.convert` runs per occurrence, so each `-o` value is
    // split into a typed pair and a malformed one reports a real parse error. All four spellings the
    // parser recognizes work here: `-o K=V`, `-oK=V`, `--option K=V`, `--option=K=V`
    // (internal/parse/Parser.kt splits a long token at its FIRST '='; the rest, `K=V`, stays the value).
    val sshOptions = option("--option", "-o", help = "set a config option, as KEY=VALUE")
        .convert(::sshOptionKeyValue)
        .multiple()

    val jumpHosts = option("--jump", "-J", help = "connect via a jump host first").multiple()

    // ---------------------------------------------------------------------------------------------
    // Port forwarding
    // ---------------------------------------------------------------------------------------------

    val (localForward, remoteForward, dynamicForward) = group("Port forwarding") {
        Triple(
            option("--local-forward", "-L", help = "[bind:]port:host:hostport").multiple(),
            option("--remote-forward", "-R", help = "[bind:]port:host:hostport").multiple(),
            option("--dynamic-forward", "-D", help = "[bind:]port, SOCKS proxy").multiple(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // Flags
    // ---------------------------------------------------------------------------------------------

    // The counting flag: -v, -vv, -vvv and -v -v -v all read back as an Int.
    val verbose = flag("--verbose", "-v", help = "raise the debug level; repeat for more").count()

    // Real ssh's -t is likewise cumulative (-tt forces a tty even with no local tty).
    val forcePty = flag("--force-pty", "-t", help = "force pseudo-terminal allocation; repeat to force harder").count()

    val quiet = flag("--quiet", "-q", help = "quiet mode")
    val compression = flag("--compression", "-C", help = "request compression")
    val noRemoteCommand = flag("--no-remote-command", "-N", help = "do not execute a remote command")
    val disablePty = flag("--disable-pty", "-T", help = "disable pseudo-terminal allocation")

    // ssh spells agent forwarding as the short pair `-A` (on) / `-a` (off), with no long form of its own;
    // the long spellings here are invented so the fixture reads as one input, which is what ssh documents.
    val forwardAgent = flag("--forward-agent", "-A", help = "enable authentication agent forwarding")
        .negatable("--no-forward-agent", "-a", default = false)

    // `ssh -4` is ssh's own spelling. The long forms are invented, kept so the fixture still reads as one
    // input under two spellings.
    val ipv4 = flag("--ipv4", "-4", help = "force IPv4 addresses only")
    val ipv6 = flag("--ipv6", "-6", help = "force IPv6 addresses only")

    // KLAP-GAP: ssh prints its version with `-V`, but `reservedLongNames()` reserves `version` for klap's
    // own built-in (which carries no short), so `-V` here is a plain flag the action interprets itself.
    val showVersion = flag("--show-version", "-V", help = "display the version number (real ssh: -V)")

    // ---------------------------------------------------------------------------------------------
    // Operands
    // ---------------------------------------------------------------------------------------------

    // ssh's operand shape is `DESTINATION [COMMAND [ARG...]]` — a required host followed by an
    // OPTIONAL, verbatim remote command. The two slots are declarable as two slots: bindPositionals
    // keys the Multiple guard on `min` alone (internal/parse/Parser.kt), so the trailing variadic's
    // empty slice binds an empty list rather than reporting MissingArgument, and `ssh web1` reads back
    // as destination = "web1" and an empty command list. Help agrees — argSummary (internal/render/Help.kt)
    // renders a min = 0 variadic as `[command...]`, so usage reads `ssh <destination> [command...]
    // [options]` — and a missing host names the slot it means ("missing required argument
    // <destination> for 'ssh'"). The destination being a scalar again, it carries its own
    // `.validate`/`.completeWith` without the check also running on every remote-command token.
    val destination = argument("destination", "[user@]hostname")
        .validate("destination must not be blank") { it.isNotBlank() }

    // `optionsEndAtFirstOperand` above makes this arrive verbatim: destination is klap's last say over the
    // line, and everything after it, dash-led or not, is the remote's own command. A remote command that
    // itself needs to say `--json`/`--color`/`--help`/etc. still needs its own `--` ahead of it, since
    // klap's own built-ins are recognized before the walk knows which command it will reach at all.
    val remoteCommand = argument("command", "the command to run on the remote host, and its arguments")
        .multiple()

    action {
        val tail = remoteCommand()
        val settings = buildList<String> {
            port()?.let { add("port=$it") }
            login()?.let { add("login=$it") }
            bindAddress()?.let { add("bind=$it") }
            configFile()?.let { add("config=$it") }
            logFile()?.let { add("log=$it") }
            if (identity().isNotEmpty()) add("keys=${identity().joinToString(",")}")
            if (jumpHosts().isNotEmpty()) add("jump=${jumpHosts().joinToString(",")}")
            sshOptions().forEach { (key, value) -> add("$key=$value") }
            localForward().forEach { add("-L $it") }
            remoteForward().forEach { add("-R $it") }
            dynamicForward().forEach { add("-D $it") }
            if (verbose() > 0) add("verbosity=${verbose()}")
            if (forcePty() > 0) add("force-pty=${forcePty()}")
            if (quiet()) add("quiet")
            if (compression()) add("compression")
            if (noRemoteCommand()) add("no-remote-command")
            if (disablePty()) add("disable-pty")
            if (forwardAgent()) add("forward-agent")
            if (!forwardAgent()) add("no-forward-agent")
            if (ipv4()) add("ipv4")
            if (ipv6()) add("ipv6")
            if (showVersion()) add("show-version")
        }
        val host = destination()
        val what =
            if (tail.isEmpty()) "would open a login shell on $host"
            else "would run '${tail.joinToString(" ")}' on $host"
        Ok("$what [${settings.joinToString("; ")}]")
    }

    projection {
        SshInputs(
            port = port(),
            login = login(),
            bindAddress = bindAddress(),
            configFile = configFile(),
            logFile = logFile(),
            identity = identity(),
            sshOptions = sshOptions(),
            jumpHosts = jumpHosts(),
            localForward = localForward(),
            remoteForward = remoteForward(),
            dynamicForward = dynamicForward(),
            verbose = verbose(),
            forcePty = forcePty(),
            quiet = quiet(),
            compression = compression(),
            noRemoteCommand = noRemoteCommand(),
            disablePty = disablePty(),
            forwardAgent = forwardAgent(),
            ipv4 = ipv4(),
            ipv6 = ipv6(),
            showVersion = showVersion(),
            destination = destination(),
            remoteCommand = remoteCommand(),
        )
    }
}

/**
 * What one `ssh` line binds, as values rather than as the handles that read them.
 *
 * Being a plain data class is the point: a parity case asserts a whole invocation with one `assertEquals`
 * against a `copy()` of [NOTHING_BOUND], so every field it does *not* name is pinned to its default too.
 */
public data class SshInputs(
    val port: Int?,
    val login: String?,
    val bindAddress: String?,
    val configFile: String?,
    val logFile: String?,
    val identity: List<String>,
    val sshOptions: List<Pair<String, String>>,
    val jumpHosts: List<String>,
    val localForward: List<String>,
    val remoteForward: List<String>,
    val dynamicForward: List<String>,
    val verbose: Int,
    val forcePty: Int,
    val quiet: Boolean,
    val compression: Boolean,
    val noRemoteCommand: Boolean,
    val disablePty: Boolean,
    val forwardAgent: Boolean,
    val ipv4: Boolean,
    val ipv6: Boolean,
    val showVersion: Boolean,
    val destination: String,
    val remoteCommand: List<String>,
)

/** `ssh` with no arguments at all: every field at the default the declaration gives it. */
public val NOTHING_BOUND: SshInputs = SshInputs(
    port = null,
    login = null,
    bindAddress = null,
    configFile = null,
    logFile = null,
    identity = emptyList(),
    sshOptions = emptyList(),
    jumpHosts = emptyList(),
    localForward = emptyList(),
    remoteForward = emptyList(),
    dynamicForward = emptyList(),
    verbose = 0,
    forcePty = 0,
    quiet = false,
    compression = false,
    noRemoteCommand = false,
    disablePty = false,
    forwardAgent = false,
    ipv4 = false,
    ipv6 = false,
    showVersion = false,
    destination = "",
    remoteCommand = emptyList(),
)
