package top.nkbe.npatch.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun numericVersionsDoNotUseLexicalOrder() {
        assertTrue(UpdatePolicy.compare("v1.10.0", "1.9.9")!! > 0)
        assertEquals(0, UpdatePolicy.compare("1.2", "1.2.0"))
        assertTrue(UpdatePolicy.compare("1.0.0", "2.0.0")!! < 0)
        assertTrue(UpdatePolicy.compare("1.999999999999999999999", "1.2")!! > 0)
        assertTrue(UpdatePolicy.compare("1.0.0-beta", "1.0.0")!! < 0)
        assertTrue(UpdatePolicy.compare("1.0.6", "1.0.6-rc4")!! > 0)
        assertTrue(UpdatePolicy.compare("1.0.6-rc10", "1.0.6-rc9")!! > 0)
        assertTrue(UpdatePolicy.compare("1.0.6-4-beta", "1.0.6-3-beta")!! > 0)
        assertEquals(0, UpdatePolicy.compare("1.0.6+build2", "1.0.6+build3"))
        assertNull(UpdatePolicy.compare("unknown", "1.0.0"))
    }

    private fun asset(name: String, url: String = "https://github.com/Nich87/NPatch/releases/download/v1.2.0/$name", size: Long = 1234) =
        """{"name":"$name","browser_download_url":"$url","size":$size,"digest":null}"""

    private fun release(vararg assets: String, prerelease: Boolean = false, draft: Boolean = false) =
        """{"tag_name":"v1.2.0","body":"Changes","prerelease":$prerelease,"draft":$draft,"assets":[${assets.joinToString(",") }]}"""

    @Test fun selectCorrectBuildAndAllowReleaseFallbackForDebug() {
        val stable = asset("NPatch-v1.2.0-20-release.apk")
        val debug = asset("NPatch-v1.2.0-20-debug.apk")
        assertEquals("NPatch-v1.2.0-20-release.apk", UpdatePolicy.parseRelease(release(stable, debug), false).asset.name)
        assertEquals("NPatch-v1.2.0-20-debug.apk", UpdatePolicy.parseRelease(release(stable, debug), true).asset.name)
        assertEquals("1.2.0", UpdatePolicy.parseRelease(release(stable), true).version)
    }

    @Test fun ignoreUnrelatedApksAndUntrustedDownloads() {
        val valid = asset("NPatch-v1.2.0-20-release.apk")
        val other = asset("Knot.apk")
        val older = asset("NPatch-v1.1.0-19-release.apk")
        assertEquals("Changes", UpdatePolicy.parseRelease(release(valid, other, older), false).notes)
        rejects(release(asset("NPatch-v1.2.0-20-release.apk", "http://github.com/Nich87/NPatch/releases/download/v1.2.0/a.apk")))
        rejects(release(asset("NPatch-v1.2.0-20-release.apk", "https://github.com/other/repo/releases/download/v1.2.0/a.apk")))
        rejects(release(asset("NPatch-v1.2.0-20-release.apk", size = 0)))
        rejects(release(asset("NPatch-v1.2.0-20-release.apk", size = 1024L * 1024 * 1024)))
    }

    @Test fun rejectAmbiguousMissingAndUnstableReleases() {
        val valid = asset("NPatch-v1.2.0-20-release.apk")
        rejects(release())
        rejects(release(valid, asset("NPatch-v1.2.0-21-release.apk")))
        rejects(release(valid, prerelease = true))
        rejects(release(valid, draft = true))
    }

    private fun rejects(json: String) {
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parseRelease(json, false) }
    }
}
