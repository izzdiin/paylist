package com.netter.paylist.migration

import org.json.JSONArray
import org.json.JSONObject

/** Result of a durable-flush verification (R07). */
sealed interface FlushResult {
    /** The readback is content-identical to the saved snapshot. */
    data object Verified : FlushResult

    /** The readback differs (or the write/read itself failed). Fail closed. */
    data class Failed(val reason: String) : FlushResult
}

/**
 * R07: content/identity readback for the cutover durable flush
 * (design §8.2 step 2, review 6011401446).
 *
 * The old `runCutover` check compared only the queue SIZE after
 * `saveQueueSync`, so a same-count mutation — a changed `qkey`, a
 * changed `next` hop, a changed payload, or one item swapped for
 * another — passed the readback and reached `commitDraining`.
 * [verifyReadback] compares the readback against the saved snapshot
 * item by item instead, and [CutoverFlushGate] aborts the cutover
 * before `commitDraining` when it fails.
 *
 * Scope (every parsed field, not just the §8.2 checksum's): `qkey`,
 * `kind`, `route_ids`, `next`, `attempts`, `last_error`, `last_try`,
 * the whole `payload` JSON (canonical, keys sorted — hence
 * `dedupe_key`, `amount`, `type` and every other payload field),
 * `dedupe_key`, `type`, `hop_outcomes` (canonicalised), and the P9
 * privacy markers. Corrupt items (unparseable) are compared by
 * their raw canonical JSON. The comparison is keyed by `qkey` and
 * ORDER-INSENSITIVE: queue order is not part of delivery identity —
 * the drain imports by `qkey` into Room, and the design §8.2
 * canonical checksum itself sorts by `qkey`.
 *
 * Why not reuse `LegacyQueueMigration.checksum` alone: the checksum
 * omits `kind`, `last_try`, `hop_outcomes` and the privacy markers,
 * and it is blind to corrupt items (they never parse, so they never
 * enter the checksum). All of those feed the import — `hop_outcomes`
 * decides the imported delivery state, `last_try` is the event-time
 * fallback, the privacy markers feed the P9 gate — so a same-count
 * mutation of any of them would change the migration outcome while
 * the checksum still matched. The per-item serialisation below
 * reuses [CanonicalJson] (the checksum's canonicalisation), so the
 * two stay consistent; `FlushReadbackTest` proves the distinction
 * with a `hop_outcomes`-only mutation that leaves the checksum
 * unchanged while the readback fails.
 *
 * Fail-closed: any exception during verification (including a
 * canonicalisation failure) returns [FlushResult.Failed], never
 * [FlushResult.Verified].
 */
object QueueFlushVerifier {

    /**
     * Verifies that [actual] (read back after a save) is
     * content-identical to [expected] (the snapshot that was saved).
     * Order-insensitive; see the object KDoc for the scope. The
     * failure reason names the mismatch class (count, corrupt item,
     * `qkey` set, or the first differing field) without embedding
     * queue values, so it is safe to surface to the user.
     */
    fun verifyReadback(expected: List<JSONObject>, actual: List<JSONObject>): FlushResult {
        try {
            if (actual.size != expected.size) {
                return FlushResult.Failed(
                    "jumlah item antrean berubah " +
                        "(diharapkan ${expected.size}, terbaca ${actual.size})"
                )
            }
            val expectedParsed = expected.map { LegacyQueueItem.fromJson(it) }
            val actualParsed = actual.map { LegacyQueueItem.fromJson(it) }
            val expectedCorrupt = corruptSerialisations(expected, expectedParsed)
            val actualCorrupt = corruptSerialisations(actual, actualParsed)
            if (expectedCorrupt != actualCorrupt) {
                return FlushResult.Failed("item rusak di antrean berubah")
            }
            val expectedItems = expectedParsed.filterNotNull()
            val actualItems = actualParsed.filterNotNull()
            val expectedByQkey = serialisationsByQkey(expectedItems)
            val actualByQkey = serialisationsByQkey(actualItems)
            if (expectedByQkey == actualByQkey) return FlushResult.Verified
            val expectedCounts = expectedItems.groupingBy { it.qkey }.eachCount()
            val actualCounts = actualItems.groupingBy { it.qkey }.eachCount()
            val removed = expectedCounts.entries.sumOf { (qkey, count) ->
                maxOf(0, count - (actualCounts[qkey] ?: 0))
            }
            val added = actualCounts.entries.sumOf { (qkey, count) ->
                maxOf(0, count - (expectedCounts[qkey] ?: 0))
            }
            if (removed > 0 || added > 0) {
                return FlushResult.Failed("qkey antrean berubah ($removed hilang, $added baru)")
            }
            // Same qkey multiset, different content: name the first
            // differing field (qkeys sorted, so the reason is stable).
            for (qkey in expectedByQkey.keys.sorted()) {
                val expectedSerialised = expectedByQkey.getValue(qkey)
                val actualSerialised = actualByQkey.getValue(qkey)
                if (expectedSerialised == actualSerialised) continue
                val expectedGroup = expectedItems.filter { it.qkey == qkey }
                    .sortedBy { serialiseItem(it) }
                val actualGroup = actualItems.filter { it.qkey == qkey }
                    .sortedBy { serialiseItem(it) }
                val differing = expectedGroup.zip(actualGroup)
                    .firstOrNull { (e, a) -> serialiseItem(e) != serialiseItem(a) }
                val field = differing?.let { firstFieldDifference(it.first, it.second) }
                    ?: "isi"
                return FlushResult.Failed("isi item berubah: $field")
            }
            // Unreachable in principle (the maps differ, the qkey
            // multisets agree, yet no qkey's content differs) — fail
            // closed rather than claim success.
            return FlushResult.Failed("isi antrean berubah (tidak teridentifikasi)")
        } catch (e: Exception) {
            return FlushResult.Failed(
                "verifikasi antrean gagal tertutup: ${e.message?.take(100)}"
            )
        }
    }

