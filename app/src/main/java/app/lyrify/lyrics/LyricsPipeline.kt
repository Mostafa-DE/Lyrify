package app.lyrify.lyrics

import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerStore
import app.lyrify.data.LyricsStatus
import app.lyrify.data.Retry
import app.lyrify.data.recorded
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/** A local audio file with its MediaStore tags. [path] is absolute; the file itself is never opened. */
data class LocalTrack(
    val path: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
)

/** What [LyricsPipeline.process] did with a file: its [status] now, and whether LRCLIB was too busy to answer this time. */
data class Processed(val status: LyricsStatus, val lrclibBusy: Boolean = false)

/** What [LyricsPipeline.remove] did. */
enum class Removal {
    /** The `.lrc` Lyrify wrote is deleted, and the track will not get lyrics again by itself. */
    REMOVED,

    /** The file is not what Lyrify wrote any more, or is gone: nothing was deleted. */
    CHANGED,

    /** Lyrify did not write this track's lyrics, or cannot tell its file is unchanged (v1 files changed since). */
    NOT_REMOVABLE,

    /** The file could not be read or deleted. */
    FAILED,
}

/**
 * Gets lyrics for one file: skips files that have a sidecar or are not due, asks LRCLIB
 * with [TrackNames] queries, picks with [Matcher], writes Samsung-safe synced lyrics with
 * [LrcFormat], and records every outcome in the ledger.
 *
 * Files: only ever creates `<audio name>.lrc` next to the audio, and only where nothing
 * exists ([SidecarFile]). Never opens the audio file. Deletes only what [SidecarFile] allows.
 * Different files are handled side by side (LRCLIB still gets one request at a time, see
 * [HttpLrclibClient]); the same file never twice at once.
 */
