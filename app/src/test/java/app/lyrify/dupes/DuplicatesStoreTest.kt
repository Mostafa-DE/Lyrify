package app.lyrify.dupes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DuplicatesStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val logs = mutableListOf<String>()

    private fun file(id: Long, size: Long = 1_000 + id) = AudioFile(
        id = id, path = "/storage/emulated/0/Music/$id Lanterns.mp3", relativePath = "Music/", displayName = "$id Lanterns.mp3",
        title = "Lanterns", artist = "The Quiet Ferns", album = "Brine", durationMs = 200_000, size = size, mimeType = "audio/mpeg",
        bitrate = 192_000, addedAt = 1_760_000_000_000 + id,
    )

    private val memory = DuplicatesMemory(
        results = ScanResults(
            scannedAt = 1_762_450_000_000,
            scanned = 1_358,
            groups = listOf(
                DuplicateGroup(Confidence.IDENTICAL, listOf(file(1, 7_000), file(2, 7_000).copy(hasLyrics = true))),
                DuplicateGroup(Confidence.MAYBE, listOf(file(3), file(4).copy(artist = null, bitrate = null, mimeType = null, relativePath = null))),
            ),
        ),
        notDuplicates = setOf("5:100,6:100"),
        reviewed = setOf("1:7000,2:7000"),
        pendingTrash = listOf(file(2, 7_000)),
        trashed = listOf(TrashedSong(file(9), trashedAt = 1_762_450_000_000, expiresAt = 1_765_042_000_000)),
    )

    @Test
    fun `what is saved loads back the same`() {
        val store = DuplicatesStore(File(folder.root, "duplicates.json"))
        assertTrue(store.save(memory))
        assertEquals(memory, store.load())
    }

    @Test
    fun `nothing saved yet is an empty memory`() {
        assertEquals(DuplicatesMemory(), DuplicatesStore(File(folder.root, "duplicates.json")).load())
    }

    @Test
    fun `a file that is not a memory at all starts over, logging no content`() {
        val stored = File(folder.root, "duplicates.json")
        stored.writeText("Lanterns, The Quiet Ferns")
        assertEquals(DuplicatesMemory(), DuplicatesStore(stored) { logs += it }.load())
        assertTrue(logs.single().startsWith("duplicates: stored file unreadable"))
        assertFalse(logs.single().contains("Lanterns"))
    }

    @Test
    fun `a write leaves no file aside, and one that fails keeps the file before`() {
        val stored = File(folder.root, "sub/duplicates.json")
        val store = DuplicatesStore(stored) { logs += it }
        assertTrue(store.save(memory))
        assertEquals(listOf("duplicates.json"), stored.parentFile?.list()?.toList())
        val parent = stored.parentFile ?: error("no folder")
        assumeTrue(parent.setWritable(false) && !parent.canWrite())
        try {
            assertFalse(store.save(DuplicatesMemory()))
        } finally {
            parent.setWritable(true)
        }
        assertEquals(memory, store.load())
    }

    @Test
    fun `this version's stored form loads unchanged`() {
        val stored = """
            {"version":1,
             "results":{"scannedAt":1762450000000,"scanned":12,"groups":[
               {"confidence":"SAME_SONG","files":[
                 {"id":7,"path":"/storage/emulated/0/Music/a/Paper Comet.flac","relativePath":"Music/a/","displayName":"Paper Comet.flac",
                  "title":"Paper Comet","artist":"Mira Solen","album":"Night Kites","durationMs":214000,"size":30000000,
                  "mimeType":"audio/flac","bitrate":1100000,"addedAt":1760000000000,"hasLyrics":true},
                 {"id":8,"path":"/storage/emulated/0/Music/b/Paper Comet.mp3","displayName":"Paper Comet.mp3",
                  "title":"Paper Comet","durationMs":214500,"size":5000000,"addedAt":1760000001000}]}]},
             "notDuplicates":["1:10,2:10"],"reviewed":["7:30000000,8:5000000"],"pendingTrash":[]}
        """.trimIndent()
        val loaded = DuplicatesJson.decode(stored)
        val group = loaded?.results?.groups?.single()
        assertEquals(Confidence.SAME_SONG, group?.confidence)
        assertEquals(listOf(7L, 8L), group?.files?.map { it.id })
        assertEquals(true, group?.keep?.hasLyrics)
        assertNull(group?.files?.get(1)?.artist)
        assertEquals(5_000_000L, group?.extraBytes)
        assertEquals(setOf("1:10,2:10"), loaded?.notDuplicates)
        assertEquals(group?.key, loaded?.reviewed?.single())
        assertEquals(12, loaded?.results?.scanned)
    }

    @Test
    fun `what this version cannot read is left out, never the rest`() {
        val stored = """
            {"version":9,"futureField":{"x":1},
             "results":{"scannedAt":1762450000000,"groups":[
               {"confidence":"SIMILAR_SOUND","files":[]},
               {"confidence":"IDENTICAL","files":[{"id":1}]},
               {"confidence":"MAYBE","files":[
                 {"id":3,"path":"/m/3.mp3","displayName":"3.mp3","title":"Harbor","durationMs":1,"size":5,"addedAt":0,"loudness":-9},
                 {"id":4,"path":"/m/4.mp3","displayName":"4.mp3","title":"Harbor","durationMs":1,"size":6,"addedAt":0}]},
               {"confidence":"MAYBE","files":[
                 {"id":5,"path":"/m/5.mp3","displayName":"5.mp3","title":"Harbor","durationMs":1,"size":5,"addedAt":0}]},
               "not a group"]},
             "notDuplicates":["1:10,2:10", 42, null, {"id":1}],
             "reviewed":"not a list",
             "pendingTrash":[{"id":2}, {"id":6,"path":"/m/6.mp3","displayName":"6.mp3","title":"Harbor","durationMs":1,"size":5,"addedAt":0}]}
        """.trimIndent()
        val loaded = DuplicatesJson.decode(stored)
        assertEquals(listOf(listOf(3L, 4L)), loaded?.results?.groups?.map { group -> group.files.map { it.id } })
        assertEquals(0, loaded?.results?.scanned)
        assertEquals(setOf("1:10,2:10"), loaded?.notDuplicates)
        assertEquals(emptySet<String>(), loaded?.reviewed)
        assertEquals(listOf(6L), loaded?.pendingTrash?.map { it.id })
    }

    @Test
    fun `results without a scan time are no results, and the decisions still load`() {
        val loaded = DuplicatesJson.decode("""{"results":{"groups":[]},"notDuplicates":["1:10,2:10"]}""")
        assertNull(loaded?.results)
        assertEquals(setOf("1:10,2:10"), loaded?.notDuplicates)
    }

    // Trashed songs

    private fun trashed(id: Long, expiresAt: Long = 1_000) = TrashedSong(file(id), trashedAt = 0, expiresAt = expiresAt)

    @Test
    fun `trashed songs no longer in the phone's trash drop off`() {
        val songs = listOf(trashed(1), trashed(2))
        assertEquals(listOf(trashed(1)), stillTrashed(songs, inTrash = mapOf(1L to null), now = 0))
        assertEquals(emptyList<TrashedSong>(), stillTrashed(songs, inTrash = emptyMap(), now = 0))
    }

    @Test
    fun `trashed songs past their expiry drop off, by the phone's expiry when it says`() {
        val songs = listOf(trashed(1, expiresAt = 1_000), trashed(2, expiresAt = 1_000))
        assertEquals(listOf(trashed(2, expiresAt = 5_000)), stillTrashed(songs, inTrash = mapOf(1L to 900, 2L to 5_000), now = 999))
        assertEquals(emptyList<TrashedSong>(), stillTrashed(songs, inTrash = mapOf(1L to null, 2L to null), now = 1_000))
    }

    @Test
    fun `while MediaStore cannot be read only the expiry counts`() {
        val songs = listOf(trashed(1, expiresAt = 1_000), trashed(2, expiresAt = 2_000))
        assertEquals(listOf(trashed(2, expiresAt = 2_000)), stillTrashed(songs, inTrash = null, now = 1_500))
    }

    @Test
    fun `text that is not a JSON object is not a memory`() {
        assertNull(DuplicatesJson.decode("[1,2]"))
        assertNull(DuplicatesJson.decode("{"))
        assertEquals(DuplicatesMemory(), DuplicatesJson.decode("{}"))
    }
}
