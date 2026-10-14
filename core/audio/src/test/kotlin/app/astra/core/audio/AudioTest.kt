package app.astra.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioTest {

    private fun tone(hz: Double, seconds: Double, rate: Int, amp: Float = 0.5f) =
        FloatArray((seconds * rate).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    @Test fun wavRoundTrip() {
        val src = tone(440.0, 0.5, 16000)
        val bos = ByteArrayOutputStream()
        Wav.writeMono16(bos, src, 16000)
        val (back, rate) = Wav.readMono(ByteArrayInputStream(bos.toByteArray()))
        assertEquals(16000, rate)
        assertEquals(src.size, back.size)
        for (i in src.indices step 97) assertEquals(src[i], back[i], 1e-3f)
    }

    @Test fun resamplePreservesDurationAndTone() {
        val src = tone(440.0, 1.0, 48000)
        val out = Dsp.resample(src, 48000, 16000)
        assertEquals(16000, out.size)
        // Zero crossings ≈ 2 * 440 per second.
        var zc = 0
        for (i in 1 until out.size) if ((out[i - 1] < 0) != (out[i] < 0)) zc++
        assertTrue("zero crossings $zc", abs(zc - 880) < 10)
    }

    @Test fun lowPassRemovesAliasingContent() {
        val hi = tone(12000.0, 0.5, 48000) // above the 8 kHz Nyquist of 16 kHz
        val out = Dsp.resample(hi, 48000, 16000)
        val peak = out.drop(200).maxOf { abs(it) }
        assertTrue("aliased energy should be strongly attenuated, peak=$peak", peak < 0.05f)
    }

    @Test fun cleanupProducesSeparateNormalisedCopy() {
        val src = tone(300.0, 1.0, 16000, 0.1f)
        val before = src.copyOf()
        val out = Dsp.cleanup(src, 16000)
        assertTrue(src.contentEquals(before)) // original untouched
        val peak = out.maxOf { abs(it) }
        assertEquals(0.891f, peak, 0.02f) // -1 dBFS
    }

    @Test fun qualityReportFlagsClippingAndQuietness() {
        val clipped = FloatArray(16000) { if (it % 2 == 0) 1f else -1f }
        assertTrue(AudioQuality.Issue.CLIPPED in AudioQuality.analyze(clipped, 16000).issues)
        val quiet = tone(200.0, 2.0, 16000, 0.005f)
        assertTrue(AudioQuality.Issue.VERY_QUIET in AudioQuality.analyze(quiet, 16000).issues)
        assertTrue(AudioQuality.Issue.TOO_SHORT in AudioQuality.analyze(FloatArray(100), 16000).issues)
    }

    @Test fun chunkPlannerCutsAtSilenceAndCoversEverything() {
        val rate = 16000
        // 300 s of tone with silent gaps every 50 s.
        val samples = FloatArray(300 * rate) { i -> if ((i / rate) % 50 == 49) 0f else (0.3 * sin(i * 0.05)).toFloat() }
        val chunks = ChunkPlanner.plan(samples, rate, targetSeconds = 100, searchSeconds = 15)
        assertEquals(0, chunks.first().startSample)
        assertEquals(samples.size, chunks.last().endSample)
        for (k in 1 until chunks.size) assertEquals(chunks[k - 1].endSample, chunks[k].startSample)
        // Search window is 85..115 s; the cut must land inside the silent second 99..100 s.
        val cutSec = chunks[0].endSample / rate
        assertEquals(99, cutSec)
    }

    @Test fun waveformPeaks() {
        val p = Dsp.waveformPeaks(floatArrayOf(0f, 0.5f, -1f, 0.25f), 2)
        assertEquals(listOf(0.5f, 1f), p.toList())
    }
}

class StreamingResamplerTest {
    private fun run(src: FloatArray, from: Int, chunk: Int): FloatArray {
        val r = StreamingResampler(from, 16000)
        val out = FloatArrayBuilder()
        var i = 0
        while (i < src.size) { val e = minOf(src.size, i + chunk); r.process(src, i, e, out); i = e }
        r.flush(out)
        return out.toArray()
    }

    @Test fun outputIsIndependentOfBlockSizeAndHasRightLength() {
        val src = FloatArray(44100 * 2) { (0.4 * kotlin.math.sin(2 * Math.PI * 300 * it / 44100.0)).toFloat() }
        val a = run(src, 44100, 1000)
        val b = run(src, 44100, 4093)
        val c = run(src, 44100, src.size)
        assertEquals(32000, a.size)
        assertTrue(a.contentEquals(b) && b.contentEquals(c))
    }

    @Test fun matchesBatchResamplerAwayFromEdges() {
        val src = FloatArray(48000) { (0.4 * kotlin.math.sin(2 * Math.PI * 440 * it / 48000.0)).toFloat() }
        val s = run(src, 48000, 777)
        val b = Dsp.resample(src, 48000, 16000)
        assertEquals(b.size, s.size)
        for (i in 200 until s.size - 200) assertEquals("at $i", b[i], s[i], 0.01f)
    }

    @Test fun upsamplingPassesThroughShape() {
        val src = FloatArray(8000) { (0.4 * kotlin.math.sin(2 * Math.PI * 200 * it / 8000.0)).toFloat() }
        val s = run(src, 8000, 333)
        assertEquals(16000, s.size)
    }
}
