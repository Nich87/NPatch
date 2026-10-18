package top.nkbe.npatch.ui.page

import androidx.compose.runtime.compositionLocalOf
import androidx.navigation3.runtime.NavKey

/**
 * 导航辅助类，管理返回栈。
 * 包装 SnapshotStateList<NavKey> 提供导航操作。
 */
class Navigator(
    val backStack: MutableList<NavKey>,
) {
    fun push(key: NavKey) {
        backStack.add(key)
    }

    fun navigate(key: NavKey) = push(key)

    fun pop() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }
}

/**
 * CompositionLocal 用于在 Composable 树中获取 Navigator。
 */
val LocalNavigator = compositionLocalOf<Navigator> {
    error("No Navigator provided")
}
