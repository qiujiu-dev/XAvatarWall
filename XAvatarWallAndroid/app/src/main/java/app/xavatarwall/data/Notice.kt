package app.xavatarwall.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** 从服务器读来的一条公告 */
data class Notice(
    val title: String = "",
    val body: String = "",
    val links: List<Pair<String, String>> = emptyList()
)

/**
 * 远程公告。
 *
 * 服务器上的 txt 放的是 **Base64 编码后的文本**：
 * - 普通浏览器打开 → 看到一串看不懂的字符
 * - App 拉下来解码 → 还原成能读的公告
 *
 * 地址有两层：
 * 1. 引导地址（固定）→ 里面写着真正的公告服务器地址，换服务器改这里就行，不用发版
 * 2. 内置兜底地址 → 引导地址挂了也能用
 */
object NoticeRepository {

    private const val UA = "XAvatarWall/Android"

    // 兜底：Base64("http://<内置服务器>/xavatarwall/notice.txt")
    private const val ENDPOINT_B64 =
        "aHR0cDovLzguMTUyLjE5OS4yNDQveGF2YXRhcndhbGwvbm90aWNlLnR4dA=="

    // 引导地址：只放一行 Base64，内容是真正的公告地址
    private const val BOOTSTRAP_URL =
        "https://raw.githubusercontent.com/qiujiu-dev/XAvatarWall/master/endpoint.txt"

    private fun decodeB64(text: String): String = try {
        String(Base64.getDecoder().decode(text.trim()), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

    /** 先看引导地址有没有指向新服务器，没有就用内置的 */
    private suspend fun resolveEndpoint(): String = withContext(Dispatchers.IO) {
        try {
            val conn = URL(BOOTSTRAP_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            conn.setRequestProperty("User-Agent", UA)
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return@withContext decodeB64(ENDPOINT_B64)
            }
            val raw = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            conn.disconnect()
            val resolved = decodeB64(raw)
            if (resolved.startsWith("http")) resolved else decodeB64(ENDPOINT_B64)
        } catch (e: Exception) {
            decodeB64(ENDPOINT_B64)
        }
    }

    suspend fun fetch(): Notice? = withContext(Dispatchers.IO) {
        val url = resolveEndpoint()
        if (url.isEmpty()) return@withContext null
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", UA)
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return@withContext null
            }
            val raw = conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            conn.disconnect()
            parse(decodeNotice(raw))
        } catch (e: Exception) {
            null
        }
    }

    /** 服务器内容是 Base64；万一没编码，就当原文直接用，方便调试 */
    private fun decodeNotice(raw: String): String {
        val decoded = decodeB64(raw)
        return if (decoded.isEmpty()) raw else decoded
    }

    /**
     * 解析约定标记：
     * - `# 开头` → 标题
     * - `[文字](链接)` → 按钮
     * - 其余 → 正文
     */
    fun parse(text: String): Notice? {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return null

        var title = ""
        val body = ArrayList<String>()
        val links = ArrayList<Pair<String, String>>()
        val linkPattern = Regex("^\\[(.+?)]\\((.+?)\\)$")

        for (line in lines) {
            if (line.startsWith("#")) {
                val content = line.trimStart('#').trim()
                if (title.isEmpty()) title = content else body.add(content)
                continue
            }
            val m = linkPattern.find(line)
            if (m == null) {
                body.add(line)
                continue
            }
            links.add(m.groupValues[1] to m.groupValues[2])
        }

        if (title.isEmpty() && body.isEmpty() && links.isEmpty()) return null
        return Notice(title, body.joinToString("\n"), links)
    }
}
