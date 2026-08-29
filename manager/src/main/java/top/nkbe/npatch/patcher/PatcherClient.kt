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
            val lock = Any()
            var connection: ServiceConnection? = null
            var bound = false
            var service: IPatcherService? = null

            fun unbindLocked() {
                connection?.let { if (bound) runCatching { context.unbindService(it) } }
                connection = null
                bound = false
                service = null
            }

            fun finish(block: () -> Unit) {
                if (!settled.compareAndSet(false, true)) return
                synchronized(lock) { unbindLocked() }
                block()
            }

            fun abort() {
                val target = synchronized(lock) {
                    if (!settled.compareAndSet(false, true)) return
                    service
                }
                runCatching { target?.abort() }
                synchronized(lock) { unbindLocked() }
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
                    val failure = synchronized(lock) {
                        when {
                            settled.get() -> {
                                unbindLocked()
                                null
                            }
                            connected == null -> IOException("Patcher service is unavailable")
                            else -> {
                                service = connected
                                runCatching {
                                    connected.patch(
                                        options.configArgs(),
                                        options.inputApks.map { it.absolutePath }.toTypedArray(),
                                        options.newPackageName,
                                        verbose,
                                        callback,
                                    )
                                }.exceptionOrNull()
                            }
                        }
                    }
                    if (failure != null) {
                        finish { continuation.resumeWithException(failure) }
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
            continuation.invokeOnCancellation { abort() }

            val intent = Intent(context, PatcherService::class.java)
            val startFailed = synchronized(lock) {
                if (settled.get()) {
                    false
                } else {
                    connection = serviceConnection
                    bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                    if (!bound) connection = null
                    !bound
                }
            }
            if (startFailed) {
                finish {
                    continuation.resumeWithException(IOException("Unable to start the patcher process"))
                }
            }
        }
}
