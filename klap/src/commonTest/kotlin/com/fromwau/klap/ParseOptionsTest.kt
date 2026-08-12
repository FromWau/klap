package com.fromwau.klap

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import com.fromwau.klap.internal.render.helpText
import com.fromwau.klap.internal.render.message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private enum class Priority { LOW, HIGH }

private fun optTree(): Cli = cli("net") {
    command("call") {
        val port = option("--port", "-p").int().default(80)
        val verbose = flag("--verbose", "-v")
        val header = option("--header", "-H").multiple()
        action {
            Ok("port=${port()} verbose=${verbose()} headers=${header().joinToString(",")}")
        }
    }
}

private fun clusterTree(): Cli = cli("app") {
    command("run") {
        val verbose = flag("--verbose", "-v")
        val force = flag("--force", "-f")
        val port = option("--port", "-p")
        action { Ok("verbose=${verbose()} force=${force()} port=${port() ?: ""}") }
    }
}

/** Drives the full run path against a recording terminal, returning stdout. */
private fun Cli.execAndCapture(argv: List<String>): String {
    val term = RecordingTerminal()
    run(argv.toTypedArray(), term)
    return term.out.toString()
}

class ParseOptionsTest {

    @Test
    fun `long option with value`() {
        assertEquals("port=8080 verbose=false headers=\n", optTree().execAndCapture(listOf("call", "--port", "8080")))
    }

    @Test
    fun `long option equals form`() {
        assertEquals("port=8080 verbose=false headers=\n", optTree().execAndCapture(listOf("call", "--port=8080")))
    }

    @Test
    fun `short flag and default apply`() {
        assertEquals("port=80 verbose=true headers=\n", optTree().execAndCapture(listOf("call", "-v")))
    }

    @Test
    fun `repeated option collects multiple`() {
        assertEquals(
            "port=80 verbose=false headers=a,b\n",
            optTree().execAndCapture(listOf("call", "-H", "a", "-H", "b")),
        )
    }

    @Test
    fun `clustered flag then attached option`() {
        // -vp8080 = -v (flag) + -p8080 (option with attached value).
        assertEquals("port=8080 verbose=true headers=\n", optTree().execAndCapture(listOf("call", "-vp8080")))
    }

    @Test
    fun `unknown option is rejected`() {
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "--nope"))).error
        assertEquals(CliError.UnknownOption("--nope"), err)
    }

    @Test
    fun `bad int value is rejected`() {
        // A built-in converter has no payload, so cause is the reason-only case and restates reason.
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "--port", "abc"))).error
        assertEquals(CliError.BadValue("--port", "abc", "not an integer", ConversionError.NotAnInteger), err)
    }

    @Test
    fun `converters own error type survives to the parse caller`() {
        // The point of the typed converter error: the payload reaches a parse() caller intact, so it can
        // match on its own type instead of re-parsing the rendered sentence to recover what it already knew.
        val tree = cli("net") {
            option("--port").convert { raw ->
                raw.toIntOrNull()?.takeIf { it in 1..65535 }?.let(::Ok)
                    ?: Err(ConversionError.Domain(PortRejected(raw), "$raw is outside 1..65535"))
            }
            action { Ok("ok") }
        }

        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--port", "70000"))).error
        val bad = assertIs<CliError.BadValue>(err)
        assertEquals("70000 is outside 1..65535", bad.reason)
        assertEquals(PortRejected("70000"), assertIs<ConversionError.Domain>(bad.cause).error)
    }

    @Test
    fun `end of options routes flag shaped token to positional on group`() {
        // POSIX: after `--`, a flag-shaped token is an unconditional positional, so a group reports it
        // as an unknown subcommand rather than swallowing it as help.
        val app = cli("app") {
            command("grp") {
                command("child") { action { Ok("") } }
            }
        }
        val err = assertIs<Result.Error<CliError>>(app.parse(listOf("grp", "--", "-x"))).error
        assertEquals(CliError.UnknownSubcommand("grp", "-x"), err)
    }

    @Test
    fun `bare dash routes to positional`() {
        val app = cli("app") {
            command("grp") { command("child") { action { Ok("") } } }
        }
        val err = assertIs<Result.Error<CliError>>(app.parse(listOf("grp", "-"))).error
        assertEquals(CliError.UnknownSubcommand("grp", "-"), err)
    }

    @Test
    fun `second end of options is positional`() {
        val app = cli("app") {
            command("grp") { command("child") { action { Ok("") } } }
        }
        val err = assertIs<Result.Error<CliError>>(app.parse(listOf("grp", "--", "--"))).error
        assertEquals(CliError.UnknownSubcommand("grp", "--"), err)
    }
}

/** What a value-taking option does when its value is absent, unconvertible, or not one of its choices. */
class OptionValueAndChoiceTest {


    @Test
    fun `required option absent is rejected`() {
        val tree = cli("net") {
            command("dial") {
                val host = option("--host").required()
                action { Ok(host()) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial"))).error
        assertEquals(CliError.MissingRequiredOption("--host"), err)
    }

    @Test
    fun `option value missing is rejected`() {
        val tree = cli("net") {
            command("dial") {
                val host = option("--host")
                action { Ok(host() ?: "") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--host"))).error
        assertEquals(CliError.MissingOptionValue("--host"), err)
    }

    @Test
    fun `invalid choice is rejected`() {
        val tree = cli("net") {
            command("dial") {
                val mode = option("--mode").choice("tcp", "udp")
                action { Ok(mode() ?: "") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--mode", "sctp"))).error
        // `sctp` is within edit distance of `tcp`, so InvalidChoice carries that suggestion.
        assertEquals(CliError.InvalidChoice("--mode", "sctp", listOf("tcp", "udp"), "tcp"), err)
    }

    @Test
    fun `choice matches case insensitively and returns the canonical spelling`() {
        // Parity with .enum(): --mode FAST / Fast / fast all bind, and the accessor always sees the
        // declared spelling ("fast"), never the user's casing.
        val tree = cli("net") {
            command("dial") {
                val mode = option("--mode").choice("fast", "slow")
                action { Ok(mode() ?: "") }
            }
        }
        assertEquals("fast\n", tree.execAndCapture(listOf("dial", "--mode", "FAST")))
        assertEquals("fast\n", tree.execAndCapture(listOf("dial", "--mode", "Fast")))
        assertEquals("fast\n", tree.execAndCapture(listOf("dial", "--mode", "fast")))
    }

    @Test
    fun `choice still rejects an unknown value case insensitively`() {
        val tree = cli("net") {
            command("dial") {
                val mode = option("--mode").choice("fast", "slow")
                action { Ok(mode() ?: "") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--mode", "quick"))).error
        assertEquals(CliError.InvalidChoice("--mode", "quick", listOf("fast", "slow"), null), err)
    }

    @Test
    fun `choice invalid value suggestion ignores case`() {
        val tree = cli("net") {
            command("dial") {
                val mode = option("--mode").choice("fast", "slow")
                action { Ok(mode() ?: "") }
            }
        }
        // "SLOQ" is one edit away from "slow" only once case differences are ignored.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--mode", "SLOQ"))).error
        assertEquals(CliError.InvalidChoice("--mode", "SLOQ", listOf("fast", "slow"), "slow"), err)
    }

    @Test
    fun `end of options is not consumed as option value`() {
        val tree = cli("net") {
            command("dial") {
                val host = option("--host")
                action { Ok(host() ?: "") }
            }
        }
        // `--host --` must not bind host="--"; the -- is the terminator, so host has no value.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--host", "--"))).error
        assertEquals(CliError.MissingOptionValue("--host"), err)
    }

    @Test
    fun `a declared flag after a value taking option is still that options value`() {
        val tree = cli("net") {
            command("dial") {
                val host = option("--host")
                val verbose = flag("--verbose", "-v")
                action { Ok("${host()} v=${verbose()}") }
            }
        }
        // The option's value slot wins over the flag it would otherwise have been. A user who meant
        // the flag writes `--host <value> --verbose`; there is no reading that gives them both here.
        assertEquals("--verbose v=false\n", tree.execAndCapture(listOf("dial", "--host", "--verbose")))
    }
}

/** Which character a malformed short cluster blames, and how the token is quoted back. */
class ShortClusterErrorTest {


    @Test
    fun `unknown char in cluster names that char`() {
        val tree = cli("net") {
            command("dial") {
                val verbose = flag("--verbose", "-v")
                action { Ok("") }
            }
        }
        // -vz: v is a known flag, z is unknown -> error names -z, not -vz.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "-vz"))).error
        assertEquals(CliError.UnknownOption("-z", cluster = "-vz"), err)
    }

    @Test
    fun `short cluster equals after a flag reports flag takes no value named as typed`() {
        // -v=x: v is a boolean flag, so the `=` is the short form of `--verbose=x`; the error must name
        // the flag exactly as the user typed it (-v), never a fabricated "-=" token and never the long
        // declared name. --verbose=x separately reports the long form it was typed as.
        val shortErr = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "-v=x"))).error
        assertEquals(CliError.FlagTakesNoValue("-v"), shortErr)
        val longErr = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "--verbose=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--verbose"), longErr)
    }

    @Test
    fun `short and long boolean flag inline value render the flag as typed`() {
        val shortErr = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "-v=x"))).error
        assertEquals("flag '-v' does not take a value", shortErr.message())
        val longErr = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "--verbose=x"))).error
        assertEquals("flag '--verbose' does not take a value", longErr.message())
    }

    @Test
    fun `short cluster stray dash reports the whole token not a fabricated double dash`() {
        // -f-y: f is a flag, then a stray '-' that names no option; the offender must be the whole
        // original token, never the phantom "--" that "-$ch" would produce for ch = '-'.
        val err = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "-f-y"))).error
        assertEquals(CliError.UnknownOption("-f-y"), err)
    }

    @Test
    fun `short cluster unknown letter still names just that char`() {
        // -fz: f is a flag, z is an unknown LETTER (not a stray non-alphanumeric char), so the
        // single-char reporting from `unknown char in cluster names that char` above must still hold.
        val err = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "-fz"))).error
        assertEquals(CliError.UnknownOption("-z", cluster = "-fz"), err)
    }

    @Test
    fun `short option equals form still takes the attached value including the equals sign`() {
        // Documented, unchanged behavior: a short VALUE option consumes the whole attached remainder
        // before any '=' is ever reached, so -p=8080 binds the literal value "=8080".
        assertEquals("verbose=false force=false port==8080\n", clusterTree().execAndCapture(listOf("run", "-p=8080")))
    }
}

