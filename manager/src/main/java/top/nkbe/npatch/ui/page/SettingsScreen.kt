package top.nkbe.npatch.ui.page

import android.app.Activity
import android.content.Intent
import android.text.format.Formatter
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.SettingsBrightness
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nkbe.util.ModulePipeline
import nkbe.util.NeoPackageManager
import top.nkbe.npatch.LSPApplication
import top.nkbe.npatch.R
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.config.KeystorePreset
import top.nkbe.npatch.config.MyKeyStore
import top.nkbe.npatch.config.ThemeConfig
import top.nkbe.npatch.config.ThemeMode
import top.nkbe.npatch.config.ThemeSettings
import top.nkbe.npatch.config.dataStore
import top.nkbe.npatch.config.DEFAULT_CUSTOM_COLOR
import top.nkbe.npatch.database.entity.Module
import top.nkbe.npatch.network.proxy.ApkProxyService
import top.nkbe.npatch.ui.activity.MainActivity
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.AppUpdatePreferences
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.nkbe.npatch.util.LINE_PACKAGE_NAME
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.component.GlassCard
import top.nkbe.npatch.ui.component.GlassDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore

private const val TAG = "SettingsScreen"

/** スコープ登録できるインストール済み Xposed モジュール */
private data class InstalledModule(
    val packageName: String,
    val apkPath: String,
    val displayName: String,
    val version: String,
    val enabled: Boolean,
)

@Composable
fun SettingsScreen() {
    val scrollBehavior = MiuixScrollBehavior()
    NPatchScaffold(
        topBar = {
            NPatchTopAppBar(
                title = stringResource(R.string.screen_settings),
                scrollBehavior = scrollBehavior
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SmallTitle(text = stringResource(R.string.settings_appearance_theme))
                    AppearanceSettings()
                }
            }
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SmallTitle(text = stringResource(R.string.settings_modules))
                    ModuleSettings()
                }
            }
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    SmallTitle(text = stringResource(R.string.settings_other_settings))
                    AppUpdatePreferences()
                    ProxyVersionSettings()
                    ProxyCachePreference()
                    LanguagePreference()
                    KeyStore()
                    DetailPatchLogs()
                    WelcomeGuide()
                    StorageDirectory()
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * インストール済み Xposed モジュールを列挙し、スコープ登録を切り替える。
 *
 * NeoPackageManager.appList（HomeScreen がすでにロード済み）を直接参照することで
 * getInstalledPackages の二重呼び出しを廃止。appList は mutableStateOf なので
 * リストが更新されると Compose が自動的にリコンポーズする。
 */
@Composable
private fun ModuleSettings() {
    val scope = rememberCoroutineScope()

    // NeoPackageManager.appList は object レベルの mutableStateOf。
    // HomeScreen がロードした後はここでもそのまま参照できる。
    val appList = NeoPackageManager.appList
    val appsReady = appList.isNotEmpty()

    // スコープ登録されているモジュールのパッケージ名セット
    var scopedPackages by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(Unit) {
        scopedPackages = ConfigManager.getModulesForApp(LINE_PACKAGE_NAME)
            .map { it.pkgName }.toSet()
    }

    // appList から Xposed モジュールだけをフィルタして派生させる。
    // getInstalledPackages を呼ばないので高速。
    val modules = remember(appList, scopedPackages) {
        appList
            .filter { info ->
                info.isXposedModule && info.app.packageName != LINE_PACKAGE_NAME
            }
            .mapNotNull { info ->
                val meta = info.moduleMetadata ?: return@mapNotNull null
                if (meta.pipeline == ModulePipeline.UNSUPPORTED) return@mapNotNull null
                InstalledModule(
                    packageName = info.app.packageName,
                    apkPath = info.app.sourceDir,
                    displayName = meta.displayName.ifEmpty { info.label },
                    version = meta.version,
                    enabled = info.app.packageName in scopedPackages,
                )
            }
            .sortedBy { it.displayName }
    }

    if (!appsReady) {
        // HomeScreen がアプリ一覧をまだロード中
        Text(
            text = stringResource(R.string.manage_loading),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        return
    }

    if (modules.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_modules_empty),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        return
    }

    modules.forEach { module ->
        SwitchPreference(
            title = module.displayName,
            summary = module.packageName + if (module.version.isNotEmpty()) " · v${module.version}" else "",
            checked = module.enabled,
            startAction = {
                SettingsStartIcon(Icons.Outlined.Extension)
            },
            onCheckedChange = { isChecked ->
                scope.launch {
                    if (isChecked) {
                        ConfigManager.activateModule(
                            LINE_PACKAGE_NAME,
                            Module(module.packageName, module.apkPath)
                        )
                    } else {
                        ConfigManager.deactivateModule(
                            LINE_PACKAGE_NAME,
                            Module(module.packageName, module.apkPath)
                        )
                    }
                    // スコープ変更後に登録リストを再取得
                    scopedPackages = ConfigManager.getModulesForApp(LINE_PACKAGE_NAME)
                        .map { it.pkgName }.toSet()
                }
            }
        )
    }
}

