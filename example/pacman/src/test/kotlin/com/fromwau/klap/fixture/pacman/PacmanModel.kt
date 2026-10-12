package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import kotlin.test.fail

/*
 * A table of the fixture's inputs and of what the installed pacman 7.1.0 documents for each operation, from
 * which the matrix tests generate their cells. PAGES is `pacman -<OP> --help` for each operation, verbatim;
 * an option's value-taking and repeat behaviour was checked with read-only `pacman -Q` lines.
 */

/** A pacman operation, with the targets its usage line requires (`pacman {-R --remove} [options] <package(s)>`). */
internal enum class Op(
    val long: String,
    val short: Char,
    val help: String,
    val requiredTargets: List<String>,
) {
    DATABASE("--database", 'D', "operate on the package database", emptyList()),
    FILES("--files", 'F', "query the files database", emptyList()),
    QUERY("--query", 'Q', "query the package database", emptyList()),
    REMOVE("--remove", 'R', "remove package(s) from the system", listOf("pkg")),
    SYNC("--sync", 'S', "synchronize packages", emptyList()),
    DEPTEST("--deptest", 'T', "check dependencies", emptyList()),
    UPGRADE("--upgrade", 'U', "upgrade or add package(s) from a file or URL", listOf("pkg.pkg.tar.zst")),
    ;

    /** What `pacman -<short> <requiredTargets>` binds, every other input absent. */
    fun record(): PacmanInputs = when (this) {
        DATABASE -> NOTHING_BOUND.copy(database = true)
        FILES -> NOTHING_BOUND.copy(files = true)
        QUERY -> NOTHING_BOUND.copy(query = true)
        REMOVE -> NOTHING_BOUND.copy(remove = true)
        SYNC -> NOTHING_BOUND.copy(sync = true)
        DEPTEST -> NOTHING_BOUND.copy(deptest = true)
        UPGRADE -> NOTHING_BOUND.copy(upgrade = true)
    }.copy(targets = requiredTargets)

    /** This operation's own `--help` page. */
    val page: List<PageEntry> get() = PAGES.getValue(this)

    fun entryFor(long: String): PageEntry? = page.firstOrNull { it.long == long }

    fun entryFor(short: Char): PageEntry? = page.firstOrNull { it.short == short }

    /** Whether pacman counts a doubled letter of [entry] under this operation, as its help says (`-ii`). */
    fun counts(entry: PageEntry): Boolean = entry.short != null && "(-${entry.short}${entry.short}" in entry.help
}

/** One row of a `pacman -<OP> --help` page. */
internal data class PageEntry(
    val short: Char?,
    val long: String,
    val help: String,
)

/** How an input reads the words after its name. */
internal sealed interface Kind {
    data object Flag : Kind

    data object Count : Kind

    /** A value; [choices] null for free text, [bare] what a bare optional-value spelling binds. */
    data class Value(
        val path: Boolean = false,
        val choices: List<String>? = null,
        val multiple: Boolean = false,
        val bare: String? = null,
        val sample: String = if (path) "/tmp" else choices?.first() ?: "x",
    ) : Kind
}

/** One input the fixture declares besides the operations and `-V`, with the help klap shows for it. */
internal data class Input(
    val long: String,
    val short: Char?,
    val help: String,
    val kind: Kind,
    val bind: PacmanInputs.(Any) -> PacmanInputs,
) {
    override fun toString(): String = long
}

private fun flag(long: String, short: Char?, help: String, bind: PacmanInputs.(Boolean) -> PacmanInputs) =
    Input(long, short, help, Kind.Flag) { bind(it as Boolean) }

private fun count(long: String, short: Char?, help: String, bind: PacmanInputs.(Int) -> PacmanInputs) =
    Input(long, short, help, Kind.Count) { bind(it as Int) }

private fun value(
    long: String,
    short: Char?,
    help: String,
    kind: Kind.Value,
    bind: PacmanInputs.(String) -> PacmanInputs,
) = Input(long, short, help, kind) { bind(it as String) }

@Suppress("UNCHECKED_CAST")
private fun values(
    long: String,
    help: String,
    kind: Kind.Value,
    bind: PacmanInputs.(List<String>) -> PacmanInputs,
) = Input(long, null, help, kind.copy(multiple = true)) { bind(it as List<String>) }

private val PATH = Kind.Value(path = true)
private val TEXT = Kind.Value()

