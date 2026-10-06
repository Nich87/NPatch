package top.nkbe.npatch.ui.page

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import nkbe.util.NeoPackageManager
import nkbe.util.ShizukuApi
import top.nkbe.npatch.R
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.network.proxy.ApkProxyService
import top.nkbe.npatch.network.proxy.VersionListResult
import top.nkbe.npatch.repo.KnotRelease
import top.nkbe.npatch.repo.KnotReleaseLoader
import top.nkbe.npatch.ui.component.AppUpdateCard
import top.nkbe.npatch.ui.component.GlassCard
import top.nkbe.npatch.ui.component.GlassDialog
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.ReleaseNotesMarkdown
import top.nkbe.npatch.ui.theme.GlassStyle
import top.nkbe.npatch.ui.util.KnotDownloader
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.ui.util.checkIsApkFixedByLSP
import top.nkbe.npatch.ui.util.rememberDisplayedReleases
import top.nkbe.npatch.util.LINE_PACKAGE_NAME
import top.nkbe.npatch.util.formatLineVersionName

/** Knot のパッケージ名 */
private const val KNOT_PACKAGE_NAME = "app.zipper.knot"

@Composable
fun HomeScreen(
    navigator: Navigator,
    onNavigateToSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val lineApp = NeoPackageManager.appList.firstOrNull {
        it.app.packageName == LINE_PACKAGE_NAME
    }
    val installedKnot = NeoPackageManager.appList.firstOrNull {
        it.app.packageName == KNOT_PACKAGE_NAME
    }
    val installedKnotVersion = installedKnot?.versionName?.removePrefix("v")?.removePrefix("V")
    val appsLoaded = NeoPackageManager.appList.isNotEmpty()
    val shizukuReady = ShizukuApi.isReady
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val errorUnknown = stringResource(R.string.error_unknown)
    var isIntentLaunched by rememberSaveable { mutableStateOf(false) }
    var releases by remember { mutableStateOf<List<KnotRelease>>(emptyList()) }
    var releasesLoading by remember { mutableStateOf(true) }
    val displayedReleases = rememberDisplayedReleases(releases)
    var refreshKey by remember { mutableStateOf(0) }
    var showStorageWarning by remember { mutableStateOf(false) }
    var showPatchChoiceDialog by remember { mutableStateOf(false) }
    var showProxyVersionDialog by remember { mutableStateOf(false) }

    // KnotDownloader は stateless になったため HomeScreen レベルで remember する必要はないが、
    // context を保持するため remember で同一インスタンスを使い回す
    val downloader = remember { KnotDownloader(context) }

    // 保存先フォルダ (URI) が未設定なら警告して設定画面へ誘導する
    fun navigateToPatch(action: Int, data: String? = null) {
        if (Configs.storageDirectory == null) {
            showStorageWarning = true
        } else {
            navigator.navigate(Route.NewPatch(action, data))
        }
    }

    LaunchedEffect(refreshKey) {
        val activity = context as? Activity
        val launchIntent = activity?.intent
        if (!isIntentLaunched &&
            launchIntent?.action == Intent.ACTION_VIEW &&
            launchIntent.hasCategory(Intent.CATEGORY_DEFAULT) &&
            launchIntent.type == "application/vnd.android.package-archive"
        ) {
            isIntentLaunched = true
            launchIntent.data?.let {
                navigator.navigate(Route.NewPatch(ACTION_INTENT_INSTALL, it.toString()))
            }
        }
        ShizukuApi.refreshState()

        // Parallel: fetch app list and releases concurrently
        coroutineScope {
            val appJob = async {
                if (NeoPackageManager.appList.isEmpty()) {
                    NeoPackageManager.fetchAppList()
                }
            }
            val releaseJob = async {
                // キャッシュがあればスケルトンを出さず即表示し、バックグラウンドで更新する
                if (KnotReleaseLoader.cachedReleases.isNotEmpty()) {
                    releases = KnotReleaseLoader.cachedReleases
                } else {
                    releasesLoading = true
                }
                // 初期表示は TTL キャッシュを利用、リフレッシュボタン押下時のみ強制更新
                KnotReleaseLoader.fetchAllReleases(forceRefresh = refreshKey > 0)
                    .onSuccess { releases = it }
                releasesLoading = false
            }
            appJob.await()
            releaseJob.await()
        }
    }

    // MainScreen already consumes the system bar insets.
    NPatchScaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            SmallTopAppBar(
                title = stringResource(R.string.screen_home),
                color = Color.Transparent,
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                    IconButton(onClick = { onNavigateToSettings() }) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.screen_settings))
                    }
                },
                defaultWindowInsetsPadding = false,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item { AppUpdateCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                // ====== Centered app icon ======
                item {
                    // Spacing between the top app bar and the icon
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (lineApp != null) {
                            // Cache icon lookup so it doesn't re-run on every recomposition.
                            val iconBitmap = remember(lineApp.app.packageName) {
                                NeoPackageManager.getIcon(lineApp)
                            }
                            Image(
                                bitmap = iconBitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                Icons.AutoMirrored.Rounded.ExitToApp,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.size(40.dp),
                            )
                        }
                    }
                }

                // App name
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.line_app_name),
                        style = MiuixTheme.textStyles.title1,
                        fontWeight = FontWeight.Bold,
                    )
                }

                // Version + patch status
                item {
                    Spacer(Modifier.height(4.dp))
                    if (!appsLoaded) {
                        Box(
                            modifier = Modifier
                                .height(18.dp)
                                .width(160.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
                        )
                    } else if (lineApp != null) {
                        val isPatched = !checkIsApkFixedByLSP(context, LINE_PACKAGE_NAME)
                        Text(
                            text = stringResource(
                                R.string.line_installed_version,
                                lineApp.versionName ?: "?",
                            ) + " \u00B7 " + stringResource(
                                if (isPatched) R.string.line_patched else R.string.line_not_patched
                            ),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.line_not_installed),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.error,
                        )
                    }
                }

                // Patch button
                item {
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = { showPatchChoiceDialog = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .heightIn(min = 48.dp),
                        cornerRadius = 24.dp,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            text = stringResource(R.string.patch_start),
                            style = MiuixTheme.textStyles.body1,
                        )
                    }
                }

                // Shizuku status
                item {
                    Spacer(Modifier.height(16.dp))
                    GlassCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        highlighted = shizukuReady,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.shizuku),
                                    style = MiuixTheme.textStyles.subtitle,
                                )
                                Text(
                                    text = stringResource(
                                        if (shizukuReady) R.string.shizuku_available
                                        else R.string.shizuku_unavailable
                                    ),
                                    style = MiuixTheme.textStyles.body2,
                                    color = if (shizukuReady) {
                                        MiuixTheme.colorScheme.primary
                                    } else {
                                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    },
                                )
                            }
                            if (!shizukuReady) {
                                TextButton(
                                    text = stringResource(R.string.shizuku_request),
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    onClick = { ShizukuApi.requestPermission() },
                                )
                            }
                        }
                    }
                }

                // ====== Knot releases section ======
                item {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.NewReleases,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.knot_releases_title),
                                style = MiuixTheme.textStyles.subtitle,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = if (installedKnotVersion != null) {
                                    stringResource(R.string.knot_installed_version, installedKnotVersion)
                                } else {
                                    stringResource(R.string.knot_not_installed)
                                },
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }

                if (releasesLoading && displayedReleases.isEmpty()) {
                    items(3) {
                        SkeletonReleaseItem()
                        Spacer(Modifier.height(16.dp))
                    }
                } else if (displayedReleases.isEmpty()) {
                    item {
                        GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text(
                                text = stringResource(R.string.knot_releases_empty),
                                modifier = Modifier.padding(16.dp),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = displayedReleases,
                        key = { _, r -> r.tagName ?: r.hashCode().toString() }
                    ) { index, release ->
                        KnotReleaseItem(
                            release = release,
                            isLatest = index == 0,
                            installedVersion = installedKnotVersion,
                            context = context,
                            downloader = downloader,
                            onContinueToPatch = { showPatchChoiceDialog = true },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }

                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    // Proxy バージョン選択ダイアログ
    if (showProxyVersionDialog) {
        ProxyVersionSelectionDialog(
            onSelectVersion = { selectedVer ->
                showProxyVersionDialog = false
                navigateToPatch(ACTION_PROXY_DOWNLOAD, selectedVer?.toString())
            },
            onDismiss = { showProxyVersionDialog = false }
        )
    }
    // パッチ方法選択ダイアログ
    if (showPatchChoiceDialog) {
        PatchChoiceDialog(
            showInstalledOption = lineApp != null,
            onPatchFromProxy = {
                showPatchChoiceDialog = false
                val customVersion = Configs.customLineVersionCodeOrNull
                if (customVersion != null) {
                    navigateToPatch(ACTION_PROXY_DOWNLOAD, customVersion.toString())
                } else {
                    showProxyVersionDialog = true
                }
            },
            onPatchInstalled = {
                showPatchChoiceDialog = false
                navigateToPatch(ACTION_APPLIST)
            },
            onPatchFromApk = {
                showPatchChoiceDialog = false
                navigateToPatch(ACTION_STORAGE)
            },
            onDownload = {
                showPatchChoiceDialog = false
                val downloadIntent = Intent(Intent.ACTION_VIEW, LINE_DOWNLOAD_URL.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(downloadIntent) }
                    .onFailure {
                        scope.launch { snackbarHost.showSnackbar(it.message ?: errorUnknown) }
                    }
            },
            onDismiss = { showPatchChoiceDialog = false },
        )
    }

    // 保存先フォルダ (URI) 未設定の警告ダイアログ
    if (showStorageWarning) {
        GlassDialog(
            title = stringResource(R.string.storage_not_set_title),
            show = showStorageWarning,
            onDismissRequest = { showStorageWarning = false },
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.storage_not_set_message))
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        onClick = { showStorageWarning = false },
                    )
                    TextButton(
                        text = stringResource(R.string.go_to_settings),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        onClick = {
                            showStorageWarning = false
                            onNavigateToSettings()
                        },
                        colors = ButtonDefaults.textButtonColors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PatchChoiceDialog(
    showInstalledOption: Boolean,
    onPatchFromProxy: () -> Unit,
    onPatchInstalled: () -> Unit,
    onPatchFromApk: () -> Unit,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val show = remember { mutableStateOf(true) }
    GlassDialog(
        title = stringResource(R.string.patch_choice_title),
        show = show.value,
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Column {
            PatchChoiceItem(
                icon = Icons.Rounded.CloudDownload,
                text = stringResource(R.string.patch_from_proxy),
                onClick = { show.value = false; onPatchFromProxy() },
            )
            if (showInstalledOption) {
                PatchChoiceItem(
                    icon = Icons.Rounded.Smartphone,
                    text = stringResource(R.string.patch_installed_line),
                    onClick = { show.value = false; onPatchInstalled() },
                )
            }
            PatchChoiceItem(
                icon = Icons.Rounded.FolderOpen,
                text = stringResource(R.string.patch_from_apk),
                onClick = { show.value = false; onPatchFromApk() },
            )
            PatchChoiceItem(
                icon = Icons.Rounded.Download,
                text = stringResource(R.string.download_line),
                onClick = { show.value = false; onDownload() },
            )
        }
    }
}

@Composable
private fun PatchChoiceItem(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
        )
    }
}

@Composable
private fun SkeletonReleaseItem() {
    val shimmer = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.width(120.dp).height(20.dp)
                    .clip(RoundedCornerShape(4.dp)).background(shimmer),
            )
            Box(
                Modifier.fillMaxWidth(0.75f).height(12.dp)
                    .clip(RoundedCornerShape(4.dp)).background(shimmer),
            )
            Box(
                Modifier.fillMaxWidth(0.5f).height(12.dp)
                    .clip(RoundedCornerShape(4.dp)).background(shimmer),
            )
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(24.dp)).background(shimmer),
            )
        }
    }
}

