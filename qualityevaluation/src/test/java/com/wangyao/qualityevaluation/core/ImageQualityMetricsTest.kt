package com.wangyao.qualityevaluation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10

/**
 * [ImageQualityMetrics] 单元测试（纯 JVM，无 Android 依赖）。
 *
 * 覆盖第 9 章全参考客观指标的数学正确性：
 * MAE/MSE（式 9-1/9-2）、PSNR（式 9-3）、SSIM（式 9-4~9-6）、信息熵。
 */
class ImageQualityMetricsTest {

    // -------------------------------------------------------------------------
    // 测试数据构造
    // -------------------------------------------------------------------------

    /** 生成 width×height、值全为 v 的灰度图。 */
    private fun constImage(width: Int, height: Int, v: Int): ByteArray =
        ByteArray(width * height) { v.toByte() }

    /** 生成 width×height、值全为 v+delta 的灰度图。 */
    private fun shiftedImage(width: Int, height: Int, v: Int, delta: Int): ByteArray =
        ByteArray(width * height) { (v + delta).toByte() }

    // -------------------------------------------------------------------------
    // MAE / MSE（式 9-1 / 9-2）
    // -------------------------------------------------------------------------

    @Test
    fun mae_constantDifference_isAbsoluteDifference() {
        // 全部像素差 10 → MAE = 10
        val a = constImage(16, 16, 100)
        val b = shiftedImage(16, 16, 100, 10)
        assertEquals(10.0, ImageQualityMetrics.mae(a, b), 1e-9)
    }

    @Test
    fun mse_constantDifference_isSquaredDifference() {
        // 全部像素差 10 → MSE = 100（式 9-2）
        val a = constImage(16, 16, 100)
        val b = shiftedImage(16, 16, 100, 10)
        assertEquals(100.0, ImageQualityMetrics.mse(a, b), 1e-9)
    }

    @Test
    fun mse_identicalImages_isZero() {
        val a = constImage(16, 16, 128)
        assertEquals(0.0, ImageQualityMetrics.mse(a, a.copyOf()), 1e-12)
    }

    @Test
    fun mae_mse_signedDifference_noSignIssue() {
        // 负方向差分（b 比 a 小）结果应与正方向一致（绝对值/平方）
        val a = constImage(8, 8, 100)
        val b = constImage(8, 8, 90)
        assertEquals(10.0, ImageQualityMetrics.mae(a, b), 1e-9)
        assertEquals(100.0, ImageQualityMetrics.mse(a, b), 1e-9)
    }

    @Test
    fun mse_mixedPixelDifferences_averagesCorrectly() {
        // 4 像素：差分别为 0/1/2/3 → MSE = (0+1+4+9)/4 = 3.5，MAE = 1.5
        val a = byteArrayOf(50, 50, 50, 50)
        val b = byteArrayOf(50, 51, 52, 53)
        assertEquals(1.5, ImageQualityMetrics.mae(a, b), 1e-9)
        assertEquals(3.5, ImageQualityMetrics.mse(a, b), 1e-9)
    }

    // -------------------------------------------------------------------------
    // PSNR（式 9-3）
    // -------------------------------------------------------------------------

    @Test
    fun psnr_identicalImages_returnsMax() {
        val a = constImage(16, 16, 128)
        assertEquals(
            ImageQualityMetrics.PSNR_MAX,
            ImageQualityMetrics.psnr(a, a.copyOf()),
            1e-9
        )
    }

    @Test
    fun psnr_knownMse_matchesClosedForm() {
        // 常数差 10 → MSE=100 → PSNR = 10·lg(255²/100) ≈ 28.1308 dB
        val a = constImage(16, 16, 100)
        val b = shiftedImage(16, 16, 100, 10)
        val expected = 10.0 * log10(255.0 * 255.0 / 100.0)
        assertEquals(expected, ImageQualityMetrics.psnr(a, b), 1e-9)
    }

    @Test
    fun psnr_largerDistortion_isLower() {
        // 失真越大 PSNR 越小（单调性）
        val ref = constImage(16, 16, 128)
        val small = shiftedImage(16, 16, 128, 5)
        val large = shiftedImage(16, 16, 128, 40)
        val p1 = ImageQualityMetrics.psnr(ref, small)
        val p2 = ImageQualityMetrics.psnr(ref, large)
        assertTrue("PSNR should decrease with distortion ($p1 vs $p2)", p1 > p2)
    }

    @Test
    fun psnrFromMse_zero_returnsMax() {
        assertEquals(ImageQualityMetrics.PSNR_MAX, ImageQualityMetrics.psnrFromMse(0.0), 1e-9)
    }

