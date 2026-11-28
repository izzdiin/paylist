package com.netter.paylist.collector

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The single bounded in-memory store for §7.3A raw snapshots
 * (file plan §B).
 *
 * Raw snapshots live ONLY here: in the application process, owned
 * by the capture executor, keyed by (`dedupe_key`, `route_id`,
 * consent epoch). They are never written to staging, the outbox,
 * any log, or disk.
 *
 * - Capacity 50 snapshots; TTL 2 hours wall-clock from capture;
 *   eviction is LRU, oldest first. Overflow or TTL expiry ⇒ the
 *   §7.3B FallbackGate evaluation applies (the caller receives the
 *   evicted snapshot and evaluates); the snapshot is never spilled
 *   to disk.
 * - Handoff is take-don't-copy: at claim the claiming path TAKES
 *   the snapshot out of the map under the store lock — the
 *   snapshot moves into the attempt's private scope and no second
 *   claim can take it. If the Room claim transaction fails after
 *   the take, the snapshot is returned to the map ([returnSnapshot]).
 * - One dedicated lock guards the map; every operation runs under
 *   it. Lock order is fixed: `Sender.LOCK` first, then the store
 *   lock; no path acquires them in the reverse order (the worker
 *   path never takes `Sender.LOCK`).
 * - Per-route `sendFence` generation ([AtomicLong], bumped by
 *   revocation under the store lock together with the snapshot
 *   drop): the claiming thread records the generation at the
 *   pre-send gate and re-reads it immediately before the single
 *   first-write call site (§7.3A(e), R40/R41). The fence read is
 *   a plain volatile read — no lock held, no I/O, no suspension
 *   point between the read and the write.
 * - In-process only: if a future change moves the worker to a
 *   separate process (`:remote`), B is disabled until the handoff
 *   is redesigned — a cross-process snapshot would need disk,
 *   which B forbids.
 */
class RawSnapshotStore(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Max snapshots held (design §7.3A(c)). */
        const val DEFAULT_CAPACITY: Int = 50

        /** Snapshot TTL, 2 hours wall-clock from capture. */
        const val DEFAULT_TTL_MS: Long = 2 * 60 * 60 * 1000L

        /** Max title/text length per snapshot (baseline truncation). */
        const val MAX_TEXT_CHARS: Int = 500
    }

    /** One raw snapshot: the transient notification text for one route. */
    data class RawSnapshot(
        val dedupeKey: String,
        val routeId: String,
        val consentEpoch: Long,
        val title: String,
        val text: String,
        val capturedAtMs: Long,
    )

    /** Key: (`dedupe_key`, `route_id`, consent epoch). */
    private data class Key(val dedupeKey: String, val routeId: String, val consentEpoch: Long)

    private val lock = ReentrantLock()

    /** Access-ordered map: the eldest entry is the LRU victim. */
    private val map = object : LinkedHashMap<Key, RawSnapshot>(capacity, 0.75f, true) {}

    /**
     * Per-route admission-fence generations. A concurrent map so
     * the final admission read ([fence]) is a plain volatile read
     * with NO lock held (§7.3A(e)): the claiming thread re-reads
     * the generation immediately before the single first-write
     * call site, with no suspension point between the read and
     * the write. Bumps still happen under the store lock (in
     * [dropRoute]/[bumpFence]), together with the snapshot drop.
     */
    private val fences = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Stores a snapshot. Returns the evicted snapshot when the put
     * overflowed capacity (the caller runs the §7.3B evaluation
     * for it), or null when nothing was evicted. A snapshot for an
     * existing key replaces it without eviction.
     */
    fun put(snapshot: RawSnapshot): RawSnapshot? = lock.withLock {
        val key = Key(snapshot.dedupeKey, snapshot.routeId, snapshot.consentEpoch)
        val existing = map.put(key, snapshot)
        if (existing != null) return@withLock null
        if (map.size <= capacity) return@withLock null
        // Evict the LRU entry (eldest in access order).
        val eldest = map.entries.first()
        map.remove(eldest.key)
        eldest.value
    }

    /**
     * Takes the snapshot out of the map (take-don't-copy). Returns
     * null when no snapshot exists for the key, or when the stored
     * snapshot is expired (expired entries are dropped, never
     * returned — the caller treats the loss as raw loss, §7.3B).
     */
    fun take(dedupeKey: String, routeId: String, consentEpoch: Long): RawSnapshot? =
        lock.withLock {
            val key = Key(dedupeKey, routeId, consentEpoch)
            val snapshot = map.remove(key) ?: return@withLock null
            if (clock() - snapshot.capturedAtMs > ttlMs) return@withLock null
            snapshot
        }

    /** Peeks without removing (tests and diagnostics only — claims use [take]). */
    fun peek(dedupeKey: String, routeId: String, consentEpoch: Long): RawSnapshot? =
        lock.withLock { map[Key(dedupeKey, routeId, consentEpoch)] }

    /**
     * Returns a taken snapshot to the map after a failed claim
     * transaction (still within the live process). Returns the
     * evicted snapshot when the return overflowed capacity.
     */
    fun returnSnapshot(snapshot: RawSnapshot): RawSnapshot? = put(snapshot)

    /**
     * Drops every snapshot for [routeId] (revocation / opt-in
     * expiry) and bumps the route's send fence, atomically under
     * the store lock. Returns the dropped snapshots (for the
     * caller's §7.3B / BLOCKED transitions).
     */
    fun dropRoute(routeId: String): List<RawSnapshot> = lock.withLock {
        val dropped = map.entries.filter { it.key.routeId == routeId }.map { it.value }
        for (entry in dropped) {
            map.remove(Key(entry.dedupeKey, entry.routeId, entry.consentEpoch))
        }
        fenceFor(routeId).incrementAndGet()
        dropped
    }

    /** Removes expired snapshots; returns them for §7.3B evaluation. */
    fun evictExpired(nowMs: Long = clock()): List<RawSnapshot> = lock.withLock {
        val expired = map.values.filter { nowMs - it.capturedAtMs > ttlMs }
        for (snapshot in expired) {
            map.remove(Key(snapshot.dedupeKey, snapshot.routeId, snapshot.consentEpoch))
        }
        expired
    }

    /**
     * Current admission-fence generation for [routeId]. A plain
     * volatile read — NO lock held (§7.3A(e)).
     */
    fun fence(routeId: String): Long = fenceFor(routeId).get()

    /** Bumps the route's fence (revocation path; also done by [dropRoute]). */
    fun bumpFence(routeId: String): Long = lock.withLock {
        fenceFor(routeId).incrementAndGet()
    }

    fun size(): Int = lock.withLock { map.size }

    fun contains(dedupeKey: String, routeId: String, consentEpoch: Long): Boolean =
        lock.withLock { map.containsKey(Key(dedupeKey, routeId, consentEpoch)) }

    private fun fenceFor(routeId: String): AtomicLong =
        fences.computeIfAbsent(routeId) { AtomicLong(0) }
}
