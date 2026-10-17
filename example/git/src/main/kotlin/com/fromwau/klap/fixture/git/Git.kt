package com.fromwau.klap.fixture.git

import com.fromwau.kern.result.Ok
import com.fromwau.klap.Abbreviation
import com.fromwau.klap.Builtin
import com.fromwau.klap.Help
import com.fromwau.klap.Opt
import com.fromwau.klap.ShortOptions
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.Version
import com.fromwau.klap.cliOf
import com.fromwau.klap.dispatch
import com.fromwau.klap.projection

/**
 * git 2.56.0, reproduced as a command-line surface only (every action is a stub).
 *
 * Real usage:
 *   git [-v | --version] [-h | --help] [-C <path>] [-c <name>=<value>]
 *       [--exec-path[=<path>]] [-p | --paginate | -P | --no-pager]
 *       [--git-dir=<path>] [--work-tree=<path>] [--namespace=<name>] [--bare]
 *       [--no-replace-objects] [--no-optional-locks]
 *       <command> [<args>]
 *
 * Covered subcommands, as git 2.56.0 reads them; help in git's own words: commands from `git help -a`, root
 * options from git(1), remote's children from git-remote(1), command options from `git <cmd> -h` (log's -h
 * lists none of the options below, so log's keep the fixture's words):
 *   git status [-s] [-b]
 *   git add    [-n] [-v] [-f] [-p] [-u] [-A] [-N] [--chmod (+|-)x] [--] <pathspec>...   (alias: stage)
 *   git commit [-a] [-m <msg>]... [-F <file>] [(-c | -C) <commit>] [--amend] [--author=<a>] [-S[<keyid>]]
 *              [--] [<pathspec>...]
 *   git log    [-n <count>] [-p] [--oneline] [--[no-]decorate] [<revision-range>] [[--] <path>...]
 *   git remote [-v | --[no-]verbose]
 *   git remote add [-f] [--[no-]tags] [-t <branch>] [-m <master>] [--mirror=<fetch|push>] <name> <url>
 *   git remote remove <name>                                                            (alias: rm)
 *   git var    (-l | <variable>)                                                        (hidden)
 */
