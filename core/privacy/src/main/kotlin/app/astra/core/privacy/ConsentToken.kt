package app.astra.core.privacy

import app.astra.core.common.Clock
import app.astra.core.common.Hashing
import java.security.SecureRandom

/**
 * A short-lived, single-use permission bound to the canonical description of exactly one
 * request (operation, method, URL, allowed hosts, payload class and payload hash).
 *
 * Tokens never leave this module: the constructor is internal, and the transport only ever
 * receives per-hop [ConnectionTicket]s that the gateway mints after re-validating the token.
 */
class ConsentToken internal constructor(
    val id: String,
    val fingerprint: String,
    val allowedHosts: Set<String>,
    val operationId: String,
    val expiresAtMillis: Long,
) {
    @Volatile internal var consumed: Boolean = false
        private set
    @Volatile internal var revoked: Boolean = false
        private set

    internal fun markConsumed() { consumed = true }
    internal fun revoke() { revoked = true }

    companion object {
        /** Canonical, order-independent fingerprint of everything the user approved. */
        fun fingerprintOf(r: ConsentRequest): String = Hashing.sha256(
            listOf(
                "op=" + r.operationId,
                "method=" + r.method.uppercase(),
                "url=" + r.url,
                "hosts=" + r.allowedHosts.map { it.lowercase() }.sorted().joinToString(","),
                "payloadClass=" + r.payloadClass.name,
                "payloadSha256=" + (r.payloadSha256 ?: "-"),
            ).joinToString("\n")
        )
    }
}

/** Why a token was rejected. Exposed for tests and audit entries. */
enum class TokenRejection { UNKNOWN, CONSUMED, EXPIRED, REVOKED, FINGERPRINT_MISMATCH, OFFLINE_MODE }

/**
 * Issues and validates consent tokens. Validation is the single choke point that every
 * connection attempt passes through.
 */
class ConsentTokenStore(
    private val clock: Clock,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val random: SecureRandom = SecureRandom(),
) {
    private val tokens = HashMap<String, ConsentToken>()

    @Synchronized
    internal fun issue(request: ConsentRequest): ConsentToken {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        val token = ConsentToken(
            id = Hashing.hex(bytes),
            fingerprint = ConsentToken.fingerprintOf(request),
            allowedHosts = request.allowedHosts.map { it.lowercase() }.toSet(),
            operationId = request.operationId,
            expiresAtMillis = clock.nowMillis() + ttlMillis,
        )
        tokens[token.id] = token
        return token
    }

    /**
     * Atomically checks that [token] is live and matches [request], then consumes it.
     * A token can authorize exactly one operation; any second call fails with CONSUMED.
     */
    @Synchronized
    internal fun consume(token: ConsentToken, request: ConsentRequest): TokenRejection? {
        val known = tokens[token.id] ?: return TokenRejection.UNKNOWN
        if (known !== token) return TokenRejection.UNKNOWN
        if (token.revoked) return TokenRejection.REVOKED
        if (token.consumed) return TokenRejection.CONSUMED
        if (clock.nowMillis() > token.expiresAtMillis) return TokenRejection.EXPIRED
        if (token.fingerprint != ConsentToken.fingerprintOf(request)) return TokenRejection.FINGERPRINT_MISMATCH
        token.markConsumed()
        return null
    }

    /** Checks a consumed token is still usable for a further hop (redirect) of the same operation. */
    @Synchronized
    internal fun checkInFlight(token: ConsentToken): TokenRejection? {
        if (tokens[token.id] !== token) return TokenRejection.UNKNOWN
        if (token.revoked) return TokenRejection.REVOKED
        if (clock.nowMillis() > token.expiresAtMillis) return TokenRejection.EXPIRED
        return null
    }

    /** Ends the token's life when the operation finishes, fails or is cancelled. */
    @Synchronized
    internal fun retire(token: ConsentToken) {
        token.revoke()
        tokens.remove(token.id)
    }

    /** Kill switch: revoke everything immediately. */
    @Synchronized
    fun revokeAll() {
        tokens.values.forEach { it.revoke() }
        tokens.clear()
    }

    @Synchronized
    fun liveTokenCount(): Int = tokens.size

    companion object {
        /** Long enough to start a download after tapping "Allow", short enough not to linger. */
        const val DEFAULT_TTL_MILLIS = 2 * 60 * 1000L
    }
}

/**
 * Permission for exactly one HTTP exchange with one URL. Created only by the gateway (internal
 * constructor) and checked again by the transport right before it opens the socket.
 */
class ConnectionTicket internal constructor(
    val url: String,
    val method: String,
    internal val token: ConsentToken,
    private val store: ConsentTokenStore,
) {
    @Volatile private var used = false

    /** Called by the transport immediately before connecting. Throws if the ticket is not valid. */
    @Synchronized
    fun claim(forUrl: String, forMethod: String) {
        check(!used) { "Connection ticket already used" }
        check(forUrl == url && forMethod.equals(method, ignoreCase = true)) { "Ticket does not match the request" }
        val rejection = store.checkInFlight(token)
        check(rejection == null) { "Consent no longer valid: $rejection" }
        val violation = UrlRules.violation(forUrl, token.allowedHosts)
        check(violation == null) { violation ?: "" }
        used = true
    }
}
