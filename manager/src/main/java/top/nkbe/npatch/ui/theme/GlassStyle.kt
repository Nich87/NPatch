package top.nkbe.npatch.ui.theme

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.nkbe.npatch.ui.util.LocalCardBackgroundAlpha

val LocalGlassAmoled = staticCompositionLocalOf { false }

object GlassStyle {
    val cardShape = RoundedCornerShape(24.dp)
    val dialogShape = RoundedCornerShape(28.dp)
    val contentPadding = 18.dp

    @Composable
    fun surfaceColor(highlighted: Boolean = false): Color {
        val colors = COUITheme.colorScheme
        return if (highlighted) colors.primary.copy(alpha = 0.12f)
        else colors.surface.copy(alpha = LocalCardBackgroundAlpha.current)
    }

    @Composable
    fun border(highlighted: Boolean = false) = BorderStroke(
        1.dp,
        COUITheme.colorScheme.primary.copy(alpha = if (highlighted) 0.35f else 0.22f),
    )

    @Composable
    fun background(): Brush {
        val colors = COUITheme.colorScheme
        return Brush.verticalGradient(
            if (LocalGlassAmoled.current) listOf(Color.Transparent, Color.Transparent)
            else listOf(colors.primary.copy(alpha = 0.12f), colors.primary.copy(alpha = 0.03f)),
        )
    }

    @Composable
    fun dialogColor(): Color {
        val colors = COUITheme.colorScheme
        val manager = LocalContext.current.getSystemService(WindowManager::class.java)
        val blurAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isCrossWindowBlurEnabled
        return lerp(colors.surface, colors.primary, 0.04f).copy(alpha = if (blurAvailable) 0.96f else 1f)
    }
}
