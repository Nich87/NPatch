package top.nkbe.npatch.ui.viewmodel

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nkbe.util.ModuleMetadataReader
import nkbe.util.ModulePipeline
import nkbe.util.NeoPackageManager
import nkbe.util.NeoPackageManager.AppInfo
import top.nkbe.npatch.Patcher
import top.nkbe.npatch.R
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.database.entity.Module
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.network.proxy.ApkProxyService
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.service.PatchForegroundService
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.PatchConfig
import top.nkbe.npatch.util.LINE_PACKAGE_NAME
import top.nkbe.npatch.util.formatLineVersionName
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class NewPatchViewModel : ViewModel() {

    companion object {
        private const val TAG = "NewPatchViewModel"
        private const val KNOT_PACKAGE_NAME = "app.zipper.knot"

        /** パッチログの保持上限。verbose 時は zip エントリ 1 件ごとに 1 行出る。 */
        private const val MAX_LOG_LINES = 2000

        /** ログ再描画の間隔。1行ごとに不変リストを作り直すとコピーが支配的になる。 */
        private const val LOG_PUBLISH_INTERVAL_MS = 100L

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

        private val STAGE_MARKERS = listOf(
            "Connecting to" to R.string.patch_stage_connecting,
            "Merging " to R.string.patch_stage_merging,
            "Parsing original apk" to R.string.patch_stage_parsing,
            "Packing split apk" to R.string.patch_stage_writing,
            "Patching apk" to R.string.patch_stage_patching,
            "Adding config" to R.string.patch_stage_config,
            "Adding loader dex" to R.string.patch_stage_loader,
            "Adding native lib" to R.string.patch_stage_native,
            "Embedding modules" to R.string.patch_stage_modules,
            "Adding metaloader dex" to R.string.patch_stage_metaloader,
            "Writing apk" to R.string.patch_stage_writing,
            "Exporting" to R.string.patch_stage_exporting,
        )

    }

    enum class PatchState {
        INIT, SELECTING, CONFIGURING, PATCHING, CANCELLING, CANCELLED, FINISHED, ERROR
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
        object CancelPatch : ViewAction()
    }

    data class ProxyRequest(val targetVersionCode: Long?)

    var patchState by mutableStateOf(PatchState.INIT)
        private set

    var proxyRequest by mutableStateOf<ProxyRequest?>(null)
        private set

    var currentStage by mutableStateOf("")
        private set

    // パッチ設定
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

    private var patchJob: Job? = null
    private var preservedApkFiles: List<File>? = null

    lateinit var patchApp: AppInfo
        private set
    lateinit var patchOptions: Patcher.Options
        private set
    private lateinit var patchConfig: PatchConfig

    /**
     * 表示用のログ。追記はパッチプロセスからのコールバックスレッドで起きるため、
     * 可変リストを共有せず不変リストを差し替える。LazyColumn が読んでいる最中に
     * 縮むとインデックスが範囲外になる。
     */
    var logs by mutableStateOf<List<Pair<Int, String>>>(emptyList())
        private set

    private val logBuffer = ArrayDeque<Pair<Int, String>>()
    private val logsDirty = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            while (true) {
                delay(LOG_PUBLISH_INTERVAL_MS)
                if (logsDirty.compareAndSet(true, false)) publishLogs()
            }
        }
    }

    private fun updateLastLog(msg: String) {
        synchronized(logBuffer) {
            if (logBuffer.isNotEmpty()) logBuffer.removeLast()
            logBuffer.addLast(Log.INFO to msg)
        }
        logsDirty.set(true)
    }

    private fun applyStage(msg: String) {
        val marker = STAGE_MARKERS.firstOrNull { msg.contains(it.first) } ?: return
        updateStage(marker.second)
    }

    private fun onDownloadProgress(progress: ApkProxyService.DownloadProgress) {
        updateLastLog(progress.message)
        val detail = buildString {
            append("(${progress.fileIndex}/${progress.fileCount})")
            if (progress.percent != ApkProxyService.UNKNOWN_PERCENT) append(" ${progress.percent}%")
        }
        updateStage(R.string.patch_stage_downloading, detail)
    }

    /** ダウンロード中は毎秒十数回進捗が来るため、表示が変わるときだけ通知を更新する。 */
    private fun updateStage(@StringRes resId: Int, detail: String = "") {
        val stage = lspApp.getString(resId)
        val text = if (detail.isEmpty()) stage else "$stage $detail"
        if (text == currentStage) return
        currentStage = text
        PatchForegroundService.updateProgress(lspApp, text)
    }

    /** [MAX_LOG_LINES] を超えた分は古い行から捨てる。 */
    private fun appendLog(level: Int, msg: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(level to msg)
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        }
        logsDirty.set(true)
    }

    private fun clearLogs() {
        synchronized(logBuffer) { logBuffer.clear() }
        logsDirty.set(false)
        publishLogs()
    }

    private fun publishLogs() {
        logs = synchronized(logBuffer) { logBuffer.toList() }
    }

    private val logger = object : Logger() {
        override fun d(msg: String) {
            if (verbose) {
                Log.d(TAG, msg)
                appendLog(Log.DEBUG, msg)
            }
        }

        override fun i(msg: String) {
            Log.i(TAG, msg)
            appendLog(Log.INFO, msg)
            applyStage(msg)
        }

        override fun e(msg: String) {
            Log.e(TAG, msg)
            appendLog(Log.ERROR, msg)
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
                is ViewAction.CancelPatch -> cancelPatch()
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
        clearLogs()
        currentStage = ""
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

    /** アプリスコープで実行して画面を離れても継続させ、フォアグラウンドサービスでプロセスを保持する。 */
    private fun launchPatch() {
        if (patchJob?.isActive == true) return
        preservedApkFiles = lspApp.targetApkFiles?.toList()
        currentStage = lspApp.getString(R.string.patch_stage_preparing)
        PatchForegroundService.start(lspApp, currentStage)
        patchJob = lspApp.globalScope.launch(Dispatchers.Main.immediate) {
            logger.i("Launch Patch")
            val result = try {
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
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                logger.e(t.message.orEmpty())
                logger.e(t.stackTraceToString())
                PatchState.ERROR
            } finally {
                withContext(NonCancellable) { NeoPackageManager.cleanTmpApkDir() }
            }
            // 中止された場合の状態遷移と後始末は cancelPatch 側が受け持つ
            if (isActive) {
                patchState = result
                PatchForegroundService.stop(lspApp)
            }
        }
    }

    private fun cancelPatch() {
        val job = patchJob
        if (job == null || !job.isActive) {
            patchState = PatchState.CANCELLED
            return
        }
        patchState = PatchState.CANCELLING
        logger.i("[Patch] Aborting patch, cleaning up...")
        PatchForegroundService.updateProgress(lspApp, lspApp.getString(R.string.patch_cancelling))
        lspApp.globalScope.launch {
            abortPatch(job)
            patchState = PatchState.CANCELLED
        }
    }

    private suspend fun abortPatch(job: Job) {
        job.cancelAndJoin()
        patchJob = null
        Patcher.discardArtifacts(preservedApkFiles)
        preservedApkFiles = null
        PatchForegroundService.stop(lspApp)
    }

    override fun onCleared() {
        super.onCleared()
        val job = patchJob ?: return
        if (!job.isActive) return
        lspApp.globalScope.launch { abortPatch(job) }
    }

    private suspend fun downloadProxyApks(request: ProxyRequest): List<String> {
        val downloadedFiles = ApkProxyService(lspApp).downloadLineApksForPatcher(
            logger = logger,
            targetVersionCode = request.targetVersionCode,
            onProgress = ::onDownloadProgress,
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
