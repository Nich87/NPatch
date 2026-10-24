package top.nkbe.npatch

import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nkbe.util.ModuleMetadataReader
import nkbe.util.NeoPackageManager.AppInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.config.KeystorePreset
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.patcher.PatcherClient
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.PatchConfig
import top.nkbe.npatch.ui.component.NPatchScaffold
import top.nkbe.npatch.ui.page.SelectAppsScreen
import top.nkbe.npatch.ui.page.newpatch.PatchModeSelection
import top.nkbe.npatch.ui.theme.LSPTheme
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.PatchState
import top.nkbe.npatch.ui.viewmodel.NewPatchViewModel.ViewAction

class EmbeddedModulesPatchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun multipleModulesCanBeSelectedWithoutKnotAndCancellationKeepsSelection() = withViewModel { model ->
        val modules = listOf(moduleFixture(), moduleFixture("test.legacy.module", legacy = true))
        try {
            model.selectEmbeddedModules(modules.map(Uri::fromFile))
            assertEquals(setOf("test.modern.module", "test.legacy.module"), model.embeddedModules.map { it.app.packageName }.toSet())
            assertTrue(model.embeddedModules.all { it.isXposedModule })
            val selected = model.embeddedModules.map { it.app.sourceDir }
            model.selectEmbeddedModules(emptyList())
            assertEquals(selected, model.embeddedModules.map { it.app.sourceDir })
            assertNull(model.embeddedModuleError)
            model.updateEmbeddedModules(model.embeddedModules.drop(1))
            assertEquals(1, model.embeddedModules.size)
        } finally {
            modules.forEach(File::delete)
            model.embeddedModules.forEach { File(it.app.sourceDir).delete() }
        }
    }

    @Test fun invalidSelectionPreservesTheSelectedModuleAndPatchInput() = withViewModel { model ->
        val module = moduleFixture()
        val selectedInput = File.createTempFile("line-input-", ".apk", lspApp.tmpApkDir)
        try {
            selectedInput.writeText("selected LINE input")
            model.selectEmbeddedModules(listOf(Uri.fromFile(module)))
            val selected = model.embeddedModules.single().app.sourceDir
            model.selectEmbeddedModules(listOf(Uri.fromFile(module), Uri.fromFile(File(instrumentation.context.applicationInfo.sourceDir))))
            assertFalse(model.embeddedModuleError.isNullOrBlank())
            assertEquals(selected, model.embeddedModules.single().app.sourceDir)
            assertTrue(File(selected).isFile)
            assertEquals("selected LINE input", selectedInput.readText())
        } finally {
            model.embeddedModules.forEach { File(it.app.sourceDir).delete() }
            module.delete()
            selectedInput.delete()
        }
    }

    @Test fun proxyPatchKeepsTheChosenModeModuleAndMicrogVendor() = withViewModel { model ->
        configure(model, ViewAction.ConfigureProxyLinePatch())
        val module = moduleFixture()
        var selected: File? = null
        try {
            model.selectEmbeddedModules(listOf(Uri.fromFile(module)))
            selected = File(model.embeddedModules.single().app.sourceDir)
            model.setUseManager(false)
            model.setUseManager(true)
            model.setUseManager(false)
            model.microgVendor = "test.vendor"
            transition(model, ViewAction.SubmitPatch, PatchState.PATCHING)
            val args = model.patchOptions.configArgs().toList()
            assertFalse(args.contains("--manager"))
            assertEquals(model.embeddedModules.single().app.sourceDir, args[args.indexOf("-m") + 1])
            assertTrue(args.contains("--useMicroG"))
            assertEquals("test.vendor", args[args.indexOf("--microgVendor") + 1])
            model.reset()
            assertTrue(model.useManager)
            assertTrue(model.embeddedModules.isEmpty())
            assertNull(model.embeddedModuleError)
        } finally {
            selected?.delete()
            module.delete()
        }
    }

    @Test fun managerArgumentsExcludePreviouslySelectedModules() {
        val config = PatchConfig(true, false, false, 1, 2, null, null,
            false, true, "test.target", true, false, "app.revanced")
        val args = Patcher.Options("test.target", config, emptyList(), listOf("module.apk")).configArgs()
        assertTrue(args.contains("--manager"))
        assertFalse(args.contains("-m"))
    }

    @Test fun integratedEngineEmbedsModernAndLegacyModulesAndRetainsMicrogConfig() = withViewModel { model ->
        val app = instrumentation.context.applicationInfo
        configure(model, ViewAction.ConfigurePatch(AppInfo(app, "Patch fixture")))
        val modules = listOf(moduleFixture(), moduleFixture("test.legacy.module", legacy = true))
        val previousPreset = Configs.keyStorePreset
        var outputs = emptyList<File>()
        try {
            model.selectEmbeddedModules(modules.map(Uri::fromFile))
            model.setUseManager(false)
            model.microgVendor = "test.vendor"
            transition(model, ViewAction.SubmitPatch, PatchState.PATCHING)
            Configs.keyStorePreset = KeystorePreset.NPATCH
            outputs = withTimeout(120_000) {
                PatcherClient.runInPatcherProcess(object : Logger() {
                    override fun d(msg: String) = Unit
                    override fun i(msg: String) = Unit
                    override fun e(msg: String) { android.util.Log.e("EmbeddedModulesPatchTest", msg) }
                }, model.patchOptions)
            }
            ZipFile(outputs.single()).use { zip ->
                for ((name, module) in listOf("test.modern.module", "test.legacy.module").zip(modules)) {
                    val entry = requireNotNull(zip.getEntry(Constants.EMBEDDED_MODULES_ASSET_PATH + "$name.apk"))
                    assertArrayEquals(module.readBytes(), zip.getInputStream(entry).use { it.readBytes() })
                }
                val config = zip.getInputStream(requireNotNull(zip.getEntry(Constants.CONFIG_ASSET_PATH)))
                    .bufferedReader().use { JsonParser.parseReader(it).asJsonObject }
                assertFalse(config.get("useManager").asBoolean)
                assertTrue(config.get("useMicroG").asBoolean)
                assertEquals("test.vendor", config.get("microgVendor").asString)
                assertNotNull(zip.getEntry(Constants.LOADER_DEX_ASSET_PATH))
            }
        } finally {
            Configs.keyStorePreset = previousPreset
            outputs.forEach(File::delete)
            model.embeddedModules.forEach { File(it.app.sourceDir).delete() }
            modules.forEach(File::delete)
        }
    }

    private fun withViewModel(test: suspend (NewPatchViewModel) -> Unit) = runBlocking {
        val store = ViewModelStore()
        val model = withContext(Dispatchers.Main) {
            NewPatchViewModel().also { store.put("embedded-test", it) }
        }
        try {
            test(model)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
        }
    }

    private suspend fun configure(model: NewPatchViewModel, action: ViewAction) =
        transition(model, action, PatchState.CONFIGURING)

    private suspend fun transition(model: NewPatchViewModel, action: ViewAction, state: PatchState) {
        withContext(Dispatchers.Main) {
            coroutineScope {
                val changed = async(start = CoroutineStart.UNDISPATCHED) {
                    snapshotFlow { model.patchState }.first { it == state }
                }
                model.dispatch(action)
                withTimeout(10_000) { changed.await() }
            }
        }
    }

}

