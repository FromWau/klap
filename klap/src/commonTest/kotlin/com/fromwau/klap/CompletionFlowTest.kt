package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.acceptSuggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import com.fromwau.klap.testing.tab
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tab from a typed line to the line once a suggestion is taken: what is offered for each kind of slot with the
 * cursor on no word, at the end of a word or inside one, at the end of the line or before more of it.
 */
class CompletionFlowTest {

    private val rm = cli("rm") {
        flag("--recursive", "-r", help = "remove directories and their contents")
        option("--backend", "-b", help = "the backend to remove through")
        flag("--force", "-f", help = "ignore nonexistent files")
        action { Ok("") }
    }

    private val files = listOf("file1.toml", "file2.toml")

    private val tool = cli("tool") {
        command("check") {
            flag("--raw", "-r", help = "print strings without quotes")
            option("--file", "-f", help = "the file to read").completeWith { candidates(files) }
            option("--toml", help = "the TOML version").choice("1.0", "1.1")
            argument("path").multiple().completeWith { candidates(files) }
            action { Ok("") }
        }
        command("get") {
            val file = option("--file", "-f", help = "the file to read").file()
            // Offered once `--file` is given, wherever on the line it stands.
            argument("filter").completeWith { if (file() != null) candidate(".owner") }
            action { Ok("") }
        }
    }

    /** [line] offers [offered], and taking the first of them makes it [taken]; the cursor is at [cursor]. */
    private fun Cli.assertFlow(
        line: String,
        offered: List<Suggestion>,
        taken: String,
        cursor: Int = line.length,
    ) {
        val suggestions = suggestionsFor(line, cursor)
        assertEquals(offered, suggestions, "what `$line` offers with the cursor at $cursor")
        val first = suggestions.first()
        assertEquals(taken, acceptSuggestion(line, first, cursor), "`$line` once $first is taken")
    }

    @Test
    fun `a short cluster offers each remaining short with its description`() {
        // As `pacman -Q<TAB>` lists `-Qi` and the rest: the cluster so far and one more short each.
        rm.assertFlow(
            "rm -r",
            listOf(
                Suggestion("-rb", "the backend to remove through"),
                Suggestion("-rf", "ignore nonexistent files"),
                Suggestion("-rh", "Show this help"),
            ),
            taken = "rm -rb",
        )
        assertEquals("rm -rf", acceptSuggestion("rm -r", rm.suggestionsFor("rm -r")[1]))
    }

    @Test
    fun `the guide's rm example offers and takes as it says`() {
        val rm = cli("rm") {
            flag("--recursive", "-r", help = "remove directories and their contents")
            flag("--force", "-f", help = "ignore nonexistent files")
            action { Ok("") }
        }
        assertEquals(Suggestion("-rf", "ignore nonexistent files"), rm.suggestionsFor("rm -r").first())
        assertEquals("rm -rf", acceptSuggestion("rm -r", Suggestion("-rf")))
    }

    @Test
    fun `a subcommand completes on no word at the end of one and inside one`() {
        val all = listOf("check", "get", "completion", "docs").map { name ->
            Suggestion(name, tool.suggestionsFor("tool $name").single().description)
        }
        tool.assertFlow("tool ", all, "tool check")
        tool.assertFlow("tool  a.toml", all, "tool check a.toml", cursor = 5)
        tool.assertFlow("tool ch", all.take(1), "tool check")
        tool.assertFlow("tool ch a.toml", all.take(1), "tool check a.toml", cursor = 7)
        // The rest of the word after the cursor stays, as bash leaves it.
        tool.assertFlow("tool chx", all.take(1), "tool checkx", cursor = 7)
    }

    @Test
    fun `a flag completes at the end of a word and inside one but not on no word`() {
        val raw = listOf(Suggestion("--raw", "print strings without quotes"))
        tool.assertFlow("tool check --ra", raw, "tool check --raw")
        tool.assertFlow("tool check --ra a.toml", raw, "tool check --raw a.toml", cursor = 15)
        tool.assertFlow("tool check --rax", raw, "tool check --rawx", cursor = 15)
        // On no word the slot is the argument's: a flag is offered once a dash is typed.
        tool.assertFlow("tool check ", files.map { Suggestion(it) }, "tool check file1.toml")
    }

    @Test
    fun `an option name completes at the end of a word and inside one`() {
        val file = listOf(Suggestion("--file", "the file to read"))
        tool.assertFlow("tool check --fi", file, "tool check --file")
        tool.assertFlow("tool check --fi a.toml", file, "tool check --file a.toml", cursor = 15)
        tool.assertFlow("tool check --fix", file, "tool check --filex", cursor = 15)
    }

