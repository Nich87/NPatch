package top.nkbe.npatch.service;

import android.content.Intent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import top.nkbe.npatch.BuildConfig;

// This service runs in the test APK's own process, without the target's Kotlin runtime.
public class NotificationProbeService extends NotificationListenerService {
    public static final String ACTION = BuildConfig.APPLICATION_ID + ".test.NOTIFICATION_EVENT";

    @Override
    public void onListenerConnected() {
        sendBroadcast(event().putExtra("connected", true));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        report(notification, true);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
        report(notification, false);
    }

    private void report(StatusBarNotification notification, boolean posted) {
        if (!BuildConfig.APPLICATION_ID.equals(notification.getPackageName())) return;
        sendBroadcast(event().putExtra("id", notification.getId()).putExtra("posted", posted));
    }

    private Intent event() {
        return new Intent(ACTION).setPackage(BuildConfig.APPLICATION_ID)
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
    }
}
