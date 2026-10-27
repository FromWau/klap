package com.fromwau.klap.fixture.dd

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map

/** One symbol of a CONV, FLAG or LEVEL list: its [name] on the line, what it binds and its `dd --help` words. */
internal data class Named<T>(
    val name: String,
    val value: T,
    val help: String,
)

internal val CONVS: List<Named<Conv>> = listOf(
    Named("ascii", Conv.Ascii, "from EBCDIC to ASCII"),
    Named("ebcdic", Conv.Ebcdic, "from ASCII to EBCDIC"),
    Named("ibm", Conv.Ibm, "from ASCII to alternate EBCDIC"),
    Named("block", Conv.Block, "pad newline-terminated records with spaces to cbs-size"),
    Named("unblock", Conv.Unblock, "replace trailing spaces in cbs-size records with newline"),
    Named("lcase", Conv.Lcase, "change upper case to lower case"),
    Named("ucase", Conv.Ucase, "change lower case to upper case"),
    Named("sparse", Conv.Sparse, "try to seek rather than write all-NUL output blocks"),
    Named("swab", Conv.Swab, "swap every pair of input bytes"),
    Named(
        "sync",
        Conv.Sync,
        "pad every input block with NULs to ibs-size; when used with block or unblock, pad with spaces rather " +
            "than NULs",
    ),
    Named("excl", Conv.Excl, "fail if the output file already exists"),
    Named("nocreat", Conv.Nocreat, "do not create the output file"),
    Named("notrunc", Conv.Notrunc, "do not truncate the output file"),
    Named("noerror", Conv.Noerror, "continue after read errors"),
    Named("fdatasync", Conv.Fdatasync, "physically write output file data before finishing"),
    Named("fsync", Conv.Fsync, "likewise, but also write metadata"),
)

/** The input flags, `iflag=`'s table: every FLAG symbol (`dd iflag=append` runs). */
internal val IFLAGS: List<Named<Flag>> = listOf(
    Named("append", Flag.Append, "append mode (makes sense only for output; conv=notrunc suggested)"),
    Named("direct", Flag.Direct, "use direct I/O for data"),
    Named("directory", Flag.Directory, "fail unless a directory"),
    Named("dsync", Flag.Dsync, "use synchronized I/O for data"),
    Named("sync", Flag.Sync, "likewise, but also for metadata"),
    Named("fullblock", Flag.Fullblock, "accumulate full blocks of input (iflag only)"),
    Named("nonblock", Flag.Nonblock, "use non-blocking I/O"),
    Named("noatime", Flag.Noatime, "do not update access time"),
    Named("nocache", Flag.Nocache, "Request to drop cache.  See also oflag=sync"),
    Named("noctty", Flag.Noctty, "do not assign controlling terminal from file"),
    Named("nofollow", Flag.Nofollow, "do not follow symlinks"),
)

/** `oflag=`'s table: `dd oflag=fullblock`: "invalid output flag: 'fullblock'". */
internal val OFLAGS: List<Named<Flag>> = IFLAGS.filter { it.value != Flag.Fullblock }

internal val LEVELS: List<Named<Status>> = listOf(
    Named("none", Status.None, "suppresses everything but error messages"),
    Named("noxfer", Status.Noxfer, "suppresses the final transfer statistics"),
    Named("progress", Status.Progress, "shows periodic transfer statistics"),
)

/** The flags that count bytes rather than blocks, each acting on one side alone. */
internal enum class ByteFlag { CountBytes, SkipBytes, SeekBytes }

/** dd takes these in `iflag=` and `oflag=` without a warning, though `dd --help` lists none of them. */
private val BYTE_FLAGS: Map<String, ByteFlag> =
    mapOf("count_bytes" to ByteFlag.CountBytes, "skip_bytes" to ByteFlag.SkipBytes, "seek_bytes" to ByteFlag.SeekBytes)

/** One operand as dd reads it, before the line folds them into [DdInputs]. */
internal sealed interface Setting {
    data class Input(val name: String) : Setting

    data class Output(val name: String) : Setting

    data class Ibs(val bytes: Long) : Setting

    data class Obs(val bytes: Long) : Setting

    data class Bs(val bytes: Long) : Setting

    data class CbsSize(val bytes: Long) : Setting

    data class Count(val amount: Amount) : Setting

    data class Skip(val amount: Amount) : Setting

    data class Seek(val amount: Amount) : Setting

    data class Convs(val symbols: Set<Conv>) : Setting

    data class InFlags(val flags: Set<Flag>, val bytes: Set<ByteFlag>) : Setting

    data class OutFlags(val flags: Set<Flag>, val bytes: Set<ByteFlag>) : Setting

    data class Level(val status: Status) : Setting
}

/** dd's operand grammar has these ways to be wrong; [detail] picks dd's words for each. */
internal sealed interface DdOperandError : ResultError {
    data class UnknownOperand(val word: String) : DdOperandError

