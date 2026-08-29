package top.nkbe.npatch.ui.page.newpatch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Output
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.nkbe.npatch.R
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import io.github.suqi8.coui.kmp.basic.DropdownEntry
import io.github.suqi8.coui.kmp.basic.DropdownItem
import io.github.suqi8.coui.kmp.basic.FloatingActionButton
import io.github.suqi8.coui.kmp.basic.Icon
import io.github.suqi8.coui.kmp.basic.IconButton
import io.github.suqi8.coui.kmp.basic.ScrollBehavior
import io.github.suqi8.coui.kmp.basic.SmallTitle
import io.github.suqi8.coui.kmp.basic.Text
import io.github.suqi8.coui.kmp.basic.TextField
import io.github.suqi8.coui.kmp.preference.OverlayDropdownPreference
import io.github.suqi8.coui.kmp.preference.SwitchPreference
import io.github.suqi8.coui.kmp.theme.COUITheme

@Composable
fun ConfiguringTopBar(scrollBehavior: ScrollBehavior, onBackClick: () -> Unit) {
    NPatchTopAppBar(
        title = stringResource(R.string.screen_new_patch),
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
            }
        }
    )
}

@Composable
fun ConfiguringFab() {
    val viewModel = viewModel<NewPatchViewModel>()
    FloatingActionButton(
        onClick = { viewModel.dispatch(ViewAction.SubmitPatch) }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoFixHigh,
                contentDescription = null,
                tint = COUITheme.colorScheme.onPrimary
            )
            Text(
                text = stringResource(R.string.patch_start),
                color = COUITheme.colorScheme.onPrimary
            )
        }
    }
}

@Composable
fun sigBypassLvTitle(level: Int): String {
    return when (level) {
        0 -> stringResource(R.string.patch_sigbypasslv0)
        1 -> stringResource(R.string.patch_sigbypasslv1)
        2 -> stringResource(R.string.patch_sigbypasslv2)
        else -> error("Invalid sigBypassLv: $level")
    }
}

@Composable
fun sigBypassLvDesc(level: Int): String {
    return when (level) {
        0 -> stringResource(R.string.patch_sigbypasslv0_desc)
        1 -> stringResource(R.string.patch_sigbypasslv1_desc)
        2 -> stringResource(R.string.patch_sigbypasslv2_desc)
        else -> error("Invalid sigBypassLv: $level")
    }
}

/**
 * パッチオプション画面。
 * 署名バイパス・MicroG（ベンダー指定込み）・デバッグなどの詳細設定を提供する。
 */
