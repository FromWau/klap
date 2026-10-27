package com.fromwau.klap.fixture.find

import com.fromwau.klap.Cli
import com.fromwau.klap.fixture.checkCells
import com.fromwau.klap.fixture.find.FindCompletionSupport.BUILTINS
import com.fromwau.klap.fixture.find.FindCompletionSupport.CompletionCell
import com.fromwau.klap.fixture.find.FindCompletionSupport.INVENTED_LONGS
import com.fromwau.klap.fixture.find.FindCompletionSupport.Input
import com.fromwau.klap.fixture.find.FindCompletionSupport.KLAP_LONGS
import com.fromwau.klap.fixture.find.FindCompletionSupport.PREDICATES
import com.fromwau.klap.fixture.find.FindCompletionSupport.SHORTS
import com.fromwau.klap.fixture.find.FindCompletionSupport.Value
import com.fromwau.klap.fixture.find.FindCompletionSupport.assertFind
import com.fromwau.klap.fixture.find.FindCompletionSupport.slot
import com.fromwau.klap.testing.Suggestion
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

private fun Cli.checkSlots(cells: List<CompletionCell>) = checkCells(cells) { assertFind(it) }

private fun Cli.checkSlots(vararg cells: CompletionCell) = checkSlots(cells.toList())

private val NOTHING: Set<Suggestion> = emptySet()

private val FILES: Set<Suggestion> = setOf(filesAfter())

// --- what a generated cell completes ---

private const val FIND_NAME = "a long name find has"
private const val SHARED_PREFIX = "a prefix of a find name and of a name the fixture invents"
private const val MOUNT = "--mount, in the man page's words"
private const val BUILTIN = "--help or --version, in find's help wording"
private const val UNDERSCORED = "-ignore_readdir_race, in find's spelling"
private const val INVENTED = "a long the fixture invents"
private const val KLAP_BUILTIN = "a klap built-in find lacks"
private const val ABBREVIATED = "an abbreviated predicate"
private const val PATH_VALUE = "a path value"
private const val FREE_VALUE = "a free text or number value"
private const val REGEX_TYPE = "a -regextype value"
private const val TYPE_LETTER = "a -type letter"
private const val SHORT = "one of -P -L -H -D"
private const val LEVEL = "-O and its glued level"

private fun nameSubject(typed: String): String = when {
    typed == "--mount" || typed.startsWith("--moun") -> MOUNT
    typed.startsWith("--hel") || typed.startsWith("--versio") -> BUILTIN
    typed.startsWith("--ignore_") -> UNDERSCORED
    INVENTED_LONGS.any { it.startsWith(typed) } -> SHARED_PREFIX
    else -> FIND_NAME
}

private fun valueSubject(input: Input): String = when {
    input.value == Value.Path -> PATH_VALUE
    input.name == "--regextype" -> REGEX_TYPE
    input.name == "--type" -> TYPE_LETTER
    else -> FREE_VALUE
}

