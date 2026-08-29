package top.nkbe.npatch.ui.page

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import nkbe.util.ShizukuApi
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.R
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.util.backgroundAwareCardColors
import top.nkbe.npatch.ui.util.backgroundAwareColor
import io.github.suqi8.coui.kmp.basic.Button
import io.github.suqi8.coui.kmp.basic.ButtonDefaults
import io.github.suqi8.coui.kmp.basic.Card
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextButton
import io.github.suqi8.coui.kmp.theme.COUITheme

private val welcomeShizukuListener: (Int, Int) -> Unit = { _, grantResult ->
    ShizukuApi.isPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
    ShizukuApi.refreshState()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WelcomeScreen(
    reviewMode: Boolean,
    onFinish: () -> Unit,
    onReturn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 3 })
    var storageGranted by remember { mutableStateOf(context.hasStorageAccess()) }
    var appListGranted by remember { mutableStateOf(context.hasAppListAccessDeclaration()) }
    var notificationGranted by remember { mutableStateOf(context.hasNotificationAccess()) }
    var notificationRequested by remember { mutableStateOf(false) }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        storageGranted = context.hasStorageAccess()
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        storageGranted = context.hasStorageAccess()
        appListGranted = context.hasAppListAccessDeclaration()
        notificationGranted = context.hasNotificationAccess()
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationGranted = context.hasNotificationAccess()
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = runCatching {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }.getOrElse {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
            settingsLauncher.launch(intent)
        } else {
            legacyStorageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    fun requestNotificationAccess() {
        val canRequest = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationRequested
        if (canRequest) {
            notificationRequested = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            settingsLauncher.launch(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            )
        }
    }

    fun completeWelcome() {
        if (reviewMode) {
            onReturn()
        } else {
            Configs.welcomeSeen = true
            onFinish()
        }
    }

    NPatchScaffold(
        bottomBar = {
            WelcomeBottomBar(
                page = pagerState.currentPage,
                reviewMode = reviewMode,
                permissionsReady = storageGranted && appListGranted,
                onBackOrSkip = {
                    if (reviewMode) {
                        onReturn()
                    } else {
                        Configs.welcomeSeen = true
                        onFinish()
                    }
                },
                onNext = {
                    if (pagerState.currentPage == 2) {
                        completeWelcome()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }
            )
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = false,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) { page ->
            when (page) {
                0 -> WelcomeIntroPage()
                1 -> WelcomePermissionPage(
                    storageGranted = storageGranted,
                    notificationGranted = notificationGranted,
                    appListGranted = appListGranted,
                    onStorageClick = ::requestStorageAccess,
                    onNotificationClick = ::requestNotificationAccess,
                    onAppListClick = {
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    }
                )
                else -> WelcomeDisclaimerPage()
            }
        }
    }
}

@Composable
private fun WelcomeIntroPage() {
    val versionLabel = stringResource(R.string.welcome_version, BuildConfig.VERSION_NAME)
    WelcomePageContainer {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = backgroundAwareCardColors(),
            showIndication = false,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = rememberVectorPainter(WelcomeLogo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(86.dp)
                        .clip(CircleShape)
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = COUITheme.textStyles.title1,
                    fontWeight = FontWeight.SemiBold,
                    color = COUITheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = versionLabel,
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.semantics {
                        contentDescription = versionLabel
                    }
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.welcome_intro_content),
                    style = COUITheme.textStyles.body1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.welcome_intro_detail),
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Start
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = backgroundAwareCardColors(),
            showIndication = false,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                LanguagePreference()
            }
        }
    }
}

@Composable
private fun WelcomePermissionPage(
    storageGranted: Boolean,
    notificationGranted: Boolean,
    appListGranted: Boolean,
    onStorageClick: () -> Unit,
    onNotificationClick: () -> Unit,
    onAppListClick: () -> Unit,
) {
    WelcomePageContainer {
        WelcomePageHeader(
            icon = Icons.Outlined.Security,
            title = stringResource(R.string.welcome_permission_title),
            summary = stringResource(R.string.welcome_permission_summary)
        )
        Spacer(Modifier.height(16.dp))
        PermissionStatusCard(
            icon = Icons.Outlined.Folder,
            title = stringResource(R.string.welcome_permission_storage_title),
            summary = stringResource(R.string.welcome_permission_storage_summary),
            granted = storageGranted,
            onClick = onStorageClick
        )
        Spacer(Modifier.height(12.dp))
        PermissionStatusCard(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.welcome_permission_notification_title),
            summary = stringResource(R.string.welcome_permission_notification_summary),
            granted = notificationGranted,
            onClick = onNotificationClick
        )
        Spacer(Modifier.height(12.dp))
        PermissionStatusCard(
            icon = Icons.Outlined.Apps,
            title = stringResource(R.string.welcome_permission_applist_title),
            summary = stringResource(R.string.welcome_permission_applist_summary),
            granted = appListGranted,
            onClick = onAppListClick
        )
        Spacer(Modifier.height(12.dp))
        OptionalFeatureCard()
        Spacer(Modifier.height(4.dp))
        SmallTitle(text = stringResource(R.string.welcome_basic_settings_title))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = backgroundAwareCardColors(),
            showIndication = false,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                AppearanceSettings()
                StorageDirectory()
            }
        }
    }
}

