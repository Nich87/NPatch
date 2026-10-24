package top.nkbe.npatch.ui.page

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.component.GlassDialog
import top.nkbe.npatch.ui.component.GlassDialogActions
import top.nkbe.npatch.ui.component.GlassDialogChoiceItem
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.page.newpatch.ConfiguringFab
import top.nkbe.npatch.ui.page.newpatch.ConfiguringTopBar
import top.nkbe.npatch.ui.page.newpatch.DoPatchBody
import top.nkbe.npatch.ui.page.newpatch.PatchOptionsBody
import top.nkbe.npatch.ui.theme.GlassStyle
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.PatchState
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import top.nkbe.npatch.util.LINE_PACKAGE_NAME
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val errorUnknown = stringResource(R.string.error_unknown)
    val apkMimeTypes = arrayOf(
        "application/vnd.android.package-archive",
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )

    var showSelectModuleDialog by rememberSaveable { mutableStateOf(false) }
    var showInstalledModules by rememberSaveable { mutableStateOf(false) }
    val moduleStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { modules ->
        if (modules.isNotEmpty()) scope.launch { viewModel.selectEmbeddedModules(modules) }
    }

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

    val navigateBack: () -> Unit = back@{
        if (viewModel.isExporting) return@back
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
    BackHandler(enabled = viewModel.patchState != PatchState.INIT, onBack = navigateBack)

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

    if (showInstalledModules) {
        SelectAppsScreen(
            initialSelected = viewModel.embeddedModules,
            onConfirm = {
                viewModel.updateEmbeddedModules(it)
                showInstalledModules = false
            },
            onBack = { showInstalledModules = false },
        )
        return
    }

    NPatchScaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
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
                PatchState.ERROR -> SmallTopAppBar(
                    title = viewModel.patchApp.label,
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = navigateBack, enabled = !viewModel.isExporting) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.patch_return))
                        }
                    },
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
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                        onAddEmbed = { showSelectModuleDialog = true },
                    )
                }
                PatchState.PATCHING,
                PatchState.CANCELLING,
                PatchState.FINISHED,
                PatchState.ERROR -> {
                    DoPatchBody(modifier = Modifier, navigator = navigator, onCancel = { showAbortDialog = true })
                }
                else -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(size = 32.dp)
                    }
                }
            }

            EmbeddedModuleSourceDialog(
                show = showSelectModuleDialog,
                onInstalled = {
                    showSelectModuleDialog = false
                    showInstalledModules = true
                },
                onStorage = {
                    showSelectModuleDialog = false
                    moduleStorageLauncher.launch(apkMimeTypes)
                },
                onDismiss = { showSelectModuleDialog = false },
            )

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
    GlassDialog(
        title = stringResource(R.string.patch_abort_title),
        show = show.value,
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.patch_abort_message),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            GlassDialogActions(
                dismissText = stringResource(android.R.string.cancel),
                onDismiss = { show.value = false; onDismiss() },
                confirmText = stringResource(R.string.patch_abort_confirm),
                onConfirm = { show.value = false; onConfirm() },
            )
        }
    }
}

@Composable
fun LineDownloadDialog(
    onOpenDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val show = remember { mutableStateOf(true) }
    GlassDialog(
        title = stringResource(R.string.line_download_dialog_title),
        show = show.value,
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.line_download_dialog_message),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            GlassDialogActions(
                dismissText = stringResource(android.R.string.cancel),
                onDismiss = { show.value = false; onDismiss() },
                confirmText = stringResource(R.string.open_download_page),
                onConfirm = { show.value = false; onOpenDownload() },
            )
        }
    }
}

@Composable
private fun EmbeddedModuleSourceDialog(
    show: Boolean,
    onInstalled: () -> Unit,
    onStorage: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassDialog(
        title = stringResource(R.string.patch_embed_modules),
        show = show,
        onDismissRequest = onDismiss,
    ) {
        GlassDialogChoiceItem(
            icon = Icons.Rounded.Extension,
            text = stringResource(R.string.patch_from_installed_modules),
            onClick = onInstalled,
        )
        GlassDialogChoiceItem(
            icon = Icons.Rounded.FolderOpen,
            text = stringResource(R.string.patch_from_storage),
            onClick = onStorage,
        )
        Spacer(Modifier.height(16.dp))
        TextButton(
            text = stringResource(android.R.string.cancel),
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().heightIn(min = GlassStyle.dialogButtonMinHeight),
        )
    }
}
