package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A head-shaped tree: both spellings of the line count folded into one handle, the override partner real
 * head gives them in a set with that handle, plus a flag to write around the digits and the operands a
 * misread `-NUM` would silently empty.
 *
 * The fold is `lastOneWins`, not `named() ?: direct()`: a loser binds `absentValue()`, which is its
 * `.default()` when it has one, so the `?:` reading answers with the loser the moment either spelling
 * gains a default.
 */
private fun headTree(): Cli = cli("head") {
    val named = option("--lines", "-n").int()
    val direct = numberOption().int()
    val bytes = option("--bytes", "-c").int()
    val lines = lastOneWins(named, direct)
    lastWins(lines, bytes)
    val quiet = flag("--quiet", "-q")
    val verbose = flag("--verbose", "-v")
    val files = argument("file").multiple(min = 0)
    action {
        Ok("lines=${lines()} bytes=${bytes()} quiet=${quiet()} verbose=${verbose()} files=${files()}")
    }
}

private fun Cli.errorFrom(vararg argv: String): CliError =
    assertIs<Result.Error<CliError>>(parse(argv.toList())).error

/**
 * `-<NUM>` is an input of its own, and a maximal run of digits that nothing else has claimed binds to it
 * wherever in the token it sits. GNU head accepts `head -5v f` and rejects `head -v5 f`, because a
 * value-taking short takes the rest of its cluster and only position zero is unambiguous to a reader;
 * klap resolves that from the declarations and accepts both.
 */
class NumberOptionTest {

    @Test
    fun `a whole token of digits binds the number`() {
        assertEquals(
            "lines=5 bytes=null quiet=false verbose=false files=[f]",
            headTree().bindText("-5", "f"),
        )
        // The commonest real invocation of the form, and the one every other case here is a variation of:
        // a multi-digit run with nothing around it.
        assertEquals(
            "lines=12 bytes=null quiet=false verbose=false files=[f]",
            headTree().bindText("-12", "f"),
        )
        assertEquals(
            "lines=12 bytes=null quiet=false verbose=false files=[]",
            headTree().bindText("-12"),
        )
    }

    @Test
    fun `digits opening a cluster bind and the rest of the cluster still resolves`() {
        // `head -5v f` prints five lines with headers on real head; klap refuses it today.
        assertEquals(
            "lines=5 bytes=null quiet=false verbose=true files=[f]",
            headTree().bindText("-5v", "f"),
        )
    }

    @Test
    fun `digits written after a flag still bind`() {
        // Where klap is deliberately looser than head, which answers `invalid trailing option -- 5`: that
        // restriction exists because a reader cannot tell a value from a number, and klap can.
        assertEquals(
            "lines=5 bytes=null quiet=false verbose=true files=[f]",
            headTree().bindText("-v5", "f"),
        )
    }

    @Test
    fun `the digit run is maximal`() {
        // Twelve, not one then two. A per-digit reading is the dangerous alternative because it does not
        // fail: `-12v` would be two occurrences of one input, the last would win, and the line would
        // silently print two lines instead of twelve with nothing to say it split.
        assertEquals(
            "lines=12 bytes=null quiet=false verbose=true files=[f]",
            headTree().bindText("-12v", "f"),
        )
        // The run is judged where it starts, not where the token does, so a flag ahead of it changes nothing.
        assertEquals(
            "lines=12 bytes=null quiet=false verbose=true files=[f]",
            headTree().bindText("-v12", "f"),
        )
    }

    @Test
    fun `a value taking short still swallows the rest of its cluster`() {
        // `-c5` is bytes=5, never a number: the value rule runs first and leaves no run behind.
        assertEquals(
            "lines=null bytes=5 quiet=false verbose=false files=[f]",
            headTree().bindText("-c5", "f"),
        )
    }

    @Test
    fun `a run every character of which is declared is that cluster`() {
        // POSIX guideline 14, scoped to the run: `-4` is fully covered by the declared short, `-45` is
        // not, and `-45` with both declared is again. `ping -4` is why this cannot simply be refused.
        val one = cli("app") {
            val n = numberOption().int()
            val four = flag("-4")
            val verbose = flag("-v")
            action { Ok("n=${n()} four=${four()} v=${verbose()}") }
        }
        assertEquals("n=null four=true v=false", one.bindText("-4"))
        assertEquals("n=45 four=false v=false", one.bindText("-45"))
        // The run starts where the digits start, not where the token does: `-v` is claimed first, and the
        // run left behind is judged on its own characters.
        assertEquals("n=45 four=false v=true", one.bindText("-v45"))

        val both = cli("app") {
            val n = numberOption().int()
            val four = flag("-4")
            val five = flag("-5")
            action { Ok("n=${n()} four=${four()} five=${five()}") }
        }
        assertEquals("n=null four=true five=true", both.bindText("-45"))
    }

