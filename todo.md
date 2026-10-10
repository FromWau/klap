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

- [ ] **28. Completion and parsing disagree, or klap differs from getopt, at these points.**
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
- [ ] **29. The test API and the providers see something other than what reaches the shell.**
  - `suggestionsFor` skips `toCompletionLine`, so a value with a tab or newline (dropped on the wire) and a
    multi-line or blank description look different in tests than in a shell. Rows G91 to G95, V44, V45.
  - `candidate("x", "")` on an attached value replaces the option's help with nothing. Row V46.
  - One occurrence of a `.multiple()` input that fails to convert unbinds the whole list for providers. Row V69.
  - An `Error` thrown by an unrelated converter escapes the bind and stops every provider on the line. Row V92.
  - A provider reading `words` never sees the words after the cursor, though the accessors do. Row V60.
- [ ] **30. Converting and binding accept what the real tools refuse.**
  - Reproduced as item 35. A single-valued option given twice converts only its last value (`Bind.kt:131`), so
    `--width abc --width 80` binds 80; real `head`, `ls` and `dd` refuse the line. Rows P72, X55.
  - `.int()` accepts non-ASCII digits (`"١٢"`, `"１２"` give 12) and `.double()` accepts `" 1"`, `"1.5f"`,
    `"0x1p3"` and reads `1e400` as Infinity: `toIntOrNull` and `toDoubleOrNull` on the JVM, run on Kotlin 2.4.21,
    not yet through klap. Rows X14, X24, X25.
  - `TypedCli.parse` throws, with no `CliError`, when a projection reads another command's input or uses `!!` on
    an `.absentWhen()` slot. Rows P10 to P19.
  - A built-in klap adds that the tool lacks binds the same record as the line without it (`cp --json a b`);
    overlaps cp item 22. Rows P30, P2 (disputed).
