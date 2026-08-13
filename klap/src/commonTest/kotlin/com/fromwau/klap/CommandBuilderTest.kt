package com.fromwau.klap

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.klap.internal.render.helpText
import com.fromwau.klap.internal.spec.shorts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandBuilderTest {

    @Test
    fun `builder registers specs in order`() {
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
    fun `group has no action block and resolves subcommands`() {
        val cmd = cli("config") {
            command("get") { action { Ok("") } }
            command("set") { action { Ok("") } }
        }
        assertTrue(cmd.isGroup)
        assertEquals("get", cmd.subcommand("get")?.name)
        assertNull(cmd.subcommand("missing"))
    }

    @Test
    fun `alias resolves`() {
        val cmd = cli("root") {
            command("scan") {
                aliases = listOf("index")
                action { Ok("") }
            }
        }
        assertEquals("scan", cmd.subcommand("index")?.name)
    }

    @Test
    fun `root aliases are rejected at build`() {
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
}

/** The order rules over one command's positional slots, enforced when the command is built. */
class PositionalDeclarationOrderTest {


    @Test
    fun `a variadic may be followed by required positionals`() {
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
    fun `required cannot follow optional`() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                argument("a").optional()
                argument("b")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `required positional turned optional by sibling command fails at build`() {
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
}

/** A negatable flag's generated negative half is a declaration too, so it collides like any other name. */
class NegatableFlagDeclarationTest {


    @Test
    fun `negatable flag colliding with declared name fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                flag("--color").negatable()
                flag("--no-color")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `negatable flag colliding with declared option fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                flag("--color").negatable()
                option("--no-color")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `count and negatable are mutually exclusive`() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                flag("--verbose", "-v").negatable().count()
                action { Ok("") }
            }
        }
    }

    @Test
    fun `count then negatable are mutually exclusive`() {
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
}

/** A global and a local input may not claim the same spelling, in either declaration order. */
class GlobalAndLocalNameCollisionTest {


    @Test
    fun `negatable global flag colliding with subcommand local fails at build`() {
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
    fun `subcommand redeclaring global long name fails at build`() {
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
    fun `subcommand redeclaring global short fails at build`() {
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
    fun `root local option colliding with global fails at build`() {
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
    fun `local negatable flag negation colliding with global name fails at build`() {
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
    fun `global negatable flag negation colliding with another global name fails at build`() {
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
    fun `global option with a digit short beside a number input fails at build`() {
        // The pre-strip reads a cluster against the globals alone, so `-25` matches `-2` on the run's first
        // digit and takes `5` as its value before the command owning the number input is ever resolved,
        // where every other walk reads the number 25. Refuse the pair instead of letting them disagree.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                globalOption("--two", "-2")
                command("go") {
                    numberOption().int()
                    action { Ok("") }
                }
            }
        }
        assertTrue("digit short '-2'" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `non colliding local negatable flag and global still build`() {
        val cmd = cli("ok") {
            globalFlag("--bar")
            command("run") {
                flag("--foo").negatable()
                action { Ok("") }
            }
        }
        assertTrue(cmd.isGroup)
    }
}

/** Hiding removes a positional from help, so one the user must supply would have no way to be found. */
class HiddenPositionalTest {


    @Test
    fun `hidden required positional fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                argument("secret").hidden()
                action { Ok("") }
            }
        }
    }

    @Test
    fun `hidden optional positional is allowed`() {
        val cmd = cli("ok") {
            argument("note", help = "a note").optional().hidden()
            action { Ok("") }
        }
        assertTrue("a note" !in cmd.helpText())
    }

    @Test
    fun `hidden mandatory variadic positional fails at build`() {
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
    fun `hidden optional variadic positional is allowed`() {
        val cmd = cli("ok") {
            argument("files").multiple().hidden()
            action { Ok("") }
        }
        assertTrue(cmd.arguments.single().name == "files")
    }
}

/** The names klap injects for its own built-ins, which an app may not redeclare. */
class ReservedNameTest {


    @Test
    fun `option named json fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                option("--json")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `flag named help fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                flag("--help")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `global option named version fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                globalOption("--version")
                command("c") { action { Ok("") } }
            }
        }
    }

    @Test
    fun `color is a reserved option name`() {
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
    fun `option with reserved short h fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                option("--thing", "-h")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `root subcommand named docs fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("docs") { action { Ok("") } }
            }
        }
    }

    @Test
    fun `root subcommand named completion fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("completion") { action { Ok("") } }
            }
        }
    }

    @Test
    fun `root subcommand named complete fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                command("__complete") { action { Ok("") } }
            }
        }
    }

    @Test
    fun `nested subcommand named docs is allowed`() {
        val cmd = cli("x") {
            command("outer") {
                command("docs") { action { Ok("") } }
            }
        }
        assertEquals("docs", cmd.subcommand("outer")?.subcommand("docs")?.name)
    }
}

