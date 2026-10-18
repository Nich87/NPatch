package top.nkbe.npatch.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.content.Context
import top.nkbe.npatch.R
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import top.nkbe.npatch.ui.theme.LSPTheme
import top.nkbe.npatch.update.UpdateAsset
import top.nkbe.npatch.update.UpdateRelease
import top.nkbe.npatch.update.UpdateState
import top.nkbe.npatch.update.AppUpdater

@RunWith(AndroidJUnit4::class)
class AppUpdateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val release = UpdateRelease("1.2.0", "Regression release notes",
        UpdateAsset("NPatch-v1.2.0-20-debug.apk", "https://github.com/Nich87/NPatch/releases/download/v1.2.0/NPatch-v1.2.0-20-debug.apk", 1234, null))

    @Test fun prereleaseSettingPersistsAndClearsPreviousChannelNotice() {
        val preferences = compose.activity.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
        val originalInclude = AppUpdater.includePrereleases
        val originalRelease = preferences.getString("release_json", null)
        val originalNotified = preferences.getString("notified_version", null)
        try {
            compose.runOnIdle { AppUpdater.includePrereleases = false }
            compose.setContent { LSPTheme(isDarkTheme = true) { Column { AppUpdatePreferences() } } }
            val title = compose.activity.getString(R.string.app_update_include_prereleases)
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            compose.runOnIdle {
                assertTrue(AppUpdater.includePrereleases)
                assertTrue(preferences.getBoolean("include_prereleases", false))
                val json = """{"tag_name":"v1.2.0-rc1","prerelease":true,"draft":false,"assets":[{"name":"NPatch-v1.2.0-rc1-20-release.apk","browser_download_url":"https://github.com/Nich87/NPatch/releases/download/v1.2.0-rc1/app.apk","size":1234}]}"""
                preferences.edit().putString("release_json", json).putString("notified_version", "1.2.0-rc1").apply()
                AppUpdater.openDetails()
                assertEquals("1.2.0-rc1", AppUpdater.state.value.release?.version)
            }
            compose.onNodeWithText(title).performClick()
            compose.runOnIdle {
                assertFalse(AppUpdater.includePrereleases)
                assertFalse(preferences.getBoolean("include_prereleases", true))
                assertEquals(null, AppUpdater.state.value.release)
                assertEquals(null, AppUpdater.state.value.file)
                assertFalse(preferences.contains("release_json"))
                assertFalse(preferences.contains("notified_version"))
            }
        } finally {
            compose.runOnIdle {
                AppUpdater.includePrereleases = originalInclude
                preferences.edit().putString("release_json", originalRelease)
                    .putString("notified_version", originalNotified).apply()
                AppUpdater.showDialog(false)
            }
        }
    }

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

    @Test fun changelogAutolinksUrlsAndMentionsButPreservesExistingLinksAndCode() {
        val notes = "[Guide](https://example.com/guide)\n\nhttps://github.com/2b-zipper/Knot/compare/v2.8.3...v2.9.0\n\n@2b-zipper dev@example.com\n\n`@code https://example.com/code`\n\n```\n@fenced https://example.com/fenced\n```"
        compose.setContent { LSPTheme(isDarkTheme = true) { ReleaseNotesMarkdown(notes) } }
        compose.runOnIdle {
            val view = findTextView(compose.activity.window.decorView) ?: error("Markdown TextView was not created")
            val text = view.text as android.text.Spanned
            val links = text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
            assertEquals(setOf("https://example.com/guide", "https://github.com/2b-zipper/Knot/compare/v2.8.3...v2.9.0", "https://github.com/2b-zipper"), links.map { it.url }.toSet())
            assertEquals(3, links.size)
            assertTrue(view.movementMethod is android.text.method.LinkMovementMethod)
            for (link in links.filterNot { it is io.noties.markwon.core.spans.LinkSpan }) {
                val paint = android.text.TextPaint().apply { linkColor = view.linkTextColors.defaultColor }
                link.updateDrawState(paint)
                assertTrue(paint.isUnderlineText)
                assertEquals(view.linkTextColors.defaultColor, paint.color)
            }
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
                // Reproduces MainActivity's sibling placement outside the Miuix Scaffold.
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
        compose.onNodeWithText("Regression release notes").assertDoesNotExist()
    }

    @Test fun downloadClosesDialogAndProgressCardCanReopenIt() {
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
        compose.onNodeWithText("Regression release notes").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_downloading, 42)).assertIsDisplayed().performClick()
        compose.onNodeWithText("Regression release notes").assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_download)).assertDoesNotExist()
    }

    @Test fun currentVersionRemovesHomeNoticeButCheckingAndErrorsStayVisible() {
        var state by mutableStateOf(UpdateState(release = release))
        compose.setContent {
            LSPTheme(isDarkTheme = true) {
                AppUpdateStatusCard(state, onOpen = {}, onCheck = {})
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_open)).assertIsDisplayed()

        compose.runOnIdle {
            state = UpdateState(upToDate = true, message = compose.activity.getString(R.string.app_update_current))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_current)).assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_open)).assertDoesNotExist()

        compose.runOnIdle { state = state.copy(checking = true) }
        compose.onNodeWithText(compose.activity.getString(R.string.app_update_checking)).assertIsDisplayed()
        compose.runOnIdle { state = UpdateState(message = "Check failed") }
        compose.onNodeWithText("Check failed").assertIsDisplayed()
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
