package com.netter.paylist.security

import java.net.URI

/** The result of endpoint URL validation (design §7.8). */
sealed interface EndpointValidation {
    /** A usable HTTPS endpoint URL. */
    data object Valid : EndpointValidation

    /** Not a usable endpoint URL. */
    data class Invalid(val reason: String) : EndpointValidation

    /**
     * A legacy plain-HTTP route: flagged, never silently converted to
     * HTTPS and never silently accepted — the operator must act
     * explicitly (update the URL or confirm the exception).
     */
    data object LegacyHttp : EndpointValidation
}

/**
 * HTTPS and URL validation (design §7.8, Stage C): new endpoint URLs
 * are HTTPS-only; scheme, host, credentials, and redirect policy are
 * validated. Pure (no Android, no I/O).
 *
 * Redirect policy is enforced by the transport
 * (`setInstanceFollowRedirects(false)`), not here — this validator
 * only judges the URL itself.
 */
object EndpointValidator {
    fun validate(url: String): EndpointValidation {
        if (url.isBlank()) return EndpointValidation.Invalid("empty URL")
        val uri = try {
            URI(url.trim())
        } catch (_: Exception) {
            return EndpointValidation.Invalid("malformed URL")
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (scheme != "https" && scheme != "http") {
            return EndpointValidation.Invalid("unsupported scheme: $scheme")
        }
        if (uri.userInfo != null) {
            return EndpointValidation.Invalid("credentials in URL are not allowed")
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return EndpointValidation.Invalid("missing host")
        }
        // URI.getPort() throws on an out-of-range port only via
        // URISyntaxException at parse time; a negative value other than
        // -1 cannot occur. An explicit port is accepted as-is.
        if (scheme == "http") return EndpointValidation.LegacyHttp
        return EndpointValidation.Valid
    }
}
