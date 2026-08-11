package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.internal.render.helpText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * POSIX.1-2024 (IEEE Std 1003.1-2024, The Open Group Base Specifications Issue 8), XBD chapter 12
 * "Utility Conventions", section 12.2 "Utility Syntax Guidelines" — executed rather than asserted in prose.
 * https://pubs.opengroup.org/onlinepubs/9799919799.2024edition/basedefs/V1_chap12.html
 *
 * **The rule this suite enforces.** klap must never change the meaning of a command line the guidelines
 * DO define. klap's own sugar — long options, permutation, non-alphanumeric shorts, optional
 * option-arguments — may only assign meaning to input the guidelines leave undefined, which is why each
 * extension below is paired with a conforming line proving the extension did not disturb it.
 *
 * Each test quotes the guideline it executes, so a reader can check the claim against the standard
 * without leaving the file. Guidelines 1 and 2 constrain a UTILITY'S NAME, not its argument parsing, and
 * are the tool author's to keep; they have no klap behaviour to pin.
 */
class PosixConformanceTest {

    /** The shape every guideline below is exercised against: two flags, a value-taking option, operands. */
    private fun tree(): Cli = cli("util") {
        val a = flag("--all", "-a")
        val b = flag("--brief", "-b")
        val c = option("--config", "-c")
        val files = argument("file").multiple(min = 0)
        action { Ok("a=${a()} b=${b()} c=${c()} files=${files()}") }
    }

    private fun bind(vararg argv: String): String = RecordingTerminal().let { term ->
        tree().run(argv.toList().toTypedArray(), term)
        term.out.toString().trim()
    }

    // --- Guideline 3: "Each option name should be a single alphanumeric character ... Multi-digit
    // options should not be allowed." ---

