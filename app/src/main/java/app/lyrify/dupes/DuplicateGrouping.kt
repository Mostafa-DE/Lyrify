package app.lyrify.dupes

import app.lyrify.lyrics.TrackNames
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs

/** One audio file as MediaStore knows it. Only its tags, size and place; the audio itself is never read here. */
@Serializable
data class AudioFile(
    /** MediaStore's `_ID`: the item the phone's trash request names. */
    val id: Long,
    /** Absolute path, as the ledger keys songs. */
    val path: String,
    /** MediaStore's `RELATIVE_PATH`, with a trailing slash ("Music/kjb/"). */
    val relativePath: String? = null,
    val displayName: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long,
    val size: Long,
    val mimeType: String? = null,
    /** Bits per second, as MediaStore indexed it; null when it did not. */
    val bitrate: Long? = null,
    /** When MediaStore first saw the file, wall-clock millis; null when it does not say. */
    val addedAt: Long? = null,
    /** A `.lrc` is next to it. Known only for files in a group ([DuplicateGrouping.groups]). */
    val hasLyrics: Boolean = false,
) {
    /** [bitrate], or worked out from the size and length when MediaStore has none; 0 when neither is known. */
    val effectiveBitrate: Long
        get() = bitrate?.takeIf { it > 0 } ?: if (durationMs > 0) size * BITS_PER_BYTE * MS_PER_SECOND / durationMs else 0

    /** FLAC, WAV, AIFF or APE, by the MIME type or else the file name. */
    val lossless: Boolean
        get() = mimeType?.lowercase(Locale.ROOT) in LOSSLESS_TYPES ||
            displayName.substringAfterLast('.', "").lowercase(Locale.ROOT) in LOSSLESS_EXTENSIONS
}

private const val BITS_PER_BYTE: Long = 8
private const val MS_PER_SECOND: Long = 1_000
private val LOSSLESS_TYPES: Set<String> = setOf(
    "audio/flac", "audio/x-flac", "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave",
    "audio/aiff", "audio/x-aiff", "audio/ape", "audio/x-ape", "audio/x-monkeys-audio",
)
private val LOSSLESS_EXTENSIONS: Set<String> = setOf("flac", "wav", "aif", "aiff", "ape")

/** How sure Lyrify is that a group's files are one song, most sure first. */
@Serializable
enum class Confidence {
    /** The same bytes: the same size and the same content hash. */
    IDENTICAL,

    /** The same title and artist and a length within 2 s, in other files (another bitrate or format). */
    SAME_SONG,

    /** The same title and a length within 2 s, but a missing or other artist, or a title from the file name. */
    MAYBE,
}

/** Files that may be one song. [files] has the suggested keep first (see [DuplicateGrouping]): only a hint, never acted on. */
@Serializable
data class DuplicateGroup(val confidence: Confidence, val files: List<AudioFile>) {
    val keep: AudioFile get() = files.first()

    val copies: Int get() = files.size

    /** The space every copy but the suggested keep takes. */
    val extraBytes: Long get() = files.sumOf { it.size } - keep.size

    /**
     * The group's files by id and size: what "Not duplicates" remembers. A file added, gone or changed in size makes
     * another key, so the group can come back.
     */
    val key: String get() = files.map { it.id to it.size }.sortedBy { it.first }.joinToString(",") { (id, size) -> "$id:$size" }
}

/** What a scan found: [scanned] files looked at, at [scannedAt] (wall-clock millis), and the groups not ruled out by the user. */
@Serializable
data class ScanResults(val scannedAt: Long, val scanned: Int, val groups: List<DuplicateGroup>) {
    /** The space all extra copies take. */
    val extraBytes: Long get() = groups.sumOf { it.extraBytes }

    fun section(confidence: Confidence): List<DuplicateGroup> = groups.filter { it.confidence == confidence }

    /** Groups per confidence, zeros included. */
    fun counts(): Map<Confidence, Int> = Confidence.entries.associateWith { confidence -> groups.count { it.confidence == confidence } }

    /** The tab's badge: groups whose key is not in [reviewed]. */
    fun unreviewed(reviewed: Set<String>): Int = groups.count { it.key !in reviewed }
}

/**
 * Finds the groups of files that may be one song, as pure functions of MediaStore's tags and the content keys of
 * [identicalKeys]. Every file is in at most one group, the most certain one:
 *
 * 1. Files with one content key are identical. Identical copies form one unit from here on.
 * 2. Units with the same cleaned title and artist ([TrackNames]), the same version words and lengths within 2 s of the
 *    shortest are the same song; identical copies among them stay in that one group.
 * 3. Single files left with a title in common (also the title half of an untagged "Artist - Title" title, or the title
 *    in the file name) and lengths within 2 s are a maybe. Neither identical copies nor same-song files are pulled into a maybe.
 *
 * A live, edited, remixed, acoustic, demo or remastered version (its title or album says so, [VERSION_WORDS]) is only
 * grouped with the same version, unless the files are identical.
 *
 * The suggested keep is the copy in the best place ([Place]: Music and the main music folders, then other folders, then
 * download and messaging folders), then the oldest added, of identical files; of other files, the highest bitrate, then
 * the best place, then lossless, then the most complete tags, then the one with lyrics, then the oldest added.
 */
