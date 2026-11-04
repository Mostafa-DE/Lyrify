package app.lyrify.ai

import app.lyrify.lyrics.LocalTrack
import app.lyrify.lyrics.LrcProblem
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

/** How [LyricsFromAudio] maps what its stages produce to an [AiResult]. All text here is made up. */
class LyricsFromAudioTest {
    private val track = LocalTrack("/music/song.mp3", "Song", "Artist", null, durationMs = 30_000)
    private val lyrics = listOf("alpha beta gamma", "delta epsilon zeta", "eta theta iota", "kappa lambda mu")

    /** Where Whisper's segment of each lyric line starts. */
    private val heard = listOf(5.5, 10.5, 15.5, 20.5)

    /** Each call is one second later: every stage takes 1000 ms (transcribe 3000 ms, see [stats]). */
    private fun engine(stages: AiStages): LyricsFromAudio {
        var now = 0L
        return LyricsFromAudio(stages, clock = { now.also { now += 1_000 } })
    }

    private val sung = lyrics.zip(heard) { line, t -> Segment(t, t + 2.5, " $line") }

    // Transcribe

    @Test
    fun `without known lyrics the kept segments become lines at their starts`() = runTest {
        val segments = listOf(
            Segment(5.9, 8.0, " first heard line"),
            Segment(10.5, 12.0, " ¶¶"), // junk
            Segment(15.9, 18.0, " second heard line"),
            Segment(20.9, 23.0, " third heard line"),
            Segment(27.5, 30.0, " Thank you."), // a filler over frames 27-29, only 27 vocal: dropped
        )
        val stages = FakeStages(vocalSeconds = 4..27, segments = segments)
        val result = engine(stages).run(track, knownPlainLyrics = null)
        // Each line starts 0.44 s after its segment (segments start early).
        val expectedLrc = "[00:06.340]first heard line\n[00:16.340]second heard line\n[00:21.340]third heard line\n"
        // 9 words kept (not the junk, not the filler) in 30 s: 18 words per minute.
        assertEquals(AiResult.Transcribed(expectedLrc, stats(lines = 3, segments = 5, align = false, wordsPerMinute = 18.0)), result)
        // One vocal run, frames 4-27, padded by 1 s: one chunk.
        assertEquals(listOf(VocalChunk(3.0, 29.0)), stages.chunks)
    }

    @Test
    fun `the timing numbers' transcribe offset moves every heard line`() = runTest {
        val segments = listOf(Segment(5.9, 8.0, " first heard line"), Segment(15.9, 18.0, " second heard line"), Segment(20.9, 23.0, " third heard line"))
        var now = 0L
        val later = PlainLyricsTiming.PARAMS.copy(transcribeOffsetSec = 0.25)
        val result = LyricsFromAudio(FakeStages(4..27, segments), clock = { now.also { now += 1_000 } }, timing = later).run(track, null)
        assertTrue(result.toString(), result is AiResult.Transcribed)
        assertEquals("[00:06.150]first heard line\n[00:16.150]second heard line\n[00:21.150]third heard line\n", (result as AiResult.Transcribed).lrc)
    }

    @Test
    fun `the 16 kHz stem Whisper hears is the vocals' channel mean, resampled and through PCM 16`() = runTest {
        val stages = FakeStages(4..27, sung)
        engine(stages).run(track, null)
        assertTrue(checkNotNull(stages.opened).closed)
        val stem = checkNotNull(stages.stem)
        assertEquals(ResampleFrac(Rates.MIX, Rates.SPEECH).outputLength(30 * Rates.MIX), stem.size)
        assertTrue(stem.all { it * 32768f == Math.floor(it * 32768.0).toFloat() })
    }

    @Test
    fun `too few lines heard is rejected as an LRC Samsung would not show`() = runTest {
        val segments = listOf(Segment(5.5, 8.0, "first heard line"), Segment(15.5, 18.0, "second heard line"))
        val result = engine(FakeStages(4..27, segments)).run(track, null)
        assertEquals(AiResult.Rejected(RejectReason.INVALID_LRC, agreement = null, problem = LrcProblem.TOO_FEW_LINES), result)
    }

    @Test
    fun `a song with no vocal frame at all is an instrumental, and Whisper does not run`() = runTest {
        val stages = FakeStages(vocalSeconds = IntRange.EMPTY, segments = sung)
        assertEquals(AiResult.Instrumental(0.0), engine(stages).run(track, null))
        assertEquals(listOf("decode", "separate"), stages.calls)
    }

