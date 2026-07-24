package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private fun optTree(): Cli = cli("net") {
    command("call") {
        val port = option("port", "p").int().default(80)
        val verbose = flag("verbose", "v")
        val header = option("header", "H").multiple()
        action {
            Ok("port=${port()} verbose=${verbose()} headers=${header().joinToString(",")}")
        }
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
    fun longOptionWithValue() {
        assertEquals("port=8080 verbose=false headers=\n", optTree().execAndCapture(listOf("call", "--port", "8080")))
    }

    @Test
    fun longOptionEqualsForm() {
        assertEquals("port=8080 verbose=false headers=\n", optTree().execAndCapture(listOf("call", "--port=8080")))
    }

    @Test
    fun shortFlagAndDefaultApply() {
        assertEquals("port=80 verbose=true headers=\n", optTree().execAndCapture(listOf("call", "-v")))
    }

    @Test
    fun repeatedOptionCollectsMultiple() {
        assertEquals(
            "port=80 verbose=false headers=a,b\n",
            optTree().execAndCapture(listOf("call", "-H", "a", "-H", "b")),
        )
    }

    @Test
    fun clusteredFlagThenAttachedOption() {
        // -vp8080 = -v (flag) + -p8080 (option with attached value).
        assertEquals("port=8080 verbose=true headers=\n", optTree().execAndCapture(listOf("call", "-vp8080")))
    }

    @Test
    fun unknownOptionIsRejected() {
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "--nope"))).error
        assertEquals(CliError.UnknownOption("--nope"), err)
    }

    @Test
    fun badIntValueIsRejected() {
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "--port", "abc"))).error
        assertEquals(CliError.BadValue("port", "abc", "not an integer"), err)
    }

    @Test
    fun endOfOptionsRoutesFlagShapedTokenToPositionalOnGroup() {
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
    fun bareDashRoutesToPositional() {
        val app = cli("app") {
            command("grp") { command("child") { action { Ok("") } } }
        }
        val err = assertIs<Result.Error<CliError>>(app.parse(listOf("grp", "-"))).error
        assertEquals(CliError.UnknownSubcommand("grp", "-"), err)
    }

    @Test
    fun secondEndOfOptionsIsPositional() {
        val app = cli("app") {
            command("grp") { command("child") { action { Ok("") } } }
        }
        val err = assertIs<Result.Error<CliError>>(app.parse(listOf("grp", "--", "--"))).error
        assertEquals(CliError.UnknownSubcommand("grp", "--"), err)
    }

    @Test
    fun requiredOptionAbsentIsRejected() {
        val tree = cli("net") {
            command("dial") {
                val host = option("host").required()
                action { Ok(host()) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial"))).error
        assertEquals(CliError.MissingRequiredOption("host"), err)
    }

    @Test
    fun optionValueMissingIsRejected() {
        val tree = cli("net") {
            command("dial") {
                val host = option("host")
                action { Ok(host() ?: "") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--host"))).error
        assertEquals(CliError.MissingOptionValue("host"), err)
    }

    @Test
    fun invalidChoiceIsRejected() {
        val tree = cli("net") {
            command("dial") {
                val mode = option("mode").choice("tcp", "udp")
                action { Ok(mode() ?: "") }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--mode", "sctp"))).error
        assertEquals(CliError.InvalidChoice("mode", "sctp", listOf("tcp", "udp")), err)
    }

    @Test
    fun endOfOptionsIsNotConsumedAsOptionValue() {
        val tree = cli("net") {
            command("dial") {
                val host = option("host")
                action { Ok(host() ?: "") }
            }
        }
        // `--host --` must not bind host="--"; the -- is the terminator, so host has no value.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--host", "--"))).error
        assertEquals(CliError.MissingOptionValue("host"), err)
    }

    @Test
    fun flagLikeNextTokenIsNotAnOptionValue() {
        val tree = cli("net") {
            command("dial") {
                val host = option("host")
                val verbose = flag("verbose", "v")
                action { Ok(host() ?: "") }
            }
        }
        // --verbose is a flag, not host's value.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--host", "--verbose"))).error
        assertEquals(CliError.MissingOptionValue("host"), err)
    }

    @Test
    fun unknownCharInClusterNamesThatChar() {
        val tree = cli("net") {
            command("dial") {
                val verbose = flag("verbose", "v")
                action { Ok("") }
            }
        }
        // -vz: v is a known flag, z is unknown -> error names -z, not -vz.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "-vz"))).error
        assertEquals(CliError.UnknownOption("-z"), err)
    }
}
