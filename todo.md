# Open work

Numbers are never reused. A closed item keeps its place in **Done** with a verdict: what was decided or done, how
it was proved, and the date. Every item was reproduced before it was filed, except those under **Found by reading,
not reproduced**; a test-first item names the failing
test that states it, and closing it means that test passes.

## Defects

## Found by reading, not reproduced

Found by the matrix-extension agents on 2026-10-09 from klap's source (at `ca1bb61`), each with the matrix rows that
test it. A TDD pass writing those rows reproduces or refutes each finding; a reproduced one moves to **Defects**
or a fixture section with its failing test, a refuted one is ticked here with the evidence.

- [x] **28. Completion and parsing disagree, or klap differs from getopt, at these points.**
  - `optionsEndAtFirstOperand` is never checked while completing: `ssh web1 ls -‸` likely offers ssh's own names
    (`Completion.kt:175` checks only a literal `--`). Rows K30, L16, S34.
  - Reproduced as item 32. The two value branches do not check for `--`: `cp -- --backup=n‸` likely offers
    backup values (`Completion.kt:157`, `:165`). Rows L4b, L4c.
  - Reproduced as item 33. A lone `--` as an option's value: the parser refuses it and ends options, the
    completer takes it as the value, and getopt gives it to the option (real `cp -b -S -- -v x1 x3` ran verbose).
    Rows K41, S33, P40.
  - `-h` and negative shorts are offered as cluster continuations but end the cluster once typed, so `cp -P‸`
    likely offers no continuations (`Completion.kt:502`). Rows K7i, K7j, S47, G59. The `-h` half is reproduced as
    item 34; the negative shorts are not yet.
  - Subcommand names are offered where the parse never routes: after `--` (`git -- ‸`) and after an unrouted
    option (`git -s ‸`), the walk ignoring the sift error (`Completion.kt:96`, `:367`). Rows K12a, K14, K23.
  - A long abbreviation is matched against every long in the tree, so `git remote add --ma` (unique there:
    `--master`) is likely refused because `log` has `--max-count`; real git 2.56.0 accepts it. Rows K35, P46 to P51.
  - A parent's own option before its child's name stops the walk: `git remote -v ‸` never reaches `add`, while
    real `git remote -v show` works. Rows S40, P17.
  - `chmod -w‸` never runs the provider of chmod's dash-led mode operand: the flag-name branch answers first.
    Row V62.
  - **Verdicts 2026-10-17** (at `0def2e2`): the first bullet is 159, the subcommand bullet 160, the chmod bullet
    161. Refuted: negative shorts (`cp -P‸` offers `-PL`, `-Pr`, `-Pv`; `firstNonFlagShort` looks up
    `negativeShorts`) and the tree-wide abbreviation (`git remote add --ma main o u` binds `master=main`). Not a bug:
    a parent's option before the child's name, refused by design (`UnroutedSubcommand`), an owner call under 160.
- [x] **29. The test API and the providers see something other than what reaches the shell.**
  - `suggestionsFor` skips `toCompletionLine`, so a value with a tab or newline (dropped on the wire) and a
    multi-line or blank description look different in tests than in a shell. Rows G91 to G95, V44, V45.
  - `candidate("x", "")` on an attached value replaces the option's help with nothing. Row V46.
  - One occurrence of a `.multiple()` input that fails to convert unbinds the whole list for providers. Row V69.
  - An `Error` thrown by an unrelated converter escapes the bind and stops every provider on the line. Row V92.
  - A provider reading `words` never sees the words after the cursor, though the accessors do. Row V60.
  - **Verdicts 2026-10-17:** the first bullet is 162, the second 163, the fourth 164. Not a bug, by design: one bad
    occurrence unbinds a `.multiple()` for providers (`Bind.kt` "Lenient leaves it unbound", the `CompletionScope`
    KDoc), and `words` ends at the cursor (the `CompletionScope` KDoc).
- [x] **30. Converting and binding accept what the real tools refuse.**
  - Reproduced as item 35. A single-valued option given twice converts only its last value (`Bind.kt:131`), so
    `--width abc --width 80` binds 80; real `head`, `ls` and `dd` refuse the line. Rows P72, X55.
  - `.int()` accepts non-ASCII digits (`"١٢"`, `"１２"` give 12) and `.double()` accepts `" 1"`, `"1.5f"`,
    `"0x1p3"` and reads `1e400` as Infinity: `toIntOrNull` and `toDoubleOrNull` on the JVM, run on Kotlin 2.4.21,
    not yet through klap. Rows X14, X24, X25. The `.int()`/`.long()` half (X14) is closed 2026-10-11 by item 72:
    ASCII decimal digits only, leading blanks taken as strtol does; `.double()` stays open.
  - `TypedCli.parse` throws, with no `CliError`, when a projection reads another command's input or uses `!!` on
    an `.absentWhen()` slot. Rows P10 to P19.
  - A built-in klap adds that the tool lacks binds the same record as the line without it (`cp --json a b`);
    overlaps cp item 22. Rows P30, P2 (disputed).
  - **Verdicts 2026-10-17:** the `.double()` half is 165. Not a bug: `TypedCli.parse` throwing on a projection that
    reads another command's input or uses `!!` on an absent slot (a programmer error, as an exception from an action
    is). Refuted for cp (it turns `json` off since 103a); a CLI that keeps `--json` binds the same inputs on purpose.
- [x] **31. The ssh fixture ends options at the host; real ssh reads them until the remote command.**
  `example/ssh/.../Ssh.kt:31` sets `optionsEndAtFirstOperand`, so the destination ends options. Real OpenSSH 10.6p1
  keeps reading them after the host and stops at the command's first word: `ssh -G web1 -p 2222` gives port 2222, `ssh
  -G web1 ls -p 2222` gives port 22 (run 2026-10-09). The fixture also declares `--ipv4` and `--ipv6` (`Ssh.kt:109`),
  and OpenSSH has no long options. Row K30; an ssh fixture pass writes the targets.
  - **Verdict 2026-10-17:** the first half is 166. The invented longs are deliberate (`Ssh.kt:105`) and recorded by
    `accepts surface real ssh does not have`.

## Fixture parity targets

Each item is a failing test in an example's `*TargetsTest.kt`, written by the `fixture-parity` skill from
the real binary's behaviour; it carries `@Ignore("TDD target: ...")` until fixed. An agent taking an item removes
the annotation, runs the test to see it fail for the stated reason, and changes the fixture or klap until it
passes. If the test itself turns out wrong (it does not state what the real binary does), the agent does not bend
the code to it: it reports the test as needing a fix, with the evidence, and leaves the item open.

Audit of the pacman gaps, 2026-10-10, agreed with the owner. A gap is closed where a normal CLI behaves the same
way, and left where only pacman does (moved to **Will not do**: 6, 33, 36, 40, their tests ignored as will not do
rather than as targets). Order to take them in:

1. klap bugs, small and safe: 32, 1, 34, 15 and 35 are done 2026-10-10.
2. Choices and built-ins: 7, 37, 41, 8, 45 and 9 are done 2026-10-10 (7 and 37 in klap 0.6.0).
3. Mode-as-flag scoping: done 2026-10-10 by moving pacman's operations onto flag-spelled commands (item 47), which
   closed items 4, 5, 10, 11, 12 and 44; item 5 came free with it.
4. Fixture work, klap has what it needs: 39, 42, 43 and the decimal half of 38 are done 2026-10-10 on
   `fix/example-pacman`.

### pacman

Closed 2026-10-10: every pacman target from the two QA passes (2026-10-09 and the matrix slices of 2026-10-10)
passes, and their items are in **Done**. pacman 7.1.0 has 170 tests; the 2 still ignored state pacman quirks klap
does not follow (items 36 and 40, under **Will not do**).

### cp (matrix pass 2026-10-10, GNU coreutils 9.12)

Six agents, one per matrix file, rewrote cp's tests against real cp 9.12 (`cp --help`, and real runs in a throwaway
directory): `CpCompletionTest` (K), `CpValueCandidatesTest` (V), `CpLineContextTest` (L, S), `CpSuggestionListTest`
(G, E), `CpProjectionTest` (P), `CpConverterTest` (X), each with its `*TargetsTest` class. They replace
`CpParityTest`, `CpTargetsTest`, `CpProjectionMatrixTest`, `CpCompletionMatrixTest` and `CpCompletionTargetsTest`.
186 tests: 142 pass, 43 targets and 1 will-not-do record (40). After the TDD pass of 2026-10-11 and folding the
passing targets into the suites: 174 tests, 166 pass, 6 targets wait on klap API (items 21 and 57), 1 record (40).
After the API pass (21a, 21b, 57a, 63): 177 tests, 176 pass, 1 record (40); cp's matrix pass is closed. Every target was seen to fail on its assertion
with its `@Ignore` removed. A target named below as "Projection: x" is test `x` in `CpProjectionTargetsTest`.

### find (matrix pass 2026-10-11, GNU findutils 4.11.0)

Six agents, one per matrix file, wrote find's tests against `/usr/bin/find` (the shell's `find` here is a bfs
wrapper), reading real find through the fixture's respelling (decisions F1, F1a, F1b in the matrix
`decisions.md`): `FindCompletionTest` (K), `FindValueCandidatesTest` (V), `FindLineContextTest` (L, S),
`FindSuggestionListTest` (G, E), `FindProjectionTest` (P), `FindConverterTest` (X), each with its `*TargetsTest`
class. They replace `FindParityTest` and `FindExpressionSplitTest`; `FindVersionTest` stays. 212 tests: 129 pass,
76 targets and 7 will-not-do records (F1: the single-dash spelling, the expression grammar and order, `-exec ... ;`,
the raw tail after `--`; F1a: the `=` form; 40: `__complete`, twice). Every one of the 83 was seen to fail with its
`@Ignore` removed. After the TDD pass of 2026-10-11 (items 65 to 75 done, `ShortOptions` and GNU number rules in klap):
210 tests, 192 pass, 5 targets wait on item 76, 13 will-not-do records. Each of the 5 was seen to fail with its `@Ignore`
removed. After item 76 (2026-10-12): 211 tests, 198 pass, 13 records; the deslop pass of 2026-10-12 added `a lone dash is a
starting point the action walks`: 212 tests, 199 pass; find's matrix pass is closed. Several tables state the same gap from their own axis; an item names each test that states it,
as `Class: test`, the class without its `Find` prefix and `TargetsTest` suffix.

### chmod (matrix pass 2026-10-12, GNU coreutils 9.12)

The QA pass of 2026-10-12 replaced `ChmodParityTest` with one file per matrix table: `ChmodCompletionTest` (K, K7),
`ChmodValueCandidatesTest` (V), `ChmodLineContextTest` (L, S), `ChmodSuggestionListTest` (G, E), `ChmodProjectionTest`
(P), `ChmodConverterTest` (X), each with its `*TargetsTest` class; `ChmodVersionTest` stays. 103 tests: 77 pass, 26
targets; every target was seen to fail with its `@Ignore` removed, and nothing else failed. Evidence from
`/usr/bin/chmod` 9.12 in a throwaway directory, spot-checked by the main session. An item names each target as `Class:
test`, the class without its `Chmod` prefix and `TargetsTest` suffix. After the TDD pass of 2026-10-12 (items 95 to 102,
95a's help short, `.dashLed(gather)`, `completion = Builtin.Off` freeing `__complete`): 103 tests, every one passes, no
record; chmod's matrix pass is closed.

### mv (matrix pass 2026-10-12, GNU coreutils 9.12)

The QA pass of 2026-10-12 replaced `MvParityTest` with one file per matrix table: `MvCompletionTest` (K, K7),
`MvValueCandidatesTest` (V), `MvLineContextTest` (L, S), `MvSuggestionListTest` (G, E), `MvProjectionTest` (P),
`MvConverterTest` (X), each with its `*TargetsTest` class; `MvVersionTest` stays. 171 tests: 139 pass, 32 targets; every
target was seen to fail with its `@Ignore` removed, and nothing else failed. No klap core gap: every target is fixture
work with API klap has. Evidence from `/usr/bin/mv` 9.12 in a throwaway directory, spot-checked by the main session. An
item names each target as `Class: test`, the class without its `Mv` prefix and `TargetsTest` suffix. After the TDD pass of 2026-10-12 (items 103 to 110, `Update` and `BackupControl` enums, the environment seam): 174 tests, 173 pass, 1 kept as a record (todo 89); mv's matrix pass is closed.

### rm (matrix pass 2026-10-12, GNU coreutils 9.12)

The QA pass of 2026-10-12 replaced `RmParityTest` with one file per matrix table: `RmCompletionTest` (K, K7),
`RmValueCandidatesTest` (V), `RmLineContextTest` (L, S), `RmSuggestionListTest` (G, E), `RmProjectionTest` (P),
`RmConverterTest` (X), each with its `*TargetsTest` class; `RmVersionTest` stays. 145 tests: 106 pass, 39 targets; every
target was seen to fail with its `@Ignore` removed, and nothing else failed. No klap core bug; items 116 and 120 ask for
klap API. Evidence from `/usr/bin/rm` 9.12 in a throwaway directory (never `--no-preserve-root` in any spelling),
spot-checked by the main session. An item names each target as `Class: test`, the class without its `Rm` prefix and
`TargetsTest` suffix. After the TDD pass of 2026-10-12 to 13 (items 114 to 121 and 117a; klap's
`.enum<E>(spellings = )`, `abbreviates = false` and `.spelledInFull()`): 147 tests, 145 pass, 2 kept as records (todo
118, 121); rm's matrix pass is closed.

### ls (matrix pass 2026-10-13, GNU coreutils 9.12)

The QA pass of 2026-10-13 replaced `LsParityTest` with one file per matrix table: `LsCompletionTest` (K, K7),
`LsValueCandidatesTest` (V), `LsLineContextTest` (L, S), `LsSuggestionListTest` (G, E), `LsProjectionTest` (P),
`LsConverterTest` (X), each with its `*TargetsTest` class; `LsVersionTest` stays. 156 tests: 121 pass, 35 targets; every
target was seen to fail with its `@Ignore` removed, and nothing else failed. One klap core gap (123); the rest is
fixture work. Evidence from `/usr/bin/ls` 9.12 in a throwaway directory with LS_COLORS, TIME_STYLE, QUOTING_STYLE,
COLUMNS and POSIXLY_CORRECT unset, spot-checked by the main session. An item names each target as `Class: test`, the
class without its `Ls` prefix and `TargetsTest` suffix. After the TDD pass of 2026-10-13 (items 123 to 132): 152 tests,
every one passes, no record; ls's matrix pass is closed.

### head (matrix pass 2026-10-16, GNU coreutils 9.12)

The QA pass of 2026-10-16 replaced `HeadParityTest` with one file per matrix table: `HeadCompletionTest` (K, K7),
`HeadValueCandidatesTest` (V), `HeadLineContextTest` (L, S), `HeadSuggestionListTest` (G, E), `HeadProjectionTest`
(P), `HeadConverterTest` (X), with `*TargetsTest` classes for Completion, SuggestionList, Projection and Converter;
`HeadVersionTest` stays. 118 tests: 94 pass, 24 targets; every target was seen to fail with its `@Ignore` removed,
and nothing else failed. Three klap core gaps (143, 144, 145); the rest is fixture work. Evidence from
`/usr/bin/head` 9.12 in a throwaway directory, spot-checked by the main session. An item names each target as
`Class: test`, the class without its `Head` prefix and `TargetsTest` suffix. The matrix examples P60 (`head -n 3 -5`)
and P61 (`head -c 5 -5`) are refused by real head ("invalid trailing option -- 5"), and K90's `head -5n ‸` is
settled the same way (`-5n` is refused); the tests follow real head. After the TDD pass of 2026-10-16 (items
138 to 146): 119 tests, every target passes, 1 kept as a record (todo 145); head's matrix pass is closed.

### dd (matrix pass 2026-10-16, GNU coreutils 9.12)

The QA pass of 2026-10-16 replaced `DdParityTest` with one file per matrix table: `DdCompletionTest` (K),
`DdValueCandidatesTest` (V), `DdLineContextTest` (L, S), `DdSuggestionListTest` (G, E), `DdProjectionTest` (P),
`DdConverterTest` (X), each with its `*TargetsTest` class; `DdVersionTest` stays. 94 tests: 66 pass, 28 targets;
every target was seen to fail with its `@Ignore` removed, and nothing else failed. dd has no options but `--help`
and `--version`, so the option rows of the matrix are `n/a`. One klap question (149, how a `key=value` operand is
declared); the rest is fixture work. Evidence from `/usr/bin/dd` 9.12 in a throwaway directory, spot-checked by the
main session. An item names each target as `Class: test`, the class without its `Dd` prefix and `TargetsTest`
suffix. After the TDD pass of 2026-10-17 (items 149 to 158): 98 tests, every one passes, no record; dd's matrix
pass is closed.

### After dd (owner, 2026-10-17)

The full matrix loop stops after dd: the ls, head and dd passes found almost only fixture work (help words, built-ins
off, value grammars) and one klap bug by chance (147). Next, the klap items: 28 to 31 reproduced or refuted and the
confirmed ones fixed test-first, with 147; then one git pass scoped to its subcommand-tree rows (with 27). ssh, tar
and curl leave the loop (their modules stay as examples); ssh's defect 31 goes with 28 to 31.

### git (subcommand-tree pass 2026-10-17, git 2.56.0)

The QA pass of 2026-10-17 covered only the matrix rows about git's subcommand tree (K8e, K19, K21, K35, K38, K39 and
the cells disputed under 27; L17, L18, L25, S37 to S43; V1b, V3g, V8b, V16, V22, V28, V39, V40, V64, V77, V79, V84;
P1, P2, P4b, P11 to P29, P35, P37, P38, P45, P47, P51) and answered 27 and 28.7 on the real git. New files beside
`GitParityTest`: `GitCompletionTest`, `GitLineContextTest`, `GitValueCandidatesTest`, `GitSuggestionListTest`,
`GitProjectionTest`, each with its `*TargetsTest` class, and `ParitySuite.helpFor`. 64 tests: 32 pass, 32 targets,
each seen failing with its `@Ignore` removed. Evidence in a throwaway repository, spot-checked by the main session
(`git remote -v add o2 u2` exits 0; `git remote add -v o3 u` and `git status -C .` give "unknown switch"). After the
TDD pass of 2026-10-17 (169, 170, 173, 174, 176, 177): 65 tests, 63 pass, and the 2 skipped are the records of 171 and
172; git's subcommand-tree pass is closed.

- [x] **169. A command's own option binds on its own segment, before its child's name.** git parses each level with
  its own parser, which stops at the child's name: `git remote -v add o2 u2` runs with remote's `-v` kept (`git
  remote -h`: "must be placed before a subcommand"), `git remote add -v o u` is refused, and every root option is
  refused after the subcommand (`git status -C .`). klap refuses the first with `UnroutedSubcommand` and binds the
  last through `globalOption`. Settles 28.7 and the core of 27. Targets: `Projection: remote's own option before a
  child's name routes to the child`, `LineContext: the walk continues past remote's own option to its child`, and
  the root-option-after-the-leaf targets once git's root options are plain. klap core, then the fixture.
  - **Owner, 2026-10-17:** A. A plain option of a parent binds on the parent's segment, between its name and its
    child's name, and the walk routes on; after the child's name it is refused as today. No new API: `globalOption`
    keeps binding at any depth, and git's root options move to plain root options, so their letters are free below
    the root (`git -p log` and `git log -p`, commit's `-C`/`-c`). `UnroutedSubcommand`'s KDoc changes; the test `no
    subcommand name is offered after an option of the parent` (`CompletionTest.kt:1845`) flips to expect the
    children. This replaces 160's A for this case. Left for their own decisions: a glued value (`-Csub`), root shorts
    in one cluster, root longs that abbreviate.
  Done 2026-10-17: one walk (`internal/parse/Walk.kt`) serves the parse and Tab, and steps over a run of the command's
  own option words, an option with the value it takes from the next word, when the command has children; the
  built-in scan's walk routes on the same words. Every command passed binds its own options, so a child's action
  reads them, an absent one its default. `ParentSegmentTest` (10) pins it, seen failing before the change and again
  with the step disabled; `a local option of the parent stops the walk so its subcommand never routes` is gone, and
  two completion cells are restated (`remote -v ‸` offers `add`; `-po list ‸` offers `list`, since `list` is `-o`'s
  value). git's P14 target passes; S40 now waits on 174's words only. Open for the owner: a group without an action
  still may not declare options, though a child could now read them.

- [x] **170. git's root reads each word as a whole option, its subcommands by getopt.** `git -Csub status`, `git
  -pP status` and `git --git-d=.git status` are "unknown option", while `git status -sb`, `-uno` and `--bran` run
  (git 2.56.0). getopt_long takes all three (`getopt -o 'C:c:pP' -- -Csub status` gives `-C 'sub'`), so this is
  git's own root parser, not GNU. klap has the rules (`ShortOptions.Alone`, `Abbreviation.None`) only CLI-wide.
  Targets: `Projection: -C and -c take their value as the next word only`, `root shorts in one word are refused`, `a
  root long is matched whole`; `LineContext: a cluster continues with the command's own letters only`. Rest of 27.
  klap core, then the fixture.
  - **Owner, 2026-10-17:** A. A command may set `shortOptions` and `abbreviation` for its own segment; unset, its
    segment follows the CLI-wide setting. git sets its root's segment to `ShortOptions.Alone` and
    `Abbreviation.None`. The builder name is open (`ownSegment { }` was the sketch).
  - **Owner, 2026-10-17 (170a):** A. `ownOptions(shortOptions, abbreviation)`, a call on any command builder, both
    required, so no hidden unset state; a command without it reads its segment by the tree's settings.
  Done 2026-10-17: `CommandBuilder.ownOptions(shortOptions, abbreviation)` sets a command's `OwnReading`; the globals
  view `GlobalAccumulator.at(cmd)` carries it into the sift, the long pool (a segment without abbreviation matches every
  name whole, so the pre-walk scans read `--vers` as the sift does), the cluster reads and Tab, and the walk resolves a
  command's children by its own abbreviation. `OwnOptionsTest` (5) pins it; 4 fail again with the call ignored. Left as
  they are: the globals' pre-strip reads by the tree's `shortOptions`, and on a tree whose `abbreviation` is `None` the
  built-in scans do not abbreviate inside a segment that does. Under `Alone` Tab offers the typed short itself
  (`ShortOptionsTest`), where git's S42 target expects nothing after `git -P`: settled when the fixture moves.

- [x] **173. A version built-in recognised only on the root's segment.** `git -v` and `git -v status` print `git
  version 2.56.0`, `git status --version` and `git remote --version` are "unknown option `version'" (exit 129), and
  `git add -v f` and `git remote -v` are verbose. klap's version built-in, short included, is claimed at every depth,
  so the fixture cannot declare git's `-v` (`Git.kt:88`, whose "`git --vers` still abbreviates fine" is false, see
  170). Target: `Completion: status offers globals and --json, --color, --version` (its `--version` part). klap core,
  then the fixture.
  - **Owner, 2026-10-17:** A. An opt-in setting makes the version built-in, long and short, the root's segment only,
    its letters free below; the default stays every depth. git turns it on and declares
    `Version.Of("2.56.0", long = "version", short = 'v')`.
  - **Owner, 2026-10-17 (173a):** A. The setting is `rootOnly: Boolean = false` on `Version.Of`:
    `Version.Of("2.56.0", long = "version", short = 'v', rootOnly = true)`.
  Done 2026-10-17: below the root the parse, Tab, help and the built-in scan read the globals through a view without
  the version (`GlobalAccumulator.at`), the version short answers only on the root's segment, and the build reserves
  its names on the root only. `VersionRootOnlyTest` (7) pins it; 4 of them fail again with the runtime half off, the
  other 3 at the build check. The fixture's `-v` and its KLAP-GAP note at `Git.kt:88` go with 174.

- [x] **174. The fixture's surface against git's.** `git completion` is "not a git command", `git status --json` is
  "unknown option `json'", `git --directory . status` is "unknown option: --directory" (and `--config`), `git remote
  --no-verbose` runs, `git var` alone is usage (exit 129) while `git var GIT_EDITOR` runs; every description in git's
  own words (`git help -a`, man git, man git-remote, `git <cmd> -h`), an alias described as its command. Targets: the
  "in git's words" ones in Completion, LineContext, ValueCandidates and SuggestionList; `Projection: a built-in git
  does not have is refused`, `git var needs -l or a variable`, `remote's verbose has a negative form`; `SuggestionList:
  the subcommand slot offers no subcommand git does not have`. Fixture.
  - **Owner, 2026-10-17:** A. Fixed test-first in the same TDD pass as 169, 170 and 173: klap first, then the fixture
    moves its root options to plain root options and takes git's surface.
  Done 2026-10-17 by the fixture pass: root options are plain root options under `ownOptions(ShortOptions.Alone,
  Abbreviation.None)`, with `-p`/`--paginate`, commit's `-C`/`-c`, `-v` as the `rootOnly` version, no `--directory` or
  `--config`; klap's json, color, completion and docs built-ins off; remote's `--no-verbose`; `git var` takes `-l` or a
  variable (`exactlyOne`; `git var --list` and `git var -l GIT_EDITOR` exit 129); every description in git's words but
  log's (93). Every target passes; `GitParityTest` cells restated with git's runs (`git -v`, `__complete` refused).

- [x] **177. Tab on a complete short under `ShortOptions.Alone`.** The S42 target expected nothing after `git -P‸`;
  under `Alone` klap offers the typed short itself (`ShortOptionsTest`: `-L‸` offers `-L`), and `-P` is a word git
  takes, while every continuation (`-pP`, `-PC`) is refused. fish's own git completion offers those continuations, so it
  is no guide. **Owner, 2026-10-17:** A. klap's rule stays; the fixture pass restates the S42 cell to expect `-P` with
  its description.
  Done 2026-10-17: the S42 cell expects `-P`, "Do not pipe Git output into a pager", and passes.

- [x] **176. Options on a command group with no action.** The build refuses an option or flag on an action-less
  command with children ("has no action to read it", `BuilderValidation.kt` `validateActionlessLocalOptions`), so git's
  root options cannot move off `globalOption`: `git` bare is a group. Since 169 its children read them. **Owner,
  2026-10-17:** A. A group may declare options and flags, read by its children, an absent one its default; a group run
  bare still shows its help; a group's operands stay refused. klap core, ahead of the fixture pass.
  Done 2026-10-17: `validateActionlessOperands` (was `validateActionlessLocalOptions`) refuses only a positional; a group
  given only its own options binds them and shows its help. `ActionlessCommandInputTest` restates the three refusals as
  `a group's own option and flag are read by its child`, `a group given only its own options shows its help` and `a
  nested group's own option is read by its child`, each seen failing first; the help one fails again with its step off.

### git and curl (later)

- [x] **93. Git.kt's header claims every subcommand matches git's `-h`.** Found building item 92. `Git.kt:23` says the
  covered subcommands were "all verified against git 2.55.0's own `-h` output"; at 2.56.0 `git log -h` lists none of
  `-n`, `-p` or `--oneline`, so the claim is false for log. Open: drop the claim, or name what each subcommand was
  checked against. Fixture comment.
  - **Owner, 2026-10-17:** A. The header names git 2.56.0 and where each kind of description comes from (`git help
    -a`, git(1), git-remote(1), `git <cmd> -h`), and says log's keep the fixture's words. Done 2026-10-17.
- [x] **94. curl's `-d` help differs from curl 8.22.0.** Found building item 92. Every option and short matches
  `curl --help all` at 8.22.0, but the fixture's `-d` says "HTTP POST data" where 8.22.0 says "Post data", so
  `Curl.kt:16` still says 8.21.0. Open: change the help to 8.22.0's and renumber. Fixture.
  - **Owner, 2026-10-17:** A, at curl 8.23.0, installed since: the fixture pins 8.23.0 (header, `Version.Of`, its
    `--version` text and `CurlVersionTest`) and `-d` says "Post data"; the other 13 options match `curl --help all`.
    `CurlParityTest: data is described in curl's words` pins it. `example/README.md` names 8.23.0 and drops its
    sentence on older evidence comments, of which none is left. Done 2026-10-17.

## Candidate work

- [x] **111. A closed set of values is `.enum`, and `.choice` is the escape hatch.** Owner, 2026-10-12: "we always
  prefer .enum and not .choice(). that is an escape hatch." Fixture main sources still call `.choice(` (a grep, comments
  not told apart): ls 4, cp 3, git 3, curl 2, dd, find, pacman and rm 1 each; mv's two go with item 109. The guide's
  converter table (`docs/guide.md:327-328`) lists `.choice` beside `.enum` as a peer. Each fixture's next pass moves its
  closed sets to enums (cp's `--update` and resolved backup as mv's 109); the guide says when `.choice` is the right
  hatch (a set not known until runtime, a spelling an enum cannot carry). Owner, 2026-10-13: `.choice` stays in klap
  as the string-only escape hatch for a user who cannot use an enum; nothing removes it.  Done for ls 2026-10-13 by item 126 (no `.choice` left in `Ls.kt`). Fixtures and docs.
  Done 2026-10-17 by a sweep (two agents, merged): cp's `--update` (`Update`), `--sparse` and `--reflink` (`When`), git's
  `--chmod` (`ExecutableBit`, spelled `+x`/`-x`), `--cleanup` (`Cleanup`) and `--mirror` (`Mirror`), find's
  `-regextype` (`RegexType`, 13 entries in `find . -regextype help` order) and pacman's `--color` (`ColorWhen`) are
  enums, their records typed; cp's resolved backup is `BackupControl`, as mv's. Every test unchanged but for records
  moving from strings to constants. The guide's `.choice` row says a closed set is `.enum<E>()` and `.choice` the hatch
  for a set known at runtime or a spelling an enum cannot carry; its `NotOneOf` and `--color[=WHEN]` examples use
  enums. curl's `-X`: curl takes any word (curl(1): "curl passes on the verbatim string you give it"). **Owner,
  2026-10-18:** "-X should work like origin". It is a plain option with `.completeWith` offering the eight common
  methods; `-X post`, `-X PURGE` and `-X PROPFIND` bind verbatim (`CurlParityTest`), the KLAP-GAP note is gone. No
  `.choice` is left in a fixture; task-manager's is an example app's.


