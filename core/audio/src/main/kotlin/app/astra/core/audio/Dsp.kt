package app.astra.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Signal processing used for decoding, analysis and optional cleanup. All functions are pure. */
object Dsp {

    const val WHISPER_RATE = 16_000

    /** Mixes interleaved PCM16 frames to mono float. */
    fun interleavedPcm16ToMono(bytes: ByteArray, length: Int, channels: Int, out: FloatArrayBuilder) {
        val frame = channels * 2
        var i = 0
        while (i + frame <= length) {
            var acc = 0f
            for (c in 0 until channels) {
                val lo = bytes[i + 2 * c].toInt() and 0xFF
                val hi = bytes[i + 2 * c + 1].toInt()
                acc += ((hi shl 8) or lo).toShort() / 32768f
            }
            out.add(acc / channels)
            i += frame
        }
    }

    /**
     * Band-limited resampling: a windowed-sinc low-pass (when downsampling) followed by
     * linear interpolation on the filtered signal. Accurate enough for speech recognition,
     * cheap enough for phones.
     */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int = WHISPER_RATE): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        val src = if (toRate < fromRate) lowPass(input, fromRate, toRate * 0.45) else input
        val ratio = fromRate.toDouble() / toRate
        val outLen = ((input.size.toLong() * toRate) / fromRate).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = src[idx.coerceAtMost(src.size - 1)]
            val b = src[(idx + 1).coerceAtMost(src.size - 1)]
            out[i] = a + (b - a) * frac
        }
        return out
    }

    /** FIR low-pass with a Blackman window. */
    fun lowPass(input: FloatArray, rate: Int, cutoffHz: Double, taps: Int = 63): FloatArray {
        val fc = cutoffHz / rate
        val m = taps - 1
        val h = DoubleArray(taps) { n ->
            val x = n - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
            val w = 0.42 - 0.5 * cos(2 * PI * n / m) + 0.08 * cos(4 * PI * n / m)
            sinc * w
        }
        val sum = h.sum()
        for (i in h.indices) h[i] /= sum
        val out = FloatArray(input.size)
        val half = m / 2
        for (i in input.indices) {
            var acc = 0.0
            for (k in 0 until taps) {
                val j = i + k - half
                if (j >= 0 && j < input.size) acc += input[j] * h[k]
            }
            out[i] = acc.toFloat()
        }
        return out
    }

    /** Second-order high-pass (RBJ biquad) — removes rumble and DC offset. */
    fun highPass(input: FloatArray, rate: Int, cutoffHz: Double = 80.0, q: Double = 0.707): FloatArray {
        val w0 = 2 * PI * cutoffHz / rate
        val alpha = sin(w0) / (2 * q)
        val cosw = cos(w0)
        val a0 = 1 + alpha
        val b0 = (1 + cosw) / 2 / a0
        val b1 = -(1 + cosw) / a0
        val b2 = (1 + cosw) / 2 / a0
        val a1 = -2 * cosw / a0
        val a2 = (1 - alpha) / a0
        val out = FloatArray(input.size)
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in input.indices) {
            val x0 = input[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = y0.toFloat()
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
        }
        return out
    }

    /** Frame RMS values (frameMs windows). */
    fun frameRms(samples: FloatArray, rate: Int, frameMs: Int = 30): FloatArray {
        val frame = (rate * frameMs / 1000).coerceAtLeast(1)
        val n = (samples.size + frame - 1) / frame
        return FloatArray(n) { f ->
            var acc = 0.0
            val start = f * frame
            val end = minOf(samples.size, start + frame)
            for (i in start until end) acc += samples[i] * samples[i]
            sqrt(acc / (end - start).coerceAtLeast(1)).toFloat()
        }
    }

    fun dbfs(x: Float): Double = if (x <= 1e-9f) -120.0 else 20 * log10(x.toDouble())

    /**
     * Gentle cleanup that produces a SEPARATE derivative signal (the original is never modified):
     * high-pass at 80 Hz, a soft downward expander on frames well below the speech level, then
     * peak normalisation to -1 dBFS. It reduces hum/rumble and steady background between words;
     * it cannot recover clipped audio or separate overlapping speakers.
     */
    fun cleanup(input: FloatArray, rate: Int): FloatArray {
        val hp = highPass(input, rate, 80.0)
        val frameMs = 20
        val frame = rate * frameMs / 1000
        val rms = frameRms(hp, rate, frameMs)
        if (rms.isEmpty()) return hp
        val sorted = rms.sorted()
        val noiseFloor = sorted[(sorted.size * 0.10).toInt().coerceAtMost(sorted.size - 1)]
        val speechLevel = sorted[(sorted.size * 0.90).toInt().coerceAtMost(sorted.size - 1)]
        val threshold = noiseFloor + (speechLevel - noiseFloor) * 0.15f
        val gains = FloatArray(rms.size) { i ->
            if (rms[i] >= threshold || threshold <= 0f) 1f else (0.25f + 0.75f * (rms[i] / threshold)).coerceIn(0.25f, 1f)
        }
        // Smooth gains across frames to avoid zipper noise.
        val smooth = FloatArray(gains.size)
        var g = 1f
        for (i in gains.indices) { g = if (gains[i] < g) g * 0.7f + gains[i] * 0.3f else g * 0.5f + gains[i] * 0.5f; smooth[i] = g }
        val out = FloatArray(hp.size)
        for (i in hp.indices) {
            val f = (i / frame).coerceAtMost(smooth.size - 1)
            out[i] = hp[i] * smooth[f]
        }
        return normalizePeak(out, -1.0)
    }

    fun normalizePeak(input: FloatArray, targetDbfs: Double = -1.0): FloatArray {
        var peak = 0f
        for (s in input) peak = maxOf(peak, abs(s))
        if (peak < 1e-6f) return input.copyOf()
        val target = Math.pow(10.0, targetDbfs / 20).toFloat()
        val gain = (target / peak).coerceAtMost(20f) // never boost noise by more than +26 dB
        return FloatArray(input.size) { input[it] * gain }
    }

    /** Min/max pairs for drawing a waveform with [buckets] columns. */
    fun waveformPeaks(samples: FloatArray, buckets: Int): FloatArray {
        if (samples.isEmpty() || buckets <= 0) return FloatArray(0)
        val out = FloatArray(buckets)
        val per = samples.size.toDouble() / buckets
        for (b in 0 until buckets) {
            val start = (b * per).toInt()
            val end = minOf(samples.size, ((b + 1) * per).toInt().coerceAtLeast(start + 1))
            var peak = 0f
            for (i in start until end) peak = maxOf(peak, abs(samples[i]))
            out[b] = peak
        }
        return out
    }
}