    @Test
    fun `vocal-looking frames Whisper hears few words over are an instrumental, fillers not counted`() = runTest {
        // A lead guitar in the vocal stem: frames 4-27 look vocal, Whisper writes 4 words and a filler in 30 s.
        val heard = listOf(Segment(6.0, 9.0, " la la"), Segment(12.0, 14.0, " oh oh"), Segment(27.5, 30.0, " Thank you."))
        val stages = FakeStages(4..27, heard)
        assertEquals(AiResult.Instrumental(8.0), engine(stages).run(track, null))
        assertEquals(listOf("decode", "separate", "transcribe"), stages.calls)
        // The same with known lyrics: nothing to time.
        assertEquals(AiResult.Instrumental(8.0), engine(FakeStages(4..27, heard, lyrics = lyrics)).run(track, lyrics.joinToString("\n")))
    }

    @Test
    fun `lyrics with nothing to time are transcribed, without the aligner`() = runTest {
        val stages = FakeStages(4..27, sung)
        val result = engine(stages).run(track, "[Chorus]\n\n...\n")
        assertTrue(result.toString(), result is AiResult.Transcribed)
        assertEquals(listOf("decode", "separate", "transcribe"), stages.calls)
    }

    // Align

    @Test
    fun `known lyrics are timed where the whole-song aligner puts them, Whisper agreeing`() = runTest {
        // The singing starts 0.4 s after each segment: the S2c windows around the segments see it there too.
        val stages = FakeStages(4..27, sung, ctcTimes = heard.map { it + 0.4 }, lyrics = lyrics)
        val result = engine(stages).run(track, lyrics.joinToString("\n", prefix = "[Verse 1]\n"))
        val expectedLrc = lyrics.zip(listOf("05.900", "10.900", "15.900", "20.900")) { line, t -> "[00:$t]$line\n" }.joinToString("")
        assertEquals(AiResult.Aligned(expectedLrc, agreement = 1.0, stats(lines = 4, segments = 4, align = true, wordsPerMinute = 24.0)), result)
        assertEquals(listOf("decode", "separate", "transcribe", "emissions"), stages.calls)
    }

    @Test
    fun `known lyrics sung far from where Whisper heard them are rejected with the agreement`() = runTest {
        // Sung 61 s later than heard: past the 60 s S2c window around the segments, so S2c and S4 disagree. More is
        // heard at 31-58 s, so the song is sung.
        val far = track.copy(durationMs = 90_000)
        val more = listOf(32.0, 37.0, 42.0, 47.0).map { Segment(it, it + 4, " na na na na na na na na") }
        val stages = FakeStages((4..27) + (31..58), sung + more, ctcTimes = heard.map { it + 61.0 }, lyrics = lyrics, seconds = 90)
        val result = engine(stages).run(far, lyrics.joinToString("\n"))
        assertTrue(result.toString(), result is AiResult.Rejected && result.reason == RejectReason.LOW_AGREEMENT && checkNotNull(result.agreement) < 0.8)
    }

    @Test
    fun `known lyrics nothing heard matches are rejected before wav2vec2 runs`() = runTest {
        val other = heard.map { Segment(it, it + 2.5, " zzz qqq xxx www") } // sung, but nothing like the lyrics
        val stages = FakeStages(4..27, other, lyrics = lyrics)
        assertEquals(AiResult.Rejected(RejectReason.NO_WORDS, agreement = null), engine(stages).run(track, lyrics.joinToString("\n")))
        assertEquals(listOf("decode", "separate", "transcribe"), stages.calls)
    }

    // Failures

    @Test
    fun `a stage that throws makes the run fail with the reason`() = runTest {
        val stages = FakeStages(4..27, sung, failDecode = IllegalStateException("no audio track in song.mp3"))
        assertEquals(AiResult.Failed("IllegalStateException: no audio track in song.mp3"), engine(stages).run(track, null))
    }

