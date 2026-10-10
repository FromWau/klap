package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

/*
 * Every expected value is from pacman 7.1.0: a `because` quotes what it printed for a read-only `-Q` line or its
 * `--help`, or names the man page. The -Qv lines ran in a scratch directory holding the directories the values name
 * (`a`, `b`, `--`, `-i`, `a=b`, `=a`, `~`, `v`), and -Qv printed the bound path back.
 */

/**
 * The record a pacman line binds in the fixture, or its refusal, as real pacman 7.1.0 reads the line (P of the testing
 * matrix): operations and their clusters, every option and letter in every spelling, abbreviations, positions, repeats,
 * the end of options, and help, version and klap's built-ins on a line.
 */
class PacmanProjectionTest {

    private val parity = ParitySuite(pacmanCli())

    private val query = NOTHING_BOUND.copy(query = true)

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

    @Test
    fun `every operation binds by its long its short and its abbreviation`() {
        checkCells(Op.entries) { op ->
            parity.check(ParseCell(op.line(), op.record(), "pacman -h lists ${op.long}"))
            parity.check(ParseCell(listOf(op.long) + op.requiredTargets, op.record(), "pacman -h lists ${op.long}"))
            abbreviationOf(op.long)?.let { prefix ->
                parity.check(ParseCell(listOf(prefix) + op.requiredTargets, op.record(), "`pacman --quer` exits 0"))
            }
        }
    }

    @Test
    fun `two different operations and none at all are refused`() {
        parity.check(ParseCell(emptyList(), null, "pacman: error: no operation specified (use -h for help)"))
        val pairs = Op.entries.flatMap { a -> Op.entries.filter { it > a }.map { a to it } }
        checkCells(pairs) { (a, b) ->
            val why = "pacman: error: only one operation may be used at a time (`pacman -Q --sync pacman`)"
            parity.check(ParseCell(listOf("-${a.short}${b.short}"), null, why))
            parity.check(ParseCell(listOf(a.long, b.long), null, why))
        }
    }

    @Test
    fun `every long on an operation's page binds in every form`() {
        val cells = Op.entries.flatMap { op ->
            op.page.map { input(it.long) }.flatMap { longCells(op, it) }
        }
        checkCells(cells) { parity.check(it) }
    }

    @Test
    fun `every letter whose fixture meaning is pacman's binds in every form`() {
        val cells = Op.entries.flatMap { op ->
            op.page.filter { it.agreesWithFixture() }.flatMap { letterCells(op, it) }
        }
        checkCells(cells) { parity.check(it) }
    }

    @Test
    fun `a flag refuses an attached value`() {
        // `pacman -Q --owns=/x` and `--file=x`: "option '--owns' doesn't allow an argument", getopt's answer
        // for every option declared without one.
        val flags = Op.QUERY.page.map { input(it.long) }.filter { it.kind !is Kind.Value }
        checkCells(flags) { parity.check(ParseCell(listOf("-Q", "${it.long}=x"), null, "doesn't allow an argument")) }
    }

    @Test
    fun `everything after the end of options is a target`() {
        // `pacman -Q -- -Q`: "error: package '-Q' was not found".
        val words = listOf("-Q", "--dbpath", "-b", "--", "pkg")
        checkCells(Op.entries) { op ->
            parity.check(ParseCell(listOf("-${op.short}", "--") + words, op.record().copy(targets = words), "--"))
        }
    }

    @Test
    fun `an unknown letter or long is refused`() {
        parity.check(ParseCell(listOf("-Qa"), null, "pacman: invalid option -- 'a'"))
        parity.check(ParseCell(listOf("-Qz"), null, "pacman: invalid option -- 'z'"))
        parity.check(ParseCell(listOf("-Qf"), null, "pacman: error: invalid option '-f'"))
        parity.check(ParseCell(listOf("-Q", "--zzz"), null, "pacman: unrecognized option '--zzz'"))
        parity.check(ParseCell(listOf("-Q", "--colour=never"), null, "pacman: unrecognized option '--colour=never'"))
        parity.check(
            ParseCell(
                listOf("--docs"),
                null,
                "pacman: unrecognized option '--docs'",
                differs = "klap reads its --docs built-in and wants a format, pacman has no --docs (todo 8)",
            ),
        )
    }

