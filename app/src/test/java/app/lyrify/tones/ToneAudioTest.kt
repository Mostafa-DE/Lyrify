package app.lyrify.tones

import app.lyrify.ai.ArrayMix
import app.lyrify.ai.MixSource
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ToneAudioTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val second = ToneAudio.RATE

    // The fade envelope

    @Test
    fun `without fades the whole window plays at full volume, nothing outside it`() {
        val fade = FadeEnvelope(totalFrames = 100, fadeInFrames = 0, fadeOutFrames = 0)
        assertEquals(1f, fade.gain(0), 0f)
        assertEquals(1f, fade.gain(50), 0f)
        assertEquals(1f, fade.gain(99), 0f)
        assertEquals(0f, fade.gain(-1), 0f)
        assertEquals(0f, fade.gain(100), 0f)
    }

    @Test
    fun `a fade in rises from silence to full volume in a straight line`() {
        val fade = FadeEnvelope(totalFrames = 1_000, fadeInFrames = 100, fadeOutFrames = 0)
        assertEquals(0f, fade.gain(0), 0f)
        assertEquals(0.25f, fade.gain(25), 1e-6f)
        assertEquals(0.5f, fade.gain(50), 1e-6f)
        assertEquals(1f, fade.gain(100), 0f)
        assertEquals(1f, fade.gain(999), 0f)
    }

    @Test
    fun `a fade out falls to silence on the last frame`() {
        val fade = FadeEnvelope(totalFrames = 1_000, fadeInFrames = 0, fadeOutFrames = 100)
        assertEquals(1f, fade.gain(899), 0f)
        assertEquals(0.5f, fade.gain(949), 1e-6f)
        assertEquals(0f, fade.gain(999), 0f)
    }

    @Test
    fun `fades longer than a short window meet in the middle, never louder than either`() {
        val fade = FadeEnvelope(totalFrames = 101, fadeInFrames = 100, fadeOutFrames = 100)
        assertEquals(0.5f, fade.gain(50), 1e-6f)
        (0 until 101L).forEach { f -> assertTrue("$f", fade.gain(f) <= minOf(f / 100f, (100 - f) / 100f) + 1e-6f) }
    }

    @Test
    fun `fades are given in milliseconds of the 44_1 kHz window`() {
        val fade = FadeEnvelope.of(ToneWindow(10_000, 30_000), fadeInMs = 1_500, fadeOutMs = 0)
        assertEquals(30L * second, fade.totalFrames)
        assertEquals(1_500L * second / 1_000, fade.fadeInFrames)
        assertEquals(0L, fade.fadeOutFrames)
    }

    // 16-bit PCM

    @Test
    fun `float samples become 16-bit, clipped`() {
        assertEquals(0.toShort(), ToneAudio.pcm16(0f))
        assertEquals(32767.toShort(), ToneAudio.pcm16(1f))
        assertEquals((-32767).toShort(), ToneAudio.pcm16(-1f))
        assertEquals(32767.toShort(), ToneAudio.pcm16(1.5f))
        assertEquals((-32768).toShort(), ToneAudio.pcm16(-1.5f))
        assertEquals(8192.toShort(), ToneAudio.pcm16(0.25f))
    }

    // The render pipeline: decode the window, fade it, encode it

    /** Three seconds whose left sample is its frame number / 1e6 and right one its negative: where a frame came from shows. */
    private fun numbered(seconds: Int = 3, piece: Int = 1_000): ArrayMix {
        val left = FloatArray(seconds * second) { it / 1e6f }
        return ArrayMix(left, FloatArray(left.size) { -left[it] }, piece)
    }

    @Test
    fun `the window's frames are encoded in order, in pieces, then the encoder is finished`() = runBlocking {
        val encoder = FakeEncoder()
        val frames = ToneAudio.render(numbered(), ToneWindow(1_000, 1_000), FadeEnvelope.of(ToneWindow(1_000, 1_000), 0, 0), encoder) {}

        assertEquals(second.toLong(), frames)
        assertEquals("finish", encoder.calls.last())
        assertEquals(1, encoder.calls.count { it == "finish" })
        assertTrue(encoder.pieces.all { it in 1..ToneAudio.CHUNK_FRAMES })
        assertEquals(second, encoder.pieces.sum())
        // The first frame encoded is the song's frame 44100, the last 88199, both channels.
        assertEquals(ToneAudio.pcm16(second / 1e6f), encoder.samples[0])
        assertEquals(ToneAudio.pcm16(-second / 1e6f), encoder.samples[1])
        assertEquals(ToneAudio.pcm16((2 * second - 1) / 1e6f), encoder.samples[2 * second - 2])
    }

    @Test
    fun `the fades shape the PCM that is encoded`() = runBlocking {
        val loud = FloatArray(3 * second) { 0.5f }
        val window = ToneWindow(500, 2_000)
        val encoder = FakeEncoder()
        ToneAudio.render(ArrayMix(loud, piece = 777), window, FadeEnvelope.of(window, fadeInMs = 500, fadeOutMs = 500), encoder) {}

        val left = encoder.samples.filterIndexed { i, _ -> i % 2 == 0 }
        assertEquals(2 * second, left.size)
        assertEquals(0.toShort(), left.first())
        assertEquals(0.toShort(), left.last())
        assertEquals(ToneAudio.pcm16(0.25f), left[second / 4])
        assertEquals(ToneAudio.pcm16(0.5f), left[second])
        // Rising through the fade in.
        assertTrue((1 until second / 2).all { left[it] >= left[it - 1] })
    }

    @Test
    fun `a song that ends inside the window gives what it has`() = runBlocking {
        val encoder = FakeEncoder()
        val frames = ToneAudio.render(numbered(seconds = 2), ToneWindow(1_500, 1_000), FadeEnvelope(second.toLong(), 0, 0), encoder) {}
        assertEquals(second / 2L, frames)
        assertEquals("finish", encoder.calls.last())
    }

    @Test
    fun `a song that ends before the window is a song that cannot be read`() = runBlocking {
        val encoder = FakeEncoder()
        try {
            ToneAudio.render(numbered(seconds = 1), ToneWindow(2_000, 1_000), FadeEnvelope(second.toLong(), 0, 0), encoder) {}
            fail("expected ToneSourceException")
        } catch (_: ToneSourceException) {
        }
        assertFalse(encoder.calls.contains("finish"))
    }

    @Test
    fun `a decoder failure is the song's, an encoder failure is not`() = runBlocking {
        val broken = object : MixSource {
            override val expectedFrames: Int = 0

            override fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int = throw IllegalStateException("codec gone")
        }
        try {
            ToneAudio.render(broken, ToneWindow(0, 1_000), FadeEnvelope(second.toLong(), 0, 0), FakeEncoder()) {}
            fail("expected ToneSourceException")
        } catch (e: ToneSourceException) {
            assertTrue(e.cause is IllegalStateException)
        }
        try {
            ToneAudio.render(numbered(), ToneWindow(0, 1_000), FadeEnvelope(second.toLong(), 0, 0), FakeEncoder(failAt = 1)) {}
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun `progress rises to 1 over the skipped part and the window`() = runBlocking {
        val seen = mutableListOf<Float>()
        ToneAudio.render(numbered(), ToneWindow(1_000, 1_000), FadeEnvelope(second.toLong(), 0, 0), FakeEncoder()) { seen += it }
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
        assertEquals(1f, seen.last(), 0f)
        // Reported per whole percent at most, not per piece.
        assertTrue(seen.size <= 101)
    }

    @Test
    fun `cancelling stops between pieces and never finishes the file`() = runBlocking {
        val encoder = FakeEncoder()
        lateinit var job: Job
        encoder.onEncode = { if (it == 2) job.cancel() }
        job = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            ToneAudio.render(numbered(), ToneWindow(0, 2_000), FadeEnvelope(2L * second, 0, 0), encoder) {}
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(2, encoder.pieces.size)
        assertFalse(encoder.calls.contains("finish"))
    }

    // Waveform peaks

    @Test
    fun `each point is the loudest sample of its frames on either channel, the last partial point included`() {
        val left = floatArrayOf(0.1f, -0.6f, 0.2f, 0f, 0f, 0.3f, 0f, 0f, 0f, 0.05f, -0.2f)
        val right = floatArrayOf(0f, 0f, 0.7f, 0f, 0f, 0f, 0f, -0.1f, 0f, 0f, 0f)
        assertArrayEquals(floatArrayOf(0.7f, 0.3f, 0.2f), Peaks.of(left, right, framesPerPoint = 5), 0f)
    }

    @Test
    fun `peaks gathered in pieces equal peaks of the whole`() {
        val left = FloatArray(1_003) { kotlin.math.sin(it / 7.0).toFloat() * (it % 13) / 13f }
        val right = FloatArray(left.size) { -left[it] / 2 }
        val meter = PeakMeter(framesPerPoint = 50)
        var at = 0
        listOf(1, 49, 50, 333, 570).forEach { n ->
            meter.add(left.copyOfRange(at, at + n), right.copyOfRange(at, at + n), n)
            at += n
        }
        assertArrayEquals(Peaks.of(left, right, 50), meter.peaks(), 0f)
        assertArrayEquals(FloatArray(0), PeakMeter(50).peaks(), 0f)
    }

    @Test
    fun `a song gets about two points a second`() = runBlocking {
        val peaks = Peaks.read(ArrayMix(FloatArray(3 * second) { 0.4f }, piece = 4_000)) {}
        assertEquals(3 * Peaks.POINTS_PER_SECOND, peaks.size)
        assertTrue(peaks.all { it == 0.4f })
    }

    @Test
    fun `peaks stop at the frames asked for`() = runBlocking {
        val peaks = Peaks.read(ArrayMix(FloatArray(3 * second) { 0.4f }, piece = 4_000), maxFrames = 2L * second) {}
        assertEquals(2 * Peaks.POINTS_PER_SECOND, peaks.size)
    }

    // The preview

    @Test
    fun `a preview's volume follows the saved tone's fades`() {
        val window = ToneWindow(72_000, 30_000)
        assertEquals(0f, previewGain(0, window, 1_500, 1_500), 0f)
        assertEquals(0.5f, previewGain(750, window, 1_500, 1_500), 1e-3f)
        assertEquals(1f, previewGain(15_000, window, 1_500, 1_500), 0f)
        assertEquals(0f, previewGain(30_000, window, 1_500, 1_500), 1e-3f)
        assertEquals(1f, previewGain(0, window, 0, 0), 0f)
    }

    // The peaks cache, app-private, one file per song

    @Test
    fun `cached peaks read back to within a 255th`() {
        val song = folder.newFile("Paper Lanterns.mp3").apply { writeBytes(ByteArray(10)) }
        val cache = PeaksCache(folder.newFolder("peaks"))
        val peaks = floatArrayOf(0f, 0.1f, 0.5f, 0.999f, 1f, 1.7f)
        cache.write(song, peaks)
        val back = cache.read(song)
        assertArrayEquals(floatArrayOf(0f, 0.1f, 0.5f, 0.999f, 1f, 1f), back, 1 / 255f)
    }

    @Test
    fun `a song changed since, or a damaged cache file, is computed again`() {
        val dir = folder.newFolder("peaks")
        val song = folder.newFile("Glass Harbor.mp3").apply { writeBytes(ByteArray(10)) }
        val cache = PeaksCache(dir)
        cache.write(song, floatArrayOf(0.5f, 0.5f))

        song.writeBytes(ByteArray(11))
        assertNull(cache.read(song))

        cache.write(song, floatArrayOf(0.5f, 0.5f))
        dir.listFiles().orEmpty().forEach { it.writeBytes(byteArrayOf(1, 2)) }
        assertNull(cache.read(song))
    }

    @Test
    fun `cache file names say nothing about the song`() {
        val dir = folder.newFolder("peaks")
        val song = folder.newFile("Low Orbit.mp3").apply { writeBytes(ByteArray(10)) }
        PeaksCache(dir).write(song, floatArrayOf(0.5f))
        val names = dir.listFiles().orEmpty().map(File::getName)
        assertEquals(1, names.size)
        assertFalse(names.single().contains("Orbit"))
    }

    @Test
    fun `the cache keeps a bounded number of songs, the one just written always`() {
        val dir = folder.newFolder("peaks")
        val cache = PeaksCache(dir, maxFiles = 3)
        val songs = (1..5).map { n -> folder.newFile("Song $n.mp3").apply { writeBytes(ByteArray(n)) } }
        songs.forEach { cache.write(it, floatArrayOf(0.5f)) }
        assertEquals(3, dir.listFiles().orEmpty().size)
        assertArrayEquals(floatArrayOf(0.5f), cache.read(songs.last()), 1 / 255f)
    }
}
