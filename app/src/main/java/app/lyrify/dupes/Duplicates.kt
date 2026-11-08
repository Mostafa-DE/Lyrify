package app.lyrify.dupes

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import app.lyrify.LOG_TAG
import app.lyrify.Lyrify
import app.lyrify.logFailures
import app.lyrify.lyrics.LrcFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.TimeUnit

/** Where a scan is. */
sealed interface ScanStatus {
    data object Idle : ScanStatus

    /** [done] of [total] files looked at; [total] is 0 while MediaStore is read. */
    data class Running(val done: Int, val total: Int) : ScanStatus

    /** MediaStore could not be read (All files access off?). The results before stay. */
    data object Failed : ScanStatus
}

/** Moving copies to the phone's trash: two confirmations, Lyrify's then the phone's. */
sealed interface TrashStep {
    data object None : TrashStep

    /** Lyrify's own dialog asks: "Move 2 songs to trash?" */
    data class Confirm(val files: List<AudioFile>) : TrashStep

    /** The phone's dialog is open for [files] ([MediaStoreTrash]); its answer goes to [Duplicates.systemAnswered]. */
    data class System(val files: List<AudioFile>) : TrashStep

    /** The phone's dialog is open to take [song] back out of its trash; its answer goes to [Duplicates.restoreAnswered]. */
    data class Restoring(val song: TrashedSong) : TrashStep
}

/** What the phone's answer did. */
sealed interface Trashed {
    /** [count] songs are in the phone's trash; Lyrify's own lyrics for them are gone too. */
    data class Moved(val count: Int) : Trashed

    /** The phone's dialog was cancelled, or nothing waited for an answer: nothing changed. */
    data object Unchanged : Trashed

    /** The phone said OK, but MediaStore could not say what is in its trash: nothing changed yet; the next scan settles it. */
    data object NotConfirmed : Trashed
}

/** What "Move to trash" in Lyrify's dialog did ([Duplicates.confirmTrash]). */
sealed interface Confirmation {
    /** The phone's own dialog is to open for these MediaStore [ids]. */
    data class Ask(val ids: List<Long>) : Confirmation

    /** A file changed since the scan: nothing is asked, and its group waits for the next scan ([DuplicatesState.stale]). */
    data object Changed : Confirmation

    /** MediaStore could not be read: nothing is asked; the user can try again. */
    data object Unavailable : Confirmation

    /** Lyrify's dialog was not open: nothing is asked. */
    data object NotAsked : Confirmation
}

/** What the phone's answer to "Restore" did. */
sealed interface Restored {
    /** The song is back in its place; Lyrify sees it as a new song, so its lyrics come back with the next lookup. */
    data object Back : Restored

    /** The dialog was cancelled, or the song is still in the trash: nothing changed. */
    data object Unchanged : Restored
}

/**
 * What happens to a song once the phone moved its audio file to its trash: the `.lrc` Lyrify wrote for it goes (only
 * while byte for byte what Lyrify wrote), and its ledger entry is updated. A `.lrc` Lyrify did not write stays. The
 * lyrics pipeline in the app.
 */
fun interface TrashCleanup {
    suspend fun trashed(audioPath: String)
}

/** The phone's trash as MediaStore shows it: [MediaStoreTrash] in the app, made up in tests. */
fun interface PhoneTrash {
    /**
     * Which of the MediaStore [ids] are in the phone's trash now, each with when the phone deletes it for good (wall-clock
     * millis; null when MediaStore does not say). Null when MediaStore could not be read. Blocking.
     */
    fun inTrash(ids: Collection<Long>): Map<Long, Long?>?
}

/** The Duplicates tab's state. */
data class DuplicatesState(
    /** The stored memory is read; until then the tab shows nothing. */
    val loaded: Boolean = false,
    val scan: ScanStatus = ScanStatus.Idle,
    val trash: TrashStep = TrashStep.None,
    internal val memory: DuplicatesMemory = DuplicatesMemory(),
    /** Groups with a file that changed since the scan ([Confirmation.Changed]): nothing goes to the trash until the next scan. */
    val stale: Set<String> = emptySet(),
) {
    /** The last scan's groups; null before the first scan. */
    val results: ScanResults? get() = memory.results

    val reviewed: Set<String> get() = memory.reviewed

    /** Songs Lyrify moved to the phone's trash and can still restore, the latest first. */
    val trashed: List<TrashedSong> get() = memory.trashed.sortedByDescending { it.trashedAt }

    /** The tab's badge: groups not opened yet. */
    val badge: Int get() = results?.unreviewed(memory.reviewed) ?: 0
}

