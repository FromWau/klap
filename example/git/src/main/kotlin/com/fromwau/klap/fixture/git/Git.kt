package com.fromwau.klap.fixture.git

import com.fromwau.klap.Ok
import com.fromwau.klap.Opt
import com.fromwau.klap.TypedCli
import com.fromwau.klap.ValueScope
import com.fromwau.klap.cliOf
import com.fromwau.klap.dispatch
import com.fromwau.klap.projection

/**
 * git 2.55.0, reproduced as a command-line surface only (every action is a stub).
 *
 * Real usage:
 *   git [-v | --version] [-h | --help] [-C <path>] [-c <name>=<value>]
 *       [--exec-path[=<path>]] [-p | --paginate | -P | --no-pager]
 *       [--git-dir=<path>] [--work-tree=<path>] [--namespace=<name>] [--bare]
 *       [--no-replace-objects] [--no-optional-locks]
 *       <command> [<args>]
 *
 * Covered subcommands (all verified against git 2.55.0's own `-h` output):
 *   git status [-s] [-b]
 *   git add    [-n] [-v] [-f] [-p] [-u] [-A] [-N] [--chmod (+|-)x] [--] <pathspec>...   (alias: stage)
 *   git commit [-a] [-m <msg>]... [-F <file>] [--amend] [--author=<a>] [-S[<keyid>]] [--] [<pathspec>...]
 *   git log    [-n <count>] [-p] [--oneline] [--[no-]decorate] [<revision-range>] [[--] <path>...]
 *   git remote [-v]
 *   git remote add [-f] [--[no-]tags] [-t <branch>] [-m <master>] [--mirror=<fetch|push>] <name> <url>
 *   git remote remove <name>                                                            (alias: rm)
 */
