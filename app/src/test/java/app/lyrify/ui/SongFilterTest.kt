package app.lyrify.ui

import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.data.recorded
import app.lyrify.lyrics.Candidate
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
            listOf(
                "ALL", "NEEDS_CHECK", "SYNCED", "AI_LYRICS", "AI_TIMED", "PLAIN_ONLY", "NOT_FOUND", "OTHER_VERSION", "INSTRUMENTAL", "AI_INSTRUMENTAL",
                "AI_NOT_CONFIDENT", "AI_FAILED", "RETRYING", "NO_ARTIST", "EXISTING", "REMOVED", "WRITING",
            ),
            SongFilter.entries.map { it.name },
        )
        LyricsStatus.entries.forEach { assertEquals(it, SongFilter.of(it).status) }
    }

    @Test
    fun `AI songs have chips of their own`() {
        val withAi = ledger
            .put(song("/f.mp3", "Song F", "Artist F", LyricsStatus.AI_TRANSCRIBED, 11))
            .put(song("/g.mp3", "Song G", "Artist G", LyricsStatus.AI_ALIGNED, 12))
            .put(song("/h.mp3", "Song H", "Artist H", LyricsStatus.AI_REJECTED, 13))
            .put(song("/i.mp3", "Song I", "Artist I", LyricsStatus.AI_FAILED, 14))
            .put(song("/j.mp3", "Song J", "Artist J", LyricsStatus.AI_INSTRUMENTAL, 15))
        val list = songList(withAi, "", SongFilter.AI_LYRICS)
        assertEquals(listOf("/f.mp3"), list.songs.map { it.path })
        assertEquals(1, list.counts[SongFilter.AI_TIMED])
        assertEquals(1, list.counts[SongFilter.AI_NOT_CONFIDENT])
        assertEquals(1, list.counts[SongFilter.AI_FAILED])
        assertEquals(1, list.counts[SongFilter.AI_INSTRUMENTAL])
        assertEquals(listOf("/g.mp3"), songList(withAi, "song g", SongFilter.AI_TIMED).songs.map { it.path })
        assertEquals(
            listOf(
                SongFilter.ALL, SongFilter.SYNCED, SongFilter.AI_LYRICS, SongFilter.AI_TIMED, SongFilter.NOT_FOUND, SongFilter.AI_INSTRUMENTAL,
                SongFilter.AI_NOT_CONFIDENT, SongFilter.AI_FAILED, SongFilter.RETRYING, SongFilter.NO_ARTIST,
            ),
            list.chips,
        )
    }

    // The check list

    private val candidate = Candidate(3, "Song K", "Artist K", null, 200.0, synced = true)

    private fun toCheck(path: String, title: String, updatedAt: Long, candidates: List<Candidate> = listOf(candidate)) =
        recorded(null, LocalTrack(path, title, null, null, 200_000), LyricsStatus.NEEDS_CHECK, null, updatedAt, candidates = candidates)

    @Test
    fun `songs to check have a chip of their own, first after All`() {
        val withChecks = ledger.put(toCheck("/k.mp3", "Song K", 20)).put(toCheck("/l.mp3", "Song L", 21))
        val list = songList(withChecks, "", SongFilter.NEEDS_CHECK)
        assertEquals(listOf("/l.mp3", "/k.mp3"), list.songs.map { it.path })
        assertEquals(2, list.counts[SongFilter.NEEDS_CHECK])
        assertEquals(listOf(SongFilter.ALL, SongFilter.NEEDS_CHECK, SongFilter.SYNCED), list.chips.take(3))
    }

    @Test
    fun `the check list holds songs to check that have candidates, newest first`() {
        val withChecks = ledger
            .put(toCheck("/k.mp3", "Song K", 20))
            .put(toCheck("/m.mp3", "Song M", 22))
            .put(toCheck("/l.mp3", "Song L", 22))
            .put(toCheck("/n.mp3", "Song N", 23, candidates = emptyList()))
        assertEquals(listOf("/l.mp3", "/m.mp3", "/k.mp3"), checkList(withChecks).map { it.path })
        assertEquals(emptyList<String>(), checkList(ledger).map { it.path })
    }

    @Test
    fun `picked plain lyrics get timed only where the AI runs and is on`() {
        assertEquals(PlainTiming.AI_ON, PlainTiming.of(supported = true, enabled = true))
        assertEquals(PlainTiming.AI_OFF, PlainTiming.of(supported = true, enabled = false))
        assertEquals(PlainTiming.NO_AI, PlainTiming.of(supported = false, enabled = true))
        assertEquals(PlainTiming.NO_AI, PlainTiming.of(supported = false, enabled = false))
    }
}
