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

    private fun release(vararg assets: String, prerelease: Boolean = false, draft: Boolean = false, version: String = "1.2.0") =
        """{"tag_name":"v$version","body":"Changes","prerelease":$prerelease,"draft":$draft,"assets":[${assets.joinToString(",") }]}"""

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

    @Test fun prereleasesRequireOptInEvenWhenReadingCachedRelease() {
        val json = release(asset("NPatch-v1.2.0-rc1-21-release.apk"), prerelease = true, version = "1.2.0-rc1")
        assertEquals("1.2.0-rc1", UpdatePolicy.parseLatestRelease(json, false, includePrereleases = true)?.version)
        assertNull(UpdatePolicy.parseLatestRelease(json, false))
        val draft = release(asset("NPatch-v1.2.0-20-release.apk"), prerelease = true, draft = true)
        assertNull(UpdatePolicy.parseLatestRelease(draft, false, includePrereleases = true))
    }

    @Test fun newestCompatibleReleaseWinsRegardlessOfApiOrder() {
        val stable = release(asset("NPatch-v1.2.0-20-release.apk"))
        val rc2 = release(asset("NPatch-v1.3.0-rc2-22-release.apk"), prerelease = true, version = "1.3.0-rc2")
        val rc10 = release(asset("NPatch-v1.3.0-rc10-30-release.apk"), prerelease = true, version = "1.3.0-rc10")
        val feed = "[$rc2,$stable,$rc10]"
        assertEquals("1.2.0", UpdatePolicy.parseLatestRelease(feed, false)?.version)
        assertEquals("1.3.0-rc10", UpdatePolicy.parseLatestRelease(feed, false, includePrereleases = true)?.version)
        val final = release(asset("NPatch-v1.3.0-31-release.apk"), version = "1.3.0")
        assertEquals("1.3.0", UpdatePolicy.parseLatestRelease("[$rc10,$final]", false, includePrereleases = true)?.version)
    }

    @Test fun skipDraftsMissingApksAndUntrustedAssetsInReleaseFeed() {
        val stable = release(asset("NPatch-v1.2.0-20-release.apk"))
        val draft = release(asset("NPatch-v2.0.0-50-release.apk"), draft = true, version = "2.0.0")
        val untrusted = release(asset("NPatch-v3.0.0-60-release.apk", "https://example.com/app.apk"), version = "3.0.0")
        val missing = release(version = "4.0.0")
        assertEquals("1.2.0", UpdatePolicy.parseLatestRelease("[$draft,$untrusted,$missing,{},$stable]", false, includePrereleases = true)?.version)
        assertNull(UpdatePolicy.parseLatestRelease("[]", false, includePrereleases = true))
    }

    private fun rejects(json: String) {
        assertThrows(IllegalArgumentException::class.java) { UpdatePolicy.parseRelease(json, false) }
    }
}
