package app.lyrify.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** One LRCLIB record, as returned by `/api/get` and `/api/search`. Durations are in seconds. */
@Serializable
data class LrclibRecord(
    val id: Long,
    val trackName: String = "",
    val artistName: String = "",
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) {
    val hasSyncedLyrics: Boolean get() = !instrumental && !syncedLyrics.isNullOrBlank()
    val hasPlainLyrics: Boolean get() = !plainLyrics.isNullOrBlank()
}

/** An LRCLIB answer. Failures that are expected (overload, network) are values, not exceptions. */
sealed interface LrclibResult {
    data class Found(val records: List<LrclibRecord>) : LrclibResult

    /** `/api/get` has no such track. */
    data object NotFound : LrclibResult

    /** Overloaded, offline or garbled: worth asking again later. [cause] is for the log. */
    data class Transient(val cause: String) : LrclibResult
}

/**
 * How urgent a lookup is. HIGH: the track playing and the next few; LOW: the rest of the
 * queue, swept in the background and spaced wider to go easy on LRCLIB.
 */
enum class Lane(val spacingMs: Long) {
    HIGH(spacingMs = 500),
    LOW(spacingMs = 1_500),
}

/**
 * LRCLIB (https://lrclib.net). Requests carry only artist, title, album and duration;
 * nothing that identifies the user. [Lane] sets how far apart requests are.
 */
interface LrclibClient {
    /** Exact lookup by signature; LRCLIB accepts durations within about 2 s. */
    suspend fun get(artist: String, title: String, album: String?, durationSeconds: Long, lane: Lane): LrclibResult

    /** Search by title and artist, or by title alone when [artist] is null (up to about 20 records). */
    suspend fun search(title: String, artist: String?, lane: Lane): LrclibResult
}

/**
 * The app's LRCLIB client: [HttpURLConnection] on [Dispatchers.IO], one request at a time
 * app-wide (one instance per process), each [Lane.spacingMs] after the previous one.
 * Redirects are not followed, so requests never leave [base]. A cancelled request closes its
 * connection, so it frees the gate at once instead of at its timeout.
 */
class HttpLrclibClient(
    private val userAgent: String,
    private val base: String = LrclibUrls.BASE,
    private val gate: RequestGate = RequestGate { System.nanoTime() / NANOS_PER_MILLI },
    /** Makes the connection for a URL without connecting yet; tests pass a fake. */
    private val open: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
) : LrclibClient {
    override suspend fun get(artist: String, title: String, album: String?, durationSeconds: Long, lane: Lane): LrclibResult =
        fetch(LrclibUrls.get(base, artist, title, album, durationSeconds), lane, LrclibResponses::get)

    override suspend fun search(title: String, artist: String?, lane: Lane): LrclibResult =
        fetch(LrclibUrls.search(base, title, artist), lane, LrclibResponses::search)

    private suspend fun fetch(url: String, lane: Lane, interpret: (Int, String?) -> LrclibResult): LrclibResult =
        gate.run(lane.spacingMs) { request(url, interpret) }

    private suspend fun request(url: String, interpret: (Int, String?) -> LrclibResult): LrclibResult {
        val connection = try {
            open(url)
        } catch (e: IOException) {
            return LrclibResult.Transient("${e.javaClass.simpleName}: ${e.message}")
        }
        return coroutineScope {
            val response = async(Dispatchers.IO) { exchange(connection, interpret) }
            try {
                response.await()
            } finally {
                // Also on cancellation: a blocked socket read does not notice it, but disconnecting ends
                // it at once from any thread (Android's HttpURLConnection cancels its OkHttp engine).
                connection.disconnect()
            }
        }
    }

    /** Blocking: sends the request and reads the answer. */
    private fun exchange(connection: HttpURLConnection, interpret: (Int, String?) -> LrclibResult): LrclibResult = try {
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "application/json")
        val code = connection.responseCode
        // Error bodies come on errorStream (null when empty); either one is closed here.
        val stream = if (code < HttpURLConnection.HTTP_BAD_REQUEST) connection.inputStream else connection.errorStream
        stream.use { input ->
            when {
                code != HttpURLConnection.HTTP_OK -> interpret(code, null)
                input == null -> LrclibResult.Transient("no body")
                else -> readCapped(input, MAX_BODY_BYTES)?.let { interpret(code, it.decodeToString()) }
                    ?: LrclibResult.Transient("response over $MAX_BODY_BYTES bytes")
            }
        }
    } catch (e: IOException) {
        LrclibResult.Transient("${e.javaClass.simpleName}: ${e.message}")
    }

    companion object {
        /** A record is a few KiB and a search at most ~20 of them; anything near this is not LRCLIB answering. */
        const val MAX_BODY_BYTES: Int = 2 * 1024 * 1024
        private const val TIMEOUT_MS: Int = 15_000
        private const val NANOS_PER_MILLI: Long = 1_000_000
    }
}