    @Test
    fun `an ambiguous abbreviation is refused`() {
        val cells = listOf("--re", "--qu", "--c", "--conf", "--dis", "--fil", "--no")
        checkCells(cells) { parity.check(ParseCell(listOf("-Q", it), null, "pacman: option '$it' is ambiguous")) }
    }

    @Test
    fun `help and version print as pacman's do`() {
        parity.showsHelp("-h", because = "pacman -h prints its usage")
        parity.showsHelp("--help", because = "pacman --help prints its usage")
        parity.showsHelp("-Qh", because = "pacman -Qh prints the -Q page")
        parity.showsHelp("-hQ", because = "pacman -hQ prints the -Q page")
        parity.showsHelp("-Q", "--help", because = "pacman -Q --help prints the -Q page")
        parity.showsVersion("--version", because = "pacman --version prints its banner, exit 0")
        parity.showsVersion("-Qq", "--version", because = "pacman -Qq --version prints its banner, exit 0")
    }

    @Test
    fun `options and the operation permute among the targets`() = parity.checkAll(
        listOf(
            // `pacman pacman -Q` lists pacman, and `pacman -Q pacman -i glibc` prints two Name lines.
            cell(listOf("pacman", "-Q"), QUERY.copy(targets = listOf("pacman")), "pacman pacman -Q: exit 0"),
            cell(listOf("pacman", "-Qi"), QUERY.copy(info = 1, targets = listOf("pacman")), "exit 0"),
            cell(
                q("pacman", "-i", "glibc"),
                QUERY.copy(info = 1, targets = listOf("pacman", "glibc")),
                "pacman -Q pacman -i glibc: info on both",
            ),
            cell(q("pacman", "--dbpath", "a"), QUERY.copy(dbPath = "a", targets = listOf("pacman")), "DB Path: .../a/"),
        ),
    )

    @Test
    fun `a counted modifier sums its long and short spellings`() = parity.checkAll(
        listOf(
            // `pacman -Qi pacman` shows no "Backup Files" section; `-Q --info -i` and `-Qi --info` show it, as -Qii.
            cell(q("--info", "-i", "pacman"), QUERY.copy(info = 2, targets = listOf("pacman")), "shows Backup Files"),
            cell(listOf("-Qi", "--info", "pacman"), QUERY.copy(info = 2, targets = listOf("pacman")), "as -Qii"),
            cell(listOf("-Qkk", "--check", "pacman"), QUERY.copy(check = 3, targets = listOf("pacman")), "exit 0"),
        ),
    )

    @Test
    fun `a value-taking letter takes the rest of its cluster or the next word`() = parity.checkAll(
        listOf(
            cell(listOf("-Qvb", "a"), QUERY.copy(verbose = true, dbPath = "a"), "-Qvb a: DB Path: .../a/"),
            cell(listOf("-Qvr", "a"), QUERY.copy(verbose = true, root = "a"), "-r takes a value like -b"),
            // `pacman -Qbv` prints no -v paths: the v was the database path.
            cell(listOf("-Qbv"), QUERY.copy(dbPath = "v"), "-Qbv: no Root/DB Path lines, so -v was the value"),
            cell(q("-b=a"), QUERY.copy(dbPath = "=a"), "-Qv -b=a: DB Path: .../=a/"),
            cell(listOf("-Qb"), null, "pacman: option requires an argument -- 'b'"),
            cell(q("-b"), null, "pacman: option requires an argument -- 'b'"),
        ),
    )

