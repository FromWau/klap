package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** cp's shape: `-S` takes the last of its values, `-t` refuses a second one, and the rest repeat freely. */
private fun cpLike(policy: RepeatedOptions? = null): Cli = cli("cp") {
    policy?.let { repeatedOptions = it }
    val suffix = option(short = 'S', long = "suffix", help = "backup suffix")
    val target = option(short = 't', long = "target-directory", help = "copy into DIRECTORY")
        .file()
        .repeated(RepeatedOptions.Refuse)
    val verbose = flag(short = 'v', long = "verbose", help = "explain")
    val cache = flag(long = "cache").negatable()
    val tags = option(long = "tag").multiple()
    val debug = flag(short = 'd', long = "debug").count()
    val files = argument("file").multiple()
    action<String>(human = { it }) {
        Ok("S=${suffix()} t=${target()} v=${verbose()} cache=${cache()} tags=${tags()} d=${debug()} files=${files()}")
    }
}

private fun Cli.repeated(vararg args: String): CliError.RepeatedOption =
    parse(args.toList()).assertError<CliError.RepeatedOption>()

class RepeatedOptionsTest {

    @Test
    fun `an option given twice takes its last value by default`() {
        // `cp -S .x -S .y a b` backs up with `.y`, as `ls --sort=size --sort=time` sorts by time.
        assertEquals(
            "S=.y t=null v=true cache=true tags=[] d=0 files=[a]",
            cpLike().bindText("-S", ".x", "-v", "-S", ".y", "-v", "a"),
        )
    }

    @Test
    fun `an option that refuses a repeat refuses its second occurrence in any spelling`() {
        // `cp -t d -t e a`: "multiple target directories specified".
        val tool = cpLike()
        assertEquals(CliError.RepeatedOption("--target-directory"), tool.repeated("-t", "d", "-t", "e", "a"))
        tool.repeated("-t", "d", "--target-directory=e", "a")
        tool.repeated("--target-directory", "d", "-vte", "a")
        tool.repeated("-t", "d", "a", "-t", "d")
    }

    @Test
    fun `an option that refuses a repeat binds when given once`() {
        assertEquals("S=null t=d v=false cache=true tags=[] d=0 files=[a]", cpLike().bindText("-t", "d", "a"))
    }

    @Test
    fun `a cli that refuses repeats refuses every option and flag given twice`() {
        val tool = cpLike(RepeatedOptions.Refuse)
        assertEquals(CliError.RepeatedOption("--suffix"), tool.repeated("-S", ".x", "-S", ".y"))
        assertEquals(CliError.RepeatedOption("--verbose"), tool.repeated("-v", "-v"))
        tool.repeated("-vv")
        tool.repeated("-v", "--verbose")
        // Both forms of a negatable flag set one input.
        assertEquals(CliError.RepeatedOption("--cache"), tool.repeated("--cache", "--no-cache"))
    }

    @Test
    fun `a multiple option and a counted flag collect under a cli that refuses repeats`() {
        assertEquals(
            "S=null t=null v=false cache=true tags=[a, b] d=3 files=[]",
            cpLike(RepeatedOptions.Refuse).bindText("--tag", "a", "--tag", "b", "-dd", "--debug"),
        )
    }

    @Test
    fun `an option may take the last of its values under a cli that refuses repeats`() {
        val tool = cli("tool") {
            repeatedOptions = RepeatedOptions.Refuse
            val suffix = option(short = 'S', long = "suffix").repeated(RepeatedOptions.LastWins)
            val verbose = flag(short = 'v').repeated(RepeatedOptions.LastWins)
            action<String>(human = { it }) { Ok("S=${suffix()} v=${verbose()}") }
        }
        assertEquals("S=.y v=true", tool.bindText("-S", ".x", "-v", "-S", ".y", "-v"))
    }

