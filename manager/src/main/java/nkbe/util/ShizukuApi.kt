package nkbe.util

import android.app.IActivityManager
import android.content.ComponentName
import android.content.IntentSender
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.IPackageDataObserver
import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.IPackageManager
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstallerHidden
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.os.SystemProperties
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rikka.tools.refine.Refine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.ShizukuProvider
import rikka.shizuku.SystemServiceHelper
import top.nkbe.npatch.lspApp

/**
 * Thin wrapper around the Shizuku API that exposes the state of the
 * binder connection and permission, and provides access to privileged
 * system services (package manager, activity manager, package installer).
 */
object ShizukuApi {

    private const val PERMISSION_REQUEST_CODE = 114514
    private const val PREFS_PERMISSION_REQUESTED = "shizuku_permission_requested"
    private var initialized = false
    private val permissionPrompt = ShizukuPermissionPrompt(
        requestedBefore = { lspApp.prefs.getBoolean(PREFS_PERMISSION_REQUESTED, false) },
        rememberRequest = { lspApp.prefs.edit().putBoolean(PREFS_PERMISSION_REQUESTED, true).apply() },
        request = { requestCode ->
            runCatching { Shizuku.requestPermission(requestCode) }
                .onFailure { Log.w("ShizukuApi", "Failed to request Shizuku permission", it) }
        },
        automaticRequestCode = PERMISSION_REQUEST_CODE,
    )

    private fun IBinder.wrap() = ShizukuBinderWrapper(this)
    private fun IInterface.asShizukuBinder() = this.asBinder().wrap()

    private val iPackageManager: IPackageManager
        get() = IPackageManager.Stub.asInterface(getSystemService("package"))

    private val iActivityManager: IActivityManager
        get() = IActivityManager.Stub.asInterface(getSystemService("activity"))

    private val iPackageInstaller: IPackageInstaller
        get() = IPackageInstaller.Stub.asInterface(iPackageManager.packageInstaller.asShizukuBinder())

