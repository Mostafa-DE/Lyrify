package app.lyrify.tones

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.MediaStore
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.ai.MixDecoder
import app.lyrify.ai.MixSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * A new tone's MediaStore row: the file "<Title> (Lyrify).m4a", titled "<Title> (Lyrify)" as the scanner will title it
 * from the file name, in its kind's shared folder.
 */
data class ToneEntry(val displayName: String, val title: String, val kind: ToneKind) {
    /**
     * The row as inserted: pending (only Lyrify can open it until [PUBLISH]), in `<folder>/`, with the kind's IS_* flag.
     * MediaStore derives the flags from the folder again when it scans the published file (AOSP MediaProvider,
     * FileUtils.computeAudioTypeValuesFromData), so both agree. The scan also sets the title from the file's tags, and
     * without a title tag from the file name: read it back after publishing ([ToneMedia.row]).
     */
    fun insertValues(): Map<String, Any> = mapOf(
        MediaStore.MediaColumns.DISPLAY_NAME to displayName,
        MediaStore.MediaColumns.TITLE to title,
        MediaStore.MediaColumns.MIME_TYPE to MIME_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH to "${kind.folder}/",
        MediaStore.Audio.AudioColumns.IS_RINGTONE to flag(kind == ToneKind.RINGTONE),
        MediaStore.Audio.AudioColumns.IS_ALARM to flag(kind == ToneKind.ALARM),
        MediaStore.Audio.AudioColumns.IS_NOTIFICATION to flag(kind == ToneKind.NOTIFICATION),
        MediaStore.Audio.AudioColumns.IS_MUSIC to 0,
        MediaStore.MediaColumns.IS_PENDING to 1,
    )

    companion object {
        /** The song title's characters kept in a name, at most. */
        const val MAX_NAME_CHARS: Int = 100
        const val MIME_TYPE: String = "audio/mp4"
        private const val EXTENSION: String = "m4a"

        /** The update that publishes a written row: other apps see it, and MediaStore scans it. */
        val PUBLISH: Map<String, Any> = mapOf(MediaStore.MediaColumns.IS_PENDING to 0)

        private const val FALLBACK: String = "Tone"
        private val NOT_IN_FILE_NAMES = Regex("""[\\/:*?"<>|\p{Cc}\p{Cf}]""")
        private val SPACES = Regex("""\s+""")

        /** The entry for a tone of the song titled [songTitle]: a name a file can have, "Tone" for a blank title. */
        fun of(songTitle: String, kind: ToneKind): ToneEntry {
            val name = "${safeName(songTitle)} (Lyrify)"
            return ToneEntry("$name.$EXTENSION", name, kind)
        }

        /** [text] as a file name's base: characters a file name cannot hold become spaces, no leading dot, "Tone" when blank. */
        fun safeName(text: String): String = tidy(text.replace(NOT_IN_FILE_NAMES, " ")).trimStart('.').trim().ifBlank { FALLBACK }

        /** The update that renames a tone's file to [name] (made safe); MediaStore moves the file and rescans it. */
        fun renameValues(name: String): Map<String, Any> = mapOf(MediaStore.MediaColumns.DISPLAY_NAME to "${safeName(name)}.$EXTENSION")

        private fun tidy(name: String): String = name.replace(SPACES, " ").trim().take(MAX_NAME_CHARS).trim()

        private fun flag(on: Boolean): Int = if (on) 1 else 0
    }
}

/** What MediaStore lists for a row: the title sound pickers show, the file's name, size and folder (`Ringtones/`). */
data class MediaRow(val title: String?, val displayName: String, val size: Long, val relativePath: String)

/**
 * Whether [row] is still the file [tone] was saved as: in a tone folder, with the name and size Lyrify recorded. An id
 * can come to point at another file (MediaStore rebuilt, the tone replaced), and with All files access nothing else
 * would stop a delete or rename of a song, so Lyrify changes a row only when this holds.
 */
fun isSavedTone(tone: SavedTone, row: MediaRow?): Boolean =
    tone.fileName.isNotBlank() && tone.size > 0 && matchesToneRow(row, tone.fileName, tone.size)

private fun matchesToneRow(row: MediaRow?, fileName: String, size: Long): Boolean =
    row != null && ToneKind.entries.any { row.relativePath.startsWith("${it.folder}/") } &&
        row.displayName == fileName && row.size == size

