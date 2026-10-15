package top.nkbe.npatch

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal suspend fun <T> writeNewExport(
    outputName: String,
    existingNames: Set<String>,
    create: (String) -> T,
    write: suspend (T) -> Unit,
    delete: (T) -> Unit,
): String {
    val dot = outputName.lastIndexOf('.').takeIf { it > 0 } ?: outputName.length
    val stem = outputName.substring(0, dot)
    val extension = outputName.substring(dot)
    var name = outputName
    var suffix = 1
    while (name in existingNames) name = "$stem (${suffix++})$extension"
    currentCoroutineContext().ensureActive()
    val destination = create(name)
    try {
        write(destination)
        currentCoroutineContext().ensureActive()
    } catch (error: Throwable) {
        try {
            delete(destination)
        } catch (cleanupError: Exception) {
            error.addSuppressed(cleanupError)
        }
        throw error
    }
    return name
}