/**
 * The Duplicates tab's engine. It finds files that may be one song ([scan]: [identicalKeys], then [DuplicateGrouping])
 * and only ever suggests. Nothing is deleted by it: the only way a file goes is the phone's own trash request
 * ([MediaStoreTrash]), opened after Lyrify's own dialog ([askTrash], [confirmTrash]) for files the user checked, and
 * settled after the phone's answer ([systemAnswered]). Songs moved there are listed until the phone deletes them, and
 * can be taken back out through the phone's own dialog too ([restore]). Audio files are only read, and only to hash them.
 *
 * A scan runs in [scope] on [io], one file at a time; it is not resumed: one cancelled or cut short by the app's end
 * leaves the results before, and the next one starts over. What the user decided ("Not duplicates", the groups opened)
 * and the last results are kept in [store]. The log gets counts only, never titles or paths.
 */
class Duplicates(
    private val library: AudioLibrary,
    private val store: DuplicatesStore,
    private val cleanup: TrashCleanup,
    private val phoneTrash: PhoneTrash,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val hasher: ContentHasher = ContentHasher(),
    /** Whether a `.lrc` is next to a file; asked for files in groups only. */
    private val hasLyrics: (AudioFile) -> Boolean = ::sidecarExists,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(DuplicatesState())
    val state: StateFlow<DuplicatesState> = _state.asStateFlow()

    /** One write of [store] at a time, each of the memory as it is then. */
    private val saving = Mutex()

    private val loading: Job = scope.launch(io) {
        val memory = store.load()
        _state.update { it.copy(loaded = true, memory = memory) }
    }

    /** The scan running; null when none is. Only it may change the scan status or store results. */
    @Volatile
    private var scanning: Job? = null

    /** "Scan my songs" / "Scan again": starts a scan unless one is running. */
    fun scan() {
        if (scanning?.isActive == true) return
        _state.update { it.copy(scan = ScanStatus.Running(0, 0)) }
        val job = scope.launch(io, start = CoroutineStart.LAZY) {
            val self = coroutineContext.job
            try {
                loading.join()
                runScan(self)
            } finally {
                status(self, ScanStatus.Idle) { it is ScanStatus.Running }
            }
        }
        scanning = job
        job.start()
    }

    /** "Cancel": the scan stops after the file it is on; the results before stay. */
    fun cancelScan() {
        scanning?.cancel()
        scanning = null
        _state.update { if (it.scan is ScanStatus.Running) it.copy(scan = ScanStatus.Idle) else it }
    }

    /** The user opened [group]'s sheet: it no longer counts in the badge. */
    fun opened(group: DuplicateGroup) {
        if (!state.value.loaded || group.key in state.value.reviewed) return
        commitLater { it.copy(reviewed = it.reviewed + group.key) }
    }

    /** "Not duplicates": [group] is hidden for good, until one of its files changes ([DuplicateGroup.key]). */
    fun notDuplicates(group: DuplicateGroup) {
        if (!state.value.loaded) return
        commitLater { memory ->
            memory.copy(
                results = memory.results?.let { results -> results.copy(groups = results.groups.filter { it.key != group.key }) },
                notDuplicates = memory.notDuplicates + group.key,
                reviewed = memory.reviewed - group.key,
            )
        }
    }

    /**
     * "Move to trash" in [group]'s sheet with the files [ids] checked: Lyrify's own dialog asks first ([TrashStep.Confirm]).
     * Any files of a group shown now, the suggested keep too (it is only a hint), but never all of them, so one copy
     * always stays. False when [ids] are not such files: nothing is asked.
     */
    fun askTrash(group: DuplicateGroup, ids: Set<Long>): Boolean {
        if (state.value.memory.pendingTrash.isNotEmpty()) return false
        val shown = state.value.results?.groups?.firstOrNull { it.key == group.key } ?: return false
        val files = shown.files.filter { it.id in ids }
        if (files.isEmpty() || files.size != ids.size || files.size >= shown.files.size || shown.key in state.value.stale || state.value.trash is TrashStep.System) {
            return false
        }
        _state.update { it.copy(trash = TrashStep.Confirm(files)) }
        return true
    }

    /** "Cancel" in Lyrify's dialog: nothing changes. */
    fun cancelTrash() {
        _state.update { if (it.trash is TrashStep.Confirm) it.copy(trash = TrashStep.None) else it }
    }

    /**
     * "Move to trash" in Lyrify's dialog: the files are remembered (so an answer the app does not live to hear is still
     * settled), then their MediaStore ids are returned for the phone's own dialog ([MediaStoreTrash.request]). A changed
     * row blocks the request until the next scan.
     */
    suspend fun confirmTrash(): Confirmation {
        val files = (state.value.trash as? TrashStep.Confirm)?.files ?: return Confirmation.NotAsked
        val rows = withContext(io) { library.rows(files.map { it.id }) }
        if (rows == null) {
            _state.update { it.copy(trash = TrashStep.None) }
            return Confirmation.Unavailable
        }
        val changed = files.any { file ->
            val row = rows[file.id]
            row == null || row.path != file.path || row.size != file.size || row.displayName != file.displayName
        }
        if (changed) {
            val ids = files.map { it.id }.toSet()
            _state.update { state ->
                val stale = state.results?.groups.orEmpty().filter { group -> group.files.any { it.id in ids } }.map { it.key }
                state.copy(trash = TrashStep.None, stale = state.stale + stale)
            }
            return Confirmation.Changed
        }
        if (state.value.trash != TrashStep.Confirm(files)) return Confirmation.NotAsked
        withContext(NonCancellable) { commit { it.copy(pendingTrash = files) } }
        _state.update { it.copy(trash = TrashStep.System(files)) }
        return Confirmation.Ask(files.map { it.id })
    }

    /**
     * The phone's dialog answered. [approved] (RESULT_OK): for each file MediaStore confirms is trashed, the lyrics
     * Lyrify wrote go ([cleanup]) and the file leaves the results. Not approved: nothing changes. Finishes even if the
     * screen goes.
     */
    suspend fun systemAnswered(approved: Boolean): Trashed = withContext(NonCancellable + io) {
        loading.join()
        val pending = state.value.memory.pendingTrash
        _state.update { it.copy(trash = TrashStep.None) }
        when {
            pending.isEmpty() -> Trashed.Unchanged
            !approved -> {
                commit { it.copy(pendingTrash = emptyList()) }
                Trashed.Unchanged
            }
            else -> settle(pending)?.let(Trashed::Moved) ?: Trashed.NotConfirmed
        }
    }

    /**
     * "Restore" on a song Lyrify trashed: its MediaStore id for the phone's own dialog ([MediaStoreTrash.request] with
     * trash false). Empty while another dialog is open or the song is not listed.
     */
    fun restore(song: TrashedSong): List<Long> {
        val listed = state.value.memory.trashed.any { it.file.id == song.file.id }
        if (!listed || state.value.trash != TrashStep.None) return emptyList()
        _state.update { it.copy(trash = TrashStep.Restoring(song)) }
        return listOf(song.file.id)
    }

    /**
     * The phone's restore dialog answered: when [approved] (RESULT_OK) and MediaStore no longer has the song in its trash,
     * the song leaves the list. Its ledger entry went when it was trashed, so Lyrify sees it as a new song.
     */
    suspend fun restoreAnswered(approved: Boolean): Restored = withContext(NonCancellable + io) {
        val song = (state.value.trash as? TrashStep.Restoring)?.song
        _state.update { if (it.trash is TrashStep.Restoring) it.copy(trash = TrashStep.None) else it }
        val back = song != null && approved && phoneTrash.inTrash(listOf(song.file.id))?.let { song.file.id !in it } == true
        if (song == null || !back) return@withContext Restored.Unchanged
        commit { memory -> memory.copy(trashed = memory.trashed.filter { it.file.id != song.file.id }) }
        log("duplicates: 1 song restored from the trash")
        Restored.Back
    }

    /** Drops the trashed songs the phone no longer has in its trash (restored elsewhere, deleted for good) or that expired. */
    suspend fun refreshTrashed(): Unit = withContext(io) {
        loading.join()
        val songs = state.value.memory.trashed
        if (songs.isEmpty()) return@withContext
        val ids = songs.map { it.file.id }.toSet()
        val kept = stillTrashed(songs, phoneTrash.inTrash(ids), clock()).associateBy { it.file.id }
        if (kept.values.toList() == songs) return@withContext
        withContext(NonCancellable) {
            // Songs listed meanwhile stay as they are.
            commit { memory -> memory.copy(trashed = memory.trashed.mapNotNull { if (it.file.id in ids) kept[it.file.id] else it }) }
        }
        log("duplicates: ${songs.size - kept.size} trashed songs off the list")
    }

    /** Sets the scan status to [status] while [job] is the scan running and the status is one [replaces]. */
    private fun status(job: Job, status: ScanStatus, replaces: (ScanStatus) -> Boolean = { true }) {
        _state.update { if (scanning === job && replaces(it.scan)) it.copy(scan = status) else it }
    }

    private suspend fun runScan(self: Job) {
        // A trash answer the app did not live to hear: MediaStore confirms which files are trashed.
        val pending = state.value.memory.pendingTrash
        if (pending.isNotEmpty() && state.value.trash !is TrashStep.System) withContext(NonCancellable) { settle(pending) }
        refreshTrashed()
        val files = when (val read = library.read()) {
            is LibraryRead.Unavailable -> {
                log("duplicates: songs not readable (${read.cause})")
                status(self, ScanStatus.Failed)
                return
            }
            is LibraryRead.Files -> read.files.sortedBy { it.path }
        }
        status(self, ScanStatus.Running(0, files.size))
        val keys = identicalKeys(files, hasher) { done -> status(self, ScanStatus.Running(done, files.size)) }
        val found = DuplicateGrouping.groups(files, keys, hasLyrics)
        // The results and their file go together; a scan cancelled meanwhile stores nothing.
        withContext(NonCancellable) {
            if (scanning !== self) return@withContext
            val scannedAt = clock()
            commit { memory ->
                if (scanning !== self) return@commit memory
                val shown = found.filter { it.key !in memory.notDuplicates }
                memory.copy(results = ScanResults(scannedAt, files.size, shown), reviewed = memory.reviewed intersect shown.map { it.key }.toSet())
            }
            _state.update { if (scanning === self) it.copy(stale = emptySet()) else it }
            status(self, ScanStatus.Idle)
            log("duplicates: ${files.size} files, ${keys.size} hashed, ${found.size} groups")
        }
    }

    /**
     * The [pending] files the phone moved to its trash: their lyrics cleaned up, gone from the results, and listed as
     * trashed until the phone deletes them. Returns how many.
     */
    private suspend fun settle(pending: List<AudioFile>): Int? {
        val expiries = phoneTrash.inTrash(pending.map { it.id }) ?: return null
        val trashed = pending.filter { it.id in expiries }
        for (file in trashed) cleanup.trashed(file.path)
        val ids = trashed.map { it.id }.toSet()
        val now = clock()
        val listed = trashed.map { TrashedSong(it, now, expiries[it.id] ?: (now + TRASH_KEPT_MS)) }
        commit { memory ->
            val before = memory.results?.groups.orEmpty()
            val after = before.map { group -> group to group.copy(files = group.files.filter { it.id !in ids }) }.filter { it.second.files.size > 1 }
            // A group opened before is still reviewed with fewer files.
            val reviewed = after.filter { (old, _) -> old.key in memory.reviewed }.map { it.second.key }
            memory.copy(
                results = memory.results?.copy(groups = after.map { it.second }),
                reviewed = (memory.reviewed + reviewed) intersect after.map { it.second.key }.toSet(),
                pendingTrash = emptyList(),
                trashed = memory.trashed.filter { it.file.id !in ids } + listed,
            )
        }
        log("duplicates: ${trashed.size} of ${pending.size} files moved to the trash")
        return trashed.size
    }

    /** Changes the memory now and stores it before returning. */
    private suspend fun commit(change: (DuplicatesMemory) -> DuplicatesMemory) {
        _state.update { it.copy(memory = change(it.memory)) }
        persist()
    }

    /** Changes the memory now and stores it in the background. */
    private fun commitLater(change: (DuplicatesMemory) -> DuplicatesMemory) {
        _state.update { it.copy(memory = change(it.memory)) }
        scope.launch(io) { persist() }
    }

    private suspend fun persist() {
        withContext(io) { saving.withLock { store.save(state.value.memory) } }
    }

    private companion object {
        /** How long the phone keeps trashed files when MediaStore does not say (AOSP MediaProvider: 30 days). */
        const val TRASH_KEPT_MS: Long = 30 * 24 * 60 * 60_000L
    }
}

