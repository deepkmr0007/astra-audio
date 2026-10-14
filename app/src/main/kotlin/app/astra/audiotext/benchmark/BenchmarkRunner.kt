package app.astra.audiotext.benchmark

import android.os.Build
import app.astra.audiotext.audio.AudioDecoder
import app.astra.audiotext.audio.PcmCache
import app.astra.audiotext.device.DeviceInfo
import app.astra.audiotext.engine.EngineManager
import app.astra.audiotext.storage.LibraryRepository
import app.astra.core.audio.Dsp
import app.astra.core.common.Json
import app.astra.core.text.ErrorRate
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.CancelSignal
import app.astra.core.transcript.EngineResult
import app.astra.core.transcript.TranscribeListener
import app.astra.core.transcript.TranscribeOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local accuracy benchmark. The user pairs library recordings with reference transcripts they
 * trust; the app transcribes each with the active model and reports WER/CER and speed. Everything
 * stays on the phone; no result is ever invented — if a case fails it is reported as failed.
 */
class BenchmarkRunner(
    private val library: LibraryRepository,
    private val engines: EngineManager,
    private val decoder: AudioDecoder,
    private val device: DeviceInfo,
) {
    data class Case(val itemId: String, val title: String, val reference: String, val language: String)

    data class CaseResult(
        val title: String, val language: String, val audioMs: Long, val processingMs: Long,
        val wer: ErrorRate.Result?, val cer: ErrorRate.Result?, val hypothesis: String, val error: String?,
    ) {
        val realTimeFactor: Double get() = if (audioMs == 0L) 0.0 else processingMs.toDouble() / audioMs
    }

    data class Report(
        val modelId: String, val modelSha256: String, val engine: String, val profile: AccuracyProfile, val threads: Int,
        val device: String, val normalization: String, val cases: List<CaseResult>, val createdMillis: Long,
    ) {
        val corpusWer: Double get() = ErrorRate.corpus(cases.mapNotNull { it.wer })
        val corpusCer: Double get() = ErrorRate.corpus(cases.mapNotNull { it.cer })

        fun toJson(): String = Json.write(mapOf(
            "tool" to "ASTRA Audio-Text local benchmark",
            "created" to createdMillis, "model" to modelId, "modelSha256" to modelSha256, "engine" to engine,
            "profile" to profile.name, "threads" to threads, "device" to device, "normalization" to normalization,
            "corpusWER" to corpusWer, "corpusCER" to corpusCer,
            "cases" to cases.map { c -> mapOf(
                "title" to c.title, "language" to c.language, "audioMs" to c.audioMs, "processingMs" to c.processingMs,
                "rtf" to c.realTimeFactor, "wer" to c.wer?.rate, "cer" to c.cer?.rate,
                "werDetail" to c.wer?.let { mapOf("S" to it.substitutions, "D" to it.deletions, "I" to it.insertions, "N" to it.referenceLength) },
                "error" to c.error, "hypothesis" to c.hypothesis) },
            "caveats" to listOf(
                "Results apply only to these recordings, this model, these settings and this phone.",
                "WER/CER depend on text normalisation (listed above); punctuation and casing were ignored.",
                "CER counts Unicode code points; Devanagari conjuncts span several code points.",
            ),
        ), pretty = true)
    }

    companion object { const val MAX_CASE_SECONDS = 600 }

    private val _progress = MutableStateFlow<String?>(null)
    val progress: StateFlow<String?> = _progress.asStateFlow()

    suspend fun run(cases: List<Case>, profile: AccuracyProfile, threads: Int, cancel: CancelSignal): Report {
        val norm = ErrorRate.Normalization()
        val results = ArrayList<CaseResult>()
        var modelId = "none"; var sha = ""; var engineName = ""
        engines.use { load ->
            val ready = load as? EngineManager.Load.Ready
            if (ready == null) {
                cases.forEach { results.add(CaseResult(it.title, it.language, 0, 0, null, null, "", (load as EngineManager.Load.Unavailable).reason)) }
                return@use
            }
            modelId = ready.spec.id; sha = ready.model.sha256; engineName = ready.engine.displayName
            for ((i, c) in cases.withIndex()) {
                if (cancel.cancelled) break
                _progress.value = "Case ${i + 1} of ${cases.size}: ${c.title}"
                try {
                    if (!library.pcmCacheFile(c.itemId).exists()) decoder.decodeToCache(c.itemId, false, {}, { cancel.cancelled })
                    val cache = PcmCache(library.pcmCacheFile(c.itemId), library)
                    val total = cache.totalSamples()
                    if (total > MAX_CASE_SECONDS * Dsp.WHISPER_RATE) throw IllegalArgumentException("Benchmark clips must be 10 minutes or shorter.")
                    val pcm = cache.read(0, total)
                    val t0 = System.nanoTime()
                    val r = ready.engine.transcribe(pcm, 0, TranscribeOptions(language = c.language, profile = profile, threads = threads),
                        object : TranscribeListener {}, cancel)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val audioMs = pcm.size * 1000L / Dsp.WHISPER_RATE
                    when (r) {
                        is EngineResult.Success -> {
                            val hyp = r.segments.joinToString(" ") { it.text.trim() }
                            results.add(CaseResult(c.title, c.language, audioMs, ms, ErrorRate.wer(c.reference, hyp, norm), ErrorRate.cer(c.reference, hyp, norm), hyp, null))
                        }
                        is EngineResult.Failure -> results.add(CaseResult(c.title, c.language, audioMs, ms, null, null, "", r.reason))
                        EngineResult.Cancelled -> break
                    }
                } catch (e: Exception) {
                    results.add(CaseResult(c.title, c.language, 0, 0, null, null, "", e.message ?: "failed"))
                }
            }
        }
        _progress.value = null
        val snap = device.snapshot()
        val deviceDesc = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${snap.cpuCores} cores, ${snap.totalRamBytes / 1_000_000} MB RAM, ABI ${Build.SUPPORTED_ABIS.firstOrNull()}"
        return Report(modelId, sha, engineName, profile, threads, deviceDesc, norm.describe(), results, System.currentTimeMillis())
    }
}
