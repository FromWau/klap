package com.fromwau.klap.internal.spec

/** How many values a holder takes and what happens when it is absent. */
internal sealed interface Cardinality {
    data object Required : Cardinality
    data object Optional : Cardinality
    data class Default(val value: Any?) : Cardinality
    data class Multiple(val min: Int) : Cardinality
}