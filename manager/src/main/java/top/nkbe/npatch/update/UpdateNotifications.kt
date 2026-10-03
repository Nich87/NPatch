package top.nkbe.npatch.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.activity.MainActivity

object UpdateNotifications {
    const val OPEN_UPDATE = "top.nkbe.npatch.OPEN_UPDATE"
    private const val CHANNEL_ID = "manager_updates"
    private const val NOTIFICATION_ID = 4102

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.app_update_notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun allowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun settingsIntent(context: Context) = Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CHANNEL_ID)

    fun dismiss(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    @Suppress("MissingPermission") // allowed() checks the runtime permission and channel before posting.
    fun notify(context: Context, release: UpdateRelease) {
        ensureChannel(context)
        if (!allowed(context)) return
        val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        if (!UpdateNoticePolicy.shouldNotify(release.version, BuildConfig.VERSION_NAME,
                preferences.getString("notified_version", null))) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(OPEN_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(context.getString(R.string.app_update_available, release.version))
                    .setContentText(context.getString(R.string.app_update_notification_summary))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build())
            preferences.edit().putString("notified_version", release.version).apply()
        } catch (_: SecurityException) {
            // Permission can be revoked between checking and posting. Do not mark it notified.
        }
    }
}
