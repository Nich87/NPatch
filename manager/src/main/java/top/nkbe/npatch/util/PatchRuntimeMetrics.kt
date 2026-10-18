package top.nkbe.npatch.util

import android.os.Debug
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import kotlinx.parcelize.Parcelize
import top.nkbe.npatch.patcher.PatcherClient

data class PatchRuntimeSample(
    val memoryBytes: Long?,
    val ioBytesPerSecond: Long?,
    val cpuPercent: Float?,
)

@Parcelize
data class ProcessRuntimeSnapshot(
    val pid: Int,
    val startedAtMillis: Long,
    val timeMillis: Long,
    val memoryBytes: Long?,
    val cpuMillis: Long?,
    val ioBytes: Long?,
) : Parcelable

internal fun readProcessRuntimeSnapshot(): ProcessRuntimeSnapshot {
    val memory = readRuntimeCounter("memory") { Debug.getPss().takeIf { it > 0 }?.times(1024) }
    val io = readRuntimeCounter("storage I/O") { ProcessStorageIo.readBytes().takeIf { it >= 0 } }
    return ProcessRuntimeSnapshot(
        pid = Process.myPid(),
        startedAtMillis = Process.getStartElapsedRealtime(),
        timeMillis = SystemClock.elapsedRealtime(),
        memoryBytes = memory,
        cpuMillis = readRuntimeCounter("CPU") { Process.getElapsedCpuTime() },
        ioBytes = io,
    )
}

internal fun counterRate(previous: Long, current: Long, elapsedMillis: Long): Double? {
    if (previous < 0 || current < previous || elapsedMillis <= 0) return null
    return (current - previous).toDouble() * 1_000 / elapsedMillis
}

internal fun aggregateRuntimeSamples(
    previous: List<ProcessRuntimeSnapshot>,
    current: List<ProcessRuntimeSnapshot>,
    processorCount: Long?,
    baselines: List<ProcessRuntimeSnapshot> = emptyList(),
): PatchRuntimeSample {
    val lastByProcess = (baselines + previous).associateBy { it.pid to it.startedAtMillis }
    val continuing = current.mapNotNull { now ->
        lastByProcess[now.pid to now.startedAtMillis]?.let { it to now }
    }
    fun rate(counter: (ProcessRuntimeSnapshot) -> Long?): Double? = continuing.map { (last, now) ->
        val before = counter(last)
        val after = counter(now)
        if (before != null && after != null) counterRate(before, after, now.timeMillis - last.timeMillis) else null
    }.completeSum()
    return PatchRuntimeSample(
        memoryBytes = current.map { it.memoryBytes?.toDouble() }.completeSum()?.toLong(),
        ioBytesPerSecond = rate { it.ioBytes }?.toLong(),
        cpuPercent = processorCount?.takeIf { it > 0 }?.let { cores ->
            rate { it.cpuMillis }?.let { (it / 10 / cores).toFloat().coerceIn(0f, 100f) }
        },
    )
}

private fun List<Double?>.completeSum(): Double? =
    if (isNotEmpty() && all { it != null }) sumOf { requireNotNull(it) } else null

internal class PatchRuntimeMetrics(
    private val workerBaseline: () -> ProcessRuntimeSnapshot? = PatcherClient::runtimeBaseline,
    private val workerSnapshot: () -> ProcessRuntimeSnapshot? = PatcherClient::runtimeSnapshot,
) {
    private val startedAtMillis = SystemClock.elapsedRealtime()
    private val processorCount = readRuntimeCounter("processor count") {
        Os.sysconf(OsConstants._SC_NPROCESSORS_ONLN).takeIf { it > 0 }
    }
    private var previous = emptyList<ProcessRuntimeSnapshot>()
    private var previousWorker: ProcessRuntimeSnapshot? = null

    fun sample(): PatchRuntimeSample {
        val worker = workerSnapshot()?.takeIf { it.timeMillis >= startedAtMillis && it != previousWorker }
        val snapshots = listOfNotNull(readProcessRuntimeSnapshot(), worker)
        val baselines = listOfNotNull(workerBaseline())
        return aggregateRuntimeSamples(previous, snapshots, processorCount, baselines).also {
            previous = snapshots
            if (worker != null) previousWorker = worker
        }
    }
}

private inline fun <T> readRuntimeCounter(name: String, read: () -> T): T? = try {
    read()
} catch (error: Exception) {
    Log.w("PatchRuntimeMetrics", "Unable to read $name", error)
    null
}
