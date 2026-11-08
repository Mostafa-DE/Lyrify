package app.lyrify.lyrics

import app.lyrify.ai.AiResult
import app.lyrify.ai.RejectReason
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LedgerStore
import app.lyrify.data.LyricsSource
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
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * The version of the lookup: raised when it can find more than before, so songs an older one found nothing for
 * ([LedgerEntry.lookupStale]) are looked up once more. 1: file names, loose searches and the check list.
 */
const val LOOKUP_REV: Int = 1

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

/**
 * Whether the user can remove [entry]'s lyrics in the app: Lyrify wrote them and knows their
 * hash, or they are marked removed while the file is still there (its delete and the undo of
 * the entry both failed). Blocking: may look at the file.
 */
fun canRemove(entry: LedgerEntry): Boolean =
    entry.removable ||
        (
            entry.status == LyricsStatus.REMOVED &&
                entry.sha256 != null &&
                LrcFormat.sidecarPath(entry.path)?.let { Files.exists(File(it).toPath(), LinkOption.NOFOLLOW_LINKS) } == true
            )

/** LRCLIB's plain lyrics of a record, for the AI to time ([LyricsPipeline.plainLyrics]); never stored. */
sealed interface PlainLyrics {
    data class Found(val text: String) : PlainLyrics

    /** The record is gone, instrumental or holds no plain lyrics: the song is transcribed instead. */
    data object None : PlainLyrics

    /** LRCLIB could not be asked (overloaded, offline): the song waits for another run. [cause] is for the log. */
    data class Unavailable(val cause: String) : PlainLyrics
}

/**
 * Audio paths the on-device AI is working on. Its engine runs for up to a minute per song outside the pipeline's per-file
 * exclusivity (only the write at the end goes through it, and checks the song again), so meanwhile the queue sweep skips
 * the song instead of looking it up on LRCLIB for nothing. In memory only; never blocks.
 */
class AiPaths {
    private val paths = MutableStateFlow<Set<String>>(emptySet())

    operator fun contains(path: String): Boolean = path in paths.value

    fun hold(path: String) = paths.update { it + path }

    fun release(path: String) = paths.update { it - path }
}

/** What the user's answer on the check list did ([LyricsPipeline.choose], [LyricsPipeline.decline]). */
sealed interface Checked {
    /** Recorded: the song's [status] now (SYNCED, PLAIN_ONLY or NOT_FOUND; EXISTING when a `.lrc` appeared meanwhile). */
    data class Done(val status: LyricsStatus) : Checked

    /** The song no longer waits for a check (it was settled meanwhile): nothing changed. */
    data object Changed : Checked

    /** LRCLIB no longer has lyrics there that fit the song: nothing changed. */
    data object Gone : Checked

    /** LRCLIB could not be asked: nothing changed, the user can try again. [cause] is for the log. */
    data class Unavailable(val cause: String) : Checked

    /** The lyrics file or the ledger could not be written: the song is looked up again later, or stays to check. */
    data object NotSaved : Checked
}

/** The first lines of a record's lyrics, for the user to recognise the song ([LyricsPipeline.preview]); never stored. */
sealed interface LyricsPreview {
    data class Lines(val lines: List<String>) : LyricsPreview

    /** The record is gone or holds no lyrics. */
    data object None : LyricsPreview

    data class Unavailable(val cause: String) : LyricsPreview
}

/** What the user's own search found ([LyricsPipeline.search]). */
sealed interface Searched {
    data class Found(val candidates: List<Candidate>) : Searched

    data class Unavailable(val cause: String) : Searched
}

/** What [LyricsPipeline.remove] did. */
enum class Removal {
    /** The `.lrc` Lyrify wrote is deleted, and the track will not get lyrics again by itself. */
    REMOVED,

    /** The `.lrc` was gone already; the track will not get lyrics again by itself either. */
    ALREADY_GONE,

    /** The file is not what Lyrify wrote any more: nothing was deleted. */
    CHANGED,

    /** Lyrify did not write this track's lyrics, or cannot tell its file is unchanged (v1 files changed since). */
    NOT_REMOVABLE,

    /** The file could not be read or deleted, or the ledger not written: nothing changed. */
    FAILED,
}

/**
 * Gets lyrics for one file: skips files that have a sidecar or are not due, asks LRCLIB
 * with [TrackNames] queries, picks with [Matcher], writes Samsung-safe synced lyrics with
 * [LrcFormat], and records every outcome in the ledger. When nothing of this recording can
 * be written, a loose search by title may find [Candidates] for the user to check
 * ([LyricsStatus.NEEDS_CHECK]); the user's pick is written here too ([choose]). The
 * on-device AI's lyrics are written and recorded here too ([recordAi]), through the same path.
 *
 * Files: only ever creates `<audio name>.lrc` next to the audio, and only where nothing
 * exists ([SidecarFile]). Never opens the audio file. Deletes only what [SidecarFile] allows.
 * A write is marked in the ledger before the file is created ([LyricsStatus.WRITING]), so one
 * the app's death interrupted is settled the next time the track is seen ([reconcile]).
 * Different files are handled side by side (LRCLIB still gets one request at a time, see
 * [HttpLrclibClient]); the same file never twice at once.
 */
