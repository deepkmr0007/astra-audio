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

class SilenceSkipperTest {
    private val rate = 16000
    private fun speech(seconds: Double) = FloatArray((seconds * rate).toInt()) { (0.3 * kotlin.math.sin(it * 0.07) * (0.6 + 0.4 * kotlin.math.sin(it * 0.0009))).toFloat() }
    private fun silence(seconds: Double, noise: Float = 0.0005f) = FloatArray((seconds * rate).toInt()) { (if (it % 2 == 0) noise else -noise) }

    @Test fun removesLongPausesAndMapsTimesBack() {
        // 2 s speech, 10 s silence, 3 s speech, 8 s silence
        val pcm = speech(2.0) + silence(10.0) + speech(3.0) + silence(8.0)
        val r = SilenceSkipper.compact(pcm, rate)
        assertTrue("should remove most silence, removed=${r.removedFraction}", r.removedFraction > 0.6)
        assertEquals(2, r.spans.size)
        // Start of the second speech burst (original 12.0 s) must map back to ≈12.0 s.
        val secondDst = r.spans[1].dstStart.toLong() * 1000 / rate
        val back = r.startToOriginalMs(secondDst + 300) // +pad kept before the speech
        assertTrue("mapped to $back", kotlin.math.abs(back - 12_000) < 60)
        // End of first span maps to its original end, not into the next span.
        val endFirst = r.endToOriginalMs(r.spans[0].dstEnd.toLong() * 1000 / rate)
        assertTrue(endFirst < 3_000)
        // Every kept sample is identical to the original (nothing re-synthesised).
        for (s in r.spans) for (k in s.srcStart until s.srcEnd step 997) assertEquals(pcm[k], r.samples[s.dstStart + (k - s.srcStart)], 0f)
    }

    @Test fun shortPausesAreKept() {
        val pcm = speech(3.0) + silence(0.8) + speech(3.0)
        assertTrue(SilenceSkipper.compact(pcm, rate).isIdentity)
    }

    @Test fun allSilentIsDetected() {
        assertTrue(SilenceSkipper.compact(silence(20.0, 0.0002f), rate).allSilent)
    }

    @Test fun quietSpeechIsNotMistakenForSilence() {
        // Quiet speaker (~ -36 dBFS) with long pauses: speech must survive.
        val quiet = speech(4.0).map { it * 0.05f }.toFloatArray()
        val pcm = quiet + silence(6.0, 0.00005f) + quiet
        val r = SilenceSkipper.compact(pcm, rate)
        val kept = r.samples.size.toDouble() / rate
        assertTrue("kept $kept s", kept >= 7.9)
    }
}

class LiveWindowingTest {
    @Test fun cutsWindowsAtQuietPointsAndKeepsEverySample() {
        val rate = 16000
        val w = LiveWindowing(rate)
        val total = 75 * rate
        val src = FloatArray(total) { i -> if ((i / rate) % 25 == 24) 0f else (0.3 * kotlin.math.sin(i * 0.05)).toFloat() }
        val windows = ArrayList<LiveWindowing.Window>()
        var i = 0
        while (i < total) { val e = minOf(total, i + 1600); windows += w.push(src, i, e); i = e }
        w.flush()?.let { windows += it }
        assertEquals(total, windows.sumOf { it.samples.size })
        var expectedStart = 0L
        for (win in windows) { assertEquals(expectedStart, win.startSample); expectedStart += win.samples.size }
        assertTrue(windows.dropLast(1).all { it.samples.size in 20 * rate..28 * rate })
        // First cut lands in the quiet second (24..25 s).
        assertEquals(24, windows[0].samples.size / rate)
    }
}