    @Test
    fun `a long value keeps everything after the first equals sign`() = parity.checkAll(
        listOf(
            cell(q("--dbpath=a=b"), QUERY.copy(dbPath = "a=b"), "-Qv --dbpath=a=b: DB Path: .../a=b/"),
            cell(q("--dbpath==a"), QUERY.copy(dbPath = "=a"), "-Qv --dbpath==a: DB Path: .../=a/"),
        ),
    )

    @Test
    fun `a value word that looks like an option is the value`() = parity.checkAll(
        listOf(
            cell(q("--dbpath", "-i"), QUERY.copy(dbPath = "-i"), "-Qv --dbpath -i: DB Path: .../-i/, Targets: None"),
            cell(q("--dbpath", "-S"), QUERY.copy(dbPath = "-S"), "failed to resolve path '-S' passed to '--dbpath'"),
            cell(q("--arch=-x", "pacman"), QUERY.copy(arch = "-x", targets = listOf("pacman")), "exit 0"),
            cell(q("--arch", "--help", "pacman"), QUERY.copy(arch = "--help", targets = listOf("pacman")), "exit 0"),
            cell(
                q("--arch", "--version", "pacman"),
                QUERY.copy(arch = "--version", targets = listOf("pacman")),
                "pacman -Q --arch --version pacman lists pacman, exit 0",
            ),
        ),
    )

    @Test
    fun `a free text value may be empty`() = parity.checkAll(
        listOf(
            cell(q("--arch=", "pacman"), QUERY.copy(arch = "", targets = listOf("pacman")), "exit 0"),
            cell(q("--arch", "", "pacman"), QUERY.copy(arch = "", targets = listOf("pacman")), "exit 0"),
            cell(q("--arch=x", "--arch=", "pacman"), QUERY.copy(arch = "", targets = listOf("pacman")), "exit 0"),
            cell(q("--logfile=", "pacman"), QUERY.copy(logFile = "", targets = listOf("pacman")), "exit 0"),
            // `pacman -Q ""`: "error: package '' was not found", so the empty word was a target.
            cell(q(""), QUERY.copy(targets = listOf("")), "error: package '' was not found"),
        ),
    )

    @Test
    fun `targets and values keep their exact text`() = parity.checkAll(
        listOf(
            cell(
                q("a b", "~/x"),
                QUERY.copy(targets = listOf("a b", "~/x")),
                "error: package 'a b' was not found, error: package '~/x' was not found",
            ),
            cell(q("--dbpath", "~"), QUERY.copy(dbPath = "~"), "-Qv --dbpath '~': DB Path: <cwd>/~/"),
            cell(q("--arch", "x y", "pacman"), QUERY.copy(arch = "x y", targets = listOf("pacman")), "exit 0"),
        ),
    )

    @Test
    fun `a single-valued option given twice keeps the later value`() = parity.checkAll(
        listOf(
            cell(q("--dbpath=a", "-b", "b"), QUERY.copy(dbPath = "b"), "-Qv: DB Path: .../b/"),
            cell(q("-b", "a", "--dbpath", "b"), QUERY.copy(dbPath = "b"), "-Qv: DB Path: .../b/"),
            cell(q("-r", "a", "-r", "b"), QUERY.copy(root = "b"), "failed to initialize alpm library: (root: .../b"),
            cell(q("--root=a", "--root=b"), QUERY.copy(root = "b"), "(root: .../b, dbpath: .../b/var/lib/pacman/)"),
            cell(q("--logfile=a", "--logfile=b"), QUERY.copy(logFile = "b"), "-Qv: Log File: .../b/log"),
            cell(q("--gpgdir=g1", "--gpgdir", "g2"), QUERY.copy(gpgDir = "g2"), "-Qv: GPG Dir: .../g2/"),
            cell(
                q("--config", "nope", "--config=/etc/pacman.conf"),
                QUERY.copy(config = "/etc/pacman.conf"),
                "-Qv: Conf File: /etc/pacman.conf",
            ),
            cell(
                q("--config=/etc/pacman.conf", "--config", "nope"),
                QUERY.copy(config = "nope"),
                "error: config file nope could not be read, so the later one was read",
            ),
            // `pacman -Qi --color=never --color=always pacman` prints escape codes into a pipe, the reverse none.
            cell(q("--color=never", "--color=always"), QUERY.copy(color = "always"), "escape codes in the output"),
            cell(q("--color=always", "--color=never"), QUERY.copy(color = "never"), "no escape codes"),
            cell(q("--color=auto", "--color=always"), QUERY.copy(color = "always"), "escape codes in the output"),
            cell(q("--color", "never", "--color=always"), QUERY.copy(color = "always"), "escape codes"),
        ),
    )

