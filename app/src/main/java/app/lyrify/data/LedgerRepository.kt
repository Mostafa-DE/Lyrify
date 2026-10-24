package app.lyrify.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import app.lyrify.LOG_TAG
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private object LedgerSerializer : Serializer<LedgerState> {
    override val defaultValue: LedgerState = LedgerState()

    override suspend fun readFrom(input: InputStream): LedgerState = try {
        LedgerJson.decode(input.readBytes().decodeToString())
    } catch (e: SerializationException) {
        throw CorruptionException("Unreadable ledger", e)
    }

    override suspend fun writeTo(t: LedgerState, output: OutputStream) {
        output.write(LedgerJson.encode(t).encodeToByteArray())
    }
}

/** App-private file, never backed up (allowBackup is off). An unreadable file starts over. */
private val Context.ledgerStore: DataStore<LedgerState> by dataStore(
    fileName = "ledger.json",
    serializer = LedgerSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Log.w(LOG_TAG, "ledger unreadable, starting over: ${e.message}")
        LedgerState()
    },
)

/** The ledger in a typed DataStore; one file per process however many repositories are made. */
class LedgerRepository(context: Context) : LedgerStore {
    private val store: DataStore<LedgerState> = context.applicationContext.ledgerStore

    override val ledger: Flow<LedgerState> = store.data.catch { e ->
        if (e !is IOException) throw e
        Log.w(LOG_TAG, "ledger unreadable: ${e.javaClass.simpleName}")
        emit(LedgerState())
    }

    override suspend fun entry(path: String): LedgerEntry? = ledger.first().entries[path]

    /** A failed write is logged; the track is simply handled again next time it is seen. */
    override suspend fun put(entry: LedgerEntry) {
        try {
            store.updateData { it.put(entry) }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "ledger not written: ${e.javaClass.simpleName}")
        }
    }
}
