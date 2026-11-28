package com.netter.paylist.matching

import com.netter.paylist.data.dao.OrderRegistryDao
import com.netter.paylist.data.dao.RegistryImportMetadataDao
import com.netter.paylist.data.entity.OrderRegistryEntity
import com.netter.paylist.data.entity.RegistryFreshness
import com.netter.paylist.data.entity.RegistryImportMetadataEntity
import com.netter.paylist.data.entity.RegistrySnapshotStatus
import java.security.MessageDigest

/**
 * The versioned order registry (design §3.1.4/§3.1.5/§4.6, P3 —
 * owner-decided 2026-11-15).
 *
 * In v1.1 the registry is supplied as a versioned import (no live
 * contract endpoint, §3.1.7). One `ACTIVE` snapshot per merchant;
 * an import replaces a merchant's snapshot atomically (validated
 * swap — an invalid or partial import leaves the prior version
 * intact, §3.1.9/R29); previous snapshots survive as
 * hashes/metadata in `registry_imports`.
 *
 * Lookup rule: an order is visible to the matcher only when its
 * merchant's snapshot is `ACTIVE`, `VERIFIED`, and unexpired.
 * Missing, expired, or unknown-freshness snapshots ⇒ the lookup
 * returns null and the matcher holds the event for operator
 * review — never auto-attributed (§4.6).
 *
 * A later import never silently re-evaluates historical
 * attributions: previously `attributed` rows are untouched, and
 * previously `unmatched`/`manual_review` events are re-evaluated
 * only by an explicit, audited operator action (Stage D, D3
 * review surface). This class performs no re-evaluation.
 */
class OrderRegistry(
    private val registryDao: OrderRegistryDao,
    private val metadataDao: RegistryImportMetadataDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** The outcome of a registry import. */
    sealed interface ImportResult {
        /** The snapshot swapped in atomically. */
        data class Imported(
            val merchantId: String,
            val registryVersion: String,
            val rowCount: Int,
        ) : ImportResult

        /** Validation failed — the prior version is intact. */
        data class Rejected(val reason: String) : ImportResult
    }

    /**
     * Imports one merchant's snapshot (versioned import file,
     * §3.1.5). Validates uniqueness, schema, currency/direction,
     * and merchant identity, then atomically swaps the merchant's
     * active snapshot; the previous snapshot becomes SUPERSEDED
     * (its hash/metadata retained). Returns [ImportResult.Rejected]
     * (prior version intact) on any validation failure.
     */
    suspend fun importSnapshot(
        merchantId: String,
        registryVersion: String,
        sourceOwner: String,
        rows: List<OrderRegistryEntity>,
        validUntilMs: Long? = null,
        freshness: String = RegistryFreshness.VERIFIED,
    ): ImportResult {
        val now = clock()
        val metadata = RegistryImportMetadataEntity(
            registryVersion = registryVersion,
            merchantId = merchantId,
            sourceOwner = sourceOwner,
            importedAtMs = now,
            validUntilMs = validUntilMs,
            freshness = freshness,
            snapshotHash = snapshotHash(rows),
            status = RegistrySnapshotStatus.ACTIVE,
        )
        return try {
            metadataDao.swapSnapshot(metadata, rows)
            ImportResult.Imported(merchantId, registryVersion, rows.size)
        } catch (e: IllegalArgumentException) {
            ImportResult.Rejected(e.message ?: "invalid registry snapshot")
        }
    }

    /**
     * Looks up an order by (`merchant_id`, `order_number`) — no
     * source-account component in the key (P3(b)). Returns null
     * when the merchant has no usable snapshot (missing, expired,
     * or `UNVERIFIED`) or the order is absent from the active
     * snapshot.
     */
    suspend fun lookup(merchantId: String, orderNumber: String): OrderRegistryEntity? {
        if (!isSnapshotUsable(merchantId)) return null
        return registryDao.lookup(merchantId, orderNumber)
    }

    /** The merchant's active snapshot metadata, or null when none is active. */
    suspend fun activeSnapshot(merchantId: String): RegistryImportMetadataEntity? =
        metadataDao.getActive(merchantId)

    /** Snapshot history for a merchant, newest first (hashes/metadata retained). */
    suspend fun history(merchantId: String): List<RegistryImportMetadataEntity> =
        metadataDao.getHistory(merchantId)

    /**
     * Whether the merchant's active snapshot may be used for
     * advisory attribution: `ACTIVE` + `VERIFIED` + unexpired.
     * Unknown freshness or an expired snapshot ⇒ unusable (§4.6).
     */
    suspend fun isSnapshotUsable(merchantId: String): Boolean {
        val active = metadataDao.getActive(merchantId) ?: return false
        if (active.freshness != RegistryFreshness.VERIFIED) return false
        val validUntil = active.validUntilMs
        if (validUntil != null && clock() >= validUntil) return false
        return true
    }

    /**
     * Canonical snapshot hash: SHA-256 over the rows sorted by
     * order number, one `order_number|amount|currency|direction|status|customer_id`
     * line per row. Deterministic for the same content (audit trail).
     */
    fun snapshotHash(rows: List<OrderRegistryEntity>): String {
        val canonical = rows.sortedBy { it.orderNumber }.joinToString("\n") { row ->
            listOf(
                row.orderNumber, row.amount.toString(), row.currency,
                row.direction, row.status, row.customerId.orEmpty(),
            ).joinToString("|")
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
