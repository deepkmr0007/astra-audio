package app.astra.audiotext.audio

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import app.astra.audiotext.storage.LibraryRepository
import app.astra.core.audio.AudioQuality
import app.astra.core.audio.Dsp
import app.astra.core.audio.FloatArrayBuilder
import app.astra.core.audio.StreamingResampler
import app.astra.core.audio.Wav
import app.astra.core.security.StreamingAead
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Feeds MediaExtractor / MediaPlayer from an encrypted file without writing plaintext to disk. */
class EncryptedMediaDataSource(private val reader: StreamingAead.RandomAccessReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        if (size == 0) 0 else reader.readAt(position, buffer, offset, size)
    override fun getSize(): Long = reader.plainSize
    override fun close() = reader.close()
}

/**
 * 16 kHz mono PCM16 cache of an item's audio (encrypted). Long recordings are decoded once into this
 * cache and then read chunk-by-chunk, so an hour of audio never needs to sit in RAM as floats.
 */
class PcmCache(private val file: File, private val library: LibraryRepository) {
    val exists: Boolean get() = file.exists()

    fun totalSamples(): Int = library.randomAccess(file).use { (it.plainSize / 2).toInt() }

    fun read(startSample: Int, endSample: Int): FloatArray = library.randomAccess(file).use { readWith(it, startSample, endSample) }

    private fun readWith(r: StreamingAead.RandomAccessReader, startSample: Int, endSample: Int): FloatArray {
        val n = (endSample - startSample).coerceAtLeast(0)
        val bytes = ByteArray(n * 2)
        var got = 0
        while (got < bytes.size) {
            val k = r.readAt(startSample * 2L + got, bytes, got, bytes.size - got)
            if (k <= 0) break
            got += k
        }
        val bb = ByteBuffer.wrap(bytes, 0, got - got % 2).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((got / 2)) { bb.short / 32768f }
    }

    /** Peak level of [buckets] short windows spread over the file (for drawing a waveform cheaply). */
    fun peaks(buckets: Int, windowSamples: Int = 1600): FloatArray = library.randomAccess(file).use { r ->
        val total = (r.plainSize / 2).toInt()
        FloatArray(buckets) { b ->
            val start = (total.toLong() * b / buckets).toInt()
            readWith(r, start, minOf(total, start + windowSamples)).maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        }
    }

    /** Lowest-energy point in [from, to) (100 ms windows) — used to cut chunks between words. */
    fun quietest(from: Int, to: Int): Int {
        val window = read(from, to)
        return from + app.astra.core.audio.ChunkPlanner.quietestPoint(window, Dsp.WHISPER_RATE, 0, window.size)
    }
}

data class DecodeResult(val samples: Int, val durationMs: Long, val quality: AudioQuality.Report)

/**
 * Decodes any audio format supported by the phone's media codecs (MP3, AAC/M4A, OGG/Opus, FLAC,
 * WAV, AMR, 3GP, …) to the 16 kHz mono cache. Unsupported or corrupt files raise a clear error.
 * Runs entirely on-device; MediaExtractor is given a local data source, never a URL.
 */
class AudioDecoder(private val library: LibraryRepository) {

    class DecodeException(message: String) : IOException(message)

