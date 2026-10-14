package app.astra.core.privacy

import app.astra.core.common.Json

/**
 * Local-only audit trail. Entries never contain transcript text, audio, prompts,
 * file paths or URL query strings — only what happened, when, and to which host.
 */
enum class AuditType {
    NETWORK_PROMPT_SHOWN,
    NETWORK_ALLOWED_ONCE,
    NETWORK_DECLINED,
    NETWORK_OFFLINE_ALTERNATIVE,
    NETWORK_BLOCKED_OFFLINE_MODE,
    NETWORK_BLOCKED_BACKGROUND,
    NETWORK_BLOCKED_POLICY,
    NETWORK_REDIRECT_BLOCKED,
    NETWORK_COMPLETED,
    NETWORK_FAILED,
    NETWORK_CANCELLED,
    KILL_SWITCH_ON,
    KILL_SWITCH_OFF,
    RECORDING_STARTED,
    RECORDING_STOPPED,
    RECORDING_DISCARDED,
    IMPORT,
    EXPORT,
    SHARE,
    MODEL_INSTALLED,
    MODEL_REJECTED,
    MODEL_DELETED,
    ITEM_DELETED,
    DATA_WIPED,
}

data class AuditEvent(
    val timeMillis: Long,
    val type: AuditType,
    /** Short, content-free description, e.g. "Download speech model: Whisper base". */
    val summary: String,
    val host: String? = null,
    val detail: String? = null,
) {
    fun toJson(): String = Json.write(
        mapOf("t" to timeMillis, "type" to type.name, "summary" to summary, "host" to host, "detail" to detail)
    )

    companion object {
        fun fromJson(line: String): AuditEvent? = try {
            val m = Json.parseObject(line)
            AuditEvent(
                timeMillis = (m["t"] as Number).toLong(),
                type = AuditType.valueOf(m["type"] as String),
                summary = m["summary"] as String,
                host = m["host"] as String?,
                detail = m["detail"] as String?,
            )
        } catch (e: Exception) {
            null
        }
    }
}

fun interface AuditSink {
    fun record(event: AuditEvent)
}

/** In-memory sink for tests. */
class MemoryAuditSink : AuditSink {
    val events = mutableListOf<AuditEvent>()
    @Synchronized override fun record(event: AuditEvent) { events.add(event) }
    fun types(): List<AuditType> = events.map { it.type }
}
