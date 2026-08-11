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
            expected = NOTHING_BOUND.copy(remove = true, recursive = true, noSave = true, targets = listOf("vim")),
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
        // -V is terminal in real pacman rather than an operation that conflicts, so this line is legal
        // there; see the -V KLAP-GAP for the half that is not.
        parity.binds("-QV", expected = NOTHING_BOUND.copy(query = true, versionOperation = true))
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
    fun `known divergence from real pacman`() {
        // THE divergence for this tool: a contested short letter carries one meaning tree-wide, so the
        // canonical `-Rns` binds nosave (right) and search (wrong — real pacman reads `-s` under -R as
        // --recursive). Flip `recursive = true, search = false` above if klap ever gains a per-operation
        // letter namespace; until then the long form above is the exact spelling.
        parity.binds(
            "-Rns", "vim",
            expected = NOTHING_BOUND.copy(remove = true, noSave = true, search = true, targets = listOf("vim")),
        )
        // The same collision from the query side: `-Qc` is --changelog to real pacman and --clean here.
        parity.binds("-Qc", "firefox", expected = NOTHING_BOUND.copy(query = true, clean = 1, targets = listOf("firefox")))

        // The quieter half of the gap: real pacman rejects a modifier that does not belong to the
        // selected operation, in BOTH spellings ("error: invalid option '-y'" and "error: invalid
        // option '--recursive'", both verified). Every flag declared on a klap command is legal
        // whatever else bound, so these lines parse cleanly here.
        parity.bindsLoosely(
            "-Qy",
            because = "real pacman: error: invalid option '-y'",
            expected = NOTHING_BOUND.copy(query = true, refresh = 1),
        )
        parity.bindsLoosely(
            "-Q", "--recursive",
            because = "real pacman: error: invalid option '--recursive'",
            expected = NOTHING_BOUND.copy(query = true, recursive = true),
        )

        // The -V KLAP-GAP: real pacman prints its version and exits 0 here. klap's built-in --version has
        // no short form and its name is reserved, so `-V` is an ordinary flag that satisfies no operation
        // and the requireExactlyOne set rejects the line.
        parity.rejects("-V", because = "klap: --version is a built-in with no short, NOT real-pacman behaviour")

        // The two built-ins that AGREE with real pacman, which lists `{-h --help}` and `{-V --version}`
        // among its own operations: both print and exit before any operation is required.
        parity.shortCircuits("-h", because = "real pacman also prints help here, exit 0")
        parity.shortCircuits("--version", because = "real pacman also prints its version here, exit 0")
        parity.shortCircuits("-Qq", "--version", because = "--version outranks a bound operation, as in real pacman")

        // A built-in pacman never had, reaching a line whose first target could be a package name.
        parity.shortCircuits("__complete", because = "klap's hidden __complete subcommand shadows a target")
    }
}
