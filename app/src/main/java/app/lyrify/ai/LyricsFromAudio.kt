package app.lyrify.ai

import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcFormat
import app.lyrify.lyrics.LrcLine
import app.lyrify.lyrics.LrcProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

/** What the AI made of one song. The engine writes nothing; the caller records it and writes the LRC. */
sealed interface AiResult {
    /** No lyrics were known: Whisper's segments, one LRC line each. [lrc] passed [LrcFormat.validate]. */
    data class Transcribed(val lrc: String, val stats: AiStats) : AiResult

    /** The known plain lyrics, timed; [agreement] is the timing's confidence ([TimedLines.confidence]). [lrc] passed validation. */
    data class Aligned(val lrc: String, val agreement: Double, val stats: AiStats) : AiResult

    /**
     * Not sung ([VocalGate]): no vocal frame at all, or Whisper heard [wordsPerMinute] words per minute of song, at most
     * [VocalGate.VOCAL_ABOVE_WORDS_PER_MINUTE].
     */
    data class Instrumental(val wordsPerMinute: Double) : AiResult

    /** Lyrics were made but are not good enough to write; [agreement] for the align job, [problem] for an LRC Samsung would not show. */
    data class Rejected(val reason: RejectReason, val agreement: Double?, val problem: LrcProblem? = null) : AiResult

    /**
     * The engine could not run: an unreadable file, a native error, no memory, a model that did not load. [reason] is
     * short: the exception's class and message, every absolute path cut to its file name (the log has the whole error).
     * [model] is the model that did not load, when that was the failure; [npu] is set when the phone's NPU could not be
     * used at all (no QNN execution provider or NPU device). Neither is the song's fault.
     */
    data class Failed(val reason: String, val model: File? = null, val npu: Boolean = false) : AiResult
}

/** [model] is there but ONNX Runtime could not load it ([cause]): a damaged file, not the expected model, or the NPU refused it. */
class ModelLoadException(val model: File, cause: Throwable) :
    Exception("${model.name} could not be loaded: ${cause.javaClass.simpleName}: ${cause.message}", cause)

/** The NPU could not be used at all: the QNN execution provider did not register or offers no NPU device. */
class NpuUnavailableException(cause: Throwable) : Exception("the NPU is not available: ${cause.javaClass.simpleName}: ${cause.message}", cause)

enum class RejectReason {
    /** Align: the timing's confidence is under its threshold ([TimedLines.confidence]). */
    LOW_AGREEMENT,

    /** Align: too few line times step forward. */
    NOT_MONOTONIC,

    /** Align: Whisper heard nothing to place the lyrics by. */
    NO_WORDS,

    /** Align: the aligner found no path for the lyrics (no letter in them, or a song too short for them). */
    NO_ALIGNMENT,

    /**
     * The song is too long for the AI: longer than the decoder takes ([PcmToMix.MAX_AUDIO_SEC]), than the app's heap
     * allows ([AiMemory]), or, timing known lyrics, its alignment past the aligner's CPU cap ([Ctc.Budget.maxCells]).
     */
    TOO_LONG,

    /** The song's audio is in a form the AI cannot use, such as a sample rate it does not resample ([UnsupportedAudioException]). */
    UNSUPPORTED_AUDIO,

    /** The LRC would not show properly in Samsung Music, for example fewer than 3 lines ([AiResult.Rejected.problem]). */
    INVALID_LRC,
}

/** The steps of one run, for timings and the crash guard. Stored by name in the crash guard's file. */
enum class AiStage {
    /** Open the file and start its decoder; the decoding itself runs as [SEPARATE] reads the mix. */
    DECODE,

    /** Decode the mix to 44.1 kHz stereo as it is read, MDX vocals on the NPU, the gate's frames and the 16 kHz stem made on the way. */
    SEPARATE,

    /** The stem's last samples and the gate's frames. */
    GATE,

    /** Whisper on the NPU, on the vocal chunks. */
    TRANSCRIBE,

    /** Timing known lyrics: wav2vec2 emissions on the CPU and the timing method (align job only). */
    ALIGN,
}

