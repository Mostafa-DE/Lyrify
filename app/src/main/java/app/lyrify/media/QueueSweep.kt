package app.lyrify.media

import app.lyrify.lyrics.Lane
import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.Processed
import kotlinx.coroutines.CancellationException
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

/** How far the sweep of Samsung Music's queue is: [checked] of [total] queue items; [retrying] while "Retry failed now" runs. */
data class SweepProgress(val checked: Int, val total: Int, val paused: Boolean, val retrying: Boolean = false)

/**
 * Gets lyrics for every item in Samsung Music's queue, so they exist before a song starts
 * (Samsung reads lyrics when a track loads, and on shuffle the next song can be anywhere).
 * One worker, so LRCLIB still gets one request at a time, fed by two lanes: HIGH, the current
 * track and the next [SessionTracks.PREFETCH_COUNT], and LOW, the files the user asked to
 * retry ([retry]), the rest of the queue, then the files due again after a failed lookup.
 * HIGH always goes first: a LOW item in progress is dropped the moment HIGH work arrives and
 * done again afterwards (what it finished is in the ledger, so the redo is cheap). A new
 * current track replaces the pending HIGH lane, and drops the HIGH item in progress (backoff
 * included) unless it is still the current track or one of the next few; a new queue replaces
 * the queue part of LOW. Dropping never cuts a `.lrc` write short: the pipeline writes without
 * cancellation. An item that fails is logged and skipped.
 *
 * After [BUSY_LIMIT] LOW items in a row found LRCLIB busy, LOW pauses for [PAUSE_MS] while
 * HIGH keeps going; the user's retries run anyway, and do not count. When the worker starts and
 * every [RETRY_EVERY_MS] after, every file whose last lookup failed and is due again goes to the
 * end of LOW, in the queue or not, unless it is waiting already. Each run also settles the
 * `.lrc` writes the app's death interrupted ([settleInterrupted]).
 */
