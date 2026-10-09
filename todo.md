# Open work

Numbers are never reused. A closed item keeps its place in **Done** with a verdict: what was decided or done, how
it was proved, and the date. Every item was reproduced before it was filed, except those under **Found by reading,
not reproduced**; a test-first item names the failing
test that states it, and closing it means that test passes.

## Defects

- [ ] **1. A short cluster offers a member of an exclusive set it already holds one of.**
  `klap/src/commonMain/kotlin/com/fromwau/klap/internal/render/Completion.kt:177`
  With `-Q` and `-S` declared `requireExactlyOne`, `tool -Q<TAB>` offers `-QS`, while `tool -Q -<TAB>` correctly
  leaves `-S` out: `membersRuledOutBy(sifted)` reads the words before the cursor, never the cluster under it. The
  real `pacman -QS` is refused with "only one operation may be used at a time". Found by the pacman fixture QA
  pass and reproduced 2026-10-09 at `6f3bdce` (`suggestionsFor("tool -Q")` gives `[-QS, -Qi, -Qh]`).
  The same read misses a member typed after the cursor (`Completion.kt:95`, `:177`): `pacman -‸ -S` offers `-Q`
  and every other operation, and `pacman -Q -‸` offers `-Q` again. Reproduced 2026-10-10 by
  `PacmanLineContextTargetsTest` `an operation after the cursor keeps every other operation out` and
  `an operation given before or after the cursor is not offered again` (matrix L5, L6, L22, S10).
- [ ] **32. A word after `--` is still completed as an option or its value.**
  `PacmanLineContextTargetsTest` `an option spelled after the end of options is a target and takes no value`.
  `pacman -Q -- --dbpath ‸` offers the file directive and `-- --color=al‸` offers `--color=always`, while real
  `pacman -Q -- --dbpath pacman` gives "package '--dbpath' was not found". The value branches of
  `render/Completion.kt` never check for `--` (item 28, rows L4b, L4c). klap core.
- [ ] **34. The help short ends a short cluster.** `PacmanCompletionTargetsTest` `the help short alone or in
  a cluster continues with the letters pacman reads after it`. `suggestionsFor("pacman -Qh")` gives `[]`; real
  `pacman -Qhi` prints the `-Q` page and `pacman -Qhz` is "invalid option -- 'z'", so every letter after `-h` is
  read. `firstNonFlagShort` in `render/Completion.kt` stops at `h` (item 28, rows K13b, K7i). klap core.
- [ ] **35. A single-valued option given twice converts only its last value.** `PacmanConverterTargetsTest`
  `an invalid earlier occurrence of a single-valued option is refused`. Real `pacman -Q --color=bogus
  --color=never` and `--debug=3 --debug=2` are refused; klap binds the last (`Bind.kt:131`; item 30, rows P72,
  X55). klap core.

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
  `example/ssh/.../Ssh.kt:31` sets `optionsEndAtFirstOperand`, so the destination ends options. Real OpenSSH
  10.6p1 keeps reading them after the host and stops at the command's first word: `ssh -G web1 -p 2222` gives
  port 2222, `ssh -G web1 ls -p 2222` gives port 22 (run 2026-10-09). The fixture also declares `--ipv4` and
  `--ipv6` (`Ssh.kt:109`), and OpenSSH has no long options. Row K30; an ssh fixture pass writes the targets.

## Fixture parity targets

Each item is a failing test in an example's `*TargetsTest.kt`, written by the `fixture-parity-tests` skill from
the real binary's behaviour; it carries `@Ignore("TDD target: ...")` until fixed. An agent taking an item removes
the annotation, runs the test to see it fail for the stated reason, and changes the fixture or klap until it
passes. If the test itself turns out wrong (it does not state what the real binary does), the agent does not bend
the code to it: it reports the test as needing a fix, with the evidence, and leaves the item open.

Audit of the pacman gaps, 2026-10-10, agreed with the owner. A gap is closed where a normal CLI behaves the same
way, and left where only pacman does (moved to **Will not do**: 6, 33, 36, 40, their tests ignored as will not do
rather than as targets). Order to take them in:

1. klap bugs, small and safe: 32, 1, 34, 35, and 15 (a counted short is offered again, as `-vv` needs).
2. Choices and built-ins: 7 (exact case by default or a switch) and 37 (abbreviation per choice, since GNU `ls
   --color=al` abbreviates and pacman does not); 41 and 8 (a tool can have none of klap's built-ins, and a declined
   or absent one stays out of abbreviations), 45 (a tool rewords a built-in's help), 9 (a short alias for version).
3. Mode-as-flag scoping, the one large design task: an option belongs to some members of a `requireExactlyOne`
   set, as tar's `-c`/`-x`/`-t` need too. Items 4, 10, 11, 12, 44 and the `-Q` half of 42 follow from it. Item 5
   (a letter means something else per operation) is a pacman quirk inside it, worth doing only if scoping makes
   it nearly free.
4. Fixture work, klap has what it needs: 38, 39, 42, 43.

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
- [ ] **7. A choice value is matched in its own case.** `PacmanConverterTargetsTest`
  `a color value in capitals is refused`; completion counterpart in `PacmanValueCandidatesTargetsTest`. `pacman -Q
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
- [ ] **12. Completion offers only what the operation on the line accepts.** `PacmanCompletionTargetsTest` 14 tests: `a
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
- [ ] **15. A counted letter is offered again in a cluster.** `PacmanCompletionTargetsTest` `a counted letter is offered
  again in its cluster` (14 cells). Where the operation's `--help` page shows the doubled form (`-Qii`, `-Qkk`, `-Qtt`;
  `-Scc -Sdd -Sgg -Sii -Suu -Syy`; `-Rdd -Rss`; `-Udd`; `-Dkk`; `-Fyy`), the letter is offered once more; a third time
  never. Item 13. klap core (a `.count()` short stays a continuation of a cluster that holds it).

