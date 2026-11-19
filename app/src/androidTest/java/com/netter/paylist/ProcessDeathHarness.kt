package com.netter.paylist

import android.content.Context
import androidx.room.Room
import com.netter.paylist.data.PaylistDatabase
import com.netter.paylist.data.entity.CollectorStateEntity
import com.netter.paylist.data.entity.CollectorStateKeys
import com.netter.paylist.migration.CutoverCoordinator
import com.netter.paylist.migration.LegacyQueueMigration
import com.netter.paylist.migration.LegacyQueueSnapshotSource
import com.netter.paylist.security.SecretVault
import org.json.JSONObject

/**
 * Shared state for the real process-death tests (P1-1, R03/R04/R05).
 * PhaseA and PhaseB run in SEPARATE processes (separate
 * `am instrument` invocations — PhaseA kills its own process), so
 * everything they share must be durable: the file-backed test Room
 * database, the SharedPreferences queue, the server ledger file,
 * and the phase markers in SharedPreferences.
 */
object ProcessDeathHarness {
    /** The file-backed Room database both phases open (never in-memory). */
    const val TEST_DB_NAME: String = "paylist-process-death-test.db"

    const val NOW: Long = 1_795_000_000_000L

    /** Lease length, design §5.1: 5 min, no renewal. */
    const val LEASE_MS: Long = 300_000L

    const val BOOT_A: String = "boot-death-a"
    const val BOOT_B: String = "boot-death-b"

    val ROUTES: List<String> = listOf("route-1", "route-2")

    /** Marker key in the "paylist" prefs: PhaseA writes, PhaseB requires. */
    fun markerKey(scenario: String): String = "process_death_$scenario"

    fun writeMarker(ctx: Context, scenario: String) {
        val ok = ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit()
            .putString(markerKey(scenario), "a-complete").commit()
        check(ok) { "marker write must commit for $scenario" }
    }

    fun readMarker(ctx: Context, scenario: String): String? =
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE)
            .getString(markerKey(scenario), null)

    /** Fresh state for PhaseA: empty prefs, no test DB, no ledger. */
    fun clearAllDurableState(ctx: Context) {
        ctx.getSharedPreferences("paylist", Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getSharedPreferences(SecretVault.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.deleteDatabase(TEST_DB_NAME)
        ServerLedger.delete(ctx)
    }

    fun seedRoutes(ctx: Context) {
        Store(ctx).saveRoutes(
            listOf(
                Route(
                    "route-1", "v2New", listOf("com.gojek.gopaymerchant"),
                    "https://example.invalid/notify-1", Route.DEFAULT_KEYWORDS, true,
                ),
                Route(
                    "route-2", "warganet", listOf("com.gojek.gopaymerchant"),
                    "https://example.invalid/notify-2", Route.DEFAULT_KEYWORDS, true,
                ),
            ),
        )
    }

    fun openTestDb(ctx: Context): PaylistDatabase =
        Room.databaseBuilder(ctx, PaylistDatabase::class.java, TEST_DB_NAME).build()

    /** The queue source over the real [Store], as in production. */
    fun storeQueueSource(store: Store): LegacyQueueSnapshotSource =
        object : LegacyQueueSnapshotSource {
            override fun queueSnapshot(): List<JSONObject> = store.queueSnapshot()
            override fun saveQueueSync(items: List<JSONObject>): Boolean =
                store.saveQueueSync(items)
        }

    fun newCoordinator(db: PaylistDatabase, queueSource: LegacyQueueSnapshotSource): CutoverCoordinator {
        val migration = LegacyQueueMigration(
            db.eventDao(),
            db.deliveryDao(),
            db.attemptDao(),
            db.promotionReceiptDao(),
            db.transactionRunner(),
        )
        return CutoverCoordinator(
            db.collectorStateDao(),
            db.deliveryDao(),
            db.attemptDao(),
            db.transactionRunner(),
            migration,
            queueSource,
            SenderLock.lock,
            neverSentGatePassed = true,
        )
    }

    suspend fun setBootId(db: PaylistDatabase, bootId: String) {
        db.collectorStateDao().put(
            CollectorStateEntity(
                CollectorStateKeys.BOOT_ID,
                JSONObject().put("boot_id", bootId).put("boot_started_at_ms", NOW).toString(),
                NOW,
            ),
        )
    }
}

/** A queue source that fails every durable write while armed. */
class FailingQueueSource(
    private val delegate: LegacyQueueSnapshotSource,
    var failWrites: Boolean = false,
) : LegacyQueueSnapshotSource {
    override fun queueSnapshot(): List<JSONObject> = delegate.queueSnapshot()
    override fun saveQueueSync(items: List<JSONObject>): Boolean =
        if (failWrites) false else delegate.saveQueueSync(items)
}
