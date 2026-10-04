package top.nkbe.npatch.update

/** Alert once for each newer version; API rollbacks must not repeat an older alert. */
object UpdateNoticePolicy {
    fun shouldNotify(version: String, installed: String, lastNotified: String?): Boolean {
        if (UpdatePolicy.compare(version, installed)?.let { it > 0 } != true) return false
        if (lastNotified == null) return true
        return UpdatePolicy.compare(version, lastNotified)?.let { it > 0 } ?: true
    }
}
