package app.lyrify.dupes

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What the Duplicates tab remembers between runs of the app. */
data class DuplicatesMemory(
    /** The last scan's groups, without those the user said are not duplicates; null before the first scan. */
    val results: ScanResults? = null,
    /** "Not duplicates": the [DuplicateGroup.key]s of groups never shown again. */
    val notDuplicates: Set<String> = emptySet(),
    /** Groups the user has opened: not counted in the tab's badge. */
    val reviewed: Set<String> = emptySet(),
    /**
     * Files the phone's trash dialog was opened for and whose answer was not handled yet. Kept so that an answer the app
     * did not live to hear is still settled ([Duplicates]); empty otherwise.
     */
    val pendingTrash: List<AudioFile> = emptyList(),
    /** Songs Lyrify moved to the phone's trash, restorable from the tab until they expire. */
    val trashed: List<TrashedSong> = emptyList(),
)

/** A song Lyrify moved to the phone's trash, at [trashedAt]: [file] as it was, restorable until [expiresAt] (wall-clock millis). */
@Serializable
data class TrashedSong(val file: AudioFile, val trashedAt: Long, val expiresAt: Long)

/**
 * [songs] as the phone's trash has them [now]: [inTrash] holds the ids in the trash, with when the phone deletes each
 * (null when MediaStore did not say), or is null when MediaStore could not be read. A song no longer in the trash
 * (restored, or deleted for good) or past its expiry drops off; while MediaStore cannot be read, only the expiry counts.
 */
fun stillTrashed(songs: List<TrashedSong>, inTrash: Map<Long, Long?>?, now: Long): List<TrashedSong> =
    songs.mapNotNull { song ->
        when {
            inTrash == null -> song
            song.file.id !in inTrash -> null
            else -> song.copy(expiresAt = inTrash[song.file.id] ?: song.expiresAt)
        }
    }.filter { it.expiresAt > now }

/**
 * The stored form of [DuplicatesMemory]: JSON with a version. Read leniently, as the ledger is: fields a newer version
 * wrote are ignored, fields added since take their defaults, and each group or file is read on its own, so one this
 * version cannot read (a newer confidence, a missing field) is left out instead of losing the rest. The user's
 * decisions are plain strings, always read.
 */
object DuplicatesJson {
    const val VERSION: Int = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(memory: DuplicatesMemory): String {
        val root = buildMap<String, JsonElement> {
            put(VERSION_KEY, JsonPrimitive(VERSION))
            memory.results?.let { put(RESULTS_KEY, json.encodeToJsonElement(ScanResults.serializer(), it)) }
            put(NOT_DUPLICATES_KEY, JsonArray(memory.notDuplicates.sorted().map(::JsonPrimitive)))
            put(REVIEWED_KEY, JsonArray(memory.reviewed.sorted().map(::JsonPrimitive)))
            put(PENDING_TRASH_KEY, JsonArray(memory.pendingTrash.map { json.encodeToJsonElement(AudioFile.serializer(), it) }))
            put(TRASHED_KEY, JsonArray(memory.trashed.map { json.encodeToJsonElement(TrashedSong.serializer(), it) }))
        }
        return json.encodeToString(JsonObject.serializer(), JsonObject(root))
    }

    /** The memory in [text]; null when it is not one at all (not a JSON object). */
    fun decode(text: String): DuplicatesMemory? {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: return null
        return DuplicatesMemory(
            results = (root[RESULTS_KEY] as? JsonObject)?.let(::results),
            notDuplicates = strings(root[NOT_DUPLICATES_KEY]),
            reviewed = strings(root[REVIEWED_KEY]),
            pendingTrash = items(root[PENDING_TRASH_KEY], AudioFile.serializer()),
            trashed = items(root[TRASHED_KEY], TrashedSong.serializer()),
        )
    }

    /** The results, with the groups this version reads (and that still are groups); null without a scan time. */
    private fun results(stored: JsonObject): ScanResults? {
        val scannedAt = (stored[SCANNED_AT_KEY] as? JsonPrimitive)?.longOrNull ?: return null
        val scanned = (stored[SCANNED_KEY] as? JsonPrimitive)?.intOrNull ?: 0
        return ScanResults(scannedAt, scanned, items(stored[GROUPS_KEY], DuplicateGroup.serializer()).filter { it.files.size > 1 })
    }

    private fun <T> items(element: JsonElement?, serializer: KSerializer<T>): List<T> =
        (element as? JsonArray).orEmpty().mapNotNull { item ->
            try {
                json.decodeFromJsonElement(serializer, item)
            } catch (_: IllegalArgumentException) {
                // A SerializationException is one.
                null
            }
        }

    private fun strings(element: JsonElement?): Set<String> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }.toSet()

    private const val VERSION_KEY: String = "version"
    private const val RESULTS_KEY: String = "results"
    private const val NOT_DUPLICATES_KEY: String = "notDuplicates"
    private const val REVIEWED_KEY: String = "reviewed"
    private const val PENDING_TRASH_KEY: String = "pendingTrash"
    private const val TRASHED_KEY: String = "trashed"
    private const val SCANNED_AT_KEY: String = "scannedAt"
    private const val SCANNED_KEY: String = "scanned"
    private const val GROUPS_KEY: String = "groups"
}

/**
 * [DuplicatesMemory] in one app-private file (never backed up: allowBackup is off), written whole or not at all
 * (written aside, then moved over). One that cannot be read is logged, by its size only, and the tab starts over.
 * Blocking.
 */
class DuplicatesStore(private val file: File, private val log: (String) -> Unit = {}) {
    fun load(): DuplicatesMemory {
        if (!file.isFile) return DuplicatesMemory()
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log("duplicates: not readable (${e.javaClass.simpleName}), starting over")
            return DuplicatesMemory()
        }
        return DuplicatesJson.decode(text) ?: DuplicatesMemory().also { log("duplicates: stored file unreadable (${text.length} chars), starting over") }
    }

    /** False when it could not be written; the file before stays then. */
    fun save(memory: DuplicatesMemory): Boolean {
        val aside = File(file.path + TMP)
        return try {
            file.parentFile?.mkdirs()
            aside.writeText(DuplicatesJson.encode(memory))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: IOException) {
            aside.delete()
            log("duplicates: not written (${e.javaClass.simpleName})")
            false
        }
    }

    private companion object {
        const val TMP: String = ".tmp"
    }
}
