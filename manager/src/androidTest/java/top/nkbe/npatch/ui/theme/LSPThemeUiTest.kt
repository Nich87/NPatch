package top.nkbe.npatch.ui.theme

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

@RunWith(AndroidJUnit4::class)
class LSPThemeUiTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)

    @Test fun amoledToggleKeepsSettingsInDarkTheme() = verifyToggle(dark = true, monet = false)

    @Test fun amoledToggleKeepsSettingsInLightTheme() = verifyToggle(dark = false, monet = false)

    @Test fun amoledToggleKeepsSettingsWithMonet() = verifyToggle(dark = true, monet = true)

    private data class ThemeSnapshot(
        val amoled: Boolean,
        val screen: String,
        val contentIdentity: Any,
        val background: Color,
        val materialBackground: Color,
        val primary: Color,
        val glassAmoled: Boolean,
        val schemeMode: ColorSchemeMode?,
    )

    private fun verifyToggle(dark: Boolean, monet: Boolean) {
        var amoled by mutableStateOf(false)
        var openSettings: () -> Unit = { error("Theme content has not been composed") }
        val snapshots = LinkedBlockingQueue<ThemeSnapshot>()
        activity.scenario.onActivity { host ->
            host.setContent {
                LSPTheme(isDarkTheme = dark, useMonet = monet, amoledBlack = amoled) {
                    var screen by rememberSaveable { mutableStateOf("Home") }
                    val identity = remember { Any() }
                    val colors = MiuixTheme.colorScheme
                    val materialColors = MaterialTheme.colorScheme
                    val glassAmoled = LocalGlassAmoled.current
                    val schemeMode = MiuixTheme.colorSchemeMode
                    SideEffect {
                        openSettings = { screen = "Settings" }
                        snapshots.offer(ThemeSnapshot(
                            amoled, screen, identity, colors.background, materialColors.background,
                            colors.primary, glassAmoled, schemeMode,
                        ))
                    }
                    Text("Screen: $screen")
                }
            }
        }
        fun awaitSnapshot(): ThemeSnapshot = snapshots.poll(5, TimeUnit.SECONDS)
            ?: error("Theme content did not finish composing")

        awaitSnapshot()
        activity.scenario.onActivity {
            snapshots.clear()
            openSettings()
        }
        val original = awaitSnapshot()
        assertEquals("Settings", original.screen)
        val expectedMode = when {
            monet -> ColorSchemeMode.MonetSystem
            dark -> ColorSchemeMode.MonetDark
            else -> ColorSchemeMode.MonetLight
        }

        repeat(2) {
            for (enabled in listOf(true, false)) {
                activity.scenario.onActivity {
                    snapshots.clear()
                    amoled = enabled
                }
                val updated = awaitSnapshot()
                assertEquals(enabled, updated.amoled)
                assertEquals("AMOLED toggling must keep the settings screen", "Settings", updated.screen)
                assertSame("Theme changes must retain remembered navigation state", original.contentIdentity, updated.contentIdentity)
                assertEquals(if (dark && enabled) Color.Black else original.background, updated.background)
                assertEquals(updated.background, updated.materialBackground)
                assertEquals(original.primary, updated.primary)
                assertEquals(dark && enabled, updated.glassAmoled)
                assertEquals(expectedMode, updated.schemeMode)
            }
        }
    }
}
