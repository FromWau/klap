package com.fromwau.klap

import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun sampleTree(): Cli = cli("todo") {
    version = "1.2.3"
    description = "Manage a todo list"
    command("add") {
        description = "Add a task"
        argument("text", help = "the task text")
        option("--priority", "-p", help = "priority level").int().default(0)
        action { Ok("") }
    }
    command("config") {
        description = "Manage config"
        command("get") {
            description = "read a key"
            action { Ok("") }
        }
        command("set") {
            description = "write a key"
            action { Ok("") }
        }
    }
}

class DocsTest {

    @Test
    fun `single command markdown does not duplicate root description`() {
        val tree = cli("wc") {
            description = "count words"
            argument("file")
            action { Ok("") }
        }
        val md = tree.renderMarkdownDocs()
        // A single-command tool renders its description only in the node's own section, not also as a top
        // synopsis: "count words" must appear exactly once.
        assertEquals(1, md.split("count words").size - 1, md)
    }

    @Test
    fun `man name section collapses a multi line description`() {
        val tree = cli("tool") {
            description = "line one\nline two"
            command("x") { action { Ok("") } }
        }
        val nameLine = tree
            .renderManPage()
            .lines()
            .let { it[it.indexOf(".SH NAME") + 1] }
        // The POSIX NAME section stays one line: the embedded newline is collapsed to a space.
        assertEquals("tool \\- line one line two", nameLine)
    }

    @Test
    fun `doc format fromOrNull is case insensitive and rejects unknown`() {
        assertEquals(DocFormat.MARKDOWN, DocFormat.fromOrNull("markdown"))
        assertEquals(DocFormat.MAN, DocFormat.fromOrNull("MAN"))
        assertEquals(DocFormat.MARKDOWN, DocFormat.fromOrNull("md"))
        assertNull(DocFormat.fromOrNull("pdf"))
    }

    @Test
    fun `markdown includes every command including deeply nested`() {
        // A 3-level tree (app -> group -> leaf): a BFS walk deduped by name would drop this if docs
        // reused it, since the leaf's bare name could collide with a shallower node. docNodes must not.
        val tree = cli("app") {
            command("group") {
                description = "a group"
                command("leaf") {
                    description = "the deeply nested leaf"
                    action { Ok("") }
                }
            }
        }
        val docs = tree.renderMarkdownDocs()
        assertTrue("group leaf" in docs, docs)
        assertTrue("the deeply nested leaf" in docs, docs)
    }

    @Test
    fun `markdown shows command options with help`() {
        val docs = sampleTree().renderMarkdownDocs()
        assertTrue("add" in docs, docs)
        // The table cell escapes the <value> placeholder's angle brackets (see mdCell); a raw <value>
        // would be read as an unclosed HTML tag and swallowed by a real CommonMark/GFM renderer.
        assertTrue("-p, --priority &lt;value&gt;" in docs, docs)
        assertTrue("priority level" in docs, docs)
        assertTrue("the task text" in docs, docs)
    }

    @Test
    fun `markdown lists table of contents and usage`() {
        val docs = sampleTree().renderMarkdownDocs()
        // Paths are qualified from the root, so the root's own name prefixes every entry.
        assertTrue("- [todo config get](#todo-config-get)" in docs, docs)
        assertTrue("usage: todo add <text> [options]" in docs, docs)
    }

