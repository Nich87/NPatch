package top.nkbe.npatch.ui.page.newpatch

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Api
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Output
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import top.nkbe.npatch.R
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.ui.component.GlassCard
import top.nkbe.npatch.ui.component.NPatchTopAppBar
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.ViewAction
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
                tint = MiuixTheme.colorScheme.onPrimary
            )
            Text(
                text = stringResource(R.string.patch_start),
                color = MiuixTheme.colorScheme.onPrimary
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
fun PatchOptionsBody(modifier: Modifier, onAddEmbed: () -> Unit) {
    val viewModel = viewModel<NewPatchViewModel>()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 84.dp)
    ) {
        GlassCard(
            modifier = Modifier.fillMaxWidth(),
            highlighted = true,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Text(text = viewModel.patchApp.label, style = MiuixTheme.textStyles.headline1)
                Text(
                    text = viewModel.patchApp.app.packageName,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        PatchModeSelection(
            useManager = viewModel.useManager,
            moduleCount = viewModel.embeddedModules.size,
            onUseManagerChange = viewModel::setUseManager,
            onAddEmbed = { if (!viewModel.isLoadingEmbeddedModule) onAddEmbed() },
        )
        if (!viewModel.useManager) {
            if (viewModel.isLoadingEmbeddedModule) {
                Text(
                    text = stringResource(R.string.manage_loading),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    style = MiuixTheme.textStyles.body2,
                )
            }
            viewModel.embeddedModuleError?.let { error ->
                Text(
                    text = error,
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        SmallTitle(text = stringResource(R.string.patch_advanced))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
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
                    MicrogVendorPreference(viewModel.microgVendor) { viewModel.microgVendor = it }
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

@Composable
internal fun PatchModeSelection(
    useManager: Boolean,
    moduleCount: Int,
    onUseManagerChange: (Boolean) -> Unit,
    onAddEmbed: () -> Unit,
) {
    val itemShape = RoundedCornerShape(16.dp)
    SmallTitle(text = stringResource(R.string.patch_mode))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(8.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PatchModeItem(
                modifier = Modifier.clip(itemShape),
                selected = useManager,
                onClick = { onUseManagerChange(true) },
                icon = Icons.Outlined.Api,
                title = stringResource(R.string.patch_local),
                desc = stringResource(R.string.patch_local_desc),
            )
            PatchModeItem(
                modifier = Modifier.clip(itemShape),
                selected = !useManager,
                onClick = { onUseManagerChange(false) },
                icon = Icons.Outlined.WorkOutline,
                title = stringResource(R.string.patch_integrated),
                desc = stringResource(R.string.patch_integrated_desc),
                extraContent = {
                    val label = stringResource(R.string.patch_embed_modules)
                    Text(
                        text = if (moduleCount > 0) "$label ($moduleCount)" else label,
                        modifier = Modifier.padding(top = 8.dp).clickable(onClick = onAddEmbed),
                        color = MiuixTheme.colorScheme.primary,
                        style = MiuixTheme.textStyles.body2,
                    )
                },
            )
        }
    }
}

private val MICROG_VENDOR_PRESETS = listOf(
    "app.revanced" to R.string.patch_microg_vendor_revanced,
    "com.google.android.gms" to R.string.patch_microg_vendor_microg,
)

@Composable
internal fun MicrogVendorPreference(vendor: String, onVendorChange: (String) -> Unit) {
    val customLabel = stringResource(R.string.patch_microg_vendor_custom)
    val items = MICROG_VENDOR_PRESETS.map { (_, labelRes) -> stringResource(labelRes) } + customLabel
    var custom by rememberSaveable { mutableStateOf(MICROG_VENDOR_PRESETS.none { it.first == vendor }) }
    val selectedIndex = if (custom) items.lastIndex else MICROG_VENDOR_PRESETS.indexOfFirst { it.first == vendor }
        .takeIf { it >= 0 } ?: items.lastIndex

    OverlayDropdownPreference(
        title = stringResource(R.string.patch_microg_vendor),
        summary = stringResource(R.string.patch_microg_vendor_desc),
        items = items,
        selectedIndex = selectedIndex,
        startAction = { Icon(Icons.Outlined.CloudSync, null) },
        onSelectedIndexChange = { index ->
            custom = index == items.lastIndex
            MICROG_VENDOR_PRESETS.getOrNull(index)?.let { (vendor, _) ->
                onVendorChange(vendor)
            }
        }
    )

    // カスタム指定時はパッケージ名を直接入力する
    if (custom) {
        TextField(
            value = vendor,
            onValueChange = onVendorChange,
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


@Composable
private fun PatchModeItem(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    title: String,
    desc: String? = null,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    val backgroundColor = animateColorAsState(
        targetValue = if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.1f)
        else Color.Transparent,
        label = "SelectionItemBg"
    ).value
    val selectedLabel = stringResource(R.string.accessibility_selected)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .background(backgroundColor)
            .semantics { stateDescription = if (selected) selectedLabel else "" }
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.title3
            )
            if (desc != null || extraContent != null) {
                AnimatedVisibility(
                    visible = selected,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    Column {
                        if (desc != null) {
                            Text(
                                text = desc,
                                modifier = Modifier.padding(top = 4.dp),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.67f)
                            )
                        }
                        extraContent?.invoke(this)
                    }
                }
            }
        }
    }
}
