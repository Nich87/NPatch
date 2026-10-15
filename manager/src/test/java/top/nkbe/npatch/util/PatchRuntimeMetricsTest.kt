package top.nkbe.npatch.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatchRuntimeMetricsTest {
    private fun snapshot(pid: Int = 1, start: Long = 1, time: Long = 1000,
        memory: Long? = 100, cpu: Long? = 100, io: Long? = 1000) =
        ProcessRuntimeSnapshot(pid, start, time, memory, cpu, io)

    @Test fun managerAndWorkerRatesUseTheirOwnSamplingIntervals() {
        val before = listOf(snapshot(), snapshot(pid = 2, time = 1500))
        val after = listOf(snapshot(time = 2000, cpu = 600, io = 3000),
            snapshot(pid = 2, time = 2000, cpu = 350, io = 2000))
        assertEquals(PatchRuntimeSample(200, 4000, 25f), aggregateRuntimeSamples(before, after, 4))
    }

    @Test fun workerStartingOrStoppingDoesNotDiscardManagerRates() {
        val before = listOf(snapshot())
        val manager = snapshot(time = 2000, cpu = 200, io = 3000)
        val worker = snapshot(pid = 2, time = 2000, memory = 900, cpu = 50000, io = 5000000)
        assertEquals(PatchRuntimeSample(1000, 2000, 5f), aggregateRuntimeSamples(before, listOf(manager, worker), 2))
        assertEquals(PatchRuntimeSample(100, 2000, 5f), aggregateRuntimeSamples(before + worker, listOf(manager), 2))
    }

    @Test fun reusedPidDoesNotContributeItsPreviousProcessCounters() {
        val before = listOf(snapshot(), snapshot(pid = 2))
        val after = listOf(snapshot(time = 2000, cpu = 200, io = 3000),
            snapshot(pid = 2, start = 2000, time = 2000, cpu = 50000, io = 5000000))
        assertEquals(PatchRuntimeSample(200, 2000, 5f), aggregateRuntimeSamples(before, after, 2))
    }

    @Test fun workerCompletingBetweenSamplesUsesItsInitialCounters() {
        val initialWorker = snapshot(pid = 2, time = 1200, cpu = 10, io = 100)
        val completedWorker = snapshot(pid = 2, time = 1700, cpu = 510, io = 2100)
        val manager = snapshot(time = 2000, cpu = 200, io = 3000)
        assertEquals(PatchRuntimeSample(200, 6000, 55f),
            aggregateRuntimeSamples(listOf(snapshot()), listOf(manager, completedWorker), 2, listOf(initialWorker)))
    }

    @Test fun missingCountersAreUnknownAndDoNotHideOtherMetrics() {
        val before = listOf(snapshot())
        val after = listOf(snapshot(time = 2000, memory = null, cpu = null, io = 3000))
        assertEquals(PatchRuntimeSample(null, 2000, null), aggregateRuntimeSamples(before, after, 2))
        assertEquals(PatchRuntimeSample(100, null, null), aggregateRuntimeSamples(emptyList(), before, 2))
        assertEquals(PatchRuntimeSample(null, null, null), aggregateRuntimeSamples(before, emptyList(), 2))
    }

    @Test fun rateRejectsCounterResetAndInvalidElapsedTime() {
        assertEquals(2000.0, counterRate(1000, 4000, 1500)!!, 0.0)
        assertNull(counterRate(4000, 1000, 1500))
        assertNull(counterRate(1000, 4000, 0))
    }
}
