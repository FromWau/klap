package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperandTerminatedOptionsTest {

    private fun sshLike() = cli("ssh") {
        optionsEndAtFirstOperand = true
        val verbose = flag(short = 'v', long = "verbose")
        val login = option(short = 'l', long = "login")
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
            globalFlag(short = 'v', long = "verbose")
            command("run") {
                optionsEndAtFirstOperand = true
                val rest = argument("rest").multiple()
                action<String>(human = { it }) { Ok("rest=${rest()}") }
            }
        }
        assertEquals("rest=[tar, -cvf, x]", tree.bindText("run", "tar", "-cvf", "x"))
    }

    @Test
    fun `a word after the first operand is no value of the command's option`() {
        assertEquals("v=false l=null cmd=[ls, -l]", sshLike().bindText("web1", "ls", "-l", "--color=never"))
    }

    @Test
    fun `the end of options marker ends options under the switch too`() {
        assertEquals("v=true l=null cmd=[ls, -la]", sshLike().bindText("-v", "--", "web1", "ls", "-la"))
    }

    @Test
    fun `the switch is off by default so options permute`() {
        val permuting = cli("t") {
            val v = flag(short = 'v', long = "verbose")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("v=${v()} files=${files()}") }
        }
        assertEquals("v=true files=[f1, f2]", permuting.bindText("f1", "-v", "f2"))
    }

    @Test
    fun `an unknown option before the first operand still errors`() {
        val err = sshLike().parse(listOf("-x", "web1")).assertError<CliError.UnknownOption>()
        assertEquals(CliError.UnknownOption("-x"), err)
    }

    @Test
    fun `a mixed cluster after the first operand is refused`() {
        // Neither reading survives here: the cluster cannot split (that would bind -f past the end of
        // options) and cannot pass through whole (that would drop -s silently), so it is refused.
        val tree = cli("app") {
            optionsEndAtFirstOperand = true
            val sort = globalOption(short = 's', long = "sort")
            val force = flag(short = 'f', long = "force")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("sort=${sort()} force=${force()} files=${files()}") }
        }
        val err = tree.parse(listOf("f1", "-fs", "x", "f2")).assertError<CliError.MixedClusterAfterOperands>()
        assertEquals(CliError.MixedClusterAfterOperands("-fs", "-s"), err)
    }

    @Test
    fun `a cluster after the first operand whose global letter is a local option's value is an operand`() {
        // getopt with POSIXLY_CORRECT reads `x -fv` as the operands x and -fv; past -f no letter is a global.
        val tree = cli("app") {
            optionsEndAtFirstOperand = true
            val verbose = globalFlag(short = 'v', long = "verbose")
            val file = option(short = 'f', long = "file")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("v=${verbose()} f=${file()} files=${files()}") }
        }
        assertEquals("v=false f=null files=[x, -fv]", tree.bindText("x", "-fv"))
    }

    @Test
    fun `a cluster of only global shorts after the first operand still binds`() {
        // The neighbour of the refusal above, and the reason it can stay narrow: siftGlobals claims a
        // wholly-global cluster before this command ever sifts, so it never reaches that branch.
        val tree = cli("app") {
            val sort = globalOption(short = 's', long = "sort")
            val verbose = globalFlag(short = 'v', long = "verbose")
            command("run") {
                optionsEndAtFirstOperand = true
                val files = argument("file").multiple()
                action<String>(human = { it }) { Ok("sort=${sort()} verbose=${verbose()} files=${files()}") }
            }
        }
        assertEquals("sort=x verbose=true files=[f1]", tree.bindText("run", "f1", "-vs", "x"))
    }

    private fun withHelp() = cli("app") {
        builtins { help = Help.ByCommand(short = 'h') }
        optionsEndAtFirstOperand = true
        version = Version.Of("1.0", short = 'V')
        globalFlag(short = 'v', long = "verbose")
        flag(short = 'a', long = "all")
        argument("file").multiple()
        action<String>(human = { it }) { Ok("ran") }
    }

    @Test
    fun `the help short in a cluster with a local short after the first operand is refused`() {
        for (cluster in listOf("-ah", "-ha")) {
            val err = withHelp().parse(listOf("x", cluster)).assertError<CliError.MixedClusterAfterOperands>()
            assertEquals(CliError.MixedClusterAfterOperands(cluster, "-h"), err)
        }
        // The refused cluster comes first on the line, so a later -h does not answer.
        withHelp().parse(listOf("x", "-ah", "-h")).assertError<CliError.MixedClusterAfterOperands>()
        // The version short is klap's too, and reaches as far.
        assertEquals(
            CliError.MixedClusterAfterOperands("-aV", "-V"),
            withHelp().parse(listOf("x", "-aV")).assertError<CliError.MixedClusterAfterOperands>(),
        )
        assertIs<Invocation.ShowVersion>(withHelp().parse(listOf("x", "-V")).assertSuccess())
    }

    @Test
    fun `the help short reaches past the first operand alone or among globals`() {
        assertIs<Invocation.ShowHelp>(withHelp().parse(listOf("x", "-h")).assertSuccess())
        assertIs<Invocation.ShowHelp>(withHelp().parse(listOf("x", "-vh")).assertSuccess())
        assertIs<Invocation.ShowHelp>(withHelp().parse(listOf("-h", "x", "-ah")).assertSuccess())
    }

    @Test
    fun `a mixed cluster after the separator stays a literal operand`() {
        val tree = cli("app") {
            optionsEndAtFirstOperand = true
            val sort = globalOption(short = 's', long = "sort")
            flag(short = 'f', long = "force")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("sort=${sort()} files=${files()}") }
        }
        assertEquals("sort=null files=[-fs]", tree.bindText("--", "-fs"))
    }

    @Test
    fun `no option name of the command is offered after the first operand`() {
        val tree = sshLike()
        assertEquals("v=false l=null cmd=[ls, -v]", tree.bindText("web1", "ls", "-v"))
        val offered = tree.offered("web1", "ls", "-")
        val own = listOf("-v", "--verbose", "-l", "--login")
        assertTrue(offered.none { it in own }, "the remote command's word offers $offered")
    }
}

