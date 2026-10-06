package top.nkbe.npatch.update

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.R
import java.util.UUID

class UpdateNotificationsTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private lateinit var preferenceName: String

    @Before fun setUp() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        preferenceName = "update-notice-test-${UUID.randomUUID()}"
        context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(if (name == "app_updates") preferenceName else name, mode)
        }
        manager = base.getSystemService(NotificationManager::class.java)
        UpdateNotifications.ensureChannel(context)
        assertTrue("Device notification permission must be enabled", UpdateNotifications.allowed(context))
        manager.cancel(NOTIFICATION_ID)
        assertNotice(false)
    }

    @After fun tearDown() {
        manager.cancel(NOTIFICATION_ID)
        assertNotice(false)
        context.deleteSharedPreferences(preferenceName)
    }

    @Test fun successfulCheckDismissesCurrentAndOlderReleaseNotices() {
        for (version in listOf(BuildConfig.VERSION_NAME, "0.0.1")) {
            postNotice()
            UpdateNotifications.notify(context, release(version))
            assertNotice(false)
        }
    }

    @Test fun startupDismissesTheVersionAlreadyInstalled() {
        context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit()
            .putString("notified_version", BuildConfig.VERSION_NAME).commit()
        postNotice()
        UpdateNotifications.dismissIfInstalled(context)
        assertNotice(false)
    }

    @Test fun startupRetainsNewerAndUnrecognizedVersionNotices() {
        for (version in listOf("999.0.0", "unknown")) {
            context.getSharedPreferences("app_updates", Context.MODE_PRIVATE).edit()
                .putString("notified_version", version).commit()
            postNotice()
            UpdateNotifications.dismissIfInstalled(context)
            assertNotice(true)
            manager.cancel(NOTIFICATION_ID)
            assertNotice(false)
        }
    }

    private fun postNotice() {
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, "manager_updates")
            .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle("Update regression test").build())
        assertNotice(true)
    }

    private fun assertNotice(expected: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (manager.activeNotifications.any { it.id == NOTIFICATION_ID } != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(20)
        }
        assertEquals(expected, manager.activeNotifications.any { it.id == NOTIFICATION_ID })
    }

    private fun release(version: String) = UpdateRelease(version, "", UpdateAsset("test.apk", "https://github.com/", 1, null))

    private companion object {
        const val NOTIFICATION_ID = 4102
    }
}
