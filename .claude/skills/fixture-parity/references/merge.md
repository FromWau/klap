# Running the TDD agents and merging them

## Split by owner, before spawning

Two agents editing one declaration produce a conflict that needs judgment; two agents editing different declarations
of one file merge cleanly. So split by what each item changes, and say in each prompt what it owns:

- **klap agent:** every klap core item, plus its wiring into the fixture, limited to the declarations the item names
  (chmod: only the `mode` argument and the rule beside it). It also fixes the other fixtures and klap tests a klap
  default change breaks, each checked against that fixture's real tool.
- **fixture agent:** every fixture-only item, told which declarations it must not touch.
- **Cross-dependencies:** a target that needs both halves stays ignored in both worktrees; the main session
  un-ignores it after the merge. Name such targets in both prompts so neither agent bends code to make them pass
  early. When a fixture item depends on an unbuilt klap default (chmod's `-h` before 95a), the fixture agent states
  the setting explicitly; the explicit line is harmless after the merge.

Keep to two or three agents. Each run is long, and a wider fan-out needs the user's go-ahead (warn at 10 or more).

## Worktrees

```bash
S=.claude/skills/fixture-parity/scripts
$S/worktrees.sh /home/fromml/Projects/klap /tmp/claude-1000/klap-<tool>-build klap fixture
```

Each worktree starts at HEAD with the main checkout's uncommitted files (the QA tests, the decided `todo.md`)
copied in and staged, so the agent's unstaged diff is exactly its own work. Put `<BASE>/oracle` in the brief as the
only place for real tool runs. Use a directory under `/tmp/claude-1000/`, not the session scratchpad: a long build
can outlive it.

## Merge

```bash
B=/tmp/claude-1000/klap-<tool>-build
$S/agent-patch.sh $B/wt-klap $B/klap.patch
$S/agent-patch.sh $B/wt-fixture $B/fixture.patch
$S/worktrees.sh /home/fromml/Projects/klap $B merge          # a third worktree with the same baseline staged
git -C $B/wt-merge commit -q -m baseline-tmp                 # so a conflict's "base" is the baseline, not HEAD
cd $B/wt-merge && git apply --3way $B/klap.patch && git apply --3way $B/fixture.patch
git -C $B/wt-merge diff --name-only --diff-filter=U          # the files with conflicts
```

Apply the klap patch first: it is the larger one, and the fixture patch's conflicts then sit in the fixture's test
files, where they are easy to read.

**Resolving a conflict.** The usual one: both agents moved tests from a `*TargetsTest` class into the passing class,
so each side deleted different tests from the targets class and added different ones at the end of the passing
class. The right result is the union of the moves: every moved test in the passing class once, none of them left in
the targets class, one targets class header (or none, if it is now empty). After resolving, check that no test name
appears twice (`grep -o 'fun \`[^\`]*\`' file | sort | uniq -d`) and no conflict marker is left.

**Finishing what neither could.** Un-ignore the cross-dependency targets, run the module, and move each that passes.
Read both reports' "needs" sections for tests that only the merged state breaks (chmod: the wire test, broken once
completion was off) and restate them with the real tool's evidence.

**Verify.** Delete stale reports first, then run the full check and count:

```bash
/usr/bin/find . -path '*/build/test-results' -type d -prune -exec rm -rf {} +   # in the merge worktree
./gradlew --offline check --continue -q
python3 $S/test-counts.py $B/wt-merge
```

The fixture should show 0 skipped except its records; the total should be the agents' counts combined.

**Copy to main.** `$S/copy-merge.sh $B/wt-merge /home/fromml/Projects/klap` copies every changed file and compares
the merge's diff with main's; it must print "main matches the merge". Then remove the worktrees
(`git worktree remove --force` for each `$B/wt-*`).

## Pitfalls met so far

- `git apply --3way` stages what it applies. Reading the merge as an unstaged diff leaves most of it behind;
  `copy-merge.sh` reads it against the revision instead.
- A worktree made with plain `git worktree add` lacks the uncommitted QA tests; agents then work on the wrong state.
- An agent's count includes only its own half. Recount the merge yourself.
- An agent's claim about another file can be wrong (R7: two classes "did not exist" but were nested in another
  file). Check a surprising claim before you repeat it.
- When the klap agent only moved its cross-dependency targets and the fixture agent restated the record, every
  fixture test file conflicts. Take the fixture worktree's test files whole (check first that the klap agent's test
  diff is only moves and removed `@Ignore` lines), then un-ignore the cross-dependency targets and move them yourself
  (rm, 2026-10-13).
- A TDD agent can find that a decided shape cannot pass a test (rm's `.enum<RootScope>()` abbreviated `=al` under
  `Abbreviation.Options`). It parks the cells as a new target; file the question as `<item>a`, walk it, and build it
  with one agent in a fresh worktree made after the merge is in main, so its baseline is the merged state.
- Your own claims in a decision can be wrong too ("nothing tested is lost" in 102 lost four wire tests). Say so in the
  report when the build shows it.
