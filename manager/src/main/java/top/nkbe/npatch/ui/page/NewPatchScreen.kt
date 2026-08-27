package top.nkbe.npatch.ui.page

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.COUIScrollBehavior
import io.github.suqi8.coui.kmp.basic.CircularProgressIndicator
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.overlay.OverlayDialog
import io.github.suqi8.coui.kmp.theme.COUITheme
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.page.newpatch.ConfiguringFab
import top.nkbe.npatch.ui.page.newpatch.ConfiguringTopBar
import top.nkbe.npatch.ui.page.newpatch.DoPatchBody
import top.nkbe.npatch.ui.page.newpatch.PatchOptionsBody
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.util.LINE_PACKAGE_NAME
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.PatchState
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.ViewAction

const val ACTION_STORAGE = 0
const val ACTION_APPLIST = 1
const val ACTION_INTENT_INSTALL = 2
const val ACTION_PROXY_DOWNLOAD = 3

const val LINE_DOWNLOAD_URL = "https://www.apkmirror.com/uploads/?appcategory=line"

/**
 * パッチ実行画面。
 * ストレージからの APK 選択、インストール済みアプリの直接パッチ、インテント経由の APK、Proxy 自動ダウンロードパッチを扱う。
 */
@Composable
fun NewPatchScreen(
    id: Int,
    data: String? = null
) {
    val navigator = LocalNavigator.current
    val viewModel = viewModel<NewPatchViewModel>()
    val snackbarHost = LocalSnackbarHost.current
    val scrollBehavior = COUIScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val errorUnknown = stringResource(R.string.error_unknown)
    val apkMimeTypes = arrayOf(
        "application/vnd.android.package-archive",
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    // インストール済みアプリが見つからない場合、ダウンロードページへ誘導するダイアログ
    var showDownloadDialog by remember { mutableStateOf(false) }
    // パッチ実行中に戻ろうとした場合の強制終了確認ダイアログ
    var showAbortDialog by remember { mutableStateOf(false) }

    // ストレージから APK を選択
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { apks ->
        if (apks.isEmpty()) {
            viewModel.reset()
            navigator.pop()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            NeoPackageManager.getAppInfoFromApks(apks)
                .onSuccess {
                    viewModel.dispatch(ViewAction.ConfigurePatch(it.first()))
                }
                .onFailure {
                    snackbarHost.showSnackbar(it.message ?: errorUnknown)
                    viewModel.reset()
                    navigator.pop()
                }
        }
    }

    LaunchedEffect(Unit) {
        if (viewModel.hasExecutedIntent) return@LaunchedEffect
        viewModel.hasExecutedIntent = true
        NeoPackageManager.cleanTmpApkDir()
        when (id) {
            ACTION_STORAGE -> {
                storageLauncher.launch(apkMimeTypes)
                viewModel.dispatch(ViewAction.DoneInit)
            }
            ACTION_APPLIST -> {
                // インストール済みアプリを直接パッチ対象にする
                viewModel.dispatch(ViewAction.DoneInit)
                val lineApp = NeoPackageManager.appList.firstOrNull {
                    it.app.packageName == LINE_PACKAGE_NAME
                }
                if (lineApp != null) {
                    viewModel.dispatch(ViewAction.ConfigurePatch(lineApp))
                } else {
                    // アプリ未インストール: ダウンロードページを開くダイアログを表示する
                    showDownloadDialog = true
                }
            }
            ACTION_INTENT_INSTALL -> {
                data?.let { dataStr ->
                    val uri = dataStr.toUri()
                    scope.launch {
                        NeoPackageManager.getAppInfoFromApks(listOf(uri)).onSuccess {
                            viewModel.dispatch(ViewAction.ConfigurePatch(it.first()))
                        }.onFailure {
                            snackbarHost.showSnackbar(it.message ?: errorUnknown)
                            viewModel.reset()
                            navigator.pop()
                        }
                    }
                }
                viewModel.dispatch(ViewAction.DoneInit)
            }
            ACTION_PROXY_DOWNLOAD -> {
                val targetVer = data?.toLongOrNull()
                viewModel.dispatch(ViewAction.ConfigureProxyLinePatch(targetVer))
            }
        }
    }

    BackHandler(enabled = viewModel.patchState != PatchState.INIT) {
        when (viewModel.patchState) {
            PatchState.PATCHING -> showAbortDialog = true
            // 中止処理中は後始末が終わるまで待つ
            PatchState.CANCELLING -> Unit
            else -> {
                scope.launch { NeoPackageManager.cleanTmpApkDir() }
                viewModel.reset()
                navigator.pop()
            }
        }
    }

    LaunchedEffect(viewModel.patchState) {
        if (viewModel.patchState != PatchState.PATCHING) showAbortDialog = false
        if (viewModel.patchState != PatchState.CANCELLED) return@LaunchedEffect
        viewModel.reset()
        navigator.pop()
        Toast.makeText(
            context.applicationContext,
            context.getString(R.string.patch_cancelled),
            Toast.LENGTH_SHORT,
        ).show()
    }

    NPatchScaffold(
        topBar = {
            when (viewModel.patchState) {
                PatchState.CONFIGURING -> ConfiguringTopBar(scrollBehavior) {
                    scope.launch { NeoPackageManager.cleanTmpApkDir() }
                    viewModel.reset()
                    navigator.pop()
                }
                PatchState.PATCHING,
                PatchState.CANCELLING,
                PatchState.FINISHED,
                PatchState.ERROR -> NPatchTopAppBar(
                    title = viewModel.patchApp.app.packageName,
                    scrollBehavior = scrollBehavior,
                )
                else -> NPatchTopAppBar(title = "", scrollBehavior = scrollBehavior)
            }
        },
        floatingActionButton = {
            if (viewModel.patchState == PatchState.CONFIGURING) {
                ConfiguringFab()
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (viewModel.patchState) {
                PatchState.CONFIGURING -> {
                    PatchOptionsBody(
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    )
                }
                PatchState.PATCHING,
                PatchState.CANCELLING,
                PatchState.FINISHED,
                PatchState.ERROR -> {
                    DoPatchBody(modifier = Modifier, navigator = navigator)
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(size = 32.dp)
                    }
                }
            }

            if (showAbortDialog) {
                PatchAbortDialog(
                    onConfirm = {
                        showAbortDialog = false
                        viewModel.dispatch(ViewAction.CancelPatch)
                    },
                    onDismiss = { showAbortDialog = false },
                )
            }

            if (showDownloadDialog) {
                LineDownloadDialog(
                    onOpenDownload = {
                        val downloadIntent = Intent(Intent.ACTION_VIEW, LINE_DOWNLOAD_URL.toUri())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(downloadIntent) }
                            .onFailure {
                                scope.launch { snackbarHost.showSnackbar(it.message ?: errorUnknown) }
                            }
                        viewModel.reset()
                        navigator.pop()
                    },
                    onDismiss = {
                        viewModel.reset()
                        navigator.pop()
                    },
                )
            }
        }
    }
}

@Composable
fun PatchAbortDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val show = remember { mutableStateOf(true) }
    OverlayDialog(
        title = stringResource(R.string.patch_abort_title),
        show = show.value,
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.patch_abort_message),
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { show.value = false; onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.patch_abort_confirm),
                    onClick = { show.value = false; onConfirm() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(),
                )
            }
        }
    }
}

@Composable
fun LineDownloadDialog(
    onOpenDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val show = remember { mutableStateOf(true) }
    OverlayDialog(
        title = stringResource(R.string.line_download_dialog_title),
        show = show.value,
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.line_download_dialog_message),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { show.value = false; onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.open_download_page),
                    onClick = { show.value = false; onOpenDownload() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(),
                )
            }
        }
    }
}