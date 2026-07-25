package com.fromwau.klap

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
        assertEquals(CliError.BadValue("--port", "abc", "not an integer"), err)
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
                val host = option("--host").required()
                action { Ok(host()) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial"))).error
        assertEquals(CliError.MissingRequiredOption("--host"), err)
    }

    @Test
    fun optionValueMissingIsRejected() {
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
    fun invalidChoiceIsRejected() {
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
    fun choiceMatchesCaseInsensitivelyAndReturnsTheCanonicalSpelling() {
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
    fun choiceStillRejectsAnUnknownValueCaseInsensitively() {
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
    fun choiceInvalidValueSuggestionIgnoresCase() {
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
    fun endOfOptionsIsNotConsumedAsOptionValue() {
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
    fun aDeclaredFlagAfterAValueTakingOptionIsStillThatOptionsValue() {
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

    @Test
    fun unknownCharInClusterNamesThatChar() {
        val tree = cli("net") {
            command("dial") {
                val verbose = flag("--verbose", "-v")
                action { Ok("") }
            }
        }
        // -vz: v is a known flag, z is unknown -> error names -z, not -vz.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "-vz"))).error
        assertEquals(CliError.UnknownOption("-z"), err)
    }

    @Test
    fun shortClusterEqualsAfterAFlagReportsFlagTakesNoValueNamedAsTyped() {
        // -v=x: v is a boolean flag, so the `=` is the short form of `--verbose=x`; the error must name
        // the flag exactly as the user typed it (-v), never a fabricated "-=" token and never the long
        // declared name. --verbose=x separately reports the long form it was typed as.
        val shortErr = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "-v=x"))).error
        assertEquals(CliError.FlagTakesNoValue("-v"), shortErr)
        val longErr = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "--verbose=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--verbose"), longErr)
    }

    @Test
    fun shortAndLongBooleanFlagInlineValueRenderTheFlagAsTyped() {
        val shortErr = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "-v=x"))).error
        assertEquals("flag '-v' does not take a value", shortErr.message())
        val longErr = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "--verbose=x"))).error
        assertEquals("flag '--verbose' does not take a value", longErr.message())
    }

    @Test
    fun shortClusterStrayDashReportsTheWholeTokenNotAFabricatedDoubleDash() {
        // -f-y: f is a flag, then a stray '-' that names no option; the offender must be the whole
        // original token, never the phantom "--" that "-$ch" would produce for ch = '-'.
        val err = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "-f-y"))).error
        assertEquals(CliError.UnknownOption("-f-y"), err)
    }

    @Test
    fun shortClusterUnknownLetterStillNamesJustThatChar() {
        // -fz: f is a flag, z is an unknown LETTER (not a stray non-alphanumeric char), so the
        // single-char reporting from unknownCharInClusterNamesThatChar above must still hold.
        val err = assertIs<Result.Error<CliError>>(clusterTree().parse(listOf("run", "-fz"))).error
        assertEquals(CliError.UnknownOption("-z"), err)
    }

    @Test
    fun shortOptionEqualsFormStillTakesTheAttachedValueIncludingTheEqualsSign() {
        // Documented, unchanged behavior: a short VALUE option consumes the whole attached remainder
        // before any '=' is ever reached, so -p=8080 binds the literal value "=8080".
        assertEquals("verbose=false force=false port==8080\n", clusterTree().execAndCapture(listOf("run", "-p=8080")))
    }

    @Test
    fun validateFailurePassesThroughAsBadValue() {
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
    fun validateFailureOnEnumBackedOptionYieldsBadValueNotInvalidChoice() {
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
    fun validatePassAllowsConversionThrough() {
        val tree = cli("net") {
            command("dial") {
                val mode = option("--mode").enum<Priority>().validate("must be HIGH") { it == Priority.HIGH }
                action { Ok(mode()?.name ?: "") }
            }
        }
        assertEquals("HIGH\n", tree.execAndCapture(listOf("dial", "--mode", "high")))
    }

    @Test
    fun rangeRejectsOutOfBoundsOption() {
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
    fun rangeAcceptsInBoundsOption() {
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").int().range(1..65535)
                action { Ok(port()?.toString() ?: "") }
            }
        }
        assertEquals("8080\n", tree.execAndCapture(listOf("dial", "--port", "8080")))
    }

    @Test
    fun multipleOptionMinEnforced() {
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
    fun multipleOptionMinSatisfiedAtExactCount() {
        val tree = cli("net") {
            command("call") {
                val header = option("--header", "-H").multiple(min = 2)
                action { Ok(header().joinToString(",")) }
            }
        }
        assertEquals("a,b\n", tree.execAndCapture(listOf("call", "-H", "a", "-H", "b")))
    }

    @Test
    fun multiplePositionalMinEnforced() {
        // Mirrors multipleOptionMinEnforced above: a repeatable positional short of its min reports the
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
    fun multiplePositionalMinAbsentIsStillMissingArgument() {
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
    fun repeatedTypedOptionCollectsConvertedValues() {
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
    fun repeatedTypedOptionBadElementIsRejected() {
        // A bad element on any occurrence fails the converter, surfacing as BadValue like a scalar .int().
        val tree = cli("net") {
            command("call") {
                val num = option("--num", "-n").int().multiple()
                action { Ok(num().joinToString(",")) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("call", "-n", "1", "-n", "abc"))).error
        assertEquals(CliError.BadValue("--num", "abc", "not an integer"), err)
    }

    @Test
    fun repeatedNullMappingOptionRejectsTheNullElementInsteadOfBindingIt() {
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
        assertEquals(CliError.BadValue("--num", "x", "conversion failed"), err)
    }

    @Test
    fun defaultBypassesValidationOnOption() {
        // A .default value is trusted: it binds directly and is never routed through validate.
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").int().range(1..65535).default(99999)
                action { Ok(port().toString()) }
            }
        }
        assertEquals("99999\n", tree.execAndCapture(listOf("dial")))
    }

    @Test
    fun countFlagAccumulatesClusteredOccurrences() {
        val tree = cli("net") {
            command("run") {
                val verbose = flag("--verbose", "-v").count()
                action { Ok(verbose().toString()) }
            }
        }
        assertEquals("3\n", tree.execAndCapture(listOf("run", "-vvv")))
    }

    @Test
    fun countFlagAccumulatesMixedLongShortAndClusteredOccurrences() {
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
    fun countFlagAbsentIsZero() {
        val tree = cli("net") {
            command("run") {
                val verbose = flag("--verbose", "-v").count()
                action { Ok(verbose().toString()) }
            }
        }
        assertEquals("0\n", tree.execAndCapture(listOf("run")))
    }

    @Test
    fun negatableFlagLongFormSetsTrue() {
        val tree = cli("net") {
            command("run") {
                val tint = flag("--tint").negatable(default = false)
                action { Ok(tint().toString()) }
            }
        }
        assertEquals("true\n", tree.execAndCapture(listOf("run", "--tint")))
    }

    @Test
    fun negatableFlagNoFormSetsFalse() {
        val tree = cli("net") {
            command("run") {
                val tint = flag("--tint").negatable()
                action { Ok(tint().toString()) }
            }
        }
        assertEquals("false\n", tree.execAndCapture(listOf("run", "--no-tint")))
    }

    @Test
    fun negatableFlagAbsentUsesDefault() {
        val tree = cli("net") {
            command("run") {
                val tint = flag("--tint").negatable()
                action { Ok(tint().toString()) }
            }
        }
        assertEquals("true\n", tree.execAndCapture(listOf("run")))
    }

    @Test
    fun negatableFlagLastTokenWins() {
        val tree = cli("net") {
            command("run") {
                val tint = flag("--tint").negatable()
                action { Ok(tint().toString()) }
            }
        }
        assertEquals("false\n", tree.execAndCapture(listOf("run", "--tint", "--no-tint")))
    }

    @Test
    fun unknownOptionSuggestsNearestLongName() {
        // The near-miss must not be a PREFIX of the name it suggests, here and in the sibling tests below:
        // a prefix resolves as an abbreviation and binds, so it never reaches did-you-mean at all.
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "--verbse"))).error
        assertEquals(CliError.UnknownOption("--verbse", "--verbose"), err)
    }

    @Test
    fun unknownOptionFarMissHasNoSuggestion() {
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "--zzzznope"))).error
        assertEquals(CliError.UnknownOption("--zzzznope"), err)
    }

    @Test
    fun unknownOptionShortSingleCharHasNoSuggestion() {
        // Single-char short options never get a suggestion, even next to a near-miss long flag.
        val err = assertIs<Result.Error<CliError>>(optTree().parse(listOf("call", "-z"))).error
        assertEquals(CliError.UnknownOption("-z"), err)
    }

    @Test
    fun globalFlagReadableFromNestedSubcommandAction() {
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
    fun globalFlagParsesBeforeAndAfterTheSubcommandPath() {
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
    fun clusteredGlobalShortFlagsAreEachRecognized() {
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

    @Test
    fun clusterPeelsGlobalsAndLeavesTheLocalRemainder() {
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
    fun mixedClusterBindsBothRegardlessOfOrder() {
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
    fun mixedClusterLocalFlagThenGlobalOptionConsumesValue() {
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
    fun mixedClusterGlobalFlagThenLocalOptionConsumesAttachedValue() {
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
    fun mixedClusterStillRejectsATrulyUnknownChar() {
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("build") {
                flag("--force", "-f")
                action { Ok("") }
            }
        }
        // -fvz: f local, v global, z unknown -> the error still names the offending char, -z.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "-fvz"))).error
        assertEquals(CliError.UnknownOption("-z"), err)
    }

    @Test
    fun groupClusterErrorNamesFirstOffendingCharLikeALeaf() {
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
    fun groupRootLongOptionTypoSuggestsTheVersionBuiltin() {
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
    fun groupRootLongOptionTypoSuggestsAGlobalFlag() {
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--verbse"))).error
        assertEquals(CliError.UnknownOption("--verbse", "--verbose"), err)
    }

    @Test
    fun invalidChoiceSuggestsNearestChoice() {
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
    fun subcommandAfterSeparatorIsNamedNotReportedUnknown() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
        }
        // `--` ends command parsing, so `build` after it is an operand, not a route; the error names the
        // misplacement instead of claiming the real command is unknown.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--", "build"))).error
        assertEquals(CliError.SubcommandAfterSeparator("build", "app"), err)
    }

    @Test
    fun tooManyArgumentsNoLongerSuggestsDocsOnASingleCommandTool() {
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
    fun tooManyArgumentsSuggestsANearbySubcommandOnAHybridCommand() {
        val tree = cli("app") {
            action { Ok("") }
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("biuld"))).error
        assertEquals(CliError.TooManyArguments("app", listOf("biuld"), "build"), err)
    }

    @Test
    fun globalOptionWithConverterAndDefaultResolves() {
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
    fun requiredGlobalEnforcedOnLeafExecuteButNotOnBareGroupHelp() {
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
    fun requiredAtLeastOnceGlobalDefersOnHelpButErrorsOnLeafExecute() {
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
    fun danglingGlobalOptionReportsMissingValueNotUnknownOption() {
        val tree = cli("app") {
            val workspace = globalOption("--workspace", "-w")
            command("plan") { action { Ok(workspace() ?: "") } }
        }
        val longErr = assertIs<Result.Error<CliError>>(tree.parse(listOf("plan", "--workspace"))).error
        assertEquals(CliError.MissingOptionValue("--workspace"), longErr)
        val shortErr = assertIs<Result.Error<CliError>>(tree.parse(listOf("plan", "-w"))).error
        assertEquals(CliError.MissingOptionValue("--workspace"), shortErr)
    }

    @Test
    fun localBooleanFlagRejectsInlineValue() {
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
    fun negatableFlagInlineValueSuggestsTheNegation() {
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
    fun negatedInlineValueReportsTheNoFormWithoutANegationHint() {
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
    fun globalBooleanFlagRejectsInlineValue() {
        val tree = cli("app") {
            val debug = globalFlag("--debug")
            command("run") { action { Ok(debug().toString()) } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("run", "--debug=1"))).error
        assertEquals(CliError.FlagTakesNoValue("--debug"), err)
    }

    @Test
    fun globalShortFlagClusterEqualsValueReportsFlagTakesNoValueBeforeAndAfterTheSubcommand() {
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
    fun globalShortFlagClusterEqualsValueOnAnExplicitNegativeShortReportsFlagTakesNoValue() {
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
    fun globalShortOptionEqualsFormStillTakesTheAttachedValueBeforeTheSubcommand() {
        // A global VALUE option's short attached form is untouched by the FlagTakesNoValue fix above:
        // -o=val binds the literal "=val", same documented behavior as a local option (see
        // shortOptionEqualsFormStillTakesTheAttachedValueIncludingTheEqualsSign).
        val tree = cli("myapp") {
            val out = globalOption("--output", "-o")
            command("flags") { action { Ok(out() ?: "") } }
        }
        assertEquals("=val\n", tree.execAndCapture(listOf("-o=val", "flags")))
    }

    @Test
    fun unknownOptionNeverSuggestsAHiddenLocalOption() {
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
    fun unknownOptionNeverSuggestsAHiddenGlobalOption() {
        val tree = cli("app") {
            globalOption("--global-secret").hidden()
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--global-secrett"))).error
        assertEquals(CliError.UnknownOption("--global-secrett"), err)
    }

    @Test
    fun unknownOptionSuggestsAGlobalFlag() {
        val tree = cli("app") {
            globalFlag("--verbose", "-v")
            command("build") { action { Ok("") } }
        }
        // The candidate set for did-you-mean must include globals, not just this command's own locals.
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--verbse"))).error
        assertEquals(CliError.UnknownOption("--verbse", "--verbose"), err)
    }

    @Test
    fun unknownOptionSuggestsTheHelpBuiltin() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--hepl"))).error
        assertEquals(CliError.UnknownOption("--hepl", "--help"), err)
    }

    @Test
    fun unknownOptionSuggestsTheVersionBuiltinWhenRootIsVersioned() {
        val tree = cli("app") {
            version = "1.0.0"
            command("build") { action { Ok("") } }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("build", "--versoin"))).error
        assertEquals(CliError.UnknownOption("--versoin", "--version"), err)
    }

    @Test
    fun helpBuiltinGivenAnInlineValueTakesNoValue() {
        val tree = cli("x") {
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--help=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--help", null), err)
    }

    @Test
    fun jsonBuiltinGivenAnInlineValueTakesNoValue() {
        val tree = cli("x") {
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--json=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--json", null), err)
    }

    @Test
    fun versionBuiltinGivenAnInlineValueTakesNoValueWhenVersioned() {
        val tree = cli("x") {
            version = "1.0"
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--version=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--version", null), err)
    }

    @Test
    fun versionEqualsIsStillUnknownOptionWhenNotVersioned() {
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
    fun helpShortFormGivenAnInlineValueTakesNoValue() {
        val tree = cli("x") {
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("-h=x"))).error
        assertEquals(CliError.FlagTakesNoValue("--help", null), err)
    }

    @Test
    fun helpBuiltinGivenAnEmptyInlineValueTakesNoValue() {
        val tree = cli("x") {
            argument("a")
            action { Ok("") }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("--help="))).error
        assertEquals(CliError.FlagTakesNoValue("--help", null), err)
    }

    // --- "?: default" substitution semantics on options ---

    @Test
    fun optionDefaultNonNull_stillNarrows_absentUsesDefault_presentUsesValue() {
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
    fun optionMapToNullDefault_substitutesDefaultInsteadOfNpeOnBadInput() {
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
    fun optionIntDefault_badValueStillErrorsInsteadOfBeingMaskedByDefault() {
        // A converter ERROR (not a null success) must still surface as BadValue, never silently defaulted.
        val tree = cli("net") {
            command("dial") {
                val port = option("--port").int().default(0)
                action { Ok(port().toString()) }
            }
        }
        val err = assertIs<Result.Error<CliError>>(tree.parse(listOf("dial", "--port", "abc"))).error
        assertEquals(CliError.BadValue("--port", "abc", "not an integer"), err)
        assertEquals("5\n", tree.execAndCapture(listOf("dial", "--port", "5")))
        assertEquals("0\n", tree.execAndCapture(listOf("dial")))
    }

    @Test
    fun endOfOptionsMakesABuiltinLookingTokenAPositionalNotATakesNoValueError() {
        // A built-in-looking token AFTER the `--` end-of-options marker is an unconditional positional
        // (same POSIX rule as endOfOptionsRoutesFlagShapedTokenToPositionalOnGroup above), so it must
        // never be misread as --help's takes-no-value form, nor trigger help.
        val tree = cli("x") {
            val a = argument("a")
            action { Ok(a()) }
        }
        val inv = assertIs<Result.Success<Invocation>>(tree.parse(listOf("--", "--help=x"))).value
        assertIs<Invocation.Execute>(inv)
        assertEquals("--help=x\n", tree.execAndCapture(listOf("--", "--help=x")))
    }

    // --- converter/validate chains must never throw at parse (never-throw contract) ---

    @Test
    fun nullableMapThenValidateOnBadInputSkipsValidateInsteadOfCrashing() {
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
    fun nullableMapFeedingNullIntoALaterStringStageYieldsBadValueNotACrash() {
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

    @Test
    fun shortOnlyOptionIsDeclarableAndParses() {
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
    fun shortOnlyOptionHasNoLongForm() {
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
    fun shortOnlyOptionIsNotSuggestedAsALongForm() {
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

    @Test
    fun oneFlagAnswersToEverySpelling() {
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
    fun oneOptionAnswersToEverySpelling() {
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
    fun everyLongSpellingOfANegatableFlagGeneratesItsOwnNegation() {
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
    fun errorTextNamesAShortOnlyOptionByTheTokenThatActuallyWorks() {
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
    fun errorTextNamesTheOptionsPrimarySpellingNotItsShort() {
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
    fun theLongFormMissingValueErrorKeepsItsDashes() {
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
    fun theClusterNegationHintNamesTheFlagsRealLongForm() {
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
    fun aSecondaryShortClustersLikeThePrimaryOne() {
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
    fun mixedClusterBeforeTheNegationLosesToIt() {
        // The bug: `-fv` is resolved by the later pass, but sits EARLIER in argv, so `--no-verbose` wins.
        assertEquals("v=false f=true\n", negatableGlobalTree().execAndCapture(listOf("go", "-fv", "--no-verbose")))
    }

    @Test
    fun mixedClusterAfterTheNegationBeatsIt() {
        // The mirror, and why "skip an already-set polarity" is not the fix: here the cluster IS last.
        assertEquals("v=true f=true\n", negatableGlobalTree().execAndCapture(listOf("go", "--no-verbose", "-fv")))
    }

    @Test
    fun longFormOrderingIsUnchanged() {
        val tree = negatableGlobalTree()
        assertEquals("v=false f=false\n", tree.execAndCapture(listOf("go", "--verbose", "--no-verbose")))
        assertEquals("v=true f=false\n", tree.execAndCapture(listOf("go", "--no-verbose", "--verbose")))
    }

    @Test
    fun pureGlobalClusterOrderingIsUnchanged() {
        // `-v` alone is fully global, so the pre-strip resolves it in the same pass as `--no-verbose`.
        val tree = negatableGlobalTree()
        assertEquals("v=false f=false\n", tree.execAndCapture(listOf("go", "-v", "--no-verbose")))
        assertEquals("v=true f=false\n", tree.execAndCapture(listOf("go", "--no-verbose", "-v")))
        assertEquals("v=false f=false\n", tree.execAndCapture(listOf("-v", "go", "--no-verbose")))
    }

    @Test
    fun negationAloneAndAbsenceAreUnchanged() {
        val tree = negatableGlobalTree()
        assertEquals("v=false f=false\n", tree.execAndCapture(listOf("go", "--no-verbose")))
        assertEquals("v=true f=false\n", tree.execAndCapture(listOf("go")))
    }

    @Test
    fun negationStrippedFromAheadOfTheSubcommandStillOrdersAgainstTheCluster() {
        // The negation is consumed before the subcommand token, so it has no index in the leaf's segment at
        // all — only the raw argv index the pre-strip recorded can place it against the cluster.
        val tree = negatableGlobalTree()
        assertEquals("v=true f=true\n", tree.execAndCapture(listOf("--no-verbose", "go", "-fv")))
        // The reverse cannot be written with a MIXED cluster: `-fv` names the leaf's own short, so ahead of
        // the subcommand it stops the walk and is a usage error before any polarity is resolved. Its
        // pure-global form is covered by pureGlobalClusterOrderingIsUnchanged below.
        assertIs<Result.Error<CliError>>(tree.parse(listOf("-fv", "go", "--no-verbose")))
    }

    @Test
    fun negatableLocalFlagIsUnaffectedInEveryOrder() {
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
    fun countGlobalInAMixedClusterStillCounts() {
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
    fun negatableGlobalWithNoShortIsUnchanged() {
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
    fun negatableGlobalWhoseShortNeverClustersIsUnchanged() {
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
    fun aDashLedTokenBindsAsAnOptionValue() {
        assertEquals("-weird\n", messageTree().execAndCapture(listOf("-m", "-weird")))
        assertEquals("--verbose\n", messageTree().execAndCapture(listOf("--message", "--verbose")))
    }

    @Test
    fun aDashLedTokenBindsThroughAShortClusterToo() {
        val tree = cli("app") {
            val verbose = flag("--verbose", "-v")
            val message = option("--message", "-m").required()
            action { Ok("v=${verbose()} m=${message()}") }
        }
        assertEquals("v=true m=-x\n", tree.execAndCapture(listOf("-vm", "-x")))
    }

    @Test
    fun aDashLedTokenBindsToAGlobalBeforeTheSubcommandResolves() {
        val tree = cli("app") {
            val message = globalOption("--message", "-m").required()
            command("go") { action { Ok(message()) } }
        }
        assertEquals("--verbose\n", tree.execAndCapture(listOf("--message", "--verbose", "go")))
        assertEquals("-x\n", tree.execAndCapture(listOf("-m", "-x", "go")))
    }

    @Test
    fun aTrailingValueLessOptionStillReportsMissingValue() {
        // The one case the greedy rule must NOT swallow: there is no next token at all.
        val err = assertIs<Result.Error<CliError>>(messageTree().parse(listOf("--message"))).error
        assertEquals(CliError.MissingOptionValue("--message"), err)
    }

    @Test
    fun endOfOptionsStillTerminatesRatherThanBindingAsAValue() {
        // `--` is structural, not a value: `app --message -- x` must not bind "--" as the message.
        val err = assertIs<Result.Error<CliError>>(messageTree().parse(listOf("--message", "--"))).error
        assertEquals(CliError.MissingOptionValue("--message"), err)
    }

    @Test
    fun anUnknownOptionAfterAValueTakingOneIsSwallowedAsItsValue() {
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
    fun aLongGlobalInALocalOptionsValueSlotIsThatOptionsValue() {
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
    fun aFullyGlobalShortClusterInAValueSlotIsTheOptionsLiteralValue() {
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
    fun aGlobalStillTakesItsOwnValueOutsideASlotAtEveryDepth() {
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
    fun aNegatableGlobalsNegationSpellingsAreOrdinaryValuesInASlot() {
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
    fun aGlobalOutsideAValueSlotStillBindsPastAnOperandAndPastTheSwitch() {
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
    fun aDigitShortIsDeclarableAndParses() {
        val tree = cli("curl") {
            val ipv4 = flag("-4", help = "resolve names to IPv4 addresses only")
            val ipv6 = flag("-6", help = "resolve names to IPv6 addresses only")
            action { Ok("4=${ipv4()} 6=${ipv6()}") }
        }
        assertEquals("4=true 6=false\n", tree.execAndCapture(listOf("-4")))
        assertEquals("4=true 6=true\n", tree.execAndCapture(listOf("-4", "-6")))
    }

    @Test
    fun anUndeclaredDigitTokenIsAnUnknownOptionRatherThanAnOperand() {
        // Real `ls -5` and `sleep -1` both reject, and so does klap: a dash-led token is an option token
        // whatever follows the dash, and only a declaration makes it mean anything.
        val tree = cli("app") {
            val n = argument("n").int()
            action { Ok(n().toString()) }
        }
        assertEquals(CliError.UnknownOption("-1"), assertIs<Result.Error<CliError>>(tree.parse(listOf("-100"))).error)
    }

    @Test
    fun aNegativeNumberOperandIsWrittenAfterTheEndOfOptionsMarker() {
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
    fun aDeclaredDigitShortIsJustAnotherClusterChar() {
        val tree = cli("app") {
            val one = flag("-1", help = "one file per line")
            val rest = argument("n").multiple(min = 0)
            action { Ok("1=${one()} rest=${rest()}") }
        }
        assertEquals("1=true rest=[]\n", tree.execAndCapture(listOf("-1")))
        assertEquals(CliError.UnknownOption("-2"), assertIs<Result.Error<CliError>>(tree.parse(listOf("-2"))).error)
    }

    @Test
    fun aGlobalDigitShortIsStrippedBeforeTheSubcommandResolves() {
        val tree = cli("app") {
            val ipv4 = globalFlag("-4", help = "IPv4 only")
            command("go") { action { Ok(ipv4().toString()) } }
        }
        assertEquals("true\n", tree.execAndCapture(listOf("-4", "go")))
        assertEquals("true\n", tree.execAndCapture(listOf("go", "-4")))
    }

    @Test
    fun aDigitShortStillClustersWithItsNeighbours() {
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
    fun aNumericAliasBindsThroughTheOptionItAliases() {
        assertEquals("5:[f]\n", headTree().execAndCapture(listOf("-5", "f")))
        assertEquals("5:[f]\n", headTree().execAndCapture(listOf("-n", "5", "f")))
        // Any N, not a fixed set of declared shorts.
        assertEquals("20:[f]\n", headTree().execAndCapture(listOf("-20", "f")))
    }

    @Test
    fun aNumericAliasFeedsTheOptionsOwnConverterAndValidation() {
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
    fun aDeclaredDigitShortWinsOverTheNumericAlias() {
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
    fun aDigitTokenIsAnUnknownOptionWithoutANumericAlias() {
        // Real `ls -5` and `sleep -1` both reject. A tree that declares no numeric alias must too, rather
        // than silently binding a file named "-5".
        val tree = cli("ls") {
            val files = argument("file").multiple(min = 0)
            action { Ok(files().toString()) }
        }
        assertEquals(CliError.UnknownOption("-5"), assertIs<Result.Error<CliError>>(tree.parse(listOf("-5"))).error)
    }

    @Test
    fun aNumericAliasClaimsOnlyAnAllDigitToken() {
        // `-5x` is not a number, so the alias must not take it; it stays a short cluster.
        assertEquals(
            CliError.UnknownOption("-5"),
            assertIs<Result.Error<CliError>>(headTree().parse(listOf("-5x"))).error,
        )
    }

    @Test
    fun theAliasedOptionsHelpRowAdvertisesTheNumericForm() {
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
    fun theAttachedFormBindsItsOwnValue() {
        assertEquals("color=never files=[]\n", run("--color=never"))
    }

    @Test
    fun aBareOccurrenceBindsTheDeclaredBareValue() {
        assertEquals("color=always files=[]\n", run("--color"))
    }

    @Test
    fun theSpaceFormLeavesTheNextTokenAsAnOperand() {
        // The rule guideline 7 exists for: an optional-value option cannot tell its value from the next
        // operand, so it never takes one. GNU does the same, and `ls --color src` colours `src`'s listing.
        assertEquals("color=always files=[src]\n", run("--color", "src"))
    }

    @Test
    fun anAbsentOccurrenceStillBindsNull() {
        assertEquals("color=null files=[f]\n", run("f"))
    }

    @Test
    fun aShortFormBindsAttachedAndBareTheSameWay() {
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
    fun theBareValueRunsThroughTheOptionsOwnConverter() {
        val tree = cli("app") {
            val depth = option("--depth").optionalValue("1").int()
            action { Ok(depth().toString()) }
        }
        assertEquals("1\n", tree.execAndCapture(listOf("--depth")))
        assertEquals("5\n", tree.execAndCapture(listOf("--depth=5")))
    }

    @Test
    fun aBadAttachedValueIsStillRejected() {
        val tree = cli("app") {
            val depth = option("--depth").optionalValue("1").int()
            action { Ok(depth().toString()) }
        }
        assertEquals(
            CliError.BadValue("--depth", "abc", "not an integer"),
            assertIs<Result.Error<CliError>>(tree.parse(listOf("--depth=abc"))).error,
        )
    }

    @Test
    fun anOptionWithoutTheOptInStillDemandsItsValue() {
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
    fun aBareOccurrenceAtTheEndOfArgvBinds() {
        // A bare occurrence at the end of argv has no next token to reach for at all.
        assertEquals("color=always files=[f]\n", run("f", "--color"))
    }

    @Test
    fun aGlobalOptionalValueOptionBehavesTheSameBeforeAndAfterTheSubcommand() {
        val tree = cli("git") {
            val execPath = globalOption("--exec-path", "-e").optionalValue("/usr/lib/git-core")
            command("log") { action { Ok(execPath() ?: "none") } }
        }
        assertEquals("/usr/lib/git-core\n", tree.execAndCapture(listOf("--exec-path", "log")))
        assertEquals("/usr/lib/git-core\n", tree.execAndCapture(listOf("log", "--exec-path")))
        assertEquals("/opt/git\n", tree.execAndCapture(listOf("--exec-path=/opt/git", "log")))
    }

    @Test
    fun aGlobalOptionalValueOptionBindsThroughTheShortClusterPath() {
        val tree = cli("git") {
            val execPath = globalOption("--exec-path", "-e").optionalValue("/usr/lib/git-core")
            command("log") { action { Ok(execPath() ?: "none") } }
        }
        // A bad advance here would swallow "log", the token right after the bare short global.
        assertEquals("/usr/lib/git-core\n", tree.execAndCapture(listOf("-e", "log")))
        assertEquals("/opt/git\n", tree.execAndCapture(listOf("-e/opt/git", "log")))
    }
}