    @Test
    fun `a failure's reason keeps file names but no folders, the log keeps the whole message`() = runTest {
        val song = LocalTrack("/storage/emulated/0/Music/Some Band/01 Song One.mp3", "Song One", null, null, durationMs = 30_000)
        val model = "/data/user/0/app.lyrify/files/models/${AiModels.MDX}"
        val message = "${song.path}: open failed: ENOENT (No such file or directory); Load model from $model failed:Protobuf parsing failed."
        val logged = mutableListOf<String>()
        var now = 0L
        val engine = LyricsFromAudio(FakeStages(4..27, sung, failDecode = FileNotFoundException(message)), { now++ }, logged::add)
        val reason = "FileNotFoundException: 01 Song One.mp3: open failed: ENOENT (No such file or directory); " +
            "Load model from ${AiModels.MDX} failed:Protobuf parsing failed."
        assertEquals(AiResult.Failed(reason), engine.run(song, null))
        // A release build's log has the file name and the short reason: no folders, no stack trace.
        assertEquals(listOf("01 Song One.mp3: AI failed: $reason"), logged)
        // A debug build's has the whole trace.
        val traced = mutableListOf<String>()
        LyricsFromAudio(FakeStages(4..27, sung, failDecode = FileNotFoundException(message)), { now++ }, traced::add, traces = true).run(song, null)
        assertTrue(traced.toString(), traced.single().contains(message) && traced.single().contains("\tat "))
        // Other slashes stay.
        val ratio = engine(FakeStages(4..27, sung, failDecode = IllegalStateException("3/4 windows, audio/mpeg")))
        assertEquals(AiResult.Failed("IllegalStateException: 3/4 windows, audio/mpeg"), ratio.run(track, null))
    }

    @Test
    fun `a song longer than the heap allows, or than 20 minutes, is rejected as too long before anything runs`() = runTest {
        val notes = mutableListOf<String>()
        var now = 0L
        val stages = FakeStages(4..27, sung)
        // 30 s needs the separation's 72 MB and the app's 32 MB: more than 80% of a 64 MB heap.
        val small = LyricsFromAudio(stages, { now++ }, notes = { notes += it }, maxHeapBytes = 64L shl 20)
        assertEquals(AiResult.Rejected(RejectReason.TOO_LONG, agreement = null), small.run(track, null))
        assertEquals(emptyList<String>(), stages.calls)
        assertEquals(listOf("too long for the AI: 30 s, about 72 MB of a 64 MB heap"), notes)
        val long = track.copy(durationMs = 20 * 60_000L + 1_000)
        assertEquals(AiResult.Rejected(RejectReason.TOO_LONG, agreement = null), engine(stages).run(long, null))
        assertEquals(emptyList<String>(), stages.calls)
    }

    @Test
    fun `audio the decoder finds longer than 20 minutes, or in a form it cannot use, is rejected with that reason, not failed`() = runTest {
        val notes = mutableListOf<String>()
        var now = 0L
        val long = FakeStages(4..27, sung, unfit = AudioTooLongException("too long: over 20 minutes of audio"))
        assertEquals(AiResult.Rejected(RejectReason.TOO_LONG, agreement = null), LyricsFromAudio(long, { now++ }, notes = { notes += it }).run(track, null))
        val odd = FakeStages(4..27, sung, unfit = UnsupportedAudioException("44056 Hz audio: 44056 -> 44100 needs 11025 filter phases (max 4096)"))
        assertEquals(AiResult.Rejected(RejectReason.UNSUPPORTED_AUDIO, agreement = null), LyricsFromAudio(odd, { now++ }, notes = { notes += it }).run(track, null))
        assertTrue(notes.toString(), "not for the AI (TOO_LONG): too long: over 20 minutes of audio" in notes)
        assertTrue(notes.toString(), "not for the AI (UNSUPPORTED_AUDIO): 44056 Hz audio: 44056 -> 44100 needs 11025 filter phases (max 4096)" in notes)
    }

    @Test
    fun `the peak heap grows with the stem and the emissions, the separation's arrays and the table fixed`() {
        val mb = 1L shl 20
        // 6 minutes: timing (stem 23 MB, emissions 4.6 MB, table 64 MB) is the peak.
        assertEquals(23_040_000L + 4_608_000 + 64_000_000, AiMemory.peakBytes(360.0))
        // 12 minutes: timing again.
        assertEquals(46_080_000L + 9_216_000 + 64_000_000, AiMemory.peakBytes(720.0))
        // 20 minutes: timing, 156 MB; with the app's allowance about 188 MB, under 300 MB.
        assertEquals(76_800_000L + 15_360_000 + 64_000_000, AiMemory.peakBytes(1200.0))
        assertTrue(AiMemory.peakBytes(1200.0) + AiMemory.APP_BYTES < 300 * mb)
        // A short song: the separation's own arrays.
        assertEquals(30 * 16_000 * 2L + AiMemory.SEPARATION_BYTES, AiMemory.peakBytes(30.0))
        // The phone's 512 MB heap takes every song up to the decoder's 20 minutes; 256 MB too.
        assertTrue(AiMemory.fits(1200.0, 512 * mb))
        assertTrue(AiMemory.fits(1200.0, 256 * mb))
        assertTrue(!AiMemory.fits(1201.0, 512 * mb))
        // The boundary: peak + app <= 80% of the heap.
        val heap = ((AiMemory.peakBytes(600.0) + AiMemory.APP_BYTES) / AiMemory.HEAP_SHARE).toLong() + 1
        assertTrue(AiMemory.fits(600.0, heap))
        assertTrue(!AiMemory.fits(600.0, heap - 2))
    }

