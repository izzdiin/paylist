package com.netter.paylist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.netter.paylist.data.entity.ExportAuditEntity

/**
 * DAO for [ExportAuditEntity] (design §4.7, R31): one audit row per
 * export attempt, including cancelled/failed attempts.
 */
@Dao
interface ExportAuditDao {
    @Insert
    suspend fun insert(audit: ExportAuditEntity)

    @Query("SELECT * FROM export_audit WHERE export_id = :id")
    suspend fun getById(id: String): ExportAuditEntity?

    @Query("SELECT * FROM export_audit ORDER BY exported_at_ms DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<ExportAuditEntity>

    @Query("SELECT COUNT(*) FROM export_audit WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}
