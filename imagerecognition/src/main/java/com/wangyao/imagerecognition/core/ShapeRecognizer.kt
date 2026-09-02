package com.wangyao.imagerecognition.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Hu 不变矩（《数字图像与视频处理》第 11 章 11.3 节：形状特征提取）。
 *
 * 原理：
 * 1. 二值图像的区域矩：m_pq = Σ Σ x^p·y^q·I(x,y)
 * 2. 中心矩（平移不变）：μ_pq = Σ Σ (x-x̄)^p·(y-ȳ)^q·I(x,y)
 * 3. 归一化中心矩（尺度不变）：η_pq = μ_pq / μ_00^(1+(p+q)/2)
 * 4. Hu 于 1962 年由 η_20/η_02/η_30/… 构造出 7 个函数，
 *    同时具有平移/旋转/尺度不变性——形状识别的经典特征。
 *
 * 工程上常取对数压缩动态范围：
 *   H'_k = -sign(H_k)·log10(|H_k|)
 */
object HuMoments {

    /** 计算 7 个 Hu 不变矩（输入为 0/255 二值图，行优先）。 */
    fun compute(mask: ByteArray, width: Int, height: Int): DoubleArray {
        // ---- 原始矩 → 质心 ----
        var m00 = 0.0
        var m10 = 0.0
        var m01 = 0.0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val v = (mask[row + x].toInt() and 0xFF) / 255.0
                if (v > 0.0) {
                    m00 += v
                    m10 += x * v
                    m01 += y * v
                }
            }
        }
        if (m00 <= 0.0) return DoubleArray(7)
        val cx = m10 / m00
        val cy = m01 / m00

        // ---- 中心矩（3 阶以内）----
        val mu = HashMap<String, Double>()
        for (p in 0..3) {
            for (q in 0..3) {
                if (p + q > 3 || p + q == 0) continue
                var s = 0.0
                for (y in 0 until height) {
                    val row = y * width
                    val dy = y - cy
                    for (x in 0 until width) {
                        val v = (mask[row + x].toInt() and 0xFF) / 255.0
                        if (v > 0.0) {
                            s += (x - cx).let { it.pow(p) } * dy.pow(q) * v
                        }
                    }
                }
                mu["${p}${q}"] = s
            }
        }

        // ---- 归一化中心矩 ----
        fun eta(p: Int, q: Int): Double {
            val m = mu["${p}${q}"] ?: 0.0
            return m / m00.pow(1.0 + (p + q) / 2.0)
        }
        val e20 = eta(2, 0)
        val e02 = eta(0, 2)
        val e11 = eta(1, 1)
        val e30 = eta(3, 0)
        val e12 = eta(1, 2)
        val e21 = eta(2, 1)
        val e03 = eta(0, 3)

        // ---- Hu 七个不变矩 ----
        val h = DoubleArray(7)
        h[0] = e20 + e02
        h[1] = (e20 - e02).pow(2) + 4.0 * e11.pow(2)
        h[2] = (e30 - 3.0 * e21).pow(2) + (3.0 * e12 - e03).pow(2)
        h[3] = (e30 + e21).pow(2) + (e12 + e03).pow(2)
        h[4] = (e30 - 3.0 * e21) * (e30 + e21) *
            ((e30 + e21).pow(2) - 3.0 * (e12 + e03).pow(2)) +
            (3.0 * e12 - e03) * (e12 + e03) *
            (3.0 * (e30 + e21).pow(2) - (e12 + e03).pow(2))
        h[5] = (e20 - e02) *
            ((e30 + e21).pow(2) - (e12 + e03).pow(2)) +
            4.0 * e11 * (e30 + e21) * (e12 + e03)
        h[6] = (3.0 * e12 - e03) * (e30 + e21) *
            ((e30 + e21).pow(2) - 3.0 * (e12 + e03).pow(2)) -
            (e30 - 3.0 * e21) * (e12 + e03) *
            (3.0 * (e30 + e21).pow(2) - (e12 + e03).pow(2))
        return h
    }

    /** 对数变换：H' = -sign(H)·log10(|H|)（0 映射为 0）。 */
    fun logTransform(h: DoubleArray): DoubleArray =
        DoubleArray(h.size) { i ->
            val v = h[i]
            when {
                v == 0.0 -> 0.0
                v > 0 -> -log10(v)
                else -> log10(-v)
            }
        }
}

/**
 * 形状识别器：合成形状库 + Hu 不变矩特征 + 最近邻分类
 * （第 11 章 11.3~11.4 节：特征提取 → 统计分类决策的完整演示）。
 *
 * 形状库：圆形 / 矩形 / 三角形 / 星形 / 椭圆，
 * 每类含原始 + 旋转 30°/45° + 缩放 1.5× 的副本——
 * 同类不同变换副本的 Hu 特征应几乎相同（不变性验证），
 * 不同类之间距离显著更大（可分性验证）。
 */
class ShapeRecognizer {

    /** 形状类别。 */
    enum class Shape { CIRCLE, RECT, TRIANGLE, STAR, ELLIPSE }

    /** 分类结果。 */
    data class Result(
        val label: Shape,
        /** 与最近库样本的对数 Hu 距离（越小越相似）。 */
        val distance: Double,
        /** 置信度 = 1/(1+d)，∈(0,1]。 */
        val confidence: Double,
        /** Top-3 候选（按距离升序）。 */
        val top3: List<Pair<Shape, Double>>
    )

    /** 库样本：形状 + 其对数 Hu 特征。 */
    private data class Sample(val shape: Shape, val feat: DoubleArray)

    private val samples = mutableListOf<Sample>()

    init { buildLibrary() }