/**
 * The tone rows in MediaStore's audio collection on the phone's shared storage: ContentResolver in the app
 * ([AndroidToneMedia]), a fake in tests. Only rows Lyrify made are ever written or deleted, checked with [isSavedTone].
 */
interface ToneMedia {
    /** Inserts a row with [values]; its id. Throws when MediaStore makes none. */
    fun insert(values: Map<String, Any>): Long

    /** Publishes a new row, or checks [expected] before renaming. False when the row no longer matches. */
    fun update(id: Long, values: Map<String, Any>, expected: MediaRow? = null): Boolean

    /** Runs [block] on the row's file opened for writing, closed after. */
    suspend fun <T> write(id: Long, block: suspend (FileDescriptor) -> T): T

    /** The row as MediaStore lists it; null when there is no such row. Throws when MediaStore cannot say. */
    fun row(id: Long): MediaRow?

    /** Checks [expected] before removing the row and its file; false when it no longer matches. */
    fun delete(id: Long, expected: MediaRow): Boolean

    /** Which of [ids] still exist; null when MediaStore cannot say (nothing is pruned then). */
    fun existing(ids: Collection<Long>): Set<Long>?
}

/** [ToneMedia] on MediaStore.Audio, VOLUME_EXTERNAL_PRIMARY. No storage permission is needed for rows the app owns. */
class AndroidToneMedia(private val resolver: ContentResolver) : ToneMedia {
    private val collection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    override fun insert(values: Map<String, Any>): Long {
        val uri = resolver.insert(collection, contentValues(values)) ?: throw IOException("MediaStore made no row")
        return ContentUris.parseId(uri)
    }

    override fun update(id: Long, values: Map<String, Any>, expected: MediaRow?): Boolean {
        if (expected == null) {
            require(values == ToneEntry.PUBLISH) { "a rename needs the saved identity" }
            if (resolver.update(uri(id), contentValues(values), null, null) == 0) throw IOException("MediaStore row not published")
            return true
        }
        val current = row(id)
        if (!matchesToneRow(current, expected.displayName, expected.size) || current == null) return false
        return resolver.update(uri(id), contentValues(values), IDENTITY, identityArgs(current)) > 0
    }

    /** "rw": MediaMuxer seeks back to finish an MPEG-4 file. */
    override suspend fun <T> write(id: Long, block: suspend (FileDescriptor) -> T): T {
        val file = resolver.openFileDescriptor(uri(id), "rw") ?: throw IOException("no file for MediaStore row $id")
        return file.use { block(it.fileDescriptor) }
    }

    override fun row(id: Long): MediaRow? {
        val columns = arrayOf(
            MediaStore.MediaColumns.TITLE,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val cursor = resolver.query(uri(id), columns, null, null, null) ?: throw IOException("MediaStore did not answer for row $id")
        return cursor.use {
            if (!it.moveToFirst()) return null
            MediaRow(title = it.getString(0), displayName = it.getString(1).orEmpty(), size = it.getLong(2), relativePath = it.getString(3).orEmpty())
        }
    }

    override fun delete(id: Long, expected: MediaRow): Boolean {
        val current = row(id)
        if (!matchesToneRow(current, expected.displayName, expected.size) || current == null) return false
        return resolver.delete(uri(id), IDENTITY, identityArgs(current)) > 0
    }

    private fun identityArgs(row: MediaRow): Array<String> = arrayOf(row.relativePath, row.displayName, row.size.toString())

    private companion object {
        const val IDENTITY: String = "relative_path = ? AND _display_name = ? AND _size = ?"
    }

    override fun existing(ids: Collection<Long>): Set<Long>? {
        val found = HashSet<Long>()
        // Under SQLite's bound-argument limit.
        ids.chunked(500).forEach { chunk ->
            val selection = "${BaseColumns._ID} IN (${chunk.joinToString(",") { "?" }})"
            val cursor = resolver.query(collection, arrayOf(BaseColumns._ID), selection, chunk.map(Long::toString).toTypedArray(), null) ?: return null
            cursor.use { while (it.moveToNext()) found += it.getLong(0) }
        }
        return found
    }

    private fun uri(id: Long): Uri = ContentUris.withAppendedId(collection, id)

    private fun contentValues(values: Map<String, Any>): ContentValues = ContentValues().apply {
        values.forEach { (column, value) ->
            when (value) {
                is String -> put(column, value)
                is Int -> put(column, value)
                is Long -> put(column, value)
                else -> throw IllegalArgumentException("unsupported value type for $column")
            }
        }
    }
}

/** What the editor saves: [window] of the song at [songPath], faded, as a [kind] tone named after [songTitle]. */
data class ToneRequest(
    val songPath: String,
    val songTitle: String,
    val window: ToneWindow,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val kind: ToneKind,
)

/** What a delete or rename did. */
sealed interface ToneEdit {
    /** Done; [tone] is the tone as listed now (null after a delete). */
    data class Done(val tone: SavedTone?) : ToneEdit