    @Test
    fun `a run outranks a value taking digit short that does not cover it`() {
        // The one place the two rules read as disagreeing. `-2` takes the rest of its cluster, so rule 1
        // alone would call `-25` the value 5; the run `25` is not fully covered by declared shorts, so
        // rule 2 calls it the number 25. Rule 2 wins, which is also what klap answers today.
        val tree = cli("app") {
            val n = numberOption().int()
            val two = option("--two", "-2").int()
            action { Ok("n=${n()} two=${two()}") }
        }
        assertEquals("n=25 two=null", tree.bindText("-25"))
        assertEquals("n=235 two=null", tree.bindText("-235"))
        // ...and where the run IS fully covered, the cluster reading holds and `-2` takes its value.
        assertEquals("n=null two=5", tree.bindText("-2", "5"))
    }

    @Test
    fun `a number carries a position that lastWins can order`() {
        // Real head prints five bytes for `head -2 -c 5 f`, so the number must be an ordinary member of
        // the set. This is the case example/head's parity rests on, and the reason the handle has to be a
        // real Input rather than a value read back beside one.
        assertEquals(
            "lines=null bytes=5 quiet=false verbose=false files=[f]",
            headTree().bindText("-2", "-c", "5", "f"),
        )
        assertEquals(
            "lines=3 bytes=null quiet=false verbose=false files=[f]",
            headTree().bindText("-c", "5", "-3", "f"),
        )
    }

    @Test
    fun `the last number wins`() {
        // `git log -2 -1` shows one commit.
        assertEquals(
            "lines=1 bytes=null quiet=false verbose=false files=[]",
            headTree().bindText("-2", "-1"),
        )
    }

    @Test
    fun `where the number sits in the command is not constrained`() {
        // klap does not adopt head's leading-only rule: a dash-led token is an option under guideline 14,
        // and a CLI declaring a number option has told its users so. `--` and `./` remain the escapes.
        assertEquals(
            "lines=2 bytes=null quiet=true verbose=false files=[f]",
            headTree().bindText("-q", "-2", "f"),
        )
        assertEquals(
            "lines=2 bytes=null quiet=false verbose=false files=[f]",
            headTree().bindText("f", "-2"),
        )
    }

    @Test
    fun `the end of options marker is unaffected`() {
        assertEquals(
            "lines=2 bytes=null quiet=false verbose=false files=[f]",
            headTree().bindText("-2", "--", "f"),
        )
        assertEquals(
            "lines=null bytes=null quiet=false verbose=false files=[-2]",
            headTree().bindText("--", "-2"),
        )
    }

    @Test
    fun `the handle takes the ordinary converters and reports under its label`() {
        // No range parameter on the declaration: the handle is an input like any other. It has no
        // spelling — requireValidSpelling refuses a one-dash name longer than two characters — so
        // `-<NUM>` is a display label an error names it by, never something a user can type.
        val tree = cli("head") {
            val n = numberOption().int().range(1..10)
            action { Ok("n=${n()}") }
        }
        assertEquals("n=5", tree.bindText("-5"))
        assertEquals(CliError.BadValue("-<NUM>", "50", "must be in 1..10"), tree.errorFrom("-50"))
    }

    @Test
    fun `a leading zero is not special to the parser`() {
        // The run is handed to the converters as written, so `.int()` decides what `007` means. Worth
        // pinning because a parser that treated a leading zero as a mode (octal, or "not a number") is a
        // plausible thing to write by accident, and nothing else here would catch it.
        val tree = cli("app") {
            val n = numberOption().int()
            action { Ok("n=${n()}") }
        }
        assertEquals("n=0", tree.bindText("-0"))
        assertEquals("n=7", tree.bindText("-007"))
    }

