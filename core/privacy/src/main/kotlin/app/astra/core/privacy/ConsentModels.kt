package app.astra.core.privacy

/**
 * What kind of data leaves the phone. The consent screen shows this verbatim and
 * the token is bound to it, so permission to download a model can never be reused
 * to upload a transcript.
 */
enum class PayloadClass(val label: String, val leavesDevice: Boolean) {
    /** A plain GET with no user content (e.g. downloading a public model file). */
    NONE("Nothing from your phone except the standard request needed to download (IP address, the file being requested, TLS handshake).", false),
    TEXT_CONTENT("Text you selected (shown in the preview below).", true),
    AUDIO_CONTENT("An audio recording you selected.", true),
    FILE_METADATA("File names / sizes only.", true),
}

/**
 * A complete, human-readable description of one network operation. Everything the
 * consent screen shows comes from here, and the consent token is derived from all of it.
 */
data class ConsentRequest(
    /** Unique per user-initiated operation. Consent never carries over to another operationId. */
    val operationId: String,
    /** e.g. "Download speech model: Whisper base (multilingual)". */
    val task: String,
    val method: String,
    val url: String,
    /**
     * Every host the operation may touch, including expected redirect targets. The user sees
     * this full list. A redirect anywhere else is blocked before any connection is made.
     */
    val allowedHosts: Set<String>,
    val payloadClass: PayloadClass,
    /** Exact categories that would be sent, in plain language. */
    val payloadDescription: String,
    /** Optional preview of the exact content to be sent (for text payloads). */
    val payloadPreview: String? = null,
    /** SHA-256 of the exact request body; the token is bound to it. Null when there is no body. */
    val payloadSha256: String? = null,
    val reason: String,
    /** Estimated transfer size in bytes, or null for "unknown". */
    val estimatedBytes: Long? = null,
    /** Known retention/processing terms of the remote service; null renders as "Not verified". */
    val providerPrivacyNote: String? = null,
    /** Label of a local alternative, if one exists (shown as "Use offline alternative"). */
    val offlineAlternative: String? = null,
    /** Whether device identifiers would be sent. The app never sends any; kept explicit for the UI. */
    val sendsDeviceIdentifiers: Boolean = false,
) {
    val destinationHost: String get() = UrlRules.hostOf(url) ?: ""
}

/** The user's answer on the consent screen. There is deliberately no "always allow". */
enum class ConsentDecision { ALLOW_ONCE, CANCEL, USE_OFFLINE_ALTERNATIVE }

/** Presents the consent screen and suspends until the user answers. */
fun interface ConsentPrompter {
    suspend fun ask(request: ConsentRequest): ConsentDecision
}

/** Visible network state for the status chip and the privacy dashboard. */
sealed class NetworkStatus {
    data object Offline : NetworkStatus()
    data class RequestPending(val task: String) : NetworkStatus()
    data class ApprovedForTask(val task: String, val host: String) : NetworkStatus()
}

/** Result of a gateway operation. Only [Completed] means bytes were exchanged with the server. */
sealed class NetworkOutcome {
    data class Completed(val httpStatus: Int, val finalHost: String) : NetworkOutcome()
    /** The user cancelled or chose the offline alternative: no connection was attempted. */
    data class Declined(val decision: ConsentDecision) : NetworkOutcome()
    /** Strict Offline Mode (kill switch) is on: nothing was attempted and no prompt shown. */
    data object BlockedByOfflineMode : NetworkOutcome()
    /** The app is not in the foreground: background networking is never allowed. */
    data object BlockedInBackground : NetworkOutcome()
    /** The device reports no connectivity; the user is told instead of being re-prompted. */
    data object DeviceOffline : NetworkOutcome()
    /** The request itself violates policy (non-HTTPS, host not listed, payload mismatch...). */
    data class PolicyViolation(val reason: String) : NetworkOutcome()
    /** The server redirected to a host the user did not approve; that host was never contacted. */
    data class RedirectBlocked(val unapprovedHost: String) : NetworkOutcome()
    data class Failed(val reason: String) : NetworkOutcome()
    data object Cancelled : NetworkOutcome()
}