/** How a run went, for logs and the app's progress. */
data class AiStats(
    val audioSec: Double,
    /** The share of 1 s frames the V2 rule calls vocal (it builds the chunks; it does not decide). */
    val vocalShare: Double,
    /** What decides: words Whisper heard per minute of song ([VocalGate.wordsPerMinute]). */
    val wordsPerMinute: Double,
    val chunks: Int,
    /** Seconds of audio Whisper decoded: the chunks only. */
    val transcribedSec: Double,
    val segments: Int,
    val lines: Int,
    val stageMs: Map<AiStage, Long>,
)

/**
 * The model files the app downloads (the engine only reads them): AI Hub's Whisper for this phone's chip (w8a16, or
 * float), unpacked into the folder [whisper]; the MDX vocal model; wav2vec2 for timing known lyrics.
 */
data class AiModels(val whisper: File, val mdx: File, val wav2vec2: File) {
    val encoder: File get() = File(whisper, ENCODER)
    val decoder: File get() = File(whisper, DECODER)

    /** AI Hub's metadata.json of the Whisper export: its I/O contract ([WhisperContract.fromMetadata]). */
    val metadata: File get() = File(whisper, METADATA)

    companion object {
        /** The folder AI Hub's Whisper is unpacked into, whichever export the chip gets. */
        const val WHISPER: String = "whisper-npu"
        const val ENCODER: String = "encoder.onnx"
        const val DECODER: String = "decoder.onnx"
        const val METADATA: String = "metadata.json"

        /** What the Whisper folder holds: the two EPContext models, their QNN context binaries and AI Hub's metadata. */
        val WHISPER_FILES: List<String> = listOf(ENCODER, "encoder.bin", DECODER, "decoder.bin", METADATA)

        const val MDX: String = "UVR-MDX-NET-Voc_FT.onnx"

        /** Xenova's q4 export (see [CtcModel]); its own name, so the fp32 file of earlier builds is retired by name. */
        const val WAV2VEC2: String = "wav2vec2-base-960h_q4.onnx"

        fun inFolder(folder: File): AiModels = AiModels(File(folder, WHISPER), File(folder, MDX), File(folder, WAV2VEC2))
    }
}

/** Called after each piece of a stage (an MDX segment, an aligner window); throws to stop it. */
fun interface Progress {
    fun step(done: Int, total: Int)
}

/**
 * The heavy steps of [LyricsFromAudio]: on the phone [NpuStages], in tests fakes. Their models stay loaded from first
 * use until the caller closes them (a night's run); a model that does not load throws [ModelLoadException]. All block.
 */
interface AiStages {
    /** The first audio track of [file], opened read only, as a mix that decodes while it is read; the caller closes it. */
    fun decode(file: File): MixSource

    /** Separates the vocals of [mix], read to its end, giving them to [vocals] in order (planar stereo at 44.1 kHz). */
    fun separateVocals(mix: MixSource, vocals: VocalSink, progress: Progress)

    /** Whisper on [chunk] of [stem] (16 kHz mono): its segments, in seconds of the song. */
    fun transcribe(stem: FloatArray, chunk: VocalChunk): List<Segment>

    /** wav2vec2 CTC emissions of the whole [stem] (16 kHz mono), as [Ctc.wholeSong] makes them. */
    fun emissions(stem: FloatArray, progress: Progress): Emissions
}

/** The gate's frames and the 16 kHz mono vocal stem: what a run keeps of the separation ([VocalStem]). */
class Vocals(val frames: GateFrames, val stem: FloatArray)

/**
 * The Java heap one song takes at most, by its length, and whether that fits the app's heap. The 44.1 kHz mix and vocals
 * are never held whole (they stream through the separation), so what grows with the song is the 16 kHz stem (4 bytes a
 * sample; 2 while it is made), wav2vec2's emissions (32 doubles every 20 ms, twice while they are joined) and, timing
 * known lyrics, the aligner's back-pointer table, at most [Ctc.MAX_TABLE_CELLS] bytes. The phases do not overlap:
 * - separating: the stem at 16 bits so far, and the separation's own arrays ([SEPARATION_BYTES]);
 * - finishing the stem: its 16-bit and float copies;
 * - timing: the stem, the emissions and the table (or the emissions twice while they are joined).
 * Whisper's side holds the stem and a few MB.
 */