/** Every input of `pacmanCli()` other than the operations, `-V` and the targets, as the fixture declares it. */
internal val INPUTS: List<Input> = listOf(
    value("--dbpath", 'b', "set an alternate database location", PATH) { copy(dbPath = it) },
    value("--root", 'r', "set an alternate installation root", PATH) { copy(root = it) },
    flag("--verbose", 'v', "be verbose") { copy(verbose = it) },
    value("--arch", null, "set an alternate architecture", TEXT) { copy(arch = it) },
    values("--cachedir", "set an alternate package cache location", PATH) { copy(cacheDirs = it) },
    value("--color", null, "colorize the output", Kind.Value(choices = listOf("always", "never", "auto"))) {
        copy(color = it)
    },
    value("--config", null, "set an alternate configuration file", PATH) { copy(config = it) },
    // `--confirm` turns `--noconfirm` off again, so alone it binds what no word binds.
    flag("--confirm", null, "always ask for confirmation") { copy(noConfirm = noConfirm && !it) },
    value("--debug", null, "display debug messages", Kind.Value(choices = listOf("1", "2"), bare = "1")) {
        copy(debug = it)
    },
    flag("--disable-download-timeout", null, "use relaxed timeouts for download") {
        copy(disableDownloadTimeout = it)
    },
    flag("--disable-sandbox", null, "disables all sandbox features used for the downloader process") {
        copy(disableSandbox = it)
    },
    flag("--disable-sandbox-filesystem", null, "disables the filesystem part of the downloader process sandbox") {
        copy(disableSandboxFilesystem = it)
    },
    flag("--disable-sandbox-syscalls", null, "disables the syscalls part of the downloader process sandbox") {
        copy(disableSandboxSyscalls = it)
    },
    value("--gpgdir", null, "set an alternate home directory for GnuPG", PATH) { copy(gpgDir = it) },
    values("--hookdir", "set an alternate hook location", PATH) { copy(hookDirs = it) },
    value("--logfile", null, "set an alternate log file", PATH) { copy(logFile = it) },
    flag("--noconfirm", null, "do not ask for any confirmation") { copy(noConfirm = it) },
    value("--sysroot", null, "operate on a mounted guest system (root-only)", PATH) { copy(sysroot = it) },
    count("--nodeps", 'd', "skip dependency version checks (-dd to skip all checks)") { copy(noDeps = it) },
    flag("--print", 'p', "print the targets instead of performing the operation") { copy(printOnly = it) },
    values("--assume-installed", "add a virtual package to satisfy dependencies", TEXT) {
        copy(assumeInstalled = it)
    },
    flag("--dbonly", null, "only modify database entries, not package files") { copy(dbOnly = it) },
    flag("--noprogressbar", null, "do not show a progress bar when downloading files") { copy(noProgressBar = it) },
    flag("--noscriptlet", null, "do not execute the install scriptlet if one exists") { copy(noScriptlet = it) },
    value("--print-format", null, "specify how the targets should be printed", TEXT) { copy(printFormat = it) },
    flag("--downloadonly", 'w', "download packages but do not install/upgrade anything") { copy(downloadOnly = it) },
    flag("--asdeps", null, "install packages as non-explicitly installed") { copy(asDeps = it) },
    flag("--asexplicit", null, "install packages as explicitly installed") { copy(asExplicit = it) },
    values("--ignore", "ignore a package upgrade (can be used more than once)", TEXT) { copy(ignore = it) },
    values("--ignoregroup", "ignore a group upgrade (can be used more than once)", TEXT) { copy(ignoreGroup = it) },
    flag("--needed", null, "do not reinstall up to date packages") { copy(needed = it) },
    values("--overwrite", "overwrite conflicting files (can be used more than once)", TEXT) { copy(overwrite = it) },
    count(
        "--refresh",
        'y',
        "download fresh package databases from the server (-yy to force a refresh even if up to date)",
    ) { copy(refresh = it) },
    count("--sysupgrade", 'u', "upgrade installed packages (-uu enables downgrades)") { copy(sysUpgrade = it) },
    count("--clean", 'c', "remove old packages from cache directory (-cc for all)") { copy(clean = it) },
    count("--info", 'i', "view package information (-ii for backup files or extended information)") {
        copy(info = it)
    },
    flag("--list", 'l', "list the files of a package, or the packages in a repo (-S)") { copy(list = it) },
    flag("--search", 's', "search packages for matching strings") { copy(search = it) },
    count("--groups", 'g', "view all members of a package group (-gg for all groups)") { copy(groups = it) },
    flag("--quiet", 'q', "show less information for query and search") { copy(quiet = it) },
    flag("--nosave", 'n', "remove configuration files") { copy(noSave = it) },
    flag("--explicit", 'e', "list packages explicitly installed [filter]") { copy(explicit = it) },
    count("--check", 'k', "check that package files exist (-kk for file properties)") { copy(check = it) },
    flag("--foreign", 'm', "list installed packages not found in sync db(s) [filter]") { copy(foreign = it) },
    flag("--owns", 'o', "query the package that owns <file>") { copy(owns = it) },
    count(
        "--unrequired",
        't',
        "list packages not (optionally) required by any package (-tt to ignore optdepends) [filter]",
    ) { copy(unrequired = it) },
    flag("--regex", 'x', "enable searching using regular expressions") { copy(regex = it) },
    flag("--changelog", null, "view the changelog of a package (-Q)") { copy(changelog = it) },
    flag("--cascade", null, "remove packages and all packages that depend on them (-R)") { copy(cascade = it) },
    count(
        "--recursive",
        null,
        "remove unnecessary dependencies (-R; twice includes explicitly installed dependencies)",
    ) { copy(recursive = it) },
    flag("--unneeded", null, "remove unneeded packages (-R)") { copy(unneeded = it) },
    flag("--upgrades", null, "list outdated packages (-Q) [filter]") { copy(upgrades = it) },
    flag("--native", null, "list installed packages only found in sync db(s) (-Q) [filter]") {
        copy(nativePackages = it)
    },
    flag("--deps", null, "list packages installed as dependencies (-Q) [filter]") { copy(deps = it) },
    flag("--file", null, "query a package file instead of the database (-Q)") { copy(packageFile = it) },
    flag("--machinereadable", null, "produce machine-readable output (-F)") { copy(machineReadable = it) },
)

