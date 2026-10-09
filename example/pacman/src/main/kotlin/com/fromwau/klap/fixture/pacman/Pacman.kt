package com.fromwau.klap.fixture.pacman

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.CountFlag
import com.fromwau.klap.Flag
import com.fromwau.klap.Opt
import com.fromwau.klap.TypedCli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection

/**
 * Pacman v7.1.0 (libalpm v16.0.1), reproduced as a command-line surface only. Nothing here touches a
 * package database; the action is a stub, as everywhere in this suite.
 *
 * Real usage: `pacman <operation> [options] [targets]`
 * ```
 * pacman {-h --help}
 * pacman {-V --version}
 * pacman {-D --database} <options> <package(s)>
 * pacman {-F --files}    [options] [file(s)]
 * pacman {-Q --query}    [options] [package(s)]
 * pacman {-R --remove}   [options] <package(s)>
 * pacman {-S --sync}     [options] [package(s)]
 * pacman {-T --deptest}  [options] [package(s)]
 * pacman {-U --upgrade}  [options] <file(s)>
 * ```
 *
 * The shape no coreutil in this suite has: the operation is a CHARACTER INSIDE the option cluster, and the
 * remaining characters are modifiers whose meaning is scoped to it — `-Syu` is sync + refresh + sysupgrade,
 * `-Rns` is remove + nosave + recursive, `-Qi` is query + info. See the operation-modifier note below for
 * what klap does with that.
 */
