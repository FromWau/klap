package com.fromwau.klap.internal.platform

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get

/** The strings of a C array of [entries] ended by a null pointer, as libc's `environ` is. */
@OptIn(ExperimentalForeignApi::class)
internal fun <T : CPointed> entriesOf(
    entries: CPointer<CPointerVar<T>>?,
    decode: (CPointer<T>) -> String,
): Sequence<String> = sequence {
    if (entries == null) return@sequence
    var index = 0
    while (true) yield(decode(entries[index++] ?: break))
}
