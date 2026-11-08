package app.lyrify.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import app.lyrify.LOG_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/**
 * Reads and writes the ledger. Entries this version cannot read never fail a read ([LedgerJson.decode] keeps them as
 * they are). A file that is not a ledger at all is first copied to [backupDir] as `ledger-corrupt-<epochMillis>.json`
 * (the [MAX_BACKUPS] newest copies are kept), and only then reported as corrupt, so the store starts over without
 * losing it. When the copy cannot be made, the read fails with a plain IOException instead, and the file stays as it
 * is. DataStore reads a corrupt file twice before it starts over: the second read finds the copy of the first. The log
 * gets the copy's name and size only, never what the ledger holds.
 */
internal class LedgerSerializer(private val backupDir: File, private val clock: () -> Long, private val log: (String) -> Unit) : Serializer<LedgerState> {
    override val defaultValue: LedgerState = LedgerState()

    override suspend fun readFrom(input: InputStream): LedgerState {
        val bytes = input.readBytes()
        val state = try {
            LedgerJson.decode(bytes.decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable ledger, kept as ${keep(bytes)}", e)
        }
        if (state.unknownEntries.isNotEmpty()) log("ledger: ${state.unknownEntries.size} entries from a newer version kept as they are")
        return state
    }

    override suspend fun writeTo(t: LedgerState, output: OutputStream) {
        output.write(LedgerJson.encode(t).encodeToByteArray())
    }

    /** The name of a copy of [bytes] in [backupDir]: one already there, or a new one. Throws an IOException when it cannot make one. */
    private fun keep(bytes: ByteArray): String {
        backups().firstOrNull { (_, copy) -> copy.length() == bytes.size.toLong() && copy.readBytes().contentEquals(bytes) }?.let { return it.second.name }
        val copy = File(backupDir, "$BACKUP_PREFIX${clock()}$BACKUP_SUFFIX")
        // On storage before the store starts over.
        Files.write(copy.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
        log("ledger unreadable: kept as ${copy.name} (${bytes.size} bytes), starting over")
        // The new copy always stays, even when the clock stepped back and it is not the newest by its name.
        backups().filter { it.second != copy }.sortedByDescending { it.first }.drop(MAX_BACKUPS - 1).forEach { (_, old) ->
            try {
                Files.deleteIfExists(old.toPath())
            } catch (e: IOException) {
                log("ledger: old copy ${old.name} not removed: ${e.javaClass.simpleName}")
            }
        }
        return copy.name
    }

    /** The copies in [backupDir], with the time in their names. */
    private fun backups(): List<Pair<Long, File>> =
        backupDir.listFiles().orEmpty().mapNotNull { file ->
            val time = file.name.takeIf { it.startsWith(BACKUP_PREFIX) && it.endsWith(BACKUP_SUFFIX) }
                ?.removePrefix(BACKUP_PREFIX)?.removeSuffix(BACKUP_SUFFIX)?.toLongOrNull()
            time?.let { it to file }
        }

    companion object {
        const val MAX_BACKUPS: Int = 3
        private const val BACKUP_PREFIX: String = "ledger-corrupt-"
        private const val BACKUP_SUFFIX: String = ".json"
    }
}

/**
 * The ledger's DataStore at [file]: one per file and process. It starts over only once [LedgerSerializer] kept a copy
 * in [backupDir].
 */
internal fun ledgerStore(file: File, backupDir: File, scope: CoroutineScope, clock: () -> Long, log: (String) -> Unit): DataStore<LedgerState> =
    DataStoreFactory.create(
        serializer = LedgerSerializer(backupDir, clock, log),
        corruptionHandler = ReplaceFileCorruptionHandler { LedgerState() },
        scope = scope,
        produceFile = { file },
    )

/**
 * The app's ledger: app-private `files/datastore/ledger.json`, copies of an unreadable one in `files`; never backed up
 * (allowBackup is off). One store per process.
 */
private object LedgerFile {
    private var store: DataStore<LedgerState>? = null

    @Synchronized
    fun store(context: Context): DataStore<LedgerState> = store ?: ledgerStore(
        file = context.applicationContext.dataStoreFile("ledger.json"),
        backupDir = context.applicationContext.filesDir,
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        clock = System::currentTimeMillis,
        log = { Log.w(LOG_TAG, it) },
    ).also { store = it }
}

/**
 * The ledger as [data] gives it, or [LedgerState.UNREADABLE] when the file cannot be read (it could not be kept aside,
 * or storage fails): never an empty ledger, which would have the sweep look every song up again.
 */
internal fun readableLedger(data: Flow<LedgerState>, log: (String) -> Unit): Flow<LedgerState> = data.catch { e ->
    if (e !is IOException) throw e
    log("ledger unreadable: ${e.javaClass.simpleName}")
    emit(LedgerState.UNREADABLE)
}

/** The ledger in a typed DataStore; one file per process however many repositories are made. */
class LedgerRepository(context: Context) : LedgerStore {
    private val store: DataStore<LedgerState> = LedgerFile.store(context)

    override val ledger: Flow<LedgerState> = readableLedger(store.data) { Log.w(LOG_TAG, it) }

    override suspend fun entry(path: String): LedgerEntry? = ledger.first().entries[path]

    /** A failed write is retried a few times, then reported; the track is handled again next time it is seen. */
    override suspend fun put(entry: LedgerEntry): Boolean =
        writeWithRetries(onFailure = { Log.w(LOG_TAG, "ledger not written: ${it.javaClass.simpleName}") }) {
            store.updateData { it.put(entry) }
        }

    override suspend fun drop(path: String): Boolean =
        writeWithRetries(onFailure = { Log.w(LOG_TAG, "ledger not written: ${it.javaClass.simpleName}") }) {
            store.updateData { it.drop(path) }
        }
}
