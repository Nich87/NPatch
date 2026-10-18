package top.nkbe.npatch.ui.page.newpatch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import nkbe.util.ShizukuApi
import top.nkbe.npatch.R
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.ui.component.PatchResultSummary
import top.nkbe.npatch.ui.component.PatchSaveActions
import top.nkbe.npatch.ui.component.PatchRunHeader
import top.nkbe.npatch.ui.component.PatchRuntimeStrip
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.ui.page.Navigator
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.ui.util.checkIsApkFixedByLSP
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.PatchState
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.nkbe.npatch.ui.component.GlassDialog
import top.nkbe.npatch.ui.component.GlassDialogActions
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAG = "NewPatchPage"

/**
 * パッチ実行中の進捗・ログ表示と、完了後のインストール処理を担うボディ。
 */
@Composable
fun DoPatchBody(modifier: Modifier, navigator: Navigator, onCancel: () -> Unit) {
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var followLogs by rememberSaveable { mutableStateOf(true) }
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    fun copyLogs() {
        val text = viewModel.logs.joinToString("\n") { (level, message) ->
            "[${when (level) { Log.ERROR -> "E"; Log.DEBUG -> "D"; else -> "I" }}] $message"
        }
        if (text.isEmpty()) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NPatch Log", text))
        scope.launch { snackbarHost.showSnackbar(context.getString(R.string.home_info_copied)) }
    }
    val saveDirectoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                Configs.storageDirectory = uri.toString()
                viewModel.retryExport()
            } catch (error: Exception) {
                scope.launch { snackbarHost.showSnackbar(error.message ?: context.getString(R.string.error_unknown)) }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.logs.isEmpty()) {
            viewModel.dispatch(ViewAction.LaunchPatch)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        val required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (required) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val running = viewModel.patchState == PatchState.PATCHING ||
        viewModel.patchState == PatchState.CANCELLING

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        val colors = MiuixTheme.colorScheme
        PatchRunHeader(
            stage = when (viewModel.patchState) {
                PatchState.FINISHED -> stringResource(R.string.patch_completed)
                PatchState.ERROR -> stringResource(R.string.patch_failed)
                PatchState.CANCELLING -> stringResource(R.string.patch_cancelling)
                else -> viewModel.currentStage.ifEmpty { stringResource(R.string.patch_stage_preparing) }
            },
            elapsedMillis = viewModel.elapsedMillis,
            running = running,
            failed = viewModel.patchState == PatchState.ERROR,
        )
        Spacer(Modifier.height(12.dp))
        PatchRuntimeStrip(
            memory = viewModel.runtimeSamples.map { it.memoryBytes },
            io = viewModel.runtimeSamples.map { it.ioBytesPerSecond },
            cpu = viewModel.runtimeSamples.map { it.cpuPercent },
        )
        Spacer(Modifier.height(12.dp))
        Surface(modifier = Modifier.fillMaxWidth().weight(1f), shape = RoundedCornerShape(24.dp),
            color = colors.surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.patch_log), modifier = Modifier.weight(1f),
                        style = MiuixTheme.textStyles.body1)
                    Text(viewModel.logs.size.toString(), style = MiuixTheme.textStyles.footnote1,
                        color = colors.onSurfaceVariantSummary)
                    IconButton(onClick = { followLogs = !followLogs }, modifier = Modifier.size(48.dp)) {
                        Icon(if (followLogs) Icons.Outlined.Pause else Icons.Outlined.VerticalAlignBottom,
                            contentDescription = stringResource(if (followLogs) R.string.patch_logs_pause else R.string.patch_logs_latest),
                            tint = colors.primary)
                    }
                }
                HorizontalDivider(color = colors.outline.copy(alpha = 0.12f))
                val scrollState = rememberLazyListState()
                val dragged by scrollState.interactionSource.collectIsDraggedAsState()
                LaunchedEffect(dragged) { if (dragged) followLogs = false }
                LazyColumn(state = scrollState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                    overscrollEffect = null) {
                    items(viewModel.logs) { (level, message) ->
                        val error = level == Log.ERROR
                        val ink = if (error) colors.error else colors.primary
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when (level) { Log.ERROR -> "E"; Log.DEBUG -> "D"; else -> "I" },
                                modifier = Modifier.background(ink.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace), color = ink)
                            Text(message, style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f).combinedClickable(onClick = {}, onLongClick = ::copyLogs),
                                color = if (error) colors.error else colors.onSurface)
                        }
                    }
                    if (!running) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PatchResultSummary(viewModel.patchState == PatchState.FINISHED,
                                viewModel.patchApp.app.packageName, viewModel.patchError,
                                viewModel.outputSizeBytes, viewModel.elapsedMillis)
                            if (viewModel.patchState == PatchState.FINISHED) {
                                if (viewModel.exportError != null) {
                                    Text(stringResource(R.string.patch_save_recovery), style = MiuixTheme.textStyles.body2,
                                        modifier = Modifier.padding(horizontal = 4.dp))
                                }
                                TextButton(
                                    text = stringResource(if (viewModel.isExporting) R.string.patch_saving else R.string.patch_save_options),
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    enabled = !viewModel.isExporting,
                                    onClick = { showSaveDialog = true },
                                )
                            }
                        }
                    }
                }
                LaunchedEffect(viewModel.logs, followLogs, viewModel.patchState) {
                    if (followLogs && viewModel.logs.isNotEmpty()) {
                        scrollState.scrollToItem(if (running) viewModel.logs.lastIndex else viewModel.logs.size)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 下部操作ボタン ──
        when (viewModel.patchState) {
            PatchState.FINISHED -> {
                val installFailed = stringResource(R.string.patch_install_failed)
                val copyError = stringResource(R.string.copy_error)
                var installation by remember { mutableStateOf<NewPatchViewModel.InstallMethod?>(null) }

                // Shizuku 有効時はパッチ完了と同時にインストールを自動開始する
                LaunchedEffect(Unit) {
                    if (ShizukuApi.isReady && installation == null && viewModel.exportError == null) {
                        installation = NewPatchViewModel.InstallMethod.SHIZUKU
                        Log.d(TAG, "Auto installation via Shizuku")
                    }
                }

                val onFinish: (Int, String?) -> Unit = { status, message ->
                    installation = null
                    scope.launch {
                        if (status == PackageInstaller.STATUS_SUCCESS) {
                            viewModel.reset()
                            navigator.pop()
                            Toast.makeText(
                                context.applicationContext,
                                context.getString(R.string.patch_install_successfully),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else if (status != NeoPackageManager.STATUS_USER_CANCELLED &&
                            status != PackageInstaller.STATUS_FAILURE_ABORTED
                        ) {
                            val result = snackbarHost.showSnackbar(installFailed, copyError)
                            if (result == SnackbarResult.ActionPerformed) {
                                val cm = lspApp.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("NPatch", message))
                            }
                        }
                    }
                }
                when (installation) {
                    NewPatchViewModel.InstallMethod.SYSTEM -> InstallDialog(
                        patchApp = viewModel.patchApp,
                        method = NeoPackageManager.InstallMethod.SYSTEM,
                        onFinish = onFinish,
                    )

                    NewPatchViewModel.InstallMethod.SHIZUKU -> InstallDialog(
                        patchApp = viewModel.patchApp,
                        method = NeoPackageManager.InstallMethod.SHIZUKU,
                        onFinish = onFinish,
                    )

                    null -> {}
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PatchBottomAction(
                        label = stringResource(R.string.install), icon = Icons.Outlined.FileDownload,
                        modifier = Modifier.weight(1f), primary = true,
                        enabled = !viewModel.isExporting,
                        onClick = {
                            installation =
                                if (!ShizukuApi.isReady) NewPatchViewModel.InstallMethod.SYSTEM
                                else NewPatchViewModel.InstallMethod.SHIZUKU
                            Log.d(TAG, "Installation method: $installation")
                        },
                    )
                    PatchBottomAction(stringResource(R.string.patch_logs_copy), Icons.Outlined.ContentCopy,
                        modifier = Modifier.weight(1f), onClick = ::copyLogs)
                }
            }
            PatchState.ERROR -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PatchBottomAction(stringResource(R.string.patch_logs_copy), Icons.Outlined.ContentCopy,
                        modifier = Modifier.fillMaxWidth(), onClick = ::copyLogs)
                }
            }
            PatchState.PATCHING, PatchState.CANCELLING -> Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PatchBottomAction(stringResource(R.string.patch_stop), Icons.Outlined.Close,
                    modifier = Modifier.weight(1f), destructive = true,
                    enabled = viewModel.patchState == PatchState.PATCHING, onClick = onCancel)
                PatchBottomAction(stringResource(R.string.patch_logs_copy), Icons.Outlined.ContentCopy,
                    modifier = Modifier.weight(1f), enabled = viewModel.logs.isNotEmpty(), onClick = ::copyLogs)
            }
            else -> Unit
        }
    }
    GlassDialog(title = stringResource(R.string.patch_save_options), show = showSaveDialog,
        onDismissRequest = { if (!viewModel.isExporting) showSaveDialog = false }) {
        PatchSaveActions(viewModel.exportError, viewModel.isExporting,
            onRetry = { showSaveDialog = false; viewModel.retryExport() },
            onChooseDirectory = {
                showSaveDialog = false
                saveDirectoryLauncher.launch(Configs.storageDirectory?.toUri())
            })
        TextButton(
            text = stringResource(android.R.string.cancel),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
            enabled = !viewModel.isExporting,
            onClick = { showSaveDialog = false },
        )
    }
}

