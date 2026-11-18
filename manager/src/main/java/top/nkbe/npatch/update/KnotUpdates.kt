package top.nkbe.npatch.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.nkbe.npatch.R
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.repo.KnotRelease
import top.nkbe.npatch.ui.activity.MainActivity

/** Knot のパッケージ名 */
const val KNOT_PACKAGE_NAME = "app.zipper.knot"

private const val PREFERENCES = "app_updates"
private const val KEY_BACKGROUND = "knot_background"
private const val KEY_NOTIFIED_VERSION = "knot_notified_version"

/** Knot のリリース一覧から、インストール済みより新しい版を選ぶ。 */
object KnotUpdatePolicy {
    /** 表示・比較できる最新リリース。プレリリースは [includePrereleases] の時だけ候補にする。 */
    fun latestRelease(releases: List<KnotRelease>, includePrereleases: Boolean): KnotRelease? =
        releases.asSequence()
            .filter { includePrereleases || !it.isPrerelease }
            .mapNotNull { release -> release.version?.let { version -> release to version } }
            .filter { (_, version) -> UpdatePolicy.compare(version, version) != null }
            .maxWithOrNull { (_, a), (_, b) -> requireNotNull(UpdatePolicy.compare(a, b)) }
            ?.first

    /** Knot が未インストール、または比較できないバージョンなら null。 */
    fun findUpdate(releases: List<KnotRelease>, installedVersion: String?, includePrereleases: Boolean): KnotRelease? {
        val installed = installedVersion?.let(::normalizeVersion) ?: return null
        val latest = latestRelease(releases, includePrereleases) ?: return null
        val version = latest.version ?: return null
        return latest.takeIf { UpdatePolicy.compare(version, installed)?.let { it > 0 } == true }
    }

    /** "v3.1.2" や "3.1.2 (42)" のような表記から比較用のバージョンを取り出す。 */
    fun normalizeVersion(versionName: String): String? =
        versionName.trim().substringBefore(' ').removePrefix("v").removePrefix("V")
            .takeIf { it.isNotEmpty() && UpdatePolicy.compare(it, it) != null }
}

/** Knot の更新確認の設定と、通知から Home を開く要求を保持する。 */
object KnotUpdateChecker {
    private val preferences get() = lspApp.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    var backgroundChecks: Boolean
        get() = preferences.getBoolean(KEY_BACKGROUND, true)
        set(value) {
            preferences.edit().putBoolean(KEY_BACKGROUND, value).apply()
            KnotUpdateCheckWorker.schedule(lspApp)
        }

    /** プレリリース設定は Home の Knot リリース一覧と共通。 */
    val includePrereleases: Boolean
        get() = Configs.includePrereleaseVersions

    private val mutableOpenRequests = MutableStateFlow(0L)

    /**
     * 通知タップごとに更新される要求トークン (0 は要求なし)。
     * 起動からの経過時間を使うため、プロセスが再生成されても保存済みの処理済みトークンより必ず大きい。
     */
    val openRequests = mutableOpenRequests.asStateFlow()

    fun requestOpen() {
        mutableOpenRequests.value = maxOf(SystemClock.elapsedRealtimeNanos(), mutableOpenRequests.value + 1)
        KnotUpdateNotifications.dismiss(lspApp)
    }

    fun installedVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(KNOT_PACKAGE_NAME, 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    /** チャネルが変わると以前の通知履歴は比較に使えないため、通知済みの版を忘れる。 */
    fun onPrereleaseChannelChanged() {
        preferences.edit().remove(KEY_NOTIFIED_VERSION).apply()
        KnotUpdateNotifications.dismiss(lspApp)
    }
}

object KnotUpdateNotifications {
    const val OPEN_KNOT_UPDATE = "top.nkbe.npatch.OPEN_KNOT_UPDATE"
    private const val CHANNEL_ID = "knot_updates"
    private const val NOTIFICATION_ID = 4103

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.knot_update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT)
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

    /** Knot が通知した版以上に更新、またはアンインストールされたら通知を消す。 */
    fun dismissIfResolved(context: Context) {
        val notified = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_NOTIFIED_VERSION, null) ?: return
        val installed = KnotUpdateChecker.installedVersion(context)?.let(KnotUpdatePolicy::normalizeVersion)
        if (installed == null || UpdatePolicy.compare(notified, installed)?.let { it <= 0 } != false) dismiss(context)
    }

    @Suppress("MissingPermission") // allowed() checks the runtime permission and channel before posting.
    fun notify(context: Context, release: KnotRelease, installedVersion: String) {
        val version = release.version ?: return
        ensureChannel(context)
        if (!allowed(context)) return
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (!UpdateNoticePolicy.shouldNotify(version, installedVersion,
                preferences.getString(KEY_NOTIFIED_VERSION, null))) return
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(OPEN_KNOT_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_monochrome)
                    .setContentTitle(context.getString(R.string.knot_update_available, version))
                    .setContentText(context.getString(R.string.knot_update_notification_summary, installedVersion))
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build())
            preferences.edit().putString(KEY_NOTIFIED_VERSION, version).apply()
        } catch (_: SecurityException) {
            // Permission can be revoked between checking and posting. Do not mark it notified.
        }
    }
}
