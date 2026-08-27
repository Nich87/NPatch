package top.nkbe.npatch.patcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import top.nkbe.npatch.Patcher
import top.nkbe.npatch.config.Configs
import top.nkbe.npatch.lspApp
import top.nkbe.npatch.patch.util.Logger
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Drives [PatcherService] in the `:patcher` process and mirrors its logs into [Logger].
 */
object PatcherClient {

    private const val TAG = "PatcherClient"

    /** Runs the patch in the patcher process and returns the produced APKs, in input order. */
    suspend fun runInPatcherProcess(logger: Logger, options: Patcher.Options): List<File> =
        suspendCancellableCoroutine { continuation ->
            val context = lspApp
            // NPatch used to set this while parsing -v, but it runs in the other process now.
            // Without it the patcher suppresses debug lines and this logger drops the rest.
            val verbose = Configs.detailPatchLogs
            logger.verbose = verbose
            val settled = AtomicBoolean(false)
            var connection: ServiceConnection? = null
            val service = AtomicReference<IPatcherService?>()

            fun unbind() {
                connection?.let { runCatching { context.unbindService(it) } }
                connection = null
                service.set(null)
            }

            fun abort() {
                if (settled.compareAndSet(false, true)) {
                    runCatching { service.get()?.abort() }
                    unbind()
                }
            }

            fun finish(block: () -> Unit) {
                if (settled.compareAndSet(false, true)) {
                    unbind()
                    block()
                }
            }

            val callback = object : IPatcherCallback.Stub() {
                override fun onLog(levels: IntArray, messages: Array<String>) {
                    for (index in messages.indices) {
                        when (levels.getOrNull(index)) {
                            Log.DEBUG -> logger.d(messages[index])
                            Log.ERROR -> logger.e(messages[index])
                            else -> logger.i(messages[index])
                        }
                    }
                }

                override fun onSuccess(outputApkPaths: Array<String>) {
                    val outputs = outputApkPaths.map { File(it) }
                    finish { continuation.resume(outputs) }
                }

                override fun onError(message: String) {
                    finish { continuation.resumeWithException(IOException(message)) }
                }
            }

            val serviceConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val connected = IPatcherService.Stub.asInterface(binder)
                    service.set(connected)
                    if (connected == null) {
                        finish {
                            continuation.resumeWithException(IOException("Patcher service is unavailable"))
                        }
                        return
                    }
                    runCatching {
                        connected.patch(
                            options.configArgs(),
                            options.inputApks.map { it.absolutePath }.toTypedArray(),
                            options.newPackageName,
                            verbose,
                            callback,
                        )
                    }.onFailure { error ->
                        finish { continuation.resumeWithException(error) }
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // The patcher process died mid-patch; OutOfMemoryError is the usual cause.
                    Log.e(TAG, "Patcher process died")
                    finish {
                        continuation.resumeWithException(
                            IOException(
                                "The patcher process stopped before finishing. " +
                                    "This usually means it ran out of memory.",
                            ),
                        )
                    }
                }
            }
            connection = serviceConnection

            continuation.invokeOnCancellation { abort() }

            val intent = Intent(context, PatcherService::class.java)
            val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                finish {
                    continuation.resumeWithException(IOException("Unable to start the patcher process"))
                }
            }
        }
}
