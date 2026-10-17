package com.fromwau.klap.fixture.git

import com.fromwau.klap.testing.Suggestion

/*
 * The parts of real git 2.56.0 this pass compares the fixture with: its subcommand tree, the options of the root, and
 * the options each command the fixture declares takes, in git's own words. A command's description is its line in
 * `git help -a`; a child of `remote` and an option of the root have none there, so theirs is the first sentence of
 * `man git-remote` and `man git`, its full stop dropped; an option of a command is its line in `git <command> -h`.
 * Only the inputs the fixture declares are listed: git's other options and commands are out of this pass.
 */

/** git's own help options carry no description, so a cell names them with klap's words, which G45 settles. */
internal const val HELP_WORDS: String = "Show this help"

/** Each of [spellings] described by [help]. */
private fun described(
    help: String,
    vararg spellings: String,
): List<Suggestion> = spellings.map { Suggestion(it, help) }

/** `-h` and `--help`, which every command of real git takes. */
internal val HELP_NAMES: List<Suggestion> = described(HELP_WORDS, "--help", "-h")

/** The commands git lists in `git help -a` that the fixture declares, `stage` described as `add` is. */
internal val COMMANDS: List<Suggestion> = listOf(
    Suggestion("status", "Show the working tree status"),
    Suggestion("add", "Add file contents to the index"),
    Suggestion("stage", "Add file contents to the index"),
    Suggestion("commit", "Record changes to the repository"),
    Suggestion("log", "Show commit logs"),
    Suggestion("remote", "Manage set of tracked repositories"),
)

/** The children of `git remote` the fixture declares, `rm` described as `remove` is. */
internal val REMOTE_CHILDREN: List<Suggestion> = listOf(
    Suggestion("add", "Add a remote named <name> for the repository at <URL>"),
    Suggestion("remove", "Remove the remote named <name>"),
    Suggestion("rm", "Remove the remote named <name>"),
)

/** [names] whose value starts with [typed]. */
internal fun List<Suggestion>.startingWith(typed: String): List<Suggestion> = filter { it.value.startsWith(typed) }

/** The root's longs the fixture declares, in `man git`'s words. */
internal val ROOT_LONGS: List<Suggestion> = listOf(
    Suggestion("--paginate", "Pipe all output into less (or if set, \$PAGER) if standard output is a terminal"),
    Suggestion("--no-pager", "Do not pipe Git output into a pager"),
    Suggestion("--git-dir", "Set the path to the repository (\".git\" directory)"),
    Suggestion("--work-tree", "Set the path to the working tree"),
    Suggestion("--namespace", "Set the Git namespace"),
    Suggestion("--bare", "Treat the repository as a bare repository"),
    Suggestion("--exec-path", "Path to wherever your core Git programs are installed"),
    Suggestion("--no-replace-objects", "Do not use replacement refs to replace Git objects"),
    Suggestion("--no-optional-locks", "Do not perform optional operations that require locks"),
)

/** `git status`'s names the fixture declares, in `git status -h`'s words. */
internal val STATUS_NAMES: List<Suggestion> =
    described("show status concisely", "--short", "-s") +
        described("show branch information", "--branch", "-b") +
        HELP_NAMES

/** `git add`'s names the fixture declares, in `git add -h`'s words. */
internal val ADD_NAMES: List<Suggestion> =
    described("dry run", "--dry-run", "-n", "--no-dry-run") +
        described("be verbose", "--verbose", "-v", "--no-verbose") +
        described("allow adding otherwise ignored files", "--force", "-f") +
        described("select hunks interactively", "--patch", "-p") +
        described("update tracked files", "--update", "-u") +
        described("add changes from all tracked and untracked files", "--all", "-A") +
        described("record only the fact that the path will be added later", "--intent-to-add", "-N") +
        described("just skip files which cannot be added because of errors", "--ignore-errors") +
        described("override the executable bit of the listed files", "--chmod") +
        described("read pathspec from file", "--pathspec-from-file") +
        HELP_NAMES

/** `git remote`'s own names, in `git remote -h`'s words. */
internal val REMOTE_NAMES: List<Suggestion> =
    described("be verbose; must be placed before a subcommand", "--verbose", "-v") + HELP_NAMES