    @Test
    fun `a run too large for its converter is the converters own error`() {
        // Recognition and conversion are separate: the digits are a number to the parser whatever their
        // magnitude, and `.int()` rejects them exactly as it rejects an oversized `--lines` value. The
        // failure must name the run and the handle's label, not report an unknown option.
        val tree = cli("app") {
            val n = numberOption().int()
            action { Ok("n=${n()}") }
        }
        assertEquals(
            CliError.BadValue("-<NUM>", "99999999999999999999", "not an integer", ConversionError.NotAnInteger),
            tree.errorFrom("-99999999999999999999"),
        )
    }

    @Test
    fun `the handle has a help row of its own`() {
        // A nameless input renders its signature from `shorts + longs` and would otherwise show a
        // described row naming nothing, so the display label has to reach the help render.
        val tree = cli("app") {
            numberOption().int().range(1..10)
            action { Ok("") }
        }
        val help = tree.helpText()
        assertContains(help, "-<NUM>")
        assertContains(help, "1..10")
    }

    @Test
    fun `an undeclared character in the cluster is reported as itself`() {
        // The run binds, so the cluster is blamed at the character that actually names nothing.
        assertEquals(
            CliError.UnknownOption("-x", cluster = "-12x"),
            headTree().errorFrom("-12x", "f"),
        )
    }

    @Test
    fun `the number is an input of its own and needs no option beside it`() {
        val tree = cli("app") {
            val count = numberOption().int()
            action { Ok("count=${count()}") }
        }
        assertEquals("count=7", tree.bindText("-7"))
        assertEquals("count=null", tree.bindText())
    }

    @Test
    fun `two runs in one token are two occurrences of one input`() {
        // Each run stands alone, so `-1a2` is 1 then 2 with `a` resolved between them. `.multiple()`
        // collects both; without it the last wins, as a second occurrence of any option would.
        val lastWins = cli("app") {
            val n = numberOption().int()
            val a = flag("-a")
            action { Ok("n=${n()} a=${a()}") }
        }
        assertEquals("n=2 a=true", lastWins.bindText("-1a2"))

        val collected = cli("app") {
            val n = numberOption().int().multiple(min = 0)
            val a = flag("-a")
            action { Ok("n=${n()} a=${a()}") }
        }
        assertEquals("n=[1, 2] a=true", collected.bindText("-1a2"))

        // Only one run when the character between them takes a value: `-a` claims the `2` by the ordinary
        // cluster rule, so there is nothing left for a second run to be cut from.
        val valueTaking = cli("app") {
            val n = numberOption().int()
            val a = option("--a-opt", "-a")
            action { Ok("n=${n()} a=${a()}") }
        }
        assertEquals("n=1 a=2", valueTaking.bindText("-1a2"))
    }

    @Test
    fun `a number outranks a marked slot`() {
        // Constraint 1: the dash-led admission asks its predicate BEFORE the cluster walk starts, so a
        // run that resolves has to resolve inside that predicate; nothing can run "ahead of" it. Without
        // that, the whole token is taken as an operand.
        val tree = cli("app") {
            val n = numberOption().int()
            val rest = argument("rest").dashLed().multiple(min = 0)
            action { Ok("n=${n()} rest=${rest()}") }
        }
        assertEquals("n=5 rest=[]", tree.bindText("-5"))
    }

    @Test
    fun `a run does not stop the token after it from being claimed`() {
        // Constraint 4: the arity walk must step past a run rather than stop at it, or `-5n` claims its
        // value when binding and claims nothing in the walk that decides value slots.
        assertEquals(
            "lines=7 bytes=null quiet=false verbose=false files=[]",
            headTree().bindText("-5n", "7"),
        )
        // The attached form has to split the same way: the run `3`, then `-n` taking `7`. Distinct digits
        // so the assertion can tell which of the two the fold answered with — `-n` sits later in the
        // cluster, so it wins the set.
        assertEquals(
            "lines=7 bytes=null quiet=false verbose=false files=[]",
            headTree().bindText("-3n7"),
        )
    }

    @Test
    fun `a cluster carrying the help short asks for help once a run resolves`() {
        // Constraints 1 and 3: namesHelpShort stops at any character resolving to nothing, so `-5h` is
        // not a help request today. Once a run resolves it must be one, or the help ladder and the bind
        // disagree about the same cluster.
        val tree = cli("app") {
            val n = numberOption().int()
            action { Ok("n=${n()}") }
        }
        val shown = assertIs<Result.Success<Invocation>>(tree.parse(listOf("-5h"))).value
        assertEquals("app", assertIs<Invocation.ShowHelp>(shown).qualifiedName)
    }
}