    private val packageInstaller: PackageInstaller
        get() {
            val userId = Process.myUserHandle().hashCode()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Refine.unsafeCast(
                    PackageInstallerHidden(iPackageInstaller, "com.android.shell", null, userId)
                )
            } else {
                Refine.unsafeCast(
                    PackageInstallerHidden(iPackageInstaller, "com.android.shell", userId)
                )
            }
        }

    var isBinderAvailable by mutableStateOf(false)
    var isPermissionGranted by mutableStateOf(false)

    val isReady: Boolean
        get() = isBinderAvailable && isPermissionGranted

    fun init() {
        if (initialized) {
            refreshState()
            return
        }
        initialized = true
        ShizukuProvider.enableMultiProcessSupport(true)
        Shizuku.addBinderReceivedListenerSticky {
            refreshState()
            permissionPrompt.onConnectionUpdated(isBinderAvailable, isPermissionGranted, wasPermissionDenied())
        }
        Shizuku.addBinderDeadListener {
            isBinderAvailable = false
            isPermissionGranted = false
        }
        // 権限リクエスト結果はここで一元管理する。
        // 画面側で個別にリスナーを登録すると、登録タイミングのずれや
        // refreshState() による上書きで、権限付与済みなのに
        // 「リクエスト」表示のままになることがある。
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            isPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
        }
    }

    fun refreshState() {
        isBinderAvailable = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        isPermissionGranted = if (isBinderAvailable) {
            // サーバークエリが一時的に失敗しても直前の状態を保持する
            // (付与直後に false で上書きして「リクエスト」表示のままになるのを防ぐ)
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(isPermissionGranted)
        } else {
            false
        }
    }

    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        refreshState()
        permissionPrompt.requestManually(isBinderAvailable, requestCode)
    }

    fun onManagerResumed() {
        refreshState()
        permissionPrompt.onManagerResumed(isBinderAvailable, isPermissionGranted, wasPermissionDenied())
    }

    fun onManagerPaused() {
        permissionPrompt.onManagerPaused()
    }

    private fun wasPermissionDenied(): Boolean {
        return isBinderAvailable && runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)
    }

    fun addRequestPermissionResultListener(listener: (Int, Int) -> Unit) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun addOnReadyListener(listener: () -> Unit) {
        Shizuku.addBinderReceivedListenerSticky {
            refreshState()
            if (isReady) listener()
        }
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            if (isBinderAvailable && grantResult == PackageManager.PERMISSION_GRANTED) listener()
        }
    }

    fun removeRequestPermissionResultListener(listener: (Int, Int) -> Unit) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    fun getVersionOrNull(): Int? {
        return if (isBinderAvailable) runCatching { Shizuku.getVersion() }.getOrNull() else null
    }

    fun getSystemService(name: String): IBinder {
        ensureReady()
        return SystemServiceHelper.getSystemService(name).wrap()
    }

    fun bindUserService(
        componentName: ComponentName,
        connection: ServiceConnection,
        tag: String,
        version: Int,
        daemon: Boolean = true,
    ) {
        ensureReady()
        val args =
            Shizuku.UserServiceArgs(componentName)
                .tag(tag)
                .version(version)
                .daemon(daemon)
        Shizuku.bindUserService(args, connection)
    }

    fun unbindUserService(
        componentName: ComponentName,
        connection: ServiceConnection,
        tag: String,
        version: Int,
        remove: Boolean = false,
    ) {
        val args = Shizuku.UserServiceArgs(componentName).tag(tag).version(version)
        Shizuku.unbindUserService(args, connection, remove)
    }

    private fun ensureReady() {
        refreshState()
        check(isBinderAvailable) { "Shizuku binder is not available" }
        check(isPermissionGranted) { "Shizuku permission is not granted" }
    }

    fun getInstalledApplications(): List<ApplicationInfo> {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val flags = PackageManager.GET_META_DATA.toLong()
        return iPackageManager.getInstalledApplications(flags, userId).list
    }

    fun getInstalledPackages(flags: Int): List<PackageInfo> {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        return iPackageManager.getInstalledPackages(flags.toLong(), userId).list
    }

    fun createPackageInstallerSession(
        params: PackageInstaller.SessionParams
    ): PackageInstaller.Session {
        ensureReady()
        val sessionId = packageInstaller.createSession(params)
        val iSession =
            IPackageInstallerSession.Stub.asInterface(
                iPackageInstaller.openSession(sessionId).asShizukuBinder()
            )
        return Refine.unsafeCast(PackageInstallerHidden.SessionHidden(iSession))
    }

    fun isPackageInstalledWithoutPatch(packageName: String): Boolean {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val app = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            iPackageManager.getApplicationInfo(
                packageName,
                PackageManager.GET_META_DATA.toLong(),
                userId,
            )
        } else {
            iPackageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA, userId)
        }
        return (app != null) && (app.metaData?.containsKey("npatch") != true)
    }

    fun uninstallPackage(packageName: String, intentSender: IntentSender) {
        ensureReady()
        packageInstaller.uninstall(packageName, intentSender)
    }

    fun performDexOptMode(packageName: String): Boolean {
        ensureReady()
        return iPackageManager.performDexOptMode(
            packageName,
            SystemProperties.getBoolean("dalvik.vm.usejitprofiles", false),
            "verify", true, true, null
        )
    }

    fun forceStopPackage(packageName: String) {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        iActivityManager.forceStopPackage(packageName, userId)
    }

    fun clearApplicationUserData(packageName: String, observer: IPackageDataObserver) {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val method = iPackageManager.javaClass.getMethod(
            "clearApplicationUserData",
            String::class.java,
            IPackageDataObserver::class.java,
            Int::class.java
        )
        method.invoke(iPackageManager, packageName, observer, userId)
    }

    fun setApplicationEnabledSetting(packageName: String, newState: Int) {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val method = iPackageManager.javaClass.getMethod(
            "setApplicationEnabledSetting",
            String::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java,
            String::class.java
        )
        method.invoke(iPackageManager, packageName, newState, 0, userId, "com.android.shell")
    }

    fun getApplicationEnabledSetting(packageName: String): Int {
        ensureReady()
        val userId = Process.myUserHandle().hashCode()
        val method = iPackageManager.javaClass.getMethod(
            "getApplicationEnabledSetting",
            String::class.java,
            Int::class.java
        )
        return method.invoke(iPackageManager, packageName, userId) as Int
    }
}
