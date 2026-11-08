package app.lyrify.data

import app.lyrify.lyrics.Candidate
import app.lyrify.lyrics.LOOKUP_REV
import app.lyrify.lyrics.LocalTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
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

    /**
     * Neither the tags nor the file name name an artist, so nothing is written by itself (a title alone could match any
     * song), and a search by title found nothing to offer.
     */
    UNIDENTIFIED,

    /**
     * Nothing could be written by itself, but LRCLIB may have the song: [LedgerEntry.candidates] wait for the user to
     * pick one (or none) in the app. The AI leaves the song alone meanwhile.
     */
    NEEDS_CHECK,

    /** The user removed lyrics Lyrify wrote. Never added again automatically. */
    REMOVED,

    /**
     * Lyrify is creating the `.lrc`; the entry holds its text, hash and size. Still there later
     * only if the app died meanwhile: the file then decides what happened (see LyricsPipeline).
     */
    WRITING,

    /** No lyrics were known: the on-device AI wrote down what it heard, and Lyrify wrote the `.lrc`. */
    AI_TRANSCRIBED,

    /** The on-device AI timed LRCLIB's plain lyrics, and Lyrify wrote the `.lrc`. */
    AI_ALIGNED,

    /** The AI heard (almost) no singing: nothing written. */
    AI_INSTRUMENTAL,

    /** The AI's lyrics were not good enough to write; [LedgerEntry.agreement] says how close the timing came. */
    AI_REJECTED,

    /** The AI could not run on this song; it tries again later ([LedgerEntry.aiRetryAt]). */
    AI_FAILED,
    ;

    /** Lyrify wrote the `.lrc` (LRCLIB's lyrics or the AI's): the user can remove it while it is unchanged. */
    val written: Boolean get() = this == SYNCED || this == AI_TRANSCRIBED || this == AI_ALIGNED

    /** One of the AI's statuses: what the on-device AI made of the song. */
    val byAi: Boolean get() = this == AI_TRANSCRIBED || this == AI_ALIGNED || this == AI_INSTRUMENTAL || this == AI_REJECTED || this == AI_FAILED

    /** LRCLIB had no lyrics to write and the AI has not settled the song (a failed run is tried again): the AI may try it. */
    val waitsForAi: Boolean get() = this == PLAIN_ONLY || this == NOT_FOUND || this == AI_FAILED
}

/** Who made the lyrics Lyrify wrote. */
@Serializable
enum class LyricsSource {
    LRCLIB,

