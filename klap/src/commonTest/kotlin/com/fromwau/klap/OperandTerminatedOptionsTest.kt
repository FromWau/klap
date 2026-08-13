package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
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
    fun `options before the first operand still bind`() {
        assertEquals("v=true l=root cmd=[]", sshLike().bindText("-v", "-l", "root", "web1"))
    }

    @Test
    fun `a dash led token after the first operand is an operand`() {
        assertEquals("v=false l=null cmd=[ls, -la]", sshLike().bindText("web1", "ls", "-la"))
    }

    @Test
    fun `a declared short after the first operand stays with the operands`() {
        // The silent theft the switch prevents: `-l` is declared, so under permutation `ssh web1 ls -la`
        // binds login = "a" and exits 0 with the `-la` cluster consumed locally.
        assertEquals("v=false l=null cmd=[tar, -C, /src]", sshLike().bindText("web1", "tar", "-C", "/src"))
    }

    @Test
    fun `an undeclared option after the first operand is an operand not an error`() {
        assertEquals("v=false l=null cmd=[grep, -x, pat]", sshLike().bindText("web1", "grep", "-x", "pat"))
    }

    @Test
    fun `an undeclared tail cluster carrying a global's letter stays an operand`() {
        // Only a cluster resolving in FULL is the ambiguous one. `-cvf` is a word that happens to contain
        // a global's letter, and refusing it would take every tar-shaped tail with it.
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("run") {
                optionsEndAtFirstOperand = true
                val rest = argument("rest").multiple()
                action<String>(human = { it }) { Ok("rest=${rest()}") }
            }
        }
        assertEquals("rest=[tar, -cvf, x]", tree.bindText("run", "tar", "-cvf", "x"))
    }

    @Test
    fun `the end of options marker ends options under the switch too`() {
        assertEquals("v=true l=null cmd=[ls, -la]", sshLike().bindText("-v", "--", "web1", "ls", "-la"))
    }

    @Test
    fun `the switch is off by default so options permute`() {
        val permuting = cli("t") {
            val v = flag("--verbose", "-v")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("v=${v()} files=${files()}") }
        }
        assertEquals("v=true files=[f1, f2]", permuting.bindText("f1", "-v", "f2"))
    }

    @Test
    fun `an unknown option before the first operand still errors`() {
        val err = assertIs<Result.Error<CliError>>(sshLike().parse(listOf("-x", "web1"))).error
        assertEquals(CliError.UnknownOption("-x"), err)
    }

    @Test
    fun `a mixed cluster after the first operand is refused`() {
        // Neither reading survives here: the cluster cannot split (that would bind -f past the end of
        // options) and cannot pass through whole (that would drop -s silently), so it is refused.
        val tree = cli("app") {
            optionsEndAtFirstOperand = true
            val sort = globalOption("--sort", "-s")
            val force = flag("--force", "-f")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("sort=${sort()} force=${force()} files=${files()}") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("f1", "-fs", "x", "f2"))).error
        assertEquals(CliError.MixedClusterAfterOperands("-fs", "-s"), err)
    }

    @Test
    fun `a cluster of only global shorts after the first operand still binds`() {
        // The neighbour of the refusal above, and the reason it can stay narrow: siftGlobals claims a
        // wholly-global cluster before this command ever sifts, so it never reaches that branch.
        val tree = cli("app") {
            val sort = globalOption("--sort", "-s")
            val verbose = globalFlag("--verbose", "-v")
            command("run") {
                optionsEndAtFirstOperand = true
                val files = argument("file").multiple()
                action<String>(human = { it }) { Ok("sort=${sort()} verbose=${verbose()} files=${files()}") }
            }
        }
        assertEquals("sort=x verbose=true files=[f1]", tree.bindText("run", "f1", "-vs", "x"))
    }

    @Test
    fun `a mixed cluster after the separator stays a literal operand`() {
        val tree = cli("app") {
            optionsEndAtFirstOperand = true
            val sort = globalOption("--sort", "-s")
            flag("--force", "-f")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("sort=${sort()} files=${files()}") }
        }
        assertEquals("sort=null files=[-fs]", tree.bindText("--", "-fs"))
    }
}
