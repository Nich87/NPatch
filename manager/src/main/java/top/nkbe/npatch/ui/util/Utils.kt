package top.nkbe.npatch.ui.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

fun checkIsApkFixedByLSP(context: Context, packageName: String): Boolean {
    return try {
        val app =
            context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        (app.metaData?.containsKey("npatch") != true)
    } catch (_: PackageManager.NameNotFoundException) {
        Log.e("NPatch", "Package not found: $packageName")
        false
    } catch (e: Exception) {
        Log.e("NPatch", "Unexpected error in checkIsApkFixedByLSP", e)
        false
    }
}
