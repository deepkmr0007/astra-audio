package app.astra.audiotext

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.astra.core.audio.Dsp
import app.astra.core.audio.Wav
import app.astra.core.models.InstallOutcome
import app.astra.core.models.ModelDownloader
import app.astra.core.models.ModelInstaller
import app.astra.core.models.ModelRegistry
import app.astra.core.privacy.ConsentDecision
import app.astra.core.privacy.MemoryAuditSink
import app.astra.core.privacy.NetworkConsentGateway
import app.astra.core.text.ErrorRate
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.CancelSignal
import app.astra.core.transcript.EngineResult
import app.astra.core.transcript.SilenceAware
import app.astra.core.transcript.TranscribeListener
import app.astra.core.transcript.TranscribeOptions
import app.astra.engine.whisper.WhisperEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end speech recognition on an Android device/emulator with a REAL Whisper model.
 *
 * CI downloads ggml-tiny.en.bin and the public-domain JFK inaugural clip into androidTest assets
 * (they are not committed). The model goes through the app's real verification path (registry
 * SHA-1, GGML header) before use. Timing lines are logged with tag ASTRA-BENCH so CI can report them.
 * Skipped automatically when the assets are absent (e.g. a local run without the download).
 */
@RunWith(AndroidJUnit4::class)
class RealTranscriptionTest {

    private val reference = "And so my fellow Americans, ask not what your country can do for you, ask what you can do for your country."

    private fun asset(name: String): ByteArray? = try {
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { it.readBytes() }
    } catch (e: Exception) { null }

    @Test fun transcribesRealSpeechOnDevice() = runBlocking {
        val modelBytes = asset("ggml-tiny.en.bin")
        val wavBytes = asset("jfk.wav")
        assumeTrue("real-model assets not bundled", modelBytes != null && wavBytes != null)

        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "real-asr").apply { deleteRecursively(); mkdirs() }
        val installer = ModelInstaller(File(dir, "models"))
        val gateway = NetworkConsentGateway(object : app.astra.core.privacy.Transport {
            override fun exchange(ticket: app.astra.core.privacy.ConnectionTicket, request: app.astra.core.privacy.HopRequest,
                                  onResponse: (app.astra.core.privacy.HopResponse) -> Unit, isCancelled: () -> Boolean) = error("no network in tests")
        }, { ConsentDecision.CANCEL }, MemoryAuditSink())
        val spec = ModelRegistry.byId("whisper-tiny-en")!!
        val outcome = ModelDownloader(gateway, installer, MemoryAuditSink()).importFile(modelBytes!!.inputStream(), spec)
        assertTrue("model must pass verification: $outcome", outcome is InstallOutcome.Installed)
        val model = (outcome as InstallOutcome.Installed).model

        val libDir = ctx.applicationInfo.nativeLibraryDir
        val t0 = System.nanoTime()
        val engine = WhisperEngine.load(installer.fileOf(model), spec.id, false, false, libDir)
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("engine must load", engine != null)
        Log.i("ASTRA-BENCH", "backend=${WhisperEngine.backendInfo(libDir)} abi=${android.os.Build.SUPPORTED_ABIS.first()} cores=${Runtime.getRuntime().availableProcessors()}")

        val (pcm0, rate) = Wav.readMono(wavBytes!!.inputStream())
        val pcm = Dsp.resample(pcm0, rate, Dsp.WHISPER_RATE)
        val audioMs = pcm.size * 1000L / Dsp.WHISPER_RATE
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)

        for (profile in listOf(AccuracyProfile.FAST, AccuracyProfile.BALANCED)) {
            val t1 = System.nanoTime()
            val r = engine!!.transcribe(pcm, 0, TranscribeOptions(language = "en", profile = profile, threads = threads, skipSilence = false),
                object : TranscribeListener {}, CancelSignal())
            val ms = (System.nanoTime() - t1) / 1_000_000
            assertTrue("transcription failed: $r", r is EngineResult.Success)
            val text = (r as EngineResult.Success).segments.joinToString(" ") { it.text.trim() }
            val wer = ErrorRate.wer(reference, text).rate
            Log.i("ASTRA-BENCH", "profile=$profile model=tiny.en audio=${audioMs}ms took=${ms}ms rtf=${"%.2f".format(ms.toDouble() / audioMs)} wer=${"%.3f".format(wer)} load=${loadMs}ms text=\"$text\"")
            assertTrue("WER too high ($wer): $text", wer < 0.25)
        }

        // Same speech with 40 s of silence around it: silence skipping must keep the text and timestamps
        // and should cost far less than processing 51 s of audio.
        val padded = FloatArray(Dsp.WHISPER_RATE * 20) + pcm + FloatArray(Dsp.WHISPER_RATE * 20)
        val t2 = System.nanoTime()
        val r2 = SilenceAware.transcribe(engine!!, padded, 0, TranscribeOptions(language = "en", profile = AccuracyProfile.FAST, threads = threads),
            object : TranscribeListener {}, CancelSignal())
        val ms2 = (System.nanoTime() - t2) / 1_000_000
        val segs = (r2 as EngineResult.Success).segments
        val text2 = segs.joinToString(" ") { it.text.trim() }
        Log.i("ASTRA-BENCH", "silence-skip audio=${padded.size * 1000L / Dsp.WHISPER_RATE}ms took=${ms2}ms firstSegmentStart=${segs.firstOrNull()?.startMs} text=\"$text2\"")
        assertTrue(ErrorRate.wer(reference, text2).rate < 0.25)
        assertTrue("timestamps must map back past the leading silence", (segs.first().startMs) >= 19_000)
        engine.close()
        dir.deleteRecursively()
    }
}
