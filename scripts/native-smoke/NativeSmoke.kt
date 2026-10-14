// Host smoke test / benchmark for the JNI bridge: loads libastra_whisper (built for the host), runs
// transcriptions through WhisperEngine and prints what came back. Used by scripts/verify-native.sh
// and by CI's real-model benchmark (-Dastra.reference="…" prints WER lines prefixed "BENCH").
import app.astra.core.audio.Dsp
import app.astra.core.audio.Wav
import app.astra.core.text.ErrorRate
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.CancelSignal
import app.astra.core.transcript.EngineResult
import app.astra.core.transcript.Segment
import app.astra.core.transcript.SilenceAware
import app.astra.core.transcript.TranscribeListener
import app.astra.core.transcript.TranscribeOptions
import app.astra.engine.whisper.WhisperEngine
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) = runBlocking {
    val model = File(args[0]); val wav = File(args[1])
    val cancelAfterMs = args.getOrNull(2)?.toLongOrNull()
    val reference = System.getProperty("astra.reference") ?: System.getenv("ASTRA_REFERENCE")
    val threads = (System.getProperty("astra.threads") ?: System.getenv("ASTRA_THREADS") ?: "2").toInt()
    val libDir = System.getProperty("astra.libdir") ?: ""
    println("backend: " + WhisperEngine.backendInfo(libDir))
    val tl = System.nanoTime()
    val engine = WhisperEngine.load(model, "smoke", speakerTurns = false, translate = true, nativeLibDir = libDir) ?: error("model failed to load")
    val loadMs = (System.nanoTime() - tl) / 1_000_000
    println("engine: ${engine.displayName} multilingual=${engine.multilingual} load=${loadMs}ms")
    val (pcm, rate) = wav.inputStream().use { Wav.readMono(it) }
    val samples = Dsp.resample(pcm, rate, 16000)
    val audioMs = samples.size * 1000L / 16000
    val profiles = if (reference != null) listOf(AccuracyProfile.FAST, AccuracyProfile.BALANCED, AccuracyProfile.HIGHEST) else listOf(AccuracyProfile.FAST)
    for (profile in profiles) {
        val signal = CancelSignal()
        if (cancelAfterMs != null) Thread { Thread.sleep(cancelAfterMs); signal.cancel() }.start()
        var partials = 0
        val t0 = System.currentTimeMillis()
        val lang = if (engine.multilingual) "auto" else "en"
        val r = engine.transcribe(samples, 5000, TranscribeOptions(language = lang, profile = profile, threads = threads, skipSilence = false),
            object : TranscribeListener {
                override fun onProgress(percent: Int) { if (reference == null) print("progress=$percent ") }
                override fun onSegments(segments: List<Segment>) { partials += segments.size }
            }, signal)
        val took = System.currentTimeMillis() - t0
        if (reference == null) println()
        when (r) {
            is EngineResult.Success -> {
                val text = r.segments.joinToString(" ") { it.text.trim() }
                check(r.segments.all { it.startMs >= 5000 }) { "offset not applied" }
                if (reference != null) {
                    val w = ErrorRate.wer(reference, text); val c = ErrorRate.cer(reference, text)
                    println("BENCH profile=$profile threads=$threads audio=${audioMs}ms took=${took}ms rtf=${"%.3f".format(took.toDouble() / audioMs)} " +
                        "WER=${"%.3f".format(w.rate)} (S${w.substitutions} D${w.deletions} I${w.insertions} N${w.referenceLength}) CER=${"%.3f".format(c.rate)} lang=${r.detectedLanguage} text=\"$text\"")
                } else {
                    println("OK in $took ms, lang=${r.detectedLanguage}, segments=${r.segments.size}, partials=$partials")
                    r.segments.take(5).forEach { s -> println("[${s.startMs}-${s.endMs}] '${s.text}' words=${s.tokens.size} meanP=${s.meanProbability}") }
                }
            }
            is EngineResult.Cancelled -> println("CANCELLED after $took ms")
            is EngineResult.Failure -> { println("FAILURE: ${r.reason}"); kotlin.system.exitProcess(1) }
        }
    }
    if (reference != null) {
        // Silence skipping: the same speech surrounded by 40 s of silence.
        val padded = FloatArray(16000 * 20) + samples + FloatArray(16000 * 20)
        val t0 = System.currentTimeMillis()
        val lang = if (engine.multilingual) "auto" else "en"
        val r = SilenceAware.transcribe(engine, padded, 0, TranscribeOptions(language = lang, profile = AccuracyProfile.FAST, threads = threads),
            object : TranscribeListener {}, CancelSignal()) as EngineResult.Success
        val took = System.currentTimeMillis() - t0
        val text = r.segments.joinToString(" ") { it.text.trim() }
        val tNo = System.currentTimeMillis()
        engine.transcribe(padded, 0, TranscribeOptions(language = lang, profile = AccuracyProfile.FAST, threads = threads, skipSilence = false),
            object : TranscribeListener {}, CancelSignal())
        val tookNo = System.currentTimeMillis() - tNo
        println("BENCH silence-skip audio=${padded.size / 16}ms with-skip=${took}ms without-skip=${tookNo}ms speedup=${"%.2f".format(tookNo.toDouble() / took.coerceAtLeast(1))}x " +
            "firstStart=${r.segments.firstOrNull()?.startMs}ms WER=${"%.3f".format(ErrorRate.wer(reference, text).rate)}")
    }
    engine.close()
}
