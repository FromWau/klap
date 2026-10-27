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

/**
 * Commands spelled as flags, as pacman's operations are (todo 47 and 48): reached by `-Q` or `--query` anywhere
 * among the options, never by a word. The K7y to K7ah rows of the testing matrix, each as a parse and, where the
 * row is about Tab, as completion.
 */
class FlagCommandTest {

    private lateinit var verbose: Flag
    private lateinit var dbPath: Opt<String?>

    private val pac = cli("pac") {
        builtins { help = Help.ByCommand(short = 'h') }
        version = Version.Of("1.0", short = 'V')
        abbreviation = Abbreviation.LongOptions
        verbose = globalFlag(short = 'v', long = "verbose", help = "be verbose")
        dbPath = globalOption(short = 'b', long = "dbpath", help = "set an alternate database location")
        command(short = 'Q', long = "query") {
            help = "query the package database"
            val info = flag(short = 'i', long = "info", help = "view package information").count()
            val changelog = flag(short = 'c', long = "changelog", help = "view the changelog of a package")
            val packages = argument("package").multiple()
            action { Ok("Q info=${info()} c=${changelog()} v=${verbose()} b=${dbPath()} ${packages()}") }
        }
        command(short = 'R', long = "remove") {
            help = "remove packages from the system"
            val cascade = flag(short = 'c', long = "cascade", help = "remove packages and all that depend on them")
            val packages = argument("package").multiple()
            action { Ok("R c=${cascade()} ${packages()}") }
        }
        command(short = 'S', long = "sync") {
            help = "synchronize packages"
            val refresh = flag(short = 'y', long = "refresh", help = "download fresh package databases").count()
            val upgrade = flag(short = 'u', long = "sysupgrade", help = "upgrade installed packages").count()
            action { Ok("S y=${refresh()} u=${upgrade()}") }
        }
    }

    private fun bound(vararg argv: String): String = pac.bindText(*argv)

    private fun Cli.out(vararg argv: String): String {
        val terminal = RecordingTerminal()
        run(arrayOf(*argv), terminal)
        return terminal.out
    }

    private fun offered(vararg words: String): List<String> = pac.offered(*words)

    @Test
    fun `a flag-spelled family abbreviates against one table`() {
        // pacman's operations are options of one getopt_long table, so `pacman -Q --no` is ambiguous among every
        // `--no...` pacman has, a long of -S's included, and a long of another operation is named as resolved.
        val ambiguous = pac.parse(listOf("-Q", "--c")).assertError<CliError.AmbiguousOption>()
        assertTrue("--changelog" in ambiguous.candidates && "--cascade" in ambiguous.candidates, "$ambiguous")
        assertEquals("--cascade", pac.parse(listOf("-Q", "--cas")).assertError<CliError.UnknownOption>().token)
        assertEquals("Q info=0 c=true v=false b=null []", bound("-Q", "--ch"))
    }

    @Test
    fun `a flag-spelled command is reached by its short and its long and never by its word`() {
        assertEquals("Q info=0 c=false v=false b=null [pacman]", bound("-Q", "pacman"))
        assertEquals("Q info=0 c=false v=false b=null [pacman]", bound("--query", "pacman"))
        assertEquals("Q info=0 c=false v=false b=null [pacman]", bound("--que", "pacman"))
        pac.parse(listOf("query", "pacman")).assertError<CliError.UnknownSubcommand>()
        assertEquals(CliError.FlagTakesNoValue("--query"), pac.parse(listOf("--query=x")).assertError())
    }

    @Test
    fun `a line naming no flag-spelled command asks for one`() {
        pac.parse(listOf("pacman")).assertError<CliError.UnknownSubcommand>()
        assertIs<Invocation.ShowHelp>(pac.parse(emptyList()).assertSuccess())
    }

    @Test
    fun `K7y the command letter heading a cluster takes the rest as its own letters`() {
        assertEquals("Q info=1 c=false v=false b=null [pacman]", bound("-Qi", "pacman"))
        assertEquals("Q info=2 c=false v=false b=null [pacman]", bound("-Qii", "pacman"))
        assertEquals("S y=1 u=1", bound("-Syu"))
        assertTrue("-Qi" in offered("-Q"), "the command's own letters continue its cluster")
        assertTrue("-Qc" in offered("-Q"))
        assertTrue("-Qv" in offered("-Q"), "and the globals")
        assertTrue("-Qy" !in offered("-Q"), "another command's letter does not")
    }

