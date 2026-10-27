# Tables G and E: the suggestion list and what every completion cell asserts

Purpose: what the suggestion list holds (G) and what every completion cell asserts (E).

## Contents

- [G: existing rows](#g-existing-rows)
- [G: order](#g-order)
- [G: duplicates](#g-duplicates)
- [G: spellings and names](#g-spellings-and-names)
- [G: aliases and subcommands](#g-aliases-and-subcommands)
- [G: descriptions, source](#g-descriptions-source)
- [G: descriptions, wording and shape](#g-descriptions-wording-and-shape)
- [G: hidden inputs](#g-hidden-inputs)
- [G: negations](#g-negations)
- [G: built-ins](#g-built-ins)
- [G: the file directive](#g-the-file-directive)
- [G: case](#g-case)
- [G: prefix filter](#g-prefix-filter)
- [G: empty lists and failure](#g-empty-lists-and-failure)
- [G: special characters and the wire](#g-special-characters-and-the-wire)
- [G: what a shell shows](#g-what-a-shell-shows)
- [G: entries the real tool refuses](#g-entries-the-real-tool-refuses)
- [E: existing rows](#e-existing-rows)
- [E: new assertions](#e-new-assertions)
- [Required cells](#required-cells)

`‸` marks the cursor. In the notes, paths are short: `render/Completion.kt` is
`klap/src/commonMain/kotlin/com/fromwau/klap/internal/render/Completion.kt` (the planner, `completeCandidates`),
`Completion.kt` is `klap/src/commonMain/kotlin/com/fromwau/klap/Completion.kt` (`CompletionScope`, the four shell
scripts), `testing/Completion.kt` the public test API, `Runner.kt` the `__complete` printer (`toCompletionLine`).
"needs checking" marks a row whose klap behaviour was read from the source but not run, or where it is
unclear klap supports the property at all. Notes name functions and branches, not line numbers; re-checked against
the code on 2026-10-12.

## G: the suggestion list

What the list offered at a cursor holds, apart from which slot it is for. E1 asserts the list; these rows say what
"the list" must be.

### G: existing rows

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G1a | which entries: exactly what the real tool accepts there | nothing the tool refuses | `cp --b‸` offers `--backup` only |
| G1b | which entries: nothing missing | each input the real tool would offer there and the fixture lacks is a target in that list cell (G101) | `cp --‸` missing `--debug` |
| G2 | order | the order the fixture declares, or its provider returns; klap does not sort, the shell sorts as it does its own | `--level=‸` offers `debug`, `info`, `warn`, `error` as declared |
| G3 | no duplicates | one entry per value, however many spellings lead to it | `--recursive` once |
| G4 | spellings | `-‸` offers both spellings of each input, `--‸` only the long ones | `cp -‸` offers `-v` and `--verbose` |
| G5 | description source | a name its own help; an attached value without a description of its own its option's help; a cluster continuation the added letter's help | `--backup=existing`, `-Qi` |
| G6a | description wording | the real tool's words, for the app's own inputs | `cp --help` verbatim |
| G6b | description wording of a built-in | the real tool's words where it has the built-in; klap's own words there are a target (G45) | `cp --he‸` against cp's `display this help and exit` |
| G7 | no description | `null` where neither the entry nor its option has one | a spaced choice value |
| G8 | hidden inputs | never offered, though they still parse | a `.hidden()` option |
| G9 | aliases | a subcommand's aliases are entries of their own | `git sta‸` offers `stage` and `status` |
| G10 | negations | offered with their positive form, each with its own description | `--no-dereference` |
| G11 | built-ins | only those the real tool has | `cp` offers no `--json` |
| G12 | the file directive | the whole answer, never beside other entries | `cp --target-directory=‸` |
| G13 | case | values in the tool's own case; the typed prefix matched in its case | `--color=AL‸` offers nothing for a case-sensitive tool |
| G14 | prefix filter | entries extend the typed word, unless the provider matches on its own (V5) | `cp --ve‸` |
| G15 | an empty list | `[]` where nothing fits, the line left as typed | `cp --zzz‸` |

Disputed:

- G2. Disputed: klap's order is not one declared order, since names come grouped by kind (options, flags, globals, built-ins), each input's spellings in a fixed sequence and commands before operand values (G16 to G23).
- G5. Disputed: a value in its own word gets a `null` description from klap, not its option's help, so the row hides a difference between the spaced and attached forms that the owner should decide (G41).
- G7. Disputed: "a spaced choice value" reads either as a value containing a space or a value in the spaced form, and klap gives the spaced form `null` even where its option has help (G41, G52).
- G10. Settled: `Flag.negatable(..., help = ...)` gives the negative half words of its own, in `--help` and in Tab (`negativeHelp`); without it the negation carries its positive's description (G44).
- G13. Settled: a user `.choice()` or `.enum<E>()` matches in its exact case unless declared `ignoreCase = true` (`choiceIgnoreCase`), and the built-in `--color` always does (G76, G78). pacman backs G13: `pacman -Q --color=ALWAYS` is "invalid argument".

Splits:

- **G1** into G1a "nothing the tool refuses" and G1b "nothing missing" (G101), since the second is a list of targets per missing input and the first a filter on klap's list; the coverage report then names each.
- **G2** into G16 to G23: "the order declared" is not one order in klap. Names come grouped by kind (options, flags, globals, built-ins), each input's spellings in a fixed sequence, commands before operand values. A cell that says "as declared" cannot be written until the matrix says which of these it means.
- **G3** into G24 to G27: names are deduped (first wins, so an app's re-declared built-in name keeps its description), provider and hybrid lists are not. Each needs its own cell.
- **G5** into G41 (spaced value: no description today), G42 (glued short value), G43 (provider description over option help), G44 (negation), G45 (built-ins), G33 (aliases).
- **G6** into G6a "the app's own inputs" and G45 "built-ins" (here G6b): a built-in's words are klap's, so G6 as written makes every `--help`/`--version` entry a target. G50 covers wrapped help lines and G51 whitespace, so "verbatim" has a rule.
- **G7** into G52 (blank help is `null`, and a provider's `""`) and G41 (spaced values are `null` though the option has help).
- **G8** into G55 to G58 (hidden option value, operand, negation, global) and G34 (hidden command aliases): hidden suppresses names only, and the value of a hidden option still completes.
- **G10** into G59 to G63 and G44: a negation's own words come from `.negatable(help = ...)`, else its positive's help.
- **G11** into G64 to G70, one per built-in, since each has its own gate (`Help`'s `short`, `--help-all` under `Help.ByTree` on a command with a visible subcommand, `version != null`, `metaOptions`) and `-h` also shows up as a cluster continuation.
- **G12** into G71 to G75: drops subcommand names, the head for each word shape, the glued short with nothing after it, exclusivity, and the prefix-filter exemption.
- **G13** into G76 to G79: choices match by case unless `ignoreCase`, providers and names match by case, enums display lower case unless given a `spelling`.
- **G14** into G80 to G85: attached values filter after the head, a provider value gets the head prepended, a dash-led value in a value slot, a dash-led operand value, `=` inside a value, a value inside a cluster.
- **G15** into G86 to G90: a throwing provider, past the last slot, a used-up cluster, a cluster closed by a value-taking short, an ambiguous abbreviation in a value slot.

Decided: klap does not sort (G2), `-‸` offers both spellings and `--‸` only longs
(G4), aliases are entries of their own (G9), descriptions in the real tool's words, error text never asserted.

### G: order

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G16 | order across kinds | the name list holds the command's options, then its flags, then the globals, then the built-ins, whatever order the fixture declared them in; a cell asserts the order klap produces and states the declared order beside it | `cp -‸`: every `option(...)` name before every `flag(...)` name |
| G17 | order within one input | an input's longs in declared order, then its shorts, then its negative longs, then its negative shorts | `cp -‸` lists `--dereference`, `-L`, `--no-dereference`, `-P` in that order |
| G18 | order of the built-ins | `-h`, `--help`, `--help-all`, `--json`, `--color`, the version short, `--version`, `--completion`, `--docs`, each only where it exists, after every app entry | `git --‸` ends in `--help`, `--json`, `--color`, ... |
| G19 | order at a hybrid first slot | subcommand names and their aliases first, then the operand's values | a parent with its own operand and subcommands: `tool ‸` lists the commands, then the provider's values |
| G20 | order of aliases | each command's own name, then its aliases, command by command in declared order | `git ‸` lists `add`, `stage`, then the next command |
| G21 | order of cluster continuations | the remaining shorts in the order of G16 and G17: value-taking shorts before flag shorts, negative shorts after the positive ones, `-h` last | `cp -r‸` lists `-rS`, `-rt` before `-rf`, `-rv` |
| G22 | order of an enum's values | the enum's constants in declaration order, not alphabetical | `.enum<Format>()` offers its constants as written |
| G23 | order a provider returns, kept through the filter | the default prefix filter keeps the provider's order among the survivors | chmod `‸` offers `644`, `755`, `600`, ... as provided |

Notes:

- G16: `render/Completion.kt` name branch, `(cmd.options + cmd.flags + globalSpecs)` then the built-in `names +=` lines. Touches every fixture mixing `option` and `flag` (cp, ls, git, pacman). Whether "declared order" in G2 means this is the open question this row settles.
- G17: `render/Completion.kt` the `forEach` over `spec.longs`, `spec.shorts`, `negativeLongs`, `negativeShorts`. cp (`.negatable(short = 'P', long = "no-dereference")`), git (`--no-pager`, `-P`), ssh (`-a`).
- G18: `render/Completion.kt` the built-in `names +=` block. `CompletionTest.kt` `builtin option names carry their own help text`. Every fixture.
- G19: `render/Completion.kt` `return subcommands + positionalCandidates`. git root (subcommands only, so mostly `n/a`); `CompletionTest.kt` `complete files stays exclusive of a sibling subcommand name at the first positional`.
- G20: `render/Completion.kt` `sub.words.map { Candidate(it, help) }` (the name, then its aliases). git (`add`/`stage`, `remote remove`/`rm`).
- G21: `render/Completion.kt` the `inCluster` branch filters the same `names` list. cp, pacman (`-Q‸`), tar.
- G22: `Converters.kt` `applyEnum` sets `choices = spellings` in `values` order; planner `candidatesFor` `choices` branch. task-manager (`.enum<Priority>()`); `n/a` for a fixture without `.enum<E>()`.
- G23: `render/Completion.kt` `candidatesFor` `scope.collected.filter`. chmod mode provider, dd operand keys; `CompletionFlowTest.kt` `suggestions keep the order they are declared or provided in`.

### G: duplicates

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G24 | a name shared by an app input and a built-in | one entry, carrying the app's description | ls frees `-h` (`help = Help.ByCommand(short = null)`) and declares its own: `ls -‸` offers `-h` once, as `--human-readable`'s help |
| G25 | duplicates a provider returns | one entry per value, as the real tool would offer it (needs checking: klap keeps a provider's repeats, nothing dedupes `collected`) | a provider returning `a`, `a` |
| G26 | a subcommand name equal to an operand value at a hybrid slot | one entry (needs checking: the subcommand list and the operand list are concatenated without `distinctBy`) | a provider that also offers `status` beside the `status` command |
| G27 | cluster continuations | one entry per letter, even where two specs answer to it in some form | `cp -r‸` offers `-rP` once |

Notes:

- G24: `render/Completion.kt` `names.distinctBy { it.value }` keeps the first, and app specs are appended before built-ins. ls (`-h`), pacman and ls (`color = Builtin.Off` plus their own `--color`).
- G25: `render/Completion.kt` `candidatesFor` returns `scope.collected` without `distinctBy`; `Completion.kt` `CompletionScope.candidate`. No fixture today; likely a klap target if the owner wants dedupe.
- G26: `render/Completion.kt` `subcommands + positionalCandidates`. No fixture; klap test candidate.
- G27: `render/Completion.kt` inCluster branch `.distinctBy { it.value }`. cp, git (`-P` negative short of a global).

### G: spellings and names

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G28 | an input with several longs | every long is its own entry, each with the same description | an option declared `option(long = listOf("dereference", "deref"), ...)` lists both |
| G29 | a value-taking long is offered bare | `--backup`, never `--backup=` or `--backup ` with a no-space marker; the real tool's own shell completion may offer `--backup=`, a presentation difference noted, not asserted | `cp --backu‸` offers `--backup` |
| G30 | an optional-value long | offered once, bare, never also as `--color=`; its values only after the `=` (K11) | `ls --col‸` offers `--color` |
| G31 | a digit short (`numberOption`) | never a name entry: `head -‸` offers no `-NUM` placeholder, and a cluster continuation never offers a digit | `head -‸` |
| G32 | a short spelled only in a cluster | a short is offered both as `-x` on `-‸` and as a continuation, with the same description | `pacman -‸` and `pacman -Q‸` both carry `-i`'s help |

Notes:

- G28: `render/Completion.kt` `spec.longs.forEach`. Any fixture declaring two longs for one input (needs checking which do).
- G29: `render/Completion.kt` name branch adds `Candidate("--$long", help)` whatever the spec kind. cp, ls, pacman. Whether an `=` form should exist is an owner decision (needs checking against what the fish/bash completion shipped for the tool offers).
- G30: `render/Completion.kt` name branch; `trailingValueOption` refuses optional-value options (`valueOptional`). ls (`--color`, `--hyperlink`), cp (`--reflink`, `--sparse`).
- G31: `render/Completion.kt` the name list is built from `longs`/`shorts` only, `numberRunAt` only peels digits in `firstNonFlagShort`. head; `CompletionTest.kt` `a cluster opening with a run still offers the remaining shorts`.
- G32: `render/Completion.kt` both branches read the same `names`. pacman, tar.

### G: aliases and subcommands

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G33 | an alias's description | the aliased command's help, word for word the same as its name's, unless the real tool describes the alias in its own words | `git sta‸` gives `stage` and `status` their own commands' help |
| G34 | a hidden command's aliases | never offered, though they route | a `.hidden()` command with an alias |
| G35 | a hidden or deprecated spelling of a visible input | not offered where the real tool keeps it out of its own help and completion (needs checking: klap has no per-spelling hidden; every long and alias is offered) | GNU tools' undocumented long aliases; a deprecated git alias |
| G36 | subcommands only in the first operand slot | a word after an operand or after a mode never offers subcommand names | `git add f ‸` offers no `commit` |
| G37 | built-in subcommands | `completion`, `docs` and `__complete` are never offered on a dispatcher, though `completion` and `docs` still parse | `git ‸` offers no `completion` |
| G38 | a parent's own options after a subcommand | not offered once the line has routed past the parent, unless declared global | `git -C dir log --‸` offers no `-C` |
| G39 | globals at every depth | every visible global is offered under every subcommand, after that command's own names | `git remote add --‸` offers `--no-pager`'s global |
| G40 | a hidden command typed in full | its own inputs complete once its name is on the line (needs checking: `walkTo` uses `resolveSubcommand`, which may or may not skip hidden commands) | `tool hidden-cmd --‸` |

Notes:

- G33: `render/Completion.kt` `sub.words.map { Candidate(it, help) }`; `CompletionTest.kt` `subcommand aliases carry the same commands help as description`. git.
- G34: `render/Completion.kt` `filterNot { it.hidden }` before `flatMap`; `CompletionTest.kt` `complete candidates excludes hidden subcommand aliases`. No fixture today.
- G35: `render/Completion.kt` offers every entry of `spec.longs` and `sub.words`. Survey real tools for such spellings; likely a klap API gap.
- G36: `render/Completion.kt` `if (positionalIndex == 0)`. git.
- G37: `render/Completion.kt` subcommand branch filters hidden; `CompletionTest.kt` `complete candidates excludes hidden subcommands and builtins`. git (a dispatcher); a real tool with its own `completion` command would collide (needs checking).
- G38: `render/Completion.kt` `walkLine`/`walkTo` picks `cmd`, names come from `cmd.options + cmd.flags + globalSpecs`. git (`-C`, `-c` root options vs globals).
- G39: `render/Completion.kt` `globalSpecs` in the name list. git, pulse.
- G40: `render/Completion.kt` `walkTo`. klap test candidate.

### G: descriptions, source

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G41 | a value in its own word has no description | `--backup ‸` offers bare values with `null` descriptions, while `--backup=‸` describes each by the option's help (decide whether the spaced form should be described too) | `cp --backup ‸` against `cp --backup=‸` |
| G42 | a glued short value's description | the option's help, as an attached long value | `cp -Snu‸`-style glued values carry `-S`'s help |
| G43 | a provider candidate's own description over the option's | `candidate(v, d)` keeps `d` attached and spaced; `candidate(v)` attached takes the option's help, spaced stays `null` | dd `if=` carries its key help |
| G44 | a negation's description | the negative half's own words where the fixture gives them (`.negatable(help = ...)`, as the real tool words it), else the positive input's help | `git --no-pa‸` carries `--paginate`'s text unless the fixture words `--no-pager` |
| G45 | a built-in's description | klap's own words (`Show this help`, `Output as JSON`, `Show the version`, `Show help for every subcommand`, `Colorize output: auto, always, or never`, `Print a shell completion script`, `Print generated documentation`) unless the fixture words it; where the real tool has the same built-in (`--help`, `--version`), the fixture gives its words through `Help.ByCommand(description = ...)` (or `ByTree`, `allDescription` for `--help-all`), `Version.Of(description = ...)` and `Builtin.On(description)`, and klap's words left there are a target | `cp --he‸` gives cp's `display this help and exit` |
| G46 | a built-in's value candidates | `--color[=WHEN]` takes its value attached only, each value described by the built-in's text (`Colorize output: ...`); the word after a bare `--color` is the next slot | `git --color=‸` against `git --color ‸` |
| G47 | completion descriptions carry none of `--help`'s annotations | no `(default: on)`, `(one of: ...)`, `(required)`, `(repeatable)`, `(bare: ...)`, `(absent when ...)`: the bare help text only | `cp --no-deref‸` has no `(default: off)` |
| G48 | a group heading is not a description | inputs in a `group(title) { }` carry their own help, never the section title, and are not reordered by group | chmod's "Symlink traversal" flags, curl's "Request"/"Output" |
| G49 | a ruled-out member's description never reappears elsewhere | once a set member is given, the others vanish together with their negations and their cluster continuations | `tar -c -‸` offers neither `-x` nor `--extract`; `tar -c‸` no `-cx` |

Notes:

- G41: `render/Completion.kt` `candidatesFor` `choices` branch builds `Candidate(it)`; only `asWholeWord` adds the option's help. cp, ls, pacman, git; `CompletionTest.kt` `choice value completion carries no description`.
- G42: `render/Completion.kt` `attachedValueOption` short branch then `asWholeWord(..., attachedOption.help)`. cp (`-S`, `-t`), tar (`-f`), pacman.
- G43: `render/Completion.kt` `asWholeWord` `it.description ?: help`. dd (`candidate("$key=", help)` against `candidate("$key=$value")`), chmod.
- G44: `render/Completion.kt` `val negativeHelp = spec.negativeHelp ?: help` for `negativeLongs` and `negativeShorts`; `Converters.kt` `Flag.negatable(..., help)`; `CompletionTest.kt` `short and negated option names carry their help as description`. git, cp, chmod, curl, rm, ssh, pacman.
- G45: `render/Help.kt` `BuiltinOptionHelp`, overridden through `Builtins.kt` (`Help.ByCommand.description`, `Help.ByTree.allDescription`, `Version.Of.description`, `Builtin.On.description`) into `builtins.helpText` and the like. Every fixture whose tool has `--help`/`--version`; cp, find and pacman word `--help`.
- G46: `render/Completion.kt` `colorValueCandidates` (attached form only), `colorModeCandidates`; `CompletionTest.kt` `complete candidates offers no color value after a bare color`, `complete candidates prefix filters the builtin color option value`, `complete candidates completes the builtin color option value attached form`. Fixtures that keep `color` (git, tar and the rest; cp, find, ls and pacman set `color = Builtin.Off`).
- G47: `render/Help.kt` `FlagSpec.metaHint`/`ValueSpec.metaHint` append to help rows; the planner reads `spec.help` raw. Every fixture with negatables, choices, defaults, constraints.
- G48: `CommandBuilder.kt` `group` sets `currentSection`, which only `render/Help.kt` reads. chmod, curl.
- G49: `render/Completion.kt` `membersRuledOutBy` filters specs before both the full list and the cluster branch. tar, pacman, find, ls.

### G: descriptions, wording and shape

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G50 | a real tool's wrapped help line | the description is the whole help text on one line, the real tool's continuation lines joined with one space | a cp `--help` entry that wraps onto a second line |
| G51 | whitespace in a description | `suggestionsFor` gives the help as declared, while `__complete` trims it and turns tabs and newlines into spaces; a cell asserts the trimmed one-line form, so a fixture help with a stray space or newline shows | a help ending in a space |
| G52 | a blank description | `null`, whether the help is `""` or only spaces; a provider's `candidate(v, "")` is `null` too (needs checking: the name branch maps blank to `null`, a provider's blank description reaches `suggestionsFor` as `""`, and only the wire drops it) | a flag declared without help |
| G53 | a description equal to its value | kept, not dropped as redundant | a provider `candidate("all", "all")` |
| G54 | descriptions never match | a typed word that appears only in a description offers nothing | `cp --backu‸` matches no description text, `cp --make‸` offers nothing though `--backup`'s help says "make a backup" |

Notes:

- G50: G6 asks for verbatim; this pins how a wrapped `--help` row reads as one description. `render/Completion.kt` reads `spec.help` as declared. cp, ls, curl.
- G51: `Runner.kt` `toCompletionLine` sanitises; `testing/Completion.kt` `suggestionsFor` maps `completeCandidates` directly, skipping it. Every fixture. Needs checking whether the test API should go through `toCompletionLine` (an API gap if not).
- G52: `render/Completion.kt` `help.takeIf { it.isNotBlank() }` in the name and subcommand branches, none in `candidatesFor`; `Runner.kt` `toCompletionLine`. git (`dry run` style helps), task-manager.
- G53: `render/Completion.kt` keeps it; PowerShell's tooltip falls back to the value when there is none (`Completion.kt` `renderPowershell`). Low value; klap test only.
- G54: `render/Completion.kt` every filter is on `value`. cp, any fixture.

### G: hidden inputs

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G55 | a hidden option's value | completes once its name is typed, in both forms, though the name is never offered | `tool --secret ‸` offers its choices |
| G56 | a hidden operand | its slot offers nothing, not the next slot's values | an `argument(...).hidden()` slot |
| G57 | a hidden flag's negation | neither half offered | a `.hidden().negatable()` flag |
| G58 | a hidden global | not offered at any depth, still parses | a hidden `globalFlag` |

Notes:

- G55: `render/Completion.kt` `matchingValueOption` does not filter hidden; `CompletionTest.kt` `complete candidates completes a hidden options own value not the next positionals choices`. Fixtures with `.hidden()` (needs checking which).
- G56: `render/Completion.kt` `slotForOperand(...)?.takeUnless { it.hidden }`. ssh (hidden remote command operand, needs checking).
- G57: `render/Completion.kt` the spec is filtered before its negations are added. klap test candidate.
- G58: `render/Completion.kt` `globalSpecs` share the `filterNot { it.hidden }`. git, pulse (needs checking).

### G: negations

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G59 | an explicit negative spelling replaces the generated one | `.negatable(short = 'P', long = "no-dereference")` offers exactly those, never a generated `--no-<long>` for any other long | cp, git `-P`, ssh `-a` |
| G60 | a generated negation per long | a negatable with two longs offers `--no-` of each | a flag with `--foo` and `--foobar` |
| G61 | a negative short in a cluster | offered as a continuation with the negation's description (G44), and a cluster that starts with one continues (confirmed on cp 9.12, `cp -Pv a b` copies; done in klap, todo 59) | `cp -P‸`, `cp -rP‸` |
| G62 | a negation after its positive is given | still offered, since the pair overrides (`lastWins`-like) as the real tool reads it | `cp -L --no-d‸` offers `--no-dereference` |
| G63 | a negation inside a constraint set | the negative form does not count as giving the set's member, so the other members stay offered | a negatable member of `atMostOne` given as `--no-x` |

Notes:

- G59: `HolderSpec.kt` `negativeLongs` (generated only when `negativeNames` is empty), `negativeShorts`. cp, git, ssh.
- G60: `HolderSpec.kt` `longs.map { "no-$it" }`. Needs checking for a fixture with two longs on a negatable.
- G61: `render/Completion.kt` `firstNonFlagShort` reads a negative short as a flag letter; `CompletionTest.kt` `a negative short continues a cluster as any flag does`. cp, git.
- G62: `render/Completion.kt` no supplied filter in the name branch. cp, chmod, git.
- G63: `CommandBuilder.kt` `exactlyOne` KDoc ("only the positive form selects"), `render/Completion.kt` `membersRuledOutBy` via `supplied`. Needs checking for a fixture with such a set.

### G: built-ins

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G64 | `-h` where the tool has none or another meaning | not offered, and never as a cluster continuation (`cp -r‸` offers no `-rh`); where the tool's `-h` means something else, the tool's entry only | cp has no `-h`; chmod's `-h` is `--no-dereference` |
| G65 | `--help-all` | offered only under `Help.ByTree`, on a command with a visible subcommand; under the default `Help.ByCommand` never, and the name is the tool's own (no fixture uses `Help.ByTree`, so fixture cells assert its absence) | `git --help-a‸` offers nothing |
| G66 | `--version` | offered only when the fixture sets a version, and then where the real tool has `--version` | `cp --vers‸` |
| G67 | `--completion` and `--docs` | offered on a single-command or hybrid root unless switched off; never on a dispatcher, which hides its `completion`/`docs` commands | `cp --comp‸` |
| G68 | a built-in's values where the tool lacks the built-in | the slot answers as the real tool would read the word (an operand, a refusal), not with klap's values (checked on pacman: `pacman -Q --completion ‸` and `pacman -Q --docs ‸` offer nothing) | `cp --color ‸`, `cp --completion ‸` |
| G69 | a tool without `--help` | `Help.Off` removes `--help` and its short, so neither is offered | a tool without `--help` |
| G70 | built-ins inside a subcommand | the same built-ins at every depth, and under `Help.ByTree` `--help-all` only where that command has a visible subcommand | `git remote --‸` |

Notes:

- G64: `render/Completion.kt` `builtins.helpShort?.let { names += Candidate("-$it", builtins.helpText) }` and the inCluster branch takes every two-character name; `Help.ByCommand(short = null)` frees `-h`. cp, find, ls and chmod free it; chmod declares `-h` as `--no-dereference`.
- G65: `render/Completion.kt` `if (builtins.helpAll && cmd.subcommands.any { !it.hidden })`, `builtins.helpAll` set only by `Help.ByTree` (`Builtins.kt`); `CompletionTest.kt` `complete candidates offers help all when the command has visible subcommands`, `complete candidates omits help all for a leaf tool with no subcommands`; `HelpSettingTest.kt` `completion offers --help-all only under ByTree in its words`. git, task-manager (dispatchers, default `Help.ByCommand`: absent).
- G66: `render/Completion.kt` `if (version != null)`. Every fixture; checks the fixture sets `version`.
- G67: `render/Completion.kt` `if (metaOptions)`; dd's comment on declining them. cp, dd, head, ls (single-command).
- G68: `render/Completion.kt` `colorValueCandidates` runs before everything; no branch for the meta options. cp, head, dd.
- G69: `Builtins.kt` `Help.Off`; `HelpSettingTest.kt` `Off removes --help and has no --help-all`. Every current fixture's tool has `--help`, so `n/a`.
- G70: `render/Completion.kt` built-in block keyed on `cmd`. git.

### G: the file directive

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G71 | the directive drops subcommand names | at a hybrid first slot a `.file()` operand answers `filesAfter("")` alone | a parent with a file operand and subcommands |
| G72 | the directive's head | `filesAfter(head)`, the head exactly what precedes the path in the word: `--file=` attached, `-f` glued, `-xf` in a cluster, `if=` from a provider, `""` spaced | `tar -xf‸`, `tar -xfa‸`, `dd if=/d‸` |
| G73 | a glued short with nothing after it | `tar -f‸` is a name word, not the directive: klap offers names until one path character is typed | `cp -t‸` against `cp -td‸` |
| G74 | `completeFiles` is exclusive both ways | candidates before it are dropped, `candidate` calls after it are dropped | dd `of=‸` |
| G75 | the directive escapes the prefix filter | the directive is offered whatever is typed after the head | `tar -f /no/such/pre‸` still answers `filesAfter("")` |

Notes:

- G71: `render/Completion.kt` `if (positionalCandidates.isNativeFileRequest()) return`. `CompletionTest.kt` `complete files stays exclusive of a sibling subcommand name at the first positional`.
- G72: `render/Completion.kt` `asWholeWord` file branch; `CompletionScope.completeFiles(nonPathPrefix)`. tar, dd, cp `-t`, curl `-o`.
- G73: `render/Completion.kt` `attachedValueOption` returns null when `partial.isEmpty()`, unless the option takes its value only attached (`valueAttachedOnly`), whose bare short is offered glued to each candidate (`shortNames`). cp, tar, curl.
- G74: `Completion.kt` `CompletionScope.completeFiles` clears, `candidate` checks `filesRequested`; `CompletionTest.kt` `complete files discards candidates collected before it`, `complete files drops candidates added after it`. dd.
- G75: `render/Completion.kt` `candidatesFor` exempts it. tar, cp, dd.

### G: case

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G76 | choice matching follows the declaration's case rule, values keep their case | by default `.choice()` and `.enum<E>()` match in the exact case, so `--x=AL‸` offers nothing; declared `ignoreCase = true`, `--x=AL‸` offers `--x=always`, the value in the declared case replacing the typed one | curl `-X P‸` offers `POST`, `PUT`, `PATCH`, and `-X p‸` nothing |
| G77 | provider and name matching keep case | a provider's candidates and every name are matched case-sensitively | `cp --VERB‸` offers nothing |
| G78 | the built-in `--color` matches case-sensitively | `--color=AL‸` offers nothing, unlike a user `.choice()` | a tool that keeps the built-in |
| G79 | an enum's displayed case | constants shown lower case by default (`BEFORE_CONTEXT` as `before_context`); `.enum<E>(spelling = ...)` gives the tool's own spelling (dashes) | an `.enum<E>()` value slot |

Notes:

- G76: `render/Completion.kt` `choices!!.filter { it.startsWith(current, ignoreCase = choiceIgnoreCase) }`, `choiceIgnoreCase` from `.choice(ignoreCase)` (default false). curl, cp, ls, find. A fixture sets `ignoreCase = true` only where the real tool takes any case.
- G77: `render/Completion.kt` `startsWith(current)` in the name, subcommand and provider branches. Every fixture.
- G78: `render/Completion.kt` `colorModeCandidates` uses plain `startsWith`, while the parse's `resolveBuiltinChoice` may accept other cases (needs checking). cp, git, tar.
- G79: `Converters.kt` `enum`'s default `spelling = { it.name.lowercase() }`, applied by `applyEnum`. task-manager (`.enum<Priority>()`); klap tests.

### G: prefix filter

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G80 | an attached value is filtered on the part after its head | `--backup=n‸` filters values on `n`, not on `--backup=n` | cp |
| G81 | a provider that returns whole words for an attached slot | gets its head prepended again; a provider offers bare values there (decide whether a fixture provider returning `--opt=v` is a fixture bug) | `--tag=‸` with a provider giving `--tag=red` gives `--tag=--tag=red` |
| G82 | a value starting with a dash | in a value slot it completes as a value, not as names | git `--chmod ‸` then `-‸` offers `-x`; `chmod -w‸` (K17) |
| G83 | an operand value starting with a dash is unreachable once the dash is typed | at an operand slot a typed `-` switches to names, so a provider's `-1` is offered on no word only (unless `.dashLed()`, K17) | head-like operands, chmod modes `-w` |
| G84 | a value containing `=` | an attached value `--opt=a=b‸` filters on `a=b`, split at the first `=` only | dd `conv=`, `--define=k=v` |
| G85 | the cursor word inside a cluster is a value | after a value-taking short in a cluster, filter on what follows it | `tar -xfarch‸` filters on `arch` |

Notes:

- G80: `render/Completion.kt` `attachedValueOption` returns the partial; `candidatesFor(partial, ...)`. cp, ls, pacman, dd.
- G81: `render/Completion.kt` `asWholeWord` prepends `head` unconditionally; guide "a provider offers the bare value". dd (its operand provider returns `key=value` for a whole operand, not attached, so fine).
- G82: `render/Completion.kt` the `valueOption` branch runs before the name branch. git (`.choice("+x", "-x")`), chmod.
- G83: `render/Completion.kt` name branch `current.startsWith("-")` precedes the positional branch. chmod, find (`-name` style primaries), git.
- G84: `render/Completion.kt` `body.indexOf('=')`. dd, curl.
- G85: `render/Completion.kt` `attachedValueOption` short branch. tar, cp.

### G: empty lists and failure

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G86 | a provider that throws | `[]`, no error; a sibling slot's provider still works | a fixture provider reading a missing file |
| G87 | past the last operand slot | `[]`, where the parse would report too many arguments | `chmod 644 f ‸` is a file again (variadic), so only a tool with a fixed operand count has this cell |
| G88 | a cluster with every letter used | `[]`, or only counted letters | `tar -cvzf‸`-like with nothing left |
| G89 | a value-taking short closing a cluster | `[]` at the end of `cp -rS‸` (its value is the next word or the rest) | cp `-rS‸` |
| G90 | an ambiguous abbreviation in a value slot | `[]`: no option's values, since the parse binds none; an unknown option instead leaves the next word an operand (K47, decided 2026-10-11) | `cp --re ‸` with `--recursive`/`--reflink` |

Notes:

- G86: `render/Completion.kt` `runCatching { ... }.getOrNull()`; `CompletionTest.kt` `complete candidates returns empty when provider throws an error`, `complete candidates returns empty when provider throws but sibling provider still works`. dd, chmod, any provider.
- G87: `render/Completion.kt` `slotForOperand` returns null. dd, chmod (mode then files), ssh.
- G88: `render/Completion.kt` inCluster branch `filterNot { it.value[1] in typedShorts && it.value[1] !in counted }`. pacman, tar, cp.
- G89: `render/Completion.kt` inCluster gate fails, then `startsWith("-rS")` matches nothing, `attachedValueOption` returns null. cp, tar. Overlaps K7f; this row pins the empty list.
- G90: `render/Completion.kt` `matchingValueOption` returns null on `NameMatch.Ambiguous`, and the planner returns `[]` when `prev` resolves ambiguous (`resolvedLong`). cp, ls (abbreviating fixtures).

### G: special characters and the wire

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G91 | a value with a tab, newline or carriage return | dropped by `__complete`, so the shell never offers it; `suggestionsFor` still returns it (a test API gap to decide: assert the wire form) | a provider reading file names |
| G92 | an empty value | `__complete` prints an empty line, or nothing when it is the only one; `suggestionsFor` returns `Suggestion("")` (decide whether an empty value is ever offered) | `candidate("")` |
| G93 | a value with a space, quote, `$`, `!`, backtick or `\` | returned raw by klap; each shell escapes it on insertion (bash `%q` or the open quote's escaping, zsh inside `"`, PowerShell single quotes); fixture cells assert the raw value, real-shell tests the insertion | a provider value `a b`, `it's` |
| G94 | a unicode value | matched and inserted as typed, the prefix filter comparing UTF-16 units (needs checking for combining marks and case-insensitive choices beyond ASCII) | a choice `ü` |
| G95 | a very long list | every entry, in order, no truncation; the shell may ask before showing it | `pacman -S --‸`, `curl --‸` |

Notes:

- G91: `Runner.kt` `toCompletionLine` returns null; `testing/Completion.kt` skips it. Needs checking; any provider fixture (chmod, dd).
- G92: `Runner.kt` `if (text.isNotEmpty())`. klap test candidate.
- G93: `Completion.kt` `renderBash`, `renderZsh`, `renderPowershell` quoting; `RealShellCompletionTest.kt` `bash inserts a candidate so that a run receives it as offered`, `powershell quotes a candidate a run would not receive as offered`. Fixture cells `n/a` beyond the raw value.
- G94: `render/Completion.kt` `startsWith` on `String`. klap test candidate.
- G95: no limit anywhere in `render/Completion.kt`. pacman, curl, git.

### G: what a shell shows

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G96 | per-shell presentation | bash shows values only, zsh `value  -- description`, fish its tab form, PowerShell the description as tooltip (the value when there is none); fixture cells assert `Suggestion(value, description)` and leave presentation to real-shell tests | any |
| G97 | fish sees no words after the cursor | a cell whose answer depends on a later word holds in bash, zsh and PowerShell; in fish the later word reads as absent (stated in the cell's comment) | `get ‸ -f conf.toml` |

Notes:

- G96: `Completion.kt` the four `render*` functions; `RealShellCompletionTest.kt` `zsh fish and powershell show a candidates description and bash does not`. Fixture cells `n/a`.
- G97: `Completion.kt` `renderFish` comment; guide "fish shows a completion the line only up to the end of the word being completed". dd, git, pacman L3 cells.

### G: entries the real tool refuses

| ID | Property | What a cell checks | Example |
|---|---|---|---|
| G98 | options of another mode | a mode's options not offered under another mode where the real tool refuses them (needs checking: klap rules out only `exactlyOne`/`atMostOne` members and inputs refusing a repeat, not `requires` or a mode's own options) | `pacman -Q --need‸` offers no `--needed` |
| G99 | an input already given that the tool does not repeat | not offered again (S3); klap drops a given input that refuses a repeat (`.repeated(RepeatedOptions.Refuse)`) and keeps every other given input offered outside clusters | `cp -t d -‸` offers no `-t` |
| G100 | a ruled-out option's value | once named anyway, its value offers nothing, since the line is refused | `cp -T --target-directory=‸` |
| G101 | inputs the real tool has that the fixture lacks | each missing entry is a target in the list cell where the real tool would offer it | `cp --‸` missing `--debug` |

Notes:

- G98: `render/Completion.kt` `membersRuledOutBy` and `refusedBy` are the only filters on the name list. pacman, tar, find.
- G99: `render/Completion.kt` name branch drops `refused.repeats`; `membersRuledOutBy` KDoc says the supplied member stays offered on purpose. cp and mv (`-t`); every fixture, cross-reference S3.
- G100: `render/Completion.kt` the value branch and the attached branch return `[]` for an option `refusedBy` rules out (`valueOption in refusedBy(...)`, `attachedOption in refusedBy(...)`, todo 76). cp (`-t` with `-T`), tar, pacman.
- G101: G1's "nothing missing", split out so the coverage report names the missing inputs. Every fixture.

## E: what every completion cell asserts

### E: existing rows

| ID | Assertion |
|---|---|
| E1 | the exact suggestions, values and descriptions, in order |
| E2 | the line after `acceptSuggestion` of a named suggestion: pinned once in klap's `CompletionFlowTest` (`klap/src/commonTest`), not per fixture cell; a cell asserts its offered set |
| E3 | the line after `tab`: the one suggestion taken, what several share, or the line unchanged: pinned once in klap's `CompletionFlowTest`, not per fixture cell; a cell asserts its offered set |
| E4 | what must not be offered: anything the real tool refuses at that point |

Splits:

- **E1** into E1, E5 and E6: order, descriptions with `null`, and exact length are three different ways a list can be wrong.
- **E2** into E7 and E16: the accepted line per cursor column, and the chained next step.
- **E3** into E8 to E14: one, several sharing, several sharing nothing, the directive, empty, a case change, a fuzzy match. Each is a separate branch of `tab`.
- **E4** into E22 (per kind) and E18 (`tab` never builds a refused word), with E15 as its positive counterpart.

E2, E3 and their splits E7 to E14 are rules of klap's `acceptSuggestion` and `tab` (`testing/Completion.kt`), which
do not depend on the fixture: `CompletionFlowTest` pins them with literal lines (one suggestion, a shared prefix,
nothing shared, the file directive, the cursor inside a word). A fixture cell asserts the list it is offered (E1, E5,
E6) and does not rebuild the accepted line or the common prefix to compare with those helpers, which could only
fail when a copy drifts. Where a line a fixture's tool would take after a suggestion matters, E15 and E16 assert
it through the parse and the next step's list.

### E: new assertions

| ID | Assertion |
|---|---|
| E5 | every description asserted, `null` included: a cell compares whole `Suggestion(value, description)` lists, never values alone |
| E6 | the exact length: the list has no entry beyond the expected ones, asserted with list equality, never `containsAll` |
| E7 | the line after `acceptSuggestion` in every cursor column: end of word (the word replaced), inside (the typed part replaced, the rest of the word kept after it, `cp --verbose` + `rb` → `cp --verboserb`), at the start (the value inserted before the word, `cp --verbose--verb`), no word (inserted, the words after kept) |
| E8 | `tab` in every cursor column, the same way as E7 |
| E9 | `tab` with exactly one suggestion: the line holds that suggestion; with one suggestion equal to the typed word, the line unchanged (the space a shell adds after a sole match is not modelled, and a cell never asserts one) |
| E10 | `tab` with several sharing a prefix past the typed word: that prefix; sharing nothing past it: the line unchanged; a shared prefix that is a whole suggestion (`--color`, `--color-scheme`): extended to it, not beyond |
| E11 | `tab` on the file directive: the line unchanged, and `acceptSuggestion` is never applied to `filesAfter(...)` |
| E12 | `tab` on an empty list: the line unchanged (G15) |
| E13 | `tab` where the typed case differs from the suggestion's (G76): the typed word replaced by the declared case, as klap's shared prefix reads it; note whether the real shell would do the same |
| E14 | `tab` where a provider does not filter by prefix (V5): a shared prefix shorter than the typed word leaves the line; a single fuzzy match replaces the typed word, which zsh would refuse (noted per cell) |
| E15 | the accepted line is a line the real tool reads that way: the line once a suggestion is taken, written out literally (and the value or operands still missing), gives through `ParitySuite.accepts` or `binds` the record the real tool would, never `rejects` |
| E16 | a chained completion: after a name is taken, the next word's suggestions (`cp --backup` then `cp --backup ‸`, the second line written out), and after a cluster continuation, the next continuations |
| E17 | a completed word asked again: a name offers itself (plus longer names), a cluster offers only its continuations and never itself, an attached value offers itself |
| E18 | `tab` never builds a word the real tool refuses: where the shared prefix of the offered suggestions is itself a refused word, the cell's comment says so (`tab` itself is klap's, E3) |
| E19 | the wire form agrees: what `__complete` prints for the line (through `run` with a recording terminal) is the same list as `suggestionsFor`, value and description, tab-separated, after sanitising (G51, G91) |
| E20 | the completion description and the `--help` row agree: each name's description is the help text its `--help` row shows, less klap's annotations (G47) |
| E21 | the cell names its shells: one that differs in fish (G97) or in zsh/PowerShell whole-word replacement (E7) says so in its comment, and its expected value is bash's |
| E22 | what must not be offered is asserted per kind: a refused name, a refused value (wrong case for a case-sensitive tool), a refused cluster continuation, a hidden input, a built-in the tool lacks, each as its own `assertTrue(none { ... })` with the line and cursor in the message |
| E23 | an input with special characters is asserted raw: the unit API splits the line at spaces and tabs only, so a value with a space cannot be typed back into a cell's line; such cells assert `suggestionsFor` and leave quoting to real-shell tests |
| E24 | the answer does not change on a second call: `suggestionsFor` twice on the same line gives the same list (a provider with state, a lazy bind) |
| E25 | a Tab press runs no converter or validator where the slot has no provider: a fixture with a `.validate { }` or converter of side effects sees none on a name or choice cell (needs checking whether any fixture can observe it) |

Notes:

- E5: `testing/Completion.kt` `Suggestion` is a data class; `CompletionFlowTest.kt` `assertFlow` compares full lists. Every fixture.
- E6: guards G1/G101 both ways. Every fixture.
- E7: `testing/Completion.kt` `acceptSuggestion` replaces `line.take(wordStart)` to `cursor`; `CompletionFlowTest.kt` `the cursor right after an equals sign completes the value from there` (`--toml=1.1.1`). klap only (E2). Matches bash/readline; zsh, fish and PowerShell replace the whole word (`RealShellCompletionTest`).
- E8: `testing/Completion.kt` `tab` uses the same `wordStart`/`cursor`. klap only (E3).
- E9: `testing/Completion.kt` `tab` `if (shared.length > typed.length)`. klap only (E3).
- E10: `testing/Completion.kt` `commonPrefixWith` reduce; `CompletionFlowTest.kt` `tab takes the one suggestion or puts in what several share`. klap only (E3).
- E11: `testing/Completion.kt` `tab` checks `startsWith(COMPLETE_FILES)` on the first suggestion. klap only (E3); a fixture cell asserts `filesAfter(...)` as its offered set.
- E12: `testing/Completion.kt` `suggestions.isEmpty()`. klap only (E3); a fixture cell asserts `[]`.
- E13: `testing/Completion.kt` `commonPrefixWith` is case-sensitive, `render/Completion.kt` choices `ignoreCase = choiceIgnoreCase`. klap only (E3); reached only through an `ignoreCase = true` choice.
- E14: `testing/Completion.kt` `tab`; guide "zsh still offers only a candidate that extends what was typed". klap only (E3); a fixture with `filterByPrefix = false` asserts its offered set.
- E15: ties completion to `example/parity` `ParitySuite.accepts`/`binds`. Every fixture; the positive half of E4.
- E16: `suggestionsFor` on the next line, written out literally. cp, tar, pacman, dd (`if=` then `filesAfter("if=")`).
- E17: `render/Completion.kt` name branch `startsWith(current)` keeps the exact name; inCluster branch never returns `current`. cp, pacman, ls.
- E18: `testing/Completion.kt` `tab` (`commonPrefixWith`). Rare; pacman `-Q` options. E4's counterpart for `tab`.
- E19: `Runner.kt` `toCompletionLine`; `CompletionTest.kt` `complete through run emits value tab description line on stdout`; `example/parity` `wireSuggestions` (`CompletionContext.kt`) runs a line through `__complete` and decodes it; pacman's cells agree with `suggestionsFor`.
- E20: `ParitySuite.helpPage`; `render/Help.kt` row rendering vs `render/Completion.kt` `spec.help`. Every fixture.
- E21: `Completion.kt` script comments; `RealShellCompletionTest.kt`. Every L3 and inside-word cell.
- E22: splits E4 into checkable kinds; each maps to G1, G13, G21, G8, G11. Every fixture.
- E23: `testing/Completion.kt` `split(' ', '\t')`. Provider fixtures (chmod, dd); `RealShellCompletionTest.kt`.
- E24: `render/Completion.kt` lazy `sifted`/`wholeSifted`, user providers. Providers in dd, chmod.
- E25: `render/Completion.kt` `valuesFor` lazy, `CompletionScope` KDoc. ssh's comment on `.validate`/`.completeWith` above `destination` (`Ssh.kt`).

## Required cells

G and E have no required-cell rows of their own, since they apply to every completion cell. G says what the list at a cursor
must be, E what each cell asserts about it.
