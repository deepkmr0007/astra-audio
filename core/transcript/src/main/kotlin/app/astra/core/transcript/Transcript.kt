package app.astra.core.transcript

import app.astra.core.common.Json
import app.astra.core.common.bool
import app.astra.core.common.double
import app.astra.core.common.long
import app.astra.core.common.int
import app.astra.core.common.objList
import app.astra.core.common.str
import app.astra.core.common.list

/**
 * A token as produced by the engine. [probability] is the engine's own token probability and is
 * null when the engine does not supply one — the app never fabricates confidence values.
 * Timestamps are null unless the engine computed token-level timing.
 */
data class TokenInfo(val text: String, val startMs: Long?, val endMs: Long?, val probability: Float?)

data class Segment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val tokens: List<TokenInfo> = emptyList(),
    /** Neutral label ("Speaker 1") or a user-assigned name. Null when diarization was not run. */
    val speaker: String? = null,
    /** tinydiarize marks a probable speaker change AFTER this segment (model-dependent, can be wrong). */
    val speakerTurnNext: Boolean = false,
) {
    /** Mean engine token probability over word tokens, or null when the engine supplied none. */
    val meanProbability: Float?
        get() {
            val ps = tokens.mapNotNull { it.probability }
            return if (ps.isEmpty()) null else ps.average().toFloat()
        }

    /** Lowest token probability — a single garbled word is easier to spot than an average. */
    val minProbability: Float? get() = tokens.mapNotNull { it.probability }.minOrNull()

    fun isUncertain(threshold: Float = UNCERTAIN_THRESHOLD): Boolean {
        val mean = meanProbability ?: return false
        val min = minProbability ?: mean
        return mean < threshold || min < threshold / 2
    }

    companion object {
        const val UNCERTAIN_THRESHOLD = 0.55f
    }
}

/** Provenance of a change in the corrected layer. */
data class CorrectionRecord(val segmentIndex: Int, val kind: String, val before: String, val after: String, val timeMillis: Long)

data class EngineInfo(
    val engineId: String,
    val engineName: String,
    val modelId: String,
    val modelSha256: String?,
    val profile: String,
    val settingsSummary: String,
    val ranOffline: Boolean,
)

/**
 * A transcript keeps the engine's RAW output untouched and a separate CORRECTED layer that the
 * user edits (and that glossary corrections are applied to). Exports can use either.
 */
