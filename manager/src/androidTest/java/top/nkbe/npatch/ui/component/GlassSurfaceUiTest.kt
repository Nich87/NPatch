package top.nkbe.npatch.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.nkbe.npatch.ui.theme.LSPTheme

@RunWith(AndroidJUnit4::class)
class GlassSurfaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun longLightDialogKeepsItsActionReachable() = verifyLongDialog(dark = false)

    @Test fun longDarkDialogKeepsItsActionReachable() = verifyLongDialog(dark = true)

    private fun verifyLongDialog(dark: Boolean) {
        var show by mutableStateOf(true)
        compose.setContent {
            LSPTheme(isDarkTheme = dark) {
                GlassDialog(title = "Scrollable dialog", show = show, onDismissRequest = { show = false }) {
                    repeat(80) { Text("Dialog item $it") }
                    TextButton(onClick = { show = false }) { Text("Dismiss dialog") }
                }
            }
        }
        compose.onNodeWithText("Dialog item 0").assertIsDisplayed()
        compose.onNodeWithText("Dismiss dialog").assertIsNotDisplayed().performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Dialog item 0").assertDoesNotExist()
    }
}