object AiMemory {
    /**
     * The separation's own arrays for Voc_FT, 4 STFT threads: the spectrum in and out and ONNX Runtime's copy of the
     * output (3 x 12.6 MB), seven chunk-long float arrays (7.3 MB), the STFT's threads and overlap-add (about 15 MB) and
     * the decoder's unread frames (about 4 MB): 67 MB, rounded up.
     */
    const val SEPARATION_BYTES: Long = 72L shl 20

    /** The rest of the app's heap while a song runs (the ledger, WorkManager, coroutines), allowed for. */
    const val APP_BYTES: Long = 32L shl 20

    /** The share of the heap a run may plan to fill: the garbage collector needs room to work. */
    const val HEAP_SHARE: Double = 0.8

    fun peakBytes(durationSec: Double): Long {
        val stem = (durationSec * Rates.SPEECH).toLong() * Float.SIZE_BYTES
        val emissions = Math.round(durationSec / Ctc.FRAME_SEC) * Ctc.CLASSES * Double.SIZE_BYTES
        val separating = stem / 2 + SEPARATION_BYTES
        val finishing = stem / 2 + stem
        val timing = maxOf(stem + 2 * emissions, stem + emissions + Ctc.MAX_TABLE_CELLS)
        return maxOf(separating, finishing, timing)
    }

    /** A song of [durationSec] runs within a heap of [maxHeapBytes] (`Runtime.maxMemory()`) and the decoder's limit. */
    fun fits(durationSec: Double, maxHeapBytes: Long): Boolean =
        durationSec <= PcmToMix.MAX_AUDIO_SEC && peakBytes(durationSec) + APP_BYTES <= maxHeapBytes * HEAP_SHARE
}

/**
 * The AI lyrics engine for one song, as measured on the phone (lyrify-npu-spike, full stage) and on the bench: decode,
 * MDX vocals on the NPU, the stem at 16 kHz as the bench makes it, Whisper on the NPU on the vocal chunks (the V2 frame
 * rule makes them), the word-rate gate on what it heard ([VocalGate]: an instrumental stops there), then
 * - with no known lyrics, transcribe: the segments [SegmentFilters] keeps become the LRC ([Transcription]);
 * - with known plain lyrics, align: [PlainLyricsTiming] times the lines and says how confident it is.
 *
 * Writes nothing and goes nowhere: audio is read, the result is returned. Call it from a background dispatcher: the
 * stages block. Cancelling the coroutine stops the run at the next MDX segment, Whisper chunk or aligner window and
 * throws [CancellationException].
 */
