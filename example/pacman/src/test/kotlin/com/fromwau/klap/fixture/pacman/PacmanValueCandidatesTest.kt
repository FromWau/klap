package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.testing.filesAfter
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Where the candidates for a pacman value come from (V of the testing matrix): the option's own source wherever its
 * value slot is reached, through repeats, clusters and dash-led words, and the operand's (none) where no option took
 * the word. Every expectation is what real pacman 7.1.0 accepts at the cursor.
 */
class PacmanValueCandidatesTest {

    private val pacman = pacmanCli().cli

    @Test
    fun `every occurrence of a repeated path option completes through the shell`() = pacman.checkContext(
        // `pacman -Q --cachedir /tmp --cachedir /var/cache/pacman/pkg pacman` exits 0.
        at("V24 S19", "pacman -Q --cachedir /a --cachedir |", FILES),
        at("V24 V3b", "pacman -Q --cachedir=/a --cachedir=|", filesAfter("--cachedir=")),
        at("V24 L2b", "pacman -Q --hookdir /a --hookdir=/h| pkg", filesAfter("--hookdir=")),
        at("V24", "pacman --cachedir /a --cachedir /v|", FILES),
    )

    @Test
    fun `a value typed with dashes is completed by its option's source`() = pacman.checkContext(
        // `pacman -Q --arch --h pacman` and `pacman -Q --arch --config pacman` exit 0: the word is the value.
        at("V63 G82", "pacman -Q --arch --h|"),
        at("V63 G82", "pacman -Q --arch -|"),
        at("V63 G82", "pacman -Q --arch --dbpath=|"),
        at("V63 G82", "pacman -Q --config -|", FILES),
        at("V63 G82", "pacman -Q --config --h|", FILES),
        at("V63 G82", "pacman -Q --color -|"),
        at("V63 G82 V97", "pacman -Q -b -|", FILES),
    )

    @Test
    fun `a spaced value after a cluster ending in a path letter completes through the shell`() = pacman.checkContext(
        at("V97", "pacman -Qvb |", FILES),
        at("V97 L2b", "pacman -Qvb | pkg", FILES),
        at("V97 L2b", "pacman -Qb | pkg", FILES),
        at("V97", "pacman -Qvr /t|", FILES),
    )

    @Test
    fun `the word after an option taking a value is that value and not an option`() = pacman.checkContext(
        // `pacman -Q --arch --config pacman` exits 0, so `--config` is the architecture and `|` a target.
        at("V98", "pacman -Q --arch --config |"),
        at("V98", "pacman -Q --arch -b |"),
        at("V98 L2b", "pacman -Q --arch -b | pkg"),
    )

    @Test
    fun `an ambiguous or unknown option leaves the next word without candidates`() = pacman.checkContext(
        // `pacman -Q --co pacman`: "option '--co' is ambiguous; possibilities: '--confirm' '--config' '--color'";
        // `pacman -Q --conf pacman` the same for '--confirm' '--config'; `pacman -Q --zzz pacman`: unrecognized.
        at("V96 S45 G90", "pacman -Q --co |"),
        at("V96 S45 G90 L2b", "pacman -Q --co | pkg"),
        at("V96 S45 G90", "pacman -Q --conf |"),
        at("V96 G90", "pacman -Q --co=|"),
        at("V96 G90", "pacman -Q --conf=|"),
        at("S45", "pacman -Q --zzz |"),
    )

    @Test
    fun `the word after a built in pacman does not have is a target`() = pacman.checkContext(
        // `pacman -Q --completion bash`: "unrecognized option '--completion'", so no value slot follows it.
        at("V21 G68", "pacman -Q --completion |"),
        at("V21 G68", "pacman --completion |"),
        at("V21 G68", "pacman -Q --docs |"),
        at("V21 G68", "pacman -Q --completion b|"),
    )
}

/**
 * Where a pacman value's candidates come from (V of the testing matrix) where klap offers something other than what
 * real pacman 7.1.0 would accept. Each test states what real pacman 7.1.0 does and is ignored with `TDD target: <what
 * must change>`; removing the annotation shows it failing for that reason, and no other test pins today's behaviour, so
 * a fix breaks nothing else.
 */
class PacmanValueCandidatesTargetsTest {

    private val pacman = pacmanCli().cli

    @Ignore("TDD target: klap must match a choice value case-sensitively, as pacman's --color does")
    @Test
    fun `a color value in capitals is not offered`() =
        pacman.checkPacman(cells("value capitals", NO_OPERATION + ANY_OPERATION, CHOICE))
}
