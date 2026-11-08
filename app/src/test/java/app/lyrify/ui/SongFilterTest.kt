package app.lyrify.ui

import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import app.lyrify.data.recorded
import app.lyrify.lyrics.Candidate
import app.lyrify.lyrics.LocalTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class SongFilterTest {
    private fun song(path: String, title: String, artist: String?, status: LyricsStatus, updatedAt: Long) =
        recorded(null, LocalTrack(path, title, artist, null, 200_000), status, null, updatedAt)

    private val ledger = LedgerState()
        .put(song("/a.mp3", "Paper Lanterns", "Kröner Ensemble", LyricsStatus.SYNCED, 5))
        .put(song("/b.mp3", "Salt Window", "Kröner Ensemble", LyricsStatus.NOT_FOUND, 9))
        .put(song("/c.mp3", "Glass Orchard", "Velvet Transit", LyricsStatus.SYNCED, 7))
        .put(song("/d.mp3", "Intro", null, LyricsStatus.UNIDENTIFIED, 1))
        .put(song("/e.mp3", "Cinder Hymn", "The Low Bells", LyricsStatus.ERROR, 7))

    private fun list(query: SongQuery, of: LedgerState = ledger) = songList(of, query, NOW, ZoneOffset.UTC)

    private fun paths(text: String = "", group: SongGroup? = null, status: LyricsStatus? = null, sort: SongSort = SongSort.RECENT) =
        list(SongQuery(text, group, status, sort)).songs.map { it.path }

    // Search and filters

    @Test
    fun `every song shows, newest first, ties by path`() {
        assertEquals(listOf("/b.mp3", "/c.mp3", "/e.mp3", "/a.mp3", "/d.mp3"), paths())
    }

    @Test
    fun `search matches title or artist, ignoring case and accents`() {
        assertEquals(listOf("/b.mp3", "/a.mp3"), paths("kroner"))
        assertEquals(listOf("/b.mp3", "/a.mp3"), paths("  KRÖNER "))
        assertEquals(listOf("/a.mp3"), paths("paper lan"))
        assertEquals(listOf("/c.mp3"), paths("velvet t"))
        assertEquals(emptyList<String>(), paths("zzz"))
    }

    @Test
    fun `a group shows its statuses, a status narrows the group`() {
        assertEquals(listOf("/c.mp3", "/a.mp3"), paths(group = SongGroup.HAS_LYRICS))
        assertEquals(listOf("/b.mp3", "/d.mp3"), paths(group = SongGroup.NO_LYRICS))
        assertEquals(listOf("/d.mp3"), paths(group = SongGroup.NO_LYRICS, status = LyricsStatus.UNIDENTIFIED))
        assertEquals(listOf("/e.mp3"), paths(group = SongGroup.WAITING))
        assertEquals(emptyList<String>(), paths(group = SongGroup.TO_CHECK))
    }

    @Test
    fun `search, group and status combine`() {
        assertEquals(listOf("/a.mp3"), paths("kroner", SongGroup.HAS_LYRICS))
        assertEquals(listOf("/b.mp3"), paths("kroner", SongGroup.NO_LYRICS, LyricsStatus.NOT_FOUND))
        assertEquals(emptyList<String>(), paths("kroner", SongGroup.NO_LYRICS, LyricsStatus.UNIDENTIFIED))
    }

    @Test
    fun `group and status counts follow the search`() {
        val all = list(SongQuery())
        assertEquals(5, all.total)
        assertEquals(mapOf(SongGroup.HAS_LYRICS to 2, SongGroup.TO_CHECK to 0, SongGroup.WAITING to 1, SongGroup.NO_LYRICS to 2), all.groupCounts)
        assertEquals(2, all.statusCounts[LyricsStatus.SYNCED])
        assertEquals(1, all.statusCounts[LyricsStatus.ERROR])
        assertEquals(0, all.statusCounts[LyricsStatus.REMOVED])
        assertEquals(LyricsStatus.entries.toSet(), all.statusCounts.keys)

        val searched = list(SongQuery("kroner"))
        assertEquals(2, searched.total)
        assertEquals(mapOf(SongGroup.HAS_LYRICS to 1, SongGroup.TO_CHECK to 0, SongGroup.WAITING to 0, SongGroup.NO_LYRICS to 1), searched.groupCounts)
        assertEquals(1, searched.statusCounts[LyricsStatus.NOT_FOUND])
        assertEquals(0, searched.statusCounts[LyricsStatus.ERROR])
    }

    @Test
    fun `counts do not depend on the group or status picked, the shown count does`() {
        val picked = list(SongQuery("", SongGroup.NO_LYRICS, LyricsStatus.UNIDENTIFIED))
        assertEquals(5, picked.total)
        assertEquals(1, picked.shown)
        assertEquals(2, picked.groupCounts[SongGroup.HAS_LYRICS])
        assertEquals(1, picked.statusCounts[LyricsStatus.NOT_FOUND])
    }

    @Test
    fun `a query is filtered while it has text, a group or a status`() {
        assertFalse(SongQuery().filtered)
        assertFalse(SongQuery("  ", sort = SongSort.TITLE).filtered)
        assertTrue(SongQuery("a").filtered)
        assertTrue(SongQuery(group = SongGroup.WAITING).filtered)
    }

    @Test
    fun `status chips show only for a group with two or more statuses with songs`() {
        assertEquals(emptyList<LyricsStatus>(), list(SongQuery()).statuses)
        // Has lyrics: only synced songs.
        assertEquals(emptyList<LyricsStatus>(), list(SongQuery(group = SongGroup.HAS_LYRICS)).statuses)
        // No lyrics: not found and no artist, in the group's order.
        assertEquals(listOf(LyricsStatus.NOT_FOUND, LyricsStatus.UNIDENTIFIED), list(SongQuery(group = SongGroup.NO_LYRICS)).statuses)
        // The search leaves one status in the group: no chips.
        assertEquals(emptyList<LyricsStatus>(), list(SongQuery("kroner", SongGroup.NO_LYRICS)).statuses)
    }

    @Test
    fun `a selected status keeps its chip while the search hides its songs`() {
        val picked = list(SongQuery("salt", SongGroup.NO_LYRICS, LyricsStatus.UNIDENTIFIED))
        assertEquals(listOf(LyricsStatus.NOT_FOUND, LyricsStatus.UNIDENTIFIED), picked.statuses)
        assertEquals(emptyList<String>(), picked.songs.map { it.path })
    }

    @Test
    fun `every one of the 16 statuses can still be shown on its own`() {
        val every = LyricsStatus.entries.foldIndexed(LedgerState()) { i, state, status ->
            state.put(song("/s$i.mp3", "Song $i", "Artist $i", status, i.toLong())).put(song("/t$i.mp3", "Tune $i", "Artist $i", status, i.toLong()))
        }
        LyricsStatus.entries.forEach { status ->
            val picked = list(SongQuery(group = status.group, status = status), every)
            assertEquals(status.name, setOf(status), picked.songs.map { it.status }.toSet())
            assertEquals(status.name, 2, picked.songs.size)
            if (status.group.statuses.size > 1) assertEquals(status.name, status.group.statuses, picked.statuses)
        }
    }

    @Test
    fun `the library counts groups from the ledger's counts`() {
        assertEquals(
            mapOf(SongGroup.HAS_LYRICS to 2, SongGroup.TO_CHECK to 0, SongGroup.WAITING to 1, SongGroup.NO_LYRICS to 2),
            groupCounts(ledger.counts()),
        )
    }

    // Sort and sections

    private val dated = LedgerState()
        .put(song("/1.mp3", "Morning Tide", "Odile Varga", LyricsStatus.SYNCED, NOW - HOUR))
        .put(song("/2.mp3", "Late Bloom", "Hollis Brandt", LyricsStatus.SYNCED, START_OF_TODAY + 1))
        .put(song("/3.mp3", "Ember Postcards", "Ruth Anselm", LyricsStatus.SYNCED, START_OF_TODAY - 1))
        .put(song("/4.mp3", "Quiet Engines", null, LyricsStatus.UNIDENTIFIED, NOW - 3 * DAY))
        .put(song("/5.mp3", "4 Walls", "Émile Roux", LyricsStatus.NOT_FOUND, NOW - 7 * DAY))
        .put(song("/6.mp3", "Échoes", "88 Keys", LyricsStatus.SYNCED, NOW - 30 * DAY))

    private fun sections(sort: SongSort) = list(SongQuery(sort = sort), dated).sections.map { it.section to it.songs.map { song -> song.path } }

    @Test
    fun `recent first groups by today, yesterday, this week and earlier`() {
        assertEquals(
            listOf(
                Section.Today to listOf("/1.mp3", "/2.mp3"),
                Section.Yesterday to listOf("/3.mp3"),
                Section.ThisWeek to listOf("/4.mp3"),
                Section.Earlier to listOf("/5.mp3", "/6.mp3"),
            ),
            sections(SongSort.RECENT),
        )
    }

    @Test
    fun `title order groups by first letter, accents aside, digits and symbols under hash first`() {
        assertEquals(
            listOf(
                Section.Letter("#") to listOf("/5.mp3"),
                Section.Letter("E") to listOf("/6.mp3", "/3.mp3"),
                Section.Letter("L") to listOf("/2.mp3"),
                Section.Letter("M") to listOf("/1.mp3"),
                Section.Letter("Q") to listOf("/4.mp3"),
            ),
            sections(SongSort.TITLE),
        )
    }

    @Test
    fun `artist order groups by the artist's first letter, songs with none or a number under hash`() {
        assertEquals(
            listOf(
                Section.Letter("#") to listOf("/4.mp3", "/6.mp3"),
                Section.Letter("E") to listOf("/5.mp3"),
                Section.Letter("H") to listOf("/2.mp3"),
                Section.Letter("O") to listOf("/1.mp3"),
                Section.Letter("R") to listOf("/3.mp3"),
            ),
            sections(SongSort.ARTIST),
        )
    }

    @Test
    fun `sections follow the filter`() {
        assertEquals(
            listOf(Section.Letter("#") to listOf("/5.mp3"), Section.Letter("Q") to listOf("/4.mp3")),
            list(SongQuery(group = SongGroup.NO_LYRICS, sort = SongSort.TITLE), dated).sections.map { it.section to it.songs.map { s -> s.path } },
        )
        assertEquals(emptyList<SongSection>(), list(SongQuery("zzz"), dated).sections)
    }

    @Test
    fun `the query survives as saved text`() {
        val query = SongQuery("salt", SongGroup.NO_LYRICS, LyricsStatus.NOT_FOUND, SongSort.ARTIST)
        assertEquals(query, SongQuery.restore(SongQuery.save(query)))
        assertEquals(SongQuery(), SongQuery.restore(SongQuery.save(SongQuery())))
    }

    // The check list

    private val candidate = Candidate(3, "Song K", "Artist K", null, 200.0, synced = true)

    private fun toCheck(path: String, title: String, updatedAt: Long, candidates: List<Candidate> = listOf(candidate)) =
        recorded(null, LocalTrack(path, title, null, null, 200_000), LyricsStatus.NEEDS_CHECK, null, updatedAt, candidates = candidates)

    @Test
    fun `songs to check are the To check group`() {
        val withChecks = ledger.put(toCheck("/k.mp3", "Song K", 20)).put(toCheck("/l.mp3", "Song L", 21))
        val picked = list(SongQuery(group = SongGroup.TO_CHECK), withChecks)
        assertEquals(listOf("/l.mp3", "/k.mp3"), picked.songs.map { it.path })
        assertEquals(2, picked.groupCounts[SongGroup.TO_CHECK])
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
    fun `picked plain lyrics get synced only where the AI runs and is on`() {
        assertEquals(PlainTiming.AI_ON, PlainTiming.of(supported = true, enabled = true))
        assertEquals(PlainTiming.AI_OFF, PlainTiming.of(supported = true, enabled = false))
        assertEquals(PlainTiming.NO_AI, PlainTiming.of(supported = false, enabled = true))
        assertEquals(PlainTiming.NO_AI, PlainTiming.of(supported = false, enabled = false))
    }

    private companion object {
        const val HOUR: Long = 3_600_000
        const val DAY: Long = 24 * HOUR
        val NOW: Long = Instant.parse("2026-11-06T15:00:00Z").toEpochMilli()
        val START_OF_TODAY: Long = Instant.parse("2026-11-06T00:00:00Z").toEpochMilli()
    }
}
