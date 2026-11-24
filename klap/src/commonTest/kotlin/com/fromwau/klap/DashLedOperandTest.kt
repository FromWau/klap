package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.terminal.test.RecordingTerminal
import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DashLedOperandTest {

    private fun seekTree(mark: Boolean) = cli("echoctl") {
        builtins { help = Help.ByCommand(short = 'h') }
        command("seek") {
            val position = if (mark) argument("position").dashLed() else argument("position")
            action { Ok("pos=${position()}") }
        }
    }

    private fun refusing(slot: CommandBuilder.() -> Arg<*>) = cli("set") {
        val value = slot()
        action { Ok("value=${value()}") }
    }

    private fun Cli.errorFor(vararg words: String): String {
        val terminal = RecordingTerminal()
        assertEquals(USAGE_ERROR_EXIT, run(words.toList(), terminal))
        return terminal.err.lines().first()
    }

    @Test
    fun `a word a full slot's test refuses is an unknown option`() {
        val digits = refusing { argument("value").dashLed { word -> word.drop(1).firstOrNull()?.isDigit() == true } }
        assertEquals("error: unknown option '-x'", digits.errorFor("1", "-x"))
        assertEquals("error: unknown option '-x'", digits.errorFor("-5", "-x"))
        assertEquals("error: unknown option '-x'", digits.errorFor("1", "2", "-x"))
    }

    @Test
    fun `a word a slot's test refuses before --help or --version is an unknown option as one no slot takes`() {
        val digits = cli("set") {
            version = Version.Of("1.0")
            val value = argument("value").dashLed { word -> word.drop(1).firstOrNull()?.isDigit() == true }
            action { Ok("value=${value()}") }
        }
        assertEquals("error: unknown option '-x'", digits.errorFor("-x", "--help"))
        assertEquals("error: unknown option '-x'", digits.errorFor("-x", "--version"))
    }

    @Test
    fun `a word refused past a variadic slot's maximum is an unknown option`() {
        val digits = refusing {
            argument("value").dashLed { word -> word.drop(1).firstOrNull()?.isDigit() == true }.multiple(max = 2)
        }
        assertEquals("error: unknown option '-x'", digits.errorFor("-1", "-2", "-x"))
    }

    @Test
    fun `a dash word past a full slot with no test is an unknown option`() {
        val any = refusing { argument("value").dashLed() }
        assertEquals("error: unknown option '-x'", any.errorFor("1", "-x"))
        assertEquals("error: unknown option '-x'", any.errorFor("-5", "-x"))
        assertEquals("error: unexpected extra argument: 2", any.errorFor("1", "2"))
    }

    @Test
    fun `a dash word a full slot's test takes is an extra argument`() {
        val digits = refusing { argument("value").dashLed { word -> word.drop(1).firstOrNull()?.isDigit() == true } }
        assertEquals("error: unexpected extra argument: -5", digits.errorFor("1", "-5"))
    }

    @Test
    fun `in a provider isDashLed counts a finished word the slot's test takes and a word it refuses fills no slot`() {
        val tree = cli("set") {
            val value = argument("value")
                .dashLed { word -> word.drop(1).firstOrNull()?.isDigit() == true }
                .completeWith { candidate("value") }
            argument("next").completeWith { candidate("${value.isDashLed}") }
            action { Ok("") }
        }
        assertEquals(listOf("true"), tree.completeCandidates(listOf("-5", "")).map { it.value })
        assertEquals(listOf("value"), tree.completeCandidates(listOf("-x", "")).map { it.value })
    }

    @Test
    fun `in a provider isDashLed does not count the word being typed`() {
        val tree = cli("rm") {
            val files = argument("file").dashLed().multiple()
            files.completeWith { candidate("-x${files.isDashLed}") }
            action { Ok("") }
        }
        assertEquals(listOf("-xfalse"), tree.completeCandidates(listOf("a", "-x")).map { it.value })
        assertEquals(listOf("-xtrue"), tree.completeCandidates(listOf("-y", "-x")).map { it.value })
    }

    private fun Cli.offered(
        vararg words: String,
        cursor: Int,
    ): List<String> = completeCandidates(words.toList(), cursor).map { it.value }

    @Test
    fun `a gathered word being typed is no occurrence and no dash word for its own provider`() {
        val tree = cli("chmod") {
            val mode = argument("mode").dashLed(",") { it.startsWith("-w") || it.startsWith("-x") }
            val files = argument("file").multiple()
            files.completeWith {
                val order = inOrder(files, mode).joinToString(",") { "${it.value}" }
                candidate("-w files=${files().joinToString(",")} order=$order dash=${mode.isDashLed}")
            }
            mode.completeWith {
                val order = inOrder(files, mode).joinToString(",") { "${it.value}" }
                candidate("-w files=${files().joinToString(",")} order=$order dash=${mode.isDashLed}")
            }
            action { Ok("") }
        }
        assertEquals(listOf("-w files=a,b order=a,b dash=false"), tree.offered("-w", "a", "b", cursor = 0))
        assertEquals(listOf("-w files=a,b order=a,b dash=false"), tree.offered("a", "-w", "b", cursor = 1))
        assertEquals(listOf("-w files=a,b order=-x,a,b dash=true"), tree.offered("-x", "-w", "a", "b", cursor = 1))
    }

    @Test
    fun `Tab offers an operand's values a test takes while the word is still partial`() {
        val tree = cli("set") {
            argument("value")
                .dashLed { it == "-inf" || it.drop(1).firstOrNull()?.isDigit() == true }
                .completeWith { candidates(listOf("-inf", "-1", "7")) }
            action { Ok("") }
        }
        assertEquals(listOf("-inf"), tree.offered("-i", cursor = 0))
        assertEquals(listOf("-inf"), tree.offered("-in", cursor = 0))
        assertEquals(true, tree.offered("-", cursor = 0).containsAll(listOf("-inf", "-1")))
    }

    @Test
    fun `Tab asks a slot's test only about a word the parser would give it`() {
        val tree = cli("set") {
            argument("value")
                .dashLed { it[1].isDigit() }
                .completeWith { candidate("-1") }
            action { Ok("") }
        }
        assertEquals(true, "-1" in tree.offered("-", cursor = 0))
    }

    @Test
    fun `a word refused by the test of a slot its trigger removed is an unknown option`() {
        val tree = cli("t") {
            val all = flag(long = "all")
            val value = argument("v").dashLed { word -> word.drop(1).firstOrNull()?.isDigit() == true }.absentWhen(all)
            action { Ok("${value.isDashLed}") }
        }
        assertEquals("error: unknown option '-x'", tree.errorFor("--all", "-x"))
    }

    @Test
    fun `a dash word for a removed slot with no test is an unknown option`() {
        val tree = cli("t") {
            val all = flag(long = "all")
            val value = argument("v").dashLed().absentWhen(all)
            action { Ok("${value.isDashLed}") }
        }
        assertEquals("error: unknown option '-x'", tree.errorFor("--all", "-x"))
    }

    @Test
    fun `a dash word a removed slot's test takes is an extra argument`() {
        val tree = cli("t") {
            val all = flag(long = "all")
            val value = argument("v").dashLed { word -> word.drop(1).firstOrNull()?.isDigit() == true }.absentWhen(all)
            action { Ok("${value.isDashLed}") }
        }
        assertEquals("error: unexpected extra argument: -5", tree.errorFor("--all", "-5"))
    }

    @Test
    fun `declaring dashLed leaves an ordinary parse untouched`() {
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "5"), t))
        assertEquals("pos=5\n", t.out)
    }

    @Test
    fun `dashLed is order free against a converter`() {
        val tree = cli("app") {
            command("go") {
                val n = argument("n").dashLed().int()
                action { Ok("n=${n()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "7"), t))
        assertEquals("n=7\n", t.out)
    }

    @Test
    fun `a dashLed filter binds the dash words it accepts and leaves the others unknown options`() {
        val tree = cli("app") {
            command("set") {
                val value = argument("value").dashLed { word -> word.drop(1).firstOrNull()?.isDigit() == true }
                action { Ok("value=${value()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("set", "-5"), t))
        assertEquals("value=-5\n", t.out)
        val refused = tree.parse(listOf("set", "-x")).assertError<CliError.UnknownOption>()
        assertEquals(CliError.UnknownOption("-x"), refused)
    }

    @Test
    fun `an unmarked command still rejects a dash led operand`() {
        val error = seekTree(mark = false).parse(listOf("seek", "-1m")).assertError<CliError.UnknownOption>()
        assertEquals(CliError.UnknownOption("-1", cluster = "-1m"), error)
    }

    @Test
    fun `a cluster resolves in full only when every character does`() {
        val tree = cli("app") {
            command("go") {
                val verbose = flag(short = 'v', long = "verbose")
                val n = argument("n").dashLed()
                action { Ok("v=${verbose()} n=${n()}") }
            }
        }
        // -v resolves, so it stays a flag and never reaches the marked slot.
        val resolved = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-v", "5"), resolved))
        assertEquals("v=true n=5\n", resolved.out)
        // -v1m does not resolve in full, so the whole word is the operand and -v is NOT counted.
        val admitted = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-v1m"), admitted))
        assertEquals("v=false n=-v1m\n", admitted.out)
    }

    @Test
    fun `a marked slot takes a negative offset`() {
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "-1m"), t))
        assertEquals("pos=-1m\n", t.out)
    }

    @Test
    fun `a marked slot takes a negative hour offset even though -h is a builtin`() {
        // `h` resolves inside a cluster (it is klap's own short), but `1` does not, so the cluster still
        // fails to resolve in full. Pinning this stops a change to cluster evaluation order from silently
        // taking hour offsets away.
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "-1h"), t))
        assertEquals("pos=-1h\n", t.out)
    }

    @Test
    fun `a declared builtin still wins against a marked slot`() {
        val invocation = seekTree(mark = true).parse(listOf("seek", "-h")).assertSuccess()
        assertIs<Invocation.ShowHelp>(invocation)
    }

    @Test
    fun `a double dash token is never an operand`() {
        // Declares --verbose so --verbsoe has a real spelling to be a typo of: the single-dash restriction
        // exists so a long-option typo keeps its did-you-mean instead of being swallowed as an operand.
        val tree = cli("app") {
            command("seek") {
                flag(short = 'v', long = "verbose")
                val position = argument("position").dashLed()
                action { Ok("pos=${position()}") }
            }
        }
        val unknown = tree.parse(listOf("seek", "--verbsoe")).assertError<CliError.UnknownOption>()
        assertEquals("--verbose", unknown.suggestion)
    }

    @Test
    fun `a dash led operand ends options when the command says the first operand does`() {
        val tree = cli("app") {
            command("seek") {
                optionsEndAtFirstOperand = true
                val verbose = flag(short = 'v', long = "verbose")
                val position = argument("position").dashLed()
                val rest = argument("rest").multiple()
                action { Ok("v=${verbose()} pos=${position()} rest=${rest().joinToString(",")}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("seek", "-1m", "-v"), t))
        assertEquals("v=false pos=-1m rest=-v\n", t.out)
    }

    @Test
    fun `a cluster whose tail is an option value still resolves`() {
        // The characters after a short option are its value, not more shorts to resolve, so `-vp8080`
        // binds as it always has. Without this the marked slot would swallow a token the parser owns.
        val tree = cli("app") {
            command("go") {
                val verbose = flag(short = 'v', long = "verbose")
                val port = option(short = 'p', long = "port").int()
                val n = argument("n").dashLed().optional()
                action { Ok("v=${verbose()} port=${port()} n=${n()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-vp8080"), t))
        assertEquals("v=true port=8080 n=null\n", t.out)
    }

    @Test
    fun `an unmarked second slot rejects a dash led token`() {
        val tree = cli("app") {
            command("seek") {
                val from = argument("from").dashLed()
                val to = argument("to").optional()
                action { Ok("from=${from()} to=${to()}") }
            }
        }
        // The first slot is marked, so the sift admits both words; only bind can tell that `to` is not.
        val error = tree.parse(listOf("seek", "-1m", "-2m")).assertError<CliError.UnknownOption>()
        assertEquals(CliError.UnknownOption("-2m"), error)
    }

    @Test
    fun `an unmarked variadic rejects a dash led token`() {
        val tree = cli("app") {
            command("seek") {
                val from = argument("from").dashLed()
                val rest = argument("rest").multiple()
                action { Ok("from=${from()} rest=${rest().joinToString(",")}") }
            }
        }
        val error = tree.parse(listOf("seek", "-1m", "-2m", "f")).assertError<CliError.UnknownOption>()
        assertEquals(CliError.UnknownOption("-2m"), error)
    }

    @Test
    fun `the escape still reaches an unmarked slot on a marked command`() {
        // The rule 3 check must see only what the sift admitted through the dash-led path. A `--`-escaped
        // operand has always bound in an unmarked slot and still must.
        val tree = cli("app") {
            command("seek") {
                val from = argument("from").dashLed()
                val to = argument("to").optional()
                action { Ok("from=${from()} to=${to()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("seek", "-1m", "--", "-2m"), t))
        assertEquals("from=-1m to=-2m\n", t.out)
    }

    @Test
    fun `a declared short a long and an abbreviation all win against a marked slot`() {
        val tree = cli("app") {
            abbreviation = Abbreviation.Options
            command("go") {
                val verbose = flag(short = 'v', long = "verbose")
                val mode = option(long = "mode")
                val n = argument("n").dashLed()
                action { Ok("v=${verbose()} mode=${mode()} n=${n()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-v", "--mod", "x", "5"), t))
        assertEquals("v=true mode=x n=5\n", t.out)
    }

    @Test
    fun `a number wins against a marked slot`() {
        val tree = cli("app") {
            command("go") {
                val lines = numberOption().int()
                val rest = argument("rest").dashLed().optional()
                action { Ok("lines=${lines()} rest=${rest()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-5"), t))
        assertEquals("lines=5 rest=null\n", t.out)
    }

    @Test
    fun `the escape still works on a marked command`() {
        // The `--` half of the pair above: a declared `-h` wins on a marked command, and `--` still
        // overrides that, which is the escape dashLed()'s KDoc promises. It holds by construction rather
        // than by this branch of the code: a post-`--` token never enters `dashLedAdmitted`, so rule 3
        // cannot reach it.
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "--", "-h"), t))
        assertEquals("pos=-h\n", t.out)
    }

    @Test
    fun `a marked slot pairs with multiple`() {
        val tree = cli("app") {
            command("go") {
                val ns = argument("n").dashLed().multiple(min = 1)
                action { Ok("ns=${ns().joinToString(",")}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-1m", "-2m"), t))
        assertEquals("ns=-1m,-2m\n", t.out)
    }

    @Test
    fun `a dash led token past the last slot is an unknown option as on an unmarked command`() {
        val error = seekTree(mark = true).parse(listOf("seek", "-1m", "-2m")).assertError<CliError.UnknownOption>()
        assertEquals(CliError.UnknownOption("-2m"), error)
    }

    @Test
    fun `completion after a dash led operand still offers the next slot`() {
        val tree = cli("app") {
            command("go") {
                val from = argument("from").dashLed()
                val to = argument("to").optional().completeWith { candidate("after ${from()}") }
                action { Ok("${from()}${to()}") }
            }
        }
        assertEquals(
            listOf("after -1m"),
            tree.completeCandidates(listOf("go", "-1m", "")).map { it.value },
        )
    }

    @Test
    fun `completion binds a dash led token an unmarked slot would refuse`() {
        val tree = cli("app") {
            command("go") {
                val from = argument("from").dashLed()
                val to = argument("to")
                val third = argument("third").optional().completeWith { candidate("saw ${to()}") }
                action { Ok("${from()}${to()}${third()}") }
            }
        }
        // Rule 3 refuses `-2m` in the unmarked `to` slot on a real parse. Completion runs the same binder
        // under Lenient, which must never fail, or a provider reading that slot goes dark exactly when the
        // user is asking what to type.
        tree.parse(listOf("go", "-1m", "-2m")).assertError<CliError.UnknownOption>()
        assertEquals(
            listOf("saw -2m"),
            tree.completeCandidates(listOf("go", "-1m", "-2m", "")).map { it.value },
        )
    }

    @Test
    fun `a dash led word the operand takes completes from its provider`() {
        val tree = cli("chmod") {
            flag(short = 'R', long = "recursive")
            val mode = argument("mode")
                .dashLed(gather = ",") { word -> word.drop(1).all { it in "rwx" } }
                .completeWith { candidates(listOf("-w", "-x", "644")) }
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("mode=${mode()} files=${files()}") }
        }
        assertEquals("mode=-w files=[f]", tree.bindText("-w", "f"))
        assertEquals(listOf("-w"), tree.offered("-w"))
    }

    @Test
    fun `a dashLed test is not asked about a word being typed so Tab offers its candidates beside the names`() {
        val tree = cli("app") {
            flag(short = 'v', long = "verbose")
            val value = argument("value")
                .dashLed { word -> word.drop(1).firstOrNull()?.isDigit() == true }
                .completeWith { candidates(listOf("-5", "-9")) }
            action<String>(human = { it }) { Ok("value=${value()}") }
        }
        assertEquals(listOf("-5"), tree.offered("-5"))
        assertEquals(
            listOf("--verbose", "-v", "--help", "--json", "--color", "--color=", "--completion", "--docs", "-5", "-9"),
            tree.offered("-"),
        )
    }

    @Test
    fun `a word only an option takes completes from the names`() {
        val tree = chmodLike()
        assertEquals("mode=u+x files=[f] recursive=true", tree.bindText("--rec", "u+x", "f"))
        assertEquals(listOf("--recursive"), tree.offered("--rec"))
    }

    @Test
    fun `a lone dash offers the names and the dash led operand's values`() {
        val tree = chmodLike()
        assertEquals("mode=- files=[f] recursive=false", tree.bindText("-", "f"))
        assertEquals(
            listOf("--recursive", "-R", "--help", "--version", "-w", "-x"),
            tree.offered("-"),
        )
    }

    private fun chmodLike() = cli("chmod") {
        version = Version.Of("9.12")
        builtins {
            json = Builtin.Off
            color = Builtin.Off
            completion = Builtin.Off
            docs = Builtin.Off
        }
        val recursive = flag(short = 'R', long = "recursive")
        val mode = argument("mode")
            .dashLed(gather = ",") { word -> word.drop(1).all { it in "rwx" } }
            .completeWith { candidates(listOf("-w", "-x", "644")) }
        val files = argument("file").multiple()
        action<String>(human = { it }) { Ok("mode=${mode()} files=${files()} recursive=${recursive()}") }
    }

    /** A one-operand tree that prints the operand and whether a dash word filled it. */
    private fun telling(declare: CommandBuilder.() -> Arg<*>) = cli("tell") {
        val word = declare()
        action { Ok("${word()} ${word.isDashLed}") }
    }

    private fun told(cli: Cli, vararg line: String): String {
        val t = RecordingTerminal()
        assertEquals(0, cli.run(line.toList(), t), t.err)
        return t.out
    }

    @Test
    fun `isDashLed is true for a dash word before the end of options`() {
        val tree = telling { argument("word").dashLed() }
        assertEquals("-5 true\n", told(tree, "-5"))
    }

    @Test
    fun `isDashLed is false for the same word after the end of options`() {
        val tree = telling { argument("word").dashLed() }
        assertEquals("-5 false\n", told(tree, "--", "-5"))
    }

    @Test
    fun `isDashLed is false for a plain word`() {
        val tree = telling { argument("word").dashLed() }
        assertEquals("5 false\n", told(tree, "5"))
    }

    @Test
    fun `isDashLed is false without dashLed even for a dash word after the end of options`() {
        val tree = telling { argument("word") }
        assertEquals("-5 false\n", told(tree, "--", "-5"))
    }

    @Test
    fun `isDashLed tells a dash word the slot's test takes from the same word after the end of options`() {
        val tree = telling { argument("word").dashLed { true } }
        assertEquals("-5 true\n", told(tree, "-5"))
        assertEquals("-5 false\n", told(tree, "--", "-5"))
    }

    @Test
    fun `isDashLed is false for an operand left out`() {
        val tree = telling { argument("word").dashLed().optional() }
        assertEquals("null false\n", told(tree))
    }

    @Test
    fun `isDashLed on a variadic operand is true when any of its words was a dash word`() {
        val tree = telling { argument("word").dashLed().multiple() }
        assertEquals("[a, -5] true\n", told(tree, "a", "-5"))
        assertEquals("[a, -5] false\n", told(tree, "a", "--", "-5"))
    }

    @Test
    fun `isDashLed is true only on the operand a dash word filled`() {
        val tree = cli("tell") {
            val first = argument("first").dashLed()
            val second = argument("second").dashLed()
            action { Ok("${first.isDashLed} ${second.isDashLed}") }
        }
        assertEquals("false true\n", told(tree, "a", "-5"))
        assertEquals("true false\n", told(tree, "-5", "a"))
    }

    @Test
    fun `isDashLed on a gathering operand is true when it gathered a dash word`() {
        val tree = cli("tell") {
            val mode = argument("mode").dashLed(gather = ",") { true }.optional()
            val rest = argument("rest").optional()
            action { Ok("${mode()} ${rest()} ${mode.isDashLed}") }
        }
        assertEquals("-w null true\n", told(tree, "-w"))
        assertEquals("a null false\n", told(tree, "a"))
    }

    @Test
    fun `in a provider isDashLed tells a dash word from a plain one`() {
        val tree = cli("tell") {
            val first = argument("first").dashLed()
            val second = argument("second").completeWith { candidates(listOf("dash=${first.isDashLed}")) }
            action { Ok("${first()} ${second()}") }
        }
        assertEquals(listOf("dash=true"), tree.completeCandidates(listOf("-5", "")).map { it.value })
        assertEquals(listOf("dash=false"), tree.completeCandidates(listOf("5", "")).map { it.value })
    }
}
