package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class PacmanParityTest {

    private val parity = ParitySuite(pacmanCli())

    @Test
    fun `binds stacked operation clusters`() {
        // The headline shape, and the answer to "what does klap do with a stacked operation flag":
        // the short-cluster walk resolves every character independently against this command's own
        // specs, so `-Syu` binds exactly as `-S -y -u` would. The operation letter is a flag like any
        // other; nothing about it re-scopes the letters that follow.
        parity.binds("-Syu", expected = NOTHING_BOUND.copy(sync = true, refresh = 1, sysUpgrade = 1))
        // pacman's doubled letters are cumulative, which is what .count() is for; they cluster too.
        parity.binds("-Syyu", expected = NOTHING_BOUND.copy(sync = true, refresh = 2, sysUpgrade = 1))
        parity.binds(
            "-Suu", "firefox",
            expected = NOTHING_BOUND.copy(sync = true, sysUpgrade = 2, targets = listOf("firefox")),
        )
        parity.binds("-Scc", expected = NOTHING_BOUND.copy(sync = true, clean = 2))
        parity.binds(
            "-Qi", "firefox",
            expected = NOTHING_BOUND.copy(query = true, info = 1, targets = listOf("firefox")),
        )
        parity.binds(
            "-Qii", "firefox",
            expected = NOTHING_BOUND.copy(query = true, info = 2, targets = listOf("firefox")),
        )
        parity.binds(
            "-Qs", "editor",
            expected = NOTHING_BOUND.copy(query = true, search = true, targets = listOf("editor")),
        )
        parity.binds(
            "--sync", "--refresh", "--sysupgrade",
            expected = NOTHING_BOUND.copy(sync = true, refresh = 1, sysUpgrade = 1),
        )
        // The long form of `-Rns`, and the only spelling of it this fixture gets exactly right: the
        // long names do not collide across operations, so all three reach the input real pacman means.
        parity.binds(
            "--remove", "--recursive", "--nosave", "vim",
            expected = NOTHING_BOUND.copy(remove = true, recursive = 1, noSave = true, targets = listOf("vim")),
        )
        // `pacman -Q` with no target lists every installed package, so the operand list is zero-or-more.
        parity.binds("-Q", expected = NOTHING_BOUND.copy(query = true))
        // `--quer` reaches `--query` and no other spelling, as it does for real pacman.
        parity.binds("--quer", expected = NOTHING_BOUND.copy(query = true))
        parity.binds(
            "-Sw", "firefox",
            expected = NOTHING_BOUND.copy(sync = true, downloadOnly = true, targets = listOf("firefox")),
        )
        parity.binds(
            "-Sdd", "firefox",
            expected = NOTHING_BOUND.copy(sync = true, noDeps = 2, targets = listOf("firefox")),
        )
        parity.binds(
            "-Sp", "firefox",
            expected = NOTHING_BOUND.copy(sync = true, printOnly = true, targets = listOf("firefox")),
        )
        parity.binds(
            "-Q", "-b", "/var/lib/pacman",
            expected = NOTHING_BOUND.copy(query = true, dbPath = "/var/lib/pacman"),
        )
        parity.binds("-Q", "--root=/mnt", expected = NOTHING_BOUND.copy(query = true, root = "/mnt"))
        // `--color` reaches pacman's own option rather than klap's built-in rendering switch.
        parity.binds("--color=never", "-Q", expected = NOTHING_BOUND.copy(color = "never", query = true))
        parity.binds(
            "--noconfirm", "-U", "./pkg.tar.zst",
            expected = NOTHING_BOUND.copy(upgrade = true, noConfirm = true, targets = listOf("./pkg.tar.zst")),
        )
    }

    @Test
    fun `rejects what real pacman rejects`() {
        parity.rejects("--zzz", because = "real pacman: unrecognized option '--zzz'")
        parity.rejects("--colour=never", "-Q", because = "real pacman: unrecognized option '--colour=never'")
        parity.rejects("-z", because = "real pacman: invalid option -- 'z'")
        parity.rejects("-Qz", because = "real pacman: invalid option -- 'z'")
        parity.rejects("--dbpath", because = "real pacman: option '--dbpath' requires an argument")
        parity.rejects("--color=zzz", "-Q", because = "real pacman: invalid argument 'zzz' for --color")
        // This reproduces both of pacman's own operation errors, with no hand-rolled check in the action.
        parity.rejects(because = "real pacman: error: no operation specified (use -h for help)")
        parity.rejects("-Q", "-T", because = "real pacman: error: only one operation may be used at a time")
        parity.rejects("--query", "--deptest", because = "real pacman: error: only one operation may be used at a time")
    }

    @Test
    fun `built ins that agree with real pacman`() {
        // Real pacman lists `{-h --help}` and `{-V --version}` among its own operations: both print and exit
        // before any operation is required.
        parity.shortCircuits("-h", because = "real pacman also prints help here, exit 0")
        parity.shortCircuits("--version", because = "real pacman also prints its version here, exit 0")
        parity.shortCircuits("-Qq", "--version", because = "--version outranks a bound operation, as in real pacman")
    }
}
