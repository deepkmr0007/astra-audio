package app.astra.core.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Speed-up: removes long silences before audio reaches the speech model, and maps timestamps back
 * to the original recording exactly.
 *
 * Why it is faster: Whisper always processes audio in 30-second windows, so silence costs as much
 * compute as speech. Meetings, lectures and voice notes often contain 20–60 % pauses. Skipping them
 * cuts the number of windows (and the battery/heat) proportionally, and a chunk that is entirely
 * silent never reaches the model at all. It also removes the main trigger for Whisper's
 * "hallucinated" text during silence.
 *
 * Safety margins: only pauses longer than [Params.minSilenceMs] are cut, and [Params.padMs] of the
 * pause is kept on each side, so word onsets/offsets and natural sentence breaks survive. If the
 * saving would be small (< [Params.minSavingFraction]) the audio is passed through unchanged.
 */
object SilenceSkipper {

    data class Params(
        val frameMs: Int = 30,
        val minSilenceMs: Int = 1200,
        val padMs: Int = 300,
        /** Never treat anything louder than this as silence, whatever the noise floor. */
        val maxSilenceDbfs: Double = -35.0,
        /** Anything quieter than this is always silence. */
        val minSilenceDbfs: Double = -60.0,
        val minSavingFraction: Double = 0.10,
    )

    /** A run of original samples [srcStart, srcEnd) copied to [dstStart] in the compacted signal. */
    data class Span(val srcStart: Int, val srcEnd: Int, val dstStart: Int) {
        val length: Int get() = srcEnd - srcStart
        val dstEnd: Int get() = dstStart + length
    }

    class Result(val samples: FloatArray, val spans: List<Span>, val originalLength: Int, val rate: Int) {
        val isIdentity: Boolean get() = spans.size == 1 && spans[0].srcStart == 0 && spans[0].srcEnd == originalLength
        val allSilent: Boolean get() = spans.isEmpty()
        val removedFraction: Double get() = if (originalLength == 0) 0.0 else 1.0 - samples.size.toDouble() / originalLength

        /** Maps a time in the compacted audio (ms) to the original audio (ms). */
        fun toOriginalMs(compactMs: Long, preferNext: Boolean): Long {
            if (spans.isEmpty()) return 0
            val dst = (compactMs * rate / 1000).toInt()
            // Binary search for the span containing dst.
            var lo = 0
            var hi = spans.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                if (spans[mid].dstStart <= dst) lo = mid else hi = mid - 1
            }
            var s = spans[lo]
            // Exactly at a junction: a segment START belongs to the next span, an END to the previous.
            if (dst == s.dstStart && !preferNext && lo > 0) s = spans[lo - 1]
            val src = s.srcStart + (dst - s.dstStart).coerceIn(0, s.length)
            return src.toLong() * 1000 / rate
        }