    @Test
    fun `a repeatable directory keeps every occurrence in order`() = parity.checkAll(
        listOf(
            cell(q("--cachedir=b", "--cachedir", "a"), QUERY.copy(cacheDirs = listOf("b", "a")), "Cache Dirs: b/ a/"),
            cell(q("--cachedir=a", "--cachedir=a"), QUERY.copy(cacheDirs = listOf("a", "a")), "Cache Dirs: a/ a/"),
            cell(
                q("--hookdir=b", "--hookdir=a"),
                QUERY.copy(hookDirs = listOf("b", "a")),
                "Hook Dirs: /usr/share/libalpm/hooks/ b/ a/",
            ),
        ),
    )

    @Test
    fun `a lone dash is a target`() = parity.checkAll(
        listOf(
            // The man page: "if stdin is not from a terminal and a single hyphen (-) is passed as an argument,
            // targets will be read from stdin"; `pacman -Q --debug -` waited on stdin.
            cell(q("-"), QUERY.copy(targets = listOf("-")), "man pacman: a single hyphen reads targets from stdin"),
            cell(q("--debug", "-"), QUERY.copy(debug = "1", targets = listOf("-")), "waited on stdin"),
        ),
    )

    @Test
    fun `a dash-led word after the end of options is a target`() = parity.checkAll(
        listOf(
            cell(q("--", "-5"), QUERY.copy(targets = listOf("-5")), "error: package '-5' was not found"),
            cell(q("--", "--help"), QUERY.copy(targets = listOf("--help")), "error: package '--help' was not found"),
            cell(q("--debug", "--", "pacman"), QUERY.copy(debug = "1", targets = listOf("pacman")), "exit 0"),
        ),
    )

    @Test
    fun `a digit is not an option`() = parity.checkAll(
        listOf(
            cell(listOf("-Q5"), null, "pacman: invalid option -- '5'"),
            cell(q("-5"), null, "pacman: invalid option -- '5'"),
        ),
    )

    @Test
    fun `a line setting every input of its operation binds them all`() {
        // `pacman -Q --deps --info --check --list --native --quiet --root / --unrequired --upgrades --verbose ...
        // --noconfirm pacman` printed its -v paths and ran; under -T the same general options exit 127 (a
        // dependency not satisfied), so both parsed.
        val general = listOf(
            "--root", "/", "--verbose", "--arch", "x", "--cachedir", "a", "--color", "never",
            "--config", "/etc/pacman.conf", "--confirm", "--debug", "--disable-download-timeout",
            "--disable-sandbox", "--disable-sandbox-filesystem", "--disable-sandbox-syscalls", "--hookdir", "a",
            "--logfile", "log", "--noconfirm", "--dbpath", "a",
        )
        val generalRecord: PacmanInputs.() -> PacmanInputs = {
            copy(
                root = "/",
                verbose = true,
                arch = "x",
                cacheDirs = listOf("a"),
                color = "never",
                config = "/etc/pacman.conf",
                noConfirm = true,
                debug = "1",
                disableDownloadTimeout = true,
                disableSandbox = true,
                disableSandboxFilesystem = true,
                disableSandboxSyscalls = true,
                hookDirs = listOf("a"),
                logFile = "log",
                dbPath = "a",
                targets = listOf("pacman"),
            )
        }
        val queryLine = listOf(
            "-Q", "--deps", "--info", "--check", "--list", "--native", "--quiet", "--unrequired", "--upgrades",
        ) + general + "pacman"
        val queryRecord = QUERY.generalRecord().copy(
            deps = true,
            info = 1,
            check = 1,
            list = true,
            nativePackages = true,
            quiet = true,
            unrequired = 1,
            upgrades = true,
        )
        parity.checkAll(
            listOf(
                cell(queryLine, queryRecord, "pacman -Q with every compatible -Q option: exit 0"),
                cell(listOf("-T") + general + "pacman", Op.DEPTEST.record().generalRecord(), "exit 127"),
            ),
        )
    }

