package app.lyrify.lyrics

import kotlinx.serialization.Serializable
import java.io.File
import java.util.Collections
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

    /**
     * Equal names, or one held in the other as whole words when the shorter one has a telling word ([tellingWord]):
     * "Band A" is in "Band A & Orchestra B", but "The" or "DJ" alone says nothing.
     */
    private fun sameArtist(queried: String, recordArtist: String): Boolean {
        val wanted = TrackNames.normalize(TrackNames.withoutFeat(queried))
        if (wanted.isEmpty() || recordArtist.isEmpty()) return false
        if (wanted == recordArtist) return true
        val shorter = if (wanted.length <= recordArtist.length) wanted else recordArtist
        val held = " $recordArtist ".contains(" $wanted ") || " $wanted ".contains(" $recordArtist ")
        return held && shorter.split(' ').any(::tellingWord)
    }
}

/**
 * A record LRCLIB may have for a song, offered for the user to check ([Candidates]). Only what tells records apart:
 * never their lyrics. [duration] is in seconds; [synced] is false for plain lyrics only.
 */
@Serializable
data class Candidate(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Double,
    val synced: Boolean,
)

/**
 * Records that may be the song when [Matcher] found nothing to write, for the user to pick from. Each one has lyrics
 * Samsung Music can show (or plain lyrics for the AI to time), a length within [Matcher.DURATION_TOLERANCE_SECONDS]
 * (otherwise the timing would be off), and lyrics not mostly in Arabic script. Best first: an artist named in the
 * song's tags, title or file name, then the closest length, then synced before plain; one per artist, title and kind.
 */
object Candidates {
    const val MAX_OFFERED: Int = 3
    const val MAX_SEARCHED: Int = 10


    /**
     * What a loose search found that may be [track]: also named like the song ([TrackNames.names]), the candidate's
     * title equal to one of its names or one holding the other as whole words, version tags and feat. aside.
     */
    fun offered(records: List<LrclibRecord>, track: LocalTrack): List<Candidate> {
        val names = TrackNames.names(track.title, File(track.path).name).map(::words).filter { it.isNotEmpty() }
        return ranked(records, track) { record -> words(record.trackName).let { title -> names.any { sameName(it, title) } } }.take(MAX_OFFERED)
    }

    /** What the user's own search found for [track]: any title, of the song's length. */
    fun searched(records: List<LrclibRecord>, track: LocalTrack): List<Candidate> = ranked(records, track) { true }.take(MAX_SEARCHED)

    /** More than half the letters of [text] are in Arabic script. */
    fun mostlyArabic(text: String): Boolean {
        var letters = 0
        var arabic = 0
        text.codePoints().forEach { codePoint ->
            if (Character.isLetter(codePoint)) {
                letters++
                if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.ARABIC) arabic++
            }
        }
        return 2 * arabic > letters
    }

    private fun ranked(records: List<LrclibRecord>, track: LocalTrack, named: (LrclibRecord) -> Boolean): List<Candidate> {
        if (track.durationMs <= 0) return emptyList()
        val seconds = track.durationMs / MS_PER_SECOND
        val haystack = " " + listOfNotNull(track.artist, track.album, track.title, File(track.path).name.substringBeforeLast('.'))
            .joinToString(" ") { TrackNames.normalize(it) } + " "
        return records.asSequence()
            .filter { !it.instrumental && !TrackNames.isInstrumental(it.trackName) }
            .mapNotNull { record -> candidate(record, track.durationMs) }
            .filter { (record, candidate) -> abs(candidate.duration - seconds) <= Matcher.DURATION_TOLERANCE_SECONDS && named(record) }
            // Only the lyrics that would be used.
            .filter { (record, candidate) -> !mostlyArabic(if (candidate.synced) record.syncedLyrics.orEmpty() else record.plainLyrics.orEmpty()) }
            .map { (_, candidate) -> candidate }
            .sortedWith(
                compareByDescending<Candidate> { namedIn(it.artist, haystack) }
                    .thenBy { abs(it.duration - seconds) }
                    .thenBy { !it.synced },
            )
            .distinctBy { Triple(TrackNames.normalize(it.artist), words(it.title), it.synced) }
            .toList()
    }

    /** [record] as a candidate when it has a duration and lyrics to use: synced lyrics Samsung Music can show, or plain ones. */
    private fun candidate(record: LrclibRecord, durationMs: Long): Pair<LrclibRecord, Candidate>? {
        val duration = record.duration ?: return null
        val synced = record.hasSyncedLyrics && LrcFormat.convert(record.syncedLyrics.orEmpty(), durationMs) is Conversion.Ready
        if (!synced && !record.hasPlainLyrics) return null
        return record to Candidate(record.id, record.trackName, record.artistName, record.albumName, duration, synced)
    }

    /** A title's words, version tags and feat. aside ("A & B" is "A and B"). */
    private fun words(title: String): List<String> =
        TrackNames.normalize(TrackNames.withoutFeat(TrackNames.withoutVersionTags(title))).split(' ').filter { it.isNotEmpty() }

    /** Equal, equal but for one word split in two ("Dont" and "Don t"), or one holds the other as whole words. */
    private fun sameName(name: List<String>, title: List<String>): Boolean =
        name == title || oneSplit(name, title) || oneSplit(title, name) || holds(name, title) || holds(title, name)

    /** [split] is [whole] with exactly one of its words written as two. */
    private fun oneSplit(split: List<String>, whole: List<String>): Boolean {
        if (split.size != whole.size + 1) return false
        // The first word that differs is the one split; with none, [split] only has a word more.
        val at = whole.indices.firstOrNull { split[it] != whole[it] } ?: return false
        return split[at] + split[at + 1] == whole[at] && split.drop(at + 2) == whole.drop(at + 1)
    }

    private fun holds(outer: List<String>, inner: List<String>): Boolean = inner.isNotEmpty() && Collections.indexOfSubList(outer, inner) >= 0

    /** The artist's whole name, or one of its telling words, is a whole word of the song's tags, title or file name. */
    private fun namedIn(artist: String, haystack: String): Boolean {
        val name = TrackNames.normalize(TrackNames.withoutFeat(artist))
        if (name.isEmpty()) return false
        return haystack.contains(" $name ") ||
            name.split(' ').any { tellingWord(it) && haystack.contains(" $it ") }
    }

    private const val MS_PER_SECOND: Double = 1_000.0
}

/** Words that say nothing about an artist on their own; shorter words than [MIN_ARTIST_WORD] never do. */
private val COMMON_WORDS = setOf("the", "and", "los", "las", "les", "der", "die", "das", "del")
private const val MIN_ARTIST_WORD: Int = 3

/** A word that can tell an artist apart: at least [MIN_ARTIST_WORD] long and not one of [COMMON_WORDS]. */
private fun tellingWord(word: String): Boolean = word.length >= MIN_ARTIST_WORD && word !in COMMON_WORDS
