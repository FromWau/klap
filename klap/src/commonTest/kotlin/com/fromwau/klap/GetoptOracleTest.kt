package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * klap's reading of a command line beside GNU getopt_long's, cell by cell. Every expected value is what util-linux
 * 2.42.4 `getopt`, which calls glibc's getopt_long, printed for the same words against the same declarations:
 *
 * ```
 * getopt -o ab:c:: -l alpha,beta:,gamma::,betamax -- <argv>
 * ```
 *
 * A reading is getopt's own output (` -a -b '1' -- 'x'`: the options in line order, then `--`, then the operands);
 * a refusal is getopt's message on stderr, which it gives with exit code 1. The POSIX cells are the same spec with
 * a leading `+` (POSIXLY_CORRECT=1 reads every one of them alike), against `optionsEndAtFirstOperand`.
 */
class GetoptOracleTest {

    private sealed interface Gnu {
        data class Reads(val output: String) : Gnu
        data class Refuses(val message: String) : Gnu
    }

    /** One line: the words, written space-separated with `''` for an empty word, and what getopt made of them. */
    private data class Cell(val line: String, val gnu: Gnu) {
        val argv: List<String> = if (line.isEmpty()) emptyList() else line.split(" ").map { if (it == "''") "" else it }
    }

    private fun reads(line: String, output: String) = Cell(line, Gnu.Reads(output))

    private fun refuses(line: String, message: String) = Cell(line, Gnu.Refuses(message))

    /** `-o ab:c:: -l alpha,beta:,gamma::,betamax`, with every klap built-in off so each name is the spec's. */
    private fun spec(posix: Boolean = false) = cliOf("t") {
        builtins {
            help = Help.Off
            json = Builtin.Off
            color = Builtin.Off
            completion = Builtin.Off
            docs = Builtin.Off
        }
        optionsEndAtFirstOperand = posix
        val a = flag(short = 'a')
        val b = option(short = 'b')
        val c = option(short = 'c').optionalValue()
        val alpha = flag(long = "alpha")
        val beta = option(long = "beta")
        val gamma = option(long = "gamma").optionalValue()
        val betamax = flag(long = "betamax")
        val operands = argument("operand").multiple(min = 0)
        action { Ok("") }
        projection {
            val names = mapOf<Any, String>(
                a to "-a",
                b to "-b",
                c to "-c",
                alpha to "--alpha",
                beta to "--beta",
                gamma to "--gamma",
                betamax to "--betamax",
            )
            val options = inOrder(a, b, c, alpha, beta, gamma, betamax).joinToString("") { occurrence ->
                val name = names.getValue(occurrence.input)
                when (val value = occurrence.value) {
                    true -> " $name"
                    // getopt prints a missing optional argument as '', the same as an empty one.
                    OptionalValue.Bare -> " $name ''"
                    is OptionalValue.Given<*> -> " $name ${quoted(value.value.toString())}"
                    else -> " $name ${quoted(value.toString())}"
                }
            }
            options + " --" + operands().joinToString("") { " ${quoted(it)}" }
        }
    }

    private fun quoted(word: String) = "'" + word.replace("'", "'\\''") + "'"

    /** The refusal klap must give for each of getopt's messages. */
    private fun refusalFor(message: String): String = when {
        "unrecognized option" in message || "invalid option" in message -> "UnknownOption"
        "is ambiguous" in message -> "AmbiguousOption"
        "requires an argument" in message -> "MissingOptionValue"
        "doesn't allow an argument" in message -> "FlagTakesNoValue"
        else -> error("no refusal mapped for getopt's '$message'")
    }

    private fun klapReading(cell: Cell, posix: Boolean): String = when (val parsed = spec(posix).parse(cell.argv)) {
        is Result.Success -> parsed.value ?: "a built-in"
        is Result.Error -> "refused: ${parsed.error::class.simpleName}"
    }

    private fun expected(gnu: Gnu): String = when (gnu) {
        is Gnu.Reads -> gnu.output
        is Gnu.Refuses -> "refused: ${refusalFor(gnu.message)}"
    }

