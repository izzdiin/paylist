package com.netter.paylist.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.withTransaction
import com.netter.paylist.core.model.DeliveryKind
import com.netter.paylist.core.model.DeliveryState
import com.netter.paylist.data.dao.AttemptDao
import com.netter.paylist.data.dao.CaptureLogDao
import com.netter.paylist.data.dao.CollectorStateDao
import com.netter.paylist.data.dao.DeliveryDao
import com.netter.paylist.data.dao.EventDao
import com.netter.paylist.data.dao.ExportAuditDao
import com.netter.paylist.data.dao.MatchResultDao
import com.netter.paylist.data.dao.OrderRegistryDao
import com.netter.paylist.data.dao.RegistryImportMetadataDao
import com.netter.paylist.data.entity.AttemptState
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureStagingDao
import com.netter.paylist.data.entity.CaptureStagingEntity
import com.netter.paylist.data.entity.ClassificationDao
import com.netter.paylist.data.entity.ClassificationEntity
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.DeliveryAttemptEntity
import com.netter.paylist.data.entity.DeliveryEntity
import com.netter.paylist.data.entity.EventEntity
import com.netter.paylist.data.entity.ExportAuditEntity
import com.netter.paylist.data.entity.MatchResultEntity
import com.netter.paylist.data.entity.OrderRegistryEntity
import com.netter.paylist.data.entity.PromotionReceiptDao
import com.netter.paylist.data.entity.PromotionReceiptEntity
import com.netter.paylist.data.entity.ReceiptState
import com.netter.paylist.data.entity.RegistryImportMetadataEntity

/**
 * Runs a block inside one Room transaction (or directly, in tests).
 *
 * A plain interface, not a `fun interface`: a functional interface's
 * single abstract method cannot have type parameters, and `run` is
 * generic over the block's result type.
 */
interface TransactionRunner {
    suspend fun <T> run(block: suspend () -> T): T
}

/** Enum <-> TEXT converters for the Room schema (design §4). */
object PaylistConverters {
    @TypeConverter
    fun fromDeliveryKind(kind: DeliveryKind): String = kind.name

    @TypeConverter
    fun toDeliveryKind(value: String): DeliveryKind = DeliveryKind.valueOf(value)

    @TypeConverter
    fun fromDeliveryState(state: DeliveryState): String = state.name

    @TypeConverter
    fun toDeliveryState(value: String): DeliveryState = DeliveryState.valueOf(value)

    @TypeConverter
    fun fromAttemptState(state: AttemptState): String = state.name

    @TypeConverter
    fun toAttemptState(value: String): AttemptState = AttemptState.valueOf(value)

    @TypeConverter
    fun fromReceiptState(state: ReceiptState): String = state.name

    @TypeConverter
    fun toReceiptState(value: String): ReceiptState = ReceiptState.valueOf(value)
}

/**
 * The Room database (design §4, schema v2): `paylist-outbox`.
 *
 * Schema v1 is a fresh database — there is no Room-to-Room migration
 * in v1.1. `fallbackToDestructiveMigration()` is FORBIDDEN (design
 * §4): future migrations must be explicit (`Migration(1, 2)` …) so
 * the outbox is never lost on upgrade.
 *
 * v2 (Stage D D4, addendum 06 LH-04) adds the `capture_log` table
 * ([MIGRATION_1_2], purely additive — no existing table touched).
 *
 * Schema export is disabled for now (`exportSchema = false`): the
 * schema JSON location is build configuration, and the first
 * migration is additive-only, so no schema diff is needed to prove
 * it. Enabling schema export is a pre-release (Stage E) item, before
 * any migration that alters an existing table.
 */
@Database(
    entities = [
        EventEntity::class,
        DeliveryEntity::class,
        DeliveryAttemptEntity::class,
        CollectorStateEntity::class,
        CaptureStagingEntity::class,
        PromotionReceiptEntity::class,
        OrderRegistryEntity::class,
        MatchResultEntity::class,
        ClassificationEntity::class,
        RegistryImportMetadataEntity::class,
        ExportAuditEntity::class,
        CaptureLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(PaylistConverters::class)
abstract class PaylistDatabase : RoomDatabase() {
    abstract fun eventDao(): EventDao
    abstract fun deliveryDao(): DeliveryDao
    abstract fun attemptDao(): AttemptDao
    abstract fun collectorStateDao(): CollectorStateDao
    abstract fun captureStagingDao(): CaptureStagingDao
    abstract fun promotionReceiptDao(): PromotionReceiptDao
    abstract fun orderRegistryDao(): OrderRegistryDao
    abstract fun matchResultDao(): MatchResultDao
    abstract fun classificationDao(): ClassificationDao
    abstract fun registryImportMetadataDao(): RegistryImportMetadataDao
    abstract fun exportAuditDao(): ExportAuditDao
    abstract fun captureLogDao(): CaptureLogDao

    /** A [TransactionRunner] backed by this database's transactions. */
    fun transactionRunner(): TransactionRunner = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T = withTransaction(block)
    }

    companion object {
        /** Database file name (design Phase 1.2: `paylist-outbox`). */
        const val DATABASE_NAME: String = "paylist-outbox.db"

        /** Opens (or creates) the production database. Never destructive. */
        fun create(context: Context): PaylistDatabase =
            Room.databaseBuilder(context, PaylistDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()

        /** In-memory database for instrumented tests. */
        fun createInMemory(context: Context): PaylistDatabase =
            Room.inMemoryDatabaseBuilder(context, PaylistDatabase::class.java)
                .build()
    }
}
