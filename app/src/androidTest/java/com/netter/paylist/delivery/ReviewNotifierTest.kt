package com.netter.paylist.delivery

import android.app.NotificationManager
import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.netter.paylist.PaylistApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C1 alert (instrumented): `notifyAmbiguous` posts a real
 * high-priority notification on the review channel with the
 * amount in its text. Runs in CI on the emulator; compile-only
 * on the 2 GB dev box.
 *
 * Note: on API 33+ the notification only appears when the user
 * has granted POST_NOTIFICATIONS. When permission is absent the
 * call returns false and the app falls back to the in-app badge;
 * the test then passes vacuously (the no-op is the correct
 * behaviour), and CI's emulator image grants it by default for
 * test apps.
 */
@RunWith(AndroidJUnit4::class)
class ReviewNotifierTest {

    private lateinit var app: PaylistApplication
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        notificationManager =
            app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @After
    fun tearDown() {
        notificationManager.cancelAll()
    }

    /**
     * Polls activeNotifications until a notification with [id]
     * appears (or the timeout elapses). `notify()` returns before
     * the system has necessarily propagated the post to the active
     * list, so an immediate read can spuriously find nothing on a
     * loaded emulator (flaked once in CI run 45304077042).
     */
    private fun awaitActive(id: Int, timeoutMs: Long = 2_000L): StatusBarNotification? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val found = notificationManager.activeNotifications.firstOrNull { it.id == id }
            if (found != null) return found
            Thread.sleep(50)
        }
        return null
    }

    @Test
    fun notifyAmbiguous_postsNotificationOnReviewChannel() {
        val shown = ReviewNotifier.notifyAmbiguous(app, "test-case-1", 170286L, "com.gojek.gopay")
        if (!shown) {
            // POST_NOTIFICATIONS denied on this device: the call
            // correctly no-ops and the badge is the fallback.
            return
        }
        val id = ReviewNotifier.notificationIdFor("test-case-1")
        val mine = awaitActive(id)
        assertTrue("expected an active notification with id $id", mine != null)
        val notification = mine!!.notification
        assertEquals(ReviewNotifier.CHANNEL_ID, notification.channelId)
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        // The notifier title is Indonesian ("Pembayaran ambigu ..."):
        // match case-insensitively; the exact capitalisation is not
        // the assertion.
        assertTrue("title mentions ambiguous payment", title.contains("ambigu", ignoreCase = true))
        assertTrue("text carries the amount", text.contains("170.286"))
    }

    @Test
    fun notifyAmbiguous_reNotifySameKey_updatesInsteadOfStacking() {
        val shown = ReviewNotifier.notifyAmbiguous(app, "test-case-2", 100800L, "com.gojek.gopay")
        if (!shown) return
        val shown2 = ReviewNotifier.notifyAmbiguous(app, "test-case-2", 100800L, "com.gojek.gopay")
        assertTrue(shown2)
        val id = ReviewNotifier.notificationIdFor("test-case-2")
        // Poll for the single updated notification (see awaitActive):
        // the immediate read raced the system's propagation once.
        val deadline = System.currentTimeMillis() + 2_000L
        var count = 0
        while (System.currentTimeMillis() < deadline) {
            count = notificationManager.activeNotifications.count { it.id == id }
            if (count == 1) break
            Thread.sleep(50)
        }
        assertEquals(1, count)
    }
}
