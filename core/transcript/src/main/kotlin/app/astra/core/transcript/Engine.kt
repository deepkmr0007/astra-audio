package app.astra.core.transcript

/**
 * Engine adapter contract. Engines are swappable: the app only depends on this interface,
 * so a new on-device model/runtime can be added without rewriting the app.
 */
interface TranscriptionEngine {
    val id: String
    val displayName: String

    /**
     * True only when it has been VERIFIED that recognition runs on this device with no network
     * (e.g. whisper.cpp in-process, or Android's on-device recognizer API on API 31+).
     */
    val verifiedOffline: Boolean

    val capabilities: EngineCapabilities

    /**
     * Transcribes 16 kHz mono float PCM. [offsetMs] is added to every timestamp so chunks of a long
     * recording keep their absolute timing. Must honour [cancel] promptly.
     */
    suspend fun transcribe(
        pcm: FloatArray,
        offsetMs: Long,
        options: TranscribeOptions,
        listener: TranscribeListener,
        cancel: CancelSignal,
    ): EngineResult
}

data class EngineCapabilities(
    val languages: Set<String>,          // ISO codes; "*" = every language the model was trained on
    val languageDetection: Boolean,
    val translateToEnglish: Boolean,
    val tokenProbabilities: Boolean,
    val tokenTimestamps: Boolean,
    val speakerTurns: Boolean,            // tinydiarize models only
)

/** Accuracy / performance profiles shown to the user with their trade-offs. */
enum class AccuracyProfile(
    val label: String,
    val tradeOff: String,
    val beamSize: Int,       // 0 = greedy
    val bestOf: Int,
    val temperatureFallback: Boolean,
) {
    FAST("Fast", "Greedy decoding. Lowest battery use, heat and latency; more mistakes on noisy or accented speech.", 0, 1, false),
    BALANCED("Balanced", "Greedy with best-of-5 and temperature fallback on hard passages. Good default.", 0, 5, true),
    HIGHEST("Highest practical accuracy", "Beam search (5 beams) with fallback. Typically 2–4× slower than Fast, uses more battery and can warm the phone.", 5, 5, true),
}

enum class TranscriptStyle(val label: String) {
    /** Tries to keep fillers, repetitions and false starts — as far as the model permits. */
    VERBATIM("Verbatim"),
    /** Normal model output; a cleaned view can be derived later without losing the original. */
    STANDARD("Standard"),
}

data class TranscribeOptions(
    val language: String = "auto",
    val translateToEnglish: Boolean = false,
    val profile: AccuracyProfile = AccuracyProfile.BALANCED,
    val style: TranscriptStyle = TranscriptStyle.STANDARD,
    val threads: Int = 4,
    val initialPrompt: String? = null,
    val tokenTimestamps: Boolean = true,
    val speakerTurns: Boolean = false,
) {
    fun summary(): String = buildString {
        append("language=").append(language)
        if (translateToEnglish) append(", translate→en")
        append(", profile=").append(profile.name)
        append(", style=").append(style.name)
        append(", threads=").append(threads)
        if (initialPrompt != null) append(", glossary prompt")
        if (speakerTurns) append(", speaker turns")
    }
}

interface TranscribeListener {
    /** 0..100 for the current call. */
    fun onProgress(percent: Int) {}
    /** New segments as soon as the engine emits them (for progressive display). */
    fun onSegments(segments: List<Segment>) {}
}

/** Thread-safe cancellation flag shared with native code via polling. */
class CancelSignal {
    @Volatile var cancelled: Boolean = false
        private set
    fun cancel() { cancelled = true }
}

sealed class EngineResult {
    data class Success(val segments: List<Segment>, val detectedLanguage: String?) : EngineResult()
    data class Failure(val reason: String) : EngineResult()
    data object Cancelled : EngineResult()
}
