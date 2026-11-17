package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

/**
 * The `order_registry` table (design §4.6, P3): the versioned order
 * registry, supplied as a versioned import in v1.1 (no live contract
 * endpoint, §3.1.7).
 *
 * The table holds the ACTIVE snapshot only: an import replaces a
 * merchant's snapshot atomically (delete + insert in one transaction),
 * and previous snapshots survive only as hashes/metadata in
 * `registry_imports` (§4.6, R29). The unique index on
 * (`merchant_id`, `order_number`) is therefore the cross-account
 * uniqueness constraint (P3(b)): an `order_number` is unique per
 * `merchant_id` across all source/bank accounts; distinct merchants
 * may reuse the same order number. No source-account column exists
 * in the key, and registry lookups are keyed by
 * (`merchant_id`, `order_number`) with no source-account component.
 *
 * `merchant_id` is the registry key assigned at merchant
 * registration (operator-entered, stable across source accounts);
 * it is never derived from a notification span and never inferred
 * from free text (§3.1.4).
 */
@Entity(
    tableName = "order_registry",
    primaryKeys = ["merchant_id", "order_number"],
    indices = [
        Index(value = ["registry_version"]),
    ],
)
data class OrderRegistryEntity(
    /** Canonical merchant identity, assigned at merchant registration. */
    @ColumnInfo(name = "merchant_id")
    val merchantId: String,

    /** Merchant-specified order number. */
    @ColumnInfo(name = "order_number")
    val orderNumber: String,

    /** Customer id, when the registry supplies one. */
    @ColumnInfo(name = "customer_id")
    val customerId: String?,

    /** Registered order amount, in integer minor units. */
    @ColumnInfo(name = "amount")
    val amount: Long,

    /** ISO 4217 currency code. */
    @ColumnInfo(name = "currency")
    val currency: String,

    /** `in` | `out` — the expected transaction direction. */
    @ColumnInfo(name = "direction")
    val direction: String,

    /** Registry order status (as supplied by the import). */
    @ColumnInfo(name = "status")
    val status: String,

    /** Registry version this row belongs to. */
    @ColumnInfo(name = "registry_version")
    val registryVersion: String,

    /** Import time (epoch ms). */
    @ColumnInfo(name = "imported_at_ms")
    val importedAtMs: Long,
)
