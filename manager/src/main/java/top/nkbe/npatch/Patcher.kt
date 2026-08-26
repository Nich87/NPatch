package top.nkbe.npatch

import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.config.KeystorePreset
import top.nkbe.npatch.config.MyKeyStore
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.PatchConfig
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.patcher.PatcherClient
import java.io.File
import java.io.IOException
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

        fun toStringArray(inputApks: List<File> = apkPaths.map { File(it).absoluteFile }): Array<String> =
            configArgs() + inputApks.map { it.absolutePath }

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
                if (config.useMicroG) add("--useMicroG")
                if (config.hideLibs) add("--hidelibs")
                when (Configs.keyStorePreset) {
                    KeystorePreset.NPATCH -> add("-npa")
                    KeystorePreset.FPA -> add("-fpa")
                    KeystorePreset.CUSTOM -> addAll(arrayOf("-k", MyKeyStore.file.path, Configs.keyStorePassword, Configs.keyStoreAlias, Configs.keyStoreAliasPassword))
                }
            }.toTypedArray()
        }
    }

    suspend fun patch(logger: Logger, options: Options) {
        withContext(Dispatchers.IO) {
            // Merging and repackaging peak close to the heap ceiling, so they run in the
            // :patcher process. An OutOfMemoryError there kills that process instead of the
            // manager, and the binder death is reported as a patch failure.
            val orderedOutputs = PatcherClient.runInPatcherProcess(logger, options)

            val uri = Configs.storageDirectory?.toUri()
                ?: throw IOException("Uri is null")
            val root = DocumentFile.fromTreeUri(lspApp, uri)
                ?: throw IOException("DocumentFile is null")
            val installDir = createInstallSetDirectory()
            val apkFileList = orderedOutputs.map { tempApkFile ->
                moveToInstallSet(tempApkFile, installDir.resolve(tempApkFile.name))
            }

            try {
                if (apkFileList.size == 1) {
                    val patchedApkFile = apkFileList.first()
                    exportFile(
                        root = root,
                        source = patchedApkFile,
                        mimeType = "application/vnd.android.package-archive",
                        outputName = patchedApkFile.name,
                    )
                    logger.i("Patched apk is saved to ${root.uri.lastPathSegment}/${patchedApkFile.name}")
                } else {
                    val archiveName = buildArchiveName(options.newPackageName)
                    val localArchive = installDir.resolve(archiveName + ".tmp")
                    localArchive.outputStream().use { output ->
                        createApksArchive(output, apkFileList)
                    }
                    exportFile(
                        root = root,
                        source = localArchive,
                        mimeType = "application/octet-stream",
                        outputName = archiveName,
                    )
                    localArchive.delete()
                    logger.i("Patched archive is saved to ${root.uri.lastPathSegment}/$archiveName")
                }
                replaceInstallSet(apkFileList)
            } catch (error: Throwable) {
                installDir.deleteRecursively()
                throw error
            }
        }
    }

    private fun createInstallSetDirectory(): File {
        val cacheRoot = lspApp.externalCacheDir ?: lspApp.cacheDir
        val installRoot = cacheRoot.resolve("npatch-install")
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

    private fun replaceInstallSet(apkFiles: List<File>) {
        val previous = lspApp.targetApkFiles.orEmpty().toList()
        val currentDirectory = apkFiles.first().parentFile?.absoluteFile
        lspApp.targetApkFiles = ArrayList(apkFiles)
        previous
            .mapNotNull(File::getParentFile)
            .map(File::getAbsoluteFile)
            .filter { it != currentDirectory }
            .distinct()
            .forEach { directory ->
                if (directory.parentFile?.name == "npatch-install") {
                    directory.deleteRecursively()
                }
            }
    }

    private fun exportFile(
        root: DocumentFile,
        source: File,
        mimeType: String,
        outputName: String,
    ) {
        root.findFile(outputName)?.delete()
        val destination = root.createFile(mimeType, outputName)
            ?: throw IOException("Unable to create output file: $outputName")
        try {
            lspApp.contentResolver.openOutputStream(destination.uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("Unable to open an output stream: ${destination.uri}")
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun buildArchiveName(packageName: String): String {
        return packageName.replace(Regex("[\\\\/:*?\"<>|]"), "_") + Constants.PATCH_ARCHIVE_SUFFIX
    }

    private fun createApksArchive(output: OutputStream, apkFiles: List<File>) {
        require(apkFiles.isNotEmpty()) { "APK set is empty" }
        val duplicateNames = apkFiles.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }
        require(duplicateNames.isEmpty()) { "Duplicate APKS entries: ${duplicateNames.keys}" }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.setLevel(Deflater.NO_COMPRESSION)
            apkFiles.forEach { apkFile ->
                val entry = ZipEntry(apkFile.name).apply { time = 0L }
                zip.putNextEntry(entry)
                apkFile.inputStream().use { input ->
                    input.copyTo(zip)
                }
                zip.closeEntry()
            }
        }
    }
}