class LyricsPipeline(
    private val client: LrclibClient,
    private val ledger: LedgerStore,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
    /** For what needs a person to look: the ledger failing where a file's safety depends on it. */
    private val warn: (String) -> Unit = log,
    /** How the bytes go into a new `.lrc`; tests make it fail. */
    private val writeBytes: (FileChannel, ByteBuffer) -> Unit = SidecarFile::writeFully,
) {
    /** Audio paths being handled right now. */
    private val inProgress = MutableStateFlow<Set<String>>(emptySet())

    /** Songs the AI is working on, for the queue sweep to skip (one set with the pipeline, so one per process). */
    val aiPaths: AiPaths = AiPaths()

    /**
     * Handles [track] and returns its status, or null when it cannot have a sidecar. With
     * [force], a track that is not due yet is looked up anyway (lyrics on disk still win,
     * and lyrics the user removed are never looked up again). A track an older lookup found
     * nothing for ([LedgerEntry.lookupStale]) is due at once. While the ledger cannot be read
     * ([LedgerState.readable]), nothing is done (null): every song would look new.
     */
    suspend fun process(track: LocalTrack, lane: Lane = Lane.HIGH, force: Boolean = false): Processed? = exclusive(track.path) {
        val sidecarPath = LrcFormat.sidecarPath(track.path)
        if (sidecarPath == null) {
            log("skip ${track.path}: no file extension")
            return@exclusive null
        }
        val sidecar = File(sidecarPath).toPath()
        val state = ledger.ledger.first()
        if (!state.readable) {
            log("skip ${track.path}: the ledger cannot be read")
            return@exclusive null
        }
        val previous = state.entries[track.path]
        when {
            previous?.status == LyricsStatus.REMOVED -> Processed(LyricsStatus.REMOVED)
            previous != null && previous.status == LyricsStatus.WRITING -> reconcile(track, sidecar, previous, lane)
            Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) ->
                Processed(previous?.status?.takeIf { it in ON_DISK } ?: outcome(previous, track, LyricsStatus.EXISTING))
            previous != null && previous.status !in ON_DISK && !force && !previous.isDue(clock()) && !previous.lookupStale ->
                Processed(previous.status)
            TrackNames.isInstrumental(track.title) -> Processed(outcome(previous, track, LyricsStatus.INSTRUMENTAL))
            else -> lookUpAndWrite(track, sidecar, previous, lane)
        }
    }

    /**
     * Records what the AI made of [track] and writes its lyrics, through the same marker, exclusive create and hash as
     * LRCLIB's: only while the song still waits for the AI ([LyricsStatus.waitsForAi]), its lookup is this version's
     * ([LedgerEntry.lookupStale]) and it has no `.lrc`. A song that changed during the run (lyrics written or removed
     * meanwhile, a write interrupted, a check to make) is left as it is. Returns the song's status, or null when the
     * ledger does not know it or it cannot have a sidecar.
     */
    suspend fun recordAi(track: LocalTrack, result: AiResult): LyricsStatus? = exclusive(track.path) {
        val sidecarPath = LrcFormat.sidecarPath(track.path) ?: return@exclusive null
        val sidecar = File(sidecarPath).toPath()
        val previous = ledger.entry(track.path) ?: return@exclusive null
        when {
            !previous.status.waitsForAi || previous.lookupStale -> previous.status
            Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) -> outcome(previous, track, LyricsStatus.EXISTING)
            // The file and its ledger entries go together, even if the run is stopped.
            else -> withContext(NonCancellable) { recordAiResult(track, sidecar, previous, result) }
        }
    }

    /**
     * LRCLIB's plain lyrics of record [id], for the AI to time (`/api/get/{id}` in the LOW lane, asked again while busy
     * as lookups are). Fetched when needed and never stored.
     */
    suspend fun plainLyrics(id: Long): PlainLyrics = when (val result = withRetries { client.byId(id, Lane.LOW) }) {
        is LrclibResult.Found ->
            result.records.firstOrNull { it.id == id && !it.instrumental && it.hasPlainLyrics }?.plainLyrics?.let(PlainLyrics::Found)
                ?: PlainLyrics.None
        LrclibResult.NotFound -> PlainLyrics.None
        is LrclibResult.Transient -> PlainLyrics.Unavailable(result.cause)
    }

    /**
     * Once at startup: songs waiting for a check whose candidates an older Lyrify stored before their lyrics were compared
     * ([LedgerEntry.candidatesCompared]) get them compared now, as [Candidates.offered] does, so the same lyrics show
     * once. Each record is fetched by id in the LOW lane (asked again while busy, as [plainLyrics] is); its lyrics are
     * never stored. Once LRCLIB cannot answer, the pass stops: that song and the rest wait for the next start. A song
     * answered or looked up meanwhile is left as it is. The song keeps its place on the list. Returns how many songs were
     * compared.
     */
    suspend fun compareStored(): Int {
        var compared = 0
        for (stored in ledger.ledger.first().entries.values.filter { it.status == LyricsStatus.NEEDS_CHECK && !it.candidatesCompared }) {
            val lyrics = storedLyrics(stored) ?: break
            if (saveCompared(stored, lyrics)) compared++
        }
        return compared
    }

    /**
     * The lyrics of [stored]'s candidates by id, as [Candidates.normalizedLyricLines]; null when LRCLIB could not be
     * asked. One card has nothing to be compared with. A record gone from LRCLIB is never merged ("Use" then says so).
     */
    private suspend fun storedLyrics(stored: LedgerEntry): Map<Long, List<String>>? {
        if (stored.candidates.size < 2) return emptyMap()
        return buildMap {
            for (candidate in stored.candidates) {
                when (val result = withRetries { client.byId(candidate.id, Lane.LOW) }) {
                    is LrclibResult.Transient -> return null
                    LrclibResult.NotFound -> Unit
                    is LrclibResult.Found -> result.records.firstOrNull { it.id == candidate.id }
                        ?.let { put(candidate.id, Candidates.normalizedLyricLines(Candidates.usedLyrics(it, candidate))) }
                }
            }
        }
    }

    private suspend fun saveCompared(stored: LedgerEntry, lyrics: Map<Long, List<String>>): Boolean =
        exclusive(stored.path) {
            // Answered or looked up again meanwhile.
            if (ledger.entry(stored.path) != stored) return@exclusive false
            val candidates = Candidates.compared(stored.candidates, lyrics, stored.track, stored.candidates.size)
            ledger.put(stored.copy(candidates = candidates, candidatesCompared = true))
        }

    /** "Retry failed now": every track whose last lookup failed, due or not; the queue sweep looks them up. */
    suspend fun failed(): List<LocalTrack> = ledger.ledger.first().failed().map { it.track }

    /**
     * Once at startup: every write the app's death interrupted is settled as [process] would
     * (in the LOW lane, should a file need looking up again). Returns how many there were.
     */
    suspend fun finishInterrupted(): Int =
        ledger.ledger.first().entries.values
            .filter { it.status == LyricsStatus.WRITING }
            .onEach { process(it.track, Lane.LOW) }
            .size

    /**
     * Tracks whose last lookup failed and that are due for another one now, with the tags the
     * ledger keeps. One whose audio file is gone is left out, its entry as it is. Blocking.
     */
    suspend fun dueErrors(): List<LocalTrack> = ledger.ledger.first().retryDue(clock()).map { it.track }.filter { File(it.path).isFile }

    /**
     * Tracks an older lookup found nothing for ([LedgerEntry.lookupStale]), most recently handled first, with the tags
     * the ledger keeps, while their audio file is there: the queue sweep looks each up once more ([process] finds them
     * due). Blocking.
     */
    suspend fun staleLookups(): List<LocalTrack> =
        ledger.ledger.first().entries.values.filter { it.lookupStale }.sortedByDescending { it.updatedAt }.map { it.track }.filter { File(it.path).isFile }

    /**
     * "This one" on the check list: record [id] for the song at [audioPath], which waits for a check. LRCLIB is asked
     * once for the record (the user is waiting, and can tap again), which must still fit the song as a candidate
     * ([Candidates.searched]). Synced lyrics are written through the same marker and exclusive create as any; plain
     * lyrics are kept as PLAIN_ONLY with that record, for the AI to time.
     */
    suspend fun choose(audioPath: String, id: Long): Checked = exclusive(audioPath) {
        val previous = ledger.entry(audioPath)?.takeIf { it.status == LyricsStatus.NEEDS_CHECK }
        val sidecar = LrcFormat.sidecarPath(audioPath)?.let { File(it).toPath() }
        if (previous == null || sidecar == null) return@exclusive Checked.Changed
        val track = previous.track
        if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return@exclusive Checked.Done(outcome(previous, track, LyricsStatus.EXISTING))
        val found = when (val result = client.byId(id, Lane.HIGH)) {
            is LrclibResult.Found -> result.records.firstOrNull { it.id == id }
            LrclibResult.NotFound -> null
            is LrclibResult.Transient -> return@exclusive Checked.Unavailable(result.cause)
        }
        val candidate = found?.let { Candidates.searched(listOf(it), track).firstOrNull() }
        if (found == null || candidate == null) return@exclusive Checked.Gone
        val text = found.syncedLyrics?.takeIf { candidate.synced }?.let { synced ->
            when (val lrc = LrcFormat.convert(synced, track.durationMs)) {
                is Conversion.Ready -> lrc.text
                is Conversion.Rejected -> null
            }
        }
        // The file and its ledger entries go together, even if the screen closes.
        withContext(NonCancellable) {
            when {
                text != null -> {
                    val status = write(track, sidecar, previous, text, Written(LyricsStatus.SYNCED, LyricsSource.LRCLIB, id))
                    if (status == LyricsStatus.SYNCED || status == LyricsStatus.EXISTING) Checked.Done(status) else Checked.NotSaved
                }
                // A `.lrc` made during the fetch wins, as it does for synced lyrics (the exclusive create).
                Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) -> Checked.Done(outcome(previous, track, LyricsStatus.EXISTING))
                record(previous, track, LyricsStatus.PLAIN_ONLY, id, durationMatched = true) -> Checked.Done(LyricsStatus.PLAIN_ONLY)
                else -> Checked.NotSaved
            }
        }
    }

    /** "None of these": the song at [audioPath] is not found, so the AI may take it, and no candidates are offered for it again. */
    suspend fun decline(audioPath: String): Checked = exclusive(audioPath) {
        val previous = ledger.entry(audioPath)?.takeIf { it.status == LyricsStatus.NEEDS_CHECK } ?: return@exclusive Checked.Changed
        if (record(previous, previous.track, LyricsStatus.NOT_FOUND, declined = true)) Checked.Done(LyricsStatus.NOT_FOUND) else Checked.NotSaved
    }

    /** "Show lyrics": the first [PREVIEW_LINES] lines of record [id], asked for once. Shown, never stored or logged. */
    suspend fun preview(id: Long): LyricsPreview = when (val result = client.byId(id, Lane.HIGH)) {
        is LrclibResult.Found ->
            result.records.firstOrNull { it.id == id }?.let(::firstLines)?.takeIf { it.isNotEmpty() }?.let(LyricsPreview::Lines)
                ?: LyricsPreview.None
        LrclibResult.NotFound -> LyricsPreview.None
        is LrclibResult.Transient -> LyricsPreview.Unavailable(result.cause)
    }

    /** "Search myself": what LRCLIB has for [text] (`q=`, asked once) that fits [track] ([Candidates.searched]). */
    suspend fun search(track: LocalTrack, text: String): Searched {
        val words = text.trim().take(MAX_SEARCH_CHARS)
        if (words.isEmpty()) return Searched.Found(emptyList())
        return when (val result = client.search(words, null, Lane.HIGH)) {
            is LrclibResult.Found -> Searched.Found(Candidates.searched(result.records, track))
            LrclibResult.NotFound -> Searched.Found(emptyList())
            is LrclibResult.Transient -> Searched.Unavailable(result.cause)
        }
    }

    private fun firstLines(record: LrclibRecord): List<String> {
        val lines = if (record.hasSyncedLyrics) LrcFormat.parseLrclib(record.syncedLyrics.orEmpty()).map { it.text } else record.plainLyrics.orEmpty().lines()
        return lines.map(String::trim).filter { it.isNotEmpty() }.take(PREVIEW_LINES)
    }

    /**
     * The audio file at [audioPath] is in the phone's trash: the user moved it there from the Duplicates tab, and
     * MediaStore confirmed IS_TRASHED. The `.lrc` Lyrify wrote for it is deleted as [remove] deletes it (only while byte for
     * byte what Lyrify wrote), then its ledger entry goes, so a song restored from the trash later is new to Lyrify. A
     * `.lrc` Lyrify did not write, or one changed since, stays. The entry stays when the `.lrc` could not be deleted
     * (FAILED), so it can still be removed from the app.
     */
    suspend fun trashed(audioPath: String): Removal = exclusive(audioPath) {
        val entry = ledger.entry(audioPath)
        if (entry == null) return@exclusive Removal.NOT_REMOVABLE
        val sha256 = entry.sha256
        val sidecar = LrcFormat.sidecarPath(audioPath)?.let { File(it).toPath() }
        // The deletion and the ledger go together, even if the screen closes.
        withContext(NonCancellable) {
            val removal = if (!canRemove(entry) || sha256 == null || sidecar == null) {
                Removal.NOT_REMOVABLE
            } else {
                when (SidecarFile.compare(sidecar, sha256) {}) {
                    Compared.SAME -> {
                        if (!ledger.put(entry.removed(clock()))) {
                            Removal.FAILED
                        } else {
                            val deleted = when (SidecarFile.compare(sidecar, sha256) {}) {
                                Compared.SAME -> SidecarFile.delete(sidecar) {}
                                Compared.MISSING -> Removal.ALREADY_GONE
                                Compared.CHANGED -> Removal.CHANGED
                                Compared.UNREADABLE -> Removal.FAILED
                            }
                            if ((deleted == Removal.CHANGED || deleted == Removal.FAILED) && !ledger.put(entry)) {
                                warn("trash cleanup: 1 entry not restored")
                            }
                            deleted
                        }
                    }
                    Compared.MISSING -> Removal.ALREADY_GONE
                    Compared.CHANGED -> Removal.CHANGED
                    Compared.UNREADABLE -> Removal.FAILED
                }
            }
            if (removal != Removal.FAILED && !ledger.drop(audioPath)) log("trash cleanup: 1 entry not dropped")
            log("trash cleanup: ${if (removal == Removal.REMOVED) 1 else 0} sidecars removed")
            removal
        }
    }

    /**
     * Deletes the `.lrc` Lyrify wrote next to the audio file at [audioPath], only if it is
     * still byte for byte what Lyrify wrote, and records [LyricsStatus.REMOVED]. A file
     * already gone is only recorded as removed. See [canRemove].
     */
    suspend fun remove(audioPath: String): Removal = exclusive(audioPath) {
        val entry = ledger.entry(audioPath)?.takeIf(::canRemove)
        val sha256 = entry?.sha256
        val sidecar = LrcFormat.sidecarPath(audioPath)?.let { File(it).toPath() }
        if (entry == null || sha256 == null || sidecar == null) return@exclusive Removal.NOT_REMOVABLE
        withContext(NonCancellable) {
            // The deletion and its ledger entry go together, even if the screen closes.
            when (SidecarFile.compare(sidecar, sha256, log)) {
                Compared.SAME -> deleteRemoved(entry, sidecar, sha256)
                Compared.MISSING -> if (ledger.put(entry.removed(clock()))) Removal.ALREADY_GONE else Removal.FAILED
                Compared.CHANGED -> Removal.CHANGED
                Compared.UNREADABLE -> Removal.FAILED
            }
        }
    }

    /**
     * REMOVED is stored before the file goes: deleted without it, the file would be looked up
     * and written again. The file is then checked once more and deleted only if it is still
     * exactly what Lyrify wrote; a file that stays gets its entry back.
     */
    private suspend fun deleteRemoved(entry: LedgerEntry, sidecar: Path, sha256: String): Removal {
        if (!ledger.put(entry.removed(clock()))) return Removal.FAILED
        val removal = when (SidecarFile.compare(sidecar, sha256, log)) {
            Compared.SAME -> SidecarFile.delete(sidecar, log)
            Compared.MISSING -> Removal.ALREADY_GONE
            Compared.CHANGED -> Removal.CHANGED
            Compared.UNREADABLE -> Removal.FAILED
        }
        if ((removal == Removal.CHANGED || removal == Removal.FAILED) && !ledger.put(entry)) {
            warn("${entry.title}: marked removed, but its lyrics file is still there; it can be removed again")
        }
        return removal
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
        ledger.put(entry.copy(sha256 = stamp.sha256, writtenAt = stamp.modifiedAt)).also { if (it) log("${entry.title}: v1 lyrics adopted") }
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

    /**
     * At most [TrackNames.MAX_REQUESTS] requests: the [TrackNames.queries] (from the tags, and the file name when they
     * say too little), then, when they found nothing of this recording ([Verdict.unsure]), the loose searches of
     * [settle].
     */
    private suspend fun lookUpAndWrite(track: LocalTrack, sidecar: Path, previous: LedgerEntry?, lane: Lane): Processed {
        val queries = TrackNames.queries(track.artist, track.title, track.album, File(track.path).name)
        val result = if (queries.isEmpty()) Lookup.Answer(Match.NotFound, emptyList(), emptySet()) else lookUp(track, queries, lane)
        return when (result) {
            // The file and its ledger entries go together, even if the listener moves on.
            is Lookup.Write -> withContext(NonCancellable) {
                Processed(write(track, sidecar, previous, result.text, Written(LyricsStatus.SYNCED, LyricsSource.LRCLIB, result.record.id)))
            }
            is Lookup.Answer -> settle(track, previous, if (queries.isEmpty()) Verdict(LyricsStatus.UNIDENTIFIED) else verdict(result.match), result, lane)
            is Lookup.Failed -> busy(previous, track, result.cause)
        }
    }

    private suspend fun busy(previous: LedgerEntry?, track: LocalTrack, cause: String): Processed {
        log("${track.title}: LRCLIB unavailable ($cause)")
        return Processed(outcome(previous, track, LyricsStatus.ERROR), lrclibBusy = true)
    }

    /**
     * Records LRCLIB's [verdict]. When it holds nothing of this recording, the loose searches ([TrackNames.looseQueries],
     * those the [answer] did not ask already) may find [Candidates]: the song then waits for the user's check. A song
     * the user said none of the candidates is ([LedgerEntry.declined]) is not searched again, and stays not found.
     */
    private suspend fun settle(track: LocalTrack, previous: LedgerEntry?, verdict: Verdict, answer: Lookup.Answer, lane: Lane): Processed {
        if (!verdict.unsure) return Processed(outcome(previous, track, verdict.status, verdict.lrclibId, verdict.durationMatched))
        if (previous?.declined == true) return Processed(outcome(previous, track, LyricsStatus.NOT_FOUND))
        var records = answer.records
        for (text in TrackNames.looseQueries(track.title, File(track.path).name)) {
            if (TrackNames.normalize(text) in answer.searched) continue
            when (val result = withRetries { client.search(text, null, lane) }) {
                is LrclibResult.Transient -> return busy(previous, track, result.cause)
                LrclibResult.NotFound -> Unit
                is LrclibResult.Found -> records = records + result.records
            }
        }
        val candidates = Candidates.offered(records.distinctBy { it.id }, track)
        if (candidates.isEmpty()) return Processed(outcome(previous, track, verdict.status, verdict.lrclibId, verdict.durationMatched))
        return Processed(outcome(previous, track, LyricsStatus.NEEDS_CHECK, candidates = candidates))
    }

    /** The AI's [result] for [track], which waits for it and has no `.lrc`: its lyrics written (validated first), or its verdict recorded. */
    private suspend fun recordAiResult(track: LocalTrack, sidecar: Path, previous: LedgerEntry, result: AiResult): LyricsStatus {
        // The LRCLIB record and what it said about the duration stay, so a failed run can be tried again the same way.
        fun ai(status: LyricsStatus, agreement: Double? = null, reason: String? = null) =
            Written(status, LyricsSource.AI, previous.lrclibId, previous.durationMatched, agreement, reason)
        return when (result) {
            is AiResult.Transcribed -> writeAi(track, sidecar, previous, result.lrc, ai(LyricsStatus.AI_TRANSCRIBED))
            is AiResult.Aligned -> writeAi(track, sidecar, previous, result.lrc, ai(LyricsStatus.AI_ALIGNED, result.agreement))
            is AiResult.Instrumental -> verdict(previous, track, ai(LyricsStatus.AI_INSTRUMENTAL))
            is AiResult.Rejected -> verdict(previous, track, ai(LyricsStatus.AI_REJECTED, result.agreement, result.reason.name))
            is AiResult.Failed -> verdict(previous, track, ai(LyricsStatus.AI_FAILED))
        }
    }

    /** The AI's lyrics, checked once more with Samsung's rules before anything is written (the engine checked them too). */
    private suspend fun writeAi(track: LocalTrack, sidecar: Path, previous: LedgerEntry, text: String, written: Written): LyricsStatus {
        val problem = LrcFormat.validate(text, track.durationMs) ?: return write(track, sidecar, previous, text, written)
        log("${track.title}: AI lyrics unusable ($problem)")
        return verdict(previous, track, written.copy(status = LyricsStatus.AI_REJECTED, aiReason = RejectReason.INVALID_LRC.name))
    }

    /** An AI verdict without a file. */
    private suspend fun verdict(previous: LedgerEntry, track: LocalTrack, written: Written): LyricsStatus =
        outcome(previous, track, written.status, written.lrclibId, written.durationMatched, written.agreement, written.aiReason)

    /** What a write records once its file is complete: [status] (a written one), who made the lyrics, and what stays from the entry before. */
    private data class Written(
        val status: LyricsStatus,
        val source: LyricsSource,
        val lrclibId: Long?,
        val durationMatched: Boolean? = null,
        val agreement: Double? = null,
        /** Why the AI rejected its lyrics ([LedgerEntry.aiReason]). */
        val aiReason: String? = null,
    )

    /** A write that made no file: LRCLIB lyrics are looked up again after a while, the AI's song waits for its next try. */
    private suspend fun notWritten(previous: LedgerEntry?, track: LocalTrack, written: Written): LyricsStatus =
        if (written.source == LyricsSource.AI) {
            outcome(previous, track, LyricsStatus.AI_FAILED, written.lrclibId, written.durationMatched)
        } else {
            outcome(previous, track, LyricsStatus.ERROR)
        }

    /**
     * Writes [text] at [sidecar]. The ledger first gets a WRITING entry with the text, hash
     * and size of the exact bytes, so a write the app's death interrupts can be told from a
     * file someone else made ([reconcile]); when that entry cannot be stored, no file is made.
     */
    private suspend fun write(track: LocalTrack, sidecar: Path, previous: LedgerEntry?, text: String, written: Written): LyricsStatus {
        val bytes = LrcFormat.encode(text)
        val sha256 = SidecarFile.sha256(bytes)
        val size = bytes.size.toLong()
        val marker = recorded(
            previous, track, LyricsStatus.WRITING, written.lrclibId, clock(), written.durationMatched,
            sha256 = sha256, size = size, pendingText = text, source = written.source, agreement = written.agreement,
        )
        if (!ledger.put(marker)) {
            log("${track.title}: ledger not writable, no lyrics written")
            return notWritten(previous, track, written)
        }
        return when (SidecarFile.create(sidecar, bytes, log, writeBytes)) {
            Created.YES -> {
                // Should this entry be lost, the marker stays and reconcile finds the file complete.
                record(
                    previous, track, written.status, written.lrclibId, written.durationMatched,
                    sha256 = sha256, size = size, source = written.source, agreement = written.agreement,
                )
                written.status
            }
            Created.ALREADY_THERE -> keepForeign(previous, track, marker)
            Created.FAILED ->
                if (Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                    // The partial file could not be deleted: the marker stays, so reconcile deletes it and tries again.
                    log("${track.title}: partial lyrics file left, settled next time")
                    LyricsStatus.WRITING
                } else {
                    notWritten(previous, track, written)
                }
        }
    }

    /**
     * Records EXISTING over the WRITING [marker] for a `.lrc` Lyrify did not write. If that
     * cannot be stored, the marker loses its text instead, so reconcile can never take the
     * file for Lyrify's own partial and delete it.
     */
    private suspend fun keepForeign(previous: LedgerEntry?, track: LocalTrack, marker: LedgerEntry): LyricsStatus {
        if (!record(previous, track, LyricsStatus.EXISTING) && !ledger.put(marker.copy(pendingText = null))) {
            warn("${track.title}: ledger not writable twice; its WRITING entry keeps the text, so a later look could delete a file that is not Lyrify's")
        }
        return LyricsStatus.EXISTING
    }

    /**
     * [marker] says a write began and its end was never recorded: the app died in between, or
     * the ledger failed. The file decides. Missing: looked up again. Complete: recorded as
     * written. Lyrify's own partial: deleted, then looked up again. Anything else: kept.
     */
    private suspend fun reconcile(track: LocalTrack, sidecar: Path, marker: LedgerEntry, lane: Lane): Processed {
        val left = try {
            SidecarFile.interrupted(sidecar, marker)
        } catch (e: IOException) {
            // Left as it is: settled the next time the track is seen.
            log("${sidecar.fileName}: not readable: ${e.javaClass.simpleName}")
            return Processed(LyricsStatus.WRITING)
        }
        return when (left) {
            Interrupted.MISSING -> lookUpAndWrite(track, sidecar, marker, lane)
            Interrupted.COMPLETE -> {
                // Should this entry be lost too, the marker stays and the next look finds the file complete again.
                val status = marker.writtenStatus()
                record(
                    marker, track, status, marker.lrclibId, marker.durationMatched.takeIf { marker.source == LyricsSource.AI },
                    sha256 = marker.sha256, size = marker.size, source = marker.source ?: LyricsSource.LRCLIB, agreement = marker.agreement,
                )
                Processed(status)
            }
            Interrupted.PARTIAL ->
                if (SidecarFile.deleteInterrupted(sidecar, log)) lookUpAndWrite(track, sidecar, marker, lane) else Processed(LyricsStatus.WRITING)
            Interrupted.FOREIGN -> Processed(keepForeign(marker, track, marker))
        }
    }

    /** What LRCLIB's answer means for the ledger. */
    private data class Verdict(val status: LyricsStatus, val lrclibId: Long? = null, val durationMatched: Boolean? = null) {
        /** Nothing of this recording's lyrics: a loose search may still find some for the user to check. */
        val unsure: Boolean get() = when (status) {
            LyricsStatus.NOT_FOUND, LyricsStatus.DURATION_MISMATCH, LyricsStatus.UNIDENTIFIED -> true
            LyricsStatus.PLAIN_ONLY -> durationMatched == false
            else -> false
        }
    }

    private fun verdict(match: Match): Verdict = when (match) {
        is Match.Instrumental -> Verdict(LyricsStatus.INSTRUMENTAL, match.record.id)
        is Match.DurationMismatch -> Verdict(LyricsStatus.DURATION_MISMATCH, match.record.id)
        is Match.PlainOnly -> Verdict(LyricsStatus.PLAIN_ONLY, match.record.id, match.durationMatched)
        // Synced lyrics Samsung could not show (the reason is logged by firstWritable): their text is still worth keeping for v2.
        is Match.Synced ->
            if (match.record.hasPlainLyrics) Verdict(LyricsStatus.PLAIN_ONLY, match.record.id, durationMatched = true) else Verdict(LyricsStatus.NOT_FOUND)
        Match.NotFound -> Verdict(LyricsStatus.NOT_FOUND)
    }

    private sealed interface Lookup {
        data class Write(val record: LrclibRecord, val text: String) : Lookup

        /** LRCLIB answered every query; nothing writable. [records]: all it gave; [searched]: the `q=` texts asked, normalised. */
        data class Answer(val match: Match, val records: List<LrclibRecord>, val searched: Set<String>) : Lookup

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
        val searched = queries.filter { it.artist == null }.map { TrackNames.normalize(it.title) }.toSet()
        return Lookup.Answer(Matcher.match(pool.usable(), queries, durationSeconds), pool.usable(), searched)
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
        size: Long? = null,
        source: LyricsSource? = null,
        agreement: Double? = null,
        aiReason: String? = null,
        candidates: List<Candidate> = emptyList(),
        declined: Boolean = false,
    ): Boolean {
        val entry = recorded(
            previous, track, status, lrclibId, clock(), durationMatched, sha256, size,
            source = source, agreement = agreement, aiReason = aiReason, candidates = candidates, declined = declined,
        )
        val stored = ledger.put(entry)
        log("${track.title}: $status${lrclibId?.let { " (LRCLIB $it)" }.orEmpty()}${if (stored) "" else ", not stored"}")
        return stored
    }

    /**
     * [record] where a ledger failure needs nothing more: the entry stays as it was, so the
     * track is simply handled again the next time it is seen. Returns [status].
     */
    private suspend fun outcome(
        previous: LedgerEntry?,
        track: LocalTrack,
        status: LyricsStatus,
        lrclibId: Long? = null,
        durationMatched: Boolean? = null,
        agreement: Double? = null,
        aiReason: String? = null,
        candidates: List<Candidate> = emptyList(),
    ): LyricsStatus {
        record(previous, track, status, lrclibId, durationMatched, agreement = agreement, aiReason = aiReason, candidates = candidates)
        return status
    }

    private companion object {
        const val MS_PER_SECOND: Double = 1_000.0

        /** "Show lyrics" shows this many lines. */
        const val PREVIEW_LINES: Int = 4

        /** A search the user types is cut to this many characters. */
        const val MAX_SEARCH_CHARS: Int = 200

        /** How far a v1 file's modification time may be from its ledger entry to count as untouched since. */
        const val ADOPT_WINDOW_MS: Long = 120_000

        /** Statuses that mean a `.lrc` is on disk: decided by the file, never by the schedule. */
        val ON_DISK: Set<LyricsStatus> = setOf(LyricsStatus.SYNCED, LyricsStatus.EXISTING, LyricsStatus.AI_TRANSCRIBED, LyricsStatus.AI_ALIGNED)
    }
}