class QueueSweep(
    /** The local files a session track may be (blocking is fine: the worker runs on IO). */
    private val files: suspend (SessionTrack) -> List<LocalTrack>,
    /** Gets lyrics for a file; with [force], also when its ledger entry is not due yet. */
    private val process: suspend (track: LocalTrack, lane: Lane, force: Boolean) -> Processed?,
    /** Files whose last lookup failed, due again and still there, with the tags the ledger keeps. */
    private val dueErrors: suspend () -> List<LocalTrack>,
    /** Settles writes the app's death interrupted; run alongside the worker each time it starts. */
    private val settleInterrupted: suspend () -> Unit = {},
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

    /** "Retry failed now": [tracks] go into LOW ahead of the queue, each once, looked up even if not due. */
    fun retry(tracks: List<LocalTrack>) {
        lanes.update { it.withForcedRetries(tracks) }
    }

    /** The worker. Runs until cancelled; the ledger lets the next run pick up where this one stopped. */
    suspend fun run() = coroutineScope {
        launch { retryErrorsEvery() }
        launch { settleInterrupted() }
        var busyInARow = 0
        while (true) {
            val next = take()
            val busy = handleUnlessPreempted(next)
            lanes.update { it.copy(inFlight = null) }
            // Only the automatic sweep counts towards the pause (and waits it out).
            if (busy == null || next.lane == Lane.HIGH || next.work is Work.Retry) continue
            busyInARow = if (busy) busyInARow + 1 else 0
            if (busyInARow >= BUSY_LIMIT) {
                busyInARow = 0
                pauseLow()
            }
        }
    }

    /** The next item: HIGH first, then the user's retries, then the rest of LOW while not paused; waits until there is one. */
    private suspend fun take(): Taken {
        while (true) {
            val current = lanes.first { it.hasWork() }
            val taken = current.next() ?: continue
            if (lanes.compareAndSet(current, taken.rest.copy(inFlight = taken.work))) return taken
        }
    }

    /**
     * [taken], dropped when more urgent work arrives ([Lanes.preempts]): null then, and the item
     * goes back to the front of LOW. Also null when it failed.
     */
    private suspend fun handleUnlessPreempted(taken: Taken): Boolean? = coroutineScope {
        val work = async { handleOrSkip(taken.work, taken.lane) }
        val preempt = launch {
            lanes.first { it.preempts(taken) }
            work.cancel()
        }
        work.join()
        preempt.cancel()
        if (work.isCancelled) {
            lanes.update { it.putBack(taken.work) }
            null
        } else {
            work.await()
        }
    }

    /** [handle], except that a failure is logged and the item skipped (null): one bad item must not stop the sweep. */
    private suspend fun handleOrSkip(work: Work, lane: Lane): Boolean? = try {
        handle(work, lane)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$work failed, skipped: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private suspend fun handle(work: Work, lane: Lane): Boolean = when (work) {
        is Work.Item -> handle(work.entry, lane)
        // Asked for by the user: looked up even though the ledger says it is not due yet.
        is Work.Retry -> process(work.track, lane, true)?.lrclibBusy == true
        is Work.Due -> process(work.track, lane, false)?.lrclibBusy == true
    }

    /** Gets lyrics for every file [entry] may be; true when LRCLIB was too busy to answer for one of them. */
    private suspend fun handle(entry: QueueEntry, lane: Lane): Boolean {
        val found = files(entry.track)
        // The file lookup blocks and may return after the listener disconnected: publish nothing then.
        currentCoroutineContext().ensureActive()
        if (lanes.value.current == entry) onCurrent(entry.track, found)
        var busy = false
        for (file in found) busy = process(file, lane, false)?.lrclibBusy == true || busy
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
            val due = dueErrors()
            lanes.update { it.withDue(due) }
            delay(RETRY_EVERY_MS)
        }
    }

    /** Sweep work: a queue item (its files are found when it is handled), a file the user asked to retry, or a file due again. */
    private sealed interface Work {
        data class Item(val entry: QueueEntry) : Work

        data class Retry(val track: LocalTrack) : Work

        /** A file whose failed lookup is due again: looked up like any queue file, not forced. */
        data class Due(val track: LocalTrack) : Work
    }

    private data class Taken(val work: Work, val lane: Lane, val rest: Lanes)

    private data class Lanes(
        val high: List<QueueEntry> = emptyList(),
        val low: List<QueueEntry> = emptyList(),
        /** Files the user asked to retry: LOW work, ahead of [low] and run even while [paused]. */
        val retries: List<LocalTrack> = emptyList(),
        /** The HIGH entry of the track playing, as last submitted. */
        val current: QueueEntry? = null,
        val queue: List<QueueEntry> = emptyList(),
        /** Queue ids handled since this queue was submitted. */
        val checked: Set<Long> = emptySet(),
        /** Files due again after a failed lookup: LOW work after [low], kept when the queue changes. */
        val due: List<LocalTrack> = emptyList(),
        val paused: Boolean = false,
        /** The item the worker is on. */
        val inFlight: Work? = null,
    ) {
        fun hasWork(): Boolean = high.isNotEmpty() || retries.isNotEmpty() || (!paused && (low.isNotEmpty() || due.isNotEmpty()))

        fun next(): Taken? = when {
            high.isNotEmpty() -> Taken(Work.Item(high.first()), Lane.HIGH, copy(high = high.drop(1)))
            retries.isNotEmpty() -> Taken(Work.Retry(retries.first()), Lane.LOW, copy(retries = retries.drop(1)))
            !paused && low.isNotEmpty() -> Taken(Work.Item(low.first()), Lane.LOW, copy(low = low.drop(1)))
            !paused && due.isNotEmpty() -> Taken(Work.Due(due.first()), Lane.LOW, copy(due = due.drop(1)))
            else -> null
        }

        /**
         * Whether [taken] should stop for more urgent work. LOW work: any HIGH work. HIGH work:
         * a new current track it is not one of (the track or the next few). While the worker is
         * on HIGH work, [high] is the whole HIGH lane as last submitted.
         */
        fun preempts(taken: Taken): Boolean = when (taken.lane) {
            Lane.LOW -> high.isNotEmpty()
            Lane.HIGH -> current != taken.rest.current && (taken.work !is Work.Item || high.none { it.isSameItem(taken.work.entry) })
        }

        fun submitted(newQueue: List<QueueEntry>, order: SweepOrder): Lanes =
            if (newQueue == queue) {
                copy(high = order.high, current = order.high.firstOrNull())
            } else {
                Lanes(
                    high = order.high,
                    low = order.low,
                    retries = retries,
                    current = order.high.firstOrNull(),
                    queue = newQueue,
                    due = due,
                    paused = paused,
                    inFlight = inFlight,
                )
            }

        /** [entry] is done, [found] its files: none of them waits as due again any more. */
        fun handled(entry: QueueEntry, found: List<LocalTrack>): Lanes {
            val rest = copy(due = due.filter { file -> found.none { it.path == file.path } })
            return if (queue.none { it.queueId == entry.queueId }) rest else rest.copy(checked = checked + entry.queueId)
        }

        /** A dropped item at the front of its list, unless it left the queue or is scheduled already. */
        fun putBack(work: Work): Lanes = when (work) {
            is Work.Item -> if (work.entry in queue && work.entry !in low && work.entry !in high) copy(low = listOf(work.entry) + low) else this
            is Work.Retry -> if (retries.none { it.path == work.track.path }) copy(retries = listOf(work.track) + retries) else this
            // A forced retry of the same file waiting already covers it.
            is Work.Due -> if ((due + retries).none { it.path == work.track.path }) copy(due = listOf(work.track) + due) else this
        }

        /** [tracks] after the retries already waiting, each file once (a forced retry replaces the same file due again). */
        fun withForcedRetries(tracks: List<LocalTrack>): Lanes =
            copy(retries = (retries + tracks).distinctBy { it.path }, due = due.filter { file -> tracks.none { it.path == file.path } })

        /**
         * [tracks] at the end of LOW, each file once, except files waiting already or in progress.
         * A queue item still waiting may be one of them: handling it takes its files off [due].
         */
        fun withDue(tracks: List<LocalTrack>): Lanes {
            val inProgress = listOfNotNull((inFlight as? Work.Retry)?.track, (inFlight as? Work.Due)?.track)
            val waiting = (due + retries + inProgress).map { it.path }.toSet()
            val again = tracks.filter { it.path !in waiting }.distinctBy { it.path }
            return if (again.isEmpty()) this else copy(due = due + again)
        }

        fun progress(): SweepProgress = SweepProgress(checked.size, queue.size, paused, retrying = retries.isNotEmpty() || inFlight is Work.Retry)

        /** The same queue item, by queue id: the current track's entry carries the session's metadata, its queue entry does not. */
        private fun QueueEntry.isSameItem(other: QueueEntry): Boolean =
            if (queueId == SessionTracks.UNKNOWN_QUEUE_ID) this == other else queueId == other.queueId
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
