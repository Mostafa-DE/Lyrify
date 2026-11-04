package app.lyrify.ai

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The mix as the separation reads it: planar stereo at 44.1 kHz, pulled a piece at a time, so a song is never held whole.
 * [expectedFrames] is what the file claims (0 when it does not say), for progress only.
 */
interface MixSource : AutoCloseable {
    val expectedFrames: Int

    /** Up to [count] frames into [left] and [right] from [offset]; 0 once the audio has ended. */
    fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int

    override fun close() {}
}

/**
 * The song's audio is not for the AI: a verdict on the song ([reason], recorded and not tried again by this version),
 * not a failure to retry. The message holds numbers only.
 */
sealed class UnfitAudioException(val reason: RejectReason, message: String) : Exception(message)

/** Longer than the AI takes ([PcmToMix.MAX_AUDIO_SEC]). */
class AudioTooLongException(message: String) : UnfitAudioException(RejectReason.TOO_LONG, message)

/** In a form the mix cannot be made from: no rate or channels, a PCM encoding not converted, a rate not resampled. */
class UnsupportedAudioException(message: String) : UnfitAudioException(RejectReason.UNSUPPORTED_AUDIO, message)

/**
 * The first audio track of a file (mp3, m4a/AAC, flac, ogg, wav...) as a [MixSource], decoded with MediaExtractor and
 * MediaCodec in synchronous mode as it is read: each [read] feeds and drains the codec until it has the frames asked for
 * or the stream ends. The file is opened read only; [close] releases the codec.
 *
 * Float output is requested with KEY_PCM_ENCODING = ENCODING_PCM_FLOAT at configure time; per the MediaCodec
 * reference it only applies if the output format confirms it, so 16-bit, 32-bit, 24-bit packed and 8-bit output are
 * converted too ([PcmToMix]). Rate and channel count come from the output format, which can differ from the
 * container's (HE-AAC doubles the rate).
 *
 * Nothing in the file is trusted for sizes or time: the claimed duration is only [expectedFrames], decoding stops past
 * [PcmToMix.MAX_AUDIO_SEC] of audio ([AudioTooLongException]), and [DecodeDeadline] ends a decoder that stops moving or
 * never signals end of stream; the time the reader spends between reads is not the decoder's.
 */
class MixDecoder(file: File) : MixSource {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val durationUs: Long
    private val info = MediaCodec.BufferInfo()
    private val deadline = DecodeDeadline(SystemClock::uptimeMillis)
    private var pcm: PcmToMix? = null
    private var inputDone = false
    private var outputDone = false

    init {
        try {
            extractor.setDataSource(pfd.fileDescriptor)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track in ${file.name}")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                configure(codec, format)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
        } catch (e: Exception) {
            extractor.release()
            pfd.close()
            throw e
        }
    }

    override val expectedFrames: Int get() = PcmToMix.expectedFrames(durationUs)

    override fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int {
        deadline.resume()
        try {
            while (!outputDone && (pcm?.available ?: 0) < count) step()
            val out = checkNotNull(pcm) { "the decoder produced no audio" }
            return out.read(left, right, offset, count)
        } finally {
            deadline.pause()
        }
    }

    override fun close() {
        try {
            codec.release()
        } finally {
            extractor.release()
            pfd.close()
        }
    }

    /** One turn of the codec: an input buffer queued if one is free, an output buffer taken if one is ready. */
    private fun step() {
        deadline.verify()
        if (!inputDone) {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                deadline.progressed()
                val buffer = requireNotNull(codec.getInputBuffer(inIndex))
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
        }
        val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
        if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            deadline.progressed()
            reformat(codec.outputFormat)
        } else if (outIndex >= 0) {
            deadline.progressed()
            if (info.size > 0) {
                val out = pcm ?: reformat(codec.getOutputFormat(outIndex))
                val buffer = requireNotNull(codec.getOutputBuffer(outIndex))
                buffer.position(info.offset)
                buffer.limit(info.offset + info.size)
                out.append(buffer.slice().order(ByteOrder.nativeOrder()))
            }
            codec.releaseOutputBuffer(outIndex, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                outputDone = true
                pcm?.finish()
            }
        }
    }

    private fun reformat(format: MediaFormat): PcmToMix {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        pcm?.let { out ->
            check(rate == out.sampleRate && channels == out.channelCount) {
                "the output format changed mid-stream: ${out.sampleRate} Hz x ${out.channelCount} -> $rate Hz x $channels"
            }
            out.encoding = encoding
            return out
        }
        return PcmToMix(rate, channels, encoding).also { pcm = it }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L

        fun configure(codec: MediaCodec, format: MediaFormat) {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            try {
                codec.configure(format, null, null, 0)
            } catch (e: Exception) {
                // A decoder that rejects the float request still decodes to its default 16-bit PCM.
                codec.reset()
                format.removeKey(MediaFormat.KEY_PCM_ENCODING)
                codec.configure(format, null, null, 0)
            }
        }
    }
}

/**
 * Decoder output to the mix: interleaved PCM of any encoding MediaCodec gives ([append]) to planar float stereo at
 * 44.1 kHz ([read]). A mono file is repeated on both channels (the separation spec's "mono files: copy the channel"),
 * only the first two of more channels are kept, and another rate is resampled as it arrives ([Resampler.Stream]); the
 * values are those the whole-file path made. Only what has not been read yet is held. Past [MAX_AUDIO_SEC] of audio
 * [append] throws [AudioTooLongException]; a format it cannot use (no rate or channels, an encoding it does not convert,
 * a rate whose ratio to 44.1 kHz needs more filter phases than [Resampler.MAX_PHASES], such as 44056 Hz) throws
 * [UnsupportedAudioException]: the same file would fail the same way every time.
 */
