package com.miniichat.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal PCM16 WAV helpers. Recordings are always 16 kHz mono PCM16 (what whisper wants). */
object WavIO {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS = 16
    const val HEADER_SIZE = 44

    fun writeHeader(raf: RandomAccessFile, dataBytes: Long, sampleRate: Int = SAMPLE_RATE, channels: Int = CHANNELS) {
        val byteRate = sampleRate * channels * BITS / 8
        val bb = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt((36 + dataBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)                               // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort((channels * BITS / 8).toShort())
        bb.putShort(BITS.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        raf.seek(0)
        raf.write(bb.array())
    }

    data class Info(val sampleRate: Int, val channels: Int, val bits: Int, val dataOffset: Long, val dataBytes: Long) {
        val durationMs: Long get() =
            if (sampleRate <= 0 || channels <= 0 || bits <= 0) 0
            else dataBytes * 1000L / (sampleRate.toLong() * channels * (bits / 8))
    }

    /** Parse RIFF chunks to locate fmt and data. Returns null if not a PCM WAV. */
    fun readInfo(file: File): Info? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val hdr = ByteArray(12)
            raf.readFully(hdr)
            if (String(hdr, 0, 4) != "RIFF" || String(hdr, 8, 4) != "WAVE") return null
            var sampleRate = 0; var channels = 0; var bits = 0; var fmtTag = 0
            val chunkHdr = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHdr)
                val id = String(chunkHdr, 0, 4)
                val size = ByteBuffer.wrap(chunkHdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt().coerceAtLeast(16))
                        raf.readFully(fmt, 0, size.toInt())
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        fmtTag = b.getShort(0).toInt() and 0xffff
                        channels = b.getShort(2).toInt()
                        sampleRate = b.getInt(4)
                        bits = b.getShort(14).toInt()
                        if (size % 2 == 1L) raf.skipBytes(1)
                    }
                    "data" -> {
                        if (fmtTag != 1 && fmtTag != 0xFFFE) return null
                        // header of a still-open/unfinished file may report 0 → use remaining length
                        val remaining = raf.length() - raf.filePointer
                        val dataBytes = if (size == 0L || size > remaining) remaining else size
                        return Info(sampleRate, channels, bits, raf.filePointer, dataBytes)
                    }
                    else -> raf.seek(raf.filePointer + size + (size % 2))
                }
            }
            null
        }
    }.getOrNull()

    /**
     * Read [maxSamples] mono float samples starting at sample index [startSample].
     * Only 16 kHz PCM16 is supported here (everything we store is normalised to that).
     */
    fun readFloats(file: File, info: Info, startSample: Long, maxSamples: Int): FloatArray {
        require(info.bits == 16) { "Only 16-bit PCM WAV supported" }
        val frameBytes = 2 * info.channels
        val totalFrames = info.dataBytes / frameBytes
        if (startSample >= totalFrames) return FloatArray(0)
        val n = minOf(maxSamples.toLong(), totalFrames - startSample).toInt()
        val bytes = ByteArray(n * frameBytes)
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(info.dataOffset + startSample * frameBytes)
            raf.readFully(bytes)
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0f
            for (c in 0 until info.channels) acc += bb.getShort((i * info.channels + c) * 2) / 32768f
            out[i] = acc / info.channels
        }
        return out
    }
}
