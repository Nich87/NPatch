package top.nkbe.npatch.ui.component

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.nkbe.npatch.R
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

fun formatPatchElapsed(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

@Composable
fun PatchRunHeader(stage: String, elapsedMillis: Long, running: Boolean, failed: Boolean) {
    val colors = MiuixTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stage, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.headline1,
                color = if (failed) colors.error else colors.primary)
            Text(formatPatchElapsed(elapsedMillis),
                modifier = Modifier.background(colors.surface, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                style = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace))
        }
        if (running) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp),
                color = colors.primary, trackColor = colors.primary.copy(alpha = 0.12f))
        } else {
            LinearProgressIndicator(progress = { if (failed) 0f else 1f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = colors.primary, trackColor = colors.primary.copy(alpha = 0.12f),
                drawStopIndicator = {})
        }
    }
}

@Composable
fun PatchRuntimeStrip(memory: List<Long?>, io: List<Long?>, cpu: List<Float?>) {
    val context = LocalContext.current
    val memoryValue = memory.lastOrNull()?.let { Formatter.formatShortFileSize(context, it) } ?: "—"
    val ioValue = io.lastOrNull()?.let { Formatter.formatShortFileSize(context, it) + "/s" } ?: "—"
    val cpuValue = cpu.lastOrNull()?.let { "%.0f%%".format(it) } ?: "—"
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RuntimeMetric(stringResource(R.string.patch_runtime_memory), memoryValue,
            memory.map { it?.toFloat() }, Modifier.weight(1f))
        RuntimeMetric(stringResource(R.string.patch_runtime_io), ioValue,
            io.map { it?.toFloat() }, Modifier.weight(1f))
        RuntimeMetric(stringResource(R.string.patch_runtime_cpu), cpuValue, cpu, Modifier.weight(1f))
    }
}

@Composable
private fun RuntimeMetric(label: String, value: String, samples: List<Float?>, modifier: Modifier) {
    val colors = MiuixTheme.colorScheme
    val labelStyle = MiuixTheme.textStyles.footnote1.let { it.copy(lineHeight = it.fontSize * 1.4f) }
    Surface(modifier = modifier.fillMaxHeight(), shape = RoundedCornerShape(16.dp), color = colors.surface) {
        Column(Modifier.fillMaxHeight().heightIn(min = 100.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = labelStyle, color = colors.onSurfaceVariantSummary, minLines = 2)
            Text(value, style = MiuixTheme.textStyles.body1.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.BottomCenter) {
                Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    val maximum = samples.filterNotNull().maxOrNull()?.coerceAtLeast(1f) ?: 1f
                    val slots = 24
                    val step = size.width / slots
                    samples.takeLast(slots).forEachIndexed { index, sample ->
                        if (sample != null && sample > 0f) {
                            val barHeight = sample / maximum * size.height
                            drawRoundRect(colors.primary.copy(alpha = 0.35f + 0.65f * (index + 1) / samples.size.coerceAtLeast(1)),
                                topLeft = Offset((slots - samples.size.coerceAtMost(slots) + index) * step, size.height - barHeight),
                                size = Size((step - 1.dp.toPx()).coerceAtLeast(1f), barHeight),
                                cornerRadius = CornerRadius(1.dp.toPx()))
                        }
                    }
                }
                if (samples.none { it != null }) HorizontalDivider(color = colors.outline.copy(alpha = 0.18f))
            }
        }
    }
}
