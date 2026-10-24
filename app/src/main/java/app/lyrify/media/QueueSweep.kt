package app.lyrify.media

import app.lyrify.lyrics.Lane
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.Processed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How far the sweep of Samsung Music's queue is: [checked] of [total] queue items. */
data class SweepProgress(val checked: Int, val total: Int, val paused: Boolean)

/**
 * Gets lyrics for every item in Samsung Music's queue, so they exist before a song starts
 * (Samsung reads lyrics when a track loads, and on shuffle the next song can be anywhere).
 * One worker, so LRCLIB still gets one request at a time, fed by two lanes: HIGH, the current
 * track and the next [SessionTracks.PREFETCH_COUNT], and LOW, the rest of the queue. HIGH
 * always goes first: a LOW item in progress is dropped the moment HIGH work arrives and done
 * again afterwards (what it finished is in the ledger, so the redo is cheap). A new current
 * track replaces the pending HIGH lane, a new queue the LOW lane.
 *
 * After [BUSY_LIMIT] LOW items in a row found LRCLIB busy, LOW pauses for [PAUSE_MS] while
 * HIGH keeps going. Every [RETRY_EVERY_MS], queue items whose last lookup failed and is due
 * again go back into LOW.
 */
class QueueSweep(
    /** The local files a session track may be (blocking is fine: the worker runs on IO). */
    private val files: suspend (SessionTrack) -> List<LocalTrack>,
    private val process: suspend (LocalTrack, Lane) -> Processed?,
    /** Audio paths whose last lookup failed and that are due again. */
    private val dueErrors: suspend () -> Set<String>,
    /** The files found for the track playing now, once looked for. */
    private val onCurrent: (SessionTrack, List<LocalTrack>) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
) {
    private val lanes = MutableStateFlow(Lanes())

    val progress: Flow<SweepProgress> = lanes.map { it.progress() }.distinctUntilChanged()

    /** Work for [snapshot]: always a new HIGH lane, a new LOW lane only when the queue itself changed. */
    fun submit(snapshot: SessionSnapshot) {
        val order = SessionTracks.sweepOrder(snapshot.queue, snapshot.activeQueueId, snapshot.current)
        lanes.update { it.submitted(snapshot.queue, order) }
    }

    /** The worker. Runs until cancelled; the ledger lets the next run pick up where this one stopped. */
    suspend fun run() = coroutineScope {
        launch { retryErrorsEvery() }
        var busyInARow = 0
        while (true) {
            val next = take()
            if (next.lane == Lane.HIGH) {
                handle(next.entry, Lane.HIGH)
                continue
            }
            val busy = handleUnlessPreempted(next.entry) ?: continue
            busyInARow = if (busy) busyInARow + 1 else 0
            if (busyInARow >= BUSY_LIMIT) {
                busyInARow = 0
                pauseLow()
            }
        }
    }

    /** The next item, HIGH first, LOW only while not paused; waits until there is one. */
    private suspend fun take(): Taken {
        while (true) {
            val current = lanes.first { it.hasWork() }
            val taken = current.next() ?: continue
            if (lanes.compareAndSet(current, taken.rest)) return taken
        }
    }

    /** LOW work, dropped when HIGH work arrives: null then, and the item goes back to the front of LOW. */
    private suspend fun handleUnlessPreempted(entry: QueueEntry): Boolean? = coroutineScope {
        val work = async { handle(entry, Lane.LOW) }
        val preempt = launch {
            lanes.first { it.high.isNotEmpty() }
            work.cancel()
        }
        work.join()
        preempt.cancel()
        if (work.isCancelled) {
            lanes.update { it.putBack(entry) }
            null
        } else {
            work.await()
        }
    }

    /** Gets lyrics for every file [entry] may be; true when LRCLIB was too busy to answer for one of them. */
    private suspend fun handle(entry: QueueEntry, lane: Lane): Boolean {
        val found = files(entry.track)
        // The file lookup blocks and may return after the listener disconnected: publish nothing then.
        currentCoroutineContext().ensureActive()
        if (lanes.value.current == entry) onCurrent(entry.track, found)
        var busy = false
        for (file in found) busy = process(file, lane)?.lrclibBusy == true || busy
        lanes.update { it.handled(entry, found) }
        return busy
    }

    private fun CoroutineScope.pauseLow() {
        lanes.update { it.copy(paused = true) }
        log("LRCLIB busy: queue sweep paused for ${PAUSE_MS / MS_PER_MINUTE} min")
        launch {
            delay(PAUSE_MS)
            lanes.update { it.copy(paused = false) }
        }
    }

    private suspend fun retryErrorsEvery(): Nothing {
        while (true) {
            delay(RETRY_EVERY_MS)
            val due = dueErrors()
            lanes.update { it.withRetries(due) }
        }
    }

    private data class Taken(val entry: QueueEntry, val lane: Lane, val rest: Lanes)

    private data class Lanes(
        val high: List<QueueEntry> = emptyList(),
        val low: List<QueueEntry> = emptyList(),
        /** The HIGH entry of the track playing, as last submitted. */
        val current: QueueEntry? = null,
        val queue: List<QueueEntry> = emptyList(),
        /** Queue ids handled since this queue was submitted. */
        val checked: Set<Long> = emptySet(),
        /** The audio paths found for each queue id, for the periodic retry. */
        val paths: Map<Long, List<String>> = emptyMap(),
        val paused: Boolean = false,
    ) {
        fun hasWork(): Boolean = high.isNotEmpty() || (!paused && low.isNotEmpty())

        fun next(): Taken? = when {
            high.isNotEmpty() -> Taken(high.first(), Lane.HIGH, copy(high = high.drop(1)))
            !paused && low.isNotEmpty() -> Taken(low.first(), Lane.LOW, copy(low = low.drop(1)))
            else -> null
        }

        fun submitted(newQueue: List<QueueEntry>, order: SweepOrder): Lanes =
            if (newQueue == queue) {
                copy(high = order.high, current = order.high.firstOrNull())
            } else {
                Lanes(high = order.high, low = order.low, current = order.high.firstOrNull(), queue = newQueue, paused = paused)
            }

        fun handled(entry: QueueEntry, found: List<LocalTrack>): Lanes =
            if (queue.none { it.queueId == entry.queueId }) {
                this
            } else {
                copy(checked = checked + entry.queueId, paths = paths + (entry.queueId to found.map { it.path }))
            }

        /** A preempted LOW item, unless it left the queue or is scheduled already. */
        fun putBack(entry: QueueEntry): Lanes =
            if (entry in queue && entry !in low && entry !in high) copy(low = listOf(entry) + low) else this

        /** Queue items with a file in [due] go to the end of LOW, once. */
        fun withRetries(due: Set<String>): Lanes {
            val again = queue.filter { entry ->
                entry !in low && entry !in high && paths[entry.queueId].orEmpty().any { it in due }
            }
            return if (again.isEmpty()) this else copy(low = low + again)
        }

        fun progress(): SweepProgress = SweepProgress(checked.size, queue.size, paused)
    }

    companion object {
        private const val MS_PER_MINUTE: Long = 60_000

        /** LOW items in a row that found LRCLIB busy before LOW pauses. */
        const val BUSY_LIMIT: Int = 3
        const val PAUSE_MS: Long = 2 * MS_PER_MINUTE

        /** Matches the first wait after an error (30 min, doubling): each due retry is picked up within one period. */
        const val RETRY_EVERY_MS: Long = 30 * MS_PER_MINUTE
    }
}