/** Whether a `.lrc` is next to [file] (never through a link). Blocking. */
fun sidecarExists(file: AudioFile): Boolean =
    LrcFormat.sidecarPath(file.path)?.let { Files.exists(File(it).toPath(), LinkOption.NOFOLLOW_LINKS) } == true

/**
 * The phone's trash through MediaStore (Android 11 and later). [request] makes the phone's own dialog that moves songs to
 * its trash or back (`MediaStore.createTrashRequest`), launched by the screen with
 * `ActivityResultContracts.StartIntentSenderForResult`; RESULT_OK means it is done. The phone shows that dialog to every
 * app without the Media management permission (MANAGE_MEDIA), which Lyrify never asks for: the phone always asks the user
 * itself. Trashed files keep their MediaStore id and can be restored for about 30 days; then the phone deletes them.
 */
class MediaStoreTrash(private val resolver: ContentResolver) : PhoneTrash {
    sealed interface Request {
        data class Ready(val intent: PendingIntent) : Request

        /** No dialog could be made (an id MediaStore does not know, too many at once); [cause] is for the log. */
        data class Unavailable(val cause: String) : Request
    }

    /** The phone's dialog moving the songs with MediaStore [ids] to its trash, or with [trash] false back out of it. */
    fun request(ids: List<Long>, trash: Boolean = true): Request = try {
        Request.Ready(MediaStore.createTrashRequest(resolver, ids.map(::uri), trash))
    } catch (e: IllegalArgumentException) {
        Request.Unavailable(e.javaClass.simpleName)
    } catch (e: SecurityException) {
        Request.Unavailable(e.javaClass.simpleName)
    }

