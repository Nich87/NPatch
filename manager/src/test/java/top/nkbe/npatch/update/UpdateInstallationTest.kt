package top.nkbe.npatch.update

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class UpdateInstallationTest {
    private val ready = UpdateState(
        release = UpdateRelease("1.2.0", "", UpdateAsset("update.apk", "https://github.com/", 1, null)),
        file = File("update.apk"),
    )

    @Test fun downloadedUpdateStartsOnceAndCancellationAllowsManualRetry() {
        val downloaded = ready.copy(installRequested = true, showDialog = true)
        assertTrue(downloaded.shouldResumeInstallation(false))
        val started = requireNotNull(downloaded.beginInstallation())
        assertFalse(started.showDialog)
        assertNull(started.beginInstallation())
        val cancelled = started.copy(installing = false)
        assertFalse(cancelled.shouldResumeInstallation(true))
        assertEquals(ready.file, cancelled.file)
        assertNotNull(cancelled.beginInstallation())
    }

    @Test fun permissionDenialDoesNotLoopAndGrantResumesOnce() {
        val awaiting = ready.copy(awaitingInstallPermission = true)
        assertFalse(awaiting.shouldResumeInstallation(false))
        assertTrue(awaiting.shouldResumeInstallation(true))
        val started = requireNotNull(awaiting.beginInstallation())
        val cancelled = started.copy(installing = false)
        assertFalse(cancelled.shouldResumeInstallation(true))
    }

    @Test fun noInstallationUntilTheDownloadedApkIsReady() {
        assertNull(ready.copy(downloading = true).beginInstallation())
        assertNull(ready.copy(checking = true).beginInstallation())
        assertNull(ready.copy(file = null).beginInstallation())
        assertNull(ready.copy(release = null).beginInstallation())
    }
}
