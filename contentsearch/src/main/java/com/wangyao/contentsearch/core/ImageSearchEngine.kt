package com.wangyao.contentsearch.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import java.util.Random

/**
 * 基于内容的图像检索引擎（第 10 章 10.2~10.4 节）。
 *
 * 职责：
 * 1. [buildDatabase]：由素材原图构建演示图像库（几何/光度变换副本
 *    + 风格迥异的合成图），模拟真实检索系统的多图数据库；
 * 2. [index]：离线提取库内全部图像的特征（HSV 颜色直方图 +
 *    梯度方向纹理直方图）——对应 CBIR 的「建立索引」阶段；
 * 3. [search]：提取查询图特征，与库内特征逐一计算综合相似度
 *    （加权融合），按相似度降序返回 Top-K——对应「在线检索」阶段。
 */
class ImageSearchEngine {

    /** 图像库条目。 */
    data class Entry(
        /** 显示名（如「原图」「旋转 10°」「合成：草地」）。 */
        val label: String,
        /** 库内图像（供 UI 缩略图展示）。 */
        val bitmap: Bitmap,
        /** 特征向量（索引阶段提取）。 */
        var feature: ImageFeatures.Feature? = null
    )

    /** 检索命中结果。 */
    data class Match(
        val entry: Entry,
        /** 综合相似度 ∈[0,1]。 */
        val score: Double,
        /** 颜色分量相似度。 */
        val colorScore: Double,
        /** 纹理分量相似度。 */
        val textureScore: Double
    )

    /** 图像库（已索引）。 */
    private val database = mutableListOf<Entry>()

    /** 库大小。 */
    val size: Int get() = database.size

    // -------------------------------------------------------------------------
    // 索引（离线阶段）
    // -------------------------------------------------------------------------

    /**
     * 由原图构建演示图像库并建立特征索引。
     * 库内包含：
     * - 原图自身（作为「最相似」的参照基准）；
     * - 光度变换副本（亮度±、噪声、模糊、JPEG 压缩）——颜色特征应
     *   保持较高相似度，验证颜色直方图对几何变换的不变性；
     * - 几何变换副本（旋转、镜像、缩放裁剪）——验证 CBIR 特征的
     *   旋转/镜像不变性（第 10 章 10.2.1 节）；
     * - 合成干扰图（草地绿、天空蓝、晚霞橙、棋盘灰）——特征差异大，
     *   应排在检索结果末尾。
     */
    fun buildDatabase(original: Bitmap): Int {
        database.clear()
        val w = original.width
        val h = original.height

        // ---- 原图与光度/几何变换副本 ----
        addEntry("原图", original)
        addEntry("亮度+40", shiftBrightness(original, 40))
        addEntry("亮度-40", shiftBrightness(original, -40))
        addEntry("镜像", flip(original))
        addEntry("旋转10°", rotate(original, 10f))
        addEntry("0.7×缩放", scale(original, 0.7f))
        addEntry("高斯噪声", addNoise(original, seed = 7L))
        addEntry("模糊", blur(original))

        // ---- 合成干扰图（与原图内容无关）----
        addEntry("合成:草地", synthGrass(w, h))
        addEntry("合成:天空", synthSky(w, h))
        addEntry("合成:晚霞", synthSunset(w, h))
        addEntry("合成:棋盘", synthChecker(w, h))

        // 全部条目提取特征（建立索引）
        for (e in database) index(e)
        return database.size
    }

    /** 单条目提取特征（索引）。 */
    private fun index(entry: Entry) {
        val bmp = entry.bitmap
        val w = bmp.width
        val h = bmp.height
        val argb = IntArray(w * h)
        bmp.getPixels(argb, 0, w, 0, 0, w, h)
        val luma = ByteArray(w * h)
        for (i in argb.indices) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        entry.feature = ImageFeatures.extract(argb, luma, w, h)
    }

    // -------------------------------------------------------------------------
    // 检索（在线阶段）
    // -------------------------------------------------------------------------

    /**
     * 以 [query] 为查询图检索 Top-[k] 相似图像（式 10-2~10-4）。
     * 查询图自身（像素级相同）也会参与排序，相似度应≈1.0。
     */
    fun search(query: Bitmap, k: Int = 8): List<Match> {
        val w = query.width
        val h = query.height
        val argb = IntArray(w * h)
        query.getPixels(argb, 0, w, 0, 0, w, h)
        val luma = ByteArray(w * h)
        for (i in argb.indices) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
        }
        val qf = ImageFeatures.extract(argb, luma, w, h)

