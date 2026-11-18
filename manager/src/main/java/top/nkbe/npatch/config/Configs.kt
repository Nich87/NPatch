package top.nkbe.npatch.config

import top.nkbe.npatch.lspApp
import top.nkbe.npatch.ui.util.delegateStateOf
import top.nkbe.npatch.ui.util.getValue
import top.nkbe.npatch.ui.util.setValue
import java.io.File

object Configs {

    private const val PREFS_KEYSTORE_PRESET = "keystore_preset"
    private const val PREFS_KEYSTORE_PASSWORD = "keystore_password"
    private const val PREFS_KEYSTORE_ALIAS = "keystore_alias"
    private const val PREFS_KEYSTORE_ALIAS_PASSWORD = "keystore_alias_password"
    private const val PREFS_STORAGE_DIRECTORY = "storage_directory"
    private const val PREFS_DETAIL_PATCH_LOGS = "detail_patch_logs"
    private const val PREFS_LANGUAGE = "language"
    private const val PREFS_WEL_SKIP = "WEL_SKIP"
    private const val PREFS_OUTPUT_FULL_LOG = "output_full_log"
    private const val PREFS_USE_CUSTOM_LINE_VERSION = "use_custom_line_version"
    private const val PREFS_CUSTOM_LINE_VERSION_CODE = "custom_line_version_code"
    private const val PREFS_INCLUDE_PRERELEASE = "include_prerelease_versions"

    private fun defaultKeyStorePreset(): KeystorePreset {
        return if (File(lspApp.filesDir, "keystore.bks").exists()) {
            KeystorePreset.CUSTOM
        } else {
            KeystorePreset.NPATCH
        }
    }

    var keyStorePreset by delegateStateOf(
        KeystorePreset.fromPrefValue(
            lspApp.prefs.getString(PREFS_KEYSTORE_PRESET, null),
            defaultKeyStorePreset()
        )
    ) {
        lspApp.prefs.edit().putString(PREFS_KEYSTORE_PRESET, it.prefValue).apply()
    }

    var language by delegateStateOf(lspApp.prefs.getString(PREFS_LANGUAGE, "")!!) {
        lspApp.prefs.edit().putString(PREFS_LANGUAGE, it).apply()
    }

    var keyStorePassword by delegateStateOf(lspApp.prefs.getString(PREFS_KEYSTORE_PASSWORD, "123456")!!) {
        lspApp.prefs.edit().putString(PREFS_KEYSTORE_PASSWORD, it).apply()
    }

    var keyStoreAlias by delegateStateOf(lspApp.prefs.getString(PREFS_KEYSTORE_ALIAS, "key0")!!) {
        lspApp.prefs.edit().putString(PREFS_KEYSTORE_ALIAS, it).apply()
    }

    var keyStoreAliasPassword by delegateStateOf(lspApp.prefs.getString(PREFS_KEYSTORE_ALIAS_PASSWORD, "123456")!!) {
        lspApp.prefs.edit().putString(PREFS_KEYSTORE_ALIAS_PASSWORD, it).apply()
    }

    var storageDirectory by delegateStateOf(lspApp.prefs.getString(PREFS_STORAGE_DIRECTORY, null)) {
        lspApp.prefs.edit().putString(PREFS_STORAGE_DIRECTORY, it).apply()
    }

    var detailPatchLogs by delegateStateOf(lspApp.prefs.getBoolean(PREFS_DETAIL_PATCH_LOGS, true)) {
        lspApp.prefs.edit().putBoolean(PREFS_DETAIL_PATCH_LOGS, it).apply()
    }

    var welcomeSeen by delegateStateOf(lspApp.prefs.getBoolean(PREFS_WEL_SKIP, false)) {
        lspApp.prefs.edit().putBoolean(PREFS_WEL_SKIP, it).apply()
    }

    var outputFullLog by delegateStateOf(lspApp.prefs.getBoolean(PREFS_OUTPUT_FULL_LOG, false)) {
        lspApp.prefs.edit().putBoolean(PREFS_OUTPUT_FULL_LOG, it).apply()
    }

    var useCustomLineVersion by delegateStateOf(lspApp.prefs.getBoolean(PREFS_USE_CUSTOM_LINE_VERSION, false)) {
        lspApp.prefs.edit().putBoolean(PREFS_USE_CUSTOM_LINE_VERSION, it).apply()
    }

    var customLineVersionCode by delegateStateOf(lspApp.prefs.getString(PREFS_CUSTOM_LINE_VERSION_CODE, "")!!) {
        lspApp.prefs.edit().putString(PREFS_CUSTOM_LINE_VERSION_CODE, it).apply()
    }

    val customLineVersionCodeOrNull: Long?
        get() = if (useCustomLineVersion) {
            customLineVersionCode.trim().toLongOrNull()?.takeIf { it > 0 }
        } else {
            null
        }

    var includePrereleaseVersions by delegateStateOf(lspApp.prefs.getBoolean(PREFS_INCLUDE_PRERELEASE, false)) {
        lspApp.prefs.edit().putBoolean(PREFS_INCLUDE_PRERELEASE, it).apply()
        top.nkbe.npatch.update.KnotUpdateChecker.onPrereleaseChannelChanged()
    }
}