public fun gitCli(): TypedCli<GitInputs> = cliOf("git") {
    description = "the stupid content tracker"
    version = "2.55.0"
    epilogue = "See 'git help <command>' to read about a specific subcommand."

    example("git -C /tmp/repo status --short", "a global option before the subcommand")
    example("git remote add origin https://example.com/x.git", "two levels of subcommand")
    example("git commit -m subject -m body --amend", "a repeatable option plus a flag")
    example("git log -n 5 --oneline main..HEAD", "a short option with a value")

    val directory = globalOption("--directory", "-C", help = "run as if git was started in <path>")
        .file()
        .multiple()

    val config = globalOption("--config", "-c", help = "pass a configuration parameter to the command")
        .multiple()

    // KLAP-GAP: `validateGlobalCollisions` reserves a global's short tree-wide, but git reuses the same
    // letter below the subcommand with a different meaning, so `-p` (git's paginate/patch overload) stays unclaimed here and `git -p log` is not expressible.
    val paginate = globalFlag("--paginate", help = "pipe all output into a pager")
        .negatable("--no-pager", "-P")

    val gitDir = globalOption("--git-dir", help = "set the path to the repository (.git directory)").file()
    val workTree = globalOption("--work-tree", help = "set the path to the working tree").file()
    val namespace = globalOption("--namespace", help = "set the git namespace")
    val bare = globalFlag("--bare", help = "treat the repository as a bare repository")

    // `--exec-path[=<path>]` is optional-value: a bare occurrence binds the default below, and the space
    // form leaves the next token alone, so `git --exec-path log` routes to `log` rather than swallowing
    // it as the path. Real git's bare form is actually print-and-exit (verified against 2.55.0), which
    // klap has no way to express for a single option — it binds and continues instead, so `log` runs here
    // where real git never gets that far. Pinned in knownDivergenceFromRealGit below.
    val execPath = globalOption("--exec-path", help = "path to where your core git programs are installed")
        .file()
        .optionalValue("/usr/lib/git-core")

    // These two are negative-ONLY in git (there is no `--replace-objects`), which klap expresses exactly:
    // a plain flag whose declared long name happens to start with "no-". requireValidName only rejects a
    // LEADING dash (HolderSpec.kt), so the literal spelling is legal.
    val noReplaceObjects = globalFlag("--no-replace-objects", help = "do not use replacement refs")
    val noOptionalLocks =
        globalFlag("--no-optional-locks", help = "do not perform optional operations that require locks")

    // One helper, because 10 globals on every variant would be worse than what we are replacing.
    fun ValueScope.globals() = GitGlobals(
        directory(), config(), paginate(), gitDir(), workTree(),
        namespace(), bare(), execPath(), noReplaceObjects(), noOptionalLocks(),
    )

    // KLAP-GAP: git's top-level `-v` is a short for `--version`, but the built-in `--version` has no short
    // form and none can be added, so `git -v` is not expressible (`git --vers` still abbreviates fine).

    val status = command("status", "show the working tree status") {
        val short = flag("--short", "-s", help = "show status concisely")
        val branch = flag("--branch", "-b", help = "show branch information")

        action {
            val where = directory().lastOrNull() ?: "."
            Ok(
                "would show status in $where (short=${short()}, " +
                    "branch=${branch()}, bare=${bare()})",
            )
        }

        projection { GitInputs.Status(globals(), short(), branch()) }
    }

    val add = command("add", "add file contents to the index") {
        // `git stage` ships as a synonym for `git add` (its own -h prints git-add's usage).
        aliases = listOf("stage")

        val dryRun = flag("--dry-run", "-n", help = "dry run").negatable(default = false)
        val verbose = flag("--verbose", "-v", help = "be verbose").negatable(default = false)
        val force = flag("--force", "-f", help = "allow adding otherwise ignored files")
        val patch = flag("--patch", "-p", help = "select hunks interactively")
        val update = flag("--update", "-u", help = "update tracked files")
        val addAll = flag("--all", "-A", help = "add changes from all tracked and untracked files")
        val intentToAdd = flag("--intent-to-add", "-N", help = "record only that the path will be added later")
        val ignoreErrors = flag("--ignore-errors", help = "skip files that cannot be added because of errors")

        // Real git accepts `git add --chmod -x file`: parse-options hands a required-value option the next
        // token whatever it looks like (verified against 2.55.0), so the space-separated `-x` binds as the
        // value here too, same as the attached `--chmod=-x` form.
        val chmod = option("--chmod", help = "override the executable bit of the listed files")
            .choice("+x", "-x")

        val pathspecFromFile = option("--pathspec-from-file", help = "read pathspec from file").file()

        // git's usage line writes `<pathspec>...` as mandatory, but real `git add` with zero operands
        // exits 0 with "Nothing specified, nothing added." (verified against 2.55.0), so min = 0 is the
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

    val commit = command("commit", "record changes to the repository") {
        // group(...) returns Unit, so a holder declared inside it needs the lateinit dance the README
        // documents; the block runs synchronously, so these are set before any action runs.
        val message: Opt<List<String>>
        val messageFile: Opt<String?>
        val author: Opt<String?>
        val date: Opt<String?>
        val reuseMessage: Opt<String?>

        group("Commit message options") {
            message = option("--message", "-m", help = "commit message").multiple()
            messageFile = option("--file", "-F", help = "read message from file").file()
            author = option("--author", help = "override author for commit")
            date = option("--date", help = "override date for commit")

            // KLAP-GAP: real spelling is `-C, --reuse-message <commit>`, but the short is unavailable — the
            // global `-C` (chdir) already claims that letter tree-wide. Same story for `-c, --reedit-message`.
            reuseMessage = option("--reuse-message", help = "reuse message from the specified commit")
        }

        val commitAll = flag("--all", "-a", help = "commit all changed files")
        val amend = flag("--amend", help = "amend previous commit")
        val edit = flag("--edit", "-e", help = "force edit of commit").negatable(default = true)
        val signoff = flag("--signoff", "-s", help = "add a Signed-off-by trailer").negatable(default = false)
        val allowEmpty = flag("--allow-empty", help = "allow an empty commit")
        val dryRun = flag("--dry-run", help = "show what would be committed")
        val quiet = flag("--quiet", "-q", help = "suppress summary after successful commit")

        val cleanup = option("--cleanup", help = "how to strip spaces and #comments from the message")
            .choice("strip", "whitespace", "verbatim", "scissors", "default")

        val verify = flag("--verify", help = "verify pre-commit and commit-msg hooks")
            .negatable("--no-verify", "-n")

        // `-S[<keyid>]` / `--gpg-sign[=<key-id>]` is optional-value, and the short spelling
        // matches real git exactly here — its own docs say the key-id "must be stuck to the option without
        // a space" (`-Sabc`, no separate token), which is precisely what `.optionalValue()`'s attached form
        // does for both spellings. `git commit -S -m x` (sign with the default key) still binds "default"
        // and leaves `-m x` alone, unaffected. git's `-u[<mode>]` is the same shape and stays unexpressed.
        //
        // Real git also has `--no-gpg-sign` alongside `-S`/`--gpg-sign[=<key-id>]` (verified against
        // 2.55.0) — the same negatable-plus-optional-value shape `--decorate` and `--preserve-root` face,
        // and klap cannot hold both on one holder here either. Unlike those two, this one took the value
        // form and lost `--no-gpg-sign`: the trade goes the other way because picking WHICH key signs
        // (`-S`/`-S<keyid>`) is what git users actually reach for on this option, where `--decorate` and
        // `--preserve-root` are reached for by their bare negative spelling.
        val gpgSign = option("--gpg-sign", "-S", help = "GPG-sign the commit")
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
                reuseMessage()?.let { "--reuse-message=$it" },
            ) + pathspec()
            Ok("would commit '$subject' (${message().size} message part(s)) ${opts.joinToString(" ")}")
        }

        projection {
            GitInputs.Commit(
                globals(), message(), messageFile(), author(), date(), reuseMessage(), commitAll(), amend(),
                edit(), signoff(), allowEmpty(), dryRun(), quiet(), cleanup(), verify(), gpgSign(), pathspec(),
            )
        }
    }

    val log = command("log", "show commit logs") {
        val maxCount = option("--max-count", "-n", help = "limit the number of commits to output")
            .int()
            .validate("must be a positive count") { it > 0 }

        val skip = option("--skip", help = "skip <n> commits before starting to show output").int()

        val since = option("--since", help = "show commits more recent than a specific date")
        val until = option("--until", help = "show commits older than a specific date")

        val author = option("--author", help = "limit to commits whose author matches <pattern>")
        val grep = option("--grep", help = "limit to commits whose message matches <pattern>")
        val pretty = option("--pretty", help = "pretty-print the commits in the given format")

        val patch = flag("--patch", "-p", help = "show the patch for each commit")
        val stat = flag("--stat", help = "show a diffstat for each commit")
        val oneline = flag("--oneline", help = "shorthand for --pretty=oneline --abbrev-commit")
        val graph = flag("--graph", help = "draw a text-based graph of the commit history")
        val allRefs = flag("--all", help = "pretend as if all refs were listed on the command line")
        val reversed = flag("--reverse", help = "output the commits in reverse order")

        // git's `--decorate[=short|full|auto|no]` plus `--no-decorate` needs BOTH `.negatable()` (for
        // `--no-decorate`) AND `.optionalValue()` (for `=full`) on the same holder, but no call combines
        // them: `.negatable()` lives on `Flag`, `.optionalValue()` on `Opt<T>`. Keeping `.negatable()`
        // matches `--decorate`/`--no-decorate` exactly, at the cost of the `=WHEN` spellings.
        val decorate = flag("--decorate", help = "print the ref names of shown commits").negatable(default = true)

        // KLAP-GAP: `--` only ends option parsing rather than separating git's two operand groups, so `git
        // log -- src/` misbinds "src/" as the revision range, and a multi-rev invocation can't be absorbed
        // either, since a command allows at most one trailing variadic.
        val revisionRange = argument("revision-range", "which commits to show, e.g. main..HEAD").optional()
        val paths = argument("path", "limit output to commits touching these paths").file().multiple()

        // `git log -5` is shorthand for `-n 5`; numericAlias binds the bare number straight to maxCount.
        numericAlias(maxCount)

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
    val remote = command("remote", "manage the set of tracked repositories") {
        // KLAP-GAP: real git accepts `git remote -v add up <url>` (verified against 2.55.0); klap's walk
        // breaks at the first non-child token, so `-v` binds on `remote` instead — only a global survives
        // there, since `siftGlobals` strips globals out of argv before the walk runs.
        val verbose = flag("--verbose", "-v", help = "be verbose")

        val remoteAdd = command("add", "add a remote named <name> for the repository at <url>") {
            val fetch = flag("--fetch", "-f", help = "fetch the remote branches")
            val tags = flag("--tags", help = "import all tags and associated objects when fetching")
                .negatable(default = true)
            val track = option("--track", "-t", help = "branch(es) to track").multiple()
            val master = option("--master", "-m", help = "master branch")

            // git's is `--mirror[=(push|fetch)]` (bare `--mirror` is the deprecated push-mirror form); this
            // stays value-required on purpose, not for lack of an expressible form — `.optionalValue()`
            // could express it too, like `--exec-path`/`-S` above.
            val mirror = option("--mirror", help = "set up the remote as a mirror to push to or fetch from")
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
                // Reads a global two levels down from where it was declared, and a config override too.
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

        val remoteRemove = command("remove", "remove the remote named <name> and all its tracking branches") {
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

    // Hidden diagnostic: proves the globals really are readable from anywhere in the tree.
    val gitVar = command("var", "print a git logical variable") {
        hidden = true
        action {
            Ok(
                listOf(
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
                ).joinToString("\n"),
            )
        }
        projection { GitInputs.Var(globals()) }
    }

    dispatch(status, add, commit, log, remote, gitVar)
}

/** Every global a `git` invocation carries, regardless of which command it routes to. */
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
 * value, because a root global is readable from any command's scope and each variant's own `projection { }`
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

    /** `git var`, hidden: proves the globals really are readable from anywhere in the tree. */
    public data class Var(override val globals: GitGlobals) : GitInputs
}

/** Every global at its declared default: no directories, no config overrides, pager and bare both off. */
public val NO_GLOBALS: GitGlobals = GitGlobals(
    directory = emptyList(),
    config = emptyList(),
    // globalFlag(...).negatable("--no-pager", "-P") took no explicit `default`, so the vararg overload's
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
