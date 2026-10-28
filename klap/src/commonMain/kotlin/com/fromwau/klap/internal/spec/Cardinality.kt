package com.fromwau.klap.internal.spec

/** How many values a holder takes and what happens when it is absent. */
internal sealed interface Cardinality {
    data object Required : Cardinality
    data object Optional : Cardinality
    data class Default(val value: Any?) : Cardinality

    /** [default] binds when no value is given, so it is only ever set with a [min] of zero. */
    data class Multiple(
        val min: Int,
        val max: Int = Int.MAX_VALUE,
        val default: List<Any?>? = null,
    ) : Cardinality
}