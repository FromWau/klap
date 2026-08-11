package com.fromwau.klap.fixture.mv

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class MvParityTest {

    private val parity = ParitySuite(mvCli())

    @Test
    fun `binds options and the two operand slots`() {
        parity.binds(
            "old.txt", "new.txt",
            expected = NOTHING_BOUND.copy(sources = listOf("old.txt"), dest = "new.txt"),
        )
        parity.binds(
            "a.txt", "b.txt", "archive/",
            expected = NOTHING_BOUND.copy(sources = listOf("a.txt", "b.txt"), dest = "archive/"),
        )
        // -t removes the DEST slot entirely, so every operand on the line is a source.
        parity.binds(
            "-t", "archive/", "a.txt", "b.txt",
            expected = NOTHING_BOUND.copy(targetDirectory = "archive/", sources = listOf("a.txt", "b.txt")),
        )
        parity.binds(
            "--target-directory=archive/", "a.txt",
            expected = NOTHING_BOUND.copy(targetDirectory = "archive/", sources = listOf("a.txt")),
        )
        parity.binds(
            "-T", "src", "dest",
            expected = NOTHING_BOUND.copy(noTargetDirectory = true, sources = listOf("src"), dest = "dest"),
        )
        parity.binds(
            "-fv", "a", "b",
            expected = NOTHING_BOUND.copy(force = true, verbose = true, sources = listOf("a"), dest = "b"),
        )
        parity.binds("-n", "a", "b", expected = NOTHING_BOUND.copy(noClobber = true, sources = listOf("a"), dest = "b"))
        parity.binds(
            "--backup=numbered", "a", "b",
            expected = NOTHING_BOUND.copy(backup = "numbered", sources = listOf("a"), dest = "b"),
        )
        // Bare form binds GNU's own documented default, and the operands survive.
        parity.binds(
            "--backup", "a", "b",
            expected = NOTHING_BOUND.copy(backup = "existing", sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "--update", "a", "b",
            expected = NOTHING_BOUND.copy(update = "older", sources = listOf("a"), dest = "b"),
        )
        // -b is short-only, which is the whole spelling real mv offers for it.
        parity.binds(
            "-b", "a", "b",
            expected = NOTHING_BOUND.copy(backupSimple = true, sources = listOf("a"), dest = "b"),
        )
        parity.binds("-S", ".bak", "a", "b", expected = NOTHING_BOUND.copy(suffix = ".bak", sources = listOf("a"), dest = "b"))
        parity.binds(
            "--suffix=.bak", "a", "b",
            expected = NOTHING_BOUND.copy(suffix = ".bak", sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "--update=none", "a", "b",
            expected = NOTHING_BOUND.copy(update = "none", sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "-u", "a", "b",
            expected = NOTHING_BOUND.copy(updateOlder = true, sources = listOf("a"), dest = "b"),
        )
        parity.binds("-Z", "a", "b", expected = NOTHING_BOUND.copy(context = true, sources = listOf("a"), dest = "b"))
        parity.binds(
            "--debug", "--exchange", "--no-copy", "a", "b",
            expected = NOTHING_BOUND.copy(
                debug = true,
                exchange = true,
                noCopy = true,
                sources = listOf("a"),
                dest = "b",
            ),
        )
        parity.binds(
            "--strip-trailing-slashes", "a/", "b",
            expected = NOTHING_BOUND.copy(stripTrailingSlashes = true, sources = listOf("a/"), dest = "b"),
        )
        // `--strip` reaches that one spelling and no other, as it does for real mv.
        parity.binds(
            "--strip", "a", "b",
            expected = NOTHING_BOUND.copy(stripTrailingSlashes = true, sources = listOf("a"), dest = "b"),
        )
        // A dash-led filename needs the POSIX escape here exactly as it does in real mv.
        parity.binds("--", "-foo", "bar", expected = NOTHING_BOUND.copy(sources = listOf("-foo"), dest = "bar"))
        // real mv: `--backup=nu` makes b.~1~, the only value starting with "nu".
        parity.binds(
            "--backup=nu", "a", "b",
            expected = NOTHING_BOUND.copy(backup = "numbered", sources = listOf("a"), dest = "b"),
        )
    }

    @Test
    fun `binds each of real mvs three operand shapes`() {
        // `mv a b d` with d a directory: exit 0 (verified, coreutils 9.11).
        parity.binds("a", "b", "c", expected = NOTHING_BOUND.copy(sources = listOf("a", "b"), dest = "c"))
        // `mv -t d a`: exit 0 (verified). -t removes the DEST slot, so every operand is a source.
        parity.binds("-t", "d", "a", expected = NOTHING_BOUND.copy(targetDirectory = "d", sources = listOf("a")))

        parity.rejects("a", because = "real mv: missing destination file operand after 'a'")
        // The minimum is shape-dependent and both halves now hold: 2 operands normally, 1 after -t DIR.
        parity.rejects("-t", "d", because = "real mv: missing file operand")
    }

    @Test
    fun `rejects what real mv rejects`() {
        parity.rejects("--zzz", because = "real mv: unrecognized option '--zzz'")
        parity.rejects("-Q", "a", "b", because = "real mv: invalid option -- 'Q'")
        parity.rejects(because = "real mv: missing file operand")
        parity.rejects("-S", because = "real mv: option requires an argument -- 'S'")
        parity.rejects("--target-directory", because = "real mv: option '--target-directory' requires an argument")
        parity.rejects("--update=zzz", "a", "b", because = "real mv: invalid argument 'zzz' for '--update'")
        parity.rejects("--force=yes", "a", "b", because = "real mv: option '--force' doesn't allow an argument")
        // real mv: 'n' matches 'none', 'numbered', 'nil' and 'never', so argmatch calls it ambiguous.
        parity.rejects("--backup=n", "a", "b", because = "real mv: ambiguous argument 'n' for 'backup type'")
    }

    @Test
    fun `known divergence from real mv`() {
        // A variadic declares a minimum and no maximum, so -T's exactly-two rule has nowhere to live
        // in the declaration and only the action can refuse the third operand.
        parity.bindsLoosely(
            "-T", "a", "b", "c",
            because = "real mv: extra operand 'c'",
            expected = NOTHING_BOUND.copy(noTargetDirectory = true, sources = listOf("a", "b"), dest = "c"),
        )

        // Real mv lets the LAST of -i/-f/-n take effect (`lastWins`), so these two lines behave
        // differently: the first overwrites silently, the second prompts.
        parity.binds("-i", "-f", "a", "b", expected = NOTHING_BOUND.copy(force = true, sources = listOf("a"), dest = "b"))
        parity.binds(
            "-f", "-i", "a", "b",
            expected = NOTHING_BOUND.copy(interactive = true, sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "-fn", "a", "b",
            expected = NOTHING_BOUND.copy(noClobber = true, sources = listOf("a"), dest = "b"),
        )

        // klap's injected built-ins reach a tool that has neither. Real mv answers `mv -h a b` with
        // "invalid option -- 'h'", and treats `__complete` as an ordinary filename.
        parity.shortCircuits("-h", "a", "b", because = "klap's built-in -h is not one of mv's own options")
        parity.shortCircuits("__complete", because = "klap's hidden __complete subcommand shadows a filename")
    }
}