internal fun moduleFixture(packageName: String = "test.modern.module", legacy: Boolean = false): File {
    val manifest = AndroidManifestBlock().apply {
        setPackageName(packageName)
        setVersionCode(1)
        setApplicationLabel("Modern fixture")
        refreshFull()
    }
    return File.createTempFile("module-fixture-", ".apk", lspApp.cacheDir).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            val entries = if (legacy) mapOf("assets/xposed_init" to "test.LegacyModule\n".toByteArray())
            else mapOf(
                "META-INF/xposed/module.prop" to "minApiVersion=101\ntargetApiVersion=102\n".toByteArray(),
                "META-INF/xposed/java_init.list" to "test.ModernModule\n".toByteArray(),
            )
            for ((name, bytes) in entries + ("AndroidManifest.xml" to manifest.bytes)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}

class SelectModulesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val files = mutableListOf<File>()

    @After fun cleanUp() { files.forEach(File::delete) }

    @Test fun searchAndMultiSelectionKeepImportedModulesAndConfirmAllSelectedPackages() {
        val modules = listOf(module("test.module.alpha", "Alpha"), module("test.module.beta", "Beta"))
        var result: List<AppInfo>? = null
        compose.setContent {
            LSPTheme { SelectAppsScreen(modules, { result = it }, {}) }
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("test.module.alpha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("test.module.alpha").assertIsOn().performClick()
        compose.onNodeWithText("test.module.alpha").assertIsOff()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.screen_select_modules)).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("BETA")
        compose.onNodeWithText("test.module.beta").assertIsDisplayed().assertIsOn()
        compose.onNodeWithText("test.module.alpha").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(android.R.string.cancel)).performClick()
        compose.onNodeWithText("test.module.alpha").assertIsOff().performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.add)).performClick()
        compose.runOnIdle {
            assertEquals(setOf("test.module.alpha", "test.module.beta"), result?.map { it.app.packageName }?.toSet())
        }
    }

    @Test fun cancellingThePickerDoesNotCommitChangedSelection() {
        val modules = listOf(module("test.module.alpha", "Alpha"))
        var confirmed = false
        var cancelled = false
        compose.setContent {
            LSPTheme { SelectAppsScreen(modules, { confirmed = true }, { cancelled = true }) }
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("test.module.alpha").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("test.module.alpha").performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.patch_return)).performClick()
        compose.runOnIdle {
            assertTrue(cancelled)
            assertFalse(confirmed)
            assertEquals(1, modules.size)
        }
    }

    private fun module(packageName: String, label: String): AppInfo {
        val fixture = moduleFixture(packageName).also(files::add)
        val file = File.createTempFile("module-ui-", ".apk", lspApp.tmpApkDir).also(files::add)
        fixture.copyTo(file, overwrite = true)
        val pm = lspApp.packageManager
        val info = requireNotNull(pm.getPackageArchiveInfo(file.path, PackageManager.GET_META_DATA))
        val app = requireNotNull(info.applicationInfo).apply {
            sourceDir = file.path
            publicSourceDir = file.path
        }
        return AppInfo(app, label, ModuleMetadataReader.read(info, pm))
    }
}

class PatchModeSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun patchModeCanBeChangedInBothDirections() {
        var useManager by mutableStateOf(true)
        var opened = false
        compose.setContent {
            LSPTheme { NPatchScaffold { Column { PatchModeSelection(useManager, 2, { useManager = it }, { opened = true }) } } }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.patch_integrated)).performClick()
        compose.runOnIdle { assertFalse(useManager) }
        compose.onNodeWithText(compose.activity.getString(R.string.patch_integrated_desc)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.patch_embed_modules) + " (2)").performClick()
        compose.runOnIdle { assertTrue(opened) }
        compose.onNodeWithText(compose.activity.getString(R.string.patch_local)).performClick()
        compose.runOnIdle { assertTrue(useManager) }
        compose.onNodeWithText(compose.activity.getString(R.string.patch_local_desc)).assertIsDisplayed()
    }
}
