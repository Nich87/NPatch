package nkbe.util

internal class ShizukuPermissionPrompt(
    private val requestedBefore: () -> Boolean,
    private val rememberRequest: () -> Unit,
    private val request: (Int) -> Unit,
    private val automaticRequestCode: Int,
) {
    private var managerResumed = false

    fun onManagerResumed(binderAvailable: Boolean, permissionGranted: Boolean, previouslyDenied: Boolean) {
        managerResumed = true
        onConnectionUpdated(binderAvailable, permissionGranted, previouslyDenied)
    }

    fun onManagerPaused() {
        managerResumed = false
    }

    fun onConnectionUpdated(binderAvailable: Boolean, permissionGranted: Boolean, previouslyDenied: Boolean) {
        if (!managerResumed || !binderAvailable || requestedBefore()) return
        rememberRequest()
        if (!permissionGranted && !previouslyDenied) request(automaticRequestCode)
    }

    fun requestManually(binderAvailable: Boolean, requestCode: Int) {
        if (!binderAvailable) return
        rememberRequest()
        request(requestCode)
    }
}
