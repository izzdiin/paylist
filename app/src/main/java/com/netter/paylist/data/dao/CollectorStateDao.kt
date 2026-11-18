package com.netter.paylist.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.netter.paylist.data.entity.CollectorStateEntity

/**
 * DAO for [CollectorStateEntity] (design §4.4): status writes/reads
 * and the mode/intent/fence transactions.
 *
 * The authoritative mode store is Room `collector_state`. Mode +
 * migration state are written in a single Room transaction; a crash
 * mid-transaction rolls back and the old mode stands (§4.4).
 */
@Dao
interface CollectorStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: CollectorStateEntity)

    @Query("SELECT * FROM collector_state WHERE key = :key")
    suspend fun get(key: String): CollectorStateEntity?

    @Query("SELECT value_json FROM collector_state WHERE key = :key")
    suspend fun getValue(key: String): String?

    @Query("DELETE FROM collector_state WHERE key = :key")
    suspend fun delete(key: String): Int

    @Query("SELECT * FROM collector_state")
    suspend fun getAll(): List<CollectorStateEntity>

    /** Writes several keys atomically (e.g. mode + intent + migration state). */
    @Transaction
    suspend fun putAll(states: List<CollectorStateEntity>) {
        for (state in states) put(state)
    }
}