class LyricsPipeline(
    private val client: LrclibClient,
    private val ledger: LedgerStore,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) {
    /** Audio paths being handled right now. */
    private val inProgress = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Handles [track] and returns its status, or null when it cannot have a sidecar. With
     * [force], a track that is not due yet is looked up anyway (lyrics on disk still win,
     * and lyrics the user removed are never looked up again).
     */
    suspend fun process(track: LocalTrack, lane: Lane = Lane.HIGH, force: Boolean = false): Processed? = exclusive(track.path) {
        val sidecarPath = LrcFormat.sidecarPath(track.path)
        if (sidecarPath == null) {
            log("skip ${track.path}: no file extension")
            return@exclusive null
        }
        val sidecar = File(sidecarPath).toPath()
        val previous = ledger.entry(track.path)
        when {
            previous?.status == LyricsStatus.REMOVED -> Processed(LyricsStatus.REMOVED)
            Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) ->
                Processed(previous?.status?.takeIf { it in ON_DISK } ?: record(previous, track, LyricsStatus.EXISTING))
            previous != null && previous.status !in ON_DISK && !force && !previous.isDue(clock()) -> Processed(previous.status)
            TrackNames.isInstrumental(track.title) -> Processed(record(previous, track, LyricsStatus.INSTRUMENTAL))
            else -> lookUpAndWrite(track, sidecar, previous, lane)
        }
    }

    /** "Retry failed now": every track whose last lookup failed, due or not, in the LOW lane. Returns how many. */
    suspend fun retryFailed(): Int {
        val failed = ledger.ledger.first().failed()
        failed.forEach { process(it.track, Lane.LOW, force = true) }
        return failed.size
    }

    /** Paths whose last lookup failed and that are due for another one now. */
    suspend fun dueErrors(): Set<String> = ledger.ledger.first().retryDue(clock())

    /**
     * Deletes the `.lrc` Lyrify wrote next to the audio file at [audioPath], only if it is
     * still byte for byte what Lyrify wrote, and records [LyricsStatus.REMOVED].
     */
    suspend fun remove(audioPath: String): Removal = exclusive(audioPath) {
        val entry = ledger.entry(audioPath)?.takeIf { it.removable }
        val sha256 = entry?.sha256
        val sidecar = LrcFormat.sidecarPath(audioPath)?.let { File(it).toPath() }
        if (entry == null || sha256 == null || sidecar == null) return@exclusive Removal.NOT_REMOVABLE
        withContext(NonCancellable) {
            // The deletion and its ledger entry go together, even if the screen closes.
            SidecarFile.remove(sidecar, sha256, log).also { if (it == Removal.REMOVED) ledger.put(entry.removed(clock())) }
        }
    }

    /**
     * v1 kept no hashes. A SYNCED file last modified within [ADOPT_WINDOW_MS] of its ledger
     * entry is still as v1 wrote it, so its hash is stored now and the user can remove it.
     * Running it again changes nothing. Returns how many files were adopted.
     */
    suspend fun adoptLegacy(): Int =
        ledger.ledger.first().entries.values
            .filter { it.status == LyricsStatus.SYNCED && it.sha256 == null }
            .count { adopt(it.path) }

    private suspend fun adopt(audioPath: String): Boolean = exclusive(audioPath) {
        val entry = ledger.entry(audioPath)?.takeIf { it.status == LyricsStatus.SYNCED && it.sha256 == null }
        val sidecar = LrcFormat.sidecarPath(audioPath)?.let { File(it).toPath() }
        if (entry == null || sidecar == null) return@exclusive false
        val stamp = try {
            SidecarFile.stamp(sidecar)
        } catch (e: IOException) {
            log("${sidecar.fileName}: not readable: ${e.javaClass.simpleName}")
            null
        }
        if (stamp == null || abs(stamp.modifiedAt - entry.updatedAt) > ADOPT_WINDOW_MS) return@exclusive false
        ledger.put(entry.copy(sha256 = stamp.sha256, writtenAt = stamp.modifiedAt))
        log("${entry.title}: v1 lyrics adopted")
        true
    }

    /** Runs [block] once no other call is handling [path], keeping others off it meanwhile. */
    private suspend fun <T> exclusive(path: String, block: suspend () -> T): T {
        while (true) {
            val busy = inProgress.first { path !in it }
            if (inProgress.compareAndSet(busy, busy + path)) break
        }
        try {
            return block()
        } finally {
            inProgress.update { it - path }
        }
    }

    private suspend fun lookUpAndWrite(track: LocalTrack, sidecar: Path, previous: LedgerEntry?, lane: Lane): Processed {
        val queries = TrackNames.queries(track.artist, track.title, track.album)
        if (queries.isEmpty()) return Processed(record(previous, track, LyricsStatus.UNIDENTIFIED))
        return when (val result = lookUp(track, queries, lane)) {
            is Lookup.Write -> withContext(NonCancellable) {
                // The file and its ledger entry go together, even if the listener moves on.
                val bytes = LrcFormat.encode(result.text)
                val status = when (SidecarFile.create(sidecar, bytes, log)) {
                    Created.YES -> record(previous, track, LyricsStatus.SYNCED, result.record.id, sha256 = SidecarFile.sha256(bytes))
                    Created.ALREADY_THERE -> record(previous, track, LyricsStatus.EXISTING)
                    Created.FAILED -> record(previous, track, LyricsStatus.ERROR)
                }
                Processed(status)
            }
            is Lookup.Answer -> Processed(recordAnswer(previous, track, result.match))
            is Lookup.Failed -> {
                log("${track.title}: LRCLIB unavailable (${result.cause})")
                Processed(record(previous, track, LyricsStatus.ERROR), lrclibBusy = true)
            }
        }
    }

    private suspend fun recordAnswer(previous: LedgerEntry?, track: LocalTrack, match: Match): LyricsStatus = when (match) {
        is Match.Instrumental -> record(previous, track, LyricsStatus.INSTRUMENTAL, match.record.id)
        is Match.DurationMismatch -> record(previous, track, LyricsStatus.DURATION_MISMATCH, match.record.id)
        is Match.PlainOnly -> record(previous, track, LyricsStatus.PLAIN_ONLY, match.record.id, match.durationMatched)
        // Synced lyrics Samsung could not show (the reason is logged by firstWritable): their text is still worth keeping for v2.
        is Match.Synced ->
            if (match.record.hasPlainLyrics) {
                record(previous, track, LyricsStatus.PLAIN_ONLY, match.record.id, durationMatched = true)
            } else {
                record(previous, track, LyricsStatus.NOT_FOUND)
            }
        Match.NotFound -> record(previous, track, LyricsStatus.NOT_FOUND)
    }

    private sealed interface Lookup {
        data class Write(val record: LrclibRecord, val text: String) : Lookup

        /** LRCLIB answered every query; nothing writable. */
        data class Answer(val match: Match) : Lookup

        data class Failed(val cause: String) : Lookup
    }

    /**
     * Queries in order, `/api/get` then `/api/search` each, until synced lyrics Samsung can
     * show turn up. Records gathered so far are matched again after every answer.
     */
    private suspend fun lookUp(track: LocalTrack, queries: List<TrackQuery>, lane: Lane): Lookup {
        val durationSeconds = track.durationMs / MS_PER_SECOND
        var pool = Pool(emptyList(), emptySet())
        for (query in queries) {
            for (request in requests(query, track.durationMs, lane)) {
                when (val result = withRetries(request)) {
                    is LrclibResult.Transient -> return Lookup.Failed(result.cause)
                    LrclibResult.NotFound -> Unit
                    is LrclibResult.Found -> pool = pool.with(result.records)
                }
                val (write, checked) = firstWritable(pool, queries, track.durationMs)
                if (write != null) return write
                pool = checked
            }
        }
        return Lookup.Answer(Matcher.match(pool.usable(), queries, durationSeconds))
    }

    private fun requests(query: TrackQuery, durationMs: Long, lane: Lane): List<suspend () -> LrclibResult> = buildList {
        if (query.artist != null && durationMs > 0) {
            add { client.get(query.artist, query.title, query.album, (durationMs / MS_PER_SECOND).roundToLong(), lane) }
        }
        add { client.search(query.title, query.artist, lane) }
    }

    /** Records seen so far (first copy of each id) and ids whose synced lyrics Samsung could not show. */
    private data class Pool(val records: List<LrclibRecord>, val unusableSynced: Set<Long>) {
        fun with(found: List<LrclibRecord>): Pool =
            copy(records = records + found.filter { new -> records.none { it.id == new.id } }.distinctBy { it.id })

        /** Records as the matcher should see them: unusable synced lyrics count as absent. */
        fun usable(): List<LrclibRecord> = records.map { if (it.id in unusableSynced) it.copy(syncedLyrics = null) else it }
    }

    /** The best synced candidate that passes [LrcFormat] validation, and the pool with the failures marked. */
    private fun firstWritable(pool: Pool, queries: List<TrackQuery>, durationMs: Long): Pair<Lookup.Write?, Pool> {
        var current = pool
        while (true) {
            val match = Matcher.match(current.usable(), queries, durationMs / MS_PER_SECOND)
            if (match !is Match.Synced) return null to current
            when (val lrc = LrcFormat.convert(match.record.syncedLyrics.orEmpty(), durationMs)) {
                is Conversion.Ready -> return Lookup.Write(match.record, lrc.text) to current
                is Conversion.Rejected -> {
                    log("LRCLIB ${match.record.id}: synced lyrics unusable (${lrc.problem})")
                    current = current.copy(unusableSynced = current.unusableSynced + match.record.id)
                }
            }
        }
    }

    /** Up to [Retry.SESSION_ATTEMPTS] tries while LRCLIB is busy, waiting 2, 4, 8 s (with jitter) between them. */
    private suspend fun withRetries(request: suspend () -> LrclibResult): LrclibResult {
        var attempt = 1
        while (true) {
            val result = request()
            if (result !is LrclibResult.Transient || attempt == Retry.SESSION_ATTEMPTS) return result
            val wait = Retry.sessionDelayMs(attempt, random.nextDouble())
            log("LRCLIB busy (${result.cause}), retry $attempt in $wait ms")
            delay(wait)
            attempt++
        }
    }

    private suspend fun record(
        previous: LedgerEntry?,
        track: LocalTrack,
        status: LyricsStatus,
        lrclibId: Long? = null,
        durationMatched: Boolean? = null,
        sha256: String? = null,
    ): LyricsStatus {
        ledger.put(recorded(previous, track, status, lrclibId, clock(), durationMatched, sha256))
        log("${track.title}: $status${lrclibId?.let { " (LRCLIB $it)" }.orEmpty()}")
        return status
    }

    private companion object {
        const val MS_PER_SECOND: Double = 1_000.0

        /** How far a v1 file's modification time may be from its ledger entry to count as untouched since. */
        const val ADOPT_WINDOW_MS: Long = 120_000

        /** Statuses that mean a `.lrc` is on disk: decided by the file, never by the schedule. */
        val ON_DISK: Set<LyricsStatus> = setOf(LyricsStatus.SYNCED, LyricsStatus.EXISTING)
    }
}

