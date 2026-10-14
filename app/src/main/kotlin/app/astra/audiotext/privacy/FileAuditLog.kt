package app.astra.audiotext.privacy

import app.astra.core.privacy.AuditEvent
import app.astra.core.privacy.AuditSink
import app.astra.core.privacy.AuditType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Local-only audit history in app-private storage. Entries are content-free by construction
 * (see AuditEvent), so the log is kept as plain JSON lines and capped at [maxEntries].
 * Network consent outcomes are always kept (they back the privacy dashboard); other events
 * are recorded only while the user has the audit log enabled.
 */
class FileAuditLog(private val file: File, private val enabled: () -> Boolean, private val maxEntries: Int = 2000) : AuditSink {

    private val _events = MutableStateFlow<List<AuditEvent>>(emptyList())
    val events: StateFlow<List<AuditEvent>> = _events.asStateFlow()
    private var loaded = false

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        _events.value = if (file.exists()) file.readLines().mapNotNull { AuditEvent.fromJson(it) }.takeLast(maxEntries) else emptyList()
    }

    @Synchronized
    override fun record(event: AuditEvent) {
        val isNetwork = event.type.name.startsWith("NETWORK_") || event.type == AuditType.KILL_SWITCH_ON || event.type == AuditType.KILL_SWITCH_OFF
        if (!isNetwork && !enabled()) return
        ensureLoaded()
        val list = (_events.value + event).takeLast(maxEntries)
        _events.value = list
        file.parentFile?.mkdirs()
        if (list.size == maxEntries) file.writeText(list.joinToString("\n", postfix = "\n") { it.toJson() })
        else file.appendText(event.toJson() + "\n")
    }

    @Synchronized
    fun load(): List<AuditEvent> { ensureLoaded(); return _events.value }

    @Synchronized
    fun clear() {
        file.delete()
        _events.value = emptyList()
        loaded = true
    }
}
