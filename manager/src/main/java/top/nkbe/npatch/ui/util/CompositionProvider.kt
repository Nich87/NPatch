package top.nkbe.npatch.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.SnackbarHostState

const val BG_SURFACE_ALPHA = 0.6f

val LocalSnackbarHost = compositionLocalOf<SnackbarHostState> {
    error("CompositionLocal LocalSnackbarController not present")
}

val LocalBackgroundImagePath = compositionLocalOf { "" }

val LocalCardBackgroundAlpha = compositionLocalOf { BG_SURFACE_ALPHA }

@Composable
fun backgroundAwareColor(
    color: Color,
    backgroundAlpha: Float = BG_SURFACE_ALPHA,
): Color {
    return if (LocalBackgroundImagePath.current.isNotEmpty()) {
        color.copy(alpha = backgroundAlpha)
    } else {
        color
    }
}