/**
 * Streaming version of [Dsp.resample] for decoders that deliver audio in arbitrary blocks:
 * causal windowed-sinc low-pass (when downsampling) + linear interpolation, with the filter's
 * group delay compensated so timestamps stay aligned. Output is identical however the input is split.
 */
class StreamingResampler(private val fromRate: Int, private val toRate: Int = Dsp.WHISPER_RATE, taps: Int = 63) {
    private val ratio = fromRate.toDouble() / toRate
    private val h: FloatArray? = if (toRate < fromRate) designLowPass(fromRate, toRate * 0.45, taps) else null
    private val delay = if (h != null) (taps - 1) / 2 else 0
    private val history = FloatArray(taps)
    private var histPos = 0
    private var filtered = FloatArrayBuilder(8192)
    private var filteredBase = 0L   // global index of filtered[0]
    private var inputCount = 0L
    private var outIndex = 0L
    private var flushed = false

    private fun designLowPass(rate: Int, cutoff: Double, taps: Int): FloatArray {
        val fc = cutoff / rate
        val m = taps - 1
        val d = DoubleArray(taps) { n ->
            val x = n - m / 2.0
            val sinc = if (x == 0.0) 2 * fc else kotlin.math.sin(2 * Math.PI * fc * x) / (Math.PI * x)
            sinc * (0.42 - 0.5 * kotlin.math.cos(2 * Math.PI * n / m) + 0.08 * kotlin.math.cos(4 * Math.PI * n / m))
        }
        val s = d.sum()
        return FloatArray(taps) { (d[it] / s).toFloat() }
    }

    private fun pushInput(x: Float) {
        if (h == null) { filtered.add(x); inputCount++; return }
        history[histPos] = x
        histPos = (histPos + 1) % history.size
        var acc = 0f
        var idx = histPos
        // history[histPos] is now the oldest sample.
        for (k in h.indices.reversed()) { acc += h[k] * history[idx]; idx = (idx + 1) % history.size }
        filtered.add(acc)
        inputCount++
    }

    private fun emit(out: FloatArrayBuilder, limitInputs: Long) {
        while (true) {
            val p = outIndex * ratio + delay
            val i = Math.floor(p).toLong()
            if (i + 1 >= filteredBase + filtered.size) break
            if (outIndex >= limitInputs) break
            val a = filteredAt(i)
            val b = filteredAt(i + 1)
            out.add(a + (b - a) * (p - i).toFloat())
            outIndex++
        }
        compact((Math.floor(outIndex * ratio + delay).toLong() - 1).coerceAtLeast(filteredBase))
    }

    private fun filteredAt(i: Long): Float {
        val k = (i - filteredBase).toInt()
        return if (k < 0) 0f else filtered.get(k)
    }

    private fun compact(keepFrom: Long) {
        val drop = (keepFrom - filteredBase).toInt()
        if (drop < 4096) return
        val rest = filtered.toArray().copyOfRange(drop, filtered.size)
        filtered = FloatArrayBuilder(maxOf(8192, rest.size * 2)).apply { addAll(rest) }
        filteredBase += drop
    }

    fun process(input: FloatArray, from: Int, to: Int, out: FloatArrayBuilder) {
        check(!flushed) { "Resampler already flushed" }
        for (j in from until to) pushInput(input[j])
        emit(out, Long.MAX_VALUE)
    }

    /** Emits the tail. Total output length = floor(inputs * toRate / fromRate). */
    fun flush(out: FloatArrayBuilder) {
        if (flushed) return
        flushed = true
        val target = inputCount * toRate / fromRate
        repeat(delay + 2) { pushInput(0f) }
        emit(out, target)
    }
}
