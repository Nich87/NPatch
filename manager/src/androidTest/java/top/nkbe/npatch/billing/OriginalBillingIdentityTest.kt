package top.nkbe.npatch.billing

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
        const val PACKAGE = "jp.naver.line.android"
    }
}
