# Chrome palette: finish the colour unification

**Status:** Implemented on `master`, 2026-08-02, except the deliberately-excluded error-colour work (see
"Deliberately NOT in this change" and the Out-of-scope list). Output is byte-identical: no existing help
snapshot needed editing, which is the gate this change is judged by.
**Date:** 2026-08-02

## Goal

Finish the piece of `2026-07-28-color-palette-design.md` §4 that did not ship: route klap's own output
through the same `Style`-application mechanism a consumer's action uses, instead of a second, hand-rolled
one. As a side effect this settles the open `ColorScope` question recorded in
`2026-08-01-completion-value-scope-design.md`.

## What actually shipped, and what did not

§4 of the colour design said the chrome "moves off the ad-hoc `HelpStyle.paint(text, ansiCode)` onto the
same `Style` application with `enabled = effectiveColor`", "through an internal palette carrying the chrome
`enabled`", and named both `internal/render/Help.kt` and `internal/render/ErrorRendering.kt`.

Measured against the code as of `39836f0`:

| §4 clause | State |
|---|---|
| `Ansi` constants collapse into `Style` constants, one source of ANSI codes | **Shipped.** `Help.kt` imports `com.fromwau.klap.bold`. |
| Chrome applies styles through an internal palette carrying `enabled` | **Not shipped.** `HelpStyle` passes the flag by hand: `bold.render(text, color)` (`Help.kt:232`, `:238`). |
| `HelpStyle` keeps only its `columns` responsibility; its colour path changes | **Not shipped.** It still carries `color: Boolean` and applies it directly. |
| `ErrorRendering.kt` is chrome and moves too | **Never happened.** Error output applies no colour at all — `terminal.err("error: $rendered\n")`. |

An earlier revision of `2026-07-28-completion-descriptions-design.md` claimed the first and second clauses
were both closed. That was wrong, and has been corrected there.

## Why this settles `ColorScope`

`ColorScope` (`Style.kt:42`) is a `public sealed interface` with exactly one implementor, `ActionScope`.
Because it is sealed, that is permanent unless klap adds an implementor itself — so today
`fun ColorScope.warn(...)` and `fun ActionScope.warn(...)` have identical sets of valid call sites, and the
type buys no expressiveness. That is the whole of the open question.

The chrome is the missing implementor, and §4 already asked for it under a different name ("an internal
palette carrying the chrome `enabled`"). Giving the chrome a `ColorScope` both delivers §4 and makes the
type pay for itself, at which point the question closes on its own rather than being decided by fiat.

**Sealing constrains where that implementor can live.** Verified against the compiler:

```
error: a class can only extend a sealed class or interface declared in the same package.
```

`ColorScope` is in `com.fromwau.klap`; `HelpStyle` is in `com.fromwau.klap.internal.render`. So `HelpStyle`
cannot implement `ColorScope` directly. The implementor must sit in the root package — which is exactly the
shape §4 described, a small internal palette object rather than the renderer itself.

## Design

### 1. `Palette` — the chrome's `ColorScope`

New internal class in `Style.kt`, beside `ColorScope` so the sealed-package rule is satisfied:

```kotlin
/**
 * The chrome's [ColorScope]: klap's own help and error output resolves a [Style] through this, exactly as a
 * consumer's action resolves one through [ActionScope]. Two surfaces, one mechanism — the alternative is a
 * second hand-rolled `Style.render(text, flag)` path that has to be kept in step by eye.
 *
 * Internal, and in this package because [ColorScope] is sealed: a subtype must be declared alongside it.
 */
internal class Palette(private val enabled: Boolean) : ColorScope {
    override fun Style.invoke(block: () -> String): String = render(block(), enabled)
    override fun Style.invoke(text: String): String = render(text, enabled)
}
```

### 2. `HelpStyle` carries one

```kotlin
internal data class HelpStyle(val columns: Int, val color: Boolean) {
    /** Not a constructor property: derived from [color], and it must stay out of equals/hashCode. */
    val palette: Palette = Palette(color)
    companion object { val PLAIN = HelpStyle(columns = 0, color = false) }
}
```

`color` stays, because `paintSignature` branches on it for padding (a bolded signature and a plain one must
occupy the same visible width). Only the *application* moves.

The two call sites become:

```kotlin
private fun HelpStyle.paint(text: String): String = with(palette) { bold(text) }

private fun HelpStyle.paintSignature(signature: String, width: Int): String {
    if (!color) return signature.padEnd(width)
    val pad = " ".repeat((width - signature.length).coerceAtLeast(0))
    return with(palette) { bold(signature) } + pad
}
```

This is not a local readability win — `with(palette) { bold(x) }` is slightly more ceremony than
`bold.render(x, color)`. The win is that there is now one way to apply a `Style` in the codebase instead of
two, and the second one cannot drift.

### 3. Output is byte-identical

`Palette.invoke` calls the same `Style.render(text, enabled)` the chrome calls today, with the same flag.
No help or error output changes. The existing help snapshots are the proof, and none should need editing.

## Deliberately NOT in this change

**Colourizing error output.** §4 lists `ErrorRendering.kt` as chrome, and it applies no colour at all — so
`error: unknown option --foo` is plain even on a colour terminal. Making it red is a genuine improvement and
is what §4 asked for, but it is a **UX change, not a refactor**: it needs a decision about what gets styled
(the `error:` prefix, the offending token, the did-you-mean hint), it has to thread an `enabled` flag into a
path that currently takes none, and `ErrorRenderingTest`'s 21 exact-string assertions would need to be read
through. Bundling it here would mean this change can no longer claim byte-identical output, which is the
property that makes it safe.

Tracked as the follow-up below.

## Testing

- The full existing suite is the gate: output is byte-identical, so nothing should need editing. An edited
  help snapshot means the refactor changed behaviour and is wrong.
- One new assertion that `Palette` honours its flag in both operator forms (text and block), since it is a
  new type with its own behaviour.
- `HelpTest`'s existing `HelpStyle(columns = 0, color = true)` case already exercises the coloured path end
  to end.

## Out of scope / future

- **Colour in error output** (above). The substantive half of §4's chrome clause, and the thing that would
  give `Palette` a second call site worth having.
- Should `HelpStyle` eventually drop `color` entirely and carry only `columns` + `palette`? §4 says its
  "colour path changes" and it "keeps its `columns` responsibility". `paintSignature`'s padding branch is
  the one thing standing in the way; a `Style`-aware pad helper would remove it. Not worth it for one site.

## Files touched (estimate)

- `Style.kt` — new internal `Palette`.
- `internal/render/Help.kt` — `HelpStyle` gains `palette`; the two paint helpers route through it.
- `StyleTest.kt` — one test for `Palette`.
