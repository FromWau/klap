package com.fromwau.klap.internal.render

import com.fromwau.klap.Builtins
import com.fromwau.klap.Command
import com.fromwau.klap.internal.spec.NamedSpec

/** [styledHelp] as the plain text a piped run prints, for asserting on help without escape codes. */
internal fun Command.helpText(
    qualifiedName: String = name,
    globalSpecs: List<NamedSpec> = emptyList(),
    style: HelpStyle = HelpStyle.PLAIN,
    rootVersioned: Boolean = false,
    rootMetaOptions: Boolean = false,
    builtins: Builtins = Builtins.DEFAULT,
): String = styledHelp(qualifiedName, globalSpecs, style, rootVersioned, rootMetaOptions, builtins).toString()