- [ ] **134. klap targets JDK 17 while building with 25.** From the notes, section 5: split the catalog's `jdk = "25"`
  into `jdk-build` (25, the toolchain) and `jdk-target` (17); set `jvmTarget`, `-Xjdk-release=17` and the published
  `TargetJvmVersion` from `jdk-target` (the toolchain alone sets `jvmTarget` 25, which `-Xjdk-release=17` refuses);
  run the JVM tests on 17 too in `check` (`jvmTestOnTarget`). kern did this in `b2e1328` and it found a real JDK 17
  difference there. klap's notes found no direct `java.*` use in `klap/src`. Fixtures and examples stay on the build
  JDK. `README.md`'s toolchain floor ("Java 25 bytecode") is restated. Build.
  **Blocked 2026-10-16 on kern 0.6.0:** built and reverted. With `jvmTarget` 17 the JVM compile classpath asks for
  Java 17, and every kern 0.5.x JVM variant declares 25 (`org.gradle.jvm.version: 25` in Maven Local's 0.5.1;
  `b2e1328` is in no kern tag), so `:klap:jvmTest` fails before compiling: "No matching variant of
  com.fromwau.kern:result:0.5.0 was found. The consumer was configured to find a library … compatible with Java 17".
  Lands with 135, once kern 0.6.0 is published. The edits are kern's `kern-module.gradle.kts` ones, applied in
  `klap/build.gradle.kts`, plus `jdk-build` in `klap-fixture.gradle.kts` and `example/parity/build.gradle.kts`.
- [ ] **135. klap's kern and Kotlin versions follow kern's.** From the notes, section 5: klap pins kern 0.5.0
  (`gradle/libs.versions.toml:10`) while kern is at 0.5.1, and kern's 0.6.0 (logger API, result's smart casts,
  `toResult` and combining, the two terminal fixes of `2d1a924`) is not yet in Maven Local. Kotlin is 2.4.21 in klap,
  2.4.20 in kern; the notes want kern raised to match. klap uses only `result`, `terminal`, `fuzzy` and `result-test`,
  so kern's `dirs`/`fs` split does not touch it; kern keeps `fuzzy` (item 175; the revert of kern's `61a6ae5` is staged
  in the worktree `/home/fromml/Projects/kern-fuzzy` on `feat/fuzzy-back`, for kern's review). Waits on kern 0.6.0 being
  published. Build.
- [x] **175. `didYouMean` and `editDistance` move into klap.** Owner, 2026-10-17 (kern notes, section 6, F2 A): kern's
  "two real projects" rule, and klap is fuzzy's only user. kern 0.6.0 drops the module; the source is in kern at
  `3c5b4bb` (`fuzzy/`). Both become public in `com.fromwau.klap` (the guide's "Did-you-mean, in your own messages"
  changes its import); `fuzzyRank` and natural order are not moved. Lands with 135. Then, test-first, from kern's
  fuzzy QA (2026-10-17, probes against kern `3c5b4bb`):
  - a one-character word suggests nothing: `r` over docker's commands gives `rm`, and `s` with an alias `st` gives
    `st` (one dropped character from a two-character name);
  - the unique-prefix rule follows `Abbreviation`: with `None`, `--o` suggests `--output` and `--n` `--namespace`
    today; decide whether a prefix is a typo there;
  - `warning` over `trace, debug, info, warn, error, off` suggests nothing (three edits from `warn`);
  - the KDoc and guide name the package, say a one-character word can match a two-character name, and that a word
    equal to a candidate is never suggested back.
  Corpus: 88 shared cases plus three held-out sets (224) where kmatch 0.3.1 with a one-swap scorer (`Scorers.Ratio`,
  `defaultProcess`, cutoff 70) made no mistakes and kern 9; kern notes, section 6, have the numbers.
  - **Owner, 2026-10-17 (175a):** A, replacing the move. The premise "klap is fuzzy's only user" is false: kotoml's
    `core` (`DecodeScalar.kt:474`, guessing true, false, inf or nan) and `serialization` (`NodeDecoder.kt:438`, with
    `preferred` for the keywords a field reads) depend on `kern.fuzzy` too, so two real projects use it and kern's
    rule keeps it. kern keeps `fuzzy` (`didYouMean` with its `preferred` tier, and `editDistance`); `61a6ae5`, which
    removed it, is reverted in kern by the kern session; klap stays on `kern.fuzzy`, and the QA points above become
    kern work. kmatch 0.3.1 was probed on kotoml's 45 keyword cases
    (`~/.claude/jobs/028c4674/tmp/kmatch-probe`): kern's rule gets all 45, kmatch's best setting (`Ratio`, one-swap
    scorer, cutoff 67 to 75) 42, missing `tr` and `fa` and guessing `inf` for `in_fo`. Nothing to do in klap.