- [ ] **31. The ssh fixture ends options at the host; real ssh reads them until the remote command.**
  `example/ssh/.../Ssh.kt:31` sets `optionsEndAtFirstOperand`, so the destination ends options. Real OpenSSH 10.6p1
  keeps reading them after the host and stops at the command's first word: `ssh -G web1 -p 2222` gives port 2222, `ssh
  -G web1 ls -p 2222` gives port 22 (run 2026-10-09). The fixture also declares `--ipv4` and `--ipv6` (`Ssh.kt:109`),
  and OpenSSH has no long options. Row K30; an ssh fixture pass writes the targets.

## Fixture parity targets

Each item is a failing test in an example's `*TargetsTest.kt`, written by the `fixture-parity-tests` skill from
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

### pacman (QA pass 2026-10-09, pacman 7.1.0, checked at `6f3bdce` plus working tree)

All 38 targets (22 projection, 16 completion) were confirmed to fail with their `@Ignore` removed, rechecked after
the rework that dropped the tests' model of klap (item 14). Evidence is in each test's comment; the
`--help` pages the tests read are copied into `PacmanModel.kt`.

### pacman, the matrix slices (QA pass 2026-10-10, pacman 7.1.0, checked at `2c89351` plus working tree)

Three agents wrote the cells of the testing matrix the first pass lacked, one slice each (P and X; K and K7; V, L,
S, G and E). Since 2026-10-10 the pacman tests sit in one file per matrix file, so each item names the class its
tests are in now. All 29 targets (16, 7 and 6) were confirmed to fail with their `@Ignore` removed. The klap core
ones reproduced from item 28 and 30 are items 1 and 32 to 35; the rest are below.

### cp (QA pass 2026-10-09, GNU coreutils 9.12)

The 27 targets in `example/cp/src/test/.../cp/CpTargetsTest.kt` (15) and `CpCompletionTargetsTest.kt` (12)
were confirmed to fail with their `@Ignore` removed; evidence is in each test's comment.

- [ ] **16. `-r` is a short of `--recursive`.** `CpTargetsTest` `-r is a short of --recursive`. `cp --help`:
  `-R, -r, --recursive`; the fixture's separate `--recursive-r` also makes `--rec` ambiguous. Fixture.
- [ ] **17. `-u` is `--update` without a value, and the later of the two wins.** `CpTargetsTest`
  `-u is --update without a value and the later of the two wins`. `cp --update=none -u src dst` replaced the older
  `dst`; `cp -u --update=none` left it. Fixture.
- [ ] **18. A bare or empty `--backup` keeps the control already chosen.** `CpTargetsTest`
  `a bare or empty --backup keeps the backup control already chosen`. `cp --backup=numbered --backup src dst` made
  `dst.~1~`. Fixture, or klap's `.optionalValue()`.
- [ ] **19. `--preserve` and `--context` take a value only when attached.** `CpTargetsTest` `--preserve and
  --context take their value only when attached`; completion counterpart `--preserve and --context leave a spaced
  word to the operands`. `cp --help`: `--preserve[=ATTR_LIST]`, `--context[=CTX]`. Fixture.
- [ ] **20. An attribute list holds cp's attribute names, and repeated lists add up.** `CpTargetsTest` `an
  attribute list holds only cp's attribute names or their unique prefixes`, `a repeated attribute list adds to the
  earlier one`; completion `an attribute list completes cp's attribute names after each comma`. `cp
  --preserve=bogus` is refused, `--preserve=m` reads as `mode`. Fixture.
- [ ] **21. The target-directory forms are exclusive and `-T` takes exactly two operands.** `CpTargetsTest` `a
  second target directory is refused`, `-T takes exactly a source and a destination`, `-t and -T are refused
  together`; completion `-T leaves no room for a third operand` and the `-t`/`-T` exclusion. `cp -t a -t b x`:
  "multiple target directories specified"; `cp -T a b c`: "extra operand". Fixture, plus klap core for the
  completion of a full `-T` line.
- [ ] **25. Help and completion describe cp's inputs in cp's words.** `CpTargetsTest` `the help page lists cp's
  options and nothing cp refuses`, `--version names coreutils 9 dot 12`; completion `a long name is described in
  cp's words`, `an attached choice is described by cp's help`, `--no-dereference is described as never following
  links`, `--help and --version are described in cp's words`, and the cluster test. `cp --help`, verbatim. Fixture
  for the help texts and the version; klap core for a negation's own help (`.negatable()` takes none) and for the
  wording of `--help` and `--version` (fixed in `render/Help.kt`).

## Candidate work

- [ ] **52. A pacman completion target expects fewer repeats than pacman takes.** `PacmanCompletionTest`
  `a cluster continues from the cursor with its operation's page letters` (still ignored) expects `pacman -Qii<TAB>`
  to stop offering `-i` ("a counted one once more"), but `pacman -Qiii pacman` exits 0 (pacman 7.1.0, run
  2026-10-10), and item 13 decided that a counted letter is offered again. klap offers `-Qiii`. The test needs fixing,
  not klap: once the model allows a counted letter any number of times, the target should pass.
- [ ] **53. pacman's operations are commands now: other modelling notes.** Found while moving it (2026-10-10):
  `builtins { helpAll = false }` was added so pacman can decline `--help-all`, which a tree with subcommands offers
  (item 49 folds it into `helpAll = Builtin.Off`); a line naming no operation is refused by a root
  `validateInputs` ("no operation specified"); `pacman -QQ` is refused as pacman refuses it.
- [ ] **50. A negation describes itself.** Decided by the owner 2026-10-10 (50 A): `.negatable(..., help = "...")`
  gives the negative half its own words, its own `--help` row and its own description in Tab. pacman declares
  `--noconfirm` with `--confirm` as its negation, which orders the two as pacman does (the later wins), and its record
  holds one `noConfirm`, pacman's `config->noconfirm`. Closes cp's `a negation spelling carries its own help` too.
