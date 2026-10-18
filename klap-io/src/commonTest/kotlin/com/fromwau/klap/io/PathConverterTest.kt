package com.fromwau.klap.io

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import com.fromwau.klap.cli
import com.fromwau.klap.cliOf
import com.fromwau.klap.projection
import com.fromwau.klap.testing.filesAfter
import com.fromwau.klap.testing.suggestionsFor
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val copy = cliOf("cp") {
    val target = option(short = 't', long = "target-directory").path()
    val sources = argument("source").path().multiple(min = 1)
    action { Ok("") }
    projection { target() to sources() }
}

private fun sourcesOf(vararg words: String): List<CliPath> = copy.parse(words.toList()).assertSuccess()!!.second

class PathConverterTest {

    @Test
    fun `a trailing slash is kept in the text and named by endsWithSlash`() {
        val path = sourcesOf("a/").single()
        assertEquals("a/", path.text)
        assertTrue(path.endsWithSlash)
        assertEquals(Path("a"), path.path)
    }

    @Test
    fun `a path without a trailing slash says so`() {
        val path = sourcesOf("a").single()
        assertEquals("a", path.text)
        assertFalse(path.endsWithSlash)
        assertEquals(Path("a"), path.path)
    }

    @Test
    fun `a doubled slash survives in the text`() {
        val path = sourcesOf("a//b").single()
        assertEquals("a//b", path.text)
        assertFalse(path.endsWithSlash)
        assertEquals(Path("a//b"), path.path)
    }

    @Test
    fun `a relative path with a trailing slash keeps both`() {
        val path = sourcesOf("./a/").single()
        assertEquals("./a/", path.text)
        assertTrue(path.endsWithSlash)
        assertEquals(Path("./a"), path.path)
    }

    @Test
    fun `the root ends with a slash and is absolute`() {
        val path = sourcesOf("/").single()
        assertEquals("/", path.text)
        assertTrue(path.endsWithSlash)
        assertEquals(Path("/"), path.path)
        assertTrue(path.path.isAbsolute)
    }

    @Test
    fun `an empty word binds an empty path rather than being refused`() {
        assertEquals(CliPath(""), sourcesOf("").single())
    }

    @Test
    fun `two paths are equal by the text as typed`() {
        assertEquals(CliPath("a/"), CliPath("a/"))
        assertEquals(CliPath("a/").hashCode(), CliPath("a/").hashCode())
        assertNotEquals(CliPath("a/"), CliPath("a"))
        assertNotEquals(CliPath("a//b"), CliPath("a/b"))
    }

    @Test
    fun `a path prints as its text`() {
        assertEquals("a//b/", CliPath("a//b/").toString())
    }

    @Test
    fun `an option path and a multiple operand bind in order`() {
        assertEquals(
            CliPath("dest/") to listOf(CliPath("a"), CliPath("b/")),
            copy.parse(listOf("-t", "dest/", "a", "b/")).assertSuccess(),
        )
        assertEquals(null to listOf(CliPath("a")), copy.parse(listOf("a")).assertSuccess())
    }

    @Test
    fun `a path chains required and default`() {
        val tool = cliOf("app") {
            val out = option(long = "out").path().required()
            val dir = argument("dir").path().default(CliPath("./"))
            action { Ok("") }
            projection { out() to dir() }
        }
        assertEquals(CliPath("o") to CliPath("./"), tool.parse(listOf("--out=o")).assertSuccess())
    }

    @Test
    fun `an operand path completes files`() {
        assertEquals(listOf(filesAfter()), copy.cli.suggestionsFor("cp "))
    }

    @Test
    fun `an option path completes files after the space and after the equals sign`() {
        assertEquals(listOf(filesAfter()), copy.cli.suggestionsFor("cp a -t "))
        assertEquals(listOf(filesAfter("--target-directory=")), copy.cli.suggestionsFor("cp a --target-directory="))
    }

    @Test
    fun `a converter through a handle kept past path is refused when the CLI is built`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            cli("cp") {
                val source = argument("source")
                source.path()
                source.int()
                action { Ok("") }
            }
        }
        assertContains(refused.message.orEmpty(), "argument 'source': cannot add another converter after")
    }
}
