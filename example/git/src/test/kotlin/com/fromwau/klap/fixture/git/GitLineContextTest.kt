package com.fromwau.klap.fixture.git

import com.fromwau.klap.fixture.git.GitTab.NOTHING
import com.fromwau.klap.fixture.git.GitTab.at
import com.fromwau.klap.fixture.git.GitTab.checkTabs
import com.fromwau.klap.fixture.git.GitTab.files
import com.fromwau.klap.fixture.git.GitTab.offersAll
import com.fromwau.klap.fixture.git.GitTab.offersNone
import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test

/** log's own names the fixture declares; `git log -h` lists none of them (todo 93), so cells check names only. */
private val LOG_OWN_NAMES: List<String> = listOf(
    "--max-count", "-n", "--skip", "--since", "--until", "--author", "--grep", "--pretty", "--patch", "-p", "--stat",
    "--oneline", "--graph", "--all", "--reverse", "--decorate", "--no-decorate",
)

/**
 * What Tab offers on a git line given what stands before the cursor and after it (L and S of the testing matrix, the
 * rows about the subcommand tree), checked against what real git 2.56.0 accepts there. This class holds what klap
 * and the fixture already do.
 */
class GitLineContextTest {

    private val git = gitCli().cli

    @Test
    fun `a subcommand after the cursor leaves the slot to the command the head reaches`() = git.checkTabs(
        // L17: `git -C sub status` runs, so -C's value is the slot; `git remote --tag add` is "unknown option `tag'".
        at("L17", "git -C | log", files()),
        at("L17", "git remote --tag | add", NOTHING),
    )

    @Test
    fun `a root option's value slot before the subcommand is the value`() = git.checkTabs(
        // S38: `git -C sub status` and `git -c a.b=1 status` run; -c's name=value is free text.
        at("S38", "git -C |", files()),
        at("S38", "git -c |", NOTHING),
    )

    @Test
    fun `a sibling's or a parent's option is not offered at a leaf`() {
        // S41: `git remote add -v o4 u4` and `git remote rm -v o` are "unknown switch `v'"; log has no -m.
        git.offersNone("S41", "git remote add -|", listOf("-v", "--verbose"))
        git.offersNone("S41", "git remote rm -|", listOf("-v", "--verbose"))
        git.offersNone("S41", "git log -|", listOf("-m", "--message", "-s", "--short"))
    }

    @Test
    fun `a command reached by its alias offers that command's own names`() =
        // S43: `git stage -h` prints git-add's usage.
        git.offersAll("S43", "git stage -|", ADD_NAMES.map { it.value })

    @Test
    fun `the walk reaches log past a root option before it`() =
        // S37: `git --no-pager log -1` runs.
        git.offersAll("S37", "git --no-pager log -|", LOG_OWN_NAMES)

    @Test
    fun `the subcommand slot completes in git's words whatever follows the cursor`() = git.checkTabs(
        // L17, S37: `git -C sub status` runs; `git completion` is "'completion' is not a git command".
        at("L17", "git | log", COMMANDS),
        at("S37", "git -C d |", COMMANDS),
        // L18: `git status -s` runs; of the fixture's names only status starts with `status`.
        at("L18", "git status| -s", COMMANDS.startingWith("status")),
        at("L18", "git remote add| origin url", REMOTE_CHILDREN.startingWith("add")),
        // L25: remote has no operand of its own (`git remote origin` is "unknown subcommand"), so its first slot
        // offers its children alone.
        at("L25", "git remote | origin url", REMOTE_CHILDREN),
    )

    @Test
    fun `a root long before a subcommand completes in git's words`() = git.checkTabs(
        // L17: `git --no-pager log -1` runs.
        at("L17", "git --no-pa| log", ROOT_LONGS.startingWith("--no-pa")),
    )

    @Test
    fun `a root option is not offered at a leaf`() {
        git.checkTabs(
            // S39: `git log --no-pager -1` is "unrecognized argument: --no-pager".
            at("S39", "git log --no-pa|", NOTHING),
        )
        // S37, G38: log offers its own names, and `git log --bare` and the rest are "unrecognized argument".
        git.offersNone("S37", "git --no-pager log -|", ROOT_OPTION_NAMES + BUILTINS_GIT_REFUSES_BELOW_ROOT)
    }

    @Test
    fun `a leaf's dash offers exactly its own names`() = git.checkTabs(
        // S41: `git remote add -h` lists add's options; `git remote add -C . o u` is "unknown switch `C'".
        at("S41", "git remote add -|", REMOTE_ADD_NAMES),
        at("S41", "git remote rm -|", REMOTE_REMOVE_NAMES),
        // S43: `git stage -h` prints git-add's usage.
        at("S43", "git stage -|", ADD_NAMES),
    )

    @Test
    fun `the walk continues past remote's own option to its child`() = git.checkTabs(
        // S40: `git remote -v show` lists the URLs and `git remote -v add o2 u2` adds, so -v keeps its meaning and the
        // word after it is a child's name; fish's own completion offers the children there too.
        at("S40", "git remote -v |", REMOTE_CHILDREN),
        at("S40", "git remote --verbose |", REMOTE_CHILDREN),
        at("S40", "git remote -v a|", REMOTE_CHILDREN.startingWith("a")),
        // `git remote -v add -h` prints add's usage, and `git remote add -v` is "unknown switch `v'".
        at("S40", "git remote -v add -|", REMOTE_ADD_NAMES),
    )

    @Test
    fun `a cluster continues with the command's own letters only`() = git.checkTabs(
        // S42: `git -P status` runs while `git -pP status` and `git -PC . status` are "unknown option", so the root
        // offers the typed short alone (todo 177); `git status -sb` runs and `git status -sh` prints the usage, while
        // `git status -P` is "unknown switch `P'".
        at("S42", "git -P|", Suggestion("-P", "Do not pipe Git output into a pager")),
        at(
            "S42",
            "git status -s|",
            Suggestion("-sb", "show branch information"),
            Suggestion("-sh", HELP_WORDS),
        ),
    )
}