- [x] **24. Opt-in converter modules for types klap core does not convert to.**
  Agreed with the owner 2026-10-09: klap core keeps converting only to plain types; a fixture or app that wants
  a richer type opts into a module by dependency. `klap-io` (kotlinx-io) adds `.path()` on `Opt<String?>` and
  `Arg<String>`, returning `kotlinx.io.files.Path` and implying `.file()`; `klap-datetime` (kotlinx-datetime)
  adds `.instant()`, `.localDate()`, `.localDateTime()`, `.timeZone()`. They are top-level extensions with a
  `context(scope: ConverterScope)` parameter (stable since Kotlin 2.4.0; klap is on 2.4.10), not an overload of
  `.file()`: core's `.file()` is a member extension of `ConverterScope` and would always win, and two functions
  cannot differ by return type alone. Each chains like a core converter (`.path().required()`,
  `.path().multiple()`), and a value that does not parse is refused through `.convert { }` with a
  `ConversionError`. Then the fixtures opt in (`pacman -U <file>` binds `Path("...")`), and their path fields,
  now `String`, are P4 targets until they do.
  **Owner, 2026-10-09:** one module per dependency. `Duration` and `Uuid` are standard library and go into core
  (`.duration()`, `.uuid()`). A duration takes ISO 8601 (`PT1H30M`) and the human style (`1s`, `5h 31m 2s 123ms`);
  check that `Duration.parse` accepts every human form wanted, the units in decreasing order, and pin each in a
  test. An `Instant` requires a zone or offset (`...Z`, `...+02:00`); one without is refused.
  Open before starting (matrix X74 to X86, observed on Kotlin 2.4.21 with older kotlinx-datetime 0.7.1 and
  kotlinx-io 0.8.0): `Path("a/")` equals `Path("a")` and `//` collapses, so `.path()` loses the trailing slash
  `rsync src/` and `cp -r a/` depend on (keep the raw text too, or say it is lost); `Duration.parse` also takes
  `Infinity`, `-(1h 30m)` and `1h30m`, so decide which of them klap accepts; `Instant.parse` takes an offset of
  just `+02` and refuses `12:00Z` without seconds; whether `.uuid()` takes a UUID without dashes depends on
  which `Uuid` parse it calls.
  - Probed 2026-10-17 on Kotlin 2.4.21, kotlinx-io 0.9.1, kotlinx-datetime 0.8.0 (`~/.claude/jobs/028c4674/tmp/
    conv-probe`): `Path("a/")` is `a` and equals `Path("a")`, `a//b` is `a/b`; `Duration.parse` takes `PT1H30M`, `1s`,
    `5h 31m 2s 123ms`, `1h30m`, `1.5h`, `1d`, `P1D`, `Infinity`, `-1h`, `-(1h 30m)`, refuses `30m 1h`, `1 h`, `1H`,
    `pt1h` and blank; `kotlin.time.Instant.parse` takes `...Z`, `+02:00`, `+02`, lowercase `t`/`z` and nanoseconds,
    refuses `12:00Z`, no offset, and a space for `T`; `Uuid.parse` takes dashed, plain 32-digit hex and upper case,
    `parseHexDash` dashed only, `parseHex` plain only, none takes braces.
  - **Owner, 2026-10-17 (24a):** A. kotlinx-datetime 0.8.0 uses the standard library's `kotlin.time.Instant` (no
    opt-in), so `.instant()` goes into core with `.duration()` and `.uuid()`; `klap-datetime` keeps `.localDate()`,
    `.localDateTime()` and `.timeZone()`.
  - **Owner, 2026-10-17 (24b, revised):** `.path()` carries the trailing slash: "a good UX and needed often in a CLI",
    as GNU cp tells `cp -r link/` (the directory) from `cp -r link` (the symlink). `kotlinx.io.files.Path` drops it
    (`Path("a/")` is `a`), so `.path()` returns a klap type around the `Path`; its shape is 24b2.
  - **Owner, 2026-10-18 (24b2):** A. `class CliPath(val text: String)` in `klap-io`, the text as typed, with `path`
    (`kotlinx.io.files.Path`) and `endsWithSlash` derived from it; equality by text, so a doubled slash survives too.
  - **Owner, 2026-10-18 (24c):** A. `.duration()` reads Kotlin's format (`Duration.parse`: ISO `PT1H30M`, `1s`,
    `1h30m`, `1.5h`, `5h 31m 2s 123ms`, `2d 3h`, units in decreasing order) plus a bare number as seconds, as GNU's
    `timeout` and `sleep` read one (`5`, `5.5`, `0`); a negative or infinite duration is refused (`-1h`, `-(1h 30m)`,
    `Infinity`), and so are `inf`, `1H`, `1 h`, `30m 1h`.
  - **Owner, 2026-10-18 (24d):** A. `.instant()` is exactly `kotlin.time.Instant.parse`: ISO 8601 with an offset
    (`Z`, `+02:00`, `+02`, lowercase `t`/`z`, fractions); no seconds, no offset or a space for the `T` is refused.
  - **Owner, 2026-10-18 (24e):** A. `.uuid()` is `Uuid.parse`: dashed or plain 32-digit hex, either case; braces and
    anything else refused; the value shows dashed.
  Done 2026-10-18 (one agent, merged): core's `.duration()`, `.instant()` and `.uuid()` on `Arg<String>` and
  `Opt<String?>`, refused as `ConversionError.NotADuration`, `NotAnInstant` and `NotAUuid` (new subtypes of a public
  sealed interface); a bare number reads as `.double()` reads one. `klap-io` (`com.fromwau:klap-io`, package
  `com.fromwau.klap.io`): `CliPath` and `.path()`, which implies `.file()`. `klap-datetime`: `.localDate()`,
  `.localDateTime()`, `.timeZone()`, refused as `ConversionError.Domain(DateTimeError.*)`. Both modules are top-level
  extensions with `context(scope: ConverterScope)` (no flag needed on 2.4.21) and expose klap and their kotlinx library
  as `api`. A `klap-library` convention in `build-logic` carries the targets for all three; `klap-publish` names each
  POM after its module. `StdlibConvertersTest` (15), `PathConverterTest` (12), `DateTimeConvertersTest` (7), each seen
  failing against stubs, on the JVM and linuxX64. linuxX64 keeps `a//b` where the JVM's `Path` collapses it, so the
  docs say "on the JVM". The guide has the rows and a "Converters from other modules" section. The fixtures opt in
  later (`pacman -U <file>` and the other path fields, still `String`).
  - Fixtures opted in 2026-10-18 (three agents, merged): every path input of chmod, cp, head, ls, mv, rm, curl, ssh,
    tar, find, git and pacman's options reads `.path()` and binds `CliPath`; cp's and mv's sources and destination and
    find's `-samefile`/`-newer` keep a trailing slash, tested (`cp -r link/ c` copies the directory, `find dir link
    -samefile link/` prints `dir`); cp's value rows read as `cp --help` writes them (`--sparse=WHEN`,
    `-t, --target-directory=DIRECTORY`). Not paths, left as `.file()`: git's pathspecs and remote URL.
  - **Owner, 2026-10-18 (P1):** A. find's projection splits its operand slot into `startingPoints: List<CliPath>` and
    `expression: List<String>`, by the split the action already makes (`Find.kt:520`); `find link/` prints `link/`
    and `link/a`, `find link` only `link`. Done 2026-10-18: `splitOperands` in `Find.kt` is the one rule, used by the
    action and the projection; `FindProjectionTest` pins `find link/` binding `CliPath("link/")`, and the starting-point
    cells fail when the split drops the slash. A test helper `walking(...)` builds a record of starting points only.
  - **Owner, 2026-10-18 (P2):** A. pacman's `-U` targets stay strings in the shared `targets` field: `man pacman` on
    `-U`: "Either a URL or file path can be specified", and a URL is no `CliPath`; item 24's pacman example does not
    apply. pacman's eight path options (`--dbpath`, `--root`, `--cachedir`, `--config`, `--gpgdir`, `--hookdir`,
    `--sysroot`, `--logfile`) read `.path()`.
  - Open: a module converter goes through the public `.convert { }`, so it cannot set core's internal type-changing
    marker; `.int()` through a leaked `Arg<String>` handle after `.path()` fails at parse with a ClassCastException
    instead of core's build-time refusal. Needs a public or `@PublishedApi` hook in core. Owner call.
    **Owner, 2026-10-18 (24-hook):** A. `.convert { }` and `.map { }` read their result type (`reified T`) and close
    the chain when it is not `String`, so users', modules' and core's converters are all covered; a `String` to
    `String` step (`.convert { Ok(it.trim()) }.choice(...)`) still chains. To build, test-first.
    Done 2026-10-18: `.convert` and `.map` on `Arg<String>` and `Opt<String?>` are `inline` with `reified T`; a result
    class other than `String` closes the chain (`String?` keeps it open, as `NeverThrowContractTest` pins), which
    covers `.path()` and the datetime converters unchanged. A lambda that only fails now names its type
    (`.convert<Int> { Err(...) }`), as `action { }` does. Tests in `ConvertersTest`, `PathConverterTest` and
    `DateTimeConvertersTest`, seen failing, and again with the type check off.
- [x] **27. An option of the root that stays before the subcommand, as git's are.**
  A `globalOption` is persistent: it binds at any depth, the way task-manager's `--file` is meant to. git's
  options before the subcommand are not: `git remote add -C . o u` ("unknown switch `C'"), `git status
  --no-pager` and `git status -c x=y` are refused (git 2.56.0, run 2026-10-09 in a throwaway repository). The git
  fixture declares them with `globalOption` (`example/git/.../Git.kt:44` to `76`), so it accepts and completes
  lines git refuses, and because a global's short is reserved tree-wide, two of its KLAP-GAP notes follow from the
  same cause: `git -p log` (`Git.kt:51`) and commit's `-C`/`-c` for `--reuse-message`/`--reedit-message`
  (`Git.kt:171`). Needs a root-scoped kind of option, or a flag on `globalOption`, that binds only before the first
  subcommand and leaves its letters free below it; whether a plain root `option` can serve is unchecked. A
  second gap from the same pass: git's `-C` and `-c` take their value only as the next word (`git -Csub status`
  and `git -csome.key=v status` are "unknown option"), and klap has no way to say so; git also has no
  `--directory` or `--config` long, which the fixture declares. Matrix rows: K21, K38, K39, and the cells disputed
  for this reason (K4b, K5, K10, K19, K43, K66, K7k, P17, P20, P22, P23, P51).
  - **Verdict 2026-10-17** (git pass): confirmed on git 2.56.0 and split into 169 (a command's option on its own
    segment), 170 (git's root reads whole words), 173 (version at the root only) and 174 (the fixture's surface);
    closes with them. Closed 2026-10-17 with the git fixture pass.

- [x] **178. Help measures text by display width.** `wrap` (`Help.kt:469`) and the signature column's `padEnd`
  (`Help.kt:487`) count UTF-16 units: a wide or full-width character (日) is 1 unit but 2 columns, so the line runs past
  the edge, and a combining mark (U+0301) is 1 unit but 0 columns, so it wraps early; ASCII help is right. Owner,
  2026-10-18 (with 136): a display-width function (East Asian wide and full-width 2, combining and zero-width 0), no
  dependency, used by both. Test-first, with wide, combining and emoji cells. klap core (or kern:terminal, if kern
  wants it beside its width).
  Done 2026-10-18: `internal/render/DisplayWidth.kt` (`displayWidth`, `padToWidth`; Unicode 16.0 tables, binary
  search), used by `wrap` and the signature column. `DisplayWidthTest` (11). Chosen: Hangul medial and final jamo 1, a
  soft hyphen 0, an emoji ZWJ sequence or skin tone measured per code point (👍🏽 is 4 where a terminal shows 2).

- [x] **179. The parse and completion entry points are too long to read.** Branch deslop, 2026-10-18: `completeCandidates`
  (`internal/render/Completion.kt`) is 301 lines, 189 on `master`; `parseTokens` (`Parser.kt`) 275, 237 on `master`;
  `sift` (`internal/parse/Parser.kt`) 271. Each is a run of early-returning stages with a comment per stage. Split
  each into named stage functions, no behaviour change, the suite as the guard. Design, owner to schedule.
  Done 2026-10-19 (one agent, merged): `parseTokens` is 13 lines over named stages (`readBuiltins`, `walkToCommand`,
  `readHelpRequest`, `bindCommand`, ...), `sift` a delegate to a `SegmentSift` with one reader per token kind,
  `completeCandidates` 9 lines over `TabLine` and its candidate stages; no test changed, counts identical. Left, found
  on the way: `unknownSubcommandBeforeHelp`'s KDoc sits above `routesTransparently`'s; `completionAnswer`, `docsAnswer`
  and `routeBuiltin` repeat one choice-resolution step; `siftGlobals` (about 200 lines) has the same shape.
- [x] **180. The subcommand walk exists twice.** Branch deslop, 2026-10-18: `walkSubcommands` (`internal/parse/
  Walk.kt`) routes the parse and Tab and steps over a command's own option words by a trial `sift`; `ArityWalk`
  (`internal/parse/ArgvScan.kt`) routes the built-in scans over raw argv by its own claim rules (`Claim.routesOn`).
  169 taught both the same rule separately, so they can drift; fold the scan onto the walk, or pin their agreement in a
  test over the fixtures' lines. klap core.
  2026-10-19: `WalkAgreementTest` grew 10 tests (globals, value slots, `--`, help, meta-options, prio, flag-spelled,
  `ownOptions`, a first-word number). The fold stopped on two lines where the walks disagree, each wrong once:
  `tool remote --verbose=x add o` (the scan routes past a flag given a value; the walk stops) and `tool remote -v -5
  add o` (the walk reads `-5` as `remote`'s first word and routes on; the scan stops). The parse refuses both, and Tab
  follows the walk, so `remote -v -5 add -` offers add's options. **Owner, 2026-10-19 (180a):** A. 160's rule
  decides both: a word the command's own options refuse stops routing, in the walk and in the scan; the walk's trial
  read knows a word past the command's first is not its first word. Then both route by one shared step, and the two
  lines go back into `WalkAgreementTest`. Done 2026-10-19 (one agent, merged): `SubcommandRouter.step` in `Walk.kt` is
  the one per-word routing step (`RouteStep`: descend, step over, the command's own run, stop); `walkSubcommands` loops
  over it and `ArityWalk` calls it on the raw argv, keeping only what the scan needs (value slots, pools, the first
  word, `optionsEndAt`); a trial read knows a word past the command's first (`sift(leadsSegment)`). The two lines and
  Tab on `remote -v -5 add -` are pinned, each seen failing first.
  **Owner, 2026-10-19 (180b):** A. A global before a first-word-only number leaves the number the command's first word,
  in the scan as in the parse, which strips the global first (`tool remote -g -5 add -x --help o` printed add's help,
  `-5 -g` ran). Done: a word the parse strips (`Claim.stripped`: a global, `--json`, `--color`) moves the scan's first
  word past it; `WalkAgreementTest` pins `-g -5`, `-t x -5` and `--global -5`, seen failing first.

- [x] **181. The branch review's defects.** Review of `feat/klap-0.6.0` against `master`, 2026-10-18, every finding
  reproduced; fixed test-first by two agents, merged. (1) A group with its own options blamed its own option for any
  mistake on its segment (`app -v bogus` gave `UnknownOption(-v)`): it is read by the same sift as a leaf, its first
  operand an unknown subcommand. (2) A parent's `.required()` option outranked a later options mistake (`app build
  --bogus`): every options refusal on the line is ordered first, requirements after. (3) A parent's own option could
  not precede a flag-spelled or prio child (`pm -v -Q`, `mytool -v license`): hoisting keeps it on the parent's
  segment. (4) The built-in scan stopped routing at a number run where the parse routes on (`app -3 sub -e --help`);
  `WalkAgreementTest` pins the two walks agreeing (180 stays open for folding them). (5) `run` read `--json` from the
  unhoisted line. (6) The version and help shorts ignored the root's `ownOptions` (`-pv`). (7) A flag-spelled
  command's long abbreviated under the root's `Abbreviation.None` (`pm --quer`). (8, 9) Two false guide examples
  (`mytool --completion bash license` on a root without an action; `-Qc` with no `-c` on `-Q`). Open choices the
  agents made, for the owner:
  - `pm -Q pkg -v`: a parent's own option after the child's first operand is refused, as 169 refuses it after the
    child's name.
    **Owner, 2026-10-19 (181a):** A. For a flag-spelled child the parent's own options bind anywhere on the line, as
    pacman reads one getopt loop (`pacman -Q pacman -v` and `pacman -Q pacman --dbpath /var/lib/pacman` exit 0); a
    word child keeps 169's refusal after its name. `FlagCommandTest`'s `the parent's own option after the command's
    operand is refused` is restated. To build.
  - A letter both the parent and a flag-spelled child declare goes to the parent in the leading run, so the child's
    is reachable only past an operand; nothing pins it. A tie-break by position, or a build refusal?
    **Owner, 2026-10-19 (181b):** A. Building refuses a parent's own option whose short or long a flag-spelled child
    also declares, naming both, as `validateGlobalCollisions` refuses a global's letter reused below it. To build.
  - A `prio` child skips its parent's required globals but not the parent's own `.required()` options.
    **Owner, 2026-10-19 (181c):** A. A `prio` child skips every requirement of the commands it passes, their own
    `.required()` options as well as required globals; its action must not read one it runs without. `CommandBuilder.prio`'s
    KDoc and the guide say "required inputs". To build.
  Done 2026-10-19 (181a to 181c), test-first: `frontOwnWords` reads a flag-spelled child's whole line up to `--`
  (`FlagCommandTest`: `the parent's own option binds anywhere on a flag-spelled command's line`, restated from the
  refusal); `validateFlagSpelledCommands` refuses a spelling a parent's own option and its flag-spelled child share,
  naming the command by its spellings (`a flag-spelled command may not declare a letter or long its parent's own
  option has`; a word child may still reuse one, `git -p log -p`); a passed command binds under
  `BindPolicy.DeferRequired` and skips its `.requiredWhen` checks for a `prio` child (`PrioCommandTest`). Each seen
  failing first. The guide's flag-command and prio paragraphs and `CommandBuilder.prio`'s KDoc are restated.

## Owner calls

- [x] **64. Open points from the cp API pass (2026-10-11).** Each is a small call on what was built for 21, 57 and 63:
  - `Flag.repeated(...)` exists beside the option form, so a `Refuse` app can let one flag repeat; 21a named options
    only. Keep or drop.
    **Owner, 2026-10-18 (64a):** A, keep it (first B, changed once the example showed that a `.count()` flag such as
    `-vvv` is untouched either way): a `Refuse` CLI may still exempt one on/off flag, as it may an option. Done.
  - `Occurrence.input` is typed `Any`, so `when (occurrence.input) { archive -> ... }` needs no `else`; typed
    `Input`, every `when` needs `else -> Unit`. `Occurrence.value` is `Any?`, so a caller casts.
    **Owner, 2026-10-18 (64b):** A. `input: Any` and `value: Any?` stay, so a `when` over the handles needs no `else`
    (`Cp.kt:284`, `Ls.kt`, `Rm.kt:106`) and a caller casts the value it knows. Done.
  - JSON help does not show `.multiple(max)` or `.atMostWhen` caps; adding them changes the help JSON schema.
    **Owner, 2026-10-18 (64c):** A. `ArgumentHelp` and `OptionHelp` gain optional `min`, `max` and `atMostWhen`
    (`[{"input": "--no-target-directory", "max": 1}]`), absent when no cap is set, so the JSON states what the text help
    does. To build, test-first.
    Done 2026-10-18: `ArgumentHelp` gains `min`, `max`, `atMostWhen` and `OptionHelp` `min` and `max` (an option has
    no `.atMostWhen` and `Opt.multiple()` no max, so those stay absent); `min` shows when above 0. `HelpJsonTest`.
  - klap writes an optional value's placeholder as `--backup[=<CONTROL>]`; cp's help writes `--backup[=CONTROL]`.
    **Owner, 2026-10-18 (64d):** A. An option's value placeholder follows GNU by default: `-S, --suffix=SUFFIX`,
    `--backup[=CONTROL]`, `--sparse=WHEN` (cp --help, coreutils 9.12), in help, docs and their pinned tests
    (`HelpTest.kt:878`, `BareOptionalValueTest.kt:144`, the fixtures' help cells); how an operand is written is not part
    of this. To build, test-first.
    Done 2026-10-18 as GNU writes them (checked on cp, head, xargs, od, date, sed, pr): a value after the long with
    `=` (`-S, --suffix=SUFFIX`), an optional one bracketed on both spellings (`-w[BYTES], --width[=BYTES]`), a
    short-only one after a space (`-E END`) or glued in brackets (`-l[MAX-LINES]`); usage lines and the built-ins
    likewise (`--color[=auto|always|never]`). The default placeholder word stays `value` and there is no opt-in for the
    old `<V>` form. `ValuePlaceholderHelpTest`; every pinned help cell restated. The cp fixture declares no placeholder
    for `-S`, `--sparse` or `-t`, so its rows read `--suffix=value`, not cp's `SUFFIX`: fixture work, open.
  - Done 2026-10-12 by item 105: mv's `mv -T a b c` is refused through `.atMostWhen(noTargetDirectory, 1)`.
  - Completion after an ambiguous long (item 60) does not cover one carrying `=x`, or one earlier than the word
    before the cursor.
    **Owner, 2026-10-18 (64e):** A, not a bug. `cp --re=x ‸`, `cp --re a ‸` and `cp --re=x a ‸` offer files, the word
    being an operand under every reading of `--re`, as K47 offers operands after a refused unknown option and as
    bash-completion's `_comp_complete_longopt` offers them on all four lines (`cp --re ‸` too, where klap offers
    nothing by item 60). Pin the three in `AmbiguousLongCompletionTest`. To build.
    Done 2026-10-18: four cells in `CompletionTest.kt`'s `AmbiguousLongCompletionTest` class, each seen failing
    under a mutation of the check.

## Release

- [ ] **3. Merge `feat/klap-0.6.0` into `master` and publish klap 0.6.0.** The branch is 18 commits on
  `origin/master` (2026-10-19): the completion rounds and the 0.5.3 bump that were on `master`, then the 0.6.0 work
  squashed into six. Publishing needs HEAD tagged `v0.6.0` and a clean checkout (`klap-publish`), and klap 0.6.0
  depends on kern 0.6.0 being published first (134, 135). kotoml's `dev` builds against klap 0.5.3 from Maven Local
  until then.

## Will not do

- **137. Markdown as a third output mode.** Owner, 2026-10-18: not now. It needs a Markdown-to-terminal renderer
  (Mordant was ruled out under 136) and a `--markdown` built-in no GNU tool has, and no app asks for it; `docs markdown`
  stays for reference docs. Reopen when an app needs report output.
- **136. Mordant for help layout.** Owner, 2026-10-18: no. It would give display-width wrapping and alignment, the
  real terminal width on the JVM and a Markdown renderer, but every klap CLI would carry it; `mordant-jvm-ffm` needs JDK
  22 and `--enable-native-access` (against 134's JDK 17), `mordant-jvm-jna` bundles a native library, `mordant-core`
  detects no size; kern already gets colour, `NO_COLOR`/`FORCE_COLOR`, the Windows console and write errors right,
  which Mordant does not; iOS support unconfirmed. The width gap is item 178; the JVM width stays `COLUMNS`, else 80.
  Mordant's Markdown renderer may come back as an opt-in module with 137.
- **122. cp's and mv's `--backup` through `.enum`.** GNU keeps only the last `--backup` word and reads it after the
  options, an empty word meaning the default (`cp --backup=bogus --backup=t a b` and `cp --backup= a b` exit 0), as
  `ls --time-style` does; most options are read per occurrence (`cp --sparse=bogus --sparse=auto` and `ls
  --color=bogus --color=auto` are refused), and klap's `.enum` checks every occurrence. Owner, 2026-10-18: A. No klap
  change: klap's default is GNU's usual rule, and cp and mv keep their private `argmatch` for the exception.
- **172. A bare `--exec-path` that prints and exits.** git pass 2026-10-17: `git --exec-path status` prints
  `/usr/lib/git-core` and exits 0 without running status; klap binds the optional value's default and routes on
  (`Git.kt:71`). getopt_long has no such form; a tool acts on it in its own code. Owner A: nothing in klap; an app
  acts on a bare value before it dispatches. `Projection: a bare exec-path answers before any command` becomes a
  `Will not do (todo 172)` record; `git --exec-path bogus` stays refused by klap where git prints and exits.
- **171. git's help page by position.** git pass 2026-10-17: `git --help remote add` shows git-remote(1) (the root's
  `--help` takes only the next word as a topic) and `git remote -h add` prints remote's usage, where klap shows the
  deepest command named, wherever the request sits (`docs/guide.md`, "Which command help documents"). Owner A: keep
  klap's rule, which lets a tree be explored by appending names; klap's defaults behave as GNU tools do, and a tool
  that departs gets an opt-in setting only when one is decided. The two cells of `Projection: a help request shows the
  page git shows for its position` become a `Will not do (todo 171)` record; `git remote -v add -h` (add's usage)
  stays a target under 169.
- **148. The mkdir and rsync fixtures.** Owner, 2026-10-16: removed rather than brought to parity, as their command
  lines are shapes the finished fixtures already cover: mkdir's `-m MODE` (chmod), `--context[=CTX]` (ls's
  `--color[=WHEN]`, cp's `--backup[=CONTROL]`) and directory operands; rsync's sources then a destination (cp),
  `Abbreviation.None` (find, curl) and `--no-` options (git), its own `host:path` being a network target with no safe
  oracle. `example/mkdir` and `example/rsync` are deleted, with their `settings.gradle.kts` entries, the README rows
  and every mention in the guide, the fixture-parity skill and its matrix files. The fixtures left to bring to parity
  are dd, ssh, tar, git and curl.
- **82. GNU's `-W foo` extension.** GNU audit 2026-10-12, owner A: glibc reads `-W alpha` as `--alpha` only for a tool
  whose short spec holds `W;` (`getopt -o 'W;' -l alpha,beta: -- -W alpha -Wbeta=1` gives ` --alpha --beta '1'`); no
  tool klap models uses it (`ls`, `cp`, `head`, `sort -W foo`: "invalid option -- 'W'", tar's `-W` is `--verify`),
  and klap reads an undeclared `-W` as getopt does without `W;`.
- **Reading an option from the environment (owner, 2026-10-11).** klap parses a command line and reads no
  environment variable. A fixture whose tool reads one fakes it: `cpCli(environment = ...)` takes the map a test
  hands it (item 62).

Decided against, with the reason, so they are not proposed again.

- **36. Help, version and a refusal rank as pacman ranks them.** pacman's help always wins, even in a cluster (`pacman
  -hV` and `pacman -Vh` both print the usage), and an unknown option or two operations beat both. GNU tools answer
  whichever of `--help` and `--version` comes first (`ls --help --version` prints the usage, `ls --version --help` the
  version), and since 2026-10-10 klap does the same (owner, 36a): `--help`, `--help-all` and `--version` form a first
  bucket that beats `--completion` and `--docs`, and inside a bucket the line's first one answers (`BuiltinsTest` `a
  line naming two built-ins answers by bucket then by order`, `ArgvOrderInvarianceTest` `--help and --version answer
  whichever the line gives first`). pacman's order stays a quirk klap does not follow; the tests stay as a record:
  `PacmanProjectionTargetsTest` `help outranks version in either order`, `an unknown option refuses the line beside
  version`; `two operations are refused beside help` passes since 2026-10-10, as two flag-spelled commands are a parse
  error (item 47), which outranks help.
  - 36b (owner, 2026-10-10, A; reversed 2026-10-11): a bad option refused the line wherever it stood, as grep and
    pacman do. Reversed for GNU coreutils' line order, item 61: `cp --help --bogus` prints the usage, `cp --bogus
    --help` is "unrecognized option". grep and pacman refusing both orders become quirks klap does not follow.
    `PacmanProjectionTest` `an unknown option refuses the line beside version` passes; `help outranks version in
    either order` stays ignored as pacman's quirk. GNU findutils and coreutils answer by line order instead
    (`find --version --zzz` and `ls --version --bogus` print the version), so `FindParityTest` `version answers ahead
    of a later unknown predicate` is ignored as will not do.
- **40. A first word `__complete` is an ordinary target.** Reversed for a CLI with `completion = Builtin.Off` by item 102. klap reserves the first word `__complete` for its completion
  protocol (`docs/guide.md:833`), as other CLI frameworks reserve theirs; a target with that name is not worth a second
  protocol. Owner's decision 2026-10-10. The test stays as a record, ignored as will not do:
  `PacmanProjectionTargetsTest` `__complete as the first word is a target`. Its completion half, `a first target
  named __complete is an ordinary target`, passes since 2026-10-10: Tab no longer treats a first `__complete` apart
  once the operations are commands.

- **klap sorts or ranks suggestions.** Tried on 2026-10-09 with kern's `fuzzyRank` (natural order, fuzzy ranking
  for providers that match on their own) and reverted the same day, owner's decision. The shells sort a
  completion list themselves (zsh, fish and bash's readline by default), so klap's order would show mostly in
  PowerShell; natural order threw away orders that mean something (`--level` with `debug, info, warn, error`,
  in Tab and in "choose from"); and ranking a provider's candidates overrides the order the provider chose. klap
  hands candidates over in the order declared or provided (`CompletionFlowTest` `suggestions keep the order they
  are declared or provided in`), and a provider that wants fuzzy matching ranks its own candidates. The same pass
  tried `fuzzyRank` for the "did you mean" hints, which made them wrong (`--he` suggested `--header` over
  `--help`, and hints appeared where nothing is close); they stay on `didYouMean`. `fuzzyRank` stays in kern.
  kern keeps `fuzzy` (item 175a).

## Done

- [x] **168. `optionsEndAtFirstOperand` and `endsOptions()` on one command.** Both are accepted and the switch wins,
  which neither KDoc says. Refuse the pair at build, or document it. Found in 166's TDD pass. klap core; owner call.

  - **Owner, 2026-10-17:** A. Refused at build.
  Done 2026-10-17: `validateEndsOptionsSlot` takes the switch and refuses the pair ("optionsEndAtFirstOperand and
  endsOptions() on 'command' both say where options end; keep one"), from `build()` and the re-validation pass; both
  KDocs say so. `EndsOptionsTest` `a command cannot end options at its first operand and at an endsOptions argument`
  failed first, and passes.
- [x] **167. A `validateInputs` block that throws an `Error` escapes `parse`.** `runValidations` (`Parser.kt:618`)
  catches only `Exception`, though its KDoc says it is "guarded like `.validate()`", which catches every `Throwable`
  but cancellation since 164. Found in 164's TDD pass, not yet pinned by a test. klap core.
  Done 2026-10-17: `runValidations` catches `Throwable` but `CancellationException`; `ValidateInputsTest` `a block that
  throws an error is reported rather than thrown` (a `TODO()` rule) failed with `NotImplementedError` out of `parse`
  first, and passes.
- [x] **147. A `.validate` written before `.map` checks the mapped value.** Found in head's TDD pass:
  `option(...).validate { s: String -> ... }.map { Count.Lines(it) }` compiles, but the predicate runs on the
  `Count.Lines`, so every value is refused (`BadValue(reason = "class ...Count$Lines cannot be cast to class
  java.lang.String")`). `applyValidate` (`Converters.kt:181`) keeps one validator that runs on the final converted
  value, while `.map` adds a conversion step, so the call order is lost. head now writes `.map` first. Either a
  validator runs at the point of the chain it was declared (on the value converted so far), or `.map` after a
  `.validate` is refused at build. klap core; owner call.
  - **Owner, 2026-10-17:** A. A `.validate` runs where it is declared in the chain, on the value converted so far;
    both orders work. Test: `ConvertersTest` `a validate before a map never judges the mapped value`.
  - **Owner, 2026-10-17:** A, follow-up. The build-time rule "call .validate()/.range() after every type-changing
    converter" goes for every converter: a check and a range run in declared order everywhere. The two tests
    `validate before a type changing converter is rejected at construction` and `range before ...`
    (`ConvertersTest.kt:573`, `:585`) are restated as chains that build and check the value at their place, and the
    guide's ordering note with them.
  Done 2026-10-17: a check added before a later converter is folded into the chain there (`thenChecked`), refusing as
  `BadValue` with a null cause (internal `CheckRefused`), so checks on both sides of a converter each run in written
  order; the build-time ordering rule is gone and its two tests are restated (`a validate before a type changing
  converter judges the raw value`, `a range before ... bounds the raw value`: `.range("0".."5").int()` compares the
  text, so `10` passes it), with `GuideSnippetsTest` and the guide's ordering note. Its test passes.
- [x] **159. Past the first operand of an `optionsEndAtFirstOperand` command, Tab still offers its option names.**
  `ssh web1 ls -‸` offers `--login`, `-l`, `--verbose`, `-v`, while the parse binds `cmd=[ls, -v]`; `optionsEnded`
  in `completeCandidates` (`internal/render/Completion.kt`) checks only a literal `--`. From 28.1. Test:
  `OperandTerminatedOptionsTest` `no option name of the command is offered after the first operand`. klap core.
  - **Owner, 2026-10-17:** A. Past the first operand of such a command `optionsEnded` holds in `completeCandidates`,
    in the name, value-option and attached branches; klap's built-ins stay offered, as the parse still reads them.
  Done 2026-10-17: completion counts operands against the command's `optionsEndAt` (shared with 166); past it the name,
  value-option and attached branches offer only globals and klap's built-ins, merged with the operand slot's
  candidates. Its test passes.
- [x] **160. Tab offers subcommand names where the parse never routes to one.** `git -- ‸` offers `status`,
  `remote`, ... while the parse gives `SubcommandAfterSeparator`; `git remote -v ‸` offers `add` while the parse
  gives `UnroutedSubcommand`; `git -s ‸` offers every name while the parse gives `UnknownOption` (real git refuses
  `git -- status` and `git -s status`). The walk ignores the sift error. From 28.5; tied to 28.7 (a parent's own
  option before the child's name), which klap refuses by design (`UnroutedSubcommand` KDoc, `Git.kt:337`) while real
  git accepts `git remote -v show`. Tests: `UnroutedSubcommandCompletionTest` (3). klap core; owner call on 28.7.
  - **Owner, 2026-10-17:** A. Completion follows today's parse: no subcommand name after `--`, after an unknown option,
    or after the parent's own option. 28.7 (a parent's option before its child's name) waits for the git pass with
    27; if that pass lets `git remote -v add` route, `no subcommand name is offered after an option of the parent`
    flips to expect `add`.
  Done 2026-10-17: `walkTo` reports whether the walk still routes (`Walk.routing`); names are offered only while it
  does. Two `CompletionTest` cells restated: `-po list ‸` and `--col ‸` under `Abbreviation.None` now offer nothing, as
  the parse routes nowhere. Its 3 tests pass.
- [x] **161. A dash-led operand's provider never answers a word starting with a dash.** With a provider offering
  `-w`, `-w f` parses as the mode but `-w‸` offers nothing: the flag-name branch answers first. From 28.8. Test:
  `DashLedOperandTest` `a dash led word the operand takes completes from its provider`. klap core; owner call on a
  word both could take.
  - **Owner, 2026-10-17:** A. Completion asks what the parse asks: a word only the dash-led operand takes completes
    from its provider, a word only an option takes from the names, and an unfinished word both could take (a lone
    `-`) merges both lists; a `chmod -‸` cell is added with that expected value.
  Done 2026-10-17: `dashLedOperandFor` reads the word as `sift` does; a lone `-` merges the names, then the operand's
  values. Added: `a lone dash offers the names and the dash led operand's values`, `a word only an option takes
  completes from the names`. Its test passes.
- [x] **162. `suggestionsFor` returns what the shell never receives.** It skips `toCompletionLine`, so a value with
  a tab or newline (dropped on the wire) and a multi-line or padded description (`" two\nlines "`, sent as `two
  lines`) differ between tests and shells. From 29.1. Test: `CompletionFlowTest` `suggestions are what the shell
  receives`. klap core (`testing/Completion.kt`).
  - **Owner, 2026-10-17:** A. One normalisation, a `Candidate` for the shell or none, shared by the `__complete` wire
    encoder (`Runner.kt`) and `suggestionsFor`; a test leaning on a raw description is restated with the shell's form.
  Done 2026-10-17: internal `Candidate.forShell(): Candidate?` (null for a value with a tab, newline or carriage
  return; a description folded to one trimmed line, blank to none), used by `toCompletionLine()` and `suggestionsFor`.
  Its test passes.
- [x] **163. A blank description on an attached value drops the option's help.** `candidate("red", "")` after
  `--tag=` gives `Suggestion("--tag=red", "")`; `candidate("blue")` gives the option's help. From 29.2. Test:
  `CompletionFlowTest` `an attached value with a blank description shows the option's help`. klap core
  (`asWholeWord`).
  - **Owner, 2026-10-17:** A. A blank description counts as none in `asWholeWord`, so the option's help fills in.
  Done 2026-10-17: `asWholeWord` treats a blank description as none. Its test passes.
- [x] **164. An `Error` thrown by one converter stops every provider, and escapes `parse`.** A `.map { throw
  NotImplementedError() }` on `--later` makes a provider that reads only `--name` offer nothing, and
  `parse(listOf("--later", "z"))` throws out of `parse`; `convertOne` and `applyMap` catch only `Exception`. From 29.4.
  Test: `ProviderFailureTest` `an error thrown by another input's converter leaves a provider the inputs it reads`.
  klap core; owner call on which throwables are caught.
  - **Owner, 2026-10-17:** A. Converters and validators catch every `Throwable` but `CancellationException`, in parse
    and completion, as `convertOne`'s parse-never-throws comment promises; an `Error` becomes `ConversionError.Threw`
    and a `BadValue`. A parse cell (`--later z` refused, not thrown) is added.
  Done 2026-10-17: `applyMap`, `convertOne` and a shared `judge` for every check catch `Throwable` but
  `CancellationException`. Added: `an error thrown by a map refuses the value instead of escaping parse`, `an error
  thrown by a validate refuses the value instead of escaping parse`. Its test passes. `validateInputs` still catches
  only `Exception`: item 167.
- [x] **165. `.double()` accepts a type suffix and a trailing blank.** `1.5f`, `1.5F`, `1.5d`, `1.5D` and `"1 "` bind
  1.5 or 1.0 on the JVM and native, where strtod-reading tools refuse them (`timeout 1.5f true`, `timeout '1 ' true`:
  "invalid time interval"); a leading blank and `0x1p3` are strtod's too. From 30.2. Test: `ConvertersTest` `a double
  refuses a type suffix and a trailing blank`. klap core; owner call on `1e400` (Infinity today) and `inf`/`nan`.
  - **Owner, 2026-10-17:** A. `.double()` reads strtod's grammar with nothing left over: optional blanks and sign, a
    decimal or hex float, or `inf`/`infinity`/`nan` in any case; `f`/`d` suffixes and trailing text refused; an
    overflow stays Infinity (timeout accepts `1e400`, checked 2026-10-17). Cells for `inf`, `NAN`, `0x1p3` added.
  Done 2026-10-17: `strtodDouble` behind `.double()` on operands and options; `nan(chars)` stays refused. Added: `a
  double reads infinity nan and a hex float as strtod does`, `a double refuses a word strtod reads only in part`. Its
  test passes.
- [x] **166. ssh's options run until the remote command, past the destination.** `ssh -G web1 -p 2222` gives port
  2222, `ssh -G web1 -v ls -p 2222` gives loglevel DEBUG and port 22; the fixture's `optionsEndAtFirstOperand` binds
  `-p 2222` into the command. klap has no switch for options ending at the second operand. From 31. Test:
  `SshParityTest` `options after the destination bind until the remote command starts` (`@Ignore("TDD target (todo
  31): ...")`). klap core or fixture; owner call.
  - **Owner, 2026-10-17:** A. `Arg<T>.endsOptions()`, opt-in: the first word bound to that argument ends options, so
    options before it permute as getopt's. ssh declares `remoteCommand.endsOptions()` and drops
    `optionsEndAtFirstOperand` (find keeps it). Completion follows the parse, as 159. The ssh test `a -- after the
    destination is forwarded to the remote command` is restated: `ssh -G web1 -- -p 2222` gives port 22, so ssh
    consumes that `--` (checked 2026-10-17). Pin it beside a non-final argument and with 159's change.

  Done 2026-10-17: `public fun <T> Arg<T>.endsOptions(): Arg<T>`, one rule `Command.optionsEndAt` for `sift`,
  `ArgvScan.walk` and completion; building refuses a second such slot, a `.multiple()` before it, a variadic one with
  slots after it, and `.absentWhen()` or `dashLed(gather)` on it or before it. ssh declares
  `remoteCommand.multiple().endsOptions()`; its `--` test is restated (`a -- after the destination ends the options and
  is not forwarded`). `EndsOptionsTest` (11) added; the guide's switch section gains the call. Its test passes. Both
  settings on one command: the switch wins, undocumented (item 168).
- [x] **149. dd's operands bind as dd reads them.** The info page: "If this operand is given multiple times, the last
  one takes precedence"; `dd if=in if=in2` reads in2, `dd count=1 count=2` copies two blocks; `bs=1K` is 1024 bytes.
  The fixture declares one `argument("operand").multiple()` with a hand-written `key=value` grammar, and its record
  is that list of strings (`DdInputs(operands: List<DdOperand>)`), every occurrence kept. Its KLAP-GAP comment says
  klap has no named operand, so the 14 keys cannot be declared one by one. Targets: Projection: `a single value given
  twice binds the later`, `a number operand binds the number dd reads`. klap core (a named `key=value` operand) or
  fixture (typed fields projected from the list); owner call, and it decides how 151 to 158 are built.
  - **Owner, 2026-10-17:** A. Fixture only: the one positional and its grammar stay, and the projection folds the
    list into a typed `DdInputs`, the last occurrence of a key winning: named states rather than `null`
    (`Stream.Standard`/`Stream.File(name)`, `Amount.All`/`Blocks(n)`/`Bytes(n)`, `Status.Default`/...), enums for
    the conv and flag sets, numbers as dd reads them (`bs=1K` is 1024, `bs=` sets `ibs` and `obs`). The TDD agent
    drafts the field names and reports them; no klap API.
  Done 2026-10-17: `DdInputs(input: Stream, output: Stream, ibs: Long, obs: Long, cbs: Cbs, count: Amount, skip:
  Amount, seek: Amount, conv: Set<Conv>, iflag: Set<Flag>, oflag: Set<Flag>, status: Status)`,
  `Stream.Standard`/`File(name)`, `Amount.All`/`Blocks(n)`/`Bytes(n)`, `Cbs.None`/`Bytes(n)`, `enum Conv`, `enum Flag`,
  `enum Status { Default, None, Noxfer, Progress }`, `NOTHING_BOUND = DdInputs()`; `DdOperand` is gone. The fold: a
  key's last occurrence wins, `bs=` sets `ibs` and `obs` wherever it stands, the lists add up, a `B` anywhere in a
  count or offset or the matching byte flag (`iflag=count_bytes`, `iflag=skip_bytes`, `oflag=seek_bytes`) counts bytes,
  the byte flags on the other side change nothing (checked by the main session: `ibs=10 count=3 oflag=count_bytes` 30
  bytes, `iflag=count_bytes` 3; `count=3B count=2` 20, `count=2 count=3B` 3). `skip` and `seek` default to `Blocks(0)`,
  so `Amount.All` never reaches them. Its 2 targets pass.
- [x] **150. dd has none of klap's built-ins, and `__complete` is an operand it refuses.** `dd --json if=in`, `--j`,
  `--color=never`, `--col`, `--completion bash`, `--docs markdown`: "unrecognized option"; `dd __complete if=`:
  "unrecognized operand '__complete'". Targets: Completion: `a dash offers exactly dd's two options`, `--color= offers
  no values`, `the word after __complete is an operand`; Projection: `an option dd does not have is refused`,
  `__complete is refused as an operand`; SuggestionList: `a built-in dd does not have is not offered`. Fixture
  (`builtins { ... = Builtin.Off }`, as head's 138).
  - **Owner, 2026-10-17:** A. `builtins { help = Help.ByCommand(...); json = Builtin.Off; color = Builtin.Off;
    completion = Builtin.Off; docs = Builtin.Off }`: `__complete` is an operand dd refuses.
  Done 2026-10-17: the four built-ins off and `Help.ByCommand(description = ...)`; its 6 targets pass.
- [x] **151. Every key and symbol is described in `dd --help`'s words.** `dd --help`: `bs=BYTES` "read and write up
  to BYTES bytes at a time (default: 512); overrides ibs and obs", `seek=N` "(or oseek=N) skip N obs sized output
  blocks", `skip=N` "(or iseek=N) ...", `status=LEVEL` "The LEVEL of information to print to standard error; ...",
  the CONV and FLAG tables, `--help` "display this help and exit", `--version` "output version information and
  exit". The fixture cuts the rows short, gives symbols no description and uses klap's built-in words; its KDoc says
  9.11. Targets: Completion: `--help and --version are described in dd's words`, `every key is described by its
  whole row in dd's help`; SuggestionList: `a key's description is the text its help row shows`, `Tab offers dd's
  words in dd's words and nothing else` (with 150); ValueCandidates: `every symbol is described in dd's words`.
  Fixture.
  - **Owner, 2026-10-17:** A. Each key its whole `--help` row joined into one line, each CONV and FLAG symbol its
    table row, each status level the clause of the status row that names it, `Help.ByCommand(description = "display
    this help and exit")`, `Version.Of(..., description = "output version information and exit")`, the KDoc at 9.12
    and the help header with dd's closing period.
  Done 2026-10-17: dd's words on every key, symbol, level, `--help` and `--version`; the epilogue is generated from the
  same tables; its 5 targets pass.
- [x] **152. `iseek=` and `oseek=` are offered.** `dd --help` names them beside `skip=` and `seek=`; `dd iseek=0
  oseek=0 if=in of=o` runs. The fixture parses them but never offers them. Targets: Completion: `an empty operand
  word offers every key dd takes`; LineContext: `every key dd takes is offered again after another`. Fixture.
  - **Owner, 2026-10-17:** A. Tab offers `iseek=` and `oseek=`, each described by its twin's row (`skip=`'s and
    `seek=`'s).
  Done 2026-10-17: `oseek=` after `seek=` and `iseek=` after `skip=`, each with its twin's row; its 2 targets pass.
- [x] **153. `oflag=` does not take `fullblock`.** `dd oflag=fullblock if=in of=o`: "invalid output flag:
  'fullblock'"; `iflag=append`, `oflag=append` and `iflag=fullblock` run. The fixture shares one table. Targets:
  ValueCandidates: `oflag offers every flag but fullblock`; Converter: `fullblock is refused as an output flag`.
  Fixture.
  - **Owner, 2026-10-17:** A. Two tables: `oflag=` is the flag table without `fullblock`, `iflag=` keeps every flag
    (`append` included); the comment that dd rejects misplaced flags only at run time goes.
  Done 2026-10-17: two tables; its 2 targets pass.
- [x] **154. A comma continues a symbol list.** `dd conv=ucase,notrunc`, `iflag=direct,nocache`, `status=none,progress`
  run; Tab after `conv=ucase,` offers nothing. Target: ValueCandidates: `a comma continues the list with every symbol
  dd still takes`. Fixture (`.completeWith`).
  - **Owner, 2026-10-17:** A, one decision for 154, 155 and 156. One rule in the fixture for the converter and the
    provider: every `conv=` on the line counts toward one set; at most one of {ascii, ebcdic, ibm}, {lcase, ucase},
    {excl, nocreat}, and once `cbs=` is on the line one of {block, unblock}, `ascii` counting as `unblock` and `ebcdic`
    and `ibm` as `block` (run `conv=ebcdic,unblock cbs=4` first, an inference); after a comma Tab offers what the rule
    still allows, a repeat included (`ucase,ucase` runs); `status=` takes a list, the last level bound.
  Done 2026-10-17: with 155 and 156, `convConflict(convs, cbsGiven)` serves `validateInputs` and the provider; its
  target passes.
- [x] **155. conv symbols dd cannot combine are refused, and not offered.** "cannot combine any two of
  {ascii,ebcdic,ibm}", "cannot combine lcase and ucase", "cannot combine excl and nocreat", in one word and across
  words (`dd conv=ucase conv=lcase`). `block` and `unblock` conflict only once `cbs=` is given, and then `ascii`
  counts as `unblock`: `dd conv=block,unblock` and `conv=ascii,block` run, `dd conv=block,unblock cbs=4` and
  `conv=ascii,block cbs=4`: "cannot combine block and unblock" (checked by the main session; the tests' comment
  that the info page's exclusivity does not hold needs restating). The fixture accepts and offers every pair.
  Targets: Converter: `conv symbols dd cannot combine are refused`; LineContext: `conv offers no symbol another conv
  on the line rules out`. Fixture.
  - **Owner, 2026-10-17:** A, one decision for 154, 155 and 156. One rule in the fixture for the converter and the
    provider: every `conv=` on the line counts toward one set; at most one of {ascii, ebcdic, ibm}, {lcase, ucase},
    {excl, nocreat}, and once `cbs=` is on the line one of {block, unblock}, `ascii` counting as `unblock` and `ebcdic`
    and `ibm` as `block` (run `conv=ebcdic,unblock cbs=4` first, an inference); after a comma Tab offers what the rule
    still allows, a repeat included (`ucase,ucase` runs); `status=` takes a list, the last level bound.
  Done 2026-10-17: checked on real dd: `ebcdic,unblock` and `ibm,unblock` under `cbs=4` refused, across words too;
  `ebcdic,block`, `ibm,block`, `ascii,unblock` under `cbs=4` and `ebcdic,unblock` without it run. Two cbs tests added;
  its 2 targets pass.
- [x] **156. `status=` takes a list.** `dd status=none,noxfer` runs, the last level winning. The fixture refuses a
  comma. Target: Converter: `a status list is accepted`. Fixture.
  - **Owner, 2026-10-17:** A, one decision for 154, 155 and 156. One rule in the fixture for the converter and the
    provider: every `conv=` on the line counts toward one set; at most one of {ascii, ebcdic, ibm}, {lcase, ucase},
    {excl, nocreat}, and once `cbs=` is on the line one of {block, unblock}, `ascii` counting as `unblock` and `ebcdic`
    and `ibm` as `block` (run `conv=ebcdic,unblock cbs=4` first, an inference); after a comma Tab offers what the rule
    still allows, a repeat included (`ucase,ucase` runs); `status=` takes a list, the last level bound.
  Done 2026-10-17: a status list binds its last level; its target passes.
- [x] **157. The undocumented `count_bytes`, `skip_bytes` and `seek_bytes` flags.** They run in `iflag=` and `oflag=`
  (`dd iflag=count_bytes count=2`, `oflag=count_bytes`, `iflag=seek_bytes`) with no warning; `--help` and info do not
  list them. The fixture refuses them. Target: Converter: `the undocumented byte flags are accepted` (accepted, not
  offered, as G35). Fixture.
  - **Owner, 2026-10-17:** A. `count_bytes`, `skip_bytes` and `seek_bytes` are accepted in `iflag=` and `oflag=` and
    never offered; run `oflag=skip_bytes` and `oflag=seek_bytes` first, which the main session did not.
  Done 2026-10-17: accepted on both sides (`oflag=skip_bytes`, `oflag=seek_bytes` run), never offered; its target
  passes.
- [x] **158. N and BYTES are read as dd reads them.** dd takes every suffix spelling (`1k`, `1KB`, `1kiB`, `1KD`,
  `1cB`, `1bB`, `1wB`), a suffix alone (`bs=K`), leading blanks and `+5`; it refuses a block size of zero (`bs=0`,
  `bs=2x0`: "invalid number"), a value past 2^63-1 (`count=9223372036854775808`: "Value too large"), and other
  digits (`count=١٢`). `ddNumberIsValid` is narrower on the first and looser on the second (Kotlin's `isDigit`
  takes every Unicode digit). Targets: Converter: `a number takes every suffix spelling dd reads`, `a suffix alone
  counts one`, `a number may open with blanks and a plus sign`, `a block size of zero is refused`, `a number too
  large is refused`, `a number in other digits is refused`. Fixture.
  - **Owner, 2026-10-17:** A. One reader for every number key, returning the value 149's record binds: optional
    blanks and `+`, ASCII digits and/or a suffix (alone it counts one), suffixes `c w b`, `k`/`K` with optional `B`,
    `iB` or `D`, `M G T P E Z Y R Q` with optional `B` or `iB`, `cB bB wB` (`m`, `g`, `kb` refused), `x` products;
    block-size keys refuse zero, count keys take it; every key refuses past 2^63-1. Checked 2026-10-17: runs `1k 1KB
    1kiB 1KD 1kD 1cB 1bB 1wB K c Kx2 2xK ' 5' +5 1M`, `count=0`, `count=0x10`, `count=9223372036854775807`,
    `bs=2147483648`; refused `bs=0 0B 2x0 00x10 0x10`, `cbs=0`, `ibs=0`, `1x 1K2 2xx2 1e 1g 1m 1kb ١٢`,
    `count=9223372036854775808`, `1Q 1Z 15E`, `bs=9223372036854775807`. dd's own block-size bound lies between 2^31
    and 2^63-1 and is not probed (it allocates the buffer): a comment in the test says so.

  Done 2026-10-17: `ddNumber`, dd's wording on refusal. Real dd decided what the owner line left: `D` after any
  multiplier (`1MD`, `1GD` run), blanks and `+` per factor (`2x+3`, `2x 3` run), none before a bare suffix (`+K`, ` K`
  refused), `1cD`, `1KiD`, `Bx2` refused; cells added. Its 6 targets pass.
- [x] **138. head has none of klap's built-ins, and `__complete` is a file name.** `head --json f`, `head --j f`:
  "unrecognized option"; `head __complete f`: "cannot open '__complete' for reading". The fixture accepts and offers
  `--json`, `--color`, `--completion`, `--docs`, and runs klap's completion protocol on `__complete`. Targets:
  Completion: `a dash offers exactly head's names`, `klap's built-ins head lacks are not offered`, `__complete as the
  first word is a file`; SuggestionList: `a built-in head does not have is not offered`, `the built-ins come last and
  are only head's`, `a completion script's call reaches head as its own words`; Projection: `an option head does not
  have is refused`, `__complete is a file name`. Fixture (`builtins { json/color/completion/docs = Builtin.Off }`, as
  rm's 114 and ls's 124).
  - **Owner, 2026-10-16:** A. `builtins { json = Builtin.Off; color = Builtin.Off; completion = Builtin.Off; docs =
    Builtin.Off }`, as rm and ls: head's `__complete` is a file name again.
  Done 2026-10-16: `builtins { help = Help.ByCommand(...); json/color/completion/docs = Builtin.Off }`; its 8 targets
  pass.
- [x] **139. Every name is described in `head --help`'s words.** `head --help`: `-n` "print the first NUM lines
  instead of the first 10; with the leading '-', print all but the last NUM lines of each file", `-c` the same for
  bytes, `--help` "display this help and exit", `--version` "output version information and exit". The fixture cuts
  `-n` and `-c` short and gives klap's "Show this help" and "Show the version"; its KDoc and comments say 9.11.
  Targets: Completion: `every name and letter is described in head's words`; SuggestionList: `-n and -c are described
  in head's words`, `the built-ins are described in head's words`, `a dash offers head's names in head's words and
  nothing else` (with 138). Fixture.
  - **Owner, 2026-10-16:** A. head's `--help` lines for `-n` and `-c`, `Help.ByCommand(description = "display this
    help and exit")`, `Version.Of("9.12", description = "output version information and exit", ...)`, and the KDoc
    and comments say 9.12.
  Done 2026-10-16: head's `--help` lines on `-n` and `-c`, `Help.ByCommand(description = "display this help and
  exit")`, `Version.Of("9.12", description = "output version information and exit", ...)`, the KDoc and comments at
  9.12; its 4 targets pass.
- [x] **140. The last of `-q` and `-v` decides the headers.** `head -vq f g` prints no headers, `head -qv f g` prints
  them. The fixture binds both flags true. Target: Projection: `the last of -q and -v decides the header mode`.
  Fixture (`lastWins(quiet, verbose)`, the record's shape is an owner call).
  - **Owner, 2026-10-16:** A. `lastWins(quiet, verbose)`, and the record's `quiet` and `verbose` become one
    `headers: Headers`, `enum class Headers { WhenSeveral, Never, Always }`, projected from the winner.
  Done 2026-10-16: `lastWins(quiet, verbose)` and `HeadInputs.headers: Headers` (`WhenSeveral`, `Never`, `Always`); its
  target passes.
- [x] **141. NUM is read as head reads it.** `head -n +5 f`, `-n ' 5' f`, `-n 1m f`, `-n K f`, `-n 1kB f`, `-n 1KiB f`
  and `-n -+2 f` run; `-n 1B f`, `-n 0x10 f`, `-n 5x f`, `-n 1e f` are refused ("invalid number of lines"). The
  fixture's `HEAD_NUM` refuses a plus sign, leading blanks, a lower-case `m` and a multiplier alone. Targets:
  Converter: `a NUM with a plus sign or leading blanks is taken`, `a NUM takes a lower-case m multiplier`, `a
  multiplier alone is a NUM`. Fixture.
  - **Owner, 2026-10-16:** A. One `HEAD_NUM` for `-n` and `-c` following head's `xstrtoumax` reader:
    `-?[ \t\n\u000B\f\r]*\+?(?:[0-9]+UNIT?|UNIT)` with `UNIT = (?:b|[kKmMGTPEZYRQ](?:B|iB)?)`; the value stays the
    string typed. The converter cells pin both lists checked 2026-10-16: runs `+5`, ` +5`, ` 5`, `\n5`, `\v5`, `- 5`,
    `-+5`, `-0`, `00`, `1m`, `1mB`, `1kB`, `1kiB`, `1KB`, `1MB`, `1G`, `1E`, `1Q`, `1QiB`, `K`, `KiB`, `b`, a 23-digit
    run; refused `1g`, `1e`, `1B`, `1bB`, `bB`, `iB`, `1Ki`, `+ 5`, `-- 5`, `+-5`, `1 K`, empty, `-`, `+`, ` `, `٥`,
    `5x`, `0x10`, `5 `.
  Done 2026-10-16: `HEAD_NUM` as decided, on `-n` and `-c`; both lists are converter cells, checked again on real head
  with `-n` and `-c` (plus `\f5`, `\r5`); its 3 targets pass.
- [x] **142. A line without `-n` or `-c` reads ten lines.** `head f` prints 10 lines; the record has `lines = null`.
  Target: Projection: `a line without -n or -c binds ten lines`. Fixture; the record's shape is an owner call.
  - **Owner, 2026-10-16:** A. The record's `lines` and `bytes` become one `count: Count`, `sealed interface Count {
    Lines(num: String); Bytes(num: String) }`, projected as `bytes()?.let { Count.Bytes(it) } ?: Count.Lines(lines() ?:
    "10")`; the target is restated as `count = Count.Lines("10")`.
  Done 2026-10-16: `HeadInputs.count: Count` (`Lines(num)`, `Bytes(num)`, `Count.Lines("10")` when none is given).
  Built as `-n` mapped to `Count.Lines`, `-c` to `Count.Bytes`, the number input through `obsoleteCount`, folded by one
  `lastOf(lines, bytes, obsolete)`, which replaces `lastOf(named, direct)` plus `lastWins(lines, bytes)`: the owner's
  `bytes()?.let ... ?: Lines(...)` could not make `-3b` bytes. Its target passes.
- [x] **143. The obsolete count is read only as the first word.** `head -c 5 -3 f`, `head -v -3 f`, `head f -3`,
  `head -n 2 f -3`: "invalid trailing option -- 3"; `head -v12 f`: "invalid trailing option -- 1". klap's
  `numberOption` reads a digit run anywhere, in a cluster too (`-v12` binds 12). git's fixture also declares it
  (`git log -3`), so this is not klap's default for every `numberOption`. Targets: Projection: `a count anywhere but
  first is refused`; Completion: `a dash-led count anywhere but first offers nothing`. klap core.
  - **Owner, 2026-10-16:** A, with 144: `numberOption(firstWordOnly = true)`, opt-in (git keeps the run anywhere). A
    run in a later word or after a letter (`-v12`) stays an unknown option.
  Done 2026-10-16: `numberOption(help, firstWordOnly: Boolean = false, suffixes: String = "")` (`OptionSpec.number:
  NumberShape?` in place of `isNumber`); "first word" is the first word after the command's name, threaded through
  every cluster reader, the arity walk and completion. klap tests: `NumberOptionFirstWordOnlyTest` (6). head declares
  `firstWordOnly = true, suffixes = "bcklm"`; its 2 targets pass. git keeps the default.
- [x] **144. The obsolete count takes head's letters after its digits.** `info coreutils 'head invocation'`:
  `-[NUM][bkm][cqv]`; runs: `-3c f` 3 bytes, `-3b f` 1536 bytes, `-3l f` 3 lines, `-3bl f` 1536 lines, `-3k f` and
  `-3m f` run, `-3v f` a header, `-3q f g` none, `-3z f` NUL lines, `-3cv f` 3 bytes with a header, `-5c -n 2 f` 2
  lines; `-3n5 f`, `-5n 3 f`: "invalid trailing option -- n". klap reads the letters through getopt: `-3c f` makes
  `-c` take `f`, `-3b` is refused, `-3n5` binds `-n 5`, and `head -2‸` offers `-2n`. Targets: Projection: `the
  obsolete count takes head's unit letters after its digits`, `an n after the obsolete count is refused`; Completion:
  `the obsolete count continues with head's own letters`, `the word after a count ending in n is an operand`. klap
  core (no `numberOption` takes a letter grammar), or the fixture if klap gives it the first word.
  - **Owner, 2026-10-16:** A, with 143: `numberOption(suffixes = "bcklm")`, opt-in. The suffix letters stay in the
    value in the order typed (`-3bc` binds `"3bc"`); the run's other letters are the cluster's shorts (`-3vq` is 3,
    `-v`, `-q`); a value-taking short there (`n`) is refused; Tab after `head -2‸` offers the suffixes and `q v z`,
    never `n`. The fixture reads the value into `Count` in NUM's own spelling, as `-c 3b` binds (`-3b` is
    `Count.Bytes("3b")`, `-3bl` `Count.Lines("3b")`, `-3bc` `Count.Bytes("3")`), the last unit letter winning, as checked 2026-10-16 on `seq
    3000`: `b`, `k`, `m` set bytes with that multiplier (`-3b` 1536 bytes, `-3k` 3072), `c` bytes times one (`-3bc` 3
    bytes, `-3cb` 1536 bytes), `l` lines keeping the multiplier (`-3bl` 1536 lines, `-3lb` 1536 bytes). Check first
    that `numberRunAt` (`Parser.kt:101`, `Completion.kt:714`) takes the suffixes without breaking K16's other shapes;
    stop and report if not.
  Done 2026-10-16: with 143. A suffix letter may stand anywhere after the run (`-3vk` binds `"3k"` and `-v`, as head
  reads it) and wins over a short spelled the same (`c`); Tab leaves out a suffix already typed; under
  `ShortOptions.Alone` a run with its suffixes is one word. klap tests: `NumberOptionSuffixesTest` (9). The fixture's
  `obsoleteCount` reads the value, checked on real head (`-3bk` 3072 bytes, `-3kb` 1536, `-3bcl` 3 lines, `-3lc` 3
  bytes, `-3cl` 3 lines besides the owner's list), pinned by its own converter test; the 4 targets pass. The number
  input's help now reads "same as -n NUM, or -c NUM after a b, c, k or m; ...".
- [x] **145. head's undocumented `---presume-input-pipe`.** `head --=x f` lists it among the possibilities; `head
  ---presume-input-pipe f`, `---p f` and `--- f` print f. klap refuses a long starting with `-`. Target: Projection:
  `---presume-input-pipe is accepted`. Record, as rm's 121, or klap core.
  - **Owner, 2026-10-16:** A. A record: `@Ignore("Will not do (todo 145): klap refuses a long starting with '-', and
    head's ---presume-input-pipe is an undocumented test hook")`. No API change.
  - **Owner, 2026-10-16:** asked whether `.hidden()` or a help-only variant settles it. No: `.hidden()` already
    keeps an input out of help, Tab and the docs (`docs/guide.md:349`), and the blocker is the spelling, which
    `requireValidSpelling` refuses (`internal/spec/HolderSpec.kt:324`). The record stands; no `hidden(helpOnly = true)`
    is filed, as no tool needs one. Item 146 fixes the KDocs.
  Done 2026-10-16: the record `@Ignore("Will not do (todo 145): ...")` in `HeadProjectionTargetsTest`.
- [x] **146. The KDocs of `.hidden()` say less than it does.** `Arg<T>.hidden()`, `Opt<T>.hidden()` and
  `Flag.hidden()` (`Converters.kt:465`, `:839`, `:975`) say "Omit this ... from `--help`", while it also leaves the
  input out of completion and the generated docs (`docs/guide.md:349`, `render/Completion.kt:237`). Found in the head
  walkthrough. klap core docs.
  - **Owner, 2026-10-16:** A. Each says it is left out of `--help`, completion and the generated docs, and still parses
    and binds; built in head's TDD pass.

  Done 2026-10-16: the three KDocs say the input is left out of `--help`, completion and the generated docs, and still
  parses and binds.
- [x] **133. Drop the Android target from klap and the task-manager example.** From the kern/klap notes
  (`~/Projects/kern/kern-klap-changes.md`, section 1, "Dropping Android from klap", 2026-10-12): the Kotlin `android`
  target means Android apps, which have no terminal, so a CLI library has nothing to do there; kern keeps Android for
  the logger. Today `klap/build.gradle.kts:3,15-17` and `example/task-manager/build.gradle.kts:3,35-37` declare it,
  `klap/src/jvmAndroidMain` is the shared JVM and Android source set, the catalog carries `agp` and the
  `android-*` versions, and the README lists Android among the targets (`README.md:9`). Termux is out of scope (it
  runs the JVM artifact or needs `androidNativeArm64`). Build.
  Done 2026-10-16: klap and task-manager declare no `android` target; the catalog loses `agp`, the `android-*`
  versions and the `androidKotlinMultiplatformLibrary` plugin, the root build its `apply false`, `settings.gradle.kts`
  its `google()` repositories and `gradle.properties` its `android.*` flags. `jvmAndroidMain` is gone and its one
  file is `jvmMain/.../platform/Exit.jvm.kt`. task-manager is linuxX64 and mingwX64 only, so its `jvmToolchain`
  went too. `README.md` (targets, toolchain floor without the build-tools note), `docs/guide.md:118,1831,1836` and
  `example/README.md:247` no longer name Android. 4090 tests, 16 skipped, 0 failed, as before.
- [x] **113. The help JSON keys `absentWith` and `optionalWith` still say "with".** After 112d the help text reads
  "absent when" and "optional when", and the calls are `absentWhen` and `optionalWhen`, but `--help --json` emits
  `absentWith` and `optionalWith` (`internal/render/HelpJson.kt:43-44`, `docs/guide.md:1505`, pinned in
  `HelpJsonTest`). A key is a published format, so the 112 rename left them. Owner call: rename them to
  `absentWhen` and `optionalWhen` (pre-1.0, as 112e), or keep them. klap core.
  - **Owner, 2026-10-13:** A. The keys follow the calls and the help text: `absentWhen` and `optionalWhen`, pre-1.0
    and at once, as 112e (this reverses the key names chosen 2026-10-03, kotoml FRICTION_LOG entry 60).
  Done 2026-10-13: `ArgumentHelp`'s fields `absentWhen` and `optionalWhen` (`internal/render/HelpJson.kt:43-44`), so
  `--help --json` emits `"absentWhen":"--target-directory"` and `"optionalWhen":"--force"`; `HelpJsonTest` and
  `docs/guide.md:1530` follow. 3942 tests, 16 skipped, 0 failed.
- [x] **114. rm has none of klap's built-ins, and `__complete` is a file name.** `rm --json a`, `--color a`, `--j a`,
  `--c a`: "unrecognized option"; `rm --d ed` removes the directory (it is `--dir`); `rm __complete a`: "cannot remove
  '__complete'", and a is removed. Targets: Completion: `klap's built-ins rm lacks are not offered`, `__complete as the
  first word is a file`; ValueCandidates: `a built-in rm does not have takes no attached value`; SuggestionList: `a
  built-in rm does not have is not offered`, `the built-ins come last and are only rm's`, `a completion script's call
  reaches rm as its own words`; Projection: `an option rm does not have is refused`, `--d abbreviates --dir`,
  `__complete is a file name`. Fixture (`builtins { }`, as mv's 103; items 102, 103a).
  - **Owner, 2026-10-12:** A. `rmCli()` turns off json, color, completion and docs in `builtins { }` and gives
    `--help` rm's description "display this help and exit", as `mvCli()`; `__complete` is a file name (item 102).
  Done 2026-10-13: `rmCli()` turns json, color, completion and docs off and gives `--help` "display this help and exit";
  `--d` is `--dir` and `__complete` a file name. Its 9 targets pass.
- [x] **115. `-I` has no long name.** `rm --interactive-once a`: "unrecognized option"; `rm --i a` and `rm --inter a`
  prompt, as `--interactive`. The fixture invents `--interactive-once`, so `--i` is ambiguous. Targets: Completion: `the
  fixture's --interactive-once is not offered`, `an abbreviation of --interactive reaches its value`; ValueCandidates:
  `an abbreviated --interactive keeps the typed spelling as its head`; LineContext: `an abbreviated --interactive
  before the cursor claims no word`; Projection: `--interactive-once is no long of rm and --i abbreviates
  --interactive`; SuggestionList: `a name rm does not have is not offered for --interactive`; Converter: `an
  abbreviated --interactive reads its value`. Fixture.
  - **Owner, 2026-10-12:** A. `-I` is declared short only (`flag(short = 'I', help = ...)`); `--interactive-once`
    goes, so `--i` and `--inter` abbreviate `--interactive`.
  Done 2026-10-13: `-I` is `flag(short = 'I', ...)`; `--interactive-once` is gone and `--i`, `--inter` abbreviate
  `--interactive`. Its 7 targets pass.
- [x] **116. `--interactive` reads rm's argmatch words: `never`, `no`, `none` / `once` / `always`, `yes`.**
  `rm --interactive=bogus a` lists them in that order; `=no`, `=none`, `=non` and `=n` remove without asking (a prefix
  of several words that mean one thing is accepted), `=yes`, `=y`, `=a` prompt. The fixture has `.choice("never",
  "once", "always")`. The same shape recurs: `ls --color=n` and `=ye` are accepted, and mv and cp read `--backup`
  through their own private `argmatch` (`Mv.kt:315`). Targets: Completion: `every value rm accepts after --interactive=
  is offered`; ValueCandidates: `every word rm reads as a WHEN is offered after --interactive=`; SuggestionList: `the
  values of --interactive come in rm's order`; Projection: `--interactive binds every word rm reads as a mode`;
  Converter: `--interactive binds no none and yes and their prefixes`. klap core (an enum entry with several
  spellings) or fixture; `.enum` per item 111.
  - **Owner, 2026-10-12:** A. klap gains `.enum<E>(spellings = { it.words })`: each entry is typed as any of its
    words, completion offers every word in declared order, and a prefix is accepted when every word it starts names
    one entry (gnulib argmatch; `=n` is `Never`). rm declares `enum class Interactive(val words: List<String>) {
    Never(never, no, none), Once(once), Always(always, yes) }` with `.optionalValue()` (bare is `Always`). To check
    first in the build: the parameter name beside today's `spelling`, which word `--help` shows, and how an
    ambiguous prefix across entries is refused. Follow-up: item 122.
  Done 2026-10-13: klap: `.enum<E>(spellings = { it.words })` (`@JvmName("enumOfSpellings")`, the named argument
  required), argmatch prefixes under `Abbreviation.Options`/`All` (a prefix whose words name one entry resolves, across
  entries it is `AmbiguousValue`), `--help` shows each entry's first word, errors list every word; `EnumSpellingsTest`,
  guide rows and snippets. rm: `enum class Interactive(val words: List<String>)` read with it,
  `.placeholder("WHEN").optionalValue()`. Its 5 targets pass.
- [x] **117. `--preserve-root` takes an optional `all`, beside `--no-preserve-root`.** `rm --preserve-root=all a` and
  `rm --pre=all a` run; `=al`, `=ALL`: "unrecognized --preserve-root argument". The fixture has one negatable flag
  (its KLAP-GAP note: one holder cannot be both negatable and optional-valued). Targets: Completion: `--preserve-root=
  completes to all`; ValueCandidates: `--preserve-root offers all attached in every spelling`; SuggestionList:
  `--preserve-root= offers all`; Projection: `--preserve-root=all binds in every spelling`, `--interactive and
  --preserve-root show an optional value and no bare one`; Converter: `--preserve-root binds all`. Fixture.
  - **Owner, 2026-10-12:** A. `--preserve-root` is `option(long = "preserve-root").enum<RootScope>().optionalValue()`
    with `enum class RootScope { All }`, `--no-preserve-root` a flag of its own, and `lastWins(preserveRoot,
    noPreserveRoot)`; the record's field is `enum class PreserveRoot { Off, Root, All }` (Off from
    `--no-preserve-root`, All from `=all`, Root otherwise). Unverified: the order between the two, since
    `--no-preserve-root` is never run; `lastWins` is getopt_long's default reading. The KLAP-GAP note goes.
  - **117a, Owner, 2026-10-13:** A. Found in the TDD pass: `.enum<RootScope>()` under `Abbreviation.Options` takes
    `--preserve-root=al` and `=a`, which rm refuses ("unrecognized --preserve-root argument: 'al'"; `=all` and
    `--pre=all` run). klap gains an `abbreviates = false` parameter on both `.enum` overloads (name to confirm in the
    build): that option's values match only whole, whatever the CLI's `abbreviation`, while its long still
    abbreviates and other enums keep prefixes (`--interactive=nev`). rm's `--preserve-root` uses it. Targets:
    Projection and Converter: `--preserve-root refuses a prefix of all`. klap core.
  Done 2026-10-13: `--preserve-root` is `.enum<RootScope>(abbreviates = false).optionalValue()`, `--no-preserve-root`
  its own flag, `lastWins` between them, the record field `PreserveRoot { Off, Root, All }`; the KLAP-GAP note is gone.
  117a: klap gains `abbreviates: Boolean = true` on both `Opt` `.enum` overloads (values match only whole; the long
  still abbreviates; Tab still completes a prefix; `EnumWholeValueTest`, guide). Its 6 targets and 117a's 2 pass.
- [x] **118. `-f`, `-i`, `-I` and `--interactive` set one prompt mode, and the later one wins.** `rm -f -i a` and
  `-fi a` prompt, `-if a` does not; `rm -f -i`, `-f -I`, `-f --interactive`: "missing operand"; `rm -i -f` exits 0.
  But `rm -f --interactive=never` exits 0 and `rm -f --interactive=never nonexist` exits 0, while `rm -f -i nonexist`
  and `rm --interactive=never nonexist` exit 1: `-f`'s "ignore missing" is undone by `-i`, `-I`, `=once`, `=always`
  and kept by `=never`. The fixture binds the four independently and ORs them. Targets: Projection: `the later of -f
  -i -I and --interactive decides the prompt`, `a prompt option after -f needs the operands again`, `each spelling of
  a prompt mode binds one record`. Fixture, maybe klap API for the `=never` case.
  - **Owner, 2026-10-12:** A. `lastWins(force, interactiveShort, interactiveOnce, interactive)` with the operands'
    `.optionalWhen(force)`; the projection replays `inOrder(...)` as rm's option loop into two fields, `prompt:
    Prompt` (`enum class Prompt { WriteProtected, Never, Once, Always }`, WriteProtected when none is given) and
    `ignoreMissing: Boolean` (set by `-f`, cleared by `-i`, `-I`, `=once`, `=always`, kept by `=never`), replacing
    `force`, `interactiveShort`, `interactive` and `interactiveOnce`. The passing Projection test `-f with a later
    --interactive=never still lets the operand list go empty` becomes a record: `@Ignore("Will not do (todo 118): a
    later --interactive=never takes -f's place under lastWins, so the operands are needed")`.
  Done 2026-10-13: `lastWins(force, interactiveShort, interactiveOnce, interactive)`, the projection replays
  `inOrder(...)` through a private `PromptReplay` into `prompt: Prompt` (`WriteProtected, Never, Once, Always`) and
  `ignoreMissing`. Its 3 targets pass; `-f with a later --interactive=never still lets the operand list go empty` is the
  record.
- [x] **119. rm's help words, `-R` as a second short of `--recursive`, and rm's help page.** `rm --help`: "-r, -R,
  --recursive"; `rm -R dd` and `rm -rR dd` remove dd. The fixture's `-R` is its own flag "same as --recursive", and
  `-I`, `--one-file-system`, `--[no-]preserve-root`, `--help` and `--version` are worded otherwise. Targets: Projection:
  `-R binds what -r binds in every spelling`, `the help page lists rm's options and nothing rm refuses`; Completion:
  `a name is described in rm's words wherever the cursor sits`, `a dash offers exactly rm's names in rm's words`;
  SuggestionList: `a long name is described in rm's words`, `a cluster continues with rm's letters in rm's words`, `a
  dash offers rm's names in rm's words and nothing else`. Fixture.
  - **Owner, 2026-10-12:** A. `-r` and `-R` are one flag, `flag(short = listOf('r', 'R'), long = listOf("recursive"),
    ...)`, and `RmInputs` loses `recursiveUpper`; every description is `rm --help`'s text with its lines joined by
    single spaces, `--version` through `Version.Of(..., description = "output version information and exit")`. The
    help page target passes once 114, 115 and 117 are built too.
  Done 2026-10-13: `flag(short = listOf('r', 'R'), long = "recursive", ...)`, `RmInputs` without `recursiveUpper`, every
  description `rm --help`'s text, `Version.Of(..., description = "output version information and exit")`. Its 7 targets
  pass.
- [x] **120. `--no-preserve-root` refuses every abbreviation.** `strings /usr/bin/rm`: "you may not abbreviate the
  --no-preserve-root option"; getopt reads `--no-p` and `--n` as that long. Not run. klap's abbreviation is one
  setting for the whole CLI (`Abbreviation.kt`). Target: Projection: `--no-preserve-root refuses every abbreviation`.
  klap core (a long that must be spelled in full), or record.
  - **Owner, 2026-10-12:** A. klap gains `.spelledInFull()` on `Flag` and `Opt` (name to confirm in the build): that
    input's longs are matched only whole, whatever the CLI's `abbreviation`, so `--no-p` and `--n` are refused while
    the other longs still abbreviate; completion still offers the whole word. rm's `--no-preserve-root` uses it.
  Done 2026-10-13: klap: `Opt<T>.spelledInFull()` and `Flag.spelledInFull()`: the input's longs (a negation included)
  match only whole; a prefix shared with another long stays ambiguous, as getopt_long; Tab and did-you-mean still name
  the whole word; `SpelledInFullTest`, guide. rm's `--no-preserve-root` uses it. Its target passes.
- [x] **121. rm's undocumented `---presume-input-tty`.** `rm --=x a` lists it among the possibilities; `rm
  ---presume-input-tty a` removes a. Target: Projection: `---presume-input-tty is accepted`. Fixture, if klap takes a
  long starting with `-`, else klap core or record. Checked 2026-10-12: `flag(long = "-presume-input-tty")` throws at
  build, "invalid flag spelling '-presume-input-tty': write it without dashes".
  - **Owner, 2026-10-12:** A. A record: `@Ignore("Will not do (todo 121): klap refuses a long starting with '-', and
    rm's ---presume-input-tty is an undocumented test hook")`. No API change.
  Done 2026-10-13: the record `@Ignore("Will not do (todo 121): ...")` in `RmProjectionTargetsTest`.
- [x] **123. A line holding an ambiguous long anywhere before the cursor completes nothing.** Real ls refuses the
  whole line (`ls --re a`: "option '--re' is ambiguous"), so nothing Tab offers can run. klap checks only the word right
  before the cursor and a long without `=x`: `ls --re a ‸`, `ls --s=x ‸` and `ls --re -l ‸` offer files, `ls --re
  --format ‸` offers the format words. The open point under item 64. Target: LineContext: `nothing completes after an
  ambiguous long earlier on the line`. klap core.
  - **Owner, 2026-10-13:** A. klap stays as K34 and K47 have it: Tab answers the slot under the cursor, so only the
    word right after an ambiguous long offers nothing. The target is restated as a passing test pinning today's
    answers (`ls --re a ‸` and `ls --s=x ‸` offer files, `ls --re --format ‸` the format words). No klap change.
  Done 2026-10-13: no klap change; LineContext's target is restated as the passing `an ambiguous long earlier on the
  line leaves the slot under the cursor its own answer` (`ls --re a ‸` and `ls --s=x ‸` offer files, `ls --re --format
  ‸` the format words).
- [x] **124. The fixture lacks about 30 of ls's inputs.** `ls --=x` lists 44 longs; the fixture declares 26 names.
  Missing: `--author`, `-b/--escape`, `--block-size`, `-B/--ignore-backups`, `-c`, `-D/--dired`, `-f`, `--file-type`,
  `--full-time`, `-g`, `--group-directories-first`, `-G/--no-group`, `-H/--dereference-command-line`,
  `--dereference-command-line-symlink-to-dir`, `--hyperlink[=WHEN]`, `--indicator-style`, `-k/--kibibytes`,
  `-L/--dereference`, `-n/--numeric-uid-gid`, `-N/--literal`, `-o`, `-p`, `-q/--hide-control-chars`,
  `--show-control-chars`, `-Q/--quote-name`, `--quoting-style`, `--time`, `--time-style`, `-u`, `-v`, `--zero`. The
  missing longs also make prefixes ambiguous the fixture binds: `ls --di a` ("ambiguous; possibilities: '--directory'
  '--dired'"), `--in` (`--inode`, `--indicator-style`), `--f` (`--full-time`, `--file-type`, `--format`). Targets:
  Projection: `every input real ls has is accepted`, `a prefix one of the fixture's longs shares with a long it lacks is
  refused`, `the help page lists ls's options and nothing ls refuses`; Completion: `every name and word real ls has is
  offered`, `a cluster continues with every letter ls accepts after it`, `a dash offers exactly ls's names`, `nothing
  completes after a long ls calls ambiguous through one the fixture lacks`; SuggestionList: `every long ls has is
  offered after two dashes`, `a dash offers ls's names in ls's words and nothing else`; ValueCandidates: `every value
  option ls has offers its words`. Several also need 125 or 131. Fixture.
  - **Owner, 2026-10-13:** A. Declare every missing input with ls's help words. Each flag is one Boolean unless
    127 to 129 fold it into a shared setting; the closed sets (`--time`, `--quoting-style`, `--indicator-style`,
    `--hyperlink`) are enums as 126 decides; `--time-style` and `--block-size` stay strings with no validation, since
    ls checks `--time-style` only beside `-l` (`ls --time-style=bogus a` runs) and SIZE is its own grammar; Tab
    offers `--time-style`'s known words.
  Done 2026-10-13: `Ls.kt` declares every input of `ls --help` in its words; `--time-style` is a `String?` whose Tab
  offers `full-iso`, `long-iso`, `iso` and `locale`, `--block-size` a string inside `SizeUnits.Block`; `-F` short only
  and `--classify` long only, as ls's short string has no optional value. Every prefix ls calls ambiguous is refused
  (`--di`, `--in`, `--f`, `--t`, `--d`); `ls -d --dire‸` offers `--directory` and `--dired`. The ten targets pass;
  three were renamed for the contract (`a prefix --directory --inode --format or --ignore shares with another long is
  refused`, `nothing completes after a prefix --directory or --format shares with another long`, and the three
  ambiguity tests folded into `every prefix ls calls ambiguous is refused`). The `group { }` help sections went, as ls
  has none.
- [x] **125. klap's `--json`, `--completion`, `--docs` and `__complete` are not ls's.** `ls --json a`: "unrecognized
  option '--json'"; `ls __complete a`: "cannot access '__complete'". The fixture keeps them (only `color` is off).
  Targets: Completion: `klap's built-ins ls lacks are not offered`, `__complete as the first word is a file`;
  LineContext: `klap's --json is no name of ls`; Projection: `an option ls does not have is refused`, `__complete is a
  file name`; SuggestionList: `a built-in ls does not have is not offered`, `the built-ins come last and are only
  ls's`, `a completion script's call reaches ls as its own words`. Fixture.
  - **Owner, 2026-10-13:** A. `builtins { json, color, completion, docs = Builtin.Off }`, as rm's; `__complete` is a
    file name.
  Done 2026-10-13: `builtins { json, color, completion, docs = Builtin.Off }`; `__complete` binds as a file. The eight
  targets pass; E19 (`what a completion script reads back is the same list`) was deleted, as ls has no completion
  protocol once completion is off.
- [x] **126. ls's value options read every argmatch word.** `ls --color=bogus` lists `'always', 'yes', 'force'`,
  `'never', 'no', 'none'`, `'auto', 'tty', 'if-tty'`; `--color=ye`, `=non`, `=t` run. `ls --format=bogus` lists
  `'verbose', 'long'`, `'commas'`, `'horizontal', 'across'`, `'vertical'`, `'single-column'` in that order. The fixture
  reads `--color` and `--classify` as `.choice("always", "auto", "never")` and `--format` as seven separate strings in
  declared order. Items 111 and 122. Targets: Projection: `every word ls reads for one value binds that value`;
  ValueCandidates: `every word ls reads as a WHEN is offered after the equals sign`; SuggestionList: `--format's values
  come in ls's order`, `--color's values come in ls's order`. Fixture.
  - **Owner, 2026-10-13:** A. Six enums read with `.enum<E>(spellings = { it.words })`, entries in ls's argmatch order:
    `When` (Always: always yes force; Never: never no none; Auto: auto tty if-tty), shared by `--color`, `--classify`
    and `--hyperlink`; `Format` (Long: verbose long; Commas; Across: horizontal across; Vertical; SingleColumn);
    `Sort` (None, Size, Time, Version, Extension, Name, Width); `TimeWord` (Atime: atime access use; Ctime: ctime
    status; Mtime: mtime modification; Birth: birth creation); `QuotingStyle` (literal, shell, shell-always,
    shell-escape, shell-escape-always, c, c-maybe, escape, locale, clocale); `IndicatorStyle` (none, slash,
    file-type, classify). A bare `--color` binds `When.Always`. Closes items 111 and 122 for ls.
  Done 2026-10-13: `When`, `Format(words, layout)`, `Sort`, `TimeWord`, `QuotingStyle` and `IndicatorStyle`, each
  `.enum<E>(spellings = { it.words })` in argmatch order; a bare `--color`, `--classify` or `--hyperlink` binds
  `When.Always` through `.optionalValue("always")`. The four targets pass. Items 111 and 122 are done for ls.
- [x] **127. A shorthand and its word are one value on the record.** `ls --help`: "long (-l)", "size (-S)",
  "-F, --classify[=WHEN]". `ls -l -C a b` lists in columns, `ls -C -l a` lists long, `ls -l --format=single-column a`
  lists one name, but `ls -l -1 a` and `ls -1 --format=long a` list long: `-1` never overrides a long format. The fixture
  binds `-l -C -x -m -1` as booleans beside `format: String?`, `-t -S -U -X` beside `sort`, `-F` beside `classify`.
  Targets: Projection: `each spelling of one value binds one record`, `the layout shorts and --format are one setting
  the last given wins`. Fixture.
  - **Owner, 2026-10-13:** A. `layout: Layout` (`ByTerminal, Long, Commas, Across, Vertical, SingleColumn`, a record
    enum apart from `Format`, whose entries all carry words), one setting the last given wins over `-l -g -o -n
    --full-time -C -x -m -1 --format`, replayed with `inOrder` as rm's prompt is, `-1` skipped while the layout so far
    is Long; `-g`, `-o`, `-n` and `--full-time` keep their own fields too. `sort: Sort` (default `Name`) over `-t -S -U
    -X -f --sort` under `lastWins`; `-f` also sets `all`. `-c` binds `time = Ctime` and `-u` `Atime`; their order
    against `--time` and `--sort` is unverified: the TDD agent checks it against real ls first.
  Done 2026-10-13: `layout: Layout` replayed with `inOrder` over `-l -g -o -n --full-time -C -x -m -1 --zero
  --format`, `-1` and `--zero` leaving a Long layout as it is (`ls -C --zero` one per line, `-l --zero` long); `sort:
  Sort` under `lastWins(-t, -S, -U, -X, -v, -f, --sort)` (`-v` added: `ls -m -S -v` sorts by version, `-v -S` by
  size); `time: TimeWord` under `lastWins(-c, -u, --time)`. Seen on real ls: an explicit sort beats a timestamp in
  either order, and a timestamp with no sort given sorts by it only when the final layout is not Long (`-l -c` by
  name, `-l -c -C` by ctime), so `sort` falls back to `Time` exactly then. Both targets pass, plus `-c -u and --time
  are one timestamp that sorts only where nothing else sorts and the listing is not long` (20 cells).
- [x] **128. `-a` against `-A`, and `-h` against `--si`, are each one setting the last wins.** `ls -m -a -A` hides `.`
  and `..`, `ls -m -A -a` shows them; `ls -s -h --si big` prints `8.2k`, `ls -s --si -h big` prints `8.0K`. The fixture
  binds each pair side by side. Targets: Projection: `-a and -A are one setting the last given wins`, `-h and --si are
  one setting the last given wins`. Fixture.
  - **Owner, 2026-10-13:** A. `hidden: Hidden` (`Skip, AlmostAll, All`) over `-a -A -f`, the last given wins (`ls -m
    -A -f` shows `.` and `..`, `-f -A` hides them); `units: SizeUnits`, a sealed interface (`Default`, `Human`, `Si`,
    `Block(size: String)`), over `-h --si --block-size`, the last given wins (`-h --block-size=1` prints 5001216,
    `--block-size=1 -h` 4.8M). `-k` stays its own `kibibytes` Boolean: it never overrides the three in either order.
    `-f`, in both `hidden` and `sort`, is replayed with `inOrder`.
  Done 2026-10-13: `hidden: Hidden` replayed over `-a -A -f`; `units: SizeUnits` under `lastWins(-h, --si,
  --block-size)`; `kibibytes` its own field. Both targets pass, widened with `-f`, `--block-size` and `-k` cells.
- [x] **129. A `--classify` WHEN that resolves to "no" leaves the indicator style as it was.** `ls -d --classify
  --classify=never sub` prints `sub/`, `ls -d -p --classify=never sub` prints `sub/`, `ls -d -F --indicator-style=none
  sub` prints `sub`. So `-p`, `--file-type`, `--indicator-style` and `-F[=WHEN]` share one setting, and a never (or
  auto off a terminal) takes no part. The fixture keeps `--classify`'s last WHEN. Target: Projection: `--classify given
  twice keeps the strongest value`. Fixture, with 124.
  - **Owner, 2026-10-13:** A. One `indicator: IndicatorStyle` field (default `None`), replayed with `inOrder`: `-p`
    Slash, `--file-type` FileType, `--indicator-style=W` W, `-F`, bare `--classify` and `=always` Classify,
    `--classify=never` no part, `--classify=auto` Classify only on a terminal. `lsCli(terminal: Boolean = false)`
    stands in for isatty, as mv's `environment` map does. `--color=auto` stays `When.Auto` on the record.
  Done 2026-10-13: `indicator: IndicatorStyle` replayed over `-p --file-type --indicator-style -F --classify`, a WHEN
  taking part only when `When.appliesOn(terminal)`; `lsCli(terminal: Boolean = false)`. The target passes as `a
  --classify WHEN that resolves to no leaves the indicator style as it was`, plus `--classify=auto appends indicators
  on a terminal and --color=auto stays auto`.
- [x] **130. `-w` and `-T` read COLS in base 0.** `ls -x -w 021` lays out differently from `-w 21`; `-w 080`: "invalid
  line width"; `-w 0x11` and `-w 99999999999999999999` run; `-w -1`: "invalid line width". The fixture reads decimal
  `.int()`. Targets: Converter: `-w and -T read octal and hex`, `a width past the largest number is accepted`, `-w and
  -T refuse a negative number`. Fixture.
  - **Owner, 2026-10-13:** A. A fixture-local strtoumax base 0 reader through `.convert { }`: octal after `0`, hex
    after `0x`, leading blanks and `+` taken, a minus refused on 0 too, trailing text refused; `-w` saturates past
    2^64-1, `-T` refuses it ("Value too large for defined data type"). `width` and `tabSize` become `ULong?`.
  Done 2026-10-13: a private `cols()` reads strtoumax base 0 through `.convert { }` (`ConversionError.Domain`);
  `width` and `tabSize` are `ULong?`. `-w` saturates past 2^64-1; `-T` refuses past 2^63-1, not 2^64-1 as the decision
  said: `ls -T 9223372036854775807` runs and `-T 9223372036854775808` is "Value too large for defined data type"
  (checked by the main session). The three targets pass.
- [x] **131. Help words differ from `ls --help`.** For `--hide`, `--format`, `-t`, `--sort`, `-h`, `-w`, `--color`,
  `-F`, `--help` and `--version`. The fixture's header also says coreutils 9.11. Targets: Completion: `a name is
  described in ls's words wherever the cursor sits`; ValueCandidates: `an attached value is described by its option's
  ls help`; SuggestionList: `the built-ins are described in ls's words`, `an attached value is described by its
  option's help`. Fixture.
  - **Owner, 2026-10-13:** A. Every input takes `ls --help`'s words verbatim, a wrapped entry joined into one line
    (`-w`'s two spaces after the full stop kept); `--help` and `--version` say "display this help and exit" and
    "output version information and exit" through `Help.ByCommand(description = )` and `Version.Of(description = )`,
    as rm's; the header says 9.12.
  Done 2026-10-13: every help text verbatim from `ls --help`, `--help` and `--version` in ls's words, the header at
  9.12. The four targets pass.
- [x] **132. Three testing-matrix notes are stale.** X14 (`X.md:115`) says `.int()` accepts non-ASCII digits and X15
  (`X.md:118`) that it refuses a leading space; klap refuses `١٢` and accepts `-w ' 17'`, as ls does, and both ls tests
  pass. P46 (`P.md:190`) and `K.md:233` say `ls --so` is ambiguous with `--show-control-chars`; `ls --so=size a` runs,
  as `--show` starts `--sh`. Skill reference.
  - **Owner, 2026-10-13:** A. The main session restated X14 and X15 (`X.md`) as klap reads numbers since item 72, and
    moved P46 (`P.md`) and K34 (`K.md`) to `ls --di` (`--directory`, `--dired`). Done with the walkthrough.
  Done 2026-10-13 by the main session, as the owner line says.
- [x] **112. The rule declarations follow one naming grammar.** Owner, 2026-10-12: A, file it and decide the names one
  at a time. Today (`CommandBuilder.kt:182-207`, `Converters.kt:379-411`, `Converters.kt:633`):
  - `lastWins(a, b)` declares an override rule and returns Unit; `lastOneWins(a, b)` folds spellings into a new
    `Opt<T>` handle. One word apart, different jobs; the KDoc needs both in one example to tell them apart.
  - The set rules mix grammars: `requireExactlyOne`, `requireAtMostOne`, then `lastWins` with no verb.
  - The per-input conditions mix suffixes: `absentWhen`, `atMostWhen`, `requiredUnless` (Arg only), `requiredIf`
    (Opt only, takes only a `Flag`, rendered in help as "(required when --remote)").
  Decided as sketched (112a to 112e below): `exactlyOne`, `atMostOne`, `lastWins`, `lastOf(named, direct)` for the
  fold; every condition ends in `When`: `absentWhen`, `atMostWhen`, `optionalWhen` (was `requiredUnless`),
  `requiredWhen` (was `requiredIf`). `validateInputs` keeps its name. Uses in fixture main sources: lastWins 14,
  requireAtMostOne 10, lastOneWins 3, requireExactlyOne 3, absentWhen 4, atMostWhen 2, requiredUnless 2, requiredIf 0;
  klap's own tests and `docs/` use them too. To decide per name: the new name, and whether the old one goes at once
  (pre-1.0) or stays deprecated for a release. klap core, then fixtures and docs.
  - **112a, Owner, 2026-10-12:** A. The fold `lastOneWins(named, direct)` becomes `lastOf(named, direct)`: the name
    says it returns a value, so it no longer reads as a twin of the rule `lastWins`, which keeps its name.
  - **112b, Owner, 2026-10-12:** A. `requireExactlyOne` becomes `exactlyOne` and `requireAtMostOne` becomes
    `atMostOne`, so the three set rules read alike (`exactlyOne`, `atMostOne`, `lastWins`) and match their help
    annotations. Accepted with the overlap noted: `atMostOne` counts inputs given, `Arg.atMostWhen` counts operand
    values.
  - **112c, Owner, 2026-10-12:** A. `Arg.requiredUnless(input)` becomes `Arg.optionalWhen(input)` and
    `Opt.requiredIf(flag)` becomes `Opt.requiredWhen(flag)`, so every condition ends in `When` (`absentWhen`,
    `atMostWhen`, `optionalWhen`, `requiredWhen`). Names only: `requiredWhen` still takes only a `Flag`.
  - **112d, Owner, 2026-10-12:** A. Every condition's help hint says "when", as the calls do: "(absent when
    --reference)", "(optional when --force; ...)", "(at most 1 when --no-target-directory; ...)", "(required when
    --remote)" unchanged. Strings at `Help.kt:182-185`; pinned in `ConditionalOperandTest.kt:73, 93` and
    `OperandCapTest.kt:102, 104`; `docs/guide.md:507, 512`.
  - **112e, Owner, 2026-10-12:** A. The old names go at once, no deprecated aliases (klap is pre-1.0, has no
    `@Deprecated` anywhere, and kotoml calls none of them): one klap change renames the API, the help strings, klap's
    tests, every fixture and task-manager, and `docs/guide.md`. Decided.
  Done 2026-10-12: `CommandBuilder.lastOf(vararg Opt<T>): Opt<T>`, `exactlyOne(vararg Input)`, `atMostOne(vararg
  Input)`, `Arg<T>.optionalWhen(input: Input)` and `Opt<T>.requiredWhen(flag: Flag)` replace the old names, which are
  gone. Help hints read "absent when", "optional when", "at most N when". Error messages name the new calls; the
  internal `ArgumentSpec.relaxedWhen` is `optionalWhen`, and the test classes follow (`LastOfTest`, `ExactlyOneTest`,
  `AtMostOneTest`, `RequiredWhenTest`). Every fixture, task-manager, `docs/guide.md`, `example/README.md` and the
  fixture-parity skill's references moved. 3743 tests, 14 skipped, 0 failed, the count before the rename. The help
  JSON keys `absentWith` and `optionalWith` kept "with" until item 113 renamed them.
- [x] **103a. Does klap turn on `--json`, `--color`, `--completion` and `--docs` by default?** Every fixture turns all
  four off (`Cp.kt:45`, `Chmod.kt:89`, `Find.kt:258`, `Pacman.kt:60`), and mv would be the fifth; no GNU tool has any of
  them (`mv --json a b`: "unrecognized option '--json'"). kotoml's app relies on json and completion and turns only
  color off (`Main.kt:182`). klap core default, decided before 103.
  - **Owner, 2026-10-12:** A. The four built-ins stay on by default: they are klap's features, not parsing rules,
    and a new app keeps completion with nothing to write. A GNU-shaped fixture turns them off in `builtins { }`.
  Done 2026-10-12: decision only; klap keeps the four built-ins on, and the walkthrough reference records it.
- [x] **103. mv has none of klap's built-ins, and `__complete` is a file name.** `mv --json a b`, `--color`,
  `--completion bash`, `--docs`: "unrecognized option"; `mv --d a b` renames (it is `--debug`), and util-linux getopt
  with mv's table reads `--c` and `--co` as `--context`; `mv __complete a`: "cannot stat '__complete'". The fixture keeps
  all four built-ins, so `--c` and `--d` are ambiguous. Targets: Completion: `klap's built-ins mv lacks are not offered`;
  ValueCandidates: `a built in mv does not have takes no attached value`; SuggestionList: `a dash offers mv's names in
  mv's words and nothing else`, `a built-in mv does not have is not offered`, `the built-ins come last and are only
  mv's`, `a completion script's call reaches mv as its own words`; Projection: `an option mv does not have is refused`,
  `--c and --d abbreviate --context and --debug`, `__complete is a source file name`, `the help page lists mv's options
  and nothing mv refuses`. Fixture (`builtins { }`, item 102).
  - **Owner, 2026-10-12:** A. `mvCli()` turns off json, color, completion and docs in `builtins { }`, as cp;
    `__complete` is a source file name (item 102).
  Done 2026-10-12: `mvCli()` turns json, color, completion and docs off and gives `--help` mv's description; `--c`
  and `--d` resolve to `--context` and `--debug`, and `__complete` is a source file name. Its 10 targets pass.
- [x] **104. Help and completion describe mv's inputs in mv's words.** `mv --help`, verbatim: `--debug` "explain how a
  file is copied.  Implies -v", `-n` "(deprecated) silently skip existing files.  See also --update", `-u` "equivalent
  to --update[=older].  See below", and `--update`, `-Z`, `--help`, `--version`. The fixture's header also says 9.11.
  Targets: Completion: `a name is described in mv's words wherever the cursor sits`; SuggestionList: `a long name is
  described in mv's words`, `a cluster continues with mv's letters in mv's words`; ValueCandidates: `an attached update
  mode is described by mv's help`, `an optional value completes attached in mv's words`. Fixture (as cp's item 25).
  - **Owner, 2026-10-12:** A. Every help text is `mv --help`'s verbatim, `--update` with its `UPDATE={all,none,none-
    fail,older(default)}` line as `Cp.kt:130`, `--help`/`--version` with mv's descriptions, and the KDoc header says
    9.12.
  Done 2026-10-12: every help text is `mv --help`'s, `--update` with its `UPDATE={...}` line,
  `Version.Of(description =)` and `Help.ByCommand(description =)` carry mv's words, and the header says 9.12. Its 5
  targets pass.
- [x] **105. `-t` with `-T`, and a third operand under `-T`, are refused by declared rules.** `mv -t d -T a`: "cannot
  combine --target-directory (-t) and --no-target-directory (-T)"; `mv -T a b c`: "extra operand 'c'". The fixture
  refuses both in its action only, so the line parses and Tab offers them. Targets: Completion: `-t and -T leave each
  other out`, `-T leaves no room for a third operand`; LineContext: `-t and -T leave each other out wherever the line
  names one`, `-T leaves no operand slot once a source and a destination are on the line`; Projection: `-t and -T are
  refused together`, `-T takes exactly a source and a destination`. Fixture (`requireAtMostOne`, `.atMostWhen`, the
  last point of item 64).
  - **Owner, 2026-10-12:** A. `requireAtMostOne(targetDirectory, noTargetDirectory)` and
    `sources.atMostWhen(noTargetDirectory, 1)`, as `Cp.kt:234` and `Cp.kt:250`; the two action checks and the KLAP-GAP
    comment go; closes item 64's last point.
  Done 2026-10-12: `requireAtMostOne(targetDirectory, noTargetDirectory)` and `sources.atMostWhen(noTargetDirectory,
  1)`; both action checks are gone, and item 64's last point is closed by it. Its 6 targets pass.
- [x] **106. mv's backup reads as cp's.** With `b.~1~` present: `mv --backup a b`, `-b` and `--backup=` make `b.~2~`;
  `--backup=simple --backup` makes `b~`; `--backup=t` makes `b.~1~`, `nil` `b~`, `off` none; `--backup=none -b` and
  `-b --backup=none` make none; `mv --backup=bogus --backup=never a b` exits 0; `mv -S .x a b` makes `b.x`. The fixture
  keeps `-b` and `--backup` apart, refuses an empty control, binds an alias as typed, checks each control as it is met,
  and `-S` asks for no backup. Targets: Projection: `-b and a bare --backup are one setting`, `a bare --backup after a
  value keeps the value`, `an empty backup control means the default one`, `a backup control alias binds what its
  control binds`, `a bad backup control ahead of help still shows help`, `a suffix asks for a backup`; Converter: `an
  invalid earlier --backup is never read`. Fixture (cp's item 18 built `resolveBackup` privately in `Cp.kt`).
  - **Owner, 2026-10-12:** A. `Mv.kt` gets its own copy of cp's backup code: `--backup` free text
    (`.optionalValue().multiple()`, `completeWith` the control names), read after the options through `resolveBackup`
    and `argmatch`; `-b`, a bare `--backup` and `-S` ask for a backup; `MvInputs` holds `backup: String?` (the
    control's first name, null for no backup) and `suffix: String?`, and `backupSimple` goes. Its record type is item 109's
    `BackupControl` enum.
  Done 2026-10-12: `--backup` is free text (`.placeholder("CONTROL").optionalValue().multiple()`, completed from the
  control names), read after the options by `Mv.kt`'s own `resolveBackup` and `argmatch`; `-b` (`backupDefault`), a
  bare `--backup` and `-S` ask for a backup; `MvInputs.backup: BackupControl` and `suffix`, `backupSimple` gone. Its
  7 targets pass. The record cannot tell `--backup=none` from no backup; both are `None`.
- [x] **107. `-u` is `--update` without a value, the later wins, and `--debug` implies `-v`.** With `b` older: `mv
  --update=none -u a b` replaced `b`, `mv -u --update=none a b` kept it; `mv --help`: `--debug` "Implies -v". Targets:
  Projection: `-u is --update without a value and the later of the two wins`, `--debug implies -v`. Fixture (cp's item
  17).
  - **Owner, 2026-10-12:** A. `lastWins(update, updateOlder)` as `Cp.kt:137`, `MvInputs` holds one `update: String?`
    (`-u` binds `older` when it is the later) and `updateOlder` goes; the projection sets `verbose = verbose() ||
    debug()` and keeps `debug`.
  Done 2026-10-12: `lastWins(update, updateOlder)`, `MvInputs.update: Update`, `updateOlder` gone; the projection
  sets `verbose = verbose() || debug()`. Both targets pass.
- [x] **108. A backup beside `-n`, `--update=none`, `none-fail` or `--exchange` is refused.** `mv -n -b a b`,
  `--update=none -b`, `-b --exchange`, `-S .x -n`: "cannot combine --backup with --exchange, -n, or
  --update=none-fail"; `mv -n -i -b a b` is accepted (the later `-i` holds). Target: Projection: `options mv cannot
  combine are refused`. Fixture (`validateInputs`, as cp's item 58; Tab does not see it, item 89).
  - **Owner, 2026-10-12:** A. A `validateInputs` refuses when the line asks for a backup (`-b`, any `--backup`
    including `=none`, `-S`) and the later of `-i`/`-f`/`-n` is `-n`, `update` resolves to `none` or `none-fail`, or
    `--exchange` is given (real mv: `--backup=none -n` is refused, `-n -i -b` and `--update=none -u -b` are accepted).
    One record, as cp's: `@Ignore("Will not do (todo 89): ...")` pinning that `mv -n --b<Tab>` offers `--backup`.
  Done 2026-10-12: a `validateInputs` refuses a requested backup (any control, `=none` too) beside a final `-n`, an
  update of `None` or `NoneFail`, or `--exchange`, with `CliError.Usage`. Its target passes; the record
  `LineContext: --backup is not offered beside -n` keeps `@Ignore("Will not do (todo 89): ...")`.
- [x] **109. The help page shows `--backup[=CONTROL]` and `--update[=UPDATE]`.** `mv --help`, verbatim. The fixture's
  page lists the choices as the placeholder and a "bare:" hint. Target: Projection: `--backup and --update show an
  optional value and no bare one` (it asserts klap's `[=<CONTROL>]`, item 64). Fixture.
  - **Owner, 2026-10-12:** A (revised: prefer an enum to `.choice`). `enum class Update(val cli: String) { All, None,
    NoneFail, Older }` read with `.enum<Update>(spelling = { it.cli }).placeholder("UPDATE").optionalValue()`, a bare
    `--update` mapped to `Older` in the projection, no `--update` `All`; `resolveBackup` returns `enum class
    BackupControl { None, Numbered, Existing, Simple }`, `None` when nothing asks for a backup. `MvInputs` holds
    `update: Update` and `backup: BackupControl`, no null for either; this changes 106's record. Unverified: that
    `.enum` then `.optionalValue()` builds; check it first and stop if not.
  Done 2026-10-12: `enum class Update(val cli: String)` read by `.enum<Update>(spelling = { it.cli
  }).placeholder("UPDATE").optionalValue()` (it builds; a bare `--update` is `Older`, none on the line `All`), and
  `enum class BackupControl { None, Numbered, Existing, Simple }`. Help shows `--backup[=<CONTROL>]` and
  `--update[=<UPDATE>]` with no bare hint. Its target passes; `a line without --update binds what --update=all
  binds` restates the old test, from `mv --help`.
- [x] **110. A backup takes its control and suffix from the environment.** `mv --help`: "The version control method may
  be selected via the --backup option or through the VERSION_CONTROL environment variable"; the suffix through
  `SIMPLE_BACKUP_SUFFIX`. No test: it needs an `mvCli(environment)` seam, as cp's item 62, so a test would not compile
  today. Not run against real mv. Fixture.
  - **Owner, 2026-10-12:** A. `mvCli(environment: Map<String, String> = emptyMap())`, a fake environment the test
    hands in (klap is CLI only and reads none), passed to 106's `resolveBackup`. New tests first, from the main
    session's runs: with `b` existing, `VERSION_CONTROL=numbered` makes `-b` and `-S .x` numbered, `--backup=simple`
    beats it, no backup reads nothing (`bogus` is accepted then), `bogus` with `-b` is refused;
    `SIMPLE_BACKUP_SUFFIX=.env` gives `b.env`, `-S` beats it, an empty one is ignored.
  Done 2026-10-12: `mvCli(environment: Map<String, String> = emptyMap())` feeds `resolveBackup` and `resolveSuffix`;
  tests written first from `env VAR=value mv` runs: Projection `a backup without its own control or suffix takes
  them from the environment` (10 cells) and `the backup environment changes nothing where mv makes no backup or
  names its control` (7 cells).
- [x] **102. `__complete` as chmod's first word.** `chmod __complete f`: "invalid mode: '__complete'"; klap reserves
  the word (item 40, kept). Targets: Completion: `the word after __complete is a file`; Projection: `__complete as the
  first word is the mode`. Follows item 40.
  - **Owner, 2026-10-12:** A, as one switch: `builtins { completion = Builtin.Off }` removes both the `--completion`
    built-in and the hidden `__complete` command, so a first `__complete` is the tool's own word. Default on, so a
    real app keeps Tab; with it off a generated script has nothing to call (said in the KDoc, including for a script
    rendered with `Cli.renderCompletion`). Fixture completion is tested through `suggestionsFor`, which calls
    `completeCandidates` directly (`testing/Completion.kt:36`), so nothing tested is lost. This reverses item 40 for
    a CLI that turns completion off: cp, find and pacman already do, chmod will (95), and their 6 records (cp 1,
    find 2, pacman 1, chmod 2) become passing tests. klap core.
  Done 2026-10-12: `completion = Builtin.Off` drops `__complete` (BuiltinsOptOutTest); cp, find (2), pacman and chmod
  (2) `__complete` tests pass; the wire tests of cp, find, pacman and chmod became `a completion script's call reaches
  <tool> as its own words`, refused as each real tool refuses `--cursor`.
- [x] **101. Tab does not offer `--reference` after a dash-led mode.** `chmod -w --reference=f f`: "cannot combine mode
  and --reference options"; parse refuses it, Tab offers it. Target: LineContext: `--reference is not offered after a
  dash-led mode`. klap core or fixture rule.
  - **Owner, 2026-10-12:** A. With 99's gathering a dash-led mode word counts as the mode even beside `--reference`,
    and the fixture declares `requireAtMostOne(mode, reference)`, so parse and Tab refuse the pair from one rule.
    `chmod 755 --reference=f g` stays accepted (755 is a file: "cannot access '755'"), as `absentWhen` gives today.
    If the builder refuses the rule on an `absentWhen` argument, the TDD agent reports it instead of another shape.
    Fixture, on 99.
  Done 2026-10-12: `requireAtMostOne(mode, reference)`; klap counts a slot its `absentWhen` trigger removed toward no
  rule and a gathered word toward one, and Tab still offers the trigger beside an operand it would make a file;
  LineContext `--reference is not offered after a dash-led mode` passes, `chmod 755 --reference=f g` stays accepted.
- [x] **100. A dash word with a letter getopt refuses is no mode.** `chmod -z f`: "invalid option -- 'z'"; klap's
  `.dashLed()` admits any word it cannot resolve as the operand, so Tab after `chmod -z ` offers files where chmod
  still wants MODE (parse refuses both, a `differs` note). Target: Completion: `the word after an unknown letter is
  the MODE operand`. klap core.
  - **Owner, 2026-10-12:** A, decided with 99: a word the predicate refuses (`-z`) is an unknown option again, so the
    slot after it is still MODE. 99 and 100 together make 4 targets pass.
  Done 2026-10-12: with the predicate, `-z` is an unknown option again; Completion `the word after an unknown letter is
  the MODE operand` passes.
- [x] **99. Every dash-led mode word, wherever it stands, adds to the one mode.** `chmod -w -x f` gives 444,
  `chmod -w -755 f` gives 000, `chmod u+x -w f` gives 444 and "cannot access 'u+x'": chmod collects each dash-led mode
  word before getopt and reads every other operand as a file. `.dashLed()` takes one word, in the mode's own slot.
  Targets: Projection: `every dash-led mode word on the line adds to the one mode`, `a dash-led mode after an operand
  is still the mode`; LineContext: `a dash-led mode after the cursor turns the slot into a file`. klap core.
  - **Owner, 2026-10-12:** A, with 100. klap: `.dashLed(gather = ",") { word -> ... }`, opt-in beside today's
    `.dashLed()` (head unchanged). A single-dash word the tree cannot resolve and the predicate accepts is a mode
    word wherever it stands before `--`; every one is joined with the separator into the one value; once one is
    given the slot takes no plain operand, so every operand is a file. Evidence (f at 644): `-w -x f` and `-w f -x`
    give 444; `-w u+x f` gives 444 and "cannot access 'u+x'"; `-Rw d` is "invalid mode: '-Rw'" (a whole word, not a
    cluster); `-w -- -x f` "cannot access '-x'"; `-- -w f` gives 444. `info coreutils 'chmod invocation'` calls it a
    convenience next to `chmod -- -w file`. chmod declares its predicate over `r w x X s t u g o a 0-7 + = ,`.
    klap core, public API; the fixture follows.
  Done 2026-10-12: `fun <T> Arg<T>.dashLed(gather: String, accepts: (word: String) -> Boolean): Arg<T>`
  (DashLedGatherTest, 13 tests); the builder refuses a second gathering slot in a command and a gathering `.multiple()`
  slot; chmod's mode gathers over `r w x X s t u g o a 0-7 + = ,`; all three targets pass.
- [x] **98. The mode converter reads chmod's octal forms.** `chmod 00755 f` and `000000755 f` give 755 (`17755` is
  invalid); `chmod u+x,=755 f` gives 755 (`u+x,755` is invalid). Targets: Converter: `an octal mode with leading
  zeros binds its bits`, `an operator with octal digits is a clause of a symbolic mode`. Fixture.
  - **Owner, 2026-10-12:** A. Any number of leading zeros, refused past 07777 (`17777`: "invalid mode"); an operator
    with octal digits (`=755`, `+0755`) is a clause in a comma list, bare digits there (`u+x,755`) stay invalid.
    Fixture converter.
  Done 2026-10-12: octal `[-+=]?[0-7]+` up to 07777, an operator with octal digits a clause in a list; both targets
  pass, plus cells for `=00755`, `-00755`, `u+x,+0755`, `+7,u+x`, `=755,=644`, `077777`, `u+x,=17777`.
- [x] **97. `--dereference` under `-R` follows chmod's rule.** `chmod -R --dereference -P u+x d`: "-R --dereference
  requires either -H or -L" (with a later `-L` accepted); and `-R` alone does not follow a link inside the tree while
  `-R --dereference` does, so an absent `--dereference` is not true. Targets: Projection: `-R --dereference refuses -P
  as the final traversal`, `-R alone and -R --dereference bind apart`. Fixture (a rule, and the record's field).
  - **Owner, 2026-10-12:** A (null as a third state is a sentinel, so named states instead). The record holds
    `enum class Dereference { Default, Follow, NoFollow }`: `Default` when neither spelling is given (chmod follows a
    link it is given and, under `-R`, none). The fixture declares `--dereference` and `-h`/`--no-dereference` as two
    flags with `lastWins`, as chmod's help lists them, and the projection maps them; a rule refuses `-R
    --dereference` when the final traversal flag is `-P`. This replaces 95's `.negatable(short = 'h')`. Fixture.
  Done 2026-10-12: `enum class Dereference { Default, Follow, NoFollow }` from `--dereference` and
  `-h`/`--no-dereference` under `lastWins`; the `-P` refusal is a `CliError.Failure` (chmod exits 1 with no "Try 'chmod
  --help'" line); both targets pass.
- [x] **96. `-c` and `-v` set one verbosity; the later wins.** `chmod -v -c 644 f` prints nothing, `chmod -c -v 644 f`
  prints "mode of 'f' retained as 0644". Target: Projection: `the later of -c and -v holds`. Fixture
  (`lastWins(changes, verbose)`).
  - **Owner, 2026-10-12:** A. `lastWins(changes, verbose)`. Fixture.
  Done 2026-10-12: `lastWins(changes, verbose)`; Projection `the later of -c and -v holds` passes.
- [x] **95. The fixture has chmod's names, in chmod's words.** `chmod --help`: "-h, --no-dereference", `-H`, `-L`, `-P`
  with no long, "-f, --silent, --quiet", and no `--json`, `--color`, `--completion` or `--docs` (each "unrecognized
  option"); so `chmod --c` is `--changes` and `--d` is `--dereference`. The fixture keeps klap's help short `-h`,
  invents `--traverse-*` longs, declares `--quiet` as its own flag, keeps klap's built-ins, and words several helps
  its own way. Targets: Completion: `a long name is described in chmod's help words`, `a cluster continues with the
  letters chmod accepts after it in chmod's words`, `-h is offered as --no-dereference`, `names chmod does not have
  are not offered`; SuggestionList: `a dash offers chmod's names in chmod's words and nothing else`, `a built-in is
  described in chmod's words`, `both longs of -f carry one description`, `the built-ins come last and are only
  chmod's`, `the help page describes every input in chmod's words`; Projection: `--quiet binds what --silent binds`,
  `-h is the short of --no-dereference`, `-H -L and -P have no long names`, `an option chmod does not have is
  refused`, `--c and --d abbreviate --changes and --dereference`. Fixture (`Help.ByCommand(short = null)`,
  `.negatable(short = 'h')`, `Builtin.Off`, one flag with two longs).
  - **Owner, 2026-10-12:** A. `-h` is `--no-dereference`'s short once 95a frees it (declared as item 97 says); `-H`, `-L`, `-P` shorts
    only; `--quiet` a second long of `-f`; `--json`, `--color`, `--completion`, `--docs` declined (`Builtin.Off`, as
    cp); every description in `chmod --help`'s words, `--help` and `--version` among them. Fixture.
  - **95a, Owner 2026-10-12:** A. klap's `--help` has no short by default: every `Help` variant defaults to
    `short = null`, as GNU's `--help` has none (`cp -h`, `mv -h`, `rm -h`, `mkdir -h`, `head -h`: "invalid option --
    'h'"; `chmod -h` is `--no-dereference`, `ls -h` `--human-readable`). A tool whose `-h` is help opts in with
    `short = 'h'`. This changes item 80's default. Every fixture without `short = null` is checked against its real
    tool (opts in where `-h` is help, loses it otherwise); klap tests that use `-h` as help declare it; guide
    restated. klap core.
  Done 2026-10-12: `-f` is `--silent`/`--quiet` on one flag, `-H`/`-L`/`-P` shorts only,
  `--json`/`--color`/`--completion`/`--docs` off, every description `chmod --help`'s; 14 targets pass.
- [x] **92. Four small fixture fixes.** Review 2026-10-12 (R9). **Owner, 2026-10-12:** A. (1) `GitParityTest.kt:207-209`
  rejects `-v` "because the --version built-in has no short", but `Version.Of` has a short now; restate it with
  `Git.kt:85`'s reason (a `Version.Of` short is reserved in every command, and `git add -v`/`git remote -v` are
  --verbose). (2) `Git.kt` says git 2.55.0 (header and nine "verified against" comments) while it pins 2.56.0, the
  installed one; `Curl.kt:16` says 8.21.0 while it pins 8.22.0: re-verify each comment against the installed tool,
  then write its version. (3) `example/README.md:95` cites `cp -dbuHpZ` setting backup and update, and no test binds
  that line: add a cp `binds` test against real cp 9.12. (4) `SshParityTest` `known divergence from real ssh` opens
  with two cells that match real ssh (options end at the destination): move them to a parity-named test and name
  the rest for the divergences they pin (`--json` stripped from the tail, `--` forwarded). Tests and comments.
  Done 2026-10-12: (1) restated; (2) every git claim re-run on 2.56.0 and renumbered, except Git.kt:23 (item 93);
  Curl.kt:16 left at 8.21.0 (item 94); (3) CpProjectionTest `a cluster of short-only flags binds the backup and update
  it implies`; (4) split into `options end at the destination as real ssh reads them`, `--json is stripped from the
  remote command`, `a -- after the destination is forwarded to the remote command`.
- [x] **91. The wrapper jar matches the Gradle it names.** Review 2026-10-12 (R8). **Owner, 2026-10-12:** A.
  `gradle-wrapper.properties` names 9.8.1 but `gradle-wrapper.jar` (sha256 238e777f...) is the jar of Gradle 9.8.0's
  dist (9.8.1's is 3b8a2577...); `./gradlew --offline wrapper --gradle-version 9.8.1` writes the matching one. Left
  as is, by the same decision: `klap-publish` keeps klap's POM inline (its one user is `klap/build.gradle.kts`, and a
  parameter would have one caller), and the repository URL stays written in each of the three settings blocks and
  the publish block (each settings script is evaluated alone, so sharing it needs more machinery than the four
  lines). Build.
  Done 2026-10-12: the jar's sha256 is 3b8a2577..., the one in Gradle 9.8.1's dist; gradlew, gradlew.bat and the
  properties unchanged.
- [x] **90. The matrix notes match the code again.** Review 2026-10-12 (R7). **Owner, 2026-10-12:** A. Every stale
  note and row in `.claude/skills/fixture-parity/references/matrix/` rewritten against the current code, each
  checked first: K7p, K7q and K7i cite `ShortClusterCompletionTest` and `CompletionLineRulesTest`, which do not exist
  (the tests are in `CompletionTest.kt:68`, `:80`, `:1727`); K7i, G24 and G64 name `helpShort`, and K13b and K61
  `Help.Generate(short = null)`, both removed (now `Help.ByCommand(short = null)`); K13c, G65 and G70 state
  `--help-all` on every command with subcommands, which since item 80 only `Help.ByTree` offers; X55, X64, L3 to L5
  and G13 re-checked against code and tools. The F1a note's `FindValueCandidatesTargetsTest` exists and stays. Docs
  only.
  Done 2026-10-12: every stale note in the six matrix files rewritten after a check; line numbers replaced by function
  and branch names. Correction: `ShortClusterCompletionTest` and `CompletionLineRulesTest` exist, as classes inside
  `CompletionTest.kt`; only files by those names do not.
- [x] **89. cp's completion model says only what it keeps.** Review 2026-10-12 (R6). **Owner, 2026-10-12:** A. The
  header of `CpCompletionModel.kt:10-14` claims nothing cp refuses is offered, but `refused()` keeps only the rules
  by name (a second `-t`, `-t` with `-T`, `-l` with `-s`). cp 9.12 also refuses `-n -b`, `-b --update=none` or
  `none-fail` ("--backup is mutually exclusive with -n or --update=none-fail") and `--reflink=always` with a
  `--sparse` other than auto ("--reflink can be used only with --sparse=auto"); `Cp.kt:264-280` writes them as
  `validateInputs` lambdas, which Tab does not run (`Completion.kt:136`). The header names the rules it keeps, and one
  cp record (Will not do) pins `cp -n --b<Tab>` offering `--backup`: Tab prunes only by declared rules, and Enter
  still gets cp's error. No klap change. Tests.
  Done 2026-10-12: the header names what the model keeps and what only cp's validators refuse; record
  CpLineContextTargetsTest `--backup is not offered beside -n` (seen failing for that reason). cp 179 tests, 2 records.
- [x] **88. The fixtures stop re-deriving `acceptSuggestion` and `tab`.** Review 2026-10-12 (R5). **Owner,
  2026-10-12:** A. cp (`CpLineContextSupport.kt:76`, `tabbed` at :110), find (`FindCompletionSupport.kt:361`) and
  pacman (`PacmanCompletionModel.kt:225`) rebuild the accepted line and the common-prefix rule and compare them with
  klap's helpers, so those checks fail only when a copy drifts. The helpers are pinned once in klap's
  `CompletionFlowTest` with literal lines (one suggestion, a shared prefix, nothing shared, a file directive, the
  cursor inside a word; add whichever is missing); the three copies and their assertions go; matrix rows E2 and E3
  say klap's test covers them, and the cells keep their offered sets. Tests.
  Done 2026-10-12: the copies in cp, find and pacman are gone; CompletionFlowTest gained the cursor inside a word, a
  spaced file directive and nothing offered; matrix E2, E3 and the skill's brief say klap's test covers them.
- [x] **87. Test code and tests that repeat each other go to one home.** Review 2026-10-12 (R4). **Owner,
  2026-10-12:** A. (1) One `Cli.offered`/`Cli.offeredAt`, with `bindText` and `helpText`, in a shared test file beside
  `TestTerminal.kt`, replacing the private copies in about 12 klap test files. (2) One home per rule: item 61's line
  order in `GnuConformanceTest` (drop the two `ArgvOrderInvarianceTest` duplicates, keep `-V`, `--color`, cluster and
  repeat shapes), `--` as a value in one test, long-name validation in `AuthorTest`. (3) find: the item 75 records
  merged across Completion and LineContext, the `-D`/`-O` tests duplicated in Projection and Converter given one home,
  the three tests the generated cells subsume dropped, `UNPINNED` removed so every description is compared. Test only;
  counts in todo and the example README restated. Tests.
  Done 2026-10-12: `TestCli.kt` holds `offered`, `offeredAt`, `bindText`, `helpText` (13 private copies gone); item
  61's order only in GnuConformanceTest, `--` as a value only there, long-name validation in AuthorTest (option and
  flag); find: item 75 records two in FindCompletionTargetsTest, `-D`/`-O` home FindConverterTest (`-O` twice binds the
  later level, per `find -D opt`), three subsumed tests dropped, `UNPINNED` gone. find 201 tests, 11 records.
- [x] **86. One home each for the operand minimum and for which built-ins take a value.** Review 2026-10-12 (R3).
  The relaxed minimum is decided in `internal/parse/Bind.kt:225` and again in `internal/render/Completion.kt:488`;
  which built-in longs take the next word is named in `FlagCommands.kt:200` (`VALUED_BUILTIN_LONGS`), `ArgvScan.kt:254`
  and `Parser.kt:61` `builtinLongs`, with the same gates. **Owner, 2026-10-12:** A. An internal `Command.operandMin`
  beside `operandCap`, read by bind and completion; one internal table of the built-in longs (name, gate, takes the
  next word) read by `builtinLongs`, `ArityWalk` and `FlagCommands`. No behaviour change: every test stays as it is.
  klap core.
  Done 2026-10-12: `Command.operandMin` beside `operandCap`, read by bind and the planner; one `builtinLongTable`
  (name, takes the next word, gated) read by `builtinLongs`, `ArityWalk.builtinClaim` and FlagCommands;
  `VALUED_BUILTIN_LONGS` gone. No test changed.
- [x] **85. Completion walks to the command the line names, past `--help`, as parsing does.** Review 2026-10-12
  (R2). `g --help build` is build's help (`routesTransparently`), but `g --help build --‸` offers the root's names
  (probe 2026-10-12: `[--help, --json, --color]`, where `g build --‸` adds `--fast`). **Owner, 2026-10-12:** A.
  Completion's `walkTo` steps over the same words through the one `routesTransparently`. klap core.
  Done 2026-10-12: completion's `walkTo` steps over what `routesTransparently` lets through, with parse's
  `globalFlagShorts`; `g --help build --` offers what `g build --` does. CompletionTest.
- [x] **84. Every page lists the built-ins that work there.** Review 2026-10-12 (R1). On a root with its own action
  and subcommands, `app sub --completion bash` parses and `app sub --‸` offers `--completion` and `--docs`, but `app
  sub --help` lists only `--help`, `--json`, `--color` (the `node is Cli && node.metaOptions` gate in
  `internal/render/Help.kt` `builtinOptions`; probe 2026-10-12). **Owner, 2026-10-12:** A. List them under Global
  options on every page where they work, and read completion's built-in list from the same place, so help and Tab
  cannot drift. klap core.
  Done 2026-10-12: `builtinOptions` takes the root's `rootMetaOptions`, so every page below a root with its own action
  lists `--completion` and `--docs` (text, JSON, man, markdown); completion reads its built-in names from
  `builtinOptions`. HelpTest, CompletionTest.
- [x] **83. An error names a spelling as GNU does.** GNU audit 2026-10-12 (C4). klap names the word as typed, cut at
  `=`; getopt_long names the resolved long when one resolved (`--al=x`: "option '--alpha' doesn't allow an argument";
  `ls --blo`: "option '--block-size' requires an argument"), the whole word as typed when none did (`--bet=2`: "option
  '--bet=2' is ambiguous"; `--zeta=1`: "unrecognized option '--zeta=1'"), and the letter alone in a cluster (`-a-`
  binds `-a`, then "invalid option -- '-'"). **Owner, 2026-10-12:** A. The same three rules, in klap's own phrasing,
  for the `CliError` fields as well (so `--json` shows them); restate the tests that pin today's spellings. klap core.
  Done 2026-10-12: a resolved long is named by its full spelling, an unresolved word whole, a cluster's bad letter alone (`-a-` is UnknownOption("--", cluster = "-a-")); GnuConformanceTest, and GluedValueTest, ParseOptionsTest, CompletionTest restated. `-a=x` stays FlagTakesNoValue("-a") (getopt binds `-a`, then refuses `=`): a different kind, left.
- [x] **81. An empty long name is an abbreviation, as getopt_long reads it.** GNU audit 2026-10-12. `getopt -o ''
  -l beta: -- --=x y` gives ` --beta 'x'`; with several longs it is ambiguous (`ls --=x`, `cp --=`: "option '--=x' is
  ambiguous; possibilities: ..."); klap refuses `--=x` as UnknownOption("--"). **Owner, 2026-10-12:** A. Under
  `Abbreviation.LongOptions` and up the empty name resolves when the pool holds one long and is ambiguous otherwise;
  under `None` it is unknown; the error quotes the whole word `--=x`. Tests: the 2 empty-name targets in
  `GetoptOracleTest`. klap core.
  Done 2026-10-12: `resolveLong` treats the empty name as a prefix of every long; both GetoptOracleTest targets pass (the first with the built-ins off, as getopt's table has none).
- [x] **80. One `help` setting says what `--help` covers; `helpAll` goes.** GNU audit 2026-10-12 (C2, P2), from the
  owner's draft. The GNU Coding Standards name `--help` and `--version` and no `--help-all`; git's `<cmd> --help` is
  one command and `git help -a` the list. **Owner, 2026-10-12:** A. `sealed interface Help`: `ByCommand(description,
  short = 'h')` (the default, GNU: `--help` is this command, no `--help-all`), `ByTree(description, short = 'h',
  allDescription)` (adds `--help-all`, the tree from where it is typed), `All(description, short = 'h')` (`--help`
  is the tree from here, no `--help-all`), `Custom(page, description, short = 'h')` (its page for `--help`, no
  `--help-all`), `Off`. `BuiltinsBuilder.helpAll` and `Help.Generate` go. On a CLI with no subcommand the three print
  one page. C2: under `ByTree` the exact-only carve-out narrows to what it protects, so `--h` to `--help` mean `--help`
  and `--help-` to `--help-al` reach `--help-all` (`getopt -o '' -l help,help-all -- --help-` gives ` --help-all`);
  elsewhere there is no `--help-all` and `--he` is `--help`, as in coreutils. Restate the tests, guide and fixtures
  (pacman's `helpAll = Builtin.Off`). klap core, public API.
  Done 2026-10-12: `Help.ByCommand` (default), `ByTree`, `All`, `Custom`, `Off` in `Builtins.kt`; `helpAll` and `Help.Generate` gone; under `All` a bare group prints the tree too, and `Custom` keeps `--help --json` as the generated data. `HelpSettingTest` (19). The carve-out is `YIELDING_LONGS = mapOf("help-all" to "help")`: `--help-` reaches `--help-all`. Tests that pinned `--help-all` set `Help.ByTree()`; git's fixture refuses `--help-all` as real git does; pacman drops `helpAll = Builtin.Off`. Matrix notes K13b, K13c, K61, G65, G70 are stale (owner's files).
- [x] **79. The help short counts as a global letter in a mixed cluster after the first operand.** GNU audit
  2026-10-12. Under `optionsEndAtFirstOperand`, `t x -av` with a declared global `-v` is `MixedClusterAfterOperands`
  (`docs/guide.md:2213`), but `t x -ah` and `t x -ha` show help. **Owner, 2026-10-12:** A. Refuse them as
  `MixedClusterAfterOperands`, as the guide states; `t x -h` still shows help (the built-ins' reach). Test:
  `GetoptOracleTest` `a cluster mixing the help short with a local one after the first operand is refused`. klap
  core.
  Done 2026-10-12: `mixedGlobalShort` counts the help and version shorts as global letters; `x -ah`, `x -ha`, `x -aV` are `MixedClusterAfterOperands`, `x -h` and `x -vh` still answer. The GetoptOracleTest target passes; OperandTerminatedOptionsTest.
  **Owner, 2026-10-12 (79a):** A, every built-in letter counts as a global letter, so `x -aV` is refused like `x -ah`.
- [x] **78. The built-in `--color` is GNU's `--color[=WHEN]`.** GNU audit 2026-10-12 (C1). klap's built-in takes a
  required value but never a word that starts with a dash (`--color --invert` is MissingOptionValue), which is neither
  getopt kind (`getopt -o '' -l color:,invert -- --color --invert f` gives ` --color '--invert'`). ls, grep and diff
  declare `--color[=WHEN]` ("The WHEN argument defaults to 'always'", `ls --help`); `ls --color always` lists the
  file `always`, `ls --color=bogus` is "invalid argument". **Owner, 2026-10-12:** A. `--color` is an optional value,
  bare meaning `always`, `--color=WHEN` setting it, a spaced word an operand; `--completion <shell>` and `--docs
  <format>` keep a required value and take the next word as getopt does, so `--completion --x` is an invalid shell.
  Help shows `--color[=WHEN]`'s klap form. Restate the tests, guide and fixtures that pin `--color always` spaced. klap
  core.
  Done 2026-10-12: a bare `--color` means always and the next word is an operand; `--completion` and `--docs` take the next word whatever it is; help shows `--color[=<auto|always|never>]`; completion offers values only after `--color=`. BuiltinsTest, HelpTest; color tests across klap restated with `ls --color always` evidence.
- [x] **77. An abbreviation resolves against the current command's own longs.** GNU audit 2026-10-12 (C3). klap
  resolves an abbreviated long against every long in the tree (`longMatchPool`, `internal/parse/Parser.kt:472`), so
  `t b --verb` is ambiguous between b's `--verbose` and its sibling a's `--verbatim` (probe, 2026-10-12); a GNU tool
  calls getopt_long with that command's table (`getopt -o '' -l verbose -- --verb` gives ` --verbose --`).
  **Owner, 2026-10-12:** A. Resolve against the current command's own longs, the globals and the built-ins; keep the
  invariant `longMatchPool`'s KDoc names (the pre-walk scans for `--help` and the like must not resolve what the
  walk calls ambiguous, or the reverse); report if it cannot hold. klap core.
  Done 2026-10-12: `longMatchPool` is the command's own longs, negations, flag-spelled children, globals and built-ins; the tree-wide pool and its helpers are gone, and the scans before the walk resolve each word against the pool of the command in scope where it stands (`ArityWalk` routes as the walk does), so parse, run and completion agree. A command and its flag-spelled children share one table (`Cli.sharedLongs`), as pacman's one getopt table reads (`pacman -Q --no` ambiguous). AbbreviationTest and FlagCommandTest.
  **Owner, 2026-10-12 (77a):** A, the shared table for a flag-spelled family stays.
- [x] **76. Completion follows what the line refuses after an optional value and beside `requireAtMostOne`.**
  Internal klap work left by the find TDD pass of 2026-10-11, no public API. (a) An `.optionalValue()` short offers
  its candidates glued on `-O‸` (`-O0` to `-O3`), as `find -O3` is its only form; today `-O` itself is offered. (b)
  Nothing is offered after a bare optional-value short whose bare form the fixture refuses (`find -O ‸`, refused by
  find: "must be immediately followed by a decimal integer"); completion cannot see a `validateInputs` refusal today,
  so this may need the refusal stated declaratively (owner call if so). (c) `requireAtMostOne(operand, files0From)`
  closes the operand slot in completion as it does in parsing (`find --files0-from x ‸` offers files; real find:
  "file operands cannot be combined with -files0-from"). Tests: Completion: `-O completes to its glued levels`, `a
  dash offers every name find accepts there`, `--files0-from leaves no room for a starting point`; ValueCandidates:
  `a bare -O offers its levels glued`, `-O takes no value in the next word`. klap core.
  (a) and (c) done 2026-10-11 in `internal/render/Completion.kt` (`GluedOptionalValueCompletionTest`, 9 tests;
  `ConstraintCompletionTest`, 4 more): an optional-value short offers its candidates glued (`-O‸`, `-‸`, `-vO‸`),
  and a `requireAtMostOne(operand, option)` closes the operand slot; 4 of the 5 find targets pass. (b) has no internal
  route: running `validateInputs` during completion broke 28 pacman tests (its root refuses every line without an
  operation), and a validation judges a finished line a later word can cure.
  **Owner, 2026-10-11:** (b) A: a new opt-in `.gluedValue()` on an option: the value is glued to the short or
  attached to the long with `=`, and the bare form is refused as `MissingOptionValue`; completion offers nothing
  after a bare one. Not a getopt form (getopt has required `O:`, which takes `-O 3`, and optional `O::`, which
  takes a bare `-O`); find's own parser (`find -O`: "must be immediately followed by a decimal integer", where getopt
  tools say "option requires an argument"), so the KDoc says so. GNU defaults stay; find opts in and drops its
  `validateInputs` and the `OptionalValue` wrapper on `-O`.
  Done 2026-10-12: `ConverterScope`'s `Opt<T>.gluedValue(): Opt<T>` (`Converters.kt`), `OptionSpec.gluedValue`
  and `valueAttachedOnly` (optional or glued, never the next word) read by `ArgvScan`, `FlagCommands`,
  `refusesCluster` and completion; a bare one is `MissingOptionValue`; help shows `-O<LEVEL>` and
  `-O, --optimise=<LEVEL>`; combining it with `optionalValue` fails at build time. `GluedValueTest` (27). find's `-O`
  uses it and drops its `validateInputs`; `FindInputs.optimise` stays the last level (`Int?`), since find uses the
  last `-O`. Both (b) targets pass: find has no target left, 211 tests, 198 pass, 13 records.
- [x] **65. find has none of klap's built-ins but `--help` and `--version`, described in find's words.** `find -h`,
  `find --json .`, `find --color .`, `find --completion bash`, `find --docs`: each "unknown predicate"; `find --help`
  says "display this help and exit" and "output version information and exit". Tests: Completion: `a klap built-in
  find lacks is not offered`, `--help and --version are described in find's words`; SuggestionList: `a built-in find
  does not have is not offered`, `help and version are described in find's words`, `a value after a built-in find
  lacks is none of klap's`; Projection: `an option klap adds that find does not have is refused`; ValueCandidates: `a
  built in --color claims no word so the next one is a starting point`; LineContext: `--color before the cursor is an
  unknown predicate that takes no value`. Fixture: `builtins { }`, `Help.Generate(description, short = null)`,
  `Version.Of(description)`.
  **Owner, 2026-10-11:** A, as written, the shape of `Cp.kt`'s `builtins { }`; the fixture loses `--completion`,
  Tab through `__complete` stays.
  Done 2026-10-11: `builtins { }` and `Version.Of(description)` as in `Cp.kt`; all 8 targets pass.
- [x] **66. find matches every predicate whole (F1b).** `find . -maxdep 1`, `find --he`: "unknown predicate". Tests:
  Completion: `an abbreviated predicate is an unknown word`; LineContext: `an abbreviated predicate is unknown and
  takes no value`; SuggestionList: `an abbreviated long is an unknown option`; ValueCandidates: `an abbreviated
  predicate is an unknown option whose next word is a starting point`; Projection: `a prefix of a predicate is
  refused as find refuses it`, `a prefix of help or version is refused`. Fixture: `abbreviation = Abbreviation.None`.
  **Owner, 2026-10-11:** A, as written.
  Done 2026-10-11: `abbreviation = Abbreviation.None`; all 6 targets pass.
- [x] **67. The fixture declares find's names and only those.** It invents `--physical`, `--logical`,
  `--follow-args`, `--debug`, `--optimise` (`find . -logical`: unknown predicate); it spells `-ignore_readdir_race`
  with dashes; its `--mount` help talks about klap; it lacks the predicates find has (amin, anewer, cmin, cnewer,
  daystart, execdir, false, fls, follow, fprint, fprint0, fstype, gid, ilname, inum, ipath, iregex, iwholename, lname,
  newerXY, noignore_readdir_race, nowarn, ok, okdir, samefile, true, uid, used, warn, wholename, xtype; each exits 0
  in a throwaway directory). Tests: Completion: `a long the fixture invents is not offered`, `--mount is described
  without a note on the fixture's source`, `--ignore_readdir_race is spelled as find spells it`, `a predicate find has
  that the fixture lacks completes by name`, `a dash offers every name find accepts there`; SuggestionList: `a long
  find does not have is not offered`, `ignore_readdir_race is spelled as find spells it`, `every predicate find has is
  offered` (dropped by item 87), `a long name completes to every long of find it starts across the gaps`, `a dash offers find's names and
  nothing else`, `the mount description speaks of the filesystem not of klap` (dropped by item 87); ValueCandidates: `the long spellings
  of -D and -O are unknown options`; Projection: `--ignore_readdir_race is spelled with underscores`, `a predicate find
  has binds`. Fixture. Open: whether a klap long name may hold `_`, and how `-fprintf FILE FORMAT` (two words) and
  the one-letter `-d` are respelled.
  **Owner, 2026-10-11:** A. Declare every predicate that runs here, `--d` and the 12 `-newerXY` forms of a, c, m
  and t included, each as one long; `-P -L -H -D -O` shorts only; `long = "ignore_readdir_race"` (klap takes `_`);
  `--mount` in the man page's words. `-fprintf FILE FORMAT` takes two words, so it is a will-not-do record beside
  `-exec`; `-context` and the birth-time forms are left out, since this system refuses them.
  Done 2026-10-11: shorts only for `-P -L -H -D -O`, `ignore_readdir_race`, `--mount` as this man page words it ("Ignore files on other devices", it calls it no synonym), every predicate that runs here with its own `FindInputs` field; `-fprintf` is a will-not-do record. All targets pass but `a dash offers every name find accepts there`, whose `-O` cells wait on item 76.
- [x] **68. `-regextype` takes find's 13 types, in find's order.** `find . -regextype help` lists
  findutils-default, awk, ed, egrep, emacs, gnu-awk, grep, posix-awk, posix-basic, posix-egrep, posix-extended,
  posix-minimal-basic, sed; the fixture has 6. Tests: Completion: `--regextype completes every regex type find
  accepts`; SuggestionList: `a regex type is any of find's in find's order`; ValueCandidates: `the regex type offers
  every type find takes in every form`; Converter: `--regextype binds every name find has`. Fixture.
  **Owner, 2026-10-11:** A, the 13 in find's order.
  Done 2026-10-11: the 13 types in find's order; all 4 targets pass.
- [x] **69. `-O` takes its level glued, any decimal up to 65535.** `find -O9 .` exits 0; `find -O65536 .`: "too
  high"; `find -O 3 .` and `find -O .`: "must be immediately followed by a decimal integer"; `-O+1` refused.
  Completion offers the documented `-O0` to `-O3`. Tests: Completion: `-O completes to its glued levels`;
  ValueCandidates: `a bare -O offers its levels glued`; Projection: `-O takes any decimal level`; Converter: `-O binds a
  level above 3 up to 65535`, `-O refuses a plus sign and non-ASCII digits`. Fixture; refusing the spaced `-O 3` is
  pinned loose in `FindProjectionTest` until klap can declare a glued-only value (see 74).
  **Owner, 2026-10-11:** A. `-O` is `.optionalValue()` (item 63): GNU's optional argument is glued-only, so `-O 3`
  reads `3` as a starting point and the bare `-O` is refused; ASCII digits 0 to 65535; `completeWith` offers the
  documented `-O0` to `-O3`. If completion does not offer an optional value glued on `-O‸`, that is internal klap
  work.
  Done 2026-10-11 for binding: `-O` is `.optionalValue()` with ASCII digits 0 to 65535, a bare `-O` refused; `-O4`, `-O9`, `-O65535` bind, `-O 3` and `-O+1` are refused. The completion half (`-O0` to `-O3` on `-O‸`, nothing after a bare `-O`) is item 76.
- [x] **70. `-D` takes find's debug keys in the next word, adds up, and `-D help` lists them.** `find -D help`
  lists exec, opt, rates, search, stat, time, tree, all, help and exits 0; `find -D tree -D search .` traces both;
  `find -D '' .`: "Empty argument to the -D option"; `find -Dtree .`: unknown predicate. Tests: Completion: `-D
  completes find's debug options in the next word`; ValueCandidates: `the debug option offers the keys find takes`;
  Projection: `-D accumulates every list it is given`, `-D refuses an empty list`, `-D help short-circuits as help
  does`; Converter: `-D refuses a list with no key`, `-D given twice keeps the keys of both`, `-D help prints the debug
  keys instead of searching`. Fixture, except `-D help`, which needs a way for a value to answer as `--help` does
  (owner call); glued `-Dtree` is pinned loose like `-O 3`.
  **Owner, 2026-10-11:** A. `-D` completes the 9 keys (one more after each comma), refuses an empty list, and is
  `.multiple()`, flattened. `-D help` binds `debug = [help]` and the action prints the key list, as find's own code
  does; the two short-circuit tests (Projection: `-D help short-circuits as help does`, Converter: `-D help prints the
  debug keys instead of searching`) are restated as binds. Glued `-Dtree` stays pinned loose (item 74).
  Done 2026-10-11: keys offered one more after each comma, an empty list refused, `.multiple()` flattened, `-D help` binds `[help]` and the action prints find's key list; the two short-circuit tests are restated as binds. Glued `-Dtree` is refused through item 74.
- [x] **71. `-type` takes find's letters, each once.** `-type f,d` runs; `-type f,f`: "Duplicate file type";
  `-type D`: "not supported because Solaris doors are not supported on the platform". Tests: Completion: `--type
  completes find's type letters`; ValueCandidates: `the file type offers the letters find takes`; Converter: `--type
  refuses a letter given twice`, `--type refuses the door letter on this platform`. Fixture (a `completeWith`
  provider and the validation).
  **Owner, 2026-10-11:** A. Letters `b c d p f l s`, each once; `D` refused as this system's find refuses it.
  Done 2026-10-11: letters `bcdpfls`, each once, `D` refused, the same rule for `--xtype`; all 4 targets pass.
- [x] **72. find's value grammars, as findutils parses them.** Counts take leading blanks (`-size ' 1'`), refuse a
  minus after the comparison (`-size +-1`), non-ASCII digits, and reach 2^64-1; time predicates read a strtod real
  (`-mtime 1.5`, `1e1`, `0x1`, `inf`); depths refuse `+1`; `-perm` refuses a mode find cannot compile (`8`, `+644`);
  `-regex '['` and `-printf '%'` are refused while parsing; an empty `-user`, `-group`, `-newer`, `-files0-from` is
  refused. Tests: every target in Converter but the `-O`, `-D`, `--type` and `--regextype` ones; Projection: `an
  empty file user or group name is refused`. Fixture (`findSize`, `findCount`, `findPerm` and the declarations).
  **Owner, 2026-10-11:** A, after asking which rules are GNU-wide. klap's `.int()` and `.long()` take GNU's number
  rules by default (leading blanks and `+` accepted, trailing blanks refused, ASCII decimal digits only; evidence
  `head -n ' 2'`, `head -n +2`, `ls -w ' 80'` run, `head -n '2 '`, `head -n ١`, `ls -w ١` refused), closing the
  X14 half of item 30 and adding X15; tests that pin the old reading are restated with GNU evidence. find opts into
  its own: depths refuse a sign, the time predicates read a strtod real (`FindCount.value` a `Double`), `-size` and
  `-links` a `ULong`, no sign after the comparison, `-perm` as find checks it (`-perm +644` refused, though `mkdir
  -m +644` runs), `-printf` refuses a trailing `%`, and an empty `-user`, `-group`, `-newer`, `-files0-from` is
  refused. `-regex '['` is a will-not-do record: Kotlin has no findutils regex engine.
  Done 2026-10-11: klap's `.int()` and `.long()` take GNU's number rules (`gnuDecimal` in `Converters.kt`, `ConvertersTest`), no other test pinned the old reading; find's own: depths ASCII digits only with no sign or blank, the time predicates a strtod `Double`, `-size`/`-links`/`-inum`/`-uid`/`-gid` a `ULong`, `-perm` as gnulib's `mode_compile` (about 50 modes checked), `-printf` as find scans for a trailing `%`, an empty reference file or name refused. `-regex '['` is a will-not-do record. `.double()` is unchanged (item 30's X24/X25 half stays open).
- [x] **73. A test given twice applies both.** `find . -type f -type d` matches nothing; `-printf x -printf y`
  prints both. Test: Projection: `a test given twice applies both`. Fixture (`.multiple()` on each test).
  **Owner, 2026-10-11:** A. The 12 tests (type, size, mtime, mmin, ctime, atime, links, perm, newer, user, group,
  printf) take `.multiple()` and their `FindInputs` fields become lists; the global options keep last-wins, as find
  does.
  Done 2026-10-11: the 12 tests, and every new one, are `.multiple()`; the target passes.
- [x] **74. find's switches `-P -L -H -D -O` never cluster.** `find -LP .`, `find -LD tree .`: unknown predicate;
  `find -L -P .` runs. Tests: Completion: `-P -L -H and -D are offered alone`, `a cluster of find's letters is one
  unknown word`; SuggestionList: `a short of find does not cluster`; ValueCandidates: `a cluster find refuses leaves
  the next word to the starting points`. klap: a short that does not cluster is new API (owner call); the loose
  clusters are pinned in `FindProjectionTest` until then.
  **Owner, 2026-10-11:** A. New CLI-wide setting beside `abbreviation`: `shortOptions = ShortOptions.Alone` (default
  `ShortOptions.Clustered`, as getopt). Alone: each short is its own word, no clusters, a required value is the next
  word; an optional value stays glued (getopt's only form), so `-O3` (item 69) keeps working. Names proposed in the
  walkthrough and accepted with it. The loose `-LP`, `-PL`, `-LL`, `-Dtree` cells become refusals.
  Done 2026-10-11: `public enum class ShortOptions { Clustered, Alone }` (`ShortOptions.kt`) and `CliBuilder.shortOptions`, root-only, one rule `Command.refusesCluster` in `internal/parse/Parser.kt` consulted wherever a cluster is walked (sift, globals, `ArgvScan`, flag commands, completion); `ShortOptionsTest` (14), a guide section beside Abbreviation. find sets `ShortOptions.Alone`; the 4 targets pass and `-LP`, `-PL`, `-LL`, `-Dtree` are refused.
- [x] **75. Order: the switches come before the first starting point, the starting points before the first
  predicate, and `-files0-from` replaces them.** `find . -L`: unknown predicate; `find -name a .`: "paths must
  precede expression"; `find . -files0-from a`: "extra operand". Tests: Completion: `find's own options end at the
  first starting point`, `a starting point after a predicate is not offered`, `--files0-from leaves no room for a
  starting point`; LineContext: `the pre-path switches are not offered after a starting point or a predicate`, `no
  starting point is offered after a predicate`; Projection: `--files0-from refuses a starting point beside it`. The
  `-files0-from` half is fixture work (`requireAtMostOne`); the order half needs klap API or belongs to F1's order
  record (owner call).
  **Owner, 2026-10-11:** A. `requireAtMostOne(operand, files0From)` for the `-files0-from` half; the 4 order
  targets (Completion: `find's own options end at the first starting point`, `a starting point after a predicate is
  not offered`; LineContext: both) fold into the F1 record `find's expression grammar and its order are read as find
  reads them`, since `optionsEndAtFirstOperand` would end the predicates too and a position API would serve find alone.
  Done 2026-10-11: `requireAtMostOne(operand, files0From)`, the projection target passes; the 4 order targets are will-not-do records (F1, 75). Completion still offers starting points beside `--files0-from`: item 76.
- [x] **21. The target-directory forms are exclusive, `-t` is given once, and `-T` takes exactly two operands.**
  Projection: `a second target directory is refused`, `-T takes exactly a source and a destination`, `-t and -T are
  refused together`; Completion: `-t and -T leave each other out and -t is not offered twice`, `-T leaves no room
  for a third operand`; LineContext: `-t and -T leave each other out wherever the line names one`, `a second target
  directory is not offered before or after the cursor`, `-T leaves no operand slot once a source and a destination
  are on the line`. `cp -t d -t d a`: "multiple target directories specified"; `cp -T a b c`: "extra operand". The
  exclusion is fixture only (a `requireAtMostOne` makes the LineContext cells pass); refusing a second `-t` needs
  klap to let an option refuse a repeat, and capping the operands under `-T` needs a declarative maximum. Fixture
  plus klap core.
  - 21a (owner, 2026-10-11, A; revised the same day, A): a CLI-wide `repeatedOptions = RepeatedOptions.LastWins |
    Refuse` on the `cli { }` builder, beside `abbreviation`, defaults to GNU's `LastWins` (`cp -S .x -S .y`, `ls
    --sort=size --sort=time`, `head -n 1 -n 2` take the last); `Refuse` makes a second occurrence
    `CliError.RepeatedOption`, rendered as `RepeatedCommand` is. A per-option `.repeated(RepeatedOptions.Refuse)`
    overrides it for a tool that mixes the two, as cp does: `cp -t d -t e a`, `mv -t d -t e a`, `install -t d -t e
    a`: "multiple target directories specified". `.multiple()` options collect and are outside the policy.
    Completion leaves out an option the policy refuses once it is given. Replaces the first answer's `.once()`.
  - 21b (owner, 2026-10-11, A): `.multiple(min, max = Int.MAX_VALUE)` caps a variadic, unbounded by default as GNU's
    operand lists are, and `.atMostWhen(input, n)` caps it while an input is given, beside `absentWhen`. Parse
    refuses an operand past the cap (`CliError.TooManyArguments`) and completion offers no operand slot once it is
    reached. `cp -T a b c`: "extra operand 'c'"; `cp -T a b2` and `cp a c d` copy. klap core, then cp declares
    `sources.multiple(min = 1).atMostWhen(noTargetDirectory, 1)`.
  - 2026-10-11: the fixture half is done: `requireAtMostOne(-t, -T)`, a validation refusing a second `-t` and `-T`
    past two operands (Projection's three targets and LineContext's exclusion pass). Open: 21a and 21b in klap, then
    Completion `-T leaves no room for a third operand`, `-t and -T leave each other out and -t is not offered twice`
    (also an excluded option's attached value or cluster: `cp -T --target-directory=‸`, `cp -t d -vT‸`), LineContext
    `a second target directory is not offered before or after the cursor`, `-T leaves no operand slot once a source
    and a destination are on the line`. `cp -t a -t b --help` is refused by cp and shows help in the fixture until
    `.once()` makes the repeat a parse error.
  **Verdict 2026-10-11:** done (klap core and fixture). 21a: `RepeatedOptions { LastWins, Refuse }` as
  `repeatedOptions` on `cli { }`, `.repeated(...)` on an option or flag, `CliError.RepeatedOption`
  ("'--target-directory' may be given only once"), placed in line order so `cp -t a -t b --help` is refused and `cp
  --help -t a -t b` shows help; `RepeatedOptionsTest`. 21b: `Arg.multiple(min, max)` and `Arg.atMostWhen(input, n)`,
  so `cp -T a b c` refuses `c`, and completion offers no operand once the slots are full, counting words after the
  cursor; `OperandCapTest`. Completion also leaves out an option the line excludes, in its attached value and in a
  cluster (`ConstraintCompletionTest`). cp declares `-t` with `.repeated(Refuse)` and caps its sources; mv's `-t`
  refuses a repeat (`MvParityTest`).
- [x] **57. A line without a link option, and the shorthands, bind as cp reads them.** Projection: `a line without a
  link option binds apart from -P`, `a shorthand and a later link or preserve option bind as their order reads`,
  `the later of --preserve and --no-preserve decides an attribute`. `cp lnk out` copies the file and `cp -R lnk out`
  the link, so no link option is a third state; `cp -d -L lnk o1` gave a file and `cp -L -d lnk o2` a link; with a
  mode 600 file, `-p --no-preserve=mode` gave 644 and the reverse 600. Fixture.
  - 2026-10-11: `a line without a link option binds apart from -P` passes (`-L`, `-P`, `-H` are three flags under
    `lastWins`, so no link option is its own state). Open, needing klap API (owner decision): a fixture reading the
    order of two inputs that both keep their own effect, for `a shorthand and a later link or preserve option bind
    as their order reads` and `the later of --preserve and --no-preserve decides an attribute`.
  - 57a (owner, 2026-10-11, A): one generic primitive, `inOrder(vararg inputs)` in the projection, gives the
    occurrences of those inputs in line order with their values, and the fixture replays them as cp's getopt loop
    assigns its settings. Real cp 9.12: `cp -d -L lnk out` gives a regular file, `cp -L -d lnk out` a link, `cp -a -L`
    a file; `cp -p --no-preserve=mode` gives mode 644, `cp --no-preserve=mode -p` 600, `cp --preserve=mode
    --no-preserve=mode` 644. klap core, then cp replays `-a`, `-d`, `-p`, `-L`, `-P`, `-H`, `--preserve` and
    `--no-preserve`.
  **Verdict 2026-10-11:** done (klap core and fixture). `inOrder(vararg inputs)` on `ValueScope` gives each
  occurrence (`Occurrence(input, value)`) in line order (`InOrderTest`); cp replays `-a`, `-d`, `-p`, `-L`, `-P`,
  `-H`, `--preserve` and `--no-preserve` as its loop assigns them, so `cp -d -L` and `cp -L -d` bind apart and the
  later of `--preserve` and `--no-preserve` decides an attribute.
- [x] **63. An optional value whose bare form names no value (owner, 2026-10-11, E1 A).** `optionalValue()` with no
  argument binds `OptionalValue.Bare` when the option is given bare and `OptionalValue.Given(value)` when a value is
  attached, and help shows no `bare:` hint; `optionalValue(whenBare)` stays for a bare form that means a value
  (`ls --color` is `--color=always`, `cp --update` is `older`, `cp --reflink` is `always`). cp's bare `--backup`
  ("the control from `VERSION_CONTROL`, else `existing`") and bare `--context` ("like `-Z`") name no value, so the
  fixture binds the marker strings `$VERSION_CONTROL` and `-Z` today: `cp --help` in the fixture shows `(bare:
  $VERSION_CONTROL)`, which real cp's `--backup[=CONTROL]` row does not, and `cp --context=-Z a b` reads as bare.
  klap core, then cp drops both markers (`Cp.kt` `BACKUP_BARE`, `CONTEXT_BARE`).
  **Verdict 2026-10-11:** done (klap core and fixture). `Opt<T?>.optionalValue(): Opt<OptionalValue<T>?>` binds
  `OptionalValue.Bare` or `OptionalValue.Given(value)`, composes with converters before it and `.multiple()` after
  it, and help shows no bare hint (`BareOptionalValueTest`); cp's `--backup` and `--context` use it, the two markers
  are gone, and `cp --context=-Z a b` binds the context `-Z`.

- [x] **16. `-r` is a short of `--recursive`.** Projection: `-r is a short of --recursive`; SuggestionList: `a long
  name completes to cp's names only`, `a dash offers cp's names in cp's words and nothing else`. `cp --help`: `-R,
  -r, --recursive`; `cp --recursive-r a b`: "unrecognized option". The help-page half is Projection: `the help page
  lists cp's options and nothing cp refuses`. Fixture.
  **Verdict 2026-10-11:** done (fixture). `--recursive` takes `-R` and `-r`; `--recursive-r` is gone, so `--rec`
  resolves and the help page lists cp's names only.
- [x] **17. `-u` is `--update` without a value, and the later of the two wins.** Projection: `-u is --update without
  a value and the later of the two wins`. With `b` older than `a`, `cp --update=none -u a b` replaced `b`; `cp -u
  --update=none a b` left it. Fixture.
  **Verdict 2026-10-11:** done (fixture). `-u` and `--update` are one setting under `lastWins`; `-u` binds `update =
  older` when it is the later.
- [x] **18. `--backup`: a bare one keeps the control already chosen, an empty one means the default, and only the
  last is read.** Projection: `a bare --backup keeps the control already chosen and an empty one means what -b
  means`, `-b and a bare --backup are one setting`, `a backup control alias binds what its control binds`;
  Converter: `an invalid earlier --backup is never read`. With `b.~1~` present, `cp --backup=simple --backup a b`
  made `b~` and `cp --backup=simple --backup= a b` made `b.~2~`; `cp --backup=bogus --backup=never a b` exits 0;
  `--backup=t` makes `b.~1~`, `nil` makes `b~`. (Corrected 2026-10-10: an empty `--backup=` does not keep the
  earlier control.) Fixture, or klap's `.optionalValue()` for the bare form.
  **Verdict 2026-10-11:** done (fixture). `--backup` is free text read after the options: the last control that names
  one wins, else the environment's `VERSION_CONTROL`, else `existing`, matched as gnulib's argmatch does (unique
  prefixes, aliases bound by the first name); `-b`, a bare `--backup` and `-S` all ask for a backup.
- [x] **19. `--preserve` and `--context` take a value only when attached.** Projection: `--preserve and --context
  take their value only when attached`; ValueCandidates: `--preserve and --context leave a spaced word to the
  operands`, `a bare --preserve or --context leaves the next option to take the cursor word`. `cp --help`:
  `--preserve[=ATTR_LIST]`, `--context[=CTX]`; `cp --preserve mode a b`: "target 'b': Not a directory". Fixture.
  **Verdict 2026-10-11:** done (fixture). `--preserve` and `--context` take an optional value; bare, `--preserve` is
  `mode,ownership,timestamps` and `--context` is `-Z`.
- [x] **20. An attribute list holds cp's attribute names, and repeated lists add up.** Converter: `an attribute list
  refuses a name cp does not have`, `an attribute list reads a unique prefix as the name it abbreviates`, `a
  repeated attribute list adds to the earlier one`, `an invalid earlier attribute list refuses the line`;
  ValueCandidates: `an attribute list completes cp's attribute names after each comma`. `cp --preserve=bogus` is
  refused, `--preserve=m` reads as `mode`, `--preserve=mode --preserve=timestamps` keeps both. Fixture.
  **Verdict 2026-10-11:** done (fixture). The attribute-list converter refuses a name cp lacks and reads a unique
  prefix as its name; lists are `.multiple()` and add up; completion offers the names after each comma.
- [x] **25. Help and completion describe cp's inputs in cp's words.** SuggestionList: `a long name is described in
  cp's words`, `--no-dereference is described as never following links`, `--help and --version are described in
  cp's words`, `a cluster continues with cp's letters in cp's words`; Completion: `a long name is described in cp's
  words wherever the cursor sits in it`; ValueCandidates: `an attached choice is described by cp's help`, `an
  attached choice keeps cp's help through prefixes and abbreviations`; LineContext: `an override partner around the
  cursor is described in cp's words`. `cp --help`, verbatim. klap 0.6.0 has what it needs (`negatable(help =)`,
  `Help.Generate(description =)`, `Version.Of(description =)`), so this is fixture work. The version text is done:
  `CpVersionTest`.
  **Verdict 2026-10-11:** done (fixture). Every help text is `cp --help`'s, `-P` has its own, and
  `--help`/`--version` carry cp's descriptions.
- [x] **58. The option pairs cp refuses before it copies are refused.** Projection: `options cp cannot combine are
  refused`. `cp -l -s a o`: "cannot make both hard and symbolic links"; `-n -b`: "--backup is mutually exclusive
  with -n or --update=none-fail"; `--reflink --sparse=never`: "--reflink can be used only with --sparse=auto".
  Fixture (`requireAtMostOne` for `-l`/`-s`, validation for the value-dependent pairs).
  **Verdict 2026-10-11:** done (fixture). `requireAtMostOne(link, symbolicLink)` and validations refuse a backup with
  `-n` or `--update=none[-fail]`, and `--reflink=always` with a `--sparse` other than `auto`.
- [x] **62. A backup without its own control or suffix takes them from the environment.** Projection: `a backup
  without its own control or suffix takes them from the environment` (10 of its 13 cells fail; the passing
  neighbour `the backup environment changes nothing where cp makes no backup or names its control` stays green).
  Real cp 9.12 with `b` existing: under `VERSION_CONTROL=numbered`, `cp -b`, `--backup`, `--backup=` and `-S .x`
  make `b.~1~` and `--backup=simple` makes `b~`; `VERSION_CONTROL=n` is ambiguous and `bogus` invalid, refused
  only when cp backs up; `SIMPLE_BACKUP_SUFFIX=.env cp -b a b` makes `b.env`, `-S` beats it, and a suffix with a
  slash or an empty one falls back to `~`. `cpCli(environment)` is the seam, unused so far. Fixture.
  **Verdict 2026-10-11:** done (fixture). `cpCli(environment)` resolves `VERSION_CONTROL` and `SIMPLE_BACKUP_SUFFIX`
  only when a backup is made; an empty suffix or one holding a slash is ignored.
- [x] **59. Completion stops a short cluster at a negative short.** cp: Completion `a negative short continues a
  cluster as any flag does`. `suggestionsFor("cp -P")` gives `[-P]` and `cp -Pv` gives `[]`; real `cp -Pv a c1`
  copies. `firstNonFlagShort` (`internal/render/Completion.kt`) looks letters up through the flags' own shorts,
  which hold no negative short. klap core.
  **Verdict 2026-10-11:** done (klap core). `firstNonFlagShort` steps over a flag's negative shorts as over its plain
  ones; `ShortClusterCompletionTest` `a negative short continues a cluster as any flag does`.
- [x] **60. Completion offers the operand slot after an ambiguous long.** cp: Completion `nothing completes after an
  ambiguous long`, ValueCandidates `an ambiguous abbreviation leaves the next word without candidates`.
  `suggestionsFor("cp --re ")` gives the file directive; `cp --re a b`: "option '--re' is ambiguous". Decision K34
  says nothing; `matchingValueOption` returns null on `NameMatch.Ambiguous` and the positional branch answers.
  klap core.
  **Verdict 2026-10-11:** done (klap core). After an ambiguous long the planner offers nothing, through one
  `resolvedLong` helper; an unknown long still leaves the next word an operand (K47); `AmbiguousLongCompletionTest`.
  Not covered yet: an ambiguous long carrying `=x`, or one earlier than the word before the cursor.
- [x] **61. The first of `--help`, `--version` and a bad option or value decides, in line order (owner,
  2026-10-11).** GNU coreutils answers `--help` and `--version` inside getopt's loop, so whatever the line gives
  first wins; klap follows it, reversing 36b and extending 36a. Real cp 9.12, run word by word:
  `cp --help --bogus` and `cp --help --sparse=bogus` print the usage, `cp --version --bogus` the banner (exit 0);
  `cp --bogus --help` is "unrecognized option" and `cp --sparse=bogus --help` "invalid argument 'bogus' for
  '--sparse'" (exit 1). `ls` and `mkdir` read `--bogus` the same way; grep refuses both orders. klap today answers
  help ahead of a bad value in either order and refuses a bad option in either order.
  - Targets: `ArgvOrderInvarianceTest` `--help or --version written before a bad option answers`, `a bad value
    written before --help refuses the line` (its passing neighbours `a bad option written before --help or
    --version refuses the line`, `--help written before a bad value answers` must stay green); cp
    `CpProjectionTargetsTest` `help or version written before an unknown option answers`, `a bad choice written
    before help refuses the line`; find `FindParityTest` `version answers ahead of a later unknown predicate`.
  - Also changes: pacman refuses both orders, so `PacmanProjectionTest` `an unknown option on the line refuses it
    even beside help` (its `--help --bogus` cell) and `an unknown option refuses the line beside version` become
    will-not-do records; `BuiltinsTest` `unknown option with help errors identically to without help` keeps its
    `--zzz --help` order. The guide's "Which built-in answers" paragraph ("An option the tree declares nowhere
    refuses the line ahead of both buckets, wherever it stands ... A bad *value* does not") and the comment over
    `versionFirst` in `Parser.kt` state the old rule. A `prio` command (36b note above) is untouched.
  - Layer: klap core (`Parser.kt`: the built-in answer and the walk's first error compare positions).
  **Verdict 2026-10-11:** done (klap core). `parseTokens` compares the earliest refusal (an unknown or ambiguous
  option, a value given to a flag, a value failing its conversion or check, a bad `--color`) with the answering
  built-in's token, and the earlier wins; operands, required inputs and `validateInputs` never refuse help.
  `GnuConformanceTest` `the first of --help and a bad option or value decides`, `operands are checked after the
  options so they never refuse --help`; the guide states it. pacman refuses both orders, so its tests became
  will-not-do records (`an unknown option refuses the line beside help or version`, `help outranks a bad value
  written before it`).

- [x] **56. GNU getopt_long is klap's standard, not the POSIX guidelines.** Decided by the owner 2026-10-10 (56 A),
  with 33a (a `--` in an option's value slot is that value, as getopt reads it: `ls --sort --` is "invalid argument
  '--' for '--sort'", `grep -e -- -v` searches for `--` and still reads `-v`). The guide's "POSIX conformance" becomes
  "GNU conformance", `PosixConformanceTest` becomes `GnuConformanceTest`, each rule shown by a real tool, and KDoc that
  cites a POSIX guideline states the GNU rule. Item 33 moves from will not do to done with it.
  - 56b (owner, A): the default abbreviation is `Abbreviation.LongOptions`, getopt_long's own rule: long names
    abbreviate (`mkdir --par d`, `ls --s` "is ambiguous"), values do not; coreutils' value matching
    (`ls --colo=al`) stays `Options`, opted into.
  **Verdict 2026-10-10:** done. The guide's "GNU conformance" section and the README list getopt_long's rules, each
  with a real tool's line; `GnuConformanceTest` (24 tests) replaces `PosixConformanceTest`; every KDoc and test
  comment that cited a POSIX guideline states the getopt rule. The default abbreviation is `LongOptions` (56b).
- [x] **33. A `--` that an option takes as its value is that value.** Filed as will not do on 2026-10-10.
  **Verdict 2026-10-10 (owner, 33a):** reversed with item 56: as getopt reads it, the word after a value-taking option
  is its value even when it is `--` (`ls --sort --`, `grep -e -- -v`), and the options go on after it; only a `--`
  where an option would stand ends them. The pacman tests `a value word that is exactly the end of options is the
  value`, `names go on after a -- that was an option's value` and `an end of options taken as a value leaves the
  options open` pass.
- [x] **55. A typed error for one command named twice.** **Verdict 2026-10-10 (owner, A):** `CliError.RepeatedCommand(
  command)`, rendered "'--query' may be given only once" and in JSON as `{"type":"RepeatedCommand","command":"--query"}`,
  replaces the `CliError.Usage` sentence klap used for `pacman -QQ`; `Usage` stays the app's own. `FlagCommandTest`
  `K7ad two commands of one parent on a line are refused` pins both forms.
- [x] **6. The operation given twice is refused.** pacman refuses `-Q -Q` and `-QQ` ("only one operation may be used
  at a time"). Filed as will not do on 2026-10-10, as a repeated flag is harmless in a normal CLI.
  **Verdict 2026-10-10 (owner):** reversed: an operation is a flag-spelled command now (item 47), and naming one command
  twice is refused as pacman refuses it. `PacmanProjectionTest` `the operation given twice is refused` and
  `PacmanLineContextTest` `an operation given before or after the cursor is not offered again` pass.
- [x] **52. A pacman completion target expects fewer repeats than pacman takes.** `PacmanCompletionTest`
  `a cluster continues from the cursor with its operation's page letters` (still ignored) expects `pacman -Qii<TAB>`
  to stop offering `-i` ("a counted one once more"), but `pacman -Qiii pacman` exits 0 (pacman 7.1.0, run
  2026-10-10), and item 13 decided that a counted letter is offered again. klap offers `-Qiii`. The test needs fixing,
  not klap: once the model allows a counted letter any number of times, the target should pass.
  **Verdict 2026-10-10:** the model was wrong, as filed: it now lets a counted letter repeat any number of times, as
  pacman does, and the target passes in `PacmanCompletionTest`.
- [x] **53. pacman's operations are commands now: other modelling notes.** Found while moving it (2026-10-10):
  `builtins { helpAll = false }` was added so pacman can decline `--help-all`, which a tree with subcommands offers
  (item 49 folds it into `helpAll = Builtin.Off`); a line naming no operation is refused by a root
  `validateInputs` ("no operation specified"); `pacman -QQ` is refused as pacman refuses it.
  **Verdict 2026-10-10:** recorded, nothing open: `helpAll = false` became `helpAll = Builtin.Off` with item 49.
- [x] **50. A negation describes itself.** Decided by the owner 2026-10-10 (50 A): `.negatable(..., help = "...")`
  gives the negative half its own words, its own `--help` row and its own description in Tab. pacman declares
  `--noconfirm` with `--confirm` as its negation, which orders the two as pacman does (the later wins), and its record
  holds one `noConfirm`, pacman's `config->noconfirm`. Closes cp's `a negation spelling carries its own help` too.
  **Verdict 2026-10-10:** done in klap 0.6.0 (`NegationSpellingTest` `a negation with its own help has a row and a
  description of its own`); pacman declares `--noconfirm` with `--confirm` as its negation.
- [x] **49. One value per built-in instead of switches and texts.** Proposed by the owner 2026-10-10, being decided
  one by one. Today `builtins { }` holds 7 switches (`json`, `color`, `completion`, `docs`, `helpShort`, `helpAll`,
  `versionShort`) and 7 texts, which can disagree (`json = false` beside a `jsonText`).
  - 49a: help takes three cases: `Help.Generate` (the page klap generates, the default), `Help.Custom("...")`, whose
    text is the page `--help` prints, and `Help.Off`. Turning help off undoes the rule that `--help` always exists.
  - 49b: the `--help` row words (today's `helpText`) and the `-h` short are parameters of the two cases that keep
    `--help`: `Help.Generate(description = ..., short = 'h')`, `Help.Custom(page, description = ..., short = 'h')`,
    klap's words and `-h` by default. Nothing stays a separate switch or text.
  - 49c: `Version` takes the same three cases (`Generate` prints `<name> <version>`, `Custom` a text of the tool's own,
    such as pacman's banner, `Off`), with `description` and `short` on the first two. `helpAll`, `json`, `color`,
    `completion` and `docs` take `Builtin.On(description = ...)`, the default, or `Builtin.Off`: they only report
    the tree, so there is nothing to replace. `Version.Off` is the one way to drop `--version`; the root's `version`
    string stays (the `--json` envelope and the docs read it), and a `version` built-in on without one is refused.
  - 49d (owner, 2026-10-10, A; replaces 49c's `Version`): `version` on the root takes `Version.Off`, the default, or
    `Version.Of(number, long = "version", short: Char? = null, description = null, text = null)`, one value that both
    turns `--version` on and holds the number, its spellings, its row words and, as `text`, a line of the tool's own to
    print in place of `<name> <number>` (pacman's banner). Nothing about the version stays in `builtins { }`.
  **Verdict 2026-10-10:** done in klap 0.6.0 as 49a to 49d decided: `builtins { }` takes `help: Help` (`Generate`,
  `Custom`, `Off`) and `Builtin.On`/`Builtin.Off` for `helpAll`, `json`, `color`, `completion` and `docs`; the root's
  `version` is `Version.Off` or `Version.Of(...)`, whose spellings read as a flag's (`short = 'V'` alone is `-V`
  only, item 54). The internal `Builtin` enum became `BuiltinKind`. `BuiltinsTest` pins `Help.Off`, `Help.Custom`
  and `Version.Of`'s long, short and text.
- [x] **54. `Version.Of` cannot go without a long.** ssh's version switch is `-V` alone (`ssh --version`: "unknown
  option -- -"), but `Version.Of` always gives `--version` a long, so the fixture accepts it.
  `SshParityTest` `--version is refused as ssh refuses it`, ignored as a target.
  **Verdict 2026-10-10 (owner):** `Version.Of(number, short = 'V')` is the short alone, as a flag's spellings read
  (48e); `long` and `short` together give both. ssh declares it, and the test passes.
- [x] **4. An option belongs to the operations whose page lists it.** `PacmanProjectionTargetsTest`
  `a long of another operation is refused under -Q` (and `-S`, `-R`, `-U`, `-D`, `-F`, `-T`: 7 tests) and
  `a letter of another operation is refused under ...` (7 tests). `pacman -Q --needed pacman` gives "error:
  invalid option '--needed'", `pacman -Qy` "invalid option '-y'". klap core: no way to scope an option to some
  members of a `requireExactlyOne` set.
  **Verdict 2026-10-10:** done by moving pacman onto flag-spelled commands (item 47): each operation is
  `command(short = 'Q', long = "query") { }` with the options its page lists, in the page's words, and the 18 options
  every page lists are globals. `PacmanProjectionTest` `a long of another operation is refused under -Q` and `a letter of another
  operation is refused under -Q` (and under each other operation) pass.
- [x] **5. A letter means what the operation on the line gives it.** `PacmanProjectionTargetsTest`
  `a contested letter means what -Q gives it`, `... -R gives it`. Under `-Q`, `-c -d -n -p -u` are changelog,
  deps, native, file, upgrades; under `-R`, `-c -s -u` are cascade, recursive (`-ss`), unneeded. klap core: one
  letter, one spec today.
  **Verdict 2026-10-10:** done by moving pacman onto flag-spelled commands (item 47): each operation is
  `command(short = 'Q', long = "query") { }` with the options its page lists, in the page's words, and the 18 options
  every page lists are globals. A letter means what its operation declares: `PacmanProjectionTest` `a contested letter means what
  -Q gives it` and `... -R gives it` pass.
- [x] **10. An operation's help lists that operation's options only.** `PacmanProjectionTargetsTest`
  `an operation's help lists that operation's options only`. `pacman -Qh` prints the `-Q` page. klap core.
  **Verdict 2026-10-10:** done by moving pacman onto flag-spelled commands (item 47): each operation is
  `command(short = 'Q', long = "query") { }` with the options its page lists, in the page's words, and the 18 options
  every page lists are globals. `pacman -Qh` shows the `-Q` page: `PacmanProjectionTest` `an operation's help lists that
  operation's options only` passes.
- [x] **11. An operation whose usage needs targets refuses a line without them.** `PacmanProjectionTargetsTest`
  `an operation whose usage requires targets refuses a line without them` (`-R`, `-U`). Evidence from the usage
  lines only; never run. klap core: operand arity per operation.
  **Verdict 2026-10-10:** done by moving pacman onto flag-spelled commands (item 47): each operation is
  `command(short = 'Q', long = "query") { }` with the options its page lists, in the page's words, and the 18 options
  every page lists are globals. `-R` and `-U` declare their targets `.multiple(min = 1)`: `PacmanProjectionTest` `an operation whose
  usage requires targets refuses a line without them` passes.
- [x] **12. Completion offers only what the operation on the line accepts.** `PacmanCompletionTargetsTest` 14 tests: `a
  cluster holding an operation offers no other operation` (item 1), `an option of another operation is not offered`, `a
  dash offers the operation's page and nothing pacman refuses`, `a long of another operation completes to nothing`, `a
  long on the page is described in that page's words`, `the operation already given is not offered again`, `another
  operation's name as typed completes to this operation's options`, `a long name completes to what an operation after
  the cursor accepts`, `a letter of another operation offers nothing after it`, `a contested letter continues by its
  meaning on the operation's page`, `a letter on the page continues with that page's letters`, `a letter of an operation
  continues with that operation's letters or with nothing`, `an operation's own cluster continues with that operation's
  letters`, `a cluster continues with the letters of an operation after the cursor`, and
  `PacmanValueCandidatesTargetsTest` `a color value in capitals is not offered`. `pacman -Q --ne<TAB>` offers `--needed`
  today. klap core, plus the fixture for the built-ins of item 8.
  **Verdict 2026-10-10:** done by moving pacman onto flag-spelled commands (item 47): each operation is
  `command(short = 'Q', long = "query") { }` with the options its page lists, in the page's words, and the 18 options
  every page lists are globals. Completion reads the operation wherever it stands, the cursor's cluster and the words after it
  included, and before one is named offers every operation's names, each described by the first page listing it.
  The 14 completion targets and `a color value in capitals is not offered` pass; one cell group stays open as item 52.
- [x] **44. Completion keeps to the operation's page at every cursor position.** `PacmanCompletionTargetsTest`
  `a dash inside a word or before an operation offers what a bare dash under it offers`, `a long name typed in part
  completes to the longs of the operation's page`, `a cluster continues from the cursor with its operation's page
  letters`. Item 12 at the inside, start and words-after positions: `pacman -Q --db‸path`, `pacman -Q --r‸e`
  and `pacman --sy‸ -Q` offer longs pacman refuses there.
  klap core.
  **Verdict 2026-10-10:** done by moving pacman onto flag-spelled commands (item 47): each operation is
  `command(short = 'Q', long = "query") { }` with the options its page lists, in the page's words, and the 18 options
  every page lists are globals. The three targets for the inside, start and words-after positions pass.
- [x] **51. Testing piped input.** **Verdict 2026-10-10 (owner, A):** klap adds no stdin seam and no pipe tests:
  it parses argv and never reads stdin, and its part (a lone `-` and the words after `--` are operands) is covered.
  Standard input belongs in kern's `Terminal`, which only writes today (kern 0.5.1); kotoml files that as its item
  135, and klap's `run(argv, terminal)` hands the terminal through as it does now.
- [x] **48. Declarations take their spellings without dashes and the rest in a block.** Proposed by the owner
  2026-10-10, being decided one by one; folds in item 26 (separate short and long spellings) and 47c (the overload
  `command(short, long)` clashing with today's `command(name, help)`):
  - The shape: `flag("f", "force") { help = "force action" }`, every setting but the spellings inside the block.
  - 48a: spellings never carry dashes. `short` is one character, written `-f` (POSIX guideline 3), and `long` a
    word, written `--force` (GNU getopt_long), so the parameter decides the shape, not the string. A word command
    keeps today's API, `command("add") { ... }` (`git add`); a flag-spelled command takes `short` and `long` (pacman's
    `-S` and `--sync`). find's single-dash predicates (`-name`) stay outside, as today (`Find.kt:93`).
  - 48b: a command puts `help` and `prio` in its block, which already holds its inputs and action; a flag, option or
    operand takes `short`, `long` and `help` as named parameters and keeps today's chain for the value's type and
    shape (`option(short = "l", long = "level", help = "log level").int().range(0..3).default(1)`), the shape Clikt
    uses. Named parameters also settle 47c: `command(short = "S", long = "sync") { }` and `command("add") { }`.
  - 48c: a short is a `Char`, so the one-character rule is a type and no dash can be missing or extra. Two
    signatures, neither nullable: `flag(short: Char, long: String, help)` for one of each, and `flag(short:
    List<Char> = emptyList(), long: List<String> = emptyList(), help)` for several or for only one kind (cp's
    `flag(short = listOf('R', 'r'), long = listOf("recursive"))`, a long-only `flag(long = listOf("force"))`).
  - 48d: it replaces the dash-carrying shape in one go, released as klap 0.6.0; before 1.0 the break is fine, so no
    deprecation period. klap's tests, the fixtures and the guide move in the same change; kotoml (on 0.5.3) moves
    when it takes 0.6.0.
  - 48e (owner, 2026-10-10, A): one spelling of a kind is a value and several are a list, on every spelled declaration
    (`flag`, `option`, `globalFlag`, `globalOption`, `.negatable`, a flag-spelled `command`): `flag(long = "needed")`,
    `flag(short = 'Z')`, `flag(short = listOf('r', 'R'), long = "recursive")`. The order stays `short`, `long`;
    `argument` keeps its name.
  - 48f (owner, 2026-10-10, A): a long-only flag-spelled command keeps its list, `command(long = listOf("sync")) { }`:
    `command(long: String)` would clash with the word form `command(name: String)` ("Conflicting overloads").
  **Verdict 2026-10-10:** done in klap 0.6.0. `flag`, `option`, `globalFlag`, `globalOption` and `.negatable` take
  `short: Char, long: String` or `short: List<Char>, long: List<String>` (both defaulting to `emptyList()`), the primary
  being the first long, else the first short; a long carrying a dash or an `=` is refused. A command's `help` and `prio`
  go in its block (`description` became `help`, and `command(name, help) { }` is gone). An explicit negation still
  replaces the generated `--no-<long>`. Every call site moved (klap tests, fixtures, guide, README); four klap tests that
  had passed only because their spelling was malformed now test what they name (`BuiltinsOptOutTest`). `AuthorTest`
  `a long written with its dashes is rejected and names the bare word`.
- [x] **47. A command answers ahead of the line (`prio`), and a command can be spelled as a flag (pacman's `-S`).**
  Proposed by the owner 2026-10-10, being decided one by one:
  - 36b: `command("license") { prio = true; action { ... } }` puts a command in the first bucket with `--help` and
    `--version`: given, it answers ahead of the parent's required inputs and of the second bucket, and against the
    first bucket the line's first one answers.
  - The flag spelling of a command (`-S`, `--sync`) is the route to mode-as-flag scoping (group 3: items 4, 10,
    11, 12, 44 and the `-Q` half of 42), since each operation's options then belong to its own command.
  - With both, klap's own `--help`, `--version`, `completion` and `docs` can become ordinary commands inside, and
    the special cases for them folded.
  - 47f: beside a `prio` command, `--help` keeps its usual meaning in either position: `mytool license --help` and
    `mytool --help license` both show license's help, as `--help remote` does today. `prio` decides only what the
    command beats: its parent's required inputs and the second bucket. `mytool license` runs without asking for a
    required global. This narrows 36b: a prio command does not compete with `--help` and `--version` by order.
  - 47g (owner, 2026-10-10, A): the line's rearranging, which already moves a flag-spelled command to where its
    word would stand, also moves a prio command to the front of its parent's segment; the behaviour stays as 47f.
  - 47a, 47b, 47c: a shorthand makes a command flag-spelled, reached as `-S` and `--sync` and no longer by its word
    (`pacman sync` is "no operation specified"); its declaration is item 48's `command(short = 'S', long =
    "sync") { }`, a word command staying `command("add") { }`.
  - 47d: a flag-spelled command may stand anywhere among the options. A global binds wherever it stands; the
    command's own options bind once the line names the command, before or after it (`pacman -i -Q pacman` exits 0
    and prints the info); two flag-spelled commands of one parent on a line are refused.
  - 47e: a short cluster holding a command's short is read as one set: the command letter picks the command wherever
    it stands in the cluster (`pacman -iQ pacman` prints the info), the other letters are that command's options
    or globals, and a value-taking letter takes the rest of the cluster, as today. Needs heavy testing with globals,
    the command's own flags and a subcommand's flags in one cluster: matrix K7, rows K7y onward.
  **Verdict 2026-10-10:** done in klap core. `command(short = 'Q', long = "query") { }` declares a flag-spelled
  command; parse and completion move it to where its word would stand before the walk (`FlagCommands.kt`), so it
  binds anywhere among the options and in clusters; help and docs list it as `-Q, --query`. `prio = true` per 47f and
  47g: the same rearranging moves a prio command to the front of its parent's segment, and it runs without the parent's
  required globals and ahead of `--completion` and `--docs`. `FlagCommandTest` pins K7y to K7ah, `PrioCommandTest` the
  prio rules. pacman still declares its operations as flags; moving it is the mode-scoping work (items 4, 5, 10, 11,
  12, 44), and folding klap's own built-ins into commands is left for later.
- [x] **26. Spellings declared as separate short and long lists.**
  Proposed by the owner 2026-10-09: `flag(short = listOf('a', 'l'), long = listOf("all", "long"))` in place of
  the dash-carrying vararg (`flag("--all", "-a", "-l")`, `CommandBuilder.kt`), the same for `option`,
  `globalOption` and `globalFlag`. `numberOption` keeps its own declaration: it has no spellings, its digits are
  the value. `.negatable(short = ..., long = ...)`: no `short` means no
  short negation, no `long` means `--no-<long>` for each long, so `.negatable(short = listOf('P'))` keeps the
  generated `--no-dereference` (today a negative list replaces it, `Converters.kt` `Flag.negatable(vararg)`).
  Gains: a short is a `Char`, so the one-character rule, a missing or extra dash and a positional `help` taken as
  a spelling stop being runtime refusals. The parser does not get simpler: `HolderSpec` already splits longs,
  shorts and `negativeNames`. Open before starting: which spelling is primary (error messages, `--help` order;
  proposed the first long, else the first short); how to opt out of the generated long negation; a single-value
  form for the common case so `listOf` is not everywhere; rewriting every call site (326 in `example/*/src/main`;
  breaking the API is allowed).
  **Verdict 2026-10-10:** folded into item 48 and done with it.
- [x] **7. A choice value is matched in its own case.** `PacmanConverterTargetsTest`
  `a color value in capitals is refused`; completion counterpart in `PacmanValueCandidatesTargetsTest`. `pacman -Q
  --color=ALWAYS` gives "invalid argument 'ALWAYS' for --color". klap: `.choice()` matches any case.
  Decided 2026-10-10 (owner, 7a): `.choice()` and `.enum()` match in exact case by default, as `ls --color=ALWAYS`,
  `pacman -Q --color=ALWAYS` and `cp --backup=NUMBERED` refuse; `ignoreCase = true` opts in. Lands with 0.6.0.
  **Verdict 2026-10-10:** done in klap 0.6.0. `choiceIgnoreCase` on the spec drives the converter, prefix resolution,
  completion, the default and bare-value checks; klap's own `--color`, `completion` and `docs` values match in exact
  case too. The pacman and cp targets pass and moved to the passing classes; curl's `-X post` is now a `rejects` with
  a `differs` note.
- [x] **37. A choice value is never abbreviated.** `PacmanConverterTargetsTest` `a color value is never
  abbreviated`. `pacman -Q --color=al pacman` gives "invalid argument 'al' for --color"; klap binds `always`, and
  choice prefix resolution has no off switch. klap core.
  Decided 2026-10-10 (owner, 37a): the levels split, `enum class Abbreviation { None, LongOptions, Options, All }`.
  `LongOptions` abbreviates long names only (pacman: `--col` is `--color`, `--color=al` is refused), `Options` names
  and choice values as today (GNU `ls --color=al`), `All` subcommands too. pacman sets `LongOptions`.
  **Verdict 2026-10-10:** done in klap 0.6.0. `Abbreviation.LongOptions` abbreviates long names only; pacman sets it,
  and `a color value is never abbreviated` passes. `AbbreviationModeTest` `long options abbreviates the name but never
  its value`.
- [x] **9. `-V` prints the version.** **Verdict 2026-10-10:** `builtins { versionShort = 'V' }` (owner, 9a: any letter,
  off by default, reserved only while set) gives `--version` a short alias that prints the version alone or in a
  cluster, shows in `--help` as `-V, --version` and in completion; it needs a `version`. pacman sets it and drops its
  own `-V` flag. `BuiltinsTest` `a version short prints the version alone or in a cluster` and `PacmanProjectionTest`
  `-V prints the version` pass. `pacman -QV --help` prints help in real pacman, which is item 36 (will not do), so that
  cell moved to its test.
- [x] **45. `--help` and `--version` are described in pacman's words.** **Verdict 2026-10-10:** `builtins { }` takes a
  text per built-in (`helpText`, `helpAllText`, `versionText`, `jsonText`, `colorText`, `completionText`, `docsText`;
  owner, 45a), which `--help`, the docs and completion all read; an unset one keeps klap's words. pacman words `--help`
  and `--version` as its man page. `BuiltinsTest` `a built-in is described in the words the tool gives it` and
  `PacmanSuggestionListTest` `help and version are described in pacman's own words` pass; `help and version complete
  under any operation` now expects pacman's words. The guide says it, and that `--help-all` exists only with a
  subcommand to expand.
- [x] **41. klap's built-ins pacman lacks stay out of abbreviations and suggestions.** **Verdict 2026-10-10:** the
  fixture declines `--json`, `--docs` and `--completion` (`builtins { }` could already), which also takes them out of
  the abbreviation pool: `--do` is `--downloadonly`, `--comp` and `--js` are unknown. `PacmanProjectionTest` `a prefix
  of a klap built-in pacman lacks is refused`, `a prefix shared only with a klap built-in names pacman's own option`,
  and both `a built-in pacman does not have is not offered` tests pass.
- [x] **8. Options pacman does not have are refused and not offered.** **Verdict 2026-10-10:** declined as in item 41,
  and klap has no `--help-all` in a tree with no visible subcommand (owner, 41c): not recognised, not reserved, and a
  tool may declare the name. A tree with subcommands keeps it on every command. `PacmanProjectionTest` `an option pacman
  does not have is refused` and `BuiltinsTest` `a cli with no subcommand has no help-all` pass; curl, dd, find, rsync
  and ssh pinned klap accepting `--help-all` and now each have `--help-all is refused as the real <tool> refuses it`.
- [x] **22. Options cp does not have are refused and not offered.** **Verdict 2026-10-10:** the fixture declines `-h`,
  `--json`, `--color`, `--completion` and `--docs`, and `--help-all` is gone with item 8. `CpTargetsTest` `an option cp
  does not have is refused` and `--help-all is refused` pass (still in the targets file until cp moves to the matrix
  layout). `__complete is a source file name` is will not do, as item 40. The completion counterpart waits on the
  invented `--recursive-r` and on item 45.
- [x] **35. A single-valued option given twice converts only its last value.** **Verdict 2026-10-10:** every occurrence
  is converted and the last one binds (`Bind.kt`), so an invalid earlier value refuses the line, as GNU `head -n abc -n
  5` and `ls --width abc --width 80` do. `ConvertersTest` `an invalid earlier occurrence of a single-valued option
  refuses the line` (failing on the old code) and `PacmanConverterTest` `an invalid earlier occurrence of a
  single-valued option is refused` pass; the guide no longer says the last occurrence "silently" wins.
- [x] **46. Every occurrence of an option with a bare form, and enums spelled their own way.**
  Proposed by the owner 2026-10-10, design not started. Today an option binds its last occurrence only, and
  `.optionalValue()` refuses `.multiple()` (`Converters.kt:531`), so `pacman --debug=2 --debug` cannot keep level 2
  (item 38). The idea: keep every occurrence for the action to reduce (the owner's ".discard()": the action drops
  the ones it does not want), and let `.enum<E>()` take a CLI spelling per constant (today the spelling is the
  constant's lower-cased name, so `"1"` and `"2"` cannot be values). The order between values is stated, never
  taken from declaration order (owner, 2026-10-10). A Kotlin enum's `compareTo` is final and compares ordinals, so
  an enum cannot carry its own `Comparable`; the order has to come from a key the enum declares (`val level: Int`)
  passed to klap or to the reduce (`debug().maxByOrNull { it.level }`), or the values are a type that implements
  `Comparable` itself, which klap would need a converter for.
  Converting every occurrence on that path also closes item 35. First users: items 38 and 35.
  Decided one by one with the owner 2026-10-10:
  - 46a: `.optionalValue()` combines with `.multiple()`, each occurrence binding its own value and a bare one the
    `whenBare` value, in line order: `--debug=2 --debug` binds `["2", "1"]`. The pacman fixture keeps its levels
    `1` and `2` for parity (`pacman -Q --debug=INFO` is "not a valid debug level"), while named levels are the
    normal case the enum spelling (46b) serves.
  - 46b: `.enum<E>(spelling = { it.cli })` maps a constant to its CLI spelling; the default stays the lower-cased
    name, so existing enums are unchanged and an enum needs no klap import.
  - 46c: klap adds no reducer; the action reduces the list by a key the enum declares
    (`debug().maxByOrNull { it.rank }`), never by declaration order.
  **Verdict 2026-10-10:** done as decided. `.optionalValue()` and `.multiple()` combine in either order
  (`ConvertersTest` `an optional value option keeps every occurrence in line order when it is multiple`), and
  `.enum<E>(spelling = { ... })` spells a constant its own way (`opt enum spells each constant as its spelling
  gives it`); the guide says both. Item 35 for single-valued options stays open.
- [x] **38. The highest `--debug` level given wins.** **Verdict 2026-10-10:** the fixture keeps every `--debug`
  (item 46a) and the projection takes the highest level. `PacmanConverterTest` `a lower debug level after level 2
  keeps level 2` and `a debug level binds every spelling pacman reads as a number` pass. It keeps its decimal
  converter, not an enum, since pacman reads `01` and `+2` as numbers.
- [x] **1. A short cluster offers a member of an exclusive set it already holds one of.** **Verdict 2026-10-10:** a
  member typed after the cursor, or in the short cluster being continued, rules the other members out
  (`render/Completion.kt`, the flag-name branch); a long name being typed does not count yet. The member already given
  stays offered, as klap's design keeps it (a repeat is legal, as `tar -c -c` is), which is item 6.
  `CompletionLineRulesTest` `a cluster holding one member of an exclusive set does not continue with another` and `a
  member typed after the cursor rules the others out`, and `PacmanLineContextTest` `an operation after the cursor keeps
  every other operation out` pass; its `--sy` cells became `--syn`, since pacman finds `--sy` ambiguous.
- [x] **32. A word after `--` is still completed as an option or its value.** **Verdict 2026-10-10:** after `--` neither
  value branch reads a word as an option. `CompletionLineRulesTest` `a word after the end of options takes no option
  value` and `PacmanLineContextTest` `an option spelled after the end of options is a target and takes no value` pass.
- [x] **34. The help short ends a short cluster.** **Verdict 2026-10-10:** the cluster peel steps over klap's help short
  where it is on, as the parse does. `ShortClusterCompletionTest` `the help short continues a cluster as any flag does`
  passes. The pacman cells (`the help short alone or in a cluster continues ...`) stay a target for item 12, the page's
  letters. cp has no `-h`, so its fixture now declines it (`builtins { helpShort = false }`).
- [x] **15. A counted letter is offered again in a cluster.** **Verdict 2026-10-10:** a counted short continues a
  cluster that holds it, as often as typed. `ShortClusterCompletionTest` `a counted short continues a cluster that
  already holds it` passes. The pacman target `a counted letter is offered again in its cluster` now fails only on the
  page's wording and the contested `-s` (items 12 and 5), so it stays a target for them.
- [x] **39. An empty path is refused.** **Verdict 2026-10-10:** every path option but `--logfile` and `--arch` validates
  a non-empty value (a local `path()` in the fixture). `PacmanConverterTest` `an empty path is refused` passes.
- [x] **42. Two `-Q` modifiers pacman forbids together are refused.** **Verdict 2026-10-10:** a `validateInputs` block
  on the root refuses the 32 pairs under `-Q` only, as `MutuallyExclusive`. `PacmanProjectionTest` `two -Q modifiers
  pacman forbids together are refused` passes. The same pairs under `-S` and `-R` are still unchecked, as nothing was
  run there.
- [x] **43. Comma lists and implied flags project as the man page says.** **Verdict 2026-10-10:** the projection splits
  `--ignore`, `--ignoregroup` and `--overwrite` at commas, binds `printOnly` when `--print-format` is given and both
  sandbox parts when `--disable-sandbox` is. `PacmanConverterTest` `a comma separates the packages groups and globs of
  an upgrade option` and `PacmanProjectionTest` `--print-format implies --print`, `--disable-sandbox disables both parts
  of the sandbox` pass; the generated cells and `binds the transaction and upgrade options` now expect the implied
  fields. Rests on the man page only.
- [x] **23. Do wording-only targets stay?**
  **Verdict 2026-10-09 (owner):** they stay. Help texts and completion descriptions are part of a fixture's
  parity and are asserted in the real tool's words; error text is not asserted (a refusal is checked as a
  refusal). The cp wording targets are item 25.
- [x] **2. Whether a parity target stays red or carries `@Ignore`.**
  **Verdict 2026-10-09 (owner):** targets carry `@Ignore("TDD target: ...")`, so `check` stays green. A TDD agent
  removes the annotation and makes the test pass, or reports the test as wrong. The `fixture-parity-tests` skill
  says so.
- [x] **13. Is a counted letter offered again in a cluster?**
  **Verdict 2026-10-09 (owner):** like real pacman: a counted letter (`-Qii`, `-Qkk`, `-ss`) is offered again.
  The completion contract changed to say so; the target is item 15.
- [x] **14. The pacman completion tests split cells with a model of klap's planner.**
  **Verdict 2026-10-09 (owner):** the actual values come from the klap library, the expected ones from the real
  pacman; no test reimplements klap. `KLAP_TODAY` goes, and targets are grouped by what they test.
