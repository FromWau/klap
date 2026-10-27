package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals

/** find's pre-path switches: `-P` and `-L` flags, `-D` taking a value, `-O` an optional one, then the paths. */
private fun findLike(mode: ShortOptions? = null): Cli = cli("find") {
    mode?.let { shortOptions = it }
    val physical = flag(short = 'P', help = "never follow symbolic links")
    val logical = flag(short = 'L', help = "follow symbolic links")
    val debug = option(short = 'D', help = "print diagnostics")
    val level = option(short = 'O', help = "optimisation level").int().optionalValue()
    val paths = argument("path").file().multiple()
    action<String>(human = { it }) {
        Ok("P=${physical()} L=${logical()} D=${debug()} O=${level()} paths=${paths()}")
    }
}

/** A tool with a global `-v` beside a subcommand's own `-f`. */
private fun withGlobal(): Cli = cli("tool") {
    shortOptions = ShortOptions.Alone
    val verbose = globalFlag(short = 'v')
    val quiet = globalFlag(short = 'q')
    command("build") {
        val force = flag(short = 'f')
        action<String>(human = { it }) { Ok("v=${verbose()} q=${quiet()} f=${force()}") }
    }
}

private fun Cli.unknown(vararg args: String): CliError.UnknownOption =
    parse(args.toList()).assertError<CliError.UnknownOption>()

class ShortOptionsTest {

    @Test
    fun `shorts cluster by default as getopt reads them`() {
        assertEquals("P=true L=true D=tree O=null paths=[.]", findLike().bindText("-LP", "-Dtree", "."))
    }

    @Test
    fun `each short binds as a word of its own when shorts stand alone`() {
        // `find -L -P -D tree -O3 .` runs.
        assertEquals(
            "P=true L=true D=tree O=Given(value=3) paths=[.]",
            findLike(ShortOptions.Alone).bindText("-L", "-P", "-D", "tree", "-O3", "."),
        )
    }

    @Test
    fun `a cluster is one unknown option when shorts stand alone`() {
        // `find -LP .`, `find -PL .` and `find -LL .`: "unknown predicate `-LP'".
        val tool = findLike(ShortOptions.Alone)
        assertEquals(CliError.UnknownOption("-LP"), tool.unknown("-LP", "."))
        assertEquals(CliError.UnknownOption("-PL"), tool.unknown("-PL", "."))
        assertEquals(CliError.UnknownOption("-LL"), tool.unknown("-LL", "."))
        assertEquals(CliError.UnknownOption("-LO3"), tool.unknown("-LO3", "."))
        assertEquals(CliError.UnknownOption("-L=1"), tool.unknown("-L=1", "."))
    }

    @Test
    fun `a required value glued to its short is an unknown option when shorts stand alone`() {
        // `find -Dtree .`: "unknown predicate `-Dtree'"; `find -D tree .` runs.
        val tool = findLike(ShortOptions.Alone)
        assertEquals(CliError.UnknownOption("-Dtree"), tool.unknown("-Dtree", "."))
        assertEquals(CliError.UnknownOption("-D=tree"), tool.unknown("-D=tree", "."))
        assertEquals("P=false L=false D=tree O=null paths=[.]", tool.bindText("-D", "tree", "."))
    }

    @Test
    fun `an optional value stays glued when shorts stand alone`() {
        // getopt's optional argument is glued only: `find -O3 .` runs, and the word after a bare `-O` is no value.
        val tool = findLike(ShortOptions.Alone)
        assertEquals("P=false L=false D=null O=Given(value=3) paths=[.]", tool.bindText("-O3", "."))
        assertEquals("P=false L=false D=null O=Bare paths=[3, .]", tool.bindText("-O", "3", "."))
    }

    @Test
    fun `a refused cluster takes no value and leaves the next word to the operands`() {
        // `find -LD tree .`: "unknown predicate `-LD'", so `tree` is no value of `-D`.
        val tool = findLike(ShortOptions.Alone)
        assertEquals(CliError.UnknownOption("-LD"), tool.unknown("-LD", "tree", "."))
        assertEquals(listOf(COMPLETE_FILES), tool.offered("-LD", ""))
        assertEquals(listOf(COMPLETE_FILES), tool.offered("-L", "-D", "tree", ""))
    }

    @Test
    fun `a global short stands alone too`() {
        val tool = withGlobal()
        assertEquals("v=true q=true f=true", tool.bindText("-v", "build", "-q", "-f"))
        assertEquals(CliError.UnknownOption("-vq"), tool.unknown("-vq", "build"))
        assertEquals(CliError.UnknownOption("-fv"), tool.unknown("build", "-fv"))
        assertEquals(CliError.UnknownOption("-vf"), tool.unknown("build", "-vf"))
    }

    @Test
    fun `a command spelled as a short is named only by a word of its own when shorts stand alone`() {
        val tool = cli("pac") {
            shortOptions = ShortOptions.Alone
            command(short = 'Q', long = "query") {
                val info = flag(short = 'i')
                action<String>(human = { it }) { Ok("Q i=${info()}") }
            }
        }
        assertEquals("Q i=true", tool.bindText("-Q", "-i"))
        assertEquals(CliError.UnknownOption("-Qi"), tool.unknown("-Qi"))
    }

    @Test
    fun `a refused cluster fills a dash-led operand as an unknown word does`() {
        val tool = cli("tool") {
            shortOptions = ShortOptions.Alone
            val all = flag(short = 'a')
            val position = argument("position").dashLed()
            action<String>(human = { it }) { Ok("a=${all()} position=${position()}") }
        }
        assertEquals("a=false position=-aa", tool.bindText("-aa"))
        assertEquals("a=true position=-1", tool.bindText("-a", "-1"))
    }

    @Test
    fun `a run of the number option reads as the number when shorts stand alone`() {
        val tool = cli("head") {
            shortOptions = ShortOptions.Alone
            val quiet = flag(short = 'q')
            val lines = numberOption().int()
            action<String>(human = { it }) { Ok("q=${quiet()} n=${lines()}") }
        }
        assertEquals("q=false n=25", tool.bindText("-25"))
        assertEquals(CliError.UnknownOption("-q5"), tool.unknown("-q5"))
    }

    @Test
    fun `the help short asks for help only as a word of its own when shorts stand alone`() {
        val tool = findLike(ShortOptions.Alone)
        assertEquals(CliError.UnknownOption("-Lh"), tool.unknown("-Lh"))
        assertEquals(CliError.UnknownOption("-hL"), tool.unknown("-hL"))
    }

    @Test
    fun `a short is offered alone with no cluster continuations when shorts stand alone`() {
        val tool = findLike(ShortOptions.Alone)
        assertEquals(listOf("-L"), tool.offered("-L"))
        assertEquals(listOf("-P"), tool.offered("-L", "-P"))
        assertEquals(emptyList(), tool.offered("-LP"))
        assertEquals(emptyList(), tool.offered("-Dtr"))
        // By default `-L` goes on with every short it can cluster with.
        assertEquals(listOf("-LD", "-LO", "-LP"), findLike().offered("-L"))
    }

    @Test
    fun `an unknown cluster renders as the one word it is`() {
        val terminal = RecordingTerminal()
        findLike(ShortOptions.Alone).run(arrayOf("-LP", "."), terminal)
        assertEquals("error: unknown option '-LP'", terminal.err.lines().first())
    }

    @Test
    fun `help lists each short as it does under clustering`() {
        assertEquals(findLike().helpText(), findLike(ShortOptions.Alone).helpText())
    }
}