    @Test
    fun `a model that does not load fails the run naming that model`() = runTest {
        val mdx = File("/models", AiModels.MDX)
        val result = engine(FakeStages(4..27, sung, failLoad = mdx)).run(track, null)
        assertEquals(AiResult.Failed("ModelLoadException: ${AiModels.MDX} could not be loaded: IllegalStateException: QNN graph finalize failed", mdx), result)
    }

    @Test
    fun `an NPU that cannot be used fails the run as the phone's, not the song's or a model's`() = runTest {
        val result = engine(FakeStages(4..27, sung, noNpu = true)).run(track, null)
        assertTrue(result.toString(), result is AiResult.Failed && result.npu && result.model == null)
    }

    @Test
    fun `ONNX Runtime internals that fail while Whisper runs fail closed through the same NPU path`() = runTest {
        // What NpuStages throws when IoTensors cannot make a uint16 tensor (the w8a16 mel, mask): never a crash, never
        // the song's failure, never a model deleted; the night's run stops and the hold says the NPU was not usable.
        val internals = NpuUnavailableException(OrtInternalsException(NoSuchMethodException("createTensorFromBuffer")))
        val stages = FakeStages(4..27, sung, failTranscribe = internals)
        val result = engine(stages).run(track, null)
        assertTrue(result.toString(), result is AiResult.Failed && result.npu && result.model == null)
        assertEquals(1, stages.calls.count { it == "transcribe" })
    }

    @Test
    fun `cancelling stops the run at the next piece of work instead of failing it`() = runTest {
        val stages = FakeStages(4..27, sung)
        var result: AiResult? = null
        val job = launch { result = engine(stages).run(track, null) }
        stages.duringTranscribe = { job.cancel() }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(result)
    }

    // What the run tells its caller

    @Test
    fun `a run tells each stage it enters, and waits its turn before each chunk`() = runTest {
        // 70 s with singing at 4-27 s and 40-67 s: two chunks.
        val long = track.copy(durationMs = 70_000)
        val stages = FakeStages((4..27) + (40..67), sung, ctcTimes = heard.map { it + 0.4 }, lyrics = lyrics, seconds = 70)
        val entered = mutableListOf<AiStage>()
        var turns = 0

        engine(stages).run(long, lyrics.joinToString("\n"), entered::add) { turns++ }

        assertEquals(listOf(AiStage.DECODE, AiStage.SEPARATE, AiStage.GATE, AiStage.TRANSCRIBE, AiStage.ALIGN), entered)
        assertEquals(listOf(VocalChunk(3.0, 29.0), VocalChunk(39.0, 69.0)), stages.chunks)
        assertEquals(2, turns)
    }

    @Test
    fun `the notes for the run log hold no lyrics`() = runTest {
        val notes = mutableListOf<String>()
        var now = 0L
        val stages = FakeStages(4..27, sung, ctcTimes = heard.map { it + 0.4 }, lyrics = lyrics)

        val result = LyricsFromAudio(stages, { now++ }, notes = { notes += it }).run(track, lyrics.joinToString("\n"))

        assertTrue(result.toString(), result is AiResult.Aligned)
        val noted = notes.joinToString("\n")
        assertTrue(noted, "confidence" in noted && "chunk 1 of 1" in noted)
        // The alignment's size and the line count, as counts.
        assertTrue(noted, Regex("""accepted, \d+ cells \(\d+ frames x \d+ states\), 4 lines""") in noted)
        lyrics.flatMap { it.split(' ') }.forEach { word -> assertFalse("\"$word\" in $noted", word in noted) }
    }

    private fun stats(lines: Int, segments: Int, align: Boolean, wordsPerMinute: Double) = AiStats(
        audioSec = ResampleFrac(Rates.MIX, Rates.SPEECH).outputLength(30 * Rates.MIX).toDouble() / Rates.SPEECH,
        vocalShare = 24.0 / 30,
        wordsPerMinute = wordsPerMinute,
        chunks = 1,
        transcribedSec = 26.0,
        segments = segments,
        lines = lines,
        // The transcribe stage also times its one chunk: two more clock reads inside it.
        stageMs = listOfNotNull(AiStage.DECODE, AiStage.SEPARATE, AiStage.GATE, AiStage.TRANSCRIBE, AiStage.ALIGN.takeIf { align })
            .associateWith { if (it == AiStage.TRANSCRIBE) 3_000L else 1_000L },
    )

