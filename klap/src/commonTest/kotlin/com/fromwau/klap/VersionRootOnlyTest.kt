package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `Version.Of(rootOnly = true)` keeps the version on the root's own segment, as git's `-v` and `--version` are:
 * `git -v status` prints the version, `git status --version` is an unknown option, and `git add -v` is add's.
 */
class VersionRootOnlyTest {

    private val git = cli("git") {
        version = Version.Of("2.56.0", long = "version", short = 'v', rootOnly = true)
        command("status") {
            val short = flag(short = 's', long = "short")
            action { Ok("status short=${short()}") }
        }
        command("add") {
            val verbose = flag(short = 'v', long = "verbose")
            action { Ok("add verbose=${verbose()}") }
        }
    }

    @Test
    fun `the version answers on the root's segment`() {
        assertEquals("git 2.56.0", git.bindText("-v"))
        assertEquals("git 2.56.0", git.bindText("--version"))
        assertEquals("git 2.56.0", git.bindText("-v", "status"))
    }

    @Test
    fun `below the root the version is no option`() {
        assertEquals(CliError.UnknownOption("--version"), git.parse(listOf("status", "--version")).assertError())
        assertEquals(CliError.UnknownOption("-v"), git.parse(listOf("status", "-v")).assertError())
    }

    @Test
    fun `a command below the root may declare the version's letter`() {
        assertEquals("add verbose=true", git.bindText("add", "-v"))
    }

    @Test
    fun `the root still may not declare the version's letter`() {
        assertFailsWith<IllegalArgumentException> {
            cli("git") {
                version = Version.Of("2.56.0", long = "version", short = 'v', rootOnly = true)
                flag(short = 'v', long = "verbose")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `tab offers the version at the root only`() {
        assertTrue("--version" in git.offered("-"))
        assertTrue("-v" in git.offered("-"))
        assertFalse("--version" in git.offered("status", "-"))
        assertFalse("-v" in git.offered("status", "-"))
    }

    @Test
    fun `a command's help lists no version`() {
        assertTrue("--version" in git.bindText("--help"))
        assertFalse("--version" in git.bindText("status", "--help"))
    }

    @Test
    fun `without the setting the version answers at every depth`() {
        val everywhere = cli("git") {
            version = Version.Of("2.56.0")
            command("status") { action { Ok("") } }
        }
        assertIs<Invocation.ShowVersion>(everywhere.parse(listOf("status", "--version")).assertSuccess())
    }

    @Test
    fun `the version short reads by the root's own segment`() {
        val tool = cli("tool") {
            version = Version.Of("1", long = "version", short = 'v', rootOnly = true)
            ownOptions(ShortOptions.Alone, Abbreviation.None)
            val paginate = flag(short = 'p')
            action { Ok("p=${paginate()}") }
        }
        assertEquals("tool 1", tool.bindText("-v"))
        for (word in listOf("-pv", "-vp", "-pp")) {
            assertEquals(CliError.UnknownOption(word), tool.parse(listOf(word)).assertError(), word)
        }
    }
}
