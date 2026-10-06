package top.nkbe.npatch.ui.page

import top.nkbe.npatch.R
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 主页面底部导航标签枚举。LINE 専用マネージャではホームと設定のみ。
 */
enum class MainTab(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    Home(R.string.screen_home, Icons.Rounded.Home),
    Settings(R.string.screen_settings, Icons.Rounded.Settings)
}
