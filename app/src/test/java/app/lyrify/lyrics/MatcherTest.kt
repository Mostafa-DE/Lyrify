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

    // Candidates for the user to check

    /** "05- Song A" with no artist tag, 200 s long, in a file named after a band. */
    private val song = LocalTrack("/music/05- Band A - Song A (MP3_320K).mp3", "05- Song A", null, null, 200_000)

    private fun found(
        id: Long,
        track: String = "Song A",
        artist: String = "Somebody",
        duration: Double? = 200.0,
        synced: String? = this.synced,
        plain: String? = "w0001\nw0002\nw0003",
        instrumental: Boolean = false,
    ) = LrclibRecord(id, track, artist, "Album", duration, instrumental, plain, synced)

    private fun offered(vararg records: LrclibRecord, track: LocalTrack = song): List<Long> = Candidates.offered(records.toList(), track).map { it.id }

    @Test
    fun `a candidate has the song's title and a length within three seconds`() {
        assertEquals(listOf(1L, 2L, 3L), offered(found(1), found(2, artist = "B", duration = 203.0), found(3, artist = "C", duration = 197.0)))
        assertEquals(emptyList<Long>(), offered(found(4, duration = 203.5), found(5, duration = null), found(6, track = "Song B")))
    }

    @Test
    fun `titles match without version tags or feat, or when one holds the other as whole words`() {
        assertEquals(listOf(1L), offered(found(1, track = "Song A (Live) [feat. Singer]")))
        assertEquals(listOf(2L), offered(found(2, track = "Band A - Song A")))
        assertEquals(listOf(3L), offered(found(3, track = "Song A Reprise")))
        // "Song" is a whole word of "Band A - Song A"; "Song Ab" and "Son" are not.
        assertEquals(listOf(4L), offered(found(4, track = "Song")))
        assertEquals(emptyList<Long>(), offered(found(5, track = "Song Ab"), found(6, track = "Son")))
    }

    @Test
    fun `an underscore that stood for an apostrophe or an ampersand still matches`() {
        val lost = LocalTrack("/music/Band_s Song _ Dance.mp3", "Band_s Song _ Dance", null, null, 200_000)
        assertEquals(listOf(1L, 2L), offered(found(1, track = "Band's Song & Dance"), found(2, track = "Bands Song and Dance", artist = "B"), track = lost))
    }

    @Test
    fun `plain lyrics are offered too, labelled plain`() {
        val candidates = Candidates.offered(listOf(found(1, synced = null), found(2, artist = "Other", duration = 201.0)), song)
        assertEquals(listOf(false, true), candidates.map { it.synced })
        assertEquals(Candidate(1, "Song A", "Somebody", "Album", 200.0, synced = false), candidates.first())
    }

    @Test
    fun `instrumental records and records without lyrics are never offered`() {
        assertEquals(
            emptyList<Long>(),
            offered(found(1, instrumental = true), found(2, synced = null, plain = null), found(3, track = "Song A (Instrumental)")),
        )
    }

    @Test
    fun `synced lyrics Samsung Music could not show count as plain, or as nothing`() {
        val broken = "[00:01.00]w0001"
        val candidates = Candidates.offered(listOf(found(1, synced = broken), found(2, artist = "Other", synced = broken, plain = null)), song)
        assertEquals(listOf(Candidate(1, "Song A", "Somebody", "Album", 200.0, synced = false)), candidates)
    }

    @Test
    fun `an artist named in the tags, the title or the file name goes first, then the closest length, then synced`() {
        val tagged = LocalTrack("/music/Song A.mp3", "Song A", "Uploader", "Album C", 200_000)
        assertEquals(
            listOf(2L, 3L, 4L),
            offered(
                found(1, artist = "Stranger", duration = 200.0),
                found(4, artist = "The Uploader", duration = 202.5, synced = null),
                found(3, artist = "The Uploader", duration = 202.5),
                found(2, artist = "Band C", duration = 202.0),
                track = tagged.copy(album = "Band C Live"),
            ),
        )
        // From the file name: "Band A" is in "05- Band A - Song A".
        assertEquals(listOf(2L, 1L), offered(found(1, artist = "Stranger", duration = 200.0), found(2, artist = "Band A feat. Singer", duration = 202.9)))
    }

    @Test
    fun `short or common artist words are not evidence on their own`() {
        // "A" is a word of the file name, but a one-letter word says nothing about "The A Crew".
        assertEquals(listOf(1L, 2L), offered(found(1, artist = "Stranger", duration = 200.5), found(2, artist = "The A Crew", duration = 201.0)))
    }

    @Test
    fun `at most three, one per artist, title and kind of lyrics`() {
        val copies = (1L..5L).map { found(it, artist = "Band A", duration = 200.0 + it * 0.5) }
        assertEquals(listOf(1L), offered(*copies.toTypedArray()))
        val many = (1L..6L).map { found(it, artist = "Artist $it") }
        assertEquals(Candidates.MAX_OFFERED, Candidates.offered(many, song).size)
    }

    @Test
    fun `mostly Arabic lyrics are never offered`() {
        // Letters of the alphabet, not lyrics.
        val arabic = "[00:01.00]ا ب ت\n[00:02.00]ث ج ح\n[00:03.00]خ د ذ w0001"
        assertEquals(emptyList<Long>(), offered(found(1, synced = arabic, plain = null), found(2, synced = null, plain = "ا ب ت ث\nج ح")))
        assertTrue(Candidates.mostlyArabic("ا ب ت ث ج w0001"))
        assertFalse(Candidates.mostlyArabic("w0001 w0002 w0003 ا ب"))
        assertFalse(Candidates.mostlyArabic("12 34"))
    }

    @Test
    fun `a song without a length gets no candidates`() {
        assertEquals(emptyList<Long>(), offered(found(1, duration = 2.0), track = song.copy(durationMs = 0)))
    }

    @Test
    fun `a search shows songs of the same length whatever their title, up to ten`() {
        val results = (1L..12L).map { found(it, track = "Title $it", artist = "Artist $it", duration = 200.0 + it * 0.2) } + found(20, duration = 210.0)
        val searched = Candidates.searched(results, song)
        assertEquals((1L..10L).toList(), searched.map { it.id })
        assertEquals(Candidates.MAX_SEARCHED, searched.size)
    }

    @Test
    fun `an artist inside a longer name counts only when it holds a telling word`() {
        val theA = listOf(TrackQuery("The A", "Nemo"))
        assertTrue(Matcher.isRelevant(record(1, artist = "The A"), theA))
        assertFalse(Matcher.isRelevant(record(1, artist = "The A Crew"), theA))
        assertFalse(Matcher.isRelevant(record(1, artist = "DJ"), listOf(TrackQuery("DJ Someone", "Nemo"))))
        assertTrue(Matcher.isRelevant(record(1, artist = "Band A & Orchestra B"), listOf(TrackQuery("Band A", "Nemo"))))
    }

    @Test
    fun `only the lyrics that would be used are checked for Arabic script`() {
        // Letters of the alphabet, not lyrics.
        val arabic = "[00:01.00]ا ب ت\n[00:02.00]ث ج ح\n[00:03.00]خ د ذ"
        assertEquals(listOf(1L), offered(found(1, plain = "ا ب ت ث"), found(2, artist = "B", synced = arabic, plain = "w0001")))
    }

    @Test
    fun `words run together match only one split word, never a missing one`() {
        val glue = LocalTrack("/music/Paper and Glue.mp3", "Paper and Glue", null, null, 200_000)
        assertEquals(emptyList<Long>(), offered(found(1, track = "Paper Glue"), track = glue))
        val dont = LocalTrack("/music/Dont Wait.mp3", "Dont Wait", null, null, 200_000)
        assertEquals(listOf(2L), offered(found(2, track = "Don't Wait"), track = dont))
        assertEquals(emptyList<Long>(), offered(found(3, track = "Do Nt Wa It"), track = dont))
    }
}
