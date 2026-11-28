package com.netter.paylist.collector

import com.netter.paylist.Route

/**
 * Package allowlist and keyword checks (file plan §B, Stage D).
 *
 * Pure functions over the route list — no Android, no I/O — so the
 * capture routing rules are unit-testable on the JVM. The rules are
 * the v2.3 listener rules, unchanged:
 * - a notification is a candidate only for ENABLED routes that list
 *   its package;
 * - a route joins the chain only when one of its keywords occurs
 *   (substring, case-insensitive) in the combined title/text/bigText.
 *
 * Consent checks (P6 opt-in, revocation) live in `PrivacyGate`
 * (Stage D, D2); this object answers only "which routes see this
 * notification".
 */
object SourcePolicy {

    /** Enabled routes listing [packageName] as a source package. */
    fun routesForPackage(routes: List<Route>, packageName: String): List<Route> =
        routes.filter { it.enabled && packageName in it.packages }

    /**
     * The routes whose keywords match [combinedText] (substring,
     * case-insensitive). Only these routes join the delivery chain
     * for this notification.
     */
    fun keywordMatchedRoutes(routes: List<Route>, combinedText: String): List<Route> {
        val lower = combinedText.lowercase()
        return routes.filter { r -> r.keywords.any { it in lower } }
    }
}