/** A `.validate`/`.range` failure reaching the user as a value error rather than an exception. */
class ConverterAndValidationTest {


    @Test
    fun `validate failure passes through as bad value`() {
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").int().validate("must be positive") { it > 0 }
                action { Ok(port()?.toString() ?: "") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--port", "-5"))).error
        assertEquals(CliError.BadValue("--port", "-5", "must be positive"), err)
    }

    @Test
    fun `validate failure on enum backed option yields bad value not invalid choice`() {
        // The key correctness point: a validate failure must never be mislabelled InvalidChoice,
        // even when the spec carries choices from .enum().
        val tree = cli("net") {
            command("dial") {
                val mode = option("--mode").enum<Priority>().validate("must be HIGH") { it == Priority.HIGH }
                action { Ok(mode()?.name ?: "") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--mode", "low"))).error
        assertEquals(CliError.BadValue("--mode", "low", "must be HIGH"), err)
    }

    @Test
    fun `validate pass allows conversion through`() {
        val tree = cli("net") {
            command("dial") {
                val mode = option("--mode").enum<Priority>().validate("must be HIGH") { it == Priority.HIGH }
                action { Ok(mode()?.name ?: "") }
            }
        }
        assertEquals("HIGH\n", tree.execAndCapture(listOf("dial", "--mode", "high")))
    }

    @Test
    fun `range rejects out of bounds option`() {
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").int().range(1..65535)
                action { Ok(port()?.toString() ?: "") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--port", "70000"))).error
        assertEquals(CliError.BadValue("--port", "70000", "must be in 1..65535"), err)
    }

    @Test
    fun `range accepts in bounds option`() {
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").int().range(1..65535)
                action { Ok(port()?.toString() ?: "") }
            }
        }
        assertEquals("8080\n", tree.execAndCapture(listOf("dial", "--port", "8080")))
    }
}

/** `multiple(min)` on options and positionals, and what a declared default does and does not bypass. */
class RepeatedAndDefaultedValueTest {


    @Test
    fun `multiple option min enforced`() {
        val tree = cli("net") {
            command("call") {
                val header = option("--header", "-H").multiple(min = 2)
                action { Ok(header().joinToString(",")) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("call", "-H", "a"))).error
        assertEquals(CliError.TooFewOccurrences("--header", 2, 1), err)
    }

    @Test
    fun `multiple option min satisfied at exact count`() {
        val tree = cli("net") {
            command("call") {
                val header = option("--header", "-H").multiple(min = 2)
                action { Ok(header().joinToString(",")) }
            }
        }
        assertEquals("a,b\n", tree.execAndCapture(listOf("call", "-H", "a", "-H", "b")))
    }

    @Test
    fun `multiple positional min enforced`() {
        // Mirrors `multiple option min enforced` above: a repeatable positional short of its min reports the
        // same count-aware TooFewOccurrences an option's Multiple(min) branch reports, not MissingArgument.
        val tree = cli("net") {
            command("send") {
                val file = argument("file").multiple(min = 2)
                action { Ok(file().joinToString(",")) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("send", "a"))).error
        assertEquals(CliError.TooFewOccurrences("file", 2, 1), err)
    }

    @Test
    fun `multiple positional min absent is still missing argument`() {
        // Zero occurrences is unchanged: a fully-absent mandatory positional stays MissingArgument, never
        // TooFewOccurrences, even though the spec declares a min above 1.
        val tree = cli("net") {
            command("send") {
                val file = argument("file").multiple(min = 2)
                action { Ok(file().joinToString(",")) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("send"))).error
        assertEquals(CliError.MissingArgument("send", "file"), err)
    }

    @Test
    fun `repeated typed option collects converted values`() {
        // The converter runs per occurrence: the accessor is List<Int>, so each element is a real Int.
        val tree = cli("net") {
            command("call") {
                val num = option("--num", "-n").int().multiple()
                action { Ok(num().joinToString(",")) }
            }
        }
        assertEquals("1,2\n", tree.execAndCapture(listOf("call", "-n", "1", "-n", "2")))
    }

    @Test
    fun `repeated typed option bad element is rejected`() {
        // A bad element on any occurrence fails the converter, surfacing as BadValue like a scalar .int().
        val tree = cli("net") {
            command("call") {
                val num = option("--num", "-n").int().multiple()
                action { Ok(num().joinToString(",")) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("call", "-n", "1", "-n", "abc"))).error
        assertEquals(CliError.BadValue("--num", "abc", "not an integer", ConversionError.NotAnInteger), err)
    }

    @Test
    fun `repeated null mapping option rejects the null element instead of binding it`() {
        // .map { it.toIntOrNull() }.multiple() narrows the accessor to a non-null List<Int>, so a
        // null-success element (bad input) has no valid slot: it must surface as BadValue, not bind a null
        // the action would NPE on. Contrast the scalar .map { }.default path, where null falls back to default.
        val tree = cli("net") {
            command("call") {
                val num = option("--num", "-n").map { it.toIntOrNull() }.multiple()
                action { Ok(num().joinToString(",")) }
            }
        }
        assertEquals("1,2\n", tree.execAndCapture(listOf("call", "-n", "1", "-n", "2")))
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("call", "-n", "1", "-n", "x"))).error
        // The converter SUCCEEDED with null rather than throwing, so there is no ConversionError
        // behind this: cause stays null even though the wording matches the thrown-converter case.
        assertEquals(CliError.BadValue("--num", "x", "conversion failed"), err)
    }

    @Test
    fun `default bypasses validation on option`() {
        // A .default value is trusted: it binds directly and is never routed through validate.
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").int().range(1..65535).default(99999)
                action { Ok(port().toString()) }
            }
        }
        assertEquals("99999\n", tree.execAndCapture(listOf("dial")))
    }
}

/** A `.count()` flag binds how many times it was written, however it was spelled. */
class CountFlagTest {


    @Test
    fun `count flag accumulates clustered occurrences`() {
        val tree = cli("net") {
            command("run") {
                val verbose = flag("--verbose", "-v").count()
                action { Ok(verbose().toString()) }
            }
        }
        assertEquals("3\n", tree.execAndCapture(listOf("run", "-vvv")))
    }

    @Test
    fun `count flag accumulates mixed long short and clustered occurrences`() {
        val tree = cli("net") {
            command("run") {
                val verbose = flag("--verbose", "-v").count()
                action { Ok(verbose().toString()) }
            }
        }
        // -v (1) + -vv clustered (2) + --verbose (1) = 4
        assertEquals("4\n", tree.execAndCapture(listOf("run", "-v", "-vv", "--verbose")))
    }

    @Test
    fun `count flag absent is zero`() {
        val tree = cli("net") {
            command("run") {
                val verbose = flag("--verbose", "-v").count()
                action { Ok(verbose().toString()) }
            }
        }
        assertEquals("0\n", tree.execAndCapture(listOf("run")))
    }
}

/** `--x` / `--no-x` and the declared default, resolved by last occurrence. */
class NegatableFlagBindingTest {


    @Test
    fun `negatable flag long form sets true`() {
        val tree = cli("net") {
            command("run") {
                val tint = flag("--tint").negatable(default = false)
                action { Ok(tint().toString()) }
            }
        }
        assertEquals("true\n", tree.execAndCapture(listOf("run", "--tint")))
    }

    @Test
    fun `negatable flag no form sets false`() {
        val tree = cli("net") {
            command("run") {
                val tint = flag("--tint").negatable()
                action { Ok(tint().toString()) }
            }
        }
        assertEquals("false\n", tree.execAndCapture(listOf("run", "--no-tint")))
    }

    @Test
    fun `negatable flag absent uses default`() {
        val tree = cli("net") {
            command("run") {
                val tint = flag("--tint").negatable()
                action { Ok(tint().toString()) }
            }
        }
        assertEquals("true\n", tree.execAndCapture(listOf("run")))
    }

    @Test
    fun `negatable flag last token wins`() {
        val tree = cli("net") {
            command("run") {
                val tint = flag("--tint").negatable()
                action { Ok(tint().toString()) }
            }
        }
        assertEquals("false\n", tree.execAndCapture(listOf("run", "--tint", "--no-tint")))
    }
}

/** When a rejected option carries a did-you-mean, and when it must not. */
class UnknownOptionSuggestionTest {


    @Test
    fun `unknown option suggests nearest long name`() {
        // The near-miss must not be a PREFIX of the name it suggests, here and in the sibling tests below:
        // a prefix resolves as an abbreviation and binds, so it never reaches did-you-mean at all.
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "--verbse"))).error
        assertEquals(CliError.UnknownOption("--verbse", "--verbose"), err)
    }