/** Every generated cell, for every input of find and klap and every cursor column. */
private val CELLS: List<CompletionCell> by lazy {
    buildList {
        for (input in PREDICATES + BUILTINS) {
            val name = input.name
            val inside = name.dropLast(1)
            add(slot("K2 K5 end", "find $name|", nameSubject(name)))
            add(slot("K2 K5 words after", "find $name| --print", nameSubject(name)))
            add(slot("K2 K5 inside", "find $inside|${name.last()}", nameSubject(inside)))
            add(slot("K2 K5 start", "find |$name", FIND_NAME))
        }
        for (name in INVENTED_LONGS + KLAP_LONGS) {
            val subject = if (name in INVENTED_LONGS) INVENTED else KLAP_BUILTIN
            add(slot("K2 K5 end", "find $name|", subject))
            add(slot("K2 K5 inside", "find ${name.dropLast(1)}|${name.last()}", subject))
        }
        for (input in PREDICATES.filter { it.value != null }) {
            val name = input.name
            val subject = valueSubject(input)
            val p = input.sample
            add(slot("K8a no word", "find $name |", subject))
            add(slot("K8a no word words after", "find $name | .", subject))
            add(slot("K8a dash-led word after", "find $name | --print", subject))
            add(slot("K8a start", "find $name |${p}zz", subject))
            add(slot("K8a end", "find $name $p|", subject))
            add(slot("K8a inside", "find $name $p|zz", subject))
            add(slot("K8a words after", "find $name $p| .", subject))
            add(slot("K9 no word", "find $name=|", subject))
            add(slot("K9 end", "find $name=$p|", subject))
            add(slot("K9 inside", "find $name=$p|zz", subject))
            add(slot("K9 words after", "find $name=$p| .", subject))
            add(slot("K9 before the =", "find $name|=$p", nameSubject(name)))
            add(slot("K9 start", "find |$name=$p", FIND_NAME))
            add(slot("K40 dash-led value", "find $name -|", subject))
            add(slot("K41 the end of options as the value", "find $name --|", subject))
            val prefix = name.dropLast(2)
            add(slot("K8c K15b abbreviated", "find $prefix |", ABBREVIATED))
            add(slot("K8c K15b abbreviated", "find $prefix $p|", ABBREVIATED))
            add(slot("K15b abbreviated attached", "find $prefix=|", ABBREVIATED))
            add(slot("K15b abbreviated attached", "find $prefix=$p|", ABBREVIATED))
        }
        for (input in SHORTS) {
            val name = input.name
            add(slot("K3 K6 end", "find $name|", SHORT))
            add(slot("K3 K6 words after", "find $name| .", SHORT))
            add(slot("K3 K6 start", "find |$name", SHORT))
            add(slot("K3 K6 K7c after -L", "find -L $name|", SHORT))
        }
        add(slot("K6 end", "find -O|", LEVEL))
        add(slot("K6 words after", "find -O| .", LEVEL))
        add(slot("K6 start", "find |-O3", LEVEL))
        add(slot("K10 end", "find -O3|", LEVEL))
        add(slot("K10 inside", "find -O|3", LEVEL))
        add(slot("K10 words after", "find -O3| .", LEVEL))
        add(slot("K10 after -L", "find -L -O|", LEVEL))
    }
}

private fun all(vararg subjects: String): List<CompletionCell> = CELLS.filter { it.subject in subjects }

/**
 * What Tab completes at each slot and cursor column of a find line (K and K7 of the testing matrix, with their
 * cursor columns), checked against what real find 4.11.0 would accept there in the fixture's respelling (F1, F1a,
 * F1b in `matrix/decisions.md`): the exact suggestions with their descriptions. A hand-written cell names its K
 * row; the generated cells cover every input of find. Real
 * find's runs are quoted beside the cells and read in `FindCompletionSupport`; the records of what F1 keeps out are
 * [FindCompletionTargetsTest].
 */
class FindCompletionTest {

    private val find = findCli().cli

    // --- generated cells, every input ---

    @Test
    fun `a long name of find completes to every name it starts`() = find.checkSlots(all(FIND_NAME))

    @Test
    fun `a path value completes as a file in every form`() = find.checkSlots(all(PATH_VALUE))

    @Test
    fun `a free text or number value offers nothing in every form`() = find.checkSlots(all(FREE_VALUE))

    // --- K2 K4d K15a K20: names ---

    @Test
    fun `a long name typed in part completes to every find name it starts`() = find.checkSlots(
        // K15a: no find long abbreviates (`find . -maxd 1`: "unknown predicate `-maxd'"), so a prefix completes
        // only to whole names.
        slot("K15a", "find --re|"),
        slot("K15a inside", "find --r|e"),
        slot("K15a words after", "find --re| ."),
        slot("K15a", "find --ma|"),
        slot("K15a", "find --mi|"),
        slot("K15a", "find --e|"),
        // K4d: find's flags named no... are positive flags, with no negated or bare form beside them.
        slot("K4d", "find --no|"),
        // K20: a whole name that also starts others: `--print` beside `--print0` and `--printf`.
        slot("K20", "find --print|"),
        slot("K20 inside", "find --prin|t"),
        slot("K20 start", "find |--print"),
        slot("K20 words after", "find --print| ."),
        slot("K20", "find --regex|"),
    )

