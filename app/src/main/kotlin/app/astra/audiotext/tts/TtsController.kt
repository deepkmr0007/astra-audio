package app.astra.audiotext.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Text-to-speech through the phone's installed TTS engine. Only voices that report they need no
 * network and are already installed are offered, and network synthesis is explicitly disabled per
 * utterance. Voice downloads are never triggered by this app.
 *
 * Honest limit: the TTS engine is a separate app (e.g. Google Speech Services); ASTRA can choose
 * offline voices but cannot control that app's own behaviour. This is disclosed in the UI.
 */
class TtsController(context: Context) {

    data class VoiceOption(val name: String, val locale: Locale, val quality: Int)

    data class State(
        val ready: Boolean = false,
        val engineName: String? = null,
        val voices: List<VoiceOption> = emptyList(),
        val speaking: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null

    /** Created lazily when the Speak screen opens (binding the TTS service is not free). */
    fun init() {
        if (tts != null) return
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) refresh()
            else _state.value = State(error = "No text-to-speech engine is available on this phone.")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { _state.value = _state.value.copy(speaking = true) }
            override fun onDone(utteranceId: String?) { _state.value = _state.value.copy(speaking = false) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { _state.value = _state.value.copy(speaking = false, error = "Speech failed.") }
        })
    }

    private fun refresh() {
        val t = tts ?: return
        val voices = try { t.voices } catch (e: Exception) { null } ?: emptySet<Voice>()
        val offline = voices.filter { v ->
            !v.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features
        }.map { VoiceOption(it.name, it.locale, it.quality) }
            .sortedWith(compareBy<VoiceOption>({ priority(it.locale) }, { it.locale.displayName }, { -it.quality }))
        _state.value = State(ready = true, engineName = t.defaultEngine, voices = offline,
            error = if (offline.isEmpty()) "No offline voices are installed. Install voice data in Android settings › Text-to-speech." else null)
    }

    private fun priority(l: Locale): Int = when (l.language) { "hi" -> 0; "en" -> if (l.country == "IN") 1 else 2; else -> 3 }

    fun speak(text: String, voiceName: String?, rate: Float, pitch: Float) {
        val t = tts ?: return
        val voice = t.voices?.firstOrNull { it.name == voiceName && !it.isNetworkConnectionRequired }
        if (voice != null) t.voice = voice
        t.setSpeechRate(rate.coerceIn(0.25f, 3f))
        t.setPitch(pitch.coerceIn(0.5f, 2f))
        val params = Bundle().apply {
            @Suppress("DEPRECATION")
            putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "false")
            putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true")
        }
        // Long texts are split at sentence ends to respect the engine's input limit.
        val max = TextToSpeech.getMaxSpeechInputLength() - 100
        val parts = text.chunkedBySentence(max)
        parts.forEachIndexed { i, part ->
            t.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, "astra-$i")
        }
    }

    fun stop() { tts?.stop(); _state.value = _state.value.copy(speaking = false) }

    fun shutdown() { tts?.shutdown(); tts = null; _state.value = State() }

    private fun String.chunkedBySentence(max: Int): List<String> {
        if (length <= max) return listOf(this)
        val out = ArrayList<String>()
        var rest = this
        while (rest.length > max) {
            val cut = rest.substring(0, max).lastIndexOfAny(charArrayOf('.', '!', '?', '।', '\n')).takeIf { it > max / 2 } ?: (max - 1)
            out.add(rest.substring(0, cut + 1)); rest = rest.substring(cut + 1)
        }
        if (rest.isNotBlank()) out.add(rest)
        return out
    }
}
