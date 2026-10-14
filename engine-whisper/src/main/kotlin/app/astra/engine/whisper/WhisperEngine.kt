package app.astra.engine.whisper

import app.astra.core.transcript.CancelSignal
import app.astra.core.transcript.EngineCapabilities
import app.astra.core.transcript.EngineResult
import app.astra.core.transcript.Segment
import app.astra.core.transcript.TokenInfo
import app.astra.core.transcript.TranscribeListener
import app.astra.core.transcript.TranscribeOptions
import app.astra.core.transcript.TranscriptStyle
import app.astra.core.transcript.TranscriptionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * whisper.cpp running in-process on the CPU. Verified offline by construction: the native library
 * is built without any networking code (see CMakeLists.txt) and only reads the local model file.
 */
class WhisperEngine private constructor(
    private val handle: Long,
    val modelId: String,
    val multilingual: Boolean,
    private val supportsSpeakerTurns: Boolean,
    private val supportsTranslate: Boolean,
) : TranscriptionEngine, Closeable {

    override val id: String = "whisper.cpp"
    override val displayName: String = "whisper.cpp ${runCatching { WhisperNative.nativeVersion() }.getOrDefault("")}".trim()
    override val verifiedOffline: Boolean = true
    override val capabilities = EngineCapabilities(
        languages = if (multilingual) setOf("*") else setOf("en"),
        languageDetection = multilingual,
        translateToEnglish = multilingual && supportsTranslate,
        tokenProbabilities = true,
        tokenTimestamps = true,
        speakerTurns = supportsSpeakerTurns,
    )

    private val lock = Mutex()
    @Volatile private var closed = false

    override suspend fun transcribe(
        pcm: FloatArray,
        offsetMs: Long,
        options: TranscribeOptions,
        listener: TranscribeListener,
        cancel: CancelSignal,
    ): EngineResult = lock.withLock {
        check(!closed) { "Engine closed" }
        if (pcm.isEmpty()) return@withLock EngineResult.Success(emptyList(), null)
        val language = if (!multilingual) "en" else options.language
        val prompt = buildPrompt(options)
        coroutineScope {
            val run = async(Dispatchers.Default) {
                WhisperNative.nativeTranscribe(
                    handle, pcm, options.threads, language,
                    options.translateToEnglish && capabilities.translateToEnglish,
                    options.profile.beamSize, options.profile.bestOf, options.profile.temperatureFallback,
                    prompt, options.tokenTimestamps, options.speakerTurns && supportsSpeakerTurns,
                )
            }
            // Poll progress, partial segments and cancellation without native->Java callbacks.
            val poller = launch(Dispatchers.Default) {
                var lastProgress = -1
                while (isActive && !run.isCompleted) {
                    if (cancel.cancelled) WhisperNative.nativeRequestCancel(handle)
                    val p = WhisperNative.nativeProgress(handle)
                    if (p != lastProgress) { listener.onProgress(p); lastProgress = p }
                    val partial = drain(offsetMs)
                    if (partial.isNotEmpty()) listener.onSegments(partial)
                    delay(200)
                }
            }
            val rc = run.await()
            poller.cancel()
            when {
                rc == -1 || cancel.cancelled -> EngineResult.Cancelled
                rc != 0 -> EngineResult.Failure("whisper.cpp returned error $rc (out of memory or unsupported input?)")
                else -> withContext(Dispatchers.Default) {
                    drain(offsetMs) // discard leftovers; final results are read below
                    listener.onProgress(100)
                    EngineResult.Success(collect(offsetMs, options), if (language == "auto") WhisperNative.nativeDetectedLanguage(handle) else language)
                }
            }
        }
    }

    private fun buildPrompt(options: TranscribeOptions): String? {
        // Verbatim style: a disfluent prompt nudges Whisper to keep "um", "uh" and repetitions, which
        // it otherwise tends to drop. This is a known, model-dependent effect, not a guarantee.
        val verbatimHint = if (options.style == TranscriptStyle.VERBATIM) "Umm, let me think like, hmm... Okay, so, uh, I I think." else null
        return listOfNotNull(verbatimHint, options.initialPrompt).joinToString(" ").ifBlank { null }
    }

    private fun drain(offsetMs: Long): List<Segment> {
        val arr = WhisperNative.nativeDrainSegments(handle)
        val out = ArrayList<Segment>(arr.size / 2)
        var i = 0
        while (i + 1 < arr.size) {
            val t = arr[i] as LongArray
            val text = decode(arr[i + 1] as ByteArray)
            out.add(Segment(offsetMs + t[0] * 10, offsetMs + t[1] * 10, text))
            i += 2
        }
        return out
    }

    private fun collect(offsetMs: Long, options: TranscribeOptions): List<Segment> {
        val n = WhisperNative.nativeSegmentCount(handle)
        val segments = ArrayList<Segment>(n)
        var speakerIndex = 0
        for (i in 0 until n) {
            val info = WhisperNative.nativeSegmentInfo(handle, i)
            val text = decode(WhisperNative.nativeSegmentText(handle, i))
            val words = words(i, offsetMs, options.tokenTimestamps)
            val turns = options.speakerTurns && supportsSpeakerTurns
            segments.add(
                Segment(
                    startMs = offsetMs + info[0] * 10,
                    endMs = offsetMs + info[1] * 10,
                    text = text,
                    tokens = words,
                    // tinydiarize only marks CHANGES; labels alternate and must be checked by the user.
                    speaker = if (turns) "Speaker ${speakerIndex % 2 + 1}" else null,
                    speakerTurnNext = info[2] == 1L,
                ),
            )
            if (turns && info[2] == 1L) speakerIndex++
        }
        return segments
    }

    /**
     * Re-assembles BPE tokens into words. Whisper marks a new word with a leading space; tokens are
     * concatenated as BYTES before decoding so Devanagari characters split across tokens are rebuilt.
     * A word's probability is the minimum of its tokens' probabilities (engine-supplied, not invented).
     */
    private fun words(seg: Int, offsetMs: Long, withTimes: Boolean): List<TokenInfo> {
        val count = WhisperNative.nativeTokenCount(handle, seg)
        val out = ArrayList<TokenInfo>()
        var bytes = java.io.ByteArrayOutputStream()
        var minP = 1f
        var start = -1L
        var end = -1L
        fun flush() {
            if (bytes.size() == 0) return
            val text = decode(bytes.toByteArray())
            if (text.isNotBlank()) {
                out.add(TokenInfo(text, if (withTimes && start >= 0) offsetMs + start * 10 else null,
                    if (withTimes && end >= 0) offsetMs + end * 10 else null, minP))
            }
            bytes = java.io.ByteArrayOutputStream(); minP = 1f; start = -1; end = -1
        }
        for (j in 0 until count) {
            val tb = WhisperNative.nativeTokenText(handle, seg, j) ?: continue // special token
            val data = WhisperNative.nativeTokenData(handle, seg, j, withTimes)
            val startsWord = tb.isNotEmpty() && tb[0] == ' '.code.toByte()
            val isPunct = tb.isNotEmpty() && tb.all { it.toInt() in 0x21..0x2F || it.toInt() in 0x3A..0x40 }
            if (startsWord && !isPunct) flush()
            bytes.write(tb)
            minP = minOf(minP, data[0])
            if (start < 0 && data[1] >= 0) start = data[1].toLong()
            if (data[2] >= 0) end = data[2].toLong()
        }
        flush()
        return out
    }

    private fun decode(b: ByteArray): String =
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(b)).toString()

    override fun close() {
        if (closed) return
        closed = true
        WhisperNative.nativeFree(handle)
    }

    companion object {
        @Volatile private var backendDevices = -1

        /**
         * Loads the native library and the fastest CPU kernels this phone supports (once per process).
         * [nativeLibDir] is the app's native library directory (ApplicationInfo.nativeLibraryDir).
         */
        @Synchronized
        fun prepare(nativeLibDir: String): Int {
            WhisperNative.ensureLoaded()
            if (backendDevices < 0) backendDevices = WhisperNative.nativeLoadBackends(nativeLibDir)
            return backendDevices
        }

        /** Loads a verified model file. Returns null if whisper.cpp cannot load it (corrupt / out of memory). */
        fun load(model: File, modelId: String, speakerTurns: Boolean, translate: Boolean, nativeLibDir: String): WhisperEngine? {
            if (prepare(nativeLibDir) <= 0) return null
            val h = WhisperNative.nativeInit(model.absolutePath)
            if (h == 0L) return null
            return WhisperEngine(h, modelId, WhisperNative.nativeIsMultilingual(h), speakerTurns, translate)
        }

        fun systemInfo(): String = runCatching { WhisperNative.ensureLoaded(); WhisperNative.nativeSystemInfo() }.getOrDefault("unavailable")

        /** Which CPU kernel variant is active, e.g. "CPU: NEON ARM_FMA FP16_VA DOTPROD LLAMAFILE REPACK". */
        fun backendInfo(nativeLibDir: String): String = runCatching { prepare(nativeLibDir); WhisperNative.nativeBackendInfo() }.getOrDefault("unavailable")
    }
}
