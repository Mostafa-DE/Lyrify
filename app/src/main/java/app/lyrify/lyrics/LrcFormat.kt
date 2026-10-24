package app.lyrify.lyrics

import java.util.Locale

/** One timed lyrics line. [text] may be empty: an instrumental break. */
data class LrcLine(val timeMs: Long, val text: String)

/** Why lyrics would show as nothing (or wrongly) in Samsung Music. */
enum class LrcProblem {
    /** Samsung needs `[` as the very first character of a timed line. */
    BYTE_ORDER_MARK,

    /** Fewer than [LrcFormat.MIN_LINES] timed lines with text: plain text, empty or nearly empty lyrics. */
    TOO_FEW_LINES,

    /** Samsung looks lines up by binary search. */
    NOT_ASCENDING,

    /** Timed past the end of the file (plus [LrcFormat.DURATION_SLACK_MS]): lyrics of another version. */
    PAST_DURATION,
}

sealed interface Conversion {
    /** Samsung-safe `.lrc` text, UTF-8 without BOM once [LrcFormat.encode]d. */
    data class Ready(val text: String) : Conversion

    data class Rejected(val problem: LrcProblem) : Conversion
}

/**
 * LRCLIB synced lyrics to the `.lrc` dialect Samsung Music 16 reads: one `[mm:ss.SSS]text`
 * line per entry, ascending, no header tags. Samsung parses the bracket with
 * `SimpleDateFormat("mm:ss.SS")`, which reads the fraction as raw milliseconds (`.13` is
 * 13 ms), so three-digit milliseconds are always written.
 */
object LrcFormat {
    const val MIN_LINES: Int = 3
    const val DURATION_SLACK_MS: Long = 5_000

    /** An LRCLIB timestamp: `[mm:ss]`, `[mm:ss.x]`, `[mm:ss.xx]` or `[mm:ss.xxx]` (also with `:` before the fraction). */
    private val LRCLIB_STAMP = Regex("""^\s*\[(\d{1,4}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** Samsung: a bracket at the very start of the line, then `mm:ss.SS` at the start of its content. */
    private val SAMSUNG_TAG = Regex("""^\[([^\[\]]+)]""")
    private val SAMSUNG_TIME = Regex("""^(\d{1,9}):(\d{1,9})\.(\d{1,9})""")
    private val ANY_TAG = Regex("""\[[^\[\]]+]""")
    private val UNPRINTABLE = Regex("""[\p{Cc}\p{Cf}]""")

    private const val MS_PER_SECOND: Long = 1_000
    private const val MS_PER_MINUTE: Long = 60_000

    /** Converts and validates in one go; only [Conversion.Ready] text may be written. */
    fun convert(synced: String, durationMs: Long): Conversion {
        val text = render(parseLrclib(synced))
        return validate(text, durationMs)?.let(Conversion::Rejected) ?: Conversion.Ready(text)
    }

    /**
     * Timed lines of LRCLIB synced lyrics, sorted by time (stable). A line with several
     * timestamps becomes one line per timestamp; header tags and untimed text are dropped.
     */
    fun parseLrclib(synced: String): List<LrcLine> =
        synced.removePrefix("\uFEFF").lineSequence().flatMap(::parseLrclibLine).sortedBy { it.timeMs }.toList()

    private fun parseLrclibLine(line: String): List<LrcLine> {
        val times = mutableListOf<Long>()
        var rest = line
        while (true) {
            val stamp = LRCLIB_STAMP.find(rest) ?: break
            times += stamp.minutes() * MS_PER_MINUTE + stamp.seconds() * MS_PER_SECOND + fractionMs(stamp.groupValues[3])
            rest = rest.substring(stamp.range.last + 1)
        }
        val text = rest.replace(UNPRINTABLE, "").trim()
        return times.map { LrcLine(it, text) }
    }

    private fun MatchResult.minutes(): Long = groupValues[1].toLong()

    private fun MatchResult.seconds(): Long = groupValues[2].toLong()

    /** `.5` is half a second here: LRCLIB means tenths, hundredths or thousandths by digit count. */
    private fun fractionMs(digits: String): Long = when (digits.length) {
        0 -> 0
        1 -> digits.toLong() * 100
        2 -> digits.toLong() * 10
        else -> digits.toLong()
    }

    /** `[mm:ss.SSS]text` lines ending in `\n`; minutes keep counting past 59. */
    fun render(lines: List<LrcLine>): String = buildString {
        lines.forEach { line ->
            append(
                String.format(
                    Locale.ROOT,
                    "[%02d:%02d.%03d]",
                    line.timeMs / MS_PER_MINUTE,
                    line.timeMs / MS_PER_SECOND % 60,
                    line.timeMs % MS_PER_SECOND,
                ),
            )
            append(line.text)
            append('\n')
        }
    }

    /** UTF-8 without a byte order mark, as Samsung tries UTF-8 first. */
    fun encode(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /**
     * What Samsung Music would show, as far as its parser is known: the first bracket of a
     * line that starts with `[`, read as `mm:ss.SS` with the fraction in raw milliseconds
     * (anything else on the line, header tags included, is dropped); further brackets are
     * stripped from the text; lines with the time of the previous line are merged.
     */
    fun parseLikeSamsung(text: String): List<LrcLine> = merged(samsungEntries(text))

    /** Null when Samsung would show these lyrics properly for a track of [durationMs]. */
    fun validate(text: String, durationMs: Long): LrcProblem? {
        if (text.startsWith('\uFEFF')) return LrcProblem.BYTE_ORDER_MARK
        val entries = samsungEntries(text)
        return when {
            entries.zipWithNext().any { (before, after) -> after.timeMs < before.timeMs } -> LrcProblem.NOT_ASCENDING
            merged(entries).count { it.text.isNotBlank() } < MIN_LINES -> LrcProblem.TOO_FEW_LINES
            entries.last().timeMs > durationMs + DURATION_SLACK_MS -> LrcProblem.PAST_DURATION
            else -> null
        }
    }

    private fun samsungEntries(text: String): List<LrcLine> = text.lines().mapNotNull { line ->
        val tag = SAMSUNG_TAG.find(line) ?: return@mapNotNull null
        val time = SAMSUNG_TIME.find(tag.groupValues[1]) ?: return@mapNotNull null
        val (minutes, seconds, millis) = time.destructured
        LrcLine(
            timeMs = minutes.toLong() * MS_PER_MINUTE + seconds.toLong() * MS_PER_SECOND + millis.toLong(),
            text = line.substring(tag.range.last + 1).replace(ANY_TAG, "").trim(),
        )
    }

    private fun merged(entries: List<LrcLine>): List<LrcLine> = entries.fold(emptyList()) { lines, entry ->
        val last = lines.lastOrNull()
        if (last != null && last.timeMs == entry.timeMs) {
            lines.dropLast(1) + last.copy(text = listOf(last.text, entry.text).filter { it.isNotEmpty() }.joinToString("\n"))
        } else {
            lines + entry
        }
    }

    /**
     * Where Samsung Music looks for lyrics: the audio path cut at its last `.`, plus `.lrc`.
     * Null when the file name has no extension (Samsung would look in the wrong place).
     */
    fun sidecarPath(audioPath: String): String? {
        val dot = audioPath.lastIndexOf('.')
        if (dot <= audioPath.lastIndexOf('/') + 1) return null
        return audioPath.substring(0, dot) + ".lrc"
    }
}
