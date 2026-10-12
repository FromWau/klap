package com.fromwau.klap.fixture.chmod

import com.fromwau.klap.fixture.chmod.ChmodTab.FILES
import com.fromwau.klap.fixture.chmod.ChmodTab.at
import com.fromwau.klap.fixture.chmod.ChmodTab.checkTab
import com.fromwau.klap.fixture.chmod.ChmodTab.modes
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * Every expectation is what real chmod 9.12 accepts at the cursor. A cell names its V rows (and the K, L or G rows it
 * meets) and is typed with `|` at the cursor. The mode candidates are the fixture's own list; each is a mode real
 * chmod accepts (`chmod 644 f` to `chmod go-rwx f` all exit 0), and the list is filtered by what is typed.
 */

private val NOTHING: List<Suggestion> = emptyList()

/**
 * Where the candidates for a chmod value come from (V of the testing matrix): the fixture's mode provider on the MODE
 * operand, the shell's file completion for `--reference` and every FILE, and nothing where chmod would read no value.
 */
class ChmodValueCandidatesTest {

    private val chmod = chmodCli().cli

    @Test
    fun `the mode provider offers the candidates that start with what is typed`() = chmod.checkTab(
        // V4a, V4d, V14, V47, G23: a spaced provider value carries no description.
        at("V4a V4d", "chmod |", modes()),
        at("V4a", "chmod 6|", modes("6")),
        at("V4a V14", "chmod 7|", modes("7")),
        at("V4a E17", "chmod 755|", modes("755")),
        at("V4a", "chmod go|", modes("go")),
        at("V4a", "chmod a-|", modes("a-")),
        at("V4a L2", "chmod u| f", modes("u")),
        at("V4a", "chmod 9|", NOTHING),
    )

    @Test
    fun `the mode provider matches what is typed in its own case`() = chmod.checkTab(
        // V48, G13, G77: `chmod U+x f` and `chmod A-w f`: "invalid mode", so a capital offers no candidate.
        at("V48 G13", "chmod U|", NOTHING),
        at("V48 G13", "chmod GO|", NOTHING),
    )

    @Test
    fun `the mode provider answers in every column of the MODE slot`() = chmod.checkTab(
        // V59, V81: inside a word only the part before the cursor is matched; at the start of a word nothing is.
        at("V59 inside", "chmod 7|55", modes("7")),
        at("V59 V81 start", "chmod |755", modes()),
        at("V59 words after", "chmod | f g", modes()),
        at("V59 after a flag", "chmod -R |", modes()),
        at("V59 after the end of options", "chmod -- |", modes()),
    )

    @Test
    fun `a dash-led mode word gets no candidate as no candidate starts with a dash`() = chmod.checkTab(
        // V61, V62, G83: `chmod -w f` and `chmod -- -w f` make f 444; the provider has no dash-led candidate, so the
        // dash word offers nothing whether the names or the provider answer it.
        at("V62", "chmod -w|", NOTHING),
        at("V61", "chmod -- -|", NOTHING),
        at("V61", "chmod -- -w|", NOTHING),
    )

    @Test
    fun `--reference completes its file through the shell in every form`() = chmod.checkTab(
        // V3a, V3b, V3c, V27, V95: chmod reads RFILE as any file (`chmod --reference=r f` gives f r's mode 600).
        at("V3a", "chmod --reference |", FILES),
        at("V3a", "chmod --reference r|", FILES),
        at("V3b", "chmod --reference=|", filesAfter("--reference=")),
        at("V3b", "chmod --reference=r|", filesAfter("--reference=")),
        at("V3c V95", "chmod --ref=|", filesAfter("--ref=")),
        at("V3c V95", "chmod --ref=r|", filesAfter("--ref=")),
        at("V95", "chmod --ref |", FILES),
        at("V3a L2", "chmod --reference | f", FILES),
    )

    @Test
    fun `a value word spelled with dashes is still --reference's file`() = chmod.checkTab(
        // V63, V98: `chmod --reference --verbose f`: "failed to get attributes of '--verbose'", so the word was RFILE
        // and the next word a FILE.
        at("V63", "chmod --reference --h|", FILES),
        at("V63", "chmod --reference -|", FILES),
        at("V98", "chmod --reference --verbose |", FILES),
    )

    @Test
    fun `every FILE completes through the shell however many come before it`() = chmod.checkTab(
        // V24, V9: `chmod u+x f g` changes both; a file already named is the shell's to list again.
        at("V24", "chmod u+x |", FILES),
        at("V24", "chmod u+x f |", FILES),
        at("V24", "chmod u+x f f |", FILES),
        at("V24", "chmod u+x f | g", FILES),
        at("V24 V71", "chmod --reference=r f |", FILES),
    )

    @Test
    fun `nothing is offered where chmod reads no value`() = chmod.checkTab(
        // V96: `chmod --re r f`: "option '--re' is ambiguous", so the word after it is no option's value.
        at("V96", "chmod --re |", NOTHING),
        at("V96", "chmod --re=|", NOTHING),
    )

    @Test
    fun `a second Tab press on the same line offers the same`() {
        // E24: the provider is a fixed list, so nothing on the line changes between two presses.
        for (line in listOf("chmod ", "chmod 7", "chmod --reference=", "chmod u+x ")) {
            assertEquals(chmod.suggestionsFor(line), chmod.suggestionsFor(line), "E24 `$line|` asked twice")
        }
    }
}
