package app.lyrify.media

import android.content.ContentResolver
import android.provider.MediaStore
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.lyrics.LocalTrack
import app.lyrify.verbose
import java.io.File

/**
 * Finds the local audio files behind a session track in MediaStore (readable for all audio
 * with All files access). Samsung Music's media ids are its own, not MediaStore ids, so
 * files are matched by name. Blocking: call it on a background thread.
 */
class TrackResolver(private val resolver: ContentResolver) {
    /**
     * Every file [track] may be, as [SessionTracks.candidates] decides, that has a duration
     * and is still on disk. Empty when there is none, or too many to tell apart.
     */
    fun resolve(track: SessionTrack): List<LocalTrack> {
        val rows = SessionTracks.candidates(query(track.title), track) { if (verbose()) Log.v(LOG_TAG, it) }
        if (rows.isEmpty()) Log.d(LOG_TAG, "no MediaStore match for \"${track.title}\"")
        return rows.filter { it.durationMs > 0 && File(it.path).isFile }.map { it.toLocalTrack() }
    }

    /** Rows whose title equals [title] ignoring ASCII case; [SessionTracks.candidates] then compares normalized. */
    private fun query(title: String): List<AudioRow> = try {
        resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, PROJECTION, SELECTION, arrayOf(title), null)?.use { cursor ->
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
        const val SELECTION: String = "${MediaStore.Audio.Media.TITLE} = ? COLLATE NOCASE"

        val PROJECTION: Array<String> = arrayOf(
            DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
        )
    }
}
