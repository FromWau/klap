package com.fromwau.klap.fixture.head

import com.fromwau.kern.result.Ok
import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test
import kotlin.test.assertEquals

class HeadParityTest {

    private val head = headCli()

    private val parity = ParitySuite(head)

    @Test
    fun `binds counts flags and the optional operand list`() {
        parity.binds("-n", "20", "notes.txt", expected = NOTHING_BOUND.copy(lines = "20", files = listOf("notes.txt")))
        parity.binds("--lines=20", "notes.txt", expected = NOTHING_BOUND.copy(lines = "20", files = listOf("notes.txt")))
        parity.binds("-c", "512", "image.bin", expected = NOTHING_BOUND.copy(bytes = "512", files = listOf("image.bin")))
        // A multiplier suffix is part of NUM, so the value stays a string rather than an Int.
        parity.binds("-n", "1K", "f", expected = NOTHING_BOUND.copy(lines = "1K", files = listOf("f")))
        // A dash-led value reaches the option slot in every spelling.
        parity.binds("-n", "-5", "f", expected = NOTHING_BOUND.copy(lines = "-5", files = listOf("f")))
        parity.binds("--lines", "-5", "f", expected = NOTHING_BOUND.copy(lines = "-5", files = listOf("f")))
        parity.binds("-c", "-5", "f", expected = NOTHING_BOUND.copy(bytes = "-5", files = listOf("f")))
        // All three spellings are the one input real head documents.
        parity.binds("-q", "a", "b", expected = NOTHING_BOUND.copy(quiet = true, files = listOf("a", "b")))
        parity.binds("--quiet", "f", expected = NOTHING_BOUND.copy(quiet = true, files = listOf("f")))
        parity.binds("--silent", "f", expected = NOTHING_BOUND.copy(quiet = true, files = listOf("f")))
        parity.binds(
            "-qv", "a.txt", "b.txt",
            expected = NOTHING_BOUND.copy(quiet = true, verbose = true, files = listOf("a.txt", "b.txt")),
        )
        parity.binds("-z", "f", expected = NOTHING_BOUND.copy(zeroTerminated = true, files = listOf("f")))
        // A lone dash is a value, not an option: head's own spelling for standard input.
        parity.binds("-", expected = NOTHING_BOUND.copy(files = listOf("-")))
        // `head` with no operand reads stdin, so the operand list is genuinely zero-or-more.
        parity.binds(expected = NOTHING_BOUND)
        // `--li` reaches `--lines` and no other spelling, as it does for real head.
        parity.binds("--li", "3", "f", expected = NOTHING_BOUND.copy(lines = "3", files = listOf("f")))
        // The LAST of -c/-n decides the unit in real head, so these two lines print different things
        // (three lines, then five bytes) rather than binding both identically. Asserting the whole record
        // is what makes the loser's absence part of the claim rather than a separate assertion.
        parity.binds("-c", "5", "-n", "3", "f", expected = NOTHING_BOUND.copy(lines = "3", files = listOf("f")))
        parity.binds("-n", "3", "-c", "5", "f", expected = NOTHING_BOUND.copy(bytes = "5", files = listOf("f")))
        // The obsolete `-NUM` spelling carries a position like any other, so it loses to a later `-c`
        // exactly as `-n` does: real head prints five bytes for this line.
        parity.binds("-3", "-c", "5", "f", expected = NOTHING_BOUND.copy(bytes = "5", files = listOf("f")))
    }

