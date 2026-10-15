package top.nkbe.npatch

import android.content.pm.PackageManager
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.config.KeystorePreset
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.patcher.PatcherClient
import top.nkbe.npatch.share.Constants
import top.nkbe.npatch.share.PatchConfig
import top.nkbe.npatch.util.PatchRuntimeMetrics
import top.nkbe.npatch.util.PatchRuntimeSample
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class PatchExportFlowTest {
    @Test
    fun exportFailureAndRetryPreservePatchedApk() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val originalDirectory = Configs.storageDirectory
        val originalPreset = Configs.keyStorePreset
        val originalTargets = lspApp.targetApkFiles
        val originalTemporaryFiles = lspApp.tmpApkDir.listFiles().orEmpty().toSet()
        val cache = lspApp.externalCacheDir ?: lspApp.cacheDir
        val installRoot = File(cache, "npatch-install")
        val backupRoot = File(cache, "export-test-backup-${UUID.randomUUID()}")
        val hadInstallRoot = installRoot.exists()
        if (hadInstallRoot) {
            assertTrue("Preserve existing install cache", installRoot.renameTo(backupRoot))
        }
        val logger = object : Logger() {
            override fun d(msg: String) { Log.d(TAG, msg) }
            override fun i(msg: String) { Log.i(TAG, msg) }
            override fun e(msg: String) { Log.e(TAG, msg) }
        }
        try {
            lspApp.targetApkFiles = null
            Configs.keyStorePreset = KeystorePreset.NPATCH
            Configs.storageDirectory = DocumentsContract.buildTreeDocumentUri(
                "com.android.externalstorage.documents",
                "primary:NPatch-ungranted-${UUID.randomUUID()}",
            ).toString()
            val packageName = instrumentation.context.packageName
            val config = PatchConfig(
                true, false, false, 0, 2, null, null,
                false, true, packageName, false, false,
            )
            val samples = mutableListOf<PatchRuntimeSample>()
            val finalMetrics = PatchRuntimeMetrics()
            finalMetrics.sample()
            var observedWorker = false
            val sampling = launch(Dispatchers.IO) {
                val metrics = PatchRuntimeMetrics {
                    PatcherClient.runtimeSnapshot().also { if (it != null) observedWorker = true }
                }
                while (isActive) {
                    samples += metrics.sample()
                    delay(100)
                }
            }
            val result = try {
                withTimeout(120_000) {
                    Patcher.patch(
                        logger,
                        Patcher.Options(
                            newPackageName = packageName,
                            config = config,
                            apkPaths = listOf(instrumentation.context.applicationInfo.sourceDir),
                            embeddedModules = emptyList(),
                        ),
                    )
                }
            } finally {
                sampling.cancelAndJoin()
            }
            Log.i(TAG, "Patch runtime samples: $samples")
            val completedWorker = requireNotNull(PatcherClient.runtimeSnapshot())
            val initialWorker = requireNotNull(PatcherClient.runtimeBaseline())
            val finalSample = finalMetrics.sample()
            assertTrue("Retain the worker's final I/O after unbinding", completedWorker.ioBytes!! > initialWorker.ioBytes!!)
            assertTrue("The final sample must include the completed worker", finalSample.memoryBytes!! >= completedWorker.memoryBytes!!)
            assertTrue("Final CPU must include a patch completed between samples", (finalSample.cpuPercent ?: 0f) > 0f)
            assertTrue("Final I/O must include a patch completed between samples", (finalSample.ioBytesPerSecond ?: 0) > 0)
            assertTrue("Metrics must observe the active PatcherClient service", observedWorker)
            assertTrue("Memory must be available during patching", samples.all { (it.memoryBytes ?: 0) > 0 })
            assertTrue("CPU must reflect the patch workload", samples.any { (it.cpuPercent ?: 0f) > 0f })
            assertTrue("Storage I/O must reflect the patch workload", samples.any { (it.ioBytesPerSecond ?: 0) > 0 })

            assertTrue("Export failure must be reported separately", !result.exportError.isNullOrBlank())
            val outputs = requireNotNull(lspApp.targetApkFiles).toList()
            assertEquals(1, outputs.size)
            val output = outputs.single()
            assertTrue("Installable output must survive failed export", output.isFile && output.length() > 0)
            val info = lspApp.packageManager.getPackageArchiveInfo(
                output.path,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            assertNotNull("Output must be a parseable signed APK", info)
            assertEquals(packageName, info!!.packageName)
            assertTrue(requireNotNull(info.signingInfo).apkContentsSigners.isNotEmpty())
            ZipFile(output).use { zip ->
                assertNotNull("Output must contain the applied patch", zip.getEntry(Constants.CONFIG_ASSET_PATH))
                assertNotNull(zip.getEntry(Constants.LOADER_DEX_ASSET_PATH))
            }
            val digest = sha256(output)
            val modified = output.lastModified()
            val retryError = runCatching {
                withTimeout(30_000) { Patcher.exportPatchedApks(logger, packageName) }
            }.exceptionOrNull()

            assertNotNull("Retry must report the inaccessible destination", retryError)
            assertFalse("Retry must fail on export, not timeout", retryError is CancellationException)
            assertEquals("Retry must retain the same install set", outputs, lspApp.targetApkFiles)
            assertArrayEquals("Retry must not regenerate or damage the APK", digest, sha256(output))
            assertEquals("Retry must not rewrite the APK", modified, output.lastModified())
            Log.i(TAG, "PASS: signed patched APK retained unchanged after initial export and retry failure")
        } finally {
            Configs.storageDirectory = originalDirectory
            Configs.keyStorePreset = originalPreset
            lspApp.targetApkFiles = originalTargets
            val temporaryCleanupSucceeded = lspApp.tmpApkDir.listFiles().orEmpty()
                .filterNot { it in originalTemporaryFiles }
                .map { it.deleteRecursively() }.all { it }
            val installCleanupSucceeded = !installRoot.exists() || installRoot.deleteRecursively()
            val installRestoreSucceeded = !hadInstallRoot || backupRoot.renameTo(installRoot)
            assertTrue("Restore existing install cache", installRestoreSucceeded)
            assertTrue("Remove test install cache", installCleanupSucceeded)
            assertTrue("Remove test temporary file", temporaryCleanupSucceeded)
        }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private companion object {
        const val TAG = "PatchExportFlowTest"
    }
}
