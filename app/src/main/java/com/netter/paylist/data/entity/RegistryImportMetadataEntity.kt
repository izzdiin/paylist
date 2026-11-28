package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/** Registry freshness values (design §4.6). */
object RegistryFreshness {
    const val VERIFIED: String = "VERIFIED"
    const val UNVERIFIED: String = "UNVERIFIED"
}

/** Registry snapshot statuses (design §4.6). */
object RegistrySnapshotStatus {
    const val ACTIVE: String = "ACTIVE"
    const val SUPERSEDED: String = "SUPERSEDED"
}

/**
 * The `registry_imports` table (design §4.6, R30/m5): metadata for
 * each imported registry snapshot, one row per (`merchant_id`,
 * `registry_version`).
 *
 * The registry import is a complete, validated snapshot for a single
 * canonical merchant with `registry_version`, `imported_at_ms`,
 * `source_owner`, and `valid_until_ms` (or an explicit
 * `freshness=UNVERIFIED` state). Imports validate uniqueness, schema,
 * currency/direction, and merchant identity before one atomic swap
 * of that merchant's active snapshot; an invalid or partial import
 * leaves the prior version intact (§3.1.9, R29).
 *
 * One `ACTIVE` snapshot per merchant; previous snapshots keep their
 * hashes/metadata, and attribution audit rows record the
 * `registry_version` they used. Registry status must be explicitly
 * `ACTIVE` before advisory attribution; missing, expired,
 * unknown-freshness, cancelled, or ambiguous orders are held for
 * operator review and never auto-attributed.
 */
@Entity(
    tableName = "registry_imports",
    // Composite key: distinct merchants may reuse the same version
    // string ("v1"); a registry_version-only PK let one merchant's
    // import REPLACE another merchant's metadata row (D2b, F52/F46b).
    // The v1 schema is unreleased (no shipped app has this table),
    // so the key changes in place with no migration.
    primaryKeys = ["merchant_id", "registry_version"],
    indices = [
        Index(value = ["merchant_id", "status"]),
    ],
)
data class RegistryImportMetadataEntity(
    /** Part of the composite primary key (`merchant_id`, `registry_version`). */
    @ColumnInfo(name = "registry_version")
    val registryVersion: String,

    /** Canonical merchant identity this snapshot belongs to. */
    @ColumnInfo(name = "merchant_id")
    val merchantId: String,

    /** Who supplied this snapshot (operator/system identity). */
    @ColumnInfo(name = "source_owner")
    val sourceOwner: String,

    /** Import time (epoch ms). */
    @ColumnInfo(name = "imported_at_ms")
    val importedAtMs: Long,

    /** Expiry time (epoch ms), or null for no stated expiry. */
    @ColumnInfo(name = "valid_until_ms")
    val validUntilMs: Long?,

    /** `VERIFIED` / `UNVERIFIED` freshness (§3.1.9, R29). */
    @ColumnInfo(name = "freshness")
    val freshness: String,

    /** Canonical hash of the snapshot content (audit trail). */
    @ColumnInfo(name = "snapshot_hash")
    val snapshotHash: String,

    /** `ACTIVE` / `SUPERSEDED` — one `ACTIVE` snapshot per merchant. */
    @ColumnInfo(name = "status")
    val status: String,
)
