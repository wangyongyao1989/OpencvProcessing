package com.wangyao.videorecognition.retrieval

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 基于内容的视频检索特征与相似性度量（纯算法，无 Android
 * 依赖，可 JVM 单元测试）。
 *
 * 概念来源（contentsearch 模块《数字图像与视频处理》第 10 章
 * 10.2~10.5 节「基于内容的视频检索」）：
 * - 视频检索 = 关键帧抽取 + 图像检索技术的时序扩展；
 * - 颜色特征：亮度直方图（本模块解码管线仅取 Y 平面，故以
 *   64 维灰度直方图作为颜色/亮度分布特征，L1 归一化）；
 * - 纹理特征：Sobel 梯度方向直方图（18 bins，幅值加权，
 *   仅统计显著边缘，L1 归一化）；
 * - 综合相似度：Sim = w_c·直方图相交(颜色) + w_t·余弦(纹理)，
 *   w_c=0.6、w_t=0.4，∈[0,1]——颜色为主特征、纹理补充。
 */
object KeyframeFeatures {

    /** 亮度直方图维数。 */
    const val GRAY_BINS = 64

    /** 梯度方向量化级数（每 10° 一个 bin）。 */
    const val ORI_BINS = 18

    /** 综合相似度颜色权重 w_c。 */
    const val WEIGHT_COLOR = 0.6

    /** 综合相似度纹理权重 w_t。 */
    const val WEIGHT_TEXTURE = 0.4

    /** 一帧的综合特征向量。 */
    class Feature(
        /** 亮度直方图（L1 归一化，和为 1）。 */
        val color: FloatArray,
        /** 梯度方向直方图（L1 归一化）。 */
        val texture: FloatArray
    )

    /** 提取一帧（亮度平面）的综合特征。 */
    fun extract(luma: ByteArray, width: Int, height: Int): Feature =
        Feature(grayHistogram(luma), textureHistogram(luma, width, height))

    // -------------------------------------------------------------------------
    // 颜色（亮度）特征
    // -------------------------------------------------------------------------

    /** 64 维亮度直方图（L1 归一化，和为 1）。 */
    fun grayHistogram(luma: ByteArray): FloatArray {
        val hist = FloatArray(GRAY_BINS)
        for (v in luma) {
            hist[(v.toInt() and 0xFF) * GRAY_BINS / 256]++
        }
        val n = luma.size.toFloat()
        if (n > 0) for (i in hist.indices) hist[i] /= n
        return hist
    }

    // -------------------------------------------------------------------------
    // 纹理特征
    // -------------------------------------------------------------------------

    /**
     * Sobel 梯度方向直方图（L1 归一化）：
     * 幅值加权、仅统计显著边缘（幅值 > 均值），方向折叠到
     * [0°,180°)（无极性），对光照变化鲁棒。
     */
    fun textureHistogram(luma: ByteArray, width: Int, height: Int): FloatArray {
        val hist = FloatArray(ORI_BINS)
        if (width < 3 || height < 3) return hist
        // 先算梯度（幅值/方向），同时累加幅值总和
        val mags = FloatArray(luma.size)
        val oris = FloatArray(luma.size)
        var sumMag = 0.0
        var cnt = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                val gx = (
                    (luma[idx - width + 1].toInt() and 0xFF) +
                        2 * (luma[idx + 1].toInt() and 0xFF) +
                        (luma[idx + width + 1].toInt() and 0xFF)
                    ) - (
                    (luma[idx - width - 1].toInt() and 0xFF) +
                        2 * (luma[idx - 1].toInt() and 0xFF) +
                        (luma[idx + width - 1].toInt() and 0xFF)
                    )
                val gy = (
                    (luma[idx + width - 1].toInt() and 0xFF) +
                        2 * (luma[idx + width].toInt() and 0xFF) +
                        (luma[idx + width + 1].toInt() and 0xFF)
                    ) - (
                    (luma[idx - width - 1].toInt() and 0xFF) +
                        2 * (luma[idx - width].toInt() and 0xFF) +
                        (luma[idx - width + 1].toInt() and 0xFF)
                    )
                val mag = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                mags[idx] = mag
                oris[idx] = atan2(gy.toFloat(), gx.toFloat())
                sumMag += mag
                cnt++
            }
        }
        val meanMag = if (cnt > 0) sumMag / cnt else 0.0
        var total = 0.0f
        for (i in mags.indices) {
            val m = mags[i]
            if (m > meanMag && m > 0f) {
                var deg = Math.toDegrees(oris[i].toDouble())
                if (deg < 0) deg += 180.0
                hist[(deg / 180.0 * ORI_BINS).toInt().coerceIn(0, ORI_BINS - 1)] += m
                total += m
            }
        }
        if (total > 0f) for (i in hist.indices) hist[i] /= total
        return hist
    }

    // -------------------------------------------------------------------------
    // 综合相似度
    // -------------------------------------------------------------------------

    /**
     * 综合相似度：
     * Sim = 0.6·直方图相交(颜色) + 0.4·余弦(纹理) ∈[0,1]。
     */
    fun comprehensiveSimilarity(a: Feature, b: Feature): Double =
        WEIGHT_COLOR * histogramIntersection(a.color, b.color) +
            WEIGHT_TEXTURE * cosine(a.texture, b.texture)

    /** 直方图相交：Σ min(a(i),b(i)) ∈[0,1]。 */
    fun histogramIntersection(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += minOf(a[i], b[i])
        return s
    }

    /** 余弦相似度：A·B/(|A||B|)，特征非负时 ∈[0,1]。 */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i].toDouble()
            na += (a[i].toDouble() * a[i].toDouble())
            nb += (b[i].toDouble() * b[i].toDouble())
        }
        if (na <= 0.0 || nb <= 0.0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }
}