    @Test
    fun `rejects what real head rejects`() {
        parity.rejects("--zzz", because = "real head: unrecognized option '--zzz'")
        parity.rejects("-Q", "f", because = "real head: invalid option -- 'Q'")
        parity.rejects("-n", because = "real head: option requires an argument -- 'n'")
        parity.rejects("--lines", because = "real head: option '--lines' requires an argument")
        parity.rejects("-n", "abc", "f", because = "real head: invalid number of lines: 'abc'")
        parity.rejects("--bytes=abc", "f", because = "real head: invalid number of bytes: 'abc'")
        // The numeric alias claims an all-digit token only, so this falls through to the short cluster.
        parity.rejects("-5x", "f", because = "real head: invalid trailing option -- x")
        // klap's --version is injected, head's --verbose is declared, and the two share this prefix: the
        // scan that answers --version runs before the walk, so it has to see the declared long as well or
        // the built-in would quietly win a spelling the tool itself calls ambiguous.
        parity.rejects(
            "--ver", "f",
            because = "real head: option '--ver' is ambiguous; possibilities: '--verbose' '--version'",
        )
    }

    @Test
    fun `the help abbreviations real head accepts reach help here too`() {
        // `--help` is the only long real head has starting with `h`, so it answers all three of these with
        // its help text. klap injects `--help-all` into every tree on top of that, and were the injected
        // name to take part in prefix resolution, all three would turn ambiguous on a tool whose author
        // never asked for it. It matches its full spelling only, so head keeps the abbreviations.
        parity.showsHelp("--h", "f", because = "real head: --h is an unambiguous abbreviation of --help")
        parity.showsHelp("--he", "f", because = "real head: --he is an unambiguous abbreviation of --help")
        parity.showsHelp("--hel", "f", because = "real head: --hel is an unambiguous abbreviation of --help")
    }

    @Test
    fun `a built in binds nothing because no command ran`() {
        // Help answers before any command is reached, so there is no binding to project. Distinct from a
        // rejection, which comes back as `Result.Error`.
        assertEquals(Ok(null), head.parse(listOf("--help")))
    }

    @Test
    fun `known divergence from real head`() {
        // `head -5 f` is the obsolete spelling of `head -n 5 f`, which real head accepts; `numericAlias`
        // binds it through `lines`, for any N.
        parity.binds("-5", "f", expected = NOTHING_BOUND.copy(lines = "5", files = listOf("f")))
        parity.binds("-100", expected = NOTHING_BOUND.copy(lines = "100"))

        // Real head honours the obsolete form only as the FIRST argument: anywhere else it answers
        // "invalid trailing option -- 3". klap's numericAlias claims `-NUM` wherever it appears, and the
        // occurrence carries its position, so a later `-3` takes the unit back off an earlier `-c`. klap
        // is LOOSER than the real tool here, which is the direction that makes a fixture lie about the
        // tool it models, so it is called out as such rather than left to read as parity.
        parity.bindsLoosely(
            "-c", "5", "-3", "f",
            because = "real head: invalid trailing option -- 3",
            expected = NOTHING_BOUND.copy(lines = "3", files = listOf("f")),
        )

        // The same looseness costs a real filename. `head -5 -1` on a file named `-1` answers "invalid
        // trailing option -- 1" in real head, so the file is reachable only as `-- -1` or `./-1`. Here the
        // alias takes the token as a count instead, leaving NO operand, so the line silently reads stdin.
        parity.bindsLoosely(
            "-5", "-1",
            because = "real head: invalid trailing option -- 1",
            expected = NOTHING_BOUND.copy(lines = "1"),
        )
        // Both spellings real head documents for that file work here too, which is what keeps it reachable.
        parity.binds("-5", "--", "-1", expected = NOTHING_BOUND.copy(lines = "5", files = listOf("-1")))
        parity.binds("-n5", "./-1", expected = NOTHING_BOUND.copy(lines = "5", files = listOf("./-1")))

        // klap's injected built-ins reach a tool that has neither. Real head answers `head -h f` with
        // "invalid option -- 'h'", and treats `__complete` as an ordinary filename.
        parity.shortCircuits("-h", "f", because = "klap's built-in -h outranks head's own option letters")
        parity.shortCircuits("__complete", because = "klap's hidden __complete subcommand shadows a filename")
    }
}
