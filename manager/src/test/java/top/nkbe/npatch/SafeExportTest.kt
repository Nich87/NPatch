package top.nkbe.npatch

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SafeExportTest {
    @Test fun repeatExportKeepsCompletedFilesAndUsesNextAvailableName() = runBlocking {
        val documents = linkedMapOf("app.apk" to "old", "app (1).apk" to "older")
        val name = writeNewExport("app.apk", documents.keys.toSet(),
            create = { documents[it] = ""; it },
            write = { documents[it] = "new" },
            delete = { documents.remove(it) })
        assertEquals("app (2).apk", name)
        assertEquals(mapOf("app.apk" to "old", "app (1).apk" to "older", "app (2).apk" to "new"), documents)
    }

    @Test fun failedAndCancelledWritesRemoveOnlyTheNewDocument() = runBlocking {
        for (failure in listOf(IOException("disk full"), CancellationException("cancelled"), AssertionError("fatal"))) {
            val documents = linkedMapOf("app.apks" to "complete")
            try {
                writeNewExport("app.apks", documents.keys.toSet(),
                    create = { documents[it] = ""; it },
                    write = { documents[it] = "partial"; throw failure },
                    delete = { documents.remove(it) })
                fail("Expected write failure")
            } catch (actual: Throwable) {
                assertSame(failure, actual)
            }
            assertEquals(mapOf("app.apks" to "complete"), documents)
        }
    }

    @Test fun cleanupFailureDoesNotMaskWriteFailure() = runBlocking {
        val failure = IOException("write failed")
        val cleanupFailure = IOException("delete failed")
        try {
            writeNewExport("app.apk", emptySet(), create = { it },
                write = { throw failure }, delete = { throw cleanupFailure })
            fail("Expected write failure")
        } catch (actual: IOException) {
            assertSame(failure, actual)
            assertArrayEquals(arrayOf(cleanupFailure), actual.suppressed)
        }
    }

    @Test fun createFailureDoesNotDeleteAnyDocument() = runBlocking {
        var deletionAttempted = false
        try {
            writeNewExport<String>("app.apk", setOf("app.apk"),
                create = { throw IOException("access denied") },
                write = { fail("No document to write") },
                delete = { deletionAttempted = true })
            fail("Expected create failure")
        } catch (actual: IOException) {
            assertEquals("access denied", actual.message)
        }
        assertFalse(deletionAttempted)
    }

    @Test fun cancellationBeforeCompletionRemovesNewDocument() {
        val job = Job()
        val documents = linkedMapOf("app.apk" to "complete")
        assertThrows(CancellationException::class.java) {
            runBlocking(job) {
                writeNewExport("app.apk", documents.keys.toSet(),
                    create = { documents[it] = ""; it },
                    write = { documents[it] = "new"; job.cancel() },
                    delete = { documents.remove(it) })
            }
        }
        assertEquals(mapOf("app.apk" to "complete"), documents)
    }
}
