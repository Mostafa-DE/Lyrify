package app.lyrify.data

import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

/** What happened to a track's lyrics. */
@Serializable
enum class LyricsStatus {
    /** Lyrify wrote a synced `.lrc`. */
    SYNCED,

    /** A `.lrc` was already there; Lyrify never touches it. */
    EXISTING,

    /** LRCLIB has untimed lyrics only (kept for v2). */
    PLAIN_ONLY,
    NOT_FOUND,
    INSTRUMENTAL,

    /** LRCLIB has synced lyrics, but only for a version of another length. */
    DURATION_MISMATCH,

    /** LRCLIB could not be asked (overloaded, offline) or the file could not be written. */
    ERROR,

    /** Neither the artist tag nor the title names an artist: LRCLIB is not asked, a title alone could match any song. */
    UNIDENTIFIED,

    /** The user removed lyrics Lyrify wrote. Never added again automatically. */
    REMOVED,

    /**
     * Lyrify is creating the `.lrc`; the entry holds its text, hash and size. Still there later
     * only if the app died meanwhile: the file then decides what happened (see LyricsPipeline).
     */
    WRITING,
}

/** One audio file's lyrics history, keyed by its absolute [path]. Times are wall-clock millis. */
@Serializable
data class LedgerEntry(
    val path: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long,
    val status: LyricsStatus,
    /** The LRCLIB record behind [status]: the lyrics written, or the plain lyrics v2 will time. */
    val lrclibId: Long? = null,
    /** Lookups in a row that ended in [LyricsStatus.ERROR]; 0 once LRCLIB answers. */
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    /** When to ask LRCLIB again; null for never (lyrics are on disk, or removed by the user). */
    val nextAttemptAt: Long? = null,
    val updatedAt: Long,
    /** For [LyricsStatus.PLAIN_ONLY]: whether the plain lyrics' record has this file's duration (v2 decides what to use). */
    val durationMatched: Boolean? = null,
    /** SHA-256 (hex) of the `.lrc` bytes Lyrify wrote (or is writing); only a file still equal to it may be removed. */
    val sha256: String? = null,
    /** When those bytes were written; for [LyricsStatus.WRITING], when the write began. */
    val writtenAt: Long? = null,
    /** How many bytes those are. */
    val size: Long? = null,
    /** For [LyricsStatus.WRITING] only: the text being written, to recognise a file cut short (a few KB). */
    val pendingText: String? = null,
) {
    val track: LocalTrack get() = LocalTrack(path, title, artist, album, durationMs)

    /** Lyrify wrote this track's `.lrc` and knows its bytes, so the user can remove it from the app. */
    val removable: Boolean get() = status == LyricsStatus.SYNCED && sha256 != null

    fun isDue(now: Long): Boolean = nextAttemptAt != null && now >= nextAttemptAt

    /** After the user removed the lyrics at [now]: never looked up again, the LRCLIB id kept for v2. */
    fun removed(now: Long): LedgerEntry = copy(status = LyricsStatus.REMOVED, nextAttemptAt = null, updatedAt = now)
}

