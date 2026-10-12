package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.klap.internal.render.helpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How GNU `getopt_long` reads a command line, executed against klap rather than asserted in prose (todo 56). klap
 * follows getopt_long; where a tool asks for a reading of its own (`optionsEndAtFirstOperand`, `.optionalValue()`,
 * `dashLed()`), its test sits beside the rule it changes. Where a comment quotes a tool, the line was run on GNU
 * coreutils 9.12 or grep.
 */
class GnuConformanceTest {

    /** The shape every rule below is exercised against: two flags, a value-taking option, operands. */
    private fun tree(): Cli = cli("util") {
        val a = flag(short = 'a', long = "all")
        val b = flag(short = 'b', long = "brief")
        val c = option(short = 'c', long = "config")
        val files = argument("file").multiple(min = 0)
        action { Ok("a=${a()} b=${b()} c=${c()} files=${files()}") }
    }

    private fun bind(vararg argv: String): String = tree().bindText(*argv)

    // --- Short options: one character after one dash, bundled into clusters ---

    @Test
    fun `a short option is one character`() {
        // A short is declared as a Char, so a multi-character short cannot be written; two shorts are two
        // characters, and `-ab` reads as the cluster of both.
        val tree = cli("util") {
            val a = flag(short = 'a')
            val b = flag(short = 'b')
            action { Ok("${a()} ${b()}") }
        }
        assertEquals("true true", tree.bindText("-ab"))
    }

    @Test
    fun `a short option may be a digit or any other character`() {
        // `curl -4` and curl's `-:`, which curl's own parser reads; klap takes any character as a short.
        val tree = cli("util") {
            val four = flag(short = '4')
            val next = flag(short = ':')
            action { Ok("${four()} ${next()}") }
        }
        assertEquals("true true", tree.bindText("-4", "-:"))
    }

    @Test
    fun `shorts group behind one dash`() {
        assertEquals("a=true b=true c=null files=[]", bind("-ab"))
    }

    @Test
    fun `a group may end in the option that takes a value`() {
        assertEquals("a=true b=true c=cfg files=[]", bind("-abc", "cfg"))
        // ...and the value may be attached to it, as `head -n5` takes it.
        assertEquals("a=true b=true c=cfg files=[]", bind("-abccfg"))
    }

    @Test
    fun `a short option's value may be its own word`() {
        assertEquals("a=false b=false c=cfg files=[]", bind("-c", "cfg"))
    }

    // --- Long options: a word after two dashes, its value attached with `=` or in the next word ---

    @Test
    fun `a long option is typed after two dashes`() {
        // A long is declared without its dashes and typed after `--`, so the word alone is an operand.
        val tree = cli("util") {
            val all = flag(long = "all")
            action { Ok(all().toString()) }
        }
        assertEquals("true", tree.bindText("--all"))
        tree.parse(listOf("all")).assertError<CliError.TooManyArguments>()
    }

    @Test
    fun `a long option's value is attached or the next word`() {
        assertEquals(bind("--config", "cfg"), bind("--config=cfg"))
        assertEquals("a=true b=false c=cfg files=[]", bind("--all", "--config", "cfg"))
    }

    @Test
    fun `a long option abbreviates to any unambiguous prefix`() {
        // `mkdir --par d` reaches --parents; `ls --s` is "option '--s' is ambiguous". klap's default reads names
        // so (todo 56b); `Abbreviation.None` opts out.
        assertEquals("a=true b=false c=cfg files=[f1]", bind("--al", "--conf", "cfg", "f1"))
        val ambiguous = cli("util") {
            flag(long = "size")
            flag(long = "sort")
            action { Ok("") }
        }
        ambiguous.parse(listOf("--s")).assertError<CliError.AmbiguousOption>()
        ambiguous.parse(listOf("--si")).assertSuccess()
    }

    // --- Values ---

    @Test
    fun `a value is required unless the option makes it optional`() {
        val err = tree().parse(listOf("--config")).assertError<CliError.MissingOptionValue>()
        assertEquals(CliError.MissingOptionValue("--config"), err)
    }

    @Test
    fun `an optional value is taken only when attached`() {
        // getopt_long's optional argument (`ls --color[=WHEN]`): the bare form takes the declared value and the next
        // word stays an operand, since an optional value cannot tell itself from one.
        val optional = cli("ls") {
            builtins { color = Builtin.Off }
            val color = option(long = "color").optionalValue("always")
            val files = argument("file").multiple(min = 0)
            action { Ok("color=${color()} files=${files()}") }
        }
        assertEquals("color=always files=[]", optional.bindText("--color"))
        assertEquals("color=never files=[]", optional.bindText("--color=never"))
        assertEquals("color=always files=[src]", optional.bindText("--color", "src"))
    }

