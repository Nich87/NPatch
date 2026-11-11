package top.nkbe.npatch.loader

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import top.nkbe.npatch.BuildConfig
import top.nkbe.npatch.billing.BillingModuleScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class MicrogBillingRoutingTest {
    private val manager = ComponentName(
        BuildConfig.APPLICATION_ID,
        "top.nkbe.npatch.billing.ManagerBillingService"
    )
    private val redirector by lazy {
        Class.forName("top.nkbe.npatch.billing.MicrogBillingRedirector")
    }

    @Test fun billingClientPackageGuardAndExplicitBindReachManager() {
        val query = Intent(BILLING_ACTION).setPackage(PLAY_STORE)
        val provider = service(manager.packageName, manager.className)

        val providerQuery = requireNotNull(redirectIntent(query))
        assertEquals(manager.packageName, providerQuery.`package`)

        val visible = requireNotNull(redirectServiceResults(listOf(provider))).single().serviceInfo
        assertEquals(PLAY_STORE, visible.packageName)
        val explicitBind = Intent(query).setComponent(ComponentName(visible.packageName, visible.name))
        val providerBind = requireNotNull(redirectIntent(explicitBind))

        assertEquals(manager, providerBind.component)
        assertEquals(manager.packageName, providerBind.`package`)
        assertEquals(BILLING_ACTION, providerBind.action)
        assertEquals(PLAY_STORE, query.`package`)
        assertNull(query.component)
        assertEquals(ComponentName(PLAY_STORE, manager.className), explicitBind.component)
        assertEquals(manager.packageName, provider.serviceInfo.packageName)
    }

    @Test fun implicitBillingIntentIsCopiedWithoutLosingExtrasOrFlags() {
        val original = Intent(BILLING_ACTION)
            .putExtra("playBillingLibraryVersion", "8.3.0")
            .putExtra("requestId", "unchanged")
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

        val redirected = requireNotNull(redirectIntent(original))

        assertNotSame(original, redirected)
        assertEquals(manager.packageName, redirected.`package`)
        assertEquals(BILLING_ACTION, redirected.action)
        assertEquals("8.3.0", redirected.getStringExtra("playBillingLibraryVersion"))
        assertEquals("unchanged", redirected.getStringExtra("requestId"))
        assertEquals(original.flags, redirected.flags)
        assertNull(original.`package`)
        assertNull(original.component)
    }

    @Test fun unrelatedPlayStoreIntentsRemainTheSameObjects() {
        val store = Intent(Intent.ACTION_VIEW).setPackage(PLAY_STORE)
        val licensing = Intent("com.android.vending.licensing.ILicensingService")
            .setComponent(ComponentName(PLAY_STORE, "example.LicensingService"))

        assertSame(store, redirectIntent(store))
        assertSame(licensing, redirectIntent(licensing))
    }

    @Test fun explicitlySelectedOtherAppsAreNotRedirected() {
        val byPackage = Intent(BILLING_ACTION).setPackage("example.other.store")
        val byComponent = Intent(BILLING_ACTION)
            .setComponent(ComponentName("example.other.store", "example.OtherBillingService"))

        assertSame(byPackage, redirectIntent(byPackage))
        assertSame(byComponent, redirectIntent(byComponent))
    }

    @Test fun serviceAliasesCopyOnlyTheSelectedManagerService() {
        val selected = service(manager.packageName, manager.className)
        val selectedInfo = selected.serviceInfo
        val selectedApp = selectedInfo.applicationInfo
        val otherManagerService = service(manager.packageName, "example.UnrelatedService")
        val officialStore = service(PLAY_STORE, manager.className)
        val otherStore = service("example.other.store", manager.className)
        val missingServiceInfo = ResolveInfo()
        val original = listOf(selected, otherManagerService, officialStore, otherStore, missingServiceInfo)

        val redirected = requireNotNull(redirectServiceResults(original))
        val alias = redirected.first()

        assertEquals(original.size, redirected.size)
        assertNotSame(selected, alias)
        assertNotSame(selectedInfo, alias.serviceInfo)
        assertNotSame(selectedApp, alias.serviceInfo.applicationInfo)
        assertEquals(PLAY_STORE, alias.serviceInfo.packageName)
        assertEquals(PLAY_STORE, alias.serviceInfo.applicationInfo.packageName)
        assertEquals(manager.className, alias.serviceInfo.name)
        assertEquals(selectedInfo.exported, alias.serviceInfo.exported)
        assertEquals(selectedApp.uid, alias.serviceInfo.applicationInfo.uid)
        assertEquals(selected.priority, alias.priority)
        assertSame(otherManagerService, redirected[1])
        assertSame(officialStore, redirected[2])
        assertSame(otherStore, redirected[3])
        assertSame(missingServiceInfo, redirected[4])
        assertSame(selectedInfo, selected.serviceInfo)
        assertSame(selectedApp, selected.serviceInfo.applicationInfo)
        assertEquals(manager.packageName, selectedInfo.packageName)
        assertEquals(manager.packageName, selectedApp.packageName)
    }

    @Test fun nullAndEmptyServiceResultsRemainUnchanged() {
        val empty = emptyList<ResolveInfo>()

        assertNull(redirectServiceResults(null))
        assertSame(empty, redirectServiceResults(empty))
    }

    @Test fun missingIntentOrManagerDoesNotCreateARoute() {
        val original = Intent(BILLING_ACTION).setPackage(PLAY_STORE)
        val services = listOf(service(manager.packageName, manager.className))

        assertNull(redirectIntent(null))
        assertSame(original, redirectIntent(original, null))
        assertSame(services, redirectServiceResults(services, null))
    }

    @Test fun builtinModuleRequiresBothManagerAndMicrogOptIns() {
        assertTrue(BillingModuleScope.isEligibleConfig(config("{\"useManager\":true,\"useMicroG\":true}")))
        assertFalse(BillingModuleScope.isEligibleConfig(config("{\"useManager\":false,\"useMicroG\":true}")))
        assertFalse(BillingModuleScope.isEligibleConfig(config("{\"useManager\":true,\"useMicroG\":false}")))
        assertFalse(BillingModuleScope.isEligibleConfig(config("{}")))
        assertFalse(BillingModuleScope.isEligibleConfig(config("{\"useManager\":true,\"useMicroG\":true,\"microgVendor\":\"example.other\"}")))
    }

    @Test fun invalidPatchMetadataNeverEnablesBilling() {
        assertFalse(BillingModuleScope.isEligibleConfig(null))
        assertFalse(BillingModuleScope.isEligibleConfig("%%%"))
        assertFalse(BillingModuleScope.isEligibleConfig(config("invalid-json")))
        assertFalse(BillingModuleScope.isEligibleConfig(config("[]")))
    }

    private fun config(json: String) = Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)

    private fun redirectIntent(original: Intent?, target: ComponentName? = manager): Intent? =
        redirector.getDeclaredMethod("redirectIntent", Intent::class.java, ComponentName::class.java)
            .apply { isAccessible = true }
            .invoke(null, original, target) as Intent?

    @Suppress("UNCHECKED_CAST")
    private fun redirectServiceResults(
        original: List<ResolveInfo>?,
        target: ComponentName? = manager
    ): List<ResolveInfo>? = redirector
        .getDeclaredMethod("redirectServiceResults", List::class.java, ComponentName::class.java)
        .apply { isAccessible = true }
        .invoke(null, original, target) as List<ResolveInfo>?

    private fun service(packageName: String, className: String) = ResolveInfo().apply {
        priority = 7
        serviceInfo = ServiceInfo().apply {
            this.packageName = packageName
            name = className
            exported = true
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                uid = 12345
            }
        }
    }

    private companion object {
        const val BILLING_ACTION = "com.android.vending.billing.InAppBillingService.BIND"
        const val PLAY_STORE = "com.android.vending"
    }
}
