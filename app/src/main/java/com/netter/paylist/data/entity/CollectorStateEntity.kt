package com.netter.paylist.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Well-known `collector_state` keys (design §4.4). The authoritative
 * mode store is Room `collector_state` — no SharedPreferences mode
 * value exists in v1.1 (R14; asserted by the F27 static check in
 * `CutoverTest`).
 *
 * Values are JSON documents; the coordinator owns each document's
 * schema (see `CutoverCoordinator`). Mode + migration state are
 * written in a single Room transaction; a crash mid-transaction rolls
 * back and the old mode stands (§4.4).
 */
object CollectorStateKeys {
    /** Cutover mode: `LEGACY_ONLY` / `DRAINING` / `WORKER_ONLY` (+ blocked flag). */
    const val CUTOVER_STATE: String = "cutover_state"

    /** Drain intent: `{generation, requested_at_ms, state, requested_by}`. */
    const val DRAIN_INTENT: String = "drain_intent"

    /** Current process boot identity: `{boot_id, boot_started_at_ms}`. */
    const val BOOT_ID: String = "boot_id"

    /** Startup fence: `{fenced, updated_at_ms}` (§8.5(c)). */
    const val SENDER_FENCED: String = "sender_fenced"

    /** Monotonic fence generation counter: `{generation}`. */
    const val FENCE_GENERATION: String = "fence_generation"

    /** Import phase marker: `{phase, snapshot_count, snapshot_checksum, imported_count, …}`. */
    const val CUTOVER_IMPORT: String = "cutover_import"

    /** Migration state JSON (import progress / commit record). */
    const val MIGRATION_STATE: String = "migration_state"

    /** Durable drain diagnostic: `{state: timeout, qkey, hop_index, at_ms}` (§8.5). */
    const val DRAIN_STATE: String = "drain_state"

    /** Per-route consent records: `{routes: {route_id: {granted, epoch, disclosure_version, updated_at_ms}}}`. */
    const val ROUTE_CONSENTS: String = "route_consents"

    /** P12 writer-coverage record, per system and endpoint kind (§9.2). */
    const val P12_COVERAGE: String = "p12_coverage"

    /** Migration clear record: counts + checksum of the step-(6) clear (§8.2). */
    const val MIGRATION_CLEAR: String = "migration_clear"

    /** Prefix for sweep fence documents: `sweep_fence:<fence_id>` (§8.6). */
    const val SWEEP_FENCE_PREFIX: String = "sweep_fence:"

    /** Prefix for sweep intent documents: `sweep_intent:<sweep_intent_id>` (§8.6). */
    const val SWEEP_INTENT_PREFIX: String = "sweep_intent:"

    /** Prefix for sweep records: `sweep_record:<dedupe_key>` (§8.6). */
    const val SWEEP_RECORD_PREFIX: String = "sweep_record:"

    /** Prefix for held-copy release records: `release_record:<dedupe_key>` (§8.7, F41). */
    const val RELEASE_RECORD_PREFIX: String = "release_record:"

    /** Diagnostic counters document (capture/staging/drain counters, §8.3). */
    const val DIAGNOSTICS: String = "diagnostics"
}

/**
 * The `collector_state` table (design §4.4): a key/value store for
 * persisted collector status. Keys are the [CollectorStateKeys]
 * constants; values are JSON documents.
 *
 * Keys carried here: listener connected/disconnected, last
 * captured/persisted, last send/ACK, last heartbeat generated/ACKed,
 * migration status, storage errors, `cutover_state`
 * (`LEGACY_ONLY` / `DRAINING` / `WORKER_ONLY`, plus `BLOCKED_CUTOVER`
 * as `DRAINING` flagged blocked with `blocked_reason`),
 * `migration_state` JSON, `cutover_import` phase marker, `drain_intent`,
 * sweep fences, `boot_id` / `boot_started_at_ms`, `sender_fenced`,
 * `drain_state`, route consents.
 */
@Entity(tableName = "collector_state")
data class CollectorStateEntity(
    /** Primary key (see [CollectorStateKeys]). */
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,

    /** JSON document value. */
    @ColumnInfo(name = "value_json")
    val valueJson: String,

    /** Last update time (epoch ms). */
    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long,
)
