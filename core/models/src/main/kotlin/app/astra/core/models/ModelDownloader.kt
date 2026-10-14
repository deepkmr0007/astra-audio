package app.astra.core.models

import app.astra.core.common.Clock
import app.astra.core.common.Ids
import app.astra.core.privacy.AuditEvent
import app.astra.core.privacy.AuditSink
import app.astra.core.privacy.AuditType
import app.astra.core.privacy.ConsentRequest
import app.astra.core.privacy.NetworkConsentGateway
import app.astra.core.privacy.NetworkOutcome
import app.astra.core.privacy.PayloadClass
import java.io.InputStream

sealed class InstallOutcome {
    data class Installed(val model: InstalledModel) : InstallOutcome()
    data class Rejected(val reason: String) : InstallOutcome()
    /** The network step did not complete (declined, blocked, offline, failed...). Nothing was installed. */
    data class NotDownloaded(val network: NetworkOutcome) : InstallOutcome()
    data class Failed(val reason: String) : InstallOutcome()
    data object Cancelled : InstallOutcome()
}

/**
 * Installs models either from a file the user picks (fully offline) or by downloading through the
 * Network Consent Gateway. Every download is a separate, explicitly approved operation; there are
 * no background checks, no update polling and no automatic retries.
 */
class ModelDownloader(
    private val gateway: NetworkConsentGateway,
    private val installer: ModelInstaller,
    private val audit: AuditSink,
    private val clock: Clock = Clock.SYSTEM,
) {

    fun consentRequestFor(spec: ModelSpec, extraHosts: Set<String> = emptySet()): ConsentRequest = ConsentRequest(
        operationId = "model-download:" + spec.id + ":" + Ids.newId(),
        task = "Download speech model: ${spec.displayName}",
        method = "GET",
        url = spec.sourceUrl,
        allowedHosts = spec.sourceHosts + extraHosts,
        payloadClass = PayloadClass.NONE,
        payloadDescription = "No audio, transcripts, documents, file names or device identifiers. " +
            "The server will see your IP address, the model file name being requested, and a generic app User-Agent.",
        reason = "Speech models are large (${spec.approxBytes / (1024 * 1024)} MB) and are not bundled with the app. " +
            "After download the model runs entirely on your phone.",
        estimatedBytes = spec.approxBytes,
        providerPrivacyNote = null, // Hugging Face retention/logging terms are not verified by this app.
        offlineAlternative = "Import a model file you already have",
    )

    /** Upper bound on accepted bytes: approximate size plus 5 % (defends against oversized responses). */
    fun maxBytes(spec: ModelSpec): Long = spec.approxBytes + spec.approxBytes / 20 + 1024 * 1024

    suspend fun download(
        spec: ModelSpec,
        extraHosts: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false },
        onProgress: (bytes: Long, total: Long?) -> Unit = { _, _ -> },
    ): InstallOutcome {
        val request = consentRequestFor(spec, extraHosts)
        val staging = installer.stagingFile(spec)
        var staged: Triple<Long, String, String>? = null
        var stageError: String? = null
        val outcome = gateway.execute(
            request,
            headers = mapOf("User-Agent" to "ASTRA-AudioText", "Accept-Encoding" to "identity"),
            isCancelled = isCancelled,
        ) { response ->
            if (response.status != 200) { stageError = "Server answered HTTP ${response.status}"; return@execute }
            if (response.contentLength > maxBytes(spec)) { stageError = "Server reported an unexpectedly large file"; return@execute }
            try {
                staged = installer.stage(spec, response.body, maxBytes(spec), staging, isCancelled) { n ->
                    onProgress(n, response.contentLength.takeIf { it > 0 })
                }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                stageError = e.message ?: "Download interrupted"
            }
        }
        if (outcome !is NetworkOutcome.Completed) {
            staging.delete()
            return if (outcome is NetworkOutcome.Cancelled) InstallOutcome.Cancelled else InstallOutcome.NotDownloaded(outcome)
        }
        stageError?.let { staging.delete(); return InstallOutcome.Failed(it) }
        val (size, sha1, sha256) = staged ?: run { staging.delete(); return InstallOutcome.Failed("No data received") }
        return finishInstall(spec, staging, size, sha1, sha256, "download")
    }

    /** Offline path: verify and install a model file the user selected. */
    fun importFile(input: InputStream, expected: ModelSpec?, isCancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): InstallOutcome {
        val maxBytes = expected?.let { maxBytes(it) } ?: (ModelRegistry.ALL.maxOf { maxBytes(it) })
        val staging = java.io.File(installer.stagingFile(expected ?: ModelRegistry.ALL.first()).parentFile, "import-" + Ids.newId() + ".part")
        val (size, sha1, sha256) = try {
            installer.stage(expected, input, maxBytes, staging, isCancelled, onProgress)
        } catch (e: java.util.concurrent.CancellationException) {
            staging.delete(); return InstallOutcome.Cancelled
        } catch (e: Exception) {
            staging.delete(); return InstallOutcome.Failed(e.message ?: "Could not read file")
        }
        return finishInstall(expected, staging, size, sha1, sha256, "imported file")
    }

    private fun finishInstall(spec: ModelSpec?, staging: java.io.File, size: Long, sha1: String, sha256: String, source: String): InstallOutcome {
        return when (val v = installer.verify(spec, staging, size, sha1, sha256)) {
            is VerifyResult.Rejected -> {
                staging.delete()
                audit.record(AuditEvent(clock.nowMillis(), AuditType.MODEL_REJECTED, "Model rejected: ${spec?.displayName ?: "unknown file"}", detail = v.reason))
                InstallOutcome.Rejected(v.reason)
            }
            is VerifyResult.Ok -> try {
                val m = installer.install(v, staging, source)
                audit.record(AuditEvent(clock.nowMillis(), AuditType.MODEL_INSTALLED, "Model installed: ${v.spec.displayName}",
                    detail = "sha256=${m.sha256.take(16)}…, source=$source"))
                InstallOutcome.Installed(m)
            } catch (e: Exception) {
                staging.delete()
                InstallOutcome.Failed(e.message ?: "Install failed")
            }
        }
    }
}