/**
 * How klap describes [long] on a line naming no operation yet, where any operation's option may stand: in the words
 * of the first operation's page that lists it.
 */
internal fun unscopedHelp(long: String): String =
    Op.entries.firstNotNullOfOrNull { it.entryFor(long)?.help } ?: input(long).help

/** How klap describes the letter [short] on a line naming no operation yet, as [unscopedHelp] describes a long. */
internal fun unscopedHelp(short: Char): String? = Op.entries.firstNotNullOfOrNull { it.entryFor(short)?.help }

/** Every letter some operation's page lists, in page order. */
internal val PAGE_LETTERS: List<Char> by lazy {
    Op.entries.flatMap { op -> op.page.mapNotNull { it.short } }.distinct()
}

/** Whether a doubled [letter] counts on some operation's page, which a line naming no operation yet may become. */
internal fun countsSomewhere(letter: Char): Boolean =
    Op.entries.any { op -> op.entryFor(letter)?.let(op::counts) == true }

internal fun input(long: String): Input = INPUTS.firstOrNull { it.long == long } ?: fail("no fixture input $long")

internal fun inputFor(short: Char): Input? = INPUTS.firstOrNull { it.short == short }

internal const val VERSION_SHORT_HELP = "display version and exit"

/** `man pacman` on `-h, --help` and `-V, --version`, in the fixture's lower-case style. */
internal const val HELP_TEXT = "display syntax for the given operation"
internal const val VERSION_TEXT = VERSION_SHORT_HELP

/** Every long name either pacman or klap accepts on this line, which an abbreviation must be unique among. */
internal val ALL_LONGS: List<String> =
    INPUTS.map { it.long } + Op.entries.map { it.long } +
        listOf("--help", "--help-all", "--json", "--version", "--completion", "--docs")

/** The shortest prefix of [long] that names it alone, or null when only the whole name does. */
internal fun abbreviationOf(long: String): String? = (3 until long.length)
    .map { long.take(it) }
    .firstOrNull { prefix -> ALL_LONGS.count { it.startsWith(prefix) } == 1 }

