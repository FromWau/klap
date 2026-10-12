# Filing the targets and walking the owner through them

Load the `decision-walkthrough` skill for the message format (one decision per message, Before / Change / After,
lettered options, the recommendation first). This file holds what is particular to a fixture pass.

## Filing

Add a section under **Fixture parity targets** in `todo.md`: `### <tool> (matrix pass <date>, <tool and version>)`,
opened by one paragraph (which files replaced what, the counts, that every target was seen to fail, where the
evidence came from, and the `Class: test` naming convention). Then one item per decision, numbered on from the
highest item in the file, each with:

- a bold one-line statement of what the real tool does;
- the evidence: the exact command and its output, from a throwaway directory;
- what the fixture or klap does instead;
- its targets as `Class: test`, the class without the tool prefix and `TargetsTest`;
- the layer that owns the fix: fixture, klap core, or record.

Group targets by the change that makes them pass, not by test file: chmod's 14 wording and naming targets were one
item, because one set of fixture edits passes them all. Give each klap core gap its own item, since each is its own
public decision.

## Before the first fixture item: is the gap a klap default?

A target the fixture can fix by opting out may really be a klap default that is not GNU. chmod's `-h` looked like a
fixture fix (`Help.ByCommand(short = null)`), but klap gave every CLI a `-h` for help, and no coreutils tool has one
(`cp -h`: "invalid option -- 'h'"). The owner's rule is that klap defaults follow GNU and special behaviour is an
opt-in, so the right decision was klap's default (95a), with the tools whose `-h` is help opting in. Ask this for
every target that several fixtures already opt out of, and present the default question first, as its own item
(numbered `<item>a`), saying which earlier decision it changes.

## Shaping the options

- **Show the API in code** for a klap decision, with the call as the fixture would write it.
- **Combine items that one API settles.** chmod's 99 (gather every dash-led mode word) and 100 (a refused letter is
  no mode) were one message: the predicate that gathers is the predicate that refuses.
- **A third state gets a name, never `null`.** When a value can be "given true", "given false" and "not given", the
  owner wants the states named (`enum class Dereference { Default, Follow, NoFollow }`), since a `Boolean?` makes
  `null` a sentinel. Offer the named type; build it from existing klap pieces (two flags under `lastWins`, mapped in
  the projection) before proposing new API.
- **A record is a real option.** Where the owner already decided to keep a difference (`__complete`, item 40), the
  default answer is a record, worded as the existing ones are:
  `@Ignore("Will not do (todo 40): klap reserves the first word __complete")`. The owner may still reopen it with a
  narrower switch; that is how `completion = Builtin.Off` came to free `__complete` (102).
- **A refusal that lives in a `validateInputs` lambda is invisible to Tab** (cp, item 89): only declared rules
  (`atMostOne`, `lastWins`, `absentWhen`) prune suggestions. When the refusal can be a declared rule, offer
  that (chmod, 101); otherwise the honest options are a record or new API.
- **Count the targets each option passes, and recount** before sending: an option's count is what the owner weighs.
- **Say what you could not verify** in the message (101: whether the builder accepts the rule on an `absentWhen`
  argument), and write it into the decision so the TDD agent checks it first and stops rather than improvising.

## Owner rules that recur

- klap's defaults follow GNU getopt_long and coreutils; a fixture opts in to anything special.
- Reading the environment is not a klap feature: a fixture that needs it takes a map from the test (cp's
  `environment`).
- `__complete` is reserved while completion is on; a CLI with `completion = Builtin.Off` gets the word back.
- A fixture declines klap's `--json`, `--color`, `--completion`, `--docs` where the real tool lacks them; klap keeps
  them on by default (103a: they are klap's features, not parsing rules).
- A closed set of values is always an enum (`.enum<Update>(spelling = { it.cli })`, mv's 109); `.choice` is an
  escape hatch, never the shape a decision proposes. A
  resolved value that can be "nothing" names that state (`BackupControl.None`) rather than using `null`.

## Recording

Write each answer the moment it arrives, as a sub-bullet under the item:
`  - **Owner, <date>:** <letter>. <the decision in one or two sentences, with names as they will be built>.` A
revision the owner asks for (the `null` sentinel) is recorded as the decision, with its reason in parentheses. An
answer that changes an earlier item also edits that item (95's `-h` pointed at 97's shape), and an answer that
reverses a closed item marks it (`Reversed for ... by item 102.`).