    /**
     * Durable flush over [queue]: save [snapshot] synchronously,
     * read it back, and verify content/identity. A `false` save, a
     * failed readback, or a content mismatch all return
     * [FlushResult.Failed].
     */
    fun flushAndVerify(
        queue: LegacyQueueSnapshotSource,
        snapshot: List<JSONObject>,
    ): FlushResult {
        val saved = try {
            queue.saveQueueSync(snapshot)
        } catch (e: Exception) {
            return FlushResult.Failed("penulisan antrean gagal: ${e.message?.take(100)}")
        }
        if (!saved) return FlushResult.Failed("penulisan antrean gagal")
        val readBack = try {
            queue.queueSnapshot()
        } catch (e: Exception) {
            return FlushResult.Failed("pembacaan ulang antrean gagal: ${e.message?.take(100)}")
        }
        return verifyReadback(snapshot, readBack)
    }

    /**
     * Canonical serialisation of one parsed item: every field, in a
     * fixed order, length-prefixed per field (`<len>:<value>;`) so
     * no field value can blur a boundary — `qkey` itself contains
     * `|` separators and `last_error` is free text, so a bare `|`
     * join (as in the §8.2 checksum) would be ambiguous here.
     */
    fun serialiseItem(item: LegacyQueueItem): String {
        val fields = listOf(
            item.qkey,
            item.kind,
            CanonicalJson.stringify(JSONArray(item.routeIds)),
            item.next.toString(),
            item.attempts.toString(),
            item.lastError.orEmpty(),
            item.lastTryMs?.toString().orEmpty(),
            CanonicalJson.stringify(item.payloadJson),
            item.dedupeKey,
            item.type,
            canonicalHopOutcomes(item.hopOutcomesJson),
            item.privacyReprocessed.toString(),
            item.privacyQuarantined.toString(),
        )
        return fields.joinToString("") { "${it.length}:$it;" }
    }

    /** Canonical form of the raw `hop_outcomes` string (usually a JSON array). */
    fun canonicalHopOutcomes(raw: String?): String {
        if (raw == null) return "null"
        return try {
            CanonicalJson.stringify(JSONArray(raw))
        } catch (e: Exception) {
            try {
                CanonicalJson.stringify(JSONObject(raw))
            } catch (e2: Exception) {
                CanonicalJson.stringify(raw)
            }
        }
    }

    /**
     * Names the first field that differs between two items with the
     * same `qkey` (field order is the [serialiseItem] order, minus
     * `qkey` itself), or null when the items are identical.
     */
    fun firstFieldDifference(a: LegacyQueueItem, b: LegacyQueueItem): String? {
        if (a.kind != b.kind) return "kind"
        if (a.routeIds != b.routeIds) return "route_ids"
        if (a.next != b.next) return "hop next"
        if (a.attempts != b.attempts) return "attempts"
        if (a.lastError != b.lastError) return "last_error"
        if (a.lastTryMs != b.lastTryMs) return "last_try"
        if (CanonicalJson.stringify(a.payloadJson) != CanonicalJson.stringify(b.payloadJson)) {
            return "payload"
        }
        if (a.dedupeKey != b.dedupeKey) return "dedupe_key"
        if (a.type != b.type) return "type"
        if (canonicalHopOutcomes(a.hopOutcomesJson) != canonicalHopOutcomes(b.hopOutcomesJson)) {
            return "hop_outcomes"
        }
        if (a.privacyReprocessed != b.privacyReprocessed ||
            a.privacyQuarantined != b.privacyQuarantined
        ) {
            return "penanda privasi"
        }
        return null
    }

    private fun corruptSerialisations(
        raw: List<JSONObject>,
        parsed: List<LegacyQueueItem?>,
    ): List<String> = raw.mapIndexedNotNull { index, item ->
        if (parsed[index] == null) CanonicalJson.stringify(item) else null
    }.sorted()

    private fun serialisationsByQkey(items: List<LegacyQueueItem>): Map<String, List<String>> =
        items.groupBy({ it.qkey }, { serialiseItem(it) })
            .mapValues { (_, serialised) -> serialised.sorted() }
}

/**
 * The R07 fail-closed gate between the durable flush and
 * `commitDraining` (design §8.2, review 6011401446). Called by
 * `PaylistApplication.runCutover` after the privacy preflight and
 * before `commitDraining`: on a failed flush/readback it aborts the
 * cutover back to `LEGACY_ONLY` / `ABORTED` itself, so the caller
 * must only report the failure and return — `commitDraining` is
 * never reached. On success the coordinator is untouched (still
 * `LEGACY_ONLY` / `PENDING`), ready for `commitDraining`.
 */
object CutoverFlushGate {

    suspend fun flushOrAbort(
        coordinator: CutoverCoordinator,
        queue: LegacyQueueSnapshotSource,
        snapshot: List<JSONObject>,
        nowMs: Long,
    ): FlushResult {
        val flushed = QueueFlushVerifier.flushAndVerify(queue, snapshot)
        if (flushed is FlushResult.Verified) return flushed
        val aborted = coordinator.abortCutover(nowMs)
        if (aborted is CutoverCommit.Rejected) {
            return FlushResult.Failed(
                "${(flushed as FlushResult.Failed).reason}; " +
                    "pembatalan ditolak: ${aborted.reason}"
            )
        }
        return flushed
    }
}
