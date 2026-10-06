package top.nkbe.npatch.ui.component

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.theme.GlassStyle
import top.nkbe.npatch.update.UpdateState
import top.nkbe.npatch.update.AppUpdater
import top.nkbe.npatch.update.UpdateNotifications

@Composable
fun AppUpdatePreferences() {
    val state by AppUpdater.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var automatic by remember { mutableStateOf(AppUpdater.automaticChecks) }
    var background by remember { mutableStateOf(AppUpdater.backgroundChecks) }
    var notificationsAllowed by remember { mutableStateOf(UpdateNotifications.allowed(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationsAllowed = UpdateNotifications.allowed(context)
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notificationsAllowed = UpdateNotifications.allowed(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SwitchPreference(
        title = stringResource(R.string.app_update_automatic),
        summary = stringResource(R.string.app_update_automatic_summary),
        checked = automatic,
        onCheckedChange = { automatic = it; AppUpdater.automaticChecks = it },
    )
    SwitchPreference(
        title = stringResource(R.string.app_update_background),
        summary = stringResource(R.string.app_update_background_summary),
        checked = background,
        onCheckedChange = {
            background = it
            AppUpdater.backgroundChecks = it
            if (it && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        },
    )
    if (background && !notificationsAllowed) {
        ArrowPreference(
            title = stringResource(R.string.app_update_enable_notifications),
            summary = stringResource(R.string.app_update_notifications_blocked),
            onClick = { context.startActivity(UpdateNotifications.settingsIntent(context)) },
        )
    }
    AppUpdatePreference(state, onCheck = { AppUpdater.check() }, onOpen = { AppUpdater.openDetails() })
}

@Composable
internal fun AppUpdatePreference(state: UpdateState, onCheck: () -> Unit, onOpen: () -> Unit) {
    ArrowPreference(
        title = stringResource(R.string.app_update_check),
        summary = when {
            state.downloading -> stringResource(R.string.app_update_downloading, state.progress ?: 0)
            state.installing -> stringResource(R.string.app_update_installing)
            state.checking -> stringResource(R.string.app_update_checking)
            state.release != null -> stringResource(R.string.app_update_available_summary, state.release.version)
            else -> state.message ?: stringResource(R.string.app_update_installed, BuildConfig.VERSION_NAME)
        },
        onClick = { if (state.release != null) onOpen() else onCheck() },
    )
}

/** A prominent, tappable status card on Home, including errors and download progress. */
@Composable
fun AppUpdateCard(modifier: Modifier = Modifier) {
    val state by AppUpdater.state.collectAsState()
    if (!AppUpdater.automaticChecks && state.release == null && state.message == null && !state.checking) return
    AppUpdateStatusCard(state, modifier, onOpen = { AppUpdater.openDetails() }, onCheck = { AppUpdater.check() })
}

@Composable
internal fun AppUpdateStatusCard(state: UpdateState, modifier: Modifier = Modifier, onOpen: () -> Unit, onCheck: () -> Unit) {
    val colors = MiuixTheme.colorScheme
    val busy = state.checking || state.downloading || state.installing
    if (state.upToDate && !busy && state.release == null && state.file == null) return
    val title = when {
        state.downloading -> stringResource(R.string.app_update_downloading, state.progress ?: 0)
        state.installing -> stringResource(R.string.app_update_installing)
        state.checking -> stringResource(R.string.app_update_checking)
        state.file != null -> stringResource(R.string.app_update_ready_title)
        state.release != null -> stringResource(R.string.app_update_available, state.release.version)
        state.upToDate -> stringResource(R.string.app_update_current)
        state.message != null -> stringResource(R.string.app_update_attention)
        else -> stringResource(R.string.app_update_check)
    }
    val summary = when {
        state.message != null -> state.message
        state.file != null -> stringResource(R.string.app_update_ready)
        state.release != null && !busy -> stringResource(R.string.app_update_open)
        else -> stringResource(R.string.app_update_installed, BuildConfig.VERSION_NAME)
    }
    Surface(
        onClick = { if (state.release != null || busy) onOpen() else onCheck() },
        modifier = modifier.fillMaxWidth(),
        shape = GlassStyle.cardShape,
        color = GlassStyle.surfaceColor(highlighted = state.release != null || busy),
        contentColor = colors.onSurface,
        border = GlassStyle.border(highlighted = true),
    ) {
        Column(Modifier.padding(GlassStyle.contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(52.dp).background(colors.primary.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                    if (busy) CircularProgressIndicator(Modifier.size(26.dp), color = colors.primary, strokeWidth = 3.dp)
                    else Icon(
                        if (state.release != null) Icons.Rounded.SystemUpdate
                        else if (state.message != null && !state.upToDate) Icons.Rounded.ErrorOutline
                        else if (state.upToDate) Icons.Rounded.CheckCircle
                        else Icons.Rounded.SystemUpdate,
                        contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, color = colors.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(summary, color = colors.onSurfaceVariantSummary, fontSize = 14.sp)
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.primary)
            }
            if (state.downloading) LinearProgressIndicator(
                progress = { (state.progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth(), color = colors.primary,
            )
        }
    }
}

/** Uses a real Android dialog window, independent of Miuix's root Scaffold overlay host. */
@Composable
fun AppUpdateDialog(automaticChecksEnabled: Boolean = true) {
    val state by AppUpdater.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            AppUpdater.state.collect { AppUpdater.resumePendingInstallation() }
        }
    }
    LaunchedEffect(automaticChecksEnabled) {
        if (!automaticChecksEnabled) return@LaunchedEffect
        AppUpdater.check(automatic = true)
        val preferences = context.getSharedPreferences("app_updates", android.content.Context.MODE_PRIVATE)
        if (AppUpdater.backgroundChecks && Build.VERSION.SDK_INT >= 33 &&
            !preferences.getBoolean("notification_permission_requested", false)) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    AppUpdateDetailsDialog(state, onDismiss = { AppUpdater.showDialog(false) },
        onCheck = { AppUpdater.check() }, onDownload = { AppUpdater.download() },
        onInstall = { AppUpdater.install() })
}

@Composable
internal fun AppUpdateDetailsDialog(state: UpdateState, onDismiss: () -> Unit, onCheck: () -> Unit,
    onDownload: () -> Unit, onInstall: () -> Unit) {
    if (!state.showDialog) return
    val colors = MiuixTheme.colorScheme
    val release = state.release
    Dialog(onDismissRequest = onDismiss) {
        GlassDialogBackdrop()
        Surface(shape = GlassStyle.dialogShape, color = GlassStyle.dialogColor(), contentColor = colors.onSurface,
            border = GlassStyle.border()) {
            Column(Modifier.widthIn(max = 560.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    if (release != null) stringResource(R.string.app_update_available, release.version)
                    else stringResource(R.string.app_update_check),
                    color = colors.onSurface, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                )
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.app_update_installed, BuildConfig.VERSION_NAME), color = colors.onSurfaceVariantSummary)
                    if (release != null) {
                        if (release.notes.isBlank()) Text(stringResource(R.string.app_update_no_notes), color = colors.onSurface)
                        else ReleaseNotesMarkdown(release.notes)
                    }
                    if (state.checking) Text(stringResource(R.string.app_update_checking), color = colors.onSurface)
                    if (state.downloading) {
                        Text(stringResource(R.string.app_update_downloading, state.progress ?: 0), color = colors.onSurface)
                        LinearProgressIndicator(progress = { (state.progress ?: 0) / 100f }, modifier = Modifier.fillMaxWidth(), color = colors.primary)
                    }
                    if (state.installing) Text(stringResource(R.string.app_update_installing), color = colors.onSurface)
                    state.message?.let { Text(it, color = colors.onSurface) }
                    if (state.file != null) Text(stringResource(R.string.app_update_ready), color = colors.onSurface)
                }
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    if (!state.checking && !state.downloading && !state.installing) {
                        Button(
                            onClick = {
                                when {
                                    release == null -> onCheck()
                                    state.file != null -> onInstall()
                                    else -> {
                                        onDismiss()
                                        onDownload()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                        ) {
                            Text(stringResource(when {
                                release == null -> R.string.app_update_check
                                state.file != null -> R.string.app_update_install
                                else -> R.string.app_update_download
                            }), color = colors.onPrimary)
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.app_update_later), color = colors.primary)
                    }
                }
            }
        }
    }
}