public fun gitCli(): TypedCli<GitInputs> = cliOf("git") {
    // A command's long options abbreviate (`git commit --am` reaches `--amend`); the root's own segment reads each
    // word whole, so `git -Csub`, `git -pP` and `git --git-d=.git` are unknown options there.
    abbreviation = Abbreviation.Options
    ownOptions(shortOptions = ShortOptions.Alone, abbreviation = Abbreviation.None)
    help = "the stupid content tracker"
    // `git -v status` prints the version, while `git add -v` is add's verbose and `git status --version` is refused.
    version = Version.Of(
        "2.56.0",
        long = "version",
        short = 'v',
        description = "Prints the Git suite version that the git program came from",
        text = GIT_VERSION_TEXT,
        rootOnly = true,
    )
    epilogue = "See 'git help <command>' to read about a specific subcommand."
    // `git -h` and `git status -h` print the usage: git's `-h` is its help. git has none of klap's other built-ins.
    builtins {
        help = Help.ByCommand(short = 'h')
        json = Builtin.Off
        color = Builtin.Off
        completion = Builtin.Off
        docs = Builtin.Off
    }

    example("git -C /tmp/repo status --short", "a root option before the subcommand")
    example("git remote add origin https://example.com/x.git", "two levels of subcommand")
    example("git commit -m subject -m body --amend", "a repeatable option plus a flag")
    example("git log -n 5 --oneline main..HEAD", "a short option with a value")

    // The root's options bind before the first subcommand only, so their letters are free below it: `git -p log`
    // paginates and `git log -p` shows patches; `git commit -C HEAD` reuses a message.
    val directory = option(
        short = 'C',
        help = "Run as if git was started in <path> instead of the current working directory",
    )
        .file()
        .multiple()

    val config = option(short = 'c', help = "Pass a configuration parameter to the command").multiple()

    val paginate = flag(
        short = 'p',
        long = "paginate",
        help = "Pipe all output into less (or if set, \$PAGER) if standard output is a terminal",
    ).negatable(short = 'P', long = "no-pager", help = "Do not pipe Git output into a pager")

    val gitDir = option(long = "git-dir", help = "Set the path to the repository (\".git\" directory)").file()
    val workTree = option(long = "work-tree", help = "Set the path to the working tree").file()
    val namespace = option(long = "namespace", help = "Set the Git namespace")
    val bare = flag(long = "bare", help = "Treat the repository as a bare repository")

    // A bare `--exec-path` binds the default below and routes on, where real git prints the path and exits without
    // running the command: an app acts on the bare value before it dispatches.
    val execPath = option(long = "exec-path", help = "Path to wherever your core Git programs are installed")
        .file()
        .optionalValue("/usr/lib/git-core")

    // Negative-only in git: there is no `--replace-objects`, so each is a plain flag spelled with its `no-`.
    val noReplaceObjects =
        flag(long = "no-replace-objects", help = "Do not use replacement refs to replace Git objects")
    val noOptionalLocks =
        flag(long = "no-optional-locks", help = "Do not perform optional operations that require locks")

    fun ValueScope.globals() = GitGlobals(
        directory(), config(), paginate(), gitDir(), workTree(),
        namespace(), bare(), execPath(), noReplaceObjects(), noOptionalLocks(),
    )

    val status = command("status") {
        help = "Show the working tree status"
        val short = flag(short = 's', long = "short", help = "show status concisely")
        val branch = flag(short = 'b', long = "branch", help = "show branch information")

        action {
            val where = directory().lastOrNull() ?: "."
            Ok(
                "would show status in $where (short=${short()}, " +
                    "branch=${branch()}, bare=${bare()})",
            )
        }

        projection { GitInputs.Status(globals(), short(), branch()) }
    }

    val add = command("add") {
        help = "Add file contents to the index"
        // `git stage` ships as a synonym for `git add` (its own -h prints git-add's usage).
        aliases = listOf("stage")

        val dryRun = flag(short = 'n', long = "dry-run", help = "dry run").negatable(default = false)
        val verbose = flag(short = 'v', long = "verbose", help = "be verbose").negatable(default = false)
        val force = flag(short = 'f', long = "force", help = "allow adding otherwise ignored files")
        val patch = flag(short = 'p', long = "patch", help = "select hunks interactively")
        val update = flag(short = 'u', long = "update", help = "update tracked files")
        val addAll = flag(short = 'A', long = "all", help = "add changes from all tracked and untracked files")
        val intentToAdd = flag(
            short = 'N',
            long = "intent-to-add",
            help = "record only the fact that the path will be added later",
        )
        val ignoreErrors = flag(
            long = "ignore-errors",
            help = "just skip files which cannot be added because of errors",
        )

        // Real git accepts `git add --chmod -x file`: parse-options hands a required-value option the next
        // token whatever it looks like (verified against 2.56.0), so the space-separated `-x` binds as the
        // value here too, same as the attached `--chmod=-x` form.
        val chmod = option(long = "chmod", help = "override the executable bit of the listed files")
            .choice("+x", "-x")

        val pathspecFromFile = option(long = "pathspec-from-file", help = "read pathspec from file").file()

        // git's usage line writes `<pathspec>...` as mandatory, but real `git add` with zero operands
        // exits 0 with "Nothing specified, nothing added." (verified against 2.56.0), so min = 0 is the
        // faithful arity and the action carries the hint. Bracketing in the rendered usage line is keyed
        // on that min alone (internal/render/Help.kt), so klap prints `[pathspec...]` where git's own
        // -h prints `<pathspec>...`.
        val pathspec = argument("pathspec", "paths to add to the index").file().multiple()

        action {
            val paths = pathspec()
            val opts = listOfNotNull(
                "-n".takeIf { dryRun() },
                "-v".takeIf { verbose() },
                "-f".takeIf { force() },
                "-p".takeIf { patch() },
                "-u".takeIf { update() },
                "-A".takeIf { addAll() },
                "-N".takeIf { intentToAdd() },
                "--ignore-errors".takeIf { ignoreErrors() },
                chmod()?.let { "--chmod=$it" },
                pathspecFromFile()?.let { "--pathspec-from-file=$it" },
            )
            if (paths.isEmpty()) Ok("Nothing specified, nothing added.")
            else Ok("would add ${paths.size} path(s) ${opts.joinToString(" ")}")
        }

        projection {
            GitInputs.Add(
                globals(), dryRun(), verbose(), force(), patch(), update(), addAll(), intentToAdd(),
                ignoreErrors(), chmod(), pathspecFromFile(), pathspec(),
            )
        }
    }

    val commit = command("commit") {
        help = "Record changes to the repository"
        // group(...) returns Unit, so a holder declared inside it needs the lateinit dance the README
        // documents; the block runs synchronously, so these are set before any action runs.
        val message: Opt<List<String>>
        val messageFile: Opt<String?>
        val author: Opt<String?>
        val date: Opt<String?>
        val reeditMessage: Opt<String?>
        val reuseMessage: Opt<String?>

        group("Commit message options") {
            message = option(short = 'm', long = "message", help = "commit message").multiple()
            messageFile = option(short = 'F', long = "file", help = "read message from file").file()
            author = option(long = "author", help = "override author for commit")
            date = option(long = "date", help = "override date for commit")
            reeditMessage = option(
                short = 'c',
                long = "reedit-message",
                help = "reuse and edit message from specified commit",
            )
            reuseMessage = option(short = 'C', long = "reuse-message", help = "reuse message from specified commit")
        }

        val commitAll = flag(short = 'a', long = "all", help = "commit all changed files")
        val amend = flag(long = "amend", help = "amend previous commit")
        val edit = flag(short = 'e', long = "edit", help = "force edit of commit").negatable(default = true)
        val signoff = flag(
            short = 's',
            long = "signoff",
            help = "add a Signed-off-by trailer",
        ).negatable(default = false)
        val allowEmpty = flag(long = "allow-empty", help = "allow an empty commit")
        val dryRun = flag(long = "dry-run", help = "show what would be committed")
        val quiet = flag(short = 'q', long = "quiet", help = "suppress summary after successful commit")

        val cleanup = option(long = "cleanup", help = "how to strip spaces and #comments from message")
            .choice("strip", "whitespace", "verbatim", "scissors", "default")

        val verify = flag(long = "verify", help = "opposite of --no-verify")
            .negatable(short = 'n', long = "no-verify", help = "bypass pre-commit and commit-msg hooks")

        // `-S[<keyid>]` / `--gpg-sign[=<key-id>]` is optional-value, and the short spelling
        // matches real git exactly here — its own docs say the key-id "must be stuck to the option without
        // a space" (`-Sabc`, no separate token), which is precisely what `.optionalValue()`'s attached form
        // does for both spellings. `git commit -S -m x` (sign with the default key) still binds "default"
        // and leaves `-m x` alone, unaffected. git's `-u[<mode>]` is the same shape and stays unexpressed.
        //
        // KLAP-GAP: real git also has `--no-gpg-sign` alongside `-S`/`--gpg-sign[=<key-id>]` (verified against
        // 2.56.0) — the same negatable-plus-optional-value shape `--decorate` and `--preserve-root` face,
        // and klap cannot hold both on one holder here either. Unlike those two, this one took the value
        // form and lost `--no-gpg-sign`: the trade goes the other way because picking WHICH key signs
        // (`-S`/`-S<keyid>`) is what git users actually reach for on this option, where `--decorate` and
        // `--preserve-root` are reached for by their bare negative spelling.
        val gpgSign = option(short = 'S', long = "gpg-sign", help = "GPG sign commit")
            .optionalValue("default")

        // `git commit [--] [<pathspec>...]` takes zero or more paths, and zero is the normal case.
        // Expressed exactly: multiple() defaults to min = 0, and bindPositionals binds the empty slice
        // (internal/parse/Parser.kt), so `git commit -m x` binds [] and `git commit -m x a.txt b.txt`
        // binds both paths.
        val pathspec = argument("pathspec", "paths to commit (git accepts any number)").file().multiple()

        action {
            val subject = message().firstOrNull()
                ?: messageFile()?.let { "<from $it>" }
                ?: "<editor>"
            val opts = listOfNotNull(
                "-a".takeIf { commitAll() },
                "--amend".takeIf { amend() },
                "--no-edit".takeIf { !edit() },
                "-s".takeIf { signoff() },
                "--allow-empty".takeIf { allowEmpty() },
                "--dry-run".takeIf { dryRun() },
                "-q".takeIf { quiet() },
                "--no-verify".takeIf { !verify() },
                gpgSign()?.let { "-S=$it" },
                cleanup()?.let { "--cleanup=$it" },
                author()?.let { "--author=$it" },
                date()?.let { "--date=$it" },
                reeditMessage()?.let { "--reedit-message=$it" },
                reuseMessage()?.let { "--reuse-message=$it" },
            ) + pathspec()
            Ok("would commit '$subject' (${message().size} message part(s)) ${opts.joinToString(" ")}")
        }

        projection {
            GitInputs.Commit(
                globals(), message(), messageFile(), author(), date(), reeditMessage(), reuseMessage(), commitAll(),
                amend(), edit(), signoff(), allowEmpty(), dryRun(), quiet(), cleanup(), verify(), gpgSign(),
                pathspec(),
            )
        }
    }

    val log = command("log") {
        help = "Show commit logs"
        val namedMaxCount = option(short = 'n', long = "max-count", help = "limit the number of commits to output")
            .int()
            .validate("must be a positive count") { it > 0 }

        // `git log -5` is git's shorthand for `-n 5`, and the last of the two wins.
        val directMaxCount = numberOption(help = "same as -n NUM")
            .int()
            .validate("must be a positive count") { it > 0 }

        val maxCount = lastOf(namedMaxCount, directMaxCount)

        val skip = option(long = "skip", help = "skip <n> commits before starting to show output").int()

        val since = option(long = "since", help = "show commits more recent than a specific date")
        val until = option(long = "until", help = "show commits older than a specific date")

        val author = option(long = "author", help = "limit to commits whose author matches <pattern>")
        val grep = option(long = "grep", help = "limit to commits whose message matches <pattern>")
        val pretty = option(long = "pretty", help = "pretty-print the commits in the given format")

        val patch = flag(short = 'p', long = "patch", help = "show the patch for each commit")
        val stat = flag(long = "stat", help = "show a diffstat for each commit")
        val oneline = flag(long = "oneline", help = "shorthand for --pretty=oneline --abbrev-commit")
        val graph = flag(long = "graph", help = "draw a text-based graph of the commit history")
        val allRefs = flag(long = "all", help = "pretend as if all refs were listed on the command line")
        val reversed = flag(long = "reverse", help = "output the commits in reverse order")

        // KLAP-GAP: git's `--decorate[=short|full|auto|no]` plus `--no-decorate` needs BOTH `.negatable()`
        // and `.optionalValue()` on one holder, but the first lives on `Flag` and the second on `Opt<T>`.
        // Keeping `.negatable()` matches `--decorate`/`--no-decorate` exactly and drops the `=WHEN` spellings.
        val decorate = flag(
            long = "decorate",
            help = "print the ref names of shown commits",
        ).negatable(default = true)

        // KLAP-GAP: `--` only ends option parsing rather than separating git's two operand groups, so `git
        // log -- src/` misbinds "src/" as the revision range, and a multi-rev invocation can't be absorbed
        // either, since a command allows at most one trailing variadic.
        val revisionRange = argument("revision-range", "which commits to show, e.g. main..HEAD").optional()
        val paths = argument("path", "limit output to commits touching these paths").file().multiple()

        action {
            val filters = listOfNotNull(
                maxCount()?.let { "-n $it" },
                skip()?.let { "--skip=$it" },
                since()?.let { "--since=$it" },
                until()?.let { "--until=$it" },
                author()?.let { "--author=$it" },
                grep()?.let { "--grep=$it" },
                pretty()?.let { "--pretty=$it" },
                "-p".takeIf { patch() },
                "--stat".takeIf { stat() },
                "--oneline".takeIf { oneline() },
                "--graph".takeIf { graph() },
                "--all".takeIf { allRefs() },
                "--reverse".takeIf { reversed() },
                "--no-decorate".takeIf { !decorate() },
                "--no-pager".takeIf { !paginate() },
            )
            val scope = if (paths().isEmpty()) "" else " -- ${paths().joinToString(" ")}"
            Ok("would show ${revisionRange() ?: "HEAD"}$scope ${filters.joinToString(" ")}")
        }

        projection {
            GitInputs.Log(
                globals(), maxCount(), skip(), since(), until(), author(), grep(), pretty(), patch(), stat(),
                oneline(), graph(), allRefs(), reversed(), decorate(), revisionRange(), paths(),
            )
        }
    }

    // A hybrid: `git remote` bare lists the remotes, and it also routes to add/remove. klap allows both
    // on one node, and routing wins over a free positional (there is none here, so nothing collides).
    val remote = command("remote") {
        help = "Manage set of tracked repositories"
        val verbose = flag(short = 'v', long = "verbose", help = "be verbose; must be placed before a subcommand")
            .negatable(default = false)

        val remoteAdd = command("add") {
            help = "Add a remote named <name> for the repository at <URL>"
            val fetch = flag(short = 'f', long = "fetch", help = "fetch the remote branches")
            val tags = flag(
                long = "tags",
                help = "import all tags and associated objects when fetching " +
                    "or do not fetch any tag at all (--no-tags)",
            )
                .negatable(default = true)
            val track = option(short = 't', long = "track", help = "branch(es) to track").multiple()
            val master = option(short = 'm', long = "master", help = "master branch")

            // git's is `--mirror[=(push|fetch)]` (bare `--mirror` is the deprecated push-mirror form); this
            // stays value-required on purpose, not for lack of an expressible form — `.optionalValue()`
            // could express it too, like `--exec-path`/`-S` above.
            val mirror = option(
                long = "mirror",
                help = "set up remote as a mirror to push to or fetch from",
            )
                .choice("fetch", "push")

            val name = argument("name", "the remote's short name")
            val url = argument("url", "the remote's URL").file()

            action {
                val opts = listOfNotNull(
                    "-f".takeIf { fetch() },
                    "--no-tags".takeIf { !tags() },
                    track().joinToString(" ") { "-t $it" }.takeIf { track().isNotEmpty() },
                    master()?.let { "-m $it" },
                    mirror()?.let { "--mirror=$it" },
                )
                // Reads root options two levels down from where they were declared.
                val where = directory().lastOrNull() ?: "."
                Ok(
                    "would add remote ${name()} -> ${url()} in $where " +
                        "${opts.joinToString(" ")} ${config().size} config override(s)",
                )
            }

            projection {
                GitInputs.RemoteAdd(globals(), fetch(), tags(), track(), master(), mirror(), name(), url())
            }
        }

        val remoteRemove = command("remove") {
            help = "Remove the remote named <name>"
            aliases = listOf("rm")
            val name = argument("name", "the remote to remove")
            action { Ok("would remove remote ${name()}") }
            projection { GitInputs.RemoteRemove(globals(), name()) }
        }

        action {
            val detail = if (verbose()) " with URLs" else ""
            Ok("would list remotes$detail")
        }

        dispatch(remoteAdd, remoteRemove, projection { GitInputs.Remote(globals(), verbose()) })
    }

    // Hidden diagnostic: proves the root's options really are readable from a command below it.
    val gitVar = command("var") {
        help = "Show a Git logical variable"
        hidden = true
        val list = flag(short = 'l', help = "Display the logical variables")
        val variable = argument("variable", "the logical variable to print").optional()
        // `git var (-l | <variable>)`: bare `git var` and `git var -l GIT_EDITOR` print the usage (exit 129).
        exactlyOne(list, variable)

        action {
            val lines = listOf(
                "directory=${directory()}",
                "config=${config()}",
                "paginate=${paginate()}",
                "git-dir=${gitDir() ?: "<none>"}",
                "work-tree=${workTree() ?: "<none>"}",
                "namespace=${namespace() ?: "<none>"}",
                "bare=${bare()}",
                "exec-path=${execPath() ?: "<none>"}",
                "no-replace-objects=${noReplaceObjects()}",
                "no-optional-locks=${noOptionalLocks()}",
            )
            Ok(if (list()) lines.joinToString("\n") else "would print ${variable()}")
        }
        projection { GitInputs.Var(globals(), list(), variable()) }
    }

    dispatch(status, add, commit, log, remote, gitVar)
}

