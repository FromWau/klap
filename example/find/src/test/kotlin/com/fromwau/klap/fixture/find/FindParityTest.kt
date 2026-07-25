package com.fromwau.klap.fixture.find

import com.fromwau.klap.fixture.ParitySuite
import kotlin.test.Test

/**
 * find is the corpus's one `not-expressible` tool, so this suite is mostly divergence by design: the
 * `--`-spelled predicates are klap accepting a line real find rejects, permanently, since klap will
 * never accept a single-dash multi-character option.
 */
class FindParityTest {

    private val parity = ParitySuite(findCli())

    @Test
    fun bindsThePrePathSwitchesThatReproduceExactly() {
        // `-P`/`-L`/`-H`/`-D`/`-Olevel` are single-dash single-char, which is precisely klap's short
        // form, so these are the only find tokens spelled here exactly as find spells them.
        parity.binds("-L", "/srv", expected = NOTHING_BOUND.copy(logical = true, operand = listOf("/srv")))
        parity.binds("-H", ".", expected = NOTHING_BOUND.copy(followArgs = true, operand = listOf(".")))
        parity.binds(
            "-D", "tree,search", ".",
            expected = NOTHING_BOUND.copy(debug = listOf("tree", "search"), operand = listOf(".")),
        )
        // The attached short value, which is the only spelling real find offers for -D and -O.
        parity.binds("-Dtree", ".", expected = NOTHING_BOUND.copy(debug = listOf("tree"), operand = listOf(".")))
        parity.binds("-O3", "/srv", expected = NOTHING_BOUND.copy(optimise = 3, operand = listOf("/srv")))
        // findutils treats bare `find` as `find .`; the action supplies that default, the parser having
        // bound a genuinely empty operand list rather than failed.
        parity.binds(expected = NOTHING_BOUND)
        parity.binds(".", expected = NOTHING_BOUND.copy(operand = listOf(".")))
    }

