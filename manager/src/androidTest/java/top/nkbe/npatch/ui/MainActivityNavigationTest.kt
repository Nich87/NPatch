package top.nkbe.npatch.ui

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.core.view.doOnPreDraw
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import top.nkbe.npatch.R
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.share.LSPConfig
import top.nkbe.npatch.ui.activity.MainActivity
import top.nkbe.npatch.update.AppUpdater

class MainActivityNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun aboutShowsDeviceDetailsAndReturnsToSettingsAfterRecreation() {
        val welcomeSeen = Configs.welcomeSeen
        val automaticChecks = AppUpdater.automaticChecks
        try {
            Configs.welcomeSeen = true
            AppUpdater.automaticChecks = false
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var context = awaitFirstDraw(scenario)
                val settings = context.getString(R.string.screen_settings)
                val about = context.getString(R.string.settings_about)
                compose.onNodeWithText(settings).performClick()
                compose.onNodeWithText(about).performScrollTo().performClick()
                compose.onNodeWithText(LSPConfig.FRAMEWORK_NAME).assertIsDisplayed()

                scenario.recreate()
                context = awaitFirstDraw(scenario)
                compose.onNodeWithText(LSPConfig.FRAMEWORK_NAME).assertIsDisplayed()
                val abis = Build.SUPPORTED_ABIS.joinToString(", ")
                compose.onNode(
                    hasScrollToIndexAction() and hasAnyDescendant(hasText(LSPConfig.FRAMEWORK_NAME))
                ).performScrollToNode(hasText(abis))
                compose.onNodeWithText(abis).assertIsDisplayed()

                compose.onNodeWithContentDescription(context.getString(R.string.about_copy_details)).performClick()
                compose.runOnIdle {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
                    assertTrue(copied.contains(LSPConfig.FRAMEWORK_NAME))
                    assertTrue(copied.contains(abis))
                    assertTrue(copied.contains(Build.FINGERPRINT))
                }
                compose.onNodeWithContentDescription(context.getString(R.string.patch_return)).performClick()
                compose.onNode(hasText(settings) and isSelected()).assertIsDisplayed()
                compose.onNodeWithText(about).assertIsDisplayed()
            }
        } finally {
            Configs.welcomeSeen = welcomeSeen
            AppUpdater.automaticChecks = automaticChecks
        }
    }

    @Test fun patchConfigurationSurvivesActivityRecreation() {
        val welcomeSeen = Configs.welcomeSeen
        val storageDirectory = Configs.storageDirectory
        val customVersion = Configs.customLineVersionCode
        val useCustomVersion = Configs.useCustomLineVersion
        try {
            Configs.welcomeSeen = true
            Configs.storageDirectory = storageDirectory ?: "content://test/unused-directory"
            Configs.customLineVersionCode = "261501000"
            Configs.useCustomLineVersion = true
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var context = awaitFirstDraw(scenario)
                compose.onNodeWithText(context.getString(R.string.patch_start)).performClick()
                compose.onNodeWithText(context.getString(R.string.patch_from_proxy)).performClick()
                compose.onNodeWithText(context.getString(R.string.patch_sigbypass)).assertIsDisplayed()
                scenario.recreate()
                context = awaitFirstDraw(scenario)
                compose.onNodeWithText(context.getString(R.string.patch_sigbypass)).assertIsDisplayed()
            }
        } finally {
            Configs.welcomeSeen = welcomeSeen
            Configs.storageDirectory = storageDirectory
            Configs.customLineVersionCode = customVersion
            Configs.useCustomLineVersion = useCustomVersion
        }
    }

    private fun awaitFirstDraw(scenario: ActivityScenario<MainActivity>): MainActivity {
        val drawn = CountDownLatch(1)
        lateinit var activity: MainActivity
        scenario.onActivity {
            activity = it
            it.window.decorView.doOnPreDraw { drawn.countDown() }
            it.window.decorView.invalidate()
        }
        check(drawn.await(10, TimeUnit.SECONDS)) { "Activity content did not draw" }
        compose.waitForIdle()
        return activity
    }
}