/** Every root option a `git` invocation carries, read by whichever command it routes to. */
public data class GitGlobals(
    val directory: List<String>,
    val config: List<String>,
    val paginate: Boolean,
    val gitDir: String?,
    val workTree: String?,
    val namespace: String?,
    val bare: Boolean,
    val execPath: String?,
    val noReplaceObjects: Boolean,
    val noOptionalLocks: Boolean,
)

/**
 * What one `git` line binds, as values rather than as the handles that read them.
 *
 * git is the tree-shaped case: every command projects to its own variant of this sealed interface, and a
 * hybrid node like `remote` folds its children's projections and its own into one via `dispatch`, so a
 * parse resolves to whichever leaf actually ran and a caller's `when` over the result stays exhaustive.
 * Every variant carries [GitGlobals] under its own `globals` field rather than through some parse-wide
 * value, because a root option is readable from any command's scope and each variant's own `projection { }`
 * reads it there. A parity case asserts a whole invocation with one `assertEquals` against a `copy()` of the
 * matching `NOTHING_*` baseline below, so every field it does not name is pinned to its default too.
 */
public sealed interface GitInputs {
    public val globals: GitGlobals

    /** `git status`. */
    public data class Status(override val globals: GitGlobals, val short: Boolean, val branch: Boolean) : GitInputs