internal enum class Created { YES, ALREADY_THERE, FAILED }

/** A `.lrc` compared with the bytes Lyrify wrote there. */
internal enum class Compared { SAME, CHANGED, MISSING, UNREADABLE }

/** What a write the app's death interrupted left at the `.lrc` path. */
internal enum class Interrupted {
    MISSING,

    /** All the bytes: only the ledger entry saying so was lost. */
    COMPLETE,

    /** A strict prefix of the bytes being written (empty included): Lyrify's own, cut short. */
    PARTIAL,

    /** Not what the write would have left (other bytes, a link or a folder), or the marker cannot tell. */
    FOREIGN,
}

/**
 * Every file operation on `.lrc` files. Lyrify deletes exactly three kinds of file, all here:
 * a partial `.lrc` the same [create] call just made; (by [delete], when the user asks) a `.lrc`
 * Lyrify wrote whose bytes still have the hash it recorded; and (by [deleteInterrupted]) the
 * partial `.lrc` of Lyrify's own write that the app's death cut short.
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
        } catch (e: Throwable) {
            // Anything else (a cancellation, an Error) still leaves no partial file, and goes on up.
            log("${path.fileName}: not written: ${e.javaClass.simpleName}")
            deletePartial(path, log)
            throw e
        }
    }

    /** Whether [path] is a regular file (never through a link) whose bytes hash to [sha256]. */
    fun compare(path: Path, sha256: String, log: (String) -> Unit): Compared {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            log("${path.fileName}: gone already")
            return Compared.MISSING
        }
        val stamp = try {
            stamp(path)
        } catch (e: IOException) {
            log("${path.fileName}: not readable: ${e.javaClass.simpleName}: ${e.message}")
            return Compared.UNREADABLE
        }
        if (stamp?.sha256 == sha256) return Compared.SAME
        log("${path.fileName}: changed outside Lyrify, left alone")
        return Compared.CHANGED
    }

    /**
     * Deletes [path], which [compare] just found unchanged: REMOVED, ALREADY_GONE, or FAILED
     * while the file is still there. The file is not held open from the hash to the delete:
     * only another app rewriting this very `.lrc` in those microseconds could lose its change,
     * which is not worth the complexity.
     */
    fun delete(path: Path, log: (String) -> Unit): Removal = try {
        Files.delete(path)
        log("${path.fileName}: removed")
        Removal.REMOVED
    } catch (_: NoSuchFileException) {
        log("${path.fileName}: gone already")
        Removal.ALREADY_GONE
    } catch (e: IOException) {
        log("${path.fileName}: not removed: ${e.javaClass.simpleName}: ${e.message}")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Removal.FAILED else Removal.ALREADY_GONE
    }

    /**
     * What the write [marker] ([LyricsStatus.WRITING]) describes left at [path], judged by the
     * bytes alone: all of them, or a strict prefix of them, or anything else. A marker without
     * its text cannot tell a prefix, so nothing is Lyrify's then.
     */
    fun interrupted(path: Path, marker: LedgerEntry): Interrupted {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return Interrupted.MISSING
        }
        val pending = marker.pendingText?.let(LrcFormat::encode)
        val size = attributes.size()
        if (!attributes.isRegularFile || pending == null || size > pending.size || size > MAX_BYTES) return Interrupted.FOREIGN
        // Read no further than the size just seen (more means the file grew: not a write that died), never through a link.
        val bytes = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { readCapped(it, size.toInt()) } ?: return Interrupted.FOREIGN
        return when {
            sha256(bytes) == marker.sha256 -> Interrupted.COMPLETE
            bytes.size < pending.size && bytes.contentEquals(pending.copyOf(bytes.size)) -> Interrupted.PARTIAL
            else -> Interrupted.FOREIGN
        }
    }

    /** Deletes the partial file [interrupted] found at [path]; true when it is gone. */
    fun deleteInterrupted(path: Path, log: (String) -> Unit): Boolean = try {
        Files.deleteIfExists(path)
        log("${path.fileName}: partial file of an interrupted write removed")
        true
    } catch (e: IOException) {
        log("${path.fileName}: partial file of an interrupted write not removed: ${e.javaClass.simpleName}")
        false
    }

    /** The hash and modification time of the regular file at [path]; null for anything else (missing, a link, too large). */
    fun stamp(path: Path): Stamp? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES) return null
        val modifiedAt = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
        return Stamp(sha256(Files.readAllBytes(path)), modifiedAt)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
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
