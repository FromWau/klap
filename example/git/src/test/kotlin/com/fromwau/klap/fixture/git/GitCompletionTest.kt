package com.fromwau.klap.fixture.git

import com.fromwau.klap.fixture.git.GitTab.NOTHING
import com.fromwau.klap.fixture.git.GitTab.at
import com.fromwau.klap.fixture.git.GitTab.checkTabs
import com.fromwau.klap.fixture.git.GitTab.files
import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test

/**
 * What Tab completes at the slots of git's subcommand tree and root options (K and K7 of the testing matrix, the rows
 * this pass covers), checked against what real git 2.56.0 accepts there: the exact suggestions with their
 * descriptions in git's words (GitModel.kt). This class holds what klap and the fixture already do.
 */
class GitCompletionTest {

    private val git = gitCli().cli

    @Test
    fun `a value in its own word after a root option before the subcommand`() = git.checkTabs(
        // K8e, K37: `git -C sub status` runs; -C's value is a path, and klap has no directory-only form.
        at("K37", "git -C di|", files()),
        at("K37 inside", "git -C d|i", files()),
        at("K37 start", "git -C |dir", files()),
        at("K37 no word", "git -C |", files()),
        at("K37 words after", "git -C | status", files()),
        // `git -c a.b=1 status` runs; the name=value is free text.
        at("K37 no word", "git -c |", NOTHING),
    )

    @Test
    fun `an attached value of a root long hands the path to the shell`() = git.checkTabs(
        // K39: `git --git-dir=.git status` runs.
        at("K39", "git --git-dir=.g|", files("--git-dir=")),
        at("K39 inside", "git --git-dir=.|g", files("--git-dir=")),
        at("K39 words after", "git --git-dir=| status", files("--git-dir=")),
    )

    @Test
    fun `a value holding an equals sign is free text`() = git.checkTabs(
        // K43: `git -c core.editor=vim config core.editor` printed vim. git has no --config, so its cells offer
        // nothing either way.
        at("K43", "git -c core.editor=v|", NOTHING),
        at("K43", "git --config=core.editor=v|", NOTHING),
        at("K43 inside", "git --config=core.e|ditor=vim", NOTHING),
        at("K43 no word", "git -c core.editor=|", NOTHING),
        at("K43 no word", "git --config=|", NOTHING),
        at("K43 words after", "git -c core.editor=| status", NOTHING),
    )

    @Test
    fun `an abbreviation unique in its own command completes and takes its value`() = git.checkTabs(
        // K35: `git remote add --ma main o8 u8` ran; `--f` is unique among add's longs (owner decision K35).
        at("K35", "git remote add --ma|", Suggestion("--master", "master branch")),
        at("K35", "git remote add --f|", Suggestion("--fetch", "fetch the remote branches")),
        at("K35 no word", "git remote add --ma |", NOTHING),
        at("K35 words after", "git remote add --ma | origin", NOTHING),
    )

    @Test
    fun `an operand of a child reached through an alias is free text`() = git.checkTabs(
        // K19: `git remote rm zz` reads zz as the remote's name, any word.
        at("K19 alias", "git remote rm or|", NOTHING),
    )

    @Test
    fun `an empty word before a root long is the leaf's operand slot`() = git.checkTabs(
        // K21: handed an empty word, commit's slot is its pathspec.
        at("K21 start", "git commit |--directory", files()),
    )

    @Test
    fun `a subcommand name completes in git's words wherever the walk reaches its parent`() = git.checkTabs(
        // K19: `git remote add o u` runs; `git remote ad o5 u` is "unknown subcommand: `ad'". The words are git's
        // (man git-remote), the fixture's are its own.
        at("K19", "git remote ad|", REMOTE_CHILDREN.startingWith("ad")),
        at("K19 inside", "git remote a|d", REMOTE_CHILDREN.startingWith("a")),
        at("K19 start", "git remote |add", REMOTE_CHILDREN),
        at("K19 no word", "git remote |", REMOTE_CHILDREN),
        at("K19 words after", "git remote ad| origin url", REMOTE_CHILDREN.startingWith("ad")),
        // K37: `git -C sub status` runs; `git completion` is "'completion' is not a git command".
        at("K37 no word", "git -C dir |", COMMANDS),
        // K39, K4b: the cursor at a word's start hands an empty word, the root's subcommand slot.
        at("K39 start", "git |--git-dir=.g", COMMANDS),
        at("K4b start", "git |--no-pager", COMMANDS),
    )

    @Test
    fun `a root option is not offered after the subcommand`() = git.checkTabs(
        // K21, K4b, K5: `git status --no-pager`, `git status --git-dir=.git` and `git commit --no-pager -m x` are
        // "unknown option"; git has no --directory at all.
        at("K21", "git remote add --no-p|", NOTHING),
        at("K21", "git commit --dir|", NOTHING),
        at("K21 inside", "git commit --di|r", NOTHING),
        at("K21 words after", "git remote add --no-p| origin url", NOTHING),
        at("K4b", "git commit --no-pa|", NOTHING),
        at("K5", "git commit --git-d|", NOTHING),
        // K38: `git remote add --dir` matches no long of add.
        at("K38", "git remote add --dir|", NOTHING),
        // K7k: `git status -P` is "unknown switch `P'", and commit has no -P either.
        at("K7k", "git commit -aP|", NOTHING),
    )

    @Test
    fun `a command's dash offers its own names only`() = git.checkTabs(
        // K38: `git status -C sub` is "unknown switch `C'"; `git status --json`, `--color` and `--version` are
        // "unknown option".
        at("K38", "git status -|", STATUS_NAMES),
    )

    @Test
    fun `a root option after the subcommand takes no value`() = git.checkTabs(
        // K38: `git remote add -C . o u` is "unknown switch `C'", so the word after -C is add's <name>, free text.
        at("K38", "git remote add -C |", NOTHING),
    )

    @Test
    fun `nothing is offered after a root option between remote and its child`() = git.checkTabs(
        // K66, K19: `git remote --bare add o2 u` and `git remote --no-pager` are "unknown option".
        at("K66", "git remote --bare ad|", NOTHING),
        at("K66 no word", "git remote --no-pager |", NOTHING),
        at("K66 words after", "git remote --bare ad| origin url", NOTHING),
    )

    @Test
    fun `-C takes no glued value and --directory is not git's`() = git.checkTabs(
        // K10, K39: `git -Csub status` is "unknown option: -Csub"; `git --directory sub status` is "unknown option".
        at("K10", "git -Cdi|", NOTHING),
        at("K39", "git -Csub|", NOTHING),
        at("K39", "git --dir|", NOTHING),
    )

    @Test
    fun `a root long completes in git's words`() = git.checkTabs(
        // K4b: `git --no-pager status` runs; the words are man git's.
        at("K4b", "git --no-pa|", ROOT_LONGS.startingWith("--no-pa")),
        at("K4b inside", "git --no-|pa", ROOT_LONGS.startingWith("--no-")),
        at("K4b words after", "git --no-pa| log", ROOT_LONGS.startingWith("--no-pa")),
    )
}