public fun pacmanCli(): TypedCli<PacmanInputs> = cliOf("pacman") {
    // pacman takes any unambiguous prefix of a long option: `pacman --quer` is --query.
    abbreviation = Abbreviation.Options
    description = "Package manager utility"
    version = "7.1.0"
    epilogue = "A target is usually a package name, file name, URL, or a search string."

    // pacman documents its own `--color <when>`, and klap's built-in claims that name — it would be
    // rejected by validateReservedNames and, worse, parse() would strip both the flag and its value before
    // pacman saw them. Declining the built-in hands the name back. The two happen to take the same three
    // values, which is exactly why the collision would have gone unnoticed.
    builtins { color = false }

    example("pacman -Syu", "refresh the databases and upgrade every out-of-date package")
    example("pacman -Syyu", "the same, forcing a full database refresh")
    example("pacman -Rns vim", "remove a package, its unneeded deps, and its saved config files")
    example("pacman -Qi firefox", "show information about an installed package")
    example("pacman -Qs editor", "search installed package names and descriptions")

    val database: Flag
    val files: Flag
    val query: Flag
    val remove: Flag
    val sync: Flag
    val deptest: Flag
    val upgrade: Flag

    group("Operations") {
        database = flag("--database", "-D", help = "operate on the package database")
        files = flag("--files", "-F", help = "query the files database")
        query = flag("--query", "-Q", help = "query the package database")
        remove = flag("--remove", "-R", help = "remove package(s) from the system")
        sync = flag("--sync", "-S", help = "synchronize packages")
        deptest = flag("--deptest", "-T", help = "check dependencies")
        upgrade = flag("--upgrade", "-U", help = "upgrade or add package(s) from a file or URL")
    }

    // This is a direct hit for pacman's own two operation errors: bare `pacman` answers "error: no
    // operation specified (use -h for help)" and `pacman -Q -T` answers "error: only one operation may be
    // used at a time" (both verified). requireExactlyOne raises ExactlyOneRequired and MutuallyExclusive
    // for exactly those two cases, at parse time and ahead of every bind, and annotates each operation's
    // --help row so the set reads as a set. No hand-rolled check in action { } is needed, unlike tar's.
    requireExactlyOne(
        database,
        files,
        query,
        remove,
        sync,
        deptest,
        upgrade,
    )

    // KLAP-GAP: real pacman's `-V` is terminal and equivalent to `--version` (`pacman -QV` succeeds). klap's
    // `--version` built-in has no short form and `version` is unconditionally reserved (reservedLongNames),
    // so `-V` below is a plain flag left out of requireExactlyOne — bare `pacman -V` fails instead of printing.
    val versionOperation = flag("-V", help = "display version and exit")

    val dbPath: Opt<String?>
    val root: Opt<String?>
    val verbose: Flag
    val arch: Opt<String?>
    val cacheDirs: Opt<List<String>>
    val color: Opt<String?>
    val config: Opt<String?>
    val confirm: Flag
    val debug: Opt<String?>
    val disableDownloadTimeout: Flag
    val disableSandbox: Flag
    val disableSandboxFilesystem: Flag
    val disableSandboxSyscalls: Flag
    val gpgDir: Opt<String?>
    val hookDirs: Opt<List<String>>
    val logFile: Opt<String?>
    val noConfirm: Flag
    val sysroot: Opt<String?>

    group("General options") {
        dbPath = option("--dbpath", "-b", help = "set an alternate database location").file().placeholder("path")
        root = option("--root", "-r", help = "set an alternate installation root").file().placeholder("path")
        verbose = flag("--verbose", "-v", help = "be verbose")
        arch = option("--arch", help = "set an alternate architecture").placeholder("arch")
        cacheDirs = option("--cachedir", help = "set an alternate package cache location")
            .file()
            .placeholder("dir")
            .multiple()
        color = option("--color", help = "colorize the output").choice("always", "never", "auto")
        config = option("--config", help = "set an alternate configuration file").file().placeholder("path")
        confirm = flag("--confirm", help = "always ask for confirmation")
        // Real pacman takes only 1 and 2 attached (`--debug=3` is "not a valid debug level"); the bare form
        // binds the lower one here.
        debug = option("--debug", help = "display debug messages").choice("1", "2").optionalValue("1")
        disableDownloadTimeout = flag("--disable-download-timeout", help = "use relaxed timeouts for download")
        disableSandbox = flag(
            "--disable-sandbox",
            help = "disables all sandbox features used for the downloader process",
        )
        disableSandboxFilesystem = flag(
            "--disable-sandbox-filesystem",
            help = "disables the filesystem part of the downloader process sandbox",
        )
        disableSandboxSyscalls = flag(
            "--disable-sandbox-syscalls",
            help = "disables the syscalls part of the downloader process sandbox",
        )
        gpgDir = option("--gpgdir", help = "set an alternate home directory for GnuPG").file().placeholder("path")
        hookDirs = option("--hookdir", help = "set an alternate hook location").file().placeholder("dir").multiple()
        logFile = option("--logfile", help = "set an alternate log file").file().placeholder("path")
        noConfirm = flag("--noconfirm", help = "do not ask for any confirmation")
        sysroot = option("--sysroot", help = "operate on a mounted guest system (root-only)").file().placeholder("dir")
    }

    // The man page: "--confirm  Cancels the effects of a previous --noconfirm."
    lastWins(noConfirm, confirm)

    val noDeps: CountFlag
    val printOnly: Flag
    val assumeInstalled: Opt<List<String>>
    val dbOnly: Flag
    val noProgressBar: Flag
    val noScriptlet: Flag
    val printFormat: Opt<String?>

    group("Transaction options (-S, -R, -U)") {
        // pacman's doubled letters are genuinely cumulative ("specify this option twice to skip all
        // dependency checks"), so .count() is the faithful shape, and it clusters like a boolean flag:
        // `-Sdd` counts 2 the same way `-vv` does.
        noDeps = flag("--nodeps", "-d", help = "skip dependency version checks (-dd to skip all checks)").count()
        printOnly = flag("--print", "-p", help = "print the targets instead of performing the operation")
        assumeInstalled = option("--assume-installed", help = "add a virtual package to satisfy dependencies")
            .placeholder("package=version")
            .multiple()
        dbOnly = flag("--dbonly", help = "only modify database entries, not package files")
        noProgressBar = flag("--noprogressbar", help = "do not show a progress bar when downloading files")
        noScriptlet = flag("--noscriptlet", help = "do not execute the install scriptlet if one exists")
        printFormat = option("--print-format", help = "specify how the targets should be printed")
            .placeholder("string")
    }

    val downloadOnly: Flag
    val asDeps: Flag
    val asExplicit: Flag
    val ignore: Opt<List<String>>
    val ignoreGroup: Opt<List<String>>
    val needed: Flag
    val overwrite: Opt<List<String>>

    group("Upgrade options (-S, -U)") {
        downloadOnly = flag("--downloadonly", "-w", help = "download packages but do not install/upgrade anything")
        asDeps = flag("--asdeps", help = "install packages as non-explicitly installed")
        asExplicit = flag("--asexplicit", help = "install packages as explicitly installed")
        ignore = option("--ignore", help = "ignore a package upgrade (can be used more than once)")
            .placeholder("pkg")
            .multiple()
        ignoreGroup = option("--ignoregroup", help = "ignore a group upgrade (can be used more than once)")
            .placeholder("grp")
            .multiple()
        needed = flag("--needed", help = "do not reinstall up to date packages")
        overwrite = option("--overwrite", help = "overwrite conflicting files (can be used more than once)")
            .placeholder("glob")
            .multiple()
    }

    val refresh: CountFlag
    val sysUpgrade: CountFlag
    val clean: CountFlag
    val info: CountFlag
    val list: Flag
    val search: Flag
    val groups: CountFlag
    val quiet: Flag
    val noSave: Flag
    val explicit: Flag
    val check: CountFlag
    val foreign: Flag
    val owns: Flag
    val unrequired: CountFlag
    val regex: Flag
    val changelog: Flag
    val cascade: Flag
    val recursive: CountFlag
    val unneeded: Flag
    val upgrades: Flag
    val nativePackages: Flag
    val deps: Flag
    val packageFile: Flag
    val machineReadable: Flag

    // KLAP-GAP: real pacman resolves a modifier's meaning against the operation in its cluster (`-c` is
    // --changelog under -Q, --clean under -S); klap has no per-operation namespace, so each contested
    // letter binds one meaning tree-wide (validateDuplicateOptionFlagNames) and every other reading
    // survives only as a long spelling. Real pacman also rejects a modifier that doesn't belong to the
    // selected operation; every flag declared here is legal regardless, since a spec list can't scope
    // one input's validity to another's presence.
    group("Operation modifiers") {
        refresh = flag(
            "--refresh",
            "-y",
            help = "download fresh package databases from the server (-yy to force a refresh even if up to date)",
        ).count()
        sysUpgrade = flag("--sysupgrade", "-u", help = "upgrade installed packages (-uu enables downgrades)").count()
        clean = flag("--clean", "-c", help = "remove old packages from cache directory (-cc for all)").count()
        info = flag(
            "--info",
            "-i",
            help = "view package information (-ii for backup files or extended information)",
        ).count()
        list = flag("--list", "-l", help = "list the files of a package, or the packages in a repo (-S)")
        search = flag("--search", "-s", help = "search packages for matching strings")
        groups = flag("--groups", "-g", help = "view all members of a package group (-gg for all groups)").count()
        quiet = flag("--quiet", "-q", help = "show less information for query and search")
        noSave = flag("--nosave", "-n", help = "remove configuration files")
        // Letters only one operation uses, so klap binds them exactly as real pacman does.
        explicit = flag("--explicit", "-e", help = "list packages explicitly installed [filter]")
        check = flag("--check", "-k", help = "check that package files exist (-kk for file properties)").count()
        foreign = flag("--foreign", "-m", help = "list installed packages not found in sync db(s) [filter]")
        owns = flag("--owns", "-o", help = "query the package that owns <file>")
        unrequired = flag(
            "--unrequired",
            "-t",
            help = "list packages not (optionally) required by any package (-tt to ignore optdepends) [filter]",
        ).count()
        regex = flag("--regex", "-x", help = "enable searching using regular expressions")

        // The readings the contested shorts above displaced. Their long spellings do not collide with
        // anything, so klap can express the OPTION; only the letter is gone.
        changelog = flag("--changelog", help = "view the changelog of a package (-Q)")
        cascade = flag("--cascade", help = "remove packages and all packages that depend on them (-R)")
        recursive = flag(
            "--recursive",
            help = "remove unnecessary dependencies (-R; twice includes explicitly installed dependencies)",
        ).count()
        unneeded = flag("--unneeded", help = "remove unneeded packages (-R)")
        upgrades = flag("--upgrades", help = "list outdated packages (-Q) [filter]")
        nativePackages = flag("--native", help = "list installed packages only found in sync db(s) (-Q) [filter]")
        deps = flag("--deps", help = "list packages installed as dependencies (-Q) [filter]")
        packageFile = flag("--file", help = "query a package file instead of the database (-Q)")
        machineReadable = flag("--machinereadable", help = "produce machine-readable output (-F)")
    }

    // KLAP-GAP: real pacman's operand arity is per-operation (required for -D/-R/-U, optional for
    // -F/-Q/-S/-T), but klap's positional cardinality is fixed at build time, so it stays permissive.
    val targets = argument("target", "package name, file name, URL, or search string").multiple()

    action<String>(human = { it }) {
        val operation = when {
            database() -> "database"
            files() -> "files"
            query() -> "query"
            remove() -> "remove"
            sync() -> "sync"
            deptest() -> "deptest"
            else -> "upgrade"
        }
        val modifiers = listOfNotNull(
            "refresh=${refresh()}".takeIf { refresh() > 0 },
            "sysupgrade=${sysUpgrade()}".takeIf { sysUpgrade() > 0 },
            "clean=${clean()}".takeIf { clean() > 0 },
            "info=${info()}".takeIf { info() > 0 },
            "nodeps=${noDeps()}".takeIf { noDeps() > 0 },
            "list".takeIf { list() },
            "search".takeIf { search() },
            "groups=${groups()}".takeIf { groups() > 0 },
            "quiet".takeIf { quiet() },
            "nosave".takeIf { noSave() },
            "print".takeIf { printOnly() },
            "downloadonly".takeIf { downloadOnly() },
            "needed".takeIf { needed() },
            "noconfirm".takeIf { noConfirm() },
            "verbose".takeIf { verbose() },
            "version-requested".takeIf { versionOperation() },
            "changelog".takeIf { changelog() },
            "cascade".takeIf { cascade() },
            "recursive=${recursive()}".takeIf { recursive() > 0 },
            "unneeded".takeIf { unneeded() },
            "upgrades".takeIf { upgrades() },
            "native".takeIf { nativePackages() },
            color()?.let { "color=$it" },
            dbPath()?.let { "dbpath=$it" },
            root()?.let { "root=$it" },
        )
        Ok("would run $operation over ${targets().size} target(s) [${modifiers.joinToString(", ")}]")
    }

    projection {
        PacmanInputs(
            database(),
            files(),
            query(),
            remove(),
            sync(),
            deptest(),
            upgrade(),
            versionOperation(),
            dbPath(),
            root(),
            verbose(),
            arch(),
            cacheDirs(),
            color(),
            config(),
            confirm(),
            debug(),
            disableDownloadTimeout(),
            disableSandbox(),
            disableSandboxFilesystem(),
            disableSandboxSyscalls(),
            gpgDir(),
            hookDirs(),
            logFile(),
            noConfirm(),
            sysroot(),
            noDeps(),
            printOnly(),
            assumeInstalled(),
            dbOnly(),
            noProgressBar(),
            noScriptlet(),
            printFormat(),
            downloadOnly(),
            asDeps(),
            asExplicit(),
            ignore(),
            ignoreGroup(),
            needed(),
            overwrite(),
            refresh(),
            sysUpgrade(),
            clean(),
            info(),
            list(),
            search(),
            groups(),
            quiet(),
            noSave(),
            explicit(),
            check(),
            foreign(),
            owns(),
            unrequired(),
            regex(),
            changelog(),
            cascade(),
            recursive(),
            unneeded(),
            upgrades(),
            nativePackages(),
            deps(),
            packageFile(),
            machineReadable(),
            targets(),
        )
    }
}

