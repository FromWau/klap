package com.fromwau.klap.fixture.git

import com.fromwau.klap.fixture.git.GitTab.at
import com.fromwau.klap.fixture.git.GitTab.checkTabs
import com.fromwau.klap.fixture.git.GitTab.offersNone
import kotlin.test.Test

/**
 * The suggestion list on git's tree as the K, L, S and V cells need it (G and E of the testing matrix): which entries
 * the subcommand slot holds and how each is described, checked against real git 2.56.0. This class holds what klap
 * and the fixture already do.
 */
class GitSuggestionListTest {

    private val git = gitCli().cli

    @Test
    fun `klap's hidden protocol and hidden commands are never offered`() =
        // G37, G8: `__complete` is klap's wire, and the fixture hides `var`.
        git.offersNone("G37", "git |", listOf("__complete", "var"))

    @Test
    fun `the subcommand slot offers no subcommand git does not have`() =
        // G37: `git completion bash` and `git docs markdown` are "'completion' is not a git command".
        git.offersNone("G37", "git |", listOf("completion", "docs"))

    @Test
    fun `an alias is an entry of its own described as its command`() = git.checkTabs(
        // G9, G33: `git help -a` lists `add  Add file contents to the index`; `git stage -h` prints git-add's usage
        // and `git remote rm -h` git-remote-remove's.
        at("G9", "git st|", COMMANDS.startingWith("st")),
        at("G33", "git remote r|", REMOTE_CHILDREN.startingWith("r")),
    )

    @Test
    fun `a leaf offers none of the root's options`() =
        // G38: `git remote add o u --bare` and `git remote add -C . o u` are "unknown option" and "unknown switch".
        git.offersNone("G38", "git remote add --|", ROOT_OPTION_NAMES)
}
