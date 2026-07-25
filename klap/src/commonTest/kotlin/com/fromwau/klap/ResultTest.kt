package com.fromwau.klap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

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
    fun fold_selectsSuccessBranch() {
        val ok: Result<Int, String> = Result.Success(3)
        assertEquals("ok:3", ok.fold({ "ok:$it" }, { "err:$it" }))
    }

    @Test
    fun fold_selectsErrorBranch() {
        val err: Result<Int, String> = Result.Error("x")
        assertEquals("err:x", err.fold({ "ok:$it" }, { "err:$it" }))
    }

    @Test
    fun mapError_transformsError() {
        val r: Result<Int, String> = Result.Error("bad")
        assertEquals(Result.Error(3), r.mapError { it.length })
    }

    @Test
    fun mapError_passesThroughSuccess() {
        val r: Result<Int, String> = Result.Success(2)
        assertEquals(Result.Success(2), r.mapError { it.length })
    }

    @Test
    fun getOrElse_returnsValueOnSuccess() {
        val r: Result<Int, String> = Result.Success(7)
        assertEquals(7, r.getOrElse { -1 })
    }

    @Test
    fun onSuccess_runsActionAndReturnsReceiverOnSuccess() {
        var seen: Int? = null
        val r: Result<Int, String> = Result.Success(5)
        val returned = r.onSuccess { seen = it }
        assertEquals(5, seen)
        assertSame(r, returned)
    }

    @Test
    fun onSuccess_skipsActionOnError() {
        var ran = false
        val r: Result<Int, String> = Result.Error("bad")
        val returned = r.onSuccess { ran = true }
        assertFalse(ran)
        assertSame(r, returned)
    }

    @Test
    fun onError_runsActionAndReturnsReceiverOnError() {
        var seen: String? = null
        val r: Result<Int, String> = Result.Error("bad")
        val returned = r.onError { seen = it }
        assertEquals("bad", seen)
        assertSame(r, returned)
    }

    @Test
    fun onError_skipsActionOnSuccess() {
        var ran = false
        val r: Result<Int, String> = Result.Success(5)
        val returned = r.onError { ran = true }
        assertFalse(ran)
        assertSame(r, returned)
    }

    @Test
    fun ok_wrapsValueAsSuccess() {
        assertEquals(Result.Success(5), Ok(5))
    }

    @Test
    fun err_wrapsErrorAsFailure() {
        assertEquals(Result.Error("boom"), Err("boom"))
    }
}