/**
 * What one `pacman` line binds, as values rather than as the handles that read them.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does
 * not name is pinned to its default too.
 */
public data class PacmanInputs(
    val database: Boolean,
    val files: Boolean,
    val query: Boolean,
    val remove: Boolean,
    val sync: Boolean,
    val deptest: Boolean,
    val upgrade: Boolean,
    val versionOperation: Boolean,
    val dbPath: String?,
    val root: String?,
    val verbose: Boolean,
    val arch: String?,
    val cacheDirs: List<String>,
    val color: String?,
    val config: String?,
    val confirm: Boolean,
    val debug: String?,
    val disableDownloadTimeout: Boolean,
    val disableSandbox: Boolean,
    val disableSandboxFilesystem: Boolean,
    val disableSandboxSyscalls: Boolean,
    val gpgDir: String?,
    val hookDirs: List<String>,
    val logFile: String?,
    val noConfirm: Boolean,
    val sysroot: String?,
    val noDeps: Int,
    val printOnly: Boolean,
    val assumeInstalled: List<String>,
    val dbOnly: Boolean,
    val noProgressBar: Boolean,
    val noScriptlet: Boolean,
    val printFormat: String?,
    val downloadOnly: Boolean,
    val asDeps: Boolean,
    val asExplicit: Boolean,
    val ignore: List<String>,
    val ignoreGroup: List<String>,
    val needed: Boolean,
    val overwrite: List<String>,
    val refresh: Int,
    val sysUpgrade: Int,
    val clean: Int,
    val info: Int,
    val list: Boolean,
    val search: Boolean,
    val groups: Int,
    val quiet: Boolean,
    val noSave: Boolean,
    val explicit: Boolean,
    val check: Int,
    val foreign: Boolean,
    val owns: Boolean,
    val unrequired: Int,
    val regex: Boolean,
    val changelog: Boolean,
    val cascade: Boolean,
    val recursive: Int,
    val unneeded: Boolean,
    val upgrades: Boolean,
    val nativePackages: Boolean,
    val deps: Boolean,
    val packageFile: Boolean,
    val machineReadable: Boolean,
    val targets: List<String>,
)

