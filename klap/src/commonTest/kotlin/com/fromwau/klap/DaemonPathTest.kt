package com.fromwau.klap

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.terminal.BROKEN_PIPE_EXIT
import com.fromwau.kern.terminal.ReadError
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What a forwarding client tells a daemon about the user's terminal: all of it klap reads. */
internal data class ForwardedProperties(val columns: Int, val ansi: Boolean, val errAnsi: Boolean)

/**
 * A daemon's terminal for one forwarded run: each write is a frame sent back to the client, standard input is the
 * bytes the client forwarded, and the rest is what the client reported. It answers nothing else, so whether a stream
 * is a terminal reads false.
 */
internal class ForwardedTerminal(
    private val properties: ForwardedProperties,
    private val stdin: ByteArray = ByteArray(0),
) : Terminal {
    val frames = mutableListOf<Pair<Stream, String>>()

    override fun out(text: String) {
        frames += Stream.Out to text
    }

    override fun err(text: String) {
        frames += Stream.Err to text
    }

    override val columns: Int get() = properties.columns
    override val ansi: Boolean get() = properties.ansi
    override val errAnsi: Boolean get() = properties.errAnsi

    override fun readIn(maxBytes: Long?): Result<ByteArray, ReadError> =
        if (maxBytes != null && stdin.size > maxBytes) Err(ReadError.TooLarge(maxBytes, maxBytes + 1)) else Ok(stdin)
}

/** One run as the client sees it: the exit code, and stdout and stderr interleaved as `2>&1` shows them. */
internal data class Seen(val code: Int, val writes: List<Pair<Stream, String>>)

/** The run on the user's own terminal: [RecordingTerminal] answering what `defaultTerminal()` reports there. */
internal fun Cli.onTerminal(
    argv: List<String>,
    properties: ForwardedProperties,
    stdin: ByteArray = ByteArray(0),
): Seen {
    val terminal = RecordingTerminal(
        columns = properties.columns,
        ansi = properties.ansi,
        errAnsi = properties.errAnsi,
        outIsTerminal = properties.ansi,
        errIsTerminal = properties.errAnsi,
        inIsTerminal = true,
        input = stdin,
    )
    val code = run(argv, terminal)
    return Seen(code, terminal.writes.map { it.stream to it.text })
}

/** The same run forwarded to a daemon, which drives it with [runSuspending] on a [ForwardedTerminal]. */
internal suspend fun Cli.forwarded(
    argv: List<String>,
    properties: ForwardedProperties,
    caller: Caller = Caller.process(),
    stdin: ByteArray = ByteArray(0),
): Seen {
    val terminal = ForwardedTerminal(properties, stdin)
    val code = runSuspending(argv, terminal, caller)
    return Seen(code, terminal.frames.toList())
}

internal val colourTty = ForwardedProperties(columns = 100, ansi = true, errAnsi = true)
internal val narrowTty = ForwardedProperties(columns = 40, ansi = true, errAnsi = true)
internal val piped = ForwardedProperties(columns = 80, ansi = false, errAnsi = false)

/** A line for every kind of output the tree gives, each with the standard input it is handed. */
internal val everyOutputKind: List<Pair<List<String>, String>> = listOf(
    listOf("--help") to "",
    listOf("-h") to "",
    listOf("get", "--help") to "",
    listOf("--help-all") to "",
    listOf("docs", "markdown") to "",
    listOf("docs", "man") to "",
    listOf("--version") to "",
    listOf("--version", "--json") to "",
    listOf("get", "-f", "config.toml", ".server") to "",
    listOf("get", "-f", "config.toml", ".server", "--json") to "",
    listOf("get", "-f", "config.toml", ".nothing") to "",
    listOf("get", "-f", "config.toml", ".server", "-r", "--json") to "",
    listOf("get") to "",
    listOf("get", "--bogus") to "",
    listOf("gte") to "",
    listOf("--json", "get", "--bogus") to "",
    listOf("set") to "",
    listOf("format", "-f", "config.toml", "--check") to "",
    listOf("completion", "fish") to "",
    listOf("completion", "bash") to "",
    listOf("completion", "zsh") to "",
    listOf("completion", "powershell") to "",
    listOf("__complete", "--cursor", "3", "--", "get", "-f", "config.toml", ".ser") to "",
    listOf("__complete", "--cursor", "1", "--", "set", "--la") to "",
    listOf("__complete", "--cursor", "5", "--", "set", "-f", "config.toml", ".server", "--layout", "") to "",
    listOf("__complete", "--cursor", "0", "--", "") to "",
    listOf("check", "-") to "name = 1\n",
    listOf("check", "-", "config.toml", "other.toml") to "not toml",
    listOf("check", "--json") to "a = 1",
)

class DaemonPathTest {

    @Test
    fun `every output kind is the same forwarded to a daemon as on the user's terminal`() = runTest {
        val tree = tomlShapedCli()
        for (properties in listOf(colourTty, narrowTty, piped)) {
            for ((argv, stdin) in everyOutputKind) {
                val input = stdin.encodeToByteArray()
                assertEquals(
                    tree.onTerminal(argv, properties, input),
                    tree.forwarded(argv, properties, stdin = input),
                    "$argv on $properties",
                )
            }
        }
    }

