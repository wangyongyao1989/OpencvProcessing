package com.wangyao.digitalwatermark.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 水印攻击方法库（第 8 章 8.5 节：按攻击原理分四类）。
 *
 * 全部作用于视频帧亮度平面（尺寸不变，保证攻击后仍可重新编码）：
 * - 简单攻击：JPEG 压缩 / 高斯噪声 / 椒盐噪声 / 均值滤波 / 中值滤波
 *   （削弱水印幅度，对策：增大嵌入强度、冗余嵌入 + 多数投票、纠错编码）
 * - 同步攻击：缩放 / 旋转 / 裁剪（破坏水印与载体的同步性，相关检测失效，
 *   对策：嵌入同步模板/参照物、有源提取反转、低频过滤）
 * - 帧丢失攻击（视频特有）：丢弃奇数帧（对策：帧间冗余嵌入）
 */
object WatermarkAttacks {

    enum class Type {
        JPEG,            // JPEG 有损压缩攻击（DCT 量化）
        GAUSSIAN,        // 高斯噪声攻击
        SALT_PEPPER,     // 椒盐噪声攻击
        MEAN_FILTER,     // 3×3 均值滤波（线性滤波）攻击
        MEDIAN_FILTER,   // 3×3 中值滤波（非线性滤波）攻击
        SCALE,           // 缩放攻击（0.5 倍缩小再放大）
        ROTATE,          // 旋转攻击（2° 双线性）
        CROP,            // 裁剪攻击（四周裁掉 10% 后补黑边）
        FRAME_DROP       // 帧丢失攻击（丢弃奇数帧）
    }

    // ---- 8×8 DCT 基（与 DctWatermark 相同的正交基）----
    private val B = Array(8) { u ->
        val c = if (u == 0) kotlin.math.sqrt(1.0 / 8.0) else 0.5
        DoubleArray(8) { x -> c * cos((2 * x + 1) * u * PI / 16.0) }
    }

    /**
     * JPEG 压缩攻击：模拟有损压缩的核心环节（第 5 章式 5-19）——
     * 8×8 分块 DCT → 按质量缩放的亮度量化表量化 → IDCT。
     * @param quality JPEG 质量（1~100，越低攻击越强）
     */
    fun jpeg(luma: ByteArray, width: Int, height: Int, quality: Int) {
        val q = quality.coerceIn(1, 100)
        // libjpeg 质量缩放：quality<50 时 scale = 5000/q，否则 scale = 200 − 2q
        val scale = (if (q < 50) 5000 / q else 200 - 2 * q).coerceIn(1, 255)
        val f = Array(8) { DoubleArray(8) }
        val F = Array(8) { DoubleArray(8) }
        for (by in 0 until height / 8) for (bx in 0 until width / 8) {
            val base = by * 8 * width + bx * 8
            for (y in 0 until 8) for (x in 0 until 8)
                f[y][x] = (luma[base + y * width + x].toInt() and 0xFF).toDouble()

            // DCT
            for (u in 0 until 8) for (v in 0 until 8) {
                var s = 0.0
                for (yy in 0 until 8) for (xx in 0 until 8)
                    s += f[yy][xx] * B[u][xx] * B[v][yy]
                F[u][v] = s
            }
            // 量化（信息损失根源）
            for (u in 0 until 8) for (v in 0 until 8) {
                val step = ((DctWatermark.LUMA_QUANT_TABLE[u * 8 + v] * scale + 50) / 100)
                    .coerceAtLeast(1)
                F[u][v] = (F[u][v] / step).roundToInt() * step.toDouble()
            }
            // IDCT
            for (y in 0 until 8) for (x in 0 until 8) {
                var s = 0.0
                for (u in 0 until 8) for (v in 0 until 8)
                    s += F[u][v] * B[u][x] * B[v][y]
                luma[base + y * width + x] = s.roundToInt().coerceIn(0, 255).toByte()
            }
        }
    }

