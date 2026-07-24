package com.fromwau.klap

import kotlinx.serialization.KSerializer

/**
 * A leaf command's action: the block that produces the value, the serializer used to render it under
 * `--json`, and an optional human renderer for plain output.
 *
 * `@PublishedApi` because the public reified `action` builder in [CommandBuilder] constructs it inline.
 */
@PublishedApi
internal class ActionSpec @PublishedApi internal constructor(
    val block: () -> Result<Any?, CliError>,
    val serializer: KSerializer<Any?>,
    var human: ((Any?) -> String)? = null,
)
