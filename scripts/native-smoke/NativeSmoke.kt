// Host smoke test for the JNI bridge: loads libastra_whisper (built for the host), runs a full
// transcription through WhisperEngine and prints what came back. Used by scripts/verify-native.sh.
import app.astra.core.audio.Dsp
import app.astra.core.audio.Wav
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.CancelSignal
import app.astra.core.transcript.EngineResult
import app.astra.core.transcript.Segment
import app.astra.core.transcript.TranscribeListener
import app.astra.core.transcript.TranscribeOptions
import app.astra.engine.whisper.WhisperEngine
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) = runBlocking {
    val model = File(args[0]); val wav = File(args[1])
    val cancelAfterMs = args.getOrNull(2)?.toLong()
    println("system: " + WhisperEngine.systemInfo())
    val engine = WhisperEngine.load(model, "smoke", speakerTurns = false, translate = true) ?: error("model failed to load")
    println("engine: ${engine.displayName} multilingual=${engine.multilingual}")
    val (pcm, rate) = wav.inputStream().use { Wav.readMono(it) }
    val samples = Dsp.resample(pcm, rate, 16000)
    val signal = CancelSignal()
    if (cancelAfterMs != null) Thread { Thread.sleep(cancelAfterMs); signal.cancel() }.start()
    var partials = 0
    val t0 = System.currentTimeMillis()
    val r = engine.transcribe(samples, 5000, TranscribeOptions(language = "auto", profile = AccuracyProfile.FAST, threads = 2),
        object : TranscribeListener {
            override fun onProgress(percent: Int) { print("progress=$percent ") }
            override fun onSegments(segments: List<Segment>) { partials += segments.size }
        }, signal)
    println()
    when (r) {
        is EngineResult.Success -> {
            println("OK in ${System.currentTimeMillis() - t0} ms, lang=${r.detectedLanguage}, segments=${r.segments.size}, partials=$partials")
            r.segments.take(5).forEach { s ->
                println("[${s.startMs}-${s.endMs}] '${s.text}' words=${s.tokens.size} meanP=${s.meanProbability}")
                s.tokens.take(6).forEach { w -> println("    '${w.text}' ${w.startMs}-${w.endMs} p=${w.probability}") }
            }
            check(r.segments.all { it.startMs >= 5000 }) { "offset not applied" }
        }
        is EngineResult.Cancelled -> println("CANCELLED after ${System.currentTimeMillis() - t0} ms")
        is EngineResult.Failure -> { println("FAILURE: ${r.reason}"); kotlin.system.exitProcess(1) }
    }
    engine.close()
}
