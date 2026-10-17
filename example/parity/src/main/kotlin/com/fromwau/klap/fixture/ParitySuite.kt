package com.fromwau.klap.fixture

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.kern.terminal.Terminal
import com.fromwau.klap.Invocation
import com.fromwau.klap.TypedCli
import com.fromwau.klap.parse
import com.fromwau.klap.run
import kotlin.test.fail

/**
 * One argv line and what klap must do with it, for a CLI that recreates a real tool's surface.
 *
 * The installed binary is the oracle for [binds] versus [rejects] only — it can say whether an invocation
 * is valid, never what it bound internally, so a [binds] expectation is hand-authored and reviewed. Never
 * compare exit codes with it either: on a bad option `rm` exits 1, `ls` 2, `tar` 64 and `git` 129, so
 * the only portable claim is the binary one this class makes.
 *
 * Takes a [TypedCli] rather than a `Cli`, so a bound invocation arrives as the fixture's own projected
 * type. [binds] can then assert a whole invocation at once, which makes every field a case does *not*
 * mention part of the claim rather than silently unasserted.
 */
public class ParitySuite<T>(private val typed: TypedCli<T>) {

    /**
     * [argv] parses, dispatches to a command, and binds exactly [expected]. The action never runs, so a
     * fixture may recreate a destructive tool without owning an implementation of it.
     *
     * Prefer this over [accepts]: comparing the whole projection pins the inputs the line does *not*
     * supply as well as the ones it does, which is how an override rule like `lastWins` gets tested for
     * the loser's absence rather than only the winner's presence.
     */
    public fun binds(vararg argv: String, expected: T, because: String? = null) {
        val line = argv.toList()
        val note = because?.let { " ($it)" }.orEmpty()
        when (val projected = typed.parse(line)) {
            is Result.Error -> fail("${expected(line)} to bind$note, but it was rejected: ${projected.error}")
            is Result.Success -> {
                val actual = projected.value ?: fail("${expected(line)} to bind$note, but ${outcome(line)}")
                if (actual != expected) {
                    fail("${expected(line)} to bind$note\n  expected: $expected\n  actual:   $actual")
                }
            }
        }
    }

    /**
     * [argv] parses and dispatches to a command, and [assert] inspects what it bound.
     *
     * The narrow half of [binds], for a case whose claim is about one field rather than the whole record,
     * or whose projected type is a variant the case has to narrow to first.
     */
    public fun accepts(vararg argv: String, because: String? = null, assert: (T) -> Unit = {}) {
        val line = argv.toList()
        val note = because?.let { " ($it)" }.orEmpty()
        when (val projected = typed.parse(line)) {
            is Result.Error -> fail("${expected(line)} to bind$note, but it was rejected: ${projected.error}")
            is Result.Success ->
                assert(projected.value ?: fail("${expected(line)} to bind$note, but ${outcome(line)}"))
        }
    }

    /**
     * [argv] does not parse. [because] names the real tool's own answer to this line, or — when klap
     * diverges — the gap that makes klap stricter, so a reader can tell a pinned real-tool behaviour from
     * a pinned klap limitation without leaving the file.
     *
     * [differs] names how klap's refusal differs from the real tool's, where both refuse for different reasons.
     * The test still passes, and the run prints the note, so the gap shows in every build instead of hiding
     * behind a green refusal.
     */
    public fun rejects(vararg argv: String, because: String, differs: String? = null) {
        val line = argv.toList()
        if (typed.parse(line) is Result.Success) {
            fail("${expected(line)} to be rejected ($because), but ${outcome(line)}")
        }
        if (differs != null) {
            println("klap differs on `${command(line)}`: $differs\n  klap: ${outcome(line)}\n  real: $because")
        }
    }

    /**
     * klap binds [argv] to [expected], but the real tool rejects it — the opposite direction from a
     * [rejects] whose [because] names a gap. Usually invented surface: a spelling the fixture had to
     * declare to express something, which the real tool has never accepted.
     *
     * Worth its own name rather than a plain [binds], because the two directions need opposite treatment.
     * klap being stricter is a missing feature; klap being looser is a fixture that lies about the tool it
     * models, and it stays safe to hand to the real binary precisely because the real binary rejects it.
     */
    public fun bindsLoosely(vararg argv: String, because: String, expected: T) {
        binds(argv = argv, expected = expected, because = because)
    }

    /** The [accepts]-shaped half of [bindsLoosely], for a case asserting one field rather than the record. */
    public fun acceptsLoosely(vararg argv: String, because: String, assert: (T) -> Unit = {}) {
        accepts(argv = argv, because = because, assert = assert)
    }

