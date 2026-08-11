package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class CpParityTest {

    private val parity = ParitySuite(cpCli())

    @Test
    fun `binds flags options and the two operand slots`() {
        parity.binds(
            "-R", "src", "backup",
            expected = NOTHING_BOUND.copy(recursive = true, sources = listOf("src"), dest = "backup"),
        )
        parity.binds(
            "-r", "src", "backup",
            expected = NOTHING_BOUND.copy(recursiveLowercase = true, sources = listOf("src"), dest = "backup"),
        )
        parity.binds(
            "-iv", "a.txt", "b.txt", "archive",
            expected = NOTHING_BOUND.copy(
                interactive = true,
                verbose = true,
                sources = listOf("a.txt", "b.txt"),
                dest = "archive",
            ),
        )
        parity.binds(
            "-t", "archive", "a.txt", "b.txt",
            expected = NOTHING_BOUND.copy(targetDirectory = "archive", sources = listOf("a.txt", "b.txt")),
        )
        parity.binds(
            "-T", "a", "b",
            expected = NOTHING_BOUND.copy(noTargetDirectory = true, sources = listOf("a"), dest = "b"),
        )
        // Six of cp's flags are short-only, so this cluster is the whole spelling each offers.
        parity.binds(
            "-dbuHpZ", "a", "b",
            expected = NOTHING_BOUND.copy(
                noDereferencePreserveLinks = true,
                backupSimple = true,
                updateOlder = true,
                dereferenceArgs = true,
                preserveDefaults = true,
                selinuxDefault = true,
                sources = listOf("a"),
                dest = "b",
            ),
        )
        parity.binds(
            "--update=older", "a", "b",
            expected = NOTHING_BOUND.copy(update = "older", sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "--backup=numbered", "-S", ".bak", "a", "b",
            expected = NOTHING_BOUND.copy(backup = "numbered", suffix = ".bak", sources = listOf("a"), dest = "b"),
        )
        // The attribute list is trimmed per element, so a spaced list binds the same names.
        parity.binds(
            "--preserve=mode,ownership, timestamps", "a", "b",
            expected = NOTHING_BOUND.copy(
                preserve = listOf("mode", "ownership", "timestamps"),
                sources = listOf("a"),
                dest = "b",
            ),
        )
        parity.binds(
            "--no-preserve=mode", "--sparse=always", "--reflink=auto", "a", "b",
            expected = NOTHING_BOUND.copy(
                noPreserve = listOf("mode"),
                sparse = "always",
                reflink = "auto",
                sources = listOf("a"),
                dest = "b",
            ),
        )
        // Bare form binds GNU's own documented default, and the operands still land correctly.
        parity.binds(
            "--update", "a", "b",
            expected = NOTHING_BOUND.copy(update = "older", sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "--backup", "a", "b",
            expected = NOTHING_BOUND.copy(backup = "existing", sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "--reflink", "a", "b",
            expected = NOTHING_BOUND.copy(reflink = "always", sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "--context=unconfined_u:object_r:user_home_t:s0", "a", "b",
            expected = NOTHING_BOUND.copy(
                context = "unconfined_u:object_r:user_home_t:s0",
                sources = listOf("a"),
                dest = "b",
            ),
        )
        parity.binds(
            "-L", "a", "b",
            expected = NOTHING_BOUND.copy(dereference = true, sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "-P", "a", "b",
            expected = NOTHING_BOUND.copy(dereference = false, sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "-LP", "a", "b",
            expected = NOTHING_BOUND.copy(dereference = false, sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "-PL", "a", "b",
            expected = NOTHING_BOUND.copy(dereference = true, sources = listOf("a"), dest = "b"),
        )
        // cp's own escape for a dash-led filename, and the only way one reaches the operand slots.
        parity.binds(
            "--", "-foo", "bar",
            expected = NOTHING_BOUND.copy(sources = listOf("-foo"), dest = "bar"),
        )
        // `--parent` reaches `--parents` (abbreviation) and no other spelling, matching real cp.
        parity.binds(
            "--parent", "a", "b",
            expected = NOTHING_BOUND.copy(parents = true, sources = listOf("a"), dest = "b"),
        )
        // real cp: `--update=old` reaches `older` (the only value starting with "old") through argmatch.
        parity.binds(
            "--update=old", "a", "b",
            expected = NOTHING_BOUND.copy(update = "older", sources = listOf("a"), dest = "b"),
        )
        // -i and -n write real cp's one prompt policy, so the later of the two decides it.
        parity.binds(
            "-n", "-i", "a", "b",
            expected = NOTHING_BOUND.copy(interactive = true, sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "-i", "-n", "a", "b",
            expected = NOTHING_BOUND.copy(noClobber = true, sources = listOf("a"), dest = "b"),
        )
        // -f and --update are outside that policy in real cp: both orders below prompt AND override
        // the destination mode, and both orders of -i against --update skip without prompting, so
        // neither may clear the other here either.
        parity.binds(
            "-f", "-i", "a", "b",
            expected = NOTHING_BOUND.copy(force = true, interactive = true, sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "-i", "-f", "a", "b",
            expected = NOTHING_BOUND.copy(force = true, interactive = true, sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "-i", "--update=none", "a", "b",
            expected = NOTHING_BOUND.copy(interactive = true, update = "none", sources = listOf("a"), dest = "b"),
        )
        parity.binds(
            "--update=none", "-i", "a", "b",
            expected = NOTHING_BOUND.copy(interactive = true, update = "none", sources = listOf("a"), dest = "b"),
        )
    }

    @Test
    fun `binds each of real cps three operand shapes`() {
        // `cp a b d` with d a directory: exit 0 (verified, coreutils 9.11).
        parity.binds("a", "b", "c", expected = NOTHING_BOUND.copy(sources = listOf("a", "b"), dest = "c"))
        // `cp -t d a`: exit 0 (verified). -t removes the DEST slot, so every operand is a source.
        parity.binds("-t", "d", "a", expected = NOTHING_BOUND.copy(targetDirectory = "d", sources = listOf("a")))

        parity.rejects("a", because = "real cp: missing destination file operand after 'a'")
        // The minimum is shape-dependent and both halves now hold: 2 operands normally, 1 after -t DIR.
        parity.rejects("-t", "d", because = "real cp: missing file operand")
    }

    @Test
    fun `rejects what real cp rejects`() {
        parity.rejects("--zzz", because = "real cp: unrecognized option '--zzz'")
        parity.rejects(because = "real cp: missing file operand")
        parity.rejects("-Q", "a", "b", because = "real cp: invalid option -- 'Q'")
        parity.rejects("--Q", "a", "b", because = "real cp: unrecognized option '--Q'")
        parity.rejects("-t", because = "real cp: option requires an argument -- 't'")
        parity.rejects("-S", because = "real cp: option requires an argument -- 'S'")
        parity.rejects("--target-directory", because = "real cp: option '--target-directory' requires an argument")
        parity.rejects("--no-preserve", because = "real cp: option '--no-preserve' requires an argument")
        parity.rejects("--update=bogus", "a", "b", because = "real cp: invalid argument 'bogus' for '--update'")
        parity.rejects("--backup=bogus", "a", "b", because = "real cp: invalid argument 'bogus' for 'backup type'")
        parity.rejects("--reflink=bogus", "a", "b", because = "real cp: invalid argument 'bogus' for '--reflink'")
        // --sparse is the one required-value member of this family, so the space form is a value here too.
        parity.rejects("--sparse", "a", "b", because = "real cp: invalid argument 'a' for '--sparse'")
        // real cp: 'n' matches both 'none' and 'none-fail', so argmatch calls it ambiguous.
        parity.rejects("--update=n", "a", "b", because = "real cp: ambiguous argument 'n' for '--update'")
    }

    @Test
    fun `known divergence from real cp`() {
        // `--preserve` and `--context` stayed value-required here while real cp's are optional: real cp
        // copies a to b with its default attribute set, swallowing `a` as the value and leaving the
        // operand slots a token short. The two declared slots at least make it loud.
        parity.rejects(
            "--preserve", "a", "b",
            because = "real cp: --preserve's value is optional, so this copies a to b",
        )
        parity.rejects(
            "--context", "a", "b",
            because = "real cp: --context's value is optional, so this copies a to b",
        )
    }

    @Test
    fun `klap accepts what real cp rejects`() {
        // klap can carry any number of spellings on one holder (`flag("recursive", "r", "R")`); this
        // fixture instead splits into two flags, and `--recursive-r` is the invented long form that costs.
        parity.bindsLoosely(
            "--recursive-r", "a", "b",
            because = "real cp: unrecognized option '--recursive-r'",
            expected = NOTHING_BOUND.copy(recursiveLowercase = true, sources = listOf("a"), dest = "b"),
        )

        // A variadic declares a minimum and no maximum, so -T's exactly-two rule has nowhere to live.
        parity.bindsLoosely(
            "-T", "a", "b", "c",
            because = "real cp: extra operand 'c'",
            expected = NOTHING_BOUND.copy(noTargetDirectory = true, sources = listOf("a", "b"), dest = "c"),
        )

        // --preserve's value is a bare `.map { }`, so nothing checks the attribute names klap collects.
        parity.bindsLoosely(
            "--preserve=bogus", "a", "b",
            because = "real cp: invalid argument 'bogus' for '--preserve'",
            expected = NOTHING_BOUND.copy(preserve = listOf("bogus"), sources = listOf("a"), dest = "b"),
        )

        // `builtins { }` can decline json/color/completion/docs to free those names; this fixture declines
        // none, so klap's own surface still claims tokens real cp rejects, and each is also a filename.
        parity.bindsLoosely(
            "--json", "a", "b",
            because = "real cp: unrecognized option '--json'",
            expected = NOTHING_BOUND.copy(sources = listOf("a"), dest = "b"),
        )
        parity.bindsLoosely(
            "--color=never", "a", "b",
            because = "real cp: unrecognized option '--color=never'",
            expected = NOTHING_BOUND.copy(sources = listOf("a"), dest = "b"),
        )
        parity.shortCircuits("--help-all", "a", "b", because = "real cp: unrecognized option '--help-all'")
        parity.shortCircuits("-h", "a", "b", because = "real cp: invalid option -- 'h'")
        parity.shortCircuits("--completion", "bash", "a", "b", because = "real cp: unrecognized option '--completion'")
        parity.shortCircuits("--docs", "markdown", "a", "b", because = "real cp: unrecognized option '--docs'")
        parity.shortCircuits("__complete", "a", because = "real cp: '__complete' is a source filename, not a node")
    }
}
