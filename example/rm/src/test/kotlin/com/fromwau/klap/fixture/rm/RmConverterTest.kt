package com.fromwau.klap.fixture.rm

import com.fromwau.klap.fixture.ParitySuite
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.io.CliPath
import kotlin.test.Test

/*
 * Every expected value is from GNU rm 9.12, run inside a throwaway directory holding the files a and b, a directory dd
 * holding a file and an empty directory ed, with `n` on standard input: whether a mode took is read off whether rm
 * asked before removing a, or asked once before removing four files. A refusal is rm's own parse error. rm's later
 * refusals of a parsed line (a file that does not exist, a directory without -r) are the file system's answer, so those
 * lines bind. rm has no number, path or free text option, so the X rows of numbers, doubles, booleans, enums, dates,
 * custom converters and validation (X1 to X3, X9 to X28, X44 to X49, X62 to X86) have no rm cell.
 */

/** One rm line and what rm makes of it: the record it binds, or null when rm refuses it. */
private class ConverterCell(
    val argv: List<String>,
    val expected: RmInputs?,
    val because: String,
    val differs: String? = null,
)

private fun bound(
    vararg argv: String,
    because: String = "exit 0",
    expected: RmInputs,
) = ConverterCell(argv.toList(), expected, because)

private fun refused(
    vararg argv: String,
    because: String,
    differs: String? = null,
) = ConverterCell(argv.toList(), null, because, differs)

private fun ParitySuite<RmInputs>.checkValues(cells: List<ConverterCell>) = checkCells(cells) { cell ->
    val argv = cell.argv.toTypedArray()
    val expected = cell.expected
    if (expected == null) {
        rejects(argv = argv, because = cell.because, differs = cell.differs)
    } else {
        binds(argv = argv, expected = expected, because = cell.because)
    }
}

private val A: RmInputs = NOTHING_BOUND.copy(files = listOf(CliPath("a")))

/** `--preserve-root=all` on `a`. */
private val ALL_ROOTS: RmInputs = A.copy(preserveRoot = PreserveRoot.All)

/** `--interactive=[typed]` on `a`, read as the mode [mode]. */
private fun mode(
    typed: String,
    mode: Prompt,
): ConverterCell {
    val asks = if (mode == Prompt.Never) "removes a without asking" else "asks before removing"
    return bound(
        "--interactive=$typed", "a",
        because = "`rm --interactive=$typed a` $asks",
        expected = A.promptingBy(mode),
    )
}

private fun invalid(typed: String): ConverterCell =
    refused("--interactive=$typed", "a", because = "rm: invalid argument '$typed' for '--interactive'")

/**
 * What the fixture makes of each typed rm value (X of the testing matrix): the modes of --interactive with every
 * prefix argmatch reads, the one word --preserve-root takes, the optional values left bare or given twice, and the
 * operands, bound verbatim.
 */
class RmConverterTest {

    private val parity = ParitySuite(rmCli())

    @Test
    fun `--interactive binds each documented mode and every prefix argmatch reads`() = parity.checkValues(
        // X4a, X4c, X29: `rm --interactive=bogus a` lists "'never', 'no', 'none' / 'once' / 'always', 'yes'"; a prefix
        // whose every match names one mode is that mode, so `n` is never though no and none start with it too.
        listOf(
            mode("never", Prompt.Never), mode("once", Prompt.Once), mode("always", Prompt.Always),
            mode("n", Prompt.Never), mode("ne", Prompt.Never), mode("nev", Prompt.Never), mode("neve", Prompt.Never),
            mode("o", Prompt.Once), mode("on", Prompt.Once), mode("onc", Prompt.Once),
            mode("a", Prompt.Always), mode("al", Prompt.Always), mode("alw", Prompt.Always),
            mode("alwa", Prompt.Always), mode("alway", Prompt.Always),
        ),
    )

    @Test
    fun `--interactive refuses a word argmatch does not read`() = parity.checkValues(
        // X4a, X30, X35, X37, X58, X59: rm matches a value in its own case, whole or as a prefix, and lists nothing.
        listOf("NEVER", "Never", "ALWAYS", "A", "bogus", " never", "never ", "never,once", "no,yes", "-x", "nev=")
            .map(::invalid) +
            refused("--interactive=", "a", because = "rm: ambiguous argument '' for '--interactive'"),
    )

    @Test
    fun `--interactive left bare is always and leaves the next word to the operands`() = parity.checkValues(
        // X5a, X42: `rm --interactive a` asks; `rm --interactive never a` asks for the files never and a; `rm
        // --interactive -- a` asks for a; `rm --interactive -` is "cannot remove '-'".
        listOf(
            bound("--interactive", "a", because = "asks", expected = A.promptingBy(Prompt.Always)),
            bound(
                "--interactive", "never", "a",
                because = "asks for never",
                expected = NOTHING_BOUND
                    .copy(files = listOf(CliPath("never"), CliPath("a")))
                    .promptingBy(Prompt.Always),
            ),
            bound("--interactive", "--", "a", because = "asks for a", expected = A.promptingBy(Prompt.Always)),
            bound(
                "--interactive", "-",
                because = "cannot remove '-'",
                expected = NOTHING_BOUND.copy(files = listOf(CliPath("-"))).promptingBy(Prompt.Always),
            ),
        ),
    )

