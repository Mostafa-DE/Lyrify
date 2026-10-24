package app.lyrify.ui

import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.data.recorded
import app.lyrify.lyrics.LocalTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class SongFilterTest {
    private fun song(path: String, title: String, artist: String?, status: LyricsStatus, updatedAt: Long) =
        recorded(null, LocalTrack(path, title, artist, null, 200_000), status, null, updatedAt)

    private val ledger = LedgerState()
        .put(song("/a.mp3", "Ace of Spades", "Motörhead", LyricsStatus.SYNCED, 5))
        .put(song("/b.mp3", "Overkill", "Motörhead", LyricsStatus.NOT_FOUND, 9))
        .put(song("/c.mp3", "Nemo", "Nightwish", LyricsStatus.SYNCED, 7))
        .put(song("/d.mp3", "Intro", null, LyricsStatus.UNIDENTIFIED, 1))
        .put(song("/e.mp3", "Toxicity", "System of a Down", LyricsStatus.ERROR, 7))

    private fun paths(query: String = "", filter: SongFilter = SongFilter.ALL) = songList(ledger, query, filter).songs.map { it.path }

    @Test
    fun `every song shows, newest first, ties by path`() {
        assertEquals(listOf("/b.mp3", "/c.mp3", "/e.mp3", "/a.mp3", "/d.mp3"), paths())
    }

    @Test
    fun `search matches title or artist, ignoring case and accents`() {
        assertEquals(listOf("/b.mp3", "/a.mp3"), paths("motorhead"))
        assertEquals(listOf("/b.mp3", "/a.mp3"), paths("  MOTÖR "))
        assertEquals(listOf("/a.mp3"), paths("ace of"))
        assertEquals(listOf("/c.mp3"), paths("nightw"))
        assertEquals(emptyList<String>(), paths("soad"))
    }

    @Test
    fun `search and filter combine`() {
        assertEquals(listOf("/a.mp3"), paths("motorhead", SongFilter.SYNCED))
        assertEquals(listOf("/c.mp3", "/a.mp3"), paths(filter = SongFilter.SYNCED))
        assertEquals(listOf("/d.mp3"), paths(filter = SongFilter.NO_ARTIST))
    }

    @Test
    fun `chip counts follow the search`() {
        val all = songList(ledger, "", SongFilter.ALL).counts
        assertEquals(5, all[SongFilter.ALL])
        assertEquals(2, all[SongFilter.SYNCED])
        assertEquals(1, all[SongFilter.RETRYING])
        assertEquals(0, all[SongFilter.REMOVED])
        val searched = songList(ledger, "motorhead", SongFilter.ALL).counts
        assertEquals(2, searched[SongFilter.ALL])
        assertEquals(1, searched[SongFilter.SYNCED])
        assertEquals(1, searched[SongFilter.NOT_FOUND])
        assertEquals(0, searched[SongFilter.RETRYING])
    }

    @Test
    fun `chips with no songs are hidden, except All and the selected one`() {
        assertEquals(
            listOf(SongFilter.ALL, SongFilter.SYNCED, SongFilter.NOT_FOUND, SongFilter.RETRYING, SongFilter.NO_ARTIST),
            songList(ledger, "", SongFilter.ALL).chips,
        )
        assertEquals(listOf(SongFilter.ALL), songList(ledger, "zzz", SongFilter.ALL).chips)
        assertEquals(listOf(SongFilter.ALL, SongFilter.REMOVED), songList(ledger, "zzz", SongFilter.REMOVED).chips)
    }

    @Test
    fun `chips come in a fixed order and cover every status once`() {
        assertEquals(
            listOf("ALL", "SYNCED", "PLAIN_ONLY", "NOT_FOUND", "OTHER_VERSION", "INSTRUMENTAL", "RETRYING", "NO_ARTIST", "EXISTING", "REMOVED"),
            SongFilter.entries.map { it.name },
        )
        LyricsStatus.entries.forEach { assertEquals(it, SongFilter.of(it).status) }
    }
}