    /** The on-device AI, from the audio (with LRCLIB's plain lyrics when it timed them). */
    AI,
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
    /** Lookups in a row that ended in [LyricsStatus.ERROR] (AI runs, for [LyricsStatus.AI_FAILED]); 0 otherwise. */
    val attempts: Int = 0,
    /** The last lookup, or the last AI run for the AI statuses. */
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
    /**
     * Who made the lyrics written ([LyricsStatus.written]), being written ([LyricsStatus.WRITING]) or removed by the user
     * ([LyricsStatus.REMOVED]); null otherwise. Since schema 2; schema 1 entries are LRCLIB's.
     */
    val source: LyricsSource? = null,
    /** The share of lines the AI's two timings agree on: [LyricsStatus.AI_ALIGNED], [LyricsStatus.AI_REJECTED] and their write. */
    val agreement: Double? = null,
    /**
     * For [LyricsStatus.AI_REJECTED]: why, by the engine's reason name (`LOW_AGREEMENT`, `NOT_MONOTONIC`, `NO_WORDS`,
     * `NO_ALIGNMENT`, `TOO_LONG`, `UNSUPPORTED_AUDIO`, `INVALID_LRC`), so a later version can try selected ones again; null otherwise and in
     * entries from before Lyrify 2.0's reasons. A name, not an enum: a reason this version does not know still reads.
     */
    val aiReason: String? = null,
    /** For [LyricsStatus.NEEDS_CHECK]: what LRCLIB may have for the song, best first; empty otherwise. Never lyrics. */
    val candidates: List<Candidate> = emptyList(),
    /** The user said none of the candidates is the song ("None of these"): no candidates are offered for it again. */
    val declined: Boolean = false,
    /** The version of the lookup ([LOOKUP_REV]) behind a lookup status; 0 in entries from before it was kept. */
    val lookupRev: Int = 0,
    /**
     * For [LyricsStatus.NEEDS_CHECK]: the [candidates]' lyrics were compared (the same lyrics shown once, nearly the same
     * with [Candidate.differingLines]). False in entries from before that (Lyrify 2.0.0), compared once their records can
     * be fetched ([app.lyrify.lyrics.LyricsPipeline.compareStored]).
     */
    val candidatesCompared: Boolean = false,
) {
    val track: LocalTrack get() = LocalTrack(path, title, artist, album, durationMs)

    /**
     * Not found, or no artist, by an older lookup than this version's: looked up once more (due at once, in the queue
     * sweep), and until then the AI leaves it alone, as LRCLIB's lyrics beat the AI's.
     */
    val lookupStale: Boolean get() = (status == LyricsStatus.NOT_FOUND || status == LyricsStatus.UNIDENTIFIED) && lookupRev < LOOKUP_REV

    /** Lyrify wrote this track's `.lrc` and knows its bytes, so the user can remove it from the app. */
    val removable: Boolean get() = status.written && sha256 != null

    fun isDue(now: Long): Boolean = nextAttemptAt != null && now >= nextAttemptAt

    /** For [LyricsStatus.AI_FAILED]: when the AI may try this song again; null for any other status. */
    val aiRetryAt: Long? get() = if (status == LyricsStatus.AI_FAILED) (lastAttemptAt ?: updatedAt) + Retry.aiDelayMs(attempts) else null

    /** For [LyricsStatus.WRITING]: the status once the file is complete. AI lyrics are timed ones exactly when they have an [agreement]. */
    fun writtenStatus(): LyricsStatus = when {
        source != LyricsSource.AI -> LyricsStatus.SYNCED
        agreement != null -> LyricsStatus.AI_ALIGNED
        else -> LyricsStatus.AI_TRANSCRIBED
    }

    /** After the user removed the lyrics at [now]: never looked up again, the LRCLIB id kept for v2. */
    fun removed(now: Long): LedgerEntry = copy(status = LyricsStatus.REMOVED, nextAttemptAt = null, updatedAt = now)
}

/** The ledger, stored by [LedgerJson]. */
data class LedgerState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val entries: Map<String, LedgerEntry> = emptyMap(),
    /**
     * Entries this version cannot read (a newer version's status or value), by path, kept exactly as stored and written
     * back so: going back to an older Lyrify loses nothing. Recording the song again replaces its entry.
     */
    val unknownEntries: Map<String, JsonElement> = emptyMap(),
    /**
     * False only for [UNREADABLE]: the ledger file could not be read at all, so nothing is known about any song. While
     * so, nothing is looked up, run by the AI or written. Never stored.
     */
    val readable: Boolean = true,
) {
    fun put(entry: LedgerEntry): LedgerState = copy(entries = entries + (entry.path to entry), unknownEntries = unknownEntries - entry.path)

    /** Without [path]'s entry; the other entries and [unknownEntries] as they are. */
    fun drop(path: String): LedgerState = if (path in entries) copy(entries = entries - path) else this

    /** Every status with its number of tracks, zeros included. */
    fun counts(): Map<LyricsStatus, Int> {
        val counted = entries.values.groupingBy { it.status }.eachCount()
        return LyricsStatus.entries.associateWith { counted[it] ?: 0 }
    }

    /** The last [limit] tracks handled, newest first. */
    fun recent(limit: Int): List<LedgerEntry> = entries.values.sortedByDescending { it.updatedAt }.take(limit)

    /** Tracks waiting for a retry after an error, newest first. */
    fun failed(): List<LedgerEntry> = entries.values.filter { it.status == LyricsStatus.ERROR }.sortedByDescending { it.updatedAt }

    /** Tracks whose last lookup failed and that are due again at [now]. */
    fun retryDue(now: Long): List<LedgerEntry> = entries.values.filter { it.status == LyricsStatus.ERROR && it.isDue(now) }

    companion object {
        /** What the app sees while its ledger file cannot be read ([readable] false): never an empty ledger, which would mean no song is known. */
        val UNREADABLE: LedgerState = LedgerState(readable = false)

        /** 2: AI statuses, [LedgerEntry.source] and [LedgerEntry.agreement] (Lyrify 2.0). */
        const val SCHEMA_VERSION: Int = 2
    }
}