/** `pacman -<OP> --help`, row for row. */
internal val PAGES: Map<Op, List<PageEntry>> = mapOf(
        Op.QUERY to listOf(
            PageEntry('b', "--dbpath", "set an alternate database location"),
            PageEntry('c', "--changelog", "view the changelog of a package"),
            PageEntry('d', "--deps", "list packages installed as dependencies [filter]"),
            PageEntry('e', "--explicit", "list packages explicitly installed [filter]"),
            PageEntry('g', "--groups", "view all members of a package group"),
            PageEntry('i', "--info", "view package information (-ii for backup files)"),
            PageEntry('k', "--check", "check that package files exist (-kk for file properties)"),
            PageEntry('l', "--list", "list the files owned by the queried package"),
            PageEntry('m', "--foreign", "list installed packages not found in sync db(s) [filter]"),
            PageEntry('n', "--native", "list installed packages only found in sync db(s) [filter]"),
            PageEntry('o', "--owns", "query the package that owns <file>"),
            PageEntry('p', "--file", "query a package file instead of the database"),
            PageEntry('q', "--quiet", "show less information for query and search"),
            PageEntry('r', "--root", "set an alternate installation root"),
            PageEntry('s', "--search", "search locally-installed packages for matching strings"),
            PageEntry(
                't',
                "--unrequired",
                "list packages not (optionally) required by any package (-tt to ignore optdepends) [filter]",
            ),
            PageEntry('u', "--upgrades", "list outdated packages [filter]"),
            PageEntry('v', "--verbose", "be verbose"),
            PageEntry(null, "--arch", "set an alternate architecture"),
            PageEntry(null, "--cachedir", "set an alternate package cache location"),
            PageEntry(null, "--color", "colorize the output"),
            PageEntry(null, "--config", "set an alternate configuration file"),
            PageEntry(null, "--confirm", "always ask for confirmation"),
            PageEntry(null, "--debug", "display debug messages"),
            PageEntry(null, "--disable-download-timeout", "use relaxed timeouts for download"),
            PageEntry(null, "--disable-sandbox", "disables all sandbox features used for the downloader process"),
            PageEntry(
                null,
                "--disable-sandbox-filesystem",
                "disables the filesystem part of the downloader process sandbox",
            ),
            PageEntry(
                null,
                "--disable-sandbox-syscalls",
                "disables the syscalls part of the downloader process sandbox",
            ),
            PageEntry(null, "--gpgdir", "set an alternate home directory for GnuPG"),
            PageEntry(null, "--hookdir", "set an alternate hook location"),
            PageEntry(null, "--logfile", "set an alternate log file"),
            PageEntry(null, "--noconfirm", "do not ask for any confirmation"),
            PageEntry(null, "--sysroot", "operate on a mounted guest system (root-only)"),
        ),
        Op.SYNC to listOf(
            PageEntry('b', "--dbpath", "set an alternate database location"),
            PageEntry('c', "--clean", "remove old packages from cache directory (-cc for all)"),
            PageEntry('d', "--nodeps", "skip dependency version checks (-dd to skip all checks)"),
            PageEntry('g', "--groups", "view all members of a package group (-gg to view all groups and members)"),
            PageEntry('i', "--info", "view package information (-ii for extended information)"),
            PageEntry('l', "--list", "view a list of packages in a repo"),
            PageEntry('p', "--print", "print the targets instead of performing the operation"),
            PageEntry('q', "--quiet", "show less information for query and search"),
            PageEntry('r', "--root", "set an alternate installation root"),
            PageEntry('s', "--search", "search remote repositories for matching strings"),
            PageEntry('u', "--sysupgrade", "upgrade installed packages (-uu enables downgrades)"),
            PageEntry('v', "--verbose", "be verbose"),
            PageEntry('w', "--downloadonly", "download packages but do not install/upgrade anything"),
            PageEntry(
                'y',
                "--refresh",
                "download fresh package databases from the server (-yy to force a refresh even if up to date)",
            ),
            PageEntry(null, "--arch", "set an alternate architecture"),
            PageEntry(null, "--asdeps", "install packages as non-explicitly installed"),
            PageEntry(null, "--asexplicit", "install packages as explicitly installed"),
            PageEntry(null, "--assume-installed", "add a virtual package to satisfy dependencies"),
            PageEntry(null, "--cachedir", "set an alternate package cache location"),
            PageEntry(null, "--color", "colorize the output"),
            PageEntry(null, "--config", "set an alternate configuration file"),
            PageEntry(null, "--confirm", "always ask for confirmation"),
            PageEntry(null, "--dbonly", "only modify database entries, not package files"),
            PageEntry(null, "--debug", "display debug messages"),
            PageEntry(null, "--disable-download-timeout", "use relaxed timeouts for download"),
            PageEntry(null, "--disable-sandbox", "disables all sandbox features used for the downloader process"),
            PageEntry(
                null,
                "--disable-sandbox-filesystem",
                "disables the filesystem part of the downloader process sandbox",
            ),
            PageEntry(
                null,
                "--disable-sandbox-syscalls",
                "disables the syscalls part of the downloader process sandbox",
            ),
            PageEntry(null, "--gpgdir", "set an alternate home directory for GnuPG"),
            PageEntry(null, "--hookdir", "set an alternate hook location"),
            PageEntry(null, "--ignore", "ignore a package upgrade (can be used more than once)"),
            PageEntry(null, "--ignoregroup", "ignore a group upgrade (can be used more than once)"),
            PageEntry(null, "--logfile", "set an alternate log file"),
            PageEntry(null, "--needed", "do not reinstall up to date packages"),
            PageEntry(null, "--noconfirm", "do not ask for any confirmation"),
            PageEntry(null, "--noprogressbar", "do not show a progress bar when downloading files"),
            PageEntry(null, "--noscriptlet", "do not execute the install scriptlet if one exists"),
            PageEntry(null, "--overwrite", "overwrite conflicting files (can be used more than once)"),
            PageEntry(null, "--print-format", "specify how the targets should be printed"),
            PageEntry(null, "--sysroot", "operate on a mounted guest system (root-only)"),
        ),
        Op.REMOVE to listOf(
            PageEntry('b', "--dbpath", "set an alternate database location"),
            PageEntry('c', "--cascade", "remove packages and all packages that depend on them"),
            PageEntry('d', "--nodeps", "skip dependency version checks (-dd to skip all checks)"),
            PageEntry('n', "--nosave", "remove configuration files"),
            PageEntry('p', "--print", "print the targets instead of performing the operation"),
            PageEntry('r', "--root", "set an alternate installation root"),
            PageEntry(
                's',
                "--recursive",
                "remove unnecessary dependencies (-ss includes explicitly installed dependencies)",
            ),
            PageEntry('u', "--unneeded", "remove unneeded packages"),
            PageEntry('v', "--verbose", "be verbose"),
            PageEntry(null, "--arch", "set an alternate architecture"),
            PageEntry(null, "--assume-installed", "add a virtual package to satisfy dependencies"),
            PageEntry(null, "--cachedir", "set an alternate package cache location"),
            PageEntry(null, "--color", "colorize the output"),
            PageEntry(null, "--config", "set an alternate configuration file"),
            PageEntry(null, "--confirm", "always ask for confirmation"),
            PageEntry(null, "--dbonly", "only modify database entries, not package files"),
            PageEntry(null, "--debug", "display debug messages"),
            PageEntry(null, "--disable-download-timeout", "use relaxed timeouts for download"),
            PageEntry(null, "--disable-sandbox", "disables all sandbox features used for the downloader process"),
            PageEntry(
                null,
                "--disable-sandbox-filesystem",
                "disables the filesystem part of the downloader process sandbox",
            ),
            PageEntry(
                null,
                "--disable-sandbox-syscalls",
                "disables the syscalls part of the downloader process sandbox",
            ),
            PageEntry(null, "--gpgdir", "set an alternate home directory for GnuPG"),
            PageEntry(null, "--hookdir", "set an alternate hook location"),
            PageEntry(null, "--logfile", "set an alternate log file"),
            PageEntry(null, "--noconfirm", "do not ask for any confirmation"),
            PageEntry(null, "--noprogressbar", "do not show a progress bar when downloading files"),
            PageEntry(null, "--noscriptlet", "do not execute the install scriptlet if one exists"),
            PageEntry(null, "--print-format", "specify how the targets should be printed"),
            PageEntry(null, "--sysroot", "operate on a mounted guest system (root-only)"),
        ),
        Op.UPGRADE to listOf(
            PageEntry('b', "--dbpath", "set an alternate database location"),
            PageEntry('d', "--nodeps", "skip dependency version checks (-dd to skip all checks)"),
            PageEntry('p', "--print", "print the targets instead of performing the operation"),
            PageEntry('r', "--root", "set an alternate installation root"),
            PageEntry('v', "--verbose", "be verbose"),
            PageEntry('w', "--downloadonly", "download packages but do not install/upgrade anything"),
            PageEntry(null, "--arch", "set an alternate architecture"),
            PageEntry(null, "--asdeps", "install packages as non-explicitly installed"),
            PageEntry(null, "--asexplicit", "install packages as explicitly installed"),
            PageEntry(null, "--assume-installed", "add a virtual package to satisfy dependencies"),
            PageEntry(null, "--cachedir", "set an alternate package cache location"),
            PageEntry(null, "--color", "colorize the output"),
            PageEntry(null, "--config", "set an alternate configuration file"),
            PageEntry(null, "--confirm", "always ask for confirmation"),
            PageEntry(null, "--dbonly", "only modify database entries, not package files"),
            PageEntry(null, "--debug", "display debug messages"),
            PageEntry(null, "--disable-download-timeout", "use relaxed timeouts for download"),
            PageEntry(null, "--disable-sandbox", "disables all sandbox features used for the downloader process"),
            PageEntry(
                null,
                "--disable-sandbox-filesystem",
                "disables the filesystem part of the downloader process sandbox",
            ),
            PageEntry(
                null,
                "--disable-sandbox-syscalls",
                "disables the syscalls part of the downloader process sandbox",
            ),
            PageEntry(null, "--gpgdir", "set an alternate home directory for GnuPG"),
            PageEntry(null, "--hookdir", "set an alternate hook location"),
            PageEntry(null, "--ignore", "ignore a package upgrade (can be used more than once)"),
            PageEntry(null, "--ignoregroup", "ignore a group upgrade (can be used more than once)"),
            PageEntry(null, "--logfile", "set an alternate log file"),
            PageEntry(null, "--needed", "do not reinstall up to date packages"),
            PageEntry(null, "--noconfirm", "do not ask for any confirmation"),
            PageEntry(null, "--noprogressbar", "do not show a progress bar when downloading files"),
            PageEntry(null, "--noscriptlet", "do not execute the install scriptlet if one exists"),
            PageEntry(null, "--overwrite", "overwrite conflicting files (can be used more than once)"),
            PageEntry(null, "--print-format", "specify how the targets should be printed"),
            PageEntry(null, "--sysroot", "operate on a mounted guest system (root-only)"),
        ),
        Op.DATABASE to listOf(
            PageEntry('b', "--dbpath", "set an alternate database location"),
            PageEntry('k', "--check", "test local database for validity (-kk for sync databases)"),
            PageEntry('q', "--quiet", "suppress output of success messages"),
            PageEntry('r', "--root", "set an alternate installation root"),
            PageEntry('v', "--verbose", "be verbose"),
            PageEntry(null, "--arch", "set an alternate architecture"),
            PageEntry(null, "--asdeps", "mark packages as non-explicitly installed"),
            PageEntry(null, "--asexplicit", "mark packages as explicitly installed"),
            PageEntry(null, "--cachedir", "set an alternate package cache location"),
            PageEntry(null, "--color", "colorize the output"),
            PageEntry(null, "--config", "set an alternate configuration file"),
            PageEntry(null, "--confirm", "always ask for confirmation"),
            PageEntry(null, "--debug", "display debug messages"),
            PageEntry(null, "--disable-download-timeout", "use relaxed timeouts for download"),
            PageEntry(null, "--disable-sandbox", "disables all sandbox features used for the downloader process"),
            PageEntry(
                null,
                "--disable-sandbox-filesystem",
                "disables the filesystem part of the downloader process sandbox",
            ),
            PageEntry(
                null,
                "--disable-sandbox-syscalls",
                "disables the syscalls part of the downloader process sandbox",
            ),
            PageEntry(null, "--gpgdir", "set an alternate home directory for GnuPG"),
            PageEntry(null, "--hookdir", "set an alternate hook location"),
            PageEntry(null, "--logfile", "set an alternate log file"),
            PageEntry(null, "--noconfirm", "do not ask for any confirmation"),
            PageEntry(null, "--sysroot", "operate on a mounted guest system (root-only)"),
        ),
        Op.FILES to listOf(
            PageEntry('b', "--dbpath", "set an alternate database location"),
            PageEntry('l', "--list", "list the files owned by the queried package"),
            PageEntry('q', "--quiet", "show less information for query and search"),
            PageEntry('r', "--root", "set an alternate installation root"),
            PageEntry('v', "--verbose", "be verbose"),
            PageEntry('x', "--regex", "enable searching using regular expressions"),
            PageEntry(
                'y',
                "--refresh",
                "download fresh package databases from the server (-yy to force a refresh even if up to date)",
            ),
            PageEntry(null, "--arch", "set an alternate architecture"),
            PageEntry(null, "--cachedir", "set an alternate package cache location"),
            PageEntry(null, "--color", "colorize the output"),
            PageEntry(null, "--config", "set an alternate configuration file"),
            PageEntry(null, "--confirm", "always ask for confirmation"),
            PageEntry(null, "--debug", "display debug messages"),
            PageEntry(null, "--disable-download-timeout", "use relaxed timeouts for download"),
            PageEntry(null, "--disable-sandbox", "disables all sandbox features used for the downloader process"),
            PageEntry(
                null,
                "--disable-sandbox-filesystem",
                "disables the filesystem part of the downloader process sandbox",
            ),
            PageEntry(
                null,
                "--disable-sandbox-syscalls",
                "disables the syscalls part of the downloader process sandbox",
            ),
            PageEntry(null, "--gpgdir", "set an alternate home directory for GnuPG"),
            PageEntry(null, "--hookdir", "set an alternate hook location"),
            PageEntry(null, "--logfile", "set an alternate log file"),
            PageEntry(null, "--machinereadable", "produce machine-readable output"),
            PageEntry(null, "--noconfirm", "do not ask for any confirmation"),
            PageEntry(null, "--sysroot", "operate on a mounted guest system (root-only)"),
        ),
        Op.DEPTEST to listOf(
            PageEntry('b', "--dbpath", "set an alternate database location"),
            PageEntry('r', "--root", "set an alternate installation root"),
            PageEntry('v', "--verbose", "be verbose"),
            PageEntry(null, "--arch", "set an alternate architecture"),
            PageEntry(null, "--cachedir", "set an alternate package cache location"),
            PageEntry(null, "--color", "colorize the output"),
            PageEntry(null, "--config", "set an alternate configuration file"),
            PageEntry(null, "--confirm", "always ask for confirmation"),
            PageEntry(null, "--debug", "display debug messages"),
            PageEntry(null, "--disable-download-timeout", "use relaxed timeouts for download"),
            PageEntry(null, "--disable-sandbox", "disables all sandbox features used for the downloader process"),
            PageEntry(
                null,
                "--disable-sandbox-filesystem",
                "disables the filesystem part of the downloader process sandbox",
            ),
            PageEntry(
                null,
                "--disable-sandbox-syscalls",
                "disables the syscalls part of the downloader process sandbox",
            ),
            PageEntry(null, "--gpgdir", "set an alternate home directory for GnuPG"),
            PageEntry(null, "--hookdir", "set an alternate hook location"),
            PageEntry(null, "--logfile", "set an alternate log file"),
            PageEntry(null, "--noconfirm", "do not ask for any confirmation"),
            PageEntry(null, "--sysroot", "operate on a mounted guest system (root-only)"),
        ),
)

