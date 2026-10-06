package top.nkbe.npatch.ui.page

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import io.github.suqi8.coui.kmp.theme.COUITheme
import top.nkbe.npatch.ui.component.GlassCard
import top.nkbe.npatch.ui.component.NPatchScaffold

@Composable
fun MainScreen(
    navigator: Navigator,
    selectedTab: Int = MainTab.Home.ordinal,
    onSelectedTabChange: (Int) -> Unit = {},
) {
    val tabs = MainTab.entries
    val safeSelectedTab = selectedTab.coerceIn(0, tabs.lastIndex)
    val stateHolder = rememberSaveableStateHolder()

    @Composable
    fun Page(page: Int) {
        when (tabs[page]) {
            MainTab.Home -> HomeScreen(navigator = navigator, onNavigateToSettings = { onSelectedTabChange(MainTab.Settings.ordinal) })
            MainTab.Settings -> SettingsScreen()
        }
    }

    NPatchScaffold(
        bottomBar = {
            Column(Modifier.navigationBarsPadding()) {
                GlassCard(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    NavigationBar(containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0)) {
                        tabs.forEachIndexed { index, tab ->
                            val selected = safeSelectedTab == index
                            NavigationBarItem(
                                selected = selected,
                                onClick = { onSelectedTabChange(index) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(stringResource(tab.labelRes)) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = COUITheme.colorScheme.primary,
                                    selectedTextColor = COUITheme.colorScheme.primary,
                                    indicatorColor = COUITheme.colorScheme.primary.copy(alpha = 0.12f),
                                    unselectedIconColor = COUITheme.colorScheme.onSurfaceVariantSummary,
                                    unselectedTextColor = COUITheme.colorScheme.onSurfaceVariantSummary,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            stateHolder.SaveableStateProvider(tabs[safeSelectedTab].name) {
                Page(safeSelectedTab)
            }
        }
    }
}
