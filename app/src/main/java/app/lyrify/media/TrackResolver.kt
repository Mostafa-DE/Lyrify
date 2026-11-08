package app.lyrify.media

import android.content.ContentResolver
import android.provider.MediaStore
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.lyrics.LocalTrack

/**
 * Finds the local audio files behind a session track in MediaStore (readable for all audio
 * with All files access), by [SessionTracks.resolve]'s rules. Blocking: call it on a
 * background thread.
 */
class TrackResolver(private val resolver: ContentResolver) : AudioLibrary {
    /**
     * Every file [track] may be ([SessionTracks.resolve]): a regular file in shared storage, with
     * a duration. Empty when there is none, or too many to tell apart.
     */
    fun resolve(track: SessionTrack): List<LocalTrack> =
        SessionTracks.resolve(track, this, SessionTracks::regularFile) { Log.d(LOG_TAG, it) }.map { it.toLocalTrack() }

    override fun byPath(path: String): AudioRow? = query("$DATA = ?", path).firstOrNull()

    override fun byId(id: Long): AudioRow? = query("${MediaStore.Audio.Media._ID} = ?", id.toString()).firstOrNull()

    override fun byTitle(title: String): List<AudioRow> = query(TITLE_SELECTION, title)

    override fun byDuration(fromMs: Long, toMs: Long): List<AudioRow> = query(DURATION_SELECTION, fromMs.toString(), toMs.toString())

    private fun query(selection: String, vararg args: String): List<AudioRow> = try {
        resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, PROJECTION, selection, arrayOf(*args), null)?.use { cursor ->
            val data = cursor.getColumnIndexOrThrow(DATA)
            val titles = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val duration = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            buildList {
                while (cursor.moveToNext()) {
                    val path = cursor.getString(data) ?: continue
                    add(
                        AudioRow(
                            path = path,
                            title = cursor.getString(titles).orEmpty(),
                            artist = cursor.getString(artist),
                            album = cursor.getString(album),
                            durationMs = cursor.getLong(duration),
                        ),
                    )
                }
            }
        }.orEmpty()
    } catch (e: SecurityException) {
        Log.w(LOG_TAG, "MediaStore refused the query (All files access off?): ${e.message}")
        emptyList()
    } catch (e: IllegalArgumentException) {
        Log.w(LOG_TAG, "MediaStore rejected the query: ${e.message}")
        emptyList()
    }

    private companion object {
        /**
         * The file's absolute path. Deprecated for writing since Android 10, but with All files
         * access it is the real path, which is what Samsung Music derives the `.lrc` path from.
         */
        @Suppress("DEPRECATION")
        const val DATA: String = MediaStore.Audio.Media.DATA

        /** NOCASE is in the keyword list MediaProvider's strict SQL grammar accepts. */
        const val TITLE_SELECTION: String = "${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE"

        /** Plain comparisons and AND, as MediaProvider's strict SQL grammar accepts. */
        const val DURATION_SELECTION: String = "${MediaStore.Audio.Media.DURATION} >= ? AND ${MediaStore.Audio.Media.DURATION} <= ?"

        val PROJECTION: Array<String> = arrayOf(
            DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
    }
}
