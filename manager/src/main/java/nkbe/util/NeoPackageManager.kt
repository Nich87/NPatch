package nkbe.util

import android.R
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstallerHidden.SessionParamsHidden
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import me.zhanghai.android.appiconloader.AppIconLoader
import top.nkbe.npatch.config.ConfigManager
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.install.ApkInstallSet
import top.nkbe.npatch.install.SystemInstallResult
import top.nkbe.npatch.install.SystemPackageInstaller
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.share.Constants
import java.io.File
import java.io.IOException
import java.text.Collator
import java.util.Collections
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Central manager for app discovery, icons, Xposed module detection and
 * APK install/uninstall operations.
 */
object NeoPackageManager {

    private const val TAG = "NeoPackageManager"
    private const val SETTINGS_CATEGORY = "de.robv.android.xposed.category.MODULE_SETTINGS"

    const val STATUS_USER_CANCELLED = -2

    enum class InstallMethod { SHIZUKU, SYSTEM }

    sealed class InstallOutcome {
        data class Completed(val status: Int, val message: String?) : InstallOutcome()
        data object PermissionRequired : InstallOutcome()
    }

    @Parcelize
    class AppInfo(
        val app: ApplicationInfo,
        val label: String,
        val moduleMetadata: ModuleMetadataSnapshot? = null,
        val versionCode: Long = 0L,
        val versionName: String? = null,
    ) : Parcelable {
        val isXposedModule: Boolean
            get() = moduleMetadata != null
    }

    var appList by mutableStateOf(listOf<AppInfo>())
        private set

    fun clearMemoryCache() {
        appIcon.clear()
        appList = emptyList()
    }

    @SuppressLint("StaticFieldLeak")
    private val iconLoader = AppIconLoader(lspApp.resources.getDimensionPixelSize(R.dimen.app_icon_size), false, lspApp)
    private val appIcon = Collections.synchronizedMap(mutableMapOf<String, ImageBitmap>())

    /**
     * Loads the list of installed applications, preferring the Shizuku
     * package manager when available and falling back to the standard one.
     */
    suspend fun fetchAppList() {
        val result = withContext(Dispatchers.IO) {
            val pm = lspApp.packageManager
            val collection = mutableListOf<AppInfo>()
            val packages: List<android.content.pm.PackageInfo>

            if (ShizukuApi.isReady) {
                Log.i(TAG, "Fetching app list using Shizuku API")
                packages = runCatching {
                    ShizukuApi.getInstalledPackages(PackageManager.GET_META_DATA)
                }.getOrElse { t ->
                    Log.e(TAG, "Shizuku failed to fetch app list, falling back to standard PM", t)
                    pm.getInstalledPackages(PackageManager.GET_META_DATA)
                }
            } else {
                Log.i(TAG, "Fetching app list using standard PackageManager")
                packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
            }

            // パッケージごとのメタデータ読み取り (APK の Zip 検査を含む) を並列化して高速化。
            // 全パッケージを同時に開くとスレッドが枯渇するため、並列度は 8 に制限する。
            val io = Dispatchers.IO.limitedParallelism(8)
            val processed = packages.map { pkgInfo ->
                async(io) {
                    val appInfo = pkgInfo.applicationInfo ?: return@async null
                    val label = pm.getApplicationLabel(appInfo)
                    val moduleMetadata = runCatching {
                        ModuleMetadataReader.read(pkgInfo, pm)
                    }.getOrNull()
                    AppInfo(
                        appInfo,
                        label.toString(),
                        moduleMetadata,
                        pkgInfo.longVersionCode,
                        pkgInfo.versionName,
                    )
                }
            }.mapNotNull { it.await() }
            collection.addAll(processed)

            collection.sortWith(compareBy(Collator.getInstance(Locale.getDefault()), AppInfo::label))
            val modules = buildMap {
                collection.forEach { if (it.isXposedModule) put(it.app.packageName, it.app.sourceDir) }
            }
            ConfigManager.updateModules(modules)
            collection
        }
        withContext(Dispatchers.Main.immediate) {
            appIcon.keys.retainAll(result.map { it.app.packageName }.toSet())
            appList = result
        }
    }