    @Test
    fun `K7z the command letter picks the command wherever it stands in the cluster`() {
        assertEquals("Q info=1 c=false v=false b=null [pacman]", bound("-iQ", "pacman"))
        assertEquals("S y=1 u=1", bound("-ySu"))
        assertTrue("-iQc" in offered("-iQ"), "the cluster continues with the command's letters")
    }

    @Test
    fun `K7aa a global letter and the command letter share one cluster in any order`() {
        val expected = "Q info=1 c=false v=true b=null [pacman]"
        assertEquals(expected, bound("-vQi", "pacman"))
        assertEquals(expected, bound("-Qvi", "pacman"))
        assertEquals(expected, bound("-Qiv", "pacman"))
        assertTrue("-Qvi" in offered("-Qv"))
    }

    @Test
    fun `K7ab a global value-taking letter ends a cluster holding the command letter`() {
        val expected = "Q info=0 c=false v=true b=/tmp [pacman]"
        assertEquals(expected, bound("-Qvb/tmp", "pacman"))
        assertEquals(expected, bound("-Qvb", "/tmp", "pacman"))
        assertEquals(emptyList(), offered("-Qvb", ""), "the word after -Qvb is the database path")
    }

    @Test
    fun `K7ac the command's own letter may come before the command`() {
        assertEquals("Q info=1 c=false v=false b=null [pacman]", bound("-i", "-Q", "pacman"))
        assertEquals("Q info=1 c=false v=false b=null [pacman]", bound("pacman", "-i", "-Q"))
        assertEquals("Q info=1 c=false v=false b=null [pacman]", bound("--info", "--query", "pacman"))
        assertTrue("-Qi" in offered("-i", "-Q"))
    }

    @Test
    fun `K7ad two commands of one parent on a line are refused`() {
        pac.parse(listOf("-QS")).assertError<CliError.MutuallyExclusive>()
        pac.parse(listOf("-Q", "-S")).assertError<CliError.MutuallyExclusive>()
        pac.parse(listOf("--query", "--sync")).assertError<CliError.MutuallyExclusive>()
        // pacman refuses one operation named twice the same way: "only one operation may be used at a time".
        val repeated = pac.parse(listOf("-QQ")).assertError<CliError.RepeatedCommand>()
        assertEquals(CliError.RepeatedCommand("--query"), repeated)
        pac.parse(listOf("-Q", "--query")).assertError<CliError.RepeatedCommand>()
        val err = RecordingTerminal().also { pac.run(arrayOf("-QQ"), it) }.err
        assertTrue("'--query' may be given only once" in err, err)
        val json = RecordingTerminal().also { pac.run(arrayOf("--json", "-QQ"), it) }
        val text = json.out + json.err
        assertTrue("\"type\":\"RepeatedCommand\"" in text && "\"command\":\"--query\"" in text, text)
        assertEquals(emptyList(), offered("-Q", "-Q"))
        assertEquals(emptyList(), offered("-QS"), "nothing continues a cluster naming two commands")
        assertTrue(offered("-Q", "-").none { it in listOf("-S", "-R", "--sync", "--remove") })
    }

    @Test
    fun `K7ae a letter means what the command on the line gives it`() {
        assertEquals("Q info=0 c=true v=false b=null [pacman]", bound("-Qc", "pacman"))
        assertEquals("R c=true [pacman]", bound("-Rc", "pacman"))
        assertEquals("R c=true [pacman]", bound("-c", "-R", "pacman"))
    }

    @Test
    fun `K7af the help and version shorts beside the command letter`() {
        val help = pac.parse(listOf("-Qh")).assertSuccess()
        assertEquals("pac --query", assertIs<Invocation.ShowHelp>(help).qualifiedName)
        assertIs<Invocation.ShowHelp>(pac.parse(listOf("-hQ")).assertSuccess())
        assertIs<Invocation.ShowVersion>(pac.parse(listOf("-QV")).assertSuccess())
    }

