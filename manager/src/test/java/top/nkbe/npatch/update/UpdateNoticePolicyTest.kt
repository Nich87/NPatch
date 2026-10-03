package top.nkbe.npatch.update

import org.junit.Assert.*
import org.junit.Test

class UpdateNoticePolicyTest {
    @Test fun notifyNewReleaseOnlyOnceAndDoNotRepeatAfterApiRollback() {
        assertTrue(UpdateNoticePolicy.shouldNotify("1.2.0", "1.1.0", null))
        assertFalse(UpdateNoticePolicy.shouldNotify("1.2.0", "1.1.0", "v1.2.0"))
        assertFalse(UpdateNoticePolicy.shouldNotify("1.1.5", "1.1.0", "1.2.0"))
        assertTrue(UpdateNoticePolicy.shouldNotify("1.3.0", "1.1.0", "1.2.0"))
    }

    @Test fun noNotificationForInstalledOlderOrUnknownRelease() {
        assertFalse(UpdateNoticePolicy.shouldNotify("1.2.0", "1.2.0", null))
        assertFalse(UpdateNoticePolicy.shouldNotify("1.1.0", "1.2.0", null))
        assertFalse(UpdateNoticePolicy.shouldNotify("unknown", "1.2.0", null))
        assertFalse(UpdateNoticePolicy.shouldNotify("1.2.0", "unknown", null))
    }

    @Test fun prereleaseProgressionAndCorruptNotificationHistory() {
        assertTrue(UpdateNoticePolicy.shouldNotify("1.0.6-rc10", "1.0.6-rc4", "1.0.6-rc9"))
        assertTrue(UpdateNoticePolicy.shouldNotify("1.0.6", "1.0.6-rc4", "1.0.6-rc10"))
        assertTrue(UpdateNoticePolicy.shouldNotify("1.2.0", "1.1.0", "unknown"))
    }
}
