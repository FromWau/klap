package com.fromwau.klap

import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A global can be recorded by either of two passes — the position-independent pre-strip, or the reached
 * command's own sift when a mixed cluster carries it whole. Whichever claimed an occurrence, the order the
 * user wrote them in is the order that decides.
 */
class GlobalOccurrenceOrderTest {

    @Test
    fun `a negatable global in a fully global cluster takes the polarity written last`() {
        val tree = cli("app") {
            val check = globalFlag("--check", "-c").negatable("-C")
            command("go") { action { Ok("check=${check()}") } }
        }
        // Every character is global, so the pre-strip claims the token and resolves both halves itself.
        // Only their order within the cluster separates these two lines.
        assertEquals("check=false", tree.bindText("go", "-cC"))
        assertEquals("check=true", tree.bindText("go", "-Cc"))
    }

    @Test
    fun `a negatable global in a mixed cluster takes the polarity written last`() {
        val tree = cli("app") {
            val check = globalFlag("--check", "-c").negatable("-C")
            command("go") {
                val verbose = flag("--verbose", "-v")
                action { Ok("check=${check()} v=${verbose()}") }
            }
        }
        // The local `-v` keeps the token out of the pre-strip, so the command's own sift tops the two
        // global halves up instead. The answer must not depend on which pass got there.
        assertEquals("check=false v=true", tree.bindText("go", "-vcC"))
        assertEquals("check=true v=true", tree.bindText("go", "-vCc"))
    }

    @Test
    fun `a repeatable global collects in argv order across both passes`() {
        val tree = cli("app") {
            val tag = globalOption("--tag", "-t").multiple()
            command("go") {
                val verbose = flag("--verbose", "-v")
                val extra = flag("--extra", "-x")
                action { Ok("tags=${tag()} v=${verbose()} x=${extra()}") }
            }
        }
        // `-vt b` is mixed, so only the command's sift records its `-t`; the bare `-t a` is the
        // pre-strip's. The two interleave by where they sit in argv, not by which pass claimed them.
        assertEquals("tags=[b, a] v=true x=true", tree.bindText("go", "-x", "-vt", "b", "-t", "a"))
        assertEquals("tags=[a, b] v=true x=true", tree.bindText("go", "-t", "a", "-x", "-vt", "b"))
    }
}
