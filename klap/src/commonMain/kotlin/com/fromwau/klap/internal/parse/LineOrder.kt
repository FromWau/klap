package com.fromwau.klap.internal.parse

import com.fromwau.kern.result.Result
import com.fromwau.klap.CliError
import com.fromwau.klap.internal.spec.ArgumentSpec
import com.fromwau.klap.internal.spec.HolderSpec
import com.fromwau.klap.internal.spec.OptionSpec

/**
 * One flag, option or operand occurrence as written: a flag's polarity [on], or an option's or operand's unconverted
 * [raw] (null for a bare `.optionalValue()` occurrence). A null [at] is a position no pass tracked.
 */
internal class Written(
    val spec: HolderSpec,
    val at: ClusterPosition?,
    val raw: String?,
    val on: Boolean,
)

/**
 * Every flag, option and operand occurrence of one line in the order it was written, read by
 * [com.fromwau.klap.ValueScope.inOrder]. Values are converted only when read, with [inferValues] resolving a
 * choice prefix as the bind did.
 */
internal class Line(
    val written: List<Written>,
    val inferValues: Boolean,
    // The operands a single-dash word filled through their `dashLed`, read by [com.fromwau.klap.ValueScope.isDashLed].
    val dashLedOperands: Set<ArgumentSpec>,
) {
    /**
     * What [written] binds as one occurrence: a flag's polarity, or an option's or operand's value unless it fails to
     * convert.
     */
    fun valueOf(written: Written): Result<Any?, CliError> = when (val spec = written.spec) {
        is OptionSpec -> spec.convertOccurrence(written.raw, inferValues)
        is ArgumentSpec -> spec.convertOne(written.raw.orEmpty(), inferValues)
        else -> Result.Success(written.on)
    }

    companion object {
        val EMPTY: Line = Line(emptyList(), inferValues = false, dashLedOperands = emptySet())
    }
}

/** The bound values of one line beside its [line] order, as a [com.fromwau.klap.CompletionScope] reads them. */
internal class BoundLine(
    val values: Map<HolderSpec, Any?>,
    val line: Line,
)

/**
 * [sifted]'s and [globals]' occurrences in line order, with the operands each slot of [operands] took by their index in
 * [Sifted.positionals]; an unpositioned one comes after every positioned one.
 */
internal fun lineOrder(
    sifted: Sifted,
    globals: GlobalSift,
    inferValues: Boolean,
    dashLedOperands: Set<ArgumentSpec>,
    operands: Map<ArgumentSpec, List<Int>>,
): Line {
    val flags = (sifted.flagHits + globals.flagHits).map { Written(it.spec, it.at, raw = null, on = it.on) }
    val local = sifted.options.flatMap { (spec, raws) ->
        raws.zip(sifted.optionValuePositions[spec].orEmpty()) { raw, at -> Written(spec, at, raw, on = true) }
    }
    val global = globals.options.flatMap { (spec, occurrences) ->
        occurrences.map { Written(spec, it.position, it.value, on = true) }
    }
    val operand = operands.flatMap { (spec, indices) ->
        indices.map { Written(spec, sifted.positionalPositions[it], sifted.positionals[it], on = true) }
    }
    val gathered = dashLedOperands.mapNotNull { spec ->
        val joined = spec.gather?.let { sifted.gathered.joinToString(it.separator) } ?: return@mapNotNull null
        Written(spec, sifted.gatheredPositions.firstOrNull(), joined, on = true)
    }
    val written = (flags + local + global + operand + gathered).sortedWith(compareBy(nullsLast()) { it.at })
    return Line(written, inferValues, dashLedOperands)
}
