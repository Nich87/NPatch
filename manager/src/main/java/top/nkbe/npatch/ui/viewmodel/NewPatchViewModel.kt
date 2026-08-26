package top.nkbe.npatch.ui.viewmodel

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import nkbe.util.ModuleMetadataReader
import nkbe.util.ModulePipeline
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import top.nkbe.npatch.Patcher
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.database.entity.Module
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.network.proxy.ApkProxyService
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.PatchConfig
import top.nkbe.npatch.util.LINE_PACKAGE_NAME
import top.nkbe.npatch.util.formatLineVersionName

class NewPatchViewModel : ViewModel() {

    companion object {
        private const val TAG = "NewPatchViewModel"
        private const val KNOT_PACKAGE_NAME = "app.zipper.knot"

        /** versionCode に指定できる下限値。 */
        const val MIN_VERSION_CODE = 1L

        /** AndroidManifest の versionCode は 32bit 符号付き整数のため上限は Int.MAX_VALUE。 */
        const val MAX_VERSION_CODE = Int.MAX_VALUE.toLong()

        /**
         * 上書きの既定値。上限値にしておくことで、パッケージ名と署名が一致し
         * かつこの値が既存のインストール版以上であれば、Play ストアの更新検出回避や
         * 上書きインストールが可能になる。
         */
        const val DEFAULT_VERSION_CODE = MAX_VERSION_CODE

        /**
         * 入力文字列を versionCode として使える値に丸める。
         * 数字以外や空文字は [DEFAULT_VERSION_CODE]、範囲外の値は
         * [MIN_VERSION_CODE]..[MAX_VERSION_CODE] に収める。
         */
        fun sanitizeVersionCode(value: String): Int =
            (value.toLongOrNull() ?: DEFAULT_VERSION_CODE)
                .coerceIn(MIN_VERSION_CODE, MAX_VERSION_CODE)
                .toInt()
    }

    enum class PatchState {
        INIT, SELECTING, CONFIGURING, PATCHING, FINISHED, ERROR
    }

    enum class InstallMethod {
        SYSTEM, SHIZUKU
    }

    sealed class ViewAction {
        object DoneInit : ViewAction()
        data class ConfigurePatch(val app: AppInfo) : ViewAction()
        data class ConfigureProxyLinePatch(val targetVersionCode: Long? = null) : ViewAction()
        object SubmitPatch : ViewAction()
        object LaunchPatch : ViewAction()
    }

    data class ProxyRequest(val targetVersionCode: Long?)

    var patchState by mutableStateOf(PatchState.INIT)
        private set

    var proxyRequest by mutableStateOf<ProxyRequest?>(null)
        private set

    // Patch Configuration
    @set:JvmName("_setUseManager")
    var useManager by mutableStateOf(true)
        private set
    var newPackageName by mutableStateOf("")
    var debuggable by mutableStateOf(false)
    var overrideVersionCode by mutableStateOf(false)
    var overrideVersionCodeValue by mutableStateOf(DEFAULT_VERSION_CODE.toString())
    var sigBypassLevel by mutableStateOf(2)
    var injectProvider by mutableStateOf(false)
    var useMicroG by mutableStateOf(true)
    var microgVendor by mutableStateOf("app.revanced")
    var outputLog by mutableStateOf(true)
    var hideLibs by mutableStateOf(false)
    var embeddedModules by mutableStateOf<List<AppInfo>>(emptyList())
    var hasExecutedIntent by mutableStateOf(false)

    lateinit var patchApp: AppInfo
        private set
    lateinit var patchOptions: Patcher.Options
        private set
    private lateinit var patchConfig: PatchConfig

    val logs = mutableStateListOf<Pair<Int, String>>()

    private fun updateLastLog(msg: String) {
        if (logs.isNotEmpty()) {
            logs[logs.lastIndex] = Log.INFO to msg
        } else {
            logs += Log.INFO to msg
        }
    }

    private val logger = object : Logger() {
        override fun d(msg: String) {
            if (verbose) {
                Log.d(TAG, msg)
                logs += Log.DEBUG to msg
            }
        }

        override fun i(msg: String) {
            Log.i(TAG, msg)
            logs += Log.INFO to msg
        }

        override fun e(msg: String) {
            Log.e(TAG, msg)
            logs += Log.ERROR to msg
        }
    }

    fun dispatch(action: ViewAction) {
        viewModelScope.launch {
            when (action) {
                is ViewAction.DoneInit -> doneInit()
                is ViewAction.ConfigurePatch -> configurePatch(action.app)
                is ViewAction.ConfigureProxyLinePatch -> configureProxyLinePatch(action.targetVersionCode)
                is ViewAction.SubmitPatch -> submitPatch()
                is ViewAction.LaunchPatch -> launchPatch()
            }
        }
    }

    fun reset() {
        patchState = PatchState.INIT
        proxyRequest = null
        useManager = true
        newPackageName = ""
        debuggable = false
        overrideVersionCode = false
        overrideVersionCodeValue = DEFAULT_VERSION_CODE.toString()
        sigBypassLevel = 2
        injectProvider = false
        useMicroG = true
        microgVendor = "app.revanced"
        outputLog = true
        hideLibs = false
        embeddedModules = emptyList()
        logs.clear()
        hasExecutedIntent = false
    }

