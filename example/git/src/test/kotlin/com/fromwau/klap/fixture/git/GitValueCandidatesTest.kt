package com.fromwau.klap.fixture.git

import com.fromwau.klap.fixture.git.GitTab.NOTHING
import com.fromwau.klap.fixture.git.GitTab.at
import com.fromwau.klap.fixture.git.GitTab.checkTabs
import com.fromwau.klap.fixture.git.GitTab.files
import kotlin.test.Test

/**
 * Where the candidates for a value come from on git's tree (V of the testing matrix, the rows about the tree and
 * root options), checked against what real git 2.56.0 accepts there. The fixture declares no provider, so the rows
 * about one are not covered here. This class holds what klap and the fixture already do.
 */
class GitValueCandidatesTest {

    private val git = gitCli().cli

    @Test
    fun `a path option of the root hands its value to the shell before the subcommand`() = git.checkTabs(
        // V3g, V28: `git --git-dir .git status`, `git --git-dir=.git status` and `git --work-tree . status` run.
        at("V28", "git --git-dir |", files()),
        at("V28 words after", "git --git-dir | log", files()),
        at("V3g", "git --git-dir=|", files("--git-dir=")),
        at("V3g", "git --work-tree=|", files("--work-tree=")),
        at("V3g", "git --work-tree | status", files()),
        at("V3g", "git -C | status", files()),
    )

    @Test
    fun `a path option of the root has no value slot after the subcommand`() = git.checkTabs(
        // V28, V3g: `git log -1 --git-dir=.git` and `git log -1 --git-dir .git` are "unrecognized argument"; the word
        // after the unknown --git-dir is log's <revision-range>, free text.
        at("V28", "git log --git-dir=|", NOTHING),
        at("V3g", "git log --git-dir |", NOTHING),
        at("V3g", "git remote add o --work-tree=|", NOTHING),
    )

    @Test
    fun `remote's first operand slot offers its children alone`() = git.checkTabs(
        // V1b: remote takes no operand of its own (`git remote x` is "unknown subcommand: `x'").
        at("V1b", "git remote |", REMOTE_CHILDREN),
        at("V1b", "git remote r|", REMOTE_CHILDREN.startingWith("r")),
    )
}