    fun decodeToCache(itemId: String, clean: Boolean, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): DecodeResult {
        val target = library.pcmCacheFile(itemId)
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            val result = library.encryptTo(tmp).use { enc ->
                val out = BufferedOutputStream(enc, 64 * 1024)
                val r = try {
                    decodeWithMediaCodec(itemId, clean, out, onProgress, isCancelled)
                } catch (e: DecodeException) {
                    throw e
                } catch (e: java.util.concurrent.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: throw DecodeException("This audio format could not be decoded on this phone. Try converting it to WAV, MP3 or M4A.")
                out.flush()
                r
            }
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
            return result
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
    }

    private fun decodeWithMediaCodec(itemId: String, clean: Boolean, out: OutputStream, onProgress: (Float) -> Unit, isCancelled: () -> Boolean): DecodeResult? {
        val extractor = MediaExtractor()
        val source = EncryptedMediaDataSource(library.audioReader(itemId, clean))
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(source)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw DecodeException("No audio track found in this file.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw DecodeException("Unknown audio type.")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else -1L
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var resampler = StreamingResampler(rate)
            val mono = FloatArrayBuilder(16384)
            val resampled = FloatArrayBuilder(16384)
            val quality = AudioQuality.Accumulator(Dsp.WHISPER_RATE)
            var written = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            fun drainResampled() {
                val arr = resampled.toArray()
                if (arr.isEmpty()) return
                quality.add(arr, 0, arr.size)
                val bb = ByteBuffer.allocate(arr.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (s in arr) bb.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                out.write(bb.array())
                written += arr.size
                resampled.clear()
            }

            while (!outputDone) {
                if (isCancelled()) throw java.util.concurrent.CancellationException()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx) ?: throw DecodeException("Decoder error")
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
                if (outIdx >= 0) {
                    val ob = codec.getOutputBuffer(outIdx)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        mono.clear()
                        toMono(ob.slice().order(ByteOrder.nativeOrder()), channels, floatPcm, mono)
                        val m = mono.toArray()
                        resampler.process(m, 0, m.size, resampled)
                        drainResampled()
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (Build.VERSION.SDK_INT >= 24 && f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        floatPcm = f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    if (newRate != rate) {
                        resampler.flush(resampled); drainResampled()
                        rate = newRate
                        resampler = StreamingResampler(rate)
                    }
                }
            }
            resampler.flush(resampled)
            drainResampled()
            onProgress(1f)
            if (written == 0L) throw DecodeException("The file contains no decodable audio.")
            return DecodeResult(written.toInt(), written * 1000 / Dsp.WHISPER_RATE, quality.report())
        } finally {
            try { codec?.stop() } catch (e: Exception) { }
            codec?.release()
            extractor.release()
            source.close()
        }
    }

    private fun toMono(buf: ByteBuffer, channels: Int, floatPcm: Boolean, out: FloatArrayBuilder) {
        val ch = channels.coerceAtLeast(1)
        if (floatPcm) {
            val fb = buf.asFloatBuffer()
            while (fb.remaining() >= ch) { var acc = 0f; repeat(ch) { acc += fb.get() }; out.add(acc / ch) }
        } else {
            val sb = buf.asShortBuffer()
            while (sb.remaining() >= ch) { var acc = 0f; repeat(ch) { acc += sb.get() / 32768f }; out.add(acc / ch) }
        }
    }

    /** Writes a cleaned-up DERIVATIVE copy (WAV) of an item's audio; the original is not touched. */
    fun createCleanCopy(itemId: String, isCancelled: () -> Boolean): Long {
        val cache = PcmCache(library.pcmCacheFile(itemId), library)
        if (!cache.exists) decodeToCache(itemId, false, {}, isCancelled)
        val total = cache.totalSamples()
        // Processed in 5-minute blocks to bound memory; the high-pass state reset at block edges is inaudible.
        val block = 5 * 60 * Dsp.WHISPER_RATE
        library.openAudioOutput(itemId, clean = true).use { out ->
            out.write(Wav.header(Wav.Format(Dsp.WHISPER_RATE, 1, 16), total * 2L))
            var start = 0
            while (start < total) {
                if (isCancelled()) throw java.util.concurrent.CancellationException()
                val end = minOf(total, start + block)
                val cleaned = Dsp.cleanup(cache.read(start, end), Dsp.WHISPER_RATE)
                val bb = ByteBuffer.allocate(cleaned.size * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (s in cleaned) bb.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                out.write(bb.array())
                start = end
            }
        }
        library.update(itemId) { it.copy(hasCleanAudio = true) }
        return total * 1000L / Dsp.WHISPER_RATE
    }
}