internal class PcmToMix(val sampleRate: Int, val channelCount: Int, var encoding: Int) {
    init {
        if (sampleRate <= 0 || channelCount <= 0) throw UnsupportedAudioException("unusable output format: $sampleRate Hz x $channelCount channels")
    }

    private val maxFrames: Long = MAX_AUDIO_SEC * sampleRate
    private var decoded = 0L
    private val streams = if (sampleRate == Rates.MIX) {
        null
    } else {
        val r = try {
            Resampler(sampleRate, Rates.MIX)
        } catch (e: IllegalArgumentException) {
            throw UnsupportedAudioException("$sampleRate Hz audio: ${e.message}")
        }
        arrayOf(r.Stream(), r.Stream())
    }
    private val source = arrayOf(Floats(), Floats())
    private val left = Floats()
    private val right = Floats()

    /** Frames ready to [read]. */
    val available: Int get() = left.size

    fun append(bytes: ByteBuffer) {
        val bytesPerSample = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_8BIT -> 1
            else -> throw UnsupportedAudioException("unsupported PCM encoding $encoding")
        }
        val count = bytes.remaining() / (bytesPerSample * channelCount)
        if (decoded + count > maxFrames) throw AudioTooLongException("too long: over ${MAX_AUDIO_SEC / 60} minutes of audio")
        val target = if (streams == null) arrayOf(left, right) else source
        for (i in 0 until count) {
            for (c in 0 until channelCount) {
                val v = when (encoding) {
                    AudioFormat.ENCODING_PCM_FLOAT -> bytes.getFloat()
                    AudioFormat.ENCODING_PCM_16BIT -> bytes.getShort() / 32768f
                    AudioFormat.ENCODING_PCM_32BIT -> bytes.getInt() / 2147483648f
                    // Little endian, sign-extended from the top byte.
                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> ((bytes.get().toInt() and 0xff) or ((bytes.get().toInt() and 0xff) shl 8) or (bytes.get().toInt() shl 16)) / 8388608f
                    else -> ((bytes.get().toInt() and 0xff) - 128) / 128f
                }
                if (c < 2) target[c].add(v)
                if (channelCount == 1) target[1].add(v)
            }
        }
        decoded += count
        resample(end = false)
    }

    /** The decoder signalled the end: the resampler's last samples. */
    fun finish() = resample(end = true)

    fun read(outLeft: FloatArray, outRight: FloatArray, offset: Int, count: Int): Int {
        val n = minOf(count, left.size)
        left.data.copyInto(outLeft, offset, 0, n)
        right.data.copyInto(outRight, offset, 0, n)
        left.drop(n)
        right.drop(n)
        return n
    }

    private fun resample(end: Boolean) {
        val streams = streams ?: return
        streams[0].push(source[0].data, 0, source[0].size, left)
        streams[1].push(source[1].data, 0, source[1].size, right)
        source[0].clear()
        source[1].clear()
        if (end) {
            streams[0].finish(left)
            streams[1].finish(right)
        }
    }

    companion object {
        /** Audio decoded at most: the library's longest songs are about 15 minutes. */
        const val MAX_AUDIO_SEC: Long = 20 * 60

        /** 44.1 kHz frames of a file that claims [durationUs] (any value a header holds), at most [MAX_AUDIO_SEC]. */
        fun expectedFrames(durationUs: Long): Int = (durationUs.coerceIn(0L, MAX_AUDIO_SEC * 1_000_000L) * Rates.MIX / 1_000_000L).toInt()
    }
}

/**
 * Ends a decode that hangs: MediaCodec has no timeout of its own, so a decoder that never signals end of stream would
 * keep [MixDecoder]'s loop going forever. [verify] throws when no input or output buffer moved ([progressed]) for
 * [STALL_MS], or when the decoder has worked for [MAX_MS]. Only the decoder's time counts: between [pause] and
 * [resume] the reader works (the separation), which is neither a stall nor decoding time. [now] is a monotonic clock in
 * milliseconds; on the phone `uptimeMillis`, which stops in deep sleep, so a sleeping phone is not a stall.
 */
internal class DecodeDeadline(private val now: () -> Long) {
    private var since = now()
    private var before = 0L
    private var lastProgress = since

    fun progressed() {
        lastProgress = now()
    }

    /** The reader asks for more: the clock runs again. */
    fun resume() {
        since = now()
        lastProgress = since
    }

    /** The reader has what it asked for. */
    fun pause() {
        before += now() - since
    }

    fun verify() {
        val t = now()
        check(t - lastProgress < STALL_MS) { "the decoder made no progress for ${STALL_MS / 1000} s" }
        check(before + t - since < MAX_MS) { "decoding took over ${MAX_MS / 60_000} minutes" }
    }

    companion object {
        const val STALL_MS: Long = 30_000

        /** The spike decoded a 4-minute song in 2.9 s on the phone (lyrify-bench HANDOFF.md, run 1). */
        const val MAX_MS: Long = 10 * 60_000
    }
}