    @Test
    fun `markdown dedups colliding toc anchors so later link does not jump to first section`() {
        // A nested "app db migrate" and a flat "app db-migrate" both slug to the same base anchor
        // "app-db-migrate". A CommonMark/GitHub renderer dedups duplicate heading ids by suffixing -1 in
        // document order, so the second ToC link must target "app-db-migrate-1", not collide on the bare
        // anchor and silently jump to the first section.
        val tree = cli("app") {
            command("db") {
                command("migrate") { action { Ok("") } }
            }
            command("db-migrate") { action { Ok("") } }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("(#app-db-migrate)" in md, md)
        assertTrue("(#app-db-migrate-1)" in md, md)
    }

    @Test
    fun `markdown toc escapes brackets in link text`() {
        // A command name may contain '[' or ']' (requireValidName allows them). Left raw in the link TEXT,
        // an inner ']' would close the '[...]' early and break the link, so the ToC backslash-escapes them.
        val tree = cli("app") {
            command("list[all]") { action { Ok("") } }
        }
        val md = tree.renderMarkdownDocs()
        // The brackets are escaped in the link text; the anchor target is left unchanged (mdAnchor keeps them).
        assertTrue("- [app list\\[all\\]](#app-list[all])" in md, md)
    }

    @Test
    fun `man page emits roff structure`() {
        val man = sampleTree().renderManPage()
        assertTrue(man.startsWith(".TH \"TODO\" 1"), man)
        assertTrue(".SH NAME" in man, man)
        // Section headers carry the full qualified path (root name included), not the bare command name.
        assertTrue(".SH TODO ADD" in man, man)
        assertTrue(".SH TODO CONFIG GET" in man, man)
    }

    @Test
    fun `man page separates usage from description with a paragraph break`() {
        val man = sampleTree().renderManPage()
        // Without the .PP, roff fills the description onto the bolded usage line; assert the break is present.
        assertTrue(".PP\nManage a todo list" in man, man)
    }

    @Test
    fun `man page accepts an app supplied date`() {
        val man = sampleTree().renderManPage(date = "2026-07-25")
        // The date is roff-escaped like any other text, so its hyphens come out as the roff minus escape.
        assertTrue(".TH \"TODO\" 1 \"2026\\-07\\-25\"" in man, man)
    }

    @Test
    fun `man page escapes roff special character in command name`() {
        val tree = cli("app") {
            command("weird.sub-name") {
                description = "a .SH injection attempt"
                action { Ok("") }
            }
        }
        val man = tree.renderManPage()
        // The command name's '-' is a roff special (a request's minus sign), so it comes out as the '\-'
        // minus escape inside the section heading, while a mid-name '.' is left as-is (roffEscape only
        // escapes a '.' that begins a line). Assert the FULL heading so a truncation of the escaped
        // '\-NAME' tail would be caught, unlike a prefix assertion.
        assertTrue(".SH APP WEIRD.SUB\\-NAME" in man, man)
        // A mid-line '.SH' from the description is harmless because roff control lines must begin a line,
        // so it must not surface as its own control line. (The leading-'.' case is covered separately below.)
        assertTrue(man.lines().none { it.startsWith(".SH") && "injection" in it }, man)
    }

    @Test
    fun `docs command is auto added`() {
        assertTrue(sampleTree().subcommand("docs") != null)
    }

    @Test
    fun `docs markdown builtin returns markdown via run`() {
        val t = RecordingTerminal()
        val code = sampleTree().run(arrayOf("docs", "markdown"), t)
        assertEquals(0, code)
        assertTrue("# todo" in t.out.toString(), t.out.toString())
        assertTrue("add" in t.out.toString(), t.out.toString())
    }

    @Test
    fun `docs man builtin returns roff via run`() {
        val t = RecordingTerminal()
        val code = sampleTree().run(arrayOf("docs", "man"), t)
        assertEquals(0, code)
        assertTrue(".TH \"TODO\" 1" in t.out.toString(), t.out.toString())
    }

    @Test
    fun `docs rejects unknown format`() {
        val t = RecordingTerminal()
        val code = sampleTree().run(arrayOf("docs", "pdf"), t)
        assertEquals(2, code)
        // Named bare: the user reached this through the `docs` SUBCOMMAND, not the `--docs` option.
        assertTrue("invalid value 'pdf' for docs" in t.err.toString(), t.err.toString())
    }

    @Test
    fun `hidden subcommand is excluded from docs`() {
        val tree = cli("todo") {
            command("visible") { action { Ok("") } }
            command("secret") {
                hidden = true
                action { Ok("") }
            }
        }
        val docs = tree.renderMarkdownDocs()
        assertTrue("visible" in docs, docs)
        assertTrue("secret" !in docs, docs)
        // The internal completion helper subcommand must never leak into generated docs.
        assertTrue("__complete" !in docs, docs)
    }

    @Test
    fun `man page escapes description starting with roff control char`() {
        val tree = cli("app") {
            command("run") {
                description = ".SH FAKE"
                action { Ok("") }
            }
        }
        val man = tree.renderManPage()
        // A description that starts with '.' is prefixed with the no-op \& so it cannot inject a control line.
        assertTrue("\\&.SH FAKE" in man, man)
        assertTrue(man.lines().none { it == ".SH FAKE" }, man)
    }

    @Test
    fun `markdown includes command examples and epilog`() {
        val tree = cli("app") {
            command("run") {
                epilogue = "Report bugs upstream."
                example("app run --fast", "run quickly")
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        // Docs must not drop what --help renders: the example command and the epilogue paragraph.
        assertTrue("app run --fast" in md, md)
        assertTrue("Report bugs upstream." in md, md)
    }

    @Test
    fun `markdown example widens code span delimiter for backtick in command`() {
        val tree = cli("app") {
            command("run") {
                example("run `date`", "print today's date")
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        // The command contains a single backtick, so a lone single-backtick span would close early and
        // corrupt the line. The delimiter widens to a double backtick, and because the command ends with a
        // backtick the span is padded with one space just inside each delimiter (CommonMark strips one
        // leading/trailing space), so the inner backtick renders literally.
        assertTrue("- `` run `date` ``" in md, md)
    }

    @Test
    fun `man page includes command examples and epilog`() {
        val tree = cli("app") {
            command("run") {
                epilogue = "Report bugs upstream."
                example("app run --fast", "run quickly")
                action { Ok("") }
            }
        }
        val man = tree.renderManPage()
        // roff escapes each '-' as the minus escape, so the example's flags come out escaped.
        assertTrue("run \\-\\-fast" in man, man)
        assertTrue("Report bugs upstream." in man, man)
    }

    @Test
    fun `markdown does not mislabel default block as options`() {
        val tree = cli("app") {
            command("remote") {
                description = "manage remotes"
                command("add") {
                    description = "add a remote"
                    action { Ok("") }
                }
                command("remove") {
                    description = "remove a remote"
                    action { Ok("") }
                }
            }
            command("deploy") {
                argument("target", help = "where")
                group("Networking") { option("--host", "-H", help = "the host") }
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        // The untitled default block (a group's subcommand list, a leaf's positionals) gets no heading,
        // matching --help and the man page; it must never be mislabelled "Options".
        assertTrue("**Options**" !in md, md)
        assertTrue("add" in md && "remove" in md, md)
        assertTrue("<target>" in md, md)
        // Titled sections still keep their headings.
        assertTrue("**Networking**" in md, md)
        assertTrue("**Global options**" in md, md)
    }

    @Test
    fun `man page escapes double quotes in text`() {
        val tree = cli("app") {
            command("run") {
                example("app run \"buy milk\"", "quoted arg")
                action { Ok("") }
            }
        }
        val man = tree.renderManPage()
        // A raw `"` is swallowed as a roff macro-argument quote, so it must render as the \(dq glyph;
        // otherwise the example silently loses its quotes.
        assertTrue("\\(dq" in man, man)
        assertTrue("buy milk" in man, man)
    }

    @Test
    fun `markdown escapes pipe and newline in table cell`() {
        val tree = cli("app") {
            command("run") {
                option("--mode", "-m", help = "a | pipe\nand newline")
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        // Pipe escaped (no extra column) and newline flattened to a space (row stays on one line).
        assertTrue("a \\| pipe and newline" in md, md)
    }

    @Test
    fun `single command tool markdown has no contents toc or self listing`() {
        // A single-command tool (root action, no user subcommands) has only the hidden __complete
        // subcommand, so docNodes collapses to just the root. --help never shows a "Commands:" section
        // for such a tool, and the docs must not drift from that by inventing a lone self-entry ToC.
        val tree = cli("wc") {
            argument("f")
            action { Ok("") }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("## Contents" !in md, md)
        assertTrue("- [wc](#wc)" !in md, md)
    }

    @Test
    fun `dispatcher markdown still has contents toc and lists its subcommand`() {
        // A dispatcher (>=1 non-hidden subcommand) keeps the whole-tree navigation ToC, labeled
        // "Contents" (a flat index over every node), distinct from the per-node grouped sections below.
        val tree = cli("app") {
            command("build") { action { Ok("") } }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("## Contents" in md, md)
        assertTrue("- [app build](#app-build)" in md, md)
    }

    @Test
    fun `grouped subcommand renders under its group title in docs matching help`() {
        // The whole-tree navigation index is a separate "## Contents" ToC, distinct from the per-node
        // grouped sections below.
        val tree = cli("app") {
            group("Management") {
                command("build") { action { Ok("") } }
            }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("## Contents" in md, md)
        // `build` sits under its group's "**Management**" section, matching --help.
        val managementBlock = md.substringAfter("**Management**").substringBefore("\n**")
        assertTrue("build" in managementBlock, md)
        // ...and not under the ungrouped "**Commands**" section (which holds the injected completion/docs).
        val commandsBlock = md.substringAfter("**Commands**").substringBefore("\n**")
        assertTrue("build" !in commandsBlock, md)
    }

    @Test
    fun `single command tool man page has no redundant root section`() {
        // The man page's per-node ".SH <PATH>" wrapper is redundant for a single-command tool: the
        // root's own content would just duplicate ".SH NAME". It must appear exactly zero times, with
        // the command's usage/options folded directly after NAME instead.
        val tree = cli("wc") {
            argument("f")
            action { Ok("") }
        }
        val man = tree.renderManPage()
        assertTrue(".SH NAME" in man, man)
        assertTrue(".SH WC" !in man, man)
        // The command's own content (usage line) must still be present, just without the wrapper.
        assertTrue("usage: wc <f>" in man, man)
    }

    @Test
    fun `dispatcher man page still has per node sections`() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
        }
        val man = tree.renderManPage()
        assertTrue(".SH APP" in man, man)
        assertTrue(".SH APP BUILD" in man, man)
    }

    @Test
    fun `subcommand markdown doc lists version when root is versioned`() {
        // --version works from any subcommand, so its generated doc section must list it too,
        // not just the root's. The --help side is covered in HelpTest; this guards the docs side stays
        // in sync. sampleTree() is versioned, and "add"'s section sits between its own heading and the
        // next sibling's ("config"), so slicing there isolates just the subcommand's own content.
        val docs = sampleTree().renderMarkdownDocs()
        val addSection = docs.substringAfter("### todo add").substringBefore("### todo config")
        assertTrue("--version" in addSection, addSection)
    }

    @Test
    fun `subcommand man page lists version when root is versioned`() {
        val man = sampleTree().renderManPage()
        val addSection = man.substringAfter(".SH TODO ADD").substringBefore(".SH TODO CONFIG")
        // roff escapes each '-' as the minus escape (see manPage_escapesRoffSpecialCharacterInCommandName).
        assertTrue("\\-\\-version" in addSection, addSection)
    }

    @Test
    fun `subcommand markdown doc omits version when root is unversioned`() {
        val tree = cli("app") {
            command("build") { action { Ok("") } }
        }
        val docs = tree.renderMarkdownDocs()
        val buildSection = docs.substringAfter("### app build")
        assertTrue("--version" !in buildSection, buildSection)
    }

    @Test
    fun `markdown escapes backslash and backtick in description and epilog paragraph text`() {
        // A raw backslash in free paragraph text is unsafe: a real CommonMark renderer reads `\.` as an
        // escape sequence and eats the backslash, corrupting a Windows path. Table cells already go
        // through mdCell; description/epilogue are appended raw and must be escaped the same way a
        // paragraph-text escaper would, without collapsing structure the way mdCell does.
        val tree = cli("app") {
            description = "Config lives at C:\\Users\\name\\.refrc, see `docs`."
            epilogue = "Backup path: D:\\backup\\app`s data."
            action { Ok("") }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("C:\\\\Users\\\\name\\\\.refrc" in md, md)
        assertTrue("\\`docs\\`" in md, md)
        assertTrue("D:\\\\backup\\\\app\\`s data." in md, md)

        // The man page's own roff escaping is independent of the markdown escaping above and stays unaffected by it.
        val man = tree.renderManPage()
        assertTrue("C:\\eUsers\\ename\\e.refrc" in man, man)
    }

    @Test
    fun `markdown escapes angle brackets in description paragraph text`() {
        // A raw <name> in a description paragraph is read by a real CommonMark/GFM renderer as an
        // (unclosed) HTML tag and swallowed, so "the <name> to use" would render as "the  to use".
        // mdText escapes the angle brackets to entities the same way mdCell does for table cells.
        val tree = cli("app") {
            command("run") {
                description = "the <name> to use"
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("the &lt;name&gt; to use" in md, md)
        assertTrue("<name>" !in md, md)
    }

    @Test
    fun `markdown root description is escaped in the top synopsis not just the body copy`() {
        // The root description is rendered twice: the top-of-page synopsis and the root node's own
        // section. Both copies must escape paragraph markdown, or a real CommonMark renderer could eat
        // a raw backslash in the synopsis copy.
        val tree = cli("app") {
            description = "path C:\\x\\.y"
            command("build") { action { Ok("") } }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("C:\\\\x\\\\.y" in md, md)
        // The raw (unescaped) path must not appear anywhere: the synopsis copy is escaped too.
        assertTrue("C:\\x\\.y" !in md, md)
        // The escaped path appears in both copies (synopsis + the root's own section).
        assertTrue(md.split("C:\\\\x\\\\.y").size - 1 >= 2, md)
    }

    @Test
    fun `markdown shows subcommand aliases line`() {
        val tree = cli("app") {
            command("list") {
                description = "list items"
                aliases = listOf("ls")
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("Aliases: ls" in md, md)
        // The parent's table row for the subcommand also shows the alias, matching --help.
        assertTrue("list, ls" in md, md)
    }

    @Test
    fun `markdown escapes subcommand aliases line`() {
        // requireValidName permits markdown-active chars in an alias, so an alias with them must be escaped on the
        // Aliases: line the same way the man twin (roffEscape) and the parent table cell (mdCell) escape it;
        // otherwise a stray backtick/angle-bracket corrupts the page.
        val tree = cli("app") {
            command("list") {
                description = "list items"
                aliases = listOf("<ls>")
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("Aliases: &lt;ls&gt;" in md, md)
        assertFalse("Aliases: <ls>" in md, md)
    }

    @Test
    fun `man page shows subcommand aliases line`() {
        // Mirrors markdownFor's `Aliases:` line, roff-escaped, so the man page and markdown docs agree.
        val tree = cli("app") {
            command("list") {
                description = "list items"
                aliases = listOf("ls")
                action { Ok("") }
            }
        }
        val man = tree.renderManPage()
        assertTrue("Aliases: ls" in man, man)
        // The parent's table (roff .TP) row for the subcommand also shows the alias, matching --help.
        assertTrue(".B list, ls" in man, man)
    }

    @Test
    fun `hidden subcommand alias not shown in man page`() {
        val tree = cli("app") {
            command("visible") { action { Ok("") } }
            command("secret") {
                hidden = true
                aliases = listOf("shh")
                action { Ok("") }
            }
        }
        val man = tree.renderManPage()
        assertFalse("shh" in man, man)
    }

    @Test
    fun `markdown table cell has no leading alignment padding for option without short`() {
        // --help pads a short-less option's signature with leading spaces to align "--long" under
        // "-s, --long"; that alignment padding is --help-only and must not leak into the raw doc source.
        // <value> comes out angle-bracket-escaped (mdCell), same as every other table cell placeholder.
        val tree = cli("app") {
            command("run") {
                option("--verbose", help = "be noisy")
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("| --verbose &lt;value&gt; |" in md, md)
        assertTrue("|     --verbose &lt;value&gt; |" !in md, md)
    }

    @Test
    fun `man page has no leading alignment padding for option without short`() {
        val tree = cli("app") {
            command("run") {
                option("--verbose", help = "be noisy")
                action { Ok("") }
            }
        }
        val man = tree.renderManPage()
        // roff escapes each '-' as the minus escape (see manPage_escapesRoffSpecialCharacterInCommandName).
        assertTrue(".B \\-\\-verbose <value>" in man, man)
        assertTrue(".B     \\-\\-verbose <value>" !in man, man)
    }

    @Test
    fun `docs signature with a short renders both forms unpadded`() {
        // An option WITH a short still renders "-v, --verbose <value>" (no padding to begin with); the
        // markdown table cell additionally escapes the placeholder's angle brackets, the man page does not.
        val tree = cli("app") {
            command("run") {
                option("--verbose", "-v", help = "be noisy")
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        val man = tree.renderManPage()
        assertTrue("| -v, --verbose &lt;value&gt; |" in md, md)
        assertTrue(".B \\-v, \\-\\-verbose <value>" in man, man)
    }

    @Test
    fun `markdown escapes backtick in table cell instead of replacing with apostrophe`() {
        val tree = cli("app") {
            command("run") {
                option("--mode", "-m", help = "use `raw` mode")
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("use \\`raw\\` mode" in md, md)
        assertTrue("use 'raw' mode" !in md, md)
    }

    @Test
    fun `markdown table cell escapes angle brackets in option value placeholder`() {
        // A real CommonMark/GFM renderer reads a raw <value> as an unclosed HTML tag and swallows it,
        // so the placeholder vanishes from the rendered doc. The table cell must escape it instead.
        val tree = cli("app") {
            command("serve") {
                option("--port").int()
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("| --port &lt;value&gt; |" in md, md)
        assertTrue("<value>" !in md, md)

        // roff has no HTML-swallowing hazard, so the man page keeps the placeholder unescaped (only its
        // own roff-specific escapes, e.g. the dash, apply).
        val man = tree.renderManPage()
        assertTrue(".B \\-\\-port <value>" in man, man)
    }

    @Test
    fun `markdown table cell escapes angle brackets in argument placeholder`() {
        // A positional's <name> placeholder is just as vulnerable to being swallowed as an option's
        // <value>; it must be escaped in the table cell too.
        val tree = cli("app") {
            command("serve") {
                argument("name")
                action { Ok("") }
            }
        }
        val md = tree.renderMarkdownDocs()
        assertTrue("| &lt;name&gt; |" in md, md)
        assertTrue("| <name> |" !in md, md)
    }
}