    data class InvalidNumber(val value: String) : DdOperandError

    data class TooLarge(val value: String) : DdOperandError

    data class InvalidSymbol(val list: String, val symbol: String) : DdOperandError
}

/** dd's own wording, chosen at the boundary rather than baked into [DdOperandError]. */
internal fun DdOperandError.detail(): String = when (this) {
    is DdOperandError.UnknownOperand -> "unrecognized operand '$word'"
    is DdOperandError.InvalidNumber -> "invalid number: '$value'"
    is DdOperandError.TooLarge -> "invalid number: '$value': Value too large for defined data type"
    is DdOperandError.InvalidSymbol -> "invalid $list: '$symbol'"
}

/** Reads one `key=value` word as dd does, the value checked by its key. */
internal fun ddOperand(word: String): Result<Setting, DdOperandError> {
    val key = word.substringBefore('=', missingDelimiterValue = "")
    val value = word.substringAfter('=')
    return when (key) {
        "if" -> Ok(Setting.Input(value))
        "of" -> Ok(Setting.Output(value))
        "ibs" -> blockSize(value, Setting::Ibs)
        "obs" -> blockSize(value, Setting::Obs)
        "bs" -> blockSize(value, Setting::Bs)
        "cbs" -> blockSize(value, Setting::CbsSize)
        "count" -> amount(value, Setting::Count)
        "skip", "iseek" -> amount(value, Setting::Skip)
        "seek", "oseek" -> amount(value, Setting::Seek)
        "conv" -> symbols(value, CONVS, "conversion").map { Setting.Convs(it.toSet()) }
        "iflag" -> flags(value, IFLAGS, "input flag", Setting::InFlags)
        "oflag" -> flags(value, OFLAGS, "output flag", Setting::OutFlags)
        "status" -> symbols(value, LEVELS, "status level").map { Setting.Level(it.last()) }
        else -> Err(DdOperandError.UnknownOperand(word))
    }
}

private fun blockSize(
    value: String,
    setting: (Long) -> Setting,
): Result<Setting, DdOperandError> = ddNumber(value).flatMap { number ->
    // The info page: "Block sizes (i.e., specified by BYTES strings) must be nonzero".
    if (number.value == 0L) Err(DdOperandError.InvalidNumber(value)) else Ok(setting(number.value))
}

private fun amount(
    value: String,
    setting: (Amount) -> Setting,
): Result<Setting, DdOperandError> = ddNumber(value).map { number ->
    setting(if (number.countsBytes) Amount.Bytes(number.value) else Amount.Blocks(number.value))
}

/** The symbol of [table] named [name], or null. */
internal fun <T> List<Named<T>>.named(name: String): T? = firstOrNull { it.name == name }?.value

private fun <T> symbols(
    value: String,
    table: List<Named<T>>,
    list: String,
): Result<List<T>, DdOperandError> {
    val names = value.split(',')
    val unknown = names.firstOrNull { table.named(it) == null }
    if (unknown != null) return Err(DdOperandError.InvalidSymbol(list, unknown))
    return Ok(names.mapNotNull { table.named(it) })
}

private fun flags(
    value: String,
    table: List<Named<Flag>>,
    list: String,
    setting: (Set<Flag>, Set<ByteFlag>) -> Setting,
): Result<Setting, DdOperandError> {
    val names = value.split(',')
    val unknown = names.firstOrNull { it !in BYTE_FLAGS && table.named(it) == null }
    if (unknown != null) return Err(DdOperandError.InvalidSymbol(list, unknown))
    return Ok(setting(names.mapNotNull { table.named(it) }.toSet(), names.mapNotNull { BYTE_FLAGS[it] }.toSet()))
}

/** A number as dd reads it: its [value], and whether a `B` in it counts bytes rather than blocks. */
internal data class DdNumber(
    val value: Long,
    val countsBytes: Boolean,
)

private const val MULTIPLIERS = "KMGTPEZYRQ"

/**
 * One factor of an `x` product: blanks and a `+` before digits, as strtoumax skips them, then a multiplier, which
 * alone counts one of its unit.
 */
private const val SUFFIX = """[bcw]B?|[kK$MULTIPLIERS](?:B|D|iB)?"""

private val FACTOR = Regex("""[ \t\n\u000B\f\r]*\+?([0-9]+)(B|$SUFFIX)?|($SUFFIX)""")

/**
 * Reads N or BYTES as dd does: `dd --help`'s "c=1, w=2, b=512, kB=1000, K=1024, MB=1000*1000, M=1024*1024, xM=M"
 * and "If N ends in 'B', it counts bytes not blocks"; every value past 2^63 - 1 is too large.
 */
