package top.nkbe.npatch.network.proxy

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.patch.util.Logger
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "ApkProxyService"

private const val CHANNEL_STABLE = "stable"
private const val CHANNEL_PRERELEASE = "prerelease"

private const val PROGRESS_INTERVAL_MS = 80L

private const val ABI_ARM64 = "arm64-v8a"
private const val ABI_ARM32 = "armeabi-v7a"

private const val CACHE_DIR_NAME = "proxy_line_apks"
private const val UNKNOWN_VERSION_DIR = "latest"
private const val PART_SUFFIX = ".part"

private val deviceAbi: String
    get() = if (Build.SUPPORTED_ABIS.any { it.contains("arm64") }) ABI_ARM64 else ABI_ARM32

private fun Long.toMbString(): String = String.format(Locale.US, "%.1f", this / (1024.0 * 1024.0))

data class VersionListResult(
    val recommended: Long? = null,
    val versions: List<Long> = emptyList(),
)

class ApkProxyService(
    private val context: Context,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val versionsUrl: String = DEFAULT_VERSIONS_URL,
    private val client: OkHttpClient = defaultClient,
) {
    suspend fun fetchAvailableVersions(
        includePrerelease: Boolean = Configs.includePrereleaseVersions,
    ): VersionListResult = withContext(Dispatchers.IO) {
        val targetChannel = if (includePrerelease) CHANNEL_PRERELEASE else CHANNEL_STABLE
        runCatching {
            val request = Request.Builder().url(versionsUrl).build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }

                val channels = JSONObject(response.body.string()).getJSONObject("channels")
                val channel = channels.optJSONObject(targetChannel)
                    ?: channels.getJSONObject(CHANNEL_STABLE)

                val versions = channel.optJSONArray("versions")
                    ?.let { array -> (0 until array.length()).map { array.optLong(it, 0L) } }
                    .orEmpty()
                    .filter { it > 0 }
                    .distinct()

                VersionListResult(
                    recommended = channel.optLong("recommended", 0L).takeIf { it > 0 }
                        ?: versions.firstOrNull(),
                    versions = versions,
                )
            }
        }.onFailure {
            Log.w(TAG, "Failed to fetch available versions from $versionsUrl", it)
        }.getOrDefault(VersionListResult())
    }

    data class DownloadProgress(
        val fileIndex: Int,
        val fileCount: Int,
        /** ファイル単位の進捗率。長さ不明なら [UNKNOWN_PERCENT]。 */
        val percent: Int,
        val message: String,
    )

    suspend fun downloadLineApksForPatcher(
        logger: Logger,
        targetVersionCode: Long? = null,
        fileNames: List<String> = getSplitFilesForDevice(context),
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): List<File> = withContext(Dispatchers.IO) {
        val report: (DownloadProgress) -> Unit = onProgress ?: { logger.i(it.message) }

        val resolvedVersionCode = targetVersionCode ?: resolveTargetVersionCode(logger)
        val vParam = resolvedVersionCode?.takeIf { it > 0 }?.let { "?v=$it" }.orEmpty()
        val versionDir = File(cacheDir(context), resolvedVersionCode?.toString() ?: UNKNOWN_VERSION_DIR)
        val cachedFiles = fileNames.map { File(versionDir, it) }

        logger.i("[Proxy] Device: ABI=$deviceAbi (${Build.SUPPORTED_ABIS.joinToString()}), DPI=${context.resources.displayMetrics.densityDpi}")
        logger.i("[Proxy] Target Version Code: ${resolvedVersionCode ?: "Latest"}")

        if (resolvedVersionCode != null && cachedFiles.all { it.isFile && it.length() > 0 }) {
            val totalBytes = cachedFiles.sumOf { it.length() }
            logger.i("[Proxy] Reusing cached APKs for $resolvedVersionCode (${totalBytes.toMbString()} MB), skipping download.")
            return@withContext cachedFiles
        }

        versionDir.mkdirs()
        logger.i("[Proxy] Connecting to $baseUrl...")

        fileNames.mapIndexed { index, fileName ->
            val fileIndex = index + 1
            val downloaded = downloadSplit(fileName, versionDir, vParam, fileIndex, fileNames.size, logger, report)
            report(
                DownloadProgress(
                    fileIndex = fileIndex,
                    fileCount = fileNames.size,
                    percent = 100,
                    message = "${filePrefix(fileIndex, fileNames.size)} $fileName " +
                        "(${downloaded.length().toMbString()} MB) downloaded successfully.",
                ),
            )
            downloaded
        }.also { keepOnlyCachedVersion(versionDir) }
    }

    private suspend fun downloadSplit(
        fileName: String,
        versionDir: File,
        vParam: String,
        fileIndex: Int,
        fileCount: Int,
        logger: Logger,
        report: (DownloadProgress) -> Unit,
    ): File {
        val targetFile = File(versionDir, fileName)
        val partFile = File(versionDir, "$fileName$PART_SUFFIX")
        val prefix = filePrefix(fileIndex, fileCount)

        logger.i("$prefix Downloading $fileName...")

        val request = Request.Builder().url("$baseUrl/apk/file/$fileName$vParam").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Failed to download $fileName (HTTP ${response.code})")
            }

            val body = response.body
            val totalBytes = body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    var fileBytes = 0L
                    var lastLoggedTime = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, bytesRead)
                        fileBytes += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastLoggedTime > PROGRESS_INTERVAL_MS || fileBytes == totalBytes) {
                            lastLoggedTime = now
                            val percent = if (totalBytes > 0) {
                                ((fileBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else {
                                UNKNOWN_PERCENT
                            }
                            val transferred = if (percent == UNKNOWN_PERCENT) {
                                "(${fileBytes.toMbString()} MB)"
                            } else {
                                "$percent% (${fileBytes.toMbString()} / ${totalBytes.toMbString()} MB)"
                            }
                            report(
                                DownloadProgress(
                                    fileIndex = fileIndex,
                                    fileCount = fileCount,
                                    percent = percent,
                                    message = "$prefix Downloading $fileName... $transferred",
                                ),
                            )
                        }
                    }
                }
            }
        }

        targetFile.delete()
        if (!partFile.renameTo(targetFile)) {
            throw IllegalStateException("Failed to finalize downloaded file $fileName")
        }
        return targetFile
    }

    private fun filePrefix(fileIndex: Int, fileCount: Int) = "[Proxy] [$fileIndex/$fileCount]"

    private fun keepOnlyCachedVersion(keep: File) {
        cacheDir(context).listFiles()
            ?.filter { it.name != keep.name }
            ?.forEach { it.deleteRecursively() }
    }

    private suspend fun resolveTargetVersionCode(logger: Logger): Long? {
        Configs.customLineVersionCodeOrNull?.let { customCode ->
            logger.i("[Proxy] Target Version: $customCode (Manual override from Settings ON)")
            return customCode
        }

        val includePrerelease = Configs.includePrereleaseVersions
        val recommended = fetchAvailableVersions(includePrerelease).recommended
        if (recommended != null) {
            val channel = if (includePrerelease) CHANNEL_PRERELEASE else CHANNEL_STABLE
            logger.i("[Proxy] Target Version: $recommended (Resolved from line-versions [$channel])")
            return recommended
        }

        logger.i("[Proxy] Target Version: Latest (from Cloud Proxy)")
        return null
    }

    companion object {
        const val UNKNOWN_PERCENT = -1

        const val DEFAULT_BASE_URL = "https://2ipper.com"
        const val DEFAULT_VERSIONS_URL =
            "https://raw.githubusercontent.com/2b-zipper/line-versions/main/versions.json"

        fun cacheDir(context: Context): File = File(context.cacheDir, CACHE_DIR_NAME)

        fun cachedVersionCode(context: Context): Long? =
            cacheDir(context).listFiles()
                ?.firstOrNull { it.isDirectory }
                ?.name
                ?.toLongOrNull()

        fun cacheSizeBytes(context: Context): Long =
            cacheDir(context).walkBottomUp().filter { it.isFile }.sumOf { it.length() }

        fun clearCache(context: Context) {
            cacheDir(context).deleteRecursively()
        }

        fun deletePartialDownloads(context: Context) {
            cacheDir(context).walkBottomUp()
                .filter { it.isFile && it.name.endsWith(PART_SUFFIX) }
                .forEach { it.delete() }
        }

        fun getSplitFilesForDevice(context: Context): List<String> {
            val densityDpi = context.resources.displayMetrics.densityDpi
            val dpiSplit = when {
                densityDpi <= DisplayMetrics.DENSITY_LOW -> "config.ldpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_MEDIUM -> "config.mdpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_TV -> "config.tvdpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_HIGH -> "config.hdpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_XHIGH -> "config.xhdpi.apk"
                densityDpi <= DisplayMetrics.DENSITY_XXHIGH -> "config.xxhdpi.apk"
                else -> "config.xxxhdpi.apk"
            }

            return listOf("base.apk", "config.${deviceAbi.replace('-', '_')}.apk", dpiSplit)
        }

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