object DuplicateGrouping {
    /** At most this far apart, two files' lengths can be one song's. */
    const val LENGTH_TOLERANCE_MS: Long = 2_000

    private val VERSION_WORDS = Regex("""\b(live|edit(?:ed)?|remix(?:ed)?|acoustic|demo|remaster(?:ed)?|mix|version|unplugged|sessions?|re-recorded|extended|deluxe|bonus)\b""", RegexOption.IGNORE_CASE)
    private val ARTIST_TITLE = Regex("""\s+[-–—]\s+""")
    private const val BITS_PER_KBPS: Long = 1_000

    /** Where a copy is, the best place to keep it first. */
    private enum class Place {
        /** Music/, or a main music folder ([mainFolders]). */
        MUSIC,
        OTHER,

        /** A download or messaging folder ([DOWNLOAD_FOLDERS], or a known app media path). */
        DOWNLOADS,
    }

    private const val MUSIC_FOLDER: String = "music"
    private val DOWNLOAD_FOLDERS: Set<String> = setOf("download", "downloads", "telegram", "whatsapp", "bluetooth", "documents")

    /** A top folder holding at least this many of the files, and at least this share of them, is a main music folder. */
    private const val MAIN_FOLDER_FILES: Int = 10
    private const val MAIN_FOLDER_SHARE: Double = 0.1

    /**
     * The groups among [files]: identical first, then same song, then maybe, each by title. [identical] holds content
     * keys by file id, for files that have one; [hasLyrics] is asked only for files in a group.
     */
    fun groups(files: List<AudioFile>, identical: Map<Long, String> = emptyMap(), hasLyrics: (AudioFile) -> Boolean): List<DuplicateGroup> {
        val songs = files.distinctBy { it.id }.sortedBy { it.id }.map(::Song)
        val units = songs.groupBy { song -> identical[song.file.id] ?: "#${song.file.id}" }.values.toList()
        val sameSong = windows(units.filter { it.first().artist != null }, { it.first() }) { a, b -> a.sameSongAs(b) }
        val inSameSong = sameSong.flatten().toSet()
        val identicalOnly = units.filter { it.size > 1 && it !in inSameSong }
        val singles = units.filter { it.size == 1 && it !in inSameSong }.map { it.single() }
        val maybe = windows(singles, { it }) { a, b -> a.maybeSameAs(b) }
        val found = identicalOnly.map { Confidence.IDENTICAL to it } +
            sameSong.map { cluster -> Confidence.SAME_SONG to cluster.flatten() } +
            maybe.map { Confidence.MAYBE to it }
        val main = mainFolders(files)
        return found.map { (confidence, members) -> group(confidence, members.map { it.file }, main, hasLyrics) }
            .sortedWith(compareBy<DuplicateGroup> { it.confidence }.thenBy { TrackNames.normalize(it.keep.title) }.thenBy { it.key })
    }

    /** [files] as one group, the suggested keep first (see the ranking above); [main]: the main music folders. */
    private fun group(confidence: Confidence, files: List<AudioFile>, main: Set<String>, hasLyrics: (AudioFile) -> Boolean): DuplicateGroup {
        val known = files.map { it.copy(hasLyrics = hasLyrics(it)) }
        val byPlace = compareBy<AudioFile> { place(it, main) }
        val ranked = if (confidence == Confidence.IDENTICAL) {
            known.sortedWith(byPlace.then(oldestFirst))
        } else {
            known.sortedWith(highestBitrate.then(byPlace).then(betterCopy).then(oldestFirst))
        }
        return DuplicateGroup(confidence, ranked)
    }

    private val oldestFirst: Comparator<AudioFile> = compareBy<AudioFile> { it.addedAt?.takeIf { date -> date > 0 } ?: Long.MAX_VALUE }.thenBy { it.id }

    /** The highest bitrate first, in whole kbps. */
    private val highestBitrate: Comparator<AudioFile> = compareByDescending<AudioFile> { (it.effectiveBitrate + BITS_PER_KBPS / 2) / BITS_PER_KBPS }

    /** Lossless first, then the most complete tags, then the one with lyrics. */
    private val betterCopy: Comparator<AudioFile> =
        compareByDescending<AudioFile> { it.lossless }
            .thenByDescending { Song(it).tags }
            .thenByDescending { it.hasLyrics }

    /** The top folders of [files] (lowercase) holding a good part of them: where the user keeps their music. */
    private fun mainFolders(files: List<AudioFile>): Set<String> =
        files.mapNotNull(::topFolder).groupingBy { it }.eachCount()
            .filter { (_, count) -> count >= MAIN_FOLDER_FILES && count >= files.size * MAIN_FOLDER_SHARE }
            .keys

