package com.netter.paylist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.netter.paylist.data.entity.OrderRegistryEntity

/**
 * DAO for [OrderRegistryEntity] (design §4.6, P3): registry import
 * (atomic version replace) + lookup by (`merchant_id`, `order_number`).
 *
 * Lookups are keyed by (`merchant_id`, `order_number`) with no
 * source-account component in the key (§3.1.4, P3(b)).
 */
@Dao
interface OrderRegistryDao {
    @Query("SELECT * FROM order_registry WHERE merchant_id = :merchantId AND order_number = :orderNumber")
    suspend fun lookup(merchantId: String, orderNumber: String): OrderRegistryEntity?

    @Query("SELECT * FROM order_registry WHERE merchant_id = :merchantId")
    suspend fun getByMerchant(merchantId: String): List<OrderRegistryEntity>

    @Query("SELECT COUNT(*) FROM order_registry WHERE merchant_id = :merchantId")
    suspend fun countByMerchant(merchantId: String): Int

    @Query("DELETE FROM order_registry WHERE merchant_id = :merchantId")
    suspend fun deleteByMerchant(merchantId: String): Int

    @Insert
    suspend fun insertAll(rows: List<OrderRegistryEntity>)

    /**
     * Replaces a merchant's active snapshot atomically: delete the
     * old rows, insert the new ones, in one transaction. The caller
     * (the registry-import metadata swap) validates the rows and
     * updates the metadata in the same transaction scope.
     */
    @Transaction
    suspend fun replaceSnapshot(merchantId: String, rows: List<OrderRegistryEntity>) {
        deleteByMerchant(merchantId)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}
