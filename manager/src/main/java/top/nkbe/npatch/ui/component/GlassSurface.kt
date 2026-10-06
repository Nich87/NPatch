package top.nkbe.npatch.ui.component

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.nkbe.npatch.ui.theme.GlassStyle

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = GlassStyle.cardShape,
        color = GlassStyle.surfaceColor(highlighted),
        contentColor = COUITheme.colorScheme.onSurface,
        border = GlassStyle.border(highlighted),
    ) { Column(content = content) }
}

@Composable
fun GlassDialog(
    title: String,
    show: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return
    Dialog(onDismissRequest = onDismissRequest) {
        GlassDialogBackdrop()
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = GlassStyle.dialogShape,
            color = GlassStyle.dialogColor(),
            contentColor = COUITheme.colorScheme.onSurface,
            border = GlassStyle.border(),
        ) {
            Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
                Text(
                    title,
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    color = COUITheme.colorScheme.onSurface,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), content = content)
            }
        }
    }
}

@Composable
internal fun GlassDialogBackdrop() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val radius = with(LocalDensity.current) { 24.dp.roundToPx() }
    SideEffect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = radius }
        }
    }
}