@Composable
fun AppearanceSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val themeState by ThemeConfig.getThemeFlow(context).collectAsState(
        initial = ThemeSettings(
            backgroundImageUri = "",
            useMonet = false,
            customColor = DEFAULT_CUSTOM_COLOR,
            themeMode = ThemeMode.SYSTEM,
            amoledBlack = false,
            cardBackgroundAlphaPercent = 60,
        )
    )
    val useMonet = themeState.useMonet
    val amoledBlack = themeState.amoledBlack

    val themeModeItems = listOf(
        stringResource(R.string.settings_theme_mode_system),
        stringResource(R.string.settings_theme_mode_light),
        stringResource(R.string.settings_theme_mode_dark)
    )
    val themeModeIndex = when (themeState.themeMode) {
        ThemeMode.SYSTEM -> 0
        ThemeMode.LIGHT -> 1
        ThemeMode.DARK -> 2
    }

    OverlayDropdownPreference(
        title = stringResource(R.string.settings_theme_mode),
        items = themeModeItems,
        selectedIndex = themeModeIndex,
        startAction = {
            SettingsStartIcon(Icons.Outlined.SettingsBrightness)
        },
        onSelectedIndexChange = { index ->
            val mode = when (index) {
                1 -> ThemeMode.LIGHT
                2 -> ThemeMode.DARK
                else -> ThemeMode.SYSTEM
            }
            scope.launch {
                context.dataStore.edit { it[ThemeConfig.THEME_MODE] = mode.value }
            }
        }
    )

    SwitchPreference(
        title = stringResource(R.string.settings_monet_dynamic_color),
        summary = stringResource(R.string.settings_monet_dynamic_color_summary),
        checked = useMonet,
        startAction = {
            SettingsStartIcon(Icons.Outlined.Palette)
        },
        onCheckedChange = { isChecked ->
            scope.launch { context.dataStore.edit { it[ThemeConfig.USE_MONET] = isChecked } }
        }
    )

    SwitchPreference(
        title = stringResource(R.string.home_appearance_amoled),
        summary = stringResource(R.string.home_appearance_amoled_summary),
        checked = amoledBlack,
        startAction = { SettingsStartIcon(Icons.Outlined.DarkMode) },
        onCheckedChange = { isChecked ->
            scope.launch { context.dataStore.edit { it[ThemeConfig.AMOLED_BLACK] = isChecked } }
        },
    )
}

@Composable
private fun SettingsStartIcon(imageVector: ImageVector) {
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            tint = MiuixTheme.colorScheme.primary
        )
    }
}

