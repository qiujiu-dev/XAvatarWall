package app.xavatarwall.data

import android.content.Context
import org.json.JSONObject

/** 生成头像墙的全部个性化设置（与浏览器扩展版保持一致） */
data class WallSettings(
    /** 顶部标题，XX 或 {count} 会替换成头像数量 */
    val title: String = "XX fo谢谢大家，感谢大家！",
    /** 图片下方附加文字 */
    val extraText: String = "",
    /** classic / gradient-v / gradient-h / gradient-diag / gradient-radial / dark */
    val bgStyle: String = "gradient-v",
    val bgColor: String = "#DBEAFE",
    val colorA: String = "#DBEAFE",
    val colorB: String = "#93C5FD",
    /** round / square / circle */
    val shape: String = "round",
    val titleColor: String = "#1E3A8A",
    /** auto / small / wide */
    val gap: String = "auto"
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        put("extraText", extraText)
        put("bgStyle", bgStyle)
        put("bgColor", bgColor)
        put("colorA", colorA)
        put("colorB", colorB)
        put("shape", shape)
        put("titleColor", titleColor)
        put("gap", gap)
    }.toString()

    companion object {
        private val DEFAULTS = WallSettings()

        fun fromJson(text: String?): WallSettings {
            if (text.isNullOrBlank()) return DEFAULTS
            return try {
                val j = JSONObject(text)
                WallSettings(
                    title = j.optString("title", DEFAULTS.title),
                    extraText = j.optString("extraText", DEFAULTS.extraText),
                    bgStyle = j.optString("bgStyle", DEFAULTS.bgStyle),
                    bgColor = j.optString("bgColor", DEFAULTS.bgColor),
                    colorA = j.optString("colorA", DEFAULTS.colorA),
                    colorB = j.optString("colorB", DEFAULTS.colorB),
                    shape = j.optString("shape", DEFAULTS.shape),
                    titleColor = j.optString("titleColor", DEFAULTS.titleColor),
                    gap = j.optString("gap", DEFAULTS.gap)
                )
            } catch (e: Exception) {
                DEFAULTS
            }
        }

        val BACKGROUND_STYLES = listOf(
            "classic" to "纯色",
            "gradient-v" to "上下渐变",
            "gradient-h" to "左右渐变",
            "gradient-diag" to "斜向渐变",
            "gradient-radial" to "中间向外",
            "dark" to "深色"
        )

        val SHAPES = listOf(
            "round" to "圆角",
            "square" to "方形",
            "circle" to "圆形"
        )

        val GAPS = listOf(
            "auto" to "自动",
            "small" to "紧凑",
            "wide" to "宽松"
        )

        val PALETTE = listOf(
            "#DBEAFE", "#93C5FD", "#60A5FA", "#2563EB", "#1E3A8A",
            "#FFFFFF", "#F1F5F9", "#E2E8F0", "#94A3B8", "#0F172A",
            "#FCE7F3", "#F9A8D4", "#FDE68A", "#BBF7D0", "#DDD6FE"
        )
    }
}

/** 设置的本地存储 */
object SettingsStore {
    private const val PREF = "xaw_settings"
    private const val KEY = "wall_settings"

    fun load(context: Context): WallSettings {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return WallSettings.fromJson(sp.getString(KEY, null))
    }

    fun save(context: Context, settings: WallSettings) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, settings.toJson())
            .apply()
    }
}