/** All of [input] if it holds at most [maxBytes], else null (reading stops just past the cap). */
internal fun readCapped(input: InputStream, maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return out.toByteArray()
        out.write(buffer, 0, read)
        if (out.size() > maxBytes) return null
    }
}

private const val BUFFER_BYTES: Int = 8 * 1024

/** Runs [run] blocks one at a time, each starting at least its spacing after the previous one ended. */
class RequestGate(private val now: () -> Long) {
    private val mutex = Mutex()

    /** End of the previous block; only read and written while holding [mutex]. */
    private var lastEnd: Long? = null

    /** Held for the spacing and [block] only: callers back off outside it. */
    suspend fun <T> run(spacingMs: Long, block: suspend () -> T): T = mutex.withLock {
        lastEnd?.let { delay(it + spacingMs - now()) }
        try {
            block()
        } finally {
            lastEnd = now()
        }
    }
}

internal object LrclibUrls {
    const val BASE: String = "https://lrclib.net"

    fun get(base: String, artist: String, title: String, album: String?, durationSeconds: Long): String =
        "$base/api/get?" + query("artist_name" to artist, "track_name" to title, "album_name" to album, "duration" to durationSeconds.toString())

    fun search(base: String, title: String, artist: String?): String =
        "$base/api/search?" + if (artist == null) query("q" to title) else query("track_name" to title, "artist_name" to artist)

    private fun query(vararg params: Pair<String, String?>): String =
        params.mapNotNull { (name, value) -> value?.let { "$name=${URLEncoder.encode(it, "UTF-8")}" } }.joinToString("&")
}

/** HTTP status and body to a result: anything but a readable 200 (or a 404 from `/api/get`) is transient. */
internal object LrclibResponses {
    fun get(code: Int, body: String?): LrclibResult = when (code) {
        HttpURLConnection.HTTP_OK -> parse(body) { listOf(LrclibJson.record(it)) }
        HttpURLConnection.HTTP_NOT_FOUND -> LrclibResult.NotFound
        else -> LrclibResult.Transient("HTTP $code")
    }

    fun search(code: Int, body: String?): LrclibResult =
        if (code == HttpURLConnection.HTTP_OK) parse(body, LrclibJson::records) else LrclibResult.Transient("HTTP $code")

    private inline fun parse(body: String?, read: (String) -> List<LrclibRecord>): LrclibResult {
        if (body == null) return LrclibResult.Transient("no body")
        return try {
            LrclibResult.Found(read(body))
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException: an HTML error page, a wrong shape.
            LrclibResult.Transient("unreadable response: ${e.javaClass.simpleName}")
        }
    }
}

internal object LrclibJson {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun record(body: String): LrclibRecord = json.decodeFromString(LrclibRecord.serializer(), body)

    fun records(body: String): List<LrclibRecord> = json.decodeFromString(ListSerializer(LrclibRecord.serializer()), body)
}
