package com.fromwau.klap.fixture.mkdir

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

class MkdirParityTest {

    private val parity = ParitySuite(mkdirCli())

    @Test
    fun bindsFlagsOptionsAndOperands() {
        parity.binds("-p", "a/b/c", expected = NOTHING_BOUND.copy(parents = true, directories = listOf("a/b/c")))
        parity.binds("--mode", "755", "d", expected = NOTHING_BOUND.copy(mode = "755", directories = listOf("d")))
        // -Z has no long form, so this is the whole spelling real mkdir offers for it.
        parity.binds("-Z", "d", expected = NOTHING_BOUND.copy(selinux = true, directories = listOf("d")))
        parity.binds(
            "-pv", "one", "two", "three",
            expected = NOTHING_BOUND.copy(
                parents = true,
                verbose = true,
                directories = listOf("one", "two", "three"),
            ),
        )
        parity.binds(
            "--context=unconfined_u:object_r:user_home_t:s0", "d",
            expected = NOTHING_BOUND.copy(
                context = "unconfined_u:object_r:user_home_t:s0",
                directories = listOf("d"),
            ),
        )
        // Bare `--context` binds "default", and the space form leaves `d` as the operand, matching real mkdir.
        parity.binds("--context", "d", expected = NOTHING_BOUND.copy(context = "default", directories = listOf("d")))
        // `--par` is an unambiguous abbreviation of `--parents`, matching real mkdir.
        parity.binds("--par", "d", expected = NOTHING_BOUND.copy(parents = true, directories = listOf("d")))
    }

    @Test
    fun rejectsWhatRealMkdirRejects() {
        parity.rejects("--zzz", because = "real mkdir: unrecognized option '--zzz'")
        parity.rejects(because = "real mkdir: missing operand")
        parity.rejects("--mode", because = "real mkdir: option '--mode' requires an argument")
        parity.rejects("-m", "999", "d", because = "real mkdir: invalid mode '999'")
        parity.rejects("--context", because = "real mkdir: missing operand, its optional value having taken nothing")
    }

    @Test
    fun knownDivergenceFromRealMkdir() {
        // The dash-led value reaches `mode`; this fixture's octal-only validator rejects it here, though
        // real mkdir would accept it as a symbolic mode and create `d`.
        parity.rejects("--mode", "-w", "d", because = "this fixture's octal-only mode, NOT real-mkdir behaviour")
        parity.rejects("-m", "-w", "d", because = "this fixture's octal-only mode, NOT real-mkdir behaviour")
    }
}