    @Test
    fun `K7ah a cluster after a flag-spelled command's word subcommand mixes globals and that subcommand's letters`() {
        // No real tool has the shape; klap reads it as it reads each part alone.
        val tool = cli("tool") {
            val verbose = globalFlag(short = 'v', long = "verbose")
            command(short = 'Q', long = "query") {
                command("list") {
                    val installed = flag(short = 'i', long = "installed")
                    val pattern = argument("pattern")
                    action { Ok("v=${verbose()} i=${installed()} ${pattern()}") }
                }
            }
        }
        assertEquals("v=true i=true x", tool.bindText("-Q", "list", "-vi", "x"))
        assertEquals("v=true i=true x", tool.bindText("-Q", "list", "-iv", "x"))
        assertEquals("v=true i=true x", tool.bindText("list", "-Q", "-vi", "x"))
        assertTrue("-vi" in tool.offered("-Q", "list", "-v"), "the subcommand's letter continues the global's cluster")
        assertTrue("list" in tool.offered("-Q", ""), "the flag-spelled command's word subcommand is offered")
    }

    @Test
    fun `the word before the command holds the command as a value only when a value-taking letter ends it`() {
        // getopt -o b:r:Q reads `-br -Q pkg` as -b with the value r, then -Q.
        val tool = cli("tool") {
            command(short = 'Q', long = "query") {
                val dbPath = option(short = 'b', long = "dbpath")
                val root = option(short = 'r', long = "root")
                val packages = argument("package").multiple()
                action { Ok("Q b=${dbPath()} r=${root()} ${packages()}") }
            }
        }
        assertEquals("Q b=r r=null [pkg]", tool.bindText("-br", "-Q", "pkg"))
        assertEquals("Q b=r r=null [pkg]", tool.bindText("-Q", "-br", "pkg"))
    }

    @Test
    fun `a declared flag spelled like a declined built-in takes no word from the command after it`() {
        val tool = cli("tool") {
            builtins { color = Builtin.Off }
            val color = globalFlag(long = "color")
            command(short = 'Q', long = "query") {
                val packages = argument("package").multiple()
                action { Ok("Q color=${color()} ${packages()}") }
            }
        }
        assertEquals("Q color=true [pkg]", tool.bindText("--color", "-Q", "pkg"))
        assertEquals("Q color=true [pkg]", tool.bindText("-Q", "--color", "pkg"))
    }

    private val pm = cli("pm") {
        val verbose = flag(short = 'v', long = "verbose")
        command(short = 'Q', long = "query") {
            val info = flag(short = 'i', long = "info")
            val packages = argument("package").multiple(min = 0)
            action { Ok("Q v=${verbose()} i=${info()} ${packages()}") }
        }
        command("word") {
            action { Ok("word v=${verbose()}") }
        }
    }

    @Test
    fun `the parent's own option binds on its own segment wherever the command's spelling stands`() {
        assertEquals("Q v=true i=false []", pm.bindText("-v", "-Q"))
        assertEquals("Q v=true i=false []", pm.bindText("-vQ"))
        assertEquals("Q v=true i=false []", pm.bindText("-Qv"))
        assertEquals("Q v=true i=false []", pm.bindText("-Q", "-v"))
        assertEquals("Q v=true i=false []", pm.bindText("--verbose", "--query"))
        assertEquals("Q v=true i=true [x]", pm.bindText("-i", "-v", "-Q", "x"))
        assertEquals("word v=true", pm.bindText("-v", "word"))
    }

    @Test
    fun `the parent's own option binds anywhere on a flag-spelled command's line`() {
        // pacman reads its whole line in one getopt loop: `pacman -Q pacman -v` exits 0, verbose.
        assertEquals("Q v=true i=false [x]", pm.bindText("-Q", "x", "-v"))
        assertEquals("Q v=true i=true [x, y]", pm.bindText("-Q", "x", "-v", "y", "-i"))
        assertEquals("Q v=false i=false [x, -v]", pm.bindText("-Q", "x", "--", "-v"))
    }

