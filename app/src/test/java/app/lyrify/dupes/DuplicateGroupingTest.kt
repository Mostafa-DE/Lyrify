package app.lyrify.dupes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateGroupingTest {
    private fun file(
        id: Long,
        title: String = "Paper Comet",
        artist: String? = "Mira Solen",
        album: String? = "Night Kites",
        durationMs: Long = 214_000,
        size: Long = 5_000_000 + id,
        mimeType: String? = "audio/mpeg",
        bitrate: Long? = 192_000,
        addedAt: Long? = BASE + id,
        name: String = "$id Paper Comet.mp3",
        folder: String = "Music/a/",
    ) = AudioFile(
        id = id,
        path = "/storage/emulated/0/$folder$name",
        relativePath = folder,
        displayName = name,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        size = size,
        mimeType = mimeType,
        bitrate = bitrate,
        addedAt = addedAt,
    )

    private fun groups(files: List<AudioFile>, identical: Map<Long, String> = emptyMap(), lyrics: Set<Long> = emptySet()) =
        DuplicateGrouping.groups(files, identical) { it.id in lyrics }

    private fun ids(group: DuplicateGroup): Set<Long> = group.files.map { it.id }.toSet()

    // Identical files

    @Test
    fun `copies with the same content are identical, whatever their names`() {
        val a = file(1, size = 7_000_000, name = "03 Paper Comet.mp3", folder = "Music/kjb/")
        val b = file(2, size = 7_000_000, title = "paper comet (copy)", artist = null, name = "Paper Comet (1).mp3", folder = "Music/de/")
        val group = groups(listOf(a, b), identical = mapOf(1L to "k", 2L to "k")).single()
        assertEquals(Confidence.IDENTICAL, group.confidence)
        assertEquals(setOf(1L, 2L), ids(group))
    }

    @Test
    fun `different content keys are not identical`() {
        val result = groups(
            listOf(file(1, title = "Lanterns", artist = "A"), file(2, title = "Harbor", artist = "B")),
            identical = mapOf(1L to "k1", 2L to "k2"),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `an identical group keeps the oldest added copy, even when another has lyrics or a better name`() {
        val newer = file(1, addedAt = BASE + 500)
        val older = file(2, addedAt = BASE + 100, artist = null)
        val group = groups(listOf(newer, older), identical = mapOf(1L to "k", 2L to "k"), lyrics = setOf(1L)).single()
        assertEquals(2L, group.keep.id)
        assertEquals(listOf(2L, 1L), group.files.map { it.id })
    }

    @Test
    fun `an identical group keeps a copy outside download and messaging folders, even a newer one`() {
        val telegram = file(1, addedAt = BASE, folder = "Telegram/", name = "08 Paper Comet.mp3")
        val kjb = file(2, addedAt = BASE + 500, folder = "kjb/", name = "08 Paper Comet.mp3")
        val group = groups(listOf(telegram, kjb), identical = mapOf(1L to "k", 2L to "k")).single()
        assertEquals(listOf(2L, 1L), group.files.map { it.id })
    }

    @Test
    fun `download and messaging folders are told by a whole folder name or a messaging app's media folder, whatever the case`() {
        for (folder in listOf(
            "Download/", "Downloads/", "Telegram/", "WhatsApp/", "Bluetooth/", "Documents/", "download/a/", "Telegram/Telegram Audio/",
            "Android/media/org.telegram.messenger/Telegram/Telegram Audio/", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/",
            "Android/media/org.telegram.messenger/Audio/",
        )) {
            val moved = file(1, addedAt = BASE, folder = folder)
            val kept = file(2, addedAt = BASE + 500, folder = "Music/a/")
            assertEquals(folder, 2L, groups(listOf(moved, kept), identical = mapOf(1L to "k", 2L to "k")).single().keep.id)
        }
    }

    @Test
    fun `a folder that only has a download word in its name is a music folder`() {
        for (folder in listOf("Music/Download Festival/", "Music/Telegram Songs/", "Music/my downloads/", "Music/WhatsApp Hits/", "Music/Telegram/", "Music/Download/")) {
            val named = file(1, addedAt = BASE, folder = folder)
            val other = file(2, addedAt = BASE + 500, folder = "Music/a/")
            assertEquals(folder, 1L, groups(listOf(named, other), identical = mapOf(1L to "k", 2L to "k")).single().keep.id)
        }
    }

    @Test
    fun `a song named after a download folder is not in one`() {
        val named = file(1, addedAt = BASE, folder = "Music/a/", name = "Download Telegram.mp3")
        val other = file(2, addedAt = BASE + 500, folder = "Music/b/")
        assertEquals(1L, groups(listOf(named, other), identical = mapOf(1L to "k", 2L to "k")).single().keep.id)
    }

    @Test
    fun `Music and the main music folders come before other folders, then the oldest added`() {
        val recordings = file(1, addedAt = BASE, folder = "Recordings/")
        val music = file(2, addedAt = BASE + 500, folder = "Music/a/")
        assertEquals(2L, groups(listOf(recordings, music), identical = mapOf(1L to "k", 2L to "k")).single().keep.id)

        // kjb/ holds most of the library: a main music folder, before Recordings/ though not in Music/.
        val library = (10L..29L).map { file(it, title = "Song $it", artist = "Band $it", durationMs = 100_000 + it * 10_000, folder = "kjb/", name = "$it.mp3") }
        val kjb = file(3, addedAt = BASE + 500, folder = "kjb/")
        assertEquals(3L, groups(library + recordings + kjb, identical = mapOf(1L to "k", 3L to "k")).single().keep.id)

        val older = file(4, addedAt = BASE, folder = "Music/b/")
        assertEquals(4L, groups(listOf(music, older), identical = mapOf(2L to "k", 4L to "k")).single().keep.id)
    }

    @Test
    fun `for other songs the folder breaks a bitrate tie before lossless, tags and lyrics`() {
        val telegram = file(1, bitrate = 320_000, mimeType = "audio/flac", folder = "Telegram/", name = "Paper Comet.flac")
        val music = file(2, bitrate = 320_000, album = null, folder = "Music/a/")
        assertEquals(2L, groups(listOf(telegram, music), lyrics = setOf(1L)).single().keep.id)
        val better = file(3, bitrate = 320_000, folder = "Telegram/")
        assertEquals(3L, groups(listOf(better, file(4, bitrate = 128_000))).single().keep.id)
    }

    // Same song

    @Test
    fun `the same title, artist and length in another bitrate is the same song`() {
        val group = groups(listOf(file(1, bitrate = 128_000), file(2, bitrate = 320_000, durationMs = 215_200))).single()
        assertEquals(Confidence.SAME_SONG, group.confidence)
        assertEquals(setOf(1L, 2L), ids(group))
    }

    @Test
    fun `download junk, track numbers and featured artists do not make another song`() {
        val clean = file(1, title = "Paper Comet", artist = "Mira Solen")
        val junk = file(2, title = "04. Paper Comet (Official Video) (MP3_320K)", artist = "Mira Solen feat. Tomas Reed")
        val group = groups(listOf(clean, junk)).single()
        assertEquals(Confidence.SAME_SONG, group.confidence)
    }

    @Test
    fun `a title that repeats the artist in front is the same song`() {
        val group = groups(listOf(file(1), file(2, title = "Mira Solen - Paper Comet"))).single()
        assertEquals(Confidence.SAME_SONG, group.confidence)
    }

    @Test
    fun `another title or another artist with the same title is never the same song`() {
        assertTrue(groups(listOf(file(1), file(2, title = "Velvet Machinery"))).isEmpty())
        assertEquals(Confidence.MAYBE, groups(listOf(file(1), file(2, artist = "Northbound Owls"))).single().confidence)
    }

    // Maybe

    @Test
    fun `a missing artist makes it a maybe`() {
        val group = groups(listOf(file(1), file(2, artist = null))).single()
        assertEquals(Confidence.MAYBE, group.confidence)
    }

    @Test
    fun `an unknown artist tag counts as missing`() {
        assertEquals(Confidence.MAYBE, groups(listOf(file(1), file(2, artist = "<unknown>"))).single().confidence)
    }

    @Test
    fun `a title taken from the file name is a maybe, read like a file name`() {
        val tagged = file(1)
        val untagged = file(2, title = "07_paper_comet", artist = "<unknown>", album = null, name = "07_paper_comet.mp3")
        val group = groups(listOf(tagged, untagged)).single()
        assertEquals(Confidence.MAYBE, group.confidence)
    }

    @Test
    fun `an artist and title in an untagged title are a maybe`() {
        val group = groups(listOf(file(1), file(2, title = "Mira Solen - Paper Comet", artist = null, album = null))).single()
        assertEquals(Confidence.MAYBE, group.confidence)
    }

    @Test
    fun `a maybe never matches on a blank or very short title`() {
        assertTrue(groups(listOf(file(1, title = "Up"), file(2, title = "Up", artist = null))).isEmpty())
        assertTrue(groups(listOf(file(1, title = "Mira Solen - Go", artist = null), file(2, title = "Go", artist = "Band B"))).isEmpty())
        assertTrue(groups(listOf(file(1, title = "1979", artist = null), file(2, title = "1979"))).isEmpty())
        assertEquals(1, groups(listOf(file(1, title = "Hush"), file(2, title = "Hush", artist = null))).size)
        assertEquals(1, groups(listOf(file(1, title = "Go Go"), file(2, title = "Go Go", artist = null))).size)
        assertEquals(1, groups(listOf(file(1, title = "Mira Solen - Go On", artist = null), file(2, title = "Go On", artist = "Band B"))).size)
    }

    @Test
    fun `a short title still makes the same song with the same artist`() {
        assertEquals(Confidence.SAME_SONG, groups(listOf(file(1, title = "Up"), file(2, title = "Up", bitrate = 320_000))).single().confidence)
    }

    @Test
    fun `two untagged songs of one artist are not a maybe for sharing the artist`() {
        val result = groups(listOf(file(1, title = "Mira Solen - Paper Comet", artist = null), file(2, title = "Mira Solen - Velvet Machinery", artist = null)))
        assertTrue(result.isEmpty())
    }

    // Versions

    @Test
    fun `a live, edited, remixed, acoustic, demo or remastered version is never grouped with the studio one`() {
        val studio = file(1)
        for ((title, album) in listOf(
            "Paper Comet (Live)" to "Night Kites",
            "Paper Comet" to "Live at the Pier",
            "Paper Comet - Radio Edit" to "Night Kites",
            "Paper Comet (Remix)" to "Night Kites",
            "Paper Comet" to "Acoustic Sessions",
            "Paper Comet (Demo)" to "Night Kites",
            "Paper Comet" to "Night Kites (Remastered 2020)",
            "Paper Comet (Club Mix)" to "Night Kites",
            "Paper Comet (Single Version)" to "Night Kites",
            "Paper Comet" to "MTV Unplugged",
            "Paper Comet" to "BBC Sessions",
            "Paper Comet (Re-recorded)" to "Night Kites",
            "Paper Comet (Extended)" to "Night Kites",
            "Paper Comet" to "Night Kites (Deluxe)",
            "Paper Comet (Bonus Track)" to "Night Kites",
        )) {
            assertTrue("$title / $album", groups(listOf(studio, file(2, title = title, album = album))).isEmpty())
            assertTrue("$title / $album, no artist", groups(listOf(studio, file(2, title = title, artist = null, album = album))).isEmpty())
        }
    }

    @Test
    fun `two copies of the same live version are grouped`() {
        val group = groups(listOf(file(1, album = "Live at the Pier"), file(2, album = "Live at the Pier", bitrate = 320_000))).single()
        assertEquals(Confidence.SAME_SONG, group.confidence)
    }

    @Test
    fun `a version and the studio one are grouped when their files are identical`() {
        val group = groups(listOf(file(1), file(2, album = "Live at the Pier")), identical = mapOf(1L to "k", 2L to "k")).single()
        assertEquals(Confidence.IDENTICAL, group.confidence)
    }

    @Test
    fun `a word that only looks like a version word, inside another word, is not one`() {
        val group = groups(listOf(file(1, title = "Delivered Demons"), file(2, title = "Delivered Demons", bitrate = 320_000))).single()
        assertEquals(Confidence.SAME_SONG, group.confidence)
        assertTrue(groups(listOf(file(1, title = "Delivered Demons"), file(2, title = "Delivered Demons", album = "Demos"))).isNotEmpty())
    }

    // Length

    @Test
    fun `lengths up to 2 s apart match, more do not`() {
        assertEquals(1, groups(listOf(file(1, durationMs = 214_000), file(2, durationMs = 216_000))).size)
        assertTrue(groups(listOf(file(1, durationMs = 214_000), file(2, durationMs = 216_001))).isEmpty())
        assertTrue(groups(listOf(file(1, durationMs = 214_000), file(2, durationMs = 216_001, artist = null))).isEmpty())
    }

    @Test
    fun `lengths never chain beyond 2 s in one group`() {
        val group = groups(listOf(file(1, durationMs = 214_000), file(2, durationMs = 215_500), file(3, durationMs = 217_000))).single()
        assertEquals(setOf(1L, 2L), ids(group))
    }

    @Test
    fun `files without a length are only ever grouped as identical`() {
        assertTrue(groups(listOf(file(1, durationMs = 0), file(2, durationMs = 0))).isEmpty())
        assertTrue(groups(listOf(file(1, durationMs = 0), file(2, durationMs = 0, artist = null))).isEmpty())
        assertEquals(1, groups(listOf(file(1, durationMs = 0), file(2, durationMs = 0)), identical = mapOf(1L to "k", 2L to "k")).size)
    }

    // One group per song

    @Test
    fun `identical copies are in one group, not also in a same song or maybe one`() {
        val result = groups(listOf(file(1), file(2)), identical = mapOf(1L to "k", 2L to "k"))
        assertEquals(Confidence.IDENTICAL, result.single().confidence)
    }

    @Test
    fun `identical copies and another copy of the same song are one same song group`() {
        val result = groups(
            listOf(file(1, bitrate = 128_000), file(2, bitrate = 128_000), file(3, bitrate = 320_000)),
            identical = mapOf(1L to "k", 2L to "k"),
        )
        val group = result.single()
        assertEquals(Confidence.SAME_SONG, group.confidence)
        assertEquals(setOf(1L, 2L, 3L), ids(group))
        assertEquals(3L, group.keep.id)
    }

    @Test
    fun `a maybe never pulls a song out of a more certain group`() {
        val result = groups(listOf(file(1), file(2, bitrate = 320_000), file(3, artist = null)))
        assertEquals(Confidence.SAME_SONG, result.single().confidence)
        assertEquals(setOf(1L, 2L), ids(result.single()))
        val identical = groups(listOf(file(1), file(2), file(3, artist = null)), identical = mapOf(1L to "k", 2L to "k"))
        assertEquals(setOf(1L, 2L), ids(identical.single()))
    }

    @Test
    fun `every file is in at most one group`() {
        val files = listOf(
            file(1), file(2), file(3, bitrate = 320_000), file(4, artist = null), file(5, artist = null, durationMs = 214_500),
            file(6, title = "Harbor of Glass", artist = "The Quiet Ferns"), file(7, title = "Harbor of Glass", artist = "The Quiet Ferns"),
            file(8, title = "Harbor of Glass", artist = "The Quiet Ferns", album = "Live at the Pier"),
        )
        val result = groups(files, identical = mapOf(1L to "k", 2L to "k", 6L to "h", 7L to "h"))
        val all = result.flatMap { group -> group.files.map { it.id } }
        assertEquals(all.size, all.toSet().size)
        assertTrue(result.all { it.files.size >= 2 })
    }

    @Test
    fun `groups come most certain first`() {
        val files = listOf(
            file(1, title = "Velvet Machinery"), file(2, title = "Velvet Machinery", artist = null),
            file(3, title = "Harbor of Glass"), file(4, title = "Harbor of Glass", bitrate = 320_000),
            file(5, title = "Lanterns"), file(6, title = "Lanterns"),
        )
        val result = groups(files, identical = mapOf(5L to "k", 6L to "k"))
        assertEquals(listOf(Confidence.IDENTICAL, Confidence.SAME_SONG, Confidence.MAYBE), result.map { it.confidence })
    }

    // Suggested keep

    @Test
    fun `the suggested keep is the highest bitrate, then lossless, then the most complete tags, then lyrics, then the oldest`() {
        assertEquals(2L, groups(listOf(file(1, bitrate = 192_000), file(2, bitrate = 320_000))).single().keep.id)
        assertEquals(
            2L,
            groups(listOf(file(1, bitrate = 900_000, mimeType = "audio/mpeg"), file(2, bitrate = 900_000, mimeType = "audio/flac"))).single().keep.id,
        )
        assertEquals(2L, groups(listOf(file(1, album = null), file(2))).single().keep.id)
        assertEquals(2L, groups(listOf(file(1), file(2)), lyrics = setOf(2L)).single().keep.id)
        assertEquals(1L, groups(listOf(file(1, addedAt = BASE), file(2, addedAt = BASE + 9))).single().keep.id)
    }

    @Test
    fun `a file without a date added counts as the newest`() {
        val undated = file(1, addedAt = null)
        val dated = file(2, addedAt = BASE + 500)
        assertEquals(2L, groups(listOf(undated, dated), identical = mapOf(1L to "k", 2L to "k")).single().keep.id)
        assertEquals(2L, groups(listOf(undated, dated)).single().keep.id)
        assertEquals(null, MediaStoreLibrary.addedAt(null))
        assertEquals(null, MediaStoreLibrary.addedAt(0))
        assertEquals(1_700_000_000_000L, MediaStoreLibrary.addedAt(1_700_000_000))
    }

    @Test
    fun `bitrates are compared in whole kbps`() {
        val group = groups(listOf(file(1, bitrate = 320_400, album = null), file(2, bitrate = 320_000))).single()
        assertEquals(2L, group.keep.id)
    }

    @Test
    fun `a missing bitrate is worked out from the size and length`() {
        val estimated = file(1, bitrate = null, size = 214L * 320_000 / 8)
        assertEquals(320_000L, estimated.effectiveBitrate)
        assertEquals(1L, groups(listOf(estimated, file(2, bitrate = 128_000))).single().keep.id)
    }

    @Test
    fun `lossless is told by the MIME type or the file name`() {
        assertTrue(file(1, mimeType = "audio/x-flac").lossless)
        assertTrue(file(1, mimeType = null, name = "x.WAV").lossless)
        assertTrue(!file(1, mimeType = "audio/mpeg").lossless)
    }

    @Test
    fun `lyrics are only looked up for files in a group`() {
        val looked = mutableListOf<Long>()
        DuplicateGrouping.groups(listOf(file(1), file(2), file(3, title = "Velvet Machinery"))) { looked += it.id; false }
        assertEquals(setOf(1L, 2L), looked.toSet())
    }

    @Test
    fun `files in a group know whether they have lyrics`() {
        val group = groups(listOf(file(1), file(2)), lyrics = setOf(1L)).single()
        assertEquals(mapOf(1L to true, 2L to false), group.files.associate { it.id to it.hasLyrics })
    }

    // Space and keys

    @Test
    fun `the extra space is every copy but the suggested keep`() {
        val group = groups(
            listOf(file(1, size = 10_000_000, bitrate = 320_000), file(2, size = 8_000_000), file(3, size = 6_000_000)),
        ).single()
        assertEquals(1L, group.keep.id)
        assertEquals(14_000_000L, group.extraBytes)
        assertEquals(3, group.copies)
    }

    @Test
    fun `a group's key is its files' ids and sizes, in any order`() {
        val a = file(1, size = 100)
        val b = file(2, size = 200)
        assertEquals(DuplicateGroup(Confidence.SAME_SONG, listOf(a, b)).key, DuplicateGroup(Confidence.MAYBE, listOf(b, a)).key)
        assertNotEquals(DuplicateGroup(Confidence.SAME_SONG, listOf(a, b)).key, DuplicateGroup(Confidence.SAME_SONG, listOf(a, b.copy(size = 201))).key)
    }

    @Test
    fun `results count their sections, the extra space and the groups not yet reviewed`() {
        val identical = DuplicateGroup(Confidence.IDENTICAL, listOf(file(1, size = 10), file(2, size = 10)))
        val same = DuplicateGroup(Confidence.SAME_SONG, listOf(file(3, size = 30), file(4, size = 20)))
        val maybe = DuplicateGroup(Confidence.MAYBE, listOf(file(5, size = 50), file(6, size = 5)))
        val maybe2 = DuplicateGroup(Confidence.MAYBE, listOf(file(7, size = 7), file(8, size = 1)))
        val results = ScanResults(scannedAt = BASE, scanned = 1_358, groups = listOf(identical, same, maybe, maybe2))
        assertEquals(mapOf(Confidence.IDENTICAL to 1, Confidence.SAME_SONG to 1, Confidence.MAYBE to 2), results.counts())
        assertEquals(listOf(maybe, maybe2), results.section(Confidence.MAYBE))
        assertEquals(10L + 20 + 5 + 1, results.extraBytes)
        assertEquals(4, results.unreviewed(emptySet()))
        assertEquals(2, results.unreviewed(setOf(identical.key, maybe.key, "gone")))
        assertEquals(mapOf(Confidence.IDENTICAL to 0, Confidence.SAME_SONG to 0, Confidence.MAYBE to 0), ScanResults(BASE, 0, emptyList()).counts())
    }

    private companion object {
        const val BASE: Long = 1_760_000_000_000
    }
}
