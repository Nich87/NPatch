package top.nkbe.npatch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.nkbe.npatch.ui.theme.GlassStyle
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
fun GlassDialogActions(
    dismissText: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .heightIn(min = GlassStyle.dialogButtonMinHeight)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(GlassStyle.dialogButtonSpacing),
    ) {
        TextButton(
            text = dismissText,
            onClick = onDismiss,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        TextButton(
            text = confirmText,
            onClick = onConfirm,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
}