internal val LANGUAGE_ENTRIES = listOf(
    "" to "settings_language_system",
    "en" to "English",
    "zh-CN" to "中文 (简体)",
    "zh-MO" to "中文 (喵喵)",
    "zh-TW" to "中文 (繁體)",
    "zh-HK" to "中文 (香港)",
    "ja" to "日本語",
    "ko" to "한국어",
    "fr" to "Français",
    "de" to "Deutsch",
    "es" to "Español",
    "it" to "Italiano",
    "pt" to "Português",
    "pt-BR" to "Português (Brasil)",
    "ru" to "Русский",
    "ar" to "العربية",
    "tr" to "Türkçe",
    "nl" to "Nederlands",
    "pl" to "Polski",
    "uk" to "Українська",
    "vi" to "Tiếng Việt",
    "th" to "ภาษาไทย",
    "hi" to "हिन्दी",
    "af" to "Afrikaans",
    "bg" to "Български",
    "bn" to "বাংলা",
    "ca" to "Català",
    "cs" to "Čeština",
    "da" to "Dansk",
    "el" to "Ελληνικά",
    "et" to "Eesti",
    "fa" to "فارسی",
    "fi" to "Suomi",
    "hr" to "Hrvatski",
    "hu" to "Magyar",
    "in" to "Bahasa Indonesia",
    "iw" to "עברית",
    "ku" to "Kurdî",
    "lt" to "Lietuvių",
    "no" to "Norsk",
    "ro" to "Română",
    "si" to "සිංහල",
    "sk" to "Slovenčina",
    "sv" to "Svenska",
    "ur" to "اردو",
)

@Composable
fun LanguagePreference() {
    val context = LocalContext.current
    val systemLabel = stringResource(R.string.settings_language_system)
    val languageLabels = remember(systemLabel) {
        LANGUAGE_ENTRIES.map { (_, label) -> if (label == "settings_language_system") systemLabel else label }
    }
    var selectedIndex by remember {
        mutableStateOf(
            LANGUAGE_ENTRIES.indexOfFirst {
                LSPApplication.normalizeLanguageTag(it.first) ==
                    LSPApplication.normalizeLanguageTag(Configs.language)
            }.takeIf { it >= 0 } ?: 0
        )
    }
    OverlayDropdownPreference(
        title = stringResource(R.string.settings_language),
        items = languageLabels,
        selectedIndex = selectedIndex,
        startAction = {
            SettingsStartIcon(Icons.Outlined.Language)
        },
        onSelectedIndexChange = { index ->
            selectedIndex = index
            val tag = LSPApplication.normalizeLanguageTag(LANGUAGE_ENTRIES[index].first)
            Configs.language = tag
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            (context as? Activity)?.finish()
        }
    )
}

