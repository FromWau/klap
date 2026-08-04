package com.fromwau.klap

import com.fromwau.klap.internal.render.helpText
import com.fromwau.klap.internal.spec.shorts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandBuilderTest {

    @Test
    fun builder_registersSpecsInOrder() {
        val cmd = cli("add") {
            description = "Add a task"
            argument("text")
            option("--priority", "-p")
            flag("--done", "-d")
            action { Ok("") }
        }
        assertEquals("add", cmd.name)
        assertEquals("Add a task", cmd.description)
        assertEquals(listOf("text"), cmd.arguments.map { it.name })
        assertEquals(listOf("--priority"), cmd.options.map { it.name })
        assertEquals(listOf("--done"), cmd.flags.map { it.name })
    }

    @Test
    fun group_hasNoActionBlockAndResolvesSubcommands() {
        val cmd = cli("config") {
            command("get") { action { Ok("") } }
            command("set") { action { Ok("") } }
        }
        assertTrue(cmd.isGroup)
        assertEquals("get", cmd.subcommand("get")?.name)
        assertNull(cmd.subcommand("missing"))
    }

    @Test
    fun aliasResolves() {
        val cmd = cli("root") {
            command("scan") {
                aliases = listOf("index")
                action { Ok("") }
            }
        }
        assertEquals("scan", cmd.subcommand("index")?.name)
    }

    @Test
    fun rootAliasesAreRejectedAtBuild() {
        // aliases only make sense on command(...) subcommands; the root itself has no sibling to
        // disambiguate from, so cli(){ } rejects a root-level aliases assignment outright.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                aliases = listOf("x")
                action { Ok("") }
            }
        }
        assertTrue("root" in ex.message.orEmpty(), ex.message)
        assertTrue("aliases" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun aVariadicMayBeFollowedByRequiredPositionals() {
        // `cp SOURCE... DEST`. Only REQUIRED slots may follow — an optional one is ambiguous, and
        // NonLastVariadicTest pins that rejection alongside the binding.
        val cmd = cli("cp") {
            argument("source").multiple()
            argument("dest")
            action { Ok("") }
        }
        assertEquals(listOf("source", "dest"), cmd.arguments.map { it.name })
    }

    @Test
    fun requiredCannotFollowOptional() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                argument("a").optional()
                argument("b")
                action { Ok("") }
            }
        }
    }

    @Test
    fun requiredPositionalTurnedOptionalBySiblingCommandFailsAtBuild() {
        // "a"'s own build() ran (and passed: both were still Required) the instant command("a") { }
        // returned; "b" reaches "a"'s first argument only through a captured handle, after "a" is already
        // frozen into a Command, and relaxes it to Optional, stranding a Required argument after it.
        lateinit var first: Arg<String>
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("a") {
                    first = argument("first")
                    argument("second")
                    action { Ok("") }
                }
                command("b") {
                    first.optional()
                    action { Ok("") }
                }
            }
        }
        assertTrue(
            "required argument 'second' cannot follow an optional/default argument" in ex.message.orEmpty(),
            ex.message,
        )
    }

    @Test
    fun negatableFlagCollidingWithDeclaredNameFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                flag("--color").negatable()
                flag("--no-color")
                action { Ok("") }
            }
        }
    }

    @Test
    fun negatableFlagCollidingWithDeclaredOptionFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                flag("--color").negatable()
                option("--no-color")
                action { Ok("") }
            }
        }
    }

    @Test
    fun countAndNegatableAreMutuallyExclusive() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                flag("--verbose", "-v").negatable().count()
                action { Ok("") }
            }
        }
    }

    @Test
    fun countThenNegatableAreMutuallyExclusive() {
        // Reverse order trips the symmetric guard inside .negatable() (require(!spec.isCount)). .count()
        // returns a CountFlag with no .negatable(), so the guard is reached by reusing the Flag handle
        // after .count() has already flipped isCount on the shared spec.
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                val verbose = flag("--verbose", "-v")
                verbose.count()
                verbose.negatable()
                action { Ok("") }
            }
        }
    }

    @Test
    fun negatableGlobalFlagCollidingWithSubcommandLocalFailsAtBuild() {
        // A negatable global generates --no-color; siftGlobals would strip it before the subcommand's local
        // no-color ever sees a value, so the local is silently shadowed. Fail loudly at build instead.
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                globalFlag("--color").negatable()
                command("run") {
                    flag("--no-color")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun subcommandRedeclaringGlobalLongNameFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                globalFlag("--verbose", "-v")
                command("run") {
                    option("--verbose")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun subcommandRedeclaringGlobalShortFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                globalFlag("--verbose", "-v")
                command("run") {
                    flag("--loud", "-v")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun rootLocalOptionCollidingWithGlobalFailsAtBuild() {
        // A single-command tool whose root both declares a global and a same-named local: siftGlobals
        // would strip the token first, so the local could never receive a value. Fail loudly at build.
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                globalOption("--only")
                option("--only")
                action { Ok("") }
            }
        }
    }

    @Test
    fun localNegatableFlagNegationCollidingWithGlobalNameFailsAtBuild() {
        // "foo".negatable() generates --no-foo locally; a global also named "no-foo" would be stripped
        // by siftGlobals before the local negation ever sees a value.
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                globalFlag("--no-foo")
                command("run") {
                    flag("--foo").negatable()
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun globalNegatableFlagNegationCollidingWithAnotherGlobalNameFailsAtBuild() {
        // A global-vs-global self-collision: "foo".negatable() generates --no-foo among the globals
        // themselves, colliding with another global plainly named "no-foo".
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                globalFlag("--foo").negatable()
                globalFlag("--no-foo")
                command("run") { action { Ok("") } }
            }
        }
    }

    @Test
    fun nonCollidingLocalNegatableFlagAndGlobalStillBuild() {
        val cmd = cli("ok") {
            globalFlag("--bar")
            command("run") {
                flag("--foo").negatable()
                action { Ok("") }
            }
        }
        assertTrue(cmd.isGroup)
    }

    @Test
    fun hiddenRequiredPositionalFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                argument("secret").hidden()
                action { Ok("") }
            }
        }
    }

    @Test
    fun hiddenOptionalPositionalIsAllowed() {
        val cmd = cli("ok") {
            argument("note", help = "a note").optional().hidden()
            action { Ok("") }
        }
        assertTrue("a note" !in cmd.helpText())
    }

    @Test
    fun hiddenMandatoryVariadicPositionalFailsAtBuild() {
        // A hidden .multiple(min = 1) is just as mandatory as a hidden Required positional: the user
        // must supply at least one value for a slot they cannot see in --help.
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                argument("files").multiple(min = 1).hidden()
                action { Ok("") }
            }
        }
    }

    @Test
    fun hiddenOptionalVariadicPositionalIsAllowed() {
        val cmd = cli("ok") {
            argument("files").multiple().hidden()
            action { Ok("") }
        }
        assertTrue(cmd.arguments.single().name == "files")
    }

    @Test
    fun optionNamedJsonFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                option("--json")
                action { Ok("") }
            }
        }
    }

    @Test
    fun flagNamedHelpFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                flag("--help")
                action { Ok("") }
            }
        }
    }

    @Test
    fun globalOptionNamedVersionFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                globalOption("--version")
                command("c") { action { Ok("") } }
            }
        }
    }

    @Test
    fun colorIsAReservedOptionName() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("go") {
                    option("--color")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun optionWithReservedShortHFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                option("--thing", "-h")
                action { Ok("") }
            }
        }
    }

    @Test
    fun rootSubcommandNamedDocsFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("docs") { action { Ok("") } }
            }
        }
    }

    @Test
    fun rootSubcommandNamedCompletionFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("completion") { action { Ok("") } }
            }
        }
    }

    @Test
    fun rootSubcommandNamedCompleteFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("__complete") { action { Ok("") } }
            }
        }
    }

    @Test
    fun nestedSubcommandNamedDocsIsAllowed() {
        val cmd = cli("x") {
            command("outer") {
                command("docs") { action { Ok("") } }
            }
        }
        assertEquals("docs", cmd.subcommand("outer")?.subcommand("docs")?.name)
    }

    @Test
    fun aDigitOrDotShortBuildsAndIsRecognizedAsASpelling() {
        // A dash-led numeric token can reach these, because the parser asks the tree which shorts it
        // declares before deciding what the token is.
        val cmd = cli("x") {
            option("--level", "-1")
            flag("--verbose", "-.")
            action { Ok("") }
        }
        assertEquals(listOf("1"), cmd.options.single().shorts)
        assertEquals(listOf("."), cmd.flags.single().shorts)
    }

    @Test
    fun flagWithDashShortFailsAtBuild() {
        // A short of literal '-' generates the token '-' + '-' == '--', colliding with the
        // end-of-options sentinel, so it could never be recognized as a flag.
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                flag("--verbose", "-")
                action { Ok("") }
            }
        }
    }

    @Test
    fun optionWithSingleCharShortStillBuilds() {
        val cmd = cli("x") {
            option("--level", "-l")
            action { Ok("") }
        }
        assertEquals("--level", cmd.options.single().name)
    }

    @Test
    fun normalCliStillBuilds() {
        val cmd = cli("x") {
            option("--out", "-o")
            command("build") { action { Ok("") } }
            action { Ok("") }
        }
        assertEquals("x", cmd.name)
    }

    @Test
    fun duplicateSubcommandNameFailsAtBuild() {
        // Cli.subcommand() resolves by first match, so the second "list" would be permanently
        // unreachable while --help still lists it twice.
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                command("list") { action { Ok("") } }
                command("list") { action { Ok("") } }
            }
        }
    }

    @Test
    fun duplicateOptionLongNameFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                option("--mode")
                option("--mode")
                action { Ok("") }
            }
        }
    }

    @Test
    fun duplicateFlagLongNameFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                flag("--verbose")
                flag("--verbose")
                action { Ok("") }
            }
        }
    }

    @Test
    fun optionAndFlagSameLongNameFailsAtBuild() {
        // findFlag() is checked before findOption(), so the flag would silently shadow the option:
        // the option becomes permanently unreachable while --help still lists it.
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                option("--mode")
                flag("--mode")
                action { Ok("") }
            }
        }
    }

    @Test
    fun duplicateShortNameAmongOptionsFlagsFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                option("--mode", "-m")
                flag("--verbose", "-m")
                action { Ok("") }
            }
        }
    }

    @Test
    fun subcommandAliasCollidesWithSiblingNameFailsAtBuild() {
        // "list"'s alias "show" would silently shadow the real "show" subcommand.
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                command("list") {
                    aliases = listOf("show")
                    action { Ok("") }
                }
                command("show") { action { Ok("") } }
            }
        }
    }

    @Test
    fun subcommandAliasCollidesWithSiblingAliasFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                command("a") {
                    aliases = listOf("x")
                    action { Ok("") }
                }
                command("b") {
                    aliases = listOf("x")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun distinctSubcommandsAndNonCollidingAliasStillBuild() {
        // Distinct sibling names, a non-colliding alias, and "start" reused at a DIFFERENT nesting
        // level (root vs. under "build") must all still construct fine.
        val cmd = cli("x") {
            command("scan") {
                aliases = listOf("index")
                action { Ok("") }
            }
            command("start") { action { Ok("") } }
            command("build") {
                command("start") { action { Ok("") } }
            }
        }
        assertEquals("scan", cmd.subcommand("index")?.name)
        assertEquals("start", cmd.subcommand("start")?.name)
        assertEquals("start", cmd.subcommand("build")?.subcommand("start")?.name)
    }

    @Test
    fun subcommandAliasEqualToReservedCompletionFailsAtBuild() {
        // The alias would silently shadow the injected `completion` builtin at runtime.
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("sub") {
                    aliases = listOf("completion")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun subcommandAliasEqualToReservedDocsFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("sub") {
                    aliases = listOf("docs")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun subcommandAliasEqualToReservedCompleteFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("sub") {
                    aliases = listOf("__complete")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun subcommandNonReservedAliasStillBuildsAtRoot() {
        val cmd = cli("app") {
            command("sub") {
                aliases = listOf("alt")
                action { Ok("") }
            }
        }
        assertEquals("sub", cmd.subcommand("alt")?.name)
    }

    @Test
    fun subcommandAliasEqualToOwnNameFailsAtBuild() {
        // "foo, foo" is not a real alias, it is the same name rendered twice.
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("foo") {
                    aliases = listOf("foo")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun subcommandDuplicateAliasWithinOwnListFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("foo") {
                    aliases = listOf("x", "x")
                    action { Ok("") }
                }
            }
        }
    }

    @Test
    fun subcommandDistinctAliasesNoneEqualToOwnNameStillBuild() {
        val cmd = cli("app") {
            command("foo") {
                aliases = listOf("x", "y")
                action { Ok("") }
            }
        }
        assertEquals("foo", cmd.subcommand("x")?.name)
        assertEquals("foo", cmd.subcommand("y")?.name)
    }

    @Test
    fun subcommandAliasStartingWithDashFailsAtBuild() {
        // A dash-prefixed alias would be indistinguishable from an option token on the command line,
        // the same reason a dash-prefixed command name is rejected; aliases must run through the same
        // check ([requireValidName]). A normal alias still building is already covered by [aliasResolves]
        // and [subcommandNonReservedAliasStillBuildsAtRoot].
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("scan") {
                    aliases = listOf("-bad")
                    action { Ok("") }
                }
            }
        }
    }

    // --- construction-time validation of command names ---

    @Test
    fun blankSubcommandNameFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("") { action { Ok("") } }
            }
        }
    }

    @Test
    fun subcommandNameContainingSpaceFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("build now") { action { Ok("") } }
            }
        }
    }

    @Test
    fun subcommandNameStartingWithDashFailsAtBuild() {
        // A leading '-' would be indistinguishable from an option token on the command line.
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("-build") { action { Ok("") } }
            }
        }
    }

    @Test
    fun blankRootNameFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("") { action { Ok("") } }
        }
    }

    @Test
    fun normalAndDottedSubcommandNamesStillBuild() {
        // Dots are used deliberately elsewhere (see DocsTest's `weird.name` case) and must stay allowed.
        val cmd = cli("app") {
            command("build") { action { Ok("") } }
            command("weird.name") { action { Ok("") } }
        }
        assertEquals("build", cmd.subcommand("build")?.name)
        assertEquals("weird.name", cmd.subcommand("weird.name")?.name)
    }

    // --- construction-time validation of option/flag/argument names ---

    @Test
    fun optionWithEmptyLongNameFailsAtConstruction() {
        // A blank long name renders a broken '-z, -- <value>' help row and is ambiguous with the
        // '--' end-of-options sentinel ('--=hello' would bind as the value).
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                option("--", "-z")
                action { Ok("") }
            }
        }
    }

    @Test
    fun optionWithBlankLongNameFailsAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                option("   ")
                action { Ok("") }
            }
        }
    }

    @Test
    fun optionWithLeadingDashLongNameFailsAtConstruction() {
        // A leading '-' would be indistinguishable from an option token on the command line.
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                option("-bad")
                action { Ok("") }
            }
        }
    }

    @Test
    fun flagWithEmptyLongNameFailsAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                flag("--")
                action { Ok("") }
            }
        }
    }

    @Test
    fun argumentWithEmptyNameFailsAtConstruction() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                argument("")
                action { Ok("") }
            }
        }
    }

    @Test
    fun flagNameWithControlCharFailsAtConstruction() {
        // requireValidName rejects control chars (code in 0..0x1F or 0x7F) as a disjunct separate from
        // isWhitespace(); 0x01 is a control char but not whitespace, so this exercises that branch
        // specifically rather than the whitespace check already covered above.
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                flag("na${Char(1)}me")
                action { Ok("") }
            }
        }
    }

    @Test
    fun normalOptionAndFlagNamesStillConstruct() {
        val cmd = cli("x") {
            option("--name")
            flag("--verbose", "-v")
            action { Ok("") }
        }
        assertEquals("--name", cmd.options.single().name)
        assertEquals("--verbose", cmd.flags.single().name)
    }

    // --- reject a local option/flag on a command with no action to read it ---

    @Test
    fun actionlessRootWithLocalOptionFailsAtBuild() {
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                option("--color", "-c")
                command("build") { action { Ok("") } }
            }
        }
        assertTrue("color" in ex.message.orEmpty(), ex.message)
        assertTrue("globalOption" in ex.message.orEmpty(), ex.message)
        assertTrue("action" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun actionlessRootWithLocalFlagFailsAtBuild() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                flag("--verbose", "-v")
                command("build") { action { Ok("") } }
            }
        }
    }

    @Test
    fun hybridRootWithLocalOptionAndActionStillBuilds() {
        val cmd = cli("app") {
            option("--tint", "-c")
            command("build") { action { Ok("") } }
            action { Ok("") }
        }
        assertEquals("--tint", cmd.options.single().name)
    }

    @Test
    fun plainDispatcherWithNoLocalOptionsStillBuilds() {
        val cmd = cli("app") {
            command("build") { action { Ok("") } }
            command("test") { action { Ok("") } }
        }
        assertTrue(cmd.isGroup)
    }

    @Test
    fun actionlessRootWithGlobalOptionInsteadOfLocalStillBuilds() {
        val cmd = cli("app") {
            globalOption("--tint", "-c")
            command("build") { action { Ok("") } }
        }
        assertTrue(cmd.isGroup)
    }

    @Test
    fun actionlessNestedCommandWithLocalOptionFailsAtBuild() {
        // The check applies at every level, not just the root.
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("outer") {
                    option("--verbose")
                    command("inner") { action { Ok("") } }
                }
            }
        }
    }

    // --- reject an action-less command that declares a positional argument ---

    @Test
    fun actionlessRootWithPositionalFailsAtBuild() {
        // The root is an action-less group (it only routes to "run"), so the declared positional's slot
        // is read as a subcommand token and could never bind; it must fail loudly instead.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                argument("x")
                command("run") { action { Ok("") } }
            }
        }
        assertTrue("x" in ex.message.orEmpty(), ex.message)
        assertTrue("action" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun actionlessNestedCommandWithPositionalFailsAtBuild() {
        // The check applies at every level, not just the root.
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("outer") {
                    argument("x")
                    command("inner") { action { Ok("") } }
                }
            }
        }
    }

    @Test
    fun actionlessRootWithNoPositionalStillBuilds() {
        val cmd = cli("app") {
            command("build") { action { Ok("") } }
            command("test") { action { Ok("") } }
        }
        assertTrue(cmd.isGroup)
    }

    // --- reject a group title that collides with a reserved section heading ---

    @Test
    fun groupTitleCollidingWithReservedSectionFailsAtBuild() {
        // The exact scenario from the spec. NOTE: an action-less root with a local flag ALSO trips
        // validateActionlessLocalOptions, so this fails at build even so; the two isolating tests below
        // exercise the reserved-section check on its own.
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                group("Global options") { flag("-f") }
                command("build") { action { Ok("") } }
            }
        }
    }

    @Test
    fun optionFlagGroupTitledLikeReservedSectionFailsAtBuild() {
        // Root has its own action, so the action-less-local guard is exempt: the reserved section
        // heading "Global options" on the flag's group is the sole reason this fails.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                group("Global options") { flag("-f") }
                action { Ok("") }
            }
        }
        assertTrue("Global options" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun subcommandGroupTitledLikeReservedSectionFailsAtBuild() {
        // Grouping a subcommand under "Commands" duplicates the built-in Commands heading in --help.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                group("Commands") {
                    command("build") { action { Ok("") } }
                }
            }
        }
        assertTrue("Commands" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun optionFlagGroupTitledExamplesFailsAtBuild() {
        // "Examples" is reserved too: `--help` emits its own "Examples:" heading whenever a command
        // has example(...) entries, so a group titled "Examples" would render a duplicate heading.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") {
                group("Examples") { flag("-f") }
                action { Ok("") }
            }
        }
        assertTrue("Examples" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun nonReservedGroupTitleStillBuilds() {
        val cmd = cli("app") {
            group("Networking") {
                command("connect") { action { Ok("") } }
            }
            option("--out", "-o")
            action { Ok("") }
        }
        assertEquals("app", cmd.name)
    }
}