/** A line with `|` at the cursor, for a failure message. */
internal fun marked(
    line: String,
    cursor: Int,
): String = "`" + line.take(cursor) + "|" + line.drop(cursor) + "`"

/** Runs [check] on every cell and fails once, naming every cell that failed and how many did. */
internal fun <T> checkCells(
    cells: List<T>,
    check: (T) -> Unit,
) {
    if (cells.isEmpty()) fail("no cells to check")
    val failures = cells.mapNotNull { cell ->
        try {
            check(cell)
            null
        } catch (e: AssertionError) {
            e.message
        }
    }
    if (failures.isNotEmpty()) {
        fail("${failures.size} of ${cells.size} cells failed:\n" + failures.joinToString("\n"))
    }
}

internal val FILES: Suggestion = filesAfter()

// --- projection cells ---

/** One argv line and what real pacman makes of it: the record it binds, or null when it refuses the line. */
internal data class ParseCell(
    val argv: List<String>,
    val expected: PacmanInputs?,
    val because: String,
)

/** Asserts [cell]: its line binds the expected record, or is refused where that is null. */
internal fun ParitySuite<PacmanInputs>.check(cell: ParseCell) {
    val argv = cell.argv.toTypedArray()
    val expected = cell.expected
    if (expected == null) {
        rejects(argv = argv, because = cell.because)
    } else {
        binds(argv = argv, expected = expected)
    }
}

