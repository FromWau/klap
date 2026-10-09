package com.fromwau.klap.fixture.cp

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

/**
 * Whole cp lines beyond one input's own spellings: combinations, overrides, abbreviations, help and version, and
 * what cp refuses while parsing. Every `because` quotes real cp 9.12, run inside a throwaway directory.
 */
class CpParityTest {

    private val parity = ParitySuite(cpCli())

    @Test
    fun `binds a line combining several inputs`() {
        parity.binds(
            "-iv", "a.txt", "b.txt", "archive",
            expected = NOTHING_BOUND.copy(
                interactive = true,
                verbose = true,
                sources = listOf("a.txt", "b.txt"),
                dest = "archive",
            ),
        )
        // Five of cp's flags are short-only, so this cluster is the whole spelling each offers.
        parity.binds(
            "-dbHpZ", "a", "b",
            expected = A_TO_B.copy(
                noDereferencePreserveLinks = true,
                backupSimple = true,
                dereferenceArgs = true,
                preserveDefaults = true,
                selinuxDefault = true,
            ),
        )
        parity.binds(
            "--backup=numbered", "-S", ".bak", "a", "b",
            expected = A_TO_B.copy(backup = "numbered", suffix = ".bak"),
        )
        parity.binds("-vbS.bak", "a", "b", expected = A_TO_B.copy(verbose = true, backupSimple = true, suffix = ".bak"))
        parity.binds(
            "--no-preserve=mode", "--sparse=always", "--reflink=auto", "a", "b",
            expected = A_TO_B.copy(noPreserve = listOf("mode"), sparse = "always", reflink = "auto"),
        )
        parity.binds(
            "--context=unconfined_u:object_r:user_home_t:s0", "a", "b",
            expected = A_TO_B.copy(context = "unconfined_u:object_r:user_home_t:s0"),
        )
    }

    @Test
    fun `the later of -i and -n decides the prompt`() {
        // `echo n | cp -n -i src dst` prompts; `cp -i -n src dst` skips silently.
        parity.binds("-n", "-i", "a", "b", expected = A_TO_B.copy(interactive = true))
        parity.binds("-i", "-n", "a", "b", expected = A_TO_B.copy(noClobber = true))
        parity.binds("-n", "-i", "-f", "a", "b", expected = A_TO_B.copy(interactive = true, force = true))
    }

    @Test
    fun `-f and --update apply alongside -i and -n in either order`() {
        // `cp -f -i` and `cp -i -f` both prompt; `cp -i --update=none` skips without prompting; `cp -n -f` and
        // `cp -n --update=all` both leave the destination alone, so neither setting clears the other.
        parity.binds("-f", "-i", "a", "b", expected = A_TO_B.copy(force = true, interactive = true))
        parity.binds("-i", "-f", "a", "b", expected = A_TO_B.copy(force = true, interactive = true))
        parity.binds("-i", "--update=none", "a", "b", expected = A_TO_B.copy(interactive = true, update = "none"))
        parity.binds("--update=none", "-i", "a", "b", expected = A_TO_B.copy(interactive = true, update = "none"))
        parity.binds("-n", "-f", "a", "b", expected = A_TO_B.copy(noClobber = true, force = true))
        parity.binds("-n", "--update=all", "a", "b", expected = A_TO_B.copy(noClobber = true, update = "all"))
    }

    @Test
    fun `the last of -L -P and -H decides how links are followed`() {
        // From `info coreutils 'cp invocation'`: "If more than one of these options is specified, the last one
        // silently overrides the others." Not run: telling -H from -L apart needs a recursive copy of links.
        parity.binds("-LP", "a", "b", expected = A_TO_B.copy(dereference = false))
        parity.binds("-PL", "a", "b", expected = A_TO_B.copy(dereference = true))
        parity.binds("-H", "-L", "a", "b", expected = A_TO_B.copy(dereference = true))
        parity.binds("-L", "-H", "a", "b", expected = A_TO_B.copy(dereferenceArgs = true))
        parity.binds("-H", "-P", "a", "b", expected = A_TO_B)
        parity.binds("-P", "-H", "a", "b", expected = A_TO_B.copy(dereferenceArgs = true))
        parity.binds("--dereference", "--no-dereference", "a", "b", expected = A_TO_B)
        parity.binds("-H", "--dereference", "a", "b", expected = A_TO_B.copy(dereference = true))
    }

    @Test
    fun `binds the unique abbreviation of a long name and of a choice`() {
        // `cp --upd src dst`, `cp --pres src dst`, `cp --no-p=mode src dst` and `cp --tar=dir src` all exit 0.
        parity.binds("--parent", "a", "b", expected = A_TO_B.copy(parents = true))
        parity.binds("--verb", "a", "b", expected = A_TO_B.copy(verbose = true))
        parity.binds("--no-p=mode", "a", "b", expected = A_TO_B.copy(noPreserve = listOf("mode")))
        parity.binds("--no-d", "a", "b", expected = A_TO_B)
        parity.binds("--tar=d", "a", expected = NOTHING_BOUND.copy(targetDirectory = "d", sources = listOf("a")))
        parity.binds("--upd=old", "a", "b", expected = A_TO_B.copy(update = "older"))
        parity.binds("--update=none-", "a", "b", expected = A_TO_B.copy(update = "none-fail"))
    }

    @Test
    fun `rejects an ambiguous abbreviation as getopt does`() {
        val ambiguous = mapOf(
            "--a" to "'--archive' '--attributes-only'",
            "--c" to "'--copy-contents' '--context'",
            "--co" to "'--copy-contents' '--context'",
            "--d" to "'--debug' '--dereference'",
            "--p" to "'--parents' '--preserve'",
            "--r" to "'--recursive' '--remove-destination' '--reflink'",
            "--s" to "'--sparse' '--strip-trailing-slashes' '--suffix' '--symbolic-link'",
            "--n" to "the four --no-* options",
            "--no-" to "the four --no-* options",
            "--v" to "'--verbose' '--version'",
            "--ver" to "'--verbose' '--version'",
        )
        for ((prefix, candidates) in ambiguous) {
            val because = "real cp: option '$prefix' is ambiguous; possibilities: $candidates"
            parity.rejects(prefix, "a", "b", because = because)
        }
    }

    @Test
    fun `rejects what real cp rejects`() {
        parity.rejects("--zzz", because = "real cp: unrecognized option '--zzz'")
        parity.rejects("--x", "a", "b", because = "real cp: unrecognized option '--x'")
        parity.rejects("-Q", "a", "b", because = "real cp: invalid option -- 'Q'")
        parity.rejects("-c", "a", "b", because = "real cp: invalid option -- 'c'")
        parity.rejects("--Q", "a", "b", because = "real cp: unrecognized option '--Q'")
        // --sparse is the one required-value choice, so the spaced form takes the next word as its value.
        parity.rejects("--sparse", "a", "b", because = "real cp: ambiguous argument 'a' for '--sparse'")
        parity.rejects("--update=n", "a", "b", because = "real cp: ambiguous argument 'n' for '--update'")
        parity.rejects("--suffix", "a", "b", because = "real cp: --suffix takes a, leaving b with no destination")
    }

    @Test
    fun `help and version print as cp's do`() {
        parity.showsHelp("--help", because = "cp --help prints its usage")
        parity.showsHelp("--help", "a", "b", because = "cp --help a b prints its usage")
        parity.showsVersion("--version", because = "cp --version prints its banner")
        parity.showsVersion("--vers", because = "cp --vers prints its banner")
        parity.showsVersion("-v", "--version", "a", "b", because = "cp -v --version a b prints its banner")
    }
}
