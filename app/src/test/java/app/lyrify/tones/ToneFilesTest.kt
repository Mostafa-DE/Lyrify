package app.lyrify.tones

import app.lyrify.ai.ArrayMix
import app.lyrify.ai.MixSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException

class ToneFilesTest {
    // Names: made-up song titles only

    @Test
    fun `the file and its title are named after the song and say Lyrify, with no em-dash`() {
        val entry = ToneEntry.of("Paper Lanterns", ToneKind.RINGTONE)
        assertEquals("Paper Lanterns (Lyrify).m4a", entry.displayName)
        assertEquals("Paper Lanterns (Lyrify)", entry.title)
        assertFalse(entry.title.contains('\u2014'))
        assertFalse(entry.displayName.contains('\u2014'))
    }

    @Test
    fun `characters a file name cannot hold become spaces, and a name never starts with a dot`() {
        assertEquals("AC DC Live (Lyrify).m4a", ToneEntry.of("AC/DC: Live?", ToneKind.ALARM).displayName)
        assertEquals("Hidden Track (Lyrify).m4a", ToneEntry.of("..Hidden\tTrack", ToneKind.ALARM).displayName)
        assertEquals("AC DC Live (Lyrify)", ToneEntry.of("AC/DC: Live?", ToneKind.ALARM).title)
    }

    @Test
    fun `a blank title becomes Tone, a long one is cut`() {
        assertEquals("Tone (Lyrify).m4a", ToneEntry.of(" / ", ToneKind.NOTIFICATION).displayName)
        assertEquals("Tone (Lyrify)", ToneEntry.of("   ", ToneKind.NOTIFICATION).title)
        val long = ToneEntry.of("x".repeat(500), ToneKind.RINGTONE)
        assertEquals(ToneEntry.MAX_NAME_CHARS + " (Lyrify).m4a".length, long.displayName.length)
        assertEquals(ToneEntry.MAX_NAME_CHARS + " (Lyrify)".length, long.title.length)
    }

    // The MediaStore values

    @Test
    fun `each kind goes to its folder with its flag, pending until written`() {
        val flags = listOf("is_ringtone", "is_alarm", "is_notification")
        ToneKind.entries.forEachIndexed { i, kind ->
            val values = ToneEntry.of("Paper Lanterns", kind).insertValues()
            assertEquals("${kind.folder}/", values["relative_path"])
            flags.forEachIndexed { j, flag -> assertEquals("$kind $flag", if (i == j) 1 else 0, values[flag]) }
            assertEquals(0, values["is_music"])
            assertEquals(1, values["is_pending"])
            assertEquals("audio/mp4", values["mime_type"])
            assertEquals("Paper Lanterns (Lyrify).m4a", values["_display_name"])
            assertEquals("Paper Lanterns (Lyrify)", values["title"])
        }
        assertEquals(mapOf<String, Any>("is_pending" to 0), ToneEntry.PUBLISH)
    }

    // Saving

    private val song = "/storage/emulated/0/Music/Paper Lanterns.mp3"
    private val request = ToneRequest(
        songPath = song,
        songTitle = "Paper Lanterns",
        window = ToneWindow(1_000, 1_000),
        fadeInMs = 100,
        fadeOutMs = 100,
        kind = ToneKind.RINGTONE,
    )

    private class Setup(
        val media: FakeMedia = FakeMedia(),
        val encoder: FakeEncoder = FakeEncoder(),
        val list: MemoryToneList = MemoryToneList(),
        val source: ArrayMix = ArrayMix(FloatArray(3 * ToneAudio.RATE) { 0.5f }, piece = 2_000),
        val opened: MutableList<File> = mutableListOf(),
        val logs: MutableList<String> = mutableListOf(),
        openSong: ((File) -> MixSource)? = null,
    ) {
        val files = ToneFiles(
            media = media,
            openSong = openSong ?: { file -> opened += file; source },
            newEncoder = { encoder },
            list = list,
            clock = { 5_000 },
            log = { logs += it },
            io = Dispatchers.Unconfined,
        )
    }