    @Test
    fun `names are not offered before a dash`() = find.checkSlots(
        slot("K2 no word", "find |", expected = FILES),
        slot("K2 no word", "find -L |", expected = FILES),
        slot("K3 start", "find |-L", expected = FILES),
        slot("K13a start", "find |--help", expected = FILES),
    )

    @Test
    fun `--help-all is not offered`() = find.checkSlots(
        // K13c: `find --help-all`: "unknown predicate `--help-all'"; klap's Help.ByCommand has none.
        slot("K13c", "find --help-a|", expected = NOTHING),
        slot("K13c", "find --help-al|l", expected = NOTHING),
    )

    // --- K8 to K10, K40 to K47: value slots ---

    @Test
    fun `a flag or an unknown long has no value to complete`() = find.checkSlots(
        // K45, K46: `find . -empty=x` and `find . -name=a` are each "unknown predicate"; F1a gives the fixture's
        // respelled predicate an = form, and a flag has none.
        slot("K45", "find --print=|", expected = NOTHING),
        slot("K45 inside", "find --print=|x", expected = NOTHING),
        slot("K45 words after", "find --print=| .", expected = NOTHING),
        slot("K46", "find --zzz=|", expected = NOTHING),
        slot("K46", "find --zzz=a|", expected = NOTHING),
        slot("K46 words after", "find --zzz=| .", expected = NOTHING),
    )

    @Test
    fun `the word after an unknown long is an operand`() = find.checkSlots(
        // K47: `find --zzz` is "unknown predicate `--zzz'"; the unknown word claims no value, as the decided K47.
        slot("K47", "find --zzz |", expected = FILES),
        slot("K47", "find --zzz sr|", expected = FILES),
        slot("K47 words after", "find --zzz | .", expected = FILES),
    )

    @Test
    fun `a value that holds = or looks like an option is that value`() = find.checkSlots(
        // K40, K41, K43: `find . -name -- -print -maxdepth 0` and `find . -name a=b -maxdepth 0` run, so -name took
        // `--` and `a=b` whole and the options went on after it.
        slot("K43", "find --name a=b|", expected = NOTHING),
        slot("K43 attached", "find --name=a=|", expected = NOTHING),
        slot("K43 attached inside", "find --name=a=|b", expected = NOTHING),
        slot("K40", "find --name -fo|", expected = NOTHING),
        slot("K40 inside", "find --name -|fo", expected = NOTHING),
        slot("K40 start", "find --name |-fo", expected = NOTHING),
        slot("K40 path", "find --newer -|", expected = FILES),
        slot("K41", "find --newer --|", expected = FILES),
        slot("K41 a name after", "find --name -- --pri|"),
    )

    // --- K12 K14 K55 K58 to K60 K79 K80: operand slots ---

    @Test
    fun `a starting point completes as a file wherever the cursor stands`() = find.checkSlots(
        slot("K12a", "find sr|"),
        slot("K12a inside", "find s|rc"),
        slot("K12a start", "find |src"),
        slot("K12a no word", "find |"),
        slot("K12a words after", "find | ."),
        slot("K12a a predicate after", "find | --print"),
        slot("K12b", "find a sr|"),
        slot("K12b inside", "find a s|rc"),
        slot("K12b start", "find a |src"),
        slot("K12b no word", "find a |"),
        slot("K12b both sides", "find a | b c"),
        slot("K12b a predicate after", "find a | --print"),
    )