    /**
     * 构建形状特征库：5 类 × 6 旋转角(0°~75°，步进 15°) × 2 尺度
     * = 60 个样本。
     *
     * 参考样本密度直接决定最近邻分类器的覆盖能力：细顶点形状
     * （三角形）在斜角栅格化下 Hu 特征随旋转漂移明显，稀疏库
     * （仅 0/30/45°）时 53°+1.5× 的三角形查询会误判为矩形；
     * 加密到 15° 步进后同类最近距离 <0.6，类间 >3，余量充足。
     * 等边三角形具有 120° 旋转对称，0~75° 即等效全覆盖。
     */
    private fun buildLibrary() {
        for (shape in Shape.entries) {
            for (rot in 0..75 step 15) {
                for (scale in listOf(1.0, 1.5)) {
                    val mask = renderShape(shape, SIZE, rot.toDouble(), scale)
                    val hu = HuMoments.logTransform(HuMoments.compute(mask, SIZE, SIZE))
                    samples.add(Sample(shape, hu))
                }
            }
        }
    }

    /** 对查询二值图分类。 */
    fun classify(queryMask: ByteArray, width: Int, height: Int): Result {
        val qf = HuMoments.logTransform(HuMoments.compute(queryMask, width, height))
        val ranked = samples
            .map { it.shape to weightedL2(qf, it.feat) }
            .sortedBy { it.second }
        val (best, d) = ranked.first()
        return Result(
            label = best,
            distance = d,
            confidence = 1.0 / (1.0 + d),
            top3 = ranked.take(3)
        )
    }

    /**
     * 加权欧氏距离：只用 H1~H3（前三个低阶不变量）。
     *
     * 实测发现 H4~H7 原值趋于零，-lg 放大后对像素离散化噪声
     * 极其敏感（同形状异变换副本间甚至发生符号翻转，距离 20+），
     * 完全淹没判别信息；而 H1~H3 对本形状库的类间距离 >4、
     * 类内距离 <1，判别度充足。Hu 矩工程应用中「仅用前几个
     * 低阶不变量」是标准做法。
     */
    private fun weightedL2(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until 3) {
            val d = a[i] - b[i]
            s += d * d
        }
        return sqrt(s)
    }

    /** 计算任意形状（用于查询生成）：渲染 + Hu 特征。 */
    fun huOf(shape: Shape, rotDeg: Double = 0.0, scale: Double = 1.0): DoubleArray =
        HuMoments.logTransform(
            HuMoments.compute(renderShape(shape, SIZE, rotDeg, scale), SIZE, SIZE)
        )

    companion object {
        /** 渲染画布尺寸。 */
        const val SIZE = 96

        /**
         * 渲染指定形状的二值图（0/255）：
         * 对画布逐像素做形状内外判定（旋转/缩放后逆变换回形状
         * 局部坐标系判定），比 Canvas 绘制更精确可控。
         */
        fun renderShape(
            shape: Shape, size: Int,
            rotDeg: Double = 0.0, scale: Double = 1.0
        ): ByteArray {
            val mask = ByteArray(size * size)
            val cx = size / 2.0
            val cy = size / 2.0
            val r = size * 0.32 * scale
            val rad = rotDeg * PI / 180.0
            val cosA = kotlin.math.cos(rad)
            val sinA = kotlin.math.sin(rad)
            for (y in 0 until size) {
                for (x in 0 until size) {
                    // 旋转逆变换到形状局部坐标
                    val dx = x - cx
                    val dy = y - cy
                    val lx = dx * cosA + dy * sinA
                    val ly = -dx * sinA + dy * cosA
                    val inside = when (shape) {
                        Shape.CIRCLE -> lx * lx + ly * ly <= r * r
                        Shape.ELLIPSE -> {
                            val a = r
                            val b = r * 0.55
                            (lx * lx) / (a * a) + (ly * ly) / (b * b) <= 1.0
                        }
                        Shape.RECT -> abs(lx) <= r * 0.85 && abs(ly) <= r * 0.6
                        Shape.TRIANGLE -> {
                            // 等边三角形（顶点朝上，重心在原点）
                            val h = r * 1.5
                            val topY = -h / 3.0 * 2.0
                            if (ly >= topY && ly <= topY + h) {
                                val t = (ly - topY) / h
                                abs(lx) <= (1.0 - t) * h / kotlin.math.sqrt(3.0)
                            } else false
                        }
                        Shape.STAR -> {
                            // 标准五角星：10 顶点多边形（外/内半径按黄金比），
                            // 直线边 + 射线法（ray casting）内点判定。
                            // 相比极坐标锯齿星，直线边像素化更规整，
                            // Hu 矩对旋转/缩放更稳定。
                            val bigR = r * 1.1
                            val smallR = bigR * 0.382
                            val xs = DoubleArray(10)
                            val ys = DoubleArray(10)
                            for (k in 0 until 10) {
                                val a = -PI / 2 + k * PI / 5
                                val rr = if (k % 2 == 0) bigR else smallR
                                xs[k] = rr * kotlin.math.cos(a)
                                ys[k] = rr * kotlin.math.sin(a)
                            }
                            var insideFlag = false
                            var prev = 9
                            for (i in 0 until 10) {
                                if ((ys[i] > ly) != (ys[prev] > ly) &&
                                    lx < (xs[prev] - xs[i]) * (ly - ys[i]) /
                                    (ys[prev] - ys[i]) + xs[i]
                                ) insideFlag = !insideFlag
                                prev = i
                            }
                            insideFlag
                        }
                    }
                    if (inside) mask[y * size + x] = 255.toByte()
                }
            }
            return mask
        }
    }
}