/** Which single characters may be a short, and how one is recognized on the line. */
class ShortNameSpellingTest {


    @Test
    fun `a digit or dot short builds and is recognized as a spelling`() {
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
    fun `flag with dash short fails at build`() {
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
    fun `option with single char short still builds`() {
        val cmd = cli("x") {
            option("--level", "-l")
            action { Ok("") }
        }
        assertEquals("--level", cmd.options.single().name)
    }

    @Test
    fun `normal cli still builds`() {
        val cmd = cli("x") {
            option("--out", "-o")
            command("build") { action { Ok("") } }
            action { Ok("") }
        }
        assertEquals("x", cmd.name)
    }
}

/** One spelling, declared twice at the same level. */
class DuplicateDeclarationTest {


    @Test
    fun `duplicate subcommand name fails at build`() {
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
    fun `duplicate option long name fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                option("--mode")
                option("--mode")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `duplicate flag long name fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                flag("--verbose")
                flag("--verbose")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `option and flag same long name fails at build`() {
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
    fun `duplicate short name among options flags fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("bad") {
                option("--mode", "-m")
                flag("--verbose", "-m")
                action { Ok("") }
            }
        }
    }
}

/** What a subcommand alias may spell, and what it may not collide with. */
class SubcommandAliasTest {


    @Test
    fun `subcommand alias collides with sibling name fails at build`() {
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
    fun `subcommand alias collides with sibling alias fails at build`() {
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
    fun `distinct subcommands and non colliding alias still build`() {
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
    fun `subcommand alias equal to reserved completion fails at build`() {
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
    fun `subcommand alias equal to reserved docs fails at build`() {
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
    fun `subcommand alias equal to reserved complete fails at build`() {
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
    fun `subcommand non reserved alias still builds at root`() {
        val cmd = cli("app") {
            command("sub") {
                aliases = listOf("alt")
                action { Ok("") }
            }
        }
        assertEquals("sub", cmd.subcommand("alt")?.name)
    }

    @Test
    fun `subcommand alias equal to own name fails at build`() {
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
    fun `subcommand duplicate alias within own list fails at build`() {
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
    fun `subcommand distinct aliases none equal to own name still build`() {
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
    fun `subcommand alias starting with dash fails at build`() {
        // A dash-prefixed alias would be indistinguishable from an option token on the command line,
        // the same reason a dash-prefixed command name is rejected; aliases must run through the same
        // check ([requireValidName]). A normal alias still building is already covered by `alias resolves`
        // and `subcommand non reserved alias still builds at root`.
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("scan") {
                    aliases = listOf("-bad")
                    action { Ok("") }
                }
            }
        }
    }
}

/** Construction-time validation of command names. */
class CommandNameValidationTest {

    @Test
    fun `blank subcommand name fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("") { action { Ok("") } }
            }
        }
    }

    @Test
    fun `subcommand name containing space fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("build now") { action { Ok("") } }
            }
        }
    }

    @Test
    fun `subcommand name starting with dash fails at build`() {
        // A leading '-' would be indistinguishable from an option token on the command line.
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                command("-build") { action { Ok("") } }
            }
        }
    }

    @Test
    fun `blank root name fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("") { action { Ok("") } }
        }
    }

    @Test
    fun `normal and dotted subcommand names still build`() {
        // Dots are used deliberately elsewhere (see DocsTest's `weird.name` case) and must stay allowed.
        val cmd = cli("app") {
            command("build") { action { Ok("") } }
            command("weird.name") { action { Ok("") } }
        }
        assertEquals("build", cmd.subcommand("build")?.name)
        assertEquals("weird.name", cmd.subcommand("weird.name")?.name)
    }
}

