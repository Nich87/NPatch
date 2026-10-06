package top.nkbe.npatch.ui.theme

import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.Colors

fun Colors.toAmoled(): Colors = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF161616),
    surfaceContainerHighest = Color(0xFF1C1C1C),
)