package top.nkbe.npatch.update

import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.android.apksig.ApkVerifier
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.R
import top.nkbe.npatch.install.ApkInstallSet
import top.nkbe.npatch.install.SystemInstallResult
import top.nkbe.npatch.install.SystemPackageInstaller
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.network.NetworkDns
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class UpdateState(
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val installing: Boolean = false,
    val installRequested: Boolean = false,
    val awaitingInstallPermission: Boolean = false,
    val progress: Int? = null,
    val release: UpdateRelease? = null,
    val file: File? = null,
    val message: String? = null,
    val showDialog: Boolean = false,
    val upToDate: Boolean = false,
) {
    internal fun shouldResumeInstallation(installPermissionGranted: Boolean): Boolean =
        installRequested || (awaitingInstallPermission && installPermissionGranted)

    internal fun beginInstallation(): UpdateState? {
        if (checking || downloading || installing || file == null || release == null) return null
        return copy(installing = true, message = null, showDialog = false,
            installRequested = false, awaitingInstallPermission = false)
    }
}

/** Process-owned work survives tab changes and Activity recreation. */
object AppUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(UpdateState(release = cachedRelease()))
    val state = mutableState.asStateFlow()
    private val preferences get() = lspApp.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    var automaticChecks: Boolean
        get() = preferences.getBoolean("automatic", true)
        set(value) { preferences.edit().putBoolean("automatic", value).apply() }

    var backgroundChecks: Boolean
        get() = preferences.getBoolean("background", true)
        set(value) {
            preferences.edit().putBoolean("background", value).apply()
            UpdateCheckWorker.schedule(lspApp)
        }

    private fun cachedRelease(): UpdateRelease? = runCatching {
        val json = preferences.getString("release_json", null) ?: return null
        UpdatePolicy.parseRelease(json, BuildConfig.DEBUG).takeIf {
            UpdatePolicy.compare(it.version, BuildConfig.VERSION_NAME)?.let { order -> order > 0 } == true
        }
    }.getOrNull()

    /** Shared by foreground checks and WorkManager; never opens an Activity. */
    internal suspend fun fetchLatest(): UpdateRelease = withContext(Dispatchers.IO) {
        client().newCall(Request.Builder()
            .url("https://api.github.com/repos/Nich87/NPatch/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NPatch-Manager")
            .build()).execute().use { response ->
            if (response.code == 404) throw UpdateError(R.string.app_update_no_release)
            if (!response.isSuccessful) throw UpdateError(R.string.app_update_check_failed)
            val json = response.body.string()
            val release = UpdatePolicy.parseRelease(json, BuildConfig.DEBUG)
            preferences.edit().putString("release_json", json).apply()
            release
        }
    }

    fun check(automatic: Boolean = false) {
        val old = mutableState.value
        if (old.checking || old.downloading || old.installing) return
        // Every foreground entry checks again; returning from the installer keeps the verified APK.
        if (automatic && (!automaticChecks || old.file != null)) return
        mutableState.update { it.copy(checking = true, message = null) }
        scope.launch {
            try {
                val release = fetchLatest()
                val newer = UpdatePolicy.compare(release.version, BuildConfig.VERSION_NAME)
                    ?: throw UpdateError(R.string.app_update_version_unknown)
                if (newer <= 0) UpdateNotifications.dismiss(lspApp)
                mutableState.update { current ->
                    if (newer > 0) current.copy(checking = false, release = release, upToDate = false,
                        file = current.file.takeIf { current.release == release },
                        showDialog = current.showDialog || !automatic)
                    else current.copy(checking = false, release = null, file = null, upToDate = true,
                        message = lspApp.getString(R.string.app_update_current))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(checking = false, upToDate = false,
                    message = errorMessage(e, R.string.app_update_check_failed)) }
            }
        }
    }

    fun showDialog(show: Boolean) { mutableState.update { it.copy(showDialog = show) } }

    /** Also handles a notification after process death, using the saved trusted release. */
    fun openDetails() {
        val current = mutableState.value
        if (!current.checking && !current.downloading && !current.installing) {
            // A worker may have fetched a newer release while the existing process was in the background.
            val release = cachedRelease()
            mutableState.update { it.copy(release = release,
                file = it.file?.takeIf { file -> file.isFile && it.release?.asset == release?.asset },
                showDialog = true) }
        } else showDialog(true)
        if (mutableState.value.release == null) check()
        UpdateNotifications.dismiss(lspApp)
    }

    fun download() {
        val old = mutableState.value
        val release = old.release ?: return
        if (old.checking || old.downloading || old.installing) return
        mutableState.value = old.copy(downloading = true, progress = 0, message = null, file = null,
            showDialog = false, installRequested = false, awaitingInstallPermission = false)
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(lspApp.cacheDir, "app_updates").apply { mkdirs() }
                    val partial = File(dir, "update.apk.part")
                    val target = File(dir, "update.apk")
                    try {
                        client().newCall(Request.Builder().url(release.asset.url).build()).execute().use { response ->
                            if (!response.isSuccessful || !response.request.url.isHttps) throw UpdateError(R.string.app_update_download_failed)
                            val digest = MessageDigest.getInstance("SHA-256")
                            var received = 0L
                            var lastProgress = -1
                            response.body.byteStream().use { input ->
                                partial.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        coroutineContext.ensureActive()
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        received += count
                                        if (received > release.asset.size) throw UpdateError(R.string.app_update_invalid_apk)
                                        digest.update(buffer, 0, count)
                                        output.write(buffer, 0, count)
                                        val progress = (received * 100 / release.asset.size).toInt()
                                        if (progress != lastProgress) {
                                            lastProgress = progress
                                            mutableState.update { it.copy(progress = progress) }
                                        }
                                    }
                                }
                            }
                            if (received != release.asset.size) throw UpdateError(R.string.app_update_invalid_apk)
                            release.asset.digest?.let { expected ->
                                val actual = "sha256:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                                if (!actual.equals(expected, true)) throw UpdateError(R.string.app_update_invalid_apk)
                            }
                        }
                        validateApk(lspApp, partial, release)
                        if (target.exists() && !target.delete()) throw UpdateError(R.string.app_update_download_failed)
                        if (!partial.renameTo(target)) throw UpdateError(R.string.app_update_download_failed)
                        target
                    } finally { partial.delete() }
                }
                mutableState.update { it.copy(downloading = false, progress = null, file = file,
                    installRequested = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("NPatch-Updater", "Update download failed", e)
                mutableState.update { it.copy(downloading = false, progress = null,
                    message = errorMessage(e, R.string.app_update_download_failed)) }
            }
        }
    }

    /** Called only while the Activity is resumed, so Android can show its confirmation UI. */
    fun resumePendingInstallation() {
        val current = mutableState.value
        if (current.shouldResumeInstallation(
                current.awaitingInstallPermission && lspApp.packageManager.canRequestPackageInstalls())) {
            install()
        }
    }

    fun install() {
        val current = mutableState.value
        val started = current.beginInstallation() ?: return
        val file = requireNotNull(current.file)
        val release = requireNotNull(current.release)
        mutableState.value = started
        scope.launch {
            try {
                val installSet = withContext(Dispatchers.IO) {
                    validateApk(lspApp, file, release)
                    ApkInstallSet.fromFiles(lspApp, listOf(file))
                }
                when (val result = SystemPackageInstaller.install(lspApp, installSet)) {
                    SystemInstallResult.PermissionRequired -> mutableState.update {
                        it.copy(awaitingInstallPermission = true,
                            message = lspApp.getString(R.string.app_update_install_permission))
                    }
                    is SystemInstallResult.Completed -> when (result.status) {
                        PackageInstaller.STATUS_SUCCESS -> mutableState.update {
                            it.copy(release = null, file = null, upToDate = true)
                        }
                        PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
                        else -> {
                            Log.e("NPatch-Updater", "Update install failed: ${result.status}: ${result.message}")
                            mutableState.update { it.copy(message = lspApp.getString(R.string.app_update_install_failed)) }
                        }
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.e("NPatch-Updater", "Unable to start update installation", e)
                mutableState.update { it.copy(message = errorMessage(e, R.string.app_update_install_failed)) }
            } finally {
                mutableState.update { it.copy(installing = false) }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(context: Context, file: File, release: UpdateRelease) {
        if (!file.isFile || file.length() != release.asset.size ||
            !ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
                .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify().isVerified) {
            throw UpdateError(R.string.app_update_invalid_apk)
        }
        val pm = context.packageManager
        val apk = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: throw UpdateError(R.string.app_update_invalid_apk)
        val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (apk.packageName != context.packageName || UpdatePolicy.compare(apk.versionName.orEmpty(), release.version) != 0 ||
            UpdatePolicy.compare(apk.versionName.orEmpty(), installed.versionName.orEmpty())?.let { it > 0 } != true ||
            PackageInfoCompat.getLongVersionCode(apk) < PackageInfoCompat.getLongVersionCode(installed)) {
            throw UpdateError(R.string.app_update_invalid_apk)
        }
        val candidate = apk.signingInfo ?: throw UpdateError(R.string.app_update_invalid_apk)
        val existing = installed.signingInfo ?: throw UpdateError(R.string.app_update_invalid_apk)
        val oldSigners = existing.apkContentsSigners.toSet()
        val compatible = if (candidate.hasMultipleSigners() || existing.hasMultipleSigners()) {
            candidate.apkContentsSigners.toSet() == oldSigners
        } else {
            candidate.signingCertificateHistory.toSet().containsAll(oldSigners)
        }
        if (!compatible) throw UpdateError(R.string.app_update_signature_mismatch)
    }

    private fun client() = NetworkDns.client().newBuilder().callTimeout(10, TimeUnit.MINUTES)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private fun errorMessage(error: Exception, fallback: Int) = lspApp.getString((error as? UpdateError)?.resource ?: fallback)
    private class UpdateError(val resource: Int) : Exception()
}
