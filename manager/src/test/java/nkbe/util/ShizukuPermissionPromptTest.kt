package nkbe.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuPermissionPromptTest {
    @Test fun firstResumeWithBinderRequestsExactlyOnce() {
        val history = RequestHistory()
        val prompt = history.newPrompt()

        prompt.onManagerResumed(true, false, false)
        prompt.onManagerResumed(true, false, false)
        prompt.onConnectionUpdated(true, false, false)

        assertTrue(history.requestedBefore)
        assertEquals(listOf(AUTOMATIC_REQUEST_CODE), history.requestCodes)
    }

    @Test fun denialAndRepeatedLifecycleOrBinderEventsDoNotRequestAgain() {
        val history = RequestHistory()
        val prompt = history.newPrompt()
        prompt.onManagerResumed(true, false, false)

        prompt.onConnectionUpdated(true, false, true)
        prompt.onManagerPaused()
        prompt.onManagerResumed(true, false, true)
        prompt.onConnectionUpdated(false, false, false)
        prompt.onConnectionUpdated(true, false, false)

        assertEquals(listOf(AUTOMATIC_REQUEST_CODE), history.requestCodes)
    }

    @Test fun recreatedControllerUsesPersistedHistory() {
        val history = RequestHistory()
        history.newPrompt().onManagerResumed(true, false, false)

        val recreated = history.newPrompt()
        recreated.onManagerResumed(true, false, false)
        recreated.onConnectionUpdated(true, false, false)

        assertTrue(history.requestedBefore)
        assertEquals(listOf(AUTOMATIC_REQUEST_CODE), history.requestCodes)
    }

    @Test fun absentBinderDoesNotConsumeAutomaticRequestOpportunity() {
        val history = RequestHistory()
        val prompt = history.newPrompt()

        prompt.onManagerResumed(false, false, false)
        prompt.onConnectionUpdated(false, false, false)
        assertFalse(history.requestedBefore)
        assertTrue(history.requestCodes.isEmpty())

        prompt.onConnectionUpdated(true, false, false)
        assertEquals(listOf(AUTOMATIC_REQUEST_CODE), history.requestCodes)
    }

    @Test fun binderArrivingWhilePausedWaitsUntilResume() {
        val history = RequestHistory()
        val prompt = history.newPrompt()
        prompt.onManagerResumed(false, false, false)
        prompt.onManagerPaused()

        prompt.onConnectionUpdated(true, false, false)
        assertFalse(history.requestedBefore)
        assertTrue(history.requestCodes.isEmpty())

        prompt.onManagerResumed(true, false, false)
        assertEquals(listOf(AUTOMATIC_REQUEST_CODE), history.requestCodes)
    }

    @Test fun manualRequestsAndRetriesBypassConsumedHistoryWithCustomCodes() {
        val history = RequestHistory()
        history.newPrompt().onManagerResumed(true, false, false)
        val recreated = history.newPrompt()

        recreated.requestManually(true, 42)
        recreated.requestManually(true, 73)
        recreated.onManagerResumed(true, false, false)

        assertEquals(listOf(AUTOMATIC_REQUEST_CODE, 42, 73), history.requestCodes)
    }

    @Test fun unavailableManualRequestDoesNotConsumeAutomaticRequestOpportunity() {
        val history = RequestHistory()
        val prompt = history.newPrompt()

        prompt.requestManually(false, 42)
        assertFalse(history.requestedBefore)
        assertTrue(history.requestCodes.isEmpty())

        prompt.onManagerResumed(true, false, false)
        assertEquals(listOf(AUTOMATIC_REQUEST_CODE), history.requestCodes)
    }

    @Test fun existingGrantConsumesHistoryAndSuppressesLaterRevocationPrompt() {
        val history = RequestHistory()
        val prompt = history.newPrompt()

        prompt.onManagerResumed(true, true, false)
        assertTrue(history.requestedBefore)
        assertTrue(history.requestCodes.isEmpty())

        prompt.onConnectionUpdated(true, false, false)
        history.newPrompt().onManagerResumed(true, false, false)
        assertTrue(history.requestCodes.isEmpty())
    }

    @Test fun previousDenialConsumesHistoryEvenWhenRationaleLaterDisappears() {
        val history = RequestHistory()
        val prompt = history.newPrompt()

        prompt.onManagerResumed(true, false, true)
        assertTrue(history.requestedBefore)
        assertTrue(history.requestCodes.isEmpty())

        prompt.onConnectionUpdated(true, false, false)
        history.newPrompt().onManagerResumed(true, false, false)
        assertTrue(history.requestCodes.isEmpty())
    }

    @Test fun automaticAndManualRequestsPersistHistoryBeforeDispatch() {
        val automaticHistory = RequestHistory()
        automaticHistory.newPrompt().onManagerResumed(true, false, false)
        assertEquals(listOf(true), automaticHistory.historyAtDispatch)

        val manualHistory = RequestHistory()
        val prompt = manualHistory.newPrompt()
        prompt.requestManually(true, 42)
        assertEquals(listOf(true), manualHistory.historyAtDispatch)
        assertEquals(listOf(42), manualHistory.requestCodes)

        prompt.onManagerResumed(true, false, false)
        assertEquals(listOf(42), manualHistory.requestCodes)
    }

    private class RequestHistory {
        var requestedBefore = false
        val requestCodes = mutableListOf<Int>()
        val historyAtDispatch = mutableListOf<Boolean>()

        fun newPrompt() = ShizukuPermissionPrompt(
            requestedBefore = { requestedBefore },
            rememberRequest = { requestedBefore = true },
            request = { code ->
                historyAtDispatch.add(requestedBefore)
                requestCodes.add(code)
            },
            automaticRequestCode = AUTOMATIC_REQUEST_CODE,
        )
    }

    private companion object {
        const val AUTOMATIC_REQUEST_CODE = 100
    }
}
