package top.nkbe.npatch.billing

import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import android.content.pm.PackageManager
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalBillingIdentityTest {
    @Test fun originalManifestVersionIsUsedAfterOtherSmallEntries() {
        val apk = apk("assets/example" to byteArrayOf(1), "AndroidManifest.xml" to manifest())

        assertEquals(261600106, OriginalBillingIdentity.readVersionCode(apk.inputStream(), PACKAGE))
    }

    @Test fun anotherPackagesManifestCannotSupplyBillingIdentity() {
        val apk = apk("AndroidManifest.xml" to manifest("example.unrelated"))

        assertNull(OriginalBillingIdentity.readVersionCode(apk.inputStream(), PACKAGE))
    }

    @Test fun missingOrInvalidManifestDoesNotSupplyBillingIdentity() {
        for (apk in listOf(apk("assets/example" to byteArrayOf(1)),
                           apk("AndroidManifest.xml" to byteArrayOf(1, 2, 3)))) {
            assertNull(OriginalBillingIdentity.readVersionCode(apk.inputStream(), PACKAGE))
        }
    }

    @Test fun nonpositiveVersionDoesNotSupplyBillingIdentity() {
        val apk = apk("AndroidManifest.xml" to manifest(versionCode = 0))

        assertNull(OriginalBillingIdentity.readVersionCode(apk.inputStream(), PACKAGE))
    }

    @Test fun oversizedManifestIsRejectedBeforeParsing() {
        val apk = apk("AndroidManifest.xml" to manifest().copyOf(4 * 1024 * 1024 + 1))

        assertNull(OriginalBillingIdentity.readVersionCode(apk.inputStream(), PACKAGE))
    }

    @Test fun scanStopsBeforeDecompressingAnUnboundedPrecedingEntry() {
        val apk = apk("classes.dex" to ByteArray(32 * 1024 * 1024 + 1),
                      "AndroidManifest.xml" to manifest())

        assertNull(OriginalBillingIdentity.readVersionCode(apk.inputStream(), PACKAGE))
    }

    @Test fun renamedPackageUsesConfiguredOriginalManifest() {
        val metadata = metadata(RENAMED)
        val original = requireNotNull(OriginalBillingIdentity.originalPackageName(metadata, RENAMED))
        val apk = apk("AndroidManifest.xml" to manifest())

        assertEquals(PACKAGE, original)
        assertEquals(261600106, OriginalBillingIdentity.readVersionCode(apk.inputStream(), original))
        assertNull(OriginalBillingIdentity.readVersionCode(
            apk("AndroidManifest.xml" to manifest(RENAMED)).inputStream(), original))
    }

    @Test fun renamedPackageWithoutMicrogUsesExplicitOriginalConfiguration() {
        val metadata = metadata(RENAMED, useMicrog = false)
        metadata.remove("app.revanced.android.gms.SPOOFED_PACKAGE_NAME")
        assertEquals(PACKAGE, OriginalBillingIdentity.originalPackageName(metadata, RENAMED))
    }

    @Test fun unchangedPackageAndLegacyMetadataKeepOriginalIdentity() {
        assertEquals(PACKAGE, OriginalBillingIdentity.originalPackageName(null, PACKAGE))
        assertEquals(PACKAGE, OriginalBillingIdentity.originalPackageName(metadata(PACKAGE), PACKAGE))
        val legacy = metadata(RENAMED, originalPackage = null)
        assertEquals(PACKAGE, OriginalBillingIdentity.originalPackageName(legacy, RENAMED))
        assertNull(OriginalBillingIdentity.originalPackageName(legacy, "example.unrelated"))
    }

    @Test fun renamedPackageCertificateRequiresMatchingOriginalMetadataAndDigest() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val cert = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners.single().toByteArray()
        val metadata = metadata(RENAMED, cert = cert)

        assertArrayEquals(cert, OriginalBillingIdentity.certificate(metadata, RENAMED))
        assertArrayEquals(cert, OriginalBillingIdentity.certificate(metadata(PACKAGE, cert = cert), PACKAGE))
        assertArrayEquals(cert, OriginalBillingIdentity.certificate(
            metadata(RENAMED, originalPackage = null, cert = cert), RENAMED))
        assertNull(OriginalBillingIdentity.certificate(metadata, "example.unrelated"))
        metadata.putString("app.revanced.android.gms.SPOOFED_PACKAGE_NAME", "example.unrelated")
        assertNull(OriginalBillingIdentity.certificate(metadata, RENAMED))
        metadata.putString("app.revanced.android.gms.SPOOFED_PACKAGE_NAME", PACKAGE)
        metadata.putString("app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE", "0".repeat(40))
        assertNull(OriginalBillingIdentity.certificate(metadata, RENAMED))
    }

    private fun metadata(installedPackage: String, originalPackage: String? = PACKAGE,
                         cert: ByteArray = byteArrayOf(), useMicrog: Boolean = true): Bundle {
        val hex = cert.joinToString("") { "%02x".format(it.toInt() and 255) }
        val config = JSONObject().put("useMicroG", useMicrog).put("newPackage", installedPackage)
            .put("originalPackageName", originalPackage).put("originalSignature", hex)
        return Bundle().apply {
            putString("npatch", Base64.encodeToString(config.toString().toByteArray(), Base64.NO_WRAP))
            putString("fake-signature", hex)
            putString("app.revanced.android.gms.SPOOFED_PACKAGE_NAME", PACKAGE)
            putString("app.revanced.android.gms.SPOOFED_PACKAGE_SIGNATURE",
                MessageDigest.getInstance("SHA-1").digest(cert)
                    .joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }

    private fun manifest(packageName: String = PACKAGE, versionCode: Int = 261600106): ByteArray =
        AndroidManifestBlock().apply {
            setPackageName(packageName)
            setVersionCode(versionCode)
            refreshFull()
        }.bytes

    private fun apk(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private companion object {
        const val RENAMED = "jp.naver.line.renamed"
        const val PACKAGE = "jp.naver.line.android"
    }
}
