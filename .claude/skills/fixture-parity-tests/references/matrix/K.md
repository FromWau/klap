# K and K7: what is being completed

Purpose: the slot under the cursor (K), short clusters (K7) and the cursor positions every completion cell is written for.

## Contents

- [Context](#context)
- [K: existing rows, split](#k-existing-rows-split)
- [K7: existing rows, split](#k7-existing-rows-split)
- [Name slots the existing rows do not separate](#name-slots-the-existing-rows-do-not-separate)
- [Value slots: where the value comes from and what the word before it is](#value-slots-where-the-value-comes-from-and-what-the-word-before-it-is)
- [Optional values](#optional-values)
- [klap's built-ins as slots](#klaps-built-ins-as-slots)
- [Routing: which command the slot belongs to](#routing-which-command-the-slot-belongs-to)
- [Operand slots](#operand-slots)
- [Number inputs](#number-inputs)
- [K7: further cluster cases](#k7-further-cluster-cases)
- [Cursor columns](#cursor-columns)
- [Required cells](#required-cells)

## Context

`‸` marks the cursor. Every row is a cell family: each column is one cell, written for every input of that kind
the fixture declares. "Words after the cursor" means the same cell with more of the line after it; scope L says
which of those words may change the answer.

Under each table, every row gets a one-line note naming the klap code path it exercises and the fixtures that
declare the construct. Paths are relative to `klap/src/commonMain/kotlin/com/fromwau/klap/`; `planner` means
`internal/render/Completion.kt` (`completeCandidates` and its helpers), line numbers as of 2026-10-09.

"needs checking" marks a row where reading the planner suggests klap either lacks the construct or answers in a
way the parse does not agree with; the row stays in, and the pass that writes it decides between pass, target and
n/a from the real tool. "klap test" marks a row tested in klap's own tests rather than a fixture.

The cells of K8, K11 and K20 to K39 were agreed with the owner one by one on 2026-10-09; the real-tool evidence is
quoted in each row's note.

## K: existing rows, split

| ID | Slot | End of word | Inside the word | At the word's start | No word | Words after the cursor |
|---|---|---|---|---|---|---|
| K1 | subcommand or mode | `git com‸`; a whole name: `git commit‸` (offers only names it prefixes) | `git co‸m` | `git ‸com` | `git ‸`; built-in subcommands among the list on a dispatcher (`completion`, `docs`): E4 where the real tool has none | `git com‸ -m msg`; a subcommand after the cursor: `git ‸ commit`; a global after it: `git ‸ -C dir`; under `Abbreviation.All`, a cursor word that itself routes: `tasks a‸ --file x` |
| K2 | long flag | `cp --verb‸`; the bare double dash: `cp --‸` (longs only, G4) | `cp --‸verb`, `cp --ve‸rb`; inside the dashes: `cp -‸-verb` (handed `-`, so shorts too) | `cp ‸--verb` | names are not offered before a dash | `cp --verb‸ src dst`; a constraint member after the cursor: `tar --cr‸ -x`, `pacman --sy‸ -Q` (L3; `membersRuledOutBy` reads the head only, planner L177) |
| K3 | short flag | `cp -v‸`, `cp -‸`; a short-only input: `ls -F‸`; a short whose long is another input: `ls -F‸` beside `--classify` | `cp -‸v` | `cp ‸-v` | | `cp -‸ src-file dst-file`; a set member after the cursor: `tar -‸ -c`, `tar -‸ -z` |
| K4a | negation: a generated `--no-<long>` | `curl --no-sil‸` | | | | |
| K4b | negation: an explicit asymmetric one (`--no-pager` for `--paginate`) | `git --no-pa‸`; a global negation below the root: `git commit --no-pa‸` | `git --no-‸pa` | `git ‸--no-pager` | | `git --no-pa‸ log` |
| K4c | negation: a negative short | `cp -P‸`, `ssh -a‸`, `git -P‸` | | | | |
| K4d | negation: none, a positive flag named `no-...` | as K25 | | | | |
| K5 | long option name | `cp --back‸`; a global option's name below the root: `git commit --git-d‸` | `cp --‸back`, `cp --ba‸ck` | `cp ‸--backup` | | `cp --back‸ src dst`; the value already typed after it: `cp --back‸ numbered src dst` |
| K6 | short option name | `cp -S‸`; a global's short: `git -C‸`, `tasks -f‸` | `cp -‸S` | `cp ‸-S` | | `cp -S‸ .bak src dst` |
| K8a | value in its own word after a long | `ls --format lo‸` (offers long) | `ls --format l‸o` | `ls --format ‸long`; with words after: `ls --format ‸long dir` | `ls --format ‸` (across, horizontal, commas, long, single-column, verbose, vertical) | `ls --format ‸ dir`; a dash-led word right after the cursor: `ls --format ‸ -d dir` (the cursor word is still the value) |
| K8b | value in its own word after a short | the cells of K32 | | | | |
| K8c | value in its own word after an abbreviated long | the cells of K33 | | | | |
| K8d | value in its own word after a cluster's last letter | the cells of K36 and K7x | | | | |
| K8e | value in its own word after a global | the cells of K37 and K38 | | | | |
| K9 | value attached with `=` | `cp --backup=nu‸`; abbreviated name: `cp --back=nu‸`; a flag: `cp --verbose=‸` (nothing); an unknown name: `cp --zzz=‸` | `cp --backup=n‸u`, in the name part `cp --back‸up=nu`; right before the `=`: `cp --backup‸=nu` (handed `--backup`, E2 keeps `=nu`) | `cp ‸--backup=nu` | `cp --backup=‸` | `cp --backup=‸ src dst` |
| K10 | value glued to a short | `cp -S.b‸`; a global short glued: `git -Cdi‸` | `cp -S‸.b` | `cp ‸-S.b` | the short alone: `cp -S‸` is a name (K6), not an empty glued value | `cp -S.b‸ src dst` |
| K11a | optional value, long attached | `ls --color=al‸` | `ls --color=a‸l` | `ls ‸--color=al` | `ls --color=‸` | `ls --color=‸ dir` |
| K11b | optional value, long bare, then a space | | | | `ls --color ‸` is an operand, not the value | `cp --backup ‸ src` is an operand slot (files), not the value |
| K11c | optional value, short glued | a short optional value: `git commit -Sk‸` (K48) | | | | |
| K11d | optional value, short bare, then a space | the cells of K49 | | | | |
| K11e | optional value on a global, routing on | | | | a global optional value spaced: `git --exec-path ‸` routes the next word (K50) | |
| K12a | first operand | `cp sr‸` | `cp s‸rc` | `cp ‸src` | `cp ‸`; after a flag `cp -v ‸`, an option value `cp -S .b ‸`, an attached value `cp --backup=t ‸` (K79); on a dispatcher root after `--`: `git -- ‸` (needs checking: subcommands offered where the parse does not route) | `cp ‸ dst` |
| K12b | variadic operand | `cp a sr‸` | `cp a s‸rc` | `cp a ‸src` | `cp a ‸` | `cp a ‸ dst`; operands on both sides: `cp a ‸ b c` |
| K12c1 | the fixed slot after a variadic, below the variadic's minimum | | | | `cp ‸` (a source), against `cp a ‸` (the destination) | |
| K12c2 | the fixed slot after a variadic, at or past its minimum | `cp a b ds‸` | `cp a b d‸st` | `cp a b ‸dst` | `cp a b ‸` | `cp a ‸ b` (the cursor word a source, `b` the destination) |
| K12d1 | an operand an `.absentWhen()` trigger removes, the trigger before the cursor | `cp -t dir a‸` | `cp -t dir a‸b` | `cp -t dir ‸a` | `cp -t dir ‸` | |
| K12d2 | an operand an `.absentWhen()` trigger removes, the trigger after the cursor | | | | | `cp ‸ -t dir` (the trigger after the cursor) |
| K12d3 | an operand an `.absentWhen()` trigger removes, the trigger attached, glued, abbreviated | `cp --target-directory=d a‸`, `cp -td a‸`, `cp --target=d a‸` | | | | |
| K13a | built-in `--help` | `cp --he‸` | `cp --h‸e` | `cp ‸--help` | | `cp --he‸ src`; a built-in that answers alone, then the cursor: `cp --help ‸` (K59) |
| K13b | built-in `-h`, and its absence where `help = Help.Generate(short = null)` | `-h` on its own: `cp -h‸` | | | | |
| K13c | built-in `--help-all`, only where visible subcommands exist | `git --help‸` against `cp --help‸` | | | | |
| K13d | built-in `--version`, only with a version | `--version` only where declared | | | | |
| K13e | built-in `--json` | the `--json` name | | | | |
| K13f | built-in `--color` as a name | the `--color` name | | | | |
| K13g | built-in `--completion` and `--docs` (single-command root only) | the `--completion` and `--docs` names | | | | |
| K14a | after `--`, on a leaf | `cp -- -v‸` | `cp -- -‸v` | `cp -- ‸-v` | `cp -- ‸`; a second `--`: `cp -- -- ‸` (the second is an operand) | `cp -- ‸ dst`; `--` after the cursor, cursor before it: `cp -‸ -- x` (names still offered) |
| K14b | after `--`, on a dispatcher | a subcommand-like word after `--`: `git -- com‸` | | | a dispatcher: `git -- ‸` | |
| K15a | abbreviation: name completion | `cp --rec‸`, ambiguous `cp --re‸`; under `Abbreviation.None`, `rsync --verb‸` names still prefix-filter; an abbreviated negation: `chmod --no-pres‸` | `cp --r‸ec` | | `--help-all` is exact-only: `git --help-a ‸` | `cp --rec‸ src dst` |
| K15b | abbreviation: an abbreviated name before its value | as K33 | | | `cp --back=‸`; under `Abbreviation.None`: `curl --out ‸` does not resolve (`-o`'s values are not offered) | |
| K15c | abbreviation: an ambiguous abbreviation before a value | as K34, K35 | | | | |
| K16a | digit short (`numberOption`): the run alone | `head -2‸`; a run longer than one digit `head -20‸`; `head -‸` (does anything stand for `-<NUM>`?) | `head -2‸0` | `head ‸-20` | | `head -2‸ file` |
| K16b | digit short: a run, then letters | as K88 | | | | |
| K16c | digit short: letters, then a run | as K89 | | | | |
| K16d | digit short: a run, then a value short | as K90, K91 | | | | |
| K16e | digit short: a digit flag beside a number input | as K92 | | | | |
| K17a | dash-led operand (`.dashLed()`) naming no declared short | `chmod -w‸` | `chmod -‸w` | `chmod ‸-w` | | `chmod -w‸ file` |
| K17b | dash-led word that is a full cluster of declared flags | `chmod -R‸` (K7u) | | | | |
| K17c | the bare dash on a dashLed command | | | | `chmod -‸` (names, mode values, or both) | |
| K17d | the operand after an admitted dash-led one | as K85 | | | | |
| K18 | the program name itself | `c‸` is the shell's own command completion, not klap's: nothing is asked of klap | | | | |
| K19 | a subcommand two or more levels deep | `git remote ad‸`; through an alias: `git remote rm or‸`; with a global between the levels: `git remote --bare ad‸` | `git remote a‸d` | `git remote ‸add` | `git remote ‸` | `git remote ad‸ origin url` |

Notes:

- K4 split: each takes a different branch: `negativeLongs` from the generated form or the declared list,
  `negativeShorts` (planner L182-185), or no negation at all; and an explicit list replaces the generated form, so
  `--no-paginate` must not be offered. K4b keeps the original K4 row.
- K8 split: `trailingValueOption` resolves each through a different path: `byName`, `resolveLong`, the cluster
  peel, the global pool (planner L455-494). K8a keeps the original K8 row with the agreed example `ls --format`
  (a required value). Evidence: `ls --form long -d .` lists long.
- K11 split: `bareValue` is checked in `trailingValueOption` (L493) for the spaced form and not at all on the
  attached path; the short and global forms reach those checks through other branches. K11a keeps the original
  K11 row. K11b gains `cp --backup ‸ src` from K8. Evidence: `cp --back numbered a b` reads numbered as a source
  ("cp: target 'b': No such file or directory").
- K12c split: `slotForOperand` floors the variadic at its minimum (L349), so the first word on `cp ‸` is a source
  and on `cp a ‸` the destination; same answer for cp's files, a different slot for a tool whose slots differ.
  K12c2 keeps the original K12c row.
- K12d split: the trigger is read from `wholeSifted` (L226), a different walk from the head's; each spelling
  reaches `supplied` through a different sift branch. K12d1 keeps the original K12d row.
- K13 split: each has its own gate (planner L191-203); a single row hides that `cp` and `git` must answer
  differently. K13a keeps the original K13 row.
- K14 split: on a dispatcher the first slot after `--` also offers subcommand names (L239), which the parse never
  routes to. K14a keeps the original K14 row.
- K15 split: names are prefix-filtered whatever the mode, while a value slot resolves through `resolveLong` by
  mode; the ambiguous case falls through to the operand slot. K15a keeps the original K15 row.
- K16 split: `numberRunAt` is consulted at each position of the peel (L506), and each shape ends the peel
  differently. K16a keeps the original K16 row.
- K17 split: `sift` admits a dash-led word only when `shortClusterResolvesInFull` fails, while the planner's names
  branch never asks (L175), so the four answer differently and can disagree with the parse in different ways.
  K17a keeps the original K17 row.

## K7: existing rows, split

A cluster has no "no word" cell. Each case is written for the fixture's own letters.

| ID | Cluster case | End of word | Inside the word | At the word's start | Words after the cursor |
|---|---|---|---|---|---|
| K7a | continuation after one letter | `cp -r‸` | `cp -‸r` (klap is handed `-`; the `r` stays) | `cp ‸-r` | `cp -r‸ src dst` |
| K7b | continuation after several letters | `cp -rf‸` | `cp -r‸f`, `cp -‸rf` | `cp ‸-rf` | `cp -rf‸ src dst` |
| K7c | a new short after letters given in an earlier word | `cp -r -f‸` | `cp -r -‸f` | `cp -r ‸-f` | `cp -r -f‸ src dst` |
| K7d | a bare dash after an earlier cluster | `cp -rf -‸` | | `cp -rf ‸-` | `cp -rf -‸ src dst` |
| K7e1 | a counted letter repeated | `pacman -Qi‸` offers `-Qii` | `pacman -Q‸i` | | `pacman -Qi‸ pkg` |
| K7e2 | a non-counted letter repeated | `cp -rv‸` offers no `-rvv`, or does it? | | | |
| K7f | ends in a value-taking short, value glued | `cp -rfS‸`, `cp -rfS.b‸` | `cp -rfS.‸b` | | `cp -rfS.b‸ src dst` |
| K7g | ends in a short followed by `=` | `cp -rfb=‸`, `cp -rfS=‸` | `cp -rfS=.‸b` | | `cp -rfS=‸ src dst` |
| K7h | a value-taking short in the middle of a cluster | `cp -rSf‸` (the `f` is `-S`'s value) | | | `cp -rSf‸ src dst` |

What the cluster cells must hold, from the real tool:

- K7c, K7d: a letter given earlier on the line is not offered again, unless the tool counts it.
- K7f, K7h: once a short takes a value the rest of the word is that value, so nothing after it is a letter.
- K7g: getopt does not strip `=` after a short, so `cp -rfS=.b` gives `-S` the suffix `=.b`; `-b` takes no value,
  so `cp -rfb=` is a refusal. The real tool decides each.

Notes:

- K7e split: the planner drops every typed letter (L213), counted or not; the counted half is likely a target and
  the uncounted half a pass, and lumping them hides which. K7e1 keeps the original K7e row.

## Name slots the existing rows do not separate

| ID | Slot | End of word | Inside the word | At the word's start | No word | Words after the cursor |
|---|---|---|---|---|---|---|
| K20 | a name typed in full that is also the prefix of another name | `git commit --all‸` (offers `--all` and `--allow-empty`) | `git commit --al‸l` | `git commit ‸--all` | names are not offered before a dash | `git commit --all‸ -m msg` |
| K21 | a global option or flag named below the root | `git remote add --no-p‸`, `git commit --dir‸`: not offered; `tasks list --fi‸` offers `--file` | `git commit --di‸r`: not offered | `git commit ‸--directory`: the global not offered | | `git remote add --no-p‸ origin url`: not offered |
| K22 | a parent command's own option typed after its child | `git remote add -v‸`, `git remote add --verb‸` (remote's `-v`, which add lacks) | `git remote add --ve‸rb` | | | `git remote add -v‸ origin url` |
| K23 | a child's option typed before the child's name (needs checking) | `git --sho‸` | `git --sh‸o` | | `git -s ‸` (does klap offer subcommands the parse will not route to?) | `git --sho‸ status` |
| K24 | a hidden option's name (klap test: no fixture hides an option) | `tool --inter‸` offers nothing | `tool --in‸ter` | | | `tool --inter‸ file` |
| K25 | a positive flag whose own name starts with `no-` | `git --no-rep‸` (offers `--no-replace-objects`, never `--replace-objects`) | `git --no-r‸ep` | `git ‸--no-replace-objects` | | `git --no-rep‸ status` |
| K27 | a short spelled with punctuation | `curl -:‸` | `curl -‸:` | `curl ‸-:` | | `curl -:‸ url` |
| K28 | a short spelled with a digit, where no number input exists | `ssh -6‸` (-6, force IPv6 addresses only), `ls -1‸` (-1, list one file per line), `curl -4‸` | `ls -‸1` | `ls ‸-1` | | `ls -1‸ dir` |
| K29 | a dash-led number naming nothing | `ls -5‸` nothing | `ls -‸5` ls's names (handed `-`) | | | `ls -5‸ dir` nothing |
| K30 | a name on a command whose options end at an operand (`optionsEndAtFirstOperand`): options continue after the host until the command's first word (K31 folded in) | `ssh -p‸` names as usual; `ssh web1 -‸` ssh's names; `ssh web1 -p‸` offers -p; `ssh web1 ls -‸` nothing; `ssh web1 ls -l‸` nothing | `ssh web1 ls -‸l` nothing | `ssh web1 ‸-l` ssh's names | | `ssh web1 -‸ /var/log` ssh's names |

Notes:

- K20: names branch, `filter { it.value.startsWith(current) }` (planner L217) keeps the exact name and the longer
  one; E3 `tab` leaves the line as typed. git (`--all`/`--allow-empty`), curl, pacman (wherever one long is a
  prefix of another).
- K21: `globalSpecs` joins the name list at every depth (planner L178). git (`--directory`, `--no-pager`),
  task-manager (`--file`, `--verbose`). The git cells are "not offered": real git refuses globals after the
  subcommand (see K38); task-manager keeps `tasks list --fi‸` offering `--file`.
- K22: names come only from `cmd`, the deepest command `walkTo` reached (planner L83, L178); git's own KLAP-GAP
  comment on `remote -v add`. git.
- K23: `walkTo` stops at the first token naming no subcommand (planner L367) and the sift error is ignored (L96),
  so `git -s ‸` reaches the root's first slot and its subcommand list (L239). git.
- K24: `filterNot { it.hidden }` on names (planner L178), while `matchingValueOption` keeps hidden options so
  their values still complete (L455). No fixture hides an option (git and task-manager hide commands only).
- K25: a plain `FlagSpec` with a `no-` long, not `negatable` (planner L182 skipped). git (`--no-replace-objects`,
  `--no-optional-locks`).
- K27: `Candidate("-$it")` for a non-letter short (planner L181); `-:` in a cluster is K7r. curl (`--next`, `-:`).
  Plus a bash cell in `RealShellCompletionTest` (bash COMP_WORDBREAKS holds `:`).
- K28: `numberRunAt` declines a run every char of which is a declared short (`internal/parse/Parser.kt`
  `numberRunAt`); here no number input exists at all. ls (`-1`), curl and ssh (`-4`, `-6`). Separate from K16
  (`numberOption` stays).
- K29: `isFlagLike` reads `-5` as an option (`Parser.kt` `isFlagLike`), prefix filter leaves `[]`. ls, ssh.
  Evidence: `ls -5` -> `ls: invalid option -- '5'`.
- K30: the planner suppresses names only when `--` is in the segment (planner L175), while `sift` ends options at
  the first operand (`Parser.kt` sift, `optionsEndAtFirstOperand`), so `ssh web1 -‸` may offer ssh's names where
  the parse makes the word an operand. ssh. Folded K31: the same command before the operand that ends options:
  names as usual. Evidence: `ssh -G web1 -p 2222` -> port 2222; `ssh -G web1 ls -p 2222` -> port 22. The fixture
  ends options at the host, one word early: an ssh parity target.

## Value slots: where the value comes from and what the word before it is

| ID | Slot | End of word | Inside the word | At the word's start | No word | Words after the cursor |
|---|---|---|---|---|---|---|
| K32 | value in its own word after a short | `tar -f ar‸` filesAfter("") | `tar -f a‸r` filesAfter("") | `tar -f ‸ar` filesAfter("") | `tar -f ‸` filesAfter("") | `tar -f ‸ src` filesAfter(""); `ssh -i ‸ web1` filesAfter(""); `cp -S ‸ src dst` nothing (free text, no operand files) |
| K33 | value in its own word after an abbreviated long | `ls --form lo‸` long | | | `ls --fo ‸` the format choices | `ls --form ‸ dir`; `cp --back ‸ src` operand slot (optional value) |
| K34 | value in its own word after an ambiguous abbreviation (needs checking) | `chmod --re 7‸` offers nothing | | | `chmod --re ‸` offers nothing | `chmod --re ‸ file` offers nothing |
| K35 | value after an abbreviation unique in its own command, ambiguous only through another command's long (needs checking) | `git remote add --ma‸` offers --master; `git remote add --f‸` offers --fetch | | | `git remote add --ma ‸` --master's value slot (free text, not refused as ambiguous) | `git remote add --ma ‸ origin` |
| K36 | value in its own word after a cluster ending in a value-taking short | `tar -cvf ar‸` filesAfter("") | `tar -cvf a‸r` filesAfter("") | `tar -cvf ‸ar` filesAfter("") | `tar -cvf ‸` filesAfter("") | `tar -cvf ‸ src` filesAfter(""); `cp -rS ‸ src dst` nothing |
| K37 | a global option's value before the subcommand | `git -C di‸` filesAfter("") | `git -C d‸i` filesAfter("") | `git -C ‸dir` filesAfter("") | `git -C ‸` filesAfter(""); `git -c ‸` nothing; `git -C dir ‸` subcommand names | `git -C ‸ status` filesAfter("") |
| K38 | a global option after the subcommand: a task-manager global reaches every depth, git's are neither names nor value slots there | `tasks list --fi‸` offers --file; `git remote add --dir‸` nothing; `git status -‸` status's own names only | | | `tasks list --file ‸` filesAfter(""); `git remote add -C ‸` the `remote add` operand slot, not -C's value | |
| K39 | a global short glued or attached | `git --git-dir=.g‸` filesAfter("--git-dir="); `git -Csub‸` nothing; `git --dir‸` nothing | `git --git-dir=.‸g` filesAfter("--git-dir=") | `git ‸--git-dir=.g` names | `tasks --file=‸` filesAfter("--file=") | `git --git-dir=‸ status` filesAfter("--git-dir=") |
| K40 | a dash-led word in a value slot | `git commit -m -w‸`, `tar --exclude -fo‸` | `git commit -m -‸w` | `git commit -m ‸-w` | | `git commit -m -w‸ file` |
| K41 | the end-of-options marker in a value slot (needs checking) | `cp -S --‸` (klap's sift never takes `--` as a value; getopt does) | | | `cp -S -- ‸` | `cp -S --‸ src dst` |
| K42 | the word after a value that looks like an option or a built-in | `git commit -m --amend fi‸` | | | `git commit -m --amend ‸`, `git commit -m --help ‸`, `git commit -m --color ‸`, `git commit -m --json ‸` | `git commit -m --amend ‸ other` |
| K43 | a value that itself holds `=` | `git -c core.editor=v‸`, `git --config=core.editor=v‸`, `ssh -o StrictHostKeyChecking=n‸` | `git --config=core.e‸ditor=vim` | | `git -c core.editor=‸`, `git --config=‸` | `git -c core.editor=‸ status` |
| K44 | a hidden option's value (needs checking: no fixture) | `tool --internal fo‸` | | | `tool --internal ‸` | `tool --internal ‸ file` |
| K45 | a value slot whose option takes no value | `cp --verbose=‸`, `git --no-pager=‸` offer nothing | `cp --verbose=‸x` | | | `cp --verbose=‸ src dst` |
| K46 | a value attached to an unknown or ambiguous long | `cp --zzz=a‸`, `chmod --re=7‸` | | | `cp --zzz=‸` | `cp --zzz=‸ src dst` |
| K47 | the word after an unknown option | `cp --zzz sr‸` | | | `cp --zzz ‸` (an operand: the unknown option claims no word) | `cp --zzz ‸ dst` |

Notes:

- K32: `trailingValueOption` resolves a whole `-s` through `byName` (planner L457, L479). cp (`-S`), ssh (`-p`,
  `-i`), tar (`-f`), head (`-n`), curl (`-o`). Agreed examples: `tar -f`, `ssh -i`, and `cp -S` as free text.
- K33: `matchingValueOption` resolves through `resolveLong` against `longMatchPool` (planner L458), so an
  abbreviation the parse accepts completes that option's values. cp, chmod, head, ls, mkdir, mv, rm, tar, git
  (all `Abbreviation.Options`); task-manager (`All`). Evidence: `ls --fo long -d .` accepted.
- K34: `NameMatch.Ambiguous` returns null (planner L468), the planner then takes the positional branch (L220) and
  offers chmod's mode or files on a line the parse refuses. chmod (`--recursive`/`--reference`), ls (`--so`).
  Expect nothing after an ambiguous name. Evidence: "chmod: option '--re' is ambiguous; possibilities:
  '--recursive' '--reference'". Settled for pacman as a pass: `pacman -Q --conf ‸` offers nothing, as real
  `pacman -Q --conf x` is "option '--conf' is ambiguous".
- K35: `longMatchPool` holds every long in the tree (`internal/parse/Parser.kt` `longMatchPool`, `treeLongs`), so a
  sibling's long makes the prefix ambiguous. git. Evidence: `git remote add --ma main origin URL` and
  `git remote add --f o2 URL` accepted by real git. Same fix as P46 to P51 (a shared target).
- K36: `trailingValueOption` peels flags with `firstNonFlagShort` and takes the cluster's last char (planner
  L483-488); pinned by CompletionTest "complete candidates completes clustered short option value". tar, cp, ssh,
  curl. Evidence: `tar -cvf ar.tar src` created ar.tar.
- K37: `walkLine` strips globals before the walk (planner L307), `matchingValueOption` pools `globalAcc.optionSpecs`
  (L456). git (`-C`, `-c`, `--git-dir`, `--work-tree`, `--namespace`), task-manager (`--file`). klap has no
  directory-only form (V27), so `-C` gets filesAfter("").
- K38: the same pool below the root; a global reaches every depth. git, task-manager. Evidence: real git refuses
  `git remote add -C . o u`, `git status --no-pager`, `git status -c x=y`. Needs todo 27 (root-only option).
- Disputed: the K4b cell `git commit --no-pa‸` completes a git global negation after the subcommand, which real git refuses.
- Disputed: the K5 cell `git commit --git-d‸` completes a git global's name after the subcommand, which real git refuses.
- Disputed: the K19 cell `git remote --bare ad‸` routes through a git global between the levels, which real git refuses.
- Disputed: K66 (`git remote --bare ad‸`, `git remote --no-pager ‸`) expects git globals to be stripped between the levels, which real git refuses.
- Disputed: K7k's `git commit -aP‸` puts git's global `-P` in a cluster after the subcommand, which real git refuses.
- K39: `attachedValueOption` long and short halves (planner L538-557) with a global option. git, task-manager.
  Evidence: real git refuses `-Csub`, `--directory=sub`, `-csome.key=v`, `--config=some.key=v`, accepts
  `--git-dir=.git`. git has no `--directory` or `--config`: a git parity finding, and the fixture invents both.
  "Short value only in its own word" is added to todo 27.
- Disputed: the K10 cell `git -Cdi‸` expects a value glued to git's `-C`, which real git refuses (`-Csub`).
- Disputed: K43's `git --config=core.editor=v‸`, `git --config=core.e‸ditor=vim` and `git --config=‸` use a `--config` long that real git does not have.
- K40: the value branch (planner L157) runs before the dash-led name branch (L175); guide "Dash-led values". git
  (`-m`), tar (`--exclude`), curl (`-H`, `-d`), ssh (`-o`).
- K41: the planner's value branch takes `--` as the cursor word for `-S` (L157-160), while `sift`'s `valueFrom`
  refuses `--` as a value (`Parser.kt` sift, `takeUnless { it == END_OF_OPTIONS }`); real getopt hands `--` to the
  option. cp, tar, every fixture with a value-taking short. Settled for pacman: `pacman -Q -b --‸` offers files
  (pass), but `pacman -Q -b -- --verb‸` offers nothing where real pacman takes `--` as the path ("failed to resolve
  path '--' passed to '--dbpath'") and options go on (target).
- K42: `trailingIsConsumedValue` and `prev` taking `takeUnless { ... in scan.valueSlots }` (planner L76, L89);
  CompletionValueSlotTest pins the built-in and global cases. git, tar, curl.
- K43: the long attached form splits at the FIRST `=` (planner L541); the space form never splits. git (`-c`), ssh
  (`-o`), curl (`-H`).
- K44: `matchingValueOption` does not filter `hidden` (planner L455); CompletionTest "completes a hidden options own
  value not the next positionals choices". Invented.
- K45: `matchingValueOption` finds no option for a flag, names branch filters `--verbose=` to `[]` (L217). Every
  fixture with a long flag.
- K46: `attachedValueOption` returns null for an unresolved name (planner L543), names branch gives `[]`. Every
  fixture; chmod for the ambiguous case.
- K47: `sift` skips an unknown long without making it a positional (`Parser.kt` sift, `UnknownOption`), so the
  cursor word is the first operand. Every fixture.

## Optional values

| ID | Slot | End of word | Inside the word | At the word's start | No word | Words after the cursor |
|---|---|---|---|---|---|---|
| K48 | an optional value glued to its short | `git commit -Sk‸` | `git commit -S‸k` | `git commit ‸-Sk` | | `git commit -Sk‸ file` |
| K49 | the word after a bare optional-value short | `git commit -S fi‸` | | | `git commit -S ‸` (pathspec: the bare short takes no word) | `git commit -S ‸ file` |
| K50 | an optional-value global before the subcommand | `git --exec-path com‸` (a subcommand: the bare form takes no word) | `git --exec-path co‸m` | | `git --exec-path ‸` | `git --exec-path ‸ status` |
| K51 | an optional-value global's attached value | `git --exec-path=/us‸` | `git --exec-path=/‸us` | `git ‸--exec-path=/us` | `git --exec-path=‸` | `git --exec-path=‸ status` |
| K52 | an optional value restricted to a choice | `pacman --debug=‸` (offers `1`, `2`), `ls --classify=al‸` | `ls --classify=a‸l` | | `pacman --debug ‸` is an operand | `pacman --debug=‸ -Q` |

Notes:

- K48: `attachedValueOption` short half (planner L549-557) on an option with `bareValue`. git (`-S`/`--gpg-sign`).
- K49: `trailingValueOption` drops an option with `bareValue` (planner L493), so the next word is an operand. git.
- K50: same rule at the root: `walkTo` then routes the next word as a subcommand. git (`--exec-path`).
- K51: attached long with `.file()` gives `filesAfter("--exec-path=")` (`asWholeWord`, planner L274). git.
- K52: choices filter on the attached part (planner L440). pacman (`--debug`), ls (`--color`, `--classify`), cp
  (`--backup`, `--update`, `--reflink`), mv, mkdir, rm.

## klap's built-ins as slots

| ID | Slot | End of word | Inside the word | At the word's start | No word | Words after the cursor |
|---|---|---|---|---|---|---|
| K53 | `--color`'s value | `git --color al‸`, `git --color=al‸` | `git --color a‸l`, `git --color=a‸l` | `git ‸--color=al` | `git --color ‸`, `git --color=‸` | `git --color ‸ status` |
| K54 | `--color` abbreviated, ambiguous, or declined | `git --col ‸`, `git --colo=‸` | | | `ls --color ‸` (ls declines the built-in and declares its own), `pacman --color ‸` (declined: an operand) | `git --col ‸ status` |
| K55 | the value of `--completion` or `--docs` on a single-command root (needs checking) | `cp --completion ba‸`, `cp --completion=ba‸`, `cp --docs ma‸` | | | `cp --completion ‸` (no planner branch: may offer cp's source files) | `cp --completion ‸ src` |
| K56 | a built-in subcommand's name on a dispatcher | `git compl‸`, `git doc‸` | `git com‸pl` | `git ‸completion` | (part of `git ‸`) | `git compl‸ bash` |
| K57 | a built-in subcommand's operand | `git completion ba‸`, `git docs ma‸` | `git completion b‸a` | `git completion ‸bash` | `git completion ‸` (`bash`, `zsh`, `fish`, `powershell`; `pwsh` parses but is not listed) | `git completion ‸ extra` |
| K58 | the hidden `__complete` subcommand | `git __c‸` offers nothing | | | `rm __complete ‸` (routes into `__complete`; rm's KLAP-GAP) (needs checking) | `rm __c‸ file` |
| K59 | the words after a built-in that answers alone | `cp --help sr‸` | | | `cp --help ‸`, `cp -h ‸`, `git --version ‸`, `git --help-all ‸` | `cp --help ‸ dst` |
| K60 | a position-independent built-in before the slot | `git --json com‸`, `git --color never com‸` | | | `git --json ‸`, `dd --json ‸` | `git --json com‸ -m x` |
| K61 | a short a built-in gave back, declared by the tool | `ls -h‸`, `rsync -h‸` (the tool's own `-h`, its own description) | `ls -‸h` | `ls ‸-h` | | `ls -h‸ dir` |

Notes:

- K53: `colorValueCandidates` on the raw head, before the walk strips `--color` (planner L79, L390). Every fixture
  that keeps `color` (all but ls and pacman).
- K54: `scan.matched` resolves `--col` by abbreviation mode (planner L391); CompletionTest "an abbreviated color
  option ..." tests. git (Options), curl and rsync (None: `--col ‸` is an operand), ls and pacman (`color = Builtin.Off`).
- Disputed: K54's `pacman --color ‸` (declined: an operand) misreads pacman, whose own `--color <when>` takes a
  required value (`pacman -Q --color` is "option '--color' requires an argument"), so the slot is its value: the
  choices, a pass.
- K55: the planner has a value branch for `--color` only; `--completion`/`--docs` are built-ins, not specs, so no
  branch resolves them (planner L155-170) and the cursor falls to the positional. cp, head, ls, every fixture with
  a root `action` (`metaOptions`). Settled for pacman as a pass: after `--completion` and `--docs` nothing is
  offered (pacman's targets have no candidates); the names themselves are a target, as pacman has neither
  (`pacman -Q --completion bash`: "unrecognized option '--completion'").
- K56: `completion` and `docs` are injected on a root without `action` (`CliBuilder.kt` `build`, `injected`), then
  listed as subcommands (planner L239). git, task-manager; E4 asks whether the real tool has them (real git does
  not).
- K57: `argument("shell").enum<CompletionShell>()` (`CliBuilder.kt` `completionCommand`), `DocFormat` for docs.
  git, task-manager.
- K58: `__complete` is hidden (`CliBuilder.kt` `completeCommand`) so its name is filtered (planner L241), but
  `walkTo` still routes into it (`Cli.kt` `resolveSubcommand`: hidden children take part). rm, every fixture.
  Settled for pacman: `pacman -Q __complete --verb‸` passes, a first word `pacman __complete --verb‸` offers nothing
  where real pacman reads a target (`pacman -Q __complete`: "package '__complete' was not found"): a target.
- K59: the planner completes as if the built-in were absent: `--help` is not stripped and stays in the segment. cp,
  git; the real tool's own completion decides each cell.
- K60: `walkLine` strips `--json`, then `--color` with its value, before the walk (planner L303-304). git, dd,
  task-manager.
- K61: `builtins { help = Help.Generate(short = null) }` drops klap's `-h` (planner L191) so the declared flag answers. ls, rsync.

## Routing: which command the slot belongs to

| ID | Slot | End of word | Inside the word | At the word's start | No word | Words after the cursor |
|---|---|---|---|---|---|---|
| K62 | a slot reached through an alias | `git stage fi‸`, `git remote rm or‸` | `git stage f‸i` | `git stage ‸file` | `git stage ‸`, `git stage -‸` | `git stage ‸ other` |
| K63 | a slot reached through an abbreviated subcommand (`Abbreviation.All`) | `tasks ad --pr‸`, `tasks l -‸` (`l` reaches both `list` and its alias `ls`: one command) | | | `tasks don ‸`, `tasks wh ‸` (a hidden child, reached by prefix) | `tasks don ‸ --file x` |
| K64 | the same abbreviation under `Abbreviation.Options` (does not route) | `git comm -‸` (root names, not commit's) | | | `git comm ‸` | `git comm ‸ file` |
| K65 | a subcommand after a global before it | `git -C dir com‸`, `git --no-pager com‸`, `git -c k=v rem‸` | `git -C dir co‸m` | `git -C dir ‸commit` | `git -C dir ‸`, `git -P ‸` | `git -C dir com‸ -m x` |
| K66 | a nested subcommand with a global between the levels | `git remote --bare ad‸`, `tasks -v list -‸` | | | `git remote --no-pager ‸` | `git remote --bare ad‸ origin url` |
| K67 | the cursor before a subcommand typed after it | `git co‸ commit` | | | `git ‸ commit`, `git -‸ commit` | `git ‸ commit -m x` (the slot is the root's; the walk of the whole line reaches another command) |
| K68 | the cursor word names a subcommand, with words after that a provider reads (`Abbreviation.All`) | `tasks a‸ --file x` | | | | `remote a‸ --url x` (the parent's provider still answers) |
| K69 | a hidden subcommand | `git va‸` offers nothing | | | `git var ‸`, `git var -‸` (its own inputs complete once typed) | `git var ‸ x` |
| K70 | a hybrid's first slot: its subcommands beside its own operand | `git remote a‸` (remote acts and nests), `tool remote or‸` with an operand provider | `git remote a‸d` | `git remote ‸add` | `git remote ‸`, `tool remote ‸` (names and operand values together) | `git remote ‸ origin` |
| K71 | a hybrid whose first operand is a path | `tool get fi‸` | | | `tool get ‸` (only the file directive; subcommand names dropped) | `tool get ‸ more` |
| K72 | a hybrid's second slot | `tool remote origin x‸` | | | `tool remote origin ‸` (no subcommand names past the first slot) | |
| K73 | three or more levels deep (needs checking: no fixture nests three) | `tool a b c‸` | `tool a b ‸c` | | `tool a b ‸` | `tool a b c‸ x` |

Notes:

- K62: `walkTo` resolves aliases through `resolveSubcommand`'s name-and-alias pool (`Cli.kt`). git (`stage`, `rm`),
  task-manager (`ls`).
- K63: `walkTo` infers under `Abbreviation.All` only (planner L368); a prefix of a name and its own alias is one
  command (`Cli.kt` `resolveSubcommand`). task-manager.
- K64: same, `infer = false`. git (Options), and every Options or None fixture with subcommands.
- K65: globals stripped before the walk, flags, negative shorts and valued options alike (planner L307). git,
  task-manager.
- K66: the same strip between levels ("`-v issue show` resolves like `issue show`", planner L301). git,
  task-manager.
- K67: the whole line walks to another command, so `wholeLine` is null and `cmd` sifts the line itself (planner
  L102, L109). git.
- K68: CompletionWholeLineTest "a word under the cursor naming a subcommand does not take the parents provider
  into it". task-manager, invented hybrid.
- K69: name filtered (planner L241), the walk still routes (`Cli.kt`). git (`var`), task-manager (`where`).
- K70: subcommands only at `positionalIndex == 0`, then `+ positionalCandidates` (planner L239-250). git
  (`remote`), invented operand.
- Disputed: K70 reads `git remote` as a hybrid that acts and nests, where the agreed P2 names `git remote` a group that only shows help and yields no record.
- K71: `isNativeFileRequest` returns the directive alone (planner L234); CompletionTest "complete files stays
  exclusive of a sibling subcommand name at the first positional". Invented.
- K72: past the first slot subcommands are empty (planner L247). Invented.
- K73: `walkTo` loops to any depth. Invented.

## Operand slots

| ID | Slot | End of word | Inside the word | At the word's start | No word | Words after the cursor |
|---|---|---|---|---|---|---|
| K74 | an optional operand before a variadic | `git log ma‸` | `git log m‸a` | `git log ‸main` | `git log ‸` (revision-range: nothing offered), `git log main ‸` (paths) | `git log ‸ src` |
| K75 | an operand past the last slot | `git remote add origin url x‸` | | | `git remote add origin url ‸`, `git remote remove origin ‸` (nothing) | `git remote remove origin ‸ x` |
| K76 | a hidden operand (needs checking: no fixture) | `tool fo‸` | | | `tool ‸` (nothing for the operand; subcommand names still) | |
| K77 | a variadic with a minimum before a fixed slot, below that minimum | `cp sr‸`, `mv sr‸` (the word is a source) | | | `cp ‸`, `mv ‸` | `cp ‸ dst` |
| K78 | a variadic whose minimum another input lifts (`.requiredUnless()`) | `rm -f fi‸` | | | `rm -f ‸`, `rm ‸` | `rm -f ‸ other` |
| K79 | an operand after a flag, an option value, or an attached value | `cp -v sr‸`, `cp -S .b sr‸`, `cp --backup=t sr‸` | | | `cp -v ‸`, `cp -S .b ‸`, `cp --backup=t ‸` | `cp -S .b ‸ dst` |
| K80 | an operand after a lone `-` (standard input) | `head - fi‸` | | | `head - ‸`, `tar -cf - ‸` | `head - ‸ other` |
| K81 | `-` as an option's value | `tar -cf -‸` (the file directive for `-f`, not names) | | | | `tar -cf -‸ src` |
| K82 | an operand shaped `key=value`, completed by a provider | `dd i‸`, `dd conv=no‸`, `dd if=/de‸` | `dd if=/d‸ev` | `dd ‸if=x` | `dd ‸`, `dd if=‸` (`filesAfter("if=")`), `dd bs=‸` | `dd if=‸ of=out` |
| K83 | an operand slot an `.absentWhen()` trigger removes, the trigger attached, glued or abbreviated | `cp --target-directory=d a‸`, `cp -td a‸`, `cp --target=d a‸`, `chmod --ref=r fi‸` | | | `cp -td ‸`, `chmod --reference=r ‸` | `cp ‸ --target=d` |
| K84 | a trigger that lost a `lastWins` set (needs checking: no fixture) | `tool --to d --no-to a‸` | | | `tool --to d --no-to ‸` (the slot is back) | `tool ‸ --to d --no-to` |
| K85 | a dash-led word admitted to a `.dashLed()` slot, then the next operand | `chmod -w fi‸` | | | `chmod -w ‸` (files, the mode slot filled) | `chmod -w ‸ other` |
| K86 | an operand of a variadic carrying everything after the first operand | `ssh web1 l‸` | | | `ssh web1 ‸`, `ssh web1 ls ‸` | `ssh web1 ‸ -la` |
| K87 | an operand after `--` on a variadic that holds both sides | `find . -- -na‸` | | | `find . -- ‸` | `find . -- ‸ -print` |

Notes:

- K74: `slotForOperand` gives an optional slot `take = 1` (planner L351). git (`log`).
- K75: `slotForOperand` returns null (planner L356), nothing offered. git.
- K76: `takeUnless { it.hidden }` on the slot (planner L227). Invented.
- K77: the variadic floored at its minimum (planner L349). cp, mv (`source` min 1, then `dest`), chmod.
- K78: `requiredUnless` lowers the minimum (`Converters.kt` `requiredUnless`). rm.
- K79: options and their values never take a positional slot (CompletionTest "options and their values never
  consume a positional slot"). Every fixture.
- K80: `isFlagLike` excludes `-` (`Parser.kt` L60). head, tar, cp.
- K81: the value branch takes the word whatever it looks like (planner L157), `.file()` gives the directive. tar,
  curl (`-o -`).
- K82: `completeWith` plus `completeFiles(nonPathPrefix)` (`Completion.kt` `CompletionScope.completeFiles`). dd.
- K83: `activeArguments` reads `supplied` over `wholeSifted`, which binds every spelling (planner L226). cp, mv,
  chmod.
- K84: `activeArguments` passes `lastWinsLosers` (`Parser.kt` `activeArguments`). Invented.
- K85: `dashLedAdmitted` in `sift`; the next slot counts it (planner L220). chmod.
- K86: `optionsEndAtFirstOperand`; the trailing variadic has no provider. ssh.
- K87: find's single variadic after `--` (find's own comment). find.

## Number inputs

| ID | Slot | End of word | Inside the word | At the word's start | No word | Words after the cursor |
|---|---|---|---|---|---|---|
| K88 | a digit run, then letters | `head -5v‸`, `git log -3p‸` | `head -5‸v` | `head ‸-5v` | | `head -5v‸ file` |
| K89 | letters, then a digit run | `head -v12‸` | `head -v1‸2` | `head ‸-v12` | | `head -v12‸ file` |
| K90 | a value-taking short after a run | `git log -3n ‸`, `head -5n ‸` (needs checking: the tool's own reading) | | | | `git log -3n ‸ path` |
| K91 | a value glued after a run | `git log -3n5‸` | `git log -3n‸5` | | | `git log -3n5‸ path` |
| K92 | a digit that is a declared short beside a number input (needs checking: no fixture has both) | `tool -4‸` (the flag) | | | | `tool -4‸ file` |
| K93 | a run where a value-taking digit short exists | `tool -25‸` (the number 25, not `-2` taking `5`) (needs checking: no fixture) | | | | |

Notes:

- K88, K89: `numberRunAt` inside `firstNonFlagShort` (planner L506); CompletionTest "a cluster opening with a run
  still offers the remaining shorts". head, git (`log`), task-manager (`list`).
- K90: `trailingValueOption` after a run; CompletionTest "a value taking short after a run still completes the next
  word". git, head, task-manager.
- K91: `attachedValueOption` after a run; CompletionTest "a value glued after a run still completes". git, head.
- K92: `numberRunAt` declines a run of declared shorts (`Parser.kt` `numberRunAt`, guide "Numbers on the command
  line"). Invented.
- K93: the run is cut before the per-char walk (`Parser.kt` `numberRunAt` doc). Invented.

## K7: further cluster cases

| ID | Cluster case | End of word | Inside the word | At the word's start | Words after the cursor |
|---|---|---|---|---|---|
| K7i | klap's help short in a cluster | `cp -rh‸`; does `cp -r‸` offer `-rh`? | `cp -r‸h` | `cp ‸-rh` | `cp -rh‸ src dst` |
| K7j | a negative short in a cluster | `cp -rP‸`; does `cp -r‸` offer `-rP`? `ssh -va‸` | `cp -r‸P` | `cp ‸-rP` | `cp -rP‸ src dst` |
| K7k | a global flag beside local letters | `tasks list -vr‸`, `git commit -aP‸` | `tasks list -v‸r` | | `tasks list -vr‸ x` |
| K7l | a cluster made only of global letters before the subcommand | `tasks -vv‸`, `tasks -vv l‸` (routes on) | | | `tasks -vv‸ list` |
| K7m | a global value-taking short ending a cluster | `tasks -vf‸`, `tasks -vfstore.json‸` (glued value) | `tasks -vf‸store.json` | | `tasks -vf ‸ list` (the value in its own word) |
| K7n | a global value-taking short after local letters (needs checking) | `tasks list -rf‸`, `tasks list -rfx.json‸` | | | `tasks list -rf ‸` |
| K7o | another spelling of an input already in the cluster | `tool -r‸` offers `-rR`? (`flag(short = listOf('r', 'R'), long = "recursive")`, one holder); cp's `-r`/`-R` are two flags, `cp -r‸` offering `-rR` | | | `tool -r‸ src` |
| K7p | a counted letter given twice already | `ssh -vv‸`, `pacman -Syy‸` | `ssh -v‸v` | | `ssh -vv‸ host` |
| K7q | a member of an exactly-one or at-most-one set in the cluster | `pacman -S‸` (no `-SQ`, `-SR`), `tar -c‸` (no `-cx`), `tar -cz‸` (no `-czj`) | `tar -c‸z` | | `tar -cz‸ src` |
| K7r | a punctuation or digit letter in a cluster | `curl -s:‸`, `ls -1l‸`, `curl -4s‸` | `ls -1‸l` | | `ls -1l‸ dir` |
| K7s | a cluster with a letter that names nothing | `cp -rz‸` (nothing), `cp -zr‸` | `cp -r‸z` | | `cp -rz ‸` (the word after it: an operand?) |
| K7t | `=` right after a flag letter | `cp -v=‸`, `cp -rv=x‸` | | | `cp -v=‸ src dst` |
| K7u | a cluster on a `.dashLed()` command | `chmod -R‸` (every letter a flag: continuations), `chmod -Rw‸` (not a full cluster: the mode operand) (needs checking) | `chmod -R‸w` | | `chmod -Rw‸ file` |
| K7v | a cluster after options ended at the first operand (needs checking) | `ssh web1 -vt‸` (an operand, no continuations) | | | `ssh web1 -vt‸ x` |
| K7w | a cluster ending in an optional-value short | `git commit -aS‸`, `git commit -aSkey‸` | `git commit -aS‸key` | | `git commit -aS ‸` (an operand) |
| K7x | a cluster ending in a value-taking short, value in the next word | `tar -cvf ‸`, `cp -rS ‸` | | | `tar -cvf ‸ src` |
| K7y | a flag-spelled command's letter heading a cluster | `pacman -Qi‸`, `pacman -Syu‸` | `pacman -Q‸i` | `pacman ‸-Qi` | `pacman -Qi‸ pkg` |
| K7z | the command letter not first in its cluster (47e) | `pacman -iQ‸` | `pacman -i‸Q` | | `pacman -iQ‸ pkg` |
| K7aa | a global letter and the command letter in one cluster | `pacman -vQi‸`, `pacman -Qvi‸` (`-v` global) | `pacman -Qv‸i` | | `pacman -Qvi‸ pkg` |
| K7ab | a global value-taking letter ending a cluster that holds the command letter | `pacman -Qvb‸`, `pacman -Qvb/tm‸` | `pacman -Qvb/‸tmp` | | `pacman -Qvb ‸ pkg` |
| K7ac | a command's own letter in a word before the command's letter (47d) | `pacman -i -Q‸`, `pacman -i -‸` | | | `pacman -i -Q‸ pkg` |
| K7ad | two command letters of one parent in one cluster or line | `pacman -QS‸` (nothing), `pacman -Q -S‸` | | | `pacman -Q‸ -S` |
| K7ae | a letter two commands spell differently (pacman's `-c`: `--changelog` under `-Q`, `--cascade` under `-R`) | `pacman -Qc‸`, `pacman -Rc‸` | | | `pacman -Qc‸ pkg` |
| K7af | the help short or the version short beside the command letter | `pacman -Qh‸`, `pacman -hQ‸`, `pacman -QV‸` | | | `pacman -Qh‸ pkg` |
| K7ag | a parent's own (not global) letter in a word command's cluster | `tasks list -Xr‸` with `-X` the root's own flag | `tasks list -X‸r` | | `tasks list -Xr‸ x` |
| K7ah | a flag-spelled command with word subcommands, a cluster after the subcommand (needs checking: no real tool) | `tool -Q list -vi‸` | | | `tool -Q list -vi‸ x` |

Notes:

- K7i: since 2026-10-10 (todo 34) `firstNonFlagShort` steps over the help short where it is on, as the parse does,
  so `-rh` continues. pacman: `pacman -Qhi` prints the -Q page and `pacman -Qhz` is "invalid option -- 'z'"; klap's
  `ShortClusterCompletionTest` `the help short continues a cluster as any flag does`. Every fixture keeping
  `helpShort`.
- K7j: continuations include negative shorts (planner L184, L212), but `firstNonFlagShort` looks up `flagLookup`
  only (L511), so a typed negative short ends the cluster. cp (`-P`), ssh (`-a`), git (global `-P`).
- K7k: `flagLookup` adds `globalAcc.flagSpecs` (planner L521). task-manager (`-v`), git (`-P`).
- K7l: `ArityWalk.clusterClaim` pre-strips an all-global cluster (`internal/parse/ArgvScan.kt`). task-manager.
- K7m: `attachedValueOption` and `trailingValueOption` with a global option (planner L456). task-manager (`-f`).
- K7n: the cluster is not pre-stripped (one local letter), so the command's own sift must find the global.
  CompletionTest "a global hiding in a mixed short cluster resolves". task-manager.
- K7o: continuations drop only the typed letters (planner L213), not other spellings of the same input. Invented
  (guide "Spellings"); cp declares `-r` and `-R` as two flags (its own comment), the nearest fixture case.
- K7p: since 2026-10-10 (todo 15) a counted letter continues a cluster that holds it, as often as typed
  (`ShortClusterCompletionTest` `a counted short continues a cluster that already holds it`); companion to K7e.
  ssh, pacman, tar, curl, rsync.
- K7q: since 2026-10-10 (todo 1) a member in the cluster being continued, or typed after the cursor, rules the
  other members out (`CompletionLineRulesTest`). pacman, tar.
- K7r: `Candidate("-$it")` for non-letter shorts (L181). curl (`-:`, `-4`), ls (`-1`), ssh.
- K7s: `firstNonFlagShort` stops at the unknown letter, the prefix filter leaves `[]` (planner L217). Every fixture.
- K7t: `attachedValueOption` finds no option for `=` (planner L557), names give `[]`. cp, every fixture.
- K7u: `shortClusterResolvesInFull` decides dashLed admission (`Parser.kt` sift) while the planner's names branch
  does not consult it (L175). chmod.
- K7v: as K30. ssh.
- K7w: as K48 inside a cluster. git (`-S`).
- K7x: as K36; the cluster-shaped half of the own-word value. tar, cp, ssh.
- K7y to K7af: a flag-spelled command (todo 47, 48; decided 47d and 47e) reads its cluster as one set: the command
  letter picks the command wherever it stands, the other letters are that command's options or globals, and a
  value-taking letter takes the rest. Since klap 0.6.0 `command(short = 'Q', long = "query") { }` declares one, and
  klap's own `FlagCommandTest` pins K7y to K7ah as parses and completion, and pacman's operations are such commands
  since 2026-10-10, so its own cells pass too (todo 4, 5, 12).
- K7ag: a parent's own flag is not global, so in a subcommand's cluster it names nothing, as K7s; the parse
  refuses it.
- K7h, K7k to K7n and K7ag are pinned in klap's own `ClusterMatrixTest`, each as a parse and as completion, and
  pass (2026-10-10).
- K7ah: the case that combines all three kinds: a global, a flag-spelled command's own letter and a word
  subcommand's letter. No real tool has it; klap's own tests.

## Cursor columns

The columns of K and K7 are these cursor positions, `‸` being the cursor. The last four are extra columns: a row
they apply to writes them as cells of its nearest standard column.

| Column | Cursor | What klap is handed | Rows it applies to |
|---|---|---|---|
| End of word | right after a word's last character | the whole word | every K and K7 row |
| Inside the word | between two of its characters | the word as far as typed; the rest stays on the line | every K and K7 row |
| At the word's start | on its first character | an empty word; the rest of that word is not sent | every K and K7 row |
| No word | after whitespace, or on an empty line | an empty word | every K row (a cluster has none) |
| Words after the cursor | any of the above with more of the line after it | the words after it as well (fish never sends them: a shell rule, noted where it matters) | every K and K7 row |
| Right before the `=` | between a long name and its `=` | the name only; the `=value` stays on the line | K9, K43, K51, K53 |
| Inside the dashes | between the two dashes of a long | `-`, so shorts are offered too | K2, K4, K5, K13 |
| On whitespace between two words | after a space, with a word right after | an empty word plus the words after (fish sends none of them) | K12, K67, K70 |
| A whole name, no space yet | right after a name that is complete | the whole name: still that slot, never the next | K1, K2, K19, K20, K56 |

## Required cells

| Slot | Columns | L | S | V |
|---|---|---|---|---|
| K1 | all five | L1 L2 L3 | S1 S2 S3 | |
| K2 to K6 | end, inside, start, words after; "no word" shows names are not offered before a dash | L1 L2 L3 | S1 S2 S3 S4 | |
| K7a to K7e | as each row lists | L1 L2 L3 | S1 S2 S3 | |
| K7f to K7h | as each row lists | L1 L2 | S1 | each source the input declares |
| K7y to K7ah | as each row lists, each also as a parse (P): what the cluster binds, or its refusal | L1 L2 L3 | S1 S2 S3 | each source the input declares |
| K8 to K10 | as each row lists | L1 L2 L3 | S1 S3 | each source the input declares |
| K11 | as its row lists | L1 L2 | S1 | each source the input declares |
| K12 | all five | L1 L2 L3 | S1 S5 | each source the operand declares |
| K13 | end, inside, start, words after | L1 L2 | S1 | |
| K14 | as its row lists | L4 | S1 | |
| K15 to K17 | as each row lists | L1 L2 | S1 | |

This is the base table; `L-S.md` and `V.md` add rows for their own tables. A split row falls under its parent's line (K4a to K4d under K2 to K6,
K7e1 and K7e2 under K7a to K7e, K8a to K8e under K8 to K10, and so on); the new rows K20 to K93 and K7i to K7x have
no required-cell line yet.