    @Test
    fun `width and colour are what the client must send as help changes with each`() = runTest {
        val tree = tomlShapedCli()
        val wide = tree.forwarded(listOf("--help-all"), colourTty)
        val narrow = tree.forwarded(listOf("--help-all"), narrowTty)
        val plain = tree.forwarded(listOf("--help-all"), colourTty.copy(ansi = false))
        assertNotEquals(wide, narrow)
        assertNotEquals(wide, plain)
        val esc = Char(27)
        assertTrue(wide.writes.any { esc in it.second })
        assertTrue(plain.writes.none { esc in it.second })
    }

    @Test
    fun `__complete answers a runtime provider's candidates forwarded as on the terminal`() = runTest {
        val tree = tomlShapedCli()
        val line = listOf("__complete", "--cursor", "3", "--", "get", "-f", "config.toml", ".ser")
        val seen = tree.forwarded(line, piped)
        assertEquals(Seen(0, listOf(Stream.Out to ".server\n.server.host\n.server.port\n.servers\n")), seen)
    }

    @Test
    fun `a usage error is the same text and exit code forwarded`() = runTest {
        val tree = tomlShapedCli()
        val seen = tree.forwarded(listOf("get", "--bogus"), piped)
        assertEquals(USAGE_ERROR_EXIT, seen.code)
        assertEquals(listOf(Stream.Err), seen.writes.map { it.first })
        assertTrue(seen.writes.single().second.startsWith("error: "), seen.writes.single().second)
    }

    @Test
    fun `an action reads the forwarded standard input through its terminal`() = runTest {
        val tree = tomlShapedCli()
        assertEquals(
            Seen(0, listOf(Stream.Out to "<stdin>: OK\n")),
            tree.forwarded(listOf("check"), piped, stdin = "a = 1".encodeToByteArray()),
        )
        assertEquals(
            Seen(TomlExit.NotToml.code, listOf(Stream.Err to "error: <stdin> is not TOML\n")),
            tree.forwarded(listOf("check"), piped, stdin = "nope".encodeToByteArray()),
        )
    }

    @Test
    fun `klap reads only the terminal members a forwarding client can send`() = runTest {
        val tree = tomlShapedCli()
        val forwardable = setOf("out", "err", "columns", "ansi", "errAnsi", "awaitWriteResult", "readIn")
        val read = everyOutputKind.flatMap { (argv, stdin) ->
            val spy = SpyTerminal(colourTty, stdin.encodeToByteArray())
            tree.runSuspending(argv, spy)
            spy.read
        }.toSet()
        assertEquals(emptySet(), read - forwardable)
    }

    @Test
    fun `a write failure the terminal reports late ends a forwarded run as a broken pipe`() = runTest {
        val terminal = LateFailingTerminal(WriteError.BrokenPipe(Stream.Out))
        assertEquals(BROKEN_PIPE_EXIT, tomlShapedCli().runSuspending(listOf("--help-all"), terminal))
    }

    @Test
    fun `a write failure the terminal reports late outranks an answering exit`() = runTest {
        val terminal = LateFailingTerminal(WriteError.BrokenPipe(Stream.Out))
        val argv = listOf("format", "-f", "config.toml", "--check")
        assertEquals(BROKEN_PIPE_EXIT, tomlShapedCli().runSuspending(argv, terminal))
    }

    @Test
    fun `--color=always colours a forwarded run whose client reported no colour`() = runTest {
        val tree = cli("kotoml") { listOf(GetSpec, SetSpec, CheckSpec).forEach { command(it) } }
        val argv = listOf("--color=always", "--help")
        val seen = tree.forwarded(argv, piped)
        assertEquals(tree.onTerminal(argv, piped), seen)
        assertTrue(seen.writes.any { Char(27) in it.second })
    }
}

/** A [ForwardedTerminal] that also notes which of its members were read, by name. */
internal class SpyTerminal(properties: ForwardedProperties, stdin: ByteArray) : Terminal {
    private val forwarded = ForwardedTerminal(properties, stdin)
    val read = mutableSetOf<String>()

    override fun out(text: String) = forwarded.out(text).also { read += "out" }
    override fun err(text: String) = forwarded.err(text).also { read += "err" }
    override val columns: Int get() = forwarded.columns.also { read += "columns" }
    override val ansi: Boolean get() = forwarded.ansi.also { read += "ansi" }
    override val errAnsi: Boolean get() = forwarded.errAnsi.also { read += "errAnsi" }
    override val outIsTerminal: Boolean get() = false.also { read += "outIsTerminal" }
    override val errIsTerminal: Boolean get() = false.also { read += "errIsTerminal" }
    override val inIsTerminal: Boolean get() = false.also { read += "inIsTerminal" }
    override fun readIn(maxBytes: Long?) = forwarded.readIn(maxBytes).also { read += "readIn" }
    override fun writeResult() = forwarded.writeResult().also { read += "writeResult()" }
    override fun writeResult(stream: Stream) = forwarded.writeResult(stream).also { read += "writeResult(stream)" }
    override suspend fun awaitWriteResult() = forwarded.awaitWriteResult().also { read += "awaitWriteResult" }
    override fun afterWrites(block: (EmptyResult<WriteError>) -> Unit) = forwarded.afterWrites(block)
        .also { read += "afterWrites" }
}

/** A terminal whose writes seem to land and whose [failure] arrives only once asked to wait, as a socket's does. */
private class LateFailingTerminal(private val failure: WriteError) : Terminal {
    override fun out(text: String) = Unit
    override fun err(text: String) = Unit
    override suspend fun awaitWriteResult(): EmptyResult<WriteError> = Err(failure)
}
