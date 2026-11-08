package app.lyrify.tones

import android.media.RingtoneManager
import app.lyrify.ai.PcmToMix
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrcLine
import java.io.File
import java.io.IOException

/**
 * What a tone is for: the shared folder it is saved in (the values of Environment.DIRECTORY_RINGTONES, _ALARMS and
 * _NOTIFICATIONS, which MediaStore turns into IS_RINGTONE, IS_ALARM or IS_NOTIFICATION, so every app's sound picker lists
 * it), the length the editor starts with and its RingtoneManager type.
 */
enum class ToneKind(val folder: String, val defaultLengthMs: Long, val ringtoneType: Int) {
    RINGTONE("Ringtones", 30_000, RingtoneManager.TYPE_RINGTONE),
    ALARM("Alarms", 60_000, RingtoneManager.TYPE_ALARM),

    /** The shortest length chip: a notification sound is short. */
    NOTIFICATION("Notifications", 15_000, RingtoneManager.TYPE_NOTIFICATION),
}

/** The part of a song a tone is made of: from [startMs], [lengthMs] long. */
data class ToneWindow(val startMs: Long, val lengthMs: Long) {
    val endMs: Long get() = startMs + lengthMs

    companion object {
        const val MIN_LENGTH_MS: Long = 1_000
        const val MAX_LENGTH_MS: Long = 60_000

        /**
         * The part of a song a tone can come from: the decoder reads at most [PcmToMix.MAX_AUDIO_SEC] of audio, so a
         * longer song offers its first 20 minutes less a second (one decoder buffer never crosses the limit).
         */
        const val MAX_SOURCE_MS: Long = PcmToMix.MAX_AUDIO_SEC * 1_000 - 1_000

        fun usableDurationMs(durationMs: Long): Long = durationMs.coerceAtMost(MAX_SOURCE_MS)

        /** The length chips. */
        val LENGTHS_MS: List<Long> = listOf(15_000L, 30_000L, 45_000L, 60_000L)

        /**
         * A window that starts where the user put it (a lyric line, the start handle): the start is kept inside the song,
         * with at least [MIN_LENGTH_MS] after it, and the length shortens where the song ends first. A song shorter than
         * [MIN_LENGTH_MS] is the whole window; an unknown duration (0) gives an empty one.
         */
        fun from(startMs: Long, lengthMs: Long, durationMs: Long): ToneWindow {
            if (durationMs <= 0) return ToneWindow(0, 0)
            val start = startMs.coerceIn(0, (durationMs - MIN_LENGTH_MS).coerceAtLeast(0))
            return ToneWindow(start, minOf(lengthMs.coerceIn(MIN_LENGTH_MS, MAX_LENGTH_MS), durationMs - start))
        }

        /** The whole window dragged to [startMs]: its length is kept (at most the song's) and it moves to fit. */
        fun moved(startMs: Long, lengthMs: Long, durationMs: Long): ToneWindow {
            if (durationMs <= 0) return ToneWindow(0, 0)
            val length = lengthMs.coerceIn(MIN_LENGTH_MS, MAX_LENGTH_MS).coerceAtMost(durationMs)
            return ToneWindow(startMs.coerceIn(0, durationMs - length), length)
        }

        /**
         * "To the end of the line": the window from [startMs] lengthened so the lyric line playing at its end
         * ([startMs] + [lengthMs]) finishes, that is up to the next timed line of [lines] (a break counts) or the song's
         * end. Past [MAX_LENGTH_MS] it ends at the last line end that fits instead. Null when no line ends between
         * [MIN_LENGTH_MS] and [MAX_LENGTH_MS] after the start, or there are no lines: the chip is not offered.
         */
        fun toLineEnd(startMs: Long, lengthMs: Long, lines: List<LrcLine>, durationMs: Long): ToneWindow? {
            if (lines.isEmpty()) return null
            val ends = (lines.map { it.timeMs }.filter { it in (startMs + 1)..durationMs } + durationMs)
                .filter { it - startMs in MIN_LENGTH_MS..MAX_LENGTH_MS }
                .sorted()
            val end = ends.firstOrNull { it >= startMs + lengthMs } ?: ends.lastOrNull() ?: return null
            return ToneWindow(startMs, end - startMs)
        }
    }
}

/**
 * The song's synced lyrics for "Start at a lyric": its `.lrc` sidecar, read only. The text is shown on screen only,
 * never logged or stored.
 */
object ToneLyrics {
    /** A sidecar larger than this is not a lyrics file: not read. */
    const val MAX_LRC_BYTES: Long = 512 * 1024L

    /** The timed lines of the `.lrc` next to [songPath], sorted, breaks included; none when there is no readable one. */
    fun read(songPath: String): List<LrcLine> {
        val lrc = LrcFormat.sidecarPath(songPath)?.let(::File) ?: return emptyList()
        if (!lrc.isFile || lrc.length() > MAX_LRC_BYTES) return emptyList()
        val text = try {
            lrc.readText()
        } catch (_: IOException) {
            // Unreadable now (removed, no access): the strip is simply not shown.
            return emptyList()
        }
        return LrcFormat.parseLrclib(text)
    }

    /** The lines the strip offers as starts: with text, at least [ToneWindow.MIN_LENGTH_MS] before the end, one per time. */
    fun starts(lines: List<LrcLine>, durationMs: Long): List<LrcLine> =
        lines.filter { it.text.isNotBlank() && it.timeMs in 0..(durationMs - ToneWindow.MIN_LENGTH_MS) }.distinctBy { it.timeMs }
}
