package app.lyrify.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherTest {
    private val nemo = listOf(TrackQuery("Nightwish", "Nemo"))
    private val synced = "[00:01.00]a\n[00:02.00]b\n[00:03.00]c"

    private fun record(
        id: Long,
        duration: Double? = 276.0,
        synced: String? = this.synced,
        plain: String? = "a\nb\nc",
        instrumental: Boolean = false,
        track: String = "Nemo",
        artist: String = "Nightwish",
    ) = LrclibRecord(
        id = id,
        trackName = track,
        artistName = artist,
        albumName = "Once",
        duration = duration,
        instrumental = instrumental,
        plainLyrics = plain,
        syncedLyrics = synced,
    )

    private fun match(vararg records: LrclibRecord, duration: Double = 276.0, queries: List<TrackQuery> = nemo): Match =
        Matcher.match(records.toList(), queries, duration)

    @Test
    fun `synced lyrics within three seconds are chosen`() {
        assertEquals(Match.Synced(record(1, duration = 278.9)), match(record(1, duration = 278.9)))
        assertEquals(Match.Synced(record(2, duration = 273.0)), match(record(2, duration = 273.0)))
    }

    @Test
    fun `the closest synced duration wins`() {
        val far = record(1, duration = 278.5)
        val close = record(2, duration = 275.5)
        val farther = record(3, duration = 273.5)
        assertEquals(Match.Synced(close), match(far, close, farther))
    }

    @Test
    fun `equally close candidates keep their order`() {
        val first = record(1, duration = 277.0)
        val second = record(2, duration = 275.0)
        assertEquals(Match.Synced(first), match(first, second))
    }

    @Test
    fun `synced lyrics only at other durations are a mismatch with the closest one`() {
        val near = record(1, duration = 280.0)
        val far = record(2, duration = 300.0)
        assertEquals(Match.DurationMismatch(near), match(far, near))
    }

    @Test
    fun `a synced record without a duration is a mismatch`() {
        val unknown = record(1, duration = null)
        assertEquals(Match.DurationMismatch(unknown), match(unknown))
    }

    @Test
    fun `an instrumental record is instrumental`() {
        val instrumental = record(1, synced = null, plain = null, instrumental = true)
        assertEquals(Match.Instrumental(instrumental), match(instrumental))
    }

    @Test
    fun `matching synced lyrics beat an instrumental record`() {
        val instrumental = record(1, synced = null, plain = null, instrumental = true)
        val good = record(2)
        assertEquals(Match.Synced(good), match(instrumental, good))
    }

    @Test
    fun `an instrumental record at the right duration beats synced lyrics of another version`() {
        val instrumental = record(1, synced = null, plain = null, instrumental = true)
        val other = record(2, duration = 320.0)
        assertEquals(Match.Instrumental(instrumental), match(other, instrumental))
    }

    @Test
    fun `plain lyrics only are plain only, closest duration first`() {
        val far = record(1, synced = null, duration = 300.0)
        val near = record(2, synced = null, duration = 277.0)
        assertEquals(Match.PlainOnly(near, durationMatched = true), match(far, near))
    }

    @Test
    fun `plain lyrics of this recording beat synced lyrics of another version`() {
        val plain = record(1, synced = null, duration = 278.0)
        val otherVersion = record(2, duration = 320.0)
        assertEquals(Match.PlainOnly(plain, durationMatched = true), match(otherVersion, plain))
    }

    @Test
    fun `plain lyrics only at other durations are plain only, flagged as not matching`() {
        val far = record(1, synced = null, duration = 330.0)
        val nearer = record(2, synced = null, duration = 300.0)
        assertEquals(Match.PlainOnly(nearer, durationMatched = false), match(far, nearer))
    }

    @Test
    fun `synced lyrics of another version beat plain lyrics of another version`() {
        val plain = record(1, synced = null, duration = 300.0)
        val otherVersion = record(2, duration = 320.0)
        assertEquals(Match.DurationMismatch(otherVersion), match(plain, otherVersion))
    }

    @Test
    fun `an instrumental record beats plain lyrics`() {
        val instrumental = record(1, synced = null, plain = null, instrumental = true, duration = 320.0)
        val plain = record(2, synced = null)
        assertEquals(Match.Instrumental(instrumental), match(plain, instrumental))
    }

    @Test
    fun `Light Your Cigar - plain lyrics of the exact recording win over a synced live version`() {
        val queries = listOf(TrackQuery("Millenium", "Light Your Cigar"))
        fun cigar(id: Long, duration: Double, track: String = "Light Your Cigar", synced: String? = null) =
            record(id, duration = duration, synced = synced, track = track, artist = "Millenium")
        val exact = cigar(1, 449.0)
        val bestOf = cigar(2, 369.0)
        val bestOfLonger = cigar(3, 376.0)
        val live = cigar(4, 377.0, track = "Light Your Cigar (live version)", synced = synced)
        assertEquals(
            Match.PlainOnly(exact, durationMatched = true),
            Matcher.match(listOf(live, bestOf, bestOfLonger, exact), queries, 448.2),
        )
        assertEquals(Match.DurationMismatch(live), Matcher.match(listOf(live, bestOf, bestOfLonger), queries, 448.2))
    }

    @Test
    fun `blank lyrics count as none`() {
        assertEquals(Match.NotFound, match(record(1, synced = "  ", plain = "")))
    }

    @Test
    fun `no candidates is not found`() {
        assertEquals(Match.NotFound, match())
    }

    @Test
    fun `a record for another song is ignored`() {
        assertEquals(Match.NotFound, match(record(1, track = "Nemo Part 2")))
        assertEquals(Match.NotFound, match(record(1, artist = "Metallica")))
    }

    @Test
    fun `relevance tolerates version tags, feat, case and accents`() {
        assertTrue(Matcher.isRelevant(record(1, track = "Nemo - Remastered 2011"), nemo))
        assertTrue(Matcher.isRelevant(record(1, track = "nemo (feat. Someone)"), nemo))
        assertTrue(Matcher.isRelevant(record(1, artist = "Nightwish & Friends"), nemo))
        assertTrue(Matcher.isRelevant(record(1, track = "Nëmo"), nemo))
        assertTrue(Matcher.isRelevant(record(1, artist = "Night"), nemo).not())
    }

    @Test
    fun `an artist containing the queried artist as a word is relevant`() {
        val queries = listOf(TrackQuery("Ayreon", "Day Two"))
        assertTrue(Matcher.isRelevant(record(1, track = "Day Two", artist = "Ayreon, Someone"), queries))
        assertFalse(Matcher.isRelevant(record(1, track = "Day Two", artist = "Ayreonics"), queries))
    }

    @Test
    fun `a query without an artist never makes a record relevant`() {
        val queries = listOf(TrackQuery(null, "Nemo"))
        assertFalse(Matcher.isRelevant(record(1, artist = "Anyone"), queries))
        assertFalse(Matcher.isRelevant(record(1, artist = "Nightwish"), queries))
        assertEquals(Match.NotFound, match(record(1, artist = "Anyone"), queries = queries))
    }

    @Test
    fun `a title-only search still helps when another query names the artist`() {
        val queries = listOf(TrackQuery("Metallica", "One"), TrackQuery(null, "Metallica - One"))
        assertTrue(Matcher.isRelevant(record(1, track = "One", artist = "Metallica"), queries))
        assertFalse(Matcher.isRelevant(record(1, track = "Metallica - One", artist = "Cover Band"), queries))
    }

    @Test
    fun `any query can make a record relevant`() {
        val swapped = listOf(TrackQuery("G Air", "Bach"), TrackQuery("Bach", "G Air"))
        assertTrue(Matcher.isRelevant(record(1, track = "G Air", artist = "Johann Sebastian Bach"), swapped))
    }

    @Test
    fun `vocal lyrics are never relevant to an instrumental title`() {
        val queries = listOf(TrackQuery("Nightwish", "Nemo (Instrumental)"))
        assertFalse(Matcher.isRelevant(record(1, track = "Nemo"), queries))
        assertTrue(Matcher.isRelevant(record(1, track = "Nemo (Instrumental)"), queries))
    }
}
