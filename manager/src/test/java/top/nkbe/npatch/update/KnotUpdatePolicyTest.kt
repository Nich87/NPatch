package top.nkbe.npatch.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.nkbe.npatch.repo.KnotRelease

class KnotUpdatePolicyTest {
    private fun release(tag: String, prerelease: Boolean = false) = KnotRelease(tagName = tag, isPrerelease = prerelease)

    private val releases = listOf(
        release("v3.2.0-beta1", prerelease = true),
        release("v3.1.2"),
        release("v3.1.1"),
        release("v3.1.0-rc1", prerelease = true),
    )

    @Test fun picksNewestStableReleaseUnlessPrereleasesAreIncluded() {
        assertEquals("3.1.2", KnotUpdatePolicy.latestRelease(releases, includePrereleases = false)?.version)
        assertEquals("3.2.0-beta1", KnotUpdatePolicy.latestRelease(releases, includePrereleases = true)?.version)
    }

    @Test fun ignoresReleaseOrderAndUnparseableTags() {
        val shuffled = listOf(release("nightly"), release("v3.0.1"), release("v3.1.2"), release("v3.1.10"))
        assertEquals("3.1.10", KnotUpdatePolicy.latestRelease(shuffled, includePrereleases = false)?.version)
    }

    @Test fun reportsUpdateOnlyWhenNewerThanInstalledKnot() {
        assertEquals("3.1.2", KnotUpdatePolicy.findUpdate(releases, "3.1.1", false)?.version)
        assertEquals("3.1.2", KnotUpdatePolicy.findUpdate(releases, "v3.1.0", false)?.version)
        assertNull(KnotUpdatePolicy.findUpdate(releases, "3.1.2", false))
        assertNull(KnotUpdatePolicy.findUpdate(releases, "3.2.0", false))
        assertEquals("3.2.0-beta1", KnotUpdatePolicy.findUpdate(releases, "3.1.2", true)?.version)
    }

    @Test fun noUpdateWithoutInstalledOrComparableKnot() {
        assertNull(KnotUpdatePolicy.findUpdate(releases, null, false))
        assertNull(KnotUpdatePolicy.findUpdate(releases, "unknown", false))
        assertNull(KnotUpdatePolicy.findUpdate(emptyList(), "3.1.1", false))
    }

    @Test fun normalizesInstalledVersionNames() {
        assertEquals("3.1.2", KnotUpdatePolicy.normalizeVersion("v3.1.2"))
        assertEquals("3.1.2", KnotUpdatePolicy.normalizeVersion(" 3.1.2 (42)"))
        assertEquals("3.1.0-rc1", KnotUpdatePolicy.normalizeVersion("3.1.0-rc1"))
        assertNull(KnotUpdatePolicy.normalizeVersion("dev build"))
        assertNull(KnotUpdatePolicy.normalizeVersion(""))
    }
}
