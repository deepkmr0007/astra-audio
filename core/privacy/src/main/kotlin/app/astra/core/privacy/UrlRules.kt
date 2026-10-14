package app.astra.core.privacy

import java.net.URI

/** URL validation shared by the gateway and the transport (defence in depth). */
object UrlRules {

    /** Lower-cased ASCII host of an https URL, or null if the URL is not acceptable. */
    fun hostOf(url: String): String? = try {
        val uri = URI(url)
        uri.host?.lowercase()
    } catch (e: Exception) {
        null
    }

    /**
     * Returns null when [url] is acceptable, otherwise a reason:
     * HTTPS only, default port only, no user-info, ASCII host (IDN must be punycode),
     * no IP literals, and the host must be in [allowedHosts].
     */
    fun violation(url: String, allowedHosts: Set<String>): String? {
        val uri = try { URI(url) } catch (e: Exception) { return "Malformed URL" }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return "Only HTTPS is allowed"
        if (uri.rawUserInfo != null) return "Credentials in URL are not allowed"
        val host = uri.host?.lowercase() ?: return "URL has no host"
        if (host.any { it.code > 127 }) return "Non-ASCII host names are not allowed"
        if (isIpLiteral(host)) return "IP-address destinations are not allowed"
        if (uri.port != -1 && uri.port != 443) return "Non-standard port ${uri.port} is not allowed"
        val allowed = allowedHosts.map { it.lowercase() }.toSet()
        if (host !in allowed) return "Host $host was not approved"
        return null
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.startsWith("[")) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } }
    }

    /** Resolves a redirect Location header against the current URL. */
    fun resolve(base: String, location: String): String? = try {
        URI(base).resolve(location.trim()).toString()
    } catch (e: Exception) {
        null
    }
}
