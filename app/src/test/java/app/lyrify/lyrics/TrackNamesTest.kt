package app.lyrify.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackNamesTest {
    private fun first(artist: String?, title: String, album: String? = null): TrackQuery =
        TrackNames.queries(artist, title, album).first()

    // Titles

    @Test
    fun `a bitrate suffix is removed`() {
        assertEquals("Fear of the Dark", TrackNames.cleanTitle("Fear of the Dark (MP3_160K)"))
        assertEquals("Fear of the Dark", TrackNames.cleanTitle("Fear of the Dark (MP3_320K)"))
        assertEquals("Fear of the Dark", TrackNames.cleanTitle("Fear of the Dark [128kbps]"))
        assertEquals("Fear of the Dark", TrackNames.cleanTitle("Fear of the Dark(MP3_160K)"))
    }

    @Test
    fun `a track number prefix is removed`() {
        assertEquals("The Loner", TrackNames.cleanTitle("04 The Loner"))
        assertEquals("Title", TrackNames.cleanTitle("04. Title"))
        assertEquals("Title", TrackNames.cleanTitle("4. Title"))
        assertEquals("Title", TrackNames.cleanTitle("04 - Title"))
        assertEquals("Title", TrackNames.cleanTitle("12_Title"))
        assertEquals("Title", TrackNames.cleanTitle("7) Title"))
    }

    @Test
    fun `numbers that belong to the title stay`() {
        assertEquals("21 Guns", TrackNames.cleanTitle("21 Guns"))
        assertEquals("99 Luftballons", TrackNames.cleanTitle("99 Luftballons"))
        assertEquals("1979", TrackNames.cleanTitle("1979"))
        assertEquals("1979", TrackNames.cleanTitle("01 - 1979"))
        assertEquals("2 Minutes to Midnight", TrackNames.cleanTitle("2 Minutes to Midnight"))
    }

    @Test
    fun `junk suffixes are removed`() {
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo (Official Video)"))
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo (Official Music Video) [HD]"))
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo (Lyrics)"))
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo [HQ]"))
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo (Lyric Video)"))
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo (Official Audio)"))
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo - Official Video"))
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo | Lyrics"))
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo (4K)"))
        assertEquals("Nemo", TrackNames.cleanTitle("Nemo (www.mp3-site.com)"))
    }

    @Test
    fun `version tags are kept in the first variant`() {
        assertEquals("Nemo (Live)", TrackNames.cleanTitle("Nemo (Live)"))
        assertEquals("Nemo (Remastered 2011)", TrackNames.cleanTitle("Nemo (Remastered 2011)"))
    }

    @Test
    fun `unbalanced parentheses are closed and stray closers dropped`() {
        assertEquals(
            "World Of Fantasy (Demo) (Bonus Track)",
            TrackNames.cleanTitle("World Of Fantasy (Demo) (Bonus Track"),
        )
        assertEquals("Title", TrackNames.cleanTitle("Title)"))
        assertEquals("World Of Fantasy", TrackNames.withoutVersionTags("World Of Fantasy (Demo) (Bonus Track"))
    }

    @Test
    fun `a leading bracket tag is removed`() {
        assertEquals("G Air - Bach", TrackNames.cleanTitle("[Janinto Voice] G Air - Bach"))
        assertEquals("Song", TrackNames.cleanTitle("【MV】 Song"))
    }

    @Test
    fun `a leading parenthesis that is part of the title stays`() {
        assertEquals("(I Can't Get No) Satisfaction", TrackNames.cleanTitle("(I Can't Get No) Satisfaction"))
    }

    @Test
    fun `stray quotes are removed`() {
        assertEquals("I Pooh", TrackNames.cleanTitle("\"I Pooh\""))
        assertEquals("Heroes", TrackNames.cleanTitle("“Heroes”"))
        assertEquals("Don't Stop Me Now", TrackNames.cleanTitle("Don't Stop Me Now"))
        assertEquals("Song", TrackNames.cleanTitle("'Song'"))
        assertEquals("Song", TrackNames.cleanTitle("Song\""))
    }

    @Test
    fun `uploader handles are removed`() {
        assertEquals("Song", TrackNames.cleanTitle("Song @MetalChannel"))
        assertEquals("Song", TrackNames.cleanTitle("@MetalChannel - Song"))
        assertEquals("Song", TrackNames.cleanTitle("Song | @music_bot"))
    }

    @Test
    fun `whitespace and dangling separators are tidied`() {
        assertEquals("Song Name", TrackNames.cleanTitle("  Song   Name  - "))
        assertEquals("Song", TrackNames.cleanTitle("\uFEFFSong"))
    }

    @Test
    fun `a title that is only junk keeps its text`() {
        assertEquals("(Official Video)", TrackNames.cleanTitle("(Official Video)"))
    }

    @Test
    fun `version tags are removed for the second variant`() {
        assertEquals("Nemo", TrackNames.withoutVersionTags("Nemo (Live)"))
        assertEquals("Nemo", TrackNames.withoutVersionTags("Nemo [Remastered]"))
        assertEquals("Nemo", TrackNames.withoutVersionTags("Nemo - 2011 Remaster"))
        assertEquals("Nemo", TrackNames.withoutVersionTags("Nemo - Live at Wacken"))
        assertEquals("Nemo", TrackNames.withoutVersionTags("Nemo - Radio Edit"))
        assertEquals("Nemo - Part II", TrackNames.withoutVersionTags("Nemo - Part II"))
    }

    @Test
    fun `instrumental tags are never removed`() {
        assertEquals("Nemo (Instrumental)", TrackNames.withoutVersionTags("Nemo (Instrumental)"))
        assertEquals("Nemo - Karaoke Version", TrackNames.withoutVersionTags("Nemo - Karaoke Version"))
    }

    @Test
    fun `instrumental and karaoke titles are recognised`() {
        assertTrue(TrackNames.isInstrumental("Nemo (Instrumental)"))
        assertTrue(TrackNames.isInstrumental("Nemo - Instrumental Version"))
        assertTrue(TrackNames.isInstrumental("Nemo [Karaoke]"))
        assertFalse(TrackNames.isInstrumental("Nemo"))
        assertFalse(TrackNames.isInstrumental("Nemo (Live)"))
    }

    @Test
    fun `featured artists are removed`() {
        assertEquals("Song", TrackNames.withoutFeat("Song (feat. Someone)"))
        assertEquals("Song", TrackNames.withoutFeat("Song [ft. Someone]"))
        assertEquals("Song", TrackNames.withoutFeat("Song feat. Someone"))
        assertEquals("Song", TrackNames.withoutFeat("Song Ft. Someone & Other"))
        assertEquals("Song", TrackNames.withoutFeat("Song featuring Someone"))
        assertEquals("Artist", TrackNames.withoutFeat("Artist feat. Someone"))
        assertEquals("Artist", TrackNames.withoutFeat("Artist ft Someone"))
        assertEquals("Left Behind", TrackNames.withoutFeat("Left Behind"))
        assertEquals("Soft Feather", TrackNames.withoutFeat("Soft Feather"))
    }

    // Artists and albums

    @Test
    fun `a YouTube topic channel suffix is removed`() {
        assertEquals("Nightwish", TrackNames.cleanArtist("Nightwish - Topic"))
        assertEquals("Nightwish", TrackNames.cleanArtist("Nightwish - topic"))
        assertEquals("Nightwish", TrackNames.cleanArtist("Nightwish – Topic"))
        assertEquals("Nightwish", TrackNames.cleanArtist("Nightwish — Topic"))
    }

    @Test
    fun `unknown artists are null`() {
        assertNull(TrackNames.cleanArtist(null))
        assertNull(TrackNames.cleanArtist(""))
        assertNull(TrackNames.cleanArtist("  "))
        assertNull(TrackNames.cleanArtist("<unknown>"))
        assertNull(TrackNames.cleanArtist("Unknown Artist"))
        assertNull(TrackNames.cleanArtist("unknown"))
    }

    @Test
    fun `artist quotes, handles and VEVO are removed`() {
        assertEquals("I Pooh", TrackNames.cleanArtist("\"I Pooh\""))
        assertEquals("Nightwish", TrackNames.cleanArtist("NightwishVEVO"))
        assertEquals("Nightwish", TrackNames.cleanArtist("Nightwish @metal_bot"))
    }

    @Test
    fun `an album equal to the artist is ignored`() {
        assertNull(TrackNames.cleanAlbum("Nightwish", "Nightwish"))
        assertNull(TrackNames.cleanAlbum("nightwish ", "Nightwish - Topic"))
        assertNull(TrackNames.cleanAlbum("<unknown>", "Nightwish"))
        assertNull(TrackNames.cleanAlbum("Telegram", "Nightwish"))
        assertNull(TrackNames.cleanAlbum("snaptube", "Nightwish"))
        assertNull(TrackNames.cleanAlbum("Music", "Nightwish"))
        assertEquals("Once", TrackNames.cleanAlbum("Once", "Nightwish"))
    }

    // Query variants

    @Test
    fun `a clean tag gives one query with the album`() {
        assertEquals(
            listOf(TrackQuery("Nightwish", "Nemo", "Once")),
            TrackNames.queries("Nightwish", "Nemo", "Once"),
        )
    }

    @Test
    fun `variants go from cleaned to without version tags to without feat`() {
        assertEquals(
            listOf(
                TrackQuery("Ayreon feat. Someone", "Day Two (feat. Someone) (Live)", "01011001"),
                TrackQuery("Ayreon feat. Someone", "Day Two"),
                TrackQuery("Ayreon", "Day Two"),
            ),
            TrackNames.queries("Ayreon feat. Someone", "04. Day Two (feat. Someone) (Live) (MP3_160K)", "01011001"),
        )
    }

    @Test
    fun `the album is dropped when it equals the artist`() {
        assertEquals(TrackQuery("Gary Moore", "The Loner", null), first("Gary Moore - Topic", "04 The Loner", "Gary Moore"))
    }

    @Test
    fun `artist and title crammed into the title are split both ways`() {
        assertEquals(
            listOf(
                TrackQuery("Metallica", "One"),
                TrackQuery("One", "Metallica"),
                TrackQuery(null, "Metallica - One"),
            ),
            TrackNames.queries("<unknown>", "Metallica - One (Official Video)", null),
        )
    }

    @Test
    fun `an en dash also splits artist and title`() {
        assertEquals(TrackQuery("Metallica", "One"), first(null, "Metallica – One"))
    }

    @Test
    fun `a bracket tag before an artist title pair is dropped before splitting`() {
        val queries = TrackNames.queries(null, "[Janinto Voice] G Air - Bach", null)
        assertEquals(TrackQuery("G Air", "Bach"), queries[0])
        assertEquals(TrackQuery("Bach", "G Air"), queries[1])
    }

    @Test
    fun `an artist repeated at the start of the title is removed`() {
        assertEquals(TrackQuery("Metallica", "One"), first("Metallica", "Metallica - One"))
    }

    @Test
    fun `a title without an artist anywhere gives no query`() {
        assertEquals(emptyList<TrackQuery>(), TrackNames.queries(null, "Some Song", null))
        assertEquals(emptyList<TrackQuery>(), TrackNames.queries("<unknown>", "Intro (Live)", "Live"))
    }

    @Test
    fun `stray quotes around the artist do not reach the query`() {
        assertEquals(TrackQuery("I Pooh", "Pensiero"), first("\"I Pooh\"", "Pensiero"))
    }

    @Test
    fun `queries are distinct and capped`() {
        val queries = TrackNames.queries(null, "A feat. B - C (Live) (feat. D)", null)
        assertTrue(queries.size <= TrackNames.MAX_QUERIES)
        assertEquals(queries.size, queries.map { (it.artist?.lowercase() to it.title.lowercase()) }.distinct().size)
    }

    // Comparison

    @Test
    fun `normalize folds case, accents, punctuation and ampersands`() {
        assertEquals("mago de oz", TrackNames.normalize("Mägo de Oz"))
        assertEquals("simon and garfunkel", TrackNames.normalize("Simon & Garfunkel"))
        assertEquals("strasse", TrackNames.normalize("Straße"))
        assertEquals("don t stop me now", TrackNames.normalize("Don't  Stop, Me Now!"))
    }
}