/** Where the ledger is kept: a DataStore file in the app, in memory in tests. */
interface LedgerStore {
    val ledger: Flow<LedgerState>

    suspend fun entry(path: String): LedgerEntry?

    /** Stores [entry]; false when it could not be written, so the caller skips what depends on it. Never throws for that. */
    suspend fun put(entry: LedgerEntry): Boolean

    /** Forgets [path]'s entry (its song went to the phone's trash); false when that could not be written. A path without one changes nothing. */
    suspend fun drop(path: String): Boolean
}

/** When to ask LRCLIB again. */
object Retry {
    /** Attempts per request within one lookup before the lookup is recorded as an error. */
    const val SESSION_ATTEMPTS: Int = 4
    const val ERROR_FIRST_DELAY_MS: Long = 30 * 60_000L
    const val ERROR_MAX_DELAY_MS: Long = 24 * 60 * 60_000L

    /** Not found, plain only, another version, instrumental, no artist, to check: LRCLIB (and the tags) change, so look again after 30 days. */
    const val RECHECK_AFTER_MS: Long = 30 * 24 * 60 * 60_000L

    /** AI runs that failed are tried again after a day (the next night), doubling, at most 30 days. */
    const val AI_FIRST_DELAY_MS: Long = 24 * 60 * 60_000L
    const val AI_MAX_DELAY_MS: Long = 30 * AI_FIRST_DELAY_MS

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

    /** Wait after the [failures]th failed AI run in a row: a day, doubling, at most 30 days. */
    fun aiDelayMs(failures: Int): Long {
        val doublings = (failures - 1).coerceIn(0, 16)
        return (AI_FIRST_DELAY_MS shl doublings).coerceAtMost(AI_MAX_DELAY_MS)
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
 * The entry after a lookup (or a sidecar found on disk, or an AI run) for [track] ended in
 * [status] at [now]. [sha256] and [size] describe the `.lrc` bytes written, for a written
 * status, or about to be, for [LyricsStatus.WRITING]; [pendingText], their text, is kept for
 * WRITING only. [source] says who made those lyrics; [agreement] is the AI's, when it timed them; [aiReason] why the AI
 * rejected its lyrics, kept for [LyricsStatus.AI_REJECTED] only; [candidates] are kept for [LyricsStatus.NEEDS_CHECK]
 * only, their lyrics compared ([app.lyrify.lyrics.Candidates.offered]). [declined] stays once set. A status only a
 * lookup gives ([LOOKUP_STATUSES]) carries this version's [LOOKUP_REV]; any other keeps the one before.
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
    source: LyricsSource? = null,
    agreement: Double? = null,
    aiReason: String? = null,
    candidates: List<Candidate> = emptyList(),
    declined: Boolean = false,
): LedgerEntry {
    // Failures in a row: lookups for ERROR, AI runs for AI_FAILED.
    val counted = status == LyricsStatus.ERROR || status == LyricsStatus.AI_FAILED
    val attempts = if (counted) (previous?.takeIf { it.status == status }?.attempts ?: 0) + 1 else 0
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
            // The AI settled the song (a failed run has its own retry, aiRetryAt): LRCLIB is not asked again by itself.
            LyricsStatus.AI_TRANSCRIBED,
            LyricsStatus.AI_ALIGNED,
            LyricsStatus.AI_INSTRUMENTAL,
            LyricsStatus.AI_REJECTED,
            LyricsStatus.AI_FAILED,
            -> null
            LyricsStatus.ERROR -> now + Retry.errorDelayMs(attempts)
            LyricsStatus.PLAIN_ONLY,
            LyricsStatus.NOT_FOUND,
            LyricsStatus.INSTRUMENTAL,
            LyricsStatus.DURATION_MISMATCH,
            LyricsStatus.UNIDENTIFIED,
            LyricsStatus.NEEDS_CHECK,
            -> now + Retry.RECHECK_AFTER_MS
        },
        updatedAt = now,
        durationMatched = durationMatched,
        sha256 = sha256,
        writtenAt = sha256?.let { now },
        size = size,
        pendingText = pendingText.takeIf { status == LyricsStatus.WRITING },
        source = source,
        agreement = agreement,
        aiReason = aiReason.takeIf { status == LyricsStatus.AI_REJECTED },
        candidates = if (status == LyricsStatus.NEEDS_CHECK) candidates else emptyList(),
        declined = declined || previous?.declined == true,
        lookupRev = if (status in LOOKUP_STATUSES) LOOKUP_REV else previous?.lookupRev ?: 0,
        candidatesCompared = status == LyricsStatus.NEEDS_CHECK,
    )
}