    /** The row cannot be verified as this tone. Mismatches leave the list; legacy entries without identity stay. */
    data object Gone : ToneEdit

    /** MediaStore refused or could not say: nothing changed, the tone stays listed. */
    data object Failed : ToneEdit
}

sealed interface SaveResult {
    data class Saved(val tone: SavedTone) : SaveResult

    data class Failed(val reason: SaveFailure) : SaveResult
}

enum class SaveFailure {
    /** The song could not be opened or decoded, or holds less audio than it says. */
    SONG_UNREADABLE,

    /** MediaStore, the encoder or storage failed (no space, no row). */
    NOT_SAVED,
}

/**
 * The tone files Lyrify makes. A tone is a new file: the song is only ever read, through [openSong]. Saving writes a
 * pending MediaStore row, encodes into it, publishes it and lists it; a failure or a cancellation on the way deletes
 * the half-made row (a pending row MediaStore would also expire by itself). Deleting removes only the tone's own row.
 * Blocking work runs on [io]. Logs name failures only, never a title or path.
 */
class ToneFiles(
    private val media: ToneMedia,
    private val openSong: (File) -> MixSource,
    private val newEncoder: (FileDescriptor) -> ToneEncoder,
    private val list: ToneList,
    private val clock: () -> Long,
    private val log: (String) -> Unit,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    /** Saves [request]; [onProgress] hears the share done. Cancellable: a cancelled save leaves no file. */
    suspend fun save(request: ToneRequest, onProgress: (Float) -> Unit): SaveResult = withContext(io) {
        val source = try {
            openSong(File(request.songPath))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("tone: song not opened: ${e.javaClass.simpleName}")
            return@withContext SaveResult.Failed(SaveFailure.SONG_UNREADABLE)
        }
        source.use { saved(it, request, onProgress) }
    }

    /** Deletes [tone]'s file (never the song: only a row that is still this tone, [isSavedTone]) and its row in the list. */
    suspend fun delete(tone: SavedTone): ToneEdit = withContext(io) {
        checked(tone)?.let { return@withContext it }
        try {
            if (!media.delete(tone.id, identity(tone))) return@withContext gone(tone)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("tone not deleted: ${e.javaClass.simpleName}")
            return@withContext ToneEdit.Failed
        }
        list.remove(tone.id)
        ToneEdit.Done(null)
    }

    /**
     * Renames [tone]'s file to [name], only while its row is still this tone ([isSavedTone]); the title the sound
     * pickers list comes from the new file name (MediaStore rescans a moved file before the update returns).
     */
    suspend fun rename(tone: SavedTone, name: String): ToneEdit = withContext(io) {
        checked(tone)?.let { return@withContext it }
        try {
            if (!media.update(tone.id, ToneEntry.renameValues(name), identity(tone))) return@withContext gone(tone)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("tone not renamed: ${e.javaClass.simpleName}")
            return@withContext ToneEdit.Failed
        }
        val row = readRow(tone.id)
        val renamed = tone.copy(
            name = row?.title?.takeIf { it.isNotBlank() } ?: ToneEntry.safeName(name),
            fileName = row?.displayName.orEmpty(),
            size = row?.size ?: 0,
        )
        if (!list.put(renamed)) log("tone renamed but not listed")
        ToneEdit.Done(renamed)
    }

    /** Drops the tones deleted outside Lyrify from the list; when MediaStore cannot say, the list stays as it is. */
    suspend fun prune(): Unit = withContext(io) {
        val ids = list.state.first().tones.keys
        if (ids.isEmpty()) return@withContext
        val present = try {
            media.existing(ids)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("tones not checked: ${e.javaClass.simpleName}")
            null
        } ?: return@withContext
        if (!present.containsAll(ids)) list.retain(present)
    }

    /** Null when [tone]'s row is still the tone; [ToneEdit.Gone] (dropped from the list) or [ToneEdit.Failed] otherwise. */
    private suspend fun checked(tone: SavedTone): ToneEdit? {
        // Older entries have no identity to verify. Refuse edits but keep their history listed.
        if (tone.fileName.isBlank() || tone.size <= 0) return ToneEdit.Gone
        val row = try {
            media.row(tone.id)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("tone not checked: ${e.javaClass.simpleName}")
            return ToneEdit.Failed
        }
        if (isSavedTone(tone, row)) return null
        return gone(tone)
    }

    private fun identity(tone: SavedTone): MediaRow = MediaRow(tone.name, tone.fileName, tone.size, "${tone.kind.folder}/")

    private suspend fun gone(tone: SavedTone): ToneEdit {
        log("tone row no longer the saved tone, left alone and unlisted")
        list.remove(tone.id)
        return ToneEdit.Gone
    }

    private fun readRow(id: Long): MediaRow? = try {
        media.row(id)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        null
    }

    private suspend fun saved(source: MixSource, request: ToneRequest, onProgress: (Float) -> Unit): SaveResult {
        val entry = ToneEntry.of(request.songTitle, request.kind)
        val id = try {
            media.insert(entry.insertValues())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            log("tone not saved, no MediaStore row: ${e.javaClass.simpleName}")
            return SaveResult.Failed(SaveFailure.NOT_SAVED)
        }
        val pending = readRow(id)
        try {
            val fade = FadeEnvelope.of(request.window, request.fadeInMs, request.fadeOutMs)
            media.write(id) { fd -> newEncoder(fd).use { ToneAudio.render(source, request.window, fade, it, onProgress) } }
            media.update(id, ToneEntry.PUBLISH)
        } catch (e: Throwable) {
            withContext(NonCancellable) { discard(id, pending) }
            when (e) {
                is CancellationException -> throw e
                is ToneSourceException -> {
                    log("tone not saved, song unreadable: ${e.message}")
                    return SaveResult.Failed(SaveFailure.SONG_UNREADABLE)
                }
                is Exception -> {
                    log("tone not saved: ${e.javaClass.simpleName}")
                    return SaveResult.Failed(SaveFailure.NOT_SAVED)
                }
                else -> throw e
            }
        }
        // As MediaStore lists the published file: the name it may have made unique, the size, the scanner's title.
        val row = readRow(id)
        val tone = SavedTone(
            id = id,
            name = row?.title?.takeIf { it.isNotBlank() } ?: entry.title,
            kind = request.kind,
            songPath = request.songPath,
            startMs = request.window.startMs,
            lengthMs = request.window.lengthMs,
            fadeInMs = request.fadeInMs,
            fadeOutMs = request.fadeOutMs,
            createdAt = clock(),
            fileName = row?.displayName ?: entry.displayName,
            size = row?.size ?: 0,
        )
        // The file is saved either way; a list that failed to write only misses it until it is saved again.
        if (!list.put(tone)) log("tone saved but not listed")
        return SaveResult.Saved(tone)
    }

    private fun discard(id: Long, pending: MediaRow?) {
        if (pending == null) return
        try {
            val current = media.row(id) ?: return
            if (current.displayName != pending.displayName || current.relativePath != pending.relativePath) return
            media.delete(id, current)
        } catch (e: Exception) {
            log("half-made tone not removed (it expires): ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /** The app's: MediaStore, the song decoder, the AAC encoder and the "Your tones" list. */
        fun of(context: Context): ToneFiles = ToneFiles(
            media = AndroidToneMedia(context.contentResolver),
            openSong = ::MixDecoder,
            newEncoder = ::AacToneEncoder,
            list = ToneListStore.of(context),
            clock = System::currentTimeMillis,
            log = { Log.w(LOG_TAG, it) },
        )
    }
}