@Composable
private fun KeyStore() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showDialog = remember { mutableStateOf(false) }
    val currentPreset = Configs.keyStorePreset

    val keyStoreItems = listOf(
        "NPatch",
        "FPA",
        stringResource(R.string.settings_keystore_custom)
    )
    var selectedIndex by remember { mutableStateOf(keyStorePresetIndex(currentPreset)) }
    LaunchedEffect(currentPreset) {
        selectedIndex = keyStorePresetIndex(currentPreset)
    }

    OverlayDropdownPreference(
        title = stringResource(R.string.settings_keystore),
        items = keyStoreItems,
        selectedIndex = selectedIndex,
        startAction = {
            SettingsStartIcon(Icons.Outlined.Key)
        },
        onSelectedIndexChange = { index ->
            selectedIndex = index
            if (index == 0) {
                scope.launch { MyKeyStore.reset() }
            } else if (index == 1) {
                scope.launch { MyKeyStore.setBuiltinFpa() }
            } else {
                showDialog.value = true
            }
        }
    )

    if (showDialog.value) {
        var wrongKeystore by rememberSaveable { mutableStateOf(false) }
        var wrongPassword by rememberSaveable { mutableStateOf(false) }
        var wrongAliasName by rememberSaveable { mutableStateOf(false) }
        var wrongAliasPassword by rememberSaveable { mutableStateOf(false) }

        var path by rememberSaveable { mutableStateOf("") }
        var password by rememberSaveable { mutableStateOf("") }
        var alias by rememberSaveable { mutableStateOf("") }
        var aliasPassword by rememberSaveable { mutableStateOf("") }

        val dismissDialog = {
            showDialog.value = false
            selectedIndex = keyStorePresetIndex(Configs.keyStorePreset)
        }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            context.contentResolver.openInputStream(uri).use { input ->
                MyKeyStore.tmpFile.outputStream().use { output ->
                    input?.copyTo(output)
                }
            }
            path = uri.path ?: ""
        }

        val interactionSource = remember { MutableInteractionSource() }
        LaunchedEffect(interactionSource) {
            interactionSource.interactions.collect { interaction ->
                if (interaction is PressInteraction.Release) {
                    launcher.launch("*/*")
                }
            }
        }

        GlassDialog(
            title = stringResource(R.string.settings_keystore_dialog_title),
            show = showDialog.value,
            onDismissRequest = {
                dismissDialog()
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val wrongText = when {
                    wrongAliasPassword -> stringResource(R.string.settings_keystore_wrong_alias_password)
                    wrongAliasName -> stringResource(R.string.settings_keystore_wrong_alias)
                    wrongPassword -> stringResource(R.string.settings_keystore_wrong_password)
                    wrongKeystore -> stringResource(R.string.settings_keystore_wrong_keystore)
                    else -> null
                }

                Text(
                    modifier = Modifier.padding(bottom = 8.dp),
                    text = wrongText ?: stringResource(R.string.settings_keystore_desc),
                    color = if (wrongText != null) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    textAlign = TextAlign.Center
                )

                TextField(
                    value = path,
                    onValueChange = {},
                    label = stringResource(R.string.settings_keystore_file),
                    modifier = Modifier.fillMaxWidth(),
                    readOnly = true,
                    interactionSource = interactionSource
                )
                TextField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.settings_keystore_password),
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = stringResource(R.string.settings_keystore_alias),
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = aliasPassword,
                    onValueChange = { aliasPassword = it },
                    label = stringResource(R.string.settings_keystore_alias_password),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = stringResource(android.R.string.cancel),
                        onClick = dismissDialog,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(android.R.string.ok),
                        onClick = {
                            wrongKeystore = false
                            wrongPassword = false
                            wrongAliasName = false
                            wrongAliasPassword = false

                            if (path.isEmpty()) {
                                wrongKeystore = true
                                return@TextButton
                            }
                            val keyStore = KeyStore.getInstance("BKS")
                            try {
                                MyKeyStore.tmpFile.inputStream().use { input ->
                                    keyStore.load(input, password.toCharArray())
                                }
                            } catch (e: IOException) {
                                wrongKeystore = true
                                if (e.message == "KeyStore integrity check failed.") {
                                    wrongPassword = true
                                }
                                return@TextButton
                            }
                            if (!keyStore.containsAlias(alias)) {
                                wrongAliasName = true
                                return@TextButton
                            }
                            try {
                                keyStore.getKey(alias, aliasPassword.toCharArray())
                            } catch (e: GeneralSecurityException) {
                                wrongAliasPassword = true
                                return@TextButton
                            }

                            scope.launch { MyKeyStore.setCustom(password, alias, aliasPassword) }
                            showDialog.value = false
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(),
                    )
                }
            }
        }
    }
}

private fun keyStorePresetIndex(preset: KeystorePreset): Int {
    return when (preset) {
        KeystorePreset.NPATCH -> 0
        KeystorePreset.FPA -> 1
        KeystorePreset.CUSTOM -> 2
    }
}

@Composable
private fun DetailPatchLogs() {
    SwitchPreference(
        title = stringResource(R.string.settings_detail_patch_logs),
        startAction = {
            SettingsStartIcon(Icons.Outlined.BugReport)
        },
        checked = Configs.detailPatchLogs,
        onCheckedChange = { Configs.detailPatchLogs = it }
    )
}

@Composable
fun StorageDirectory() {
    val context = LocalContext.current
    val snackbarHost = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val errorText = stringResource(R.string.patch_select_dir_error)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        try {
            if (it.resultCode == Activity.RESULT_CANCELED) return@rememberLauncherForActivityResult
            val uri = it.data?.data ?: throw IOException("No data")
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            Configs.storageDirectory = uri.toString()
            Log.i(TAG, "Storage directory: ${uri.path}")
        } catch (e: Exception) {
            Log.e(TAG, "Error when requesting saving directory", e)
            scope.launch { snackbarHost.showSnackbar(errorText) }
        }
    }
    ArrowPreference(
        title = stringResource(R.string.settings_storage_directory),
        summary = Configs.storageDirectory ?: "no path set",
        startAction = {
            SettingsStartIcon(Icons.Outlined.Folder)
        },
        onClick = { launcher.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
    )
}