    /**
     * [argv] resolves to help for the node it names, rather than to a command, an error, or any other
     * built-in. The narrow half of [shortCircuits], for the lines where WHICH built-in answered is the
     * whole claim.
     */
    public fun showsHelp(vararg argv: String, because: String) {
        val line = argv.toList()
        when (val parsed = typed.cli.parse(line)) {
            is Result.Error ->
                fail("${expected(line)} to print help ($because), but it was rejected: ${parsed.error}")
            is Result.Success -> if (parsed.value !is Invocation.ShowHelp) {
                fail("${expected(line)} to print help ($because), but ${outcome(line)}")
            }
        }
    }

    /**
     * [argv] resolves to help, and the page printed is returned, for a case whose claim is about WHICH page
     * the real tool shows (`pacman -Qh` prints the -Q page only).
     */
    public fun helpPage(vararg argv: String, because: String): String {
        showsHelp(argv = argv, because = because)
        val terminal = CapturingTerminal()
        typed.cli.run(argv.toList(), terminal)
        return terminal.out.toString()
    }

    /**
     * [argv] resolves to help, and the qualified name of the command whose page it shows is returned (`git remote
     * add`), for a claim about which command's help a request reaches from where it stands on the line.
     */
    public fun helpFor(vararg argv: String, because: String): String {
        val line = argv.toList()
        return when (val parsed = typed.cli.parse(line)) {
            is Result.Error ->
                fail("${expected(line)} to print help ($because), but it was rejected: ${parsed.error}")
            is Result.Success -> (parsed.value as? Invocation.ShowHelp)?.qualifiedName
                ?: fail("${expected(line)} to print help ($because), but ${outcome(line)}")
        }
    }

    /** [argv] resolves to the version, rather than to a command, an error, or any other built-in. */
    public fun showsVersion(vararg argv: String, because: String) {
        val line = argv.toList()
        when (val parsed = typed.cli.parse(line)) {
            is Result.Error ->
                fail("${expected(line)} to print the version ($because), but it was rejected: ${parsed.error}")
            is Result.Success -> if (parsed.value !is Invocation.ShowVersion) {
                fail("${expected(line)} to print the version ($because), but ${outcome(line)}")
            }
        }
    }

    /** [argv] resolves to the version, and the text printed is returned, for a claim about what it says. */
    public fun versionText(vararg argv: String, because: String): String {
        showsVersion(argv = argv, because = because)
        val terminal = CapturingTerminal()
        typed.cli.run(argv.toList(), terminal)
        return terminal.out.toString()
    }

    /**
     * A built-in swallows [argv] before the command sees it, so the line is neither bound nor rejected —
     * `rm __complete` resolves to completion candidates, `git -h` to help. Pinning these keeps a
     * built-in's reach visible: it is the mechanism behind every name-collision note in the fixtures.
     */
    public fun shortCircuits(vararg argv: String, because: String) {
        val line = argv.toList()
        when (val parsed = typed.cli.parse(line)) {
            is Result.Error ->
                fail("${expected(line)} to short-circuit ($because), but it was rejected: ${parsed.error}")
            is Result.Success -> if (parsed.value is Invocation.Execute) {
                fail("${expected(line)} to short-circuit ($because), but ${outcome(line)}")
            }
        }
    }

    private fun expected(argv: List<String>): String = "expected `${command(argv)}`"

    private fun command(argv: List<String>): String {
        val quoted = argv.map { if (it.isEmpty() || it.any(Char::isWhitespace)) "\"$it\"" else it }
        return (listOf(typed.cli.name) + quoted).joinToString(" ")
    }

    /** Names what actually happened, for a failure message; re-parses because only the outcome is wanted. */
    private fun outcome(argv: List<String>): String = when (val parsed = typed.cli.parse(argv)) {
        is Result.Error -> "it was rejected: ${parsed.error}"
        is Result.Success -> when (val invocation = parsed.value) {
            is Invocation.Execute -> "it was accepted as an execution of `${invocation.command.name}`"
            is Invocation.ShowHelp -> "it printed help for `${invocation.qualifiedName}`"
            is Invocation.ShowVersion -> "it printed the version"
            is Invocation.ShowCompletion -> "it printed a ${invocation.shell} completion script"
            is Invocation.ShowDocs -> "it printed ${invocation.format} docs"
            is Invocation.ShowCompleteCandidates -> "it printed completion candidates for ${invocation.words}"
        }
    }
}

private class CapturingTerminal : Terminal {
    val out = StringBuilder()

    override fun out(text: String) {
        out.append(text)
    }

    override fun err(text: String) {
        out.append(text)
    }
}
