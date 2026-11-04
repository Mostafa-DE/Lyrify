package app.lyrify.overnight

import app.lyrify.ai.AiStage
import app.lyrify.data.LedgerEntry
import app.lyrify.data.LedgerState
import app.lyrify.data.LyricsStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A song's run the app died in, found by the next run: the stage it was in and the song's deaths so far. */
data class Death(val path: String, val title: String, val stage: AiStage, val deaths: Int)

/** What the crash guard lets the AI do, from the app's deaths since the AI last ran a song to the end. */
enum class GuardVerdict {
    NONE,

    /** Timing known lyrics killed the app [CrashGuard.MAX_DEATHS_IN_A_ROW] times: songs to time wait, the rest run. */
    NO_ALIGN,

    /** Another stage did: the AI waits for the next version of the app. */
    HOLD,
}

/**
 * The overnight run's crash guard, in one app-private file: while a run is in a stage, the song's record names it
 * ([Song.entering]); one still named when the next run starts is a run the app died in (a native crash, out of memory,
 * killed), found by [collectDeaths]. A song the app died in [MAX_DEATHS] times is given up ([Song.deaths]), and
 * [MAX_DEATHS_IN_A_ROW] deaths in one stage without a song run to the end in between stop that stage until the next
 * version of the app ([verdict]).
 *
 * A song's record goes when its run is recorded ([completed]); when its audio file or ledger status changes, its ledger
 * entry goes, or it is older than [MAX_AGE_MS] ([open], [prune]). It also remembers the audio file it was opened for, so
 * a file replaced during the run is noticed before anything is written ([Song.audioChanged]). The file is written whole
 * or not at all (written aside, then moved); one that cannot be written or read is only logged. Blocking.
 */
