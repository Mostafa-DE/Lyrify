package app.lyrify.ai

import android.media.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** Resampling to the mix rate, whole and streamed, the channel mean and frame RMS (the CPU spike's tests, plus Lyrify's); the decoder's output and limits. */
class AudioTest {
    private fun sine(freq: Double, rate: Int, seconds: Double, amplitude: Double = 0.8): FloatArray =
        FloatArray((rate * seconds).toInt()) { i -> (amplitude * sin(2 * PI * freq * i / rate)).toFloat() }

    /** Largest difference from the ideal sine at the output rate, away from the zero-padded edges. */
    private fun maxErrorAgainstSine(out: FloatArray, freq: Double, rate: Int, edge: Int, amplitude: Double = 0.8): Double {
        var worst = 0.0
        for (i in edge until out.size - edge) {
            worst = maxOf(worst, abs(out[i] - amplitude * sin(2 * PI * freq * i / rate)))
        }
        return worst
    }

    private fun rms(x: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += x[i].toDouble() * x[i]
        return sqrt(sum / (to - from))
    }

    @Test
    fun `48 kHz sine becomes the same sine at 44_1 kHz`() {
        val out = Resampler(48_000, 44_100).process(sine(1_000.0, 48_000, 1.0))
        assertEquals(44_100, out.size)
        val error = maxErrorAgainstSine(out, 1_000.0, 44_100, edge = 200)
        assertTrue("max error $error", error < 1e-3)
    }

    @Test
    fun `44_1 kHz sine becomes the same sine at 16 kHz`() {
        val out = Resampler(44_100, 16_000).process(sine(440.0, 44_100, 2.0))
        assertEquals(32_000, out.size)
        val error = maxErrorAgainstSine(out, 440.0, 16_000, edge = 200)
        assertTrue("max error $error", error < 1e-3)
    }

    @Test
    fun `16 kHz sine upsampled to 44_1 kHz`() {
        val out = Resampler(16_000, 44_100).process(sine(1_000.0, 16_000, 1.0))
        assertEquals(44_100, out.size)
        val error = maxErrorAgainstSine(out, 1_000.0, 44_100, edge = 400)
        assertTrue("max error $error", error < 1e-3)
    }

    @Test
    fun `a tone above the new Nyquist frequency is filtered out, not aliased`() {
        val out = Resampler(44_100, 16_000).process(sine(10_000.0, 44_100, 1.0))
        val level = rms(out, 200, out.size - 200)
        assertTrue("alias level $level (input rms 0.57)", level < 1e-3)
    }

    @Test
    fun `a constant passes with unity gain`() {
        val out = Resampler(44_100, 16_000).process(FloatArray(44_100) { 0.5f })
        for (i in 200 until out.size - 200) assertEquals(0.5f, out[i], 1e-4f)
    }

    @Test
    fun `output covers the input duration, rounded up`() {
        val r = Resampler(44_100, 16_000)
        assertEquals(16_000, r.outputLength(44_100))
        assertEquals(1, r.outputLength(1))
        assertEquals(0, r.outputLength(0))
        assertEquals(4_800_000, r.outputLength(13_230_000)) // five minutes
    }

