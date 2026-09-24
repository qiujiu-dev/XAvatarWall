package app.xavatarwall.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 头像下载与头像墙绘制。
 * 头像只在点击「生成头像墙」之后下载；绘制采用分批处理，避免内存爆掉。
 */
object Artwork {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val MAX_SIDE = 3200
    private const val BATCH = 8
    private const val WATERMARK = "XAvatarWall · X@jiuqiudev"

    data class GenerateResult(
        val preview: Bitmap?,
        val savedMessage: String?,
        val error: String?,
        val cachedCount: Int = 0,
        val downloadedCount: Int = 0
    )

    private fun fetchBytes(url: String): ByteArray? {
        if (url.isEmpty()) return null
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://x.com/")
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                null
            } else {
                val bytes = conn.inputStream.use { it.readBytes() }
                conn.disconnect()
                bytes
            }
        } catch (e: Exception) {
            null
        }
    }

    fun cacheDir(context: Context): File =
        File(context.filesDir, "avatar_cache").apply { if (!exists()) mkdirs() }

    /** 缓存文件名：粉丝用 ID，自己的头像用 URL 指纹 */
    private fun cacheFile(context: Context, key: String): File =
        File(cacheDir(context), "$key.img")

    /**
     * 取一张头像：优先用磁盘缓存（按用户 ID 存），没有再下载并写入缓存。
     * 返回 (位图, 是否命中缓存)。
     */
    private suspend fun loadAvatar(context: Context, fan: Fan): Pair<Bitmap?, Boolean> =
        withContext(Dispatchers.IO) {
            val key = fan.id.ifEmpty { fan.avatar.hashCode().toString() }
            val file = cacheFile(context, key)
            if (file.exists() && file.length() > 0) {
                val cached = BitmapFactory.decodeFile(file.absolutePath)
                if (cached != null) return@withContext cached to true
                file.delete()
            }
            val bytes = fetchBytes(fan.avatar) ?: return@withContext null to false
            try {
                file.writeBytes(bytes)
            } catch (e: Exception) {
                // 缓存写入失败不影响本次使用
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) to false
        }

    /**
     * 统计这批粉丝里已经缓存好的头像数量。
     * 用于在界面上提示「已缓存 N 张，重新生成不会重新下载」。
     */
    suspend fun cachedCount(context: Context, fans: List<Fan>): Int =
        withContext(Dispatchers.IO) {
            val dir = cacheDir(context)
            fans.count { fan ->
                if (fan.avatar.isEmpty()) return@count false
                val key = fan.id.ifEmpty { fan.avatar.hashCode().toString() }
                val file = File(dir, "$key.img")
                file.exists() && file.length() > 0
            }
        }

    /** 清掉全部头像缓存，下次生成才会重新下载 */
    fun clearCache(context: Context) {
        try {
            cacheDir(context).listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun fillBackground(
        canvas: Canvas,
        paint: Paint,
        w: Int,
        h: Int,
        settings: WallSettings
    ) {
        val colorA = safeColor(settings.colorA, "#DBEAFE")
        val colorB = safeColor(settings.colorB, "#93C5FD")
        when (settings.bgStyle) {
            "classic" -> {
                paint.shader = null
                paint.color = safeColor(settings.bgColor, "#DBEAFE")
            }

            "dark" -> {
                paint.shader = null
                paint.color = Color.parseColor("#0F172A")
            }

            "gradient-h" -> paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), 0f, colorA, colorB, Shader.TileMode.CLAMP
            )

            "gradient-diag" -> paint.shader = LinearGradient(
                0f, 0f, w.toFloat(), h.toFloat(), colorA, colorB, Shader.TileMode.CLAMP
            )

            "gradient-radial" -> paint.shader = RadialGradient(
                w / 2f, h / 2f, max(w, h) * 0.62f, colorA, colorB, Shader.TileMode.CLAMP
            )

            else -> paint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(), colorA, colorB, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
    }

    private fun safeColor(hex: String, fallback: String): Int =
        try {
            Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (e: Exception) {
            Color.parseColor(fallback)
        }

    private fun fillCount(text: String, count: Int): String =
        text.replace("{count}", count.toString(), ignoreCase = true)
            .replace("XX", count.toString(), ignoreCase = true)

    suspend fun generate(
        context: Context,
        fans: List<Fan>,
        settings: WallSettings,
        invisibleCount: Int,
        onProgress: (Int, Int) -> Unit
    ): GenerateResult = withContext(Dispatchers.Default) {
        val n = fans.size
        if (n == 0) return@withContext GenerateResult(null, null, "没有可用的粉丝数据")

        val cols = ceil(sqrt(n.toDouble())).toInt()
        val rows = ceil(n.toDouble() / cols).toInt()
        val cell = ((MAX_SIDE - 120) / cols).coerceIn(56, 240)
        val gap = when (settings.gap) {
            "small" -> (cell / 70).coerceAtLeast(1)
            "wide" -> (cell / 18).coerceAtLeast(6)
            else -> (cell / 40).coerceAtLeast(3)
        }
        val pad = (cell * 0.9).toInt().coerceAtLeast(48)
        val radius = when (settings.shape) {
            "square" -> 0f
            "circle" -> cell / 2f
            else -> cell * 0.18f
        }

        val headerH = (cell * 1.3f).toInt()

        // 底部文字（附加文字 / 冻结提示 / 水印）
        val footerLines = ArrayList<String>()
        if (settings.extraText.isNotEmpty()) footerLines.add(settings.extraText)
        if (invisibleCount > 0) {
            footerLines.add("注：其中 $invisibleCount 位账号已被冻结或停用，暂时无法显示头像")
        }
        footerLines.add(WATERMARK)

        val footSize = (cell * 0.42f).coerceAtLeast(16f)
        val lineHeight = (footSize * 1.7f)
        val footerH = (footerLines.size * lineHeight).toInt() + (pad * 0.4f).toInt()

        val gridW = cols * cell + (cols - 1) * gap
        val gridH = rows * cell + (rows - 1) * gap
        val w = gridW + pad * 2
        val h = pad + headerH + gridH + footerH + pad

        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        fillBackground(canvas, paint, w, h, settings)

        val dark = settings.bgStyle == "dark"
        val titleColor = if (dark) Color.parseColor("#E2E8F0")
        else safeColor(settings.titleColor, "#1E3A8A")
        val footColor = if (dark) Color.parseColor("#CBD5E1") else Color.parseColor("#334155")

        val titleText = fillCount(
            settings.title.ifBlank { WallSettings().title },
            n
        )
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = titleColor
            textSize = (cell * 0.9f).coerceAtLeast(28f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // 顶部：居中标题
        titlePaint.textAlign = Paint.Align.CENTER
        canvas.drawText(titleText, w / 2f, pad + headerH * 0.62f, titlePaint)

        // 头像网格：分批下载 -> 绘制 -> 释放
        val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.parseColor("#334155") else Color.parseColor("#E2E8F0")
        }
        val questionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.parseColor("#94A3B8") else Color.parseColor("#94A3B8")
            textAlign = Paint.Align.CENTER
            textSize = cell * 0.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val src = Rect()
        val dst = Rect()
        val clip = Path()

        var cachedCount = 0
        var downloadedCount = 0
        var index = 0
        while (index < n) {
            val slice = fans.subList(index, min(index + BATCH, n))
            val bitmaps = coroutineScope {
                slice.map { fan -> async { loadAvatar(context, fan) } }.awaitAll()
            }
            for (k in slice.indices) {
                val idx = index + k
                val col = idx % cols
                val row = idx / cols
                val x = (pad + col * (cell + gap)).toFloat()
                val y = (pad + headerH + row * (cell + gap)).toFloat()

                val (bmp, fromCache) = bitmaps[k]
                if (fromCache) cachedCount++ else if (bmp != null) downloadedCount++
                if (bmp == null) {
                    clip.reset()
                    clip.addRoundRect(
                        RectF(x, y, x + cell, y + cell), radius, radius, Path.Direction.CW
                    )
                    canvas.save()
                    canvas.clipPath(clip)
                    canvas.drawRect(x, y, x + cell, y + cell, placeholderPaint)
                    canvas.restore()
                    canvas.drawText("?", x + cell / 2f, y + cell * 0.68f, questionPaint)
                    continue
                }

                val side = min(bmp.width, bmp.height)
                if (side <= 0) {
                    bmp.recycle()
                    continue
                }
                src.set(
                    (bmp.width - side) / 2, (bmp.height - side) / 2,
                    (bmp.width + side) / 2, (bmp.height + side) / 2
                )
                dst.set(x.toInt(), y.toInt(), (x + cell).toInt(), (y + cell).toInt())
                clip.reset()
                clip.addRoundRect(
                    RectF(x, y, x + cell, y + cell), radius, radius, Path.Direction.CW
                )
                canvas.save()
                canvas.clipPath(clip)
                canvas.drawBitmap(bmp, src, dst, paint)
                canvas.restore()
                bmp.recycle()
            }
            index += slice.size
            onProgress(index, n)
        }

        // 底部文字
        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = footColor
        }
        var lineY = h - pad * 0.55f - (footerLines.size - 1) * lineHeight
        for ((i, line) in footerLines.withIndex()) {
            footPaint.textSize = if (i == footerLines.size - 1) footSize * 0.85f else footSize
            footPaint.color = if (i == footerLines.size - 1) Color.parseColor("#64748B") else footColor
            canvas.drawText(line, w / 2f, lineY, footPaint)
            lineY += lineHeight
        }

        // 预览缩略图
        val thumbW = 1080
        val thumbH = (h * thumbW / w.toFloat()).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true)

        val name = "XAvatarWall_${n}_${System.currentTimeMillis()}.jpg"
        val saved = saveToGallery(context, bitmap, name)
        bitmap.recycle()
        GenerateResult(thumb, saved, null, cachedCount, downloadedCount)
    }

    private fun saveToGallery(context: Context, bitmap: Bitmap, displayName: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/XAvatarWall"
                    )
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    "图片已生成，但写入相册失败"
                } else {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    "已保存到相册：Pictures/XAvatarWall"
                }
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "XAvatarWall"
                )
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, displayName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                }
                "已保存到：${file.absolutePath}"
            }
        } catch (e: Exception) {
            "保存失败：${e.message}"
        }
    }
}
