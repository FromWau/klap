package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `ownOptions(shortOptions, abbreviation)` sets how one command reads its own segment, as git's root reads each word
 * as a whole option (`git -Csub status` and `git --git-d=.git status` are unknown) while its commands cluster and
 * abbreviate (`git status -sb`, `git status --bran`).
 */
class OwnOptionsTest {

    private val git = cli("git") {
        version = Version.Of("2.56.0")
        abbreviation = Abbreviation.Options
        ownOptions(shortOptions = ShortOptions.Alone, abbreviation = Abbreviation.None)
        val directory = option(short = 'C', help = "run as if started in <path>")
        val paginate = flag(short = 'p', long = "paginate")
        val noPager = flag(short = 'P', long = "no-pager")
        val gitDir = option(long = "git-dir")
        command("status") {
            val short = flag(short = 's', long = "short")
            val branch = flag(short = 'b', long = "branch")
            action {
                Ok("C=${directory()} p=${paginate()} P=${noPager()} dir=${gitDir()} s=${short()} b=${branch()}")
            }
        }
        action { Ok("root") }
    }

    private fun refused(vararg words: String) =
        assertIs<Result.Error<CliError>>(git.parse(words.toList()), words.joinToString(" "))

    @Test
    fun `the root's shorts stand alone and take their value as the next word`() {
        assertEquals("C=sub p=true P=true dir=null s=false b=false", git.bindText("-C", "sub", "-p", "-P", "status"))
        refused("-Csub", "status")
        refused("-pP", "status")
    }

    @Test
    fun `the root's longs are matched whole`() {
        assertEquals("C=null p=false P=false dir=d s=false b=false", git.bindText("--git-dir=d", "status"))
        refused("--git-d=d", "status")
        refused("--pag", "status")
        refused("--vers")
        assertIs<Invocation.ShowVersion>(git.parse(listOf("--version")).assertSuccess())
    }

    @Test
    fun `a command without the call reads by the tree's settings`() {
        assertEquals("C=null p=false P=false dir=null s=true b=true", git.bindText("status", "-sb"))
        assertEquals("C=null p=false P=false dir=null s=false b=true", git.bindText("status", "--bran"))
    }

    @Test
    fun `tab continues a cluster only where the command clusters`() {
        assertEquals(listOf("-p"), git.offered("-p"))
        assertTrue("-sb" in git.offered("status", "-s"))
    }

    @Test
    fun `a command below the root reads its own segment by its own call`() {
        val tool = cli("tool") {
            command("remote") {
                ownOptions(shortOptions = ShortOptions.Alone, abbreviation = Abbreviation.LongOptions)
                val verbose = flag(short = 'v', long = "verbose")
                val quiet = flag(short = 'q', long = "quiet")
                command("add") {
                    val force = flag(short = 'f', long = "force")
                    val yes = flag(short = 'y', long = "yes")
                    action { Ok("v=${verbose()} q=${quiet()} f=${force()} y=${yes()}") }
                }
                action { Ok("v=${verbose()} q=${quiet()}") }
            }
        }
        assertEquals("v=true q=true", tool.bindText("remote", "-v", "--qui"))
        assertIs<Result.Error<CliError>>(tool.parse(listOf("remote", "-vq")))
        assertEquals("v=true q=false f=true y=true", tool.bindText("remote", "-v", "add", "-fy"))
    }
}
