package app.lyrify.tones

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import app.lyrify.ai.DecodeDeadline
import app.lyrify.ai.MixSource
import app.lyrify.ai.Rates
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The fades of a window of [totalFrames]: a straight line from silence on the first frame to full volume after
 * [fadeInFrames], and from full volume to silence on the last frame over [fadeOutFrames]. Fades longer than a short
 * window meet in the middle (the quieter of the two wins). Pure: the gain of any frame, 0 outside the window.
 */
data class FadeEnvelope(val totalFrames: Long, val fadeInFrames: Long, val fadeOutFrames: Long) {
    fun gain(frame: Long): Float {
        if (frame < 0 || frame >= totalFrames) return 0f
        val fadeIn = if (fadeInFrames > 0) frame.toDouble() / fadeInFrames else 1.0
        val fadeOut = if (fadeOutFrames > 0) (totalFrames - 1 - frame).toDouble() / fadeOutFrames else 1.0
        return minOf(1.0, fadeIn, fadeOut).toFloat()
    }

    companion object {
        /** The fades of [window], given in milliseconds, at 44.1 kHz. */
        fun of(window: ToneWindow, fadeInMs: Long, fadeOutMs: Long): FadeEnvelope =
            FadeEnvelope(ToneAudio.frames(window.lengthMs), ToneAudio.frames(fadeInMs), ToneAudio.frames(fadeOutMs))
    }
}

/**
 * Where a tone's 16-bit PCM goes: interleaved stereo at [ToneAudio.RATE], in order, then [finish]. The AAC file in the
 * app ([AacToneEncoder]), a fake in tests. [close] without [finish] abandons the output.
 */
interface ToneEncoder : AutoCloseable {
    /** [frames] stereo frames of [pcm] (left, right, left...). */
    fun encode(pcm: ShortArray, frames: Int)

    /** The input has ended: the output is complete. */
    fun finish()

    override fun close()
}

/** The song could not be read for the tone (no audio, a decoder failure, less audio than its length says). Numbers only. */
class ToneSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A tone's audio: the song's window decoded, faded and handed to an encoder. */
object ToneAudio {
    /** The decoder's output rate ([MixSource]: stereo at 44.1 kHz), and the tone's. */
    const val RATE: Int = Rates.MIX

    /** Frames handed to the encoder at once: about 93 ms. Cancellation is checked between pieces. */
    const val CHUNK_FRAMES: Int = 4_096

    fun frames(ms: Long): Long = ms * RATE / 1_000

    /** A float sample (full scale is 1) as 16-bit PCM, clipped. */
    fun pcm16(sample: Float): Short = (sample * 32_767f).roundToInt().coerceIn(-32_768, 32_767).toShort()

    /**
     * Reads [source] from its start, skips to [window], and hands the window's frames with [fade] to [encoder] in
     * pieces of at most [CHUNK_FRAMES], then finishes it. [onProgress] hears the share done (skipped and window frames
     * alike) at each new whole percent, ending with 1. Returns the frames encoded: fewer than the window's when the
     * song ends inside it. Cancellable between pieces; the encoder is then not finished. A song that fails to decode or
     * ends before the window throws [ToneSourceException]; an encoder failure is thrown as it is. Never closes either.
     */
    suspend fun render(source: MixSource, window: ToneWindow, fade: FadeEnvelope, encoder: ToneEncoder, onProgress: (Float) -> Unit): Long {
        val skip = frames(window.startMs)
        val total = frames(window.lengthMs)
        val left = FloatArray(CHUNK_FRAMES)
        val right = FloatArray(CHUNK_FRAMES)
        val pcm = ShortArray(CHUNK_FRAMES * 2)
        val progress = Percent(skip + total, onProgress)
        var skipped = 0L
        while (skipped < skip) {
            currentCoroutineContext().ensureActive()
            val n = readSong(source, left, right, minOf(CHUNK_FRAMES.toLong(), skip - skipped).toInt())
            if (n == 0) throw ToneSourceException("the song ended at frame $skipped, before the window at $skip")
            skipped += n
            progress.at(skipped)
        }
        var written = 0L
        while (written < total) {
            currentCoroutineContext().ensureActive()
            val n = readSong(source, left, right, minOf(CHUNK_FRAMES.toLong(), total - written).toInt())
            if (n == 0) break
            for (i in 0 until n) {
                val gain = fade.gain(written + i)
                pcm[2 * i] = pcm16(left[i] * gain)
                pcm[2 * i + 1] = pcm16(right[i] * gain)
            }
            encoder.encode(pcm, n)
            written += n
            progress.at(skip + written)
        }
        if (written == 0L) throw ToneSourceException("no audio in the window")
        encoder.finish()
        progress.done()
        return written
    }