    @Test
    fun `a starting point after find's own options completes as a file`() = find.checkSlots(
        // K79, K80: `find -L -P -H .`, `find -P -D tree .`, `find -O3 -L .` run; `find -` reads `-` as a path ("‘-’:
        // No such file or directory").
        slot("K79", "find -L sr|"),
        slot("K79 no word", "find -L |"),
        slot("K79 no word", "find -P -L -H |"),
        slot("K79 after -D's value", "find -D tree |"),
        slot("K79 after -D's value", "find -D tree sr|"),
        slot("K79 after a level", "find -O3 |"),
        slot("K79 words after", "find -L | ."),
        slot("K80", "find - |"),
        slot("K80", "find - fi|"),
    )

    @Test
    fun `after the end of options a word is the raw expression's`() = find.checkSlots(
        // K14a, K87: the fixture hands the words after `--` to find's expression unparsed; `find -- .` and `find
        // -L -- .` run, and a starting point there completes as a file.
        slot("K14a no word", "find -- |", expected = FILES),
        slot("K14a no word", "find -L -- |", expected = FILES),
        slot("K87", "find . -- |", expected = FILES),
        slot("K87 words after", "find . -- | -print", expected = FILES),
        slot("K14a words after", "find -- | .", expected = FILES),
    )

    @Test
    fun `the words after --help and klap's built-ins complete as find reads them`() = find.checkSlots(
        // K55, K59, K60: `find --help .` prints the help, `find . --help` too, so the words after it are still
        // find's; `find --json`, `--completion`, `--docs` and `--color` are each "unknown predicate", so the word
        // after them is a starting point (K47), `never` too.
        slot("K59", "find --help |", expected = FILES),
        slot("K59", "find --help sr|", expected = FILES),
        slot("K59", "find --version |", expected = FILES),
        slot("K59 words after", "find --help | .", expected = FILES),
        slot("K60", "find --json |", expected = FILES),
        slot("K60", "find --color never |", expected = FILES),
        slot("K55", "find --completion |", expected = FILES),
        slot("K55", "find --completion ba|", expected = FILES),
        slot("K55", "find --docs ma|", expected = FILES),
        slot("K55 words after", "find --completion | .", expected = FILES),
    )

    @Test
    fun `a starting point named like klap's completion protocol completes as a file`() = find.checkSlots(
        // K58: `find __complete`: "‘__complete’: No such file or directory", a starting point like any other.
        slot("K58", "find __c|", expected = FILES),
        slot("K58 words after", "find __c| .", expected = FILES),
    )

    // --- K7: clusters ---

    @Test
    fun `a cluster with a letter find has not offers nothing`() = find.checkSlots(
        // K7s, K7t, K29: `find -LP .` and `find -L=1 .` are "unknown predicate", and so is any other cluster.
        slot("K7s", "find -Lz|", expected = NOTHING),
        slot("K7s", "find -zL|", expected = NOTHING),
        slot("K7t", "find -L=|", expected = NOTHING),
        slot("K29", "find -5|", expected = NOTHING),
        slot("K29 words after", "find -5| .", expected = NOTHING),
    )

    @Test
    fun `a letter glued to -D is no value of it`() = find.checkSlots(
        // K7h, K10, K7g: `find -Dtree .` and `find -D=tree .` are "unknown predicate", -D's value is the next word.
        slot("K10", "find -Dt|", expected = NOTHING),
        slot("K10 inside", "find -Dt|r", expected = NOTHING),
        slot("K7g", "find -D=|", expected = NOTHING),
        slot("K7h", "find -DL|", expected = NOTHING),
    )

    @Test
    fun `a long the fixture invents is not offered`() {
        // `find --logical`, `find --physical`, `find --follow-args`, `find --debug`, `find --optimise`: each "unknown
        // predicate"; `-P -L -H -D -O` are short only.
        find.checkSlots(all(INVENTED, SHARED_PREFIX))
    }

