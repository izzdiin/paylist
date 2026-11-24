package com.netter.paylist.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.data.entity.CaptureLogEntity
import com.netter.paylist.data.entity.CaptureOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * The Room v1 → v2 migration (Stage D D4, addendum 06 LH-04)
 * against REAL SQLite: a v1 database is built from the v1 schema,
 * a row is inserted, then the file is opened with Room v2 +
 * [MIGRATION_1_2] and Room's own post-migration schema validation
 * runs. The old row must survive; `capture_log` must exist and be
 * writable through the DAO.
 *
 * The v1 schema is derived, not duplicated: the v1 tables are
 * byte-identical to the v2 tables (the migration is purely
 * additive), so the test dumps the CREATE statements from a fresh
 * Room v2 in-memory database and replays all but `capture_log`
 * (and its indexes) into the v1 file. If a v1 table ever changes,
 * this test fails loudly instead of silently testing stale DDL.
 *
 * EXECUTION NOTE: this test needs a device or an emulator. It
 * COMPILES on laptopgrt2 (`compileDebugAndroidTestKotlin`) but
 * cannot execute there — there is no device/emulator on that box.
 * Do not report it as "passing" from a compile-only gate.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    companion object {
        const val DB_NAME = "migration-test-v1.db"
        const val NOW = 1_794_960_000_000L
    }

    /** CREATE statements for every v2 table/index except `capture_log`. */
    private fun v1SchemaSql(context: Context): List<String> {
        val tmp = Room.inMemoryDatabaseBuilder(context, PaylistDatabase::class.java).build()
        try {
            val sql = mutableListOf<String>()
            val cursor = tmp.openHelper.readableDatabase.query(
                SimpleSQLiteQuery(
                    "SELECT sql FROM sqlite_master WHERE sql IS NOT NULL " +
                        "AND name NOT LIKE 'capture_log%' " +
                        "AND name NOT LIKE 'index_capture_log%' " +
                        "AND name != 'room_master_table' " +
                        // android_metadata is created by the framework
                        // before onCreate runs; replaying its CREATE
                        // fails ("table android_metadata already exists").
                        "AND name != 'android_metadata' " +
                        "ORDER BY type DESC, name",
                ),
            )
            cursor.use {
                while (it.moveToNext()) {
                    sql.add(it.getString(0))
                }
            }
            return sql
        } finally {
            tmp.close()
        }
    }

    @Test
    fun migrate1To2_keepsOldRows_addsCaptureLog() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)
        val v1Sql = v1SchemaSql(context)
        assertTrue("v1 schema must have tables", v1Sql.size > 11)

        // Build the v1 file with raw SQLite (no Room): schema +
        // one events row, user_version = 1.
        val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        for (statement in v1Sql) {
                            db.execSQL(statement)
                        }
                    }

                    override fun onUpgrade(
                        db: androidx.sqlite.db.SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                })
                .build(),
        )
        val eventId = UUID.randomUUID().toString()
        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO events (event_id, schema_version, source_type, source_event_id, device_id, " +
                    "event_type, category, source_package, occurred_at_ms, captured_at_ms, " +
                    "data_json, metadata_json, legacy_payload_json, privacy_mode, " +
                    "retention_until_ms, dedupe_key, content_sha256, export_ref, " +
                    "source_account_key) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf(
                    eventId, 1, "android_notification", "k|1", "Moto g57",
                    "payment.notification", "payment", "com.gojek.gopay",
                    NOW, NOW, """{"amount":100800}""", "{}", null, "structured",
                    null, "com.gojek.gopay|1|100800|t1", null,
                    UUID.randomUUID().toString(), null,
                ),
            )
        }
        helper.close()

        // Open with Room v2: the migration runs, then Room validates
        // the migrated schema against the entities.
        val migrated = Room.databaseBuilder(context, PaylistDatabase::class.java, DB_NAME)
            .addMigrations(MIGRATION_1_2)
            .build()
        try {
            val tables = mutableSetOf<String>()
            val cursor = migrated.openHelper.readableDatabase.query(
                SimpleSQLiteQuery("SELECT name FROM sqlite_master WHERE type = 'table'"),
            )
            cursor.use {
                while (it.moveToNext()) {
                    tables.add(it.getString(0))
                }
            }
            assertTrue("capture_log must exist after migration", tables.contains("capture_log"))

            kotlinx.coroutines.runBlocking {
                val kept = migrated.eventDao().getById(eventId)
                assertNotNull("the v1 event row must survive the migration", kept)
                assertEquals("com.gojek.gopay|1|100800|t1", kept!!.dedupeKey)

                // The new table is writable through the DAO.
                val row = CaptureLogEntity(
                    captureId = UUID.randomUUID().toString(),
                    sourcePackage = "com.gojek.gopay",
                    postTimeMs = NOW,
                    capturedAtMs = NOW,
                    outcome = CaptureOutcome.CAPTURED,
                    titleSnippet = "GoPay",
                    amountMinor = 100800L,
                    eventId = eventId,
                    contentSha256 = null,
                )
                migrated.captureLogDao().insert(row)
                assertNotNull(migrated.captureLogDao().getById(row.captureId))
                assertEquals(1, migrated.captureLogDao().count())
            }
        } finally {
            migrated.close()
            context.deleteDatabase(DB_NAME)
        }
    }
}