/** What [input] binds after [times] occurrences carrying [values] in turn (a flag ignores them). */
private fun Input.bound(
    record: PacmanInputs,
    times: Int = 1,
    values: List<String> = List(times) { (kind as? Kind.Value)?.sample ?: "" },
): PacmanInputs = when (val kind = kind) {
    Kind.Flag -> record.bind(true)
    Kind.Count -> record.bind(times)
    is Kind.Value -> record.bind(if (kind.multiple) values else values.last())
}.withImplied()

/** What the man page says an input implies: --print-format implies --print, --disable-sandbox both of its parts. */
private fun PacmanInputs.withImplied(): PacmanInputs = copy(
    printOnly = printOnly || printFormat != null,
    disableSandboxFilesystem = disableSandboxFilesystem || disableSandbox,
    disableSandboxSyscalls = disableSandboxSyscalls || disableSandbox,
)

/** `-<short>` with [words] and the targets this operation requires. */
internal fun Op.line(vararg words: String): List<String> = listOf("-$short") + words + requiredTargets

/** Every way [input]'s long name is written under [op], each with what pacman binds, for an input on its page. */
internal fun longCells(
    op: Op,
    input: Input,
): List<ParseCell> {
    val record = op.record()
    val why = "pacman -${op.short} --help lists ${input.long}"
    val long = input.long
    val cells = mutableListOf<ParseCell>()
    when (val kind = input.kind) {
        Kind.Flag, Kind.Count -> {
            cells += ParseCell(op.line(long), input.bound(record), why)
            val entry = op.entryFor(long)
            // A doubled letter counts where the page says so (`-ii`); any other flag may repeat (`-Qqq` exits 0).
            if (kind == Kind.Flag || (entry != null && op.counts(entry))) {
                cells += ParseCell(op.line(long, long), input.bound(record, times = 2), "$why, repeated")
            }
        }

        is Kind.Value -> {
            val sample = kind.sample
            for (value in kind.choices ?: listOf(sample)) {
                cells += ParseCell(op.line("$long=$value"), input.bound(record, values = listOf(value)), why)
                val bare = kind.bare
                cells += if (bare == null) {
                    ParseCell(op.line(long, value), input.bound(record, values = listOf(value)), why)
                } else {
                    ParseCell(
                        op.line(long, value),
                        input.bound(record, values = listOf(bare)).copy(targets = listOf(value) + op.requiredTargets),
                        "a bare optional value takes no next word: `pacman -Q --debug 2`: package '2' was not found",
                    )
                }
            }
            kind.bare?.let { bare ->
                val bound = input.bound(record, values = listOf(bare))
                cells += ParseCell(op.line(long), bound, "bare `--debug` prints as much as `--debug=1`")
            }
            if (kind.bare == null) {
                // `--cachedir=/tmp --cachedir /var/tmp` lists both under `pacman -Qv`; a second `--dbpath` wins.
                val (a, b) = kind.choices?.take(2) ?: listOf("/a", "/b")
                cells += ParseCell(
                    op.line("$long=$a", long, b),
                    input.bound(record, times = 2, values = listOf(a, b)),
                    "$why, repeated",
                )
                cells += ParseCell(
                    listOf("-${op.short}") + op.requiredTargets + long,
                    null,
                    "pacman: option '$long' requires an argument",
                )
            }
        }
    }
    abbreviationOf(long)?.let { prefix ->
        val first = cells.first()
        val abbreviated = first.argv.map { if (it.startsWith(long)) prefix + it.drop(long.length) else it }
        cells += first.copy(argv = abbreviated)
    }
    return cells
}