### pacman, the matrix slices (QA pass 2026-10-10, pacman 7.1.0, checked at `2c89351` plus working tree)

Three agents wrote the cells of the testing matrix the first pass lacked, one slice each (P and X; K and K7; V, L,
S, G and E). Since 2026-10-10 the pacman tests sit in one file per matrix file, so each item names the class its
tests are in now. All 29 targets (16, 7 and 6) were confirmed to fail with their `@Ignore` removed. The klap core
ones reproduced from item 28 and 30 are items 1 and 32 to 35; the rest are below.

- [ ] **37. A choice value is never abbreviated.** `PacmanConverterTargetsTest` `a color value is never
  abbreviated`. `pacman -Q --color=al pacman` gives "invalid argument 'al' for --color"; klap binds `always`, and
  choice prefix resolution has no off switch. klap core.
- [ ] **38. `--debug` takes a decimal level, the highest given wins.** `PacmanConverterTargetsTest` `a debug
  level binds every spelling pacman reads as a number`, `a lower debug level after level 2 keeps level 2`.
  `--debug=01`, `002`, `+2` and `' 2'` are accepted, and `--debug=2 --debug=1` still prints level-2 lines; the
  fixture's `.choice("1", "2")` refuses them and its comment "takes only 1 and 2" is wrong. Fixture.
- [ ] **39. An empty path is refused.** `PacmanConverterTargetsTest` `an empty path is refused`.
  `--dbpath=`, `--root=`, `--cachedir=`, `--hookdir=`, `--gpgdir=`, `--sysroot=` give "failed to resolve path
  ''", `--config=` "config file  could not be read"; `--logfile=` and `--arch=` are accepted. Fixture.
- [ ] **41. klap's built-ins pacman lacks stay out of abbreviations and suggestions.** `PacmanProjectionTargetsTest`
  `a prefix of a klap built-in pacman lacks is refused`, `a prefix shared only with a klap built-in names pacman's
  own option`; `PacmanCompletionTargetsTest` `a built-in pacman does not have is not offered` and
  `PacmanSuggestionListTargetsTest` `a built in pacman does not have is not offered` (different cells).
  `pacman -Q --do` is `--downloadonly`, `--comp` and `--js` are unrecognized; klap finds `--do` ambiguous with
  `--docs` and resolves the other two. Extends item 8; fixture for the `builtins { }` it can decline, klap for
  `--completion` and the abbreviation pool.
- [ ] **42. Two `-Q` modifiers pacman forbids together are refused.** `PacmanProjectionTargetsTest` `two -Q
  modifiers pacman forbids together are refused` (32 pairs, both orders). `pacman -Q --search --info pacman` gives
  "invalid option: '--search' and '--info' may not be used together". The same pairs under `-S`, `-R` were not run.
  Fixture.
- [ ] **43. Comma lists and implied flags project as the man page says.** `PacmanConverterTargetsTest` `a comma
  separates the packages groups and globs of an upgrade option`; `PacmanProjectionTargetsTest` `--print-format implies
  --print`, `--disable-sandbox disables both parts of the sandbox`. Man page only, never run (`-S`/`-U` options).
  Whether the record models these is a fixture design call. Fixture.
- [ ] **44. Completion keeps to the operation's page at every cursor position.** `PacmanCompletionTargetsTest`
  `a dash inside a word or before an operation offers what a bare dash under it offers`, `a long name typed in part
  completes to the longs of the operation's page`, `a cluster continues from the cursor with its operation's page
  letters`. Item 12 at the inside, start and words-after positions: `pacman -Q --db‸path`, `pacman -Q --r‸e`
  and `pacman --sy‸ -Q` offer longs pacman refuses there.
  klap core.
- [ ] **45. `--help` and `--version` are described in pacman's words.** `PacmanSuggestionListTargetsTest` `help
  and version are described in pacman's own words`. `man pacman`: "Display syntax for the given operation",
  "Display version and exit"; klap says "Show this help", "Show the version", and no API lets a fixture reword a
  built-in. The fix breaks `PacmanCompletionTest` `help and version complete under any operation`, which pins
  klap's words (`HELP_TEXT`, `VERSION_TEXT` in `PacmanModel.kt`); update it with the fix. klap core.

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
  Open before starting (matrix X74 to X86, observed on Kotlin 2.4.21 with older kotlinx-datetime 0.7.1 and
  kotlinx-io 0.8.0): `Path("a/")` equals `Path("a")` and `//` collapses, so `.path()` loses the trailing slash
  `rsync src/` and `cp -r a/` depend on (keep the raw text too, or say it is lost); `Duration.parse` also takes
  `Infinity`, `-(1h 30m)` and `1h30m`, so decide which of them klap accepts; `Instant.parse` takes an offset of
  just `+02` and refuses `12:00Z` without seconds; whether `.uuid()` takes a UUID without dashes depends on
  which `Uuid` parse it calls.
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
- **36. Help, version and a refusal rank as pacman ranks them.** pacman reads the whole line before it acts, so help
  beats version and an error beats both; GNU tools answer `--version` as soon as they read it, as klap does. Owner's
  decision 2026-10-10. The tests stay as a record, ignored as will not do: `PacmanProjectionTargetsTest` `help outranks
  version in either order`, `an unknown option refuses the line beside version`, `two operations are refused beside
  help`.
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