    override fun inTrash(ids: Collection<Long>): Map<Long, Long?>? = try {
        val found = HashMap<Long, Long?>()
        val read = ids.distinct().chunked(MAX_IDS_PER_QUERY).all { chunk -> query(chunk, found) }
        if (read) found else null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Adds the trashed ones of [ids] to [found]; false when MediaStore gave no answer. Trashed items are listed too (MATCH_INCLUDE). */
    private fun query(ids: List<Long>, found: MutableMap<Long, Long?>): Boolean {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Audio.Media._ID} IN (${ids.joinToString(",") { "?" }})")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, ids.map(Long::toString).toTypedArray())
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        val cursor = resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, PROJECTION, args, null) ?: return false
        cursor.use {
            val id = it.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val trashed = it.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_TRASHED)
            val expires = it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_EXPIRES)
            while (it.moveToNext()) {
                if (it.isNull(trashed)) return false
                if (it.getInt(trashed) == 1) found[it.getLong(id)] = if (it.isNull(expires)) null else TimeUnit.SECONDS.toMillis(it.getLong(expires))
            }
        }
        return true
    }

    private fun uri(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

    private companion object {
        /** Well under SQLite's limit of query arguments. */
        const val MAX_IDS_PER_QUERY: Int = 500

        val PROJECTION: Array<String> = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.IS_TRASHED, MediaStore.Audio.Media.DATE_EXPIRES)
    }
}

/** The app's one Duplicates engine (one per process, like the lyrics pipeline), made on first use. */
private object DuplicatesHolder {
    private var engine: Duplicates? = null

    @Synchronized
    fun engine(context: Context): Duplicates = engine ?: context.applicationContext.let { app ->
        Duplicates(
            library = MediaStoreLibrary(app.contentResolver),
            store = DuplicatesStore(File(app.filesDir, "duplicates.json")) { Log.w(LOG_TAG, it) },
            cleanup = { Lyrify.pipeline(app).trashed(it) },
            phoneTrash = MediaStoreTrash(app.contentResolver),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logFailures),
            io = Dispatchers.IO,
            clock = System::currentTimeMillis,
            log = { Log.d(LOG_TAG, it) },
        )
    }.also { engine = it }
}

/** The Duplicates tab's engine: app-private `files/duplicates.json`, never backed up (allowBackup is off). */
fun duplicates(context: Context): Duplicates = DuplicatesHolder.engine(context)
