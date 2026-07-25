package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.CountFlag
import com.fromwau.klap.Flag
import com.fromwau.klap.Ok
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
    val color: Opt<String?>
    val noConfirm: Flag

    group("General options") {
        dbPath = option("--dbpath", "-b", help = "an alternative database location").file().placeholder("path")
        root = option("--root", "-r", help = "an alternative installation root").file().placeholder("path")
        verbose = flag("--verbose", "-v", help = "output paths such as the Root, Conf File, DB Path")
        color = option("--color", help = "when to enable coloring").choice("always", "never", "auto")
        noConfirm = flag("--noconfirm", help = "bypass any and all \"Are you sure?\" messages")
    }

    val noDeps: CountFlag
    val printOnly: Flag

    group("Transaction options (-S, -R, -U)") {
        // pacman's doubled letters are genuinely cumulative ("specify this option twice to skip all
        // dependency checks"), so .count() is the faithful shape, and it clusters like a boolean flag:
        // `-Sdd` counts 2 the same way `-vv` does.
        noDeps = flag("--nodeps", "-d", help = "skip dependency version checks; twice to skip all").count()
        printOnly = flag("--print", "-p", help = "only print the targets instead of performing the operation")
    }

    val downloadOnly: Flag
    val needed: Flag

    group("Upgrade options (-S, -U)") {
        downloadOnly = flag("--downloadonly", "-w", help = "retrieve all packages, but do not install")
        needed = flag("--needed", help = "do not reinstall targets that are already up-to-date")
    }

    val refresh: CountFlag
    val sysUpgrade: CountFlag
    val clean: CountFlag
    val info: CountFlag
    val list: Flag
    val search: Flag
    val groups: Flag
    val quiet: Flag
    val noSave: Flag
    val changelog: Flag
    val cascade: Flag
    val recursive: Flag
    val unneeded: Flag
    val upgrades: Flag
    val nativePackages: Flag

    // KLAP-GAP: real pacman resolves a modifier's meaning against the operation in its cluster (`-c` is
    // --changelog under -Q, --clean under -S); klap has no per-operation namespace, so each contested
    // letter binds one meaning tree-wide (validateDuplicateOptionFlagNames) and every other reading
    // survives only as a long spelling. Real pacman also rejects a modifier that doesn't belong to the
    // selected operation; every flag declared here is legal regardless, since a spec list can't scope
    // one input's validity to another's presence.
    group("Operation modifiers") {
        refresh = flag("--refresh", "-y", help = "download a fresh copy of the master package databases").count()
        sysUpgrade = flag("--sysupgrade", "-u", help = "upgrade all packages that are out-of-date").count()
        clean = flag("--clean", "-c", help = "remove old packages from the cache").count()
        info = flag("--info", "-i", help = "display information on a given package").count()
        list = flag("--list", "-l", help = "list all packages in the specified repositories")
        search = flag("--search", "-s", help = "search each package for names or descriptions matching REGEXP")
        groups = flag("--groups", "-g", help = "display all the members for each package group specified")
        quiet = flag("--quiet", "-q", help = "show less information for certain operations")
        noSave = flag("--nosave", "-n", help = "ignore file backup designations")

        // The readings the contested shorts above displaced. Their long spellings do not collide with
        // anything, so klap can express the OPTION; only the letter is gone.
        changelog = flag("--changelog", help = "view the ChangeLog of a package (-Q; the -c letter is taken)")
        cascade = flag("--cascade", help = "also remove packages that depend on the targets (-R; -c is taken)")
        recursive = flag("--recursive", help = "also remove unneeded dependencies (-R; the -s letter is taken)")
        unneeded = flag("--unneeded", help = "remove targets not required by any package (-R; -u is taken)")
        upgrades = flag("--upgrades", help = "filter to out-of-date packages (-Q; the -u letter is taken)")
        nativePackages = flag("--native", help = "filter to packages in the sync databases (-Q; -n is taken)")
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
            "groups".takeIf { groups() },
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
            "recursive".takeIf { recursive() },
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
            color(),
            noConfirm(),
            noDeps(),
            printOnly(),
            downloadOnly(),
            needed(),
            refresh(),
            sysUpgrade(),
            clean(),
            info(),
            list(),
            search(),
            groups(),
            quiet(),
            noSave(),
            changelog(),
            cascade(),
            recursive(),
            unneeded(),
            upgrades(),
            nativePackages(),
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
    val color: String?,
    val noConfirm: Boolean,
    val noDeps: Int,
    val printOnly: Boolean,
    val downloadOnly: Boolean,
    val needed: Boolean,
    val refresh: Int,
    val sysUpgrade: Int,
    val clean: Int,
    val info: Int,
    val list: Boolean,
    val search: Boolean,
    val groups: Boolean,
    val quiet: Boolean,
    val noSave: Boolean,
    val changelog: Boolean,
    val cascade: Boolean,
    val recursive: Boolean,
    val unneeded: Boolean,
    val upgrades: Boolean,
    val nativePackages: Boolean,
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
    color = null,
    noConfirm = false,
    noDeps = 0,
    printOnly = false,
    downloadOnly = false,
    needed = false,
    refresh = 0,
    sysUpgrade = 0,
    clean = 0,
    info = 0,
    list = false,
    search = false,
    groups = false,
    quiet = false,
    noSave = false,
    changelog = false,
    cascade = false,
    recursive = false,
    unneeded = false,
    upgrades = false,
    nativePackages = false,
    targets = emptyList(),
)
