package top.nkbe.npatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import top.nkbe.npatch.config.DEFAULT_CUSTOM_COLOR
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun LSPTheme(
    isDarkTheme: Boolean = isSystemInDarkTheme(),
    useMonet: Boolean = false,
    customColor: Int = DEFAULT_CUSTOM_COLOR,
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    val controller = remember(isDarkTheme, useMonet, customColor) {
        if (useMonet) {
            ThemeController(ColorSchemeMode.MonetSystem)
        } else {
            ThemeController(
                if (isDarkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight,
                keyColor = Color(customColor)
            )
        }
    }
    val themedContent: @Composable () -> Unit = {
        val colors = MiuixTheme.colorScheme
        val material = if (isDarkTheme) darkColorScheme() else lightColorScheme()
        CompositionLocalProvider(
            LocalGlassAmoled provides (amoledBlack && isDarkTheme),
        ) {
            MaterialTheme(
                colorScheme = material.copy(
                    primary = colors.primary,
                    onPrimary = colors.onPrimary,
                    primaryContainer = colors.primaryContainer,
                    onPrimaryContainer = colors.onPrimaryContainer,
                    background = colors.background,
                    onBackground = colors.onBackground,
                    surface = colors.surface,
                    onSurface = colors.onSurface,
                    onSurfaceVariant = colors.onSurfaceVariantSummary,
                    outline = colors.outline,
                    error = colors.error,
                ),
                content = content,
            )
        }
    }
    if (amoledBlack && isDarkTheme) MiuixTheme(colors = controller.currentColors().toAmoled(), content = themedContent)
    else MiuixTheme(controller = controller, content = themedContent)
}