    @Test
    fun `help answers wherever it sits on the line`() {
        parity.showsHelp("-Q", "pacman", "--help", because = "pacman -Q pacman --help prints the -Q page")
        parity.showsHelp("pacman", "-Qi", "--help", because = "pacman pacman -Qi --help prints the -Q page")
        parity.showsHelp("-Qih", because = "pacman -Qih prints the -Q page")
        parity.showsHelp("-Q", "--hel", because = "pacman -Q --hel prints the -Q page")
        parity.showsHelp("-Q", "--he", because = "pacman -Q --he prints the -Q page")
    }

    @Test
    fun `an abbreviated --version prints the version`() {
        parity.showsVersion("-Q", "--vers", because = "pacman -Q --vers prints the banner, exit 0")
        parity.rejects("-Q", "--ver", because = "pacman: option '--ver' is ambiguous: '--version' '--verbose'")
    }

    @Test
    fun `an unknown option on the line refuses it even beside help`() = parity.checkAll(
        listOf(
            cell(q("--help", "--bogus"), null, "pacman: unrecognized option '--bogus'"),
            cell(q("--bogus", "--help"), null, "pacman: unrecognized option '--bogus'"),
        ),
    )

    @Test
    fun `help outranks a bad value on the same line`() {
        parity.showsHelp("-Q", "--color=bogus", "--help", because = "pacman -Q --color=bogus --help prints the -Q page")
        parity.showsHelp("-Q", "--help", "--color=bogus", because = "pacman -Q --help --color=bogus prints the page")
        parity.showsHelp("-Q", "--debug=3", "--help", because = "pacman -Q --debug=3 --help prints the -Q page")
    }

    @Test
    fun `completion and docs and a later __complete are targets`() = parity.checkAll(
        listOf(
            cell(q("__complete"), QUERY.copy(targets = listOf("__complete")), "package '__complete' was not found"),
            cell(listOf("completion", "-Q"), QUERY.copy(targets = listOf("completion")), "not found"),
            cell(listOf("docs", "-Q"), QUERY.copy(targets = listOf("docs")), "package 'docs' was not found"),
            cell(q("docs"), QUERY.copy(targets = listOf("docs")), "package 'docs' was not found"),
        ),
    )

    @Test
    fun `a prefix of --docs alone is refused`() = parity.checkAll(
        listOf(
            cell(
                q("--doc"),
                null,
                "pacman: unrecognized option '--doc'",
                differs = "klap resolves --doc to its --docs built-in and wants a format (todo 41)",
            ),
        ),
    )

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
        parity.binds("-Q", "--noconfirm", "--confirm", expected = query)
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
                printOnly = true,
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