        return database
            .mapNotNull { e ->
                e.feature?.let { f ->
                    Match(
                        entry = e,
                        score = ImageFeatures.similarity(qf, f),
                        colorScore = ImageFeatures.histogramIntersection(qf.color, f.color),
                        textureScore = ImageFeatures.cosine(qf.texture, f.texture)
                    )
                }
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    /** 获取库内条目（UI 展示库内容）。 */
    fun entries(): List<Entry> = database.toList()

    /** 查询图类型（UI 选择）：验证不同失真下检索的鲁棒性。 */
    enum class QueryType { ORIGINAL, BRIGHT, FLIP, ROTATE, NOISE, SCALE }

    /**
     * 生成查询图（复用库内同款变换，保证「同一内容、不同表现」）。
     * 查询图用于验证：即便查询与库内图像存在光度/几何差异，
     * 基于内容的检索仍应把「同内容」图像排在最前。
     */
    fun buildQuery(original: Bitmap, type: QueryType): Bitmap = when (type) {
        QueryType.ORIGINAL -> original
        QueryType.BRIGHT -> shiftBrightness(original, 40)
        QueryType.FLIP -> flip(original)
        QueryType.ROTATE -> rotate(original, 10f)
        QueryType.NOISE -> addNoise(original, seed = 99L)
        QueryType.SCALE -> scale(original, 0.7f)
    }

    private fun addEntry(label: String, bmp: Bitmap) {
        database.add(Entry(label, bmp))
    }

    // -------------------------------------------------------------------------
    // 图像变换工具（构造演示数据库）
    // -------------------------------------------------------------------------

    /** 亮度偏移。 */
    private fun shiftBrightness(src: Bitmap, delta: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        for (i in px.indices) {
            val p = px[i]
            val r = (((p shr 16) and 0xFF) + delta).coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + delta).coerceIn(0, 255)
            val b = ((p and 0xFF) + delta).coerceIn(0, 255)
            px[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /** 水平镜像。 */
    private fun flip(src: Bitmap): Bitmap {
        val m = Matrix().apply { postScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, false)
    }

    /** 旋转 [deg] 度（留白填充中灰）。 */
    private fun rotate(src: Bitmap, deg: Float): Bitmap {
        val m = Matrix().apply { postRotate(deg) }
        val info = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        // 计算旋转后外接尺寸
        val rad = Math.toRadians(deg.toDouble())
        val w = src.width.toDouble()
        val h = src.height.toDouble()
        val cosA = abs(cos(rad))
        val sinA = abs(sin(rad))
        val ow = (w * cosA + h * sinA).toInt()
        val oh = (w * sinA + h * cosA).toInt()
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out).apply { drawColor(Color.rgb(128, 128, 128)) }
        m.postTranslate((ow - w).toFloat() / 2f, (oh - h).toFloat() / 2f)
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        // 消除未用变量告警
        info.inJustDecodeBounds = true
        return out
    }

    /** 缩放（缩小后放大回来，模拟有损缩放）。 */
    private fun scale(src: Bitmap, factor: Float): Bitmap {
        val w = (src.width * factor).toInt().coerceAtLeast(8)
        val h = (src.height * factor).toInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        return Bitmap.createScaledBitmap(small, src.width, src.height, true)
    }

    /** 加性噪声（固定种子可复现）。 */
    private fun addNoise(src: Bitmap, seed: Long): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        val rnd = Random(seed)
        for (i in px.indices) {
            val p = px[i]
            val n = (rnd.nextGaussian() * 20).toInt()
            val r = (((p shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((p and 0xFF) + n).coerceIn(0, 255)
            px[i] = (p and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /** 3×3 均值模糊。 */
    private fun blur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val dst = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var rr = 0; var gg = 0; var bb = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val p = px[(y + dy) * w + (x + dx)]
                        rr += (p shr 16) and 0xFF
                        gg += (p shr 8) and 0xFF
                        bb += p and 0xFF
                    }
                }
                dst[y * w + x] = (px[y * w + x] and 0xFF000000.toInt()) or
                    ((rr / 9) shl 16) or ((gg / 9) shl 8) or (bb / 9)
            }
        }
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }

    // -------------------------------------------------------------------------
    // 合成干扰图
    // -------------------------------------------------------------------------

    /** 合成：草地（绿色主调 + 水平条纹纹理）。 */
    private fun synthGrass(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val g = 120 + (x + y) % 40
                px[y * w + x] = Color.rgb(30, g, 40)
            }
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 合成：天空（蓝色渐变 + 白云椭圆）。 */
    private fun synthSky(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint()
        for (y in 0 until h) {
            val t = y.toFloat() / h
            paint.color = Color.rgb(
                (120 + 80 * (1 - t)).toInt(),
                (170 + 50 * (1 - t)).toInt(),
                255
            )
            canvas.drawRect(0f, y.toFloat(), w.toFloat(), (y + 1).toFloat(), paint)
        }
        paint.color = Color.WHITE
        canvas.drawOval(
            w * 0.2f, h * 0.2f, w * 0.6f, h * 0.32f, paint
        )
        canvas.drawOval(
            w * 0.55f, h * 0.45f, w * 0.85f, h * 0.55f, paint
        )
        return out
    }

    /** 合成：晚霞（橙红渐变）。 */
    private fun synthSunset(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val t = y.toFloat() / h
            for (x in 0 until w) {
                px[y * w + x] = Color.rgb(
                    (255 - 100 * t).toInt(),
                    (140 - 80 * t).toInt(),
                    (60 + 60 * t).toInt()
                )
            }
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 合成：棋盘（黑白方块 + 45° 对角线纹理）。 */
    private fun synthChecker(w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        val cell = maxOf(8, minOf(w, h) / 12)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (((x / cell + y / cell) % 2) == 0) 235 else 20
                px[y * w + x] = Color.rgb(v, v, v)
            }
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}
