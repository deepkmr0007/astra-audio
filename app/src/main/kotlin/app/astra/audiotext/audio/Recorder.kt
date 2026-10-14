package app.astra.audiotext.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.astra.audiotext.storage.ItemKind
import app.astra.audiotext.storage.LibraryItem
import app.astra.audiotext.storage.LibraryRepository
import app.astra.core.audio.AudioQuality
import app.astra.core.audio.Dsp
import app.astra.core.audio.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Microphone recorder: 16 kHz mono PCM16, written straight into an encrypted file.
 *
 * Rules enforced here:
 *  - Recording starts only from an explicit user action ([start]) and stops on [stop]/[cancel].
 *  - The app pauses recording automatically if it leaves the foreground (no background recording).
 *  - Nothing is recorded after Stop; the AudioRecord is released immediately.
 */
class Recorder(private val library: LibraryRepository, private val scope: CoroutineScope) {

    enum class Phase { IDLE, RECORDING, PAUSED, SAVING }

    data class State(
        val phase: Phase = Phase.IDLE,
        val elapsedMs: Long = 0,
        val level: Float = 0f,
        val autoPaused: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var record: AudioRecord? = null
    private var job: Job? = null
    private var itemId: String? = null
    private var rawFile: File? = null
    private var out: BufferedOutputStream? = null
    private var samples = 0L
    private var quality = AudioQuality.Accumulator(RATE)

    @SuppressLint("MissingPermission") // The UI requests RECORD_AUDIO right before calling start().
    fun start(): Boolean {
        if (_state.value.phase != Phase.IDLE) return false
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) { _state.value = State(error = "This phone cannot record 16 kHz mono audio."); return false }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, RATE))
        } catch (e: Exception) { null }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            _state.value = State(error = "The microphone is unavailable (in use by another app, or permission denied).")
            return false
        }
        val id = library.newItemId()
        library.dir(id).mkdirs()
        val raw = File(library.dir(id), "rec_raw.enc")
        itemId = id; rawFile = raw; samples = 0; quality = AudioQuality.Accumulator(RATE)
        out = BufferedOutputStream(library.encryptTo(raw), 64 * 1024)
        record = rec
        rec.startRecording()
        _state.value = State(phase = Phase.RECORDING)
        job = scope.launch(Dispatchers.IO) { loop(rec) }
        return true
    }

    private suspend fun loop(rec: AudioRecord) {
        val shorts = ShortArray(RATE / 10)
        val floats = FloatArray(shorts.size)
        val bytes = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        while (scope.isActive && record === rec) {
            if (_state.value.phase != Phase.RECORDING) { kotlinx.coroutines.delay(50); continue }
            val n = rec.read(shorts, 0, shorts.size)
            if (n <= 0) { kotlinx.coroutines.delay(10); continue }
            bytes.clear()
            var peak = 0f
            for (i in 0 until n) {
                bytes.putShort(shorts[i])
                floats[i] = shorts[i] / 32768f
                peak = maxOf(peak, abs(floats[i]))
            }
            out?.write(bytes.array(), 0, n * 2)
            quality.add(floats, 0, n)
            samples += n
            _state.value = _state.value.copy(elapsedMs = samples * 1000 / RATE, level = peak)
        }
    }

    fun pause(auto: Boolean = false) {
        if (_state.value.phase != Phase.RECORDING) return
        try { record?.stop() } catch (e: Exception) { }
        _state.value = _state.value.copy(phase = Phase.PAUSED, level = 0f, autoPaused = auto)
    }

    fun resume() {
        if (_state.value.phase != Phase.PAUSED) return
        record?.startRecording()
        _state.value = _state.value.copy(phase = Phase.RECORDING, autoPaused = false)
    }

    private suspend fun release() {
        val rec = record
        record = null
        try { rec?.stop() } catch (e: Exception) { }
        rec?.release()
        job?.let { it.cancel(); it.join() }
        job = null
        withContext(Dispatchers.IO) { out?.close() }
        out = null
    }

    /** Stops recording and saves it as a library item (16-bit WAV, encrypted). */
    suspend fun stop(title: String): LibraryItem? {
        val p = _state.value.phase
        if (p != Phase.RECORDING && p != Phase.PAUSED) return null
        _state.value = _state.value.copy(phase = Phase.SAVING, level = 0f)
        release()
        val id = itemId ?: return null
        val raw = rawFile ?: return null
        val count = samples
        return withContext(Dispatchers.IO) {
            try {
                // Re-wrap the raw PCM with a correct WAV header (sizes are only known now).
                library.openAudioOutput(id).use { o ->
                    o.write(Wav.header(Wav.Format(RATE, 1, 16), count * 2))
                    library.decryptFrom(raw).use { it.copyTo(o, 64 * 1024) }
                }
                raw.delete()
                val now = System.currentTimeMillis()
                library.register(LibraryItem(id, title.ifBlank { "Recording" }, ItemKind.RECORDING, now, now,
                    durationMs = count * 1000 / RATE, mimeType = "audio/wav", sizeBytes = 44 + count * 2, hasAudio = true,
                    qualityIssues = quality.report().issues.map { it.name }))
            } catch (e: Exception) {
                library.dir(id).deleteRecursively()
                _state.value = State(error = "Could not save the recording: ${e.message}")
                null
            } finally {
                if (_state.value.error == null) _state.value = State()
                itemId = null; rawFile = null
            }
        }
    }

    /** Discards the recording completely. */
    suspend fun cancel() {
        if (_state.value.phase == Phase.IDLE) return
        release()
        itemId?.let { withContext(Dispatchers.IO) { library.dir(it).deleteRecursively() } }
        itemId = null; rawFile = null
        _state.value = State()
    }

    fun clearError() { if (_state.value.error != null) _state.value = State() }

    companion object { const val RATE = Dsp.WHISPER_RATE }
}
