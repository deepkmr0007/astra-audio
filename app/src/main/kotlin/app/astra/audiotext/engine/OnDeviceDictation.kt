package app.astra.audiotext.engine

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live dictation through Android's ON-DEVICE recognizer (API 31+: SpeechRecognizer
 * .createOnDeviceSpeechRecognizer). That API is specified to run recognition locally, so it is the
 * only system recognizer this app uses; the classic SpeechRecognizer is NOT used because it may send
 * audio to a cloud service. On older Android versions live dictation is reported as unavailable and
 * the user can record + transcribe with whisper.cpp instead.
 */
class OnDeviceDictation(private val context: Context) {

    data class State(
        val listening: Boolean = false,
        val finalText: String = "",
        val partial: String = "",
        val level: Float = 0f,
        val startedMillis: Long = 0,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var recognizer: SpeechRecognizer? = null
    private var language: String = "en-IN"
    private var keepGoing = false

    /** Null when available; otherwise the reason it is not. */
    fun unavailableReason(): String? {
        if (Build.VERSION.SDK_INT < 31) return "Live on-device dictation needs Android 12 or newer. Use Record, then Transcribe."
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return "This phone has no on-device speech recognizer installed. Use Record, then Transcribe."
        return null
    }

    fun start(languageTag: String) {
        if (Build.VERSION.SDK_INT < 31) return
        if (unavailableReason() != null) return
        stopInternal()
        language = languageTag
        keepGoing = true
        _state.value = State(listening = true, startedMillis = System.currentTimeMillis())
        listenOnce()
    }

    private fun listenOnce() {
        if (Build.VERSION.SDK_INT < 31) return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { _state.value = _state.value.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val fatal = error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                if (fatal) {
                    keepGoing = false
                    _state.value = _state.value.copy(listening = false, error = describe(error))
                    destroy()
                } else if (keepGoing) {
                    destroy(); listenOnce() // silence/no-match: keep dictating until the user taps Stop
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val s = _state.value
                _state.value = s.copy(finalText = (s.finalText + " " + text).trim(), partial = "")
                destroy()
                if (keepGoing) listenOnce()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                _state.value = _state.value.copy(partial = text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        r.startListening(intent)
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed for dictation."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "The on-device recognizer does not support this language."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "The language pack for this language is not installed on the phone. Install it in Android settings, or use Record + Transcribe."
        else -> "Dictation stopped (code $error)."
    }

    private fun destroy() { recognizer?.destroy(); recognizer = null }

    private fun stopInternal() { keepGoing = false; recognizer?.stopListening(); destroy() }

    /** Stops listening immediately; returns the text dictated so far. */
    fun stop(): String {
        stopInternal()
        val s = _state.value
        val text = (s.finalText + " " + s.partial).trim()
        _state.value = s.copy(listening = false, finalText = text, partial = "", level = 0f)
        return text
    }

    fun reset() { stopInternal(); _state.value = State() }
}