    @Test
    fun bindsTransliteratedPredicatesRealFindHasNeverAccepted() {
        // Every line here is klap accepting what real find rejects: the predicates are respelled with two
        // dashes because klap forbids the single-dash multi-character spelling permanently. The assertions
        // pin that the ARITY and VALUE GRAMMAR of each predicate do reproduce, which is the part klap models
        // well; only the spelling, the position and the boolean role are gone.
        parity.bindsLoosely(
            ".", "--name", "*.kt", "--type", "f", "--print",
            because = "real find: unknown predicate `--name'",
            expected = NOTHING_BOUND.copy(
                namePattern = listOf("*.kt"),
                typeTest = "f",
                printAction = true,
                operand = listOf("."),
            ),
        )
        parity.bindsLoosely(
            "-L", "-O3", "/srv", "--maxdepth", "3", "--print0",
            because = "real find: unknown predicate `--maxdepth'",
            expected = NOTHING_BOUND.copy(
                logical = true,
                optimise = 3,
                maxDepth = 3,
                print0Action = true,
                operand = listOf("/srv"),
            ),
        )
        // No starting point at all: the operand list binds empty and the action applies `.`.
        parity.bindsLoosely(
            "--type", "f",
            because = "real find: unknown predicate `--type'",
            expected = NOTHING_BOUND.copy(typeTest = "f"),
        )
        // find's comma list, which `.choice()` cannot hold: its type letters are case-sensitive and
        // choice matching is not, so `d` (directory) and `D` (door) collide at construction.
        parity.bindsLoosely(
            ".", "--type", "f,l",
            because = "real find: unknown predicate `--type'",
            expected = NOTHING_BOUND.copy(typeTest = "f,l", operand = listOf(".")),
        )
        // The `+`-led token is not dash-led at all, and a `-`-led NUMERIC one is classified as a value,
        // so both of find's comparison forms survive the space spelling.
        parity.bindsLoosely(
            ".", "--size", "+1M", "--mtime", "+7",
            because = "real find: unknown predicate `--size'",
            expected = NOTHING_BOUND.copy(
                sizeTest = FindSize('+', 1, 'M'),
                mtime = FindCount('+', 7),
                operand = listOf("."),
            ),
        )
        parity.bindsLoosely(
            ".", "--size", "-100c",
            because = "real find: unknown predicate `--size'",
            expected = NOTHING_BOUND.copy(sizeTest = FindSize('-', 100, 'c'), operand = listOf(".")),
        )
        parity.bindsLoosely(
            ".", "--perm=-u+w",
            because = "real find: unknown predicate `--perm'",
            expected = NOTHING_BOUND.copy(permTest = FindPerm('-', "u+w"), operand = listOf(".")),
        )
        // Real find accepts both `-perm -u+w` and `-name -foo` verbatim (findutils 4.11.0); this pins
        // that klap's invented `--`-spelled versions take the same space form.
        parity.bindsLoosely(
            ".", "--perm", "-u+w",
            because = "real find: unknown predicate `--perm'",
            expected = NOTHING_BOUND.copy(permTest = FindPerm('-', "u+w"), operand = listOf(".")),
        )
        parity.bindsLoosely(
            ".", "--name", "-foo",
            because = "real find: unknown predicate `--name'",
            expected = NOTHING_BOUND.copy(namePattern = listOf("-foo"), operand = listOf(".")),
        )
        // A repeatable predicate collects occurrences of THAT option only, so the interleaving with any
        // other predicate is still lost.
        parity.bindsLoosely(
            ".", "--name", "a", "--name", "b",
            because = "real find: unknown predicate `--name'",
            expected = NOTHING_BOUND.copy(namePattern = listOf("a", "b"), operand = listOf(".")),
        )
        // The synonym pair klap had to declare twice, ORed back together in the action.
        parity.bindsLoosely(
            ".", "--xdev", "--mount",
            because = "real find: unknown predicate `--xdev'",
            expected = NOTHING_BOUND.copy(xdev = true, mount = true, operand = listOf(".")),
        )
        parity.bindsLoosely(
            ".", "--regextype", "posix-egrep", "--regex", ".*[.]kt",
            because = "real find: unknown predicate `--regextype'",
            expected = NOTHING_BOUND.copy(
                regextype = "posix-egrep",
                regexPattern = listOf(".*[.]kt"),
                operand = listOf("."),
            ),
        )
        // The closest expressible thing to `-exec`: one word per occurrence, no `;`/`+` terminator,
        // and the dash-led word needs the attached form.
        parity.bindsLoosely(
            ".", "--exec", "rm", "--exec=-rf", "--exec", "{}",
            because = "real find: unknown predicate `--exec'",
            expected = NOTHING_BOUND.copy(execAction = listOf("rm", "-rf", "{}"), operand = listOf(".")),
        )
    }

    @Test
    fun bindsTheRawExpressionEscapeHatch() {
        // The escape hatch works and preserves source order, which is what a hand-written recursive-descent
        // parser would need — but the `--` it requires is itself a token real find rejects, so even this is
        // klap being looser rather than a shape find has. Nothing in the tail bound to a predicate: it is
        // raw tokens, opted out of help, completion, docs and typed errors.
        parity.bindsLoosely(
            ".", "--", "-name", "*.kt", "-o", "-size", "+1M", "-print",
            because = "real find: unknown predicate `--'",
            expected = NOTHING_BOUND.copy(
                operand = listOf(".", "-name", "*.kt", "-o", "-size", "+1M", "-print"),
            ),
        )
    }

    @Test
    fun rejectsWhatRealFindRejects() {
        parity.rejects("--zzz", because = "real find: unknown predicate `--zzz'")
        parity.rejects(".", "--type", because = "real find: missing argument to `-type'")
        parity.rejects(".", "--type", "q", because = "real find: Unknown argument to -type: q")
        parity.rejects(".", "--size", "1Q", because = "real find: invalid -size type `Q'")
        parity.rejects(".", "--mtime", "x", because = "real find: invalid argument `x' to `-mtime'")
        parity.rejects(".", "--exec", because = "real find: missing argument to `-exec'")
        parity.rejects(
            "-Ox", ".",
            because = "real find: The -O option must be immediately followed by a decimal integer",
        )
        parity.rejects(
            ".", "--maxdepth", "-1",
            because = "real find: Expected a positive decimal integer argument to -maxdepth",
        )
    }