    @Test
    fun `--interactive given twice binds the later`() = parity.checkValues(
        // X43, X56: `rm --interactive=never --interactive a` and `rm --interactive=never --interactive=always a` ask;
        // `rm --interactive --interactive=never a` removes a.
        listOf(
            bound(
                "--interactive=never", "--interactive", "a",
                because = "asks",
                expected = A.promptingBy(Prompt.Always),
            ),
            bound("--interactive=never", "--interactive=always", "a", expected = A.promptingBy(Prompt.Always)),
            bound("--interactive", "--interactive=never", "a", expected = A.promptingBy(Prompt.Never)),
            bound("--interactive=once", "--interactive=n", "a", expected = A.promptingBy(Prompt.Never)),
        ),
    )

    @Test
    fun `an invalid --interactive anywhere on the line refuses it`() = parity.checkValues(
        // X55: rm reads each --interactive as it meets it, so a bad one before or after a good one is refused.
        listOf(
            refused("--interactive=bogus", "--interactive=never", "a", because = "rm: invalid argument 'bogus'"),
            refused("--interactive=never", "--interactive=bogus", "a", because = "rm: invalid argument 'bogus'"),
        ),
    )

    @Test
    fun `--preserve-root refuses every value but all`() = parity.checkValues(
        // X4a, X30, X38: `rm --preserve-root=ALL a`, `=`, `=bogus`, `=all,all` and `=' all'` are "unrecognized
        // --preserve-root argument".
        listOf("ALL", "", "bogus", "all,all", " all").map { value ->
            refused(
                "--preserve-root=$value", "a",
                because = "rm: unrecognized --preserve-root argument: '$value'",
            )
        } + listOf(
            refused(
                "--preserve-root=bogus", "--preserve-root=all", "a",
                because = "rm: unrecognized --preserve-root argument: 'bogus'",
            ),
        ),
    )

    @Test
    fun `the operands bind verbatim`() = parity.checkValues(
        // X7b, X8, X53, X60: `rm a//`: "cannot remove 'a/': Not a directory"; `rm -r dd/` and `rm -d ed//` remove the
        // directory; `rm b a` removes both.
        listOf(
            bound("a//", because = "cannot remove 'a/'", expected = NOTHING_BOUND.copy(files = listOf(CliPath("a//")))),
            bound("-r", "dd/", expected = NOTHING_BOUND.copy(recursive = true, files = listOf(CliPath("dd/")))),
            bound("-d", "ed//", expected = NOTHING_BOUND.copy(dir = true, files = listOf(CliPath("ed//")))),
            bound("b", "a", expected = NOTHING_BOUND.copy(files = listOf(CliPath("b"), CliPath("a")))),
            bound(" a", because = "cannot remove ' a'", expected = NOTHING_BOUND.copy(files = listOf(CliPath(" a")))),
            bound("-f", because = "-f lets the list go empty", expected = NOTHING_BOUND.forcing()),
            refused(because = "rm: missing operand"),
        ),
    )

    @Test
    fun `--preserve-root binds all`() = parity.checkValues(
        // X4a, X5a: `rm --preserve-root=all a`, `rm --pre=all a` and `rm --preserve-root=all --preserve-root=all a`
        // remove a.
        listOf(
            bound("--preserve-root=all", "a", expected = ALL_ROOTS),
            bound("--pre=all", "a", expected = ALL_ROOTS),
            bound("--preserve-root=all", "--preserve-root=all", "a", expected = ALL_ROOTS),
        ),
    )

    @Test
    fun `an abbreviated --interactive reads its value`() = parity.checkValues(
        // X40: `rm --inter=al a` asks, `rm --i=never a` removes a, `rm --inter a` asks.
        listOf(
            bound("--inter=al", "a", because = "asks", expected = A.promptingBy(Prompt.Always)),
            bound("--i=never", "a", expected = A.promptingBy(Prompt.Never)),
            bound("--inter", "a", because = "asks", expected = A.promptingBy(Prompt.Always)),
        ),
    )

    @Test
    fun `--interactive binds no none and yes and their prefixes`() = parity.checkValues(
        // X4a, X32: `rm --interactive=no a`, `=none` and `=non` remove a without asking; `=yes`, `=ye` and `=y` ask.
        listOf(
            mode("no", Prompt.Never), mode("none", Prompt.Never), mode("non", Prompt.Never),
            mode("yes", Prompt.Always), mode("ye", Prompt.Always), mode("y", Prompt.Always),
        ),
    )

    @Test
    fun `--preserve-root refuses a prefix of all`() = parity.checkValues(
        // X4a, X30: `rm --preserve-root=al a` and `=a` are "unrecognized --preserve-root argument", and a is left.
        listOf("al", "a").map { value ->
            refused("--preserve-root=$value", "a", because = "rm: unrecognized --preserve-root argument: '$value'")
        },
    )
}