    @Test
    fun `a klap built-in find lacks is not offered`() = find.checkSlots(
        // K13b, K13e to K13g, K53, K54: `find -h`, `find --json`, `find --color`, `find --completion`, `find --docs`
        // are each "unknown predicate", and so the word after --color is a starting point (K47).
        all(KLAP_BUILTIN) +
            slot("K13b", "find -h|", expected = NOTHING) +
            slot("K13e", "find --js|", expected = NOTHING) +
            slot("K13f", "find --col|", expected = NOTHING) +
            slot("K13g", "find --compl|", expected = NOTHING) +
            slot("K13g", "find --doc|", expected = NOTHING) +
            slot("K53", "find --color |", expected = FILES) +
            slot("K53", "find --color al|", expected = FILES) +
            slot("K53 attached", "find --color=|", expected = NOTHING),
    )

    @Test
    fun `--help and --version are described in find's words`() = find.checkSlots(
        // K13a, K13d: `find --help`: "--help display this help and exit", "--version output version information and
        // exit". Typing is completion's: `find --hel` itself is "unknown predicate".
        all(BUILTIN) +
            slot("K13a", "find --he|") +
            slot("K13a inside", "find --h|e") +
            slot("K13a words after", "find --he| .") +
            slot("K13d", "find --vers|"),
    )

    @Test
    fun `--mount is described in the man page's words`() {
        // `man find`: "-mount Ignore files on other devices."
        find.checkSlots(all(MOUNT))
    }

    @Test
    fun `--ignore_readdir_race is spelled as find spells it`() {
        // `find . -maxdepth 0 -ignore_readdir_race` runs; `find . -maxdepth 0 -ignore-readdir-race` is "unknown
        // predicate `-ignore-readdir-race'".
        find.checkSlots(all(UNDERSCORED))
    }

    @Test
    fun `an abbreviated predicate is an unknown word`() = find.checkSlots(
        // K8c, K15b, K34: `find . -maxdep 1` is "unknown predicate `-maxdep'", so an abbreviation claims no value
        // and the word after it is a starting point (K47); an ambiguous one is no different.
        all(ABBREVIATED) +
            slot("K34", "find --re |", expected = FILES) +
            slot("K34", "find --pr |", expected = FILES) +
            slot("K34 words after", "find --re | .", expected = FILES),
    )

    @Test
    fun `-D completes find's debug options in the next word`() = find.checkSlots(
        // K8b, K32: `find -D help` lists exec, opt, rates, search, stat, time, tree, all and help, and `find -D
        // tree,search .` takes a comma list; `find -D -L .` gives -D the word `-L` ("Ignoring unrecognised debug
        // flag").
        slot("K32 no word", "find -D |"),
        slot("K32 no word words after", "find -D | ."),
        slot("K32", "find -D t|"),
        slot("K32 inside", "find -D t|r"),
        slot("K32 start", "find -D |tree"),
        slot("K32 list", "find -D tree,|"),
        slot("K32 list", "find -D tree,s|"),
        slot("K40", "find -D -|", expected = NOTHING),
    )

    @Test
    fun `--regextype completes every regex type find accepts`() {
        // `find . -regextype bogus`: "valid types are ‘findutils-default’, ‘awk’, ‘ed’, ‘egrep’, ‘emacs’, ‘gnu-awk’,
        // ‘grep’, ‘posix-awk’, ‘posix-basic’, ‘posix-egrep’, ‘posix-extended’, ‘posix-minimal-basic’, ‘sed’".
        find.checkSlots(all(REGEX_TYPE))
    }

    @Test
    fun `--type completes find's type letters`() = find.checkSlots(
        // `find . -type f,d` runs, `find . -type f,f` is "Duplicate file type 'f'", `find . -type D` "not supported
        // because Solaris doors are not supported on the platform".
        all(TYPE_LETTER) +
            slot("K8a list", "find --type f,|") +
            slot("K9 list", "find --type=f,|"),
    )

    @Test
    fun `-P -L -H and -D are offered alone`() {
        // K3, K6, K7a, K7c: `find -L -L .` and `find -L -P -H .` run, `find -LP .` is "unknown predicate `-LP'", so
        // a letter is offered alone, also when given before.
        find.checkSlots(all(SHORT))
    }

