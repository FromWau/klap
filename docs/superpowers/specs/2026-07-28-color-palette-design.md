# Unified color palette + `--color`

**Status:** Implemented on `master` (commits `ee8e01d..f58ed8d`, 2026-07-28). The design below shipped as
written and is kept here as the design of record.
**Date:** 2026-07-28

## Goal

One color story for every surface klap emits (help, error messages, and an action's own output), controlled
by a single `--color` option and exposed to consumers as a small style palette so an app can color its own
output (`action { Ok(yellow { "done" }) }`) on exactly the same terms klap's chrome uses.

## Locked decisions

1. **Flag form:** a single git-style `--color=auto|always|never` option (not a `--color`/`--no-color` pair).
2. **Value, with a default:** `--color` takes a required value `auto|always|never`; when the option is
   absent the mode is `auto` (today's detection). There is no bare `--color` (no value is a usage error) and
   no separate `--no-color` (`--color=never` covers it).
3. **Precedence:** an explicit `--color` (i.e. `always` or `never`) beats the environment. It sits at the
   top of the ladder, above `NO_COLOR`/`FORCE_COLOR`. `auto` defers to the existing ladder unchanged.
4. **Scope:** full unification. The action palette AND klap's own help/error chrome resolve color through
   one code path.
5. **Composition:** styles combine, `(bold + yellow) { "x" }`, via one open plus one reset.

## Design

### 1. The `--color` option

A built-in, position-independent, required-value meta-option, reserved like `--json`/`--completion`.

- **Parsing.** `--color` takes a required value, parsed exactly like the existing `--completion`/`--docs`
  meta-options: the value may be attached (`--color=never`) or space-separated (`--color never`), which is
  unambiguous since the value is required. Absent means `auto`. Bare `--color` with no value is a
  `CliError.MissingOptionValue("color")`, same as bare `--completion`.
- **Value validation.** `--color=bogus` is a `CliError.InvalidChoice("color", "bogus", [auto, always, never],
  <suggestion>)`, same shape as `--completion bsh`.
- **Reserved.** Add `"color"` to `RESERVED_LONG` so a consumer `option("color")`/`flag("color")` fails at
  construction. It appears under `Global options` in `--help` and is offered in option-name completion, with
  its own help text ("Colorize output: auto, always, or never") added to `BuiltinOptionHelp`.

### 2. Color resolution and the post-parse wrinkle

`internal enum class ColorMode { AUTO, ALWAYS, NEVER }`.

`Terminal.ansi` is unchanged: it stays the pure auto-detection result of the existing ladder in
`ansiEnabled` (`internal/platform/Platform.kt`): `NO_COLOR > FORCE_COLOR/CLICOLOR_FORCE > TERM=dumb > TTY`.

`--color` is parsed after `defaultTerminal()` already computed `Terminal.ansi`, so `run()` extracts the mode
from `argv` early, the same move `hasGlobalJson()` already makes, so the ERROR render path (a parse failure,
which never produces an `Execute`) honors `--color` too. Extraction is lenient: an invalid or absent value
yields `AUTO`, and the full parse separately reports the `InvalidChoice`.

```
effectiveColor = when (colorMode) { ALWAYS -> true; NEVER -> false; AUTO -> terminal.ansi }
```

`effectiveColor` drives two enabled values, computed once in `run()`:
- **chrome** (help, human error messages): `enabled = effectiveColor`.
- **action output** (the palette on `ActionScope`): `enabled = effectiveColor && !json`, so a colored string
  returned from `Ok(...)` serializes clean under `--json`. (Errors under `--json` are already plain JSON
  envelopes, so the chrome value needs no `!json` term.)

Flags-beat-env falls out: `always`/`never` short-circuit the whole ladder; `auto` uses `terminal.ansi`, which
still honors `NO_COLOR`/`FORCE_COLOR`/tty.

### 3. The `Style` palette

A style is a set of SGR parameter codes; applying wraps text in one open and one reset.

```kotlin
public class Style internal constructor(internal val codes: List<Int>)   // e.g. bold = [1], yellow = [33]
public operator fun Style.plus(other: Style): Style                       // merge codes: bold + yellow = [1,33]
```

Public style constants: the eight foreground colors (`black`, `red`, `green`, `yellow`, `blue`, `magenta`,
`cyan`, `white`) and the attributes `bold`, `dim`, `italic`, `underline`. (Bright colors and backgrounds are
out of scope for v1.)

Application is scoped to where `enabled` is known, via an operator so the `yellow { ... }` block form works:

```kotlin
// available on any color scope (ActionScope, and the internal chrome palette)
public operator fun Style.invoke(block: () -> String): String   // enabled -> "\e[<codes>m$text\e[0m", else $text
public operator fun Style.invoke(text: String): String          // convenience for a bare string
```

`(bold + yellow) { "done" }` and `yellow { "ok" }` both resolve. Because `+` produces one combined open and
one reset, the fully-combined case never double-resets. Nested-with-text (`bold { "a ${yellow { "b" }} c" }`)
is a known limitation: the inner reset clears the outer style for the trailing text, so prefer `+` for a
span that needs several styles at once. This is documented on the API.

### 4. Where it applies (full unification)

- **ActionScope** exposes the `Style.invoke` operators with `enabled = effectiveColor && !json`. So
  `action { Ok(yellow { "done" }) }` colors on a terminal, is bare when piped or `--color=never`, and is
  plain under `--json`. The `human = { }` renderer (also an `ActionScope` receiver) gets the same.
- **Chrome** (`internal/render/Help.kt`, `internal/render/ErrorRendering.kt`) moves off the ad-hoc
  `HelpStyle.paint(text, ansiCode)` onto the same `Style` application with `enabled = effectiveColor`. Each
  existing `paint(x, Ansi.BOLD)` etc. becomes a `Style` application through an internal palette carrying the
  chrome `enabled`. `HelpStyle` keeps its `columns` (wrapping) responsibility; only its color path changes.
  The existing `Ansi` code constants collapse into the `Style` constants (one source of ANSI codes).

### 5. Components (isolation)

- `ColorMode` enum + `argv.colorMode()` extractor (mirrors `hasGlobalJson`), in the parse layer.
- `Style` (public value type) + constants + `plus` + the `invoke` operators, in a new public file
  (`Style.kt` or similar) under `com.fromwau.klap`.
- An internal `Palette(enabled)` (or equivalent) carrying the `invoke` operators for a given `enabled`;
  `ActionScope` exposes one (action `enabled`), the chrome renderer holds one (chrome `enabled`).
- `run()` resolves `effectiveColor` and the two `enabled` values once, post-parse, and threads them into the
  chrome `HelpStyle` and the `Execute`'s `ActionScope`.

## Public API surface

- **New public:** `Style`, the twelve style constants (`bold`/`dim`/`italic`/`underline` + 8 colors),
  `Style.plus`, the two `Style.invoke` operators, and their availability on `ActionScope`.
- **Changed:** `--color` is a new reserved built-in; `ActionScope` gains the palette operators; `HelpStyle`
  color path re-homed onto `Style` (internal).
- No released version, so re-homing `Ansi` onto `Style` and any internal signature churn is a clean in-place
  change.

## Edge cases and safety

- `--color=never` forces off even on a real TTY with `FORCE_COLOR` set (explicit beats env, decision 3).
- `--color=always` forces on even when piped or `NO_COLOR` is set.
- Under `--json`, the action palette is disabled, so `Ok(yellow { "x" })` yields `"x"` in the JSON; klap
  never emits ANSI into a machine-readable envelope.
- Bad `--color` value (`--color=bogus`): `InvalidChoice`; the error itself renders with `auto` coloring
  (lenient extraction). `--color=` (empty attached value) is likewise a bad value -> `InvalidChoice`.
- Bare `--color` with no value -> `MissingOptionValue("color")`, same as bare `--completion`.
- The empty-`NO_COLOR` fix (from the completion spec's QA follow-up) folds in here: treat `NO_COLOR=""` as
  not-set, per the NO_COLOR spec, when resolving `auto`.
- Styles are display-only; a `Style.invoke` never alters the text content, only wraps it, and returns the
  bare text unchanged when disabled (so no accidental ANSI in logs/tests).

## Testing

- `colorMode()` extraction: absent -> AUTO; `--color=never` / `--color never` -> NEVER; `--color=always` /
  `--color always` -> ALWAYS; `--color=bogus` -> AUTO from the extractor (and `parse` returns InvalidChoice);
  bare `--color` with no value -> AUTO from the extractor (and `parse` returns MissingOptionValue).
- Resolution: ALWAYS -> color regardless of tty/NO_COLOR; NEVER -> no color regardless of tty/FORCE_COLOR;
  AUTO -> equals `terminal.ansi`. `--json` disables the action palette while chrome (help) still colors.
- `Style`: `bold + yellow` merges codes; `yellow { }` and `(bold + yellow) { }` wrap when enabled and pass
  through bare when disabled; the string overload matches the block form.
- Chrome parity: `--help` and an error render byte-identically to today under `auto` on a non-tty (no color),
  and gain color under `--color=always`; the existing HelpTest/ErrorRenderingTest expectations move onto the
  palette without wording/layout drift.
- Action output: `action { Ok(yellow { "ok" }) }` colored on a color-enabled RecordingTerminal, bare with
  `--color=never`, and plain (`"ok"`) under `--json`.
- `--color` reserved: `option("color")` throws at construction; `--color` appears in `--help` Global options
  and in option-name completion with its help text.

## Out of scope / future

- Bright colors and background colors.
- A richer palette / theming for klap's own chrome beyond the current conservative set.
- `hyperlink`/OSC-8 or other non-SGR terminal features.

## Files touched (estimate)

- New: `Style.kt` (public style type, constants, operators) under `com.fromwau.klap`.
- `internal/platform/Platform.kt` (keep `ansiEnabled` as auto; the empty-`NO_COLOR` fix).
- `Parser.kt` (root) + `internal/parse/*` (the `--color` meta-option parse + `colorMode()` extractor +
  InvalidChoice; add `color` reserved handling).
- `internal/builder/BuilderValidation.kt` (`RESERVED_LONG += "color"`).
- `Runner.kt` (resolve `effectiveColor` + the two `enabled` values post-parse; thread into HelpStyle and
  ActionScope).
- `ActionScope.kt` (expose the palette operators + carry `enabled`).
- `internal/render/Help.kt`, `internal/render/ErrorRendering.kt` (chrome onto the palette; `BuiltinOptionHelp`
  gains the `--color` row; `HelpStyle` color path).
- `internal/render/Completion.kt` (offer `--color` in option-name completion, like the other built-ins).
- Tests across `TerminalPolicyTest`, `HelpTest`, `ErrorRenderingTest`, `RunnerTest`, `CompletionTest`, plus a
  new `StyleTest`.
