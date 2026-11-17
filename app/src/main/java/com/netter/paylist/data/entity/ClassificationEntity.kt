package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The `classification_results` table (design §4.6, P3): advisory
 * transaction classification results, computed independently of
 * `match_results` — a transaction can be `matched` to an order and
 * still `UNCLASSIFIED`, or classified `donation` with no order match
 * (§3.1.2). Neither subsystem reads the other's output as an input;
 * there is no FK between the two tables.
 */
@Entity(
    tableName = "classification_results",
    foreignKeys = [
        ForeignKey(
            entity = EventEntity::class,
            parentColumns = ["event_id"],
            childColumns = ["event_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["event_id"]),
        Index(value = ["category"]),
    ],
)
data class ClassificationEntity(
    /** Primary key, UUID. */
    @PrimaryKey
    @ColumnInfo(name = "classification_id")
    val classificationId: String,

    /** FK -> events, ON DELETE CASCADE. */
    @ColumnInfo(name = "event_id")
    val eventId: String,

    /** Classification category (`UNCLASSIFIED` default). */
    @ColumnInfo(name = "category")
    val category: String = "UNCLASSIFIED",

    /** Classification confidence, 0.0–1.0. */
    @ColumnInfo(name = "confidence")
    val confidence: Double,

    /** The rule id that fired. */
    @ColumnInfo(name = "rule_id")
    val ruleId: String,

    /** Full input set as JSON, so a reviewer can replay the decision. */
    @ColumnInfo(name = "audit_json")
    val auditJson: String,

    /** Row creation time (epoch ms). */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,
)

/** DAO for [ClassificationEntity] (carried in this file, like `CaptureStagingDao`). */
@Dao
interface ClassificationDao {
    @Insert
    suspend fun insert(result: ClassificationEntity)

    @Query("SELECT * FROM classification_results WHERE event_id = :eventId")
    suspend fun getByEventId(eventId: String): List<ClassificationEntity>

    @Query("SELECT * FROM classification_results WHERE category = :category")
    suspend fun getByCategory(category: String): List<ClassificationEntity>
}