    @Test
    fun `unknown option far miss has no suggestion`() {
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "--zzzznope"))).error
        assertEquals(CliError.UnknownOption("--zzzznope"), err)
    }

    @Test
    fun `unknown option short single char has no suggestion`() {
        // Single-char short options never get a suggestion, even next to a near-miss long flag.
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "-z"))).error
        assertEquals(CliError.UnknownOption("-z"), err)
    }
}

/** A global binds wherever it appears on the line and reads from any nested action. */
class GlobalReachTest {


    @Test
    fun `global flag readable from nested subcommand action`() {
        val tree = cli("app") {
            val verbose = globalFlag("--verbose", "-v", help = "enable verbose logging")
            command("deploy") {
                command("staging") {
                    action { Ok(if (verbose()) "loud" else "quiet") }
                }
            }
        }
        assertEquals("loud\n", tree.execAndCapture(listOf("deploy", "staging", "-v")))
    }

    @Test
    fun `global flag parses before and after the subcommand path`() {
        val tree = cli("app") {
            val verbose = globalFlag("--verbose", "-v")
            command("build") {
                action { Ok(verbose().toString()) }
            }
        }
        assertEquals("true\n", tree.execAndCapture(listOf("-v", "build")))
        assertEquals("true\n", tree.execAndCapture(listOf("build", "-v")))
        assertEquals("true\n", tree.execAndCapture(listOf("--verbose", "build")))
        assertEquals("false\n", tree.execAndCapture(listOf("build")))
    }

    @Test
    fun `clustered global short flags are each recognized`() {
        val tree = cli("app") {
            val verbose = globalFlag("--verbose", "-v")
            val tint = globalFlag("--tint", "-c")
            command("build") {
                action { Ok("v=${verbose()} c=${tint()}") }
            }
        }
        // -vc is one token but two global flags; both must bind (before or after the subcommand).
        assertEquals("v=true c=true\n", tree.execAndCapture(listOf("-vc", "build")))
        assertEquals("v=true c=true\n", tree.execAndCapture(listOf("build", "-vc")))
    }
}

/** A short cluster mixing local and global characters, in either order and either side of the subcommand. */
class MixedShortClusterTest {


    @Test
    fun `cluster peels globals and leaves the local remainder`() {
        val tree = cli("app") {
            val verbose = globalFlag("--verbose", "-v")
            command("build") {
                val force = flag("--force", "-f")
                action { Ok("v=${verbose()} f=${force()}") }
            }
        }
        // -vf: v is a global, f is the subcommand's own flag; the command's global-aware sift binds both.
        assertEquals("v=true f=true\n", tree.execAndCapture(listOf("build", "-vf")))
    }

    @Test
    fun `mixed cluster binds both regardless of order`() {
        val tree = cli("app") {
            val verbose = globalFlag("--verbose", "-v")
            command("build") {
                val force = flag("--force", "-f")
                action { Ok("v=${verbose()} f=${force()}") }
            }
        }
        // The whole point: a local-then-global cluster binds both, exactly like the global-first order.
        assertEquals("v=true f=true\n", tree.execAndCapture(listOf("build", "-fv")))
        assertEquals("v=true f=true\n", tree.execAndCapture(listOf("build", "-vf")))
    }

    @Test
    fun `mixed cluster local flag then global option consumes value`() {
        val tree = cli("app") {
            val retries = globalOption("--retries", "-r").int().default(0)
            command("build") {
                val force = flag("--force", "-f")
                action { Ok("f=${force()} r=${retries()}") }
            }
        }
        // -fr 5: local flag f, then a global option r taking the following token.
        assertEquals("f=true r=5\n", tree.execAndCapture(listOf("build", "-fr", "5")))
        // -fr9: same, with the value attached to the global option char.
        assertEquals("f=true r=9\n", tree.execAndCapture(listOf("build", "-fr9")))
    }

    @Test
    fun `mixed cluster global flag then local option consumes attached value`() {
        val tree = cli("app") {
            val verbose = globalFlag("--verbose", "-v")
            command("call") {
                val port = option("--port", "-p").int().default(80)
                action { Ok("v=${verbose()} port=${port()}") }
            }
        }
        // -vp8080: global flag v, then a local option p with its attached value.
        assertEquals("v=true port=8080\n", tree.execAndCapture(listOf("call", "-vp8080")))
    }

    @Test
    fun `mixed cluster still rejects a truly unknown char`() {
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("build") {
                flag("--force", "-f")
                action { Ok("") }
            }
        }
        // -fvz: f local, v global, z unknown -> the error still names the offending char, -z.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "-fvz"))).error
        assertEquals(CliError.UnknownOption("-z", cluster = "-fvz"), err)
    }

    @Test
    fun `group cluster error names first offending char like a leaf`() {
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("build") { action { Ok("") } }
        }
        // `app` is a group (a subcommand, no root action). `-hv` mixes the non-clusterable built-in -h
        // with the global -v; the error names the first offending char -h, matching a leaf sift's
        // granularity (not the whole `-hv` token).
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("-hv"))).error
        assertEquals(CliError.UnknownOption("-h"), err)
    }

    @Test
    fun `mixed cluster before the subcommand binds both just like after`() {
        val tree = cli("app") {
            val verbose = globalFlag("--verbose", "-v")
            command("build") {
                val force = flag("--force", "-f")
                action { Ok("v=${verbose()} f=${force()}") }
            }
        }
        // -vf before "build" must bind the global and the local exactly like -vf after it: a global binds
        // identically on either side of the subcommand name, mixed cluster or not. Anchored to a literal
        // AND to the after-subcommand result, since comparing only the two would pass vacuously if both
        // sides stopped binding instead of both succeeding.
        val before = tree.execAndCapture(listOf("-vf", "build"))
        val after = tree.execAndCapture(listOf("build", "-vf"))
        assertEquals("v=true f=true\n", before)
        assertEquals(after, before)
    }

    @Test
    fun `unresolved cluster with no global char still stops routing before the subcommand`() {
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("build") {
                flag("--force", "-f")
                action { Ok("") }
            }
        }
        // -xz: neither char is declared anywhere, local or global. A fix that skips any cluster the
        // routing walk cannot resolve, rather than only one carrying a declared global, would wrongly let
        // this reach `build` instead of stopping here with the offending char.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("-xz", "build"))).error
        assertEquals(CliError.UnknownOption("-x"), err)
    }

    @Test
    fun `mixed cluster with builtin help short before the subcommand still names it`() {
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("build") { action { Ok("") } }
        }
        // -hv: the built-in help short -h never resolves inside a cluster (it names no declared spec at
        // all), even beside a real global -v; the offending char is -h, matching
        // groupClusterErrorNamesFirstOffendingCharLikeALeaf's no-subcommand case now that a real
        // subcommand actually follows.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("-hv", "build"))).error
        assertEquals(CliError.UnknownOption("-h", cluster = "-hv"), err)
    }
}

/** A miss at one node suggesting from everything that node can actually reach. */
class SuggestionAcrossTheTreeTest {


