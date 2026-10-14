package app.astra.audiotext

import android.content.Context
import app.astra.audiotext.audio.AudioDecoder
import app.astra.audiotext.audio.Player
import app.astra.audiotext.audio.Recorder
import app.astra.audiotext.benchmark.BenchmarkRunner
import app.astra.audiotext.device.DeviceInfo
import app.astra.audiotext.engine.EngineManager
import app.astra.audiotext.engine.LiveTranscriber
import app.astra.audiotext.engine.OnDeviceDictation
import app.astra.audiotext.engine.TranscriptionManager
import app.astra.audiotext.export.ExportManager
import app.astra.audiotext.net.AndroidConnectivityProbe
import app.astra.audiotext.net.HttpsTransport
import app.astra.audiotext.net.ProcessForegroundState
import app.astra.audiotext.privacy.ConsentBridge
import app.astra.audiotext.privacy.FileAuditLog
import app.astra.audiotext.security.AppLock
import app.astra.audiotext.security.KeystoreKeyWrapper
import app.astra.audiotext.settings.SettingsStore
import app.astra.audiotext.storage.LibraryRepository
import app.astra.audiotext.tts.TtsController
import app.astra.audiotext.ui.Navigator
import app.astra.core.models.ModelDownloader
import app.astra.core.models.ModelInstaller
import app.astra.core.privacy.AuditEvent
import app.astra.core.privacy.AuditType
import app.astra.core.privacy.NetworkConsentGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manual dependency wiring (no DI framework, no reflection). Constructing the container performs
 * NO network activity and no heavy I/O; the library is decrypted in the background by [start].
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(appContext)
    val keys = KeystoreKeyWrapper()
    val audit = FileAuditLog(File(appContext.filesDir, "audit/audit.jsonl"), enabled = { settings.value.auditLog })
    val consent = ConsentBridge()

    /** The single Network Consent Gateway; HttpsTransport is constructed nowhere else. */
    val gateway = NetworkConsentGateway(
        transport = HttpsTransport(),
        prompter = consent,
        audit = audit,
        connectivity = AndroidConnectivityProbe(appContext),
        foreground = ProcessForegroundState(),
        strictOfflineInitially = settings.value.strictOffline,
    )

    val library = LibraryRepository(appContext.filesDir, keys)
    val device = DeviceInfo(appContext)
    val installer = ModelInstaller(File(appContext.filesDir, "models"))
    val downloader = ModelDownloader(gateway, installer, audit)
    val engines = EngineManager(installer, settings, device, appContext.applicationInfo.nativeLibraryDir)
    val decoder = AudioDecoder(library)
    val transcriptions = TranscriptionManager(library, engines, decoder, settings, device, scope)
    val recorder = Recorder(library, scope)
    val live = LiveTranscriber(engines, library, settings, device, scope).also { lt -> recorder.pcmListener = { buf, n -> lt.onPcm(buf, n) } }
    val player = Player(library)
    val dictation = OnDeviceDictation(appContext)
    val tts = TtsController(appContext)
    val exports = ExportManager(appContext, audit)
    val benchmark = BenchmarkRunner(library, engines, decoder, device)
    val appLock = AppLock()
    val navigator = Navigator()

    sealed class LoadState {
        data object Loading : LoadState()
        data object Ready : LoadState()
        data class Failed(val reason: String) : LoadState()
    }

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Loading)
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    /** One-off notices (e.g. what a retention rule removed). */
    val notice: StateFlow<String?> = _notice.asStateFlow()
    fun clearNotice() { _notice.value = null }
    fun notify(message: String) { _notice.value = message }

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        if (settings.value.appLock) appLock.lock()
        scope.launch(Dispatchers.IO) {
            try {
                exports.clearShared()
                installer.deleteStaging()
                library.load()
                applyRetention()
                _loadState.value = LoadState.Ready
            } catch (e: Exception) {
                _loadState.value = LoadState.Failed(e.message ?: "Could not open the library")
            }
        }
    }

    private fun applyRetention() {
        val days = settings.value.retentionDays
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val old = library.items.value.filter { it.createdMillis < cutoff }
        old.forEach { library.delete(it.id) }
        if (old.isNotEmpty()) {
            audit.record(AuditEvent(System.currentTimeMillis(), AuditType.ITEM_DELETED, "Retention rule removed ${old.size} item(s) older than $days days"))
            notify("Your retention rule removed ${old.size} item(s) older than $days days.")
        }
    }

    fun setStrictOffline(on: Boolean) {
        settings.update { it.copy(strictOffline = on) }
        gateway.setStrictOffline(on)
    }

    /** Crypto-erase of everything: library, models, settings, audit, caches, and the master key. */
    fun wipeEverything() {
        player.release()
        library.wipeAll()
        File(appContext.filesDir, "models").deleteRecursively()
        exports.clearShared()
        audit.clear()
        appContext.cacheDir.deleteRecursively()
        keys.destroy()
        settings.clearAll()
        gateway.setStrictOffline(true)
        audit.record(AuditEvent(System.currentTimeMillis(), AuditType.DATA_WIPED, "All app data erased"))
    }
}
