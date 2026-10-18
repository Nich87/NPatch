package top.nkbe.npatch

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.config.KeystorePreset
import top.nkbe.npatch.config.MyKeyStore
import top.nkbe.npatch.network.proxy.ApkProxyService
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.PatchConfig
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.patcher.PatcherClient
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Patcher {

    class Options(
        val newPackageName: String,
        private val config: PatchConfig,
        private val apkPaths: List<String>,
        private val embeddedModules: List<String>?
    ) {
        internal val inputApks: List<File>
            get() = apkPaths.map { File(it).absoluteFile }

        /** Patcher CLI arguments without the trailing input paths, which the merge may replace. */
        fun configArgs(): Array<String> {
            return buildList {
                add("-o"); add(lspApp.tmpApkDir.absolutePath)
                add("-p"); add(config.newPackage)
                if (config.debuggable) add("-d")
                add("-l"); add(config.sigBypassLevel.toString())
                if (config.useManager) add("--manager")
                if (config.overrideVersionCode) {
                    add("-r")
                    add("--versioncode"); add(config.overrideVersionCodeValue.toString())
                }
                if (Configs.detailPatchLogs) add("-v")
                embeddedModules?.forEach {
                    add("-m"); add(it)
                }
                if (config.injectProvider) add("--provider")
                if (config.useMicroG) {
                    add("--useMicroG")
                    add("--microgVendor"); add(config.microgVendor)
                }
                if (!config.outputLog) add("--no-output-log")
                if (config.usesCleartextTraffic) add("--cleartext")
                if (config.overrideTargetSdk) {
                    add("--override-target-sdk")
                    add("--target-sdk"); add(config.overrideTargetSdkValue.toString())
                }
                if (config.hideLibs) add("--hidelibs")
                when (Configs.keyStorePreset) {
                    KeystorePreset.NPATCH -> add("-npa")
                    KeystorePreset.FPA -> add("-fpa")
                    KeystorePreset.CUSTOM -> addAll(arrayOf("-k", MyKeyStore.file.path, Configs.keyStorePassword, Configs.keyStoreAlias, Configs.keyStoreAliasPassword))
                }
            }.toTypedArray()
        }
    }

    data class PatchResult(val exportError: String? = null)

    suspend fun patch(logger: Logger, options: Options): PatchResult = withContext(Dispatchers.IO) {
        // Keep the active set available for cancellation rollback until the caller finishes.
        val previous = lspApp.targetApkFiles.orEmpty().toList()
        val preservedDirs = previous.mapNotNull { it.parentFile?.absoluteFile }.toSet()
        installSetRoot.listFiles()?.filter { it.absoluteFile !in preservedDirs }
            ?.forEach { it.deleteRecursively() }
        val orderedOutputs = PatcherClient.runInPatcherProcess(logger, options)
        currentCoroutineContext().ensureActive()
        require(orderedOutputs.isNotEmpty()) { "APK set is empty" }
        val installDir = createInstallSetDirectory()
        val apkFiles = try {
            orderedOutputs.map { source ->
                currentCoroutineContext().ensureActive()
                moveToInstallSet(source, installDir.resolve(source.name))
            }.also { currentCoroutineContext().ensureActive() }
        } catch (error: Throwable) {
            installDir.deleteRecursively()
            throw error
        }
        lspApp.targetApkFiles = ArrayList(apkFiles)
        try {
            exportPatchedApks(logger, options.newPackageName)
            PatchResult()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            PatchResult(exportError = error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun exportPatchedApks(logger: Logger, packageName: String) = withContext(Dispatchers.IO) {
        val apkFiles = lspApp.targetApkFiles.orEmpty().toList()
        require(apkFiles.isNotEmpty() && apkFiles.all(File::isFile)) { "Patched APK set is unavailable" }
        val uri = Configs.storageDirectory?.toUri() ?: throw IOException("Uri is null")
        val root = DocumentFile.fromTreeUri(lspApp, uri) ?: throw IOException("DocumentFile is null")
        logger.i("Exporting patched apk...")
        val outputName = if (apkFiles.size == 1) {
            exportFile(root, apkFiles.first(), "application/vnd.android.package-archive", apkFiles.first().name)
        } else {
            val archiveName = buildArchiveName(packageName)
            val localArchive = File.createTempFile("export-", ".tmp", apkFiles.first().parentFile)
            try {
                localArchive.outputStream().use { createApksArchive(it, apkFiles) }
                exportFile(root, localArchive, "application/octet-stream", archiveName)
            } finally {
                localArchive.delete()
            }
        }
        logger.i("Patched output is saved to ${root.uri.lastPathSegment}/$outputName")
    }

    private val installSetRoot: File
        get() = (lspApp.externalCacheDir ?: lspApp.cacheDir).resolve("npatch-install")

    /** 中断で残った一時 APK と生成物を消し、[preservedApkFiles] のインストールセットへ戻す。 */
    suspend fun discardArtifacts(preservedApkFiles: List<File>?) {
        withContext(Dispatchers.IO + NonCancellable) {
            lspApp.tmpApkDir.listFiles()?.forEach { it.deleteRecursively() }
            ApkProxyService.deletePartialDownloads(lspApp)
            val preserved = preservedApkFiles?.takeIf { it.isNotEmpty() && it.all(File::isFile) }
            lspApp.targetApkFiles = preserved?.let { ArrayList(it) }
            val preservedDirs = preserved.orEmpty().mapNotNull { it.parentFile?.absoluteFile }.toSet()
            installSetRoot.listFiles()?.forEach { directory ->
                if (directory.absoluteFile !in preservedDirs) directory.deleteRecursively()
            }
        }
    }

    private fun createInstallSetDirectory(): File {
        val installRoot = installSetRoot
        if (!installRoot.exists() && !installRoot.mkdirs()) {
            throw IOException("Unable to create install cache: $installRoot")
        }
        return installRoot.resolve(UUID.randomUUID().toString()).also {
            if (!it.mkdirs()) throw IOException("Unable to create install set: $it")
        }
    }

    private fun moveToInstallSet(source: File, destination: File): File {
        if (source.renameTo(destination)) return destination
        source.copyTo(destination, overwrite = false)
        if (!source.delete()) {
            destination.delete()
            throw IOException("Unable to remove temporary patched APK: $source")
        }
        return destination
    }

    private suspend fun exportFile(
        root: DocumentFile,
        source: File,
        mimeType: String,
        outputName: String,
    ): String = writeNewExport(
        outputName = outputName,
        existingNames = root.listFiles().mapNotNull { it.name }.toSet(),
        create = { name -> root.createFile(mimeType, name) ?: throw IOException("Unable to create output file: $name") },
        write = { destination ->
            lspApp.contentResolver.openOutputStream(destination.uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyToCancellable(output) }
            } ?: throw IOException("Unable to open an output stream: ${destination.uri}")
        },
        delete = { it.delete() },
    )

    private fun buildArchiveName(packageName: String): String {
        return packageName.replace(Regex("[\\\\/:*?\"<>|]"), "_") + Constants.PATCH_ARCHIVE_SUFFIX
    }

    private suspend fun createApksArchive(output: OutputStream, apkFiles: List<File>) {
        require(apkFiles.isNotEmpty()) { "APK set is empty" }
        val duplicateNames = apkFiles.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }
        require(duplicateNames.isEmpty()) { "Duplicate APKS entries: ${duplicateNames.keys}" }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            apkFiles.forEach { apkFile ->
                val entry = ZipEntry(apkFile.name).apply { time = 0L }
                zip.putNextEntry(entry)
                apkFile.inputStream().use { input ->
                    input.copyToCancellable(zip)
                }
                zip.closeEntry()
            }
        }
    }

    private suspend fun InputStream.copyToCancellable(output: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
    }
}
