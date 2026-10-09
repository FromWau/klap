# The testing matrix

Every fixture pass works against these tables, and its coverage report names cells by their IDs. For each input
the fixture declares, and each input the real tool has that the fixture lacks, every applicable cell is a test
(passing, or an `@Ignore("TDD target: ...")` target proven to fail without its annotation), or is marked
`n/a: <reason>`. A cell the report does not mention counts as not covered.

Two rules hold in every table. Expected values come from the real tool, actual values from the klap library
(`suggestionsFor`, `acceptSuggestion`, `tab`, `ParitySuite`); no test models klap. And the command line's
structure is what is asserted, with help and completion texts in the real tool's words; a refusal is asserted
as a refusal, never by its error text.

## The tables

Each table has its own file under `matrix/`, opening with a table of contents. Each file holds its rows with the
klap code path and fixtures under every themed table, its required cells, and a `Disputed:` line wherever a row
found later contradicts one agreed earlier; a pass writes the test for the side the real tool backs and reports
which. Rows marked "needs checking" are not confirmed yet, mostly read from klap's source, and the pass that writes
them settles them; "no fixture yet" and "klap test" rows belong in klap's own tests.

| File | Tables | What they cover |
|---|---|---|
| [`matrix/K.md`](matrix/K.md) | K, K7, the cursor columns | the slot under the cursor, short clusters, where the cursor sits |
| [`matrix/V.md`](matrix/V.md) | V | where a value's candidates come from |
| [`matrix/L-S.md`](matrix/L-S.md) | L, S | the words after the cursor, what is already on the line |
| [`matrix/G-E.md`](matrix/G-E.md) | G, E | the suggestion list, what every completion cell asserts |
| [`matrix/P.md`](matrix/P.md) | P | projection: the record a line binds, or its refusal |
| [`matrix/X.md`](matrix/X.md) | X | converters: from the typed string to the value |

[`matrix/decisions.md`](matrix/decisions.md) records which rows the owner decided one by one, and why.

A completion cell combines one K row with the V, L and S rows the required cells name (the base table in `K.md`,
additions in `V.md` and `L-S.md`), and asserts what E and G say. Read the files a pass needs, not all six at once.

## The coverage report

One row per input, plus one per input the real tool has and the fixture lacks. Each cell holds `pass`, `target`
(named in the report's list of targets) or `n/a: <reason>`.

| Input | K | V | End | Inside | Start | No word | Words after | L | S | P | X |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `--backup` | K8 K9 | V2 | pass | pass | pass | pass | pass | L1 L2 pass, L3 n/a: nothing after reads it | S1 S3 pass | P4 P8 pass | X4 X5 target |
