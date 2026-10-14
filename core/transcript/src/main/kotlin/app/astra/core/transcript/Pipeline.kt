package app.astra.core.transcript

import app.astra.core.common.Json
import app.astra.core.common.int
import app.astra.core.common.objList
import app.astra.core.common.str

/** One piece of a long recording, cut at a quiet point by the audio chunk planner. */
data class ChunkSpec(val index: Int, val startMs: Long, val endMs: Long)

data class ChunkResult(val index: Int, val segments: List<Segment>, val detectedLanguage: String?)

/**
 * Persisted progress of a transcription job. After process death (or a user cancel) the job
 * resumes from the first chunk that is not in [completed]; finished chunks are never redone.
 */
data class Checkpoint(
    val jobId: String,
    val sourceItemId: String,
    val optionsSummary: String,
    val totalChunks: Int,
    val completed: List<ChunkResult>,
    val lockedLanguage: String?,
) {
    fun toJson(): String = Json.write(mapOf(
        "jobId" to jobId, "source" to sourceItemId, "options" to optionsSummary, "total" to totalChunks,
        "lockedLanguage" to lockedLanguage,
        "completed" to completed.map { c ->
            mapOf("index" to c.index, "lang" to c.detectedLanguage,
                "segments" to c.segments.map { Transcript.segmentToMap(it) })
        },
    ))

    companion object {
        fun fromJson(json: String): Checkpoint {
            val m = Json.parseObject(json)
            return Checkpoint(
                jobId = m.str("jobId")!!, sourceItemId = m.str("source")!!, optionsSummary = m.str("options") ?: "",
                totalChunks = m.int("total") ?: 0, lockedLanguage = m.str("lockedLanguage"),
                completed = m.objList("completed").map { c ->
                    ChunkResult(c.int("index") ?: 0, c.objList("segments").map { Transcript.segmentFromMap(it) }, c.str("lang"))
                },
            )
        }
    }
}

interface CheckpointStore {
    fun load(jobId: String): Checkpoint?
    fun save(checkpoint: Checkpoint)
    fun delete(jobId: String)
}

class MemoryCheckpointStore : CheckpointStore {
    val map = HashMap<String, Checkpoint>()
    override fun load(jobId: String) = map[jobId]
    override fun save(checkpoint: Checkpoint) { map[checkpoint.jobId] = checkpoint }
    override fun delete(jobId: String) { map.remove(jobId) }
}

interface PipelineListener {
    fun onProgress(chunkIndex: Int, totalChunks: Int, overallPercent: Int) {}
    fun onPartial(allSegmentsSoFar: List<Segment>) {}
}

sealed class PipelineResult {
    data class Done(val segments: List<Segment>, val detectedLanguage: String?) : PipelineResult()
    data class Failed(val chunkIndex: Int, val reason: String) : PipelineResult()
    /** Progress is kept in the checkpoint; running the same job again resumes. */
    data class Cancelled(val completedChunks: Int, val totalChunks: Int) : PipelineResult()
}

class TranscriptionPipeline(
    private val engine: TranscriptionEngine,
    private val store: CheckpointStore,
) {
    suspend fun run(
        jobId: String,
        sourceItemId: String,
        chunks: List<ChunkSpec>,
        loadChunk: suspend (ChunkSpec) -> FloatArray,
        options: TranscribeOptions,
        listener: PipelineListener,
        cancel: CancelSignal,
    ): PipelineResult {
        var cp = store.load(jobId)?.takeIf { it.totalChunks == chunks.size && it.optionsSummary == options.summary() }
            ?: Checkpoint(jobId, sourceItemId, options.summary(), chunks.size, emptyList(), null)
        val done = cp.completed.map { it.index }.toSet()
        listener.onPartial(cp.completed.sortedBy { it.index }.flatMap { it.segments })

        for (chunk in chunks.sortedBy { it.index }) {
            if (chunk.index in done) continue
            if (cancel.cancelled) return PipelineResult.Cancelled(cp.completed.size, chunks.size)

            // Continuity: give the engine the tail of the previous chunk as context, after the glossary.
            val previousText = cp.completed.filter { it.index < chunk.index }.maxByOrNull { it.index }
                ?.segments?.joinToString(" ") { it.text.trim() }?.takeLast(160)
            val prompt = listOfNotNull(options.initialPrompt, previousText?.takeIf { it.isNotBlank() }).joinToString(" ").ifBlank { null }
            // Lock the language detected on the first chunk so later chunks don't flip between languages.
            val language = if (options.language == "auto" && cp.lockedLanguage != null) cp.lockedLanguage!! else options.language
            val chunkOptions = options.copy(initialPrompt = prompt, language = language)

            val pcm = try { loadChunk(chunk) } catch (e: Exception) {
                return PipelineResult.Failed(chunk.index, "Could not read audio: ${e.message ?: e.javaClass.simpleName}")
            }
            val completedBefore = cp.completed.size
            val result = engine.transcribe(pcm, chunk.startMs, chunkOptions, object : TranscribeListener {
                override fun onProgress(percent: Int) {
                    val overall = ((completedBefore + percent / 100.0) / chunks.size * 100).toInt().coerceIn(0, 100)
                    listener.onProgress(chunk.index, chunks.size, overall)
                }
            }, cancel)

            when (result) {
                is EngineResult.Cancelled -> return PipelineResult.Cancelled(cp.completed.size, chunks.size)
                is EngineResult.Failure -> return PipelineResult.Failed(chunk.index, result.reason)
                is EngineResult.Success -> {
                    val segs = result.segments.map { s ->
                        // Clamp into the chunk window so rounding never makes segments overlap the next chunk.
                        s.copy(startMs = s.startMs.coerceIn(chunk.startMs, chunk.endMs), endMs = s.endMs.coerceIn(chunk.startMs, chunk.endMs))
                    }
                    cp = cp.copy(
                        completed = cp.completed + ChunkResult(chunk.index, segs, result.detectedLanguage),
                        lockedLanguage = cp.lockedLanguage ?: result.detectedLanguage?.takeIf { options.language == "auto" },
                    )
                    store.save(cp)
                    listener.onProgress(chunk.index, chunks.size, (cp.completed.size * 100) / chunks.size)
                    listener.onPartial(cp.completed.sortedBy { it.index }.flatMap { it.segments })
                }
            }
        }
        val all = cp.completed.sortedBy { it.index }.flatMap { it.segments }
        val detected = cp.lockedLanguage ?: cp.completed.firstNotNullOfOrNull { it.detectedLanguage }
        store.delete(jobId)
        return PipelineResult.Done(all, detected)
    }
}
