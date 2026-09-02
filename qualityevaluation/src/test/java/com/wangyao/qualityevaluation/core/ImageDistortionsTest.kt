package com.wangyao.qualityevaluation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ImageDistortions] 单元测试（纯 JVM 部分；JPEG 依赖 Bitmap 仅能在设备上验证）。
 *
 * 覆盖高斯噪声 / 椒盐噪声 / 均值模糊 / 亮度偏移的数值行为。
 */
class ImageDistortionsTest {

    // -------------------------------------------------------------------------
    // 高斯噪声
    // -------------------------------------------------------------------------

    @Test
    fun gaussianNoise_changesPixelsAndStaysInRange() {
        val img = ByteArray(64 * 64) { 128.toByte() }
        val before = img.copyOf()
        ImageDistortions.gaussianNoise(img, 15.0)

        var changed = 0
        for (i in img.indices) {
            val v = img[i].toInt() and 0xFF
            assertTrue("pixel out of range: $v", v in 0..255)
            if (img[i] != before[i]) changed++
        }
        assertTrue("too few pixels changed ($changed)", changed > img.size / 2)
    }

    @Test
    fun gaussianNoise_preservesMeanApproximately() {
        // σ=10 时 4096 像素均值偏移应在 ±5 以内（大数定律）
        val img = ByteArray(64 * 64) { 128.toByte() }
        ImageDistortions.gaussianNoise(img, 10.0)
        val mean = img.average { it.toInt() and 0xFF }
        assertTrue("mean drifted too far: $mean", kotlin.math.abs(mean - 128.0) < 5.0)
    }

    @Test
    fun gaussianNoise_deterministicWithSameSeed() {
        // 固定种子 → 可复现（评价实验可重复的必要条件）
        val a = ByteArray(256) { 100.toByte() }
        val b = ByteArray(256) { 100.toByte() }
        ImageDistortions.gaussianNoise(a, 15.0, seed = 42L)
        ImageDistortions.gaussianNoise(b, 15.0, seed = 42L)
        assertTrue("same seed should produce identical noise", a.contentEquals(b))
    }

    @Test
    fun gaussianNoise_zeroSigma_isNoop() {
        val img = ByteArray(64) { 200.toByte() }
        val before = img.copyOf()
        ImageDistortions.gaussianNoise(img, 0.0)
        assertTrue(img.contentEquals(before))
    }

    // -------------------------------------------------------------------------
    // 椒盐噪声
    // -------------------------------------------------------------------------

    @Test
    fun saltPepperNoise_onlyPollutedPixelsChange() {
        val n = 10000
        val density = 0.05
        val img = ByteArray(n) { 128.toByte() }
        ImageDistortions.saltPepperNoise(img, density, seed = 42L)

        var polluted = 0
        for (v in img) {
            val p = v.toInt() and 0xFF
            when (p) {
                0, 255 -> polluted++          // 被污染像素只能是椒(0)或盐(255)
                128 -> { /* 未污染 */ }
                else -> throw AssertionError("unexpected value $p")
            }
        }
        // 期望 5%±统计波动（5σ ≈ ±0.35%）
        val expected = n * density
        assertTrue(
            "polluted count $polluted far from $expected",
            kotlin.math.abs(polluted - expected) < n * 0.01
        )
    }

    @Test
    fun saltPepperNoise_zeroDensity_isNoop() {
        val img = ByteArray(64) { 77.toByte() }
        val before = img.copyOf()
        ImageDistortions.saltPepperNoise(img, 0.0)
        assertTrue(img.contentEquals(before))
    }

    // -------------------------------------------------------------------------
    // 均值滤波（3×3）
    // -------------------------------------------------------------------------

    @Test
    fun meanBlur_constantImage_unchanged() {
        // 常量图模糊后不变（邻域均值仍为常量）
        val w = 16; val h = 16
        val img = ByteArray(w * h) { 99.toByte() }
        val before = img.copyOf()
        ImageDistortions.meanBlur(img, w, h)
        assertTrue(img.contentEquals(before))
    }

    @Test
    fun meanBlur_impulseSmoothedToNeighborhoodMean() {
        // 5×5 全 0 图中心放 9 → 内圈 3×3（中心的完整邻域）每点 = 9/9 = 1，
        // 边界一圈不参与滤波保持 0
        val w = 5; val h = 5
        val img = ByteArray(w * h) { 0 }
        img[2 * w + 2] = 9
        ImageDistortions.meanBlur(img, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val expected = if (x in 1..3 && y in 1..3) 1 else 0
                assertEquals("($x,$y)", expected, img[y * w + x].toInt() and 0xFF)
            }
        }
    }

    @Test
    fun meanBlur_bordersUntouched() {
        // 边界一圈不参与滤波，保持原值
        val w = 5; val h = 5
        val img = ByteArray(w * h) { i -> (i % 251).toByte() }
        val before = img.copyOf()
        ImageDistortions.meanBlur(img, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    assertEquals("border ($x,$y)", before[y * w + x], img[y * w + x])
                }
            }
        }
    }

    @Test
    fun meanBlur_attenuatesHighFrequency() {
        // 棋盘格（0/255 交替）模糊后方差显著下降——高频细节被衰减
        val w = 16; val h = 16
        val img = ByteArray(w * h) { i ->
            if ((i / w + i % w) % 2 == 0) 255.toByte() else 0
        }
        val varBefore = variance(img)
        ImageDistortions.meanBlur(img, w, h)
        val varAfter = variance(img)
        assertTrue(
            "variance should drop ($varBefore -> $varAfter)",
            varAfter < varBefore / 4
        )
    }

    // -------------------------------------------------------------------------
    // 亮度偏移
    // -------------------------------------------------------------------------

    @Test
    fun brightnessShift_addsDeltaPerPixel() {
        val img = ByteArray(16) { 100.toByte() }
        ImageDistortions.brightnessShift(img, 30)
        for (v in img) assertEquals(130, v.toInt() and 0xFF)
    }

    @Test
    fun brightnessShift_clampsAtBounds() {
        // 上溢截断到 255
        val high = byteArrayOf(250.toByte(), 240.toByte())
        ImageDistortions.brightnessShift(high, 30)
        assertEquals(255, high[0].toInt() and 0xFF)
        assertEquals(255, high[1].toInt() and 0xFF)
        // 下溢截断到 0
        val low = byteArrayOf(5, 10)
        ImageDistortions.brightnessShift(low, -30)
        assertEquals(0, low[0].toInt() and 0xFF)
        assertEquals(0, low[1].toInt() and 0xFF)
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    private fun variance(a: ByteArray): Double {
        val mean = a.average { it.toInt() and 0xFF }
        return a.fold(0.0) { acc, v ->
            val d = (v.toInt() and 0xFF) - mean
            acc + d * d
        } / a.size
    }
}

/** ByteArray 求均值帮助函数。 */
private fun ByteArray.average(selector: (Byte) -> Int): Double {
    var sum = 0.0
    for (v in this) sum += selector(v)
    return sum / size
}
