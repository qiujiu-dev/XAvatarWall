package app.xavatarwall.data

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * X 接口客户端。
 *
 * 采集思路参考 autonitor：用网页登录得到的 Cookie 直接请求 X 的接口。
 * 优先使用网页端真实调用的路径（x.com/i/api/1.1），失败再退回 api.x.com。
 */
class XApi {

    companion object {
        private const val TAG = "XAW-Api"

        // X 网页端公开使用的 bearer token
        private const val BEARER =
            "AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"

        private const val PAGE_SIZE = 200

        private const val WEB_BASE = "https://x.com/i/api/1.1"
        private const val API_BASE = "https://api.x.com/1.1"

        private const val WEB_GRAPHQL = "https://x.com/i/api/graphql"
        private const val API_GRAPHQL = "https://api.x.com/graphql"

        /** queryId 会变，先尝试远程拉取；失败时用这组已知值兜底 */
        private const val QUERY_ID_SOURCE =
            "https://raw.githubusercontent.com/fa0311/TwitterInternalAPIDocument/" +
                "refs/heads/develop/docs/json/GraphQL.json"

        private val FALLBACK_QUERY_IDS = mapOf(
            "Followers" to "oQWxG6XdR5SPvMBsPiKUPQ",
            "Following" to "i2GOldCH2D3OUEhAdimLrA",
            "UserByScreenName" to "oaLodhGbbnzJBACb1kk2Q",
            "UsersByRestIds" to "xavgLWWbFH8wm_8MQN8plQ",
            "UsersByScreenNames" to "ujL_oXbgVlDHQzWSTgzvnA"
        )

        /** Followers 接口需要的 features 开关（照 autonitor 的取值） */
        private val FOLLOWERS_FEATURES = linkedMapOf(
            "hidden_profile_subscriptions_enabled" to true,
            "responsive_web_graphql_exclude_directive_enabled" to true,
            "verified_phone_label_enabled" to false,
            "highlights_tweets_tab_ui_enabled" to true,
            "creator_subscriptions_tweet_preview_api_enabled" to true,
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
            "responsive_web_graphql_timeline_navigation_enabled" to true,
            "rweb_tipjar_consumption_enabled" to false,
            "subscriptions_feature_can_gift_premium" to false,
            "payments_enabled" to false,
            "responsive_web_twitter_article_notes_tab_enabled" to false,
            "profile_label_improvements_pcf_label_in_post_enabled" to false,
            "responsive_web_profile_redirect_enabled" to false,
            "responsive_web_grok_annotations_enabled" to false,
            "post_ctas_fetch_enabled" to false
        )
    }

    /** 触发 X 限速，resetAtMillis 为可选的解禁时间 */
    class RateLimitException(val resetAtMillis: Long?) :
        Exception("rate limited until $resetAtMillis")

    private data class HttpResult(
        val code: Int,
        val body: String,
        val rateLimitReset: Long?,
        val rateLimitRemaining: Int?
    )

    /** 记住哪个域名可用，后续翻页沿用 */
    private var followersBase: String? = null
    private var gqlBase: String? = null
    private val queryIdCache = HashMap<String, String>()

    /** 从 WebView 的 Cookie 里读取 x.com 的 Cookie 字符串 */
    fun readCookie(): String {
        val cm = CookieManager.getInstance()
        val raw = cm.getCookie("https://x.com") ?: return ""
        if (raw.isBlank()) return ""
        return raw.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
    }

    fun hasAuth(cookie: String): Boolean {
        val c = cookie.lowercase()
        return c.contains("auth_token=") && c.contains("ct0=")
    }

