package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The cluster walks a parse-level assertion cannot reach on its own: the arity walk that decides which argv
 * index is a value slot, and the two walks that must stay ignorant of the rule.
 */
class NumberOptionWalksTest {

    private fun tree(): Cli = cli("head") {
        val lines = option("--lines", "-n")
        val direct = numberOption()
        val count = lastOneWins(lines, direct)
        val files = argument("file").multiple(min = 0)
        action { Ok("count=${count()} files=${files()}") }
    }

    @Test
    fun `a run does not open the next token to a built in`() {
        // Guideline 6 gives the token after `-n` to `-n` whatever it is spelled, and the built-in scans run
        // before any bind, so they read the arity walk's value slots. A run that stops that walk leaves
        // `--help` looking position-independent again, and the line prints help instead of binding.
        assertEquals("count=--help files=[f]", tree().bindText("-5n", "--help", "f"))
        // The same line without the run, which already behaves this way.
        assertEquals("count=--help files=[f]", tree().bindText("-n", "--help", "f"))
    }

    @Test
    fun `the global pre strip hands a mixed digit cluster over whole`() {
        // siftGlobals is all-or-nothing against GLOBAL specs alone, and the number input belongs to a
        // command. A digit already makes that walk decline the token, which is what must stay true: were it
        // to learn the rule it would claim a token before the command owning the input is known.
        val tree = cli("app") {
            val two = globalFlag("--two", "-2")
            command("go") {
                val n = numberOption().int()
                action { Ok("n=${n()} two=${two()}") }
            }
        }
        // `2` is a global short and `5` names nothing, so the run is not fully covered and the pre-strip
        // must hand the token over rather than claim the `2` out of the middle of a number.
        assertEquals("n=25 two=false", tree.bindText("go", "-25"))
    }

    @Test
    fun `a number does not route past the command that declares it`() {
        // routesTransparently steps the subcommand walk over a token only when every part of it resolves
        // against the GLOBALS, so a run stops the walk and never reaches the command below. At the group
        // the token is what it is there: an unknown option.
        val tree = cli("app") {
            command("go") {
                val n = numberOption().int()
                action { Ok("n=${n()}") }
            }
        }
        assertEquals(
            CliError.UnknownOption("-5"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("-5", "go"))).error,
        )
        assertEquals("n=5", tree.bindText("go", "-5"))
    }

    @Test
    fun `a digit cluster carrying a global after an operand is refused`() {
        // firstUnresolvedShort through the MixedClusterAfterOperands gate: the cluster resolves in full
        // (a run, then a global), so the tail token is a mixed cluster rather than the operand it looks
        // like, and neither reading survives silently.
        val tree = cli("app") {
            globalFlag("--global", "-g")
            command("go") {
                val n = numberOption().int()
                val files = argument("file").multiple(min = 0)
                optionsEndAtFirstOperand = true
                action { Ok("n=${n()} files=${files()}") }
            }
        }
        assertEquals(
            CliError.MixedClusterAfterOperands("-5g", "-g"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("go", "f", "-5g"))).error,
        )
    }
}

/**
 * Which spellings count as declared when a run is judged. A flag's negative shorts are declared as much as
 * its positive ones, so a run they cover is that cluster rather than a number.
 */
class NumberOptionDeclaredShortTest {

    private fun tree(): Cli = cli("app") {
        val n = numberOption().int()
        val four = flag("--four", "-4").negatable("-3")
        val verbose = flag("--verbose", "-v")
        action { Ok("n=${n()} four=${four()} v=${verbose()}") }
    }

    @Test
    fun `a negative short covers a run the way a positive one does`() {
        // Without it `-3` binds the number 3 and the negation is a spelling no line can reach.
        assertEquals("n=null four=false v=false", tree().bindText("-3"))
        assertEquals("n=null four=false v=true", tree().bindText("-3v"))
    }

    @Test
    fun `a run a negative short does not cover in full is still the number`() {
        // `four` reads its untouched default: nothing in the token reached the flag either way.
        assertEquals("n=35 four=true v=false", tree().bindText("-35"))
    }
}