@Composable
private fun OptionalFeatureCard() {
    LaunchedEffect(Unit) {
        ShizukuApi.refreshState()
        ShizukuApi.addRequestPermissionResultListener(welcomeShizukuListener)
    }
    DisposableEffect(Unit) {
        onDispose {
            ShizukuApi.removeRequestPermissionResultListener(welcomeShizukuListener)
        }
    }

    val isGranted = ShizukuApi.isPermissionGranted
    val warningContainer = if (COUITheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFFE08A)
    } else {
        Color(0xFF5C4800)
    }
    val warningContent = if (COUITheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFF5A4300)
    } else {
        Color(0xFFFFF1BF)
    }
    val containerColor = if (isGranted) COUITheme.colorScheme.primaryContainer else warningContainer
    val contentColor = if (isGranted) COUITheme.colorScheme.onPrimaryContainer else warningContent
    val shizukuApiVersion = ShizukuApi.getVersionOrNull()
    val shizukuStatusDescription = shizukuApiVersion?.let {
        stringResource(R.string.home_api_version) + " $it"
    } ?: stringResource(R.string.home_shizuku_warning)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = backgroundAwareCardColors(
            color = containerColor,
            contentColor = contentColor
        ),
        showIndication = true,
        onClick = {
            if (ShizukuApi.isBinderAvailable && !isGranted) {
                ShizukuApi.requestPermission()
            }
        },
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isGranted) Icons.Outlined.CheckCircle else Icons.Outlined.Warning,
                contentDescription = null,
                tint = contentColor
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.welcome_optional_title),
                    style = COUITheme.textStyles.title3,
                    color = contentColor
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(if (isGranted) R.string.shizuku_available else R.string.shizuku_unavailable),
                    style = COUITheme.textStyles.body1,
                    color = contentColor.copy(alpha = 0.92f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = shizukuApiVersion?.let { "API $it" }
                        ?: stringResource(R.string.home_shizuku_warning),
                    style = COUITheme.textStyles.body2,
                    color = contentColor.copy(alpha = 0.82f),
                    modifier = Modifier.semantics {
                        contentDescription = shizukuStatusDescription
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.welcome_optional_summary),
                    style = COUITheme.textStyles.body2,
                    fontSize = 13.sp,
                    color = contentColor.copy(alpha = 0.9f)
                )
            }
        }
    }
}

@Composable
private fun WelcomeDisclaimerPage() {
    WelcomePageContainer {
        WelcomePageHeader(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.welcome_disclaimer_title),
            summary = stringResource(R.string.welcome_disclaimer_summary)
        )
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = backgroundAwareCardColors(),
            showIndication = false,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    text = stringResource(R.string.welcome_disclaimer_content),
                    style = COUITheme.textStyles.body1,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun WelcomePageContainer(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@Composable
private fun WelcomePageHeader(
    icon: ImageVector,
    title: String,
    summary: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(backgroundAwareColor(COUITheme.colorScheme.primaryContainer)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = COUITheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = COUITheme.textStyles.title2,
                fontWeight = FontWeight.SemiBold,
                color = COUITheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun PermissionStatusCard(
    icon: ImageVector,
    title: String,
    summary: String,
    granted: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = backgroundAwareCardColors(),
        showIndication = !granted,
        onClick = {
            if (!granted) onClick()
        }
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = COUITheme.colorScheme.primary
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = COUITheme.textStyles.title3,
                    color = COUITheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = summary,
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.onSurfaceVariantSummary
                )
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(R.string.welcome_permission_granted),
                    tint = COUITheme.colorScheme.primary
                )
            } else {
                Text(
                    text = stringResource(R.string.welcome_permission_authorize),
                    style = COUITheme.textStyles.body2,
                    color = COUITheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun WelcomeBottomBar(
    page: Int,
    reviewMode: Boolean,
    permissionsReady: Boolean,
    onBackOrSkip: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            text = stringResource(if (reviewMode) R.string.welcome_btn_return else R.string.welcome_btn_skip),
            onClick = onBackOrSkip
        )
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            enabled = page != 1 || permissionsReady,
            colors = ButtonDefaults.buttonColorsPrimary(),
            insideMargin = PaddingValues(horizontal = 22.dp, vertical = 13.dp)
        ) {
            Text(
                text = stringResource(if (page == 2) R.string.welcome_btn_finish else R.string.welcome_btn_next),
                style = COUITheme.textStyles.button
            )
        }
    }
}

private fun Context.hasStorageAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}

