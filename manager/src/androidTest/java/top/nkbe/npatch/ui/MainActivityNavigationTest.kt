package top.nkbe.npatch.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import org.junit.Rule
import org.junit.Test
import top.nkbe.npatch.R
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.ui.activity.MainActivity

class MainActivityNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()

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
                lateinit var context: MainActivity
                scenario.onActivity { context = it }
                compose.onNodeWithText(context.getString(R.string.patch_start)).performClick()
                compose.onNodeWithText(context.getString(R.string.patch_from_proxy)).performClick()
                compose.onNodeWithText(context.getString(R.string.patch_sigbypass)).assertIsDisplayed()
                scenario.recreate()
                scenario.onActivity { context = it }
                compose.onNodeWithText(context.getString(R.string.patch_sigbypass)).assertIsDisplayed()
            }
        } finally {
            Configs.welcomeSeen = welcomeSeen
            Configs.storageDirectory = storageDirectory
            Configs.customLineVersionCode = customVersion
            Configs.useCustomLineVersion = useCustomVersion
        }
    }
}