    private fun assertReadsAsGetopt(cells: List<Cell>, posix: Boolean = false) {
        val wrong = cells.mapNotNull { cell ->
            val klap = klapReading(cell, posix)
            if (klap == expected(cell.gnu)) null else "[${cell.line}] getopt: ${cell.gnu}, klap: $klap"
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    // --- Shorts ---

    @Test
    fun `a short cluster reads as getopt reads it`() = assertReadsAsGetopt(
        listOf(
            reads("-a", " -a --"),
            reads("-aa", " -a -a --"),
            reads("-ab1", " -a -b '1' --"),
            reads("-ab 1", " -a -b '1' --"),
            reads("-ab1a", " -a -b '1a' --"),
            reads("-ab1c", " -a -b '1c' --"),
            reads("-abc", " -a -b 'c' --"),
            reads("-ab ''", " -a -b '' --"),
            reads("-ac", " -a -c '' --"),
            reads("-ac3", " -a -c '3' --"),
            reads("-ca", " -c 'a' --"),
            reads("-cb", " -c 'b' --"),
        ),
    )

    @Test
    fun `a short value reads glued or spaced as getopt reads it`() = assertReadsAsGetopt(
        listOf(
            reads("-b1", " -b '1' --"),
            reads("-b 1", " -b '1' --"),
            reads("-b ''", " -b '' --"),
            reads("-b=1", " -b '=1' --"),
            reads("-c3", " -c '3' --"),
            reads("-c=1", " -c '=1' --"),
            // An optional value is only ever glued: the next word stays an operand.
            reads("-c 3", " -c '' -- '3'"),
            reads("-c ''", " -c '' -- ''"),
        ),
    )

    // --- Longs ---

    @Test
    fun `a long value reads attached or spaced as getopt reads it`() = assertReadsAsGetopt(
        listOf(
            reads("--alpha", " --alpha --"),
            reads("--alpha x", " --alpha -- 'x'"),
            reads("--beta 2", " --beta '2' --"),
            reads("--beta=2", " --beta '2' --"),
            reads("--beta=a=b", " --beta 'a=b' --"),
            reads("--beta=", " --beta '' --"),
            reads("--beta ''", " --beta '' --"),
            reads("--gamma=3", " --gamma '3' --"),
            reads("--gamma=", " --gamma '' --"),
            reads("--gamma 3", " --gamma '' -- '3'"),
            reads("--gamma --alpha", " --gamma '' --alpha --"),
            refuses("--alpha=x", "option '--alpha' doesn't allow an argument"),
            refuses("--betamax=x", "option '--betamax' doesn't allow an argument"),
        ),
    )

    @Test
    fun `an optional value attached empty is given and not bare`() {
        // getopt prints both as '', but getopt_long's optarg is "" for `--gamma=` and NULL for a bare `--gamma`:
        // `ls --color=` is "ambiguous argument '' for '--color'" on coreutils 9.12, while a bare `ls --color` runs.
        val tree = cliOf("t") {
            val gamma = option(long = "gamma").optionalValue()
            action { Ok("") }
            projection { gamma() }
        }
        assertEquals(OptionalValue.Given(""), tree.parse(listOf("--gamma=")).assertSuccess())
        assertEquals(OptionalValue.Bare, tree.parse(listOf("--gamma")).assertSuccess())
    }

    @Test
    fun `a long abbreviates as getopt abbreviates it`() = assertReadsAsGetopt(
        listOf(
            reads("--al", " --alpha --"),
            reads("--a", " --alpha --"),
            reads("--g", " --gamma '' --"),
            reads("--gam=3", " --gamma '3' --"),
            reads("--al --alpha", " --alpha --alpha --"),
            // An exact name beats the longer one it is a prefix of.
            reads("--beta 2", " --beta '2' --"),
            reads("--betam", " --betamax --"),
            reads("--betamax", " --betamax --"),
            refuses("--al=x", "option '--alpha' doesn't allow an argument"),
            refuses("--bet", "option '--bet' is ambiguous; possibilities: '--beta' '--betamax'"),
            refuses("--bet=2", "option '--bet=2' is ambiguous; possibilities: '--beta' '--betamax'"),
            refuses("--b", "option '--b' is ambiguous; possibilities: '--beta' '--betamax'"),
        ),
    )

    @Test
    fun `flags sharing a prefix abbreviate as getopt abbreviates them`() {
        // getopt -o a -l alpha,alphabet,alpine -- <word>: getopt finds a prefix of two flags ambiguous even though
        // both take no value, and an exact name still wins.
        val tree = cliOf("t") {
            val alpha = flag(long = "alpha")
            val alphabet = flag(long = "alphabet")
            val alpine = flag(long = "alpine")
            action { Ok("") }
            projection { listOf(alpha(), alphabet(), alpine()) }
        }
        tree.parse(listOf("--alp")).assertError<CliError.AmbiguousOption>() // ... '--alpha' '--alphabet' '--alpine'
        tree.parse(listOf("--alph")).assertError<CliError.AmbiguousOption>() // ... '--alpha' '--alphabet'
        assertEquals(listOf(true, false, false), tree.parse(listOf("--alpha")).assertSuccess()) // --alpha --
        assertEquals(listOf(false, true, false), tree.parse(listOf("--alphab")).assertSuccess()) // --alphabet --
    }

    // --- Values that look like options ---

    @Test
    fun `a dash-led value is the value as getopt reads it`() = assertReadsAsGetopt(
        listOf(
            reads("-b -a", " -b '-a' --"),
            reads("-b --", " -b '--' --"),
            reads("-b -", " -b '-' --"),
            reads("-b --alpha", " -b '--alpha' --"),
            reads("--beta --", " --beta '--' --"),
            reads("--beta -a", " --beta '-a' --"),
            reads("--beta --alpha", " --beta '--alpha' --"),
        ),
    )

    // --- Operands, `--` and `-` ---

    @Test
    fun `operands permute and -- ends the options as getopt reads them`() = assertReadsAsGetopt(
        listOf(
            reads("", " --"),
            reads("--", " --"),
            reads("-", " -- '-'"),
            reads("''", " -- ''"),
            reads("- -a", " -a -- '-'"),
            reads("-a ''", " -a -- ''"),
            reads("-a --", " -a --"),
            reads("-- -a x", " -- '-a' 'x'"),
            reads("x -- -a", " -- 'x' '-a'"),
            reads("-- -- x", " -- '--' 'x'"),
            reads("-a -- -- --", " -a -- '--' '--'"),
            reads("x -a y", " -a -- 'x' 'y'"),
            reads("x -b 1 y", " -b '1' -- 'x' 'y'"),
            reads("x --beta 1 y", " --beta '1' -- 'x' 'y'"),
        ),
    )

    @Test
    fun `a repeated option is every occurrence as getopt reads it`() = assertReadsAsGetopt(
        listOf(
            reads("-a -a", " -a -a --"),
            reads("--alpha --alpha", " --alpha --alpha --"),
            reads("-b 1 -b 2", " -b '1' -b '2' --"),
            reads("--beta 1 --beta 2", " --beta '1' --beta '2' --"),
            reads("-c -c5", " -c '' -c '5' --"),
        ),
    )

    // --- Refusals ---

    @Test
    fun `an undeclared option is refused as getopt refuses it`() = assertReadsAsGetopt(
        listOf(
            refuses("-z", "invalid option -- 'z'"),
            refuses("-A", "invalid option -- 'A'"),
            refuses("-az", "invalid option -- 'z'"),
            refuses("-za", "invalid option -- 'z'"),
            refuses("-a-", "invalid option -- '-'"),
            refuses("-=", "invalid option -- '='"),
            refuses("-alpha", "invalid option -- 'l'"),
            // A dash and a digit is an option, and with no number option declared an undeclared one.
            refuses("-5", "invalid option -- '5'"),
            refuses("-a5", "invalid option -- '5'"),
            refuses("-1a", "invalid option -- '1'"),
            refuses("--zeta", "unrecognized option '--zeta'"),
            refuses("--ALPHA", "unrecognized option '--ALPHA'"),
            refuses("--alphax", "unrecognized option '--alphax'"),
            refuses("--alpha-x", "unrecognized option '--alpha-x'"),
            refuses("---alpha", "unrecognized option '---alpha'"),
            // GNU's `W;` extension reads `-W alpha` as `--alpha`; klap has none, and without `W;` getopt refuses it.
            refuses("-W alpha", "invalid option -- 'W'"),
        ),
    )

    @Test
    fun `a missing value at the end of the line is refused as getopt refuses it`() = assertReadsAsGetopt(
        listOf(
            refuses("-b", "option requires an argument -- 'b'"),
            refuses("-ab", "option requires an argument -- 'b'"),
            refuses("-a -b", "option requires an argument -- 'b'"),
            refuses("x -b", "option requires an argument -- 'b'"),
            refuses("--beta", "option '--beta' requires an argument"),
            refuses("--alpha --beta", "option '--beta' requires an argument"),
            refuses("--beta=x --beta", "option '--beta' requires an argument"),
        ),
    )

    // klap reads `--=x` as the empty prefix of every long, as glibc does, so with one long declared it is that long;
    // the built-ins are off so the table is getopt's: getopt -o '' -l beta: -- --=x y -> " --beta 'x' -- 'y'"
    @Test
    fun `an empty long name abbreviates the one long declared`() {
        val tree = cliOf("t") {
            builtins {
                help = Help.Off
                json = Builtin.Off
                color = Builtin.Off
                completion = Builtin.Off
                docs = Builtin.Off
            }
            val beta = option(long = "beta")
            val operands = argument("operand").multiple(min = 0)
            action { Ok("") }
            projection { "beta=${beta()} operands=${operands()}" }
        }
        assertEquals("beta=x operands=[y]", tree.parse(listOf("--=x", "y")).assertSuccess())
    }

    // klap refuses `--=x` as ambiguous among every long, getopt: getopt -o ab:c:: -l alpha,beta:,gamma::,betamax --
    // --=x -> "option '--=x' is ambiguous; possibilities: '--alpha' '--beta' '--gamma' '--betamax'"
    @Test
    fun `an empty long name is ambiguous among several longs`() = assertReadsAsGetopt(
        listOf(
            refuses("--=x", "option '--=x' is ambiguous; possibilities: '--alpha' '--beta' '--gamma' '--betamax'"),
            refuses("--=", "option '--=' is ambiguous; possibilities: '--alpha' '--beta' '--gamma' '--betamax'"),
        ),
    )

    // --- The first operand ends the options: getopt's leading `+`, klap's optionsEndAtFirstOperand ---

    @Test
    fun `the first operand ends the options as getopt with a leading plus reads it`() = assertReadsAsGetopt(
        posix = true,
        cells = listOf(
            reads("x -a", " -- 'x' '-a'"),
            reads("-a x -b 1", " -a -- 'x' '-b' '1'"),
            reads("-b 1 x -a", " -b '1' -- 'x' '-a'"),
            reads("-a -- x -a", " -a -- 'x' '-a'"),
            // After the first operand a `--` is an operand too.
            reads("x -- -a", " -- 'x' '--' '-a'"),
            reads("- -a", " -- '-' '-a'"),
            reads("-a - -a", " -a -- '-' '-a'"),
            reads("'' -a", " -- '' '-a'"),
            reads("-b x y -a", " -b 'x' -- 'y' '-a'"),
            reads("--beta x y --alpha", " --beta 'x' -- 'y' '--alpha'"),
            reads("-c x -a", " -c '' -- 'x' '-a'"),
            reads("--gamma x -a", " --gamma '' -- 'x' '-a'"),
            reads("x --alpha", " -- 'x' '--alpha'"),
            reads("x -z", " -- 'x' '-z'"),
            refuses("-z x", "invalid option -- 'z'"),
        ),
    )

    // klap refuses `x -ah` as MixedClusterAfterOperands, as the guide's optionsEndAtFirstOperand section says of a
    // cluster mixing a global with a local short, rather than show the help; getopt has no built-ins and keeps the
    // cluster an operand, getopt: getopt -o +ah -- x -ah -> " -- 'x' '-ah'"
    @Test
    fun `a cluster mixing the help short with a local one after the first operand is refused`() {
        val tree = cli("t") {
            builtins { help = Help.ByCommand(short = 'h') }
            optionsEndAtFirstOperand = true
            flag(short = 'a')
            argument("operand").multiple(min = 0)
            action { Ok("") }
        }
        tree.parse(listOf("x", "-ah")).assertError<CliError.MixedClusterAfterOperands>()
    }

    // --- Built-ins and numbers, against GNU coreutils 9.12 head ---

    /** head's shape: `-n NUM` read by `.int()`, three flags, and klap's built-ins left on. */
    private fun head() = cliOf("head") {
        version = Version.Of("9.12")
        val lines = option(short = 'n', long = "lines").int()
        flag(short = 'q', long = "quiet")
        flag(long = "silent")
        flag(short = 'v', long = "verbose")
        val files = argument("file").multiple(min = 0)
        action { Ok("") }
        projection { "n=${lines()} files=${files()}" }
    }

    @Test
    fun `help and version abbreviate as head reads them`() {
        // `head --he f`, `head --hel f` and `head --h f` print the usage, `head --vers f` the version, `head --ver f`
        // is "option '--ver' is ambiguous; possibilities: '--verbose' '--version'", and `head --help=x f` is
        // "option '--help' doesn't allow an argument".
        for (word in listOf("--he", "--hel", "--h")) {
            assertIs<Invocation.ShowHelp>(head().cli.parse(listOf(word, "f")).assertSuccess(), word)
        }
        assertIs<Invocation.ShowVersion>(head().cli.parse(listOf("--vers", "f")).assertSuccess())
        head().parse(listOf("--ver", "f")).assertError<CliError.AmbiguousOption>()
        head().parse(listOf("--v", "f")).assertError<CliError.AmbiguousOption>()
        head().parse(listOf("--help=x", "f")).assertError<CliError.FlagTakesNoValue>()
        head().parse(listOf("--version=x", "f")).assertError<CliError.FlagTakesNoValue>()
        assertEquals("n=null files=[f]", head().parse(listOf("--q", "f")).assertSuccess())
    }

    @Test
    fun `klap's built-ins answer where getopt sees undeclared options`() {
        // Deliberate: `head --c f` is "unrecognized option", while klap's built-ins join the abbreviation pool
        // (guide, Abbreviation; todo 41), so `--c` reaches --color and --completion and `--j` reaches --json.
        // `head -h f` is "invalid option -- 'h'", as klap's --help has no short unless one is given.
        assertEquals("-h", head().cli.parse(listOf("-h", "f")).assertError<CliError.UnknownOption>().token)
        head().parse(listOf("--c", "f")).assertError<CliError.AmbiguousOption>()
        assertEquals("n=null files=[f]", head().parse(listOf("--j", "f")).assertSuccess())
        // ...and they keep their reach past the first operand (guide, optionsEndAtFirstOperand), where
        // getopt -o +a -- x --help reads " -- 'x' '--help'".
        val strict = cli("t") {
            optionsEndAtFirstOperand = true
            flag(short = 'a')
            argument("operand").multiple(min = 0)
            action { Ok("") }
        }
        assertIs<Invocation.ShowHelp>(strict.parse(listOf("x", "--help")).assertSuccess())
    }

    @Test
    fun `a number value reads as head reads its count`() {
        // `head -n ' 2'`, `head -n +2` and `head -n 010` print as -n 2 and -n 10; `head -n '2 '`, `-n 0x10`, `-n ''`,
        // `-n 1e2` and `-n ٢` are "invalid number of lines".
        val read = mapOf(" 2" to 2, "+2" to 2, "010" to 10)
        for ((value, lines) in read) {
            assertEquals("n=$lines files=[]", head().parse(listOf("-n", value)).assertSuccess(), "[$value]")
        }
        for (value in listOf("2 ", "0x10", "", "1e2", "٢")) {
            head().parse(listOf("-n", value)).assertError<CliError.BadValue>()
        }
    }
}