    @Test
    fun `two -Q modifiers pacman forbids together are refused`() {
        // Every pair of -Q flags was run as `pacman -Q <a> <b> pacman`; these are the ones answering "error:
        // invalid option: '<a>' and '<b>' may not be used together".
        val pairs = listOf(
            "--changelog" to "--groups", "--changelog" to "--owns", "--changelog" to "--search",
            "--deps" to "--explicit", "--deps" to "--owns", "--deps" to "--search",
            "--explicit" to "--owns", "--explicit" to "--search",
            "--groups" to "--info", "--groups" to "--check", "--groups" to "--list", "--groups" to "--owns",
            "--groups" to "--file", "--groups" to "--search",
            "--info" to "--owns", "--info" to "--search",
            "--check" to "--owns", "--check" to "--search",
            "--list" to "--owns", "--list" to "--search",
            "--foreign" to "--native", "--foreign" to "--owns", "--foreign" to "--search",
            "--native" to "--owns", "--native" to "--search",
            "--owns" to "--file", "--owns" to "--search", "--owns" to "--unrequired", "--owns" to "--upgrades",
            "--file" to "--search",
            "--search" to "--unrequired", "--search" to "--upgrades",
        )
        parity.checkAll(
            pairs.flatMap { (a, b) ->
                val why = "pacman: error: invalid option: '$a' and '$b' may not be used together"
                listOf(ParseCell(q(a, b, "pacman"), null, why), ParseCell(q(b, a, "pacman"), null, why))
            },
        )
    }

    @Test
    fun `--print-format implies --print`() = parity.checkAll(
        // From the man page only: "--print-format <format> ... Implies --print."
        listOf(
            ParseCell(
                listOf("-S", "--print-format", "%n", "pkg"),
                Op.SYNC.record().copy(printOnly = true, printFormat = "%n", targets = listOf("pkg")),
                "man pacman: Implies --print",
            ),
        ),
    )

    @Test
    fun `--disable-sandbox disables both parts of the sandbox`() = parity.checkAll(
        // The man page: "Equivalent to jointly specifying --disable-sandbox-filesystem and --disable-sandbox-syscalls."
        listOf(
            ParseCell(
                q("--disable-sandbox"),
                QUERY.copy(disableSandbox = true, disableSandboxFilesystem = true, disableSandboxSyscalls = true),
                "man pacman: equivalent to both",
            ),
        ),
    )

    @Test
    fun `-V prints the version`() {
        // `pacman -V` and `pacman -QV` both print the banner and exit 0.
        parity.showsVersion("-V", because = "pacman -V prints its banner, exit 0")
        parity.showsVersion("-QV", because = "pacman -QV prints its banner, exit 0")
        parity.showsVersion("-Q", "-V", because = "pacman -Q -V prints its banner, exit 0")
    }

    private fun offPageLongs(op: Op) {
        // Under -Q every one was run: `pacman -Q --needed pacman` says "error: invalid option '--needed'", and the
        // same for each long not on `pacman -Q --help`. Under -T likewise (`pacman -T --info`). For the other
        // operations the evidence is their `--help` page alone, since they are never run here.
        val cells = longsOffPage(op).map { input ->
            val kind = input.kind
            val word = if (kind is Kind.Value) "${input.long}=${kind.sample}" else input.long
            ParseCell(
                listOf("-${op.short}", word) + op.requiredTargets,
                null,
                "pacman: error: invalid option '${input.long}' (not on pacman -${op.short} --help)",
            )
        }
        checkCells(cells) { parity.check(it) }
    }

    private fun offPageLetters(op: Op) {
        // `pacman -Qy pacman`: "error: invalid option '-y'"; also -Qw, -Qx, and `pacman -Ti`, `-Tq`.
        val cells = lettersOffPage(op).map { letter ->
            val why = "pacman: error: invalid option '-$letter'"
            ParseCell(listOf("-${op.short}$letter") + op.requiredTargets, null, why)
        }
        checkCells(cells) { parity.check(it) }
    }

    private fun contestedLetters(op: Op) {
        val cells = op.page.filterNot { it.agreesWithFixture() }.flatMap { letterCells(op, it) }
        checkCells(cells) { parity.check(it) }
    }

