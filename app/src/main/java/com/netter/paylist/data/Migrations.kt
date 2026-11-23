package com.netter.paylist.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room schema migrations (design §4: `fallbackToDestructiveMigration()`
 * is FORBIDDEN — every version bump is an explicit migration so the
 * outbox is never lost on upgrade).
 *
 * v1 → v2 (Stage D D4, addendum 06 LH-04): adds the `capture_log`
 * table + indexes. Purely additive — no existing table is touched,
 * no data is moved, so no schema export is needed to prove the
 * migration (enabling schema export remains a Stage E item).
 *
 * Index names follow Room's generated pattern
 * (`index_<table>_<columns>`) so the migrated schema matches the
 * schema Room derives from [com.netter.paylist.data.entity.CaptureLogEntity].
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `capture_log` (" +
                "`capture_id` TEXT NOT NULL, " +
                "`source_package` TEXT NOT NULL, " +
                "`post_time_ms` INTEGER NOT NULL, " +
                "`captured_at_ms` INTEGER NOT NULL, " +
                "`outcome` TEXT NOT NULL, " +
                "`title_snippet` TEXT, " +
                "`amount_minor` INTEGER, " +
                "`event_id` TEXT, " +
                "`content_sha256` TEXT, " +
                "PRIMARY KEY(`capture_id`), " +
                "FOREIGN KEY(`event_id`) REFERENCES `events`(`event_id`) " +
                "ON UPDATE NO ACTION ON DELETE SET NULL)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_capture_log_source_package_post_time_ms` " +
                "ON `capture_log` (`source_package`, `post_time_ms`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_capture_log_post_time_ms` " +
                "ON `capture_log` (`post_time_ms`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_capture_log_event_id` " +
                "ON `capture_log` (`event_id`)",
        )
    }
}
