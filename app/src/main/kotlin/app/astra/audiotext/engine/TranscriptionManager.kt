package app.astra.audiotext.engine

import app.astra.audiotext.audio.AudioDecoder
import app.astra.audiotext.audio.PcmCache
import app.astra.audiotext.device.DeviceInfo
import app.astra.audiotext.settings.SettingsStore
import app.astra.audiotext.storage.LibraryRepository
import app.astra.core.audio.ChunkPlanner
import app.astra.core.audio.Dsp
import app.astra.core.common.Ids
import app.astra.core.common.Json
import app.astra.core.common.bool
import app.astra.core.common.str
import app.astra.core.models.ResourceAdvisor
import app.astra.core.text.Glossary
import app.astra.core.transcript.AccuracyProfile
import app.astra.core.transcript.CancelSignal
import app.astra.core.transcript.Checkpoint
import app.astra.core.transcript.CheckpointStore
import app.astra.core.transcript.ChunkSpec
import app.astra.core.transcript.EngineInfo
import app.astra.core.transcript.PipelineListener
import app.astra.core.transcript.PipelineResult
import app.astra.core.transcript.Segment
import app.astra.core.transcript.TranscribeOptions
import app.astra.core.transcript.Transcript
import app.astra.core.transcript.TranscriptStyle
import app.astra.core.transcript.TranscriptionPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What the user chose on the transcription panel. Persisted so an interrupted job resumes identically. */
data class JobRequest(
    val itemId: String,
    val language: String,
    val translateToEnglish: Boolean,
    val profile: AccuracyProfile,
    val style: TranscriptStyle,
    val speakerTurns: Boolean,
    val useGlossary: Boolean,
    val useCleanAudio: Boolean,
) {
    fun toJson(): String = Json.write(mapOf("item" to itemId, "lang" to language, "translate" to translateToEnglish, "profile" to profile.name,
        "style" to style.name, "turns" to speakerTurns, "glossary" to useGlossary, "clean" to useCleanAudio))

    companion object {
        fun fromJson(s: String): JobRequest? = try {
            val m = Json.parseObject(s)
            JobRequest(m.str("item")!!, m.str("lang") ?: "auto", m.bool("translate") ?: false,
                AccuracyProfile.valueOf(m.str("profile") ?: "BALANCED"), TranscriptStyle.valueOf(m.str("style") ?: "STANDARD"),
                m.bool("turns") ?: false, m.bool("glossary") ?: true, m.bool("clean") ?: false)
        } catch (e: Exception) { null }
    }
}

data class JobState(
    val itemId: String,
    val phase: Phase,
    val percent: Int = 0,
    val chunk: Int = 0,
    val chunks: Int = 0,
    val partial: List<Segment> = emptyList(),
    val message: String? = null,
    val startedMillis: Long = System.currentTimeMillis(),
) {
    enum class Phase { PREPARING, DECODING, TRANSCRIBING, DONE, FAILED, CANCELLED }
    val running: Boolean get() = phase == Phase.PREPARING || phase == Phase.DECODING || phase == Phase.TRANSCRIBING
}

/**
 * Runs transcription / speech translation jobs in an app-scoped coroutine (they survive screen
 * changes). Progress is checkpointed per chunk in an encrypted file, so after the app is killed the
 * job can be resumed from the last finished chunk. All processing is local.
 */
