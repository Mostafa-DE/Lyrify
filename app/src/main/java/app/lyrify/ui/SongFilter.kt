package app.lyrify.ui

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.lyrics.TrackNames

/** The song list's filter chips, in the order they show; [ALL] has no [status]. */
enum class SongFilter(val status: LyricsStatus?) {
    ALL(null),
    SYNCED(LyricsStatus.SYNCED),
    PLAIN_ONLY(LyricsStatus.PLAIN_ONLY),
    NOT_FOUND(LyricsStatus.NOT_FOUND),
    OTHER_VERSION(LyricsStatus.DURATION_MISMATCH),
    INSTRUMENTAL(LyricsStatus.INSTRUMENTAL),
    RETRYING(LyricsStatus.ERROR),
    NO_ARTIST(LyricsStatus.UNIDENTIFIED),
    EXISTING(LyricsStatus.EXISTING),
    REMOVED(LyricsStatus.REMOVED),
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

private fun matches(entry: LedgerEntry, words: String): Boolean =
    TrackNames.normalize(entry.title).contains(words) || TrackNames.normalize(entry.artist.orEmpty()).contains(words)