private fun Context.hasNotificationAccess(): Boolean {
    return NotificationManagerCompat.from(this).areNotificationsEnabled()
}

private fun Context.hasAppListAccessDeclaration(): Boolean {
    val permissions = packageManager
        .getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        .requestedPermissions
        .orEmpty()
    return Manifest.permission.QUERY_ALL_PACKAGES in permissions
}

/**
 * ウェルカム画面のロゴ（NPatch アイコン）。
 * painterResource はリリースビルド（R8 リソース最適化）で NPE を起こすため、
 * 元の ic_launcher_playstore.xml の pathData を PathParser でそのまま
 * ImageVector に変換して完全に再現する。
 */
private val WelcomeLogo: ImageVector by lazy {
    ImageVector.Builder(
        name = "WelcomeLogo",
        defaultWidth = 108.dp,
        defaultHeight = 108.dp,
        viewportWidth = 108f,
        viewportHeight = 108f,
    ).apply {
        // 背景（黄色）
        addPath(
            fill = SolidColor(Color(0xFFFED549)),
            pathData = PathParser().parsePathString(
                "M107.801 0H-0.1987V110.038H107.801V0Z"
            ).toNodes(),
        )
        // 薄黄色の円
        addPath(
            fill = SolidColor(Color(0xFFFFF2C8)),
            pathData = PathParser().parsePathString(
                "M53.7983 308.742C115.897 308.742 166.237 257.452 166.237 194.182C166.237 130.912 115.897 79.6213 53.7983 79.6213C-8.29998 79.6213 -58.6406 130.912 -58.6406 194.182C-58.6406 257.452 -8.29998 308.742 53.7983 308.742Z"
            ).toNodes(),
        )
        // 黒い文字
        addPath(
            fill = SolidColor(Color(0xFF000000)),
            pathData = PathParser().parsePathString(
                "M30.7787 100.519L32.4865 90.6702H34.3495L37.4005 97.3688H37.441L37.6975 95.6769L38.5682 90.6702H40.3367L38.6357 100.519H36.9145L33.769 93.5381H33.7285L33.472 95.2299L32.554 100.519H30.7787ZM40.6401 100.519L42.3478 90.6702H45.6891C46.7556 90.6702 47.5635 91.0072 48.1123 91.6812C48.6612 92.3505 48.8481 93.1873 48.6726 94.1914C48.5059 95.1723 48.0786 95.9314 47.3901 96.4678C46.7016 96.9995 45.8511 97.2656 44.8386 97.2656H42.2061L42.4828 95.6701H44.8993C45.4035 95.6701 45.8329 95.5325 46.1886 95.2574C46.5442 94.9823 46.767 94.5856 46.8568 94.0676C46.9378 93.5815 46.8589 93.171 46.6206 92.8366C46.3822 92.5018 45.9882 92.3345 45.4393 92.3345H43.8868L42.4761 100.519H40.6401ZM50.5588 100.739C49.8205 100.739 49.2535 100.507 48.8578 100.044C48.4616 99.5807 48.3245 99.0009 48.446 98.3041C48.5675 97.5931 48.9071 97.0455 49.4653 96.6604C50.0276 96.2705 50.7452 96.0758 51.6185 96.0758C51.9602 96.0758 52.307 96.1145 52.658 96.1927C53.009 96.2658 53.3014 96.3621 53.5355 96.4816L53.6165 96.0758C53.7017 95.64 53.6165 95.2892 53.36 95.0236C53.1035 94.7528 52.7297 94.6178 52.2395 94.6178C51.9514 94.6178 51.7016 94.6496 51.4903 94.7141C51.2785 94.7734 51.0827 94.856 50.903 94.9617C50.7275 95.067 50.5588 95.1861 50.3968 95.3193C50.2348 95.4474 50.1538 95.5119 50.1538 95.5119L49.3235 94.5009C49.3235 94.5009 49.4383 94.4089 49.6678 94.2258C49.8973 94.0423 50.158 93.8751 50.4508 93.7238C50.7478 93.5677 51.0671 93.4461 51.4093 93.3593C51.751 93.2673 52.145 93.2217 52.5905 93.2217C53.5714 93.2217 54.3139 93.4921 54.818 94.0333C55.3217 94.574 55.4951 95.2987 55.3378 96.2065L54.5885 100.519H52.874L53.1103 99.1844L53.2115 99.5902H52.9955C52.7162 99.9431 52.3631 100.223 51.9358 100.429C51.5126 100.636 51.0536 100.739 50.5588 100.739ZM51.2405 99.4664C51.7489 99.4664 52.2032 99.276 52.604 98.8955C53.009 98.5104 53.2676 98.0402 53.3803 97.4857V97.4788C53.2183 97.3688 53.009 97.2768 52.7525 97.2037C52.496 97.1302 52.2125 97.0937 51.902 97.0937C51.416 97.0937 51.0178 97.1968 50.7073 97.4032C50.3968 97.6095 50.2078 97.9005 50.1403 98.2766C50.0816 98.6295 50.1512 98.9162 50.3495 99.1363C50.552 99.3563 50.849 99.4664 51.2405 99.4664ZM58.7693 100.739C58.0673 100.739 57.545 100.535 57.2033 100.127C56.8612 99.7183 56.7532 99.1522 56.8793 98.4279L58.074 91.523H59.8628L58.7018 98.256C58.6432 98.5998 58.6702 98.8517 58.7828 99.0125C58.8997 99.1728 59.0908 99.2532 59.3565 99.2532C59.4464 99.2532 59.5455 99.2437 59.6535 99.2257C59.7658 99.2072 59.8898 99.1749 60.0248 99.1294C60.164 99.0834 60.234 99.0606 60.234 99.0606L59.9775 100.525C59.9775 100.525 59.9075 100.548 59.7683 100.594C59.6333 100.64 59.4755 100.674 59.2958 100.697C59.1157 100.725 58.9402 100.739 58.7693 100.739ZM56.4135 94.886L56.6633 93.4487H61.1925L60.9428 94.886H56.4135ZM64.4849 100.739C63.3597 100.739 62.5025 100.369 61.9131 99.6314C61.328 98.893 61.1343 97.9508 61.3326 96.8048C61.517 95.7363 61.9848 94.8723 62.7366 94.2121C63.488 93.5518 64.379 93.2217 65.4096 93.2217C65.9496 93.2217 66.4086 93.3043 66.7866 93.4693C67.1688 93.6296 67.4928 93.8566 67.7586 94.1502C68.0286 94.4433 68.2556 94.831 68.4404 95.3124C68.4357 95.3124 68.4336 95.3124 68.4336 95.3124C68.4378 95.3124 68.4404 95.3124 68.4404 95.3124L66.8744 95.9108C66.7482 95.6034 66.62 95.3786 66.4896 95.2368C66.3635 95.0945 66.2036 94.9823 66.0104 94.8998C65.8214 94.8173 65.5961 94.776 65.3354 94.776C64.7819 94.776 64.2933 94.9755 63.8706 95.3743C63.4521 95.7732 63.1889 96.2959 63.0809 96.9424C62.9636 97.5931 63.0539 98.1296 63.3509 98.5517C63.6521 98.9733 64.0908 99.1844 64.6671 99.1844C64.9371 99.1844 65.1708 99.1453 65.3691 99.0675C65.567 98.9892 65.7539 98.8771 65.9294 98.7305C66.1049 98.5835 66.2981 98.3497 66.5099 98.029L67.9476 98.6755C67.673 99.1749 67.376 99.5648 67.0566 99.8446C66.7368 100.12 66.3614 100.337 65.9294 100.498C65.4974 100.658 65.0156 100.739 64.4849 100.739ZM68.677 100.519L70.3848 90.6702H72.1735L71.5053 94.4803H71.5458C71.8116 94.0952 72.1558 93.79 72.5785 93.5656C73.0017 93.3361 73.4586 93.2217 73.9488 93.2217C74.6824 93.2217 75.2427 93.4715 75.6295 93.9714C76.021 94.4665 76.1472 95.1104 76.0075 95.9039L75.211 100.519H73.4155L74.1513 96.289C74.2323 95.8119 74.1783 95.4384 73.9893 95.168C73.8049 94.8929 73.4965 94.7554 73.0645 94.7554C72.6013 94.7554 72.1896 94.9204 71.8293 95.2506C71.4741 95.5807 71.2488 96.0139 71.1543 96.5504L70.4725 100.519H68.677Z"
            ).toNodes(),
        )
        // 白い Play ボタン
        addPath(
            fill = SolidColor(Color.White),
            pathData = PathParser().parsePathString(
                "M53.8013 57.3026L43.9138 67.3766C42.903 68.4065 41.6724 68.9214 40.2222 68.9214C38.772 68.9214 37.5416 68.4065 36.5308 67.3766L29.0162 59.7204C28.0055 58.6905 27.5001 57.4367 27.5001 55.9593C27.5001 54.4816 28.0055 53.2282 29.0162 52.1983L38.9039 42.1239L29.0162 32.0496C28.0055 31.0198 27.5001 29.7661 27.5001 28.2886C27.5001 26.811 28.0055 25.5573 29.0162 24.5275L36.5308 16.8711C37.5416 15.8413 38.772 15.3264 40.2222 15.3264C41.6724 15.3264 42.903 15.8413 43.9138 16.8711L53.8013 26.9454L63.6892 16.8711C64.6996 15.8413 65.9302 15.3264 67.3806 15.3264C68.8306 15.3264 70.0612 15.8413 71.072 16.8711L78.5865 24.5275C79.5973 25.5573 80.1027 26.811 80.1027 28.2886C80.1027 29.7661 79.5973 31.0198 78.5865 32.0496L68.699 42.1239L78.5865 52.1983C79.5973 53.2282 80.1027 54.4816 80.1027 55.9593C80.1027 57.4367 79.5973 58.6905 78.5865 59.7204L71.072 67.3766C70.0612 68.4065 68.8306 68.9214 67.3806 68.9214C65.9302 68.9214 64.6996 68.4065 63.6892 67.3766L53.8013 57.3026ZM55.6812 38.6664C56.1857 38.1506 56.438 37.5121 56.438 36.751C56.438 35.9898 56.1849 35.3522 55.6786 34.8382C55.1724 34.3242 54.5467 34.0663 53.8013 34.0645C53.0558 34.0627 52.4302 34.3206 51.924 34.8382C51.4177 35.3558 51.1646 35.9934 51.1646 36.751C51.1646 37.5085 51.4177 38.147 51.924 38.6664C52.4302 39.1858 53.0558 39.4428 53.8013 39.4374C54.5467 39.4321 55.1732 39.1768 55.6812 38.6664ZM42.5955 38.3628L50.1099 30.7064L40.2222 20.6322L32.7076 28.2886L42.5955 38.3628ZM48.5279 44.8103C49.275 44.8103 49.9015 44.5532 50.4077 44.0392C50.914 43.5255 51.1663 42.8869 51.1646 42.1239C51.1629 41.3609 50.9098 40.7233 50.4052 40.2111C49.9006 39.6989 49.275 39.441 48.5279 39.4374C47.7807 39.4338 47.1551 39.6917 46.6505 40.2111C46.146 40.7305 45.8928 41.3681 45.8911 42.1239C45.8895 42.8797 46.1426 43.5182 46.6505 44.0392C47.1585 44.5605 47.7845 44.8176 48.5279 44.8103ZM55.6812 49.4121C56.1857 48.8963 56.438 48.258 56.438 47.4967C56.438 46.7355 56.1849 46.0981 55.6786 45.584C55.1724 45.0699 54.5467 44.812 53.8013 44.8103C53.0558 44.8086 52.4302 45.0665 51.924 45.584C51.4177 46.1015 51.1646 46.7394 51.1646 47.4967C51.1646 48.2545 51.4177 48.8929 51.924 49.4121C52.4302 49.9318 53.0558 50.1888 53.8013 50.1832C54.5467 50.1781 55.1732 49.9227 55.6812 49.4121ZM59.0747 44.8103C59.8219 44.8103 60.4484 44.5524 60.9546 44.0366C61.4609 43.5208 61.7131 42.8832 61.7115 42.1239C61.7098 41.3645 61.4566 40.7269 60.9521 40.2111C60.4475 39.6953 59.8219 39.4374 59.0747 39.4374C58.3276 39.4374 57.702 39.6953 57.1974 40.2111C56.6928 40.7269 56.4397 41.3645 56.438 42.1239C56.4363 42.8832 56.6895 43.5216 57.1974 44.0392C57.7053 44.5571 58.3314 44.8141 59.0747 44.8103ZM57.4927 53.5415L67.3806 63.6156L74.895 55.9593L65.0076 45.8849L57.4927 53.5415Z"
            ).toNodes(),
        )
    }.build()
}
