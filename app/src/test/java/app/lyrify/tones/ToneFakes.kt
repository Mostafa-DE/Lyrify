package app.lyrify.tones

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileDescriptor
import java.io.IOException

/** An encoder that keeps what it is given; [failAt] makes that encode call throw, [onEncode] hears each one (1-based). */
class FakeEncoder(private val failAt: Int = 0) : ToneEncoder {
    val calls = mutableListOf<String>()
    val pieces = mutableListOf<Int>()
    private val kept = mutableListOf<Short>()
    val samples: List<Short> get() = kept
    var onEncode: (Int) -> Unit = {}

    override fun encode(pcm: ShortArray, frames: Int) {
        if (pieces.size + 1 == failAt) throw IOException("disk full")
        calls += "encode"
        pieces += frames
        for (i in 0 until frames * 2) kept += pcm[i]
        onEncode(pieces.size)
    }

    override fun finish() {
        calls += "finish"
    }

    override fun close() {
        calls += "close"
    }
}

/**
 * MediaStore as a list of calls: rows get [nextId]; [listed] is what MediaStore lists for any row (by default a published
 * row as the scanner names a second file of the same name). A rename changes its name and title, as MediaStore's
 * move and rescan do.
 */
class FakeMedia(private val nextId: Long = 41) : ToneMedia {
    val calls = mutableListOf<String>()
    val inserted = mutableListOf<Map<String, Any>>()
    val updated = mutableListOf<Pair<Long, Map<String, Any>>>()
    var failInsert = false
    var failUpdate = false
    var failRow = false
    var beforeEdit: () -> Unit = {}
    var listed: MediaRow? = MediaRow("Paper Lanterns (Lyrify) (1)", "Paper Lanterns (Lyrify) (1).m4a", 48_000, "Ringtones/")
    var present: Set<Long>? = emptySet()

    override fun insert(values: Map<String, Any>): Long {
        calls += "insert"
        if (failInsert) throw IOException("no row")
        inserted += values
        return nextId
    }

    override fun update(id: Long, values: Map<String, Any>, expected: MediaRow?): Boolean {
        beforeEdit()
        if (expected != null && !matches(expected)) return false
        calls += "update $id"
        if (failUpdate) throw IOException("refused")
        updated += id to values
        (values["_display_name"] as? String)?.let { name -> listed = listed?.copy(title = name.removeSuffix(".m4a"), displayName = name) }
        return true
    }

    override suspend fun <T> write(id: Long, block: suspend (FileDescriptor) -> T): T {
        calls += "write $id"
        return block(FileDescriptor())
    }

    override fun row(id: Long): MediaRow? {
        calls += "row $id"
        if (failRow) throw IOException("no answer")
        return listed
    }

    override fun delete(id: Long, expected: MediaRow): Boolean {
        beforeEdit()
        if (!matches(expected)) return false
        calls += "delete $id"
        return true
    }

    private fun matches(expected: MediaRow): Boolean = listed?.let {
        ToneKind.entries.any { kind -> it.relativePath.startsWith("${kind.folder}/") } &&
            it.displayName == expected.displayName && it.size == expected.size
    } == true

    override fun existing(ids: Collection<Long>): Set<Long>? {
        calls += "existing"
        return present?.intersect(ids.toSet())
    }
}

/** The tones list in memory. */
class MemoryToneList(initial: TonesState = TonesState()) : ToneList {
    private val data = MutableStateFlow(initial)
    val current: TonesState get() = data.value
    override val state: Flow<TonesState> = data

    override suspend fun put(tone: SavedTone): Boolean {
        data.value = data.value.put(tone)
        return true
    }

    override suspend fun remove(id: Long): Boolean {
        data.value = data.value.remove(id)
        return true
    }

    override suspend fun retain(ids: Set<Long>): Boolean {
        data.value = data.value.retain(ids)
        return true
    }
}

/** The phone's sound settings as a list of calls. */
class FakeSounds(var writable: Boolean = false) : SystemSounds {
    val calls = mutableListOf<String>()
    var settingsScreen = true
    var clock = true
    var soundSettings = true
    var takes = true
    var revoked = false
    val defaults = mutableMapOf<ToneKind, String?>()

    override fun canWrite(): Boolean = writable

    override fun openWriteSettings(): Boolean {
        calls += "openWriteSettings"
        return settingsScreen
    }

    override fun setDefault(kind: ToneKind, toneId: Long): Boolean {
        if (revoked) throw SecurityException("not allowed")
        calls += "setDefault $kind $toneId"
        if (takes) defaults[kind] = "content://0@media/external_primary/audio/media/$toneId"
        return takes
    }

    override fun defaultUri(kind: ToneKind): String? = defaults[kind]

    override fun openClock(): Boolean {
        calls += "openClock"
        return clock
    }

    override fun openSoundSettings(): Boolean {
        calls += "openSoundSettings"
        return soundSettings
    }
}

/** A saved tone with made-up values. */
fun tone(
    id: Long = 41,
    name: String = "Paper Lanterns (Lyrify)",
    kind: ToneKind = ToneKind.RINGTONE,
    createdAt: Long = 1_000,
    size: Long = 48_000,
): SavedTone = SavedTone(
    id = id,
    name = name,
    kind = kind,
    songPath = "/storage/emulated/0/Music/Paper Lanterns.mp3",
    startMs = 72_000,
    lengthMs = 30_000,
    fadeInMs = 1_500,
    fadeOutMs = 1_500,
    createdAt = createdAt,
    fileName = "$name.m4a",
    size = size,
)

/** The row MediaStore lists for [tone] while it is still the file Lyrify saved, in [relativePath]. */
fun rowOf(tone: SavedTone, relativePath: String = "${tone.kind.folder}/"): MediaRow = MediaRow(tone.name, tone.fileName, tone.size, relativePath)
