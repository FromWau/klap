# V: where a value's candidates come from

Purpose: where a value's candidates come from (V), and which value sources each slot is tested with.

## Contents

- [Context](#context)
- [Existing rows, split](#existing-rows-split)
- [Choices and enums](#choices-and-enums)
- [Files](#files)
- [`completeFiles`](#completefiles)
- [Shaping a provider's candidates](#shaping-a-providers-candidates)
- [What a provider sees: `current` and `words`](#what-a-provider-sees-current-and-words)
- [Reading other inputs](#reading-other-inputs)
- [Providers on `.multiple()` inputs](#providers-on-multiple-inputs)
- [Failure](#failure)
- [Reaching the value slot](#reaching-the-value-slot)
- [Required cells](#required-cells)

## Context

Applies to the value slots K8 to K12 and K7f to K7h. A fixture covers the sources it declares; the others are
`n/a`. `‸` marks the cursor.

Rows marked **needs checking** describe behaviour read off the source that no test pins down yet, or a klap
feature that may not exist. Prove them before writing the cells.

Short names used in the notes:

- `render/Completion.kt`: `klap/src/commonMain/kotlin/com/fromwau/klap/internal/render/Completion.kt`
- `Completion.kt`: `klap/src/commonMain/kotlin/com/fromwau/klap/Completion.kt` (`CompletionScope`)
- `Bind.kt`: `klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Bind.kt`
- `Converters.kt`, `Runner.kt`, `testing/Completion.kt`: under `klap/src/commonMain/kotlin/com/fromwau/klap/`
- CT, CWL, CF, CVS: `CompletionTest.kt`, `CompletionWholeLineTest.kt`, `CompletionFlowTest.kt`,
  `CompletionValueSlotTest.kt` in `klap/src/commonTest/kotlin/com/fromwau/klap/`

What the fixtures declare today: `.choice` in cp, ls, pacman, git, task-manager; `.enum` in task-manager;
`.file()` in cp, ls, tar, git, pacman, chmod, task-manager; `.optionalValue` with `.choice` in cp, ls, pacman,
with `.file()` in git; `completeWith` only in chmod (static list on a dash-led operand), dd (structured operand
with `completeFiles(nonPathPrefix)`) and task-manager (store-backed providers reading a global, a sibling
operand, `.multiple()`). pulse declares no provider. No fixture uses `filterByPrefix = false`, so V5 is covered
in klap's tests only. task-manager and dd have no completion tests yet; only cp and pacman have completion
matrices.

## Existing rows, split

V1 to V11, each split; the first row of each split keeps the original row's meaning.

| ID | Source | What a cell checks |
|---|---|---|
| V1a | none (free text), on an option value | nothing offered |
| V1b | none (free text), on a hybrid parent's first operand | the subcommand names alone |
| V2a | `.choice(...)` | the values in the real tool's case, filtered by the typed prefix, described by the option's help when attached |
| V2b | `.enum<E>()` | the constants as `applyEnum` spells them (V12), filtered and described as V2a |
| V2c | `.choice(...)` or `.enum<E>()`, the case rule | the filter ignores case (V13) |
| V2d | `.choice(...)` or `.enum<E>()`, by form | spaced: no description (G7); attached and glued: the option's help |
| V3a | `.file()`, or a converter that implies it (`.path()`), spaced | `filesAfter("")` |
| V3b | the same, attached `--opt=` | `filesAfter("--opt=")` |
| V3c | the same, an abbreviated `--op=` | `filesAfter("--op=")` |
| V3d | the same, glued `-o` | `filesAfter("-o")` |
| V3e | the same, cluster `-xo` | `filesAfter("-xo")` |
| V3f | the same, an optional-value option, attached only | `filesAfter(head)`, `head` the non-path part of the word |
| V3g | the same, on a global before and after the subcommand | `filesAfter(head)` in both places, where the tool takes the global there |
| V4a | `completeWith { candidates(...) }` with the default prefix filter, spaced | only the candidates starting with the typed part |
| V4b | the same, attached | the filter runs on `current`, the partial value; the head is put back on each suggestion |
| V4c | the same, glued and cluster-glued | the filter runs on the glued partial; the head is put back |
| V4d | the same, on an operand against on an option | the same filter on each |
| V5a | `completeWith(filterByPrefix = false)`, one value that does not extend the typed word | the provider's own matching (fuzzy, substring, alias), and what Tab does with a candidate that does not extend the typed word |
| V5b | the same, several values sharing a prefix | what `tab` does with them (V52) |
| V5c | the same, several values sharing none | what `tab` does with them (V52) |
| V5d | the same, an alias | a typed alias offers the canonical value (V54) |
| V5e | the same, a provider ignoring `current` | everything it returns, whatever is typed (V53) |
| V6a | `candidate(value, description)`, spaced | the description shown |
| V6b | the same, attached | an attached value keeps its own description over the option's help |
| V6c | the same, glued | as attached |
| V6d | the same, a blank description | a blank description wins over the option's help (V46) |
| V7a | a provider reading another input typed before the cursor: a global | the candidates follow that input, in each state: given, absent with default, absent optional, absent required, refused, given twice (V64 to V68) |
| V7b | the same: a command option | as V7a |
| V7c | the same: an operand | as V7a |
| V7d | the same: a flag | as V7a |
| V7e | the same: a counted flag | as V7a |
| V7f | the same: a negatable flag | as V7a |
| V8a | a provider reading another input typed after the cursor | the candidates follow it too, for each input kind and state of V7 |
| V8b | the same, a subcommand name after the cursor | as V77 |
| V8c | the same, in fish | as V80 |
| V9a | a provider on a `.multiple()` input, an option by form | the value under the cursor is not among the finished values it reads; values before and after it are (V82) |
| V9b | the same, a duplicate value | as V83 |
| V9c | the same, a global | as V84 |
| V9d | the same, a variadic operand before a fixed slot | as V85 |
| V9e | the same, a variadic operand with a minimum | as V86 |
| V10a | `completeFiles()` without a head | `filesAfter("")` |
| V10b | `completeFiles(nonPathPrefix)` | `filesAfter(nonPathPrefix)`, exclusive of candidates added before it |
| V10c | `completeFiles` on an attached value | klap adds the option head (V31, V32) |
| V10d | candidates added before `completeFiles` | discarded |
| V10e | candidates added after `completeFiles` | dropped |
| V10f | `completeFiles` called twice | as V33 |
| V11a | a provider that throws an `Exception` | nothing offered, no error (V88) |
| V11b | a provider that throws an `Error` | nothing offered, no error (V89) |
| V11c | a provider that reads an unbound required input | nothing offered, no error |
| V11d | a provider that reads a refused input | nothing offered, no error (V67) |
| V11e | candidates added before the abort | discarded (V90) |

- V1: the second is not empty, and that is where a free-text slot shows up.
- V2: each has its own code path: `applyEnum` lowercases, the filter ignores case, `asWholeWord` adds the help only
  to attached values.
- V3: the head is taken from the typed word (`current.dropLast(partial.length)`), so each spelling is a different
  expected value. V3g after the subcommand only where the tool accepts the global there: not git (decision K38,
  real git refuses `git status --no-pager`, `git status -c x=y`).
- V4: the filter runs on different strings in each, and the head is put back only on the attached forms.
- V5: `tab` gives a different line in each, and zsh drops non-extending values.
- V6: the option's help fills in only on attached values; a blank description wins over it.
- V7: each state binds differently in `bindOptions`/`bindPositionals`; the absent required and the refused ones
  abort the provider.
- V8: after-cursor reads go through `wholeSifted` and its fallback, and fish never sends them.
- V9: three code paths (`withoutOccurrence` on options, on globals in line order, `skipOperand` on operands).
- V10: `completeFiles` clears, `candidate` checks `filesRequested`, `asWholeWord` adds the option head, each
  separately.
- V11: the guide promises the same answer for each, but they reach it by different paths, and only the throws have
  a test.

Disputed: V2c records klap matching a choice's typed prefix ignoring case, where the agreed G13 has the prefix matched in the tool's case (`--color=AL‸` offers nothing for a case-sensitive tool).

## Choices and enums

| ID | Source | What a cell checks |
|---|---|---|
| V12 | `.enum<E>()` display names | the values offered are the constants lowercased, an underscore kept (`LONG_ISO` gives `long_iso`), checked against the tool's own spelling (`long-iso`); a mismatch is a target |
| V13 | `.choice`/`.enum` against case | the typed prefix is matched ignoring case and the value offered in its declared case (`--color=AL‸` offers `--color=always`); for a case-sensitive tool that is an offer the tool refuses (G13), and E3 shows what Tab does with a value that does not extend the typed word |
| V14 | a choice that is a prefix of another | the exact value typed offers itself and every longer one (`--update=none‸` offers `none` and `none-fail`); Tab leaves the line as typed |
| V15 | choice values starting with a dash | `git add --chmod -‸` offers `-x` (and `+x` is filtered out), never flag names; attached `--chmod=-‸` the same with the head |
| V16 | `.choice`/`.enum` on an operand | the values in K12 slots; at a hybrid parent's first operand they come after the subcommand names, both filtered by the same prefix |
| V17 | `.choice`/`.enum` on a `.multiple()` input | a value already given is offered again, which must match whether the tool accepts the repeat (S3 for values) |
| V18 | `.choice` with `.optionalValue(whenBare)` | attached offers every value with the option's help; the bare value is not singled out or put first; spaced is an operand (K11) |
| V19 | `.choice` with `.default(v)` | the default is offered like any other value, in declared order, not marked |
| V20 | klap's built-in `--color` modes | where the fixture does not decline the built-in: `--color ‸`, `--color=‸`, an abbreviation `--col=‸`, against whether the real tool has `--color` at all (G11) and with which values |
| V21 | built-in `--completion SHELL` and the `completion` subcommand's shell | **needs checking**: whether `cp --completion ‸` offers `bash zsh fish powershell` or falls through to the operand slot; either way it is offered only where the real tool has it |

- V12: `Converters.kt` `applyEnum` (`values.map { it.name.lowercase() }`); task-manager `Priority` is the only enum and has no completion test.
- V13: `render/Completion.kt` `candidatesFor`, `choices` branch (`startsWith(current, ignoreCase = true)`); CT `a choice completes on the same case rule the parser matches on`; fixtures ls, cp.
- V14: `render/Completion.kt` `candidatesFor` plus `testing/Completion.kt` `tab`; cp `--update`, `--backup` (`n` matches `none`, `nil`, `numbered`, `never`).
- V15: `render/Completion.kt` `completeCandidates`, the `valueOption` branch runs before the flag-name branch; git `add --chmod`. No test touches it.
- V16: `render/Completion.kt` `completeCandidates` (`subcommands + positionalCandidates`); no fixture declares a choice operand today (**needs checking** for git `remote`).
- V17: `render/Completion.kt` `candidatesFor` has no exclusion for given values; no fixture or test.
- V18: `render/Completion.kt` `trailingValueOption` (`takeUnless { it.bareValue != null }`) and `asWholeWord`; CT `OptionalValueCompletionTest`; cp `--backup`/`--update`, ls `--color`, pacman `--debug`.
- V19: no special code path, `Converters.kt` `canonicalDefaultOrThrow`; task-manager `--priority`.
- V20: `render/Completion.kt` `colorValueCandidates`, `colorModeCandidates`; CT `complete candidates completes the builtin color option value *`, `an abbreviated color option *`; cp declines nothing (dd's `KLAP-GAP` comment says the same for dd).
- V21: `Completion.kt` `COMPLETION_SHELL_NAMES`; `render/Completion.kt` `matchingValueOption` searches only declared options and globals, so a meta-option may not be a value slot.

Disputed: V13 has the typed prefix matched ignoring case (`--color=AL‸` offers `--color=always`), where the agreed G13 has it matched in the tool's case, so a case-sensitive tool offers nothing.

## Files

| ID | Source | What a cell checks |
|---|---|---|
| V22 | `.file()` on a hybrid parent's first operand | `filesAfter("")` alone, no subcommand name beside it (G12) |
| V23 | `.file()` with `.optionalValue(whenBare)` | attached `--exec-path=‸` gives `filesAfter("--exec-path=")`; spaced `--exec-path ‸` is the operand slot's source |
| V24 | `.file()` on a `.multiple()` input | every occurrence, spaced or attached, gives `filesAfter(head)`; paths already given are the shell's to list again |
| V25 | `.file()` with `.completeWith { }` on one input | **needs checking** that this compiles in a fixture: `isPath` wins and the provider never runs |
| V26 | `.file()` with `.choice`/`.enum` on one input | `isPath` wins over the choices: `filesAfter(head)`, no values |
| V27 | a value the tool takes only as a directory | **needs checking**: klap has one file directive and no directory-only form, so `cp -t ‸` offers files too; a target where the tool refuses a file there |
| V28 | `.file()` on a global, before and after the subcommand | `git --git-dir ‸ log`, task-manager `-f ‸ list` and `list -f ‸`: the same `filesAfter(head)`; `git log --git-dir=‸` is not a value slot (decision K38) |
| V29 | `.file()` or `.choice` on a hidden option | the name is never offered (G8), but once typed its value completes from its source |
| V30 | any source on a hidden operand | nothing offered, though the operand parses |

- V22: `render/Completion.kt` `completeCandidates` (`positionalCandidates.isNativeFileRequest()` returns early); CT `complete files stays exclusive of a sibling subcommand name at the first positional`; git root (**needs checking** whether any fixture has a file operand on a hybrid parent).
- V23: `render/Completion.kt` `trailingValueOption` and `attachedValueOption`; git `--exec-path`.
- V24: `render/Completion.kt` `candidatesFor` (`isPath` branch); pacman `--hookdir`, git `pathspec`, chmod `file`, ls `file`.
- V25: `render/Completion.kt` `candidatesFor`, `isPath` is the first branch; no fixture or test.
- V26: same branch order; no fixture or test.
- V27: `Completion.kt` `COMPLETE_FILES` has no directory variant; cp `--target-directory`, git `-C`. Decision K37: `git -C ‸` gives `filesAfter("")` (klap has no directory-only form).
- V28: `render/Completion.kt` `matchingValueOption` (pool `options + globalAcc.optionSpecs`), `walkLine` strips globals; CT `complete candidates completes global option value`; git, task-manager, pacman. Decision K38: real git refuses globals
  after the subcommand (`git remote add -C . o u`, `git status --no-pager`, `git status -c x=y`), so git's global
  cells stop at the subcommand; task-manager's globals reach every depth.
- V29: `render/Completion.kt` `matchingValueOption` (hidden options not filtered); CT `complete candidates completes a hidden options own value not the next positionals choices`.
- V30: `render/Completion.kt` `completeCandidates` (`slotForOperand(...)?.takeUnless { it.hidden }`); no fixture.

## `completeFiles`

| ID | Source | What a cell checks |
|---|---|---|
| V31 | `completeFiles()` without a head, on an attached or glued value | klap puts the option's head in front: `--opt=‸` gives `filesAfter("--opt=")`, `-o‸`-glued gives `filesAfter("-o")`, cluster `-xo` gives `filesAfter("-xo")` |
| V32 | `completeFiles(nonPathPrefix)` on an attached value | the head is the option's head plus the provider's prefix: `--opt=key=‸` gives `filesAfter("--opt=key=")` |
| V33 | `completeFiles` called twice | the last call's prefix wins |
| V34 | `completeFiles` chosen by what is typed (dd) | each branch is a cell: no `=` yet (`dd i‸` offers `if=`, `ibs=`, `iflag=`), a file key (`dd if=/de‸` gives `filesAfter("if=")`), a value key (`dd conv=no‸` offers `conv=nocreat`, `conv=notrunc`, `conv=noerror`), an unknown key (`dd zz=‸` offers nothing) |
| V35 | `completeFiles(nonPathPrefix)` whose prefix is not the start of `current` | **needs checking**: klap passes the prefix through as given; the shells then differ (fish cuts that many characters blindly, PowerShell and bash only when it matches), so the unit cell asserts `filesAfter(prefix)` and a live test the shells |
| V36 | `completeFiles` followed by a throw or an unbound read | nothing at all: the directive is lost with the rest of the scope |
| V37 | `completeFiles` with `filterByPrefix = false` | the same lone directive, since the directive is never prefix-filtered |

- V31: `render/Completion.kt` `asWholeWord` (the `isNativeFileRequest()` branch puts `head` in front of the directive's tail); CF `a file option leaves the path to the shell behind what is not part of it` covers `.file()`, not `completeFiles`.
- V32: same function; no test or fixture.
- V33: `Completion.kt` `completeFiles` (`collected.clear()` then adds); no test.
- V34: dd `operands.completeWith` (`DD_KEY_HELP`, `DD_FILE_KEYS`, `ddValueCandidates`); dd has no completion tests.
- V35: `Completion.kt` `renderFish` (`string sub -s`), `renderBash` (`${typedCur#"$nonPath"}`), `renderPowershell` (`StartsWith($nonPath)`).
- V36: `render/Completion.kt` `candidatesFor` (`runCatching { ... }.getOrNull()` drops the whole scope).
- V37: `render/Completion.kt` `candidatesFor` (`!scope.collected.isNativeFileRequest()`); CT `a provider can hand off to native file completion`.

## Shaping a provider's candidates

| ID | Source | What a cell checks |
|---|---|---|
| V38 | a provider returning the same value twice | klap passes both through (no de-duplication), against G3: a target where the fixture lets it happen |
| V39 | a provider value equal to a subcommand name, at a hybrid first operand | both entries are listed, subcommand first |
| V40 | a provider that offers nothing | `[]` and the line unchanged; at a hybrid first operand, the subcommand names alone |
| V41 | `candidate("")` | **needs checking**: offered only on an empty word; what each shell does with an empty line |
| V42 | very many candidates (hundreds, e.g. every installed package) | all of them, in the provider's order; Tab inserts their common prefix |
| V43 | values with a space or shell-special characters (`$`, `'`, `"`, `` ` ``, `!`, `*`, `~`, `:`, `=`, `\`, a leading `#`) | offered verbatim, unescaped (escaping is each script's job), and `acceptSuggestion` puts them in verbatim; **needs checking**: `suggestionsFor` re-splits an accepted value with a space at whitespace |
| V44 | a value carrying a tab, newline or carriage return | the wire drops it (`toCompletionLine`), but `suggestionsFor` does not go through that encoding and still lists it; **needs checking**, probably a test-API target |
| V45 | a description with a tab, newline or carriage return, or padded with spaces | the wire collapses them to spaces and trims; `suggestionsFor` shows the raw text; **needs checking** as V44 |
| V46 | a blank or empty description on an attached value (`candidate("x", "")`) | **needs checking**, likely a target: a non-null blank description beats the option's help in `asWholeWord`, then the wire drops it, so the value shows bare where `null` shows the option's help |
| V47 | description by form | spaced: the provider's description or `null` (no option help, G7); attached `--opt=` and glued `-o`/`-xo`: the provider's description, else the option's help |
| V48 | the default prefix filter against case | it is case-sensitive (`--tag=W‸` offers no `work`), unlike a choice's (V13) |
| V49 | a provider that returns values already carrying the option head | **needs checking**: on an attached value klap filters the bare value against the partial and adds the head, so `candidate("--tag=red")` is filtered out, or doubled with `filterByPrefix = false` |
| V50 | a structured operand provider (`key=value`) | candidates are filtered against the whole word, the key included (`dd status=pro‸` offers `status=progress`; `dd pro‸` offers no `status=progress`) |
| V51 | `filterByPrefix = false` on an attached or glued value | every value the provider returns, each with the head; E3 from `tab` |
| V52 | `filterByPrefix = false` values that do not extend the typed word | one such value: `tab` takes it; several: `tab` inserts their common prefix only when it is longer than the typed part, which can replace typed text; zsh offers only values that extend the word (guide), so the shell rule is noted |
| V53 | `filterByPrefix = false`, a provider ignoring `current` | everything it returns, whatever is typed |
| V54 | `filterByPrefix = false` for aliases and case | a typed alias offers the canonical value; a provider matching case-insensitively offers the value in its own case |
| V55 | ranked candidates | the provider's order kept (G2), not re-sorted |
| V56 | `candidates(...)` and `candidate(v, d)` mixed in one provider | entries in call order, descriptions only where given |
| V57 | `.completeWith` on a `.choice`/`.enum` input | the provider replaces the choices entirely, and its filter is case-sensitive; **needs checking** whether any fixture should do this |
| V58 | `.completeWith` declared twice on one input | the later provider and its `filterByPrefix` win |

- V38: `render/Completion.kt` `candidatesFor` (no `distinctBy`, unlike the flag-name branch); task-manager `knownTagCandidates` (`allTags()`, **needs checking** whether it de-duplicates).
- V39, V40: `render/Completion.kt` `completeCandidates` (`subcommands + positionalCandidates`).
- V41: `Runner.kt` `toCompletionLine` (an empty value encodes as an empty line).
- V42: no limit in `candidatesFor`; pacman package names would be a provider if the fixture declared one.
- V43: `Completion.kt` `renderBash` (`printf %q`), `renderPowershell` (single quotes), `renderZsh` (`compadd -Q`); `testing/Completion.kt` `suggestionsFor` splits at `' '` and `'\t'` only.
- V44, V45: `Runner.kt` `toCompletionLine`; CT `candidate encodes value tab description and sanitizes`; `testing/Completion.kt` `suggestionsFor` maps `completeCandidates` directly.
- V46: `render/Completion.kt` `asWholeWord` (`it.description ?: help`), `Runner.kt` `toCompletionLine` (blank encodes bare).
- V47: `render/Completion.kt` `asWholeWord`; CT `choice value completion carries no description`, CF `an option value attached to its option completes as the whole word with the option's description`; task-manager ids carry titles.
- V48: `render/Completion.kt` `candidatesFor` (`it.value.startsWith(current)`, no `ignoreCase`).
- V49: `render/Completion.kt` `completeCandidates`, attached branch (`candidatesFor(partial, ...)` then `asWholeWord`); guide says a provider offers the bare value.
- V50: dd provider; CT `complete with default prefix filters provider candidates`.
- V51 to V54: `render/Completion.kt` `candidatesFor` (`completePrefixFilter`); CT `complete with filter by prefix false skips prefix filtering`; `testing/Completion.kt` `tab`. No fixture.
- V55: CF `suggestions keep the order they are declared or provided in`.
- V56: `Completion.kt` `candidate`, `candidates`; CT `complete with dsl produces described candidates filtered on value`.
- V57: `render/Completion.kt` `candidatesFor` (`complete != null` is checked before `choices != null`).
- V58: `Converters.kt` `completeWith` overwrites `spec.complete` and `spec.completePrefixFilter`.

## What a provider sees: `current` and `words`

| ID | Source | What a cell checks |
|---|---|---|
| V59 | `current` per column and form | spaced: the typed part (end, inside), `""` (start, no word); attached `--opt=pa‸`: `pa`, never the whole word; glued `-opa‸`: `pa`; cluster `-xopa‸`: `pa` |
| V60 | `words` | the words up to and including the cursor word as typed, never the words after the cursor, so a provider scanning `words` misses an L3 word an accessor sees; attached, its last entry is the whole `--opt=pa` while `current` is `pa` |
| V61 | an operand after `--` whose word starts with a dash | the provider gets `-v` as `current` and answers; no flag names (K14) |
| V62 | a provider on a dash-led operand (`.dashLed()`) | `chmod -w‸`, `chmod -‸`: the flag-name branch answers before the operand slot, so the mode provider never runs, while chmod reads `-w` as a mode; a target where the provider offers dash-led values (K17) |
| V63 | an option value typed with dashes | `--opt --h‸`, `--opt -‸`: the option's source answers, not flag names or built-ins |

- V59: `render/Completion.kt` `completeCandidates` (`candidatesFor(current, ...)` spaced, `candidatesFor(partial, ...)` attached), `attachedValueOption`; guide "For a value attached to its option ... `current` is the partial value"; task-manager, dd.
- V60: `render/Completion.kt` `completeCandidates` (`val typed = words.take(cursor + 1)` passed as `words`); `Completion.kt` KDoc of `CompletionScope`. No test reads `words`.
- V61: `render/Completion.kt` (`current.startsWith("-") && END_OF_OPTIONS !in segment`); no provider test after `--`.
- V62: same condition, before the positional branch; chmod `mode` (`dashLed()` plus `completeWith`). No test.
- V63: `render/Completion.kt` `valueOption` branch first; CVS `a dangling local option still completes its own values`.

## Reading other inputs

| ID | Source | What a cell checks |
|---|---|---|
| V64 | reading a global | absent: its default; given spaced, with `=`, glued, inside a mixed cluster, before and after the subcommand, and after the cursor: the given value |
| V65 | reading an absent input by kind | optional: `null`; `.default`: the default; flag: `false`; counted flag: the occurrences so far; negatable flag: its polarity, `--no-x` before or after the cursor; required: the provider aborts, nothing offered |
| V66 | reading a converted input | the typed value (`.int()` an `Int`, `.map { it.trim() }` trimmed); a choice typed in another case or abbreviated (under `Abbreviation.All`) reads its canonical spelling |
| V67 | reading an input whose value is refused | a converter error (`tag rm abc ‸`) or a refusing `.validate`: that input alone is unbound, the provider aborts on reading it; a converter that succeeds with `null`: the default |
| V68 | reading a scalar given twice | the last occurrence, also when it stands after the cursor and overrides one before it |
| V69 | reading a `.multiple()` input where one occurrence fails to convert | **needs checking against intent**: the whole list is unbound, so the provider aborts, unlike a scalar that loses only itself |
| V70 | reading a `.multiple(min)` input short of `min` | what is on the line; absent, an empty list |
| V71 | reading an operand an `.absentWhen()` trigger removed | its absent value (`null`), the trigger before or after the cursor |
| V72 | reading an `.optionalValue(whenBare)` option | bare: `whenBare`; attached: the value; spaced: `whenBare`, the next word being an operand |
| V73 | reading a digit short (`numberOption`) | **needs checking**: `head -5 ‸` reads 5 through the folded handle |
| V74 | reading a `lastOneWins` pair | **needs checking**: the later spelling wins, as the action sees it |
| V75 | a provider reading its own scalar input | the cursor word as far as typed; on an empty word a converter that refuses `""` (`.int()`) leaves it unbound, so the provider aborts |
| V76 | reading a non-global input of a parent command from a child's provider | **needs checking** whether klap allows such an input: `completionValues` binds only the command under the cursor and the globals, so it would read as unbound |
| V77 | an L3 read where a subcommand name stands after the cursor | the line is sifted as the cursor command's, the provider does not walk into the subcommand |
| V78 | reading past an unknown option, surplus operands or a dangling option | the inputs that did bind still read |
| V79 | a global's provider before the subcommand (`task -f ‸ list`) | the command is the root there, so it reads globals only |
| V80 | an L3 read in fish | absent: fish sends no word after the cursor, and `current` is the whole word with the cursor inside it; a shell rule, unit cells `n/a`, live test only |
| V81 | the at-start column with a provider reading its own slot | the rest of the cursor word is not on the line, so the provider reads `""` |

- V64: `render/Completion.kt` `valuesFor` (`wholeAcc.toGlobalSift()`), `Bind.kt` `bindGlobals`; CT `provider reads globals in every token shape`, `a global hiding in a mixed short cluster resolves`; CWL `a provider reads a global typed after the cursor instead of its default`; task-manager `taskStore()`.
- V65: `Bind.kt` `bindFlags`, `bindOptions` (Lenient leaves Required unbound), `bindPositionals`; CT `a required option not yet typed leaves the provider with nothing`, `lenient cardinality binds what is typed so far`; CWL `a required input not yet on the line offers nothing`, `an input not on the line reads as absent`.
- V66: `Bind.kt` `convertOne` (`resolveChoice` when `inferValues`); task-manager `id` (`.int()`), `tag` (`.map { it.trim() }`).
- V67: `Bind.kt` `bindOptions`, `bindPositionals` (Lenient `is Result.Error -> Unit`), `convertOne`; CT `reading an unresolved input yields no candidates`, `a scalar option that fails to convert leaves only itself unbound`; task-manager `tag rm`.
- V68: `Bind.kt` `bindOptions` (`raws.lastOrNull()`); no test.
- V69: `Bind.kt` `convertAll`, `convertOccurrences` (first failure fails the list); no test.
- V70: CT `an absent variadic reads back empty whichever kind it is`, `lenient cardinality binds what is typed so far`.
- V71: `Bind.kt` `bindPositionals` (`sink[it] = it.absentValue()`); chmod `mode`/`reference` (no provider reads it today).
- V72: `Bind.kt` parse of `bareValue`; ls, cp, pacman `--debug`, git `--exec-path`.
- V73: `CommandBuilder.kt` `numberOption`; head, task-manager `list -5`.
- V74: `Bind.kt` `bindPositionals` (`lastWinsLosers`); task-manager `limit = lastOneWins(namedLimit, directLimit)`.
- V75: `render/Completion.kt` `wholeSifted` puts the cursor word on the line; CWL `a provider reads the word under the cursor through its own input`.
- V76: `Bind.kt` `completionValues`; `Completion.kt` `unbound` message "or it belongs to another command".
- V77: `render/Completion.kt` `wholeLine` (`takeIf { it.cmd === cmd }`) and the fallback sift; CWL `a word under the cursor naming a subcommand does not take the parents provider into it`.
- V78: CT `one bad token does not blank the inputs around it`, `surplus positionals do not blank the inputs that did bind`, `a provider on an option value still reads everything else`.
- V79: `render/Completion.kt` `walkLine(head)` resolves the root when the cursor is before the subcommand; task-manager `-f`.
- V80: `Completion.kt` `renderFish` (`commandline -opc`, `commandline -ct`); guide "fish shows a completion the line only up to the end of the word being completed".
- V81: `testing/Completion.kt` `suggestionsFor` (`after` drops the rest of the cursor word); matrix cursor columns.

## Providers on `.multiple()` inputs

| ID | Source | What a cell checks |
|---|---|---|
| V82 | a `.multiple()` option, by form | spaced `--tag ‸`, attached `--tag=wo‸`, glued `-two‸`, cluster `-vtwo‸`: the occurrence under the cursor is left out, every other one read, before and after the cursor |
| V83 | the cursor value equal to a finished one | `--tag work --tag work‸` reads `[work]` once, so the provider still offers `work` |
| V84 | a global `.multiple()` option | occurrences before the subcommand, after it and after the cursor read in line order, the cursor's left out |
| V85 | a variadic operand with a fixed slot after it | `cp a ‸ b` reads the sources `[a]`, `b` the destination; a provider on the fixed slot, `cp a b ‸`, reads `[a, b]` |
| V86 | a variadic operand with a minimum and operands after the cursor | they keep their own slots and are read |
| V87 | a provider excluding what was given (S3 for values) | `candidates(all - given())` offers what is left plus the value being typed |

- V82: `render/Completion.kt` `valuesFor` (`withoutOccurrence`); CWL `a variadic input leaves out the word under the cursor`, `a variadic option leaves out only the value under the cursor`, `a value glued to a short cluster keeps the flags before it`.
- V83: `render/Completion.kt` `withoutOccurrence` (`finished` count); CWL `a variadic option leaves out only the value under the cursor`.
- V84: `render/Completion.kt` `valuesFor` (`boundGlobals`); CWL `a global variadic option leaves out the value under the cursor too`, `a global variadic option reads its values in the order of the line`.
- V85: `render/Completion.kt` `slotForOperand`, `Bind.kt` `bindPositionals` (`skipOperand`); CWL `the slot under the cursor counts the operands after it`.
- V86: CWL `a variadic with a minimum leaves the operands after the cursor in their own slots`.
- V87: guide example `candidates(all - tags())`; dd could exclude keys already given but does not.

## Failure

| ID | Source | What a cell checks |
|---|---|---|
| V88 | a provider throwing an `Exception` | nothing offered, no error; a sibling provider on the same line unaffected |
| V89 | a provider throwing an `Error` or a `CancellationException` | nothing offered (`runCatching` takes any `Throwable`) |
| V90 | candidates added before a throw or an unbound read | discarded with the rest: `[]`, not the partial list |
| V91 | candidates added before an early `return@completeWith` | kept |
| V92 | a converter or validator of another input throwing | an `Exception` leaves only that input unbound (`convertOne` catches it); **needs checking**: an `Error` escapes the bind, so every provider that reads any accessor on that line offers nothing |
| V93 | a provider that reads no accessor | no converter or validator runs on that Tab press; klap-level, fixture `n/a` unless a converter's effect is observable |
| V94 | a provider whose store or resource fails (corrupt JSON, missing file) | nothing offered, as task-manager's `getOrElse { return }` intends |

- V88: `render/Completion.kt` `candidatesFor`; CT `complete candidates returns empty when provider throws but sibling provider still works`.
- V89: CT `complete candidates returns empty when provider throws an error`; `CancellationException` is not tested.
- V90: `render/Completion.kt` `candidatesFor` (`getOrNull()` on the whole scope); no test.
- V91: plain Kotlin return, `collected` is kept; task-manager `tag rm` (`?: return@completeWith`).
- V92: `Bind.kt` `convertOne` (`catch (e: Exception)` around `convert` and `validate`); no test.
- V93: `Completion.kt` `CompletionScope` (`resolved: Lazy`), `render/Completion.kt` `valuesFor`.
- V94: task-manager `taskIdCandidates`, `knownTagCandidates`; no completion test.

## Reaching the value slot

| ID | Source | What a cell checks |
|---|---|---|
| V95 | a value slot reached through an unambiguous abbreviation | `ls --fo ‸`, `cp --back=nu‸`: the option's source answers, and an attached suggestion keeps the typed spelling as its head (`--back=numbered`) |
| V96 | a value slot reached through an ambiguous abbreviation or under `Abbreviation.None` | the option binds nothing, so the operand slot's source answers instead |
| V97 | a spaced value after a cluster ending in the option | `cp -rfS ‸`, `tar -xf ‸`: the option's source answers |
| V98 | a word consumed as an earlier option's value | `-e --tag ‸`: `--tag` is a value, the cursor is on an operand and the operand's source answers |
| V99 | a glued short with nothing glued yet | **needs checking** against the tool: `cp -S‸` is not a value slot in klap (empty partial), so the flag-name branch offers `-S` itself, not its values |
| V100 | an optional-value short glued (`-c‸al` shape) | **needs checking** whether any fixture has an optional-value short; klap's attached branch treats it as glued |

- V95: `render/Completion.kt` `matchingValueOption` (`resolveLong`, `NameMatch.Prefix`), `asWholeWord` (`current.dropLast(partial.length)`); cp, ls (`Abbreviation.All`).
  Decisions K11, K33: `cp --back ‸ src` is an operand slot, not `--backup`'s value (`cp --back numbered a b` reads
  numbered as a source: "cp: target 'b': No such file or directory"); `ls --fo long -d .` is accepted, so
  `ls --fo ‸` offers the format choices.
- V96: `render/Completion.kt` `matchingValueOption` (`NameMatch.Ambiguous, NameMatch.None -> return null`); CT `an abbreviation ambiguous with another declared option offers no color modes`.
- V97: `render/Completion.kt` `trailingValueOption` (cluster's last char); CT `complete candidates completes clustered short option value`; tar, task-manager `add -Dp ‸`.
- V98: `render/Completion.kt` (`trailingIsConsumedValue`, `prev`); CVS `the attached form in a value slot leaves the cursor on an operand` and siblings.
- V99: `render/Completion.kt` `attachedValueOption` (`if (partial.isEmpty()) return null`), then the flag-name branch (`firstNonFlagShort >= 0`, so not a cluster continuation).
- V100: `render/Completion.kt` `attachedValueOption`.


## Required cells

The base required cells, with their V column, are in `K.md`'s "Required cells". These rows add V sources to slots the table already lists and name the K and V pairs that only
meet in one place.

| Slot | Columns | L | S | V |
|---|---|---|---|---|
| K8 | all five | L1 L2 L3 | S1 S3 | V2 V4 V5 V6 V7 V8 V11, plus V13 V14 V15 V47 V48 V59 V63 V75 |
| K9 | all five, plus the name part | L1 L2 L3 | S1 S3 | V2 V3 V4 V6 V10, plus V31 V32 V46 V47 V49 V51 V59 V95 |
| K10 | end, inside, start, words after; "no word" as V99 | L1 L2 | S1 | V3 V4 V6 V10, plus V31 V47 V59 |
| K11 | as its row lists | L1 L2 | S1 | V2 V3, plus V18 V23 V72; the spaced form is the operand slot's source |
| K7f | as its row lists | L1 L2 | S1 | V3 V4 V10, plus V31 (`filesAfter("-rfS")`) V59 |
| K7g | as its row lists | L1 L2 | S1 | V2 V3 V4: the value starts with `=`, so a choice offers nothing and a file head is `-rfS=` |
| K7h | as its row lists | L1 L2 | S1 | V1 to V4: the rest of the cluster is the value, so the source answers for it |
| cluster then spaced value (`cp -rfS ‸`) | end, no word, words after | L1 L2 | S1 | each source the option declares (V97) |
| K12a at a hybrid parent | all five | L1 L2 L3 | S1 | V1b V16 V22 V39 V40 |
| K12b | all five | L1 L2 L3 | S1 S3 | V9, plus V17 V24 V82 V85 V87 |
| K12c | all five | L1 L2 L3 | S1 | V9 V85 V86 |
| K12d | all five | L1 L2 L3 | S5 | V71 (a provider reading the removed slot); the removed slot's own source is never offered |
| K14 | as its row lists | L4 | S1 | V4 V61 |
| K15 then a value | end, no word | L1 L2 | S1 | V95 V96 |
| K17 | as its row lists | L1 L2 | S1 | V62 |
| K19 | end, no word, words after | L1 L2 L3 | S1 | V7 V64 V79 (globals before and after both subcommands) |
| any value slot of a global | end, no word, words after | L1 L2 L3 | S1 | V28 V64 V84 |
| any value slot whose input is `.multiple()` | end, no word, words after | L1 L2 L3 | S1 S3 | V9 V17 V82 V83 |
| any value slot with a provider | no word | L1 | S1 | V11 V88 V90 V94, one cell each where the fixture's provider can fail |
| any value slot with a provider | end | L1 | S1 | V43 V44 V45, one cell each where the provider's data can carry such values |

- K8: its example is `ls --format` (decision K8); `cp --backup ‸` is K11's operand slot.
- K19, any value slot of a global: a global after the subcommand is a value slot only where the tool accepts it
  there: task-manager's globals reach every depth, git's do not (decision K38).