internal enum class Created { YES, ALREADY_THERE, FAILED }

/**
 * Every file operation on `.lrc` files. Lyrify deletes exactly two kinds of file, both here:
 * a partial `.lrc` the same [create] call just made, and (by [remove], when the user asks) a
 * `.lrc` Lyrify wrote whose bytes still have the hash it recorded.
 */
internal object SidecarFile {
    /** Larger than any lyrics file; such a file is not one Lyrify wrote. */
    private const val MAX_BYTES: Long = 1024 * 1024

    /** A file's content hash and modification time. */
    data class Stamp(val sha256: String, val modifiedAt: Long)

    /**
     * Creates [path] holding [bytes], only if nothing at all is there: CREATE_NEW is
     * O_CREAT|O_EXCL, so an existing file, folder or symbolic link (dangling or not) makes it
     * fail, and the check and the create are one step. The bytes are synced to storage.
     */
    fun create(
        path: Path,
        bytes: ByteArray,
        log: (String) -> Unit,
        write: (FileChannel, ByteBuffer) -> Unit = ::writeFully,
    ): Created {
        val channel = try {
            FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (_: FileAlreadyExistsException) {
            log("${path.fileName}: appeared during the lookup, kept as it is")
            return Created.ALREADY_THERE
        } catch (e: IOException) {
            log("${path.fileName}: not created: ${e.javaClass.simpleName}: ${e.message}")
            return Created.FAILED
        }
        return try {
            channel.use {
                write(it, ByteBuffer.wrap(bytes))
                it.force(true)
            }
            Created.YES
        } catch (e: IOException) {
            log("${path.fileName}: not written: ${e.javaClass.simpleName}: ${e.message}")
            deletePartial(path, log)
            Created.FAILED
        }
    }

    /** Deletes [path] if its bytes hash to [sha256]: a regular file only, never through a link. */
    fun remove(path: Path, sha256: String, log: (String) -> Unit): Removal {
        val stamp = try {
            stamp(path)
        } catch (e: IOException) {
            log("${path.fileName}: not readable: ${e.javaClass.simpleName}: ${e.message}")
            return Removal.FAILED
        }
        if (stamp?.sha256 != sha256) {
            log("${path.fileName}: changed outside Lyrify or gone, left alone")
            return Removal.CHANGED
        }
        return try {
            Files.delete(path)
            log("${path.fileName}: removed")
            Removal.REMOVED
        } catch (e: IOException) {
            log("${path.fileName}: not removed: ${e.javaClass.simpleName}: ${e.message}")
            Removal.FAILED
        }
    }

    /** The hash and modification time of the regular file at [path]; null for anything else (missing, a link, too large). */
    fun stamp(path: Path): Stamp? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) return null
        val modifiedAt = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
        return Stamp(sha256(Files.readAllBytes(path)), modifiedAt)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    /** [path] was created by this very call and is incomplete: Lyrify's own partial file. */
    private fun deletePartial(path: Path, log: (String) -> Unit) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            log("${path.fileName}: partial file not removed: ${e.javaClass.simpleName}")
        }
    }
}