    @Test
    fun `a comma separated value arrives whole`() {
        // klap does not split it; a tool that takes a list in one word splits it in its converter.
        assertEquals("a=false b=false c=x,y,z files=[]", bind("-c", "x,y,z"))
    }

    @Test
    fun `a value word may look like an option`() {
        // getopt gives the word after `-e` to `-e` whatever it is spelled, klap's own built-ins included, so
        // `mygrep -e --json f.txt` searches for `--json`.
        fun tree() = cliOf("mygrep") {
            val regexp = option(short = 'e', long = "regexp")
            val files = argument("file").multiple()
            action { Ok("") }
            projection { regexp() to files() }
        }
        assertEquals(Ok("-x" to listOf("f.txt")), tree().parse(listOf("-e", "-x", "f.txt")))
        assertEquals(Ok("--json" to listOf("f.txt")), tree().parse(listOf("-e", "--json", "f.txt")))
        // A value-taking built-in in a value slot is one string, and its would-be value an operand.
        assertEquals(
            Ok("--color" to listOf("never", "f.txt")),
            tree().parse(listOf("-e", "--color", "never", "f.txt")),
        )
        assertEquals(Ok("--js" to listOf("f.txt")), tree().parse(listOf("-e", "--js", "f.txt")))
    }

    // --- Options and operands mix; `--` ends the options ---

    @Test
    fun `options and operands mix in any order`() {
        // getopt permutes: an option after an operand is still an option.
        assertEquals("a=true b=false c=cfg files=[f1, f2]", bind("-a", "-c", "cfg", "f1", "f2"))
        assertEquals("a=true b=false c=null files=[f1, f2]", bind("f1", "-a", "f2"))
    }

