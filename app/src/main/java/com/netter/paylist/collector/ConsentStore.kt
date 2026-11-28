package com.netter.paylist.collector

import com.netter.paylist.data.dao.CollectorStateDao
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import org.json.JSONObject

/**
 * Per-route consent records (design §7.5 C22(l), §7.3 P6 opt-in).
 *
 * Consent is persisted, not an in-memory flag. Records live in the
 * `route_consents` collector-state document:
 * `{routes: {route_id: {granted, epoch, disclosure_version,
 * updated_at_ms, raw_opt_in, raw_opt_in_expires_at_ms}}}`.
 *
 * - Every revoke/re-grant bumps `epoch`. The claim join
 *   ([DeliveryDao.consentCheckPasses]) requires the delivery's
 *   stored consent epoch to equal the current epoch; a mismatch
 *   ⇒ BLOCKED, never dispatched, never auto-revived.
 * - The P6 raw opt-in is a separate per-route flag with its own
 *   expiry: `raw_opt_in` + `raw_opt_in_expires_at_ms` (null =
 *   no expiry set — treated as invalid until an expiry is
 *   recorded, fail-closed). Any opt-in change bumps the route
 *   epoch too, so in-flight B snapshots are fenced by the same
 *   epoch.
 * - Absent record ⇒ no consent evidence: the claim join passes
 *   (legacy imports), but B-eligibility fails — raw forwarding
 *   requires an explicit, valid opt-in ([isRawOptInValid]).
 *
 * The revoke sweep (PENDING/RETRY_WAIT deliveries → BLOCKED,
 * raw snapshots dropped, fence bumped) is the caller's job: the
 * toggle handler runs the Room transaction (epoch bump + BLOCKED
 * sweep) and awaits commit before updating the UI (§7.5(g)); the
 * snapshot drop + fence bump go through [RawSnapshotStore].
 * This class owns the record only.
 */
class ConsentStore(
    private val dao: CollectorStateDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** One route's consent record. */
    data class RouteConsent(
        val granted: Boolean,
        val epoch: Long,
        val disclosureVersion: String,
        val updatedAtMs: Long,
        /** P6 raw-forwarding opt-in (default OFF). */
        val rawOptIn: Boolean = false,
        /** P6 opt-in expiry (epoch ms); null = no expiry recorded. */
        val rawOptInExpiresAtMs: Long? = null,
    )

    companion object {
        /** Current disclosure text version (bump when the wording changes). */
        const val DISCLOSURE_VERSION: String = "1.0"
    }

    /** Reads one route's record, or null when none exists. */
    suspend fun get(routeId: String): RouteConsent? {
        val doc = readDoc() ?: return null
        val record = doc.optJSONObject(routeId) ?: return null
        return RouteConsent(
            granted = record.optBoolean("granted", false),
            epoch = record.optLong("epoch", -1),
            disclosureVersion = record.optString("disclosure_version", ""),
            updatedAtMs = record.optLong("updated_at_ms", 0),
            rawOptIn = record.optBoolean("raw_opt_in", false),
            rawOptInExpiresAtMs =
                if (record.has("raw_opt_in_expires_at_ms")) {
                    record.optLong("raw_opt_in_expires_at_ms")
                } else {
                    null
                },
        )
    }

    /** Current consent epoch for [routeId], or -1 when no record exists. */
    suspend fun epoch(routeId: String): Long = get(routeId)?.epoch ?: -1

    /**
     * Grants consent for [routeId] (onboarding / re-grant). Bumps
     * the epoch. The P6 raw opt-in is NOT granted by this call —
     * it needs its own explicit opt-in ([setRawOptIn]).
     */
    suspend fun grant(routeId: String, disclosureVersion: String = DISCLOSURE_VERSION) {
        val current = get(routeId)
        val record = JSONObject().apply {
            put("granted", true)
            put("epoch", (current?.epoch ?: -1) + 1)
            put("disclosure_version", disclosureVersion)
            put("updated_at_ms", clock())
            put("raw_opt_in", current?.rawOptIn ?: false)
            current?.rawOptInExpiresAtMs?.let { put("raw_opt_in_expires_at_ms", it) }
        }
        writeRoute(routeId, record)
    }

    /**
     * Revokes consent for [routeId]. Bumps the epoch and clears
     * the P6 raw opt-in (revocation withdraws raw forwarding too).
     * The caller runs the BLOCKED sweep + snapshot drop (§7.5).
     */
    suspend fun revoke(routeId: String) {
        val current = get(routeId)
        val record = JSONObject().apply {
            put("granted", false)
            put("epoch", (current?.epoch ?: -1) + 1)
            put("disclosure_version", current?.disclosureVersion ?: DISCLOSURE_VERSION)
            put("updated_at_ms", clock())
            put("raw_opt_in", false)
        }
        writeRoute(routeId, record)
    }

    /**
     * Sets the P6 raw-forwarding opt-in for [routeId] explicitly.
     * Granting requires [expiresAtMs] (an opt-in without an expiry
     * is invalid — fail-closed). Bumps the epoch, so snapshots
     * taken under the old epoch are fenced.
     */
    suspend fun setRawOptIn(routeId: String, granted: Boolean, expiresAtMs: Long?) {
        val current = get(routeId)
        val record = JSONObject().apply {
            put("granted", current?.granted ?: false)
            put("epoch", (current?.epoch ?: -1) + 1)
            put("disclosure_version", current?.disclosureVersion ?: DISCLOSURE_VERSION)
            put("updated_at_ms", clock())
            put("raw_opt_in", granted)
            if (granted && expiresAtMs != null) {
                put("raw_opt_in_expires_at_ms", expiresAtMs)
            }
        }
        writeRoute(routeId, record)
    }

    /**
     * The P6 validity check (§7.3 scope rule): granted AND
     * raw-opted-in AND unexpired at [nowMs]. Absent record,
     * missing expiry, or expired ⇒ false (fail-closed).
     */
    suspend fun isRawOptInValid(routeId: String, nowMs: Long): Boolean {
        val record = get(routeId) ?: return false
        if (!record.granted || !record.rawOptIn) return false
        val expires = record.rawOptInExpiresAtMs ?: return false
        return nowMs < expires
    }

    private suspend fun readDoc(): JSONObject? {
        val json = dao.getValue(CollectorStateKeys.ROUTE_CONSENTS) ?: return null
        return try {
            JSONObject(json).optJSONObject("routes")
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun writeRoute(routeId: String, record: JSONObject) {
        val routes = readDoc() ?: JSONObject()
        routes.put(routeId, record)
        val doc = JSONObject().apply { put("routes", routes) }
        dao.put(
            CollectorStateEntity(
                CollectorStateKeys.ROUTE_CONSENTS,
                doc.toString(),
                clock(),
            ),
        )
    }
}