    @Test
    fun `a cluster of find's letters is one unknown word`() = find.checkSlots(
        // K7a, K7b, K7e2, K7f, K7i, K7x, K36: `find -LP .`, `find -LO3 .` and `find -LL` are "unknown predicate"; the
        // word after one is a starting point (K47), as -D in it claims no value.
        slot("K7b", "find -LP|", expected = NOTHING),
        slot("K7b inside", "find -L|P"),
        slot("K7b words after", "find -LP| .", expected = NOTHING),
        slot("K7e2", "find -LL|", expected = NOTHING),
        slot("K7f", "find -LD|", expected = NOTHING),
        slot("K7f", "find -LDtree|", expected = NOTHING),
        slot("K7f", "find -LO3|", expected = NOTHING),
        slot("K7f inside", "find -LDt|ree", expected = NOTHING),
        slot("K7g", "find -LD=|", expected = NOTHING),
        slot("K7i", "find -Lh|", expected = NOTHING),
        slot("K7s inside", "find -L|z"),
        slot("K7s words after", "find -Lz |", expected = FILES),
        slot("K36 K7x", "find -LD |", expected = FILES),
        slot("K36 K7x words after", "find -LD | .", expected = FILES),
    )

    @Test
    fun `-O completes to its glued levels`() = find.checkSlots(
        // K6, K10: `find -O 3 .` and `find -O .` are "The -O option must be immediately followed by a decimal
        // integer", `find -O3 .` runs; `man find` documents levels 0 to 3, which fish offers as -O0 to -O3.
        all(LEVEL) + slot("K7g", "find -O=|", expected = NOTHING),
    )

    @Test
    fun `a bare -O leaves no word to complete`() = find.checkSlots(
        // K10: `find -O .` is "The -O option must be immediately followed by a decimal integer".
        slot("K10 alone", "find -O |", expected = NOTHING),
    )

    @Test
    fun `a dash offers every name find accepts there`() = find.checkSlots(
        // K2, K3, K7d, K14a, K29: on a dash before the starting points find takes -P -L -H -D, -O0 to -O3 and every
        // predicate; the fixture's invented longs and klap's -h --json --color --completion --docs are refused.
        slot("K3", "find -|"),
        slot("K3 inside", "find -|L"),
        slot("K3 words after", "find -| ."),
        slot("K2", "find --|"),
        slot("K2 inside", "find --|max"),
        slot("K2 inside the dashes", "find -|-print"),
        slot("K6 inside", "find -|D"),
        slot("K7b inside", "find -|LP"),
        slot("K7c inside", "find -L -|P"),
        slot("K7d", "find -L -|"),
        slot("K7d words after", "find -L -| ."),
        slot("K29 inside", "find -|5"),
        slot("K14a -- after the cursor", "find -| -- x"),
    )

    @Test
    fun `--files0-from leaves no room for a starting point`() = find.checkSlots(
        // K12d: `find . -files0-from a` is "file operands cannot be combined with -files0-from", and `find
        // -files0-from list0 -maxdepth 0` reads its starting points from the file.
        slot("K12d1", "find --files0-from x |", expected = NOTHING),
        slot("K12d2", "find | --files0-from x", expected = NOTHING),
        slot("K12d2", "find a| --files0-from x", expected = NOTHING),
        slot("K12d", "find --files0-from| .", expected = NOTHING),
        slot("K12d", "find . --files0|", expected = NOTHING),
    )

    @Test
    fun `__complete is a starting point`() {
        // K58: `find __complete` reads `__complete` as a starting point ("No such file or directory").
        find.checkSlots(slot("K58", "find __complete |", expected = FILES))
    }
}

/**
 * The completion cells of the matrix the fixture does not meet by the owner's decision, each `@Ignore`d as a record
 * of what F1 keeps out, with real find's evidence, so the difference is stated once.
 */