    @Test
    fun `group root long option typo suggests the version builtin`() {
        // A dispatcher root with no subcommand match falls into the isGroup branch; a mistyped
        // `--version` there must suggest, exactly like the same typo on a leaf already does.
        val tree = cli("app") {
            version = "1.0"
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--verssion"))).error
        assertEquals(CliError.UnknownOption("--verssion", "--version"), err)
    }

    @Test
    fun `group root long option typo suggests a global flag`() {
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--verbse"))).error
        assertEquals(CliError.UnknownOption("--verbse", "--verbose"), err)
    }

    @Test
    fun `invalid choice suggests nearest choice`() {
        val tree = cli("app") {
            command("deploy") {
                option("--env").choice("dev", "staging", "prod")
                action { Ok("") }
            }
        }
        val near = assertIs<Result.Error<CliError>>(tree.parse(listOf("deploy", "--env", "prd"))).error
        assertEquals(CliError.InvalidChoice("--env", "prd", listOf("dev", "staging", "prod"), "prod"), near)
        // A value far from every choice gets no suggestion.
        val far = assertIs<Result.Error<CliError>>(tree.parse(listOf("deploy", "--env", "xyzzy"))).error
        assertEquals(CliError.InvalidChoice("--env", "xyzzy", listOf("dev", "staging", "prod"), null), far)
    }

    @Test
    fun `subcommand after separator is named not reported unknown`() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
        }
        // `--` ends command parsing, so `build` after it is an operand, not a route; the error names the
        // misplacement instead of claiming the real command is unknown.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--", "build"))).error
        assertEquals(CliError.SubcommandAfterSeparator("build", "app"), err)
    }

    @Test
    fun `too many arguments on a single command tool suggests nothing`() {
        val tree = cli("app") {
            option("--image").required()
            action { Ok("") }
        }
        // A single-command tool has no injected `docs` subcommand (docs is the `--docs` meta-option),
        // so a near-miss like `docz` has nothing to match and gets no suggestion.
        val hit = assertIs<Result.Error<CliError>>(tree.parse(listOf("--image", "x", "docz", "markdown"))).error
        assertEquals(CliError.TooManyArguments("app", listOf("docz", "markdown"), null), hit)
        // An extra far from any command name carries no suggestion either.
        val far = assertIs<Result.Error<CliError>>(tree.parse(listOf("--image", "x", "zzzz"))).error
        assertEquals(CliError.TooManyArguments("app", listOf("zzzz"), null), far)
    }

    @Test
    fun `too many arguments suggests a nearby subcommand on a hybrid command`() {
        val tree = cli("app") {
            action { Ok("") }
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("biuld"))).error
        assertEquals(CliError.TooManyArguments("app", listOf("biuld"), "build"), err)
    }
}

/** A required global fails a leaf that executes but must not block a bare group that only shows help. */
class GlobalBindPolicyTest {


    @Test
    fun `global option with converter and default resolves`() {
        val tree = cli("app") {
            val retries = globalOption("--retries").int().default(3)
            command("run") {
                action { Ok(retries().toString()) }
            }
        }
        assertEquals("3\n", tree.execAndCapture(listOf("run")))
        assertEquals("5\n", tree.execAndCapture(listOf("run", "--retries", "5")))
        assertEquals("5\n", tree.execAndCapture(listOf("--retries", "5", "run")))
    }

    @Test
    fun `required global enforced on leaf execute but not on bare group help`() {
        val tree = cli("app") {
            val token = globalOption("--token").required()
            command("grp") {
                command("child") { action { Ok(token()) } }
            }
        }
        // A bare group with no args just shows help; the unset required global must not block that.
        val bareGroup = assertIs<Result.Success<Invocation>>(tree.parse(listOf("grp")))
        assertIs<Invocation.ShowHelp>(bareGroup.value)

        // A leaf that actually executes needs the required global.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("grp", "child"))).error
        assertEquals(CliError.MissingRequiredOption("--token"), err)

        // Provided, the leaf resolves normally.
        assertEquals("abc\n", tree.execAndCapture(listOf("grp", "child", "--token", "abc")))
    }

    @Test
    fun `required at least once global defers on help but errors on leaf execute`() {
        val tree = cli("app") {
            val tags = globalOption("--tag").multiple(min = 1)
            command("grp") {
                command("child") { action { Ok(tags().joinToString(",")) } }
            }
        }
        // A bare group shows help; an unmet global minimum must not block that (help wins).
        val bareGroup = assertIs<Result.Success<Invocation>>(tree.parse(listOf("grp")))
        assertIs<Invocation.ShowHelp>(bareGroup.value)

        // A leaf that executes needs at least one occurrence, and reports the precise error.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("grp", "child"))).error
        assertEquals(CliError.TooFewOccurrences("--tag", 1, 0), err)

        // Provided, the leaf resolves normally.
        assertEquals("a,b\n", tree.execAndCapture(listOf("grp", "child", "--tag", "a", "--tag", "b")))
    }

    @Test
    fun `dangling global option reports missing value not unknown option`() {
        val tree = cli("app") {
            val workspace = globalOption("--workspace", "-w")
            command("plan") { action { Ok(workspace() ?: "") } }
        }
        val longErr = assertIs<Result.Error<CliError>>(tree.parse(listOf("plan", "--workspace"))).error
        assertEquals(CliError.MissingOptionValue("--workspace"), longErr)
        val shortErr = assertIs<Result.Error<CliError>>(tree.parse(listOf("plan", "-w"))).error
        assertEquals(CliError.MissingOptionValue("--workspace"), shortErr)
    }
}

/** A boolean flag takes no value, so `--flag=x` is an error naming the flag exactly as it was typed. */
class FlagInlineValueTest {