    @Test
    fun `a flag-spelled command may not declare a letter or long its parent's own option has`() {
        val letter = assertFailsWith<IllegalArgumentException> {
            cli("pm") {
                flag(short = 'c', long = "cache")
                command(short = 'Q', long = "query") {
                    flag(short = 'c', long = "changelog")
                    action { Ok("") }
                }
            }
        }
        assertTrue("-c" in letter.message.orEmpty() && "-Q" in letter.message.orEmpty(), letter.message)
        assertFailsWith<IllegalArgumentException> {
            cli("pm") {
                flag(long = "cache")
                command(short = 'Q', long = "query") {
                    option(long = "cache")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun `a word command may reuse a letter its parent's own option has`() {
        val git = cli("git") {
            val paginate = flag(short = 'p')
            command("log") {
                val patch = flag(short = 'p')
                action { Ok("paginate=${paginate()} patch=${patch()}") }
            }
        }
        assertEquals("paginate=true patch=true", git.bindText("-p", "log", "-p"))
    }

    @Test
    fun `tab after the parent's own option completes for the command its spelling names`() {
        val dash = pm.offered("-v", "-")
        assertTrue("-Q" in dash && "--query" in dash, dash.toString())
        val afterQuery = pm.offered("-v", "-Q", "-")
        assertTrue("-i" in afterQuery && "--info" in afterQuery, afterQuery.toString())
        assertTrue("-i" in pm.offered("-Q", "-v", "-"))
    }

    @Test
    fun `a flag-spelled command's long matches by the abbreviation of its parent's own segment`() {
        val tool = cli("pm") {
            abbreviation = Abbreviation.Options
            ownOptions(shortOptions = ShortOptions.Clustered, abbreviation = Abbreviation.None)
            command(short = 'Q', long = "query") { action { Ok("Q") } }
        }
        assertEquals("Q", tool.bindText("--query"))
        assertEquals(CliError.UnknownOption("--quer"), tool.parse(listOf("--quer")).assertError())
        val abbreviating = cli("pm") {
            ownOptions(shortOptions = ShortOptions.Clustered, abbreviation = Abbreviation.Options)
            command(short = 'Q', long = "query") { action { Ok("Q") } }
        }
        assertEquals("Q", abbreviating.bindText("--quer"))
    }

    @Test
    fun `run reads --json as the value of the command's option the parse gives it to`() {
        val tool = cli("pm") {
            command(short = 'Q', long = "query") {
                val db = option(short = 'b')
                val n = option(short = 'n').int()
                action { Ok("Q b=${db()} n=${n()}") }
            }
        }
        for (argv in listOf(arrayOf("-Qb", "--json", "-n", "x"), arrayOf("-Q", "-b", "--json", "-n", "x"))) {
            val terminal = RecordingTerminal()
            tool.run(argv, terminal)
            val err = terminal.err
            assertTrue("\"type\"" !in err && "\"type\"" !in terminal.out, "${argv.toList()}: $err")
            assertTrue("-n" in err, "${argv.toList()}: $err")
        }
    }

    @Test
    fun `a dash at the parent offers each command's spellings beside the globals`() {
        val dash = offered("-")
        assertTrue(listOf("-Q", "-R", "-S", "--query", "--remove", "--sync").all { it in dash }, dash.toString())
        assertTrue("-i" in dash, "a command's own letter binds before the command, so it is offered too")
        assertTrue("-vi" in offered("-v"), "and continues a cluster before the command")
        assertTrue("query" !in offered(""), "a flag-spelled command is no word")
    }

    @Test
    fun `help lists a flag-spelled command by its spellings and names it by its long`() {
        val help = pac.out("--help")
        assertTrue("  -Q, --query" in help, help)
        assertTrue("\n  query " !in help, help)
        assertTrue("usage: pac --query" in pac.out("-Q", "--help"))
        // The tree names it the same way; pacman has no --help-all, so the docs are where this tree is rendered.
        assertTrue("usage: pac --sync" in pac.renderMarkdownDocs(), pac.renderMarkdownDocs())
    }

    @Test
    fun `a flag-spelled command takes no aliases and its spelling names nothing else`() {
        assertFailsWith<IllegalArgumentException> {
            cli("t") {
                command(short = 'Q', long = "query") {
                    aliases = listOf("q")
                    action { Ok("") }
                }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("t") {
                globalFlag(short = 'Q', long = "quiet")
                command(short = 'Q', long = "query") { action { Ok("") } }
            }
        }
        assertFailsWith<IllegalArgumentException> {
            cli("t") {
                command(short = 'Q', long = "query") { action { Ok("") } }
                command(short = 'Q', long = "quit") { action { Ok("") } }
            }
        }
        assertFailsWith<IllegalArgumentException>("the command's own negative short is a spelling too") {
            cli("t") {
                command(short = 'Q', long = "query") {
                    flag(long = "quiet").negatable(short = 'Q')
                    action { Ok("") }
                }
            }
        }
    }
}