        fun startToOriginalMs(ms: Long) = toOriginalMs(ms, preferNext = true)
        fun endToOriginalMs(ms: Long) = toOriginalMs(ms, preferNext = false)
    }

    fun identity(samples: FloatArray, rate: Int) = Result(samples, listOf(Span(0, samples.size, 0)), samples.size, rate)

    fun compact(samples: FloatArray, rate: Int, p: Params = Params()): Result {
        if (samples.isEmpty()) return Result(samples, emptyList(), 0, rate)
        val frame = max(1, rate * p.frameMs / 1000)
        val nFrames = (samples.size + frame - 1) / frame
        val rms = FloatArray(nFrames) { f ->
            var acc = 0.0
            val a = f * frame
            val b = min(samples.size, a + frame)
            for (i in a until b) acc += samples[i] * samples[i]
            sqrt(acc / (b - a)).toFloat()
        }
        val sorted = rms.sorted()
        val floor = sorted[(sorted.size * 0.10).toInt().coerceAtMost(sorted.size - 1)]
        val loud = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        // Silence threshold: ~10 dB above the noise floor, but well below the loud (speech) level,
        // and always within [minSilenceDbfs, maxSilenceDbfs].
        val minAbs = db2lin(p.minSilenceDbfs)
        val maxAbs = db2lin(p.maxSilenceDbfs)
        var threshold = max(floor * 3.16f, minAbs)
        threshold = min(threshold, max(loud * 0.18f, minAbs))
        threshold = min(threshold, maxAbs)

        val voiced = BooleanArray(nFrames) { rms[it] > threshold }
        // Hangover: one voiced frame keeps its neighbours voiced (protects soft consonants).
        val smooth = BooleanArray(nFrames) { i -> voiced[i] || (i > 0 && voiced[i - 1]) || (i < nFrames - 1 && voiced[i + 1]) }

        if (smooth.none { it }) return Result(FloatArray(0), emptyList(), samples.size, rate)

        val minSilenceFrames = max(1, p.minSilenceMs / p.frameMs)
        val padSamples = rate * p.padMs / 1000
        // Collect silent runs long enough to cut; keep [pad] at each end of the run.
        val cuts = ArrayList<IntRange>() // sample ranges to drop
        var i = 0
        while (i < nFrames) {
            if (smooth[i]) { i++; continue }
            var j = i
            while (j < nFrames && !smooth[j]) j++
            val runFrames = j - i
            val atStart = i == 0
            val atEnd = j == nFrames
            if (runFrames >= minSilenceFrames || atStart || atEnd) {
                val runStart = i * frame
                val runEnd = min(samples.size, j * frame)
                val dropStart = if (atStart) 0 else runStart + padSamples
                val dropEnd = if (atEnd) samples.size else runEnd - padSamples
                if (dropEnd - dropStart > rate / 10) cuts.add(dropStart until dropEnd)
            }
            i = j
        }
        val removed = cuts.sumOf { it.last - it.first + 1 }
        if (removed.toDouble() / samples.size < p.minSavingFraction) return identity(samples, rate)

        val spans = ArrayList<Span>()
        var src = 0
        var dst = 0
        for (c in cuts) {
            if (c.first > src) { spans.add(Span(src, c.first, dst)); dst += c.first - src }
            src = c.last + 1
        }
        if (src < samples.size) { spans.add(Span(src, samples.size, dst)); dst += samples.size - src }
        val out = FloatArray(dst)
        for (s in spans) System.arraycopy(samples, s.srcStart, out, s.dstStart, s.length)
        return Result(out, spans, samples.size, rate)
    }

    private fun db2lin(db: Double): Float = Math.pow(10.0, db / 20).toFloat()
}

/**
 * Cuts a live, growing recording into transcription windows at quiet moments, so a background
 * worker can transcribe while the user is still speaking. Windows are 20–30 s (Whisper's native
 * window is 30 s); each cut lands on the quietest 100 ms in the last part of the window.
 */
class LiveWindowing(private val rate: Int = Dsp.WHISPER_RATE, private val minSeconds: Int = 20, private val maxSeconds: Int = 28) {

    class Window(val startSample: Long, val samples: FloatArray) {
        fun startMs(rate: Int) = startSample * 1000 / rate
    }

    private var buffer = FloatArrayBuilder(rate * (maxSeconds + 2))
    private var bufferStart = 0L

    val pendingSamples: Int get() = buffer.size

    /** Appends audio; returns zero or more windows that are ready to transcribe. */
    @Synchronized
    fun push(samples: FloatArray, from: Int = 0, to: Int = samples.size): List<Window> {
        buffer.addAll(samples, from, to)
        val out = ArrayList<Window>()
        while (buffer.size >= maxSeconds * rate) {
            val data = buffer.toArray()
            val cut = ChunkPlanner.quietestPoint(data, rate, minSeconds * rate, maxSeconds * rate).coerceIn(minSeconds * rate, maxSeconds * rate)
            out.add(Window(bufferStart, data.copyOfRange(0, cut)))
            val rest = data.copyOfRange(cut, data.size)
            buffer = FloatArrayBuilder(rate * (maxSeconds + 2)).apply { addAll(rest) }
            bufferStart += cut
        }
        return out
    }

    /** Returns whatever is left (the tail after the user taps Stop), or null if empty. */
    @Synchronized
    fun flush(): Window? {
        if (buffer.size == 0) return null
        val w = Window(bufferStart, buffer.toArray())
        bufferStart += buffer.size
        buffer = FloatArrayBuilder(rate)
        return w
    }
}
