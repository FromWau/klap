# P: projection

Purpose: every cell that checks the `cliOf { projection { } }` record a line binds, the existing rows P1 to P9 with their splits and the new rows P10 to P84.

- [Existing rows, with their splits](#existing-rows-with-their-splits)
- [The tree: commands, aliases, hybrids, dispatch](#the-tree-commands-aliases-hybrids-dispatch)
- [Globals](#globals)
- [Built-ins on a projected line](#built-ins-on-a-projected-line)
- [Option values: edges of the word](#option-values-edges-of-the-word)
- [Abbreviation on the record](#abbreviation-on-the-record)
- [Flags: negatable, counted, repeated](#flags-negatable-counted-repeated)
- [Numbers and folds](#numbers-and-folds)
- [Operands](#operands)
- [Repeats, overrides and defaults](#repeats-overrides-and-defaults)
- [Refusals and constraints](#refusals-and-constraints)
- [Required cells](#required-cells)

Every check is a hard assert on the whole projected record, written with typed values: a path is `Path("a")`, a
closed set of values an enum, a number a number, a time a `Duration` or `Instant`. A field projected as a raw
`String` where the real tool reads something more specific then fails to compile against the expected record, or
fails its equality. Which converter turns the string into the typed value, and whether it does so correctly, is the
converter table's business. Expected values come from the real tool; a refusal is a refusal, never its error text.
"needs checking" marks a claim about the real tool or about klap not confirmed from source alone; "no fixture yet"
marks a klap feature no fixture declares today, so its cells are `n/a` until one does.

Paths in the notes are short forms:

- `CliBuilder.kt`, `Parser.kt`, `Converters.kt`, `ActionScope.kt`, `Cli.kt`: `klap/src/commonMain/kotlin/com/fromwau/klap/`
- `parse/Parser.kt`, `parse/Bind.kt`, `parse/ArgvScan.kt`, `parse/NameMatch.kt`: `.../klap/internal/parse/`
- klap tests: `klap/src/commonTest/kotlin/com/fromwau/klap/`
- fixtures: `example/<tool>/src/main/...`

## Existing rows, with their splits

| ID | Check | How | Example |
|---|---|---|---|
| P1a | every command the real tool has resolves to its own record variant, by its canonical name | one `binds` per command | `git add f` binds `GitArgs.Add` |
| P1b | every alias, and every unique prefix where the tool abbreviates commands, resolves to its command's variant | one `binds` per alias and per unique prefix | `git stage f` binds `GitArgs.Add`, as `git add f` does |
| P1c | a hybrid's own variant beside each child's | one `binds` for the hybrid alone and one per child | `git remote` binds `Remote`, `git remote add o u` binds `RemoteAdd` (P12) |
| P2 | a group that only shows help, and every built-in, yields no record | `parse` gives `null` | `git remote`, `cp --help` |
| P3 | every declared input reaches the record | per input, a line setting only it binds a different record than the line without it | `cp -v a b` against `cp a b` |
| P4a | every option and operand field holds the typed value, never a raw string where the tool reads more | `binds` with the typed expected record | `--target-directory=d` binds `Path("d")`, `--backup=t` an enum value |
| P4b | every global field holds the typed value, on every variant | `binds` with the typed expected record, per command | `git -C d status` carries `directory = [Path("d")]` (the git fixture's `GitGlobals.directory` is `List<String>` today) |
| P4c | every `lastOf` fold and every `.absentWhen()` slot holds the typed value | `binds` with the typed expected record | `head -5 f` binds the lines as a number; `cp -t d a` binds `targetDirectory = Path("d")` |
| P5a | a required input is non-null | `binds` | `cp a b` gives `dest = Path("b")` |
| P5b | `.optional()` without default is `null` when absent | `binds` | `cp a b` gives `suffix = null` |
| P5c | `.default()` is the default when absent | `binds` | `head f` gives 10 lines |
| P5d | `.multiple()` is an empty list when absent | `binds` | `git commit` gives `message = []` |
| P5e | a plain flag is `false` when absent | `binds` | `cp a b` gives `verbose = false` |
| P5f | a negatable flag is its declared default when absent, not `false` | `binds` | `git commit` alone gives `edit = true` |
| P5g | a counted flag is `0` when absent | `binds` | `tar -cf a.tar f` gives `verbose = 0` |
| P5h | an `.absentWhen()` slot binds `null` (or its default) when its trigger is given | `binds` | `cp -t d a` gives `dest = null` |
| P5i | `.optionalValue()` is `null` when absent and its bare value when given bare | `binds` both | `git commit` against `git commit -S` |
| P6a | defaults are the real tool's defaults: the default when absent | `binds` on a line without the input | `head` without `-n` binds 10 lines |
| P6b | the default when the input lost a `lastWins` set (P74) | `binds`, asserting the loser's field | `cp -i -n a b` gives `interactive = false` |
| P6c | the default when a converter yields `null` (P77) | `binds` | needs checking which fixture has a nullable `.map { }` |
| P6d | a default the tool takes from its environment (`VERSION_CONTROL`, `SIMPLE_BACKUP_SUFFIX`) is not baked into the record | `binds` | `cp -b a b` claims no backup control or suffix the environment would supply |
| P7a | repeats bind as the tool reads them: counted flags (P55) | `binds` | `-vvv` gives 3 |
| P7b | `.multiple()` across spellings, in order (P73) | `binds` | `--exclude a --exclude b` gives `[a, b]` in order |
| P7c | a single-valued option twice: the later, the first, or refused (P72) | `binds` or `rejects` | `cp --suffix=a --suffix=b x y` gives `b` |
| P7d | `lastWins` sets across words and inside clusters, the loser absent (P74, P75) | `binds` | a `lastWins` pair keeps the later: `cp -i -n a b`, `cp -in a b` |
| P7e | negatable polarity (P52) | `binds` | `cp -L -P a b` gives `dereference = false` |
| P7f | an optional value bare after a value (P76) | `binds` | `cp --backup=numbered --backup a b` |
| P7g | globals across both passes (P22, P23) | `binds` | as P22 and P23 |
| P8a | every spelling of one value binds the same record: long, short and abbreviation | the same expected record for each spelling | `--backup=t`, `--back=t` |
| P8b | attached, spaced, glued, and at the end of a cluster | the same expected record for each spelling | `--backup=t`, `--backup t` where the tool allows it; `cp -S.b a b`, `cp -S .b a b`, `cp -rS.b a b` |
| P8c | negation spellings | the same expected record for each spelling | `cp --no-dereference a b`, `cp --no-deref a b`, `cp -P a b` |
| P8d | position: before, between and after operands, and for a global before, between and after the path (P20, P62) | the same expected record for each placement | `cp -v a b`, `cp a -v b`, `cp a b -v` |
| P9a | what the tool refuses gives no record: parse refusals | `rejects` | a missing operand or value, an unknown option, a conflicting pair, a flag given a value, a bad choice |
| P9b | refusals the fixture makes only in its action (P83) | `rejects`, a target while the check lives in the action | `cp -t d -T a b` |
| P9c | a refusal together with a built-in on the same line (P33, P34) | `helpPage`, `showsVersion` or `rejects` per order | `cp --help --bogus` |
| P9d | klap looser than the tool (P84) | `bindsLoosely` | `cp --json a b` |

Disputed (P2): P2 should split into a help-only group yielding `null` (P2a), a built-in the real tool has yielding `null` anywhere on the line (P2b) and a built-in klap injects that the tool lacks being refused rather than `null` (P2c, as the cp and pacman targets say), and `git remote` is a hybrid in the git fixture (it binds `Remote`), so a real help-only group is bare `git`, whose real answer is usage with exit 1 (P37).

Disputed (P3): "binds a different record" still passes when two same-typed constructor arguments are swapped or one handle is read into two fields; split P3 into the baseline record with exactly that input's field changed (P3a), globals and `lastOf` folds counted as inputs (P3b), and hidden inputs counted (P3c).

- P1: aliases and prefixes go through `resolveSubcommand`'s pool, and a hybrid's own reader is found by `Projection.claim`: three code paths that can each break alone. P1c is also where P12 lives.
- P4: the typed-value claim is made once per input kind; globals are read through a shared helper (`globals()` in git) and a fold through its own handle, each a separate place a raw `String` can slip in.
- P5: each binds its absent value through a different branch (`bindFlags`, `bindOptions`, `bindPositionals`, `absentValue`), and the negatable and `absentWhen` cases are the ones a reader most often gets wrong.
- P6: a default reaches the record by three routes in klap, and the environment case is one klap does not model, so the record must not claim a value the tool would take from elsewhere.
- P7: "repeats bind as the tool reads them" covers seven separate mechanisms; one cell per mechanism makes a missing one visible in the coverage report.
- P8: spelling and position are separate axes: the same spelling can bind differently by position (a global inside a mixed cluster, an option after the first operand under `optionsEndAtFirstOperand`).
- P9: P9a is what `rejects` checks today. P9b is invisible to it because `parse` never runs the action. P9c depends on the order rule, and P9d needs the opposite treatment (`bindsLoosely`).

## The tree: commands, aliases, hybrids, dispatch

| ID | Check | How | Example |
|---|---|---|---|
| P10 | every command's projection runs without throwing on every line it accepts: a projection reading an input its command does not bind, or forcing a nullable with `!!`, throws out of `parse` instead of returning a record or a `CliError` | per command, `binds` on its smallest accepted line and on a line setting every one of its inputs; for an `.absentWhen()` slot, both with and without the trigger | `cp -t d a` against a projection that reads `dest()!!`; a `remote add` projection that reads `remote`'s `-v` |
| P11 | each command projects through its own reader, never through the root's fallback reader | two commands whose lines differ only in the command name bind different variants | `git status` binds `GitInputs.Status`, `git var` binds `GitInputs.Var` |
| P12 | a command that both acts and nests projects its own variant when no child is named, and the child's variant when one is | `binds` on the bare hybrid, the hybrid with each of its own inputs, and each child | `git remote` and `git remote -v` bind `Remote`, `git remote add o u` binds `RemoteAdd` |
| P13 | a hybrid's first operand equal to a child's name routes to the child; after `--` it is the hybrid's operand, or refused where the hybrid takes none | `binds` or `rejects` | `git remote add o u` routes; `git remote -- add` as real git reads it (needs checking) |
| P14 | a hybrid's own option written before a child's name binds as the real tool reads it | `binds`, or a target where klap refuses | `git remote -v add o u`: real git accepts (fixture comment, git 2.55.0), klap's walk stops at `-v` |
| P15 | an input of one command written on another command's segment is refused: a sibling's, a parent's after the child's name, a child's before it | `rejects` | `git status --amend`, `git remote add -v o u`, `git remote -f add o u` |
| P16 | a hidden command and a hidden input project exactly like visible ones | `binds` | `git var` binds `Var(NO_GLOBALS)` |
| P17 | a leaf two or more levels deep projects with inputs from every level on the line: globals before, between and after the path, and its own inputs | `binds` | `git -C a remote add -f o u -C b` binds `RemoteAdd(directory = [a, b], fetch = true, ...)` |
| P18 | subcommand abbreviation follows the tool: a prefix binds under `Abbreviation.All`; a prefix reaching a name and an alias of one command binds that command; a prefix reaching two commands is refused; under `Options` and `None` every prefix is refused | `binds` and `rejects` | `git sta` refused under `Options`, as real git refuses it |
| P19 | an alias binds the same record as the canonical name in every spelling of every input, not only on the bare line | the canonical name's cells rerun under each alias | `git stage -n f` equals `git add -n f`; `git remote rm o` equals `git remote remove o` |

- P10: `TypedCli.parse` / `readerFor` (`CliBuilder.kt`) call the reader with no try/catch; `ActionScope.unbound` (`ActionScope.kt`) throws `IllegalStateException`. Touches cp and mv (`dest` is `.absentWhen(targetDirectory)`), chmod (`mode` `.absentWhen(reference)`), git (`globals()` helper on every variant).
- P11: `TypedCli.readerFor` falls back to `readers[null]`, and `validateEveryExecutableCommandProjects` (`CliBuilder.kt`) returns early when a root reader exists, so a root `projection { }` silently covers every child. git; `ProjectionTest.each command projects to its own variant`.
- P12: `Projection.claim` and `readers()` (`CliBuilder.kt`); `Command.isGroup` (`Cli.kt`) is false for a hybrid, so `bind` takes the leaf path. git `remote`; `ProjectionTest.the groups own projection answers when no child ran`.
- P13: the subcommand walk in `parseTokens` (`Parser.kt`); `bind`'s group branch `SubcommandAfterSeparator` and `tooManyArguments`/`UnroutedSubcommand` (`parse/Bind.kt`). git `remote`.
- P14: the walk in `parseTokens` breaks at the first token that neither routes nor `routesTransparently`. git `remote` (KLAP-GAP comment in `Git.kt`).
- P15: `Command.sift` (`parse/Parser.kt`) sees only the resolved command's specs plus globals. git; `ArgvOrderInvarianceTest.a child's local option before the subcommand name is refused the same way in every ordering`.
- P16: `resolveSubcommand` (`Cli.kt`) keeps hidden children; `longMatchPool` keeps hidden inputs. git `var`; no fixture has a hidden input yet.
- P17: `siftGlobals` pre-strip plus the global top-up in `sift`, bound by `bindGlobals` (`parse/Bind.kt`). git; `ArgvOrderInvarianceTest.a global reaches a three level path from any position on the line`.
  Disputed: the agreed K38 cells record that real git refuses a global after the subcommand (`git remote add -C . o u`, `git status --no-pager`, `git status -c x=y`), so the `-C b` after the path is a refusal in real git.
- P18: `resolveSubcommand` (`Cli.kt`) over `resolveName` (`parse/NameMatch.kt`), gated on `Abbreviation.All`. git (`Options`, aliases `stage` and `rm`); `AbbreviationModeTest`.
- P19: `Command.subcommand` (`Cli.kt`) resolves an alias to the same `Command`, so the same reader. git `add`/`stage`, `remote remove`/`rm`.

## Globals

| ID | Check | How | Example |
|---|---|---|---|
| P20 | a global binds the same record wherever it sits: before the path, between levels, after the path, between operands, last | the same expected record for each position | `git -C d status` equals `git status -C d` |
| P21 | every variant carries the same global values, and each global's default on every variant that does not give it | `binds` per command with and without the global | `git --bare status` and `git --bare log` both carry `bare = true` |
| P22 | a repeated `.multiple()` global keeps argv order across both passes (the pre-strip and the command's own sift) | `binds` | `git -c a=1 status -c b=2` gives `config = [a=1, b=2]` |
| P23 | a negatable global takes the polarity written last, across positions and passes | `binds` | `git --no-pager status --paginate` gives `paginate = true`; `git -P status` gives `false` |
| P24 | a global short in a mixed cluster after the subcommand binds both letters; the same cluster before the subcommand is refused; each as the real tool reads it | `binds`, `rejects`, or `bindsLoosely` where the tool refuses | `git status -sP`: klap binds `short` and `paginate = false`, real git has no `-P` after the subcommand (needs checking) |
| P25 | a global after `--` is an operand, not the global | `binds` | `git log -- --bare` gives `paths = [--bare]`, `bare = false` |
| P26 | a value-taking global takes the next word even when it names a subcommand or an option | `binds` | `git -C status status` gives `directory = [status]` and `Status`; `git -C --bare status` gives `directory = [--bare]` (real git: needs checking) |
| P27 | a global with an optional value given bare before a subcommand binds its bare value and still routes | `binds` (or a pinned divergence) | `git --exec-path log` binds `Log` with `execPath = /usr/lib/git-core`; real git prints the path and exits |
| P28 | a required global, or a `.multiple(min >= 1)` global, missing: a leaf is refused, a help-only group still yields `null` | `rejects` and `parse` gives `null` | no fixture yet |
| P29 | a letter the real tool gives one meaning before the path and another after it binds each meaning in its place | `binds`, a target while klap reserves a global's short tree-wide | `git -p log` (paginate) against `git log -p` (patch) |

- P20: `siftGlobals` (`parse/Parser.kt`) and the segment sift's `clusterHit` top-up. git; `ArgvOrderInvarianceTest.root globals bind the same wherever they sit around a subcommand and its operand`.
  Disputed: the agreed K38 cells record that real git refuses a global after the subcommand (`git remote add -C . o u`, `git status --no-pager`, `git status -c x=y`), so `git status -C d` is a refusal in real git, not the same record as `git -C d status`.
- P21: globals bound into every leaf's sink in `parseTokens` (`Parser.kt`). git `globals()` helper; `ProjectionTest.a root global reaches every variant including a nested one`.
- P22: `inArgvOrder` in `bindGlobals` (`parse/Bind.kt`). git `-c`, `-C`; `GlobalOccurrenceOrderTest.a repeatable global collects in argv order across both passes`, `OpenFindingsTest.a repeatable global keeps argv order`.
  Disputed: the agreed K38 cells record that real git refuses `git status -c x=y`, so the `-c b=2` after `status` is a refusal in real git.
- P23: polarity tracking in `GlobalAccumulator.hitFlag`, `bindGlobals`. git `--paginate`/`--no-pager`/`-P`; `GlobalOccurrenceOrderTest` (both polarity tests).
  Disputed: the agreed K38 cells record that real git refuses `git status --no-pager`, so a polarity global written after `status` is a refusal in real git.
- P24: `sift`'s cluster walk via `clusterHit`; the pre-strip leaves a mixed cluster whole. git; `ArgvOrderInvarianceTest.a mixed short cluster binds the same wherever it sits and in either spelling`; guide "Global / persistent options". Evidence (K38 decision): real git refuses `git status --no-pager` and `git status -c x=y`; `-P` after the subcommand itself was not run.
- P25: `ArgvScan.headSize` and `siftGlobals` stop at `--`. git, every fixture with a global.
- P26: `ArgvScan.valueSlots`, read off `ArityWalk` (`parse/ArgvScan.kt`), and `valueFrom` (`parse/Parser.kt`). git `-C`.
- P27: `valueFrom`'s `bareValue` branch (`parse/Parser.kt`). git `--exec-path` (divergence noted in `Git.kt`).
- P28: `BindPolicy.DeferRequired` in `bindGlobals` and the deferred error check in `parseTokens`. No fixture declares one.
- P29: `validateGlobalCollisions` (`internal/builder/BuilderValidation.kt`). git (KLAP-GAP comment on `-p`).

## Built-ins on a projected line

| ID | Check | How | Example |
|---|---|---|---|
| P30 | a built-in the real tool has yields `null` wherever it sits on an otherwise valid line: after operands, inside a cluster, abbreviated where unique, between path levels | `parse` gives `null`, plus `showsHelp`/`showsVersion` for which one | `cp a b --help`, `cp -vh a b` (where the tool has `-h`), `ls --he` |
| P31 | a built-in klap injects that the real tool lacks is refused, or binds with the tool's own meaning: `--json`, `--color`, `--help-all` (only under `Help.ByTree`), `-h`, `--version`, `--completion`, `--docs`, `completion`, `docs` | `rejects` per built-in, or `binds` with the tool's meaning | `cp --json a b` binds the same record as `cp a b` today; `ssh --version` |
| P32 | a built-in's name written as an option value, after `--`, or as a first operand binds verbatim where the tool reads it so | `binds` | `cp -S --help a b` gives `suffix = --help`; `cp -- --help b`; `cp __complete b` gives `sources = [__complete]` |
| P33 | precedence between built-ins and refusals follows the real tool's order: getopt acts on whichever option comes first | `helpPage`, `showsVersion` or `rejects` per order | `cp --help --bogus` prints help in real cp, klap refuses; `cp --help --version` prints help in real cp, klap prints the version |
| P34 | help on a line that is otherwise incomplete or carries a bad value answers as the real tool does | `showsHelp` or `rejects` | `cp --help` with no operands; `cp --backup=bogus --help` (real cp refuses the value first, needs checking) |
| P35 | the help page shown is the one the real tool shows for that position: the deepest command named, wherever the request sits | `helpPage` | `git remote add --help`, `git --help remote add`, `git remote --help` |
| P36 | under `optionsEndAtFirstOperand`, a built-in's spelling after the first operand is an operand, as the real tool passes it on | `binds` | `ssh web1 echo --json` gives `remoteCommand = [echo, --json]`; klap strips `--json` today |
| P37 | a line that is empty, or names only a help-only group, answers as the real tool does: the record of defaults, a refusal, or help | `binds`, `rejects`, or `parse` gives `null` | bare `ls` binds; bare `cp` is refused; bare `git` prints usage and exits 1 in real git, `null` in klap |
| P38 | a help-only group given an unknown word or option is refused, never `null` | `rejects` | `git bogus`, `git --bogus` |

- P30: `hasHelpRequest`, `namesHelpShort`, `routesTransparently` in `Parser.kt`. pacman, cp, find, ssh; `ProjectionTest.a built in answers with null because no command ran`.
- P31: `builtinLongs` (`Parser.kt`) and `builtins { }` (`Builtins.kt`); the projection's `ValueScope` cannot see `Globals.json`, so `--json` changes nothing on the record. cp and pacman targets `an option cp does not have is refused`, `an option pacman does not have is refused`; ls declines some.
- P32: value slots skipped by every built-in scan (`ArgvScan.valueSlots`), `ArgvScan.headSize` for `--`, `routeBuiltin` and the always-injected `__complete` (`CliBuilder.kt`). cp target `__complete is a source file name`; guide "Global / persistent options".
- P33: the ladder in `parseTokens` (`Parser.kt`): version before help regardless of order, and an `UnknownOption` from `cmd.sift` raised before help. cp, ls, head, rm, chmod (GNU getopt tools). Evidence (pacman 7.1.0): `pacman --help --version` and `--version --help` both print the usage, `-Q --help --bogus` and `-Q --version --bogus` are refused; pacman targets.
- P34: `parseTokens`: "an unresolvable NAME outranks help, a bad ARGUMENT does not". cp `--backup`, ls `--sort`. Evidence (pacman 7.1.0): `pacman -Q --color=bogus --help` and `-Q --debug=3 --help` print the -Q page (klap agrees), while `pacman -Q -T --help` is refused as two operations (a pacman target).
- P35: `Invocation.ShowHelp.qualifiedName` from the walk; `ParitySuite.helpPage`. git, pacman target `an operation's help lists that operation's options only`.
- P36: `ArityWalk.slots` deliberately does not stop at `optionsEndAtFirstOperand` for the built-in scans (`parse/ArgvScan.kt`). ssh (`SshParityTest` pins the `--json` strip today).
- P37: `bind`'s group branch returns `ShowHelp` (`parse/Parser.kt`); `bindPositionals` for a leaf. Every fixture; git for the group case.
- P38: `bind`'s group branch `UnknownOption`/`UnknownSubcommand`. git.

## Option values: edges of the word

| ID | Check | How | Example |
|---|---|---|---|
| P39 | a value in its own word that starts with a dash binds as the value | `binds` | `git add --chmod -x f` gives `chmod = -x`; `git commit -m --amend` gives `message = [--amend]`, `amend = false` |
| P40 | a value word that is exactly `--` binds as the real tool reads it: getopt gives it to the option, and so does klap | `binds` | `cp -S -- a b` (needs checking per tool) |
| P41 | an empty value binds the empty string, attached and spaced, and an empty operand binds `""` | `binds`, or `rejects` where the tool or the converter refuses | `cp --suffix= a b`, `cp -S "" a b`, `cp "" b` |
| P42 | a value containing `=` keeps everything after the first `=`; a short's `=` is part of its value | `binds` | `--context=a=b` gives `a=b`; `cp -S=x a b` gives `suffix = =x` |
| P43 | in a cluster, the rest of the word after a value-taking short is its value, so no later letter is a flag | `binds` | `cp -Svb a b` gives `suffix = vb`, `verbose = false` |
| P44 | `.optionalValue()` on a short: glued binds the value, spaced leaves the next word an operand, at the end of a cluster binds the bare value | `binds` | `git commit -Sabc` gives `gpgSign = abc`; `git commit -S abc` gives the bare value and `pathspec = [abc]` |
| P45 | an option's value equal to a subcommand name or a global's spelling stays the value | `binds` | `git commit -m status`, `git commit -m -C` |

- P39: `valueFrom`'s `next()` branch (`parse/Parser.kt`); `ArgvScan.valueSlots` keeps built-ins out of the slot. git `add --chmod` (comment in `Git.kt`), git `commit -m`.
- P40: `valueFrom`'s `next()` takes the next token whatever it is, `--` included (`parse/Parser.kt`; `ParseOptionsTest` `end of options in a value slot is that value as getopt reads it`), in both `sift` value branches. cp `-S`, tar `-f`, head `-n`. Settled for pacman: getopt hands `--` to a required value, `pacman -Qv --dbpath --` prints `DB Path: <cwd>/--/`, which klap's reading now matches.
- P41: `longToken` keeps an empty `inlineValue`; `isFlagLike` is false for `""`. cp; converter outcomes belong to X.
- P42: `longToken` splits at the first `=`; the cluster's `attached = chars.substring(j + 1)`. cp `--context`, `-S`; matrix note under K7g.
- P43: the cluster walk ends at the first option (`j = chars.length`). cp, tar (`-cvf`), ssh.
- P44: `valueFrom(attached)` with `bareValue` (`parse/Parser.kt`). git `commit -S`, pacman `--debug` (long only).
- P45: `ArgvScan.valueSlots` and the walk not stripping a value word. git.

## Abbreviation on the record

| ID | Check | How | Example |
|---|---|---|---|
| P46 | an exact spelling wins over a longer one it prefixes; a unique prefix binds; a prefix reaching two spellings is refused | `binds` and `rejects` | `ls --sort=size` exact; `ls --di` is refused by GNU ls (`--directory` and `--dired`); a fixture lacking `--dired` binds `--directory`, a gap this cell exposes (ls, todo 124) |
| P47 | a prefix unique on the resolved command but shared with a long declared elsewhere in the tree binds as the real tool binds it | `binds` | `git remote add --f o u` and `git remote add --ma o u`: unique among `add`'s own longs (`--fetch`, `--master`), though `--force`, `--file` and `--max-count` stand elsewhere in the tree; real git accepts both, and klap resolves against the command's own longs since todo 77 (see the note) |
| P48 | a prefix that also reaches a built-in is refused, or binds as the real tool reads it | `binds` or `rejects` | `--ver` beside `--verbose`; `--he` beside `--header` |
| P49 | a negation abbreviates like any long, and a prefix of two negations is refused | `binds` and `rejects` | `cp --no-deref a b` gives `dereference = false`; `chmod --no-` refused |
| P50 | a tool that does not abbreviate refuses every prefix; a tool marked `Abbreviation.None` whose real binary does abbreviate is a target | `rejects` or a target | curl (`curl --verbos`: "option --verbos: is unknown") |
| P51 | a global's long abbreviates by the pool of the command in scope where it stands, before the subcommand the root's and after it the command's, each as the real tool reads it | `binds` and `rejects` | `git --bar status` and `git status --bar` |

- P46: `resolveName` exact-first, then `infer` prefix (`parse/NameMatch.kt`); `resolveLong` with `YIELDING_LONGS` (`--help-all` yields `--h` to `--help`). ls, cp, pacman (`an ambiguous abbreviation is refused`); `AbbreviationTest`. Shared with K35.
- P47: since todo 77 `longMatchPool` (`parse/Parser.kt`) holds the command's own longs, its flag-spelled commands and the longs its flag-spelled family shares, the globals and the built-ins, as a GNU tool hands getopt_long its own table, so a sibling's long no longer makes a prefix ambiguous (`AbbreviationTest` `an abbreviation resolves against its own command's longs and not a sibling's`). git (only tree fixture). Evidence (K35 decision): `git remote add --ma main origin URL` and `git remote add --f o2 URL` accepted by real git.
- P48: `longMatchPool` holds `offeredBuiltinLongs` beside the command's own longs (`parse/Parser.kt`). Every fixture with `Abbreviation.Options`.
- P49: `longPool` includes `negativeLongs`. cp `--no-dereference`, chmod `--no-preserve-root`, git.
- P50: `resolveName(infer = false)`. curl and find (`Abbreviation.None`).
- P51: every scan before the walk resolves a token against the `longMatchPool` of the command in scope where it stands (`ArgvScan.poolAt`, built by `builtinScan`), and the segment sift against the resolved command's `longMatchPool`. git.
  Disputed: the agreed K38 cells record that real git refuses a global after the subcommand (`git status --no-pager`), so `git status --bar` is a refusal in real git whatever the pool.

## Flags: negatable, counted, repeated

| ID | Check | How | Example |
|---|---|---|---|
| P52 | a negatable flag binds the polarity written last, in long, short and cluster spellings; absent binds its declared default, not `false` | `binds` | `cp -L -P a b` gives `dereference = false`; `cp -LP` likewise; `git commit` alone gives `edit = true` |
| P53 | a negation refuses an attached value | `rejects` | `git commit --no-verify=x` |
| P54 | inside a constraint or a `requiredWhen`, only a negatable flag's positive form selects or triggers | `binds` or `rejects` | no fixture yet (`OpenFindingsTest.the negative spelling does not trigger the requirement`) |
| P55 | a counted flag sums every spelling: long, short, doubled, clustered, and repeated words | `binds` | `tar -v --verbose -vv -cf a.tar` gives `verbose = 4` |
| P56 | a counted flag the real tool also negates binds its negation | a target while `.count()` and `.negatable()` exclude each other | no fixture yet |
| P57 | a plain flag repeated binds `true` once; a tool that refuses a repeat is refused | `binds` or `rejects` | `cp -vv a b` exits 0 |

- P52: `bindFlags` and the `hit` polarity in `sift` (`parse/Bind.kt`, `parse/Parser.kt`); negative shorts in `findNegatedShort`. cp, chmod, git, curl, ssh; `NegationSpellingTest`.
- P53: the `negated != null` branch in `sift` records `FlagTakesNoValue`. git, cp.
- P54: `supplied` for a negatable `FlagSpec` reads `negations[spec] == true` (`parse/Parser.kt`). No fixture yet.
- P55: `bindFlags` `isCount`. tar, ssh, pacman.
- P56: `Flag.count` / `Flag.negatable` mutual `require` (`Converters.kt`). No fixture yet.
- P57: `bindFlags` (`hits > 0`). cp (`LONG_REPEATED`, `SHORT_REPEATED` cells).

## Numbers and folds

| ID | Check | How | Example |
|---|---|---|---|
| P58 | a `numberOption` run binds the number alone, in a cluster before and after letters, and a run every digit of which is a declared short binds those flags instead | `binds` | `head -5`, `head -5v`, `head -v5`; `ssh -4` is the flag |
| P59 | a dash-led number on a command that declares no number input is refused, and after `--` binds as an operand | `rejects` and `binds` | `ls -5`, `git status -2`; `cp -- -5 b` |
| P60 | a `lastOf` fold binds the member written last, through every spelling, and the fold, not a member, is what the record carries | `binds` both orders | `head -n 3 -5` gives 5 lines, `head -5 -n 3` gives 3 |
| P61 | a fold inside an enclosing `lastWins` set loses to a later member and wins over an earlier one | `binds` | `head -n 3 -c 5` gives `lines = null`, `bytes = 5`; `head -c 5 -5` gives `lines = 5`, `bytes = null` |

- P58: `numberRunAt` (`parse/Parser.kt`, the run-as-a-group note). head, git `log`, ssh (`-4`/`-6`); `NumberOptionTest`, `NumberOptionWalksTest`.
- P59: `isFlagLike` (`parse/Parser.kt`) never exempts digits. ls, git, cp. Evidence (K29 decision): `ls -5` gives `ls: invalid option -- '5'`.
- P60: `resolveFolds` (`parse/Parser.kt`). head, git `log`; `LastOfTest.the fold reports whichever member was written last`, `a defaulted member does not leak through the fold`.
- P61: `resolveFolds` before `resolveLastWins`. head; `LastOfTest.the fold is an ordinary member of an enclosing lastWins set`.

## Operands

| ID | Check | How | Example |
|---|---|---|---|
| P62 | options permute among operands: before, between and after all bind the same record, and the operands keep their order | the same expected record for each placement | `cp -v a b`, `cp a -v b`, `cp a b -v` |
| P63 | under `optionsEndAtFirstOperand`, options before the first operand bind and every word after it is an operand verbatim, declared shorts and unknown options included | `binds` | `ssh -p 22 web1 ls -la` gives `remoteCommand = [ls, -la]` |
| P64 | under `optionsEndAtFirstOperand`, a `--` after the first operand binds as the real tool reads it | `binds`, a pinned divergence while klap keeps it | `ssh web1 -- ls` (real ssh consumes the `--`, `SshParityTest` comment) |
| P65 | everything after the first `--` is an operand, a second `--` included; a lone `-` is an operand anywhere | `binds` | `cp -- a --`, `cp - b` |
| P66 | a `--` before a subcommand's name is refused, or read as the real tool reads it | `rejects` | `git -- status` |
| P67 | a variadic before a fixed slot leaves the fixed slot the last operand | `binds` | `cp a b c` gives `sources = [a, b]`, `dest = c` |
| P68 | an `.absentWhen()` trigger removes its slot wherever it sits on the line, and the removed slot binds `null` (or its default) | `binds` with the trigger before, between and after the operands | `cp a b -t d` gives `sources = [a, b]`, `dest = null` |
| P69 | an `.absentWhen()` or `.optionalWhen()` trigger that lost its `lastWins` set leaves the slot or the minimum in place | `binds` or `rejects` | `rm -f -i` with no file, as real rm reads it (needs checking: rm's `-f`/`-i` are not a `lastWins` set in the fixture) |
| P70 | a `.dashLed()` slot takes a dash word that resolves to nothing; a resolvable one is the option; an unmarked slot refuses dash words | `binds` and `rejects` | `chmod -w f` gives `mode = -w`; `chmod -R u+x d` gives `recursive`; `head -x f` refused |
| P71 | operands and values keep their exact text: spaces, unicode, glob characters, a leading `~` | `binds` | `cp 'a b' '~/x'` gives `sources = [a b]`, `dest = ~/x` |

- P62: `sift` non-flag branch with GNU permutation (`parse/Parser.kt`). Every fixture without `optionsEndAtFirstOperand`; `ArgvOrderInvarianceTest.two operands keep their own order while options move freely between them`.
- P63: `optionsEnded` in `sift`. ssh; `OperandTerminatedOptionsTest`. Evidence (K30 decision): real ssh continues options after the host until the command's first word (`ssh -G web1 -p 2222` gives port 2222, `ssh -G web1 ls -p 2222` gives port 22); the ssh fixture ends options at the host, an ssh parity target.
- P64: the `optionsEnded` branch adds `--` as a positional. ssh.
- P65: the `END_OF_OPTIONS` branch sets `endedBySeparator`; `isFlagLike` excludes `-`. cp (`OPERAND_CELLS`), pacman (`everything after the end of options is a target`).
- P66: `bind`'s group branch `SubcommandAfterSeparator`; the hybrid case in `tooManyArguments`. git.
- P67: `bindPositionals` `fixedAfter` slicing (`parse/Bind.kt`). cp, mv; `OpenFindingsTest.operands past the slot count resolve the way the bind would`.
- P68: `activeArguments` and the `absentValue` bind of removed slots. cp, mv, chmod; `ConditionalOperandTest`, `OpenFindingsTest.absent when then default binds the default not null`.
- P69: `lastWinsLosers` passed as `overridden` to `activeArguments` and the `optionalWhen` check. rm (`optionalWhen(force)`); `ConditionalOperandTest.a trigger that lost its override set leaves the slot in place`, `... leaves the minimum standing`.
- P70: `dashLedSlot` with `shortClusterResolvesInFull` in `sift`, rule 3 in `bindPositionals`. chmod (marked), head (unmarked); `DashLedOperandTest`.
- P71: no transformation between the token and `convert`. Every fixture with a path or free-text operand; X8 for `~`.

## Repeats, overrides and defaults

| ID | Check | How | Example |
|---|---|---|---|
| P72 | a single-valued option given twice binds as the tool reads it: the later value, the first, or a refusal | `binds` or `rejects`, never assuming last | `cp --suffix=a --suffix=b x y` gives `b`; `cp -t a -t b x` refused |
| P73 | a `.multiple()` option collects every occurrence across all its spellings, interleaved, in argv order, inside clusters too | `binds` | `git remote add -t a --track=b -fts o u` gives `track = [a, b, s]`, `fetch = true` |
| P74 | a `lastWins` loser binds what it binds when absent (its default, `null`, `false`), whatever was written for it | `binds`, asserting the loser's field | `cp -i -n a b` gives `interactive = false`, `noClobber = true` |
| P75 | `lastWins` order holds inside one cluster as between words | `binds` both orders | `cp -in a b` against `cp -ni a b` |
| P76 | an optional-value option given bare after a value binds as the tool reads it | `binds` | `cp --backup=numbered --backup a b` keeps `numbered` in real cp; klap binds the last occurrence, the bare one, so the cp fixture keeps every occurrence (`.multiple()`) and reads the control itself |
| P77 | a converter that succeeds with `null` on a given value falls back to the default, and is refused on a `.multiple()` option | `binds` and `rejects` | needs checking which fixture has a nullable `.map { }` |

- P72: `bindOptions` binds the last occurrence after converting every earlier one (`parse/Bind.kt`); `.repeated(RepeatedOptions.Refuse)` refuses a second occurrence in sift (`RepeatedOption`), and "keeps the first" still needs the fixture to read every occurrence. cp (`REPEATED` cells; `-t` refuses a repeat, `a second target directory is refused` passes), mv.
- P73: `optionValues` append order in `sift`. git `remote add --track`, git `commit -m`, tar `--exclude`, find, ssh.
- P74: `resolveLastWins` / `absentValue` (`parse/Parser.kt`). cp, mv, chmod, find, ls, pacman, head.
- P75: `ClusterPosition(token, char)` ordering (`parse/Sifted.kt`). cp `-i`/`-n`, chmod `-H`/`-L`/`-P`, find; `LastOfTest.a long short cluster does not reorder a later token`.
- P76: `bindOptions` last raw wins, and `bareValue` is a raw. cp target `a bare or empty --backup keeps the backup control already chosen`.
- P77: the `?: c.value` substitution in `bindOptions` and `convertOccurrences`' null refusal (`parse/Bind.kt`).

## Refusals and constraints

| ID | Check | How | Example |
|---|---|---|---|
| P78 | an `exactlyOne` set refuses none and two, and a member supplied only by its default does not count | `rejects` and `binds` | `tar -c -x -f a`, `tar -f a` refused |
| P79 | the same member of an exclusive set given twice binds or is refused as the tool reads it | `binds` or `rejects` | `pacman -Q -Q` (pacman target `the operation given twice is refused`) |
| P80 | an `atMostOne` set refuses two members and accepts none | `rejects` and `binds` | `tar -czjf a.tar f` refused |
| P81 | a `requiredWhen` option is refused when its trigger is on the line without it, wherever the trigger sits, a global trigger included | `rejects` and `binds` | no fixture yet |
| P82 | a `validateInputs` refusal, or a block that throws, gives no record; a parent's block never fires for a child's line; help on the same line still yields `null` | `rejects`, `binds`, `parse` gives `null` | no fixture yet (`ValidateInputsTest`) |
| P83 | a refusal the fixture makes only inside its `action` body is still a refusal of the line, so `parse` must refuse it | `rejects` (a target while the check lives in the action) | `mv -t d -T a b`; cp's `cp -t d -T a b` and `cp -T a b c` are declared rules now |
| P84 | a line klap accepts but the real tool refuses is recorded as loose binding, separately from a line klap refuses that the tool accepts | `bindsLoosely` for the first, `rejects` naming the gap or a target for the second | `cp --json a b`; `git remote -v add o u` |

- P78: `checkConstraints` uses `supplied`, which reads the sift, not the bound value (`parse/Bind.kt`). tar, pacman (`two different operations and none at all are refused`); `ConstraintTest`.
- P79: `checkConstraints` counts members, not occurrences. pacman.
- P80: `checkConstraints` `AtMostOne`. tar. pacman (no fixture constraint yet): `pacman -Q` refuses 32 modifier pairs, such as `--search --info` ("may not be used together"), a pacman target.
- P81: `checkConditionalRequirements` with `globalAcc.supplied` (`parse/Parser.kt`); `OpenFindingsTest.a global flag triggers the requirement it advertises`.
- P82: `runValidations` (`Parser.kt`) runs only on `Execute`, catches exceptions into `CliError.Failure`, and reads only the resolved command's `validations`.
- P83: mv returns `Err(CliError.Failure(...))` from its action (`Mv.kt`), which `parse` never runs. cp declares the same rules instead (`atMostOne(targetDirectory, noTargetDirectory)`, `.atMostWhen(noTargetDirectory, 1)` in `Cp.kt`), so its `-t and -T are refused together` and `-T takes exactly a source and a destination` pass.
- P84: `ParitySuite.bindsLoosely`. Every fixture.

## Required cells

P is not a completion table, so it has no required cells. Every P row above applies to every fixture whose inputs it names; a row whose feature no
fixture declares is `n/a: no fixture yet`.
