package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

/**
 * The parts of real pacman 7.1.0's surface beyond the headline clusters: its general options, the
 * per-operation options and letters, abbreviations, repeats, and what it refuses while parsing. Every
 * `because` quotes what the installed pacman printed for a read-only `-Q` line or its `--help`.
 */
class PacmanSurfaceTest {

    private val parity = ParitySuite(pacmanCli())

    private val query = NOTHING_BOUND.copy(query = true)

    @Test
    fun `binds every general option pacman documents`() {
        parity.binds("-Q", "--arch=x86_64", expected = query.copy(arch = "x86_64"))
        parity.binds("-Q", "--arch", "x86_64", expected = query.copy(arch = "x86_64"))
        parity.binds("-Q", "--config=/etc/pacman.conf", expected = query.copy(config = "/etc/pacman.conf"))
        parity.binds("-Q", "--gpgdir", "/etc/pacman.d/gnupg", expected = query.copy(gpgDir = "/etc/pacman.d/gnupg"))
        parity.binds("-Q", "--logfile=/dev/null", expected = query.copy(logFile = "/dev/null"))
        parity.binds("-Q", "--sysroot", "/mnt", expected = query.copy(sysroot = "/mnt"))
        parity.binds(
            "-Q", "--disable-download-timeout", "--disable-sandbox", "--disable-sandbox-filesystem",
            "--disable-sandbox-syscalls",
            expected = query.copy(
                disableDownloadTimeout = true,
                disableSandbox = true,
                disableSandboxFilesystem = true,
                disableSandboxSyscalls = true,
            ),
        )
        parity.binds("-Q", "--verbose", "--verbose", expected = query.copy(verbose = true))
        parity.binds("-Q", "-v", "-v", expected = query.copy(verbose = true))
    }

    @Test
    fun `binds each repeat of a directory pacman tries in turn`() {
        // The man page: "Multiple cache directories can be specified" and "Multiple hook directories can be
        // specified"; `pacman -Q --cachedir=/tmp --cachedir=/var/cache/pacman/pkg pacman` exits 0.
        parity.binds("-Q", "--cachedir=/a", "--cachedir", "/b", expected = query.copy(cacheDirs = listOf("/a", "/b")))
        parity.binds("-Q", "--hookdir=/a", "--hookdir=/b", expected = query.copy(hookDirs = listOf("/a", "/b")))
        // A repeated single-valued option is accepted by pacman too; the later one binds here.
        parity.binds("-Q", "--dbpath=/a", "--dbpath=/b", expected = query.copy(dbPath = "/b"))
    }

    @Test
    fun `binds the debug level only when attached`() {
        parity.binds("-Q", "--debug", expected = query.copy(debug = "1"))
        parity.binds("-Q", "--debug=2", expected = query.copy(debug = "2"))
        parity.binds(
            "-Q", "--debug", "2",
            because = "real pacman: error: package '2' was not found, so the 2 was a target",
            expected = query.copy(debug = "1", targets = listOf("2")),
        )
        parity.rejects("-Q", "--debug=3", because = "real pacman: error: '3' is not a valid debug level")
        parity.rejects("-Q", "--debug=0", because = "real pacman: error: '0' is not a valid debug level")
    }

    @Test
    fun `the later of --noconfirm and --confirm wins`() {
        // The man page: "--confirm  Cancels the effects of a previous --noconfirm."
        parity.binds("-Q", "--noconfirm", "--confirm", expected = query.copy(confirm = true))
        parity.binds("-Q", "--confirm", "--noconfirm", expected = query.copy(noConfirm = true))
    }

    @Test
    fun `binds the transaction and upgrade options`() {
        parity.binds(
            "-S", "--asdeps", "--dbonly", "--noprogressbar", "--noscriptlet", "--needed",
            "--print-format", "%n", "--ignore", "a", "--ignore=b", "--ignoregroup", "g",
            "--overwrite", "/usr/*", "--assume-installed", "foo=1", "firefox",
            expected = NOTHING_BOUND.copy(
                sync = true,
                asDeps = true,
                dbOnly = true,
                noProgressBar = true,
                noScriptlet = true,
                needed = true,
                printFormat = "%n",
                ignore = listOf("a", "b"),
                ignoreGroup = listOf("g"),
                overwrite = listOf("/usr/*"),
                assumeInstalled = listOf("foo=1"),
                targets = listOf("firefox"),
            ),
        )
        parity.binds("-U", "--asexplicit", "x.pkg.tar.zst", expected = NOTHING_BOUND.copy(
            upgrade = true,
            asExplicit = true,
            targets = listOf("x.pkg.tar.zst"),
        ))
    }

