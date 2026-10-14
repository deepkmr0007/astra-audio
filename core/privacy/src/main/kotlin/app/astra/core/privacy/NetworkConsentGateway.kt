package app.astra.core.privacy

import app.astra.core.common.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The single, centralized gate for every app-initiated network operation.
 *
 * Policy (default DENY):
 *  1. Strict Offline Mode (kill switch) on      -> blocked, no prompt, nothing attempted.
 *  2. App not in foreground                      -> blocked (no background networking).
 *  3. Device has no connectivity                 -> reported, no prompt, no retry.
 *  4. Request violates URL / payload policy      -> blocked.
 *  5. Otherwise the user is asked, every time.   -> "Allow once" / "Cancel" / "Use offline alternative".
 *  6. On "Allow once" a single-use token bound to this exact request is issued and consumed.
 *  7. Each hop (initial + redirects) is re-validated BEFORE connecting; unapproved redirect
 *     hosts are never contacted. There are no automatic retries.
 *  8. The token is retired when the operation completes, fails or is cancelled.
 */
class NetworkConsentGateway(
    private val transport: Transport,
    private val prompter: ConsentPrompter,
    private val audit: AuditSink,
    private val clock: Clock = Clock.SYSTEM,
    private val connectivity: ConnectivityProbe = ConnectivityProbe { true },
    private val foreground: ForegroundState = ForegroundState { true },
    strictOfflineInitially: Boolean = true,
    val tokens: ConsentTokenStore = ConsentTokenStore(clock),
    private val maxRedirects: Int = 5,
) {
    private val _status = MutableStateFlow<NetworkStatus>(NetworkStatus.Offline)
    val status: StateFlow<NetworkStatus> = _status.asStateFlow()

    private val _strictOffline = MutableStateFlow(strictOfflineInitially)
    /** Strict Offline Mode = app-level network kill switch. */
    val strictOffline: StateFlow<Boolean> = _strictOffline.asStateFlow()

    private val _lastOutcome = MutableStateFlow<Pair<Long, String>?>(null)
    /** (time, short description) of the most recent network attempt, for the privacy dashboard. */
    val lastOutcome: StateFlow<Pair<Long, String>?> = _lastOutcome.asStateFlow()

    fun setStrictOffline(on: Boolean) {
        if (on) tokens.revokeAll()
        if (_strictOffline.value != on) {
            _strictOffline.value = on
            audit.record(AuditEvent(clock.nowMillis(), if (on) AuditType.KILL_SWITCH_ON else AuditType.KILL_SWITCH_OFF,
                if (on) "Strict Offline Mode turned on" else "Strict Offline Mode turned off"))
        }
        if (on) _status.value = NetworkStatus.Offline
    }

    /**
     * Runs one consented network operation. [body] must hash to [ConsentRequest.payloadSha256]
     * when present. [onResponse] receives the final (non-redirect) response stream.
     */
    suspend fun execute(
        request: ConsentRequest,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        isCancelled: () -> Boolean = { false },
        onResponse: (HopResponse) -> Unit,
    ): NetworkOutcome {
        val host = request.destinationHost
        fun log(type: AuditType, detail: String? = null) =
            audit.record(AuditEvent(clock.nowMillis(), type, request.task, host.ifEmpty { null }, detail))

        if (_strictOffline.value) {
            log(AuditType.NETWORK_BLOCKED_OFFLINE_MODE)
            return finish(NetworkOutcome.BlockedByOfflineMode, request)
        }
        if (!foreground.isInForeground()) {
            log(AuditType.NETWORK_BLOCKED_BACKGROUND)
            return finish(NetworkOutcome.BlockedInBackground, request)
        }
        policyViolation(request, body)?.let { reason ->
            log(AuditType.NETWORK_BLOCKED_POLICY, reason)
            return finish(NetworkOutcome.PolicyViolation(reason), request)
        }
        if (!connectivity.hasConnectivity()) {
            return finish(NetworkOutcome.DeviceOffline, request)
        }

        _status.value = NetworkStatus.RequestPending(request.task)
        log(AuditType.NETWORK_PROMPT_SHOWN)
        val decision = try {
            prompter.ask(request)
        } catch (e: Exception) {
            ConsentDecision.CANCEL
        }
        if (decision != ConsentDecision.ALLOW_ONCE) {
            log(if (decision == ConsentDecision.USE_OFFLINE_ALTERNATIVE) AuditType.NETWORK_OFFLINE_ALTERNATIVE else AuditType.NETWORK_DECLINED)
            return finish(NetworkOutcome.Declined(decision), request)
        }
        // The kill switch may have been flipped while the dialog was open.
        if (_strictOffline.value) {
            log(AuditType.NETWORK_BLOCKED_OFFLINE_MODE, "Kill switch engaged during prompt")
            return finish(NetworkOutcome.BlockedByOfflineMode, request)
        }
        log(AuditType.NETWORK_ALLOWED_ONCE)

        val token = tokens.issue(request)
        try {
            val rejection = tokens.consume(token, request)
            if (rejection != null) {
                log(AuditType.NETWORK_BLOCKED_POLICY, "Token rejected: $rejection")
                return finish(NetworkOutcome.PolicyViolation("Consent token rejected: $rejection"), request)
            }
            _status.value = NetworkStatus.ApprovedForTask(request.task, host)
            val outcome = withContext(Dispatchers.IO) {
                runHops(token, request, body, headers, isCancelled, onResponse)
            }
            when (outcome) {
                is NetworkOutcome.Completed -> log(AuditType.NETWORK_COMPLETED, "HTTP ${outcome.httpStatus} via ${outcome.finalHost}")
                is NetworkOutcome.RedirectBlocked -> log(AuditType.NETWORK_REDIRECT_BLOCKED, "Unapproved redirect host: ${outcome.unapprovedHost}")
                is NetworkOutcome.Cancelled -> log(AuditType.NETWORK_CANCELLED)
                is NetworkOutcome.Failed -> log(AuditType.NETWORK_FAILED, outcome.reason)
                is NetworkOutcome.PolicyViolation -> log(AuditType.NETWORK_BLOCKED_POLICY, outcome.reason)
                is NetworkOutcome.BlockedByOfflineMode -> log(AuditType.NETWORK_BLOCKED_OFFLINE_MODE, "Kill switch engaged mid-operation")
                else -> Unit
            }
            return finish(outcome, request)
        } finally {
            tokens.retire(token)
        }
    }

    private fun runHops(
        token: ConsentToken,
        request: ConsentRequest,
        body: ByteArray?,
        headers: Map<String, String>,
        isCancelled: () -> Boolean,
        onResponse: (HopResponse) -> Unit,
    ): NetworkOutcome {
        var url = request.url
        var method = request.method.uppercase()
        var hopBody = body
        var redirects = 0
        while (true) {
            if (isCancelled()) return NetworkOutcome.Cancelled
            if (_strictOffline.value) return NetworkOutcome.BlockedByOfflineMode
            // Re-validate before EVERY connection, including redirects.
            UrlRules.violation(url, token.allowedHosts)?.let {
                val h = UrlRules.hostOf(url) ?: "unknown"
                return if (redirects > 0) NetworkOutcome.RedirectBlocked(h) else NetworkOutcome.PolicyViolation(it)
            }
            tokens.checkInFlight(token)?.let { return NetworkOutcome.PolicyViolation("Consent expired or revoked: $it") }

            val ticket = ConnectionTicket(url, method, token, tokens)
            val result = try {
                transport.exchange(ticket, HopRequest(method, url, headers, hopBody), onResponse, isCancelled)
            } catch (e: java.util.concurrent.CancellationException) {
                return NetworkOutcome.Cancelled
            } catch (e: Exception) {
                if (isCancelled()) return NetworkOutcome.Cancelled
                return NetworkOutcome.Failed(e.message ?: e.javaClass.simpleName)
            }
            when (result) {
                is HopResult.Done -> return NetworkOutcome.Completed(result.status, UrlRules.hostOf(url) ?: "")
                is HopResult.Redirect -> {
                    redirects++
                    if (redirects > maxRedirects) return NetworkOutcome.Failed("Too many redirects")
                    url = UrlRules.resolve(url, result.location) ?: return NetworkOutcome.Failed("Bad redirect location")
                    // Never re-send a body to a redirect target; downgrade to GET as browsers do for 301/302/303.
                    if (hopBody != null) { hopBody = null; method = "GET" }
                }
            }
        }
    }

    private fun finish(outcome: NetworkOutcome, request: ConsentRequest): NetworkOutcome {
        _status.value = NetworkStatus.Offline
        _lastOutcome.value = clock.nowMillis() to "${request.task}: ${describe(outcome)}"
        return outcome
    }

    private fun policyViolation(request: ConsentRequest, body: ByteArray?): String? {
        if (request.allowedHosts.isEmpty()) return "No approved hosts"
        UrlRules.violation(request.url, request.allowedHosts)?.let { return it }
        if (request.sendsDeviceIdentifiers) return "Sending device identifiers is not permitted"
        val method = request.method.uppercase()
        if (method !in setOf("GET", "HEAD", "POST", "PUT")) return "HTTP method $method is not permitted"
        if (request.payloadClass == PayloadClass.NONE && body != null) return "Request declared no payload but has a body"
        if (body != null) {
            val expected = request.payloadSha256 ?: return "Body present but payload hash not declared"
            val actual = app.astra.core.common.Hashing.sha256(body)
            if (!app.astra.core.common.Hashing.digestEquals(expected, actual)) return "Body does not match the approved payload"
        } else if (request.payloadSha256 != null) {
            return "Approved payload missing"
        }
        return null
    }

    companion object {
        fun describe(o: NetworkOutcome): String = when (o) {
            is NetworkOutcome.Completed -> "completed (HTTP ${o.httpStatus})"
            is NetworkOutcome.Declined -> if (o.decision == ConsentDecision.USE_OFFLINE_ALTERNATIVE) "used offline alternative" else "cancelled by you"
            NetworkOutcome.BlockedByOfflineMode -> "blocked by Strict Offline Mode"
            NetworkOutcome.BlockedInBackground -> "blocked (app in background)"
            NetworkOutcome.DeviceOffline -> "device is offline"
            is NetworkOutcome.PolicyViolation -> "blocked by policy: ${o.reason}"
            is NetworkOutcome.RedirectBlocked -> "blocked redirect to ${o.unapprovedHost}"
            is NetworkOutcome.Failed -> "failed: ${o.reason}"
            NetworkOutcome.Cancelled -> "cancelled"
        }
    }
}
