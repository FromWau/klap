package com.fromwau.klap.fixture.pacman

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Arg
import com.fromwau.klap.Builtin
import com.fromwau.klap.CliError
import com.fromwau.klap.CommandBuilder
import com.fromwau.klap.ConversionError
import com.fromwau.klap.Help
import com.fromwau.klap.Opt
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.dispatch
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
 * remaining characters are modifiers whose meaning is scoped to it: `-Syu` is sync + refresh + sysupgrade,
 * `-Rns` is remove + nosave + recursive, `-Qi` is query + info. Each operation is a flag-spelled command with
 * its own page's options, so klap reads a cluster the same way.
 */
public fun pacmanCli(): TypedCli<PacmanInputs> = cliOf("pacman") {
    // pacman takes any unambiguous prefix of a long option (`pacman --quer` is --query), but a value only in full:
    // `--color=al` is "invalid argument 'al' for --color".
    abbreviation = Abbreviation.LongOptions
    help = "Package manager utility"
    // `man pacman`: "-V, --version  Display version and exit"; `pacman -V` and `pacman -QV` print the banner.
    version = Version.Of(
        "7.1.0",
        long = "version",
        short = 'V',
        description = "display version and exit",
        text = PACMAN_VERSION_TEXT,
    )
    epilogue = "A target is usually a package name, file name, URL, or a search string."

    // pacman's own `--color <when>` needs the name klap's built-in would claim, and pacman has no --json, --docs or
    // --completion ("unrecognized option"). Nor has it --help-all, which Help.ByCommand does not offer.
    builtins {
        color = Builtin.Off
        json = Builtin.Off
        docs = Builtin.Off
        completion = Builtin.Off
        // `man pacman`: "-h, --help  Display syntax for the given operation".
        help = Help.ByCommand(description = "display syntax for the given operation", short = 'h')
    }

    example("pacman -Syu", "refresh the databases and upgrade every out-of-date package")
    example("pacman -Syyu", "the same, forcing a full database refresh")
    example("pacman -Rns vim", "remove a package, its unneeded deps, and its saved config files")
    example("pacman -Qi firefox", "show information about an installed package")
    example("pacman -Qs editor", "search installed package names and descriptions")

    // The options every operation's page lists, so they bind wherever they stand.
    // A path pacman resolves before it starts, so an empty one is refused ("failed to resolve path ''").
    fun Opt<String?>.path(): Opt<String?> = file().validate("must not be empty") { it.isNotEmpty() }

    val dbPath = globalOption(short = 'b', long = "dbpath", help = "set an alternate database location")
        .path()
        .placeholder("path")
    val root = globalOption(short = 'r', long = "root", help = "set an alternate installation root")
        .path()
        .placeholder("path")
    val verbose = globalFlag(short = 'v', long = "verbose", help = "be verbose")
    val arch = globalOption(long = "arch", help = "set an alternate architecture").placeholder("arch")
    val cacheDirs = globalOption(long = "cachedir", help = "set an alternate package cache location")
        .path()
        .placeholder("dir")
        .multiple()
    val color = globalOption(long = "color", help = "colorize the output").choice("always", "never", "auto")
    val config = globalOption(long = "config", help = "set an alternate configuration file")
        .path()
        .placeholder("path")
    // pacman reads the attached level as a decimal number, so `--debug=01` and `--debug=+2` are levels 1 and 2,
    // and only those two are valid (`--debug=3` is "not a valid debug level"); the bare form is level 1. Every
    // occurrence is kept, as the highest level given wins (`--debug=2 --debug` prints level-2 lines).
    val debug = globalOption(long = "debug", help = "display debug messages")
        .convert { typed ->
            when (DEBUG_LEVEL.matchEntire(typed)?.groupValues?.get(1)?.toIntOrNull()) {
                1 -> Ok("1")
                2 -> Ok("2")
                else -> Err(ConversionError.NotOneOf(listOf("1", "2")))
            }
        }
        .completeWith { candidates(listOf("1", "2")) }
        .optionalValue("1")
        .multiple()
    val disableDownloadTimeout = globalFlag(
        long = "disable-download-timeout",
        help = "use relaxed timeouts for download",
    )
    val disableSandbox = globalFlag(
        long = "disable-sandbox",
        help = "disables all sandbox features used for the downloader process",
    )
    val disableSandboxFilesystem = globalFlag(
        long = "disable-sandbox-filesystem",
        help = "disables the filesystem part of the downloader process sandbox",
    )
    val disableSandboxSyscalls = globalFlag(
        long = "disable-sandbox-syscalls",
        help = "disables the syscalls part of the downloader process sandbox",
    )
    val gpgDir = globalOption(long = "gpgdir", help = "set an alternate home directory for GnuPG")
        .path()
        .placeholder("path")
    val hookDirs = globalOption(long = "hookdir", help = "set an alternate hook location")
        .path()
        .placeholder("dir")
        .multiple()
    val logFile = globalOption(long = "logfile", help = "set an alternate log file").file().placeholder("path")
    // The man page: "--confirm  Cancels the effects of a previous --noconfirm", so the two are one setting and the
    // later one given wins.
    val noConfirm = globalFlag(long = "noconfirm", help = "do not ask for any confirmation")
        .negatable(long = "confirm", default = false, help = "always ask for confirmation")
    val sysroot = globalOption(long = "sysroot", help = "operate on a mounted guest system (root-only)")
        .path()
        .placeholder("dir")

    // What every operation's record holds from the globals.
    fun ValueScope.globals(base: PacmanInputs): PacmanInputs = base.copy(
        dbPath = dbPath(),
        root = root(),
        verbose = verbose(),
        arch = arch(),
        cacheDirs = cacheDirs(),
        color = color(),
        config = config(),
        debug = debug().maxByOrNull { it.toInt() },
        disableDownloadTimeout = disableDownloadTimeout(),
        disableSandbox = disableSandbox(),
        // The man page: --disable-sandbox is "equivalent to jointly specifying" both of these.
        disableSandboxFilesystem = disableSandboxFilesystem() || disableSandbox(),
        disableSandboxSyscalls = disableSandboxSyscalls() || disableSandbox(),
        gpgDir = gpgDir(),
        hookDirs = hookDirs(),
        logFile = logFile(),
        noConfirm = noConfirm(),
        sysroot = sysroot(),
    )

    // The options of pacman's transaction and upgrade pages, declared on each operation whose page lists them.
    fun CommandBuilder.noDeps() = flag(
        short = 'd',
        long = "nodeps",
        help = "skip dependency version checks (-dd to skip all checks)",
    ).count()

    fun CommandBuilder.printOnly() =
        flag(short = 'p', long = "print", help = "print the targets instead of performing the operation")

    fun CommandBuilder.assumeInstalled() =
        option(long = "assume-installed", help = "add a virtual package to satisfy dependencies")
            .placeholder("package=version")
            .multiple()

    fun CommandBuilder.dbOnly() = flag(long = "dbonly", help = "only modify database entries, not package files")

    fun CommandBuilder.noProgressBar() =
        flag(long = "noprogressbar", help = "do not show a progress bar when downloading files")

    fun CommandBuilder.noScriptlet() =
        flag(long = "noscriptlet", help = "do not execute the install scriptlet if one exists")

    fun CommandBuilder.printFormat() =
        option(long = "print-format", help = "specify how the targets should be printed").placeholder("string")

    fun CommandBuilder.downloadOnly() = flag(
        short = 'w',
        long = "downloadonly",
        help = "download packages but do not install/upgrade anything",
    )

    fun CommandBuilder.asDeps(help: String = "install packages as non-explicitly installed") =
        flag(long = "asdeps", help = help)

    fun CommandBuilder.asExplicit(help: String = "install packages as explicitly installed") =
        flag(long = "asexplicit", help = help)

    fun CommandBuilder.ignore() =
        option(long = "ignore", help = "ignore a package upgrade (can be used more than once)")
            .placeholder("pkg")
            .multiple()

    fun CommandBuilder.ignoreGroup() =
        option(long = "ignoregroup", help = "ignore a group upgrade (can be used more than once)")
            .placeholder("grp")
            .multiple()

    fun CommandBuilder.needed() = flag(long = "needed", help = "do not reinstall up to date packages")

    fun CommandBuilder.overwrite() =
        option(long = "overwrite", help = "overwrite conflicting files (can be used more than once)")
            .placeholder("glob")
            .multiple()

    fun CommandBuilder.quiet(help: String = "show less information for query and search") =
        flag(short = 'q', long = "quiet", help = help)

    fun CommandBuilder.refresh() = flag(
        short = 'y',
        long = "refresh",
        help = "download fresh package databases from the server (-yy to force a refresh even if up to date)",
    ).count()

    // The man page: --print-format "Implies --print".
    fun printsOnly(print: Boolean, format: String?): Boolean = print || format != null

    // The man page: several packages, groups or globs "can be specified by separating them with a comma".
    fun List<String>.commaSeparated(): List<String> = flatMap { it.split(',') }

    fun CommandBuilder.targets() = argument("target", "package name, file name, URL, or search string")

    fun CommandBuilder.stub(operation: String, targets: Arg<List<String>>) =
        action<String>(human = { it }) { Ok("would run $operation over ${targets().size} target(s)") }

    val databaseOperation = command(short = 'D', long = "database") {
        help = "operate on the package database"
        val check = flag(
            short = 'k',
            long = "check",
            help = "test local database for validity (-kk for sync databases)",
        ).count()
        val quiet = quiet(help = "suppress output of success messages")
        val asDeps = asDeps(help = "mark packages as non-explicitly installed")
        val asExplicit = asExplicit(help = "mark packages as explicitly installed")
        val targets = targets().multiple()
        stub("database", targets)
        projection {
            globals(NOTHING_BOUND).copy(
                database = true,
                check = check(),
                quiet = quiet(),
                asDeps = asDeps(),
                asExplicit = asExplicit(),
                targets = targets(),
            )
        }
    }

    val filesOperation = command(short = 'F', long = "files") {
        help = "query the files database"
        val list = flag(short = 'l', long = "list", help = "list the files owned by the queried package")
        val quiet = quiet()
        val regex = flag(short = 'x', long = "regex", help = "enable searching using regular expressions")
        val refresh = refresh()
        val machineReadable = flag(long = "machinereadable", help = "produce machine-readable output")
        val targets = targets().multiple()
        stub("files", targets)
        projection {
            globals(NOTHING_BOUND).copy(
                files = true,
                list = list(),
                quiet = quiet(),
                regex = regex(),
                refresh = refresh(),
                machineReadable = machineReadable(),
                targets = targets(),
            )
        }
    }

    val queryOperation = command(short = 'Q', long = "query") {
        help = "query the package database"
        val changelog = flag(short = 'c', long = "changelog", help = "view the changelog of a package")
        val deps = flag(short = 'd', long = "deps", help = "list packages installed as dependencies [filter]")
        val explicit = flag(short = 'e', long = "explicit", help = "list packages explicitly installed [filter]")
        val groups = flag(short = 'g', long = "groups", help = "view all members of a package group")
        val info = flag(short = 'i', long = "info", help = "view package information (-ii for backup files)").count()
        val check = flag(
            short = 'k',
            long = "check",
            help = "check that package files exist (-kk for file properties)",
        ).count()
        val list = flag(short = 'l', long = "list", help = "list the files owned by the queried package")
        val foreign = flag(
            short = 'm',
            long = "foreign",
            help = "list installed packages not found in sync db(s) [filter]",
        )
        val nativePackages = flag(
            short = 'n',
            long = "native",
            help = "list installed packages only found in sync db(s) [filter]",
        )
        val owns = flag(short = 'o', long = "owns", help = "query the package that owns <file>")
        val packageFile = flag(short = 'p', long = "file", help = "query a package file instead of the database")
        val quiet = quiet()
        val search = flag(
            short = 's',
            long = "search",
            help = "search locally-installed packages for matching strings",
        )
        val unrequired = flag(
            short = 't',
            long = "unrequired",
            help = "list packages not (optionally) required by any package (-tt to ignore optdepends) [filter]",
        ).count()
        val upgrades = flag(short = 'u', long = "upgrades", help = "list outdated packages [filter]")
        val targets = targets().multiple()

        // pacman refuses these modifier pairs together ("invalid option: '--search' and '--info' may not be used
        // together").
        validateInputs {
            val given = mapOf(
                "--changelog" to changelog(),
                "--deps" to deps(),
                "--explicit" to explicit(),
                "--groups" to groups(),
                "--info" to (info() > 0),
                "--check" to (check() > 0),
                "--list" to list(),
                "--foreign" to foreign(),
                "--native" to nativePackages(),
                "--owns" to owns(),
                "--file" to packageFile(),
                "--search" to search(),
                "--unrequired" to (unrequired() > 0),
                "--upgrades" to upgrades(),
            )
            QUERY_CONFLICTS
                .firstOrNull { (a, b) -> given.getValue(a) && given.getValue(b) }
                ?.let { (a, b) -> CliError.MutuallyExclusive(listOf(a, b)) }
        }
        stub("query", targets)
        projection {
            globals(NOTHING_BOUND).copy(
                query = true,
                changelog = changelog(),
                deps = deps(),
                explicit = explicit(),
                groups = if (groups()) 1 else 0,
                info = info(),
                check = check(),
                list = list(),
                foreign = foreign(),
                nativePackages = nativePackages(),
                owns = owns(),
                packageFile = packageFile(),
                quiet = quiet(),
                search = search(),
                unrequired = unrequired(),
                upgrades = upgrades(),
                targets = targets(),
            )
        }
    }

    val removeOperation = command(short = 'R', long = "remove") {
        help = "remove package(s) from the system"
        val cascade = flag(short = 'c', long = "cascade", help = "remove packages and all packages that depend on them")
        val noDeps = noDeps()
        val noSave = flag(short = 'n', long = "nosave", help = "remove configuration files")
        val printOnly = printOnly()
        val recursive = flag(
            short = 's',
            long = "recursive",
            help = "remove unnecessary dependencies (-ss includes explicitly installed dependencies)",
        ).count()
        val unneeded = flag(short = 'u', long = "unneeded", help = "remove unneeded packages")
        val assumeInstalled = assumeInstalled()
        val dbOnly = dbOnly()
        val noProgressBar = noProgressBar()
        val noScriptlet = noScriptlet()
        val printFormat = printFormat()
        // `pacman {-R --remove} [options] <package(s)>`: a target is required.
        val targets = targets().multiple(min = 1)
        stub("remove", targets)
        projection {
            globals(NOTHING_BOUND).copy(
                remove = true,
                cascade = cascade(),
                noDeps = noDeps(),
                noSave = noSave(),
                printOnly = printsOnly(printOnly(), printFormat()),
                recursive = recursive(),
                unneeded = unneeded(),
                assumeInstalled = assumeInstalled(),
                dbOnly = dbOnly(),
                noProgressBar = noProgressBar(),
                noScriptlet = noScriptlet(),
                printFormat = printFormat(),
                targets = targets(),
            )
        }
    }

    val syncOperation = command(short = 'S', long = "sync") {
        help = "synchronize packages"
        val clean = flag(
            short = 'c',
            long = "clean",
            help = "remove old packages from cache directory (-cc for all)",
        ).count()
        val noDeps = noDeps()
        val groups = flag(
            short = 'g',
            long = "groups",
            help = "view all members of a package group (-gg to view all groups and members)",
        ).count()
        val info = flag(short = 'i', long = "info", help = "view package information (-ii for extended information)")
            .count()
        val list = flag(short = 'l', long = "list", help = "view a list of packages in a repo")
        val printOnly = printOnly()
        val quiet = quiet()
        val search = flag(short = 's', long = "search", help = "search remote repositories for matching strings")
        val sysUpgrade = flag(
            short = 'u',
            long = "sysupgrade",
            help = "upgrade installed packages (-uu enables downgrades)",
        ).count()
        val downloadOnly = downloadOnly()
        val refresh = refresh()
        val asDeps = asDeps()
        val asExplicit = asExplicit()
        val assumeInstalled = assumeInstalled()
        val dbOnly = dbOnly()
        val ignore = ignore()
        val ignoreGroup = ignoreGroup()
        val needed = needed()
        val noProgressBar = noProgressBar()
        val noScriptlet = noScriptlet()
        val overwrite = overwrite()
        val printFormat = printFormat()
        val targets = targets().multiple()
        stub("sync", targets)
        projection {
            globals(NOTHING_BOUND).copy(
                sync = true,
                clean = clean(),
                noDeps = noDeps(),
                groups = groups(),
                info = info(),
                list = list(),
                printOnly = printsOnly(printOnly(), printFormat()),
                quiet = quiet(),
                search = search(),
                sysUpgrade = sysUpgrade(),
                downloadOnly = downloadOnly(),
                refresh = refresh(),
                asDeps = asDeps(),
                asExplicit = asExplicit(),
                assumeInstalled = assumeInstalled(),
                dbOnly = dbOnly(),
                ignore = ignore().commaSeparated(),
                ignoreGroup = ignoreGroup().commaSeparated(),
                needed = needed(),
                noProgressBar = noProgressBar(),
                noScriptlet = noScriptlet(),
                overwrite = overwrite().commaSeparated(),
                printFormat = printFormat(),
                targets = targets(),
            )
        }
    }

    val deptestOperation = command(short = 'T', long = "deptest") {
        help = "check dependencies"
        val targets = targets().multiple()
        stub("deptest", targets)
        projection { globals(NOTHING_BOUND).copy(deptest = true, targets = targets()) }
    }

    val upgradeOperation = command(short = 'U', long = "upgrade") {
        help = "upgrade or add package(s) from a file or URL"
        val noDeps = noDeps()
        val printOnly = printOnly()
        val downloadOnly = downloadOnly()
        val asDeps = asDeps()
        val asExplicit = asExplicit()
        val assumeInstalled = assumeInstalled()
        val dbOnly = dbOnly()
        val ignore = ignore()
        val ignoreGroup = ignoreGroup()
        val needed = needed()
        val noProgressBar = noProgressBar()
        val noScriptlet = noScriptlet()
        val overwrite = overwrite()
        val printFormat = printFormat()
        // `pacman {-U --upgrade} [options] <file(s)>`: a target is required.
        val targets = targets().multiple(min = 1)
        stub("upgrade", targets)
        projection {
            globals(NOTHING_BOUND).copy(
                upgrade = true,
                noDeps = noDeps(),
                printOnly = printsOnly(printOnly(), printFormat()),
                downloadOnly = downloadOnly(),
                asDeps = asDeps(),
                asExplicit = asExplicit(),
                assumeInstalled = assumeInstalled(),
                dbOnly = dbOnly(),
                ignore = ignore().commaSeparated(),
                ignoreGroup = ignoreGroup().commaSeparated(),
                needed = needed(),
                noProgressBar = noProgressBar(),
                noScriptlet = noScriptlet(),
                overwrite = overwrite().commaSeparated(),
                printFormat = printFormat(),
                targets = targets(),
            )
        }
    }

    // Bare `pacman`, or a line of globals alone: "error: no operation specified (use -h for help)".
    validateInputs { CliError.Usage("no operation specified (use -h for help)") }
    action<String>(human = { it }) { Ok("") }
    dispatch(
        databaseOperation,
        filesOperation,
        queryOperation,
        removeOperation,
        syncOperation,
        deptestOperation,
        upgradeOperation,
        projection { globals(NOTHING_BOUND) },
    )
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
    val dbPath: String?,
    val root: String?,
    val verbose: Boolean,
    val arch: String?,
    val cacheDirs: List<String>,
    val color: String?,
    val config: String?,
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
    dbPath = null,
    root = null,
    verbose = false,
    arch = null,
    cacheDirs = emptyList(),
    color = null,
    config = null,
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

/** A decimal number as pacman's strtol reads it: leading blanks, an optional plus, then digits only. */
private val DEBUG_LEVEL = Regex("""\s*\+?([0-9]+)""")

/** The -Q modifier pairs pacman 7.1.0 refuses together, found by running every pair as `pacman -Q <a> <b>`. */
private val QUERY_CONFLICTS = listOf(
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

/**
 * What `pacman --version` prints for pacman 7.1.0, the version installed when it was pinned
 * (2026-10-10).
 */
internal val PACMAN_VERSION_TEXT: String = """
 .--.                  Pacman v7.1.0 - libalpm v16.0.1
/ _.-' .-.  .-.  .-.   Copyright (C) 2006-2025 Pacman Development Team
\  '-. '-'  '-'  '-'   Copyright (C) 2002-2006 Judd Vinet
 '--'
                       This program may be freely redistributed under
                       the terms of the GNU General Public License.
"""
