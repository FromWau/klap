package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OperandTerminatedOptionsTest {

    private fun sshLike() = cli("ssh") {
        optionsEndAtFirstOperand = true
        val verbose = flag("--verbose", "-v")
        val login = option("--login", "-l")
        argument("destination")
        val command = argument("command").multiple()
        action<String>(human = { it }) {
            Ok("v=${verbose()} l=${login()} cmd=${command()}")
        }
    }

    @Test
    fun optionsBeforeTheFirstOperandStillBind() {
        assertEquals("v=true l=root cmd=[]", sshLike().bindText("-v", "-l", "root", "web1"))
    }

    @Test
    fun aDashLedTokenAfterTheFirstOperandIsAnOperand() {
        assertEquals("v=false l=null cmd=[ls, -la]", sshLike().bindText("web1", "ls", "-la"))
    }

    @Test
    fun aDeclaredShortAfterTheFirstOperandStaysWithTheOperands() {
        // The silent theft the switch prevents: `-l` is declared, so under permutation `ssh web1 ls -la`
        // binds login = "a" and exits 0 with the `-la` cluster consumed locally.
        assertEquals("v=false l=null cmd=[tar, -C, /src]", sshLike().bindText("web1", "tar", "-C", "/src"))
    }

    @Test
    fun anUndeclaredOptionAfterTheFirstOperandIsAnOperandNotAnError() {
        assertEquals("v=false l=null cmd=[grep, -x, pat]", sshLike().bindText("web1", "grep", "-x", "pat"))
    }

    @Test
    fun theEndOfOptionsMarkerEndsOptionsUnderTheSwitchToo() {
        assertEquals("v=true l=null cmd=[ls, -la]", sshLike().bindText("-v", "--", "web1", "ls", "-la"))
    }

    @Test
    fun theSwitchIsOffByDefaultSoOptionsPermute() {
        val permuting = cli("t") {
            val v = flag("--verbose", "-v")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("v=${v()} files=${files()}") }
        }
        assertEquals("v=true files=[f1, f2]", permuting.bindText("f1", "-v", "f2"))
    }

    @Test
    fun anUnknownOptionBeforeTheFirstOperandStillErrors() {
        val err = assertIs<Result.Error<CliError>>(sshLike().parse(listOf("-x", "web1"))).error
        assertEquals(CliError.UnknownOption("-x"), err)
    }

    @Test
    fun aMixedClusterAfterTheFirstOperandDropsTheGlobal() {
        // siftGlobals leaves a global-plus-local cluster whole for THIS command's own sift to split, but
        // that split never runs once f1 has already ended options, so -fs and its value x bind as literal
        // operands instead of splitting into force=true sort=x.
        val tree = cli("app") {
            optionsEndAtFirstOperand = true
            val sort = globalOption("--sort", "-s")
            val force = flag("--force", "-f")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("sort=${sort()} force=${force()} files=${files()}") }
        }
        assertEquals("sort=null force=false files=[f1, -fs, x, f2]", tree.bindText("f1", "-fs", "x", "f2"))
    }
}
