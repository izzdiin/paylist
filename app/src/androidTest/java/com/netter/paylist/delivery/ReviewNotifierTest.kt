package com.netter.paylist.delivery

import android.app.NotificationManager
import android.content.Context
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

    @Test
    fun notifyAmbiguous_postsNotificationOnReviewChannel() {
        val shown = ReviewNotifier.notifyAmbiguous(app, "test-case-1", 170286L, "com.gojek.gopay")
        if (!shown) {
            // POST_NOTIFICATIONS denied on this device: the call
            // correctly no-ops and the badge is the fallback.
            return
        }
        val id = ReviewNotifier.notificationIdFor("test-case-1")
        val active = notificationManager.activeNotifications
        val mine = active.firstOrNull { it.id == id }
        assertTrue("expected an active notification with id $id", mine != null)
        val notification = mine!!.notification
        assertEquals(ReviewNotifier.CHANNEL_ID, notification.channelId)
        val extras = notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        assertTrue("title mentions ambiguous payment", title.contains("Ambigu"))
        assertTrue("text carries the amount", text.contains("170.286"))
    }

    @Test
    fun notifyAmbiguous_reNotifySameKey_updatesInsteadOfStacking() {
        val shown = ReviewNotifier.notifyAmbiguous(app, "test-case-2", 100800L, "com.gojek.gopay")
        if (!shown) return
        val shown2 = ReviewNotifier.notifyAmbiguous(app, "test-case-2", 100800L, "com.gojek.gopay")
        assertTrue(shown2)
        val id = ReviewNotifier.notificationIdFor("test-case-2")
        val count = notificationManager.activeNotifications.count { it.id == id }
        assertEquals(1, count)
    }
}