- [ ] **49. One value per built-in instead of switches and texts.** Proposed by the owner 2026-10-10, being decided
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
- [ ] **24. Opt-in converter modules for types klap core does not convert to.**
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
- [ ] **27. An option of the root that stays before the subcommand, as git's are.**
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

## Owner calls

## Release

- [ ] **3. Merge `completion-whole-word` into `master` and publish klap.**
  `6f3bdce` holds the whole-word completion round; the public `com.fromwau.klap.testing` API, its guide section,
  the `fixture-parity-tests` skill and the pacman fixture work are still in the working tree. kotoml's `dev`
  builds against klap 0.5.3 from Maven Local until klap is published to `maven.frommhund.xyz`.

## Will not do

Decided against, with the reason, so they are not proposed again.

- **6. The operation given twice is refused.** pacman refuses `-Q -Q` and `-QQ` as two operations, an artefact of how it
  records the operation; a repeated flag is harmless in a normal CLI. Owner's decision 2026-10-10. The test stays as a
  record of pacman's behaviour: `PacmanProjectionTargetsTest` `the operation given twice is refused`, ignored as will
  not do.
- **33. A `--` that an option takes as its value ends options.** getopt gives a `--` to an option that wants a value
  (`pacman -Qv --dbpath --`), but nobody types it, and `docs/guide.md:209` keeps `--` the end of options as a deliberate
  trade-off. Owner's decision 2026-10-10. The tests stay as a record, ignored as will not do:
  `PacmanCompletionTargetsTest` `names go on after a -- that was an option's value`, `PacmanLineContextTargetsTest` `an
  end of options taken as a value leaves the options open`, `PacmanProjectionTargetsTest` `a value word that is exactly
  the end of options is the value`.
- **36. Help, version and a refusal rank as pacman ranks them.** pacman's help always wins, even in a cluster (`pacman
  -hV` and `pacman -Vh` both print the usage), and an unknown option or two operations beat both. GNU tools answer
  whichever of `--help` and `--version` comes first (`ls --help --version` prints the usage, `ls --version --help` the
  version), and since 2026-10-10 klap does the same (owner, 36a): `--help`, `--help-all` and `--version` form a first
  bucket that beats `--completion` and `--docs`, and inside a bucket the line's first one answers (`BuiltinsTest` `a
  line naming two built-ins answers by bucket then by order`, `ArgvOrderInvarianceTest` `--help and --version answer
  whichever the line gives first`). pacman's order stays a quirk klap does not follow; the tests stay as a record:
  `PacmanProjectionTargetsTest` `help outranks version in either order`, `an unknown option refuses the line beside
  version`, `two operations are refused beside help`.
- **40. A first word `__complete` is an ordinary target.** klap reserves the first word `__complete` for its completion
  protocol (`docs/guide.md:833`), as other CLI frameworks reserve theirs; a target with that name is not worth a second
  protocol. Owner's decision 2026-10-10. The tests stay as a record, ignored as will not do:
  `PacmanProjectionTargetsTest` `__complete as the first word is a target`, `PacmanCompletionTargetsTest` `a first
  target named __complete is an ordinary target`.

- **klap sorts or ranks suggestions.** Tried on 2026-10-09 with kern's `fuzzyRank` (natural order, fuzzy ranking
  for providers that match on their own) and reverted the same day, owner's decision. The shells sort a
  completion list themselves (zsh, fish and bash's readline by default), so klap's order would show mostly in
  PowerShell; natural order threw away orders that mean something (`--level` with `debug, info, warn, error`,
  in Tab and in "choose from"); and ranking a provider's candidates overrides the order the provider chose. klap
  hands candidates over in the order declared or provided (`CompletionFlowTest` `suggestions keep the order they
  are declared or provided in`), and a provider that wants fuzzy matching ranks its own candidates. The same pass
  tried `fuzzyRank` for the "did you mean" hints, which made them wrong (`--he` suggested `--header` over
  `--help`, and hints appeared where nothing is close); they stay on `didYouMean`. `fuzzyRank` stays in kern.

## Done

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
