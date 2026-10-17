package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `.enum<E>(abbreviates = false)`: an option whose values match only whole, whatever the CLI's [Abbreviation], as
 * rm compares `--preserve-root=all` ("unrecognized --preserve-root argument: 'al'").
 */
class EnumWholeValueTest {

    private enum class RootScope { All }

    private enum class When(val words: List<String>) {
        Never(listOf("never", "no", "none")),
        Always(listOf("always", "yes")),
    }

    private fun rm(mode: Abbreviation = Abbreviation.Options, ignoreCase: Boolean = false) = cli("rm") {
        abbreviation = mode
        val preserveRoot = option(long = "preserve-root").enum<RootScope>(ignoreCase = ignoreCase, abbreviates = false)
        val interactive = option(long = "interactive").enum<When>(abbreviates = false, spellings = { it.words })
        val reply = option(long = "reply").enum<When>(spellings = { it.words })
        action<String>(human = { it }) { Ok("root=${preserveRoot()} when=${interactive()} reply=${reply()}") }
    }

    @Test
    fun `a whole value binds`() {
        assertEquals("root=All when=null reply=null", rm().bindText("--preserve-root=all"))
        assertEquals("root=null when=Never reply=null", rm().bindText("--interactive=none"))
        assertEquals("root=null when=Always reply=null", rm().bindText("--interactive=yes"))
    }

    @Test
    fun `a prefix of a value is an invalid choice in every mode that abbreviates values`() {
        for (mode in listOf(Abbreviation.Options, Abbreviation.All)) {
            for (typed in listOf("a", "al")) {
                val err = rm(mode).parse(listOf("--preserve-root=$typed")).assertError<CliError.InvalidChoice>()
                assertEquals(
                    listOf("--preserve-root", typed, "all"),
                    listOf(err.name, err.value, err.choices.single()),
                    "${mode.name} $typed",
                )
            }
            val err = rm(mode).parse(listOf("--interactive=nev")).assertError<CliError.InvalidChoice>()
            assertEquals(listOf("never", "no", "none", "always", "yes"), err.choices, mode.name)
        }
    }

    @Test
    fun `its long still abbreviates`() {
        assertEquals("root=All when=null reply=null", rm().bindText("--pre=all"))
    }

    @Test
    fun `another enum keeps its prefixes`() {
        assertEquals("root=null when=null reply=Never", rm().bindText("--reply=nev"))
    }

    @Test
    fun `ignoreCase reads the whole value in any case and still refuses a prefix`() {
        assertEquals("root=All when=null reply=null", rm(ignoreCase = true).bindText("--preserve-root=ALL"))
        rm(ignoreCase = true).parse(listOf("--preserve-root=AL")).assertError<CliError.InvalidChoice>()
        rm().parse(listOf("--preserve-root=ALL")).assertError<CliError.InvalidChoice>()
    }

    @Test
    fun `completion still offers the whole value for a prefix`() {
        assertEquals(listOf("--preserve-root=all"), rm().offered("--preserve-root=a"))
        assertEquals(listOf("--interactive=no", "--interactive=none"), rm().offered("--interactive=no"))
    }

    @Test
    fun `an optional value still binds bare`() {
        val tree = cli("rm") {
            abbreviation = Abbreviation.Options
            val preserveRoot = option(long = "preserve-root").enum<RootScope>(abbreviates = false).optionalValue()
            action<String>(human = { it }) { Ok("root=${preserveRoot()}") }
        }
        assertEquals("root=Bare", tree.bindText("--preserve-root"))
        assertEquals("root=Given(value=All)", tree.bindText("--preserve-root=all"))
        tree.parse(listOf("--preserve-root=al")).assertError<CliError.InvalidChoice>()
    }
}