    @Test
    fun `local boolean flag rejects inline value`() {
        val tree = cli("app") {
            command("run") {
                val yes = flag("--yes")
                action { Ok(yes().toString()) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("run", "--yes=false"))).error
        assertEquals(CliError.FlagTakesNoValue("--yes"), err)
    }

    @Test
    fun `negatable flag inline value suggests the negation`() {
        val tree = cli("app") {
            command("run") {
                val tint = flag("--tint").negatable()
                action { Ok(tint().toString()) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("run", "--tint=false"))).error
        assertEquals(CliError.FlagTakesNoValue("--tint", "no-tint"), err)
    }

    @Test
    fun `negated inline value reports the no form without a negation hint`() {
        // --no-tint=false: the negated long form itself takes no value; there is no further hint to
        // give since --no-tint is already the negation.
        val tree = cli("app") {
            command("run") {
                val tint = flag("--tint").negatable()
                action { Ok(tint().toString()) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("run", "--no-tint=false"))).error
        assertEquals(CliError.FlagTakesNoValue("--no-tint"), err)
    }

    @Test
    fun `global boolean flag rejects inline value`() {
        val tree = cli("app") {
            val debug = globalFlag("--debug")
            command("run") { action { Ok(debug().toString()) } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("run", "--debug=1"))).error
        assertEquals(CliError.FlagTakesNoValue("--debug"), err)
    }

    @Test
    fun `global short flag cluster equals value reports flag takes no value before and after the subcommand`() {
        // -v=x before the subcommand goes through the position-independent global pre-strip
        // (siftGlobals), not the per-command sift; both paths must report the identical error.
        val tree = cli("myapp") {
            globalFlag("--verbose", "-v")
            command("flags") { action { Ok("") } }
        }
        val before = assertIs<Result.Error<CliError>>(tree.parse(listOf("-v=x", "flags"))).error
        assertEquals(CliError.FlagTakesNoValue("-v"), before)
        val after = assertIs<Result.Error<CliError>>(tree.parse(listOf("flags", "-v=x"))).error
        assertEquals(CliError.FlagTakesNoValue("-v"), after)
    }

    @Test
    fun `global short flag cluster equals value on an explicit negative short reports flag takes no value`() {
        // -P=x, where -P is an explicit negative short rather than the flag's own positive spelling: the
        // same FlagTakesNoValue its positive counterpart gets above, on both sides of the subcommand.
        val tree = cli("myapp") {
            globalFlag("--paginate", "-p").negatable("-P")
            command("flags") { action { Ok("") } }
        }
        val before = assertIs<Result.Error<CliError>>(tree.parse(listOf("-P=x", "flags"))).error
        assertEquals(CliError.FlagTakesNoValue("-P"), before)
        val after = assertIs<Result.Error<CliError>>(tree.parse(listOf("flags", "-P=x"))).error
        assertEquals(CliError.FlagTakesNoValue("-P"), after)
    }

    @Test
    fun `global short option equals form still takes the attached value before the subcommand`() {
        // A global VALUE option's short attached form is untouched by the FlagTakesNoValue fix above:
        // -o=val binds the literal "=val", same documented behavior as a local option (see
        // `short option equals form still takes the attached value including the equals sign`).
        val tree = cli("myapp") {
            val out = globalOption("--output", "-o")
            command("flags") { action { Ok(out() ?: "") } }
        }
        assertEquals("=val\n", tree.execAndCapture(listOf("-o=val", "flags")))
    }
}

/** Hiding removes an input from suggestions as well as from help; klap's own built-ins stay suggestible. */
class HiddenAndBuiltinSuggestionTest {


    @Test
    fun `unknown option never suggests a hidden local option`() {
        val tree = cli("net") {
            command("call") {
                option("--visible-opt")
                option("--debug-internal").hidden()
                action { Ok("") }
            }
        }
        // "--debug-internall" is one edit away from the hidden "--debug-internal"; a hidden input must
        // never be revealed via a did-you-mean suggestion.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("call", "--debug-internall"))).error
        assertEquals(CliError.UnknownOption("--debug-internall"), err)
    }

    @Test
    fun `unknown option never suggests a hidden global option`() {
        val tree = cli("app") {
            globalOption("--global-secret").hidden()
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--global-secrett"))).error
        assertEquals(CliError.UnknownOption("--global-secrett"), err)
    }

    @Test
    fun `unknown option suggests a global flag`() {
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("build") { action { Ok("") } }
        }
        // The candidate set for did-you-mean must include globals, not just this command's own locals.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--verbse"))).error
        assertEquals(CliError.UnknownOption("--verbse", "--verbose"), err)
    }

    @Test
    fun `unknown option suggests the help builtin`() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--hepl"))).error
        assertEquals(CliError.UnknownOption("--hepl", "--help"), err)
    }

    @Test
    fun `unknown option suggests the version builtin when root is versioned`() {
        val tree = cli("app") {
            version = "1.0.0"
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--versoin"))).error
        assertEquals(CliError.UnknownOption("--versoin", "--version"), err)
    }
}

/** klap's injected flags reject an inline value the same way an app-declared flag does. */
class BuiltinInlineValueTest {


    @Test
    fun `help builtin given an inline value takes no value`() {
        val tree = cli("x") {
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--help=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--help", null), err)
    }

    @Test
    fun `json builtin given an inline value takes no value`() {
        val tree = cli("x") {
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--json=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--json", null), err)
    }

    @Test
    fun `version builtin given an inline value takes no value when versioned`() {
        val tree = cli("x") {
            version = "1.0"
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--version=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--version", null), err)
    }

    @Test
    fun `version equals is still unknown option when not versioned`() {
        // Unlike --help/--json, --version is only a built-in when the root declares a version; with no
        // version, --version=x is genuinely an unknown option and must not be reported as takes-no-value.
        val tree = cli("x") {
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--version=x"))).error
        assertEquals(CliError.UnknownOption("--version"), err)
    }

    @Test
    fun `help short form given an inline value takes no value`() {
        val tree = cli("x") {
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("-h=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--help", null), err)
    }

    @Test
    fun `help builtin given an empty inline value takes no value`() {
        val tree = cli("x") {
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--help="))).error
        assertEquals(CliError.FlagTakesNoValue("--help", null), err)
    }
}

/** When a declared default stands in for a value: absent, or a converter that succeeds with null. */
class DefaultSubstitutionTest {

    @Test
    fun `option default non null still narrows absent uses default present uses value`() {
        val tree = cli("net") {
            command("dial") {
                val host = option("--host").default("d")
                action { Ok(host()) }
            }
        }
        assertEquals("d\n", tree.execAndCapture(listOf("dial")))
        assertEquals("foo\n", tree.execAndCapture(listOf("dial", "--host", "foo")))
    }

    @Test
    fun `option map to null default substitutes default instead of npe on bad input`() {
        // A .map { } that resolves to null on bad input is not absence, but .default must still catch it
        // via "?: default" semantics rather than let a null slip through and NPE downstream.
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").map { it.toIntOrNull() }.default(0)
                action { Ok(port().toString()) }
            }
        }
        assertEquals("5\n", tree.execAndCapture(listOf("dial", "--port", "5")))
        assertEquals("0\n", tree.execAndCapture(listOf("dial", "--port", "abc")))
        assertEquals("0\n", tree.execAndCapture(listOf("dial")))
    }

    @Test
    fun `option int default bad value still errors instead of being masked by default`() {
        // A converter ERROR (not a null success) must still surface as BadValue, never silently defaulted.
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").int().default(0)
                action { Ok(port().toString()) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--port", "abc"))).error
        assertEquals(CliError.BadValue("--port", "abc", "not an integer", ConversionError.NotAnInteger), err)
        assertEquals("5\n", tree.execAndCapture(listOf("dial", "--port", "5")))
        assertEquals("0\n", tree.execAndCapture(listOf("dial")))
    }

    @Test
    fun `end of options makes a builtin looking token a positional not a takes no value error`() {
        // A built-in-looking token AFTER the `--` end-of-options marker is an unconditional positional
        // (same POSIX rule as `end of options routes flag shaped token to positional on group` above), so it must
        // never be misread as --help's takes-no-value form, nor trigger help.
        val tree = cli("x") {
            val a = argument("a")
            action { Ok(a()) }
        }
        val inv = assertIs<Result.Success<Invocation>>(tree.parse(listOf("--", "--help=x"))).value
        assertIs<Invocation.Execute>(inv)
        assertEquals("--help=x\n", tree.execAndCapture(listOf("--", "--help=x")))
    }
}

/** A converter chain that is misused still reports a value error rather than crashing the parse. */
class NeverThrowContractTest {

    @Test
    fun `nullable map then validate on bad input skips validate instead of crashing`() {
        // .map { toIntOrNull() } resolves bad input to null, which is treated as absent, so validate is
        // never handed the null (which would NPE its non-null predicate). It binds null, no crash.
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").map { it.toIntOrNull() }.validate("positive") { it > 0 }
                action { Ok(port().toString()) }
            }
        }
        assertEquals("null\n", tree.execAndCapture(listOf("dial", "--port", "abc")))
    }

    @Test
    fun `nullable map feeding null into a later string stage yields bad value not a crash`() {
        // .map { ifEmpty { null } } produces null, which the following .boolean() stage casts to String;
        // that throws at parse, and the never-throw contract must turn it into BadValue, not a crash.
        val tree = cli("app") {
            val v = option("-v").map { it.ifEmpty { null } }.boolean()
            action { Ok(v().toString()) }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("-v", ""))).error
        // reason is the platform-dependent cast exception message, so only the type and name are pinned.
        assertIs<CliError.BadValue>(err)
        assertEquals("-v", err.name)
    }
}

/** An option declared with no long form at all. */
class ShortOnlyOptionTest {


    @Test
    fun `short only option is declarable and parses`() {
        val tree = cli("app") {
            command("go") {
                val context = option("-Z", help = "lines of context")
                action { Ok("Z=${context() ?: ""}") }
            }
        }
        assertEquals("Z=9\n", tree.execAndCapture(listOf("go", "-Z", "9")))
        assertEquals("Z=9\n", tree.execAndCapture(listOf("go", "-Z9")))
    }

    @Test
    fun `short only option has no long form`() {
        val tree = cli("app") {
            command("go") {
                val context = option("-Z", help = "lines of context")
                action { Ok("Z=${context() ?: ""}") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("go", "--Z", "9"))).error
        assertIs<CliError.UnknownOption>(err)
    }

    @Test
    fun `short only option is not suggested as a long form`() {
        // Its only spelling is `-Z`, so did-you-mean must not invent `--Z` for a nearby typo.
        val tree = cli("app") {
            command("go") {
                val context = option("-Z", help = "lines of context")
                action { Ok("Z=${context() ?: ""}") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("go", "--z"))).error
        assertEquals(CliError.UnknownOption("--z"), err)
    }
}

/** An input declared under several names answers to all of them, and errors name the right one. */
class MultipleSpellingsTest {


    @Test
    fun `one flag answers to every spelling`() {
        val tree = cli("app") {
            command("go") {
                val recursive = flag("--recursive", "-r", "-R", help = "recurse")
                action { Ok("r=${recursive()}") }
            }
        }
        assertEquals("r=true\n", tree.execAndCapture(listOf("go", "--recursive")))
        assertEquals("r=true\n", tree.execAndCapture(listOf("go", "-r")))
        assertEquals("r=true\n", tree.execAndCapture(listOf("go", "-R")))
        assertEquals("r=false\n", tree.execAndCapture(listOf("go")))
    }

    @Test
    fun `one option answers to every spelling`() {
        val tree = cli("log") {
            command("show") {
                val since = option("--since", "--after", "-a", help = "lower time bound")
                action { Ok("since=${since() ?: ""}") }
            }
        }
        assertEquals("since=noon\n", tree.execAndCapture(listOf("show", "--since", "noon")))
        assertEquals("since=noon\n", tree.execAndCapture(listOf("show", "--after", "noon")))
        assertEquals("since=noon\n", tree.execAndCapture(listOf("show", "-a", "noon")))
        assertEquals("since=noon\n", tree.execAndCapture(listOf("show", "--after=noon")))
    }

    @Test
    fun `every long spelling of a negatable flag generates its own negation`() {
        val tree = cli("app") {
            command("go") {
                val tint = flag("--tint", "--colour", "-t", help = "colourise").negatable(default = true)
                action { Ok("t=${tint()}") }
            }
        }
        assertEquals("t=false\n", tree.execAndCapture(listOf("go", "--no-tint")))
        assertEquals("t=false\n", tree.execAndCapture(listOf("go", "--no-colour")))
        assertEquals("t=true\n", tree.execAndCapture(listOf("go", "--no-colour", "--tint")))
    }

    @Test
    fun `error text names a short only option by the token that actually works`() {
        // --Z is not a spelling this option has; advertising it in an error sends the user to
        // "unknown option '--Z'".
        val tree = cli("diff") {
            command("run") {
                val context = option("-Z", help = "lines of context").required()
                action { Ok(context()) }
            }
        }
        val missing = assertIs<Result.Error<CliError>>(tree.parse(listOf("run"))).error
        assertEquals(CliError.MissingRequiredOption("-Z"), missing)
        val noValue = assertIs<Result.Error<CliError>>(tree.parse(listOf("run", "-Z"))).error
        assertEquals(CliError.MissingOptionValue("-Z"), noValue)
    }

    @Test
    fun `error text names the options primary spelling not its short`() {
        val tree = cli("app") {
            command("run") {
                val output = option("-o", "--output", help = "where to write").required()
                action { Ok(output()) }
            }
        }
        val missing = assertIs<Result.Error<CliError>>(tree.parse(listOf("run"))).error
        assertEquals(CliError.MissingRequiredOption("-o"), missing)
    }

    @Test
    fun `the long form missing value error keeps its dashes`() {
        val tree = cli("net") {
            command("dial") {
                val host = option("--host", help = "target host")
                action { Ok(host() ?: "") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--host"))).error
        assertEquals("option --host requires a value", err.message())
    }

    @Test
    fun `the cluster negation hint names the flags real long form`() {
        val tree = cli("app") {
            command("go") {
                val extract = flag("-x", "--extract", help = "extract").negatable(default = false)
                action { Ok("x=${extract()}") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("go", "-x=1"))).error
        assertEquals(CliError.FlagTakesNoValue("-x", "no-extract"), err)
    }

    @Test
    fun `a secondary short clusters like the primary one`() {
        val tree = cli("app") {
            command("go") {
                val recursive = flag("--recursive", "-r", "-R", help = "recurse")
                val force = flag("--force", "-f", help = "force")
                action { Ok("r=${recursive()} f=${force()}") }
            }
        }
        assertEquals("r=true f=true\n", tree.execAndCapture(listOf("go", "-fR")))
    }
}

/**
 * A negatable GLOBAL flag whose short can hide in a mixed local+global cluster: `-fv` is left whole by the
 * position-independent pre-strip (which holds no local specs, so it cannot tell `v` from a glued value for
 * `f`) and only resolved by the command's own sift, one pass later than the `--no-verbose` beside it.
 */
private fun negatableGlobalTree(): Cli = cli("app") {
    val verbose = globalFlag("--verbose", "-v", help = "chatty").negatable(default = true)
    command("go") {
        val force = flag("--force", "-f")
        action { Ok("v=${verbose()} f=${force()}") }
    }
}

/** Last-occurrence-wins for a negatable global, across the two passes that resolve its two forms. */
class NegatableGlobalPolarityTest {

    @Test
    fun `mixed cluster before the negation loses to it`() {
        // The bug: `-fv` is resolved by the later pass, but sits EARLIER in argv, so `--no-verbose` wins.
        assertEquals("v=false f=true\n", negatableGlobalTree().execAndCapture(listOf("go", "-fv", "--no-verbose")))
    }

    @Test
    fun `mixed cluster after the negation beats it`() {
        // The mirror, and why "skip an already-set polarity" is not the fix: here the cluster IS last.
        assertEquals("v=true f=true\n", negatableGlobalTree().execAndCapture(listOf("go", "--no-verbose", "-fv")))
    }

    @Test
    fun `the last long form wins`() {
        val tree = negatableGlobalTree()
        assertEquals("v=false f=false\n", tree.execAndCapture(listOf("go", "--verbose", "--no-verbose")))
        assertEquals("v=true f=false\n", tree.execAndCapture(listOf("go", "--no-verbose", "--verbose")))
    }

    @Test
    fun `a pure global short cluster takes part in the ordering`() {
        // `-v` alone is fully global, so the pre-strip resolves it in the same pass as `--no-verbose`.
        val tree = negatableGlobalTree()
        assertEquals("v=false f=false\n", tree.execAndCapture(listOf("go", "-v", "--no-verbose")))
        assertEquals("v=true f=false\n", tree.execAndCapture(listOf("go", "--no-verbose", "-v")))
        assertEquals("v=false f=false\n", tree.execAndCapture(listOf("-v", "go", "--no-verbose")))
    }

    @Test
    fun `negation alone binds off and absence binds the declared default`() {
        val tree = negatableGlobalTree()
        assertEquals("v=false f=false\n", tree.execAndCapture(listOf("go", "--no-verbose")))
        assertEquals("v=true f=false\n", tree.execAndCapture(listOf("go")))
    }

    @Test
    fun `negation stripped from ahead of the subcommand still orders against the cluster`() {
        // The negation is consumed before the subcommand token, so it has no index in the leaf's segment at
        // all — only the raw argv index the pre-strip recorded can place it against the cluster.
        val tree = negatableGlobalTree()
        assertEquals("v=true f=true\n", tree.execAndCapture(listOf("--no-verbose", "go", "-fv")))
        // The mirror binds too, rather than erroring: a mixed cluster ahead of the subcommand is deferred to
        // `go`'s own sift exactly like the same cluster written after it, so `-fv`'s -v still loses to the
        // later --no-verbose by the same last-occurrence-wins rule `mixed cluster before the negation loses to it`
        // pins entirely inside the leaf's own segment.
        assertEquals("v=false f=true\n", tree.execAndCapture(listOf("-fv", "go", "--no-verbose")))
    }

    @Test
    fun `negatable local flag is unaffected in every order`() {
        val tree = cli("app") {
            command("go") {
                val tint = flag("--tint", "-t").negatable(default = true)
                val force = flag("--force", "-f")
                action { Ok("t=${tint()} f=${force()}") }
            }
        }
        // One command sift resolves both forms, so ordering already held; it must keep holding.
        assertEquals("t=false f=true\n", tree.execAndCapture(listOf("go", "-ft", "--no-tint")))
        assertEquals("t=true f=true\n", tree.execAndCapture(listOf("go", "--no-tint", "-ft")))
        assertEquals("t=false f=false\n", tree.execAndCapture(listOf("go", "--tint", "--no-tint")))
    }

    @Test
    fun `count global in a mixed cluster still counts`() {
        // hitFlag does the counting as well as the polarity, so a count global must survive the change.
        val tree = cli("app") {
            val verbose = globalFlag("--verbose", "-v").count()
            command("go") {
                val force = flag("--force", "-f")
                action { Ok("v=${verbose()} f=${force()}") }
            }
        }
        assertEquals("v=2 f=true\n", tree.execAndCapture(listOf("go", "-fvv")))
        assertEquals("v=3 f=true\n", tree.execAndCapture(listOf("go", "-fv", "-v", "--verbose")))
    }

    @Test
    fun `a negatable global with no short orders by position`() {
        val tree = cli("app") {
            val tint = globalFlag("--tint").negatable(default = true)
            command("go") {
                val force = flag("--force", "-f")
                action { Ok("t=${tint()} f=${force()}") }
            }
        }
        assertEquals("t=false f=true\n", tree.execAndCapture(listOf("go", "-f", "--no-tint")))
        assertEquals("t=true f=true\n", tree.execAndCapture(listOf("go", "--no-tint", "--tint", "-f")))
    }

    @Test
    fun `an unclustered short binds on and the long negation binds off`() {
        val tree = negatableGlobalTree()
        assertEquals("v=true f=false\n", tree.execAndCapture(listOf("go", "-v")))
        assertEquals("v=false f=false\n", tree.execAndCapture(listOf("--no-verbose", "go")))
        assertEquals("v=true f=true\n", tree.execAndCapture(listOf("go", "-f", "-v")))
    }
}

private fun messageTree(): Cli = cli("app") {
    val message = option("--message", "-m").required()
    action { Ok(message()) }
}

private fun globalGrep(): TypedCli<String> = cliOf("mygrep") {
    val tag = globalOption("--tag", "-t")
    val regexp = option("--regexp", "-e")
    val files = argument("file").multiple(min = 0)
    action { Ok("") }
    projection { "e=${regexp()} gopt=${tag()} files=${files()}" }
}

private fun globalDispatcher(): TypedCli<String> = cliOf("app") {
    val tag = globalOption("--tag", "-t")
    globalFlag("--vocal", "-v")
    dispatch(
        command("sub") {
            val regexp = option("--regexp", "-e")
            val files = argument("file").multiple(min = 0)
            action { Ok("") }
            projection { "e=${regexp()} gopt=${tag()} files=${files()}" }
        },
    )
}

private fun globalFlagsDispatcher(): TypedCli<String> = cliOf("app") {
    val verbose = globalFlag("--verbose", "-v")
    val quiet = globalFlag("--quiet", "-q")
    dispatch(
        command("sub") {
            val regexp = option("--regexp", "-e")
            val files = argument("file").multiple(min = 0)
            action { Ok("") }
            projection { "e=${regexp()} verbose=${verbose()} quiet=${quiet()} files=${files()}" }
        },
    )
}

private fun globalNegatableDispatcher(): TypedCli<String> = cliOf("app") {
    val verbose = globalFlag("--verbose", "-V").negatable("--no-verbose", "-P")
    dispatch(
        command("sub") {
            val regexp = option("--regexp", "-e")
            val files = argument("file").multiple(min = 0)
            action { Ok("") }
            projection { "e=${regexp()} verbose=${verbose()} files=${files()}" }
        },
    )
}

/** A value-taking option's next token is its value, whatever that token looks like. */
class DashLedOptionValueTest {

    @Test
    fun `a dash led token binds as an option value`() {
        assertEquals("-weird\n", messageTree().execAndCapture(listOf("-m", "-weird")))
        assertEquals("--verbose\n", messageTree().execAndCapture(listOf("--message", "--verbose")))
    }

    @Test
    fun `a dash led token binds through a short cluster too`() {
        val tree = cli("app") {
            val verbose = flag("--verbose", "-v")
            val message = option("--message", "-m").required()
            action { Ok("v=${verbose()} m=${message()}") }
        }
        assertEquals("v=true m=-x\n", tree.execAndCapture(listOf("-vm", "-x")))
    }

    @Test
    fun `a dash led token binds to a global before the subcommand resolves`() {
        val tree = cli("app") {
            val message = globalOption("--message", "-m").required()
            command("go") { action { Ok(message()) } }
        }
        assertEquals("--verbose\n", tree.execAndCapture(listOf("--message", "--verbose", "go")))
        assertEquals("-x\n", tree.execAndCapture(listOf("-m", "-x", "go")))
    }

    @Test
    fun `a trailing value less option still reports missing value`() {
        // The one case the greedy rule must NOT swallow: there is no next token at all.
        val err = assertIs<Result.Error<CliError>>(messageTree().parse(listOf("--message"))).error
        assertEquals(CliError.MissingOptionValue("--message"), err)
    }

    @Test
    fun `end of options still terminates rather than binding as a value`() {
        // `--` is structural, not a value: `app --message -- x` must not bind "--" as the message.
        val err = assertIs<Result.Error<CliError>>(messageTree().parse(listOf("--message", "--"))).error
        assertEquals(CliError.MissingOptionValue("--message"), err)
    }

    @Test
    fun `an unknown option after a value taking one is swallowed as its value`() {
        // The accepted cost of the rule, pinned so it is a decision rather than a surprise: klap cannot
        // tell a mistyped option from a dash-led value, and every tool that accepts `-m -x` pays this.
        assertEquals("--nope\n", messageTree().execAndCapture(listOf("-m", "--nope")))
    }

    // --- A global's reach stops at a value-taking option's argument slot ---
    //
    // The mirror of the built-in rule (BuiltinsTest): a global is position-independent, but the slot after
    // a value-taking option belongs to that option, so a global standing in one is that option's literal
    // value and does not bind. Each test pairs the shielded line with the one that must still bind.

    @Test
    fun `a long global in a local options value slot is that options value`() {
        assertEquals(
            Ok("e=--tag gopt=null files=[f.txt]"),
            globalGrep().parse(listOf("-e", "--tag", "f.txt")),
        )
        assertEquals(
            Ok("e=--tag gopt=null files=[f.txt]"),
            globalDispatcher().parse(listOf("sub", "-e", "--tag", "f.txt")),
        )
        // The long form of the local option reaches the slot through a different branch than the short.
        assertEquals(
            Ok("e=--tag gopt=null files=[f.txt]"),
            globalDispatcher().parse(listOf("sub", "--regexp", "--tag", "f.txt")),
        )
        // Abbreviated, since prefix matching widens the set of spellings the pre-strip can reach.
        assertEquals(
            Ok("e=--ta gopt=null files=[f.txt]"),
            globalDispatcher().parse(listOf("sub", "-e", "--ta", "f.txt")),
        )
    }

    @Test
    fun `a fully global short cluster in a value slot is the options literal value`() {
        // An all-global cluster is the one shape the pre-strip claims whole, so it is the shape most at
        // risk here: `-vq` must arrive as `-e`'s value with both globals still at their defaults.
        assertEquals(
            Ok("e=-vq verbose=false quiet=false files=[f.txt]"),
            globalFlagsDispatcher().parse(listOf("sub", "-e", "-vq", "f.txt")),
        )
        // ...including when the cluster ends in a global that itself takes a value: it must not reach past
        // the slot and swallow the operand too.
        assertEquals(
            Ok("e=-vt gopt=null files=[f.txt]"),
            globalDispatcher().parse(listOf("sub", "-e", "-vt", "f.txt")),
        )
    }

    @Test
    fun `a global still takes its own value outside a slot at every depth`() {
        assertEquals(
            Ok("e=null gopt=v files=[f.txt]"),
            globalDispatcher().parse(listOf("--tag", "v", "sub", "f.txt")),
        )
        assertEquals(
            Ok("e=null gopt=v files=[f.txt]"),
            globalDispatcher().parse(listOf("sub", "--tag", "v", "f.txt")),
        )
        // Its value may look like anything, the same greedy rule a local option follows.
        assertEquals(
            Ok("e=x gopt=--json files=[f.txt]"),
            globalDispatcher().parse(listOf("sub", "--tag", "--json", "-e", "x", "f.txt")),
        )
        // The short cluster path is a separate walk in the same pass, so it gets its own line — bare, and
        // as the tail of an all-global cluster, which is the branch that skips a whole extra token.
        assertEquals(
            Ok("e=null gopt=v files=[f.txt]"),
            globalDispatcher().parse(listOf("sub", "-t", "v", "f.txt")),
        )
        assertEquals(
            Ok("e=null gopt=v files=[f.txt]"),
            globalDispatcher().parse(listOf("sub", "-vt", "v", "f.txt")),
        )
    }

    @Test
    fun `a negatable globals negation spellings are ordinary values in a slot`() {
        val tree = globalNegatableDispatcher()
        // Both halves of the negation surface: the generated `--no-<long>` and an explicit short.
        assertEquals(Ok("e=--no-verbose verbose=true files=[f.txt]"), tree.parse(listOf("sub", "-e", "--no-verbose", "f.txt")))
        assertEquals(Ok("e=-P verbose=true files=[f.txt]"), tree.parse(listOf("sub", "-e", "-P", "f.txt")))
        // Outside the slot each still turns the global off, wherever it sits.
        assertEquals(Ok("e=null verbose=false files=[f.txt]"), tree.parse(listOf("sub", "--no-verbose", "f.txt")))
        assertEquals(Ok("e=null verbose=false files=[f.txt]"), tree.parse(listOf("--no-verbose", "sub", "f.txt")))
        assertEquals(Ok("e=null verbose=false files=[f.txt]"), tree.parse(listOf("sub", "f.txt", "-P")))
    }

    @Test
    fun `a global outside a value slot still binds past an operand and past the switch`() {
        // Permuting (the default): an operand does not end options, so a later global binds normally.
        assertEquals(
            Ok("e=null gopt=v files=[f.txt]"),
            globalDispatcher().parse(listOf("sub", "f.txt", "--tag", "v")),
        )

        // optionsEndAtFirstOperand does not reach a position-independent global, by design (see the
        // switch's own docs): past the operand that fires it, the pre-strip claims the global as ever.
        val wrapper = cli("app") {
            val tag = globalOption("--tag", "-t")
            command("sub") {
                optionsEndAtFirstOperand = true
                val regexp = option("--regexp", "-e")
                val files = argument("file").multiple(min = 0)
                action<String>(human = { it }) { Ok("e=${regexp()} gopt=${tag()} files=${files()}") }
            }
        }
        assertEquals("e=null gopt=v files=[f.txt, -e, x]", wrapper.bindText("sub", "f.txt", "--tag", "v", "-e", "x"))
    }
}

/** A short whose character is a digit, and the tree's own declarations deciding `-4` from `-100`. */
class DigitShortTest {

    @Test
    fun `a digit short is declarable and parses`() {
        val tree = cli("curl") {
            val ipv4 = flag("-4", help = "resolve names to IPv4 addresses only")
            val ipv6 = flag("-6", help = "resolve names to IPv6 addresses only")
            action { Ok("4=${ipv4()} 6=${ipv6()}") }
        }
        assertEquals("4=true 6=false\n", tree.execAndCapture(listOf("-4")))
        assertEquals("4=true 6=true\n", tree.execAndCapture(listOf("-4", "-6")))
    }

    @Test
    fun `an undeclared digit token is an unknown option rather than an operand`() {
        // Real `ls -5` and `sleep -1` both reject, and so does klap: a dash-led token is an option token
        // whatever follows the dash, and only a declaration makes it mean anything.
        val tree = cli("app") {
            val n = argument("n").int()
            action { Ok(n().toString()) }
        }
        assertEquals(CliError.UnknownOption("-1", cluster = "-100"), assertIs<Result.Error<CliError>>(tree.parse(listOf("-100"))).error)
    }

    @Test
    fun `a negative number operand is written after the end of options marker`() {
        // The escape, and the reason error-by-default costs little: `--` is how every POSIX tool says
        // "operand", and an option VALUE needs no escape at all.
        val tree = cli("app") {
            val n = argument("n").int()
            val offset = option("--offset", "-o").int()
            action { Ok("${n()}:${offset()}") }
        }
        assertEquals("-100:null\n", tree.execAndCapture(listOf("--", "-100")))
        assertEquals("1:-5\n", tree.execAndCapture(listOf("-o", "-5", "1")))
    }

    @Test
    fun `a declared digit short is just another cluster char`() {
        val tree = cli("app") {
            val one = flag("-1", help = "one file per line")
            val rest = argument("n").multiple(min = 0)
            action { Ok("1=${one()} rest=${rest()}") }
        }
        assertEquals("1=true rest=[]\n", tree.execAndCapture(listOf("-1")))
        assertEquals(CliError.UnknownOption("-2"), assertIs<Result.Error<CliError>>(tree.parse(listOf("-2"))).error)
    }

    @Test
    fun `a global digit short is stripped before the subcommand resolves`() {
        val tree = cli("app") {
            val ipv4 = globalFlag("-4", help = "IPv4 only")
            command("go") { action { Ok(ipv4().toString()) } }
        }
        assertEquals("true\n", tree.execAndCapture(listOf("-4", "go")))
        assertEquals("true\n", tree.execAndCapture(listOf("go", "-4")))
    }

    @Test
    fun `a digit short still clusters with its neighbours`() {
        val tree = cli("app") {
            val ipv4 = flag("-4", help = "IPv4 only")
            val verbose = flag("--verbose", "-v", help = "chatty")
            action { Ok("4=${ipv4()} v=${verbose()}") }
        }
        assertEquals("4=true v=true\n", tree.execAndCapture(listOf("-4v")))
        assertEquals("4=true v=true\n", tree.execAndCapture(listOf("-v4")))
    }
}

/** `head -5` is not a flag named 5, it is shorthand for `-n 5`, so it is modelled as an alias. */
class NumericAliasTest {

    private fun headTree(): Cli = cli("head") {
        val lines = option("--lines", "-n", help = "print the first NUM lines").int()
        numericAlias(lines)
        val files = argument("file").multiple(min = 0)
        action { Ok("${lines()}:${files()}") }
    }

    @Test
    fun `a numeric alias binds through the option it aliases`() {
        assertEquals("5:[f]\n", headTree().execAndCapture(listOf("-5", "f")))
        assertEquals("5:[f]\n", headTree().execAndCapture(listOf("-n", "5", "f")))
        // Any N, not a fixed set of declared shorts.
        assertEquals("20:[f]\n", headTree().execAndCapture(listOf("-20", "f")))
    }

    @Test
    fun `a numeric alias feeds the options own converter and validation`() {
        val tree = cli("head") {
            val lines = option("--lines", "-n").int().range(1..10)
            numericAlias(lines)
            action { Ok(lines().toString()) }
        }
        assertEquals(
            CliError.BadValue("--lines", "99", "must be in 1..10"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("-99"))).error,
        )
    }

    @Test
    fun `a declared digit short wins over the numeric alias`() {
        val tree = cli("app") {
            val ipv4 = flag("-4", help = "IPv4 only")
            val lines = option("--lines", "-n").int()
            numericAlias(lines)
            action { Ok("4=${ipv4()} n=${lines()}") }
        }
        assertEquals("4=true n=null\n", tree.execAndCapture(listOf("-4")))
        assertEquals("4=false n=5\n", tree.execAndCapture(listOf("-5")))
    }

    @Test
    fun `a digit token is an unknown option without a numeric alias`() {
        // Real `ls -5` and `sleep -1` both reject. A tree that declares no numeric alias must too, rather
        // than silently binding a file named "-5".
        val tree = cli("ls") {
            val files = argument("file").multiple(min = 0)
            action { Ok(files().toString()) }
        }
        assertEquals(CliError.UnknownOption("-5"), assertIs<Result.Error<CliError>>(tree.parse(listOf("-5"))).error)
    }

    @Test
    fun `a numeric alias claims only an all digit token`() {
        // `-5x` is not a number, so the alias must not take it; it stays a short cluster.
        assertEquals(
            CliError.UnknownOption("-5", cluster = "-5x"),
            assertIs<Result.Error<CliError>>(headTree().parse(listOf("-5x"))).error,
        )
    }

    @Test
    fun `the aliased options help row advertises the numeric form`() {
        assertTrue("-NUM" in headTree().helpText(), headTree().helpText())
    }
}

/** An option whose value is optional, and the operand that must survive beside it. */
class OptionalValueTest {

    private fun tree(): Cli = cli("ls") {
        // --color collides with klap's own built-in of the same name; free it the same way
        // BuiltinsOptOutTest does, so the option under test can use the name unchanged.
        builtins { color = false }
        val color = option("--color").optionalValue("always")
        val files = argument("file").multiple(min = 0)
        action { Ok("color=${color()} files=${files()}") }
    }

    private fun run(vararg argv: String): String = tree().execAndCapture(argv.toList())

    @Test
    fun `the attached form binds its own value`() {
        assertEquals("color=never files=[]\n", run("--color=never"))
    }

    @Test
    fun `a bare occurrence binds the declared bare value`() {
        assertEquals("color=always files=[]\n", run("--color"))
    }

    @Test
    fun `the space form leaves the next token as an operand`() {
        // The rule guideline 7 exists for: an optional-value option cannot tell its value from the next
        // operand, so it never takes one. GNU does the same, and `ls --color src` colours `src`'s listing.
        assertEquals("color=always files=[src]\n", run("--color", "src"))
    }

    @Test
    fun `an absent occurrence still binds null`() {
        assertEquals("color=null files=[f]\n", run("f"))
    }

    @Test
    fun `a short form binds attached and bare the same way`() {
        val tree = cli("git") {
            val verbose = flag("--verbose", "-v")
            val sign = option("--gpg-sign", "-S").optionalValue("default-key")
            val files = argument("file").multiple(min = 0)
            action { Ok("verbose=${verbose()} sign=${sign()} files=${files()}") }
        }
        assertEquals("verbose=false sign=abc files=[]\n", tree.execAndCapture(listOf("-Sabc")))
        assertEquals("verbose=false sign=default-key files=[]\n", tree.execAndCapture(listOf("-S")))
        // ...and the short form's space spelling leaves its operand alone too.
        assertEquals("verbose=false sign=default-key files=[f]\n", tree.execAndCapture(listOf("-S", "f")))
        // A multi-char cluster terminates the same way: -v (flag) + -S (bare optional-value) at the end.
        assertEquals("verbose=true sign=default-key files=[]\n", tree.execAndCapture(listOf("-vS")))
    }

    @Test
    fun `the bare value runs through the options own converter`() {
        val tree = cli("app") {
            val depth = option("--depth").optionalValue("1").int()
            action { Ok(depth().toString()) }
        }
        assertEquals("1\n", tree.execAndCapture(listOf("--depth")))
        assertEquals("5\n", tree.execAndCapture(listOf("--depth=5")))
    }

    @Test
    fun `a bad attached value is still rejected`() {
        val tree = cli("app") {
            val depth = option("--depth").optionalValue("1").int()
            action { Ok(depth().toString()) }
        }
        assertEquals(
            CliError.BadValue("--depth", "abc", "not an integer", ConversionError.NotAnInteger),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--depth=abc"))).error,
        )
    }

    @Test
    fun `an option without the opt in still demands its value`() {
        // The conformance guarantee: nothing about an ordinary option changes.
        val tree = cli("app") {
            val out = option("--out").required()
            action { Ok(out()) }
        }
        assertEquals(
            CliError.MissingOptionValue("--out"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--out"))).error,
        )
        assertEquals("x\n", tree.execAndCapture(listOf("--out", "x")))
    }

    @Test
    fun `a bare occurrence at the end of argv binds`() {
        // A bare occurrence at the end of argv has no next token to reach for at all.
        assertEquals("color=always files=[f]\n", run("f", "--color"))
    }

    @Test
    fun `a global optional value option behaves the same before and after the subcommand`() {
        val tree = cli("git") {
            val execPath = globalOption("--exec-path", "-e").optionalValue("/usr/lib/git-core")
            command("log") { action { Ok(execPath() ?: "none") } }
        }
        assertEquals("/usr/lib/git-core\n", tree.execAndCapture(listOf("--exec-path", "log")))
        assertEquals("/usr/lib/git-core\n", tree.execAndCapture(listOf("log", "--exec-path")))
        assertEquals("/opt/git\n", tree.execAndCapture(listOf("--exec-path=/opt/git", "log")))
    }

    @Test
    fun `a global optional value option binds through the short cluster path`() {
        val tree = cli("git") {
            val execPath = globalOption("--exec-path", "-e").optionalValue("/usr/lib/git-core")
            command("log") { action { Ok(execPath() ?: "none") } }
        }
        // A bad advance here would swallow "log", the token right after the bare short global.
        assertEquals("/usr/lib/git-core\n", tree.execAndCapture(listOf("-e", "log")))
        assertEquals("/opt/git\n", tree.execAndCapture(listOf("-e/opt/git", "log")))
    }
}

/** A converter error with a payload the rendered sentence drops, for [ParseOptionsTest]. */
private data class PortRejected(val given: String) : IError