    /** `git add`, and its `stage` alias. */
    public data class Add(
        override val globals: GitGlobals,
        val dryRun: Boolean,
        val verbose: Boolean,
        val force: Boolean,
        val patch: Boolean,
        val update: Boolean,
        val addAll: Boolean,
        val intentToAdd: Boolean,
        val ignoreErrors: Boolean,
        val chmod: String?,
        val pathspecFromFile: String?,
        val pathspec: List<String>,
    ) : GitInputs

    /** `git commit`. */
    public data class Commit(
        override val globals: GitGlobals,
        val message: List<String>,
        val messageFile: String?,
        val author: String?,
        val date: String?,
        val reeditMessage: String?,
        val reuseMessage: String?,
        val commitAll: Boolean,
        val amend: Boolean,
        val edit: Boolean,
        val signoff: Boolean,
        val allowEmpty: Boolean,
        val dryRun: Boolean,
        val quiet: Boolean,
        val cleanup: String?,
        val verify: Boolean,
        val gpgSign: String?,
        val pathspec: List<String>,
    ) : GitInputs

    /** `git log`. */
    public data class Log(
        override val globals: GitGlobals,
        val maxCount: Int?,
        val skip: Int?,
        val since: String?,
        val until: String?,
        val author: String?,
        val grep: String?,
        val pretty: String?,
        val patch: Boolean,
        val stat: Boolean,
        val oneline: Boolean,
        val graph: Boolean,
        val allRefs: Boolean,
        val reversed: Boolean,
        val decorate: Boolean,
        val revisionRange: String?,
        val paths: List<String>,
    ) : GitInputs

