package app.lyrify.lyrics

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LedgerStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** LRCLIB scripted per test; records every request, its duration and its lane. */
class FakeLrclib : LrclibClient {
    val gets = mutableListOf<TrackQuery>()
    val searches = mutableListOf<TrackQuery>()
    val durations = mutableListOf<Long>()
    val lanes = mutableListOf<Lane>()
    var onGet: (TrackQuery) -> LrclibResult = { LrclibResult.NotFound }
    var onSearch: (TrackQuery) -> LrclibResult = { LrclibResult.Found(emptyList()) }

    /** Records asked for by id, and the answer for each. */
    val ids = mutableListOf<Long>()
    var onId: (Long) -> LrclibResult = { LrclibResult.NotFound }

    /** How long each answer takes, in virtual time. */
    var answerMs: Long = 0

    val requests: Int get() = gets.size + searches.size + ids.size

    override suspend fun get(artist: String, title: String, album: String?, durationSeconds: Long, lane: Lane): LrclibResult {
        val query = TrackQuery(artist, title, album)
        gets += query
        durations += durationSeconds
        lanes += lane
        delay(answerMs)
        return onGet(query)
    }

    override suspend fun search(title: String, artist: String?, lane: Lane): LrclibResult {
        val query = TrackQuery(artist, title)
        searches += query
        lanes += lane
        delay(answerMs)
        return onSearch(query)
    }

    override suspend fun byId(id: Long, lane: Lane): LrclibResult {
        ids += id
        lanes += lane
        delay(answerMs)
        return onId(id)
    }
}

class MemoryLedger : LedgerStore {
    private val state = MutableStateFlow(LedgerState())
    override val ledger: StateFlow<LedgerState> = state

    /** Sees every entry put and says whether it is stored: false acts as a ledger that cannot write. */
    var accepts: (LedgerEntry) -> Boolean = { true }

    /** How long each put takes, in virtual time. */
    var putMs: Long = 0

    override suspend fun entry(path: String): LedgerEntry? = state.value.entries[path]

    /** As the app's ledger when its file cannot be read: [LedgerState.UNREADABLE], and nothing can be stored. */
    fun becomeUnreadable() {
        state.value = LedgerState.UNREADABLE
    }

    override suspend fun put(entry: LedgerEntry): Boolean {
        delay(putMs)
        if (!accepts(entry) || !state.value.readable) return false
        state.update { it.put(entry) }
        return true
    }

    operator fun get(path: String): LedgerEntry? = state.value.entries[path]
}
