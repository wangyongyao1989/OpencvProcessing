package com.wangyao.contentsearch.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 基于内容的图像检索（CBIR）特征提取与相似性度量
 * （《数字图像与视频处理》第 10 章 10.2~10.4 节）。
 *
 * CBIR 流程：查询图像 → 提取内容特征（颜色/纹理/形状）→ 与图像库
 * 特征逐一匹配（相似性度量）→ 按相似度排序返回 Top-K。
 *
 * 本模块实现两类经典视觉特征：
 * - 颜色特征：HSV 空间 3D 直方图（式 10-1）——CBIR 最常用特征，
 *   对旋转/平移/尺度不变，反映全局色彩分布；
 * - 纹理特征：梯度方向直方图（类 GIST/HOG 思想）——统计边缘
 *   方向分布，区分平滑/纹理丰富区域，弥补纯颜色特征的歧义。
 *
 * 相似性度量（式 10-2~10-4）：
 * - 直方图相交 Sim(H1,H2)=Σ min(h1(i),h2(i))，∈[0,1]，越大越相似；
 * - 余弦相似度 Cos = A·B/(|A||B|)，∈[0,1]（特征非负）；
 * - 欧氏距离转相似度 Sim = 1/(1+d)。
 */
object ImageFeatures {

    // -------------------------------------------------------------------------
    // 颜色特征：HSV 3D 直方图
    // -------------------------------------------------------------------------

    /** H 量化级数（色调）。 */
    const val H_BINS = 8
    /** S 量化级数（饱和度）。 */
    const val S_BINS = 3
    /** V 量化级数（明度）。 */
    const val V_BINS = 3
    /** 颜色直方图总维数 8×3×3 = 72（第 10 章经典量化方案）。 */
    const val COLOR_DIM = H_BINS * S_BINS * V_BINS

    /**
     * 计算 HSV 3D 颜色直方图（式 10-1，L1 归一化，和为 1）。
     * RGB→HSV 后按 (H,S,V) 联合量化到 72 维。
     */
    fun colorHistogram(argb: IntArray): FloatArray {
        val hist = FloatArray(COLOR_DIM)
        for (p in argb) {
            val r = ((p shr 16) and 0xFF) / 255.0
            val g = ((p shr 8) and 0xFF) / 255.0
            val b = (p and 0xFF) / 255.0

            // RGB → HSV
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val v = max
            val s = if (max <= 0.0) 0.0 else delta / max
            val h = when {
                delta <= 0.0 -> 0.0
                max == r -> 60.0 * (((g - b) / delta) % 6.0)
                max == g -> 60.0 * (((b - r) / delta) + 2.0)
                else -> 60.0 * (((r - g) / delta) + 4.0)
            }.let { if (it < 0) it + 360.0 else it }

            // 联合量化索引
            val hi = (h / 360.0 * H_BINS).toInt().coerceIn(0, H_BINS - 1)
            val si = (s * S_BINS).toInt().coerceIn(0, S_BINS - 1)
            val vi = (v * V_BINS).toInt().coerceIn(0, V_BINS - 1)
            hist[hi * S_BINS * V_BINS + si * V_BINS + vi]++
        }
        val n = argb.size.toFloat()
        for (i in hist.indices) hist[i] /= n
        return hist
    }

    // -------------------------------------------------------------------------
    // 纹理特征：梯度方向直方图
    // -------------------------------------------------------------------------

    /** 梯度方向量化级数（18 bins，每 20° 一个）。 */
    const val ORI_BINS = 18
    /** 纹理直方图维数。 */
    const val TEXTURE_DIM = ORI_BINS

    /**
     * 梯度方向直方图（纹理特征，L1 归一化）：
     * Sobel 梯度幅值加权方向统计，统计显著边缘（幅值 > 均值），
     * 对光照变化鲁棒，区分「平滑/水平纹理/复杂纹理」等结构差异。
     */
    fun textureHistogram(luma: ByteArray, width: Int, height: Int): FloatArray {
        val hist = FloatArray(ORI_BINS)
        // 先统计梯度
        val mags = FloatArray(luma.size)
        val oris = FloatArray(luma.size)
        var sumMag = 0.0
        var cnt = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val idx = y * width + x
                // Sobel 算子
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
                oris[idx] = kotlin.math.atan2(gy.toFloat(), gx.toFloat())
                sumMag += mag
                cnt++
            }
        }
        val meanMag = if (cnt > 0) sumMag / cnt else 0.0
        // 只统计显著边缘（幅值大于均值），按幅值加权
        var total = 0.0f
        for (i in mags.indices) {
            val m = mags[i]
            if (m > meanMag && m > 0f) {
                var deg = Math.toDegrees(oris[i].toDouble())
                if (deg < 0) deg += 180.0 // 方向无极性，折叠到 [0,180°)
                val bin = (deg / 180.0 * ORI_BINS).toInt().coerceIn(0, ORI_BINS - 1)
                hist[bin] += m
                total += m
            }
        }
        if (total > 0f) for (i in hist.indices) hist[i] /= total
        return hist
    }

    // -------------------------------------------------------------------------
    // 综合特征与相似性度量
    // -------------------------------------------------------------------------

    /** 一幅图像的综合特征向量（颜色 72 维 + 纹理 18 维）。 */
    class Feature(
        /** HSV 颜色直方图（L1 归一化）。 */
        val color: FloatArray,
        /** 梯度方向直方图（L1 归一化）。 */
        val texture: FloatArray
    )

    /** 提取综合特征：[argb] 像素数组 + [luma] 灰度（可从 argb 计算）。 */
    fun extract(argb: IntArray, luma: ByteArray, width: Int, height: Int): Feature =
        Feature(colorHistogram(argb), textureHistogram(luma, width, height))

    /**
     * 综合相似度（式 10-2~10-4 的加权融合）：
     * Sim = w_c·直方图相交(颜色) + w_t·余弦(纹理)，w_c=0.6, w_t=0.4，
     * 结果 ∈[0,1]，越大越相似。颜色权重高（CBIR 主特征），纹理补充。
     */
    fun similarity(a: Feature, b: Feature): Double {
        val colorSim = histogramIntersection(a.color, b.color)
        val texSim = cosine(a.texture, b.texture)
        return 0.6 * colorSim + 0.4 * texSim
    }

    /** 直方图相交：Σ min(a(i),b(i))，∈[0,1]（式 10-2）。 */
    fun histogramIntersection(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) s += minOf(a[i], b[i])
        return s
    }

    /** 余弦相似度（式 10-3）：A·B/(|A||B|)，特征非负时 ∈[0,1]。 */
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

    /** 欧氏距离（式 10-4）。 */
    fun euclidean(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i].toDouble()
            s += d * d
        }
        return sqrt(s)
    }

    /** 一幅图像自身的颜色直方图（灰度 luma→ARGB 后计算）。 */
    fun colorHistogramFromLuma(luma: ByteArray): FloatArray {
        val argb = IntArray(luma.size)
        for (i in luma.indices) {
            val v = luma[i].toInt() and 0xFF
            argb[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return colorHistogram(argb)
    }
}
