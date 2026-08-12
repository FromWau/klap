package com.fromwau.klap.internal.spec

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs [block] on the calling thread for klap's synchronous entry points.
 *
 * Only `kotlin.coroutines` is used, which is what keeps klap free of a `kotlinx-coroutines` dependency: a
 * block that never reaches a suspension point finishes before `startCoroutine` returns, so its result is
 * already there.
 *
 * A block that genuinely suspends cannot be completed here, because no scope exists to resume it. That is
 * unreachable through the public API, since the entry points refuse a suspending action before reaching
 * this, so it fails as an internal error rather than hanging.
 */
internal fun <T> completeWithoutSuspending(block: suspend () -> T): T {
    var outcome: kotlin.Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    val completed = outcome ?: error("klap internal: a suspending action reached a synchronous entry point")
    return completed.getOrThrow()
}
