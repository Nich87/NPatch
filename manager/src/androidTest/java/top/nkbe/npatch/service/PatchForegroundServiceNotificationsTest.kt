package top.nkbe.npatch.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import top.nkbe.npatch.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PatchForegroundServiceNotificationsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val events = LinkedBlockingQueue<Pair<Int, Boolean>>()
    private val connected = CountDownLatch(1)
    private var receiverRegistered = false
    private var listenerAccessGranted = false
    private val listener = "${instrumentation.context.packageName}/${NotificationProbeService::class.java.name}"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra("connected", false)) connected.countDown()
            else events.offer(intent.getIntExtra("id", -1) to intent.getBooleanExtra("posted", false))
        }
    }

    @Before fun setUp() {
        assertTrue("Device notification permission must be enabled",
            NotificationManagerCompat.from(context).areNotificationsEnabled())
        ContextCompat.registerReceiver(context, receiver, IntentFilter(NotificationProbeService.ACTION),
            ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
        listenerAccessGranted = true
        shell("cmd notification allow_listener $listener")
        assertTrue("Notification observer did not connect", connected.await(5, TimeUnit.SECONDS))
        PatchForegroundService.setManagerVisible(context, true)
        PatchForegroundService.stop(context)
        awaitCompletion(false)
        PatchForegroundService.setManagerVisible(context, false)
    }

    @After fun tearDown() {
        try {
            PatchForegroundService.stop(context)
            PatchForegroundService.setManagerVisible(context, true)
            if (connected.count == 0L) awaitCompletion(false)
        } finally {
            PatchForegroundService.setManagerVisible(context, false)
            try {
                if (listenerAccessGranted) shell("cmd notification disallow_listener $listener")
            } finally {
                if (receiverRegistered) context.unregisterReceiver(receiver)
            }
        }
    }

    @Test fun backgroundSuccessAndFailureIdentifyTheAppAndCanBeDismissed() {
        for (succeeded in listOf(true, false)) {
            PatchForegroundService.complete(context, succeeded, "Notification test app")
            awaitCompletion(true)
            val notification = completion()
            val title = if (succeeded) R.string.patch_result_notification_success else R.string.patch_result_notification_failure
            assertEquals(context.getString(title), notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Notification test app"))
            assertTrue(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
            assertEquals(0, notification.flags and Notification.FLAG_ONGOING_EVENT)
            assertNotNull(notification.contentIntent)
            assertTrue(manager.activeNotifications.none { it.id == PROGRESS_ID })
            manager.cancel(COMPLETION_ID)
            awaitCompletion(false)
        }
    }

    @Test fun exportFailureKeepsPatchSuccessAndExplainsTheSaveFailure() {
        PatchForegroundService.complete(context, true, "Notification test app", exportFailed = true)
        awaitCompletion(true)
        assertEquals(context.getString(R.string.patch_result_notification_success),
            completion().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(context.getString(R.string.patch_result_notification_export_failed_text, "Notification test app"),
            completion().extras.getCharSequence(Notification.EXTRA_TEXT).toString())
    }

    @Test fun foregroundCompletionAndCancellationStaySilent() {
        PatchForegroundService.setManagerVisible(context, true)
        PatchForegroundService.complete(context, true, "Notification test app")
        awaitCompletion(false)
        PatchForegroundService.setManagerVisible(context, false)
        PatchForegroundService.stop(context)
        awaitCompletion(false)
    }

    @Test fun newPatchAndReturningToManagerClearPreviousCompletion() {
        PatchForegroundService.complete(context, true, "Previous patch")
        awaitCompletion(true)
        PatchForegroundService.start(context, "Next patch")
        awaitCompletion(false)
        PatchForegroundService.stop(context)
        PatchForegroundService.complete(context, false, "Next patch")
        awaitCompletion(true)
        PatchForegroundService.setManagerVisible(context, true)
        awaitCompletion(false)
    }

    @Test fun deniedRuntimePermissionDoesNotPostOrThrow() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val deniedContext = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.POST_NOTIFICATIONS) PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        }
        PatchForegroundService.complete(deniedContext, true, "Notification test app")
        awaitCompletion(false)
    }

    private fun completion(): Notification = manager.activeNotifications.single { it.id == COMPLETION_ID }.notification

    private fun awaitCompletion(expected: Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (manager.activeNotifications.any { it.id == COMPLETION_ID } != expected) {
            val remaining = deadline - System.nanoTime()
            assertTrue("Notification state did not change", remaining > 0)
            val event = events.poll(remaining, TimeUnit.NANOSECONDS)
            if (event == null) break
        }
        assertEquals(expected, manager.activeNotifications.any { it.id == COMPLETION_ID })
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
    }

    private companion object {
        const val PROGRESS_ID = 1001
        const val COMPLETION_ID = 1002
    }
}