    @Test
    fun `same rate is an exact copy`() {
        val x = floatArrayOf(0.1f, -0.2f, 0.3f)
        val out = Resampler(44_100, 44_100).process(x)
        assertArrayEquals(x, out, 0f)
        assertTrue(out !== x)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rates must be positive`() {
        Resampler(0, 16_000)
    }

    // Streamed resampling: the same output as the whole-array resamplers, whatever the pieces

    /** [x] pushed in pieces of growing, uneven sizes, then finished. */
    private fun piecewise(x: FloatArray, push: (FloatArray, Int, Int, Floats) -> Unit, finish: (Floats) -> Unit): FloatArray {
        val out = Floats()
        var at = 0
        var piece = 1
        while (at < x.size) {
            val n = minOf(piece, x.size - at)
            push(x, at, n, out)
            at += n
            piece = piece * 2 + 3
        }
        finish(out)
        return out.data.copyOf(out.size)
    }

    @Test
    fun `the streamed polyphase resampler gives process's output to the bit`() {
        val rnd = java.util.Random(9)
        for ((from, to) in listOf(48_000 to 44_100, 22_050 to 44_100, 96_000 to 44_100, 44_100 to 44_100)) {
            for (n in listOf(0, 1, 37, 48_000 + 17)) {
                val x = FloatArray(n) { (rnd.nextGaussian() * 0.3).toFloat() }
                val r = Resampler(from, to)
                val s = r.Stream()
                assertArrayEquals("$from -> $to, $n", r.process(x), piecewise(x, s::push, s::finish), 0f)
            }
        }
    }

    @Test
    fun `the streamed resample_frac gives julius's output to the bit, edges included`() {
        val rnd = java.util.Random(4)
        for (n in listOf(0, 1, 440, 441, 2_000, 44_100 * 2 + 3)) {
            val x = FloatArray(n) { (rnd.nextGaussian() * 0.3).toFloat() }
            val r = ResampleFrac(44_100, 16_000)
            val s = r.Stream()
            assertArrayEquals("$n samples", r.resample(x), piecewise(x, s::push, s::finish), 0f)
        }
    }

    @Test
    fun `the channel mean is numpy's float32 mean of two channels`() {
        assertEquals(0.5f, channelMean(1f, 0f), 0f)
        assertEquals(0.25f, channelMean(0f, 0.5f), 0f)
        assertEquals(0f, channelMean(-1f, 1f), 0f)
    }

    @Test
    fun `frame RMS uses whole frames only, rounded to 6 decimals for the gate`() {
        val x = FloatArray(10) { if (it < 4) 0.5f else -0.25f } // frames of 4: [0.5 x4], [-0.25 x4], 2 dropped
        assertArrayEquals(doubleArrayOf(0.5, 0.25), frameRms(x, 4, rounded = true), 0.0)
        val y = FloatArray(3) { 0.1234567f }
        assertEquals(0.123457, frameRms(y, 3, rounded = true)[0], 0.0)
        assertEquals(0.1234567, frameRms(y, 3, rounded = false)[0], 1e-7)
    }

    // The decoder's output to the mix (AudioDecoder.kt), without MediaCodec

