package top.nkbe.npatch.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Debug
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.nkbe.npatch.patcher.PatcherService
import top.nkbe.npatch.patcher.IPatcherService
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PatchRuntimeMetricsDeviceTest {
    @Test fun cpuAndIoRemainAvailableWithThePatcherProcessRunning() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CountDownLatch(1)
        var service: IPatcherService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = IPatcherService.Stub.asInterface(binder)
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        assertTrue(context.bindService(Intent(context, PatcherService::class.java), connection, Context.BIND_AUTO_CREATE))
        val output = File(context.cacheDir, "runtime-metrics-io-test")
        try {
            assertTrue(connected.await(10, TimeUnit.SECONDS))
            val worker = requireNotNull(service).runtimeSnapshot
            assertTrue("Worker must measure itself in a separate process", worker.pid != Process.myPid())
            assertTrue("Worker PSS must be available", requireNotNull(worker.memoryBytes) > 0)
            assertTrue("Worker CPU must be available", requireNotNull(worker.cpuMillis) >= 0)
            assertTrue("Worker I/O must be available", requireNotNull(worker.ioBytes) >= 0)
            val metrics = PatchRuntimeMetrics { requireNotNull(service).runtimeSnapshot }
            metrics.sample()
            val bytes = ByteArray(4 * 1024 * 1024) { it.toByte() }
            FileOutputStream(output).use { it.write(bytes); it.fd.sync() }
            val deadline = SystemClock.elapsedRealtime() + 150
            var checksum = 1L
            while (SystemClock.elapsedRealtime() < deadline) checksum = checksum * 31 + 1
            val sample = metrics.sample()
            Log.i("PatchRuntimeMetricsTest", "worker bound sample=$sample checksum=$checksum")
            assertTrue("CPU must be measured while :patcher is bound: $sample", (sample.cpuPercent ?: 0f) > 0f)
            assertTrue("Storage writes must be measured while :patcher is bound: $sample", (sample.ioBytesPerSecond ?: 0L) > 0L)
        } finally {
            output.delete()
            context.unbindService(connection)
        }
    }

    @Test fun memoryReflectsAllocationsWithinTheSamplingInterval() {
        val metrics = PatchRuntimeMetrics()
        val before = requireNotNull(metrics.sample().memoryBytes)
        val allocation = ByteBuffer.allocateDirect(32 * 1024 * 1024)
        try {
            for (offset in 0 until allocation.capacity() step 4096) allocation.put(offset, 1)
            val after = requireNotNull(metrics.sample().memoryBytes)
            val current = Debug.MemoryInfo().also(Debug::getMemoryInfo).totalPss.toLong() * 1024
            Log.i("PatchRuntimeMetricsTest", "memory before=$before after=$after selfPss=$current")
            assertTrue("Memory must include a new 32 MiB allocation without waiting for ActivityManager's cache: before=$before after=$after selfPss=$current",
                after - before >= 24L * 1024 * 1024)
        } finally {
            allocation.clear()
        }
    }
}
