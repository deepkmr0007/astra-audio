package app.astra.audiotext.net

import app.astra.core.privacy.ConnectionTicket
import app.astra.core.privacy.HopRequest
import app.astra.core.privacy.HopResponse
import app.astra.core.privacy.HopResult
import app.astra.core.privacy.Transport
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * THE ONLY production code that opens network connections (enforced by NetworkBypassScanTest).
 *
 * - Requires a [ConnectionTicket] minted by the Network Consent Gateway and claims it immediately
 *   before connecting (the ticket re-checks host, scheme, expiry and single use).
 * - HTTPS only; redirects are NOT followed here — they are returned to the gateway, which
 *   re-validates the next host against what the user approved before any further connection.
 * - No retries, no cookies, no caching, no identifying headers.
 */
class HttpsTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) : Transport {

    override fun exchange(
        ticket: ConnectionTicket,
        request: HopRequest,
        onResponse: (HopResponse) -> Unit,
        isCancelled: () -> Boolean,
    ): HopResult {
        ticket.claim(request.url, request.method) // throws if not permitted
        val connection = URL(request.url).openConnection() as? HttpsURLConnection
            ?: throw IllegalStateException("Only HTTPS connections are allowed")
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.requestMethod = request.method
            connection.setRequestProperty("Cookie", "")
            for ((k, v) in request.headers) connection.setRequestProperty(k, v)
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without Location header")
                return HopResult.Redirect(location)
            }
            val stream: InputStream = if (status >= 400) (connection.errorStream ?: java.io.ByteArrayInputStream(ByteArray(0))) else connection.inputStream
            stream.use { s ->
                onResponse(HopResponse(status, connection.contentLengthLong, connection.contentType, CancellableStream(s, isCancelled)))
            }
            return HopResult.Done(status)
        } finally {
            connection.disconnect()
        }
    }

    /** Lets a user cancel a long download between reads. */
    private class CancellableStream(private val inner: InputStream, private val isCancelled: () -> Boolean) : InputStream() {
        override fun read(): Int { check(); return inner.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return inner.read(b, off, len) }
        override fun close() = inner.close()
        private fun check() { if (isCancelled()) throw java.util.concurrent.CancellationException("Cancelled by user") }
    }
}
