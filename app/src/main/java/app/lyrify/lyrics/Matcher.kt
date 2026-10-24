package app.lyrify.lyrics

import kotlin.math.abs

/** What LRCLIB has for a track, best first. Only [Synced] is ever written. */
sealed interface Match {
    /** Synced lyrics timed for this recording (duration within the tolerance). */
    data class Synced(val record: LrclibRecord) : Match

    data class Instrumental(val record: LrclibRecord) : Match

    /** Synced lyrics exist, but only for versions of another length: their timing would be wrong. */
    data class DurationMismatch(val record: LrclibRecord) : Match

    /**
     * Untimed lyrics only; kept for v2, which will time them against the audio.
     * [durationMatched] is false when they come from a version of another length.
     */
    data class PlainOnly(val record: LrclibRecord, val durationMatched: Boolean = true) : Match

    data object NotFound : Match
}

/**
 * Picks among LRCLIB records. Records for another song (by name) are ignored first, because
 * a duration within a few seconds is common between unrelated songs.
 */
object Matcher {
    /** Synced lyrics are used only when LRCLIB's duration is this close to the file's. */
    const val DURATION_TOLERANCE_SECONDS: Double = 3.0

    /**
     * In order (closest duration wins each step, ties keep list order): synced within the
     * tolerance, instrumental, plain lyrics within the tolerance (the text of this exact
     * recording, for v2), synced at other durations, plain lyrics at other durations.
     */
    fun match(candidates: List<LrclibRecord>, queries: List<TrackQuery>, durationSeconds: Double): Match {
        val relevant = candidates.filter { isRelevant(it, queries) }
        val synced = relevant.filter { it.hasSyncedLyrics }
        val plain = relevant.filter { it.hasPlainLyrics }
        fun distance(record: LrclibRecord): Double = record.duration?.let { abs(it - durationSeconds) } ?: Double.POSITIVE_INFINITY
        fun List<LrclibRecord>.closest(): LrclibRecord? = minByOrNull(::distance)
        fun List<LrclibRecord>.closestInTime(): LrclibRecord? = filter { distance(it) <= DURATION_TOLERANCE_SECONDS }.closest()

        synced.closestInTime()?.let { return Match.Synced(it) }
        relevant.filter { it.instrumental }.closest()?.let { return Match.Instrumental(it) }
        plain.closestInTime()?.let { return Match.PlainOnly(it, durationMatched = true) }
        synced.closest()?.let { return Match.DurationMismatch(it) }
        plain.closest()?.let { return Match.PlainOnly(it, durationMatched = false) }
        return Match.NotFound
    }

    /**
     * The record names the same song as one of [queries] that has an artist: equal titles
     * once version tags, feat. and punctuation are set aside, and the query's artist found as
     * whole words in the record's artist (or the other way round). A title-only query never
     * counts: "Intro" would match anyone's. Instrumental tags are kept, so vocal lyrics never
     * match an instrumental title.
     */
    fun isRelevant(record: LrclibRecord, queries: List<TrackQuery>): Boolean {
        val title = comparableTitle(record.trackName)
        val artist = TrackNames.normalize(record.artistName)
        return queries.any { query ->
            query.artist != null && comparableTitle(query.title) == title && sameArtist(query.artist, artist)
        }
    }

    private fun comparableTitle(title: String): String =
        TrackNames.normalize(TrackNames.withoutFeat(TrackNames.withoutVersionTags(title)))

    private fun sameArtist(queried: String, recordArtist: String): Boolean {
        val wanted = TrackNames.normalize(TrackNames.withoutFeat(queried))
        if (wanted.isEmpty() || recordArtist.isEmpty()) return false
        return " $recordArtist ".contains(" $wanted ") || " $wanted ".contains(" $recordArtist ")
    }
}
