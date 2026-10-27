package com.fromwau.klap.fixture.pacman

import com.fromwau.klap.testing.Suggestion
import kotlin.test.Test

/**
 * How the words around the cursor change what Tab offers on a pacman line (L and S of the testing matrix): the words
 * after the cursor, and what the line already holds, from the operation given to repeats and targets. Every expectation
 * is what real pacman 7.1.0 accepts at the cursor.
 */
class PacmanLineContextTest {

    private val pacman = pacmanCli().cli

    @Test
    fun `words after the cursor that bear on nothing leave the answer as at the end of the line`() =
        pacman.checkContext(
            at("L2a", "pacman -Q --ar| -v", onQuery("--arch")),
            at("L2a", "pacman -Q --chec| --verbose", onQuery("--check")),
            // `pacman -Q --zzz pacman` is refused, but the word under the cursor is completed as on its own (L21).
            at("L21", "pacman -Q --ar| --zzz", onQuery("--arch")),
            at("L21", "pacman -Q --ar| --dbpath", onQuery("--arch")),
        )

    @Test
    fun `an option given after the cursor is still offered where pacman takes it again`() = pacman.checkContext(
        // `pacman -Q --noconfirm --confirm pacman` exits 0; the man page: "--confirm Cancels the effects of a
        // previous --noconfirm".
        at("L7", "pacman -Q --noc| --confirm", onQuery("--noconfirm")),
        at("L7", "pacman -Q --confi| --noconfirm", onQuery("--config"), onQuery("--confirm")),
        // `pacman -Q --dbpath=<dir>/a --dbpath=<dir>/afile` reports the second dbpath.
        at("L8", "pacman -Q --dbp| --dbpath /a", onQuery("--dbpath")),
        // `pacman -Q --check --check pacman` checks file properties (the -kk reading).
        at("L9", "pacman -Q --chec| --check", onQuery("--check")),
        at("L9", "pacman -Q --unreq| -tt", onQuery("--unrequired")),
        at("L10", "pacman -Q --cach| --cachedir /a", onQuery("--cachedir")),
        at("L10", "pacman -Q --hookd| --hookdir=/a", onQuery("--hookdir")),
    )

    @Test
    fun `an operation spelled after the end of options or as a value rules nothing out`() = pacman.checkContext(
        // `pacman -T -- -Q` prints `-Q` as an unsatisfied dependency: a target, not an operation.
        at("L14", "pacman --sy| -- -Q", plain("--sync"), plain("--sysupgrade"), plain("--sysroot")),
        // `pacman --arch -Q -Q pacman` exits 0: the first -Q is the architecture.
        at("L15", "pacman --sy| --arch -Q", plain("--sync"), plain("--sysupgrade"), plain("--sysroot")),
        at("S11", "pacman --arch -Q --que|", plain("--query")),
    )

    @Test
    fun `the rest of the word under the cursor is not sent`() = pacman.checkContext(
        at("L26", "pacman --qu|ery", plain("--query"), plain("--quiet")),
        at("L26", "pacman -Q --conf|irm", onQuery("--config"), onQuery("--confirm")),
    )

    @Test
    fun `an operation on the line keeps every other operation out`() {
        // `pacman -Qi -S pacman`, `pacman --query -Q pacman`, `pacman --que -T pacman`: "error: only one
        // operation may be used at a time". -S lines are read from `pacman -Sh` only.
        checkCells(
            listOf(
                "S6" to "pacman -Q -|" to Op.QUERY,
                "S6" to "pacman -Q --|" to Op.QUERY,
                "S6" to "pacman -Q --s|" to Op.QUERY,
                "S7" to "pacman -Qi -|" to Op.QUERY,
                "S7" to "pacman -Syu --|" to Op.SYNC,
                "S7" to "pacman -Syu --q|" to Op.SYNC,
                "S9" to "pacman --query -|" to Op.QUERY,
                "S9" to "pacman --que -|" to Op.QUERY,
                "S9" to "pacman --sync --|" to Op.SYNC,
            ),
        ) { (cell, op) -> pacman.assertOffersNone(cell.first, cell.second, otherOperations(op)) }
    }