/** Every way the letter [entry] is written under [op], bound to the input pacman's page gives it there. */
internal fun letterCells(
    op: Op,
    entry: PageEntry,
): List<ParseCell> {
    val letter = entry.short ?: return emptyList()
    val input = input(entry.long)
    val record = op.record()
    val why = "pacman -${op.short} --help: -$letter, ${entry.long}"
    val o = op.short
    return when (val kind = input.kind) {
        Kind.Flag, Kind.Count -> listOfNotNull(
            ParseCell(op.line().let { listOf("-$o$letter") + it.drop(1) }, input.bound(record), why),
            ParseCell(op.line("-$letter"), input.bound(record), why),
            ParseCell(listOf("-$o$letter$letter") + op.requiredTargets, input.bound(record, times = 2), "$why, doubled")
                .takeIf { op.counts(entry) },
        )

        is Kind.Value -> {
            val sample = kind.sample
            val bound = input.bound(record, values = listOf(sample))
            listOf(
                ParseCell(listOf("-$o$letter", sample) + op.requiredTargets, bound, why),
                ParseCell(listOf("-$o$letter$sample") + op.requiredTargets, bound, "$why, glued (`pacman -Qb/tmp`)"),
                ParseCell(op.line("-$letter", sample), bound, why),
                ParseCell(op.line("-$letter$sample"), bound, "$why, glued"),
            )
        }
    }
}

/** The letters the fixture declares that [op]'s page does not list, which pacman refuses there. */
internal fun lettersOffPage(op: Op): List<Char> =
    INPUTS.mapNotNull { it.short }.filter { op.entryFor(it) == null }

/** The fixture's long names that [op]'s page does not list. */
internal fun longsOffPage(op: Op): List<Input> = INPUTS.filter { op.entryFor(it.long) == null }

/** Whether the fixture's letter on [entry] means what pacman's page says it means under its operation. */
internal fun PageEntry.agreesWithFixture(): Boolean = short?.let { inputFor(it)?.long == long } ?: true

/** What `pacman -Q` binds alone. */
internal val QUERY = Op.QUERY.record()

/** A `-Q` line with [words] after it. */
internal fun q(vararg words: String): List<String> = listOf("-Q") + words

/** A [ParseCell] for one probed line. */
internal fun cell(
    argv: List<String>,
    expected: PacmanInputs?,
    because: String,
): ParseCell = ParseCell(argv, expected, because)

/** P9a cells: the line binds the record in each [cells] entry, or is refused where the entry holds null. */
internal fun ParitySuite<PacmanInputs>.checkAll(cells: List<ParseCell>) = checkCells(cells) { check(it) }