    /** `git remote`, the hybrid node: it has an action of its own and it routes to `add`/`remove`. */
    public data class Remote(override val globals: GitGlobals, val verbose: Boolean) : GitInputs

    /** `git remote add`. */
    public data class RemoteAdd(
        override val globals: GitGlobals,
        val fetch: Boolean,
        val tags: Boolean,
        val track: List<String>,
        val master: String?,
        val mirror: String?,
        val name: String,
        val url: String,
    ) : GitInputs

    /** `git remote remove`, and its `rm` alias. */
    public data class RemoteRemove(override val globals: GitGlobals, val name: String) : GitInputs

    /** `git var`, hidden: proves the root's options really are readable from a command below it. */
    public data class Var(override val globals: GitGlobals, val list: Boolean, val variable: String?) : GitInputs
}

/** Every root option at its declared default: no directories, no config overrides, pager on and bare off. */
public val NO_GLOBALS: GitGlobals = GitGlobals(
    directory = emptyList(),
    config = emptyList(),
    // flag(...).negatable(short = 'P', long = "no-pager") takes no explicit `default`, so `.negatable()`'s
    // own default of true applies: real git paginates unless told not to, and so does this fixture.
    paginate = true,
    gitDir = null,
    workTree = null,
    namespace = null,
    bare = false,
    execPath = null,
    noReplaceObjects = false,
    noOptionalLocks = false,
)