class TranscriptionManager(
    private val library: LibraryRepository,
    private val engines: EngineManager,
    private val decoder: AudioDecoder,
    private val settings: SettingsStore,
    private val device: DeviceInfo,
    private val scope: CoroutineScope,
) {
    private val _jobs = MutableStateFlow<Map<String, JobState>>(emptyMap())
    val jobs: StateFlow<Map<String, JobState>> = _jobs.asStateFlow()
    private val signals = HashMap<String, CancelSignal>()

    private fun set(s: JobState) { _jobs.value = _jobs.value + (s.itemId to s) }

    fun translationFile(itemId: String) = File(library.dir(itemId), "translation.enc")
    private fun requestFile(itemId: String) = File(library.dir(itemId), "job.enc")

    fun readTranslation(itemId: String): Transcript? = translationFile(itemId).takeIf { it.exists() }?.let {
        try { Transcript.fromJson(String(library.readEncrypted(it))) } catch (e: Exception) { null }
    }

    /** Items with an interrupted job that can be resumed. */
    fun resumable(): List<JobRequest> = library.items.value.mapNotNull { item ->
        val cp = library.checkpointFile(item.id)
        val rq = requestFile(item.id)
        if (cp.exists() && rq.exists() && _jobs.value[item.id]?.running != true) {
            try { JobRequest.fromJson(String(library.readEncrypted(rq))) } catch (e: Exception) { null }
        } else null
    }

    fun discardResume(itemId: String) { library.checkpointFile(itemId).delete(); requestFile(itemId).delete() }

    fun cancel(itemId: String) { signals[itemId]?.cancel() }

    fun dismiss(itemId: String) { if (_jobs.value[itemId]?.running != true) _jobs.value = _jobs.value - itemId }

    fun start(request: JobRequest) {
        val id = request.itemId
        if (_jobs.value[id]?.running == true) return
        val signal = CancelSignal()
        signals[id] = signal
        set(JobState(id, JobState.Phase.PREPARING))
        scope.launch(Dispatchers.Default) {
            try {
                run(request, signal)
            } catch (e: java.util.concurrent.CancellationException) {
                set(JobState(id, JobState.Phase.CANCELLED, message = "Cancelled. Finished parts are kept; you can resume."))
            } catch (e: Exception) {
                set(JobState(id, JobState.Phase.FAILED, message = e.message ?: "Transcription failed"))
            } finally {
                signals.remove(id)
            }
        }
    }

    private suspend fun run(request: JobRequest, signal: CancelSignal) {
        val id = request.itemId
        val item = library.get(id) ?: throw IllegalStateException("Item not found")
        library.writeEncrypted(requestFile(id), request.toJson().toByteArray())

        // 1) Decode to the 16 kHz cache (once per source).
        val cacheFile = library.pcmCacheFile(id)
        val sourceTag = File(library.dir(id), "pcm16k.source")
        val wantedTag = if (request.useCleanAudio) "clean" else "original"
        if (!cacheFile.exists() || !sourceTag.exists() || sourceTag.readText() != wantedTag) {
            set(JobState(id, JobState.Phase.DECODING))
            val decoded = decoder.decodeToCache(id, request.useCleanAudio, { p -> set(JobState(id, JobState.Phase.DECODING, (p * 100).toInt())) }, { signal.cancelled })
            sourceTag.writeText(wantedTag)
            library.update(id) { it.copy(durationMs = decoded.durationMs, qualityIssues = decoded.quality.issues.map { q -> q.name }) }
        }
        val cache = PcmCache(cacheFile, library)
        val total = cache.totalSamples()
        if (total < Dsp.WHISPER_RATE / 2) throw IllegalStateException("The recording is too short to transcribe (under half a second).")

        // 2) Plan chunks at quiet points.
        val s = settings.value
        val chunks = ChunkPlanner.plan(total, Dsp.WHISPER_RATE, s.chunkSeconds.coerceIn(30, 600), 10) { a, b -> cache.quietest(a, b) }
            .map { ChunkSpec(it.index, it.startMs(Dsp.WHISPER_RATE), it.endMs(Dsp.WHISPER_RATE)) }

        // 3) Transcribe with the active model.
        engines.use { load ->
            val ready = load as? EngineManager.Load.Ready ?: throw IllegalStateException((load as EngineManager.Load.Unavailable).reason)
            if (request.translateToEnglish && !ready.engine.capabilities.translateToEnglish) {
                throw IllegalStateException("The active model cannot translate speech to English. Choose a multilingual non-turbo model (e.g. Whisper base or small).")
            }
            val glossary = Glossary.parse(s.glossary)
            val options = TranscribeOptions(
                language = request.language,
                translateToEnglish = request.translateToEnglish,
                profile = request.profile,
                style = request.style,
                threads = if (s.threads > 0) s.threads else ResourceAdvisor.threads(device.snapshot()),
                initialPrompt = if (request.useGlossary && s.useGlossaryPrompt) glossary.initialPrompt() else null,
                tokenTimestamps = true,
                speakerTurns = request.speakerTurns && ready.spec.speakerTurns,
            )
            set(JobState(id, JobState.Phase.TRANSCRIBING, 0, 0, chunks.size))
            val pipeline = TranscriptionPipeline(ready.engine, EncryptedCheckpointStore(library, id))
            val result = pipeline.run(
                jobId = id + if (request.translateToEnglish) ":translate" else ":transcribe",
                sourceItemId = id,
                chunks = chunks,
                loadChunk = { c -> cache.read((c.startMs * Dsp.WHISPER_RATE / 1000).toInt(), (c.endMs * Dsp.WHISPER_RATE / 1000).toInt()) },
                options = options,
                listener = object : PipelineListener {
                    override fun onProgress(chunkIndex: Int, totalChunks: Int, overallPercent: Int) {
                        val cur = _jobs.value[id] ?: return
                        set(cur.copy(phase = JobState.Phase.TRANSCRIBING, percent = overallPercent, chunk = chunkIndex + 1, chunks = totalChunks))
                    }
                    override fun onPartial(allSegmentsSoFar: List<Segment>) {
                        val cur = _jobs.value[id] ?: return
                        set(cur.copy(partial = allSegmentsSoFar))
                    }
                },
                cancel = signal,
            )
            when (result) {
                is PipelineResult.Cancelled -> set(JobState(id, JobState.Phase.CANCELLED, message =
                    "Cancelled after ${result.completedChunks} of ${result.totalChunks} parts. Finished parts are kept; you can resume."))
                is PipelineResult.Failed -> set(JobState(id, JobState.Phase.FAILED, message = "Part ${result.chunkIndex + 1}: ${result.reason}"))
                is PipelineResult.Done -> {
                    val now = System.currentTimeMillis()
                    val warnings = buildList {
                        library.get(id)?.qualityIssues?.forEach { add("quality:$it") }
                        if (request.language == "auto") add("language:auto-detected:${result.detectedLanguage ?: "unknown"}")
                        if (request.translateToEnglish) add("translation:machine")
                        if (options.speakerTurns) add("speakers:turns-only")
                        if (result.segments.isEmpty()) add("empty:no-speech")
                    }
                    val t = Transcript(
                        id = Ids.newId(),
                        title = item.title,
                        createdMillis = now,
                        sourceItemId = id,
                        requestedLanguage = request.language,
                        detectedLanguage = result.detectedLanguage,
                        translatedToEnglish = request.translateToEnglish,
                        engine = EngineInfo(ready.engine.id, ready.engine.displayName, ready.spec.id, ready.model.sha256,
                            request.profile.name, options.summary(), ready.engine.verifiedOffline),
                        raw = result.segments,
                        corrected = result.segments,
                        warnings = warnings,
                        tags = item.tags,
                    )
                    if (request.translateToEnglish) library.writeEncrypted(translationFile(id), t.toJson().toByteArray())
                    else library.saveTranscript(t)
                    requestFile(id).delete()
                    set(JobState(id, JobState.Phase.DONE, 100, chunks.size, chunks.size, result.segments))
                }
            }
        }
    }
}

/** Checkpoints stored encrypted next to the item. */
class EncryptedCheckpointStore(private val library: LibraryRepository, private val itemId: String) : CheckpointStore {
    private val file get() = library.checkpointFile(itemId)
    override fun load(jobId: String): Checkpoint? = try {
        if (!file.exists()) null else Checkpoint.fromJson(String(library.readEncrypted(file))).takeIf { it.jobId == jobId }
    } catch (e: Exception) { null }
    override fun save(checkpoint: Checkpoint) = library.writeEncrypted(file, checkpoint.toJson().toByteArray())
    override fun delete(jobId: String) { file.delete() }
}
