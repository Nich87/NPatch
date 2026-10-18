package top.nkbe.npatch.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.activity.MainActivity

class PatchForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel(this)
        val notification = buildNotification(this, progressText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        running = true
        if (stopRequested) {
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "patch_progress"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETION_CHANNEL_ID = "patch_completion"
        private const val COMPLETION_NOTIFICATION_ID = 1002

        @Volatile
        private var managerVisible = false

        @Volatile
        private var running = false

        @Volatile
        private var stopRequested = false

        @Volatile
        private var progressText = ""

        fun start(context: Context, progress: String) {
            progressText = progress
            stopRequested = false
            runCatching {
                NotificationManagerCompat.from(context).cancel(COMPLETION_NOTIFICATION_ID)
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PatchForegroundService::class.java),
                )
            }
        }

        fun updateProgress(context: Context, progress: String) {
            progressText = progress
            if (!running || !canPostNotifications(context)) return
            createNotificationChannel(context)
            runCatching {
                NotificationManagerCompat.from(context)
                    .notify(NOTIFICATION_ID, buildNotification(context, progress))
            }
        }

        fun stop(context: Context) {
            stopRequested = true
            progressText = ""
            runCatching {
                if (running) {
                    running = false
                    context.stopService(Intent(context, PatchForegroundService::class.java))
                }
                NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            }
        }

        fun setManagerVisible(context: Context, visible: Boolean) {
            managerVisible = visible
            if (visible) NotificationManagerCompat.from(context).cancel(COMPLETION_NOTIFICATION_ID)
        }

        fun complete(
            context: Context,
            succeeded: Boolean,
            appLabel: String,
            exportFailed: Boolean = false,
        ) {
            stop(context)
            if (managerVisible || !canPostNotifications(context)) return
            val manager = context.getSystemService<NotificationManager>() ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    COMPLETION_CHANNEL_ID,
                    context.getString(R.string.patch_result_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            val title = if (succeeded) {
                R.string.patch_result_notification_success
            } else {
                R.string.patch_result_notification_failure
            }
            val text = when {
                !succeeded -> R.string.patch_result_notification_failure_text
                exportFailed -> R.string.patch_result_notification_export_failed_text
                else -> R.string.patch_result_notification_success_text
            }
            val intent = Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.getString(title))
                .setContentText(context.getString(text, appLabel))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(text, appLabel)))
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        COMPLETION_NOTIFICATION_ID,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(COMPLETION_NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Notification permission can be revoked after the check.
            }
        }

        private fun canPostNotifications(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun createNotificationChannel(context: Context) {
            val manager = context.getSystemService<NotificationManager>() ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.patch_notification_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }

        private fun buildNotification(context: Context, progress: String): Notification {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.getString(R.string.patch_notification_title))
                .setContentText(
                    progress.ifEmpty { context.getString(R.string.patch_notification_text) },
                )
                .setContentIntent(contentIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build()
        }
    }
}
