package top.nkbe.npatch.repo

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import top.nkbe.npatch.network.NetworkDns
import java.io.IOException

/**
 * GitHub Releases API から Knot のリリース一覧を取得する。
 */
object KnotReleaseLoader {

    private const val TAG = "KnotReleaseLoader"
    private const val OWNER = "2b-zipper"
    private const val REPO = "Knot"
    private const val ALL_RELEASES_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases"

    /** キャッシュの有効期限 (30 分)。ホーム表示のたびに API を叩かないようにする */
    private const val CACHE_TTL_MS = 30 * 60 * 1000L

    /** キャッシュされた全リリース */
    @Volatile
    var cachedReleases: List<KnotRelease> = emptyList()
        private set

    @Volatile
    private var lastFetchTime = 0L

    private fun buildClient() = runCatching { NetworkDns.client() }
        .getOrDefault(okhttp3.OkHttpClient())

    private fun commonHeaders(requestBuilder: Request.Builder) = requestBuilder
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "NPatch-Manager")

    /**
     * GitHub API から全リリースを取得し、[cachedReleases] にキャッシュする。
     * キャッシュが有効期限内 (30 分) の場合は API を叩かずにキャッシュを返す。
     * [forceRefresh] = true で強制更新。
     */
    suspend fun fetchAllReleases(forceRefresh: Boolean = false): Result<List<KnotRelease>> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedReleases.isNotEmpty() && now - lastFetchTime < CACHE_TTL_MS) {
            return Result.success(cachedReleases)
        }
        return withContext(Dispatchers.IO) {
        try {
            val client = buildClient()
            val allReleases = mutableListOf<KnotRelease>()

            // 最大 5 ページまで取得（GitHub API は 1 ページ 30 件がデフォルト）
            for (page in 1..5) {
                val request = commonHeaders(
                    Request.Builder().url("$ALL_RELEASES_URL?page=$page&per_page=30")
                ).build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    if (allReleases.isNotEmpty()) break
                    val msg = "HTTP ${response.code}"
                    return@withContext Result.failure(IOException(msg))
                }

                val body = response.body.string()
                val type = object : TypeToken<List<KnotRelease>>() {}.type
                val pageReleases: List<KnotRelease> = Gson().fromJson(body, type)

                if (pageReleases.isEmpty()) break
                allReleases.addAll(pageReleases)
            }

            cachedReleases = allReleases
            lastFetchTime = System.currentTimeMillis()
            Result.success(allReleases)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch all Knot releases", e)
            Result.failure(e)
        }
        }
    }
}
