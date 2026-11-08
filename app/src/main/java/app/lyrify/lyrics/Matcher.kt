package app.lyrify.lyrics

import kotlinx.serialization.Serializable
import java.io.File
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
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
    internal fun sameArtist(queried: String, recordArtist: String): Boolean {
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
 * never their lyrics. [duration] is in seconds; [synced] is false for plain lyrics only. [differingLines]: how many lines
 * tell its lyrics from those of a card above that has most of them ([Candidates.differingLines]); null for none.
 */
@Serializable
data class Candidate(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Double,
    val synced: Boolean,
    val differingLines: Int? = null,
)

/**
 * Records that may be the song when [Matcher] found nothing to write, for the user to pick from. Each one has lyrics
 * Samsung Music can show (or plain lyrics for the AI to time), a length within [Matcher.DURATION_TOLERANCE_SECONDS]
 * (otherwise the timing would be off), and lyrics not mostly in Arabic script. Best first: an artist named in the
 * song's tags, title or file name, then the closest length, then synced before plain; one per artist, title and kind.
 * The same lyrics show once, however the records name the song ([mergeIdenticalLyrics]).
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
        return ranked(records, track, MAX_OFFERED) { record -> words(record.trackName).let { title -> names.any { sameName(it, title) } } }
    }

    /** What the user's own search found for [track]: any title, of the song's length. */
    fun searched(records: List<LrclibRecord>, track: LocalTrack): List<Candidate> = ranked(records, track, MAX_SEARCHED) { true }

    /**
     * The lines of lyrics [text] as they are compared: LRC timestamps and tags such as `[ar:]` or `[Chorus]` left out
     * (synced lyrics a line per time, in time order, as plain lyrics write a repeated line out), then lowercase, without
     * apostrophes, any other punctuation and spaces as one space, and no empty lines.
     */
    fun normalizedLyricLines(text: String): List<String> {
        val timed = LrcFormat.parseLrclib(text)
        val lines = if (timed.isEmpty()) text.lines() else timed.map { it.text }
        return lines
            .map { line -> Normalizer.normalize(line.replace(APOSTROPHES, ""), Normalizer.Form.NFKC).replace(LYRIC_TAG, " ") }
            .map { it.lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * [candidates] (best first) with the same lyrics shown once, [lyrics] holding each one's [normalizedLyricLines] by id.
     * Of those alike, synced beats plain, then the closest length to [track]'s, then the better ranked; the cards left
     * keep their order. Lyrics not known, or without a word, are never merged.
     */
    fun mergeIdenticalLyrics(candidates: List<Candidate>, lyrics: Map<Long, List<String>>, track: LocalTrack): List<Candidate> {
        val seconds = track.durationMs / MS_PER_SECOND
        val kept = candidates.filter { !lyrics[it.id].isNullOrEmpty() }
            .groupBy { lyrics.getValue(it.id) }
            // Ties keep list order.
            .mapValues { (_, alike) -> alike.minWith(compareBy<Candidate> { !it.synced }.thenBy { abs(it.duration - seconds) }) }
        return candidates.filter { candidate -> lyrics[candidate.id].let { lines -> lines.isNullOrEmpty() || kept.getValue(lines).id == candidate.id } }
    }

    /**
     * How many lines tell lyrics [a] and [b] ([normalizedLyricLines]) apart when they share most of them: the longer one's
     * lines less their longest common subsequence, which holds at least half the shorter one's lines. Null when they
     * share fewer, one has no lines, or they are too long to compare so ([MAX_COMPARED]).
     */
    fun differingLines(a: List<String>, b: List<String>): Int? {
        if (a.isEmpty() || b.isEmpty() || a.size.toLong() * b.size > MAX_COMPARED) return null
        val common = commonLines(a, b)
        return if (2 * common >= minOf(a.size, b.size)) maxOf(a.size, b.size) - common else null
    }

    /**
     * [candidates] (best first) with the same lyrics shown once ([mergeIdenticalLyrics]), at most [max]; one whose lyrics
     * share most lines with a card above says how many differ from the best ranked such card ([Candidate.differingLines]).
     */
    internal fun compared(candidates: List<Candidate>, lyrics: Map<Long, List<String>>, track: LocalTrack, max: Int): List<Candidate> {
        val shown = mergeIdenticalLyrics(candidates, lyrics, track).take(max)
        return shown.mapIndexed { index, candidate ->
            val lines = lyrics[candidate.id]
            val differing = lines?.let { shown.take(index).firstNotNullOfOrNull { above -> lyrics[above.id]?.let { differingLines(it, lines) } } }
            candidate.copy(differingLines = differing)
        }
    }

    /** The lyrics of [record] that [candidate] would use: the synced ones when it is synced, the plain ones otherwise. */
    internal fun usedLyrics(record: LrclibRecord, candidate: Candidate): String =
        if (candidate.synced) record.syncedLyrics.orEmpty() else record.plainLyrics.orEmpty()

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

    private fun ranked(records: List<LrclibRecord>, track: LocalTrack, max: Int, named: (LrclibRecord) -> Boolean): List<Candidate> {
        if (track.durationMs <= 0) return emptyList()
        val seconds = track.durationMs / MS_PER_SECOND
        val haystack = " " + listOfNotNull(track.artist, track.album, track.title, File(track.path).name.substringBeforeLast('.'))
            .joinToString(" ") { TrackNames.normalize(it) } + " "
        // Each candidate with the lyrics it would use.
        val usable = records.asSequence()
            .filter { !it.instrumental && !TrackNames.isInstrumental(it.trackName) }
            .mapNotNull { record -> candidate(record, track.durationMs) }
            .filter { (record, candidate) -> abs(candidate.duration - seconds) <= Matcher.DURATION_TOLERANCE_SECONDS && named(record) }
            .map { (record, candidate) -> candidate to usedLyrics(record, candidate) }
            .filter { (_, lyrics) -> !mostlyArabic(lyrics) }
            .toList()
        val ranked = usable.map { (candidate, _) -> candidate }
            .sortedWith(
                compareByDescending<Candidate> { namedIn(it.artist, haystack) }
                    .thenBy { abs(it.duration - seconds) }
                    .thenBy { !it.synced },
            )
            .distinctBy { Triple(TrackNames.normalize(it.artist), words(it.title), it.synced) }
        val texts = usable.associate { (candidate, lyrics) -> candidate.id to lyrics }
        return compared(ranked, ranked.associate { it.id to normalizedLyricLines(texts.getValue(it.id)) }, track, max)
    }

    /** The length of the longest common subsequence of [a] and [b], a row at a time. */
    private fun commonLines(a: List<String>, b: List<String>): Int {
        var above = IntArray(b.size + 1)
        for (line in a) {
            val row = IntArray(b.size + 1)
            b.forEachIndexed { j, other -> row[j + 1] = if (line == other) above[j] + 1 else maxOf(above[j + 1], row[j]) }
            above = row
        }
        return above[b.size]
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

    /** At most this many pairs of lines are compared for [differingLines]: 200 lines each. */
    private const val MAX_COMPARED: Long = 40_000

    /** Apostrophes, left out of words ("Don't" is "Dont"); fullwidth too, as NFKC would make it one. */
    private val APOSTROPHES = Regex("['‘’‛`´ʼ＇]")

    /** What is not sung in a line: a tag (`[ar:Name]`, `[Chorus]`, a timestamp) or a word's time (`<00:12.34>`). */
    private val LYRIC_TAG = Regex("""\[[^\[\]]*]|<\d+:\d+(?:[.:]\d+)?>""")

    /** Punctuation, symbols and spaces between words. */
    private val NOT_WORD = Regex("""[^\p{L}\p{M}\p{N}]+""")
}

/** Words that say nothing about an artist on their own; shorter words than [MIN_ARTIST_WORD] never do. */
private val COMMON_WORDS = setOf("the", "and", "los", "las", "les", "der", "die", "das", "del")
private const val MIN_ARTIST_WORD: Int = 3

/** A word that can tell an artist apart: at least [MIN_ARTIST_WORD] long and not one of [COMMON_WORDS]. */
private fun tellingWord(word: String): Boolean = word.length >= MIN_ARTIST_WORD && word !in COMMON_WORDS