    @Test
    fun `repeated on an input that collects is refused when the cli is built`() {
        assertFailsWith<IllegalArgumentException> {
            cli("tool") {
                option(long = "tag").multiple().repeated(RepeatedOptions.Refuse)
                action { Ok("") }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("tool") {
                option(long = "tag").repeated(RepeatedOptions.Refuse).multiple()
                action { Ok("") }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("tool") {
                flag(short = 'd').repeated(RepeatedOptions.Refuse).count()
                action { Ok("") }
            }
        }
    }

    @Test
    fun `a refused repeat renders as a usage error naming the option`() {
        val terminal = RecordingTerminal()
        val exit = cpLike().run(arrayOf("-t", "d", "-t", "e", "a"), terminal)
        assertEquals(USAGE_ERROR_EXIT, exit)
        assertTrue("'--target-directory' may be given only once" in terminal.err, "${terminal.err}")
        val json = RecordingTerminal().also { cpLike().run(arrayOf("--json", "-t", "d", "-t", "e"), it) }
        val text = json.out + json.err
        assertTrue("\"type\":\"RepeatedOption\"" in text && "\"option\":\"--target-directory\"" in text, text)
    }

    @Test
    fun `a refused repeat written before help refuses the line and one after it does not`() {
        // `cp -t a -t b --help`: "multiple target directories specified"; `cp --help -t a -t b` and
        // `cp -t a --help -t b` print the usage.
        val tool = cpLike()
        tool.repeated("-t", "a", "-t", "b", "--help")
        assertIs<Invocation.ShowHelp>(tool.parse(listOf("--help", "-t", "a", "-t", "b")).assertSuccess())
        assertIs<Invocation.ShowHelp>(tool.parse(listOf("-t", "a", "--help", "-t", "b")).assertSuccess())
    }

    private fun withGlobals(policy: RepeatedOptions? = null): Cli = cli("tool") {
        policy?.let { repeatedOptions = it }
        val config = globalOption(short = 'c', long = "config").repeated(RepeatedOptions.Refuse)
        val quiet = globalFlag(short = 'q', long = "quiet")
        command("run") {
            val fast = flag(short = 'f', long = "fast")
            action<String>(human = { it }) { Ok("c=${config()} q=${quiet()} f=${fast()}") }
        }
    }

    @Test
    fun `a global that refuses a repeat refuses it wherever the line gives it`() {
        val tool = withGlobals()
        assertEquals(CliError.RepeatedOption("--config"), tool.repeated("-c", "a", "run", "--config", "b"))
        tool.repeated("run", "-c", "a", "-c", "b")
        assertEquals("c=a q=true f=false", tool.bindText("-q", "run", "-c", "a", "-q"))
    }

    @Test
    fun `a global flag repeated in a cluster with a local one is refused under a cli that refuses repeats`() {
        val tool = withGlobals(RepeatedOptions.Refuse)
        assertEquals(CliError.RepeatedOption("--quiet"), tool.repeated("-q", "run", "-fq"))
        assertEquals("c=null q=true f=true", tool.bindText("run", "-fq"))
    }

    @Test
    fun `completion leaves out an option once the line holds a refused repeat of it`() {
        val tool = cpLike()
        val target = listOf("-t", "--target-directory")
        for (line in listOf(listOf("-t", "d", "-"), listOf("-vtd", "-"), listOf("-t", "d", "--tar"))) {
            val offered = tool.offered(*line.toTypedArray())
            assertTrue(target.none { it in offered }, "$line offered $offered")
        }
        assertTrue(target.none { it in tool.offeredAt(0, "-", "-t", "d") })
        assertTrue(target.none { it in tool.offeredAt(0, "--tar", "--target-directory=d") })
        // `-S` takes its last value, so it is offered again.
        assertTrue("-S" in tool.offered("-S", ".x", "-"))
    }

    @Test
    fun `completion offers no value to a refused repeat`() {
        val tool = cpLike()
        assertEquals(emptyList(), tool.offered("-t", "d", "-t", ""))
        assertEquals(emptyList(), tool.offered("-t", "d", "--target-directory="))
        assertEquals(emptyList(), tool.offeredAt(1, "-t", "", "-t", "d"))
    }

    @Test
    fun `completion continues no cluster holding a refused repeat`() {
        val tool = cpLike(RepeatedOptions.Refuse)
        assertEquals(emptyList(), tool.offered("-v", "-dv"))
        assertEquals(emptyList(), tool.offeredAt(0, "-dv", "-v"))
        val continued = tool.offered("-v", "-d")
        assertTrue("-dv" !in continued && "-dd" in continued, "$continued")
    }

    @Test
    fun `completion leaves out a refusing global the line already holds`() {
        val tool = withGlobals(RepeatedOptions.Refuse)
        val offered = tool.offered("-q", "run", "-c", "a", "-")
        assertTrue(listOf("-c", "--config", "-q", "--quiet").none { it in offered }, "$offered")
        assertTrue("-f" in offered, "$offered")
    }
}
