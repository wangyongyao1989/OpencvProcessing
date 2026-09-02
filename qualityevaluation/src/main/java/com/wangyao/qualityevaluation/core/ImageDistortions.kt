package com.wangyao.qualityevaluation.core

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 图像失真生成器：为客观评价构造「失真图像」（《数字图像与视频处理》
 * 第 9 章 9.2 节：客观评价需要原始图像与失真图像对比）。
 *
 * 失真类型对应教材相关章节：
 * - JPEG 压缩（第 5 章 DCT 变换 + 量化：有损压缩的信息损失根源）；
 * - 高斯噪声 / 椒盐噪声（信号传输/采集噪声）；
 * - 均值滤波模糊（低通损失高频细节）；
 * - 亮度偏移（亮度通道的恒定失真，MSE 相同但 SSIM/主观感受不同）。
 */
object ImageDistortions {

    /** 失真类型。 */
    enum class Type { JPEG, GAUSSIAN, SALT_PEPPER, BLUR, BRIGHTNESS }

    /**
     * JPEG 有损压缩（第 5 章原理：8×8 DCT → 量化表粗量化 → 熵编码；
     * 质量因子越低量化步长越大、失真越明显）。
     * 通过 Bitmap JPEG 编解码往返实现，[quality]∈(0,100]。
     */
    fun jpeg(source: Bitmap, quality: Int): Bitmap {
        val bos = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        val bytes = bos.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** 加性高斯白噪声：g = f + n，n~N(0,σ²)，就地修改。 */
    fun gaussianNoise(luma: ByteArray, sigma: Double, seed: Long = 42L) {
        val rnd = Random(seed)
        var spare: Double? = null
        for (i in luma.indices) {
            // Box-Muller 变换生成正态分布
            val z = spare ?: run {
                val u1 = maxOf(rnd.nextDouble(), 1e-12)
                val u2 = rnd.nextDouble()
                val r = sqrt(-2.0 * kotlin.math.ln(u1))
                val t = 2.0 * PI * u2
                spare = r * kotlin.math.sin(t)
                r * cos(t)
            } ?: 0.0
            spare = null
            val v = (luma[i].toInt() and 0xFF) + z * sigma
            luma[i] = v.toInt().coerceIn(0, 255).toByte()
        }
    }

    /** 椒盐噪声：像素以密度 d 随机取 0（椒）或 255（盐），就地修改。 */
    fun saltPepperNoise(luma: ByteArray, density: Double, seed: Long = 42L) {
        val rnd = Random(seed)
        for (i in luma.indices) {
            val p = rnd.nextDouble()
            if (p < density) luma[i] = if (p < density / 2) 0 else 255.toByte()
        }
    }

    /** 3×3 均值滤波（低通模糊：高频细节被衰减）。 */
    fun meanBlur(luma: ByteArray, width: Int, height: Int) {
        val src = luma.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var sum = 0
                for (dy in -1..1) {
                    val row = (y + dy) * width
                    for (dx in -1..1) {
                        sum += src[row + x + dx].toInt() and 0xFF
                    }
                }
                luma[y * width + x] = (sum / 9).toByte()
            }
        }
    }

    /** 亮度偏移：g = f + Δ（恒定失真，SSIM 亮度因子显著下降）。 */
    fun brightnessShift(luma: ByteArray, delta: Int) {
        for (i in luma.indices) {
            luma[i] = ((luma[i].toInt() and 0xFF) + delta).coerceIn(0, 255).toByte()
        }
    }

    /** 失真强度说明用：固定参数（教学演示）。 */
    object Presets {
        const val JPEG_QUALITY = 10       // JPEG 质量因子（强压缩）
        const val GAUSSIAN_SIGMA = 15.0   // 高斯噪声标准差
        const val SALT_PEPPER_DENSITY = 0.05 // 椒盐噪声密度 5%
        const val BRIGHTNESS_DELTA = 30   // 亮度偏移 +30
    }
}
