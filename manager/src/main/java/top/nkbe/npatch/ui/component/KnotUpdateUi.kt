package top.nkbe.npatch.ui.component

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import top.nkbe.npatch.R
import top.nkbe.npatch.repo.KnotRelease
import top.nkbe.npatch.update.KnotUpdateChecker
import top.nkbe.npatch.update.KnotUpdateNotifications
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun KnotUpdateDetails(
    release: KnotRelease,
    installedVersion: String,
    downloading: Boolean,
    canDownload: Boolean,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val version = release.version ?: return
    UpdateDetailsDialog(
        title = stringResource(R.string.knot_update_available, version),
        installed = stringResource(R.string.knot_installed_version, installedVersion),
        onDismiss = onDismiss,
        details = {
            val notes = release.body.orEmpty()
            if (notes.isBlank()) Text(stringResource(R.string.app_update_no_notes))
            else ReleaseNotesMarkdown(notes)
            if (downloading) Text(stringResource(R.string.patch_stage_downloading))
        },
    ) {
        if (!downloading && (canDownload || release.htmlUrl != null)) {
            UpdateDetailsAction(
                text = stringResource(if (canDownload) R.string.app_update_download else R.string.knot_release_changelog),
                onClick = onDownload,
            )
        }
    }
}

/** NPatch 本体の [AppUpdatePreferences] と同じ形で、Knot の定期確認と通知を設定する。 */
@Composable
fun KnotUpdatePreferences() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var background by remember { mutableStateOf(KnotUpdateChecker.backgroundChecks) }
    var notificationsAllowed by remember { mutableStateOf(KnotUpdateNotifications.allowed(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = KnotUpdateNotifications.allowed(context)
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsAllowed = KnotUpdateNotifications.allowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SwitchPreference(
        title = stringResource(R.string.knot_update_background),
        summary = stringResource(R.string.knot_update_background_summary),
        checked = background,
        onCheckedChange = {
            background = it
            KnotUpdateChecker.backgroundChecks = it
            if (it && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
    if (background && !notificationsAllowed) {
        ArrowPreference(
            title = stringResource(R.string.knot_update_enable_notifications),
            summary = stringResource(R.string.knot_update_notifications_blocked),
            onClick = { context.startActivity(KnotUpdateNotifications.settingsIntent(context)) },
        )
    }
}
