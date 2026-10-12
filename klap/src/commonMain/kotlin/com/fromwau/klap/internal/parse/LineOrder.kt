package com.fromwau.klap.internal.parse

import com.fromwau.kern.result.Result
import com.fromwau.klap.CliError
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.OptionSpec

/**
 * One flag or option occurrence as written: a flag's polarity [on], or an option's unconverted [raw] (null for a
 * bare `.optionalValue()` occurrence). A null [at] is a position no pass tracked.
 */
internal class Written(
    val spec: HolderSpec,
    val at: ClusterPosition?,
    val raw: String?,
    val on: Boolean,
)

/**
 * Every flag and option occurrence of one line in the order it was written, read by
 * [com.fromwau.klap.ValueScope.inOrder]. Values are converted only when read, with [inferValues] resolving a
 * choice prefix as the bind did.
 */
internal class Line(
    val written: List<Written>,
    val inferValues: Boolean,
) {
    /** What [written] binds as one occurrence: a flag's polarity, or an option's value unless it fails to convert. */
    fun valueOf(written: Written): Result<Any?, CliError> {
        val option = written.spec as? OptionSpec ?: return Result.Success(written.on)
        return option.convertOccurrence(written.raw, inferValues)
    }

    companion object {
        val EMPTY: Line = Line(emptyList(), inferValues = false)
    }
}

/** The bound values of one line beside its [line] order, as a [com.fromwau.klap.CompletionScope] reads them. */
internal class BoundLine(
    val values: Map<HolderSpec, Any?>,
    val line: Line,
)

/** [sifted]'s and [globals]' occurrences in line order; an unpositioned one comes after every positioned one. */
internal fun lineOrder(
    sifted: Sifted,
    globals: GlobalSift,
    inferValues: Boolean,
): Line {
    val flags = (sifted.flagHits + globals.flagHits).map { Written(it.spec, it.at, raw = null, on = it.on) }
    val local = sifted.options.flatMap { (spec, raws) ->
        raws.zip(sifted.optionValuePositions[spec].orEmpty()) { raw, at -> Written(spec, at, raw, on = true) }
    }
    val global = globals.options.flatMap { (spec, occurrences) ->
        occurrences.map { Written(spec, it.position, it.value, on = true) }
    }
    val written = (flags + local + global).sortedWith(compareBy(nullsLast()) { it.at })
    return Line(written, inferValues)
}