    /** 高斯噪声攻击：加性 N(0, sigma) 白噪声（简单攻击，削弱水印幅度）。 */
    fun gaussianNoise(luma: ByteArray, sigma: Double, seed: Long = 42L) {
        val rnd = java.util.Random(seed)
        var spare: Double? = null
        for (i in luma.indices) {
            // Box-Muller 生成正态分布
            val z = spare ?: run {
                val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
                val u2 = rnd.nextDouble()
                val r = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1))
                spare = r * sin(2.0 * PI * u2)
                r * cos(2.0 * PI * u2)
            }
            spare = null
            val v = (luma[i].toInt() and 0xFF) + sigma * z
            luma[i] = v.roundToInt().coerceIn(0, 255).toByte()
        }
    }

    /** 椒盐噪声攻击：概率 p 将像素置为 0 或 255（冲击噪声）。 */
    fun saltPepperNoise(luma: ByteArray, p: Double, seed: Long = 43L) {
        val rnd = java.util.Random(seed)
        for (i in luma.indices) {
            val r = rnd.nextDouble()
            if (r < p / 2) luma[i] = 0
            else if (r < p) luma[i] = 255.toByte()
        }
    }

    /** 3×3 均值滤波攻击（线性低通滤波，平滑掉高频水印分量）。 */
    fun meanFilter(luma: ByteArray, width: Int, height: Int) {
        val src = luma.copyOf()
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                var s = 0
                for (dy in -1..1) for (dx in -1..1)
                    s += src[i + dy * width + dx].toInt() and 0xFF
                luma[i] = (s / 9).toByte()
            }
        }
    }

    /** 3×3 中值滤波攻击（非线性滤波，对冲击噪声/LSB 水印破坏力强）。 */
    fun medianFilter(luma: ByteArray, width: Int, height: Int) {
        val src = luma.copyOf()
        val w = IntArray(9)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                var k = 0
                for (dy in -1..1) for (dx in -1..1)
                    w[k++] = src[i + dy * width + dx].toInt() and 0xFF
                w.sort()
                luma[i] = w[4].toByte()
            }
        }
    }

    /** 缩放攻击：0.5 倍双线性缩小再放大回原尺寸（破坏像素与水印的同步性）。 */
    fun scale(luma: ByteArray, width: Int, height: Int) {
        val smallW = width / 2
        val smallH = height / 2
        val small = ByteArray(smallW * smallH)
        bilinear(luma, width, height, small, smallW, smallH)
        bilinear(small, smallW, smallH, luma, width, height)
    }

    /** 旋转攻击：旋转 angle 度（双线性插值，出界区域补黑）。 */
    fun rotate(luma: ByteArray, width: Int, height: Int, angleDeg: Double) {
        val src = luma.copyOf()
        val rad = Math.toRadians(angleDeg)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val cx = width / 2.0
        val cy = height / 2.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                // 逆映射：目标像素 → 源坐标
                val dx = x - cx
                val dy = y - cy
                val sx = dx * cosA + dy * sinA + cx
                val sy = -dx * sinA + dy * cosA + cy
                val i = y * width + x
                luma[i] = if (sx in 0.0..(width - 1).toDouble() &&
                    sy in 0.0..(height - 1).toDouble()
                ) sampleBilinear(src, width, height, sx, sy).toByte() else 0
            }
        }
    }

    /** 裁剪攻击：四周裁掉 [ratio] 比例后以黑边补齐（水印随裁剪区域丢失）。 */
    fun crop(luma: ByteArray, width: Int, height: Int, ratio: Double) {
        val mx = (width * ratio).toInt()
        val my = (height * ratio).toInt()
        val src = luma.copyOf()
        java.util.Arrays.fill(luma, 0)
        for (y in my until height - my) {
            System.arraycopy(src, y * width + mx, luma, y * width + mx, width - 2 * mx)
        }
    }

    // ---- 双线性插值工具 ----

    /** 双线性缩放：src(w0×h0) → dst(w1×h1)。 */
    private fun bilinear(src: ByteArray, w0: Int, h0: Int, dst: ByteArray, w1: Int, h1: Int) {
        val xr = w0.toDouble() / w1
        val yr = h0.toDouble() / h1
        for (y in 0 until h1) {
            val sy = (y + 0.5) * yr - 0.5
            for (x in 0 until w1) {
                val sx = (x + 0.5) * xr - 0.5
                dst[y * w1 + x] = sampleBilinear(src, w0, h0, sx, sy).toByte()
            }
        }
    }

    /** 双线性采样（越界取最近边界）。 */
    private fun sampleBilinear(src: ByteArray, w: Int, h: Int, sx: Double, sy: Double): Int {
        val x0 = sx.toInt().coerceIn(0, w - 1)
        val y0 = sy.toInt().coerceIn(0, h - 1)
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val fx = (sx - x0).coerceIn(0.0, 1.0)
        val fy = (sy - y0).coerceIn(0.0, 1.0)
        val p00 = src[y0 * w + x0].toInt() and 0xFF
        val p01 = src[y0 * w + x1].toInt() and 0xFF
        val p10 = src[y1 * w + x0].toInt() and 0xFF
        val p11 = src[y1 * w + x1].toInt() and 0xFF
        val top = p00 * (1 - fx) + p01 * fx
        val bottom = p10 * (1 - fx) + p11 * fx
        return (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255)
    }
}