@Serializable
data class LedgerState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val entries: Map<String, LedgerEntry> = emptyMap(),
) {
    fun put(entry: LedgerEntry): LedgerState = copy(entries = entries + (entry.path to entry))

    /** Every status with its number of tracks, zeros included. */
    fun counts(): Map<LyricsStatus, Int> {
        val counted = entries.values.groupingBy { it.status }.eachCount()
        return LyricsStatus.entries.associateWith { counted[it] ?: 0 }
    }

    /** The last [limit] tracks handled, newest first. */
    fun recent(limit: Int): List<LedgerEntry> = entries.values.sortedByDescending { it.updatedAt }.take(limit)

    /** Tracks waiting for a retry after an error, newest first. */
    fun failed(): List<LedgerEntry> = entries.values.filter { it.status == LyricsStatus.ERROR }.sortedByDescending { it.updatedAt }

    /** Paths of the tracks whose last lookup failed and that are due again at [now]. */
    fun retryDue(now: Long): Set<String> =
        entries.values.filter { it.status == LyricsStatus.ERROR && it.isDue(now) }.mapTo(mutableSetOf()) { it.path }

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/** Where the ledger is kept: a DataStore file in the app, in memory in tests. */
interface LedgerStore {
    val ledger: Flow<LedgerState>

    suspend fun entry(path: String): LedgerEntry?

    /** Stores [entry]; false when it could not be written, so the caller skips what depends on it. Never throws for that. */
    suspend fun put(entry: LedgerEntry): Boolean
}

/** When to ask LRCLIB again. */
object Retry {
    /** Attempts per request within one lookup before the lookup is recorded as an error. */
    const val SESSION_ATTEMPTS: Int = 4
    const val ERROR_FIRST_DELAY_MS: Long = 30 * 60_000L
    const val ERROR_MAX_DELAY_MS: Long = 24 * 60 * 60_000L

    /** Not found, plain only, another version, instrumental, no artist: LRCLIB (and the tags) change, so look again after 30 days. */
    const val RECHECK_AFTER_MS: Long = 30 * 24 * 60 * 60_000L

    /** Tries of a failed ledger write after the first one, 100, 200 and 400 ms apart. */
    const val LEDGER_RETRIES: Int = 3
    const val LEDGER_FIRST_DELAY_MS: Long = 100
    private const val SESSION_FIRST_DELAY_MS: Long = 2_000
    private const val JITTER: Double = 0.2

    /** Wait before session retry [retry] (1-based): 2 s, 4 s, 8 s, each moved up to ±20% by [jitter] in [0, 1). */
    fun sessionDelayMs(retry: Int, jitter: Double): Long {
        val base = SESSION_FIRST_DELAY_MS shl (retry - 1).coerceIn(0, 16)
        return (base * (1 - JITTER + 2 * JITTER * jitter)).toLong()
    }

    /** Wait after the [errors]th failed lookup in a row: 30 min, doubling, at most a day. */
    fun errorDelayMs(errors: Int): Long {
        val doublings = (errors - 1).coerceIn(0, 16)
        return (ERROR_FIRST_DELAY_MS shl doublings).coerceAtMost(ERROR_MAX_DELAY_MS)
    }
}

/**
 * Runs [write] until it works, at most [Retry.LEDGER_RETRIES] more times after an
 * IOException, waiting a little longer each time. False when every try failed; [onFailure]
 * hears each failure.
 */
suspend fun writeWithRetries(onFailure: (IOException) -> Unit, write: suspend () -> Unit): Boolean {
    var retry = 0
    while (true) {
        try {
            write()
            return true
        } catch (e: IOException) {
            onFailure(e)
            if (retry == Retry.LEDGER_RETRIES) return false
            delay(Retry.LEDGER_FIRST_DELAY_MS shl retry)
            retry++
        }
    }
}

/**
 * The entry after a lookup (or a sidecar found on disk) for [track] ended in [status] at [now].
 * [sha256] and [size] describe the `.lrc` bytes written, for [LyricsStatus.SYNCED], or about
 * to be, for [LyricsStatus.WRITING]; [pendingText], their text, is kept for WRITING only.
 */
fun recorded(
    previous: LedgerEntry?,
    track: LocalTrack,
    status: LyricsStatus,
    lrclibId: Long?,
    now: Long,
    durationMatched: Boolean? = null,
    sha256: String? = null,
    size: Long? = null,
    pendingText: String? = null,
): LedgerEntry {
    val attempts = if (status == LyricsStatus.ERROR) (previous?.takeIf { it.status == LyricsStatus.ERROR }?.attempts ?: 0) + 1 else 0
    return LedgerEntry(
        path = track.path,
        title = track.title,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs,
        status = status,
        lrclibId = if (status == LyricsStatus.ERROR) lrclibId ?: previous?.lrclibId else lrclibId,
        attempts = attempts,
        lastAttemptAt = if (status == LyricsStatus.EXISTING) previous?.lastAttemptAt else now,
        nextAttemptAt = when (status) {
            LyricsStatus.SYNCED, LyricsStatus.EXISTING, LyricsStatus.REMOVED, LyricsStatus.WRITING -> null
            LyricsStatus.ERROR -> now + Retry.errorDelayMs(attempts)
            LyricsStatus.PLAIN_ONLY,
            LyricsStatus.NOT_FOUND,
            LyricsStatus.INSTRUMENTAL,
            LyricsStatus.DURATION_MISMATCH,
            LyricsStatus.UNIDENTIFIED,
            -> now + Retry.RECHECK_AFTER_MS
        },
        updatedAt = now,
        durationMatched = durationMatched,
        sha256 = sha256,
        writtenAt = sha256?.let { now },
        size = size,
        pendingText = pendingText.takeIf { status == LyricsStatus.WRITING },
    )
}

/** The stored form of [LedgerState]: JSON with an explicit schema version. */
object LedgerJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(state: LedgerState): String = json.encodeToString(LedgerState.serializer(), state)

    /**
     * Fields written by a newer version are ignored, and fields added since v1 (hashes) take
     * their defaults, so schema 1 needs no migration. Throws a SerializationException for
     * text that is not a ledger.
     */
    fun decode(text: String): LedgerState {
        val state = json.decodeFromString(LedgerState.serializer(), text)
        return if (state.schemaVersion == LedgerState.SCHEMA_VERSION) state else state.copy(schemaVersion = LedgerState.SCHEMA_VERSION)
    }
}