@Composable
fun PatchOptionsBody(modifier: Modifier) {
    val viewModel = viewModel<NewPatchViewModel>()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 84.dp)
    ) {
        // ── アプリ情報 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(text = viewModel.patchApp.label, style = COUITheme.textStyles.headline1)
            Text(
                text = viewModel.patchApp.app.packageName,
                style = COUITheme.textStyles.body2,
                color = COUITheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        // ── 詳細設定 ──
        SmallTitle(text = stringResource(R.string.patch_advanced))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .padding(bottom = 16.dp),
        ) {
            Column(Modifier.padding(vertical = 4.dp)) {
                val maxSigBypassLevel = Constants.SIGBYPASS_HIGH
                val sigBypassEntries = listOf(
                    DropdownEntry(
                        items = (Constants.SIGBYPASS_NONE..maxSigBypassLevel).map { level ->
                            DropdownItem(
                                text = sigBypassLvTitle(level),
                                summary = sigBypassLvDesc(level),
                                selected = viewModel.sigBypassLevel == level,
                                onClick = {
                                    viewModel.sigBypassLevel = level
                                    if (level == Constants.SIGBYPASS_NONE) {
                                        viewModel.hideLibs = false
                                    }
                                }
                            )
                        }
                    )
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.patch_sigbypass),
                    startAction = { Icon(Icons.Outlined.Security, null) },
                    entries = sigBypassEntries
                )
                SwitchPreference(
                    title = stringResource(R.string.patch_hide_libs),
                    summary = stringResource(R.string.patch_hide_libs_desc),
                    startAction = { Icon(Icons.Outlined.VisibilityOff, null) },
                    checked = viewModel.hideLibs &&
                        viewModel.sigBypassLevel > Constants.SIGBYPASS_NONE,
                    onCheckedChange = {
                        viewModel.hideLibs =
                            it && viewModel.sigBypassLevel > Constants.SIGBYPASS_NONE
                    }
                )
                SwitchPreference(
                    title = stringResource(R.string.patch_use_microg),
                    summary = stringResource(R.string.patch_use_microg_desc),
                    startAction = { Icon(Icons.Outlined.CloudSync, null) },
                    checked = viewModel.useMicroG,
                    onCheckedChange = { viewModel.useMicroG = it }
                )
                if (viewModel.useMicroG) {
                    MicrogVendorPreference()
                }
                SwitchPreference(
                    title = stringResource(R.string.patch_debuggable),
                    startAction = { Icon(Icons.Outlined.BugReport, null) },
                    checked = viewModel.debuggable,
                    onCheckedChange = { viewModel.debuggable = it }
                )
                SwitchPreference(
                    title = stringResource(R.string.patch_override_version_code),
                    summary = stringResource(R.string.patch_override_version_code_desc),
                    startAction = { Icon(Icons.Outlined.Numbers, null) },
                    checked = viewModel.overrideVersionCode,
                    onCheckedChange = { viewModel.overrideVersionCode = it }
                )
                if (viewModel.overrideVersionCode) {
                    OverrideVersionCodeField()
                }
                SwitchPreference(
                    title = stringResource(R.string.patch_output_log_to_media),
                    summary = stringResource(R.string.patch_output_log_to_media_desc),
                    startAction = { Icon(Icons.Outlined.Output, null) },
                    checked = viewModel.outputLog,
                    onCheckedChange = { viewModel.outputLog = it }
                )
            }
        }
    }
}

private val MICROG_VENDOR_PRESETS = listOf(
    "app.revanced" to R.string.patch_microg_vendor_revanced,
    "com.google.android.gms" to R.string.patch_microg_vendor_microg,
)

@Composable
private fun MicrogVendorPreference() {
    val viewModel = viewModel<NewPatchViewModel>()
    val customLabel = stringResource(R.string.patch_microg_vendor_custom)
    val items = MICROG_VENDOR_PRESETS.map { (_, labelRes) -> stringResource(labelRes) } + customLabel
    val selectedIndex = MICROG_VENDOR_PRESETS.indexOfFirst { it.first == viewModel.microgVendor }
        .takeIf { it >= 0 } ?: items.lastIndex

    OverlayDropdownPreference(
        title = stringResource(R.string.patch_microg_vendor),
        summary = stringResource(R.string.patch_microg_vendor_desc),
        items = items,
        selectedIndex = selectedIndex,
        startAction = { Icon(Icons.Outlined.CloudSync, null) },
        onSelectedIndexChange = { index ->
            MICROG_VENDOR_PRESETS.getOrNull(index)?.let { (vendor, _) ->
                viewModel.microgVendor = vendor
            }
        }
    )

    // カスタム指定時はパッケージ名を直接入力する
    if (selectedIndex == items.lastIndex) {
        TextField(
            value = viewModel.microgVendor,
            onValueChange = { viewModel.microgVendor = it },
            label = stringResource(R.string.patch_microg_vendor_hint),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )
    }
}

/**
 * versionCode の上書き値を入力させるフィールド。
 * 数字以外を弾き、versionCode の上限 (Int.MAX_VALUE) を超える入力は上限値に丸める。
 */
@Composable
private fun OverrideVersionCodeField() {
    val viewModel = viewModel<NewPatchViewModel>()

    TextField(
        value = viewModel.overrideVersionCodeValue,
        onValueChange = { input ->
            val digits = input.filter { it.isDigit() }.trimStart('0')
            viewModel.overrideVersionCodeValue = when {
                digits.isEmpty() -> ""
                (digits.toLongOrNull()
                    ?: Long.MAX_VALUE) > NewPatchViewModel.MAX_VERSION_CODE ->
                    NewPatchViewModel.MAX_VERSION_CODE.toString()

                else -> digits
            }
        },
        label = stringResource(R.string.patch_custom_version_code),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    )
}