@Composable
private fun KnotReleaseItem(
    release: KnotRelease,
    isLatest: Boolean,
    installedVersion: String?,
    context: android.content.Context,
    downloader: KnotDownloader,
    onContinueToPatch: () -> Unit,
) {
    val version = release.version ?: release.tagName ?: return
    val apkAsset = release.assets.firstOrNull { it.name?.endsWith(".apk") == true }
    var isDownloading by remember { mutableStateOf(false) }
    var showChangelog by remember(release.tagName) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    GlassDialog(
        title = stringResource(R.string.knot_release_version, version),
        show = showChangelog,
        onDismissRequest = { showChangelog = false },
    ) {
        val notes = release.body.orEmpty()
        if (notes.isBlank()) Text(stringResource(R.string.app_update_no_notes))
        else ReleaseNotesMarkdown(notes)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(text = stringResource(R.string.app_update_later), onClick = { showChangelog = false })
        }
    }

    GlassCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .clip(GlassStyle.cardShape)
            .clickable(onClickLabel = stringResource(R.string.knot_release_changelog)) { showChangelog = true },
        highlighted = isLatest,
    ) {
        Column(Modifier.padding(16.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.knot_release_version, version),
                    style = MiuixTheme.textStyles.subtitle,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.primary,
                )
                if (isLatest) {
                    Text(
                        text = "\u2022 ${stringResource(R.string.knot_release_latest)}",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                if (installedVersion != null && release.version == installedVersion) {
                    Text(
                        text = stringResource(R.string.knot_installed),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                release.publishedAt?.let { date ->
                    Text(
                        text = date.take(10),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            release.body?.let { body ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = body.lines()
                        .filter { it.isNotBlank() }
                        .take(3)
                        .joinToString("\n") { it.trimStart('*', '\r', ' ') },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = {
                        val downloadUrl = apkAsset?.browserDownloadUrl
                        if (downloadUrl != null) {
                            val fileName = apkAsset.name ?: "Knot-v$version.apk"
                            isDownloading = true
                            scope.launch {
                                try {
                                    // suspend するので完了まで isDownloading が true のまま維持される
                                    // -> コルーチン内の withContext(Main) から startActivity を呼ぶため
                                    //    Android 10+ の背景起動制限にもかからない
                                    val installed = downloader.downloadAndOpen(downloadUrl, fileName)
                                    if (installed) {
                                        // ダウンロード・インストール完了後にパッチ続行へ進む
                                        onContinueToPatch()
                                    }
                                } finally {
                                    isDownloading = false
                                }
                            }
                        } else {
                            // APK asset がない場合はブラウザでリリースページへ
                            release.htmlUrl?.let { url ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    },
                    enabled = !isDownloading,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    cornerRadius = 24.dp,
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            size = 16.dp,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.knot_release_download),
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
                Button(
                    onClick = {
                        release.htmlUrl?.let { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    cornerRadius = 24.dp,
                    colors = ButtonDefaults.buttonColors(
                        color = Color.Transparent,
                        contentColor = MiuixTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.knot_release_changelog),
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogMessageRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun ProxyVersionSelectionDialog(
    onSelectVersion: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<VersionListResult?>(null) }

    LaunchedEffect(Unit) {
        result = ApkProxyService(context).fetchAvailableVersions()
        isLoading = false
    }

    val show = remember { mutableStateOf(true) }
    GlassDialog(
        title = stringResource(R.string.select_line_version_title),
        show = show.value,
        onDismissRequest = { show.value = false; onDismiss() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            val versions = result?.versions.orEmpty()
            when {
                isLoading -> DialogMessageRow(stringResource(R.string.select_line_version_loading))
                versions.isEmpty() -> DialogMessageRow(stringResource(R.string.select_line_version_empty))
                else -> {
                    val recommended = result?.recommended
                    val recommendedSuffix = stringResource(R.string.select_line_version_recommended)
                    versions.forEach { ver ->
                        val formattedVer = formatLineVersionName(ver)
                        PatchChoiceItem(
                            icon = Icons.Rounded.CloudDownload,
                            text = if (ver == recommended) "$formattedVer ($recommendedSuffix)" else formattedVer,
                            onClick = {
                                show.value = false
                                onSelectVersion(ver)
                            }
                        )
                    }
                }
            }
        }
    }
}
