package com.wangyao.qualityevaluation.core

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.sqrt

/**
 * 图像质量客观评价指标（《数字图像与视频处理》第 9 章 9.2~9.3 节）。
 *
 * 全参考（Full-Reference）客观评价：同时需要原始图像与失真图像，
 * 在灰度（亮度）分量上计算：
 * - MAE  平均绝对误差（式 9-1）；
 * - MSE  均方误差（式 9-2）；
 * - PSNR 峰值信噪比（式 9-3），单位 dB，越高质量越好；
 * - SSIM 结构相似度（式 9-4~9-6），亮度/对比度/结构三因子乘积，
 *   ∈(0,1]，越接近 1 结构越相似——比 PSNR 更符合人眼感知；
 * - 熵   信息熵 H = -Σ p(i)·log₂p(i)（bit/像素），衡量信息量。
 */
object ImageQualityMetrics {

    /** 像素峰值 L=255（8bit 图像）。 */
    const val PEAK = 255.0

    /** SSIM 稳定常数（K1=0.01, K2=0.03, L=255）。 */
    private val C1 = (0.01 * PEAK) * (0.01 * PEAK)
    private val C2 = (0.03 * PEAK) * (0.03 * PEAK)

    /** PSNR 上限（dB）：MSE=0（两图完全相同）时返回，避免 log10(0)。 */
    const val PSNR_MAX = 99.0

    /** 平均绝对误差 MAE = (1/MN)·ΣΣ|f(i,j)-g(i,j)|。 */
    fun mae(a: ByteArray, b: ByteArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
        return sum / a.size
    }

    /** 均方误差 MSE = (1/MN)·ΣΣ[f(i,j)-g(i,j)]²。 */
    fun mse(a: ByteArray, b: ByteArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val d = ((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)).toDouble()
            sum += d * d
        }
        return sum / a.size
    }

    /** 由 MSE 直接换算 PSNR = 10·lg(L²/MSE)。 */
    fun psnrFromMse(mse: Double): Double {
        if (mse <= 0.0) return PSNR_MAX
        return 10.0 * log10(PEAK * PEAK / mse)
    }

    /** 峰值信噪比 PSNR（dB）。 */
    fun psnr(a: ByteArray, b: ByteArray): Double = psnrFromMse(mse(a, b))

    /**
     * 结构相似度 SSIM（8×8 分块平均，式 9-4~9-6）：
     * SSIM(x,y) = [l(x,y)]·[c(x,y)]·[s(x,y)]
     *  l = (2μxμy+C1)/(μx²+μy²+C1)                亮度对比
     *  c = (2σxσy+C2)/(σx²+σy²+C2)                对比度对比
     *  s = (σxy+C3)/(σxσy+C3),  C3=C2/2           结构对比
     * 图像按 8×8 不重叠分块，逐块计算后取全图平均。
     */
    fun ssim(a: ByteArray, b: ByteArray, width: Int, height: Int): Double {
        val blocksX = width / 8
        val blocksY = height / 8
        if (blocksX == 0 || blocksY == 0) return 0.0

        val c3 = C2 / 2.0
        var total = 0.0
        var count = 0

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                val base = by * 8 * width + bx * 8

                // 块内一阶/二阶统计量
                var sa = 0.0; var sb = 0.0
                var saa = 0.0; var sbb = 0.0; var sab = 0.0
                for (y in 0 until 8) {
                    val row = base + y * width
                    for (x in 0 until 8) {
                        val va = (a[row + x].toInt() and 0xFF).toDouble()
                        val vb = (b[row + x].toInt() and 0xFF).toDouble()
                        sa += va; sb += vb
                        saa += va * va; sbb += vb * vb; sab += va * vb
                    }
                }
                val n = 64.0
                val ma = sa / n
                val mb = sb / n
                // 方差与协方差（无偏估计系数用 n-1 或 n 对 SSIM 影响极小，此处用 n）
                val va2 = saa / n - ma * ma
                val vb2 = sbb / n - mb * mb
                val vab = sab / n - ma * mb

                val l = (2.0 * ma * mb + C1) / (ma * ma + mb * mb + C1)
                val c = (2.0 * sqrt(va2 * vb2) + C2) / (va2 + vb2 + C2)
                val s = (vab + c3) / (sqrt(va2 * vb2) + c3)

                total += l * c * s
                count++
            }
        }
        return total / count
    }

    /** 信息熵 H = -Σ p(i)·log₂p(i)（bit/像素），衡量图像信息量。 */
    fun entropy(a: ByteArray): Double {
        val hist = IntArray(256)
        for (v in a) hist[v.toInt() and 0xFF]++
        val n = a.size.toDouble()
        var h = 0.0
        for (c in hist) {
            if (c == 0) continue
            val p = c / n
            h -= p * log2(p)
        }
        return h
    }

    /** 一站式全参考评价结果。 */
    data class Result(
        val mae: Double,
        val mse: Double,
        val psnr: Double,
        val ssim: Double,
        val entropyOriginal: Double,
        val entropyDistorted: Double
    )

    /** 对原始/失真两幅灰度图一次性计算全部指标。 */
    fun evaluateAll(original: ByteArray, distorted: ByteArray, width: Int, height: Int): Result {
        val e = mse(original, distorted)
        return Result(
            mae = mae(original, distorted),
            mse = e,
            psnr = psnrFromMse(e),
            ssim = ssim(original, distorted, width, height),
            entropyOriginal = entropy(original),
            entropyDistorted = entropy(distorted)
        )
    }
}