    /** [frames] frames of 16-bit PCM, [channels] interleaved, each sample from [value], as a decoder buffer. */
    private fun pcm16(frames: Int, channels: Int = 1, value: (Int) -> Short = { 16_384 }): ByteBuffer {
        val bytes = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.nativeOrder())
        repeat(frames * channels) { bytes.putShort(value(it)) }
        return bytes.also { it.flip() }
    }

    /** Everything [pcm] has, read back. */
    private fun drain(pcm: PcmToMix): Array<FloatArray> {
        val l = FloatArray(pcm.available)
        val r = FloatArray(pcm.available)
        assertEquals(l.size, pcm.read(l, r, 0, l.size))
        return arrayOf(l, r)
    }

    @Test
    fun `16-bit stereo at 44_1 kHz becomes the mix as it is, s over 32768`() {
        val pcm = PcmToMix(44_100, 2, AudioFormat.ENCODING_PCM_16BIT)
        pcm.append(pcm16(3, 2) { i -> (i * 1000 - 3000).toShort() })
        val (l, r) = drain(pcm)
        assertArrayEquals(floatArrayOf(-3000 / 32768f, -1000 / 32768f, 1000 / 32768f), l, 0f)
        assertArrayEquals(floatArrayOf(-2000 / 32768f, 0f, 2000 / 32768f), r, 0f)
        assertEquals(0, pcm.read(l, r, 0, 3))
    }

    @Test
    fun `a mono file is repeated on both channels and extra channels are dropped`() {
        val mono = PcmToMix(44_100, 1, AudioFormat.ENCODING_PCM_16BIT)
        mono.append(pcm16(2) { i -> (i + 1).toShort() })
        val (l, r) = drain(mono)
        assertArrayEquals(l, r, 0f)
        assertArrayEquals(floatArrayOf(1 / 32768f, 2 / 32768f), l, 0f)
        val three = PcmToMix(44_100, 3, AudioFormat.ENCODING_PCM_16BIT)
        three.append(pcm16(1, 3) { i -> (i + 1).toShort() })
        val (a, b) = drain(three)
        assertArrayEquals(floatArrayOf(1 / 32768f), a, 0f)
        assertArrayEquals(floatArrayOf(2 / 32768f), b, 0f)
    }

    @Test
    fun `every encoding converts as the whole-file decoder did`() {
        fun one(encoding: Int, bytes: ByteArray): Float {
            val pcm = PcmToMix(44_100, 1, encoding)
            pcm.append(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN))
            return drain(pcm)[0][0]
        }
        val float = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).array()
        assertEquals(0.25f, one(AudioFormat.ENCODING_PCM_FLOAT, float), 0f)
        val int32 = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(Int.MIN_VALUE).array()
        assertEquals(-1f, one(AudioFormat.ENCODING_PCM_32BIT, int32), 0f)
        assertEquals(-8388608 / 8388608f, one(AudioFormat.ENCODING_PCM_24BIT_PACKED, byteArrayOf(0, 0, 0x80.toByte())), 0f)
        assertEquals(0x123456 / 8388608f, one(AudioFormat.ENCODING_PCM_24BIT_PACKED, byteArrayOf(0x56, 0x34, 0x12)), 0f)
        assertEquals(-1f, one(AudioFormat.ENCODING_PCM_8BIT, byteArrayOf(0)), 0f)
        assertEquals(127 / 128f, one(AudioFormat.ENCODING_PCM_8BIT, byteArrayOf(0xff.toByte())), 0f)
    }

    @Test
    fun `another rate is resampled to 44_1 kHz as it arrives, the same as resampling the whole file`() {
        val rnd = java.util.Random(2)
        val samples = ShortArray(2 * 4_801) { (rnd.nextGaussian() * 6_000).toInt().toShort() }
        val pcm = PcmToMix(48_000, 2, AudioFormat.ENCODING_PCM_16BIT)
        var frame = 0
        for (size in listOf(1, 1_000, 2_048, 1_752)) {
            pcm.append(pcm16(size, 2) { i -> samples[2 * frame + i] })
            frame += size
        }
        pcm.finish()
        val (l, r) = drain(pcm)
        val whole = Resampler(48_000, 44_100)
        assertArrayEquals(whole.process(FloatArray(4_801) { samples[2 * it] / 32768f }), l, 0f)
        assertArrayEquals(whole.process(FloatArray(4_801) { samples[2 * it + 1] / 32768f }), r, 0f)
        assertEquals(4_411, l.size) // 4801 x 44100 / 48000, rounded up
    }

    @Test
    fun `only what has not been read is held, read in pieces`() {
        val pcm = PcmToMix(44_100, 1, AudioFormat.ENCODING_PCM_16BIT)
        pcm.append(pcm16(10) { i -> i.toShort() })
        val l = FloatArray(10)
        val r = FloatArray(10)
        assertEquals(4, pcm.read(l, r, 0, 4))
        assertEquals(6, pcm.available)
        assertEquals(6, pcm.read(l, r, 4, 99))
        assertArrayEquals(FloatArray(10) { it / 32768f }, l, 0f)
    }

    @Test
    fun `audio past 20 minutes is too long for the AI, a verdict rather than a failure`() {
        val pcm = PcmToMix(44_100, 1, AudioFormat.ENCODING_PCM_16BIT)
        val minute = pcm16(44_100 * 60)
        repeat(20) {
            pcm.append(minute.duplicate().order(ByteOrder.nativeOrder()))
            assertEquals(44_100 * 60, drain(pcm)[0].size)
        }
        val e = assertThrows(AudioTooLongException::class.java) { pcm.append(pcm16(1)) }
        assertEquals("too long: over 20 minutes of audio", e.message)
    }

    @Test
    fun `the file's claimed length only estimates the frames, at most 20 minutes`() {
        assertEquals(88_200, PcmToMix.expectedFrames(2_000_000))
        assertEquals(20 * 60 * 44_100, PcmToMix.expectedFrames(Long.MAX_VALUE))
        assertEquals(0, PcmToMix.expectedFrames(-5))
    }

    @Test
    fun `audio the mix cannot be made from is unsupported, a verdict rather than a failure`() {
        // No rate or channels.
        assertThrows(UnsupportedAudioException::class.java) { PcmToMix(0, 2, AudioFormat.ENCODING_PCM_16BIT) }
        assertThrows(UnsupportedAudioException::class.java) { PcmToMix(44_100, 0, AudioFormat.ENCODING_PCM_16BIT) }
        // 44056 Hz to 44.1 kHz needs 11025 filter phases, more than the resampler builds (4096).
        val odd = assertThrows(UnsupportedAudioException::class.java) { PcmToMix(44_056, 2, AudioFormat.ENCODING_PCM_16BIT) }
        assertEquals(RejectReason.UNSUPPORTED_AUDIO, odd.reason)
        assertEquals("44056 Hz audio: 44056 -> 44100 needs 11025 filter phases (max 4096)", odd.message)
        // Common rates are fine.
        for (rate in listOf(8_000, 11_025, 16_000, 22_050, 32_000, 44_100, 48_000, 88_200, 96_000, 176_400, 192_000)) PcmToMix(rate, 2, AudioFormat.ENCODING_PCM_16BIT)
        // An encoding the conversion does not know.
        val pcm = PcmToMix(44_100, 1, AudioFormat.ENCODING_PCM_16BIT)
        pcm.encoding = AudioFormat.ENCODING_AC3
        assertThrows(UnsupportedAudioException::class.java) { pcm.append(pcm16(4)) }
        // Too long is the other verdict on the audio.
        assertEquals(RejectReason.TOO_LONG, AudioTooLongException("too long").reason)
    }

    @Test
    fun `the time the reader spends between reads is neither a stall nor decoding time`() {
        var now = 0L
        val deadline = DecodeDeadline { now }
        repeat(30) {
            deadline.resume()
            now += 15_000 // decoding, buffers moving
            deadline.progressed()
            deadline.verify()
            deadline.pause()
            now += 60_000 // the separation works on what it read
        }
        deadline.resume()
        deadline.verify() // 7.5 minutes of decoding in 37.5 minutes, no stall
        now += 150_000
        deadline.progressed()
        assertEquals("decoding took over 10 minutes", assertThrows(IllegalStateException::class.java) { deadline.verify() }.message)
    }

    @Test
    fun `a decode fails after 30 s without a buffer moving`() {
        var now = 5_000L
        val deadline = DecodeDeadline { now }
        now += 29_999
        deadline.verify()
        deadline.progressed()
        now += 29_999
        deadline.verify()
        now += 1
        assertEquals("the decoder made no progress for 30 s", assertThrows(IllegalStateException::class.java) { deadline.verify() }.message)
    }

    @Test
    fun `a decode that keeps moving still stops after 10 minutes`() {
        var now = 0L
        val deadline = DecodeDeadline { now }
        while (now < 10 * 60_000L - 1_000) {
            now += 1_000
            deadline.progressed()
            deadline.verify()
        }
        now += 1_000
        deadline.progressed()
        assertEquals("decoding took over 10 minutes", assertThrows(IllegalStateException::class.java) { deadline.verify() }.message)
    }
}
