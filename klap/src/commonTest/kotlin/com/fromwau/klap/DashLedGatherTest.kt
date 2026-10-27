package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `.dashLed(gather) { }`: a slot that collects every single-dash word its predicate accepts, wherever it stands
 * before `--`, as GNU chmod collects `-w` and `-x` of `chmod -w -x f` into one mode.
 */
class DashLedGatherTest {

    private val modeLetters = "rwxXstugoa01234567+=,".toSet()

    private fun isMode(word: String) = word.drop(1).all { it in modeLetters }

    private fun chmodLike() = cli("chmod") {
        val recursive = flag(short = 'R', long = "recursive")
        val mode = argument("mode")
            .dashLed(gather = ",") { word -> isMode(word) }
            .completeWith { candidates(listOf("MODE")) }
        val files = argument("file").completeWith { candidates(listOf("FILE")) }.multiple(min = 1)
        action<String>(human = { it }) { Ok("R=${recursive()} mode=${mode()} files=${files()}") }
    }

    /** chmod's `--reference=RFILE`, which replaces the mode, and the rule that refuses both. */
    private fun withReference() = cli("chmod") {
        val reference = option(long = "reference").completeWith { candidates(listOf("RFILE")) }
        val mode = argument("mode").dashLed(gather = ",") { word -> isMode(word) }.absentWhen(reference)
        atMostOne(mode, reference)
        val files = argument("file").multiple(min = 1)
        action<String>(human = { it }) { Ok("ref=${reference()} mode=${mode()} files=${files()}") }
    }

    @Test
    fun `every accepted dash word joins the one value with the separator`() {
        assertEquals("R=false mode=-w,-x files=[f]", chmodLike().bindText("-w", "-x", "f"))
        assertEquals("R=false mode=-w,-755 files=[f]", chmodLike().bindText("-w", "-755", "f"))
    }

    @Test
    fun `a gathered word anywhere before -- takes the slot so every operand is a file`() {
        // `chmod u+x -w f` leaves f at 444 and says "cannot access 'u+x'"; `chmod -w f -x` leaves f at 444.
        assertEquals("R=false mode=-w files=[u+x, f]", chmodLike().bindText("u+x", "-w", "f"))
        assertEquals("R=false mode=-w,-x files=[f]", chmodLike().bindText("-w", "f", "-x"))
        assertEquals("R=false mode=-w files=[f]", chmodLike().bindText("f", "-w"))
    }

    @Test
    fun `without a gathered word the slot takes the first operand`() {
        assertEquals("R=false mode=u+x files=[f]", chmodLike().bindText("u+x", "f"))
    }

    @Test
    fun `a word the tree resolves stays an option`() {
        assertEquals("R=true mode=-w files=[d]", chmodLike().bindText("-R", "-w", "d"))
        assertEquals("R=true mode=-w files=[d]", chmodLike().bindText("-w", "-R", "d"))
    }

    @Test
    fun `a word the predicate refuses is an unknown option`() {
        // `chmod -z f` and `chmod -Rz f`: "invalid option -- 'z'".
        val refused = chmodLike().parse(listOf("-z", "f")).assertError<CliError.UnknownOption>()
        assertEquals("-z", refused.token)
        chmodLike().parse(listOf("-Rz", "f")).assertError<CliError.UnknownOption>()
    }

    @Test
    fun `a dash word after -- is a plain operand`() {
        // `chmod -w -- -x f`: "cannot access '-x'"; `chmod -- -w f` leaves f at 444.
        assertEquals("R=false mode=-w files=[-x, f]", chmodLike().bindText("-w", "--", "-x", "f"))
        assertEquals("R=false mode=-w files=[f]", chmodLike().bindText("--", "-w", "f"))
    }

    @Test
    fun `Tab reads a gathered word anywhere on the line`() {
        val tool = chmodLike()
        assertEquals(listOf("FILE"), tool.offeredAt(0, "", "-w"))
        assertEquals(listOf("FILE"), tool.offeredAt(0, "", "-w", "f"))
        assertEquals(listOf("FILE"), tool.offered("-w", ""))
        assertEquals(listOf("MODE"), tool.offered(""))
    }

    @Test
    fun `Tab after a refused dash word still offers the slot`() {
        // An unknown option claims no word, so the word after `chmod -z` is still the mode.
        assertEquals(listOf("MODE"), chmodLike().offered("-z", ""))
        assertEquals(listOf("MODE"), chmodLike().offered("-Rz", ""))
    }

    @Test
    fun `a gathered word counts toward a rule beside an absentWhen trigger`() {
        // `chmod -w --reference=r f` and `chmod --reference=r -w f`: "cannot combine mode and --reference options".
        val tool = withReference()
        tool.parse(listOf("-w", "--reference=r", "f")).assertError<CliError.MutuallyExclusive>()
        tool.parse(listOf("--reference=r", "-w", "f")).assertError<CliError.MutuallyExclusive>()
        assertEquals(emptyList(), tool.offered("-w", "--ref"))
    }

    @Test
    fun `a slot its absentWhen trigger removed counts toward no rule`() {
        // `chmod 755 --reference=f g`: "cannot access '755'", so 755 is a file and the line is accepted.
        val tool = withReference()
        assertEquals("ref=r mode=null files=[755, g]", tool.bindText("755", "--reference=r", "g"))
        assertEquals("ref=r mode=null files=[g]", tool.bindText("--reference=r", "g"))
    }

    @Test
    fun `Tab offers the trigger beside an operand it would make a file`() {
        // `chmod f --reference=r` gives f r's mode: with --reference on the line, f is no mode.
        val tool = withReference()
        assertEquals(listOf("--reference"), tool.offered("f", "--ref"))
        assertEquals(listOf("RFILE"), tool.offeredAt(1, "--reference", "", "f"))
    }

    @Test
    fun `one command gathers into one slot`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                argument("a").dashLed(gather = ",") { true }
                argument("b").dashLed(gather = ",") { true }
                action { Ok("") }
            }
        }
        assertTrue("gather" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a gathering slot holds one value`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                argument("a").dashLed(gather = ",") { true }.multiple()
                action { Ok("") }
            }
        }
        assertTrue("gather" in failure.message.orEmpty(), failure.message)
    }
}