    @Test
    fun `binds the letters only one operation uses as pacman does`() {
        parity.binds("-Qe", expected = query.copy(explicit = true))
        parity.binds("-Qkk", "pacman", expected = query.copy(check = 2, targets = listOf("pacman")))
        parity.binds("-Dk", expected = NOTHING_BOUND.copy(database = true, check = 1))
        parity.binds("-Qm", expected = query.copy(foreign = true))
        parity.binds("-Qo", "/usr/bin/pacman", expected = query.copy(owns = true, targets = listOf("/usr/bin/pacman")))
        parity.binds("-Qtt", expected = query.copy(unrequired = 2))
        parity.binds(
            "-Fx", "bin/ls",
            expected = NOTHING_BOUND.copy(files = true, regex = true, targets = listOf("bin/ls")),
        )
        parity.binds("-Sgg", expected = NOTHING_BOUND.copy(sync = true, groups = 2))
        parity.binds("-Qqq", expected = query.copy(quiet = true), because = "real pacman accepts it, exit 0")
        parity.binds("-F", "--machinereadable", expected = NOTHING_BOUND.copy(files = true, machineReadable = true))
    }

    @Test
    fun `binds the displaced readings of a contested letter by their long names`() {
        parity.binds("-Q", "--deps", expected = query.copy(deps = true))
        parity.binds(
            "-Q", "--file", "x.pkg.tar.zst",
            expected = query.copy(packageFile = true, targets = listOf("x.pkg.tar.zst")),
        )
        parity.binds("-R", "--recursive", "--recursive", "vim", expected = NOTHING_BOUND.copy(
            remove = true,
            recursive = 2,
            targets = listOf("vim"),
        ))
        // `--file` is an exact name though `--files` starts with it, as getopt_long matches it.
        parity.rejects("-Q", "--fil", because = "real pacman: option '--fil' is ambiguous: '--files' '--file'")
    }

    @Test
    fun `binds unambiguous abbreviations and rejects ambiguous ones as getopt does`() {
        parity.binds("-Q", "--col=never", expected = query.copy(color = "never"))
        parity.binds("-Q", "--deb", expected = query.copy(debug = "1"))
        parity.binds("-Q", "--verb", expected = query.copy(verbose = true))
        parity.binds("-Q", "--nocon", expected = query.copy(noConfirm = true))
        parity.binds("-Q", "--color", "always", expected = query.copy(color = "always"))
        parity.rejects("-Q", "--re", because = "real pacman: --re is ambiguous, --remove --recursive --regex --refresh")
        parity.rejects("-Q", "--qu", because = "real pacman: option '--qu' is ambiguous: '--query' '--quiet'")
        parity.rejects("-Q", "--conf=x", because = "real pacman: '--conf=x' is ambiguous: '--confirm' '--config'")
        parity.rejects("-Q", "--dis", because = "real pacman: option '--dis' is ambiguous (four --disable-* options)")
        parity.rejects("-Q", "--c", because = "real pacman: option '--c' is ambiguous")
    }

    @Test
    fun `rejects a missing or unwanted value as pacman does`() {
        for (option in listOf(
            "--arch", "--cachedir", "--config", "--gpgdir", "--hookdir", "--logfile", "--sysroot",
            "--print-format", "--ignore", "--ignoregroup", "--assume-installed", "--overwrite", "--root",
        )) {
            parity.rejects("-Q", option, because = "real pacman: option '$option' requires an argument")
        }
        parity.rejects("-Q", "--owns=/x", because = "real pacman: option '--owns' doesn't allow an argument")
        parity.rejects("-Q", "--file=x", because = "real pacman: option '--file' doesn't allow an argument")
        parity.rejects("-Q", "--color=", because = "real pacman: error: invalid argument '' for --color")
        parity.rejects("-Qa", because = "real pacman: invalid option -- 'a'")
        parity.rejects("-Qf", because = "real pacman: error: invalid option '-f'")
    }

    @Test
    fun `binds after the end of options as a target`() {
        parity.binds(
            "-Q", "--", "-Q",
            because = "real pacman: error: package '-Q' was not found, so it was a target",
            expected = query.copy(targets = listOf("-Q")),
        )
        parity.binds("-Qi", "--", "pacman", expected = query.copy(info = 1, targets = listOf("pacman")))
    }

    @Test
    fun `shows help for an operation's help line`() {
        // Real pacman prints the -Q page here; klap prints its one page for every operation.
        parity.showsHelp("-Qh", because = "real pacman: usage:  pacman {-Q --query} [options] [package(s)]")
        parity.showsHelp("-hQ", because = "real pacman: usage:  pacman {-Q --query} [options] [package(s)]")
        parity.showsHelp("-Q", "--help", because = "real pacman: usage:  pacman {-Q --query} [options] [package(s)]")
    }
}