class FindCompletionTargetsTest {

    private val find = findCli().cli

    // --- find's own options: -P -L -H -D -O ---

    /** A record, not a target: find's order is part of its expression grammar, F1's record (todo 75). */
    @Ignore("Will not do (F1, todo 75): klap ends no option at a position, as find's expression grammar does")
    @Test
    fun `find's own options end at the first starting point`() = find.checkSlots(
        // K30, S35: `find . -L`, `find -name a -L` and `find . -print -L` are "unknown predicate `-L'", `find . -O3`
        // "unknown predicate `-O3'" and `find . -D tree` "unknown predicate `-D'", so after a starting point or a
        // predicate only predicates are offered, in the pool of `a dash offers every name find accepts there`.
        // `find . -name -L -maxdepth 0` takes -L as the pattern, still after a predicate.
        slot("K30 S35", "find . -|"),
        slot("K30", "find . --|"),
        slot("K30 S22 S35", "find -L . -|"),
        slot("K30 words after", "find a -| b"),
        slot("S35 words after", "find . -| --name x"),
        slot("K30 S35", "find --print -|"),
        slot("S35", "find - -|"),
        slot("S35", "find . --maxdepth 1 -|"),
        slot("S35", "find --name x -|"),
        slot("S11 S35", "find --name -L -|"),
        slot("K30", "find . -L|", expected = NOTHING),
        slot("K30", "find . -D|", expected = NOTHING),
        slot("K30", "find . -O|", expected = NOTHING),
        slot("K30", "find --print -P|", expected = NOTHING),
    )

    // --- operand slots ---

    /** A record, not a target: find's order is part of its expression grammar, F1's record (todo 75). */
    @Ignore("Will not do (F1, todo 75): klap ends no option at a position, as find's expression grammar does")
    @Test
    fun `a starting point after a predicate is not offered`() = find.checkSlots(
        // K12a, K42, K41, K79, S35: `find -maxdepth 1 .`, `find . -maxdepth 0 a`, `find -L . -type f a` and `find
        // -newer a a` are "paths must precede expression"; after `--name --print` or `--name --` the word is the
        // name's, then the expression goes on.
        slot("K79 S35", "find --print |", expected = NOTHING),
        slot("K79", "find --maxdepth 1 |", expected = NOTHING),
        slot("K79 attached", "find --maxdepth=1 |", expected = NOTHING),
        slot("K79", "find . --print |", expected = NOTHING),
        slot("S35", "find . --maxdepth 1 |", expected = NOTHING),
        slot("S35", "find -L . --type f |", expected = NOTHING),
        slot("S11 S35", "find --newer -L |", expected = NOTHING),
        slot("S35", "find --name x |", expected = NOTHING),
        slot("K12b", "find --name x sr|", expected = NOTHING),
        slot("K12b words after", "find --name x | .", expected = NOTHING),
        slot("K42", "find --name --print |", expected = NOTHING),
        slot("K42", "find --name --help |", expected = NOTHING),
        slot("K41 S33 S35", "find --name -- |", expected = NOTHING),
    )

    // --- records ---

    @Ignore("Will not do (F1, todo 48a): the raw expression after -- is find's single-dash grammar, not completed")
    @Test
    fun `the raw expression after -- completes find's own predicates`() {
        // K14a, K87: the fixture spells `find . -name a` as `find . -- -name a`, so the words after `--` are find's
        // own: `find . -na` would complete -name, `-name`'s pattern is free text, and `find -- --` is "unknown
        // predicate `--'". Only the names are asserted; the descriptions would be find's own.
        val lines = mapOf(
            "find . -- -na" to listOf("-name"),
            "find -- -v" to listOf("-version"),
            "find . -- -name " to emptyList(),
            "find -- -- " to emptyList(),
        )
        checkCells(lines.entries.toList()) { (line, expected) ->
            assertEquals(expected, find.suggestionsFor(line).map { it.value }, "`$line|`")
        }
    }
}
