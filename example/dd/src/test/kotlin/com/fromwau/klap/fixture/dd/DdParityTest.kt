package com.fromwau.klap.fixture.dd

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class DdParityTest {

    private val parity = ParitySuite(ddCli())

    @Test
    fun bindsKeyValueOperandsInAnyOrder() {
        parity.binds(
            "if=/dev/zero", "of=out.img", "bs=4M", "count=10", "status=progress",
            expected = NOTHING_BOUND.copy(
                operands = listOf(
                    DdOperand("if", "/dev/zero"),
                    DdOperand("of", "out.img"),
                    DdOperand("bs", "4M"),
                    DdOperand("count", "10"),
                    DdOperand("status", "progress"),
                ),
            ),
        )
        // Order-independent in real dd, and the single anonymous slot is what makes that hold here.
        parity.binds(
            "count=1", "if=x",
            expected = NOTHING_BOUND.copy(operands = listOf(DdOperand("count", "1"), DdOperand("if", "x"))),
        )
        // A repeated key is kept twice; last-wins is the action's `associate`, not the parse's.
        parity.binds(
            "if=a", "if=b",
            expected = NOTHING_BOUND.copy(operands = listOf(DdOperand("if", "a"), DdOperand("if", "b"))),
        )
        parity.binds(
            "conv=ucase,notrunc", "iflag=direct,nocache", "oflag=append",
            expected = NOTHING_BOUND.copy(
                operands = listOf(
                    DdOperand("conv", "ucase,notrunc"),
                    DdOperand("iflag", "direct,nocache"),
                    DdOperand("oflag", "append"),
                ),
            ),
        )
        parity.binds(
            "cbs=512", "ibs=1024", "obs=2048", "seek=1", "skip=2", "oseek=3", "iseek=4",
            expected = NOTHING_BOUND.copy(
                operands = listOf(
                    DdOperand("cbs", "512"),
                    DdOperand("ibs", "1024"),
                    DdOperand("obs", "2048"),
                    DdOperand("seek", "1"),
                    DdOperand("skip", "2"),
                    DdOperand("oseek", "3"),
                    DdOperand("iseek", "4"),
                ),
            ),
        )
        // A dd number is digits plus an optional multiplicative suffix.
        parity.binds(
            "count=10B", "bs=64KiB", "ibs=2x1024",
            expected = NOTHING_BOUND.copy(
                operands = listOf(
                    DdOperand("count", "10B"),
                    DdOperand("bs", "64KiB"),
                    DdOperand("ibs", "2x1024"),
                ),
            ),
        )
        // `dd </dev/null` is a real invocation: zero operands, read stdin, write stdout.
        parity.binds(expected = NOTHING_BOUND)
    }

    @Test
    fun rejectsWhatRealDdRejects() {
        parity.rejects("--zzz", because = "real dd: unrecognized option '--zzz'")
        parity.rejects("bogus=1", because = "real dd: unrecognized operand 'bogus=1'")
        parity.rejects("count", because = "real dd: unrecognized operand 'count'")
        parity.rejects("=", because = "real dd: unrecognized operand '='")
        parity.rejects("count=x", because = "real dd: invalid number: 'x'")
        parity.rejects("bs=", because = "real dd: invalid number: ''")
        parity.rejects("status=bogus", because = "real dd: invalid status level: 'bogus'")
        parity.rejects("conv=bogus", because = "real dd: invalid conversion: 'bogus'")
        parity.rejects("iflag=bogus", because = "real dd: invalid input flag: 'bogus'")
        parity.rejects("oflag=bogus", because = "real dd: invalid output flag: 'bogus'")
    }

    @Test
    fun klapAcceptsWhatRealDdRejects() {
        // The missing named-operand shape only costs help rows and error wording, not whether a token
        // parses, so it has no accept/reject line of its own here.

        // `builtins { }` could decline json/color/completion/docs and free their names; this fixture
        // declines nothing, so klap still answers dash-led tokens on a tool that has none but --help/--version.
        parity.bindsLoosely(
            "--json", "if=x",
            because = "real dd: unrecognized operand '--json'",
            expected = NOTHING_BOUND.copy(operands = listOf(DdOperand("if", "x"))),
        )
        parity.bindsLoosely(
            "--color=never", "if=x",
            because = "real dd: unrecognized option '--color=never'",
            expected = NOTHING_BOUND.copy(operands = listOf(DdOperand("if", "x"))),
        )
        parity.shortCircuits("--help-all", "if=x", because = "real dd: unrecognized option '--help-all'")
        parity.shortCircuits("-h", "if=x", because = "real dd: invalid option -- 'h'")
        parity.shortCircuits("--completion", "bash", because = "real dd: unrecognized option '--completion'")
        parity.shortCircuits("--docs", "markdown", because = "real dd: unrecognized option '--docs'")
        parity.shortCircuits("__complete", "if=", because = "real dd: unrecognized operand '__complete'")
    }
}
