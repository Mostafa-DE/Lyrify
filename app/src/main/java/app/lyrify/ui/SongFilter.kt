package app.lyrify.ui

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.TrackNames

/** The song list's filter chips, in the order they show; [ALL] has no [status]. */
enum class SongFilter(val status: LyricsStatus?) {
    ALL(null),
    NEEDS_CHECK(LyricsStatus.NEEDS_CHECK),
    SYNCED(LyricsStatus.SYNCED),
    AI_LYRICS(LyricsStatus.AI_TRANSCRIBED),
    AI_TIMED(LyricsStatus.AI_ALIGNED),
    PLAIN_ONLY(LyricsStatus.PLAIN_ONLY),
    NOT_FOUND(LyricsStatus.NOT_FOUND),
    OTHER_VERSION(LyricsStatus.DURATION_MISMATCH),
    INSTRUMENTAL(LyricsStatus.INSTRUMENTAL),
    AI_INSTRUMENTAL(LyricsStatus.AI_INSTRUMENTAL),
    AI_NOT_CONFIDENT(LyricsStatus.AI_REJECTED),
    AI_FAILED(LyricsStatus.AI_FAILED),
    RETRYING(LyricsStatus.ERROR),
    NO_ARTIST(LyricsStatus.UNIDENTIFIED),
    EXISTING(LyricsStatus.EXISTING),
    REMOVED(LyricsStatus.REMOVED),
    WRITING(LyricsStatus.WRITING),
    ;

    fun accepts(entry: LedgerEntry): Boolean = status == null || entry.status == status

    companion object {
        fun of(status: LyricsStatus): SongFilter = entries.first { it.status == status }
    }
}

/** What the song list shows: [songs] newest first, a count per chip for the search, and the [chips] worth showing. */
data class SongList(val songs: List<LedgerEntry>, val counts: Map<SongFilter, Int>, val chips: List<SongFilter>)

/**
 * The ledger's songs matching [query] (in the title or the artist, case and accents aside)
 * and [filter]. Chip counts follow the search; chips with no song are left out, except
 * [SongFilter.ALL] and the selected one.
 */
fun songList(ledger: LedgerState, query: String, filter: SongFilter): SongList {
    val words = TrackNames.normalize(query)
    val found = ledger.entries.values.filter { words.isEmpty() || matches(it, words) }
    val counts = SongFilter.entries.associateWith { chip -> found.count(chip::accepts) }
    return SongList(
        songs = found.filter(filter::accepts).sortedWith(compareByDescending<LedgerEntry> { it.updatedAt }.thenBy { it.path }),
        counts = counts,
        chips = SongFilter.entries.filter { it == SongFilter.ALL || it == filter || (counts[it] ?: 0) > 0 },
    )
}

/** The songs of the "Check these" screen: those waiting for the user to pick lyrics, with something to pick, newest first. */
fun checkList(ledger: LedgerState): List<LedgerEntry> =
    ledger.entries.values
        .filter { it.status == LyricsStatus.NEEDS_CHECK && it.candidates.isNotEmpty() }
        .sortedWith(compareByDescending<LedgerEntry> { it.updatedAt }.thenBy { it.path })

private fun matches(entry: LedgerEntry, words: String): Boolean =
    TrackNames.normalize(entry.title).contains(words) || TrackNames.normalize(entry.artist.orEmpty()).contains(words)