    fun getIcon(appInfo: AppInfo): ImageBitmap =
        appIcon[appInfo.app.packageName] ?: loadIconBitmap(appInfo.app).also {
            appIcon[appInfo.app.packageName] = it
        }

    private fun loadIconBitmap(appInfo: ApplicationInfo): ImageBitmap =
        runCatching { iconLoader.loadIcon(appInfo).asImageBitmap() }.getOrElse {
            Log.w(TAG, "Failed to load icon for ${appInfo.packageName}", it)
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

    suspend fun cleanTmpApkDir() {
        withContext(Dispatchers.IO) {
            lspApp.tmpApkDir.listFiles()?.forEach(File::delete)
        }
    }

    suspend fun cleanExternalTmpApkDir() {
        withContext(Dispatchers.IO) {
            lspApp.externalCacheDir?.listFiles()?.forEach(File::delete)
        }
    }

    /**
     * Installs the patched APK set produced by the last patch run, falling
     * back to the configured storage directory when no fresh set is available.
     */
    suspend fun install(method: InstallMethod): InstallOutcome {
        Log.i(TAG, "Perform install patched apks via $method")
        val installSetResult = withContext(Dispatchers.IO) {
            runCatching {
                val files = lspApp.targetApkFiles.orEmpty()
                if (files.isNotEmpty()) {
                    ApkInstallSet.fromFiles(lspApp, files)
                } else {
                    buildInstallSetFromStorage()
                }
            }
        }
        val installSet = installSetResult.getOrElse { error ->
            Log.e(TAG, "Failed to build install set", error)
            return InstallOutcome.Completed(
                PackageInstaller.STATUS_FAILURE,
                error.message + "\n" + error.stackTraceToString(),
            )
        }
        return commitSession(method, installSet) {
            installSet.entries.forEach { entry ->
                Log.d(TAG, "Add ${entry.sessionName}")
                entry.file.inputStream().use { input ->
                    openWrite(entry.sessionName, 0, entry.file.length()).use { output ->
                        input.copyTo(output)
                        fsync(output)
                    }
                }
            }
        }
    }

    /**
     * Builds an install set from the patched files found in the configured
     * storage directory, extracting split APKs from `.apks` archives.
     */
    private fun buildInstallSetFromStorage(): ApkInstallSet {
        val uri = Configs.storageDirectory?.toUri() ?: throw IOException("Uri is null")
        val root = DocumentFile.fromTreeUri(lspApp, uri) ?: throw IOException("DocumentFile is null")
        val files = mutableListOf<File>()
        root.listFiles().forEach { file ->
            val name = file.name ?: return@forEach
            when {
                name.endsWith(Constants.PATCH_FILE_SUFFIX) -> files += copyDocumentToTempFile(file.uri, name)
                name.endsWith(Constants.PATCH_ARCHIVE_SUFFIX) -> {
                    val archive = copyDocumentToTempFile(file.uri, name)
                    files += extractApkArchive(archive, name)
                }
            }
        }
        if (files.isEmpty()) throw IOException("No patched APK files found in storage directory")
        return ApkInstallSet.fromFiles(lspApp, files)
    }

    /**
     * Installs a single local APK file (e.g. a downloaded module APK).
     */
    suspend fun installApkFile(file: File, method: InstallMethod): InstallOutcome {
        Log.i(TAG, "Perform install apk file ${file.name} via $method")
        val installSet = withContext(Dispatchers.IO) {
            runCatching { ApkInstallSet.fromFiles(lspApp, listOf(file)) }
        }.getOrElse { error ->
            return InstallOutcome.Completed(
                PackageInstaller.STATUS_FAILURE,
                error.message + "\n" + error.stackTraceToString(),
            )
        }
        return commitSession(method, installSet) {
            Log.d(TAG, "Add ${file.name}")
            file.inputStream().use { input ->
                openWrite(file.name, 0, input.available().toLong()).use { output ->
                    input.copyTo(output)
                    fsync(output)
                }
            }
        }
    }

    /**
     * Creates a package installer session for [method], writes the entries
     * produced by [writeEntries], commits the session and waits for the result.
     */
    private suspend fun commitSession(
        method: InstallMethod,
        installSet: ApkInstallSet? = null,
        writeEntries: PackageInstaller.Session.() -> Unit,
    ): InstallOutcome {
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        var permissionRequired = false
        withContext(Dispatchers.IO) {
            runCatching {
                if (method == InstallMethod.SYSTEM) {
                    when (val result = SystemPackageInstaller.install(lspApp, requireNotNull(installSet))) {
                        is SystemInstallResult.Completed -> {
                            status = result.status
                            message = result.message
                        }
                        SystemInstallResult.PermissionRequired -> permissionRequired = true
                    }
                    return@runCatching
                }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    installSet?.let {
                        setAppPackageName(it.packageName)
                        setSize(it.totalSize)
                    }
                }
                var flags = Refine.unsafeCast<SessionParamsHidden>(params).installFlags
                flags = flags or PackageManagerHidden.INSTALL_ALLOW_TEST or PackageManagerHidden.INSTALL_REPLACE_EXISTING
                Refine.unsafeCast<SessionParamsHidden>(params).installFlags = flags
                val session = ShizukuApi.createPackageInstallerSession(params)
                session.use {
                    writeEntries(session)
                    val channel = kotlinx.coroutines.channels.Channel<Intent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                    val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                        channel.trySend(intent)
                    }
                    val intentSender = IntentSenderHelper.newIntentSender(adapter)
                    session.commit(intentSender)

                    var intent = channel.receive()
                    var currentStatus = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

                    if (currentStatus == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        @Suppress("DEPRECATION")
                        val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        if (confirmIntent != null) {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            lspApp.startActivity(confirmIntent)
                            // Wait for the next intent after the user responds to the prompt
                            intent = channel.receive()
                            currentStatus = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                        }
                    }

                    status = currentStatus
                    message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    channel.close()
                }
            }.onFailure {
                if (it is CancellationException) throw it
                status = PackageInstaller.STATUS_FAILURE
                message = it.message + "\n" + it.stackTraceToString()
            }
        }
        return if (permissionRequired) InstallOutcome.PermissionRequired
        else InstallOutcome.Completed(status, message)
    }

    suspend fun uninstall(packageName: String): Pair<Int, String?> {
        var status = PackageInstaller.STATUS_FAILURE
        var message: String? = null
        withContext(Dispatchers.IO) {
            runCatching {
                var result: Intent? = null
                suspendCoroutine { cont ->
                    val adapter = IntentSenderHelper.IIntentSenderAdaptor { intent ->
                        result = intent
                        cont.resume(Unit)
                    }
                    val intentSender = IntentSenderHelper.newIntentSender(adapter)
                    ShizukuApi.uninstallPackage(packageName, intentSender)
                }
                result?.let {
                    status = it.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                    message = it.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                } ?: throw IOException("Intent is null")
            }.onFailure {
                status = PackageInstaller.STATUS_FAILURE
                message = "Exception happened\n$it"
            }
        }
        return Pair(status, message)
    }

    suspend fun forceStop(packageName: String): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                ShizukuApi.forceStopPackage(packageName)
                true
            }.getOrDefault(false)
        }
    }

    /**
     * Reads app information from a set of APK/APKS/XAPK files, extracting
     * split APKs from archives when necessary.
     */
    suspend fun getAppInfoFromApks(apks: List<Uri>): Result<List<AppInfo>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                var primary: ApplicationInfo? = null
                val splits = mutableListOf<String>()
                val appInfos = mutableListOf<AppInfo>()

                apks.forEachIndexed { index, uri ->
                    val src = DocumentFile.fromSingleUri(lspApp, uri)
                        ?: throw IOException("DocumentFile is null")
                    val srcName = src.name ?: "selected-$index.apk"
                    val copiedName = if (isApksArchive(srcName)) {
                        "$index-$srcName"
                    } else {
                        sanitizeVisibleFileName(srcName)
                    }
                    val copiedFile = copyDocumentToTempFile(uri, copiedName)
                    val candidates =
                        if (isApksArchive(srcName)) extractApkArchive(copiedFile, srcName)
                        else listOf(copiedFile)

                    var uriPrimary: ApplicationInfo? = null
                    candidates.forEach { candidate ->
                        val appInfo = lspApp.packageManager.getPackageArchiveInfo(
                            candidate.absolutePath, PackageManager.GET_META_DATA
                        )?.applicationInfo
                        appInfo?.sourceDir = candidate.absolutePath
                        if (appInfo == null || uriPrimary != null) {
                            splits.add(candidate.absolutePath)
                            return@forEach
                        }
                        uriPrimary = appInfo
                        if (primary == null) primary = appInfo
                        val label = lspApp.packageManager.getApplicationLabel(appInfo).toString()
                        appInfos.add(AppInfo(appInfo, label))
                    }
                }

                primary?.splitSourceDirs = splits.toTypedArray()
                if (appInfos.isEmpty()) throw IOException("No apks")
                appInfos
            }.recoverCatching { t ->
                cleanTmpApkDir()
                Log.e(TAG, "Failed to load apks", t)
                throw t
            }
        }
    }

    private fun copyDocumentToTempFile(uri: Uri, fileName: String): File {
        val dst = uniqueTempFile(fileName)
        lspApp.contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("InputStream is null")
            dst.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return dst
    }

    private fun isApksArchive(fileName: String): Boolean {
        val lower = fileName.lowercase(Locale.ROOT)
        return lower.endsWith(".apks") || lower.endsWith(".xapk") || lower.endsWith(".apkm")
    }

    private fun extractApkArchive(archiveFile: File, archiveName: String): List<File> {
        val extracted = mutableListOf<File>()
        val prefix = sanitizeVisibleFileName(archiveName.substringBeforeLast('.', archiveName))
            .ifEmpty { "archive" }

        ZipFile(archiveFile).use { zipFile ->
            val entries = Collections.list(zipFile.entries())
                .filter { entry ->
                    !entry.isDirectory && entry.name.lowercase(Locale.ROOT).endsWith(".apk")
                }
                .sortedWith(
                    compareBy<java.util.zip.ZipEntry> { entry ->
                        val name = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                        if (name == "base.apk") 0 else 1
                    }.thenBy { entry -> entry.name.lowercase(Locale.ROOT) }
                )
            if (entries.isEmpty()) {
                throw IOException("No APK entries found in archive: $archiveName")
            }

            entries.forEachIndexed { index, entry ->
                val entryName = entry.name.substringAfterLast('/').ifEmpty { "part-$index.apk" }
                val lowerName = entryName.lowercase(Locale.ROOT)
                val outName = when {
                    lowerName == "base.apk" -> "base_${prefix}.apk"
                    lowerName.startsWith("split_") -> "split_${prefix}_${sanitizeVisibleFileName(entryName)}"
                    else -> "split_${prefix}_${sanitizeVisibleFileName(entryName)}"
                }
                val dst = uniqueTempFile(outName)
                zipFile.getInputStream(entry).use { input ->
                    dst.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                extracted.add(dst)
            }
        }
        archiveFile.delete()
        return extracted
    }

    private fun sanitizeVisibleFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("[\\p{Cntrl}]"), "")
            .trim()
        return cleaned.ifEmpty { "unnamed.apk" }
    }

    private fun uniqueTempFile(fileName: String): File {
        val baseName = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var candidate = lspApp.tmpApkDir.resolve(fileName)
        var index = 1
        while (candidate.exists()) {
            val nextName = if (ext.isEmpty()) "$baseName($index)" else "$baseName($index).$ext"
            candidate = lspApp.tmpApkDir.resolve(nextName)
            index++
        }
        return candidate
    }

    fun getLaunchIntentForPackage(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(Intent.CATEGORY_INFO)
        intentToResolve.setPackage(packageName)
        var ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) {
            intentToResolve.removeCategory(Intent.CATEGORY_INFO)
            intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER)
            intentToResolve.setPackage(packageName)
            ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)
        }

        if (ris.size <= 0) return null

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name
            )
    }

    fun getSettingsIntent(packageName: String): Intent? {
        val intentToResolve = Intent(Intent.ACTION_MAIN)
        intentToResolve.addCategory(SETTINGS_CATEGORY)
        intentToResolve.setPackage(packageName)
        val ris = lspApp.packageManager.queryIntentActivities(intentToResolve, 0)

        if (ris.size <= 0) return getLaunchIntentForPackage(packageName)

        return Intent(intentToResolve)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setClassName(
                ris[0].activityInfo.packageName,
                ris[0].activityInfo.name
            )
    }
}