    @Test
    fun `a tone is opened from the song, written pending, encoded, published, then listed`() = runBlocking {
        val s = Setup()
        val result = s.files.save(request) {}

        assertEquals(listOf(File(song)), s.opened)
        assertEquals(listOf("insert", "row 41", "write 41", "update 41", "row 41"), s.media.calls)
        assertEquals(listOf(41L to ToneEntry.PUBLISH), s.media.updated)
        assertEquals("finish", s.encoder.calls[s.encoder.calls.size - 2])
        assertEquals("close", s.encoder.calls.last())
        assertTrue(s.source.closed)

        val saved = SavedTone(
            id = 41,
            name = "Paper Lanterns (Lyrify) (1)",
            kind = ToneKind.RINGTONE,
            songPath = song,
            startMs = 1_000,
            lengthMs = 1_000,
            fadeInMs = 100,
            fadeOutMs = 100,
            createdAt = 5_000,
            fileName = "Paper Lanterns (Lyrify) (1).m4a",
            size = 48_000,
        )
        assertEquals(SaveResult.Saved(saved), result)
        assertEquals(mapOf(41L to saved), s.list.current.tones)
    }

    @Test
    fun `the tone is named as MediaStore lists it, or as saved when it cannot say`() = runBlocking {
        val s = Setup(media = FakeMedia().apply { listed = null })
        val result = s.files.save(request) {} as SaveResult.Saved
        assertEquals("Paper Lanterns (Lyrify)", result.tone.name)
        assertEquals("Paper Lanterns (Lyrify).m4a", result.tone.fileName)
        // No size known: such a tone is never deleted or renamed by Lyrify.
        assertEquals(0L, result.tone.size)
        assertFalse(isSavedTone(result.tone, MediaRow("Paper Lanterns (Lyrify)", "Paper Lanterns (Lyrify).m4a", 0, "Ringtones/")))
    }

    @Test
    fun `the only file written is the new tone, the song path goes only to the decoder and the list`() = runBlocking {
        val s = Setup()
        s.files.save(request) {}
        s.media.inserted.single().values.forEach { assertFalse(it.toString().contains("/storage/")) }
        assertEquals(listOf(File(song)), s.opened)
    }

    @Test
    fun `a song that cannot be opened makes no file`() = runBlocking {
        val s = Setup(openSong = { throw FileNotFoundException("gone") })
        assertEquals(SaveResult.Failed(SaveFailure.SONG_UNREADABLE), s.files.save(request) {})
        assertEquals(emptyList<String>(), s.media.calls)
        assertTrue(s.list.current.tones.isEmpty())
    }

    @Test
    fun `a song that fails while decoding leaves no half-made file`() = runBlocking {
        val short = ArrayMix(FloatArray(ToneAudio.RATE / 2) { 0.5f })
        val s = Setup(source = short)
        assertEquals(SaveResult.Failed(SaveFailure.SONG_UNREADABLE), s.files.save(request) {})
        assertEquals(listOf("insert", "row 41", "write 41", "row 41", "delete 41"), s.media.calls)
        assertTrue(s.list.current.tones.isEmpty())
        assertTrue(short.closed)
    }

    @Test
    fun `a failed write removes the half-made file and lists nothing`() = runBlocking {
        val s = Setup(encoder = FakeEncoder(failAt = 2))
        assertEquals(SaveResult.Failed(SaveFailure.NOT_SAVED), s.files.save(request) {})
        assertEquals(listOf("insert", "row 41", "write 41", "row 41", "delete 41"), s.media.calls)
        assertFalse(s.encoder.calls.contains("finish"))
        assertEquals("close", s.encoder.calls.last())
        assertTrue(s.list.current.tones.isEmpty())
        assertTrue(s.source.closed)
        // The log names the failure, never the song.
        assertTrue(s.logs.isNotEmpty())
        s.logs.forEach { assertFalse(it, it.contains("Paper") || it.contains("/storage/")) }
    }