/** `git status` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_STATUS: GitInputs.Status = GitInputs.Status(NO_GLOBALS, short = false, branch = false)

/** `git add` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_ADD: GitInputs.Add = GitInputs.Add(
    globals = NO_GLOBALS,
    dryRun = false,
    verbose = false,
    force = false,
    patch = false,
    update = false,
    addAll = false,
    intentToAdd = false,
    ignoreErrors = false,
    chmod = null,
    pathspecFromFile = null,
    pathspec = emptyList(),
)

/** `git commit` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_COMMIT: GitInputs.Commit = GitInputs.Commit(
    globals = NO_GLOBALS,
    message = emptyList(),
    messageFile = null,
    author = null,
    date = null,
    reeditMessage = null,
    reuseMessage = null,
    commitAll = false,
    amend = false,
    edit = true,
    signoff = false,
    allowEmpty = false,
    dryRun = false,
    quiet = false,
    cleanup = null,
    verify = true,
    gpgSign = null,
    pathspec = emptyList(),
)

/** `git log` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_LOG: GitInputs.Log = GitInputs.Log(
    globals = NO_GLOBALS,
    maxCount = null,
    skip = null,
    since = null,
    until = null,
    author = null,
    grep = null,
    pretty = null,
    patch = false,
    stat = false,
    oneline = false,
    graph = false,
    allRefs = false,
    reversed = false,
    decorate = true,
    revisionRange = null,
    paths = emptyList(),
)

/** `git remote` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_REMOTE: GitInputs.Remote = GitInputs.Remote(NO_GLOBALS, verbose = false)

/** `git remote add` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_REMOTE_ADD: GitInputs.RemoteAdd = GitInputs.RemoteAdd(
    globals = NO_GLOBALS,
    fetch = false,
    tags = true,
    track = emptyList(),
    master = null,
    mirror = null,
    name = "",
    url = "",
)

/** `git remote remove` with no arguments at all: every field at the default its declaration gives it. */
public val NOTHING_REMOTE_REMOVE: GitInputs.RemoteRemove = GitInputs.RemoteRemove(NO_GLOBALS, name = "")

/**
 * What `git --version` prints for git 2.56.0, the version installed when it was pinned
 * (2026-10-10).
 */
internal val GIT_VERSION_TEXT: String = """git version 2.56.0"""
