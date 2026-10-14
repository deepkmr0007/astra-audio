package app.astra.audiotext.engine

import app.astra.audiotext.device.DeviceInfo
import app.astra.audiotext.settings.SettingsStore
import app.astra.audiotext.storage.LibraryItem
import app.astra.audiotext.storage.LibraryRepository
import app.astra.core.audio.Dsp
import app.astra.core.audio.LiveWindowing
import app.astra.core.common.Ids
import app.astra.core.text.AutoTitle
import app.astra.core.text.Glossary
import app.astra.core.transcript.CancelSignal
import app.astra.core.transcript.EngineInfo
import app.astra.core.transcript.EngineResult
import app.astra.core.transcript.Segment
import app.astra.core.transcript.SilenceAware
import app.astra.core.transcript.TranscribeListener
import app.astra.core.transcript.TranscribeOptions
import app.astra.core.transcript.Transcript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Live transcription while recording — entirely on the phone.
 *
 * The recorder hands every block of microphone audio to [onPcm]. [LiveWindowing] cuts the growing
 * recording into 20–28 s windows at quiet moments; a single background worker transcribes each
 * window as soon as it is complete (silences skipped, timestamps kept relative to the recording).
 * Text appears on screen while the user is still talking, and when they tap Stop only the last
 * few seconds remain — so the full transcript is ready almost instantly.
 *
 * The same windows are never transcribed twice, the original audio is saved unchanged by the
 * recorder, and if live mode is unavailable (no model, not enough memory) recording carries on and
 * the user can transcribe afterwards as before.
 */
class LiveTranscriber(
    private val engines: EngineManager,
    private val library: LibraryRepository,
    private val settings: SettingsStore,
    private val device: DeviceInfo,
    private val scope: CoroutineScope,
) {
    data class State(
        val active: Boolean = false,
        val segments: List<Segment> = emptyList(),
        val queuedWindows: Int = 0,
        val transcribedUntilMs: Long = 0,
        val finishing: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var windowing: LiveWindowing? = null
    private var channel: Channel<LiveWindowing.Window>? = null
    private var worker: Job? = null
    private var cancel = CancelSignal()
    @Volatile private var engineInfo: EngineInfo? = null
    @Volatile private var detectedLanguage: String? = null
    @Volatile private var accepting = false
    private var startedAt = 0L

    /** True when a speech model is installed, i.e. live mode can work. */
    fun available(): Boolean = engines.activeModel() != null

    fun start() {
        stopInternal()
        if (!available()) { _state.value = State(error = "Install a speech model to see the transcript while recording."); return }
        val w = LiveWindowing(Dsp.WHISPER_RATE)
        val ch = Channel<LiveWindowing.Window>(Channel.UNLIMITED)
        val signal = CancelSignal()
        windowing = w; channel = ch; cancel = signal; detectedLanguage = null; engineInfo = null
        accepting = true
        startedAt = System.currentTimeMillis()
        _state.value = State(active = true)
        worker = scope.launch(Dispatchers.Default) {
            engines.use { load ->
                val ready = load as? EngineManager.Load.Ready
                if (ready == null) {
                    accepting = false
                    _state.update { it.copy(error = (load as EngineManager.Load.Unavailable).reason) }
                    for (ignored in ch) { /* drain */ }
                    return@use
                }
                engineInfo = EngineInfo(ready.engine.id, ready.engine.displayName, ready.spec.id, ready.model.sha256,
                    settings.value.profile.name, "live", ready.engine.verifiedOffline)
                for (window in ch) {
                    if (signal.cancelled) break
                    transcribeWindow(ready, window, signal)
                    _state.update { it.copy(queuedWindows = (it.queuedWindows - 1).coerceAtLeast(0)) }
                }
            }
        }
    }

    private suspend fun transcribeWindow(ready: EngineManager.Load.Ready, window: LiveWindowing.Window, signal: CancelSignal) {
        val s = settings.value
        val previous = _state.value.segments.takeLast(4).joinToString(" ") { it.text.trim() }.takeLast(160)
        val glossaryPrompt = Glossary.parse(s.glossary).initialPrompt().takeIf { s.useGlossaryPrompt }
        val language = detectedLanguage ?: s.defaultLanguage
        val options = TranscribeOptions(
            language = if (ready.engine.multilingual) language else "en",
            profile = s.profile,
            style = s.style,
            threads = if (s.threads > 0) s.threads else device.recommendedThreads(),
            initialPrompt = listOfNotNull(glossaryPrompt, previous.ifBlank { null }).joinToString(" ").ifBlank { null },
            skipSilence = true,
        )
        val startMs = window.startMs(Dsp.WHISPER_RATE)
        val r = SilenceAware.transcribe(ready.engine, window.samples, startMs, options, object : TranscribeListener {}, signal)
        if (r is EngineResult.Success) {
            if (detectedLanguage == null && s.defaultLanguage == "auto") detectedLanguage = r.detectedLanguage
            val endMs = startMs + window.samples.size * 1000L / Dsp.WHISPER_RATE
            _state.update { it.copy(segments = it.segments + r.segments, transcribedUntilMs = endMs) }
        } else if (r is EngineResult.Failure) {
            _state.update { it.copy(error = r.reason) }
        }
    }

    /** Called by the recorder's audio thread for every block of samples. */
    fun onPcm(samples: FloatArray, count: Int) {
        if (!accepting) return
        val w = windowing ?: return
        val ready = w.push(samples, 0, count)
        if (ready.isNotEmpty()) {
            ready.forEach { channel?.trySend(it) }
            _state.update { it.copy(queuedWindows = it.queuedWindows + ready.size) }
        }
    }

    /**
     * After the recorder saved [item]: transcribes the remaining tail, waits for the worker and
     * stores the transcript (and, if [autoTitle], names the recording from its first sentence).
     * Returns false when no live transcript could be produced (the user can still transcribe later).
     */
    suspend fun finish(item: LibraryItem, autoTitle: Boolean): Boolean {
        val ch = channel ?: return false
        accepting = false
        _state.update { it.copy(finishing = true) }
        windowing?.flush()?.let { w -> ch.trySend(w); _state.update { it.copy(queuedWindows = it.queuedWindows + 1) } }
        ch.close()
        worker?.join()
        val st = _state.value
        val info = engineInfo
        if (info == null || (st.error != null && st.segments.isEmpty())) { reset(); return false }
        val now = System.currentTimeMillis()
        val segments = st.segments
        val warnings = buildList {
            add("live:recorded")
            if (settings.value.defaultLanguage == "auto") add("language:auto-detected:${detectedLanguage ?: "unknown"}")
            if (segments.isEmpty()) add("empty:no-speech")
            item.qualityIssues.forEach { add("quality:$it") }
        }
        val transcript = Transcript(
            id = Ids.newId(), title = item.title, createdMillis = now, sourceItemId = item.id,
            requestedLanguage = settings.value.defaultLanguage, detectedLanguage = detectedLanguage, translatedToEnglish = false,
            engine = info.copy(settingsSummary = "live while recording"), raw = segments, corrected = segments, warnings = warnings, tags = item.tags,
        )
        library.saveTranscript(transcript)
        if (autoTitle) AutoTitle.suggest(transcript.plainText())?.let { t -> library.update(item.id) { it.copy(title = t) } }
        reset()
        return true
    }

    fun cancel() { stopInternal(); reset() }

    private fun stopInternal() {
        accepting = false
        cancel.cancel()
        channel?.close()
        worker?.cancel()
        worker = null; channel = null; windowing = null
    }

    private fun reset() {
        stopInternal()
        _state.value = State()
    }
}