    @Test
    fun `a command may end its options at the first operand`() {
        // getopt's leading `+` (or POSIXLY_CORRECT), and what ssh does at its host: the rest is the remote command.
        val strict = cli("t") {
            optionsEndAtFirstOperand = true
            val a = flag(short = 'a', long = "all")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("a=${a()} files=${files()}") }
        }
        assertEquals("a=true files=[f1, f2]", strict.bindText("-a", "f1", "f2"))
        assertEquals("a=false files=[f1, -a, f2]", strict.bindText("f1", "-a", "f2"))
    }

    @Test
    fun `the end of options makes every later word an operand`() {
        assertEquals("a=true b=false c=null files=[-b, --config]", bind("-a", "--", "-b", "--config"))
        // Only the first one ends them; a second is an operand.
        assertEquals("a=false b=false c=null files=[--, x]", bind("--", "--", "x"))
    }

    @Test
    fun `a -- in a value slot is that value and the options go on`() {
        // `ls --sort --` is "invalid argument '--' for '--sort'", and `grep -e -- -v` searches for `--` and still
        // reads `-v` as an option (todo 33).
        assertEquals("a=true b=false c=-- files=[x]", bind("--config", "--", "-a", "x"))
        assertEquals("a=false b=false c=-- files=[-a]", bind("-c", "--", "--", "-a"))
    }

    @Test
    fun `operand order is kept`() {
        assertEquals("a=false b=false c=null files=[z, a, m]", bind("z", "a", "m"))
    }

    @Test
    fun `a lone dash is an operand`() {
        assertEquals("a=false b=false c=null files=[-]", bind("-"))
        assertEquals("a=true b=false c=null files=[-, f]", bind("-a", "-", "f"))
    }

    // --- An undeclared option is an error ---

    @Test
    fun `a dash-led word is an option even when it is not declared`() {
        // `ls --bogus` exits 2, a usage error; an undeclared option is never a filename.
        val err = tree().parse(listOf("-w", "notes.txt")).assertError<CliError.UnknownOption>()
        assertEquals(CliError.UnknownOption("-w"), err)
        tree().parse(listOf("-5")).assertError<CliError.UnknownOption>()
    }

    @Test
    fun `an operand may opt in to a dash-led value`() {
        // `chmod -w f`: `dashLed()` lets one operand take a single-dash word that names no declared option.
        val plain = cli("app") { command("go") { argument("n"); action { Ok("") } } }
        plain.parse(listOf("go", "-5")).assertError<CliError.UnknownOption>()
        val optedIn = cli("app") { command("go") { argument("n").dashLed(); action { Ok("") } } }
        optedIn.parse(listOf("go", "-5")).assertSuccess()
    }

    @Test
    fun `a number option claims only what no declared short does`() {
        // `head -20`: a run of digits is the number unless every digit is a declared short, which wins.
        val tree = cli("head") {
            val two = flag(short = '2')
            val lines = numberOption().int()
            action { Ok("two=${two()} lines=${lines()}") }
        }
        assertEquals("two=true lines=null", tree.bindText("-2"))
        assertEquals("two=false lines=20", tree.bindText("-20"))
        val bothDeclared = cli("head") {
            val two = flag(short = '2')
            val zero = flag(short = '0')
            val lines = numberOption().int()
            action { Ok("two=${two()} zero=${zero()} lines=${lines()}") }
        }
        assertEquals("two=true zero=true lines=null", bothDeclared.bindText("-20"))
    }

    // --- Order between options ---

    @Test
    fun `option order does not matter by default`() {
        assertEquals(bind("-a", "-b"), bind("-b", "-a"))
    }

    @Test
    fun `the later of two overriding options wins`() {
        // `rm -i -f` forces and `rm -f -i` prompts; the set documents itself in --help.
        fun overriding() = cli("rm") {
            val i = flag(short = 'i', long = "interactive")
            val f = flag(short = 'f', long = "force")
            lastWins(i, f)
            action { Ok(if (f()) "force" else if (i()) "interactive" else "neither") }
        }
        assertEquals("force", overriding().bindText("-i", "-f"))
        assertEquals("interactive", overriding().bindText("-f", "-i"))
        assertTrue("last of -i, -f wins" in overriding().helpText(), "the override must be documented")
        fun head() = cli("head") {
            val lines = option(short = 'n', long = "lines")
            val bytes = option(short = 'c', long = "bytes")
            lastWins(lines, bytes)
            argument("file")
            action<String>(human = { it }) { Ok("n=${lines()} c=${bytes()}") }
        }
        assertEquals("n=3 c=null", head().bindText("-c", "5", "-n", "3", "f"))
    }

    @Test
    fun `a negation turns a flag off under its own spelling`() {
        // `cp -L` and `-P`: the later of a flag and its negation wins.
        val tree = cli("cp") {
            val deref = flag(short = 'L', long = "dereference").negatable(short = 'P')
            argument("file")
            action<String>(human = { it }) { Ok("deref=${deref()}") }
        }
        assertEquals("deref=true", tree.bindText("-L", "f"))
        assertEquals("deref=false", tree.bindText("-L", "-P", "f"))
        assertEquals("deref=true", tree.bindText("-P", "-L", "f"))
    }

    // --- --help and --version inside the option loop ---

    /** cp's shape: longs sharing a prefix, a value-taking option, a choice, a flag, and required operands. */
    private fun copier(): Cli = cli("cp") {
        abbreviation = Abbreviation.Options
        version = Version.Of("9.12")
        flag(long = "no-clobber")
        flag(long = "no-dereference")
        flag(short = 'T', long = "no-target-directory")
        option(short = 't', long = "target-directory")
        option(long = "sparse").choice("auto", "always", "never")
        argument("source")
        argument("dest")
        action { Ok("") }
    }

    @Test
    fun `the first of --help and a bad option or value decides`() {
        // Each refusal as cp 9.12 gives it ahead of --help, while `cp --help` ahead of it prints the usage:
        // `--bogus` is unrecognized, `--no` ambiguous, `--no-target-directory=x` doesn't allow an argument, and
        // `--sparse=bogus` an invalid argument. A missing value stands last, so --help ahead of it answers.
        val bad = mapOf(
            "--bogus" to CliError.UnknownOption::class,
            "--no" to CliError.AmbiguousOption::class,
            "--no-target-directory=x" to CliError.FlagTakesNoValue::class,
            "--sparse=bogus" to CliError.InvalidChoice::class,
        )
        for ((word, refusal) in bad) {
            for (builtin in listOf("--help", "--version")) {
                val refused = copier().parse(listOf(word, builtin)).assertError<CliError>()
                assertEquals(refusal, refused::class, "$word $builtin")
            }
            assertIs<Invocation.ShowHelp>(copier().parse(listOf("--help", word)).assertSuccess(), "--help $word")
            assertIs<Invocation.ShowVersion>(copier().parse(listOf("--version", word)).assertSuccess())
        }
        assertIs<Invocation.ShowHelp>(copier().parse(listOf("--help", "--target-directory")).assertSuccess())
        copier().parse(listOf("--target-directory")).assertError<CliError.MissingOptionValue>()
    }

    @Test
    fun `--help and --version answer whichever the line gives first`() {
        // `ls --help --version` prints the usage and `ls --version --help` the version.
        assertIs<Invocation.ShowHelp>(copier().parse(listOf("--help", "--version")).assertSuccess())
        assertIs<Invocation.ShowVersion>(copier().parse(listOf("--version", "--help")).assertSuccess())
    }

    @Test
    fun `a version short decides where it stands as --version does`() {
        val app = cli("app") {
            version = Version.Of("1.0.0", long = "version", short = 'V')
            action { Ok("") }
        }
        app.parse(listOf("--bogus", "-V")).assertError<CliError.UnknownOption>()
        assertIs<Invocation.ShowVersion>(app.parse(listOf("-V", "--bogus")).assertSuccess())
    }

    @Test
    fun `the first refusal on the line is the one reported`() {
        // `ls --width abc --bogus` is "invalid line width", `ls --bogus --width abc` is "unrecognized option", and
        // `ls --width abc --tabsize xyz` blames the width.
        val app = cli("app") {
            option(long = "tabsize").int()
            option(long = "width").int()
            action { Ok("") }
        }
        assertEquals("--width", app.parse(listOf("--width", "abc", "--bogus")).assertError<CliError.BadValue>().name)
        app.parse(listOf("--bogus", "--width", "abc")).assertError<CliError.UnknownOption>()
        val first = app.parse(listOf("--width", "abc", "--tabsize", "xyz")).assertError<CliError.BadValue>()
        assertEquals("--width", first.name)
    }

    @Test
    fun `a bad --color value refuses the line only ahead of --help or --version`() {
        // `ls --color=bogus --help` is "invalid argument 'bogus' for '--color'" and `ls --help --color=bogus`
        // prints the usage.
        val app = cli("app") {
            version = Version.Of("1.0.0")
            action { Ok("") }
        }
        app.parse(listOf("--color=bogus", "--help")).assertError<CliError.InvalidChoice>()
        app.parse(listOf("--color=bogus", "--version")).assertError<CliError.InvalidChoice>()
        assertIs<Invocation.ShowHelp>(app.parse(listOf("--help", "--color=bogus")).assertSuccess())
        assertIs<Invocation.ShowVersion>(app.parse(listOf("--version", "--color=bogus")).assertSuccess())
        app.parse(listOf("--color=bogus")).assertError<CliError.InvalidChoice>()
    }

    // --- An error names a spelling as getopt_long does ---

    /** ls's shape for the spellings below: two flags sharing a prefix, a negation, two value-taking options. */
    private fun lister(): Cli = cli("ls") {
        flag(short = 'a', long = "all")
        flag(short = 'A', long = "almost-all")
        flag(short = 'G', long = "group").negatable(default = true)
        option(long = "block-size")
        option(short = 'I', long = "ignore")
        argument("file").multiple(min = 0)
        action { Ok("") }
    }

    @Test
    fun `an error names the long an abbreviation resolved to`() {
        // `ls --blo`: "option '--block-size' requires an argument"; `ls --no-gr=x`: "option '--no-group' doesn't
        // allow an argument"; getopt -o '' -l alpha -- --al=x: "option '--alpha' doesn't allow an argument".
        assertEquals(
            CliError.MissingOptionValue("--block-size"),
            lister().parse(listOf("--blo")).assertError<CliError.MissingOptionValue>(),
        )
        assertEquals(
            "--no-group",
            lister().parse(listOf("--no-gr=x")).assertError<CliError.FlagTakesNoValue>().flag,
        )
        assertEquals("--all", lister().parse(listOf("--all=x")).assertError<CliError.FlagTakesNoValue>().flag)
        assertEquals("--almost-all", lister().parse(listOf("--alm=x")).assertError<CliError.FlagTakesNoValue>().flag)
    }

    @Test
    fun `an error names the whole word when nothing resolved`() {
        // `ls --al=x`: "option '--al=x' is ambiguous; possibilities: '--all' '--almost-all'"; `ls --zeta=1`:
        // "unrecognized option '--zeta=1'".
        assertEquals(
            CliError.AmbiguousOption("--al=x", listOf("--all", "--almost-all")),
            lister().parse(listOf("--al=x")).assertError<CliError.AmbiguousOption>(),
        )
        assertEquals("--zeta=1", lister().parse(listOf("--zeta=1")).assertError<CliError.UnknownOption>().token)
    }

    @Test
    fun `an error in a cluster names the letter alone`() {
        // `ls -a-` binds -a, then "invalid option -- '-'"; `ls -I`: "option requires an argument -- 'I'".
        assertEquals(
            CliError.UnknownOption("--", cluster = "-a-"),
            lister().parse(listOf("-a-")).assertError<CliError.UnknownOption>(),
        )
        assertEquals(
            CliError.MissingOptionValue("-I"),
            lister().parse(listOf("-aI")).assertError<CliError.MissingOptionValue>(),
        )
    }

    @Test
    fun `operands are checked after the options so they never refuse --help`() {
        // `cp --help` with no operand and `cp --help a b c` print the usage: cp counts its operands after the loop.
        assertIs<Invocation.ShowHelp>(copier().parse(listOf("--help")).assertSuccess())
        assertIs<Invocation.ShowHelp>(copier().parse(listOf("--help", "a", "b", "c")).assertSuccess())
        assertIs<Invocation.ShowHelp>(copier().parse(listOf("a", "b", "c", "--help")).assertSuccess())
    }
}
