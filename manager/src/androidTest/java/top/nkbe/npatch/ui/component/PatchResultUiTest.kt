package top.nkbe.npatch.ui.component

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import top.nkbe.npatch.R
import top.nkbe.npatch.ui.theme.LSPTheme

class PatchResultUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun failedExportCanBeRetriedWithoutLosingTheSuccessfulResult() {
        var error by mutableStateOf<String?>("Storage permission was revoked")
        var saves = 0
        compose.setContent {
            LSPTheme {
                Column {
                    PatchResultSummary(true, "test.patch.fixture", null)
                    PatchSaveActions(error, false, onRetry = { saves++; error = null }, onChooseDirectory = {})
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.patch_completed)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.patch_save_retry)).performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithText(compose.activity.getString(R.string.patch_completed)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.patch_save_again)).assertIsDisplayed()
        compose.onNodeWithText("Storage permission was revoked", substring = true).assertDoesNotExist()
    }

    @Test fun savingDisablesBothSaveActionsAndCanBeRetriedAfterFailure() {
        var saving by mutableStateOf(true)
        var choices = 0
        compose.setContent {
            LSPTheme {
                PatchSaveActions("Folder unavailable", saving, onRetry = {}, onChooseDirectory = { choices++ })
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.patch_save_retry)).assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.patch_save_choose_directory)).assertIsNotEnabled()
        compose.runOnIdle { saving = false }
        compose.onNodeWithText(compose.activity.getString(R.string.patch_save_choose_directory)).performClick()
        compose.runOnIdle { assertEquals(1, choices) }
    }

    @Test fun patchFailureShowsTheCauseWithoutCallingItACopyAction() {
        compose.setContent {
            LSPTheme {
                PatchResultSummary(false, "test.patch.fixture", "Invalid APK")
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.patch_failed)).assertIsDisplayed()
        compose.onNodeWithText("Invalid APK").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.copy_error)).assertDoesNotExist()
    }

    @Test fun resultStatesRenderInBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent {
            LSPTheme(isDarkTheme = dark) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    PatchResultSummary(true, "test.patch.fixture", null)
                    PatchSaveActions("Folder unavailable", false, {}, {})
                }
            }
        }
        for (theme in listOf(false, true)) {
            compose.runOnIdle { dark = theme }
            compose.onNodeWithText(compose.activity.getString(R.string.patch_completed)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.patch_save_retry)).assertIsDisplayed()
            compose.onNodeWithText(compose.activity.getString(R.string.patch_save_choose_directory)).assertIsDisplayed()
        }
    }
}
