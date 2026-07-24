package com.fromwau.klap

/** Whether a spec is filled from a positional slot, a --long/-s option, or a boolean flag. */
internal enum class InputKind { ARGUMENT, OPTION, FLAG }

/** How many values a holder takes and what happens when it is absent. */
internal sealed interface Cardinality {
    data object Required : Cardinality
    data object Optional : Cardinality
    data class Default(val value: Any?) : Cardinality
    data class Multiple(val min: Int) : Cardinality
}

/** The model behind a holder: one mutable object shared by reference — transformers mutate it, the parser fills [boundValue], the accessor reads it. */
@PublishedApi
internal class HolderSpec(
    val name: String,
    val short: String?,
    val help: String,
    val kind: InputKind,
    var convert: (String) -> Result<Any?, String>,
    var cardinality: Cardinality,
    var choices: List<String>?,
    var isPath: Boolean,
) {
    private var bound = false
    private var boundValue: Any? = null

    fun bind(value: Any?) {
        boundValue = value
        bound = true
    }

    fun read(): Any? {
        check(bound) { "holder '$name' read before parse" }
        return boundValue
    }
}

class Arg<T> @PublishedApi internal constructor(@PublishedApi internal val spec: HolderSpec) {
    @Suppress("UNCHECKED_CAST")
    operator fun invoke(): T = spec.read() as T
}

class Opt<T> @PublishedApi internal constructor(@PublishedApi internal val spec: HolderSpec) {
    @Suppress("UNCHECKED_CAST")
    operator fun invoke(): T = spec.read() as T
}

class Flag internal constructor(internal val spec: HolderSpec) {
    operator fun invoke(): Boolean = spec.read() as Boolean
}
