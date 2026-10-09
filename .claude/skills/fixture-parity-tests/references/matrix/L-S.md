# Tables L and S

Purpose: which words after the cursor bear on the completion slot (L), and what the rest of the line already holds (S), as cells every fixture pass covers.

- [Code paths](#code-paths)
- [L: existing rows and their splits](#l-existing-rows-and-their-splits)
- [L: constraints and repeats after the cursor](#l-constraints-and-repeats-after-the-cursor)
- [L: slot-changing words after the cursor](#l-slot-changing-words-after-the-cursor)
- [L: context words after the cursor](#l-context-words-after-the-cursor)
- [L: shell rule](#l-shell-rule)
- [S: existing rows and their splits](#s-existing-rows-and-their-splits)
- [S: exclusive sets](#s-exclusive-sets)
- [S: repeats](#s-repeats)
- [S: override sets](#s-override-sets)
- [S: conditional operands and options](#s-conditional-operands-and-options)
- [S: end of options](#s-end-of-options)
- [S: subcommand context and globals](#s-subcommand-context-and-globals)
- [Required cells](#required-cells)

## Code paths

The notes below cite klap's source by these letters:

- `C` = `klap/src/commonMain/kotlin/com/fromwau/klap/internal/render/Completion.kt`
- `P` = `klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Parser.kt`
- `B` = `klap/src/commonMain/kotlin/com/fromwau/klap/internal/parse/Bind.kt`
- `CB` = `klap/src/commonMain/kotlin/com/fromwau/klap/CommandBuilder.kt`, `BI` = `.../internal/builder/BuilderImpl.kt`

Facts from the source that most rows lean on:

- Name completion rules out the other members of a set from `membersRuledOutBy(sifted)` (C:177, C:326), and
  `sifted` is the sift of the words **before** the cursor only (C:95). The cluster under the cursor and every word
  after it are not read for constraints. Operand slots, absentWhen triggers and provider values do read the whole
  line (`wholeSifted`, C:104 to C:112; `activeArguments(wholeSifted)`, C:226).
- The flag-name branch is suppressed only by a literal `--` in the head (C:175). The value branches before it
  (`valueOption` C:157, `attachedValueOption` C:165) do not check for `--`, and none of the three check
  `optionsEndAtFirstOperand` (set in sift at P:618 and P:715).
- A supplied input stays offered (comment on C:317 to C:325), so no name is ever dropped for being given already;
  a non-repeatable option given twice binds its last value silently (B:131).
- Globals are added to every command's names (C:178) and stripped from the line before the subcommand walk
  (`walkLine`, C:296), so klap reads a global anywhere; the walk itself stops at the first word that names no
  subcommand (`walkTo`, C:367).
- `firstNonFlagShort` (C:502) peels only declared flag shorts and digit runs, not negative shorts or the help
  short, so a cluster holding one of those is not treated as a cluster at all.

## L: existing rows and their splits

| ID | Line | Example |
|---|---|---|
| L1 | the cursor at the end of the line | `cp -v ‸` |
| L2a | flags or options after the cursor that do not bear on the slot: the answer is the same as L1 | `cp ‸ -v` |
| L2b | operands after the cursor: the same as L1 for a name slot; for an operand slot they move it (L13) | `cp -‸ src dst`; `cp ‸ dst` |
| L3a | a word after the cursor that a provider reads (V8): the answer follows it | `get ‸ -f conf.toml` |
| L3b | a word after the cursor that a constraint reads (L5, L6): the answer follows it | `pacman --ne‸ -S` |
| L3c | a word after the cursor that counts as an operand (L13): the answer follows it | `cp ‸ a b` |
| L3d | a word after the cursor that is a trigger (L11, L12): the answer follows it | `cp ‸ -t dir` |
| L4a | inside the end-of-options region: the cursor after `--` on a name or operand (K14) | `cp -- ‸` |
| L4b | a flag-shaped word right before the cursor, after `--` | `cp -- --backup ‸` (**needs checking**, read from source) |
| L4c | an attached-value word after `--` | `cp -- --backup=‸` (**needs checking**, read from source) |
| L4d | options ended by an operand (S34) | `ssh host ls -‸` |

- L2a, L2b: operands after the cursor move the slot (C:222, `slotForOperand`), flags do not; an operand cell can
  never be "the same as L1" for K12.
- L3a to L3d: four code paths: `valuesFor` over `wholeSifted` (C:116), `membersRuledOutBy(sifted)` (head only,
  C:177), `slotForOperand` (C:338), `activeArguments(wholeSifted)` (C:226). Today only the first, third and fourth
  read the words after the cursor.
- L4a to L4d: C:175 checks `--` for names only; C:157 and C:165 take `--backup` as an option after `--`, so
  `cp -- --backup=n‸` probably offers `--backup=never` and `cp -- -S ‸` treats the slot as `-S`'s value
  (**needs checking**, read from source).

## L: constraints and repeats after the cursor

| ID | Line | Example |
|---|---|---|
| L5 | a member of an exactly-one or at-most-one set after the cursor: the other members are not offered | `tar -‸ -c` offers no `-x`/`-t`; `tar -‸ -z` offers no `-j`; `pacman -‸ -S` offers no `-Q` |
| L6 | the same member of an exactly-one set after the cursor: offered or not as the tool takes a repeat | `pacman -‸ -Q` offers no `-Q` (item 6: `-QQ` refused); `tar -‸ -c` keeps `-c` (`tar -c -c` accepted) |
| L7 | a `lastWins` partner after the cursor: nothing is ruled out, the answer is L1's | `head -‸ -c 5` offers `-n`; `cp -‸ -n` offers `-i` |
| L8 | a non-repeatable input after the cursor that the tool refuses twice | `pacman -‸ --root /` with a tool that refuses a second `--root` (**needs checking** which fixture refuses repeats) |
| L9 | a counted flag after the cursor: still offered | `ssh -‸ -vv` offers `-v`; `tar -‸ -v` offers `-v` |
| L10 | a `.multiple()` option after the cursor: still offered | `tar --ex‸ --exclude a` offers `--exclude` |

- L5: C:177 `membersRuledOutBy(sifted)` reads only the head (C:95), so this is likely a target. Fixtures: tar
  (`requireExactlyOne(create, extract, listContents)`, `requireAtMostOne(gzip, bzip2)`), pacman. `tar -c -x` gives
  "You may not specify more than one '-Acdtrux' ... option"; `tar -czjf b.tar x1` gives "Conflicting compression
  options".
- L6: same path; klap keeps the supplied member offered by design (C:317). pacman (`PacmanProjectionTargetsTest`
  item 6), tar (`tar -c -c -f a.tar x1` succeeded).
- L7: C:327 filters out `LastWins` sets; head (`lastWins(lines, bytes)`), cp (`lastWins(interactive, noClobber)`),
  mv, ls, chmod, find.
- L8: C:178 never filters a supplied input; B:131 binds the last value. No fixture known to refuse a repeat of a
  plain option; mark `n/a` where the tool accepts it.
- L9: C:178 names list; `.count()` on tar `-v`, ssh `-v`/`-t`, rsync `-v`/`-h`, pacman counted letters, curl `-:`.
- L10: C:178; tar `--exclude`, curl `-H`/`-d`, ssh `-i`/`-L`, git `-c`/`-C` (global), ls `--hide`/`--ignore`.

## L: slot-changing words after the cursor

| ID | Line | Example |
|---|---|---|
| L11 | an `.absentWhen()` trigger after the cursor, in every spelling the tool takes | `cp ‸ -t dir`, `cp ‸ -tdir`, `cp ‸ --target-directory=dir`, `cp ‸ --target dir` (abbreviated), `cp ‸ -vt dir` (in a cluster) |
| L12 | a `.requiredUnless()` trigger after the cursor: the slot stays, its values still offered | `rm ‸ -f` offers files |
| L13 | operands after the cursor that move the slot under it | `cp ‸ a b` (a source), `cp a ‸ b c` (a source), `cp a b ‸ c` (a source), `chmod ‸ f` (the mode) |
| L14 | `--` after the cursor: the words behind it are operands, never inputs | `cp -‸ -- -v` keeps offering `-v` as unsupplied; `cp ‸ -- -t dir` keeps the destination slot (`-t` is an operand); `cp ‸ -- -x` counts `-x` as the destination |
| L15 | a dash-led word after the cursor that an option takes as its value | `cp ‸ -S -t` (`-t` is the suffix, no trigger); `tar -‸ -f -x` (`-x` is the archive, so `-x` is still offered) |
| L16 | an operand after the cursor that ends options (`optionsEndAtFirstOperand`): the dash-led words behind it are not inputs | `ssh -‸ host ls -v` (the trailing `-v` is the remote command's) |

- L11: `activeArguments(wholeSifted)` C:226 with `supplied` P:311; each spelling goes through a different sift
  branch (long, attached, abbreviated via `resolveLong`, glued short, cluster). cp, mv (`-t`), chmod
  (`--reference`). Extends K12d, which has one spelling.
- L12: `relaxedWhen` is read only by the bind (B:217); completion offers the slot regardless. rm
  (`.requiredUnless(force)`).
- L13: `slotForOperand(positionalIndex, operands)` C:338 with `operands` from `wholeSifted` C:222. cp, mv, chmod
  (`mode` then `file...`), ssh (`destination` then `command...`). Existing test: `CompletionWholeLineTest`
  `the slot under the cursor counts the operands after it`.
- L14: sift's `END_OF_OPTIONS` branch P:606 inside `wholeSifted`; the head check C:175 does not see a `--` after the
  cursor, which is right. cp, mv, rm, head.
- L15: value consumption in sift (P:664, P:769) within `wholeSifted`. cp (`-S`), tar (`-f`), head (`-n`).
- L16: P:618/P:715 set `optionsEnded` inside `wholeSifted`. ssh only. See S34 for what real ssh does. Owner
  decision (K30): real ssh reads options after the host until the command's first word; evidence
  `ssh -G web1 -p 2222` gives port 2222, `ssh -G web1 ls -p 2222` gives port 22.

## L: context words after the cursor

| ID | Line | Example |
|---|---|---|
| L17 | a subcommand name after the cursor: the slot belongs to the command the head reaches | `git ‸ log`, `git -C ‸ log`, `git --no-pa‸ log`, `git remote --tag ‸ add` |
| L18 | the word under the cursor already names a subcommand in full: its siblings sharing the prefix, not its own inputs | `git status‸ -s`, `git remote add‸ origin url` |
| L19 | a global after the cursor: the slot under the cursor is unchanged, a provider reads its value | `git ‸ --no-pager`, `git log ‸ -C dir` (log's own `-C` in real git) |
| L20 | a klap meta-option after the cursor (`--json`, `--color WHEN`, `--help`): no operand, no slot change | `cp ‸ --json`, `cp ‸ --color never`, `cp a ‸ --help` |
| L21 | malformed words after the cursor (unknown option, value missing at the end, too many operands): the answer is L1's | `cp -‸ --zzz a b`, `cp ‸ a b -S`, `head -‸ -n` |
| L22 | an abbreviated or ambiguous long after the cursor | `tar -‸ --cre` rules out `-x`; `cp ‸ --target dir`; an ambiguous `--re` rules out nothing |
| L23 | a negated member after the cursor: only the positive form selects | `-‸ --no-<member>` keeps every member offered (**needs checking**: no fixture has a negatable flag in an exactly-one or at-most-one set) |
| L24 | a positional member of an exactly-one set after the cursor | `tool --‸ name` where `requireExactlyOne(--file, <name>)` (**needs checking**: no fixture declares one) |
| L25 | a hybrid command with an operand after the cursor | `tool sub ‸ value`: subcommand names and operand values both offered (**needs checking**: git `remote` has no operand of its own) |
| L26 | the rest of the word under the cursor would change the answer, but is not sent | `tar -‸c` (klap is handed `-`), `cp --‸target-directory=d a` |

Disputed: L20's cells put `--json` and `--color` on a `cp` line, while G11 says cp offers no `--json` because real cp has none (G11 decides whether these cells exist at all).

- L17: `wholeLine` takes the whole-line walk only when it reaches the same command, else sifts the head plus the
  rest (C:102 to C:112). git. Existing tests: `CompletionWholeLineTest` `the planner reads only the words before the
  cursor`, `a variadic option leaves out only the value under the cursor`.
- L18: `walkLine(head)` versus `walkLine(words)` C:68 and C:102; `CompletionWholeLineTest` `a word under the cursor
  naming a subcommand does not take the parents provider into it`. git (`status`/`stash`, `remote add`).
- L19: `siftGlobals` strips globals before the walk (C:307). git, task-manager. Real `git log -1 --oneline -C .`
  read `-C` as log's copy detection, not the global; `git log --no-pager -1` gave "fatal: unrecognized argument:
  --no-pager". Owner decision (K38): git globals after the subcommand are neither names nor value slots (real git
  refuses `git remote add -C . o u`, `git status --no-pager`, `git status -c x=y`); task-manager's global reaches
  every depth.
- L20: `scan.strip("json")` and `stripValued("color")` in `walkLine` C:303 to C:304; `colorValueCandidates` C:390.
  Every fixture that keeps the built-ins (cp, dd, head ...), and whether the real tool has them at all (G11).
- L21: `sifted.error` is ignored (C:96); `wholeSifted` keeps what the walk collected. Every fixture.
- L22: `resolveLong` against `longMatchPool` in sift P:625; cp, tar, head, ls (`Abbreviation.Options`).
- L23: `supplied` counts a negatable flag only in its positive form (P:319). Synthetic until a fixture has one.
- L24: constraints may hold a positional (only `lastWins` refuses one, BI:183); `supplied(ArgumentSpec)` P:325.
- L25: C:239 offers subcommands only at `positionalIndex == 0`, beside the operand's own candidates (C:250).
- L26: cursor column rule ("the rest of that word is not sent"). **needs checking**: what the oracle is here, since
  bash hands the word up to the cursor and the tool would see the whole word.

## L: shell rule

| ID | Line | Example |
|---|---|---|
| L27 | fish: the words after the cursor are never sent, so every L3 and L5 to L25 cell answers as L1 | `get ‸ -f conf.toml` under fish |

- L27: the guide's whole-line section (`docs/guide.md`, "fish shows a completion the line only up to the end of
  the word"). Only for live-shell tests (kotoml `live-test`); unit cells mark it `n/a: fish rule`.

## S: existing rows and their splits

| ID | State | What must hold |
|---|---|---|
| S1 | nothing relevant | the plain candidates |
| S2 | a member of an exactly-one or at-most-one set given, before the cursor or in the cluster under it | the other members not offered |
| S3 | an input already given | not offered again, unless the tool repeats or counts it |
| S4 | a `lastWins` pair half given | both still offered |
| S5 | an input another one requires, or one an `.absentWhen()` trigger removes | offered or left out as the real tool accepts |

- S2: split into S6, S7, S8, S9, S10, S12, S13, plus L5 for "after the cursor": the cluster under the cursor
  (todo 1) and the words after it are read by different code than an earlier word; a single S2 cell passing hides
  both.
- S3: split into S16 plain flag, S17 counted in another word, S18 counted in the cluster, S19 `.multiple()`, S20
  non-repeatable option, S10 set member: each has its own expectation (counted letters must reappear, set members
  per tool) and its own path (C:178 vs C:213).
- S4: split into S22 two flags, S23 flag and option, S24 a fold, S25 a number run: a fold has no spelling, a number
  run sits in a cluster, an option member is matched as a long; all reach `lastWins` differently.
- S5: split into S27 trigger spellings, S28 a trigger that does not hold, S26 a losing trigger, S29
  `requiredUnless`, S30 `requiredIf`: "requires" and "removes" are different rules (B:217 vs P:336 vs P:211), and a
  trigger after `--` or inside a value must not fire.

## S: exclusive sets

| ID | State | What must hold |
|---|---|---|
| S6 | a set member given in its own short word before the cursor | the other members not offered: `tar -c -‸` offers no `-x`, `-t` |
| S7 | a set member given inside an earlier cluster | as S6: `tar -cvf a.tar -‸`, `pacman -Syu --‸` |
| S8 | a set member given in the cluster under the cursor | no continuation adds another member: `tar -c‸` offers no `-cx`, `pacman -Q‸` no `-QS` (todo item 1) |
| S9 | a set member given in long, abbreviated or attached form | as S6: `tar --create -‸`, `tar --cre -‸`, `pacman --sync -‸`, an option member as `--opt=v` |
| S10 | the same member given again | offered as the tool takes a repeat: tar accepts `-c -c`, pacman refuses `-QQ` |
| S11 | a dash-led word consumed as an option's value | is no input: `tar -f -x -‸` still offers `-x`; `cp -S -t a ‸` keeps the destination slot |
| S12 | a member given only in its negated form | every member still offered (**needs checking**, no fixture) |
| S13 | an at-most-one set with nothing given, and with one given | all offered; then the others not: `tar -z -‸` offers no `-j` |
| S14 | an input that sits in two sets at once | each set rules out its own members (**needs checking**, no fixture; BI:191 allows it) |
| S15 | a positional member of an exactly-one set given | the option members not offered, and the operand slot not offered once an option member is given (**needs checking**, no fixture) |

- S6, S7: `membersRuledOutBy` C:326 with `supplied` P:311; sift's cluster walk. tar, pacman.
- S8: the cluster branch C:208 to C:216 builds continuations from `names`, filtered by the head only. Likely target
  (todo item 1, pacman `PacmanCompletionTargetsTest` `a cluster holding an operation offers no other operation`).
- S9: `resolveLong` (P:625) and attached values (`longToken`, P:500). tar, pacman (`--sync`, `--query`).
- S10: C:328 keeps the supplied member; pacman item 6, tar verified.
- S11: value consumption P:664/P:769 and `scan.valueSlots` (C:76, C:89). tar `-f`, cp `-S`, head `-n`.
- S12: P:319.
- S13: same path as S6, at-most-one arity. tar `requireAtMostOne(gzip, bzip2)`.
- S14: `constraints` is a list and C:326 unions every set.
- S15: C:328 rules out options once an operand member is supplied, but the positional branch C:220 to C:229 never
  consults `ruledOut`, so the operand stays offered. klap-level target.

## S: repeats

| ID | State | What must hold |
|---|---|---|
| S16 | a plain flag given | offered again only where the tool accepts a repeat: `cp -v -‸` offers `-v` (`cp -v -v` accepted) |
| S17 | a counted flag given in an earlier word | offered again: `tar -v -‸`, `ssh -v -‸`, `rsync -vv -‸` |
| S18 | a counted flag in the cluster under the cursor | continued once more as the tool counts it: `pacman -Qi‸` offers `-Qii`, `tar -v‸` offers `-vv` |
| S19 | a `.multiple()` option given | offered again: `tar --exclude a --ex‸`, `curl -H a -‸` |
| S20 | a non-repeatable option given | offered again where the tool takes the later value, left out where it refuses: `head -n 1 -‸` |
| S21 | a provider-level repeat: a key already given in a repeated operand | as the tool reads repeats: `dd if=a ‸` offers `if=` again or not |

Disputed: S16 offers a plain flag again wherever the tool accepts a repeat (`cp -v -‸` offers `-v`), while the K7c and K7d note says a letter given earlier is not offered again unless the tool counts it.

- S16: C:178 never drops a supplied name. cp, mv, rm, ls (GNU accept repeats).
- S17: `isCount` flags; C:178. tar, ssh, rsync, pacman, curl `-:`.
- S18: C:213 `filterNot { it.value[1] in typedShorts }` drops every typed letter, counted or not; todo item 15,
  pacman `a counted letter is offered again in its cluster`.
- S19: C:178; `valuesFor` leaves out the value under the cursor (C:122 to C:127, V9).
- S20: B:131 binds `raws.lastOrNull()`. `head -n 1 -n 2 /etc/hostname` was accepted (**needs checking** which
  value won: the file has one line). head, cp `-S`, tar `-f`.
- S21: dd's provider (`example/dd/.../Dd.kt:49`) offers every key every time; real dd's repeat rule and its
  `bs=` overriding `ibs=`/`obs=` are **needs checking**. Overlaps V9 for operands.

## S: override sets

| ID | State | What must hold |
|---|---|---|
| S22 | a `lastWins` pair of two flags half given | both offered: `cp -i -‸` offers `-n`, `mv -f -‸` offers `-i`, `-n` |
| S23 | a `lastWins` set mixing a flag and an option | both offered: `ls -S --so‸` offers `--sort`, `ls --sort=time -‸` offers `-S` |
| S24 | a `lastOneWins` fold given in one spelling | the other spelling and the fold's partner still offered: `head -5 -‸` offers `-n`, `-c`; `git log -3 --max‸` |
| S25 | a number run in a cluster with flags | the run is a given input: `head -5v -‸` offers `-n`, `-c`, `-q` |
| S26 | an `.absentWhen()` trigger that lost a `lastWins` set | the removed slot is back (**needs checking**, no fixture: real cp refuses `-t` with `-T`) |

- S22: C:327; cp, mv, chmod (`-H -L -P`), find. `chmod -R -H -L u+r x1` accepted.
- S23: same; ls `lastWins(sortByTime, sortBySize, unsorted, sortByExtension, sort)`.
- S24: `resolveFolds` P:227; head, git log. The fold has no spelling, so it is never offered by name (G8-like).
- S25: `numberRunAt` P:88 in sift and in `firstNonFlagShort` C:506. head, git log.
- S26: `activeArguments(..., lastWinsLosers)` P:336, P:262. klap-level only.

## S: conditional operands and options

| ID | State | What must hold |
|---|---|---|
| S27 | an `.absentWhen()` trigger given before the cursor, in every spelling | the removed slot is not offered: `cp -t dir ‸`, `cp -tdir ‸`, `cp --target-directory=dir ‸`, `cp --target dir ‸`, `cp -vt dir ‸`, `chmod --reference=r ‸` |
| S28 | a trigger written where it does not hold | the slot stays: after `--` (`cp -- -t d ‸`), as a value (`cp -S -t a ‸`), as an ambiguous abbreviation |
| S29 | a `.requiredUnless()` trigger given | the slot and its values still offered: `rm -f ‸` offers files |
| S30 | a `.requiredIf()` trigger given | the option it requires is offered as before (**needs checking**, no fixture uses `requiredIf`) |
| S31 | a `.dashLed()` operand given | it fills its slot: `chmod -w ‸` is the file slot, `chmod -R -w -‸` offers chmod's flags |
| S32 | a lone `-` given | it is an operand: `head - ‸`, `cp - ‸` (the destination) |

- S27: `activeArguments(wholeSifted)` C:226; cp, mv, chmod. Splits S5.
- S28: sift's `--` branch P:606 and value consumption P:664; same fixtures.
- S29: B:217; rm.
- S30: `checkConditionalRequirements` P:211; nothing in completion reads `requiredWhen`.
- S31: `dashLedAdmitted` in sift; chmod (head deliberately not dash-led).
- S32: `isFlagLike` P:60 excludes `-`; cp, head, tar (`-f -`).

## S: end of options

| ID | State | What must hold |
|---|---|---|
| S33 | a value-taking option directly before `--` | as the tool reads it: GNU getopt takes `--` as the value, so `cp -S -- -‸` still offers cp's flags |
| S34 | options ended by the first operand (`optionsEndAtFirstOperand`) | no names and no option value after the point the real tool stops: `ssh host ls -‸`, `ssh host ls -i ‸` |
| S35 | options after an operand on a permuting tool | names offered: `cp a -‸ b`, `tar -cf a.tar x -‸` |
| S36 | `--` after options already ended by an operand | it is an operand (**needs checking** against ssh) |

- S33: klap refuses `--` as a value (P:664, P:769; guide "Dash-led values"), and then reads `--` as the end of
  options. Real: `cp -b -S -- -v x1 x3` printed `'x1' -> 'x3'`, so `--` was the suffix and `-v` stayed an option.
  Likely target in cp, head (`-n`), tar (`-f`).
- S34: the flag branch checks only `END_OF_OPTIONS in segment` (C:175) and the value branches nothing (C:157,
  C:165), so klap probably offers ssh's options and `-i`'s files in the remote command. Also: real OpenSSH reads
  options right after the destination (`ssh -G host -v ls` printed `debug1:`) and stops at the command's first word
  (`ssh -G host ls -v` printed none), while klap's switch ends options at the destination: a fixture-level
  difference for `ssh host -‸`. Owner decision (K30): an ssh parity target; evidence `ssh -G web1 -p 2222` gives
  port 2222, `ssh -G web1 ls -p 2222` gives port 22.
- S35: P:618 with the switch off. cp, mv, tar, head, ls.
- S36: guide, `optionsEndAtFirstOperand` section: "`--` ... still ends options wherever options have not already
  ended".

## S: subcommand context and globals

| ID | State | What must hold |
|---|---|---|
| S37 | a global given before the subcommand | the walk still reaches it: `git -C d ‸` offers subcommands, `git --no-pager log -‸` offers log's options |
| S38 | a global's value slot before the subcommand | the value, not a subcommand: `git -C ‸`, `git -c ‸` |
| S39 | at a leaf, a global spelling the real tool takes only before the subcommand | not offered where the tool refuses it: `git log --no-pa‸` offers nothing (`git log --no-pager` refused) |
| S40 | a parent's own option before the subcommand name | the walk continues past it: `git remote -v ‸` offers `add`, `remove`; `git remote -v add -‸` offers add's options |
| S41 | an ancestor's or sibling's option at the leaf | not offered: `git remote show -‸` offers no `-v`; `git log -‸` offers no `-m` |
| S42 | a cluster mixing a global short and a local one | read where the local short is legal only: `tool sub -vf‸` continues, `tool -vf‸ sub` does not (**needs checking** which fixture has a global short beside local ones: task-manager `-v`, `-f`) |
| S43 | a subcommand reached by an alias or an abbreviation | its inputs offered: `git stage -‸` as `git add -‸`; `tasks d‸` with `Abbreviation.All` (task-manager) |
| S44 | a klap meta-option before the cursor | no operand, no stop to the walk: `cp --json ‸` is the first operand, `git --json log -‸` |
| S45 | an earlier unknown option or ambiguous abbreviation | an unknown option's next word is not a value: `cp --zzz ‸`; after an ambiguous name nothing is offered: `chmod --re ‸` (owner decision K34) |
| S46 | an optional-value option given bare | still supplied, and the next word an operand: `ls --color ‸`, `cp --backup -‸` |
| S47 | a negative short or the help short in a cluster, before or under the cursor | a cluster like any other: `cp -P‸` offers `-Pv` etc., `cp -Pv‸` offers continuations, `git -P‸` |

Disputed: S44's cells put `--json` on a `cp` and a `git` line, while G11 says only the built-ins the real tool has are offered and cp has no `--json` (G11 decides).

- S37: `siftGlobals` then `walkTo` in `walkLine` C:307 to C:308. git, task-manager.
- S38: `scan.valueSlots` and `trailingValueOption` C:479 with globals in the pool (C:456). git (`-C`, `-c`,
  `--git-dir`). Owner decision (K37): `git -C ‸` offers filesAfter(""), `git -c ‸` nothing.
- S39: C:178 adds `globalSpecs` to every command's names; real git refused `git log --no-pager -1`. git. Owner
  decision (K38): real git refuses `git remote add -C . o u`, `git status --no-pager`, `git status -c x=y`; needs
  todo 27.
- S40: `walkTo` C:367 stops at `-v`; `KLAP-GAP` comment in `example/git/.../Git.kt:313`. Real `git remote -v show`
  listed the remotes. Likely target.
- S41: names are `cmd.options + cmd.flags + globalSpecs` (C:178). Real `git remote show -v` gave "unknown switch
  `v'". Should pass.
- S42: guide, "Global / persistent options" (a cluster means what its characters mean apart).
- S43: `resolveSubcommand(..., infer)` in `walkTo` C:374; git aliases, task-manager `Abbreviation.All`.
- S44: `walkLine` C:303 to C:304. Every fixture keeping the built-ins; G11 decides whether they belong at all.
- S45: `matchingValueOption` returns null on `Ambiguous`/`None` (C:468), so the next word falls to the operand slot.
  Owner decision K34 settles the ambiguous case: expect nothing after an ambiguous name. Evidence: "chmod: option '--re' is ambiguous; possibilities:
  '--recursive' '--reference'".
- S46: `trailingValueOption` C:493 drops a bare optional-value option. ls, cp, mv, rm, mkdir, pacman `--debug`, git
  `--exec-path`. Owner decision (K8, K11, K33): `cp --back numbered a b` reads numbered as a source.
- S47: `firstNonFlagShort` C:502 stops at a negative short (C:497 to C:501 note this). `cp -P` is the negative short
  of `--dereference` in the fixture, git `-P` of `--paginate`, ssh `-a`. Likely target; `cp -Pv` is accepted by GNU
  cp (**needs checking** by a run, read from `cp --help`).

## Required cells

The base required cells are in `K.md`'s "Required cells" (L2, L3 and L4 there stand for their split rows here).
These rows add L and S cells to them. Where a fixture declares nothing of a row's kind, the cell is `n/a: <reason>`.

| Slot | Columns | L | S | V |
|---|---|---|---|---|
| K1 | all five | L17 L18 L19 L20 L21 L25 L27 | S37 S38 S39 S40 S43 S44 | |
| K2 to K6 | end, inside, start, words after | L5 L6 L7 L8 L9 L10 L14 L15 L16 L21 L22 L23 L26 | S6 S7 S9 S10 S11 S12 S13 S14 S16 S17 S19 S20 S22 S23 S24 S33 S34 S35 S39 S41 S44 S45 S46 | |
| K4 | as its row lists | L23 | S12 S39 | |
| K7a to K7e | as each row lists | L5 L6 L9 L26 | S8 S10 S17 S18 S25 S42 S47 | |
| K7f to K7h | as each row lists | L21 | S11 S47 | each source the input declares |
| K8 to K10 | as each row lists | L4b L4c L15 L17 L19 L21 L22 | S11 S33 S34 S38 S45 S46 | each source the input declares |
| K11 | as its row lists | L4c L21 | S46 | each source the input declares |
| K12a to K12c | all five | L11 L12 L13 L14 L15 L16 L20 L21 L24 L25 | S11 S15 S21 S28 S29 S31 S32 S33 S34 S35 S44 S45 S46 | each source the operand declares |
| K12d | all five | L11 L14 L15 L22 | S26 S27 S28 | each source the operand declares |
| K14 | as its row lists | L4a L4b L4c L14 | S33 S36 | |
| K16 | as its row lists | L7 | S24 S25 | |
| K17 | as its row lists | L13 | S31 S35 | |
| K19 | all five | L17 L18 L19 | S37 S40 S41 S43 | |

Which fixtures carry which rows, so a pass knows where its cells are not `n/a`:

| Fixture | Rows that apply |
|---|---|
| pacman | L5 L6 L9 L10 L22 S6 to S10 S16 to S19 S22 S46 |
| tar | L5 L6 L9 L10 L15 L22 S6 to S11 S13 S17 S18 S19 S32 S33 S35 |
| cp, mv | L7 L11 L13 L14 L15 L20 L21 L22 S11 S16 S20 S22 S27 S28 S32 S33 S35 S44 S46 S47 |
| head | L7 L15 S20 S22 S24 S25 S32 S33 |
| ls | L7 S22 S23 S46 |
| chmod | L7 L11 L13 S22 S27 S31 S47 |
| rm | L12 S16 S29 S46 |
| git | L17 L18 L19 S24 S37 to S41 S43 S46 S47 |
| ssh | L9 L10 L13 L16 S17 S34 S36 S47 |
| dd | L13 S21 |
| find | L7 S22 |
| rsync, curl | L9 L10 S17 S19 |
| task-manager | L17 L19 S37 S42 S43 |
| none (klap tests or a synthetic fixture) | L23 L24 S12 S14 S15 S26 S30 |
