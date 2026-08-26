package top.nkbe.npatch.patcher

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import top.nkbe.npatch.SplitMerger
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.patch.NPatch
import top.nkbe.npatch.patch.util.Logger
import top.nkbe.npatch.share.Constants
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs the patch in the `:patcher` process.
 *
 * Merging and repackaging peak close to the heap ceiling. In a process of their own they no
 * longer compete with the UI, and an OutOfMemoryError kills only this process.
 */
class PatcherService : Service() {

    override fun onBind(intent: Intent): IBinder = binder

    private val binder = object : IPatcherService.Stub() {
        override fun patch(
            configArgs: Array<String>,
            inputApkPaths: Array<String>,
            newPackageName: String,
            verbose: Boolean,
            callback: IPatcherCallback,
        ) {
            thread(name = "npatch-patcher") {
                runPatch(configArgs, inputApkPaths, newPackageName, verbose, callback)
            }
        }
    }

    private fun runPatch(
        configArgs: Array<String>,
        inputApkPaths: Array<String>,
        newPackageName: String,
        verbose: Boolean,
        callback: IPatcherCallback,
    ) {
        val logger = BatchingLogger(callback).apply { this.verbose = verbose }
        try {
            logger.start()
            var inputApks = inputApkPaths.map { File(it).absoluteFile }
            validateInputSet(inputApks)

            // Split APK sets are merged into a single APK before patching, so the patcher
            // produces one installable APK instead of an APKS archive.
            if (inputApks.size > 1) {
                logger.i("Merging ${inputApks.size} split APKs into a single APK...")
                val mergedApk = lspApp.tmpApkDir.resolve("$newPackageName.apk")
                mergedApk.delete()
                SplitMerger.mergeToSingleApk(inputApks, mergedApk, logger)
                inputApks = listOf(mergedApk)
            }

            val outputsBeforePatch = currentPatchOutputs()
            val argv = configArgs + inputApks.map { it.absolutePath }
            NPatch(logger, *argv).doCommandLine()

            val produced = currentPatchOutputs() - outputsBeforePatch
            val ordered = matchOutputsToInputs(inputApks, produced)
            logger.stop()
            callback.onSuccess(ordered.map { it.absolutePath }.toTypedArray())
        } catch (error: Throwable) {
            Log.e(TAG, "Patch failed", error)
            logger.stop()
            val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.name
            runCatching { callback.onError("$message\n${error.stackTraceToString()}") }
        }
    }

    private fun validateInputSet(inputApks: List<File>) {
        if (inputApks.isEmpty()) throw IOException("No input APK files")
        val missing = inputApks.filterNot(File::isFile)
        if (missing.isNotEmpty()) {
            throw IOException("Input APK does not exist: ${missing.joinToString { it.path }}")
        }
        val duplicateNames = inputApks
            .groupBy { it.nameWithoutExtension.lowercase() }
            .filterValues { it.size > 1 }
            .keys
        if (duplicateNames.isNotEmpty()) {
            throw IOException("Input APK names are ambiguous: ${duplicateNames.joinToString()}")
        }
    }

    private fun currentPatchOutputs(): Set<File> = lspApp.tmpApkDir
        .listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(Constants.PATCH_FILE_SUFFIX) }
        .map { it.absoluteFile }
        .toSet()

    private fun matchOutputsToInputs(inputApks: List<File>, producedOutputs: Set<File>): List<File> {
        if (producedOutputs.size != inputApks.size) {
            throw IOException(
                "Patched APK count mismatch: expected ${inputApks.size}, got ${producedOutputs.size}",
            )
        }
        val unmatched = producedOutputs.toMutableSet()
        return inputApks.map { input ->
            val outputPattern = Regex(
                "^${Regex.escape(input.nameWithoutExtension)}-[0-9]+" +
                    "${Regex.escape(Constants.PATCH_FILE_SUFFIX)}$",
                RegexOption.IGNORE_CASE,
            )
            val matches = unmatched.filter { outputPattern.matches(it.name) }
            if (matches.size != 1) {
                throw IOException("Cannot match patched output for ${input.name}")
            }
            matches.single().also(unmatched::remove)
        }.also {
            if (unmatched.isNotEmpty()) {
                throw IOException("Unexpected patched outputs: ${unmatched.joinToString { file -> file.name }}")
            }
        }
    }

    /**
     * Batches log lines over Binder. A verbose patch emits one line per zip entry, so sending
     * each on its own is tens of thousands of transactions. Size caps keep a flush under the
     * 1MB transaction limit.
     */
    private class BatchingLogger(private val callback: IPatcherCallback) : Logger() {

        private val levels = ArrayList<Int>(MAX_BATCH_LINES)
        private val messages = ArrayList<String>(MAX_BATCH_LINES)
        private var batchBytes = 0
        private var ticker: ScheduledExecutorService? = null

        /**
         * Starts the periodic flush. A batch must not wait for the next line: the merge logs the
         * module it is about to process, then goes quiet for tens of seconds.
         */
        fun start() {
            ticker = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "npatch-log-flush").apply { isDaemon = true }
            }.also {
                it.scheduleWithFixedDelay(
                    ::flush,
                    FLUSH_INTERVAL_MS,
                    FLUSH_INTERVAL_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }

        fun stop() {
            ticker?.shutdownNow()
            ticker = null
            flush()
        }

        override fun d(msg: String) {
            if (verbose) append(Log.DEBUG, msg)
        }

        override fun i(msg: String) = append(Log.INFO, msg)

        override fun e(msg: String) {
            append(Log.ERROR, msg)
            flush()
        }

        @Synchronized
        private fun append(level: Int, msg: String) {
            levels += level
            messages += msg
            batchBytes += msg.length * 2
            if (levels.size >= MAX_BATCH_LINES || batchBytes >= MAX_BATCH_BYTES) flushLocked()
        }

        @Synchronized
        fun flush() = flushLocked()

        private fun flushLocked() {
            if (levels.isEmpty()) return
            try {
                callback.onLog(levels.toIntArray(), messages.toTypedArray())
            } catch (error: RemoteException) {
                // The manager went away; keep patching.
                Log.w(TAG, "Dropping ${levels.size} log lines: ${error.message}")
            }
            levels.clear()
            messages.clear()
            batchBytes = 0
        }

        private companion object {
            const val MAX_BATCH_LINES = 200
            const val MAX_BATCH_BYTES = 128 * 1024
            const val FLUSH_INTERVAL_MS = 150L
        }
    }

    private companion object {
        const val TAG = "PatcherService"
    }
}