/** `git remote add`'s names the fixture declares, in `git remote add -h`'s words. */
internal val REMOTE_ADD_NAMES: List<Suggestion> =
    described("fetch the remote branches", "--fetch", "-f") +
        described(
            "import all tags and associated objects when fetching or do not fetch any tag at all (--no-tags)",
            "--tags",
            "--no-tags",
        ) +
        described("branch(es) to track", "--track", "-t") +
        described("master branch", "--master", "-m") +
        described("set up remote as a mirror to push to or fetch from", "--mirror") +
        HELP_NAMES

/** `git remote remove` takes no option but help (`git remote remove -h` lists none). */
internal val REMOTE_REMOVE_NAMES: List<Suggestion> = HELP_NAMES

/**
 * One spelling of a root option, as the [words] it takes on the line, and the [globals] real git sets from it.
 * [letter] is the short it is spelled with, null for a long.
 */
internal data class RootSpelling(
    val words: List<String>,
    val globals: GitGlobals,
    val letter: Char? = null,
) {
    override fun toString(): String = words.joinToString(" ")
}

private fun spelled(
    vararg words: String,
    letter: Char? = null,
    globals: GitGlobals.() -> GitGlobals,
) = RootSpelling(words.toList(), NO_GLOBALS.globals(), letter)

/**
 * Every spelling of every root option the fixture declares that real git accepts before the subcommand, each run as
 * `git <spelling> status` in a throwaway repository.
 */
internal val ROOT_SPELLINGS: List<RootSpelling> = listOf(
    spelled("-C", "d", letter = 'C') { copy(directory = listOf("d")) },
    spelled("-c", "a.b=1", letter = 'c') { copy(config = listOf("a.b=1")) },
    spelled("-p", letter = 'p') { copy(paginate = true) },
    spelled("--paginate") { copy(paginate = true) },
    spelled("--no-pager") { copy(paginate = false) },
    spelled("-P", letter = 'P') { copy(paginate = false) },
    spelled("--git-dir=.git") { copy(gitDir = ".git") },
    spelled("--git-dir", ".git") { copy(gitDir = ".git") },
    spelled("--work-tree=.") { copy(workTree = ".") },
    spelled("--work-tree", ".") { copy(workTree = ".") },
    spelled("--namespace=n") { copy(namespace = "n") },
    spelled("--namespace", "n") { copy(namespace = "n") },
    spelled("--bare") { copy(bare = true) },
    spelled("--exec-path=x") { copy(execPath = "x") },
    spelled("--no-replace-objects") { copy(noReplaceObjects = true) },
    spelled("--no-optional-locks") { copy(noOptionalLocks = true) },
)

/**
 * One command line of the fixture's tree: the [path] of command names, the [operands] after them, and the record
 * real git reads from it with [GitGlobals] given.
 */
internal data class TreeLine(
    val path: List<String>,
    val operands: List<String>,
    val record: (GitGlobals) -> GitInputs,
) {
    val words: List<String> get() = path + operands

    override fun toString(): String = words.joinToString(" ")
}

/** Every command of the tree, once by each name, with the operands it needs; `git var` is P16's own question. */
internal val TREE_LINES: List<TreeLine> = listOf(
    TreeLine(listOf("status"), emptyList()) { NOTHING_STATUS.copy(globals = it) },
    TreeLine(listOf("add"), listOf("f")) { NOTHING_ADD.copy(globals = it, pathspec = listOf("f")) },
    TreeLine(listOf("stage"), listOf("f")) { NOTHING_ADD.copy(globals = it, pathspec = listOf("f")) },
    TreeLine(listOf("commit"), listOf("-m", "x")) { NOTHING_COMMIT.copy(globals = it, message = listOf("x")) },
    TreeLine(listOf("log"), emptyList()) { NOTHING_LOG.copy(globals = it) },
    TreeLine(listOf("remote"), emptyList()) { NOTHING_REMOTE.copy(globals = it) },
    TreeLine(listOf("remote", "add"), listOf("o", "u")) {
        NOTHING_REMOTE_ADD.copy(globals = it, name = "o", url = "u")
    },
    TreeLine(listOf("remote", "remove"), listOf("o")) { NOTHING_REMOTE_REMOVE.copy(globals = it, name = "o") },
    TreeLine(listOf("remote", "rm"), listOf("o")) { NOTHING_REMOTE_REMOVE.copy(globals = it, name = "o") },
)