/** `pacman` with no arguments at all: every field at the default the declaration gives it. */
public val NOTHING_BOUND: PacmanInputs = PacmanInputs(
    database = false,
    files = false,
    query = false,
    remove = false,
    sync = false,
    deptest = false,
    upgrade = false,
    versionOperation = false,
    dbPath = null,
    root = null,
    verbose = false,
    arch = null,
    cacheDirs = emptyList(),
    color = null,
    config = null,
    confirm = false,
    debug = null,
    disableDownloadTimeout = false,
    disableSandbox = false,
    disableSandboxFilesystem = false,
    disableSandboxSyscalls = false,
    gpgDir = null,
    hookDirs = emptyList(),
    logFile = null,
    noConfirm = false,
    sysroot = null,
    noDeps = 0,
    printOnly = false,
    assumeInstalled = emptyList(),
    dbOnly = false,
    noProgressBar = false,
    noScriptlet = false,
    printFormat = null,
    downloadOnly = false,
    asDeps = false,
    asExplicit = false,
    ignore = emptyList(),
    ignoreGroup = emptyList(),
    needed = false,
    overwrite = emptyList(),
    refresh = 0,
    sysUpgrade = 0,
    clean = 0,
    info = 0,
    list = false,
    search = false,
    groups = 0,
    quiet = false,
    noSave = false,
    explicit = false,
    check = 0,
    foreign = false,
    owns = false,
    unrequired = 0,
    regex = false,
    changelog = false,
    cascade = false,
    recursive = 0,
    unneeded = false,
    upgrades = false,
    nativePackages = false,
    deps = false,
    packageFile = false,
    machineReadable = false,
    targets = emptyList(),
)
