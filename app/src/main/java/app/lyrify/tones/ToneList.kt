package app.lyrify.tones

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.lyrify.LOG_TAG
import app.lyrify.data.writeWithRetries
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A tone Lyrify made, in "Your tones": its MediaStore id ([id], the key), its [name] as the phone's sound pickers list
 * it, and how it was cut from the song at [songPath]. [fileName] and [size] are the file's as MediaStore listed it when
 * saved or renamed: a delete or rename goes ahead only while the row still has them ([isSavedTone]). Fields added later
 * get defaults, so older lists load unchanged.
 */
@Serializable
data class SavedTone(
    val id: Long,
    val name: String,
    val kind: ToneKind,
    val songPath: String,
    val startMs: Long,
    val lengthMs: Long,
    val fadeInMs: Long = 0,
    val fadeOutMs: Long = 0,
    val createdAt: Long = 0,
    val fileName: String = "",
    val size: Long = 0,
)

/** "Your tones", stored by [TonesJson]. */
data class TonesState(
    val tones: Map<Long, SavedTone> = emptyMap(),
    /** Entries this version cannot read (a newer version's), by key, kept as stored and written back so. */
    val unknown: Map<String, JsonElement> = emptyMap(),
) {
    fun put(tone: SavedTone): TonesState = copy(tones = tones + (tone.id to tone), unknown = unknown - tone.id.toString())

    fun remove(id: Long): TonesState = copy(tones = tones - id, unknown = unknown - id.toString())

    /** Only the tones whose files are still on the phone: [ids]. */
    fun retain(ids: Set<Long>): TonesState = copy(tones = tones.filterKeys { it in ids })

    fun newestFirst(): List<SavedTone> = tones.values.sortedByDescending { it.createdAt }
}

/** The stored form of [TonesState]: JSON with a schema version, entries by MediaStore id. */
object TonesJson {
    const val SCHEMA_VERSION: Int = 1

    private const val SCHEMA_VERSION_KEY: String = "schemaVersion"
    private const val TONES_KEY: String = "tones"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(state: TonesState): String {
        val tones = state.unknown + state.tones.entries.associate { (id, tone) -> id.toString() to json.encodeToJsonElement(SavedTone.serializer(), tone) }
        val root = JsonObject(mapOf(SCHEMA_VERSION_KEY to JsonPrimitive(SCHEMA_VERSION), TONES_KEY to JsonObject(tones)))
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Lenient: unknown fields are ignored, missing ones take their defaults, and each entry is read on its own, so one
     * this version cannot read (a newer kind, a missing field) is kept in [TonesState.unknown] and never costs the
     * others. Throws a SerializationException only for text that is not a tones list at all.
     */
    fun decode(text: String): TonesState {
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("not a tones list: no JSON object")
        val stored = when (val tones = root[TONES_KEY]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> tones
            else -> throw SerializationException("not a tones list: its tones are not a JSON object")
        }
        val read = stored.mapValues { (_, element) -> tone(element) }
        return TonesState(
            tones = read.values.filterNotNull().associateBy { it.id },
            unknown = stored.filterKeys { read[it] == null },
        )
    }

    private fun tone(element: JsonElement): SavedTone? = try {
        json.decodeFromJsonElement(SavedTone.serializer(), element)
    } catch (_: IllegalArgumentException) {
        // A SerializationException is one.
        null
    }
}

/** Where "Your tones" is kept: a DataStore file in the app ([ToneListStore]), memory in tests. */
interface ToneList {
    val state: Flow<TonesState>

    /** False when the change could not be written; never throws for that. */
    suspend fun put(tone: SavedTone): Boolean

    suspend fun remove(id: Long): Boolean

    suspend fun retain(ids: Set<Long>): Boolean
}

/**
 * Reads and writes [TonesState]. A file that is not a tones list starts the list over: the tone files themselves stay
 * in MediaStore, so only the list's memory of them is lost. The log gets no names or paths.
 */
internal class TonesSerializer : Serializer<TonesState> {
    override val defaultValue: TonesState = TonesState()

    override suspend fun readFrom(input: InputStream): TonesState = try {
        TonesJson.decode(input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable tones list", e)
    }

    override suspend fun writeTo(t: TonesState, output: OutputStream) {
        output.write(TonesJson.encode(t).encodeToByteArray())
    }
}

/** The tones list's DataStore at [file]: one per file and process. */
internal fun tonesStore(file: File, scope: CoroutineScope, log: (String) -> Unit): DataStore<TonesState> =
    DataStoreFactory.create(
        serializer = TonesSerializer(),
        corruptionHandler = ReplaceFileCorruptionHandler {
            log("tones list unreadable, starting over")
            TonesState()
        },
        scope = scope,
        produceFile = { file },
    )

/** [ToneList] on a DataStore; a failed write is retried a few times, then reported. */
class ToneListStore internal constructor(private val store: DataStore<TonesState>, private val log: (String) -> Unit) : ToneList {
    override val state: Flow<TonesState> = store.data.catch { e ->
        if (e !is IOException) throw e
        log("tones list unreadable: ${e.javaClass.simpleName}")
        emit(TonesState())
    }

    override suspend fun put(tone: SavedTone): Boolean = write { it.put(tone) }

    override suspend fun remove(id: Long): Boolean = write { it.remove(id) }

    override suspend fun retain(ids: Set<Long>): Boolean = write { it.retain(ids) }

    private suspend fun write(change: (TonesState) -> TonesState): Boolean =
        writeWithRetries(onFailure = { log("tones list not written: ${it.javaClass.simpleName}") }) { store.updateData(change) }

    companion object {
        private var shared: ToneListStore? = null

        /** The app's: app-private `files/datastore/tones.json`, never backed up (allowBackup is off). One per process. */
        @Synchronized
        fun of(context: Context): ToneListStore = shared ?: run {
            val log: (String) -> Unit = { Log.w(LOG_TAG, it) }
            val file = context.applicationContext.dataStoreFile("tones.json")
            ToneListStore(tonesStore(file, CoroutineScope(Dispatchers.IO + SupervisorJob()), log), log)
        }.also { shared = it }
    }
}
