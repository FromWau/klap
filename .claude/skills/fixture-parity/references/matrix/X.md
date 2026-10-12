# X: from the typed string to the value

Purpose: which typed strings each converter, and each tool's own reading of a value, binds or refuses.

## Contents

- [Existing rows](#existing-rows)
- [Evidence](#evidence)
- [Integers: what `.int()` / `.long()` read, and what tools read instead](#integers-what-int--long-read-and-what-tools-read-instead)
- [Doubles](#doubles)
- [Booleans](#booleans)
- [Choices and enums](#choices-and-enums)
- [Optional values](#optional-values)
- [Defaults](#defaults)
- [Multiple values](#multiple-values)
- [Repeats of a single-valued input](#repeats-of-a-single-valued-input)
- [What reaches the converter: the value word itself](#what-reaches-the-converter-the-value-word-itself)
- [Validation](#validation)
- [Custom converters](#custom-converters)
- [Planned converters (todo item 24): their valid and invalid forms](#planned-converters-todo-item-24-their-valid-and-invalid-forms)
- [Globals, completion and platforms](#globals-completion-and-platforms)
- [Required cells](#required-cells)

## Existing rows

One cell per value-taking input: a line with a value the real tool accepts binds the typed value (asserted on the
record, as P4), and a value the tool refuses is refused. The bounds and spellings of klap's own converters are
klap's behaviour and are tested once in klap's tests; a fixture's cells cover what its tool reads.

| ID | Converter | Cells |
|---|---|---|
| X1a | `.int()`, `.long()`: klap's grammar, pinned once in klap's tests | a sign, leading zeros, overflow, `abc`, empty, `1e3`, `0x10`; `+5`; a lone sign; `Int.MIN_VALUE` exactly; Unicode digits (X14); leading space (X15); `080` and `0b1` (X16); `1_000` (X20) |
| X1b | the tool's number (fixtures) | the tool's valid numbers; a sign, leading zeros, overflow, `abc`, empty, `1e3`, `0x10`, each as the tool reads it; a number glued to its short (`-n5`); a negative given as an option value (`-n -5`); a saturating tool's huge number (X19); an invalid earlier occurrence (X55) |
| X2 | `.double()` | `1.5`, `-0.5`, `.5`, `1e3`, a locale comma `1,5`, `NaN`, `Infinity`, as the tool reads each; `1.5f`, `0x1p3`, ` 1` (X24); `1e400`, `4.9e-325` (X25); `nan`, `inf`, `-0.0` against a range (X26); `5.` |
| X3 | `.boolean()` | the spellings the tool takes, and an unknown word; `t`, `on`, `off`, ` true`, empty, an abbreviation `tru` with abbreviation on (X28) |
| X4a | `.choice(...)` | the exact name, another case, an unknown name, an abbreviation where the tool allows one, an ambiguous prefix; the empty value `--opt=`; an alias of a value (X32); a comma list of values (X35); a value in another case where the tool is case-sensitive (X30) |
| X4b | `.enum<E>()` | the exact name, another case, an unknown name; a hyphenated tool value against an enum (X33) |
| X4c | abbreviation of values (`.choice(...)`, `.enum<E>()`) | an abbreviation where the tool allows one, an ambiguous prefix; a prefix in another case `AL`; an exact value that also prefixes another (`no`/`none`); each mode of `Abbreviation` (X29) |
| X5a | `.optionalValue(whenBare)`, long spelling | bare `--opt` gives `whenBare`, attached `--opt=v` gives `v`, a spaced `--opt v` leaves `v` an operand; `--opt=` empty (X38); an abbreviated name (X40); `--opt --` and `--opt -` (X42); repeated bare and attached (X43); combined with a default (X41) |
| X5b | `.optionalValue(whenBare)`, short spelling | the short forms `-S`, `-Sx`, `-S x`, `-S=x` (X39); bare at a cluster's end `-vS` |
| X6 | `.default(v)` | absent gives `v`, already typed; given overrides it; declared before the converter (X45); a default the converter refuses fails construction; a default a `.validate` would refuse still binds (X46); a converter yielding null falls back to it (X47); a given-but-empty value does not (X48); on an operand as well as an option |
| X7a | `.multiple(min)` on an option | order kept, fewer than `min` refused, empty when absent; one bad occurrence (X50); a null-converting occurrence (X51); an empty-string occurrence (X52); mixed spellings in order (X54) |
| X7b | `.multiple(min)` on an operand | order kept, fewer than `min` refused, empty when absent; one bad occurrence (X50); a null-converting occurrence binds `null` (X51); `min` relaxed by `.optionalWhen()` (X53) |
| X8 | `.path()` (`klap-io`) | a path binds `Path(...)`; `~` stays literal, as the shell expands it, not the tool; a trailing slash, `a//b`, `//`, empty, `-` (X84); a relative and an absolute path; a path with a space or newline |
| X9a | `.duration()` (core), human style | the human style (`1s`, `5h 31m 2s 123ms`); a negative or unit-less value as the tool reads it; the accepted and refused lists of X74 and X75; `-(1h 30m)`, `Infinity`; outer space |
| X9b | `.duration()` (core), ISO 8601 | ISO 8601 (`PT1H30M`); the accepted and refused lists of X76; outer space |
| X10a | `.instant()` (`klap-datetime`) | `2026-10-09T12:00:00Z` and an offset `+02:00`; an `Instant` without zone or offset refused; `2026-02-30` refused; the lists of X78 and X79; lowercase `t`/`z`; `+02` hours-only offset; `T12:00Z` without seconds; leap day of a non-leap year; a leap second; `24:00` |
| X10b | `.localDate()` (`klap-datetime`) | `2026-02-30` refused; the list of X80; leap day of a non-leap year |
| X10c | `.localDateTime()` (`klap-datetime`) | the list of X81; lowercase `t`; `24:00` |
| X10d | `.timeZone()` (`klap-datetime`) | the list of X82 |
| X11 | `.uuid()` (core) | canonical, upper case, without dashes, each as the tool reads it; braces, `urn:uuid:`, outer whitespace, the nil UUID, a misplaced dash (X83) |
| X12a | `.map { }` to another type | the tool's valid values bind the typed value, the others are refused; a throw becomes a refusal (`ConversionError.Threw`); `null` is absent; a `CancellationException` propagates (X62); stacked stages (X68) |
| X12b | `.convert { }` to another type | the tool's valid values bind the typed value, the others are refused; `Ok(null)` is absent (X67); the `Domain` cause intact (X66); stacked stages (X68) |
| X13 | `.validate(message) { }` | a value the predicate refuses is refused; a throwing predicate (X62); not run on null or on a default, run per element and on the bare value, seeing the declared choice spelling (X63); after `.multiple()` on an operand is a refusal, not a crash (`ParsePositionalsTest` "validate after multiple yields bad value instead of crashing") |

Where a converter does not exist yet (`todo.md` item 24), its cells are targets.

- X1a, X1b: X1a, klap's `.int()`/`.long()` grammar (limits, signs, bases, whitespace, Unicode), is pinned once in pacman `--debug` reads a decimal level: `01`, `002`, `+2`, `' 2'` accepted, `2 `, `2x`, `0x2`, `-1`, `1.0` and an overflow refused (observed), a pacman target for the accepted ones.
  klap's tests. X1b, "the tool's number", is for fixtures: most tools read numbers with strtol (base 0 or 10,
  leading space, saturation, suffixes) and a fixture often cannot use `.int()` at all (head keeps a string).
  Today X1 mixes both, and the rule "klap's bounds are tested once" is unclear for it.
- X4a, X4b, X4c: A choice binds the declared string, an enum a constant whose spelling cannot hold a `-`;
  abbreviation is a separate code path (`resolveChoice`, before the converter) governed by the root's
  `Abbreviation` mode, with its own cells (exact wins, ambiguous, empty, another case).
- X5a, X5b: The long form splits on `=` (`longToken`), the short form takes the rest of the cluster
  (`internal/parse/Parser.kt` `sift`, the `ShortRead.Option` arm), so `-S=x` and `--opt=x` give different values, and
  only the short has a "bare at a cluster's end" cell.
- X7a, X7b: Different paths: `convertOccurrences` refuses a null, `convertAll` keeps it; an unmet `min` is
  `TooFewOccurrences` for an option and `MissingArgument` for an empty operand; only an operand has
  `.optionalWhen()` and a fixed slot after it.
- X9a, X9b: One parser takes both today, but the accepted sets differ in case, order and units (X74 to X76), and
  the owner's open questions (`Infinity`, `-(...)`, no space between units) belong to the human style alone.
- X10a to X10d: Their valid and invalid forms barely overlap (a zone is required for one and refused for
  another), so one row cannot list the cells; X78 to X82 are the four rows.
- X12a, X12b: `.map` refuses by throwing (`ConversionError.Threw`) and treats `null` as absent; `.convert` refuses
  with a typed `Domain` cause a `parse` caller can match.

## Evidence

How to read the notes. Code is named by file and function, not line number (re-checked on 2026-10-12);
`Bind.kt`, `Parser.kt` and `NameMatch.kt` without a directory are under `internal/parse/`, `Converters.kt` and
`CliError.kt` at the package top. **klap** means klap's own tests (`klap/src/commonTest`, run on every target), where a
converter's bounds and spellings are pinned once. **fixture** means the fixture tests of the named tools, which
cover what that tool reads, expected values from the real binary, a refusal asserted as a refusal. A cell
marked *needs checking* is one where it is not yet confirmed what klap or the platform does.

"Observed" means run on 2026-10-09 on the development machine:

- Kotlin 2.4.21 on the JVM (JRE 27): `toIntOrNull`, `toDoubleOrNull`, `toBooleanStrictOrNull`, `Duration.parse`,
  `Duration.parseIsoString`, `Uuid.parse`/`parseHexDash`/`parseHex`, `kotlin.time.Instant.parse`, and with
  kotlinx-datetime 0.7.1 `LocalDate.parse`, `LocalDateTime.parse`, `TimeZone.of`, and kotlinx-io 0.8.0 `Path`.
  klap's catalog pins kotlinx-datetime 0.8.0 and kotlinx-io 0.9.1, and klap also builds linuxX64, mingwX64 and
  macosArm64, so every platform-library observation is JVM-only and needs rechecking on the pinned versions and
  the native targets.
- Real binaries, read-only or in a throwaway directory: GNU coreutils 9.12 (`head`, `ls`, `chmod`, `mkdir`, `cp`,
  `dd`; the fixtures say 9.11), OpenSSH 10.6p1 (`ssh -G` only, which prints the resolved config and connects
  nowhere), and `find`, which on this machine is **bfs 4.1.1, not GNU findutils**. A find fixture pass must say
  which one it took as the oracle.

## Integers: what `.int()` / `.long()` read, and what tools read instead

| ID | Converter | Cells |
|---|---|---|
| X14 | `.int()`, `.long()`: non-ASCII digits | Arabic-Indic `١٢`, fullwidth `１２`, Devanagari `१२`, a mix `1٢`; each refused, as every C tool refuses them |
| X15 | `.int()`, `.long()`: whitespace | leading ` 5`, trailing `5 `, tab `\t5`, newline `5\n`, only spaces; as the tool reads each (strtol skips leading whitespace, rejects trailing) |
| X16 | `.int()`, `.long()`: bases | `0x10`, `0X10`, `010`, `080`, `0b1`, `0o7`; decimal in klap, while a tool may read base 0 (octal and hex) or decimal |
| X17 | `.int()`, `.long()`: exact limits | `Int.MAX_VALUE`, `Int.MIN_VALUE`, one past each; `Long.MAX_VALUE`, `Long.MIN_VALUE`, one past each; `-0`, `+0`, `00` |
| X18 | `.int()`, `.long()`: signs | `+5`, a lone `+`, a lone `-`, `--5`, `++5`, `+-5`, `-+5`, `- 5` |
| X19 | tool numbers that saturate | `99999999999999999999` where the tool clamps to its maximum rather than refusing |
| X20 | digit grouping | `1_000`, `1,000`, `1'000`, `1 000`, `1.000`; each refused unless the tool groups |
| X21 | sizes with a unit suffix (`.convert`/`.validate`) | each suffix of the tool's table (`b`, `c`, `w`, `k`/`K`, `kB`, `KB`, `KiB`, `M`, `G`, `T` ...); its case; a product `1x2`; a dangling `2x`; a suffix alone `K`; a doubled suffix `1kk`; `1Kb`; zero; negative; `1e3`; hex |
| X22 | signed comparison counts `[+-]N` (`.map`/`.convert`) | `N`, `+N`, `-N`, `++N`, `-+N`, `+-N`, a fraction `1.5`, ` 1`, `1e1`, `0x1`, an overflow, the sign alone, empty |
| X23 | `numberOption()` through a converter | `-0`, `-007`, a run past `Int.MAX_VALUE`, a run glued to a letter `-5v`, a run the `.validate` refuses, the run beside a declared digit short |

- X14: `Converters.kt` `numeric { it.toIntOrNull() }` / `toLongOrNull()`. **Observed accepted** on the JVM:
  `toIntOrNull("١٢") == 12`, `toIntOrNull("１２") == 12`. Native needs checking. klap (a likely defect: a refusal
  target), plus one fixture cell per tool that reads a number (`ls -w`, `ssh -p`, `find -O`, `git -n`, `curl`).
- X15: `toIntOrNull` refuses both leading and trailing space (observed). Real tools differ: `head -n ' 5'`,
  `ls -w ' 80'`, `ssh -p ' 22'` accept, `head -n '5 '` refuses (observed). klap for the converter; fixture
  head, ls, ssh for the tool's reading (targets where the tool accepts).
- X16: `toIntOrNull` is decimal only. Observed: `ls -w 0x50` accepted and `ls -w 080` refused (base 0), `ssh -p 022`
  reads 22 and `ssh -p 0x16` refuses (decimal), `head -n 010` accepted and `0x10` refused. klap for decimal-only;
  fixture ls, ssh, head, find.
- X17: `toIntOrNull`/`toLongOrNull`; observed `-2147483648` accepted, `2147483648` refused. Existing tests cover
  only an overflowing long and `1.5` (`ConvertersTest` "rejects overflow and decimal"). klap.
- X18: observed `toIntOrNull("+5") == 5`, lone `+`/`-` refused. bfs accepts `-mtime ++1` and `-mtime -+1` and
  refuses `+-1` (observed); the find fixture's `findCount` reads `++1` via `toLongOrNull("+1")`. klap for
  `.int()`; fixture find (`--mtime`, `--mmin`, `--links`), head (`-n +5` accepted by real head).
  GNU findutils 4.11.0 (find pass 2026-10-11) accepts `-mtime +-1` but refuses `-size +-1` and `-links +-1`.
- X19: real `head -n 99999999999999999999` and `ls -w 99999999999999999999` accept (observed); `.int()` refuses
  (`numeric` returns `NotAnInteger`). bfs refuses `-mtime 99999999999999999999`. fixture head, ls, find (targets
  where the tool saturates).
  GNU find 4.11.0 accepts `-mtime 99999999999999999999` (strtod saturates); `-size` and `-links` take up to
  2^64-1 and refuse beyond.
- X20: `toIntOrNull("1_000") == null` (observed). klap; fixture n/a unless a tool groups.
- X21: no klap converter; fixtures re-implement it (`dd/Dd.kt` `ddNumberIsValid`, `head/Head.kt` `HEAD_NUM`,
  `find/Find.kt` `findSize`). Observed: `head -c 1k`, `1kB`, `1KB`, `1KiB` accepted, `1Kb` and `0x10` refused;
  `dd bs=1x2`, `1b`, `1w`, `1c`, `1KiB`, `1G` accepted, `bs=2x`, `0`, `-1`, `1e3`, `0x10` refused; bfs
  `-size 1T` accepted (`findSize` lacks `T`), `-size 1K` refused. GNU find 4.11.0 refuses `-size 1T` ("invalid -size type"), so `findSize` agrees with GNU. fixture head, dd, find. A candidate for a
  future core `.size()` converter (owner call).
- X22: `find/Find.kt` `findCount`, `findSize`, `findPerm` via `.map`, so a throw is the refusal
  (`Converters.kt` `applyMap`). Observed bfs refuses `-mtime 1.5`, ` 1`, `1e1`, `0x1`. fixture find, head.
  GNU find 4.11.0 accepts all four for the time predicates (strtod), and leading blanks for `-size` and `-links`.
- X23: `numberOption()` feeds the same `convertOne` (`Bind.kt`, through `bindOptions`); `NumberOptionTest` "a run too
  large for its converter" covers overflow only. klap; fixture head (`head -5`), git (`git log -5`).

## Doubles

| ID | Converter | Cells |
|---|---|---|
| X24 | `.double()`: forms the platform parser lets through | `1.5f`, `1.5d`, `1.5F`, `0x1p3`, ` 1`, `1 `, `+1`, `5.`, `1E3`, `1e-3` |
| X25 | `.double()`: overflow and underflow | `1e400`, `-1e400` (become infinities), `4.9e-325` (becomes `0.0`), each then through `.range()` |
| X26 | `.double()`: special values | `NaN`, `nan`, `-NaN`, `Infinity`, `+Infinity`, `-Infinity`, `inf`, `INF`, `infinity`; and `NaN`, `Infinity`, `-0.0` against `.range(0.0..x)` |
| X27 | `.double()`: locale and script | `1,5`, `1.000,5`, Arabic `١٫٥`, a thin-space group |

- X24: `numeric { it.toDoubleOrNull() }`. **Observed accepted** on the JVM: `1.5f`, `1.5d`, `0x1p3` (8.0), ` 1`
  and `1 ` (whitespace), so `.double()` takes whitespace where `.int()` does not. Native needs checking; a
  commonTest pins one answer for all targets. klap; fixture curl (`-m`, `--connect-timeout`).
- X25: observed `toDoubleOrNull("1e400") == Infinity`, `"4.9e-325" == 0.0`. curl's `.range(0.0..86_400.0)`
  refuses infinity, while a fixture without a range would bind it. klap; fixture curl.
- X26: observed `NaN`, `Infinity`, `+Infinity` accepted, `nan`, `inf` refused. `NaN in 0.0..86400.0` is false and `-0.0`
  is in it (`Converters.kt` `range`, a `validate` of `it in range`), needs checking against curl, which reads with
  strtod (accepts `inf`/`nan` in C). klap; fixture curl.
- X27: observed `1,5` and `١٫٥` refused. klap; fixture curl (a comma locale is the shell user's, not curl's).

## Booleans

| ID | Converter | Cells |
|---|---|---|
| X28 | `.boolean()`: every near spelling | `True`, `TRUE`, `t`, `1`, `0`, `yes`, `no`, `on`, `off`, `y`, ` true`, `true `, empty; and `tru` with abbreviation on |

- X28: `toBooleanStrictOrNull` (observed: only `true`/`false`). Abbreviation never applies, since `.boolean()` sets no
  `choices` (`Bind.kt` `convertOne` resolves a prefix only when `choices != null`), so `tru` stays refused under
  `Abbreviation.Options`. Existing tests cover `TRUE`, `1`, `yes`. klap; fixture n/a today (no fixture declares
  `.boolean()`; ssh's `yes`/`no` keywords go through `-o` as key=value, X69).

## Choices and enums

| ID | Converter | Cells |
|---|---|---|
| X29 | `.choice()`/`.enum<E>()` under each `Abbreviation` mode | a unique prefix under `None` (refused), `Options` and `All` (resolves); an exact value that is also a prefix of another (`no` beside `none`); an ambiguous prefix; an empty value `--opt=`; a prefix in another case `AL` |
| X30 | case of a choice value | the tool's own case, upper case, mixed case; klap matches the declared spelling exactly and refuses another case, as cp 9.12 does (`--sparse=AL`: invalid argument) |
| X31 | Unicode case folding in choices | Kelvin sign `K` (U+212A) for `k`, dotted `İ` for `i`, `SS` for `ß`, Greek final sigma; in the exact match and in the prefix match |
| X32 | aliases of one choice value | every spelling the tool takes for one value binds the same record value |
| X33 | `.enum<E>()` names against tool spellings | a hyphenated tool value (`none-fail`) against `NONE_FAIL`; the underscore spelling `none_fail`; a constant name with digits |
| X34 | a converter chained after `.choice()` | `.choice().int()`, `.choice().map { }`, `.choice().validate { }`: the next stage sees the declared spelling, after an abbreviation resolved |
| X35 | comma-separated choice lists | each known element; a leading, trailing or doubled comma; a repeated element; a space after a comma; an unknown element; an element in another case; an abbreviated element |
| X36 | choice values that look like options | `--chmod=+x`, `--chmod=-x`, `--chmod -x` spaced, `--chmod=x` |
| X37 | the error kind of a refused choice | `InvalidChoice` (with its did-you-mean) for a value outside the set, `AmbiguousValue` for an ambiguous prefix, `BadValue` for a `.validate` failure on a choice input |

- X29: `ValueSpec.convertOne` (`Bind.kt`) resolves the prefix before the converter via `resolveChoice`
  (`Parser.kt`) and `resolveName` (`NameMatch.kt`: an empty typed value is `None`, so `""` reaches the converter
  and is refused as not one of the set). pacman 7.1.0: `--color=al`, `nev`, `au` refused (exact words only), a
  pacman target. Observed: `cp --backup=nu` and `ex` accepted,
  `--backup=n` refused (ambiguous), `ls --color=al` accepted, `ls --color=a` refused, `cp --sparse=al` accepted.
  klap for the mode table; fixture cp, mv, ls, rm, pacman, git.
- X30: klap matches a choice in its declared case unless it is declared `.choice(ignoreCase = true)` (`choiceIgnoreCase`
  in `HolderSpec.kt`), so the case-insensitive reading this note first recorded is gone (find pass 2026-10-11). Observed
  refused: `ls --color=ALWAYS`, `cp --sparse=AUTO`, `cp --backup=T`, `cp --preserve=MODE`, `dd conv=NOTRUNC`, GNU
  `find -regextype POSIX-EGREP`, `-type F`. klap and the tools agree by default.
- X31: exact matching uses `equals(ignoreCase)` (per-char upper/lower comparison) but prefix resolution uses
  `lowercase()` (`Parser.kt` `resolveChoice`, under `ignoreCase`), and the two disagree outside ASCII. Observed on the
  JVM: `"k".equals("\u212A", true)` is true, `"ss".equals("ß", true)` is false, `"İ".lowercase()` is `i̇` (two chars).
  The duplicate check (`Converters.kt` `requireDistinctChoices`) uses `lowercase()` too. needs checking. klap only.
- X32: no klap alias concept for values; a fixture writes every alias into `.choice()` and folds with `.map { }`.
  Observed: `ls --color=yes`, `force`, `tty`, `if-tty` accepted (ls lists `always, yes, force` / `never, no,
  none` / `auto, tty, if-tty`); cp's `t`/`numbered`, `nil`/`existing`, `never`/`simple`, `none`/`off`. fixture
  ls, cp, mv (P4 asserts the folded value, X32 that each alias converts).
- X33: `applyEnum` (`Converters.kt`) matches and displays each constant's `spelling`, `name.lowercase()` unless
  `.enum<E>(spelling = ...)` gives another, so a hyphenated value such as cp/mv `--update`'s `none-fail` has an
  enum spelling now. klap (pin the display and matching); the cp and mv fixtures still use `.choice()`.
- X34: `andThenConvert` composes after the choice stage (`Converters.kt`); `ConvertersTest` covers
  `.choice().int()` and `.choice().map { }` but not what the next stage sees after abbreviation, nor a
  `.validate` seeing the declared spelling (`.choice("Always").validate { it == "Always" }` with `always`).
  klap; fixture pacman `--debug` (`.choice("1", "2")`).
- X35: no klap converter for lists; fixtures use `.validate` (`find/Find.kt` `isFindTypeList`) or `.convert` pacman: `--ignore`, `--ignoregroup`, `--overwrite` take comma lists (man page only), a pacman target.
  (`dd/Dd.kt` `ddBadSymbol`). Observed: `cp --preserve=mode,links`, `mode,mode`, `mo,li` (per-element
  abbreviation) accepted; `mode,`, `,mode`, `mode, links`, `MODE`, empty refused; `dd conv=notrunc,sync` accepted,
  `notrunc,`, `notr`, `NOTRUNC` refused; bfs `-type f,l` accepted, `f,`, `,f`, `fl` refused. fixture cp
  (`--preserve`, `--no-preserve`), dd (`conv`, `iflag`, `oflag`), find (`--type`, `--xtype`). A candidate for
  a core `.list(separator)` converter (owner call).
- X36: git declares `.choice("+x", "-x")` (`git/Git.kt`, `add`'s `--chmod`); the spaced `-x` reaches the slot by
  the dash-led value rule (`Parser.kt` `valueFrom`). fixture git.
- X37: `convertOne` (`Bind.kt`) maps a converter error to `InvalidChoice` when `choices != null`, else
  `BadValue`; validate failures are always `BadValue` (covered: `ParseOptionsTest` "validate failure on
  enum backed option"). klap only; fixtures assert the refusal, never its kind or text.

## Optional values

| ID | Converter | Cells |
|---|---|---|
| X38 | `.optionalValue()`: an empty attached value | `--opt=` hands `""` to the converter, not `whenBare` |
| X39 | `.optionalValue()` on a short spelling | `-Sx` attached, `-S` bare, `-S x` bare with `x` an operand, `-S=x` (value `=x`), `-vS` bare at a cluster's end, `-Sv` (`v` the value) |
| X40 | `.optionalValue()` on an abbreviated long | `--col` bare, `--col=al` attached and abbreviated, `--co` ambiguous |
| X41 | `.optionalValue()` with `.default()` and `.required()` | absent gives the default; bare gives `whenBare`; attached gives its value; bare satisfies `.required()` |
| X42 | `.optionalValue()` against dash-led and end-of-options words | `--opt=-x`, `--opt -x` (bare, `-x` parsed on its own), `--opt --` (bare, then end of options), `--opt -` |
| X43 | `.optionalValue()` repeated | `--color --color=never`, `--color=never --color`: the last occurrence wins, bare or not |
| X44 | `whenBare` through `.validate()` | `.optionalValue("0").int().range(1..9)`: a bare occurrence and what construction says |

- X38: `valueFrom` takes `attached ?: bareValue` (`Parser.kt`), and `longToken` keeps `""` after `=` (`Parser.kt`).
  pacman: `--debug=` refused (klap agrees); an empty path value is refused by every path option but `--logfile` ("failed
  to resolve path ''"), a pacman target. Observed refused: `ls --color=`, `cp --update=`. klap; fixture ls, cp, mv, rm,
  git, pacman, mkdir.
- X39: the short-cluster branch passes `chars.substring(j + 1).ifEmpty { null }` (`Parser.kt` `sift`), and `takesValue`
  never takes the next word for an optional-value option (`ArgvScan.kt`, `valueAttachedOnly`). `ParseOptionsTest` "a
  short form binds attached and bare the same way" covers two of these. klap; fixture git (`commit -S`).
- X40: abbreviation resolves the name half of `--name=value` first. klap; fixture ls, cp (both
  `Abbreviation.Options`, verify per fixture).
- X41: `bindOptions` (`Bind.kt`). klap only.
- X42: `valueFrom` returns the bare value before looking at the next word. klap; fixture ls (`ls --color -`).
- X43: `bindOptions` binds `raws.last()` after converting every earlier one (`Bind.kt`). fixture ls, cp. pacman
  `--debug` is not last-wins: the levels accumulate, `--debug=2 --debug` stays at level 2 (observed function: lines), a
  pacman target.
- X44: `optionalValue` checks `whenBare` against a choice set at construction (`requireBareValueInChoices`) but not
  against `.int()` or `.validate`, so a bare occurrence may be refused on every line. needs checking. klap only.

## Defaults

| ID | Converter | Cells |
|---|---|---|
| X45 | `.default()` declared before a converter | `.default("5").int()` binds `5`; `.default("info").enum<L>()` binds the constant; `.default("ALWAYS").choice(...)` binds the declared spelling; a default the converter refuses fails construction |
| X46 | `.default()` and `.validate()` / `.range()` | absent binds a default the predicate would refuse (`.int().range(1..10).default(0)`), for an option and an operand |
| X47 | `.default()` behind a converter that yields null | `.map { it.toIntOrNull() }.default(0)` on `abc` binds `0`; `.int().default(0)` on `abc` is refused; the same for an operand |
| X48 | `.default()` against a given-but-empty value | `--opt=`, `--opt ''`, `-S ''` bind `""` (or are refused by the converter), never the default |
| X49 | `.default()` before a nullable `.map` | `.default("x").map { it.toIntOrNull() }`: what the stored default becomes |

- X45: `andThenConvert` converts a stored default, `canonicalDefaultOrThrow` the reverse order (both
  `Converters.kt`). `OpenFindingsTest` "a default declared before the converter is converted" and the
  choice/default tests cover parts; the enum case is not covered. klap only (construction failure asserted as a
  thrown `IllegalArgumentException`, not its text).
- X46: documented in `docs/guide.md` (".validate() / .range() predicates never run against a default");
  `ParseOptionsTest` "default bypasses validation on option" covers the option. klap (operand cell missing).
- X47: `Bind.kt` `bindOptions` (`outcome.value ?: c.value`) and `bindPositionals`; covered for an option
  (`ParseOptionsTest` "option map to null default substitutes default instead of npe on bad input", "option int
  default bad value still errors instead of being masked by default"). klap (operand cells missing).
- X48: `longToken` keeps `""`; observed `cp -S '' --backup f g` accepted. klap; fixture cp/mv (`-S`), tar
  (`--exclude ''`).
- X49: `andThenConvert` stores `Cardinality.Default(null)` when the stage maps the default to null
  (`Converters.kt`), so an absent option binds null on an accessor `.default()` had narrowed. needs
  checking. klap only.

## Multiple values

| ID | Converter | Cells |
|---|---|---|
| X50 | `.multiple()` with one bad occurrence | the bad one first, in the middle, last: the line is refused; during completion the whole list stays unbound |
| X51 | `.multiple()` behind a nullable converter | an option's occurrence converting to null is refused; an operand's binds `null` in its `List<T?>` |
| X52 | `.multiple()` with empty and repeated values | `--exclude '' --exclude a`, `--exclude a --exclude a`: both kept, in order |
| X53 | `.multiple(min)` on an operand against an option | `min` unmet with none given (operand: missing argument; option: too few occurrences) and with some given; `min` relaxed by `.optionalWhen()` |
| X54 | `.multiple()` across spellings | `-I a --ignore b --ignore=c -Id`: one list, in line order |

- X50: `convertOccurrences` / `convertAll` fail on the first bad raw (`Bind.kt`); under
  `BindPolicy.Lenient` one bad occurrence leaves the input unbound (`bindOptions`), which a completion
  provider reading it sees as V11. klap.
- X51: `convertOccurrences` turns a null into `BadValue(name, raw, "conversion failed")` (`Bind.kt`);
  `convertAll` keeps it. `ParseOptionsTest` "repeated null mapping option rejects the null element instead of
  binding it" covers the option side. klap (operand cell missing).
- X52: no filtering in `bindOptions`. fixture tar, rsync (`--exclude`), ls (`--ignore`, `--hide`), pacman
  (`--ignore`), curl (`-H`).
- X53: option `TooFewOccurrences` (`Bind.kt` `bindOptions`), operand `MissingArgument` or `TooFewOccurrences`
  and `optionalWhen` (`Bind.kt` `bindPositionals`). klap; fixture rm (`-f`), cp.
- X54: one `optionValues` list per spec (`Parser.kt` sift). fixture ls, curl, ssh (`-o`), git.

## Repeats of a single-valued input

| ID | Converter | Cells |
|---|---|---|
| X55 | a single-valued option given twice, the first invalid | `--width abc --width 80`: the tool refuses, and klap converts every occurrence and refuses too; cp's `--backup` reads only the last (`--backup=bogus --backup=never` exits 0), which the cp fixture models by declaring it `.multiple()` and reading the last word in `validateInputs` |
| X56 | a single-valued option given twice, both valid | `--width 40 --width 80` binds `80`; the same with `=` and a short glued form |

- X55: `bindOptions` (`Bind.kt`) converts every earlier occurrence before binding the last, so an invalid earlier
  one refuses the line (`ConvertersTest` "an invalid earlier occurrence of a single-valued option refuses the
  line"). Observed refused: `head -n abc -n 5 f`, `ls -w abc -w 80 .`, `ls --color=bogus --color=never .`,
  `dd bs=abc bs=1`; pacman `--color=bogus --color=never` and `--debug=3 --debug=2`. Observed accepted:
  `cp --backup=bogus --backup=never a b` (cp 9.12, 2026-10-12; `CpConverterTest`). klap, fixture head, ls, dd,
  pacman, cp.
- X56: same path. Observed `dd count=1 count=2` accepted. fixture ls, head, dd, curl, ssh (`-p`).

## What reaches the converter: the value word itself

| ID | Converter | Cells |
|---|---|---|
| X57 | dash-led values into a converter | `-n -5`, `-n-5`, `--lines -5`, `--lines=-5`, `--opt -` (stdin), `--opt --` (missing value) |
| X58 | `=` inside a value | `--opt=a=b` gives `a=b`; `--opt==x` gives `=x`; `-S=x` gives `=x` |
| X59 | an empty value through each converter | `--opt ''` and `--opt=` into `.int()`, `.double()`, `.boolean()`, `.choice()`, `.convert { }`, and a plain string |
| X60 | whitespace and control characters in a string value | a value of spaces, a tab, an embedded newline, leading or trailing space, non-ASCII, an emoji; bound verbatim |
| X61 | regexes and globs bound verbatim | `--exclude '*.o'`, `--grep '^fix'`, `--grep '('`, `-I '[a-'`; klap does not alter them, and a refusal belongs to the tool's run, not its parse |

- X57: `valueFrom`'s `next()` takes the next word whatever it is, `--` included, as getopt does (`Parser.kt`;
  `ParseOptionsTest` "end of options in a value slot is that value as getopt reads it"). klap; fixture head (`-n
  -5` accepted by real head), git (`-n`), find.
- X58: `longToken` splits on the first `=` (`Parser.kt`); the short case is covered (`ParseOptionsTest` "short
  option equals form still takes the attached value including the equals sign"). klap; fixture dd (operands are
  `key=value` words with `=` in the value), ssh `-o`.
- X59: each `numeric` stage refuses `""`; a plain string binds it. Observed refused: `ls -w ''`, `head -n ''`,
  `ssh -p ''`, `mkdir -m ''`, `chmod ''`. klap; fixture each tool with a value-taking input.
- X60: no trimming anywhere in the bind path. klap; fixture git (`-m`), curl (`-d`, `-H`).
- X61: string inputs. fixture tar, rsync, ls, git (`--grep`).

## Validation

| ID | Converter | Cells |
|---|---|---|
| X62 | `.validate()` whose predicate throws | an exception becomes a refusal, never a crash; a `CancellationException` from a predicate or `.map` propagates |
| X63 | what `.validate()` is handed | never `null` (a stage yielding null skips it), the declared choice spelling, each element of a `.multiple()`, the bare value, never a default |
| X64 | `.range()` at its edges | each end inclusive, one past each end, for `Int`, `Long`, `Double`, `Duration`; a range ending at `Int.MAX_VALUE` |
| X65 | `.validate()` on special inputs | on a `numberOption()`, on a global option before and after its subcommand, on an optional value's bare form |

- X62: `convertOne` (`Bind.kt`) catches converter and predicate exceptions and rethrows `CancellationException`,
  as `applyMap` (`Converters.kt`) does. klap only.
- X63: `convertOne` (`Bind.kt`) skips validate for null; `applyValidate` chains, first failure wins
  (`Converters.kt`).
  `ParseOptionsTest` "nullable map then validate on bad input skips validate" covers null. klap only.
- X64: `range` builds `validate("must be in ...") { it in range }` (`Converters.kt`; an `Int` range may be stepped
  or descending, `IntProgression`). klap; fixture ssh
  (`-p`, `.range(1..65535)`: observed `ssh -p 0` and `65536` refused, `65535` accepted), curl (`-m`), rsync
  (`--port` deliberately unranged). find's `-O` is no `.range()` any more: GNU find 4.11.0 accepts `-O4` to
  `-O65535` and refuses `-O65536`, so the fixture reads it with `findOptimisationLevel` (ASCII digits up to
  65535) through `.map`.
- X65: `numberOption` and globals share `convertOne`. klap; fixture git (`-n` and `-<n>` with `it > 0`), head.

## Custom converters

| ID | Converter | Cells |
|---|---|---|
| X66 | `.convert { }` error payload | a `ConversionError.Domain` arrives intact on `BadValue.cause`; a `.map { }` throw arrives as `ConversionError.Threw` |
| X67 | `.convert { }` returning `Ok(null)` | treated as absent: a default substitutes; on a `.required()` option, what binds |
| X68 | `.map { }` / `.convert { }` stacked | `.map { }.map { }`, `.convert { }.map { }`, `.map { }.convert { }`, a nullable stage feeding a String stage |
| X69 | `key=value` values | `k=v`, `k=`, `=v`, `k==v`, `k=v=w`, no `=`, the key's case, a space or tab separator (`Port 22`), an unknown key |
| X70 | file mode strings | octal of 1 to 4 digits, 5 digits, the digit `8`, leading zeros `00755`, `+0755`, `=644`, `-022`, symbolic clauses `u+x,go-w`, `=,u+x`, `u=g`, `a+X`, `-w`, a leading, trailing or doubled comma, empty, `0x1ff` |
| X71 | host specs and URLs | `host`, `user@host`, `user@host:port`, `ssh://user@host:22`, `[::1]`, `user@[::1]:22`, an empty user `@host`, `host::module` (rsync), a URL with and without a scheme, an unknown scheme |
| X72 | dates and times a tool reads itself | `git --date`/`--since`: ISO 8601, RFC 2822, `@1696852800`, `2 weeks ago`, `yesterday`; what klap-datetime would refuse |
| X73 | durations a tool reads itself | `curl -m 0.5`, `.5`, `1e1`, `-1`, `0`; `timeout`-style suffixes `1s`, `1m`, `1d`, `1.5s`, `inf` |

- X66: `ConversionError` (`CliError.kt`), `BadValue.cause`; covered for `.convert` (`ParseOptionsTest`
  "converters own error type survives to the parse caller"). klap only.
- X67: a null success is "absent" in `bindOptions` (`Bind.kt`); a `Cardinality.Required` option then binds null to a
  non-null accessor. needs checking. klap only.
- X68: `andThenConvert` (`Converters.kt`); `ParseOptionsTest` "nullable map feeding null into a later
  string stage yields bad value" covers the last. klap.
- X69: `dd/Dd.kt` `ddOperand`, `ssh/Ssh.kt` `sshOptionKeyValue` (splits on the first `=`, `split <= 0` refused).
  Observed with `ssh -G`: `Port=22`, `port=22`, `Port 22`, a tab separator accepted; `Port=`, `=22`, `Port=22=3`,
  `Port==22`, `Bogus=1` refused (the latter three by the key's own value check). The fixture accepts `Port=` and
  refuses `Port 22`: targets. fixture ssh (`-o`), dd (every key), git (`-c`, if declared).
- X70: `chmod/Chmod.kt` `parseChmodMode` (`.convert`), `mkdir/Mkdir.kt` (`.validate` with `[0-7]{1,4}`, so
  symbolic modes are refused), `find/Find.kt` `findPerm`. Observed chmod: `755`, `0755`, `00755`, `7755`, `+0755`,
  `=644`, `-022`, `u+x,go-w`, `=,u+x`, `a+X`, `u=g`, `-w` accepted; `17755`, `8`, `,u+x`, `u+x,`, empty refused.
  mkdir: `u+x`, `a=rwx` accepted (the fixture refuses: targets), `9`, empty, `0x1ff` refused. fixture chmod,
  mkdir, find (`--perm`).
- X71: ssh destination is a plain string with `.validate { it.isNotBlank() }` (`ssh/Ssh.kt`, `destination`); rsync and
  curl keep strings. fixture ssh, rsync, curl (parse-only cells; no network).
- X72: git `--date`, `--since`, `--until` are strings today. Expected values from `git` itself are not safe to
  run against a repository write; `git log --since=X` in a throwaway repository is read-only. fixture git.
- X73: `curl/Curl.kt` `.double().range(0.0..86_400.0)` (`-m`); observed `timeout 1.5`, `1e1`, `1d`, `inf` accepted,
  `-1`, `1x` refused (not a fixture, but the reference for a human duration). fixture curl.

## Planned converters (todo item 24): their valid and invalid forms

| ID | Converter | Cells |
|---|---|---|
| X74 | `.duration()` human style, accepted | `1s`, `5h 31m 2s 123ms`, `1h30m` (no space), `1h  30m` (two spaces), `90s`, `1.5h`, `1.5s`, `1d`, `1d 2h`, `1us`, `1ns`, `0s`, `-1s`, `-(1h 30m)`, `Infinity` |
| X75 | `.duration()` human style, refused | `2h 1d` (units out of order), `1m 1m` (a unit twice), `1` and `0` (no unit), `1S`, `1H` (case), `1 s` (space before the unit), ` 1s`, `1s ` (outer space), `1e3s`, `9999999999999999999d` (overflow), empty |
| X76 | `.duration()` ISO 8601 | `PT1H30M`, `P1D`, `P1DT2H`, `PT0.5S`, `-PT1S`, `PT1.5H`; refused `pt1h`, `PT`, `P`, `P1Y`, `P1M`, `P1W`, `PT1H ` |
| X77 | `.duration()` chained | `.duration().range(1.seconds..1.hours)`, `.default("5s").duration()` (a default converted), `.duration().multiple()`, `.optionalValue("1s").duration()` |
| X78 | `.instant()` accepted | `...T12:00:00Z`, `...+02:00`, `...-00:00`, `...+02` (hours only), `...+02:00:30`, lowercase `t` and `z`, nine fraction digits, `+12026-...` |
| X79 | `.instant()` refused | no offset `2026-10-09T12:00:00`, no seconds `...T12:00Z`, a space for `T`, `+0200`, `+19:00`, a leap second `:60`, `24:00:00`, ten fraction digits, a bracketed zone `[Europe/Vienna]`, epoch seconds `1696852800`, a `UTC` suffix, `2026-02-30T00:00:00Z` |
| X80 | `.localDate()` | `2026-10-09`, `2024-02-29`; refused `2026-02-29`, `2026-02-30`, `2026-13-01`, `2026-00-10`, `2026-1-9`, `20261009`, `2026/10/09`, `09.10.2026`, `2026-W41-5`, `2026-282`, ` 2026-10-09`, a date-time; edge years `0000-01-01`, `-0001-01-01`, `+12026-01-01` |
| X81 | `.localDateTime()` | `2026-10-09T12:00`, `...T12:00:00`, `...T12:00:00.5`, lowercase `t`; refused `...T12`, a space for `T`, `...T12:00Z` (a zone), a date alone, `...T25:00` |
| X82 | `.timeZone()` | `Europe/Vienna`, `UTC`, `Z`, `+02:00`, `+2`, `UTC+2`, `GMT+02:00`, `Etc/GMT-2`, `CET`; refused `europe/vienna`, `utc`, `Mars/Olympus`, empty, a trailing space; `EST` per platform |
| X83 | `.uuid()` | canonical, upper case, 32 hex digits without dashes, the nil UUID; refused braces `{...}`, `urn:uuid:...`, one digit short, outer space, `_` for a dash, dashes in other positions |
| X84 | `.path()` normalisation | `a/` against `a`, `a//b`, `//`, `/`, `./a`, `a/..`, `.`, empty `""`, `-`, `~`, `~/x`, `a\b`, `C:\x`, spaces, non-ASCII, a newline |
| X85 | `.path()` chained | implies `.file()` (V3); `.path().required()`, `.path().multiple(1)`, `.path().validate { }`, `.default(...)` before `.path()`; an absentWhen operand typed `Path?` |
| X86 | planned converters' refusals | each refusal arrives through `.convert { }` as `BadValue` with a `ConversionError` cause, and a converter error is never masked by a default |

- X74, X75: core, wrapping `Duration.parse`. All listed outcomes **observed** on the JVM (Kotlin 2.4.21).
  `Infinity`, `-(1h 30m)` and `1h30m` are accepted by `Duration.parse` and are not in todo 24's examples: the
  owner decides whether `.duration()` takes them. Native needs checking. klap; fixture curl (`-m` if it moves
  to a duration), pulse.
- X76: `Duration.parse` takes ISO too (observed `PT1H30M`, `P1D`, `P1DT2H`, `PT0.5S`, `-PT1S` accepted; `pt1h`,
  `PT`, `P1Y`, `P1W` refused). `PT1.5H` needs checking. klap.
- X77: planned on `andThenConvert`, so a default before it is converted and a `.validate` before it is refused at
  construction (both `Converters.kt` `andThenConvert`). klap.
- X78, X79: `klap-datetime`. Observed with `kotlin.time.Instant.parse` (stdlib, the type kotlinx-datetime 0.7+
  uses). The `+02` hours-only offset is accepted, the seconds-less `12:00Z` refused: the owner decides whether
  `.instant()` widens or narrows that. klap-datetime's own tests.
- X80, X81, X82: observed with kotlinx-datetime 0.7.1 on the JVM; the catalog pins 0.8.0. `TimeZone.of("EST")`
  refused and `CET` accepted on the JVM; the native targets read the system tz database, needs checking.
  klap-datetime; fixture git (`--date` would only partly fit, X72), find (`-newermt`, if declared).
- X83: core, wrapping `kotlin.uuid.Uuid`. Observed: `Uuid.parse` accepts dashed and plain hex in either case,
  `parseHexDash` only dashed, `parseHex` only plain; braces and `urn:uuid:` refused by all. Which one `.uuid()`
  uses decides the "without dashes" cell of X11. klap; fixture n/a today.
- X84: `klap-io`, wrapping `kotlinx.io.files.Path`. **Observed** (kotlinx-io 0.8.0, JVM): `Path("a/")` prints `a`
  and equals `Path("a")`, `a//b` becomes `a/b`, `//` becomes `/`; `""`, `-`, `~` stay as written. A trailing
  slash means something to `rsync src/ dst` and `cp -r a/ b`, so binding `Path` loses it: owner call before
  rsync and cp opt in. mingw separators need checking. klap-io; fixture rsync, cp, mv, tar (`-f -`), pacman
  (`-U`), git (`-F`, `--pathspec-from-file`), curl (`-o`).
- X85: todo 24 says `.path()` implies `.file()` and chains like a core converter. klap-io.
- X86: todo 24 ("refused through `.convert { }` with a `ConversionError`"). klap, klap-io, klap-datetime.

## Globals, completion and platforms

| ID | Converter | Cells |
|---|---|---|
| X87 | a converter on a global option | a bad value before the subcommand, after it, and on a bare group line that only shows help |
| X88 | conversion during completion | a provider reading an input whose typed value does not convert, or a `.multiple()` with one bad occurrence: nothing offered, no error |
| X89 | every core converter on every target | X14 to X28 and X74 to X83 run in `commonTest` on the JVM, linuxX64, mingwX64 and macosArm64, with one expected answer |

- X87: `bindGlobals` uses `BindPolicy.DeferRequired`, which defers a missing value but returns a conversion error
  at once (`Bind.kt` `bindOptions`), so `tool --global=abc group` may be refused where the group would only show
  help. needs checking. klap; fixture pacman (`--color`, `--debug`), git.
- X88: `completionValues` binds `BindPolicy.Lenient` (`Bind.kt`); cross-reference V11. klap; fixture any
  provider reading a converted input.
- X89: `toIntOrNull`, `toDoubleOrNull`, `Duration.parse` have per-platform implementations (the JVM delegates
  `toDoubleOrNull` to the Java parser after a screening regex), so X14 and X24 may differ by target. klap.

## Required cells

X is not a completion table, so it has no required cells. Its own rule stands: one cell per value-taking input, a value the
real tool accepts binding the typed value (asserted on the record, as P4) and a value the tool refuses refused.