    @Test
    fun `an input pacman takes again stays offered once given`() = pacman.checkContext(
        // `pacman -Q --verbose --verbose pacman` and `pacman -Q -v -v pacman` exit 0.
        at("S16", "pacman -Q --verbose --verb|", onQuery("--verbose")),
        at("S17", "pacman -Q --check --chec|", onQuery("--check")),
        at("S19", "pacman -Q --cachedir /a --cach|", onQuery("--cachedir")),
        at("S20", "pacman -Q --dbpath /a --dbp|", onQuery("--dbpath")),
        at("S22", "pacman -Q --noconfirm --confi|", onQuery("--config"), onQuery("--confirm")),
        at("S22", "pacman -Q --confirm --noc|", onQuery("--noconfirm")),
    )

    @Test
    fun `a counted letter given in an earlier word is offered again`() {
        // `pacman -Q --check --check pacman` is -Qkk.
        pacman.assertOffers("S17", "pacman -Qk -|", Suggestion("-k", Op.QUERY.entryFor('k')!!.help))
    }

    @Test
    fun `options after a target or a bare optional value still complete`() = pacman.checkContext(
        // `pacman -Q pacman --check` and `echo pacman | pacman -Q - --check` check pacman's files.
        at("S35", "pacman -Q pacman --chec|", onQuery("--check")),
        at("S32", "pacman -Q - --chec|", onQuery("--check")),
        // `pacman -Q --debug --check pacman` prints debug lines and checks.
        at("S46", "pacman -Q --debug --chec|", onQuery("--check")),
    )

    @Test
    fun `an option spelled after the end of options is a target and takes no value`() = pacman.checkContext(
        // `pacman -Q -- --dbpath pacman`: "error: package '--dbpath' was not found"; `pacman -Q -- --color=al`:
        // "error: package '--color=al' was not found".
        at("L4b", "pacman -Q -- --dbpath |"),
        at("L4b", "pacman -Q -- -b |"),
        at("L4b", "pacman -Q -- --color |"),
        at("L4b", "pacman -- --dbpath |"),
        at("L4c", "pacman -Q -- --color=al|"),
        at("L4c", "pacman -Q -- --dbpath=|"),
        at("L4c", "pacman -Q -- -b/tm|"),
    )

    @Test
    fun `an operation after the cursor keeps every other operation out`() {
        // `pacman -Qi -S pacman`, `pacman --que -T pacman`, `pacman -Q --syn`: "error: only one operation may be used
        // at a time". `--sy` is no cell: pacman finds it ambiguous (`--sync`, `--sysroot`, `--sysupgrade`).
        checkCells(
            listOf(
                Triple("L5", "pacman -| -S", Op.SYNC),
                Triple("L5", "pacman --| --sync", Op.SYNC),
                Triple("L5", "pacman -| -Syu", Op.SYNC),
                Triple("L5", "pacman -| -Q pkg", Op.QUERY),
                Triple("L22", "pacman -| --que", Op.QUERY),
                Triple("L22", "pacman --| --syn", Op.SYNC),
                Triple("L22", "pacman --sy| --que", Op.QUERY),
                Triple("L22", "pacman --que| --syn", Op.SYNC),
            ),
        ) { (rows, typed, op) -> pacman.assertOffersNone(rows, typed, otherOperations(op)) }
    }

    @Test
    fun `an operation given before or after the cursor is not offered again`() {
        // `pacman -Q -Q pacman`, `pacman --query -Q pacman`, `pacman -Q --query pacman`: "error: only one operation
        // may be used at a time".
        val again = listOf("-Q", "--query")
        checkCells(
            listOf(
                "L6" to "pacman -| -Q",
                "L6" to "pacman --| -Q",
                "L6" to "pacman --| --query",
                "S10" to "pacman -Q -|",
                "S10" to "pacman -Q --|",
                "S10" to "pacman --query -|",
                "S10" to "pacman -Qi --|",
            ),
        ) { (rows, typed) -> pacman.assertOffersNone(rows, typed, again) }
    }

    @Test
    fun `an end of options taken as a value leaves the options open`() = pacman.checkContext(
        // `pacman -Q --arch -- --check pacman` prints "pacman: 426 total files, 0 missing files": getopt took
        // `--` as the architecture, and --check stayed an option.
        at("S33", "pacman -Q --arch -- --chec|", onQuery("--check")),
        at("S33", "pacman -Q --arch -- --verb|", onQuery("--verbose")),
    )
}
