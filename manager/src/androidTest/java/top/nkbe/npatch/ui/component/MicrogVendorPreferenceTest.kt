package top.nkbe.npatch.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.page.newpatch.MicrogVendorPreference
import top.nkbe.npatch.ui.theme.LSPTheme

class MicrogVendorPreferenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun customSelectionOpensEditorAndKeepsItOpenForPresetText() {
        var vendor by mutableStateOf("app.revanced")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LSPTheme(isDarkTheme = true) {
                NPatchScaffold { Column { MicrogVendorPreference(vendor) { vendor = it } } }
            }
        }
        val title = compose.activity.getString(R.string.patch_microg_vendor)
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.patch_microg_vendor_custom)).performClick()
        compose.onNode(hasSetTextAction()).assertIsDisplayed().performTextReplacement("test.vendor")
        compose.runOnIdle { assertEquals("test.vendor", vendor) }
        compose.onNode(hasSetTextAction()).performTextReplacement("app.revanced")
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText(title).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.patch_microg_vendor_microg)).performClick()
        compose.runOnIdle { assertEquals("com.google.android.gms", vendor) }
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }
}
