package top.nkbe.npatch.billing

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Uses the embedded original manifest for billing when NPatch changed the installed version. */
object OriginalBillingIdentity {
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private const val MAX_SCAN_BYTES = 32 * 1024 * 1024
    private const val MAX_ENTRIES = 128

    private data class CacheEntry(
        val sourceDir: String,
        val lastUpdateTime: Long,
        val installedVersion: Long,
        val originalVersion: Int?,
    )

    private val cache = mutableMapOf<String, CacheEntry>()

    fun certificate(context: Context, packageName: String): ByteArray? {
        return try {
            val info = context.packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            val metadata = info.metaData ?: return null
            val configText = metadata.getString("npatch") ?: return null
            val certificateHex = metadata.getString("fake-signature") ?: return null
            if (certificateHex.length !in 128..32768 || certificateHex.length % 2 != 0) return null
            val config = JSONObject(String(Base64.decode(configText, Base64.DEFAULT), Charsets.UTF_8))
            if (!config.optBoolean("useMicroG") || config.optString("originalSignature") != certificateHex) return null
            val gmsPackage = "app.revanced.android.gms"
            if (metadata.getString("$gmsPackage.SPOOFED_PACKAGE_NAME") != packageName) return null
            val cert = certificateHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val input = ByteArrayInputStream(cert)
            CertificateFactory.getInstance("X.509").generateCertificate(input)
            if (input.available() != 0) return null
            val expectedDigest = metadata.getString("$gmsPackage.SPOOFED_PACKAGE_SIGNATURE") ?: return null
            val algorithm = when (expectedDigest.length) {
                40 -> "SHA-1"
                64 -> "SHA-256"
                else -> return null
            }
            val digest = MessageDigest.getInstance(algorithm).digest(cert)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            if (!digest.equals(expectedDigest, ignoreCase = true)) return null
            cert
        } catch (e: Exception) {
            Log.w("NPatch-Billing", "Ignoring invalid NPatch original identity for $packageName", e)
            null
        }
    }

    @Synchronized
    fun versionCode(context: Context, packageName: String): Int? {
        return try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            val sourceDir = info.applicationInfo?.sourceDir ?: return null
            val cached = cache[packageName]
            if (cached != null && cached.sourceDir == sourceDir &&
                cached.lastUpdateTime == info.lastUpdateTime &&
                cached.installedVersion == info.longVersionCode
            ) return cached.originalVersion

            val version = ZipFile(sourceDir).use { apk ->
                val origin = apk.getEntry("assets/npatch/origin.apk") ?: return@use null
                apk.getInputStream(origin).use { readVersionCode(it, packageName) }
            }
            cache[packageName] = CacheEntry(sourceDir, info.lastUpdateTime, info.longVersionCode, version)
            version
        } catch (_: Exception) {
            null
        }
    }

    internal fun readVersionCode(originApk: InputStream, packageName: String): Int? {
        return try {
            ZipInputStream(originApk).use { zip ->
                val buffer = ByteArray(8192)
                var scanned = 0
                repeat(MAX_ENTRIES) {
                    val entry = zip.nextEntry ?: return null
                    val isManifest = entry.name == "AndroidManifest.xml" && !entry.isDirectory
                    val manifest = if (isManifest) ByteArrayOutputStream() else null
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        scanned += count
                        if (scanned > MAX_SCAN_BYTES ||
                            (manifest != null && manifest.size() + count > MAX_MANIFEST_BYTES)
                        ) throw IOException("Embedded original manifest scan exceeded its limit")
                        manifest?.write(buffer, 0, count)
                    }
                    if (manifest != null) {
                        val block = AndroidManifestBlock().apply {
                            readBytes(manifest.toByteArray().inputStream())
                        }
                        if (block.packageName != packageName) return null
                        return block.versionCode?.takeIf { it > 0 }
                    }
                    zip.closeEntry()
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