@Composable
private fun PatchBottomAction(
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    val ink = (if (primary) colors.onPrimary else if (destructive) colors.error else colors.onSurface)
        .let { if (enabled) it else it.copy(alpha = 0.38f) }
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, modifier = Modifier.size(22.dp),
            tint = ink)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MiuixTheme.textStyles.body1,
            color = ink)
    }
    if (primary || destructive) {
        Button(onClick, modifier.heightIn(min = 56.dp), enabled = enabled, shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (destructive) colors.error.copy(alpha = 0.12f) else colors.primary),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp), content = content)
    } else {
        OutlinedButton(onClick, modifier.heightIn(min = 56.dp), enabled = enabled, shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.3f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp), content = content)
    }
}

@Composable
fun UninstallConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val show = remember { mutableStateOf(true) }
    GlassDialog(
        title = stringResource(R.string.uninstall),
        show = show.value,
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.patch_uninstall_text),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            GlassDialogActions(
                dismissText = stringResource(android.R.string.cancel),
                onDismiss = { show.value = false; onDismiss() },
                confirmText = stringResource(android.R.string.ok),
                onConfirm = { show.value = false; onConfirm() },
            )
        }
    }
}

@Composable
fun InstallDialog(
    patchApp: AppInfo,
    method: NeoPackageManager.InstallMethod,
    onFinish: (Int, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var uninstallFirst by remember(method, patchApp.app.packageName) {
        mutableStateOf(
            if (method == NeoPackageManager.InstallMethod.SHIZUKU) {
                ShizukuApi.isPackageInstalledWithoutPatch(patchApp.app.packageName)
            } else {
                checkIsApkFixedByLSP(context, patchApp.app.packageName)
            },
        )
    }
    var installing by remember { mutableStateOf(0) }
    var installStarted by remember { mutableStateOf(false) }
    val uninstallLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (checkIsApkFixedByLSP(context, patchApp.app.packageName)) {
            onFinish(PackageInstaller.STATUS_FAILURE, "Original application was not uninstalled")
        } else {
            uninstallFirst = false
        }
    }

    suspend fun doInstall() {
        Log.i(TAG, "Installing ${patchApp.app.packageName} with $method")
        installStarted = true
        installing = 1
        val outcome = NeoPackageManager.install(method)
        installing = 0
        Log.i(TAG, "Installation end: $outcome")
        when (outcome) {
            is NeoPackageManager.InstallOutcome.Completed ->
                onFinish(outcome.status, outcome.message)

            NeoPackageManager.InstallOutcome.PermissionRequired -> {
                installStarted = false
                onFinish(
                    NeoPackageManager.STATUS_USER_CANCELLED,
                    "Package install permission is required; retry after granting it",
                )
            }
        }
    }

    LaunchedEffect(uninstallFirst, method) {
        if (!uninstallFirst && !installStarted) {
            doInstall()
        }
    }

    if (uninstallFirst) {
        UninstallConfirmationDialog(
            onDismiss = { onFinish(NeoPackageManager.STATUS_USER_CANCELLED, "User cancelled") },
            onConfirm = {
                if (method == NeoPackageManager.InstallMethod.SHIZUKU) {
                    scope.launch {
                        Log.i(TAG, "Uninstalling app ${patchApp.app.packageName}")
                        installing = 2
                        val (status, message) = NeoPackageManager.uninstall(patchApp.app.packageName)
                        installing = 0
                        Log.i(TAG, "Uninstallation end: $status, $message")
                        if (status == PackageInstaller.STATUS_SUCCESS) {
                            uninstallFirst = false
                        } else {
                            onFinish(status, message)
                        }
                    }
                } else {
                    uninstallLauncher.launch(
                        Intent(Intent.ACTION_DELETE).apply {
                            data = "package:${patchApp.app.packageName}".toUri()
                        },
                    )
                }
            }
        )
    }

    if (installing != 0) {
        val showInstalling = remember { mutableStateOf(true) }
        GlassDialog(
            title = stringResource(if (installing == 1) R.string.installing else R.string.uninstalling),
            show = showInstalling.value,
            onDismissRequest = {},
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(size = 48.dp)
            }
        }
    }
}