    @Test
    fun `a row MediaStore would not make leaves nothing to remove`() = runBlocking {
        val s = Setup(media = FakeMedia().apply { failInsert = true })
        assertEquals(SaveResult.Failed(SaveFailure.NOT_SAVED), s.files.save(request) {})
        assertEquals(listOf("insert"), s.media.calls)
        assertTrue(s.source.closed)
    }

    @Test
    fun `cancelling removes the half-made file and lists nothing`() = runBlocking {
        val s = Setup()
        lateinit var job: Job
        s.encoder.onEncode = { if (it == 2) job.cancel() }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) { s.files.save(request) {} }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("insert", "row 41", "write 41", "row 41", "delete 41"), s.media.calls)
        assertFalse(s.encoder.calls.contains("finish"))
        assertEquals("close", s.encoder.calls.last())
        assertTrue(s.list.current.tones.isEmpty())
        assertTrue(s.source.closed)
    }

    @Test
    fun `progress reaches 1 for a saved tone`() = runBlocking {
        val seen = mutableListOf<Float>()
        Setup().files.save(request) { seen += it }
        assertEquals(1f, seen.last(), 0f)
    }

    // Renaming

    @Test
    fun `renaming checks the row is still the tone, moves the file and lists its new name`() = runBlocking {
        val s = Setup(list = MemoryToneList(TonesState().put(tone(41))))
        s.media.listed = rowOf(tone(41))
        val renamed = tone(41).copy(name = "Morning Call", fileName = "Morning Call.m4a")
        assertEquals(ToneEdit.Done(renamed), s.files.rename(tone(41), "Morning: Call"))
        assertEquals(listOf("row 41", "update 41", "row 41"), s.media.calls)
        assertEquals(listOf(41L to mapOf<String, Any>("_display_name" to "Morning Call.m4a")), s.media.updated)
        assertEquals(renamed, s.list.current.tones.getValue(41))
        // The renamed tone is still recognised as Lyrify's for the next change.
        assertTrue(isSavedTone(renamed, s.media.listed))
    }

    @Test
    fun `a rename MediaStore refused changes nothing`() = runBlocking {
        val s = Setup(media = FakeMedia().apply { listed = rowOf(tone(41)) }, list = MemoryToneList(TonesState().put(tone(41))))
        s.media.failUpdate = true
        assertEquals(ToneEdit.Failed, s.files.rename(tone(41), "Morning Call"))
        assertEquals(tone(41), s.list.current.tones.getValue(41))
    }

    // Ownership: an id that no longer points at the tone is never renamed or deleted

    private val notTheTone = mapOf(
        "a similarly named folder" to rowOf(tone(41), relativePath = "RingtonesExtra/"),
        "a song in another folder" to rowOf(tone(41), relativePath = "Music/"),
        "a file of another name" to rowOf(tone(41)).copy(displayName = "Glass Harbor.mp3"),
        "a file of another size" to rowOf(tone(41)).copy(size = 7_340_032),
        "no row" to null,
    )

    @Test
    fun `a row that is no longer the tone is not deleted, and the tone leaves the list`() = runBlocking {
        notTheTone.forEach { (case, row) ->
            val s = Setup(media = FakeMedia().apply { listed = row }, list = MemoryToneList(TonesState().put(tone(41)).put(tone(42))))
            assertEquals(case, ToneEdit.Gone, s.files.delete(tone(41)))
            assertEquals(case, listOf("row 41"), s.media.calls)
            assertEquals(case, setOf(42L), s.list.current.tones.keys)
        }
    }

    @Test
    fun `a row that is no longer the tone is not renamed, and the tone leaves the list`() = runBlocking {
        notTheTone.forEach { (case, row) ->
            val s = Setup(media = FakeMedia().apply { listed = row }, list = MemoryToneList(TonesState().put(tone(41))))
            assertEquals(case, ToneEdit.Gone, s.files.rename(tone(41), "Morning Call"))
            assertEquals(case, listOf("row 41"), s.media.calls)
            assertTrue(case, s.media.updated.isEmpty())
            assertTrue(case, s.list.current.tones.isEmpty())
        }
    }

    @Test
    fun `when MediaStore cannot say, nothing is changed and the tone stays listed`() = runBlocking {
        val s = Setup(media = FakeMedia().apply { failRow = true }, list = MemoryToneList(TonesState().put(tone(41))))
        assertEquals(ToneEdit.Failed, s.files.delete(tone(41)))
        assertEquals(ToneEdit.Failed, s.files.rename(tone(41), "Morning Call"))
        assertEquals(listOf("row 41", "row 41"), s.media.calls)
        assertEquals(setOf(41L), s.list.current.tones.keys)
    }

    @Test
    fun `a tone is recognised by its folder, file name and size`() {
        ToneKind.entries.forEach { kind ->
            val saved = tone(41, kind = kind)
            assertTrue(kind.name, isSavedTone(saved, rowOf(saved)))
        }
        // Another tone folder than the one saved in still counts: only song folders are refused.
        assertTrue(isSavedTone(tone(41), rowOf(tone(41), relativePath = "Alarms/")))
        assertTrue(isSavedTone(tone(41), rowOf(tone(41), relativePath = "Ringtones/Old/")))
        notTheTone.values.forEach { assertFalse(isSavedTone(tone(41), it)) }
    }

    @Test
    fun `old entries without an identity stay listed and refuse both edits`() = runBlocking {
        val old = TonesJson.decode("""{"tones":{"41":{"id":41,"name":"Paper Lanterns","kind":"RINGTONE","songPath":"/Music/Paper Lanterns.mp3","startMs":0,"lengthMs":1000}}}""").tones.getValue(41)
        val s = Setup(list = MemoryToneList(TonesState().put(old)))
        assertEquals(ToneEdit.Gone, s.files.delete(old))
        assertEquals(ToneEdit.Gone, s.files.rename(old, "Morning Call"))
        assertEquals(old, s.list.current.tones[41])
        assertTrue(s.media.calls.isEmpty())
    }

    @Test
    fun `a row replaced after the first check is refused by the media seam`() = runBlocking {
        for (rename in listOf(false, true)) {
            val media = FakeMedia().apply {
                listed = rowOf(tone())
                beforeEdit = { listed = rowOf(tone()).copy(size = 99) }
            }
            val s = Setup(media = media, list = MemoryToneList(TonesState().put(tone())))
            val result = if (rename) s.files.rename(tone(), "Morning Call") else s.files.delete(tone())
            assertEquals(ToneEdit.Gone, result)
            assertEquals(listOf("row 41"), media.calls)
            assertTrue(s.list.current.tones.isEmpty())
        }
    }

    // Deleting and pruning

    @Test
    fun `deleting a tone removes only its own file and its row in the list`() = runBlocking {
        val s = Setup(media = FakeMedia().apply { listed = rowOf(tone(41)) }, list = MemoryToneList(TonesState().put(tone(41)).put(tone(42))))
        assertEquals(ToneEdit.Done(null), s.files.delete(tone(41)))
        assertEquals(listOf("row 41", "delete 41"), s.media.calls)
        assertEquals(setOf(42L), s.list.current.tones.keys)
    }

    @Test
    fun `tones deleted outside Lyrify leave the list, unless MediaStore cannot say`() = runBlocking {
        val s = Setup(list = MemoryToneList(TonesState().put(tone(41)).put(tone(42))))
        s.media.present = setOf(42L)
        s.files.prune()
        assertEquals(setOf(42L), s.list.current.tones.keys)

        s.media.present = null
        s.files.prune()
        assertEquals(setOf(42L), s.list.current.tones.keys)
    }
}
