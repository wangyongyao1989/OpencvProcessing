package com.wangyao.digitalwatermark.core

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 水印评价指标（第 8 章 8.6 节）。
 *
 * - PSNR 峰值信噪比：衡量嵌入/攻击引入的失真（不可感知性），越高越隐蔽；
 * - NC 归一化互相关系数：衡量提取水印与原始水印的相似度（鲁棒性），NC=1 完全一致；
 * - BER 误码率：提取水印的错误比特比例。
 */
object WatermarkMetrics {

    /**
     * PSNR（dB）：PSNR = 10·log10(255² / MSE)。
     * @param a 原始亮度平面
     * @param b 待比较（含水印/受攻击）亮度平面
     */
    fun psnr(a: ByteArray, b: ByteArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var sse = 0.0
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            sse += d.toDouble() * d
        }
        val mse = sse / a.size
        if (mse <= 1e-9) return 99.0
        return 10.0 * log10(255.0 * 255.0 / mse)
    }

    /**
     * NC 归一化互相关：NC = Σ(W·Ŵ) / (√ΣW² · √ΣŴ²)，NC ∈ [0,1]。
     */
    fun nc(original: BooleanArray, extracted: BooleanArray): Double {
        if (original.size != extracted.size || original.isEmpty()) return 0.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in original.indices) {
            val x = if (original[i]) 1.0 else -1.0
            val y = if (extracted[i]) 1.0 else -1.0
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na <= 0 || nb <= 0) return 0.0
        return dot / (sqrt(na) * sqrt(nb))
    }

    /**
     * BER 误码率（%）：BER = 错误比特数 / 总比特数 × 100。
     */
    fun ber(original: BooleanArray, extracted: BooleanArray): Double {
        if (original.size != extracted.size || original.isEmpty()) return 100.0
        var err = 0
        for (i in original.indices) if (original[i] != extracted[i]) err++
        return err * 100.0 / original.size
    }
}
