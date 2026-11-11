package top.nkbe.npatch.billing

import android.content.Context
import android.os.Binder
import android.util.Log
import org.matrix.vector.ipc.LoadedModule
import org.matrix.vector.ipc.ModuleCode
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.util.LocalInjectedModuleService
import top.nkbe.npatch.util.ModuleLoader

object BuiltinBillingModule {
    private var cachedCode: ModuleCode? = null

    @Synchronized
    fun forApp(context: Context, packageName: String): LoadedModule? {
        if (!BillingModuleScope.isEligible(context.packageManager, packageName)) return null
        if (BuildConfig.DEBUG && BuildConfig.OFFICIAL_PLAY_BILLING_TEST) {
            Log.i("NPatch-Billing", "Official Play Store test: embedded routing disabled for $packageName")
            return null
        }
        val manager = context.applicationInfo
        val moduleCode = cachedCode ?: ModuleLoader.loadModule(manager.sourceDir, 93)?.code?.also {
            cachedCode = it
        } ?: return null
        return LoadedModule().apply {
            code = moduleCode
            this.packageName = manager.packageName
            apkPath = manager.sourceDir
            applicationInfo = manager
            appId = manager.uid
            versionCode = context.packageManager.getPackageInfo(manager.packageName, 0).longVersionCode
            service = LocalInjectedModuleService(context, manager.packageName, Binder.getCallingUid())
        }
    }
}