class LyricsFromAudio(
    private val stages: AiStages,
    /** Monotonic milliseconds, for the stage timings. */
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
    /** One-line notes for the AI's run log: stages, times, counts. Never lyrics, nor error messages. */
    private val notes: (String) -> Unit = {},
    /** The timing method's numbers ([PlainLyricsTiming.PARAMS]) and the transcribe job's offset. */
    private val timing: TimingParams = PlainLyricsTiming.PARAMS,
    /** The heap the app may use (`Runtime.maxMemory()`): a song that would not fit is not started ([AiMemory]). */
    private val maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
    /** Whole stack traces in [log]: debug builds only (a trace's messages can hold paths). */
    private val traces: Boolean = false,
) {
    /**
     * [knownPlainLyrics]: LRCLIB's plain lyrics of this recording (duration matched), or null to transcribe. Lyrics with
     * no line to time (see [LyricsText.lines]) are transcribed too. [onStage] hears each stage the run enters (the crash
     * guard keeps it); [beforeChunk] is called before each Whisper chunk (the overnight run waits there while the phone
     * is warm).
     */
    suspend fun run(
        track: LocalTrack,
        knownPlainLyrics: String?,
        onStage: (AiStage) -> Unit = {},
        beforeChunk: suspend () -> Unit = {},
    ): AiResult {
        val lyrics = knownPlainLyrics?.let(LyricsText::lines).orEmpty()
        val job = currentCoroutineContext()[Job]
        val seconds = track.durationMs / 1000.0
        if (!AiMemory.fits(seconds, maxHeapBytes)) {
            note("too long for the AI: %.0f s, about %d MB of a %d MB heap".format(Locale.ROOT, seconds, AiMemory.peakBytes(seconds) shr 20, maxHeapBytes shr 20))
            return AiResult.Rejected(RejectReason.TOO_LONG, agreement = null)
        }
        val name = File(track.path).name
        return try {
            Run(track, lyrics, job, onStage, beforeChunk).result()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnfitAudioException) {
            note("not for the AI (${e.reason}): ${e.message}")
            AiResult.Rejected(e.reason, agreement = null)
        } catch (e: ModelLoadException) {
            log("${e.model.name}: model did not load: ${detail(e, track)}")
            failed(e, track).copy(model = e.model)
        } catch (e: NpuUnavailableException) {
            log("the NPU is not available: ${detail(e, track)}")
            failed(e, track).copy(npu = true)
        } catch (e: Exception) {
            log("$name: AI failed: ${detail(e, track)}")
            failed(e, track)
        } catch (e: OutOfMemoryError) {
            log("$name: AI ran out of memory")
            failed(e, track)
        }
    }

    /** What [log] says of [e]: the whole trace in debug builds ([traces]), else the short reason, paths cut to file names. */
    private fun detail(e: Throwable, track: LocalTrack): String = if (traces) e.stackTraceToString() else failed(e, track).reason

    /**
     * [e] as a short [AiResult.Failed.reason]: its class and message with each absolute path cut to its file name. The
     * song's path (it can hold spaces) is cut first, then anything else from a "/" to a space, quote or colon (a model's
     * or a native library's path, say).
     */
    private fun failed(e: Throwable, track: LocalTrack): AiResult.Failed {
        val message = e.message?.let { text ->
            ABSOLUTE_PATH.replace(text.replace(track.path, File(track.path).name)) { it.groupValues[1] }
        }
        return AiResult.Failed(listOfNotNull(e.javaClass.simpleName, message).joinToString(": "))
    }

    /** A line for logcat and the run log alike: nothing in it may come from the lyrics or an error message. */
    private fun note(message: String) {
        log(message)
        notes(message)
    }

    /** One run: its stage timings are its only state. */
    private inner class Run(
        private val track: LocalTrack,
        private val lyrics: List<String>,
        private val job: Job?,
        private val onStage: (AiStage) -> Unit,
        private val beforeChunk: suspend () -> Unit,
    ) {
        private val stageMs = linkedMapOf<AiStage, Long>()

        private val progress = Progress { _, _ -> job?.ensureActive() }

        suspend fun result(): AiResult {
            val vocals = listen()
            val gate = vocals.frames.gate()
            note("vocal frames %.3f (%d of %d)".format(Locale.ROOT, gate.share, gate.activeFrames, gate.validFrames))
            val chunks = VocalChunks.of(vocals.frames, vocals.stem)
            // No vocal frame at all: nothing to transcribe.
            if (chunks.isEmpty()) return AiResult.Instrumental(0.0)
            val segments = stage(AiStage.TRANSCRIBE) { transcribe(vocals.stem, chunks) }
            val durationSec = vocals.stem.size.toDouble() / Rates.SPEECH
            val wordsPerMinute = VocalGate.wordsPerMinute(segments, vocals.frames, durationSec)
            note("%.1f words per minute".format(Locale.ROOT, wordsPerMinute))
            if (!VocalGate.isVocal(wordsPerMinute)) return AiResult.Instrumental(wordsPerMinute)
            val stats = { lines: Int ->
                AiStats(
                    audioSec = durationSec,
                    vocalShare = gate.share,
                    wordsPerMinute = wordsPerMinute,
                    chunks = chunks.size,
                    transcribedSec = chunks.sumOf { it.durationSec },
                    segments = segments.size,
                    lines = lines,
                    stageMs = stageMs.toMap(),
                )
            }
            return if (lyrics.isEmpty()) transcribed(segments, vocals.frames, stats) else aligned(segments, vocals.stem, stats)
        }

        /** Whisper on each chunk, after [beforeChunk]; a stop is noticed between chunks. */
        private suspend fun transcribe(stem: FloatArray, chunks: List<VocalChunk>): List<Segment> {
            val segments = ArrayList<Segment>()
            for ((k, chunk) in chunks.withIndex()) {
                beforeChunk()
                job?.ensureActive()
                val start = clock()
                val heard = stages.transcribe(stem, chunk)
                note("chunk ${k + 1} of ${chunks.size} (%.0f s): ${clock() - start} ms, ${heard.size} segments".format(Locale.ROOT, chunk.durationSec))
                segments += heard
                job?.ensureActive()
            }
            return segments
        }

        /**
         * Decode, separate, gate: the mix is decoded as the separation reads it, and the gate's frames and the 16 kHz stem
         * are made as the vocals come out ([VocalStem]), so no 44.1 kHz array of the whole song is ever held; what is
         * left for Whisper and wav2vec2 is the 16 kHz stem.
         */
        private fun listen(): Vocals {
            val vocals = VocalStem()
            stage(AiStage.DECODE) { stages.decode(File(track.path)) }.use { mix ->
                stage(AiStage.SEPARATE) { stages.separateVocals(vocals.listening(mix), vocals, progress) }
            }
            return stage(AiStage.GATE) { vocals.build() }
        }

        private fun transcribed(segments: List<Segment>, frames: GateFrames, stats: (Int) -> AiStats): AiResult {
            val lines = Transcription.lines(segments, frames, timing.transcribeOffsetSec)
            note("${segments.size} segments, ${lines.size} kept")
            return lrc(lines, agreement = null) { AiResult.Transcribed(it, stats(lines.size)) }
        }

        private fun aligned(segments: List<Segment>, stem: FloatArray, stats: (Int) -> AiStats): AiResult {
            val timed = stage(AiStage.ALIGN) {
                PlainLyricsTiming.time(segments, stem.size.toDouble() / Rates.SPEECH, lyrics, timing, progress = progress) {
                    stages.emissions(stem, progress).also { job?.ensureActive() }
                }
            }
            // Counts only: the alignment's size and the number of lines.
            val size = timed.trellis?.let { ", ${it.cells} cells (${it.frames} frames x ${it.states} states)" }.orEmpty()
            note("timing (${timing.source}): confidence %.2f, ${timed.rejected ?: "accepted"}$size, ${lyrics.size} lines".format(Locale.ROOT, timed.confidence))
            timed.rejected?.let { reason ->
                val measured = reason !in setOf(RejectReason.NO_WORDS, RejectReason.NO_ALIGNMENT, RejectReason.TOO_LONG, RejectReason.UNSUPPORTED_AUDIO)
                return AiResult.Rejected(reason, timed.confidence.takeIf { measured })
            }
            // Sorted by time, as the bench's write_lrc does: Samsung looks lines up by binary search.
            val lines = lyrics.zip(timed.times) { text, t -> LrcLine(lrcMillis(t), text) }.sortedBy { it.timeMs }
            return lrc(lines, timed.confidence) { AiResult.Aligned(it, timed.confidence, stats(lines.size)) }
        }

        private fun lrc(lines: List<LrcLine>, agreement: Double?, accepted: (String) -> AiResult): AiResult {
            val text = LrcFormat.render(lines)
            val problem = LrcFormat.validate(text, track.durationMs) ?: return accepted(text)
            return AiResult.Rejected(RejectReason.INVALID_LRC, agreement, problem)
        }

        /** [body] as [stage]: timed, and told to [onStage] first. */
        private inline fun <T> stage(stage: AiStage, body: () -> T): T {
            job?.ensureActive()
            onStage(stage)
            val start = clock()
            val value = body()
            val ms = clock() - start
            stageMs[stage] = ms
            note("${stage.name.lowercase()}: $ms ms")
            return value
        }
    }

    companion object {
        /** A "/" not inside a word or a relative path, then folders, then the file name (group 1). */
        private val ABSOLUTE_PATH = Regex("""(?<![\w.~/-])/(?:[^/\s:'"]+/)+([^/\s:'"]*)""")
    }
}