    @Test
    fun `a prefix of a klap built-in pacman lacks is refused`() = parity.checkAll(
        listOf(
            ParseCell(q("--comp", "bash"), null, "pacman: unrecognized option '--comp'"),
            ParseCell(q("--js"), null, "pacman: unrecognized option '--js'"),
        ),
    )

    @Test
    fun `a prefix shared only with a klap built-in names pacman's own option`() = parity.checkAll(
        // `pacman -Q --do` answers "error: invalid option '--downloadonly'": the prefix reached --downloadonly
        // alone, which -S accepts (from `pacman -Sh` only, -S is not run here).
        listOf(
            ParseCell(
                listOf("-S", "--do", "pkg"),
                Op.SYNC.record().copy(downloadOnly = true, targets = listOf("pkg")),
                "--do is --downloadonly",
            ),
        ),
    )

    @Test
    fun `a long of another operation is refused under -Q`() = offPageLongs(Op.QUERY)

    @Test
    fun `a long of another operation is refused under -S`() = offPageLongs(Op.SYNC)

    @Test
    fun `a long of another operation is refused under -R`() = offPageLongs(Op.REMOVE)

    @Test
    fun `a long of another operation is refused under -U`() = offPageLongs(Op.UPGRADE)

    @Test
    fun `a long of another operation is refused under -D`() = offPageLongs(Op.DATABASE)

    @Test
    fun `a long of another operation is refused under -F`() = offPageLongs(Op.FILES)

    @Test
    fun `a long of another operation is refused under -T`() = offPageLongs(Op.DEPTEST)

    @Test
    fun `a letter of another operation is refused under -Q`() = offPageLetters(Op.QUERY)

    @Test
    fun `a letter of another operation is refused under -S`() = offPageLetters(Op.SYNC)

    @Test
    fun `a letter of another operation is refused under -R`() = offPageLetters(Op.REMOVE)

    @Test
    fun `a letter of another operation is refused under -U`() = offPageLetters(Op.UPGRADE)

    @Test
    fun `a letter of another operation is refused under -D`() = offPageLetters(Op.DATABASE)

    @Test
    fun `a letter of another operation is refused under -F`() = offPageLetters(Op.FILES)

    @Test
    fun `a letter of another operation is refused under -T`() = offPageLetters(Op.DEPTEST)

    // `pacman -Q --help`: -c --changelog, -d --deps, -n --native, -p --file, -u --upgrades.
    @Test
    fun `a contested letter means what -Q gives it`() = contestedLetters(Op.QUERY)

    // `pacman -R --help`: -c --cascade, -s --recursive (-ss), -u --unneeded.
    @Test
    fun `a contested letter means what -R gives it`() = contestedLetters(Op.REMOVE)

    @Test
    fun `an option pacman does not have is refused`() {
        // `pacman --json`, `pacman --docs`, `pacman --completion bash`, `pacman --help-all`: all "unrecognized
        // option". klap's completion scripts are installed through --completion, so declining it needs another way.
        val cells = listOf(
            listOf("-Q", "--json"), listOf("--json", "-Q"), listOf("--docs", "man"), listOf("-Q", "--docs", "man"),
            listOf("--completion", "bash"), listOf("-Q", "--completion", "bash"), listOf("--help-all"),
            listOf("-Q", "--help-all"),
        )
        checkCells(cells) { words ->
            val option = words.first { it.startsWith("--") }
            parity.check(ParseCell(words, null, "pacman: unrecognized option '$option'"))
        }
    }

    @Test
    fun `an operation's help lists that operation's options only`() {
        // `pacman -Qh` prints "usage:  pacman {-Q --query} [options] [package(s)]" and the -Q rows only.
        checkCells(Op.entries) { op ->
            val page = parity.helpPage("-${op.short}h", because = "pacman -${op.short}h prints the -${op.short} page")
            val missing = op.page.map { it.long }.filterNot { it in page }
            val foreign = longsOffPage(op)
                .map { it.long }
                .filter { long -> Regex("$long\\b(?!-)").containsMatchIn(page) }
            assertTrue(missing.isEmpty() && foreign.isEmpty(), "-${op.short}h page: missing $missing, foreign $foreign")
        }
    }