    @Test
    fun `an option value in its own word completes on no word at the end of one and inside one`() {
        val all = files.map { Suggestion(it) }
        tool.assertFlow("tool check --file ", all, "tool check --file file1.toml")
        tool.assertFlow("tool check --file  a.toml", all, "tool check --file file1.toml a.toml", cursor = 18)
        tool.assertFlow("tool check --file file1", all.take(1), "tool check --file file1.toml")
        tool.assertFlow("tool check -f file1", all.take(1), "tool check -f file1.toml")
        tool.assertFlow("tool check --file fix", all, "tool check --file file1.tomlx", cursor = 20)
    }

    @Test
    fun `an option value attached to its option completes as the whole word with the option's description`() {
        // As `cp --backup=<TAB>` lists `--backup=existing` and the rest, each described by the option.
        val all = files.map { Suggestion("--file=$it", "the file to read") }
        tool.assertFlow("tool check --file=", all, "tool check --file=file1.toml")
        tool.assertFlow("tool check --file= a.toml", all, "tool check --file=file1.toml a.toml", cursor = 18)
        tool.assertFlow("tool check --file=file1", all.take(1), "tool check --file=file1.toml")
        val glued = listOf(Suggestion("-ffile1.toml", "the file to read"))
        tool.assertFlow("tool check -ffile1", glued, "tool check -ffile1.toml")
        val afterFlag = listOf(Suggestion("-rffile1.toml", "the file to read"))
        tool.assertFlow("tool check -rffile1", afterFlag, "tool check -rffile1.toml")
        val versions = listOf("1.0", "1.1").map { Suggestion("--toml=$it", "the TOML version") }
        tool.assertFlow("tool check --toml=", versions, "tool check --toml=1.0")
        tool.assertFlow("tool check --toml=1x", versions, "tool check --toml=1.0x", cursor = 19)
    }

    @Test
    fun `a file option leaves the path to the shell behind what is not part of it`() {
        tool.assertFlow("tool get --file ", listOf(filesAfter()), "tool get --file $COMPLETE_FILES")
        assertEquals(listOf(filesAfter("--file=")), tool.suggestionsFor("tool get --file=fi"))
        assertEquals(listOf(filesAfter("-f")), tool.suggestionsFor("tool get -ffi"))
    }

    @Test
    fun `an argument completes on no word and at the end of one among the words after the cursor`() {
        val all = files.map { Suggestion(it) }
        tool.assertFlow("tool check ", all, "tool check file1.toml")
        tool.assertFlow("tool check  file2.toml", all, "tool check file1.toml file2.toml", cursor = 11)
        tool.assertFlow("tool check file1", all.take(1), "tool check file1.toml")
        tool.assertFlow("tool check file1 file2.toml", all.take(1), "tool check file1.toml file2.toml", cursor = 16)
        tool.assertFlow("tool check --file a.toml fi", all, "tool check --file a.toml file1.toml")
    }

    @Test
    fun `tab takes the one suggestion or puts in what several share`() {
        assertEquals("tool check --raw", tool.tab("tool check --ra"))
        assertEquals("tool check --toml=1.", tool.tab("tool check --toml="))
        // Several that share nothing past what is typed leave the line as it is, for the shell to list them.
        assertEquals("tool ", tool.tab("tool "))
        assertEquals("rm -r", rm.tab("rm -r"))
        assertEquals("tool get --file=fi", tool.tab("tool get --file=fi"))
    }

    @Test
    fun `the cursor right after an equals sign completes the value from there`() {
        // As `cp --backup=|n<TAB>`: klap is handed the word up to the cursor, `--toml=`, so both versions fit, and
        // what follows the cursor stays. zsh, fish and PowerShell match the whole word instead, in the shell.
        val versions = listOf("1.0", "1.1").map { Suggestion("--toml=$it", "the TOML version") }
        assertEquals(versions, tool.suggestionsFor("tool check --toml=1.1", cursor = 18))
        assertEquals("tool check --toml=1.1.1", tool.tab("tool check --toml=1.1", cursor = 18))
    }

    @Test
    fun `suggestions keep the order they are declared or provided in`() {
        // The shell sorts a list as it does its own; an order that means something (log levels) survives in one
        // that does not.
        val levels = cli("tool") {
            option("--level").choice("debug", "info", "warn", "error")
            argument("file").completeWith { candidates(listOf("file10", "file2", "notes")) }
            action { Ok("") }
        }
        assertEquals(
            listOf("--level=debug", "--level=info", "--level=warn", "--level=error"),
            levels.suggestionsFor("tool --level=").map { it.value },
        )
        assertEquals(listOf("file10", "file2", "notes"), levels.suggestionsFor("tool ").map { it.value })
    }

    @Test
    fun `an argument reads an input typed after the cursor`() {
        val owner = listOf(Suggestion(".owner"))
        tool.assertFlow("tool get  -f conf.toml", owner, "tool get .owner -f conf.toml", cursor = 9)
        tool.assertFlow("tool get .ow -f conf.toml", owner, "tool get .owner -f conf.toml", cursor = 12)
        assertEquals(emptyList(), tool.suggestionsFor("tool get .ow"))
    }
}
