package com.fromwau.klap.fixture.dd

/** Where dd reads or writes: `if=FILE` "read from FILE instead of standard input", `of=` the same for output. */
public sealed interface Stream {
    /** No `if=` or `of=`: standard input or standard output. */
    public data object Standard : Stream

    /** The file [name] as typed after the `=`: `dd if==in` opens `=in`. */
    public data class File(val name: String) : Stream
}

/**
 * How much `count=` copies or `skip=` and `seek=` pass over: "copy only N input blocks", and "If N ends in 'B', it
 * counts bytes not blocks" (`iflag=count_bytes`, `iflag=skip_bytes` and `oflag=seek_bytes` count bytes too).
 */
public sealed interface Amount {
    /** No `count=`: dd copies to the end of its input. */
    public data object All : Amount

    /** [n] blocks of `ibs=` (or `obs=` for `seek=`) bytes. */
    public data class Blocks(val n: Long) : Amount

    /** [n] bytes. */
    public data class Bytes(val n: Long) : Amount
}

/** `cbs=BYTES` "convert BYTES bytes at a time", which `conv=block` and `conv=unblock` read. */
public sealed interface Cbs {
    /** No `cbs=`. */
    public data object None : Cbs

    /** `cbs=` [n] bytes. */
    public data class Bytes(val n: Long) : Cbs
}

/** `dd --help`'s "Each CONV symbol may be" table. */
public enum class Conv {
    Ascii, Ebcdic, Ibm, Block, Unblock, Lcase, Ucase, Sparse, Swab, Sync, Excl, Nocreat, Notrunc, Noerror, Fdatasync,
    Fsync,
}

/** `dd --help`'s "Each FLAG symbol may be" table; `iflag=` takes every one, `oflag=` all but [Fullblock]. */
public enum class Flag {
    Append, Direct, Directory, Dsync, Sync, Fullblock, Nonblock, Noatime, Nocache, Noctty, Nofollow,
}

/** `status=LEVEL` "The LEVEL of information to print to standard error". */
public enum class Status {
    /** No `status=`: dd prints its transfer statistics at the end. */
    Default,

    /** "'none' suppresses everything but error messages". */
    None,

    /** "'noxfer' suppresses the final transfer statistics". */
    Noxfer,

    /** "'progress' shows periodic transfer statistics". */
    Progress,
}

/**
 * What one `dd` line binds, as dd reads it: a key given twice binds its last occurrence, `bs=` sets [ibs] and [obs]
 * whatever its place on the line, and `conv=`, `iflag=` and `oflag=` add up.
 *
 * A parity case asserts a whole invocation against a `copy()` of [NOTHING_BOUND], so every field it does not name is
 * pinned to its default too.
 */
public data class DdInputs(
    val input: Stream = Stream.Standard,
    val output: Stream = Stream.Standard,
    val ibs: Long = DEFAULT_BLOCK_SIZE,
    val obs: Long = DEFAULT_BLOCK_SIZE,
    val cbs: Cbs = Cbs.None,
    val count: Amount = Amount.All,
    val skip: Amount = Amount.Blocks(0),
    val seek: Amount = Amount.Blocks(0),
    val conv: Set<Conv> = emptySet(),
    val iflag: Set<Flag> = emptySet(),
    val oflag: Set<Flag> = emptySet(),
    val status: Status = Status.Default,
)

/** `dd --help`: "ibs=BYTES read up to BYTES bytes at a time (default: 512)", and the same for `obs=`. */
private const val DEFAULT_BLOCK_SIZE: Long = 512

/** `dd` with no operands at all: every field at the default dd gives it. */
public val NOTHING_BOUND: DdInputs = DdInputs()