data class Transcript(
    val id: String,
    val title: String,
    val createdMillis: Long,
    val sourceItemId: String?,
    val requestedLanguage: String,
    val detectedLanguage: String?,
    val translatedToEnglish: Boolean,
    val engine: EngineInfo?,
    val raw: List<Segment>,
    val corrected: List<Segment>,
    val corrections: List<CorrectionRecord> = emptyList(),
    val warnings: List<String> = emptyList(),
    val tags: Set<String> = emptySet(),
    val speakerNames: Map<String, String> = emptyMap(),
) {
    val durationMs: Long get() = (corrected.maxOfOrNull { it.endMs } ?: 0L)

    fun plainText(layer: Layer = Layer.CORRECTED): String =
        (if (layer == Layer.RAW) raw else corrected).joinToString(" ") { it.text.trim() }.trim()

    enum class Layer { RAW, CORRECTED }

    fun withEditedSegment(index: Int, newText: String, now: Long): Transcript {
        if (index !in corrected.indices) return this
        val old = corrected[index]
        if (old.text == newText) return this
        val updated = corrected.toMutableList().also { it[index] = old.copy(text = newText) }
        return copy(corrected = updated, corrections = corrections + CorrectionRecord(index, "manual edit", old.text, newText, now))
    }

    fun withSpeaker(index: Int, speaker: String?): Transcript {
        if (index !in corrected.indices) return this
        return copy(corrected = corrected.toMutableList().also { it[index] = it[index].copy(speaker = speaker) })
    }

    /** Renames a speaker label everywhere in the corrected layer. */
    fun renameSpeaker(from: String, to: String): Transcript =
        copy(corrected = corrected.map { if (it.speaker == from) it.copy(speaker = to) else it })

    fun resetToRaw(now: Long): Transcript = copy(corrected = raw, corrections = corrections + CorrectionRecord(-1, "reset to raw", "", "", now))

    // ------------------------------------------------------------ persistence (app-internal format)

    fun toJson(pretty: Boolean = false): String = Json.write(toMap(), pretty)

    fun toMap(): Map<String, Any?> = mapOf(
        "format" to "astra-transcript",
        "version" to 1,
        "id" to id,
        "title" to title,
        "created" to createdMillis,
        "sourceItemId" to sourceItemId,
        "requestedLanguage" to requestedLanguage,
        "detectedLanguage" to detectedLanguage,
        "translatedToEnglish" to translatedToEnglish,
        "engine" to engine?.let {
            mapOf("id" to it.engineId, "name" to it.engineName, "model" to it.modelId, "modelSha256" to it.modelSha256,
                "profile" to it.profile, "settings" to it.settingsSummary, "offline" to it.ranOffline)
        },
        "raw" to raw.map { segmentToMap(it) },
        "corrected" to corrected.map { segmentToMap(it) },
        "corrections" to corrections.map { mapOf("segment" to it.segmentIndex, "kind" to it.kind, "before" to it.before, "after" to it.after, "t" to it.timeMillis) },
        "warnings" to warnings,
        "tags" to tags.toList(),
        "speakerNames" to speakerNames,
    )

    companion object {
        fun segmentToMap(s: Segment): Map<String, Any?> = mapOf(
            "start" to s.startMs, "end" to s.endMs, "text" to s.text, "speaker" to s.speaker, "turnNext" to s.speakerTurnNext,
            "tokens" to s.tokens.map { mapOf("t" to it.text, "s" to it.startMs, "e" to it.endMs, "p" to it.probability) },
        )

        fun segmentFromMap(m: Map<String, Any?>): Segment = Segment(
            startMs = m.long("start") ?: 0, endMs = m.long("end") ?: 0, text = m.str("text") ?: "",
            tokens = m.objList("tokens").map { TokenInfo(it.str("t") ?: "", it.long("s"), it.long("e"), it.double("p")?.toFloat()) },
            speaker = m.str("speaker"), speakerTurnNext = m.bool("turnNext") ?: false,
        )

        fun fromJson(json: String): Transcript {
            val m = Json.parseObject(json)
            require(m.str("format") == "astra-transcript") { "Not an ASTRA transcript" }
            val e = m["engine"] as? Map<*, *>
            @Suppress("UNCHECKED_CAST")
            val em = e as Map<String, Any?>?
            return Transcript(
                id = m.str("id") ?: error("missing id"),
                title = m.str("title") ?: "Untitled",
                createdMillis = m.long("created") ?: 0,
                sourceItemId = m.str("sourceItemId"),
                requestedLanguage = m.str("requestedLanguage") ?: "auto",
                detectedLanguage = m.str("detectedLanguage"),
                translatedToEnglish = m.bool("translatedToEnglish") ?: false,
                engine = em?.let {
                    EngineInfo(it.str("id") ?: "", it.str("name") ?: "", it.str("model") ?: "", it.str("modelSha256"),
                        it.str("profile") ?: "", it.str("settings") ?: "", it.bool("offline") ?: false)
                },
                raw = m.objList("raw").map { segmentFromMap(it) },
                corrected = m.objList("corrected").map { segmentFromMap(it) },
                corrections = m.objList("corrections").map {
                    CorrectionRecord(it.int("segment") ?: -1, it.str("kind") ?: "", it.str("before") ?: "", it.str("after") ?: "", it.long("t") ?: 0)
                },
                warnings = m.list("warnings")?.mapNotNull { it as? String } ?: emptyList(),
                tags = m.list("tags")?.mapNotNull { it as? String }?.toSet() ?: emptySet(),
                speakerNames = (m["speakerNames"] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() } ?: emptyMap(),
            )
        }
    }
}
