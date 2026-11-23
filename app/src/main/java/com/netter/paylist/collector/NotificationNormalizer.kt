package com.netter.paylist.collector

import android.app.Notification
import android.service.notification.StatusBarNotification

/**
 * An in-memory notification snapshot (design §8.3 C21(1′)): the
 * fields the capture callback reads synchronously from a posted
 * notification, handed to the capture executor. Plain data — no
 * Android types — so the capture path is unit-testable on the JVM
 * and the executor never touches a `StatusBarNotification`.
 *
 * The snapshot holds transient raw text (title/text/bigText) in
 * memory only. Nothing in it is persisted raw: the executor runs
 * PrivacyGate first and stages post-gate content only (§8.3 (2′)).
 */
data class NotificationSnapshot(
    /** Notification key — the update identity (design §3). */
    val key: String,
    /** Source application package. */
    val packageName: String,
    /** Notification `postTime` (epoch ms); part of the legacy `dedupe_key`. */
    val postTimeMs: Long,
    /** Notification flags (group-summary filtering, live path too). */
    val flags: Int,
    val title: String,
    val text: String,
    val bigText: String,
    /** When this device observed it (epoch ms). */
    val capturedAtMs: Long,
) {
    /** Full raw combined text, as the v2.3 listener builds it. */
    val combined: String get() = "$title $text $bigText"

    /** True when this notification is a group summary (never captured). */
    val isGroupSummary: Boolean
        get() = flags and Notification.FLAG_GROUP_SUMMARY != 0
}

/**
 * Extracts Android notification fields without payment assumptions
 * (file plan §B, Stage D). The only Android-dependent step in
 * capture; everything downstream works on [NotificationSnapshot].
 */
object NotificationNormalizer {

    /** Builds a snapshot from a posted notification (callback thread). */
    fun from(sbn: StatusBarNotification, capturedAtMs: Long): NotificationSnapshot {
        val extras = sbn.notification.extras
        return NotificationSnapshot(
            key = sbn.key ?: "",
            packageName = sbn.packageName ?: "",
            postTimeMs = sbn.postTime,
            flags = sbn.notification.flags,
            title = extras.getCharSequence("android.title")?.toString().orEmpty(),
            text = extras.getCharSequence("android.text")?.toString().orEmpty(),
            bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty(),
            capturedAtMs = capturedAtMs,
        )
    }
}