@Composable
private fun WelcomeGuide() {
    val navigator = LocalNavigator.current
    ArrowPreference(
        title = stringResource(R.string.settings_view_welcome),
        summary = stringResource(R.string.settings_view_welcome_summary),
        startAction = {
            SettingsStartIcon(Icons.Outlined.Info)
        },
        onClick = { navigator.push(Route.Welcome(reviewMode = true)) }
    )
}

@Composable
private fun ProxyVersionSettings() {
    val showEditDialog = remember { mutableStateOf(false) }
    var tempVersionCode by remember { mutableStateOf(Configs.customLineVersionCode) }

    SwitchPreference(
        title = stringResource(R.string.settings_include_prerelease),
        summary = stringResource(R.string.settings_include_prerelease_summary),
        startAction = {
            SettingsStartIcon(Icons.Outlined.NewReleases)
        },
        checked = Configs.includePrereleaseVersions,
        onCheckedChange = { Configs.includePrereleaseVersions = it }
    )

    SwitchPreference(
        title = stringResource(R.string.settings_custom_line_version),
        summary = if (Configs.useCustomLineVersion && Configs.customLineVersionCode.isNotBlank()) {
            stringResource(R.string.settings_custom_line_version_summary_active, Configs.customLineVersionCode)
        } else {
            stringResource(R.string.settings_custom_line_version_summary)
        },
        startAction = {
            SettingsStartIcon(Icons.Outlined.Numbers)
        },
        checked = Configs.useCustomLineVersion,
        onCheckedChange = {
            Configs.useCustomLineVersion = it
            if (it && Configs.customLineVersionCode.isBlank()) {
                tempVersionCode = Configs.customLineVersionCode
                showEditDialog.value = true
            }
        }
    )

    if (Configs.useCustomLineVersion) {
        ArrowPreference(
            title = stringResource(R.string.settings_custom_line_version_code),
            summary = Configs.customLineVersionCode.ifEmpty { stringResource(R.string.settings_custom_line_version_not_set) },
            startAction = {
                SettingsStartIcon(Icons.Outlined.Edit)
            },
            onClick = {
                tempVersionCode = Configs.customLineVersionCode
                showEditDialog.value = true
            }
        )
    }

    GlassDialog(
        title = stringResource(R.string.settings_custom_line_version_dialog_title),
        show = showEditDialog.value,
        onDismissRequest = { showEditDialog.value = false },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_custom_line_version_dialog_desc),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            TextField(
                value = tempVersionCode,
                onValueChange = { newStr: String ->
                    tempVersionCode = newStr.filter { char -> char.isDigit() }
                },
                label = stringResource(R.string.settings_custom_line_version_code),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { showEditDialog.value = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        Configs.customLineVersionCode = tempVersionCode.trim()
                        showEditDialog.value = false
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(),
                )
            }
        }
    }
}

@Composable
private fun ProxyCachePreference() {
    val context = LocalContext.current
    val showConfirmDialog = remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var cacheSizeBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(refreshKey) {
        cacheSizeBytes = withContext(Dispatchers.IO) {
            ApkProxyService.cacheSizeBytes(context)
        }
    }

    ArrowPreference(
        title = stringResource(R.string.settings_proxy_cache),
        summary = if (cacheSizeBytes > 0) {
            stringResource(
                R.string.settings_proxy_cache_summary,
                Formatter.formatFileSize(context, cacheSizeBytes),
            )
        } else {
            stringResource(R.string.settings_proxy_cache_empty)
        },
        startAction = {
            SettingsStartIcon(Icons.Outlined.Delete)
        },
        onClick = { showConfirmDialog.value = true }
    )

    val scope = rememberCoroutineScope()
    GlassDialog(
        title = stringResource(R.string.settings_proxy_cache_clear_title),
        show = showConfirmDialog.value,
        onDismissRequest = { showConfirmDialog.value = false },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_proxy_cache_clear_desc),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = stringResource(android.R.string.cancel),
                    onClick = { showConfirmDialog.value = false },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(android.R.string.ok),
                    onClick = {
                        showConfirmDialog.value = false
                        scope.launch {
                            withContext(Dispatchers.IO) { ApkProxyService.clearCache(context) }
                            refreshKey++
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(),
                )
            }
        }
    }
}