    @Test
    fun permanentDivergenceFromRealFind() {
        // klap will never accept a single-dash multi-character option, because `-name` and the cluster
        // `-n -a -m -e` are the same bytes and telling them apart would make a token's shape stop
        // determining its meaning. Every line below is real find's own spelling, and every one of them
        // dies in the short-cluster walk — permanently, unlike every other divergence in this corpus.
        parity.rejects(".", "-name", "*.kt", because = "klap non-goal: single-dash multi-char option, NOT real-find behaviour")
        parity.rejects(".", "-type", "f", because = "klap non-goal: single-dash multi-char option, NOT real-find behaviour")
        parity.rejects(".", "-maxdepth", "3", because = "klap non-goal: single-dash multi-char option, NOT real-find behaviour")
        parity.rejects(".", "-print", because = "klap non-goal: single-dash multi-char option, NOT real-find behaviour")

        // The literal command line this study targets, which real find runs and exits 0 on. It fails at
        // `unknown option '-n'` — the FIRST of its problems, not the last: the parenthesised disjunction
        // behind it has no representation in klap's model either, and that is what fixes find's verdict at
        // not-expressible rather than partly-blocked.
        parity.rejects(
            ".", "-name", "*.kt", "-type", "f", "(", "-mtime", "+7", "-o", "-size", "+1M", ")", "-print",
            because = "klap non-goal: single-dash multi-char, then the whole expression grammar",
        )
    }

    @Test
    fun knownDivergenceFromRealFind() {
        // Not a klap gap: `.range(0..3)` is this fixture's own choice, and real find takes any decimal after
        // -O. Recorded so the reject corpus does not read as if klap forced it.
        parity.rejects("-O9", ".", because = "the fixture's own .range(0..3); real find takes any decimal")
    }

    @Test
    fun acceptsSurfaceRealFindDoesNotHave() {
        // klap's short-cluster walk bundles the pre-path switches; real find matches each token whole,
        // so it reads `-LP` as one unknown predicate.
        // `lastWins` reads the order INSIDE the cluster, so the P written after the L wins.
        parity.bindsLoosely(
            "-LP", ".",
            because = "real find: unknown predicate `-LP'",
            expected = NOTHING_BOUND.copy(physical = true, operand = listOf(".")),
        )
        // The space and `=` forms of an attached-only switch.
        parity.bindsLoosely(
            "-O", "3", ".",
            because = "real find: -O must be immediately followed by a decimal",
            expected = NOTHING_BOUND.copy(optimise = 3, operand = listOf(".")),
        )
        parity.bindsLoosely(
            "--optimise=3", ".",
            because = "real find: unknown predicate `--optimise'",
            expected = NOTHING_BOUND.copy(optimise = 3, operand = listOf(".")),
        )

        // klap's own position-independent built-ins; `builtins { }` could decline json/color/
        // completion/docs/-h and free their names, but this fixture declines nothing.
        parity.bindsLoosely(
            "--json", ".",
            because = "real find: unknown predicate `--json'",
            expected = NOTHING_BOUND.copy(operand = listOf(".")),
        )
        parity.bindsLoosely(
            "--color=never", ".",
            because = "real find: unknown predicate `--color'",
            expected = NOTHING_BOUND.copy(operand = listOf(".")),
        )

        parity.shortCircuits("-h", because = "real find: unknown predicate `-h'")
        parity.shortCircuits("--help-all", because = "real find: unknown predicate `--help-all'")
        parity.shortCircuits("--completion", "bash", because = "real find: unknown predicate `--completion'")
        parity.shortCircuits("--docs", "markdown", because = "real find: unknown predicate `--docs'")
        // A starting point literally named `__complete` is unreachable: the hidden built-in wins the walk.
        parity.shortCircuits("__complete", ".", because = "real find: `__complete': No such file or directory")

        // The two built-ins find really does have, and answers the same way klap does: print and exit 0,
        // ahead of any predicate check.
        parity.shortCircuits("--help", because = "real find: prints its own usage and exits 0")
        parity.shortCircuits("--version", "--zzz", because = "real find: prints its version and exits 0")
    }
}
