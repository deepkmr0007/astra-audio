package app.astra.audiotext.audio

import android.media.MediaPlayer
import android.media.PlaybackParams
import app.astra.audiotext.storage.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Local playback of encrypted library audio (decrypted on the fly into memory). */
class Player(private val library: LibraryRepository) {

    data class State(val itemId: String? = null, val clean: Boolean = false, val playing: Boolean = false,
                     val durationMs: Long = 0, val speed: Float = 1f, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var mp: MediaPlayer? = null

    fun open(itemId: String, clean: Boolean = false) {
        if (_state.value.itemId == itemId && _state.value.clean == clean && mp != null) return
        release()
        try {
            val p = MediaPlayer()
            p.setDataSource(EncryptedMediaDataSource(library.audioReader(itemId, clean)))
            p.prepare()
            p.setOnCompletionListener { _state.value = _state.value.copy(playing = false) }
            mp = p
            _state.value = State(itemId, clean, false, p.duration.toLong(), 1f)
        } catch (e: Exception) {
            release()
            _state.value = State(itemId, clean, error = "This audio cannot be played on this phone.")
        }
    }

    fun toggle() {
        val p = mp ?: return
        if (p.isPlaying) { p.pause(); _state.value = _state.value.copy(playing = false) }
        else { p.start(); _state.value = _state.value.copy(playing = true) }
    }

    fun pause() { mp?.let { if (it.isPlaying) it.pause() }; _state.value = _state.value.copy(playing = false) }

    fun seekTo(ms: Long) { mp?.seekTo(ms.toInt()) }

    /** Plays a short span, e.g. an uncertain segment, from its start. */
    fun playFrom(ms: Long) { mp?.let { it.seekTo(ms.toInt()); it.start(); _state.value = _state.value.copy(playing = true) } }

    fun positionMs(): Long = try { mp?.currentPosition?.toLong() ?: 0L } catch (e: IllegalStateException) { 0L }

    fun setSpeed(speed: Float) {
        val p = mp ?: return
        val wasPlaying = p.isPlaying
        try {
            p.playbackParams = PlaybackParams().setSpeed(speed)
            if (!wasPlaying) p.pause() // setting params can start playback on some devices
            _state.value = _state.value.copy(speed = speed, playing = p.isPlaying)
        } catch (e: Exception) { }
    }

    fun release() {
        mp?.release()
        mp = null
        _state.value = State()
    }
}