/** What only a lookup (or the user's check of one) records: this version's lookup when recorded now. */
private val LOOKUP_STATUSES: Set<LyricsStatus> = setOf(
    LyricsStatus.PLAIN_ONLY,
    LyricsStatus.NOT_FOUND,
    LyricsStatus.INSTRUMENTAL,
    LyricsStatus.DURATION_MISMATCH,
    LyricsStatus.UNIDENTIFIED,
    LyricsStatus.NEEDS_CHECK,
)

/** The stored form of [LedgerState]: JSON with an explicit schema version. */
object LedgerJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** The entries this version reads, then [LedgerState.unknownEntries] ones as they were stored. */
    fun encode(state: LedgerState): String {
        val entries = state.unknownEntries + state.entries.mapValues { (_, entry) -> json.encodeToJsonElement(LedgerEntry.serializer(), entry) }
        val root = JsonObject(mapOf(SCHEMA_VERSION_KEY to JsonPrimitive(state.schemaVersion), ENTRIES_KEY to JsonObject(entries)))
        return json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Fields written by a newer version are ignored, and fields added since (hashes, the
     * source, the AI's agreement and reason, candidates, the lookup version) take their defaults. Each entry is read on
     * its own: one this version cannot read (a newer version's status or value) is kept as it is
     * ([LedgerState.unknownEntries]), never the reason the ledger is lost. A schema 1 ledger (Lyrify 1.x) is
     * migrated: every lyrics file it wrote, was writing or the user removed came from LRCLIB.
     * Throws a SerializationException only for text that is not a ledger at all (not a JSON object, or entries that are
     * not one).
     */
    fun decode(text: String): LedgerState {
        val root = json.parseToJsonElement(text) as? JsonObject ?: throw SerializationException("not a ledger: no JSON object")
        val stored = when (val entries = root[ENTRIES_KEY]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> entries
            else -> throw SerializationException("not a ledger: its entries are not a JSON object")
        }
        val read = stored.mapValues { (_, element) -> entry(element) }
        val state = LedgerState(
            schemaVersion = (root[SCHEMA_VERSION_KEY] as? JsonPrimitive)?.intOrNull ?: LedgerState.SCHEMA_VERSION,
            entries = read.mapNotNull { (path, entry) -> entry?.let { path to it } }.toMap(),
            unknownEntries = stored.filterKeys { read[it] == null },
        )
        val current = if (state.schemaVersion < 2) fromSchema1(state) else state
        return if (current.schemaVersion == LedgerState.SCHEMA_VERSION) current else current.copy(schemaVersion = LedgerState.SCHEMA_VERSION)
    }

    /** [element] as an entry; null when this version cannot read it (an unknown status or value, a missing field). */
    private fun entry(element: JsonElement): LedgerEntry? = try {
        json.decodeFromJsonElement(LedgerEntry.serializer(), element)
    } catch (_: IllegalArgumentException) {
        // A SerializationException is one.
        null
    }

    private const val SCHEMA_VERSION_KEY: String = "schemaVersion"
    private const val ENTRIES_KEY: String = "entries"

    private fun fromSchema1(state: LedgerState): LedgerState = state.copy(
        entries = state.entries.mapValues { (_, entry) ->
            if (entry.status in LRCLIB_IN_SCHEMA_1) entry.copy(source = LyricsSource.LRCLIB) else entry
        },
    )

    private val LRCLIB_IN_SCHEMA_1: Set<LyricsStatus> = setOf(LyricsStatus.SYNCED, LyricsStatus.WRITING, LyricsStatus.REMOVED)
}
