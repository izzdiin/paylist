package com.netter.paylist.delivery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import com.netter.paylist.HistoryActivity

/**
 * Local high-priority alerts for cases that need the operator
 * (C1 ambiguous cases, user decision 2026-11-23; file plan §D).
 *
 * The notification is IMPORTANCE_HIGH with sound + a long vibration
 * pattern, and deep-links to the history screen pre-filtered to
 * ambiguous cases. When notifications are denied (or the
 * POST_NOTIFICATIONS permission is not granted), the caller falls
 * back to the in-app badge — the badge is always available, the
 * sound/vibration is not.
 *
 * WA/email alerts to the admin are PENDING by owner decision
 * (2026-11-23) — this class sends nothing off-device.
 */
object ReviewNotifier {

    /** Notification channel for review/ambiguous alerts. */
    const val CHANNEL_ID: String = "paylist_review"

    /** Base of the per-case notification-id range (one id per case). */
    const val NOTIFICATION_ID_BASE: Int = 10_000

    private val VIBRATION_PATTERN: LongArray = longArrayOf(0, 500, 200, 500, 200, 1000)

    /**
     * Stable notification id for one ambiguous case, derived from
     * its durable key (delivery id on the Room path, queue key on
     * the legacy path). Re-notifying the same case updates the
     * existing notification instead of stacking a duplicate.
     */
    fun notificationIdFor(key: String): Int {
        val hash = key.hashCode() and 0x7FFFFFFF
        return NOTIFICATION_ID_BASE + (hash % 10_000)
    }

    /** Whether a notification would actually be shown right now. */
    fun canNotify(context: Context): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        return true
    }

    /**
     * Fires the C1 ambiguous-case alert. Safe to call from any
     * thread; no-ops (returns false) when notifications cannot be
     * shown — the caller keeps the in-app badge as the fallback.
     * Never throws.
     */
    fun notifyAmbiguous(
        context: Context,
        key: String,
        amountMinor: Long?,
        sourcePackage: String?,
    ): Boolean {
        return try {
            if (!canNotify(context)) return false
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(manager)
            val amountText = amountMinor?.let { "Rp${formatMinor(it)}" } ?: "nominal tidak diketahui"
            val appLabel = when (sourcePackage) {
                "com.gojek.gopaymerchant" -> "GoPay Merchant"
                "com.gojek.gopay" -> "GoPay"
                null -> "pembayaran"
                else -> sourcePackage
            }
            val deepLink = Intent(context, HistoryActivity::class.java).apply {
                putExtra(HistoryActivity.EXTRA_MATCH_STATUS, "ambiguous")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context,
                notificationIdFor(key),
                deepLink,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Pembayaran ambigu — perlu ditindaklanjuti")
                .setContentText("$amountText dari $appLabel: server tidak bisa menentukan tagihan yang cocok.")
                .setStyle(
                    Notification.BigTextStyle().bigText(
                        "$amountText dari $appLabel: server mengembalikan status ambiguous — " +
                            "ada lebih dari satu tagihan yang cocok. Buka riwayat untuk menindaklanjuti.",
                    ),
                )
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_LIGHTS)
                .build()
            manager.notify(notificationIdFor(key), notification)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Review & Kasus Ambigu",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Peringatan kasus pembayaran ambigu yang perlu ditindaklanjuti operator."
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            setSound(
                sound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)
    }

    private fun formatMinor(amountMinor: Long): String {
        val s = amountMinor.toString()
        val out = StringBuilder()
        var count = 0
        for (i in s.length - 1 downTo 0) {
            out.append(s[i])
            count++
            if (count == 3 && i > 0) {
                out.append('.')
                count = 0
            }
        }
        return out.reverse().toString()
    }
}
