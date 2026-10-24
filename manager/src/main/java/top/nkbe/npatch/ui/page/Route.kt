package top.nkbe.npatch.ui.page

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Navigation3 路由定义。
 * LINE 専用マネージャの最小ルート構成。
 */
sealed interface Route : NavKey {
    @Serializable
    data class Main(
        val initialTab: Int = MainTab.Home.ordinal
    ) : Route

    @Serializable
    data class Welcome(
        val reviewMode: Boolean = false
    ) : Route

    @Serializable
    data object About : Route

    @Serializable
    data class NewPatch(
        val id: Int,
        val data: String? = null
    ) : Route
}
