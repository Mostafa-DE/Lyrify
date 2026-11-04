package app.lyrify.ai

import app.lyrify.lyrics.LrcLine
import kotlin.math.max

/**
 * Which Whisper segments are lyrics. Dropped:
 * - junk, the bench's `JUNK` rule (`phone_step5_score.py`): no letter or digit ("¶¶", "."), or a whole annotation
 *   in brackets or asterisks ("[Music]", "*music*");
 * - a classic Whisper filler (the bench's `FILLER`: "Thank you.", "Thanks for watching!", a lone "you", "Bye.",
 *   "Music"...) when less than half of its time is on vocal-active gate frames. Every one of the 24 filler lines of
 *   bench variant (b) sat over less than 50% vocal frames.
 */
object SegmentFilters {
    const val FILLER_MIN_VOCAL_SHARE: Double = 0.5

    private val FILLER = Regex(
        "(thank you|thanks)( (so|very) much)?( for (watching|listening))?|you|bye( bye)?|goodbye" +
            "|(please )?(like and )?subscribe.*|subtitles? by .*|see you (next time|later|soon)|music|applause",
    )
    private val ANNOTATION = Regex("""[*\[(][^\n]*[*\])]""")

    /** A segment without an end (its window stopped before a closing timestamp) is judged on its start's frame. */
    fun keep(segment: Segment, frames: GateFrames): Boolean =
        !isJunk(segment.text) &&
            !(isFiller(segment.text) && frames.activeShare(segment.start, segment.end ?: segment.start) < FILLER_MIN_VOCAL_SHARE)

    fun isJunk(text: String): Boolean {
        val trimmed = LyricsText.pyStrip(text)
        return trimmed.codePoints().noneMatch { LyricsText.isAlnum(it) } || ANNOTATION.matches(trimmed)
    }

    fun isFiller(text: String): Boolean = LyricsText.normalize(text).let { it.isNotEmpty() && FILLER.matches(it) }
}

/** The transcribe job: Whisper's segments as LRC lines. */
object Transcription {
    /**
     * One line per segment [SegmentFilters] keeps, at its start (the timestamp token that opens it, in song time) plus
     * [offsetSec] (at least 0), sorted by time as the bench's `write_lrc` does.
     */
    fun lines(segments: List<Segment>, frames: GateFrames, offsetSec: Double): List<LrcLine> =
        segments
            .filter { SegmentFilters.keep(it, frames) }
            .map { LrcLine(lrcMillis(it.start + offsetSec), LyricsText.clean(it.text)) }
            .filter { it.text.isNotEmpty() }
            .sortedBy { it.timeMs }
}

/**
 * A segment of [chunk]'s window as the timing re-validation takes it (`npu_timing.py` `song_segments`): times rounded to
 * the millisecond, the start at most the chunk's end, an open end (the window stopped before a closing timestamp) at the
 * chunk's end, and the end between the start and the chunk's end. Greedy decoding has no timestamp rules, so a
 * timestamp can point past the audio of a short chunk.
 */
fun Segment.within(chunk: VocalChunk): Segment {
    val from = minOf(Math.rint(start * 1000) / 1000, chunk.end)
    val to = end?.let { minOf(maxOf(Math.rint(it * 1000) / 1000, from), chunk.end) } ?: chunk.end
    return copy(start = from, end = to)
}

/** Seconds to LRC milliseconds: `fmt_ts` in the bench's `common.py`, `round(max(0, t) * 1000)` (half to even). */
fun lrcMillis(seconds: Double): Long = Math.rint(max(0.0, seconds) * 1000).toLong()
