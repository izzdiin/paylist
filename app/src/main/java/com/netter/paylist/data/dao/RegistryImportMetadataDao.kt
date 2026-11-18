package com.netter.paylist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.netter.paylist.data.entity.OrderRegistryEntity
import com.netter.paylist.data.entity.RegistryImportMetadataEntity
import com.netter.paylist.data.entity.RegistrySnapshotStatus

/**
 * DAO for [RegistryImportMetadataEntity] (design §4.6, R30): validated
 * atomic snapshot swap per merchant; active-snapshot lookup;
 * previous-snapshot hash/metadata retention.
 *
 * The swap validates uniqueness, schema, currency/direction, and
 * merchant identity before one atomic swap of that merchant's active
 * snapshot; an invalid or partial import leaves the prior version
 * intact (§3.1.9, R29). A later import may re-evaluate only
 * previously `unmatched` or `manual_review` events through an
 * explicit, audited operator action; it never silently overwrites
 * historical `attributed` rows (Stage D).
 */
@Dao
interface RegistryImportMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: RegistryImportMetadataEntity)

    @Query("SELECT * FROM registry_imports WHERE registry_version = :version")
    suspend fun getByVersion(version: String): RegistryImportMetadataEntity?

    @Query("SELECT * FROM registry_imports WHERE merchant_id = :merchantId AND status = 'ACTIVE'")
    suspend fun getActive(merchantId: String): RegistryImportMetadataEntity?

    @Query("SELECT * FROM registry_imports WHERE merchant_id = :merchantId ORDER BY imported_at_ms DESC")
    suspend fun getHistory(merchantId: String): List<RegistryImportMetadataEntity>

    @Query("UPDATE registry_imports SET status = 'SUPERSEDED' WHERE merchant_id = :merchantId AND status = 'ACTIVE'")
    suspend fun supersedeActive(merchantId: String): Int

    @Query("DELETE FROM order_registry WHERE merchant_id = :merchantId")
    suspend fun deleteRegistryRows(merchantId: String): Int

    @Insert
    suspend fun insertRegistryRows(rows: List<OrderRegistryEntity>)

    /**
     * Validates and atomically swaps one merchant's active snapshot.
     * Throws [IllegalArgumentException] on any invalid row — the
     * transaction rolls back and the prior version stays intact.
     *
     * Validation (design §3.1.9, R29): every row belongs to
     * [metadata]'s merchant and version; order numbers are unique
     * within the snapshot; amount is non-negative; currency and
     * direction are non-blank; direction is `in` or `out`.
     */
    @Transaction
    suspend fun swapSnapshot(metadata: RegistryImportMetadataEntity, rows: List<OrderRegistryEntity>) {
        validateSnapshot(metadata, rows)
        supersedeActive(metadata.merchantId)
        insert(metadata.copy(status = RegistrySnapshotStatus.ACTIVE))
        deleteRegistryRows(metadata.merchantId)
        if (rows.isNotEmpty()) insertRegistryRows(rows)
    }

    companion object {
        internal fun validateSnapshot(
            metadata: RegistryImportMetadataEntity,
            rows: List<OrderRegistryEntity>,
        ) {
            require(metadata.merchantId.isNotBlank()) { "registry merchant_id is blank" }
            require(metadata.registryVersion.isNotBlank()) { "registry_version is blank" }
            val seen = mutableSetOf<String>()
            for (row in rows) {
                require(row.merchantId == metadata.merchantId) {
                    "registry row merchant ${row.merchantId} != snapshot merchant ${metadata.merchantId}"
                }
                require(row.registryVersion == metadata.registryVersion) {
                    "registry row version ${row.registryVersion} != snapshot version ${metadata.registryVersion}"
                }
                require(row.orderNumber.isNotBlank()) { "registry order_number is blank" }
                require(seen.add(row.orderNumber)) {
                    "duplicate order_number ${row.orderNumber} in registry snapshot"
                }
                require(row.amount >= 0) { "registry amount is negative for ${row.orderNumber}" }
                require(row.currency.isNotBlank()) { "registry currency is blank for ${row.orderNumber}" }
                require(row.direction == "in" || row.direction == "out") {
                    "registry direction ${row.direction} is not in/out for ${row.orderNumber}"
                }
            }
        }
    }
}
