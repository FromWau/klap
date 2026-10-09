# Open work

Numbers are never reused. A closed item keeps its place in **Done** with a verdict: what was decided or done, how
it was proved, and the date. Every item was reproduced before it was filed; a test-first item names the failing
test that states it, and closing it means that test passes.

## Defects

- [ ] **1. A short cluster offers a member of an exclusive set it already holds one of.**
  `klap/src/commonMain/kotlin/com/fromwau/klap/internal/render/Completion.kt:177`
  With `-Q` and `-S` declared `requireExactlyOne`, `tool -Q<TAB>` offers `-QS`, while `tool -Q -<TAB>` correctly
  leaves `-S` out: `membersRuledOutBy(sifted)` reads the words before the cursor, never the cluster under it. The
  real `pacman -QS` is refused with "only one operation may be used at a time". Found by the pacman fixture QA
  pass and reproduced 2026-10-09 at `6f3bdce` (`suggestionsFor("tool -Q")` gives `[-QS, -Qi, -Qh]`).

## Fixture parity targets

Each item is a failing test in an example's `*TargetsTest.kt`, written by the `fixture-parity-tests` skill from
the real binary's behaviour; it carries `@Ignore("TDD target: ...")` until fixed. An agent taking an item removes
the annotation, runs the test to see it fail for the stated reason, and changes the fixture or klap until it
passes. If the test itself turns out wrong (it does not state what the real binary does), the agent does not bend
the code to it: it reports the test as needing a fix, with the evidence, and leaves the item open.

### pacman (QA pass 2026-10-09, pacman 7.1.0, checked at `6f3bdce` plus working tree)

All 38 targets (22 projection, 16 completion) were confirmed to fail with their `@Ignore` removed, rechecked after
the rework that dropped the tests' model of klap (item 14). Evidence is in each test's comment; the
`--help` pages the tests read are copied into `PacmanModel.kt`.

- [ ] **4. An option belongs to the operations whose page lists it.** `PacmanProjectionTargetsTest`
  `a long of another operation is refused under -Q` (and `-S`, `-R`, `-U`, `-D`, `-F`, `-T`: 7 tests) and
  `a letter of another operation is refused under ...` (7 tests). `pacman -Q --needed pacman` gives "error:
  invalid option '--needed'", `pacman -Qy` "invalid option '-y'". klap core: no way to scope an option to some
  members of a `requireExactlyOne` set.
- [ ] **5. A letter means what the operation on the line gives it.** `PacmanProjectionTargetsTest`
  `a contested letter means what -Q gives it`, `... -R gives it`. Under `-Q`, `-c -d -n -p -u` are changelog,
  deps, native, file, upgrades; under `-R`, `-c -s -u` are cascade, recursive (`-ss`), unneeded. klap core: one
  letter, one spec today.
- [ ] **6. The operation given twice is refused.** `PacmanProjectionTargetsTest`
  `the operation given twice is refused`. `pacman -QQ`, `-Q -Q`, `-Q --query`: "only one operation may be used
  at a time". klap core: a repeat of the member already given is not counted as a second member.
- [ ] **7. A choice value is matched in its own case.** `PacmanProjectionTargetsTest`
  `a color value in capitals is refused`; completion counterpart in `PacmanCompletionTargetsTest`. `pacman -Q
  --color=ALWAYS` gives "invalid argument 'ALWAYS' for --color". klap: `.choice()` matches any case.
- [ ] **8. Options pacman does not have are refused and not offered.** `PacmanProjectionTargetsTest`
  `an option pacman does not have is refused`. `pacman --json`, `--docs`, `--completion bash`, `--help-all`:
  "unrecognized option". Fixture for `--json`/`--completion`/`--docs` (`builtins { }`), klap for `--help-all`.
- [ ] **9. `-V` prints the version.** `PacmanProjectionTargetsTest` `-V prints the version`. `pacman -V`,
  `pacman -QV` print the banner and exit 0. klap core.
- [ ] **10. An operation's help lists that operation's options only.** `PacmanProjectionTargetsTest`
  `an operation's help lists that operation's options only`. `pacman -Qh` prints the `-Q` page. klap core.
- [ ] **11. An operation whose usage needs targets refuses a line without them.** `PacmanProjectionTargetsTest`
  `an operation whose usage requires targets refuses a line without them` (`-R`, `-U`). Evidence from the usage
  lines only; never run. klap core: operand arity per operation.
- [ ] **12. Completion offers only what the operation on the line accepts.** `PacmanCompletionTargetsTest`
  15 tests: `a cluster holding an operation offers no other operation` (item 1), `an option of another operation
  is not offered`, `a dash offers the operation's page and nothing pacman refuses`, `a long of another operation
  completes to nothing`, `a long on the page is described in that page's words`, `the operation already given is
  not offered again`, `another operation's name as typed completes to this operation's options`, `a long name
  completes to what an operation after the cursor accepts`, `a letter of another operation offers nothing after
  it`, `a contested letter continues by its meaning on the operation's page`, `a letter on the page continues with
  that page's letters`, `a letter of an operation continues with that operation's letters or with nothing`, `an
  operation's own cluster continues with that operation's letters`, `a cluster continues with the letters of an
  operation after the cursor`, `a color value in capitals is not offered`. `pacman -Q --ne<TAB>` offers
  `--needed` today. klap core, plus the fixture for the built-ins of item 8.
- [ ] **15. A counted letter is offered again in a cluster.** `PacmanCompletionTargetsTest`
  `a counted letter is offered again in its cluster` (14 cells). Where the operation's `--help` page shows the
  doubled form (`-Qii`, `-Qkk`, `-Qtt`; `-Scc -Sdd -Sgg -Sii -Suu -Syy`; `-Rdd -Rss`; `-Udd`; `-Dkk`; `-Fyy`), the
  letter is offered once more; a third time never. Item 13. klap core (a `.count()` short stays a continuation of
  a cluster that holds it).

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
- [ ] **22. Options cp does not have are refused and not offered.** `CpTargetsTest` `an option cp does not have
  is refused`, `--help-all is refused`, `__complete is a source file name`; completion counterpart offering cp's
  names only. Fixture (`builtins { }`) for `-h`, `--json`, `--color`, `--completion`, `--docs`; klap core for
  `--help-all` and `__complete`, which cannot be declined. Case-sensitive choices are item 7.
- [ ] **25. Help and completion describe cp's inputs in cp's words.** `CpTargetsTest` `the help page lists cp's
  options and nothing cp refuses`, `--version names coreutils 9 dot 12`; completion `a long name is described in
  cp's words`, `an attached choice is described by cp's help`, `--no-dereference is described as never following
  links`, `--help and --version are described in cp's words`, and the cluster test. `cp --help`, verbatim. Fixture
  for the help texts and the version; klap core for a negation's own help (`.negatable()` takes none) and for the
  wording of `--help` and `--version` (fixed in `render/Help.kt`).

## Candidate work

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
- [ ] **26. Spellings declared as separate short and long lists.**
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
