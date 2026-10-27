package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** cp's operands: `SOURCE... DEST`, no DEST under `-t`, and one SOURCE under `-T`. */
private fun cpLike(): Cli = cli("cp") {
    val target = option(short = 't', long = "target-directory")
    val noTarget = flag(short = 'T', long = "no-target-directory")
    val sources = argument("source").file().multiple(min = 1).atMostWhen(noTarget, 1)
    val dest = argument("dest").file().absentWhen(target)
    action<String>(human = { it }) { Ok("sources=${sources()} dest=${dest()}") }
}

/** A trailing variadic with a fixed cap, and a tighter one while `-1` is given. */
private fun pairLike(): Cli = cli("pair") {
    val one = flag(short = '1', long = "one")
    val files = argument("file").file().multiple(max = 2).atMostWhen(one, 1)
    action<String>(human = { it }) { Ok("files=${files()}") }
}

private fun Cli.extras(vararg args: String): CliError.TooManyArguments =
    parse(args.toList()).assertError<CliError.TooManyArguments>()

class OperandCapTest {

    @Test
    fun `a variadic takes at most its declared maximum`() {
        assertEquals("files=[a, b]", pairLike().bindText("a", "b"))
        assertEquals(CliError.TooManyArguments("pair", listOf("c")), pairLike().extras("a", "b", "c"))
    }

    @Test
    fun `a variadic is unbounded by default`() {
        assertEquals("sources=[a, b, c] dest=d", cpLike().bindText("a", "b", "c", "d"))
    }

    @Test
    fun `a cap holds while its input is given and the operand past it is extra`() {
        // `cp -T a b c` and `cp a b c -T`: "extra operand 'c'"; `cp -T a b` copies a to b.
        assertEquals("sources=[a] dest=b", cpLike().bindText("-T", "a", "b"))
        assertEquals(CliError.TooManyArguments("cp", listOf("c")), cpLike().extras("-T", "a", "b", "c"))
        assertEquals(CliError.TooManyArguments("cp", listOf("c")), cpLike().extras("a", "b", "c", "-T"))
        assertEquals(CliError.TooManyArguments("pair", listOf("b")), pairLike().extras("-1", "a", "b"))
    }

    @Test
    fun `a cap does not apply while its input is absent`() {
        assertEquals("sources=[a, b] dest=c", cpLike().bindText("a", "b", "c"))
    }

    @Test
    fun `a minimum still holds under a cap`() {
        assertEquals(CliError.MissingArgument("cp", "dest"), cpLike().parse(listOf("-T", "a")).assertError())
    }

    @Test
    fun `a maximum the variadic cannot use is refused when the cli is built`() {
        assertFailsWith<IllegalArgumentException> { argumentTool { argument("f").multiple(min = 2, max = 1) } }
        assertFailsWith<IllegalArgumentException> { argumentTool { argument("f").multiple(max = 0) } }
        assertFailsWith<IllegalArgumentException> {
            argumentTool { argument("f").multiple().atMostWhen(flag(short = 'x'), -1) }
        }
    }

    @Test
    fun `atMostWhen is refused on a slot that is not multiple`() {
        assertFailsWith<IllegalArgumentException> {
            argumentTool { argument("f").map { listOf(it) }.atMostWhen(flag(short = 'x'), 1) }
        }
    }

    @Test
    fun `atMostWhen is refused with an input of another command`() {
        assertFailsWith<IllegalArgumentException> {
            cli("tool") {
                val elsewhere = command("other") {
                    val x = flag(short = 'x')
                    action { Ok("") }
                    x
                }
                command("run") {
                    argument("f").multiple().atMostWhen(elsewhere, 1)
                    action { Ok("") }
                }
            }
        }
    }

    private fun argumentTool(block: CommandBuilder.() -> Unit): Cli = cli("tool") {
        block()
        action { Ok("") }
    }

    @Test
    fun `help states the cap and the input that sets one`() {
        val pair = pairLike().helpText().lines().map { it.trim().replace(Regex(" {2,}"), "  ") }
        assertTrue("<file>  (at most 1 when --one; optional; repeatable, max 2)" in pair, "$pair")
        val cp = cpLike().helpText().lines().map { it.trim().replace(Regex(" {2,}"), "  ") }
        assertTrue("<source>  (at most 1 when --no-target-directory; repeatable, min 1)" in cp, "$cp")
    }

    @Test
    fun `completion offers no operand once the cap is reached`() {
        val files = listOf(COMPLETE_FILES)
        assertEquals(emptyList(), cpLike().offered("-T", "a", "b", ""))
        assertEquals(files, cpLike().offered("-T", "a", ""))
        assertEquals(files, cpLike().offered("a", "b", ""))
        assertEquals(emptyList(), pairLike().offered("a", "b", ""))
        assertEquals(files, pairLike().offered("a", ""))
    }

    @Test
    fun `completion counts the operands after the cursor toward the cap`() {
        assertEquals(emptyList(), cpLike().offeredAt(0, "", "-T", "a", "b"))
        assertEquals(emptyList(), cpLike().offeredAt(2, "-T", "a", "", "b"))
        assertEquals(listOf(COMPLETE_FILES), cpLike().offeredAt(1, "-T", "", "b"))
        assertEquals(emptyList(), pairLike().offeredAt(0, "", "a", "b"))
    }

    /** `SOURCE... DEST` where `-f` drops SOURCE's minimum, so a lone operand under `-f` is the DEST. */
    private fun relaxedSources(): Cli = cli("cp") {
        val force = flag(short = 'f', long = "force")
        val sources = argument("source").multiple(min = 1).optionalWhen(force).completeWith { candidate("SRC") }
        val dest = argument("dest").completeWith { candidate("DEST") }
        action<String>(human = { it }) { Ok("sources=${sources()} dest=${dest()}") }
    }

    @Test
    fun `a relaxed minimum hands a lone operand to the slot after the variadic`() {
        assertEquals("sources=[] dest=d", relaxedSources().bindText("-f", "d"))
        assertEquals(listOf("DEST"), relaxedSources().offered("-f", ""))
        assertEquals(listOf("SRC"), relaxedSources().offered(""))
    }
}