    // -------------------------------------------------------------------------
    // SSIM（式 9-4 ~ 9-6）
    // -------------------------------------------------------------------------

    @Test
    fun ssim_identicalImages_isOne() {
        // 完全相同 → l=c=s=1 → SSIM=1（8×8 分块需要尺寸为 8 的倍数）
        val a = constImage(16, 16, 137)
        assertEquals(1.0, ImageQualityMetrics.ssim(a, a.copyOf(), 16, 16), 1e-9)
    }

    @Test
    fun ssim_constantShift_onlyLuminanceFactorDrops() {
        // 教科书结论：常数亮度偏移（Δ=30）不改变对比度/结构，
        // 只有亮度因子 l 下降 → SSIM 显著小于 1 但远高于同等 MSE 的随机失真
        val a = constImage(16, 16, 100)
        val b = shiftedImage(16, 16, 100, 30)
        val s = ImageQualityMetrics.ssim(a, b, 16, 16)
        assertTrue("SSIM should be in (0,1) but got $s", s > 0.0 && s < 1.0)
    }

    @Test
    fun ssim_structuralDistortion_lowerThanBrightnessShift() {
        // 结构性失真（半图翻转）比等强度亮度偏移对 SSIM 伤害更大
        val w = 16; val h = 16
        val a = ByteArray(w * h) { i -> (i % w).toByte() }          // 水平渐变
        val bright = ByteArray(w * h) { i -> ((i % w) + 40).toByte() } // +40 亮度
        val structural = a.copyOf()
        for (i in structural.indices) structural[i] = (255 - (a[i].toInt() and 0xFF)).toByte()

        val sBright = ImageQualityMetrics.ssim(a, bright, w, h)
        val sStruct = ImageQualityMetrics.ssim(a, structural, w, h)
        assertTrue(
            "structural distortion should hurt SSIM more ($sBright vs $sStruct)",
            sStruct < sBright
        )
    }

    @Test
    fun ssim_tooSmallImage_returnsZero() {
        // 宽/高不足 8（无法分块）时按约定返回 0
        val a = constImage(4, 4, 128)
        assertEquals(0.0, ImageQualityMetrics.ssim(a, a.copyOf(), 4, 4), 1e-12)
    }

    // -------------------------------------------------------------------------
    // 信息熵
    // -------------------------------------------------------------------------

    @Test
    fun entropy_constantImage_isZero() {
        // 常量图无信息 → H=0
        assertEquals(0.0, ImageQualityMetrics.entropy(constImage(16, 16, 128)), 1e-12)
    }

    @Test
    fun entropy_halfHalfTwoValues_isOneBit() {
        // 一半 0 一半 255 → H = 1 bit/像素
        val img = ByteArray(100) { i -> if (i < 50) 0 else 255.toByte() }
        assertEquals(1.0, ImageQualityMetrics.entropy(img), 1e-12)
    }

    @Test
    fun entropy_uniformEightValues_isThreeBits() {
        // 8 个等概率灰度级 → H = log2(8) = 3 bit/像素
        val img = ByteArray(800) { i -> (i % 8 * 30).toByte() }
        assertEquals(3.0, ImageQualityMetrics.entropy(img), 1e-12)
    }

    @Test
    fun entropy_noiseIncreasesEntropy() {
        // 常量图叠加噪声后直方图展宽 → 熵上升（失真评价中的典型现象）
        val constant = constImage(32, 32, 128)
        val noisy = constant.copyOf()
        ImageDistortions.gaussianNoise(noisy, 20.0)
        assertTrue(
            ImageQualityMetrics.entropy(noisy) > ImageQualityMetrics.entropy(constant)
        )
    }

    // -------------------------------------------------------------------------
    // evaluateAll 聚合
    // -------------------------------------------------------------------------

    @Test
    fun evaluateAll_consistentWithIndividualMetrics() {
        val w = 16; val h = 16
        val a = constImage(w, h, 120)
        val b = shiftedImage(w, h, 120, 25)

        val r = ImageQualityMetrics.evaluateAll(a, b, w, h)

        assertEquals(ImageQualityMetrics.mae(a, b), r.mae, 1e-9)
        assertEquals(ImageQualityMetrics.mse(a, b), r.mse, 1e-9)
        assertEquals(ImageQualityMetrics.psnr(a, b), r.psnr, 1e-9)
        assertEquals(ImageQualityMetrics.ssim(a, b, w, h), r.ssim, 1e-9)
        assertEquals(ImageQualityMetrics.entropy(a), r.entropyOriginal, 1e-12)
        assertEquals(ImageQualityMetrics.entropy(b), r.entropyDistorted, 1e-12)
    }
}