/** `Arg<T>.endsOptions()`: options permute until the first word bound to that argument, then end. */
class EndsOptionsTest {

    private fun sshLike() = cli("ssh") {
        val verbose = flag(short = 'v', long = "verbose")
        val login = option(short = 'l', long = "login")
        argument("destination")
        val command = argument("command").multiple().endsOptions()
        action<String>(human = { it }) {
            Ok("v=${verbose()} l=${login()} cmd=${command()}")
        }
    }

    @Test
    fun `options after an earlier operand still bind`() {
        assertEquals("v=true l=root cmd=[]", sshLike().bindText("web1", "-v", "-l", "root"))
    }

    @Test
    fun `the first word bound to the argument ends options`() {
        assertEquals("v=true l=null cmd=[ls, -v, -l, root]", sshLike().bindText("web1", "-v", "ls", "-v", "-l", "root"))
    }

    @Test
    fun `an end of options marker before that word is consumed`() {
        assertEquals("v=false l=null cmd=[-l, root]", sshLike().bindText("web1", "--", "-l", "root"))
    }

    @Test
    fun `a non final argument ends options at its own word`() {
        val tree = cli("run") {
            val verbose = flag(short = 'v', long = "verbose")
            val host = argument("host")
            val program = argument("program").endsOptions()
            val args = argument("arg").multiple()
            action<String>(human = { it }) { Ok("v=${verbose()} host=${host()} program=${program()} args=${args()}") }
        }
        assertEquals("v=true host=h program=p args=[-v]", tree.bindText("h", "-v", "p", "-v"))
        assertEquals("v=true host=h program=p args=[]", tree.bindText("-v", "h", "p"))
    }

    @Test
    fun `a word after that one is no value of the command's option`() {
        // Past the remote command's first word `-l` is the remote's own, so klap's `--color` after it is the
        // built-in it is anywhere else on the line, as it is past the first operand under the switch.
        assertEquals("v=false l=null cmd=[ls, -l]", sshLike().bindText("web1", "ls", "-l", "--color=never"))
    }

    @Test
    fun `an unknown option before that word is still an error`() {
        val err = sshLike().parse(listOf("web1", "-x", "ls")).assertError<CliError.UnknownOption>()
        assertEquals(CliError.UnknownOption("-x"), err)
    }

    @Test
    fun `the help short still reaches past that word`() {
        val tree = cli("ssh") {
            builtins { help = Help.ByCommand(short = 'h') }
            argument("destination")
            argument("command").multiple().endsOptions()
            action { Ok("ran") }
        }
        assertIs<Invocation.ShowHelp>(tree.parse(listOf("web1", "ls", "-h")).assertSuccess())
    }

    @Test
    fun `completion offers the options until that word`() {
        val offered = sshLike().offered("web1", "-")
        val own = listOf("-v", "--verbose", "-l", "--login")
        assertTrue(offered.containsAll(own), "the word after the destination offers $offered")
    }

    @Test
    fun `completion offers no option name of the command after that word`() {
        val offered = sshLike().offered("web1", "ls", "-")
        val own = listOf("-v", "--verbose", "-l", "--login")
        assertTrue(offered.none { it in own }, "the remote command's word offers $offered")
        assertTrue("--help" in offered, "klap's own names are still read there, offered $offered")
    }

    @Test
    fun `completion offers no value of the command's option after that word`() {
        val tree = cli("ssh") {
            option(short = 'l', long = "login").completeWith { candidate("root") }
            argument("destination")
            argument("command").multiple().endsOptions().completeWith { candidate("ls") }
            action { Ok("") }
        }
        assertEquals(listOf("root"), tree.offered("web1", "-l", ""))
        assertEquals(listOf("ls"), tree.offered("web1", "ls", "-l", ""))
        assertEquals(emptyList(), tree.offered("web1", "ls", "--login="))
    }

    @Test
    fun `an argument after a variadic cannot end options`() {
        val error = assertFailsWith<IllegalArgumentException> {
            cli("cp") {
                argument("source").multiple()
                argument("dest").endsOptions()
                action { Ok("") }
            }
        }
        assertTrue("endsOptions" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `a command cannot end options at its first operand and at an endsOptions argument`() {
        val error = assertFailsWith<IllegalArgumentException> {
            cli("ssh") {
                optionsEndAtFirstOperand = true
                argument("destination")
                argument("command").multiple().endsOptions()
                action { Ok("") }
            }
        }
        assertTrue("optionsEndAtFirstOperand" in error.message.orEmpty(), error.message)
    }
}
