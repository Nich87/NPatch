package top.nkbe.npatch.update

import com.google.gson.JsonParser
import java.math.BigInteger
import java.net.URI

data class UpdateAsset(val name: String, val url: String, val size: Long, val digest: String?)
data class UpdateRelease(val version: String, val notes: String, val asset: UpdateAsset)

/** Compare numeric versions and the beta/rc tags used by this repository. */
object UpdatePolicy {
    private data class Version(val numbers: List<BigInteger>, val pre: List<String>)

    private fun parts(version: String): Version? {
        val value = version.removePrefix("v").removePrefix("V")
        val match = Regex("([0-9]+(?:\\.[0-9]+)*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?(?:\\+[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")
            .matchEntire(value) ?: return null
        return Version(match.groupValues[1].split('.').map(::BigInteger),
            Regex("[0-9]+|[A-Za-z]+").findAll(match.groupValues[2]).map { it.value.lowercase() }.toList())
    }

    fun compare(left: String, right: String): Int? {
        val a = parts(left) ?: return null
        val b = parts(right) ?: return null
        for (i in 0 until maxOf(a.numbers.size, b.numbers.size)) {
            val order = (a.numbers.getOrNull(i) ?: BigInteger.ZERO).compareTo(b.numbers.getOrNull(i) ?: BigInteger.ZERO)
            if (order != 0) return order
        }
        if (a.pre.isEmpty() && b.pre.isNotEmpty()) return 1
        if (b.pre.isEmpty() && a.pre.isNotEmpty()) return -1
        for (i in 0 until maxOf(a.pre.size, b.pre.size)) {
            val x = a.pre.getOrNull(i) ?: return -1
            val y = b.pre.getOrNull(i) ?: return 1
            val nx = x.toBigIntegerOrNull()
            val ny = y.toBigIntegerOrNull()
            val order = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x.compareTo(y)
            }
            if (order != 0) return order
        }
        return 0
    }

    fun parseRelease(json: String, debug: Boolean): UpdateRelease {
        val root = JsonParser.parseString(json).asJsonObject
        require(root.get("draft")?.asBoolean != true && root.get("prerelease")?.asBoolean != true)
        val version = root.get("tag_name").asString.removePrefix("v").removePrefix("V")
        require(parts(version) != null) { "Unsupported release version" }
        val assets = root.getAsJsonArray("assets").mapNotNull { element ->
            val a = element.asJsonObject
            val name = a.get("name")?.asString ?: return@mapNotNull null
            if (!name.matches(Regex("NPatch-v${Regex.escape(version)}-[0-9]+-(release|debug)\\.apk", RegexOption.IGNORE_CASE))) return@mapNotNull null
            val url = a.get("browser_download_url")?.asString ?: return@mapNotNull null
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null ||
                !uri.path.startsWith("/Nich87/NPatch/releases/download/")) return@mapNotNull null
            val size = a.get("size")?.asLong ?: return@mapNotNull null
            if (size <= 0 || size > 512L * 1024 * 1024) return@mapNotNull null
            val digest = a.get("digest")?.takeUnless { it.isJsonNull }?.asString
            UpdateAsset(name, url, size, digest)
        }
        val preferred = assets.filter { it.name.endsWith(if (debug) "-debug.apk" else "-release.apk", true) }
        val candidates = if (debug && preferred.isEmpty()) assets.filter { it.name.endsWith("-release.apk", true) } else preferred
        require(candidates.size == 1) { "No unambiguous compatible NPatch APK" }
        return UpdateRelease(version, root.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty(), candidates.single())
    }
}
