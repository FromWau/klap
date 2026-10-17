package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `.enum<E>(spellings = )`: an entry typed as any of several words, read as gnulib's argmatch reads rm's
 * `--interactive` (`never`, `no`, `none` / `once` / `always`, `yes`).
 */
class EnumSpellingsTest {

    private enum class When(val words: List<String>) {
        Never(listOf("never", "no", "none")),
        Once(listOf("once")),
        Always(listOf("always", "yes")),
    }

    private enum class Reply(val words: List<String>) {
        Never(listOf("never", "no")),
        Now(listOf("now")),
    }

    private fun rm(mode: Abbreviation = Abbreviation.Options, ignoreCase: Boolean = false) = cli("rm") {
        abbreviation = mode
        val interactive = option(long = "interactive").enum<When>(ignoreCase = ignoreCase, spellings = { it.words })
        action<String>(human = { it }) { Ok("when=${interactive()}") }
    }

    private fun reply() = cli("t") {
        abbreviation = Abbreviation.Options
        val reply = option(long = "reply").enum<Reply>(spellings = { it.words })
        action<String>(human = { it }) { Ok("reply=${reply()}") }
    }

    @Test
    fun `each entry is typed as any of its words`() {
        // `rm --interactive=no a` and `=none` remove without asking, `=yes` prompts.
        for ((word, entry) in listOf("never" to "Never", "no" to "Never", "none" to "Never", "yes" to "Always")) {
            assertEquals("when=$entry", rm().bindText("--interactive=$word"), word)
        }
    }

    @Test
    fun `a prefix whose every word names one entry is that entry`() {
        // `rm --interactive=n a` and `=non` remove without asking, `=y` and `=a` prompt.
        val read = listOf("n" to "Never", "non" to "Never", "o" to "Once", "a" to "Always", "y" to "Always")
        for ((typed, entry) in read) {
            assertEquals("when=$entry", rm().bindText("--interactive=$typed"), typed)
        }
    }

    @Test
    fun `a prefix reaching words of two entries is ambiguous`() {
        assertEquals(
            CliError.AmbiguousValue("--reply", "n", listOf("never", "no", "now")),
            reply().parse(listOf("--reply=n")).assertError<CliError.AmbiguousValue>(),
        )
    }

    @Test
    fun `a whole word beats being a prefix of another entry's word`() {
        assertEquals("reply=Never", reply().bindText("--reply=no"))
        assertEquals("reply=Never", reply().bindText("--reply=ne"))
    }

    @Test
    fun `a value abbreviates only where the CLI abbreviates values`() {
        // pacman's reading: the long abbreviates, its value does not.
        for (mode in listOf(Abbreviation.None, Abbreviation.LongOptions)) {
            assertEquals("when=Never", rm(mode).bindText("--interactive=no"), mode.name)
            rm(mode).parse(listOf("--interactive=n")).assertError<CliError.InvalidChoice>()
        }
    }

    @Test
    fun `ignoreCase reads every word and its prefixes in any case`() {
        assertEquals("when=Never", rm(ignoreCase = true).bindText("--interactive=NONE"))
        assertEquals("when=Never", rm(ignoreCase = true).bindText("--interactive=N"))
        rm().parse(listOf("--interactive=NO")).assertError<CliError.InvalidChoice>()
    }

    @Test
    fun `a value that is no word lists every word in declared order`() {
        // `rm --interactive=bogus a`: "Valid arguments are: 'never', 'no', 'none' / 'once' / 'always', 'yes'".
        val err = rm().parse(listOf("--interactive=bogus")).assertError<CliError.InvalidChoice>()
        assertEquals(listOf("never", "no", "none", "once", "always", "yes"), err.choices)
    }

    @Test
    fun `completion offers every word in declared order`() {
        assertEquals(
            listOf("never", "no", "none", "once", "always", "yes").map { "--interactive=$it" },
            rm().offered("--interactive="),
        )
        assertEquals(listOf("--interactive=no", "--interactive=none"), rm().offered("--interactive=no"))
    }

    @Test
    fun `help names each entry by its first word`() {
        val help = rm().helpText()
        assertTrue("--interactive <never|once|always>" in help, help)
    }

    @Test
    fun `an optional value binds an entry bare`() {
        val tree = cli("rm") {
            abbreviation = Abbreviation.Options
            val interactive = option(long = "interactive").enum<When>(spellings = { it.words }).optionalValue()
            action<String>(human = { it }) { Ok("when=${interactive()}") }
        }
        assertEquals("when=Bare", tree.bindText("--interactive"))
        assertEquals("when=Given(value=Never)", tree.bindText("--interactive=n"))
    }

    private enum class Clash(val words: List<String>) {
        One(listOf("one", "same")),
        Two(listOf("same")),
    }

    private enum class Silent(val words: List<String>) {
        Spoken(listOf("spoken")),
        Mute(emptyList()),
    }

    @Test
    fun `a word two entries share throws when the CLI is built`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                option(long = "x").enum<Clash>(spellings = { it.words })
                action { Ok("") }
            }
        }
        assertEquals("enum Clash: duplicate choice 'same'", thrown.message)
    }

    @Test
    fun `an entry with no word throws when the CLI is built`() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            cli("t") {
                option(long = "x").enum<Silent>(spellings = { it.words })
                action { Ok("") }
            }
        }
        assertEquals("enum Silent spells Mute with no word", thrown.message)
    }
}
