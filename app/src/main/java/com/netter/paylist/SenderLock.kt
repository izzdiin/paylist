package com.netter.paylist

import java.util.concurrent.locks.ReentrantLock

/**
 * The single fair lock serialising legacy-queue access (design §8,
 * C13). The legacy [Sender] holds it across a whole flush (including
 * its POSTs); the cutover coordinator takes the SAME lock (timed,
 * 45 s) before committing `DRAINING`, so no flush is running at the
 * commit. Quiescence for the legacy path comes from this lock plus
 * the mode flag — the legacy flush writes no attempt rows.
 */
object SenderLock {
    /** Fair: waiters acquire in arrival order (no starvation). */
    val lock: ReentrantLock = ReentrantLock(true)
}

/**
 * The legacy sender's mode gate (design §8): every POST start
 * requires the sender lock plus a `LEGACY_ONLY` check performed
 * under the lock. Past the `DRAINING` commit, no POST can start.
 */
fun interface ModeGate {
    /** True when the legacy sender may start a POST. */
    fun isLegacyOnly(): Boolean

    companion object {
        /**
         * Unfenced: always allows. Used only when the Application is
         * not [PaylistApplication] (never in production — the
         * manifest declares it); a plain fallback for exotic
         * harnesses, never a production path.
         */
        val ALWAYS_LEGACY: ModeGate = ModeGate { true }
    }
}