    private fun readSong(source: MixSource, left: FloatArray, right: FloatArray, count: Int): Int = try {
        source.read(left, right, 0, count)
    } catch (e: Exception) {
        // MixSource.read is blocking, not suspending: nothing here is a cancellation.
        throw ToneSourceException("the song failed to decode: ${e.javaClass.simpleName}", e)
    }
}

/** Reports a share of [total] at each new whole percent, and 1 once [done]. */
private class Percent(private val total: Long, private val report: (Float) -> Unit) {
    private var last = 0

    fun at(done: Long) {
        if (total <= 0) return
        val percent = (done * 100 / total).toInt().coerceIn(0, 100)
        if (percent > last) {
            last = percent
            report(percent / 100f)
        }
    }

    fun done() {
        if (last < 100) {
            last = 100
            report(1f)
        }
    }
}

/**
 * The waveform of a song: the loudest sample of either channel over each [framesPerPoint] frames, gathered as the
 * audio is read ([add]); the last point may cover fewer frames.
 */
class PeakMeter(private val framesPerPoint: Int) {
    private val points = ArrayList<Float>()
    private var current = 0f
    private var inPoint = 0

    init {
        require(framesPerPoint > 0)
    }

    fun add(left: FloatArray, right: FloatArray, count: Int) {
        for (i in 0 until count) {
            current = maxOf(current, abs(left[i]), abs(right[i]))
            if (++inPoint == framesPerPoint) {
                points += current
                current = 0f
                inPoint = 0
            }
        }
    }

    fun peaks(): FloatArray {
        val out = FloatArray(points.size + if (inPoint > 0) 1 else 0)
        points.forEachIndexed { i, v -> out[i] = v }
        if (inPoint > 0) out[points.size] = current
        return out
    }
}

/** Waveform peaks for the editor: about [POINTS_PER_SECOND] points a second of the whole song. */
object Peaks {
    const val POINTS_PER_SECOND: Int = 2
    const val FRAMES_PER_POINT: Int = ToneAudio.RATE / POINTS_PER_SECOND

    /** The peaks of a song held whole. Pure. */
    fun of(left: FloatArray, right: FloatArray, framesPerPoint: Int = FRAMES_PER_POINT): FloatArray =
        PeakMeter(framesPerPoint).apply { add(left, right, left.size) }.peaks()

    /**
     * The peaks of [source], read to its end or to [maxFrames]. [onProgress] hears the share read when the source says
     * how long it is. Cancellable between pieces; off the main thread (it decodes). Never closes [source].
     */
    suspend fun read(source: MixSource, maxFrames: Long = Long.MAX_VALUE, onProgress: (Float) -> Unit): FloatArray {
        val meter = PeakMeter(FRAMES_PER_POINT)
        val left = FloatArray(ToneAudio.CHUNK_FRAMES)
        val right = FloatArray(ToneAudio.CHUNK_FRAMES)
        val progress = Percent(minOf(source.expectedFrames.toLong(), maxFrames), onProgress)
        var read = 0L
        while (read < maxFrames) {
            currentCoroutineContext().ensureActive()
            val n = source.read(left, right, 0, minOf(ToneAudio.CHUNK_FRAMES.toLong(), maxFrames - read).toInt())
            if (n == 0) break
            meter.add(left, right, n)
            read += n
            progress.at(read)
        }
        progress.done()
        return meter.peaks()
    }
}

/**
 * Waveform peaks kept in app-private storage ([dir], under the cache directory in the app), one small file per song
 * named by a hash of its path, size and modification time: a song changed since is a miss, and no file name says which
 * song it is. Values are kept to a 255th (one byte each). At most [maxFiles] files: the oldest go, never the one just
 * written. A file that cannot be read is a miss; a failed write only means computing again next time.
 */
class PeaksCache(private val dir: File, private val maxFiles: Int = MAX_FILES) {
    fun read(song: File): FloatArray? {
        val file = File(dir, key(song))
        val bytes = try {
            if (!file.isFile) return null
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        if (bytes.size < HEADER_BYTES) return null
        val header = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
        if (header.int != MAGIC) return null
        val count = header.int
        if (count < 0 || bytes.size != HEADER_BYTES + count) return null
        return FloatArray(count) { (bytes[HEADER_BYTES + it].toInt() and 0xff) / 255f }
    }

    /** Throws an IOException when the file cannot be written; the caller just goes without the cache. */
    fun write(song: File, peaks: FloatArray) {
        dir.mkdirs()
        val bytes = ByteBuffer.allocate(HEADER_BYTES + peaks.size).order(ByteOrder.BIG_ENDIAN)
        bytes.putInt(MAGIC).putInt(peaks.size)
        peaks.forEach { bytes.put((it.coerceIn(0f, 1f) * 255f).roundToInt().toByte()) }
        val file = File(dir, key(song))
        val temp = File(dir, file.name + TEMP_SUFFIX)
        Files.write(temp.toPath(), bytes.array())
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        dir.listFiles().orEmpty()
            .filter { it != file && it.name.endsWith(SUFFIX) }
            .sortedByDescending { it.lastModified() }
            .drop(maxFiles - 1)
            .forEach { it.delete() }
    }

    private fun key(song: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${song.path}\u0000${song.length()}\u0000${song.lastModified()}".toByteArray())
        return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } + SUFFIX
    }