    /**
     * [seconds] of a 220 Hz tone as the mix; the vocals are half the mix in [vocalSeconds] and 1% elsewhere, so exactly
     * those gate frames are vocal. Whisper hears the [segments] that start in the chunk it runs on; the aligner puts lyric
     * line k at [ctcTimes] k.
     */
    private class FakeStages(
        vocalSeconds: Iterable<Int>,
        private val segments: List<Segment>,
        private val ctcTimes: List<Double> = emptyList(),
        private val lyrics: List<String> = emptyList(),
        private val failDecode: Exception? = null,
        /** The model that does not load when the separation starts. */
        private val failLoad: File? = null,
        private val noNpu: Boolean = false,
        /** Thrown by the first Whisper window. */
        private val failTranscribe: Exception? = null,
        /** What the decoder finds wrong with the audio: too long, or in a form it cannot use. */
        private val unfit: UnfitAudioException? = null,
        seconds: Int = 30,
    ) : AiStages {
        val calls = mutableListOf<String>()
        val chunks = mutableListOf<VocalChunk>()
        var stem: FloatArray? = null
        var duringTranscribe: () -> Unit = {}

        private val vocal = vocalSeconds.toSet()
        private val mix = FloatArray(seconds * RATE) { (0.5 * sin(2 * PI * 220 * it / RATE)).toFloat() }

        /** The mix the last run opened; closed once its separation is done. */
        var opened: ArrayMix? = null

        override fun decode(file: File): MixSource {
            calls += "decode"
            failDecode?.let { throw it }
            return ArrayMix(mix, mix.copyOf(), piece = 10_000).also { opened = it }
        }

        override fun separateVocals(mix: MixSource, vocals: VocalSink, progress: Progress) {
            calls += "separate"
            if (noNpu) throw NpuUnavailableException(IllegalStateException("the QNN EP registered but offers no NPU device"))
            failLoad?.let { throw ModelLoadException(it, IllegalStateException("QNN graph finalize failed")) }
            unfit?.let { throw it }
            val l = FloatArray(7_000)
            val r = FloatArray(7_000)
            var at = 0
            while (true) {
                val n = mix.read(l, r, 0, l.size)
                if (n == 0) break
                for (i in 0 until n) {
                    val gain = if ((at + i) / RATE in vocal) 0.5f else 0.01f
                    l[i] *= gain
                    r[i] *= gain
                }
                vocals.accept(l, r, n)
                at += n
            }
        }

        override fun transcribe(stem: FloatArray, chunk: VocalChunk): List<Segment> {
            calls += "transcribe"
            failTranscribe?.let { throw it }
            chunks += chunk
            this.stem = stem
            duringTranscribe()
            return segments.filter { it.start >= chunk.start && it.start < chunk.end }
        }

        override fun emissions(stem: FloatArray, progress: Progress): Emissions {
            calls += "emissions"
            return spelling((stem.size.toDouble() / Rates.SPEECH / Ctc.FRAME_SEC).toInt())
        }

        /** Confident emissions of the whole song that spell each lyric line from the frame of its time in [ctcTimes]. */
        private fun spelling(frames: Int): Emissions {
            val labels = IntArray(frames)
            val target = Ctc.encode(lyrics)
            val firsts = target.firstTokenOfLine.map { checkNotNull(it) }
            firsts.forEachIndexed { k, first ->
                val end = firsts.getOrNull(k + 1)?.minus(1) ?: target.tokens.size // the next line's separator sits at `end`
                var frame = Math.rint(ctcTimes[k] / Ctc.FRAME_SEC).toInt()
                for (i in first until end) {
                    if (i > first && target.tokens[i] == target.tokens[i - 1]) frame++ // a blank between repeats
                    labels[frame++] = target.tokens[i]
                }
                if (end < target.tokens.size) labels[frame + 1] = Ctc.SEPARATOR
            }
            return Emissions.of(labels.map { label -> DoubleArray(Ctc.CLASSES) { c -> if (c == label) ln(0.97) else ln(0.03 / 31) } })
        }

        private companion object {
            const val RATE = Rates.MIX
        }
    }
}