    private fun cookieValue(cookie: String, key: String): String? =
        cookie.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotEmpty() }

    private fun upgradeAvatar(url: String): String =
        if (url.isEmpty()) "" else
            url.replace(Regex("_(normal|mini|bigger|200x200)\\.([A-Za-z]+)$"), "_400x400.$2")

    private fun get(url: String, cookie: String): HttpResult {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("authorization", "Bearer $BEARER")
            conn.setRequestProperty("x-csrf-token", cookieValue(cookie, "ct0") ?: "")
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("x-twitter-active-user", "yes")
            conn.setRequestProperty("x-twitter-auth-type", "OAuth2Session")
            conn.setRequestProperty("x-twitter-client-language", "zh-cn")
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://x.com/")
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val reset = conn.getHeaderField("x-rate-limit-reset")?.toLongOrNull()
            val remaining = conn.getHeaderField("x-rate-limit-remaining")?.toIntOrNull()
            return HttpResult(code, body, reset, remaining)
        } finally {
            conn.disconnect()
        }
    }

    /** 获取 GraphQL 操作对应的 queryId（带缓存与兜底） */
    private fun resolveQueryId(operation: String): String? {
        queryIdCache[operation]?.let { return it }
        FALLBACK_QUERY_IDS[operation]?.let { queryIdCache[operation] = it }
        return queryIdCache[operation]
    }

    /** 远程刷新 queryId（X 改动后仍可用） */
    suspend fun refreshQueryIds() = withContext(Dispatchers.IO) {
        try {
            val res = get(QUERY_ID_SOURCE, "")
            if (res.code != 200 || res.body.isBlank()) return@withContext
            val arr = JSONArray(res.body)
            var updated = 0
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val exports = item.optJSONObject("exports") ?: continue
                val name = exports.optString("operationName")
                val qid = exports.optString("queryId")
                if (name.isNotEmpty() && qid.isNotEmpty() && FALLBACK_QUERY_IDS.containsKey(name)) {
                    queryIdCache[name] = qid
                    updated++
                }
            }
            Log.d(TAG, "refreshQueryIds updated $updated entries")
        } catch (e: Exception) {
            Log.w(TAG, "refreshQueryIds failed: ${e.message}")
        }
    }

    /** 读取当前登录账号：依次尝试网页端接口，最后从 Cookie 里解析 */
    suspend fun fetchMe(cookie: String): Me = withContext(Dispatchers.IO) {
        val candidates = listOf(
            "$WEB_BASE/account/settings.json?include_mention_filter=true&include_nsfw_user_flag=true",
            "$WEB_BASE/account/verify_credentials.json?skip_status=true&include_entities=false",
            "$API_BASE/account/verify_credentials.json?skip_status=true&include_entities=false"
        )

        var lastCode = -1
        for (url in candidates) {
            val res = get(url, cookie)
            Log.d(TAG, "fetchMe -> ${res.code} $url")
            if (res.code == 429 || res.code == 420) {
                throw RateLimitException(res.rateLimitReset?.times(1000))
            }
            if (res.code == 200 && res.body.isNotBlank()) {
                val json = JSONObject(res.body)
                val id = json.optString(
                    "user_id",
                    json.optString("id_str", json.optString("id"))
                )
                if (id.isNotEmpty()) {
                    return@withContext Me(
                        id = id,
                        username = json.optString("screen_name"),
                        name = json.optString("name"),
                        avatar = upgradeAvatar(json.optString("profile_image_url_https"))
                    )
                }
            }
            lastCode = res.code
        }

        // 兜底：Cookie 里的 twid 形如 u%3D123456789
        val twid = cookieValue(cookie, "twid")
        if (!twid.isNullOrEmpty()) {
            val decoded = try {
                URLDecoder.decode(twid, "UTF-8")
            } catch (e: Exception) {
                twid
            }
            val id = decoded.substringAfter("u=", "").trim()
            if (id.isNotEmpty()) {
                Log.d(TAG, "fetchMe fallback to twid -> $id")
                return@withContext Me(id = id, username = "", name = "", avatar = "")
            }
        }

        throw IllegalStateException("读取账号信息失败（HTTP $lastCode）")
    }

    private fun buildFollowersUrl(base: String, userId: String, cursor: String?): String {
        val sb = StringBuilder("$base/followers/list.json")
        sb.append("?count=").append(PAGE_SIZE)
        sb.append("&skip_status=true&include_user_entities=false")
        sb.append("&user_id=").append(URLEncoder.encode(userId, "UTF-8"))
        if (!cursor.isNullOrEmpty()) {
            sb.append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
        }
        return sb.toString()
    }

    private fun buildGraphQlFollowersUrl(
        base: String,
        queryId: String,
        userId: String,
        cursor: String
    ): String {
        val variables = JSONObject()
            .put("userId", userId)
            .put("count", 50)
            .put("includePromotedContent", false)
            .put("withGrokTranslatedBio", false)
            .put("cursor", cursor)
        val features = JSONObject()
        for ((k, v) in FOLLOWERS_FEATURES) features.put(k, v)

        return "$base/$queryId/Followers" +
            "?variables=" + URLEncoder.encode(variables.toString(), "UTF-8") +
            "&features=" + URLEncoder.encode(features.toString(), "UTF-8")
    }

    /**
     * 用 GraphQL 的 Followers 接口拉一页（每页 50 个）。
     * 这是 X 网页端自己使用的通道，可以一直翻到底，不受 v1.1 列表接口的深度限制。
     */
    suspend fun fetchFollowersGql(
        userId: String,
        cookie: String,
        cursor: String?,
        startIndex: Int
    ): FollowersPage = withContext(Dispatchers.IO) {
        val queryId = resolveQueryId("Followers")
            ?: throw IllegalStateException("未获取到 Followers 的 queryId")

        val bases = gqlBase?.let { listOf(it) } ?: listOf(WEB_GRAPHQL, API_GRAPHQL)
        var lastCode = -1
        var lastBody = ""

        for (base in bases) {
            val url = buildGraphQlFollowersUrl(base, queryId, userId, cursor ?: "")
            val res = get(url, cookie)
            Log.d(TAG, "gql Followers -> ${res.code} ${base.substringAfter("//")}")

            if (res.code == 429 || res.code == 420) {
                throw RateLimitException(res.rateLimitReset?.times(1000))
            }
            if (res.code != 200) {
                lastCode = res.code
                lastBody = res.body.take(300)
                continue
            }

            gqlBase = base
            return@withContext parseFollowersGql(res.body, startIndex)
        }

        throw IllegalStateException("GraphQL 读取粉丝失败（HTTP $lastCode）$lastBody")
    }

    /** 解析 GraphQL Followers 响应，提取用户与下一页游标 */
    private fun parseFollowersGql(body: String, startIndex: Int): FollowersPage {
        val json = JSONObject(body)
        val list = ArrayList<Fan>()
        var nextCursor: String? = null

        val instructions = json.optJSONObject("data")
            ?.optJSONObject("user")
            ?.optJSONObject("result")
            ?.optJSONObject("timeline")
            ?.optJSONObject("timeline")
            ?.optJSONArray("instructions")
            ?: return FollowersPage(emptyList(), null)

        for (i in 0 until instructions.length()) {
            val inst = instructions.optJSONObject(i) ?: continue
            val entries = inst.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                val content = entry.optJSONObject("content") ?: continue

                // 底部游标
                if (content.optString("cursorType").equals("Bottom", true)) {
                    val value = content.optString("value")
                    if (value.isNotEmpty()) nextCursor = value
                    continue
                }

                val result = content.optJSONObject("itemContent")
                    ?.optJSONObject("user_results")
                    ?.optJSONObject("result")
                    ?: continue
                val id = result.optString("rest_id")
                val legacy = result.optJSONObject("legacy") ?: JSONObject()
                val screen = legacy.optString("screen_name")
                if (id.isEmpty() || screen.isEmpty()) continue
                list.add(
                    Fan(
                        id = id,
                        username = screen,
                        name = legacy.optString("name"),
                        avatar = upgradeAvatar(legacy.optString("profile_image_url_https")),
                        index = startIndex + list.size
                    )
                )
            }
        }
        return FollowersPage(list, nextCursor)
    }

    /** 拉取一页粉丝 */
    suspend fun fetchFollowers(
        userId: String,
        cookie: String,
        cursor: String?,
        startIndex: Int
    ): FollowersPage = withContext(Dispatchers.IO) {
        val bases = followersBase?.let { listOf(it) } ?: listOf(WEB_BASE, API_BASE)
        var lastCode = -1

        for (base in bases) {
            val url = buildFollowersUrl(base, userId, cursor)
            val res = get(url, cookie)
            Log.d(TAG, "fetchFollowers -> ${res.code} $base")

            if (res.code == 429 || res.code == 420) {
                throw RateLimitException(res.rateLimitReset?.times(1000))
            }
            if (res.code != 200) {
                lastCode = res.code
                continue
            }

            followersBase = base
            val json = JSONObject(res.body)
            val arr = json.optJSONArray("users")
            val list = ArrayList<Fan>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val u = arr.optJSONObject(i) ?: continue
                    val id = u.optString("id_str", u.optString("id"))
                    val screen = u.optString("screen_name")
                    if (id.isEmpty() || screen.isEmpty()) continue
                    list.add(
                        Fan(
                            id = id,
                            username = screen,
                            name = u.optString("name"),
                            avatar = upgradeAvatar(u.optString("profile_image_url_https")),
                            index = startIndex + list.size
                        )
                    )
                }
            }
            val next = json.optString("next_cursor_str", "").takeIf { it.isNotEmpty() }
            return@withContext FollowersPage(list, next)
        }

        throw IllegalStateException("读取粉丝列表失败（HTTP $lastCode）")
    }

    /** 拉取全部粉丝 ID（ids 接口一次最多 5000 个，含资料不可见的账号） */
    suspend fun fetchFollowerIds(userId: String, cookie: String): List<String> =
        withContext(Dispatchers.IO) {
            val ids = LinkedHashSet<String>()
            val bases = listOf("$WEB_BASE/followers/ids.json", "$API_BASE/followers/ids.json")
            for (base in bases) {
                try {
                    var cursor: String? = null
                    while (true) {
                        val sb = StringBuilder(base)
                            .append("?count=5000&user_id=")
                            .append(URLEncoder.encode(userId, "UTF-8"))
                        if (!cursor.isNullOrEmpty()) {
                            sb.append("&cursor=").append(URLEncoder.encode(cursor, "UTF-8"))
                        }
                        val res = get(sb.toString(), cookie)
                        Log.d(TAG, "fetchFollowerIds -> ${res.code} (${base.substringAfter("//")})")
                        if (res.code == 429 || res.code == 420) {
                            throw RateLimitException(res.rateLimitReset?.times(1000))
                        }
                        if (res.code != 200) break
                        val json = JSONObject(res.body)
                        val arr = json.optJSONArray("ids")
                        if (arr != null) {
                            for (i in 0 until arr.length()) ids.add(arr.optLong(i).toString())
                        }
                        val next = json.optString("next_cursor_str", "")
                        if (next.isEmpty() || next == "0") break
                        cursor = next
                        delay(400)
                    }
                } catch (e: RateLimitException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "fetchFollowerIds failed on $base: ${e.message}")
                }
                if (ids.isNotEmpty()) break
            }
            ids.toList()
        }

    /**
     * 批量补全用户资料（GraphQL UsersByRestIds，每批 100 个 ID）。
     * 已冻结/停用的账号通常不会出现在返回结果里，这些会被跳过。
     */
    suspend fun fetchUsersByRestIds(ids: List<String>, cookie: String): List<Fan> =
        withContext(Dispatchers.IO) {
            if (ids.isEmpty()) return@withContext emptyList()
            val queryId = resolveQueryId("UsersByRestIds") ?: return@withContext emptyList()
            val out = ArrayList<Fan>()
            val features = JSONObject()
            for ((k, v) in FOLLOWERS_FEATURES) features.put(k, v)
            val featuresEncoded = URLEncoder.encode(features.toString(), "UTF-8")

            for (batch in ids.chunked(100)) {
                var variables = JSONObject().put("userIds", JSONArray(batch))
                var url = "$WEB_GRAPHQL/$queryId/UsersByRestIds" +
                    "?variables=" + URLEncoder.encode(variables.toString(), "UTF-8") +
                    "&features=" + featuresEncoded
                var res = get(url, cookie)
                if (res.code == 400) {
                    // 旧版参数名兜底
                    variables = JSONObject().put("restIds", JSONArray(batch))
                    url = "$WEB_GRAPHQL/$queryId/UsersByRestIds" +
                        "?variables=" + URLEncoder.encode(variables.toString(), "UTF-8") +
                        "&features=" + featuresEncoded
                    res = get(url, cookie)
                }
                Log.d(TAG, "UsersByRestIds -> ${res.code} batch=${batch.size}")
                if (res.code == 429 || res.code == 420) {
                    throw RateLimitException(res.rateLimitReset?.times(1000))
                }
                if (res.code != 200) continue

                val users = JSONObject(res.body)
                    .optJSONObject("data")
                    ?.optJSONArray("users") ?: continue
                for (i in 0 until users.length()) {
                    val result = users.optJSONObject(i)?.optJSONObject("result") ?: continue
                    val id = result.optString("rest_id")
                    val legacy = result.optJSONObject("legacy") ?: JSONObject()
                    val screen = legacy.optString("screen_name")
                    if (id.isEmpty() || screen.isEmpty()) continue
                    out.add(
                        Fan(
                            id = id,
                            username = screen,
                            name = legacy.optString("name"),
                            avatar = upgradeAvatar(legacy.optString("profile_image_url_https")),
                            index = 0
                        )
                    )
                }
                delay(400)
            }
            out
        }

    /** 读取账号的粉丝总数（X 自己报告的数字），失败返回 -1 */
    suspend fun fetchFollowersCount(username: String, cookie: String): Int =
        withContext(Dispatchers.IO) {
            if (username.isEmpty()) return@withContext -1
            val queryId = resolveQueryId("UserByScreenName") ?: return@withContext -1
            val variables = JSONObject().put("screenName", username)
            val features = JSONObject()
            for ((k, v) in FOLLOWERS_FEATURES) features.put(k, v)
            val url = "$WEB_GRAPHQL/$queryId/UserByScreenName" +
                "?variables=" + URLEncoder.encode(variables.toString(), "UTF-8") +
                "&features=" + URLEncoder.encode(features.toString(), "UTF-8")
            val res = get(url, cookie)
            Log.d(TAG, "UserByScreenName -> ${res.code}")
            if (res.code != 200) return@withContext -1
            try {
                JSONObject(res.body)
                    .optJSONObject("data")
                    ?.optJSONObject("user")
                    ?.optJSONObject("result")
                    ?.optJSONObject("legacy")
                    ?.optInt("followers_count", -1) ?: -1
            } catch (e: Exception) {
                -1
            }
        }
}
