package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals

class ResultTest {

    @Test
    fun map_transformsSuccess() {
        val r: Result<Int, String> = Result.Success(2)
        assertEquals(Result.Success(4), r.map { it * 2 })
    }

    @Test
    fun map_passesThroughError() {
        val r: Result<Int, String> = Result.Error("bad")
        assertEquals(Result.Error("bad"), r.map { it * 2 })
    }

    @Test
    fun getOrElse_usesFallbackOnError() {
        val r: Result<Int, String> = Result.Error("bad")
        assertEquals(-1, r.getOrElse { -1 })
    }

    @Test
    fun fold_selectsBranch() {
        val ok: Result<Int, String> = Result.Success(3)
        val err: Result<Int, String> = Result.Error("x")
        assertEquals("ok:3", ok.fold({ "ok:$it" }, { "err:$it" }))
        assertEquals("err:x", err.fold({ "ok:$it" }, { "err:$it" }))
    }
}