    @Test
    fun guideline3_anOptionNameIsASingleCharacter() {
        // Enforced at construction: a multi-character short is rejected, naming the cluster it would read as.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("util") { flag("-ab"); action { Ok("") } }
        }
        assertTrue("cluster" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun guideline3_aSingleDigitOptionNameIsAllowed() {
        // A digit IS alphanumeric, so `curl -4` conforms; it is only MULTI-digit that the guideline rules
        // out, and klap has no multi-character short at all.
        val tree = cli("util") {
            val four = flag("-4")
            action { Ok(four().toString()) }
        }
        assertEquals("true", RecordingTerminal().let { tree.run(arrayOf("-4"), it); it.out.toString().trim() })
    }

    // --- Guideline 4: "All options should be preceded by the '-' delimiter character." ---

    @Test
    fun guideline4_everyOptionCarriesItsDelimiter() {
        // Enforced at DECLARATION since the spelling model became explicit: a name without a dash cannot
        // declare an option at all, so a klap tree has no way to offer a delimiter-less option.
        assertFailsWith<IllegalArgumentException> {
            cli("util") { flag("all"); action { Ok("") } }
        }
    }

    // --- Guideline 5: "One or more options without option-arguments, followed by at most one option that
    // takes an option-argument, should be accepted when grouped behind one '-' delimiter." ---

    @Test
    fun guideline5_optionsGroupBehindOneDelimiter() {
        assertEquals("a=true b=true c=null files=[]", bind("-ab"))
    }

    @Test
    fun guideline5_aGroupMayEndInTheOptionThatTakesAnArgument() {
        assertEquals("a=true b=true c=cfg files=[]", bind("-abc", "cfg"))
        // ...and the argument may be attached to it, the form the guideline's own examples use.
        assertEquals("a=true b=true c=cfg files=[]", bind("-abccfg"))
    }

    // --- Guideline 6: "Each option and option-argument should be a separate argument..." ---

    @Test
    fun guideline6_anOptionArgumentMayBeItsOwnArgument() {
        assertEquals("a=false b=false c=cfg files=[]", bind("-c", "cfg"))
    }

    // --- Guideline 7: "Option-arguments should not be optional." ---

    @Test
    fun guideline7_anOptionArgumentIsNeverOptionalUnlessTheToolAsksForIt() {
        // klap's default conforms: a value-taking option demands its value, and a bare occurrence is an
        // error rather than a silently-absent value.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("--config"))).error
        assertEquals(CliError.MissingOptionValue("--config"), err)
    }

    @Test
    fun guideline7_optionalValueIsTheOptInThatStepsOutsideIt() {
        // `.optionalValue()` takes THAT option outside guideline 7, knowingly — four corpus tools need the
        // shape and no conforming spelling reaches it. The guideline exists because the option cannot tell
        // its value from the next operand, so klap resolves that the only unambiguous way: the space form
        // never binds, and the operand survives. A tool that does not opt in is untouched, which is what
        // the test above pins.
        val optional = cli("ls") {
            // --color collides with klap's own built-in of the same name; free it the same way
            // BuiltinsOptOutTest does, so the option under test can use the name unchanged.
            builtins { color = false }
            val color = option("--color").optionalValue("always")
            val files = argument("file").multiple(min = 0)
            action { Ok("color=${color()} files=${files()}") }
        }
        fun run(vararg argv: String) = RecordingTerminal().let { term ->
            optional.run(argv.toList().toTypedArray(), term)
            term.out.toString().trim()
        }
        assertEquals("color=always files=[]", run("--color"))
        assertEquals("color=never files=[]", run("--color=never"))
        assertEquals("color=always files=[src]", run("--color", "src"))
    }

    // --- Guideline 8: "When multiple option-arguments are specified to follow a single option, they
    // should be presented as a single argument, using <comma> or <blank> characters to separate them." ---

    @Test
    fun guideline8_aCommaSeparatedOptionArgumentArrivesWhole() {
        // klap does not split it; the value reaches the converter intact, so a tool that wants the
        // guideline-8 shape splits it there. klap's repeated-occurrence `.multiple()` is sugar ON TOP,
        // and the line below shows it does not disturb the conforming form.
        assertEquals("a=false b=false c=x,y,z files=[]", bind("-c", "x,y,z"))
    }

    // --- Guideline 9: "All options should precede operands on the command line." ---

    @Test
    fun guideline9_aConformingLinePutsOptionsFirst() {
        assertEquals("a=true b=false c=cfg files=[f1, f2]", bind("-a", "-c", "cfg", "f1", "f2"))
    }

    @Test
    fun guideline9_permutationIsSugarAndCannotDisturbAConformingLine() {
        // EXTENSION: klap reads an option AFTER an operand, as GNU getopt does and POSIX getopt does not.
        // It is additive by construction — a line that already puts every option first has no token for
        // this rule to reach, so no conforming invocation changes meaning. The two lines below bind
        // identically, which is the whole claim.
        assertEquals("a=true b=false c=null files=[f1, f2]", bind("-a", "f1", "f2"))
        assertEquals("a=true b=false c=null files=[f1, f2]", bind("f1", "-a", "f2"))
    }

    @Test
    fun guideline9_theSwitchRestoresTheConformingReading() {
        // The opposite direction from every other extension here: this turns the GNU permutation OFF and
        // leaves the behaviour the guideline describes, where all options precede the operands.
        val strict = cli("t") {
            optionsEndAtFirstOperand = true
            val a = flag("--all", "-a")
            val files = argument("file").multiple()
            action<String>(human = { it }) { Ok("a=${a()} files=${files()}") }
        }
        assertEquals("a=true files=[f1, f2]", strict.bindText("-a", "f1", "f2"))
        assertEquals("a=false files=[f1, -a, f2]", strict.bindText("f1", "-a", "f2"))
    }

    // --- Guideline 10: "The first -- argument that is not an option-argument should be accepted as a
    // delimiter indicating the end of options. Any following arguments should be treated as operands,
    // even if they begin with the '-' character." ---

    @Test
    fun guideline10_theDelimiterEndsOptionsAndDashLedOperandsFollow() {
        assertEquals("a=true b=false c=null files=[-b, --config]", bind("-a", "--", "-b", "--config"))
    }

    @Test
    fun guideline10_theDelimiterIsNotSwallowedAsAnOptionArgument() {
        // "...that is not an option-argument": `--config --` leaves the option without a value rather
        // than binding "--" as one, so the FIRST `--` here is still the delimiter.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("--config", "--"))).error
        assertEquals(CliError.MissingOptionValue("--config"), err)
    }

    @Test
    fun guideline10_onlyTheFirstDelimiterIsStructural() {
        // A second `--` is an ordinary operand, since the first one already ended option parsing.
        assertEquals("a=false b=false c=null files=[--, x]", bind("--", "--", "x"))
    }

    /**
     * Guideline 6 gives the argument slot to the option before it and guideline 10 makes `--` the only
     * thing that ends the option arguments, so nothing else may step into that slot — klap's own
     * position-independent built-ins least of all, since a tool author never declared them.
     *
     * `getopt` gives `-e` the literal `--json`, and so does klap: `mygrep -e --json f.txt` searches for
     * the string `--json` and reads `f.txt`.
     */
    @Test
    fun guideline10_aBuiltinSpellingInAnOptionArgumentSlotIsThatOptionsValue() {
        fun tree() = cliOf("mygrep") {
            val regexp = option("--regexp", "-e")
            val files = argument("file").multiple()
            action { Ok("") }
            projection { regexp() to files() }
        }

        // The control: an ordinary dash-led token IS taken as the value, exactly as documented. It shares
        // its answer with every line below, which is the claim — the built-in spellings are not special.
        assertEquals(Ok("-x" to listOf("f.txt")), tree().parse(listOf("-e", "-x", "f.txt")))

        assertEquals(Ok("--json" to listOf("f.txt")), tree().parse(listOf("-e", "--json", "f.txt")))

        // A value-taking built-in claims two tokens when it is klap's to read; in a value slot it is one
        // ordinary string and its would-be value is an ordinary operand.
        assertEquals(
            Ok("--color" to listOf("never", "f.txt")),
            tree().parse(listOf("-e", "--color", "never", "f.txt")),
        )

        // A `--`-led token in an option-argument slot is that option's value whatever it is spelled like,
        // even a spelling that resembles a built-in's abbreviation.
        assertEquals(Ok("--js" to listOf("f.txt")), tree().parse(listOf("-e", "--js", "f.txt")))
    }

    // --- Guideline 11: "The order of different options relative to one another should not matter, unless
    // the options are documented as mutually-exclusive and such an option is documented to override any
    // incompatible options preceding it." ---

    @Test
    fun guideline11_optionOrderDoesNotMatterByDefault() {
        assertEquals(bind("-a", "-b"), bind("-b", "-a"))
    }

    @Test
    fun guideline11_lastWinsIsTheDocumentedOverrideTheGuidelineAllowsFor() {
        // The guideline's own escape clause, which is exactly what `lastWins` declares — and it documents
        // itself, since every member's help row names the set and the usage line groups it.
        fun overriding() = cli("rm") {
            val i = flag("--interactive", "-i")
            val f = flag("--force", "-f")
            lastWins(i, f)
            action { Ok(if (f()) "force" else if (i()) "interactive" else "neither") }
        }
        fun run(vararg argv: String) = RecordingTerminal().let { term ->
            overriding().run(argv.toList().toTypedArray(), term)
            term.out.toString().trim()
        }
        assertEquals("force", run("-i", "-f"))
        assertEquals("interactive", run("-f", "-i"))
        assertTrue("last of -i, -f wins" in overriding().helpText(), "the override must be documented")
    }

    @Test
    fun guideline11_theOverrideClauseCoversAValueTakingOptionToo() {
        // Still the guideline's own escape clause, not an extension: it speaks of "options", and an option
        // that takes an option-argument is one. The set documents itself, since every member's help row
        // names it.
        fun tree() = cli("head") {
            val lines = option("--lines", "-n")
            val bytes = option("--bytes", "-c")
            lastWins(lines, bytes)
            argument("file")
            action<String>(human = { it }) { Ok("n=${lines()} c=${bytes()}") }
        }
        assertEquals("n=3 c=null", tree().bindText("-c", "5", "-n", "3", "f"))
        assertTrue("last of -n, -c wins" in tree().helpText(), "the override must be documented")
    }

    // --- Guideline 12: "The order of operands may matter and position-related interpretations should be
    // determined on a utility-specific basis." ---

    @Test
    fun guideline12_operandOrderIsPreservedForTheUtilityToInterpret() {
        assertEquals("a=false b=false c=null files=[z, a, m]", bind("z", "a", "m"))
    }

    // --- Guideline 13: "For utilities that use operands to represent files ... the '-' operand should be
    // used to mean only standard input (or standard output...) or a file named -." ---

    @Test
    fun guideline13_aLoneDashIsAnOperandNotAnOption() {
        assertEquals("a=false b=false c=null files=[-]", bind("-"))
        assertEquals("a=true b=false c=null files=[-, f]", bind("-a", "-", "f"))
    }

    // --- Guideline 14: "If an argument can be identified according to Guidelines 3 through 10 as an
    // option, or as a group of options without option-arguments behind one '-' delimiter, then it should
    // be treated as such." ---

    @Test
    fun guideline14_aDashLedTokenIsAnOptionEvenWhenItIsNotDeclared() {
        // `-w` is identifiable as an option per guidelines 3 and 4,
        // so it must be treated as one rather than demoted to an operand. An undeclared one is therefore
        // an ERROR, not a filename — which is also what stops a typo binding silently.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("-w", "notes.txt"))).error
        assertEquals(CliError.UnknownOption("-w"), err)
    }

    @Test
    fun guideline14_aDashLedNumberIsAnOptionToo() {
        // The same reading applied to digits, which is why `ls -5` errors rather than binding a file
        // named "-5". `numericAlias` is sugar that gives such a token a meaning only when a tool asks
        // for one; without it, guideline 14 holds.
        val err = assertIs<Result.Error<CliError>>(tree().parse(listOf("-5"))).error
        assertEquals(CliError.UnknownOption("-5"), err)
    }

    // --- The extensions, each paired with the conforming line it must not disturb ---

    @Test
    fun extension_longOptionsDoNotDisturbTheShortFormOrTheDelimiter() {
        // `--verbose` is outside guideline 3's model entirely (POSIX option names are one character), so
        // it is sugar. It cannot collide with a conforming line: a single `-` introduces shorts, a bare
        // `--` is still the delimiter, and only `--<name>` reaches the long form.
        assertEquals("a=true b=false c=cfg files=[]", bind("--all", "--config", "cfg"))
        assertEquals("a=true b=false c=cfg files=[]", bind("-a", "-c", "cfg"))
        assertEquals("a=false b=false c=null files=[-a]", bind("--", "-a"))
    }

    @Test
    fun extension_aNonAlphanumericShortDoesNotDisturbTheAlphanumericOnes() {
        // Guideline 3 says alphanumeric; klap allows any single character, which curl needs for `-:`.
        // Sugar, and additive: it claims a character no conforming option name could have used.
        val tree = cli("util") {
            val next = flag("-:")
            val a = flag("-a")
            action { Ok("next=${next()} a=${a()}") }
        }
        fun run(vararg argv: String) = RecordingTerminal().let { term ->
            tree.run(argv.toList().toTypedArray(), term)
            term.out.toString().trim()
        }
        assertEquals("next=true a=true", run("-:", "-a"))
        assertEquals("next=false a=true", run("-a"))
    }

    @Test
    fun extension_numericAliasClaimsOnlyWhatNoDeclaredShortDoes() {
        // Multi-digit `-20` is outside guideline 3, so `numericAlias` is sugar. A DECLARED short wins,
        // so the conforming reading of `-2...` as an option cluster is never overridden by it.
        val tree = cli("head") {
            val two = flag("-2")
            val lines = option("--lines", "-n").int()
            numericAlias(lines)
            action { Ok("two=${two()} lines=${lines()}") }
        }
        fun run(vararg argv: String) = RecordingTerminal().let { term ->
            tree.run(argv.toList().toTypedArray(), term)
            term.out.toString().trim()
        }
        assertEquals("two=true lines=null", run("-2"))
        assertEquals("two=false lines=20", run("-20"))
        // ...and where the WHOLE token is a valid option group, guideline 14 puts the cluster first.
        val bothDeclared = cli("head") {
            val two = flag("-2")
            val zero = flag("-0")
            val lines = option("--lines", "-n").int()
            numericAlias(lines)
            action { Ok("two=${two()} zero=${zero()} lines=${lines()}") }
        }
        val term = RecordingTerminal()
        bothDeclared.run(arrayOf("-20"), term)
        assertEquals("two=true zero=true lines=null", term.out.toString().trim())
    }

    @Test
    fun extension_theAttachedLongValueDoesNotDisturbTheSeparateForm() {
        // `--config=cfg` is a GNU spelling; guideline 6 wants them separate. Both bind identically.
        assertEquals(bind("--config", "cfg"), bind("--config=cfg"))
    }

    @Test
    fun extension_explicitNegationSpellingsCannotDisturbAConformingLine() {
        // EXTENSION: `.negatable(vararg)` lets a short turn a flag OFF under a spelling the flag itself
        // never declared. The guidelines describe no negation at all, so `-P` is additive: it claims a
        // character no conforming option name on this flag used, and a line that only ever says `-L`,
        // never mentioning `-P`, has no token for the extension to reach. The two trees below bind that
        // conforming line identically, which is the whole claim.
        fun tree(negatable: Boolean) = cli("cp") {
            val plain = flag("--dereference", "-L")
            val deref = if (negatable) plain.negatable("-P") else plain
            argument("file")
            action<String>(human = { it }) { Ok("deref=${deref()}") }
        }
        // Anchored to a literal as well as to each other: comparing only the two results would pass
        // vacuously if `-L` stopped binding on BOTH sides.
        assertEquals("deref=true", tree(negatable = false).bindText("-L", "f"))
        assertEquals(tree(negatable = false).bindText("-L", "f"), tree(negatable = true).bindText("-L", "f"))
    }

    @Test
    fun extension_prefixAbbreviationIsSugarAndCannotDisturbAConformingLine() {
        // EXTENSION: GNU's unambiguous-prefix rule, opt-in via `abbreviation = Abbreviation.Options`. Guideline 3
        // makes an option name a single alphanumeric character, so a `--`-led long option lies outside the
        // guidelines entirely and an abbreviation of one can only ever name input they leave undefined. On
        // a tree that opts in, the first line below is the conforming one, spelled with guideline-3 shorts
        // alone, and it binds exactly what it always bound; the second reaches the same option through an
        // abbreviation, which is the only place the rule can act.
        fun tree() = cli("util") {
            abbreviation = Abbreviation.Options
            val a = flag("--all", "-a")
            val b = flag("--brief", "-b")
            val c = option("--config", "-c")
            val files = argument("file").multiple(min = 0)
            action { Ok("a=${a()} b=${b()} c=${c()} files=${files()}") }
        }
        assertEquals("a=true b=false c=cfg files=[f1]", tree().bindText("-a", "-c", "cfg", "f1"))
        assertEquals(tree().bindText("-a", "-c", "cfg", "f1"), tree().bindText("-a", "--conf", "cfg", "f1"))
    }
}
