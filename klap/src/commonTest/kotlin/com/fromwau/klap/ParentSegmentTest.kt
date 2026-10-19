package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A command's own options stand on its own segment, between its name and its child's name, as git reads
 * `git remote -v add o u`: each level is read by its own options, and the child's name ends the parent's.
 */
class ParentSegmentTest {

    private val app = cli("app") {
        val workdir = option(short = 'w', long = "workdir")
        command("build") {
            val fast = flag(short = 'f', long = "fast")
            action { Ok("built in ${workdir()} fast=${fast()}") }
        }
        command("remote") {
            val verbose = flag(short = 'v', long = "verbose")
            command("add") {
                val name = argument("name")
                action { Ok("added ${name()} verbose=${verbose()} in ${workdir()}") }
            }
            action { Ok("listed verbose=${verbose()}") }
        }
        action { Ok("root in ${workdir()}") }
    }

    @Test
    fun `a parent's option before its child's name binds and the walk routes on`() {
        assertEquals("built in /tmp fast=false", app.bindText("--workdir", "/tmp", "build"))
        assertEquals("built in /tmp fast=false", app.bindText("--workdir=/tmp", "build"))
        assertEquals("built in /tmp fast=false", app.bindText("-w/tmp", "build"))
        assertEquals("built in /tmp fast=true", app.bindText("-w", "/tmp", "build", "-f"))
    }

    @Test
    fun `every level reads its own options on the way to a leaf`() {
        assertEquals("added o verbose=true in d", app.bindText("-w", "d", "remote", "-v", "add", "o"))
        assertEquals("added o verbose=true in null", app.bindText("remote", "--verb", "add", "o"))
    }

    @Test
    fun `a parent's option absent reads its default in the child`() {
        assertEquals("added o verbose=false in null", app.bindText("remote", "add", "o"))
    }

    @Test
    fun `a parent's option without a child still binds on the parent`() {
        assertEquals("listed verbose=true", app.bindText("remote", "-v"))
        assertEquals("root in d", app.bindText("-w", "d"))
    }

    @Test
    fun `a parent's option value that names a child stays the value`() {
        assertEquals("built in build fast=false", app.bindText("-w", "build", "build"))
    }

    @Test
    fun `a parent's option after its child's name is refused`() {
        assertEquals(CliError.UnknownOption("-v"), app.parse(listOf("remote", "add", "-v", "o")).assertError())
        assertEquals(CliError.UnknownOption("--workdir"), app.parse(listOf("build", "--workdir", "d")).assertError())
    }

    private val required = cli("app") {
        option(short = 'x', long = "xx").required()
        option(short = 'n').int()
        command("build") {
            flag(short = 'f', long = "fast")
            action { Ok("built") }
        }
    }

    @Test
    fun `a mistake in the child's options outranks a parent's missing required option`() {
        assertEquals(CliError.UnknownOption("--bogus"), required.parse(listOf("build", "--bogus")).assertError())
        assertEquals(CliError.UnknownOption("-k"), required.parse(listOf("build", "-k", "abc")).assertError())
    }

    @Test
    fun `a parent's missing required option is refused once the options are read`() {
        assertEquals(CliError.MissingRequiredOption("--xx"), required.parse(listOf("build", "-f")).assertError())
    }

    @Test
    fun `a mistake on the parent's segment outranks one in the child's options`() {
        assertIs<CliError.BadValue>(required.parse(listOf("-n", "abc", "build", "--bogus")).assertError())
    }

    @Test
    fun `a child's option before its name is refused`() {
        assertEquals(CliError.UnknownOption("-f"), app.parse(listOf("-f", "build")).assertError())
    }

    @Test
    fun `the end of options after a parent's option stops routing`() {
        assertIs<Result.Error<CliError>>(app.parse(listOf("remote", "-v", "--", "add", "o")))
    }

    @Test
    fun `help after a parent's option documents the child it reaches`() {
        val help = assertIs<Invocation.ShowHelp>(app.parse(listOf("remote", "-v", "add", "--help")).assertSuccess())
        assertEquals("app remote add", help.qualifiedName)
    }

    @Test
    fun `tab offers the children after a parent's option and then the child's own options`() {
        assertEquals(listOf("add"), app.offered("remote", "-v", ""))
        assertEquals(listOf("build", "remote"), app.offered("-w", "d", ""))
        val own = listOf("--fast", "-f", "--workdir", "-w")
        assertEquals(listOf("--fast", "-f"), app.offered("-w", "d", "build", "-").filter { it in own })
    }
}