internal fun ddNumber(raw: String): Result<DdNumber, DdOperandError> {
    var product = 1L
    for (text in raw.split('x')) {
        val match = FACTOR.matchEntire(text) ?: return Err(DdOperandError.InvalidNumber(raw))
        val (digits, suffix, alone) = match.destructured
        val count = if (alone.isNotEmpty()) 1L else digits.toLongOrNull() ?: return Err(DdOperandError.TooLarge(raw))
        val factor = timesOrNull(count, multiplier(alone.ifEmpty { suffix }))
        product = factor?.let { timesOrNull(product, it) } ?: return Err(DdOperandError.TooLarge(raw))
    }
    return Ok(DdNumber(product, countsBytes = 'B' in raw))
}

/** The value of one multiplier suffix; a `B` or `D` after a letter makes it decimal, `iB` keeps it binary. */
private fun multiplier(suffix: String): Long? = when (suffix.firstOrNull()) {
    null, 'B', 'c' -> 1
    'w' -> 2
    'b' -> 512
    else -> {
        val base = if (suffix.length == 2) 1000L else 1024L
        var value: Long? = 1
        repeat(MULTIPLIERS.indexOf(suffix.first().uppercaseChar()) + 1) { value = value?.let { timesOrNull(it, base) } }
        value
    }
}

private fun timesOrNull(
    a: Long,
    b: Long?,
): Long? = b?.let { runCatching { Math.multiplyExact(a, it) }.getOrNull() }

/** A conv pair dd refuses on one line, each in dd's words. */
internal enum class ConvConflict(val detail: String) {
    Charsets("cannot combine any two of {ascii,ebcdic,ibm}"),
    Case("cannot combine lcase and ucase"),
    Create("cannot combine excl and nocreat"),
    Blocking("cannot combine block and unblock"),
}

/**
 * The pair dd refuses among every `conv=` of a line, or null. Once `cbs=` is given dd blocks or unblocks, and then
 * `ascii` counts as `unblock` and `ebcdic` and `ibm` as `block` (`dd conv=ebcdic,unblock cbs=4`: "cannot combine
 * block and unblock", while `dd conv=ebcdic,unblock` runs).
 */
internal fun convConflict(
    convs: Set<Conv>,
    cbsGiven: Boolean,
): ConvConflict? {
    val blocks = Conv.Block in convs || Conv.Ebcdic in convs || Conv.Ibm in convs
    val unblocks = Conv.Unblock in convs || Conv.Ascii in convs
    return when {
        convs.count { it == Conv.Ascii || it == Conv.Ebcdic || it == Conv.Ibm } > 1 -> ConvConflict.Charsets
        Conv.Lcase in convs && Conv.Ucase in convs -> ConvConflict.Case
        Conv.Excl in convs && Conv.Nocreat in convs -> ConvConflict.Create
        cbsGiven && blocks && unblocks -> ConvConflict.Blocking
        else -> null
    }
}

/** Folds a line's operands into what dd reads: the last of a key wins, the symbol lists add up. */
internal fun List<Setting>.toInputs(): DdInputs {
    // `dd ibs=10 oflag=count_bytes count=3` copies 30 bytes: each byte flag acts on its own side alone, wherever it
    // stands on the line.
    val inBytes = filterIsInstance<Setting.InFlags>().flatMapTo(mutableSetOf()) { it.bytes }
    val outBytes = filterIsInstance<Setting.OutFlags>().flatMapTo(mutableSetOf()) { it.bytes }
    var inputs = NOTHING_BOUND
    var bs: Long? = null
    for (setting in this) {
        inputs = when (setting) {
            is Setting.Input -> inputs.copy(input = Stream.File(setting.name))
            is Setting.Output -> inputs.copy(output = Stream.File(setting.name))
            is Setting.Ibs -> inputs.copy(ibs = setting.bytes)
            is Setting.Obs -> inputs.copy(obs = setting.bytes)
            is Setting.Bs -> inputs.also { bs = setting.bytes }
            is Setting.CbsSize -> inputs.copy(cbs = Cbs.Bytes(setting.bytes))
            is Setting.Count -> inputs.copy(count = setting.amount.inBytesIf(ByteFlag.CountBytes in inBytes))
            is Setting.Skip -> inputs.copy(skip = setting.amount.inBytesIf(ByteFlag.SkipBytes in inBytes))
            is Setting.Seek -> inputs.copy(seek = setting.amount.inBytesIf(ByteFlag.SeekBytes in outBytes))
            is Setting.Convs -> inputs.copy(conv = inputs.conv + setting.symbols)
            is Setting.InFlags -> inputs.copy(iflag = inputs.iflag + setting.flags)
            is Setting.OutFlags -> inputs.copy(oflag = inputs.oflag + setting.flags)
            is Setting.Level -> inputs.copy(status = setting.status)
        }
    }
    // `dd bs=10 ibs=4` reads 10 bytes at a time, as `dd ibs=4 bs=10` does: bs= wins wherever it stands.
    return bs?.let { inputs.copy(ibs = it, obs = it) } ?: inputs
}

private fun Amount.inBytesIf(countsBytes: Boolean): Amount =
    if (countsBytes && this is Amount.Blocks) Amount.Bytes(n) else this
