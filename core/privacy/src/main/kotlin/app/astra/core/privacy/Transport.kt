package app.astra.core.privacy

import java.io.InputStream

/** One HTTP exchange (one hop). Redirects are NOT followed by the transport. */
data class HopRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

class HopResponse(
    val status: Int,
    val contentLength: Long,
    val contentType: String?,
    val body: InputStream,
)

sealed class HopResult {
    /** 3xx with a Location header. The gateway decides whether to follow it. */
    data class Redirect(val location: String) : HopResult()
    /** A non-redirect response whose body has been handed to the consumer. */
    data class Done(val status: Int) : HopResult()
}

/**
 * The ONLY abstraction allowed to open network connections. The single Android
 * implementation lives in `app/.../net/HttpsTransport.kt` and is the only production
 * file permitted to reference java.net / HttpURLConnection (enforced by
 * NetworkBypassScanTest).
 *
 * Implementations MUST:
 *  - call [ConnectionTicket.claim] immediately before connecting;
 *  - never follow redirects themselves;
 *  - never retry;
 *  - not add identifying headers (no cookies, no device IDs, generic User-Agent).
 */
interface Transport {
    fun exchange(
        ticket: ConnectionTicket,
        request: HopRequest,
        onResponse: (HopResponse) -> Unit,
        isCancelled: () -> Boolean,
    ): HopResult
}

/** Lets the gateway know if the device has any network at all (no traffic generated). */
fun interface ConnectivityProbe {
    fun hasConnectivity(): Boolean
}

/** Lets the gateway refuse requests while the app is in the background. */
fun interface ForegroundState {
    fun isInForeground(): Boolean
}
