package top.nkbe.npatch.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import top.nkbe.npatch.R
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import top.nkbe.npatch.ui.theme.LSPTheme
import top.nkbe.npatch.update.UpdateAsset
import top.nkbe.npatch.update.UpdateRelease
import top.nkbe.npatch.update.UpdateState

@RunWith(AndroidJUnit4::class)
class AppUpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val release = UpdateRelease("1.2.0", "Regression release notes",
        UpdateAsset("NPatch-v1.2.0-20-debug.apk", "https://github.com/Nich87/NPatch/releases/download/v1.2.0/NPatch-v1.2.0-20-debug.apk", 1234, null))

    @Test fun changelogRendersMarkdownFormattingAndClickableLinks() {
        val notes = "# Changes\n\n- **Improved** updates\n- [Details](https://github.com/Nich87/NPatch/releases)\n\n`code`"
        compose.setContent {
            LSPTheme(isDarkTheme = true) {
                ReleaseNotesMarkdown(notes)
            }
        }
        compose.onNodeWithText("Improved updates", substring = true).assertIsDisplayed()
        compose.runOnIdle {
            val view = findTextView(compose.activity.window.decorView)
                ?: error("Markdown TextView was not created")
            val text = view.text as android.text.Spanned
            assertFalse(text.toString().contains("# Changes"))
            assertFalse(text.toString().contains("**Improved**"))
            assertTrue(text.getSpans(0, text.length, io.noties.markwon.core.spans.HeadingSpan::class.java).isNotEmpty())
            assertTrue(text.getSpans(0, text.length, io.noties.markwon.core.spans.StrongEmphasisSpan::class.java).isNotEmpty())
            assertTrue(text.getSpans(0, text.length, io.noties.markwon.core.spans.LinkSpan::class.java).isNotEmpty())
            assertTrue(text.getSpans(0, text.length, io.noties.markwon.core.spans.CodeSpan::class.java).isNotEmpty())
        }
    }

    @Test fun settingsUpdateTapOpensDialogWithoutScaffoldHost() {
        var showDialog by mutableStateOf(false)
        var downloaded = false
        compose.setContent {
            LSPTheme(isDarkTheme = true) {
                Column {
                    AppUpdatePreference(UpdateState(release = release), onCheck = {}, onOpen = { showDialog = true })
                }
                // Reproduces MainActivity's sibling placement outside the COUI Scaffold.
                AppUpdateDetailsDialog(UpdateState(release = release, showDialog = showDialog),
                    onDismiss = { showDialog = false }, onCheck = {},
                    onDownload = { downloaded = true }, onInstall = {})
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_available_summary, "1.2.0")).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_available, "1.2.0")).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_check)).performClick()
        compose.onNodeWithText("Regression release notes").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_download)).performClick()
        compose.runOnIdle { assertTrue(downloaded) }
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_later)).performClick()
        compose.onNodeWithText("Regression release notes").assertDoesNotExist()
    }

    @Test fun homeCardTapShowsDownloadProgressAndCanReopenAfterDismissal() {
        var state by mutableStateOf(UpdateState(release = release))
        compose.setContent {
            LSPTheme(isDarkTheme = true) {
                AppUpdateStatusCard(state, onOpen = { state = state.copy(showDialog = true) }, onCheck = {})
                AppUpdateDetailsDialog(state, onDismiss = { state = state.copy(showDialog = false) },
                    onCheck = {}, onDownload = { state = state.copy(downloading = true, progress = 42) }, onInstall = {})
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_open)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_download)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_later)).performClick()
        compose.onNodeWithText("Regression release notes").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_downloading, 42)).assertIsDisplayed().performClick()
        compose.onNodeWithText("Regression release notes").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_download)).assertDoesNotExist()
    }
    private fun findTextView(view: android.view.View): android.widget.TextView? {
        if (view is android.widget.TextView) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                findTextView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

}