    companion object {
        /** About 2 kB each for a 15-minute song. */
        const val MAX_FILES: Int = 300
        private const val MAGIC: Int = 0x4c504b31 // "LPK1"
        private const val HEADER_BYTES: Int = 8
        private const val SUFFIX: String = ".peaks"
        private const val TEMP_SUFFIX: String = ".tmp"
    }
}

/**
 * A tone file: AAC-LC at [BIT_RATE], 44.1 kHz stereo, in an MPEG-4 (`.m4a`) container on [fd], with MediaCodec in
 * synchronous mode and MediaMuxer. The muxer track is added from the encoder's output format (it carries the codec
 * specific data), so codec-config buffers are not written as samples (MediaMuxer reference, addTrack). Presentation
 * times count the frames queued. [DecodeDeadline] ends an encoder that stops moving. [fd] stays the caller's to close.
 */
class AacToneEncoder(fd: FileDescriptor) : ToneEncoder {
    private val muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private val deadline = DecodeDeadline(SystemClock::uptimeMillis)
    private var track = -1
    private var queued = 0L
    private var muxing = false
    private var outputDone = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, ToneAudio.RATE, CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        } catch (e: Exception) {
            muxer.release()
            throw e
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            try {
                codec.release()
            } finally {
                muxer.release()
            }
            throw e
        }
    }

    override fun encode(pcm: ShortArray, frames: Int) {
        deadline.resume()
        try {
            var at = 0
            while (at < frames) {
                deadline.verify()
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    deadline.progressed()
                    val buffer = requireNotNull(codec.getInputBuffer(index)) { "no input buffer $index" }
                    buffer.clear()
                    val n = minOf(frames - at, buffer.remaining() / BYTES_PER_FRAME)
                    check(n > 0) { "input buffer of ${buffer.remaining()} bytes" }
                    buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, at * CHANNELS, n * CHANNELS)
                    codec.queueInputBuffer(index, 0, n * BYTES_PER_FRAME, presentationUs(queued), 0)
                    queued += n
                    at += n
                }
                drain(untilEnd = false)
            }
        } finally {
            deadline.pause()
        }
    }

    override fun finish() {
        deadline.resume()
        try {
            while (true) {
                deadline.verify()
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    deadline.progressed()
                    codec.queueInputBuffer(index, 0, 0, presentationUs(queued), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(untilEnd = false)
            }
            while (!outputDone) {
                deadline.verify()
                drain(untilEnd = true)
            }
            check(muxing) { "the encoder gave no output format" }
            muxer.stop()
            muxing = false
        } finally {
            deadline.pause()
        }
    }

    /** Releases the codec and the muxer whatever happened before, even when stopping the muxer throws. */
    override fun close() {
        try {
            codec.release()
        } finally {
            try {
                // Abandoned mid-way (or a stop that threw): release() alone would call stop(), which throws for a file
                // with no samples and then skips the native release. The half-made file is deleted by the caller.
                if (muxing) {
                    muxer.stop()
                    muxing = false
                }
            } catch (_: RuntimeException) {
            } finally {
                muxer.release()
            }
        }
    }

    /** Takes the encoder's ready output into the muxer; with [untilEnd], waits for at least one buffer. */
    private fun drain(untilEnd: Boolean) {
        while (!outputDone) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    deadline.progressed()
                    check(track < 0) { "the output format changed twice" }
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    deadline.progressed()
                    val buffer = requireNotNull(codec.getOutputBuffer(index)) { "no output buffer $index" }
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0) {
                        check(muxing) { "encoded audio before the output format" }
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    if (untilEnd) return
                }
                // INFO_TRY_AGAIN_LATER: nothing ready yet.
                else -> return
            }
        }
    }

    private fun presentationUs(frames: Long): Long = frames * 1_000_000L / ToneAudio.RATE

    companion object {
        const val BIT_RATE: Int = 192_000
        private const val CHANNELS: Int = 2
        private const val BYTES_PER_FRAME: Int = CHANNELS * 2
        private const val TIMEOUT_US: Long = 10_000
    }
}
