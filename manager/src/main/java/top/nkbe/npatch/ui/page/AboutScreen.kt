package top.nkbe.npatch.ui.page

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.R
import top.nkbe.npatch.share.LSPConfig
import top.nkbe.npatch.ui.component.GlassCard
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.util.LocalSnackbarHost
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = LocalSnackbarHost.current
    val scrollBehavior = MiuixScrollBehavior()
    val config = LSPConfig.instance
    val unknown = stringResource(R.string.about_unknown)
    val appDetails = listOf(
        stringResource(R.string.about_framework_name) to LSPConfig.FRAMEWORK_NAME,
        stringResource(R.string.about_manager_version) to "${config.VERSION_NAME} (${config.VERSION_CODE})",
        stringResource(R.string.about_core_version) to "${config.CORE_VERSION_NAME} (${config.CORE_VERSION_CODE})",
        stringResource(R.string.home_api_version) to config.API_CODE.toString(),
        stringResource(R.string.about_package) to BuildConfig.APPLICATION_ID,
        stringResource(R.string.about_build_type) to BuildConfig.BUILD_TYPE,
    )
    val androidVersion = buildString {
        append("${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.PREVIEW_SDK_INT > 0) {
            append(" / ${Build.VERSION.CODENAME} Preview ${Build.VERSION.PREVIEW_SDK_INT}")
        }
    }
    val deviceDetails = listOf(
        stringResource(R.string.about_model) to "${Build.MANUFACTURER} ${Build.MODEL}",
        stringResource(R.string.about_device_code) to "${Build.DEVICE} / ${Build.PRODUCT}",
        stringResource(R.string.about_android_version) to androidVersion,
        stringResource(R.string.about_security_patch) to Build.VERSION.SECURITY_PATCH.ifBlank { unknown },
        stringResource(R.string.about_abis) to Build.SUPPORTED_ABIS.joinToString(", "),
        stringResource(R.string.about_process_architecture) to stringResource(
            if (Process.is64Bit()) R.string.about_process_64_bit else R.string.about_process_32_bit
        ),
        stringResource(R.string.about_android_build) to Build.DISPLAY,
        stringResource(R.string.about_build_fingerprint) to Build.FINGERPRINT,
        stringResource(R.string.about_kernel) to (System.getProperty("os.version") ?: unknown),
    )
    val copiedMessage = stringResource(R.string.home_info_copied)
    val appTitle = stringResource(R.string.about_app_details)
    val deviceTitle = stringResource(R.string.about_device_details)

    NPatchScaffold(
        topBar = {
            NPatchTopAppBar(
                title = stringResource(R.string.settings_about),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.patch_return))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val details = listOf(appTitle to appDetails, deviceTitle to deviceDetails)
                            .joinToString("\n\n") { (title, rows) ->
                                title + "\n" + rows.joinToString("\n") { (label, value) -> "$label: $value" }
                            }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(appTitle, details))
                        scope.launch { snackbarHost.showSnackbar(copiedMessage) }
                    }) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.about_copy_details))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { AboutDetailsSection(appTitle, appDetails) }
            item { AboutDetailsSection(deviceTitle, deviceDetails) }
        }
    }
}

@Composable
private fun AboutDetailsSection(title: String, details: List<Pair<String, String>>) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(vertical = 16.dp)) {
            SmallTitle(text = title)
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    details.forEach { (label, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = label,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                            Text(
                                text = value,
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
