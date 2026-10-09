# Owner decisions on the matrix

The rows the owner went through one by one on 2026-10-09, with the real-tool evidence for each; every row
after K39 went in as the matrix-extension agents proposed it. One line per row: add, add (needs checking), fold,
change or drop.

## K

- K20 add
- K21 add
- K22 add
- K23 add (needs checking)
- K24 add (klap test)
- K25 add
- K26 drop (moot under todo 26, separate short and long lists)
- K27 add, plus a bash cell in RealShellCompletionTest for the : word break
- K28 add (separate from K16; numberOption stays)
- K29 add
- K30 add, real-ssh cells (options continue after the host until the command's first word); K31 folded into K30. ssh fixture ends options one word early: ssh parity target, evidence ssh -G web1 -p 2222 -> port 2222
- K32 add (tar -f, ssh -i, cp -S free text)
- K8 change: example becomes ls --format (required value); cp --backup ‸ moves to K11 as an operand slot. Evidence: cp --back numbered a b -> numbered is a source
- K33 add (ls --form lo‸, cp --back ‸ is an operand slot)
- K34 add (needs checking): expect nothing after an ambiguous name; evidence chmod --re 7 nofile -> ambiguous
- K35 add (needs checking): git remote add --ma/--f unique per command; real git accepts both; shared target with P46-P51
- K36 add
- K37 add
- K38 add: task-manager global reaches every depth; git globals after the subcommand are neither names nor value slots (real git refuses). K21 git cells flip to "not offered". Filed todo 27 (root-only option)
- K39 add (version A: real-git cells; --directory and --config invented by the fixture; "short value only in its own word" added to todo 27)
- K40 onward: owner 2026-10-09 "add them all": every remaining proposed row goes in as proposed; TDD evaluates them

### Agreed cells for K8 and K20 to K39

- K8: `ls --format lo‸` (offers long) | `ls --format l‸o` | `ls --format ‸long` | `ls --format ‸` (across, horizontal, commas, long, single-column, verbose, vertical) | `ls --format ‸ dir`. Evidence: `ls --form long -d .` lists long.
- K11 gains: `cp --backup ‸ src` is an operand slot (files), not the value. Evidence: `cp --back numbered a b` reads numbered as a source ("cp: target 'b': No such file or directory").
- K21: git cells flip to "not offered" (real git refuses globals after the subcommand, see K38); task-manager keeps `tasks list --fi‸` offering `--file`.
- K27: as proposed, plus a bash cell in `RealShellCompletionTest` (bash COMP_WORDBREAKS holds `:`).
- K28: `ssh -6‸` (-6, force IPv6 addresses only), `ls -1‸` (-1, list one file per line), `curl -4‸`, `ls -‸1`, `ls ‸-1`, `ls -1‸ dir`. Separate from K16 (numberOption).
- K29: `ls -5‸` nothing; `ls -‸5` ls's names (handed `-`); `ls -5‸ dir` nothing. Evidence: `ls -5` -> "ls: invalid option -- '5'".
- K30 (K31 folded in as its first cells): `ssh -p‸` names as usual; `ssh web1 -‸` ssh's names; `ssh web1 -p‸` offers -p; `ssh web1 ls -‸` nothing; `ssh web1 ls -l‸` nothing; `ssh web1 ls -‸l` nothing; `ssh web1 ‸-l` ssh's names; `ssh web1 -‸ /var/log` ssh's names. Evidence: `ssh -G web1 -p 2222` -> port 2222; `ssh -G web1 ls -p 2222` -> port 22. The fixture ends options at the host: an ssh parity target.
- K32: `tar -f ar‸`, `tar -f a‸r`, `tar -f ‸ar`, `tar -f ‸`, `tar -f ‸ src` all filesAfter(""); `ssh -i ‸ web1` filesAfter(""); `cp -S ‸ src dst` nothing (free text, no operand files).
- K33: `ls --form lo‸` long; `ls --fo ‸` the format choices; `ls --form ‸ dir`; `cp --back ‸ src` operand slot (optional value). Evidence: `ls --fo long -d .` accepted.
- K34: `chmod --re ‸`, `chmod --re 7‸`, `chmod --re ‸ file` offer nothing. Evidence: "chmod: option '--re' is ambiguous; possibilities: '--recursive' '--reference'".
- K35: `git remote add --ma‸` offers --master; `git remote add --ma ‸` --master's value slot (free text, not refused as ambiguous); `git remote add --f‸` offers --fetch; `git remote add --ma ‸ origin`. Evidence: `git remote add --ma main origin URL` and `git remote add --f o2 URL` accepted by real git. Same fix as P46 to P51.
- K36: `tar -cvf ar‸`, `tar -cvf a‸r`, `tar -cvf ‸ar`, `tar -cvf ‸`, `tar -cvf ‸ src` filesAfter(""); `cp -rS ‸ src dst` nothing. Evidence: `tar -cvf ar.tar src` created ar.tar.
- K37: `git -C di‸`, `git -C d‸i`, `git -C ‸dir`, `git -C ‸`, `git -C ‸ status` filesAfter("") (klap has no directory-only form, V27); `git -c ‸` nothing; `git -C dir ‸` subcommand names.
- K38: `tasks list --file ‸` filesAfter(""); `tasks list --fi‸` offers --file; `git remote add -C ‸` the `remote add` operand slot, not -C's value; `git remote add --dir‸` nothing; `git status -‸` status's own names only. Evidence: real git refuses `git remote add -C . o u`, `git status --no-pager`, `git status -c x=y`. Needs todo 27.
- K39: `git --git-dir=.g‸` filesAfter("--git-dir="); `git --git-dir=.‸g` filesAfter("--git-dir="); `git ‸--git-dir=.g` names; `git --git-dir=‸ status` filesAfter("--git-dir="); `git -Csub‸` nothing; `git --dir‸` nothing; `tasks --file=‸` filesAfter("--file="). Evidence: real git refuses `-Csub`, `--directory=sub`, `-csome.key=v`, `--config=some.key=v`, accepts `--git-dir=.git`. git has no `--directory` or `--config`: a git parity finding.