/** Construction-time validation of option, flag and argument names. */
class InputNameValidationTest {

    @Test
    fun `option with empty long name fails at construction`() {
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
    fun `option with blank long name fails at construction`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                option("   ")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `option with leading dash long name fails at construction`() {
        // A leading '-' would be indistinguishable from an option token on the command line.
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                option("-bad")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `flag with empty long name fails at construction`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                flag("--")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `argument with empty name fails at construction`() {
        assertFailsWith<IllegalArgumentException> {
            cli("x") {
                argument("")
                action { Ok("") }
            }
        }
    }

    @Test
    fun `flag name with control char fails at construction`() {
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
    fun `normal option and flag names still construct`() {
        val cmd = cli("x") {
            option("--name")
            flag("--verbose", "-v")
            action { Ok("") }
        }
        assertEquals("--name", cmd.options.single().name)
        assertEquals("--verbose", cmd.flags.single().name)
    }
}

/** A command with no action can read nothing, so a local option, flag or positional on one is rejected. */
class ActionlessCommandInputTest {

    @Test
    fun `actionless root with local option fails at build`() {
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
    fun `actionless root with local flag fails at build`() {
        assertFailsWith<IllegalArgumentException> {
            cli("app") {
                flag("--verbose", "-v")
                command("build") { action { Ok("") } }
            }
        }
    }

    @Test
    fun `hybrid root with local option and action still builds`() {
        val cmd = cli("app") {
            option("--tint", "-c")
            command("build") { action { Ok("") } }
            action { Ok("") }
        }
        assertEquals("--tint", cmd.options.single().name)
    }

    @Test
    fun `plain dispatcher with no local options still builds`() {
        val cmd = cli("app") {
            command("build") { action { Ok("") } }
            command("test") { action { Ok("") } }
        }
        assertTrue(cmd.isGroup)
    }

    @Test
    fun `actionless root with global option instead of local still builds`() {
        val cmd = cli("app") {
            globalOption("--tint", "-c")
            command("build") { action { Ok("") } }
        }
        assertTrue(cmd.isGroup)
    }

    @Test
    fun `actionless nested command with local option fails at build`() {
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
    fun `actionless root with positional fails at build`() {
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
    fun `actionless nested command with positional fails at build`() {
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
    fun `actionless root with no positional still builds`() {
        val cmd = cli("app") {
            command("build") { action { Ok("") } }
            command("test") { action { Ok("") } }
        }
        assertTrue(cmd.isGroup)
    }
}

/** A group title may not collide with a heading klap's own help already uses. */
class ReservedSectionTitleTest {

    @Test
    fun `group title colliding with reserved section fails at build`() {
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
    fun `option flag group titled like reserved section fails at build`() {
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
    fun `subcommand group titled like reserved section fails at build`() {
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
    fun `option flag group titled examples fails at build`() {
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
    fun `non reserved group title still builds`() {
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

class CommandCanActOrDispatchTest {

    @Test
    fun `a command declaring neither an action nor subcommands is rejected`() {
        // Not a parse error: such a command would parse fine and silently exit 0, which reads as success.
        // A command with inputs but no action is already caught by its own check, so this is the bare case.
        val ex = assertFailsWith<IllegalArgumentException> {
            cli("app") { command("dead") { description = "does nothing" } }
        }
        assertTrue("can never run" in ex.message.orEmpty(), ex.message)
    }

    @Test
    fun `a group needs no action of its own`() {
        val tree = cli("app") { command("group") { command("leaf") { action { Ok("ran") } } } }
        assertIs<Invocation.ShowHelp>(assertIs<Result.Success<Invocation>>(tree.parse(listOf("group"))).value)
        assertEquals(
            Result.Success("ran"),
            assertIs<Invocation.Execute>(
                assertIs<Result.Success<Invocation>>(tree.parse(listOf("group", "leaf"))).value,
            ).runAction(),
        )
    }
}
