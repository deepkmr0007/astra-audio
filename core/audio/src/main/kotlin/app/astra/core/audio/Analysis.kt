package app.astra.core.audio

import kotlin.math.abs

/**
 * Honest pre-flight checks. Findings are shown to the user before/after transcription so poor
 * results are explained ("the recording is clipped") rather than silently degraded.
 */
object AudioQuality {

    enum class Issue(val message: String) {
        CLIPPED("Parts of the recording are clipped (too loud). Distorted words may be transcribed wrongly."),
        VERY_QUIET("The recording is very quiet. Accuracy may drop; try the Clean-up copy or record closer to the microphone."),
        MOSTLY_SILENT("Most of the recording is silence or very low level."),
        TOO_SHORT("The recording is shorter than half a second."),
        NOISY("Background noise is high relative to speech. Expect more errors."),
    }

    data class Report(val durationMs: Long, val peakDbfs: Double, val speechLevelDbfs: Double, val noiseFloorDbfs: Double,
                      val clippedFraction: Double, val silentFraction: Double, val issues: List<Issue>)

    fun analyze(samples: FloatArray, rate: Int): Report =
        Accumulator(rate).apply { add(samples, 0, samples.size) }.report()

    /**
     * Streaming version so hour-long recordings can be analysed while they are decoded,
     * without holding all samples in memory. Keeps one RMS value per 30 ms frame.
     */
    class Accumulator(private val rate: Int) {
        private val frame = (rate * 30 / 1000).coerceAtLeast(1)
        private val rms = FloatArrayBuilder(4096)
        private var acc = 0.0
        private var inFrame = 0
        private var peak = 0f
        private var clipped = 0L
        private var total = 0L

        fun add(samples: FloatArray, from: Int, to: Int) {
            for (i in from until to) {
                val s = samples[i]
                val a = abs(s)
                if (a > peak) peak = a
                if (a >= 0.999f) clipped++
                acc += s * s
                inFrame++
                if (inFrame == frame) { rms.add(kotlin.math.sqrt(acc / frame).toFloat()); acc = 0.0; inFrame = 0 }
            }
            total += (to - from)
        }

        fun report(): Report {
            if (inFrame > 0) { rms.add(kotlin.math.sqrt(acc / inFrame).toFloat()); acc = 0.0; inFrame = 0 }
            val durationMs = total * 1000L / rate.coerceAtLeast(1)
            if (total == 0L) return Report(0, -120.0, -120.0, -120.0, 0.0, 1.0, listOf(Issue.TOO_SHORT))
            val frames = rms.toArray()
            val sorted = frames.sorted()
            val noise = sorted[(sorted.size * 0.10).toInt().coerceAtMost(sorted.size - 1)]
            val speech = sorted[(sorted.size * 0.90).toInt().coerceAtMost(sorted.size - 1)]
            val silent = frames.count { Dsp.dbfs(it) < -50 }.toDouble() / frames.size
            val clippedFraction = clipped.toDouble() / total
            val issues = ArrayList<Issue>()
            if (durationMs < 500) issues.add(Issue.TOO_SHORT)
            if (clippedFraction > 0.001) issues.add(Issue.CLIPPED)
            if (Dsp.dbfs(speech) < -35) issues.add(Issue.VERY_QUIET)
            if (silent > 0.9) issues.add(Issue.MOSTLY_SILENT)
            if (Dsp.dbfs(speech) - Dsp.dbfs(noise) < 10 && Dsp.dbfs(speech) > -35 && silent < 0.9) issues.add(Issue.NOISY)
            return Report(durationMs, Dsp.dbfs(peak), Dsp.dbfs(speech), Dsp.dbfs(noise), clippedFraction, silent, issues)
        }
    }
}

/**
 * Splits long audio into chunks that end at the quietest moment near the target length, so no
 * word is cut in half and every chunk can be resumed independently.
 */
object ChunkPlanner {

    data class Chunk(val index: Int, val startSample: Int, val endSample: Int) {
        fun startMs(rate: Int) = startSample * 1000L / rate
        fun endMs(rate: Int) = endSample * 1000L / rate
    }

    fun plan(samples: FloatArray, rate: Int, targetSeconds: Int = 120, searchSeconds: Int = 10): List<Chunk> =
        plan(samples.size, rate, targetSeconds, searchSeconds) { from, to -> quietestPoint(samples, rate, from, to) }

    /** Planning from a total length with a callback (lets the app plan without holding all audio twice). */
    fun plan(totalSamples: Int, rate: Int, targetSeconds: Int, searchSeconds: Int, quietest: (Int, Int) -> Int): List<Chunk> {
        val target = targetSeconds * rate
        val search = searchSeconds * rate
        val out = ArrayList<Chunk>()
        var start = 0
        while (start < totalSamples) {
            val remaining = totalSamples - start
            if (remaining <= target + search) { out.add(Chunk(out.size, start, totalSamples)); break }
            val from = start + target - search
            val to = start + target + search
            val cut = quietest(from, to).coerceIn(from, to)
            out.add(Chunk(out.size, start, cut))
            start = cut
        }
        return out
    }

    fun quietestPoint(samples: FloatArray, rate: Int, from: Int, to: Int): Int {
        val win = rate / 10 // 100 ms windows
        var best = from
        var bestEnergy = Double.MAX_VALUE
        var i = from
        while (i + win <= to && i + win <= samples.size) {
            var e = 0.0
            for (k in i until i + win) e += samples[k] * samples[k]
            if (e < bestEnergy) { bestEnergy = e; best = i + win / 2 }
            i += win / 2
        }
        return best
    }
}
