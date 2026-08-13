package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.internal.render.completeCandidates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DashLedOperandTest {

    private fun seekTree(mark: Boolean) = cli("echoctl") {
        command("seek") {
            val position = if (mark) argument("position").dashLed() else argument("position")
            action { Ok("pos=${position()}") }
        }
    }

    @Test
    fun `declaring dashLed leaves an ordinary parse untouched`() {
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "5"), t))
        assertEquals("pos=5\n", t.out.toString())
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
        assertEquals("n=7\n", t.out.toString())
    }

    @Test
    fun `an unmarked command still rejects a dash led operand`() {
        val error = assertIs<Result.Error<CliError>>(seekTree(mark = false).parse(listOf("seek", "-1m"))).error
        assertEquals(CliError.UnknownOption("-1", cluster = "-1m"), error)
    }

    @Test
    fun `a cluster resolves in full only when every character does`() {
        val tree = cli("app") {
            command("go") {
                val verbose = flag("--verbose", "-v")
                val n = argument("n").dashLed()
                action { Ok("v=${verbose()} n=${n()}") }
            }
        }
        // -v resolves, so it stays a flag and never reaches the marked slot.
        val resolved = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-v", "5"), resolved))
        assertEquals("v=true n=5\n", resolved.out.toString())
        // -v1m does not resolve in full, so the whole word is the operand and -v is NOT counted.
        val admitted = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-v1m"), admitted))
        assertEquals("v=false n=-v1m\n", admitted.out.toString())
    }

    @Test
    fun `a marked slot takes a negative offset`() {
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "-1m"), t))
        assertEquals("pos=-1m\n", t.out.toString())
    }

    @Test
    fun `a marked slot takes a negative hour offset even though -h is a builtin`() {
        // `h` resolves inside a cluster (it is klap's own short), but `1` does not, so the cluster still
        // fails to resolve in full. Pinning this stops a change to cluster evaluation order from silently
        // taking hour offsets away.
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "-1h"), t))
        assertEquals("pos=-1h\n", t.out.toString())
    }

    @Test
    fun `a declared builtin still wins against a marked slot`() {
        val invocation = assertIs<Result.Success<Invocation>>(
            seekTree(mark = true).parse(listOf("seek", "-h")),
        ).value
        assertIs<Invocation.ShowHelp>(invocation)
    }

    @Test
    fun `a double dash token is never an operand`() {
        // Declares --verbose so --verbsoe has a real spelling to be a typo of: the single-dash restriction
        // exists so a long-option typo keeps its did-you-mean instead of being swallowed as an operand.
        val tree = cli("app") {
            command("seek") {
                flag("--verbose", "-v")
                val position = argument("position").dashLed()
                action { Ok("pos=${position()}") }
            }
        }
        val error = assertIs<Result.Error<CliError>>(
            tree.parse(listOf("seek", "--verbsoe")),
        ).error
        val unknown = assertIs<CliError.UnknownOption>(error)
        assertEquals("--verbose", unknown.suggestion)
    }

    @Test
    fun `a dash led operand ends options when the command says the first operand does`() {
        val tree = cli("app") {
            command("seek") {
                optionsEndAtFirstOperand = true
                val verbose = flag("--verbose", "-v")
                val position = argument("position").dashLed()
                val rest = argument("rest").multiple()
                action { Ok("v=${verbose()} pos=${position()} rest=${rest().joinToString(",")}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("seek", "-1m", "-v"), t))
        assertEquals("v=false pos=-1m rest=-v\n", t.out.toString())
    }

    @Test
    fun `a cluster whose tail is an option value still resolves`() {
        // The characters after a short option are its value, not more shorts to resolve, so `-vp8080`
        // binds as it always has. Without this the marked slot would swallow a token the parser owns.
        val tree = cli("app") {
            command("go") {
                val verbose = flag("--verbose", "-v")
                val port = option("--port", "-p").int()
                val n = argument("n").dashLed().optional()
                action { Ok("v=${verbose()} port=${port()} n=${n()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-vp8080"), t))
        assertEquals("v=true port=8080 n=null\n", t.out.toString())
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
        val error = assertIs<Result.Error<CliError>>(tree.parse(listOf("seek", "-1m", "-2m"))).error
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
        val error = assertIs<Result.Error<CliError>>(tree.parse(listOf("seek", "-1m", "-2m", "f"))).error
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
        assertEquals("from=-1m to=-2m\n", t.out.toString())
    }

    @Test
    fun `a declared short a long and an abbreviation all win against a marked slot`() {
        val tree = cli("app") {
            abbreviation = Abbreviation.Options
            command("go") {
                val verbose = flag("--verbose", "-v")
                val mode = option("--mode")
                val n = argument("n").dashLed()
                action { Ok("v=${verbose()} mode=${mode()} n=${n()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-v", "--mod", "x", "5"), t))
        assertEquals("v=true mode=x n=5\n", t.out.toString())
    }

    @Test
    fun `a numeric alias wins against a marked slot`() {
        val tree = cli("app") {
            command("go") {
                val lines = option("--lines", "-n").int()
                numericAlias(lines)
                val rest = argument("rest").dashLed().optional()
                action { Ok("lines=${lines()} rest=${rest()}") }
            }
        }
        val t = RecordingTerminal()
        assertEquals(0, tree.run(listOf("go", "-5"), t))
        assertEquals("lines=5 rest=null\n", t.out.toString())
    }

    @Test
    fun `the escape still works on a marked command`() {
        // The `--` half of the pair above: a declared `-h` wins on a marked command, and `--` still
        // overrides that, which is the escape dashLed()'s KDoc promises. It holds by construction rather
        // than by this branch of the code: a post-`--` token never enters `dashLedAdmitted`, so rule 3
        // cannot reach it.
        val t = RecordingTerminal()
        assertEquals(0, seekTree(mark = true).run(listOf("seek", "--", "-h"), t))
        assertEquals("pos=-h\n", t.out.toString())
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
        assertEquals("ns=-1m,-2m\n", t.out.toString())
    }

    @Test
    fun `a dash led token past the last slot is an extra argument`() {
        // It lands in no slot at all, so the honest error is the one `seek a b` gets rather than the
        // unknown-option report the same input draws on an unmarked command.
        val error = assertIs<Result.Error<CliError>>(
            seekTree(mark = true).parse(listOf("seek", "-1m", "-2m")),
        ).error
        assertEquals(CliError.TooManyArguments("echoctl seek", listOf("-2m")), error)
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
        assertIs<Result.Error<CliError>>(tree.parse(listOf("go", "-1m", "-2m")))
        assertEquals(
            listOf("saw -2m"),
            tree.completeCandidates(listOf("go", "-1m", "-2m", "")).map { it.value },
        )
    }
}