class CrashGuard(
    private val file: File,
    private val clock: () -> Long,
    /** The app's version code: deaths count within one version, as an update kills a run too. */
    private val version: Int,
    private val log: (String) -> Unit = {},
) {
    /**
     * The record of [entry]'s song: its deaths so far, or a new one when there is none or it no longer fits the song (its
     * audio file or ledger status changed, or it is older than [MAX_AGE_MS]).
     */
    fun open(entry: LedgerEntry): Song {
        val audio = File(entry.path)
        val state = state()
        val kept = state.songs[entry.path]?.takeIf { it.status == entry.status && !expired(it) && it.isOf(audio) }
        val record = when {
            kept == null -> SongRecord(entry.title, audio.length(), audio.lastModified(), entry.status, clock(), version)
            // Deaths under an earlier version are not this one's.
            kept.version != version -> kept.copy(version = version, deaths = 0, diedIn = null)
            else -> kept
        }
        if (record != kept) write(state.copy(songs = state.songs + (entry.path to record)))
        return Song(entry.path, record)
    }

    /**
     * At the start of a run: the songs whose last run never ended, the app having died in it. Each one's death is counted
     * for the song and its stage, once. A run an update killed (an earlier version's) is no death.
     */
    fun collectDeaths(): List<Death> {
        var state = state()
        val deaths = ArrayList<Death>()
        for ((path, record) in state.songs) {
            val stage = record.stage ?: continue
            if (record.version != version) {
                state = state.copy(songs = state.songs + (path to record.copy(stage = null)))
                continue
            }
            val dead = record.copy(stage = null, deaths = record.deaths + 1, diedIn = stage)
            state = state.copy(songs = state.songs + (path to dead)).withDeathIn(stage)
            deaths += Death(path, record.title, stage, dead.deaths)
        }
        write(state)
        return deaths
    }

    /** What the deaths since the last song run to the end allow (this version's only). */
    fun verdict(): GuardVerdict {
        val inARow = state().inARow
        return when {
            inARow.any { (stage, count) -> stage != AiStage.ALIGN && count >= MAX_DEATHS_IN_A_ROW } -> GuardVerdict.HOLD
            (inARow[AiStage.ALIGN] ?: 0) >= MAX_DEATHS_IN_A_ROW -> GuardVerdict.NO_ALIGN
            else -> GuardVerdict.NONE
        }
    }

    /**
     * [path]'s run ran to the end and its result is recorded: its record goes, and the deaths in a row start over in
     * the stages [verdict] has not stopped. A stopped stage stays stopped until the next version of the app.
     */
    fun completed(path: String) {
        val state = state()
        write(state.copy(songs = state.songs - path, inARow = state.inARow.filterValues { it >= MAX_DEATHS_IN_A_ROW }))
    }

    /** Forgets [path]'s record. */
    fun drop(path: String) {
        val state = state()
        if (path in state.songs) write(state.copy(songs = state.songs - path))
    }

    /** Forgets the records of songs settled or changed since ([ledger]: gone, no longer waiting for the AI, another status) and of those older than [MAX_AGE_MS]. */
    fun prune(ledger: LedgerState) {
        val state = state()
        val kept = state.songs.filter { (path, record) ->
            val entry = ledger.entries[path]
            entry != null && entry.status.waitsForAi && entry.status == record.status && !expired(record)
        }
        if (kept != state.songs) write(state.copy(songs = kept))
    }

    /** The songs with a record. */
    fun paths(): Set<String> = state().songs.keys

    private fun expired(record: SongRecord): Boolean = clock() - record.createdAt > MAX_AGE_MS

    private fun State.withDeathIn(stage: AiStage): State = copy(inARow = inARow + (stage to (inARow[stage] ?: 0) + 1))

    /** The state of this version: another version's deaths in a row are not counted. */
    private fun state(): State {
        val saved = try {
            if (file.isFile) json.decodeFromString(State.serializer(), file.readText()) else null
        } catch (e: IOException) {
            log("AI: crash guard not readable (${e.javaClass.simpleName})")
            null
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        return when {
            saved == null -> State(version)
            saved.version != version -> State(version, songs = saved.songs)
            else -> saved
        }
    }

    private fun write(state: State) {
        val aside = File(file.path + TMP)
        try {
            file.parentFile?.mkdirs()
            aside.writeText(json.encodeToString(State.serializer(), state))
            Files.move(aside.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            aside.delete()
            log("AI: crash guard not written (${e.javaClass.simpleName})")
        }
    }

    /** One song's record (see [CrashGuard]). [deaths]: runs of it the app died in (this version), the last one in [diedIn]. */
    inner class Song internal constructor(val path: String, private var record: SongRecord) {
        val deaths: Int get() = record.deaths
        val diedIn: AiStage? get() = record.diedIn

        /** The stage this run entered last, kept after [ended] for [died]. */
        private var last: AiStage? = null

        /** The run enters [stage]: if the app dies now, the next run counts a death here. */
        fun entering(stage: AiStage) {
            last = stage
            update(record.copy(stage = stage))
        }

        /** The run of this song ended (finished or stopped): no stage is left behind to count as a death. */
        fun ended() {
            if (record.stage != null) update(record.copy(stage = null))
        }

        /**
         * This run failed in the stage it entered last as if the app had died there (a model that did not load): counted
         * now as a death of the song and of that stage, as [collectDeaths] counts one, so the song is not run forever.
         */
        fun died() {
            val stage = last ?: return
            record = record.copy(stage = null, deaths = record.deaths + 1, diedIn = stage)
            val state = state()
            write(state.copy(songs = state.songs + (path to record)).withDeathIn(stage))
        }

        /** Whether the song's audio file is no longer the one this record was opened for: replaced (its size or time changed) or gone. */
        fun audioChanged(): Boolean = !record.isOf(File(path))

        private fun update(changed: SongRecord) {
            record = changed
            val state = state()
            write(state.copy(songs = state.songs + (path to changed)))
        }
    }

    /** A song's record: what it was opened for, and the stage a run is in (null between runs). */
    @Serializable
    internal data class SongRecord(
        val title: String,
        val audioBytes: Long,
        val audioModified: Long,
        val status: LyricsStatus,
        val createdAt: Long,
        val version: Int,
        val stage: AiStage? = null,
        val deaths: Int = 0,
        val diedIn: AiStage? = null,
    ) {
        /** Whether [audio] is the file this was made for: the same size and modification time. */
        fun isOf(audio: File): Boolean = audioBytes == audio.length() && audioModified == audio.lastModified()
    }

    /** Every song's record, and the deaths per stage since the AI last ran a song to the end, under [version]. */
    @Serializable
    internal data class State(val version: Int, val songs: Map<String, SongRecord> = emptyMap(), val inARow: Map<AiStage, Int> = emptyMap())

    companion object {
        /** Runs of one song the app may die in before the song is given up (recorded as failed, tried again later). */
        const val MAX_DEATHS: Int = 2

        /** Deaths in one stage, with no song run to the end in between, that stop the stage (see [GuardVerdict]). */
        const val MAX_DEATHS_IN_A_ROW: Int = 3

        const val MAX_AGE_MS: Long = 7 * 24 * 60 * 60_000L

        private const val TMP = ".tmp"

        private val json = Json { ignoreUnknownKeys = true }
    }
}