    @Test
    fun `an operation whose usage requires targets refuses a line without them`() {
        // From the usage lines only, as these operations are never run here: `pacman {-R --remove} [options]
        // <package(s)>` and `pacman {-U --upgrade} [options] <file(s)>`.
        checkCells(Op.entries.filter { it.requiredTargets.isNotEmpty() }) { op ->
            parity.check(ParseCell(listOf("-${op.short}"), null, "pacman ${op.long} requires <package(s)>"))
        }
    }
}

/**
 * The lines pacman 7.1.0 reads one way and klap or the fixture another today (P of the testing matrix). Each test
 * states what real pacman 7.1.0 does and is ignored with `TDD target: <what must change>`; removing the annotation
 * shows it failing for that reason, and no other test pins today's behaviour, so a fix breaks nothing else.
 */
class PacmanProjectionTargetsTest {
    private val parity = ParitySuite(pacmanCli())

    @Ignore("Will not do (todo 36): states a pacman quirk klap does not follow")
    @Test
    fun `help outranks version in either order`() {
        // `pacman --help --version` and `pacman --version --help` print the usage; with -Q, the -Q page.
        val lines = listOf(
            listOf("--help", "--version"),
            listOf("--version", "--help"),
            listOf("-Q", "--version", "--help"),
            listOf("-Qh", "--version"),
            listOf("-QV", "--help"),
        )
        checkCells(lines) { parity.showsHelp(argv = it.toTypedArray(), because = "pacman prints its usage") }
    }

    @Ignore("Will not do (todo 36): states a pacman quirk klap does not follow")
    @Test
    fun `an unknown option refuses the line beside version`() = parity.checkAll(
        listOf(ParseCell(q("--version", "--bogus"), null, "pacman: unrecognized option '--bogus'")),
    )

    @Ignore("Will not do (todo 36): states a pacman quirk klap does not follow")
    @Test
    fun `two operations are refused beside help`() = parity.checkAll(
        listOf(
            ParseCell(q("-T", "--help"), null, "pacman: error: only one operation may be used at a time"),
            ParseCell(listOf("-QT", "-h"), null, "pacman: error: only one operation may be used at a time"),
        ),
    )

    @Ignore("Will not do (todo 33): states a pacman quirk klap does not follow")
    @Test
    fun `a value word that is exactly the end of options is the value`() = parity.checkAll(
        listOf(ParseCell(q("--dbpath", "--"), QUERY.copy(dbPath = "--"), "-Qv --dbpath --: DB Path: .../--/")),
    )

    @Ignore("Will not do (todo 40): states a pacman quirk klap does not follow")
    @Test
    fun `__complete as the first word is a target`() = parity.checkAll(
        listOf(
            ParseCell(
                listOf("__complete", "-Q"),
                QUERY.copy(targets = listOf("__complete")),
                "pacman __complete -Q: error: package '__complete' was not found",
            ),
        ),
    )

    @Ignore("Will not do (todo 6): states a pacman quirk klap does not follow")
    @Test
    fun `the operation given twice is refused`() {
        // `pacman -QQ pacman`, `pacman -Q -Q pacman`, `pacman -Q --query pacman`: all "error: only one operation
        // may be used at a time".
        val cells = Op.entries.flatMap { op ->
            val why = "pacman: error: only one operation may be used at a time"
            listOf(
                ParseCell(listOf("-${op.short}${op.short}") + op.requiredTargets, null, why),
                ParseCell(listOf("-${op.short}", "-${op.short}") + op.requiredTargets, null, why),
                ParseCell(listOf("-${op.short}", op.long) + op.requiredTargets, null, why),
            )
        }
        checkCells(cells) { parity.check(it) }
    }
}