    /** The first folder of the file's place on its volume ("Music", "kjb", "Telegram"), lowercase; null in the top folder. */
    private fun topFolder(file: AudioFile): String? =
        file.relativePath?.substringBefore('/')?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun place(file: AudioFile, main: Set<String>): Place {
        val folder = topFolder(file)
        // The folders only, never the file's own name: a song may be called "Download".
        val folders = file.relativePath.orEmpty().lowercase(Locale.ROOT).split('/').filter { it.isNotEmpty() }
        val app = folders.getOrNull(2)
        val appMedia = folders.take(2) == listOf("android", "media") &&
            (app == "org.telegram.messenger" || app == "org.telegram.messenger.web" || app == "com.whatsapp" || app == "com.whatsapp.w4b")
        return when {
            folder in DOWNLOAD_FOLDERS || appMedia -> Place.DOWNLOADS
            folder == MUSIC_FOLDER || folder in main -> Place.MUSIC
            else -> Place.OTHER
        }
    }

    /**
     * Clusters of [items] (by their [song]) whose lengths are within [LENGTH_TOLERANCE_MS] of the cluster's shortest and
     * that [match] it, shortest first; only clusters of two or more. Each item is in at most one.
     */
    private fun <T> windows(items: List<T>, song: (T) -> Song, match: (Song, Song) -> Boolean): List<List<T>> {
        val sorted = items.filter { song(it).file.durationMs > 0 }.sortedWith(compareBy<T> { song(it).file.durationMs }.thenBy { song(it).file.id })
        val taken = HashSet<Int>()
        val clusters = ArrayList<List<T>>()
        for (start in sorted.indices) {
            if (start in taken) continue
            val seed = song(sorted[start])
            val members = (start + 1 until sorted.size)
                .takeWhile { song(sorted[it]).file.durationMs - seed.file.durationMs <= LENGTH_TOLERANCE_MS }
                .filter { it !in taken && match(seed, song(sorted[it])) }
            if (members.isEmpty()) continue
            taken += members + start
            clusters += (listOf(start) + members).map { sorted[it] }
        }
        return clusters
    }

    /** What the grouping compares of one file, worked out once. */
    private class Song(val file: AudioFile) {
        /** The cleaned artist tag as a key; null when unknown. */
        val artist: String? = TrackNames.cleanArtist(file.artist)?.let(::key)

        /** No title tag: MediaStore gave the file's name as the title (with no artist either). */
        private val fromFileName: Boolean =
            file.title.isBlank() || (artist == null && TrackNames.normalize(file.title) == TrackNames.normalize(file.displayName.substringBeforeLast('.')))

        private val name: String =
            (if (fromFileName) TrackNames.fileTitle(file.displayName) else null) ?: TrackNames.cleanTitle(file.title)

        /** The title as a key; an "Artist - Title" title whose artist is the tag's counts as the title alone. */
        val title: String = halves(name)?.takeIf { artist != null && key(it.first) == artist }?.let { key(it.second) } ?: key(name)

        /**
         * The titles a maybe may share: [title], and without an artist tag the title half of an "Artist - Title" title
         * (never the artist half, which every song of that artist shares).
         */
        private val titles: Set<String> = setOf(title) + listOfNotNull(halves(name)?.second?.takeIf { artist == null }?.let(::key))

        /** The version words of the title and album, each as often as it appears. */
        private val versions: List<String> = listOfNotNull(file.title, file.album)
            .flatMap { text -> VERSION_WORDS.findAll(text).map { canonical(it.groupValues[1]) }.toList() }
            .sorted()

        /** How many of title, artist and album are real tags. */
        val tags: Int = listOf(!fromFileName, artist != null, TrackNames.cleanAlbum(file.album, file.artist) != null).count { it }

        fun sameSongAs(other: Song): Boolean =
            artist != null && artist == other.artist && title == other.title && versions == other.versions && closeInLength(other)

        fun maybeSameAs(other: Song): Boolean = versions == other.versions && titles.any { usefulTitle(it) && it in other.titles } && closeInLength(other)

        private fun usefulTitle(title: String): Boolean =
            title.count(Char::isLetter) >= 4 || title.split(' ').count { word -> word.any(Char::isLetter) } >= 2

        private fun closeInLength(other: Song): Boolean =
            file.durationMs > 0 && other.file.durationMs > 0 && abs(file.durationMs - other.file.durationMs) <= LENGTH_TOLERANCE_MS

        private fun halves(text: String): Pair<String, String>? {
            val parts = text.split(ARTIST_TITLE, limit = 2).map(String::trim)
            return if (parts.size == 2 && parts.all { it.isNotEmpty() }) parts[0] to parts[1] else null
        }

        private fun key(text: String): String = TrackNames.normalize(TrackNames.withoutFeat(text))

        /** "Remastered" counts as "remaster", "edited" as "edit", "remixed" as "remix". */
        private fun canonical(word: String): String = word.lowercase(Locale.ROOT).removeSuffix("ed").removeSuffix("s")
    }
}
