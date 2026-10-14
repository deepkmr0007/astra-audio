package app.astra.audiotext.engine

import app.astra.audiotext.device.DeviceInfo
import app.astra.audiotext.settings.SettingsStore
import app.astra.core.models.InstalledModel
import app.astra.core.models.ModelInstaller
import app.astra.core.models.ModelRegistry
import app.astra.core.models.ModelSpec
import app.astra.core.models.ResourceAdvisor
import app.astra.engine.whisper.WhisperEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns the loaded speech model. Loads lazily on first use, unloads on memory pressure, and refuses to
 * load a model that clearly cannot fit in this phone's RAM (with an explanation instead of a crash).
 */
class EngineManager(
    private val installer: ModelInstaller,
    private val settings: SettingsStore,
    private val device: DeviceInfo,
) {
    sealed class Load {
        data class Ready(val engine: WhisperEngine, val model: InstalledModel, val spec: ModelSpec) : Load()
        data class Unavailable(val reason: String) : Load()
    }

    private val mutex = Mutex()
    private var current: Triple<WhisperEngine, InstalledModel, ModelSpec>? = null
    private val users = java.util.concurrent.atomic.AtomicInteger(0)

    /** Runs [block] with the engine; the model is never unloaded while a block is running. */
    suspend fun <T> use(block: suspend (Load) -> T): T {
        users.incrementAndGet()
        try { return block(acquire()) } finally { users.decrementAndGet() }
    }

    fun installedModels(): List<InstalledModel> = installer.installed()

    fun activeModel(): Pair<InstalledModel, ModelSpec>? {
        val installed = installer.installed()
        val wanted = settings.value.activeModelId
        val m = installed.firstOrNull { it.specId == wanted } ?: installed.firstOrNull() ?: return null
        val spec = ModelRegistry.byId(m.specId) ?: return null
        return m to spec
    }

    private suspend fun acquire(): Load = mutex.withLock {
        val (model, spec) = activeModel() ?: return@withLock Load.Unavailable("No speech model is installed yet. Open Models to download one (asks first) or import a model file.")
        current?.let { (e, m, s) -> if (m.specId == model.specId && m.sha256 == model.sha256) return@withLock Load.Ready(e, m, s) }
        if (current != null && users.get() > 1) return@withLock Load.Unavailable("Another transcription is using the current model. Try again when it finishes.")
        current?.first?.close()
        current = null
        val advice = ResourceAdvisor.canRun(spec, device.snapshot())
        if (!advice.fits) return@withLock Load.Unavailable(advice.warning ?: "Not enough memory for this model.")
        val engine = withContext(Dispatchers.IO) {
            WhisperEngine.load(installer.fileOf(model), spec.id, spec.speakerTurns, spec.translateToEnglish)
        } ?: return@withLock Load.Unavailable("The model could not be loaded (file damaged or not enough memory). Try Verify in Models, or a smaller model.")
        current = Triple(engine, model, spec)
        Load.Ready(engine, model, spec)
    }

    /** Frees native memory (called on low-memory signals and when the model changes). */
    suspend fun unload() = mutex.withLock {
        if (users.get() > 0) return@withLock
        current?.first?.close()
        current = null
    }

    fun isLoaded(): Boolean = current != null
}
