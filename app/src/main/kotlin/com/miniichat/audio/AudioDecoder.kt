package com.miniichat.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

/**
 * Decode any audio file Android can read (m4a, mp3, ogg, opus, flac, wav, amr, …)
 * into a 16 kHz mono PCM16 WAV file so it can be transcribed and replayed.
 */
object AudioDecoder {

    fun decodeToWav(ctx: Context, uri: Uri, out: File, onProgress: (Float) -> Unit = {}): Long {
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); throw IllegalArgumentException("No audio track found") }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        var srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmFloat = false
        val resampler = LinearResampler(srcRate, WavIO.SAMPLE_RATE)
        var dataBytes = 0L

        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            WavIO.writeHeader(raf, 0)
            raf.seek(WavIO.HEADER_SIZE.toLong())
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            if (durationUs > 0) onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        srcChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        resampler.reset(srcRate)
                    }
                    outIdx >= 0 -> {
                        val buf = codec.getOutputBuffer(outIdx)!!.order(ByteOrder.LITTLE_ENDIAN)
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        val frames: Int
                        val mono: FloatArray
                        if (pcmFloat) {
                            val fb = buf.asFloatBuffer()
                            frames = fb.remaining() / srcChannels
                            mono = FloatArray(frames) { i ->
                                var a = 0f; for (c in 0 until srcChannels) a += fb.get(i * srcChannels + c); a / srcChannels
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            frames = sb.remaining() / srcChannels
                            mono = FloatArray(frames) { i ->
                                var a = 0f; for (c in 0 until srcChannels) a += sb.get(i * srcChannels + c) / 32768f; a / srcChannels
                            }
                        }
                        val res = resampler.process(mono)
                        if (res.isNotEmpty()) {
                            val bytes = ByteArray(res.size * 2)
                            for (i in res.indices) {
                                val s = (res[i].coerceIn(-1f, 1f) * 32767f).toInt()
                                bytes[i * 2] = (s and 0xff).toByte()
                                bytes[i * 2 + 1] = ((s shr 8) and 0xff).toByte()
                            }
                            raf.write(bytes)
                            dataBytes += bytes.size
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            WavIO.writeHeader(raf, dataBytes)
        }
        runCatching { codec.stop() }
        codec.release()
        extractor.release()
        onProgress(1f)
        return dataBytes * 1000 / (WavIO.SAMPLE_RATE * 2)
    }

    /** Streaming linear-interpolation resampler (good enough for speech → whisper). */
    private class LinearResampler(private var srcRate: Int, private val dstRate: Int) {
        private var pos = 0.0           // fractional read position relative to current buffer
        private var last = 0f
        fun reset(rate: Int) { srcRate = rate; pos = 0.0 }
        fun process(input: FloatArray): FloatArray {
            if (srcRate == dstRate) return input
            val step = srcRate.toDouble() / dstRate
            val out = ArrayList<Float>((input.size / step).toInt() + 2)
            // index -1 refers to `last` (final sample of previous buffer)
            while (pos < input.size - 1 + 1e-9) {
                val i = kotlin.math.floor(pos).toInt()
                val frac = (pos - i).toFloat()
                val a = if (i < 0) last else input[i]
                val b = if (i + 1 < 0) last else input[minOf(i + 1, input.size - 1)]
                out.add(a + (b - a) * frac)
                pos += step
            }
            pos -= input.size
            if (input.isNotEmpty()) last = input[input.size - 1]
            return out.toFloatArray()
        }
    }
}
