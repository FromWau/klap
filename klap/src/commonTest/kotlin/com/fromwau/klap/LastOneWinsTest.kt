package com.fromwau.klap

import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `lastOneWins` folds several spellings of one quantity into a single handle, and that handle is itself an
 * ordinary member of an enclosing `lastWins` set — which is what lets `head` hold one set over a line count
 * and a byte count without collapsing the two into one value.
 */
class LastOneWinsTest {

    private fun tree(): Cli = cli("app") {
        val a = option("--alpha", "-a").int()
        val b = option("--beta", "-b").int()
        val c = option("--gamma", "-c").int()
        val ab = lastOneWins(a, b)
        lastWins(ab, c)
        action { Ok("ab=${ab()} c=${c()}") }
    }

    @Test
    fun `the fold reports whichever member was written last`() {
        assertEquals("ab=1 c=null", tree().bindText("-a", "1"))
        assertEquals("ab=2 c=null", tree().bindText("-b", "2"))
        assertEquals("ab=2 c=null", tree().bindText("-a", "1", "-b", "2"))
        assertEquals("ab=1 c=null", tree().bindText("-b", "2", "-a", "1"))
    }

    @Test
    fun `an untouched fold reads absent`() {
        assertEquals("ab=null c=null", tree().bindText())
    }

    @Test
    fun `the fold is an ordinary member of an enclosing lastWins set`() {
        assertEquals("ab=null c=5", tree().bindText("-a", "1", "-c", "5"))
        assertEquals("ab=1 c=null", tree().bindText("-c", "5", "-a", "1"))
    }

    @Test
    fun `a fold nested inside another fold reports its own most recent member`() {
        fun nested(): Cli = cli("app") {
            val a = option("--alpha", "-a").int()
            val b = option("--beta", "-b").int()
            val c = option("--gamma", "-c").int()
            val inner = lastOneWins(a, b)
            val outer = lastOneWins(inner, c)
            action { Ok("outer=${outer()}") }
        }
        assertEquals("outer=1", nested().bindText("-c", "5", "-a", "1"))
        assertEquals("outer=1", nested().bindText("-a", "1"))
        // The ordering a fold that stands nowhere gets right by accident, so it only holds once the
        // inner fold reports a position of its own.
        assertEquals("outer=5", nested().bindText("-a", "1", "-c", "5"))
    }

    @Test
    fun `a fold two levels down still stands where its member stands`() {
        fun nested(): Cli = cli("app") {
            val a = option("--alpha", "-a").int()
            val b = option("--beta", "-b").int()
            val c = option("--gamma", "-c").int()
            val d = option("--delta", "-d").int()
            val outer = lastOneWins(lastOneWins(lastOneWins(a, b), c), d)
            action { Ok("outer=${outer()}") }
        }
        assertEquals("outer=1", nested().bindText("-d", "9", "-a", "1"))
        assertEquals("outer=9", nested().bindText("-a", "1", "-d", "9"))
    }

    @Test
    fun `a defaulted member does not leak through the fold`() {
        // What a `a() ?: b()` read gets wrong: a lastWins loser binds absentValue(), which is its
        // .default() when it has one, so the null-coalescing read answers with the loser.
        val defaults = cli("app") {
            val a = option("--alpha", "-a").int().default(10)
            val b = option("--beta", "-b").int().default(20)
            val ab = lastOneWins(a, b)
            action { Ok("ab=${ab()}") }
        }
        assertEquals("ab=2", defaults.bindText("-a", "1", "-b", "2"))
        assertEquals("ab=1", defaults.bindText("-b", "2", "-a", "1"))
    }

    @Test
    fun `both members advertise the set on their own help rows`() {
        val help = tree().helpText()
        // Row by row, not one substring over the whole text: that is satisfied by either member alone,
        // and "on their own rows" is the claim.
        val rows = help.lineSequence().filter { "last of -a, -b wins" in it }.toList()
        assertEquals(2, rows.size, help)
        assertTrue(rows.any { "--alpha" in it }, help)
        assertTrue(rows.any { "--beta" in it }, help)
    }

    @Test
    fun `a fold over one input fails to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val a = option("--alpha", "-a").int()
                lastOneWins(a)
                action { Ok("") }
            }
        }
        assertTrue("needs at least two inputs" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a required fold fails to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val a = option("--alpha", "-a").int()
                val b = option("--beta", "-b").int()
                lastOneWins(a, b).required()
                action { Ok("") }
            }
        }
        assertTrue("cannot be .required()" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a repeatable fold fails to build`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                val a = option("--alpha", "-a").int()
                val b = option("--beta", "-b").int()
                lastOneWins(a, b).multiple()
                action { Ok("") }
            }
        }
        assertTrue("cannot be .multiple()" in ex.message.orEmpty(), ex.message)
    }
    /**
     * A short cluster orders by the character within it, and a position encoding that packed that character
     * into the token index had a width past which a long cluster reached into the NEXT token's range — so a
     * member written last stopped winning once enough flags preceded it. Padded well past any such width,
     * with the pad flags irrelevant to the set: only where `-b` and `-c` sit decides this.
     */
    @Test
    fun `a long short cluster does not reorder a later token`() {
        val padded = cli("app") {
            val a = option("--alpha", "-a").int()
            val b = option("--beta", "-b").int()
            val c = option("--gamma", "-c").int()
            val pad = flag("--pad", "-p")
            val ab = lastOneWins(a, b)
            lastWins(ab, c)
            action { Ok("ab=${ab()} c=${c()} pad=${pad()}") }
        }
        for (width in listOf(1, 999, 1000, 1001, 4096)) {
            val cluster = "-" + "p".repeat(width) + "b"
            // `-c` is written last, so `-c` takes the set at every cluster width.
            assertEquals("ab=null c=5 pad=true", padded.bindText(cluster, "2", "-c", "5"), "width $width")
            // ...and the mirror: `-b` last means the fold takes it, again at every width.
            assertEquals("ab=2 c=null pad=true", padded.bindText("-c", "5", cluster, "2"), "width $width")
        }
    }
}