    fun setUseManager(value: Boolean) {
        useManager = value
        if (!value && sigBypassLevel > Constants.SIGBYPASS_HIGH) {
            sigBypassLevel = Constants.SIGBYPASS_HIGH
        }
    }

    private fun doneInit() {
        patchState = PatchState.SELECTING
    }

    private fun configurePatch(app: AppInfo) {
        Log.d(TAG, "Configuring patch for ${app.app.packageName}")
        proxyRequest = null
        patchApp = app
        patchState = PatchState.CONFIGURING
        newPackageName = app.app.packageName
    }

    private fun configureProxyLinePatch(targetVer: Long? = null) {
        Log.d(TAG, "Configuring Proxy Line Patch (targetVer=$targetVer)")
        proxyRequest = ProxyRequest(targetVer)
        useManager = true
        embeddedModules = emptyList()
        val placeholderAppInfo = ApplicationInfo().apply {
            packageName = LINE_PACKAGE_NAME
            sourceDir = ""
        }
        patchApp = AppInfo(
            app = placeholderAppInfo,
            label = "LINE",
            versionName = targetVer?.let(::formatLineVersionName) ?: "Cloud Proxy",
            versionCode = targetVer ?: 0L
        )
        newPackageName = LINE_PACKAGE_NAME
        patchState = PatchState.CONFIGURING
    }

    private fun submitPatch() {
        Log.d(TAG, "Submit Patch (proxy=${proxyRequest != null})")
        if (useManager) embeddedModules = emptyList()
        val patchSigBypassLevel = if (useManager) sigBypassLevel else sigBypassLevel.coerceAtMost(Constants.SIGBYPASS_HIGH)
        val patchHideLibs =
            hideLibs &&
                patchSigBypassLevel > Constants.SIGBYPASS_NONE
        val patchVersionCode = sanitizeVersionCode(overrideVersionCodeValue)
        sigBypassLevel = patchSigBypassLevel
        hideLibs = patchHideLibs
        overrideVersionCodeValue = patchVersionCode.toString()
        patchConfig = PatchConfig(
            useManager,
            debuggable,
            overrideVersionCode,
            patchVersionCode,
            patchSigBypassLevel,
            null,
            null,
            injectProvider,
            outputLog,
            newPackageName,
            useMicroG,
            patchHideLibs,
            microgVendor,
        )
        patchOptions = buildPatchOptions(
            apkPaths = if (proxyRequest != null) {
                emptyList()
            } else {
                listOf(patchApp.app.sourceDir) + (patchApp.app.splitSourceDirs ?: emptyArray())
            }
        )
        patchState = PatchState.PATCHING
    }

    private fun buildPatchOptions(apkPaths: List<String>) = Patcher.Options(
        newPackageName = newPackageName,
        config = patchConfig,
        apkPaths = apkPaths,
        embeddedModules = embeddedModules.flatMap {
            listOf(it.app.sourceDir) + (it.app.splitSourceDirs ?: emptyArray())
        }
    )

    private suspend fun launchPatch() {
        logger.i("Launch Patch")
        patchState = try {
            val options = proxyRequest
                ?.let { buildPatchOptions(apkPaths = downloadProxyApks(it)) }
                ?: patchOptions

            logger.i("[Patch] Starting LSPatch engine...")
            Patcher.patch(logger, options)
            logger.i("[Patch] Patching completed successfully!")

            // 自動で Knot モジュールをスコープ登録
            runCatching { autoScopeKnot() }
                .onFailure { logger.e("Auto-scope Knot failed: ${it.message}") }
            PatchState.FINISHED
        } catch (t: Throwable) {
            logger.e(t.message.orEmpty())
            logger.e(t.stackTraceToString())
            PatchState.ERROR
        } finally {
            NeoPackageManager.cleanTmpApkDir()
        }
    }

    private suspend fun downloadProxyApks(request: ProxyRequest): List<String> {
        val downloadedFiles = ApkProxyService(lspApp).downloadLineApksForPatcher(
            logger = logger,
            targetVersionCode = request.targetVersionCode,
            onProgressUpdate = ::updateLastLog,
        )
        NeoPackageManager.getAppInfoFromApks(downloadedFiles.map { it.toUri() })
            .onSuccess { appList -> appList.firstOrNull()?.let { patchApp = it } }
        return downloadedFiles.map { it.absolutePath }
    }

    private suspend fun autoScopeKnot() {
        val pm = lspApp.packageManager
        val knotInfo = runCatching {
            pm.getApplicationInfo(KNOT_PACKAGE_NAME, PackageManager.GET_META_DATA)
        }.getOrNull()
        if (knotInfo == null) {
            logger.i("Knot がインストールされていないため、自動スコープ登録をスキップ")
            return
        }
        val meta = runCatching {
            ModuleMetadataReader.read(
                pm.getPackageInfo(KNOT_PACKAGE_NAME, PackageManager.GET_META_DATA),
                pm,
            )
        }.getOrNull()
        if (meta == null || meta.pipeline == ModulePipeline.UNSUPPORTED) {
            logger.i("Knot は対応していないモジュールのため、自動スコープ登録をスキップ")
            return
        }
        ConfigManager.activateModule(
            patchApp.app.packageName,
            Module(knotInfo.packageName, knotInfo.sourceDir),
        )
        logger.i("Knot を ${patchApp.app.packageName} にスコープ登録しました")
    }
}
