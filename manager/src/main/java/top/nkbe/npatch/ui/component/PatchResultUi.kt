package top.nkbe.npatch.ui.component

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.nkbe.npatch.R
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun PatchResultSummary(
    succeeded: Boolean,
    packageName: String,
    errorMessage: String?,
    outputSizeBytes: Long? = null,
    elapsedMillis: Long? = null,
) {
    val colors = MiuixTheme.colorScheme
    val accent = if (succeeded) colors.primary else colors.error
    Surface(shape = RoundedCornerShape(16.dp), color = accent.copy(alpha = 0.09f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (succeeded) R.string.patch_completed else R.string.patch_failed),
                    modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold),
                    color = accent)
                Icon(if (succeeded) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
            }
            Text(packageName, style = MiuixTheme.textStyles.footnote1, color = colors.onSurfaceVariantSummary)
            if (!succeeded && !errorMessage.isNullOrBlank()) {
                SelectionContainer {
                    Text(errorMessage, style = MiuixTheme.textStyles.body2, color = colors.error)
                }
            }
            if (outputSizeBytes != null || elapsedMillis != null) {
                HorizontalDivider(color = accent.copy(alpha = 0.12f))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (outputSizeBytes != null) ResultValue(stringResource(R.string.patch_output_size),
                        Formatter.formatShortFileSize(LocalContext.current, outputSizeBytes), Modifier.weight(1f))
                    if (elapsedMillis != null) ResultValue(stringResource(R.string.patch_elapsed),
                        formatPatchElapsed(elapsedMillis), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ResultValue(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.body1.copy(fontFamily = FontFamily.Monospace))
    }
}

@Composable
fun PatchSaveActions(
    exportError: String?,
    isExporting: Boolean,
    onRetry: () -> Unit,
    onChooseDirectory: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (exportError != null) {
            Text(stringResource(R.string.patch_save_recovery), style = MiuixTheme.textStyles.body2)
            SelectionContainer {
                Text(exportError, style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (isExporting) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(size = 20.dp)
                Text(stringResource(R.string.patch_saving))
            }
        }
        TextButton(
                text = stringResource(if (exportError == null) R.string.patch_save_again else R.string.patch_save_retry),
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = onRetry,
            )
        TextButton(
                text = stringResource(R.string.patch_save_choose_directory),
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                onClick = onChooseDirectory,
            )
    }
}
