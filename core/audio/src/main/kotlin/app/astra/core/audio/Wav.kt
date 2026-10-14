package app.astra.core.audio

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 WAV reading/writing. Used for recordings, derivative (cleaned) audio and test fixtures. */
object Wav {

    data class Format(val sampleRate: Int, val channels: Int, val bitsPerSample: Int)

    class WavException(message: String) : Exception(message)

    fun header(format: Format, dataBytes: Long): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = format.sampleRate * format.channels * format.bitsPerSample / 8
        b.put("RIFF".toByteArray()).putInt((36 + dataBytes).coerceAtMost(0xFFFFFFFFL).toInt())
        b.put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16)
        b.putShort(1).putShort(format.channels.toShort()).putInt(format.sampleRate).putInt(byteRate)
        b.putShort((format.channels * format.bitsPerSample / 8).toShort()).putShort(format.bitsPerSample.toShort())
        b.put("data".toByteArray()).putInt(dataBytes.coerceAtMost(0xFFFFFFFFL).toInt())
        return b.array()
    }

    /** Rewrites the size fields once recording has finished. */
    fun patchSizes(file: RandomAccessFile, dataBytes: Long) {
        file.seek(4); file.write(le32(36 + dataBytes))
        file.seek(40); file.write(le32(dataBytes))
    }

    private fun le32(v: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.coerceAtMost(0xFFFFFFFFL).toInt()).array()

    fun writeMono16(out: OutputStream, samples: FloatArray, sampleRate: Int) {
        out.write(header(Format(sampleRate, 1, 16), samples.size * 2L))
        val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            if (buf.remaining() < 2) { out.write(buf.array(), 0, buf.position()); buf.clear() }
            buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.write(buf.array(), 0, buf.position())
    }

    /** Reads a PCM16 WAV (any rate, mono/stereo) into float samples per channel-mixed mono. */
    fun readMono(input: InputStream, maxSamples: Int = 16_000 * 60 * 60 * 3): Pair<FloatArray, Int> {
        val din = DataInputStream(input)
        val riff = ByteArray(12)
        din.readFully(riff)
        if (String(riff, 0, 4) != "RIFF" || String(riff, 8, 4) != "WAVE") throw WavException("Not a WAV file")
        var format: Format? = null
        while (true) {
            val hdr = ByteArray(8)
            try { din.readFully(hdr) } catch (e: EOFException) { throw WavException("No audio data chunk") }
            val id = String(hdr, 0, 4)
            val size = ByteBuffer.wrap(hdr, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val f = ByteArray(size.toInt().coerceAtMost(64)); din.readFully(f)
                if (size > 64) din.skipBytes((size - 64).toInt())
                val bb = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                val audioFormat = bb.short.toInt()
                val ch = bb.short.toInt()
                val rate = bb.int
                bb.int; bb.short
                val bits = bb.short.toInt()
                if (audioFormat != 1 || bits != 16) throw WavException("Only 16-bit PCM WAV is supported here (format=$audioFormat, bits=$bits)")
                if (ch !in 1..8 || rate !in 4000..192000) throw WavException("Unsupported WAV layout")
                format = Format(rate, ch, bits)
                if (size % 2 == 1L) din.skipBytes(1)
            } else if (id == "data") {
                val fmt = format ?: throw WavException("data chunk before fmt chunk")
                // size may be 0 or 0xFFFFFFFF for unfinished recordings: then read to EOF.
                val frameBytes = fmt.channels * 2
                val buf = ByteArray(frameBytes * 4096)
                var remaining = if (size == 0L || size == 0xFFFFFFFFL) Long.MAX_VALUE else size
                val out = FloatArrayBuilder()
                while (remaining >= frameBytes) {
                    val want = minOf(buf.size.toLong(), remaining).toInt()
                    val n = readSome(din, buf, want - want % frameBytes)
                    if (n <= 0) break
                    if (remaining != Long.MAX_VALUE) remaining -= n
                    Dsp.interleavedPcm16ToMono(buf, n - n % frameBytes, fmt.channels, out)
                    if (out.size > maxSamples) throw WavException("Audio too long")
                    if (n % frameBytes != 0) break
                }
                return out.toArray() to fmt.sampleRate
            } else {
                din.skipBytes((size + (size and 1)).toInt())
            }
        }
    }

    private fun readSome(din: DataInputStream, buf: ByteArray, want: Int): Int {
        var total = 0
        while (total < want) {
            val n = din.read(buf, total, want - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}

/** Growable float buffer without boxing. */
class FloatArrayBuilder(initial: Int = 16_000) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(maxOf(16, data.size * 2))
        data[size++] = v
    }

    fun addAll(src: FloatArray, from: Int = 0, to: Int = src.size) {
        val n = to - from
        if (size + n > data.size) data = data.copyOf(maxOf(size + n, data.size * 2))
        System.arraycopy(src, from, data, size, n)
        size += n
    }

    fun toArray(): FloatArray = data.copyOf(size)

    fun get(index: Int): Float = data[index]

    fun clear() { size = 0 }
}
