package com.wangyao.imagerecognition.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * [NccMatcher] 单元测试（第 11 章式 11-1 归一化互相关）。
 *
 * 验证维度：
 * 1. 数学正确性：完美匹配 = 1、线性光照不变性（去均值+归一化的意义）；
 * 2. 搜索定位：全图搜索能找回模板的精确位置（含粗到精金字塔路径）；
 * 3. 边界行为：窗口为空 / 方差为零时的安全返回；
 * 4. 下采样：均值运算的数值正确性。
 */
class NccMatcherTest {

    // -------------------------------------------------------------------------
    // 测试图构造工具
    // -------------------------------------------------------------------------

    /** 生成平滑伪随机灰度图（相邻像素有关联，避免处处平坦）。 */
    private fun makeScene(w: Int, h: Int, seed: Long = 42L): ByteArray {
        val rnd = java.util.Random(seed)
        val img = ByteArray(w * h)
        // 行内随机游走：相邻像素差 ±8，产生有纹理的场景
        for (y in 0 until h) {
            var v = 60 + rnd.nextInt(100)
            for (x in 0 until w) {
                v = (v + rnd.nextInt(17) - 8).coerceIn(0, 255)
                img[y * w + x] = v.toByte()
            }
        }
        // 再叠加几条竖直条带增加纹理
        for (x in w / 4 until w / 4 + w / 16) {
            for (y in 0 until h) {
                val i = y * w + x
                img[i] = ((img[i].toInt() and 0xFF) + 60).coerceAtMost(255).toByte()
            }
        }
        return img
    }

    /** 从场景中裁剪模板。 */
    private fun crop(img: ByteArray, w: Int, x0: Int, y0: Int, tw: Int, th: Int): ByteArray {
        val tpl = ByteArray(tw * th)
        for (j in 0 until th) {
            System.arraycopy(img, (y0 + j) * w + x0, tpl, j * tw, tw)
        }
        return tpl
    }

    /** 线性变换：v' = a·v + b（模拟光照增益 + 偏置）。 */
    private fun linearTransform(src: ByteArray, a: Double, b: Int): ByteArray {
        val out = ByteArray(src.size)
        for (i in src.indices) {
            out[i] = (a * (src[i].toInt() and 0xFF) + b).coerceIn(0.0, 255.0).toInt().toByte()
        }
        return out
    }

    // -------------------------------------------------------------------------
    // nccAt：数学性质
    // -------------------------------------------------------------------------

    @Test
    fun `nccAt identical template gives 1`() {
        val w = 64; val h = 64
        val img = makeScene(w, h)
        val tpl = crop(img, w, 16, 20, 24, 20)
        val score = NccMatcher.nccAt(img, w, tpl, 24, 20, 16, 20)
        assertEquals(1.0, score, 1e-9)
    }

    @Test
    fun `nccAt is invariant to linear illumination change`() {
        val w = 64; val h = 64
        // 先把值域压缩到 [30,130]，保证 1.4 倍增益后仍不超出 [0,255]
        // （若发生截断则破坏线性性，测的就不是不变性了）
        val raw = makeScene(w, h)
        val img = ByteArray(raw.size) { i ->
            (0.39 * (raw[i].toInt() and 0xFF) + 30).toInt().toByte()
        }
        val tpl = crop(img, w, 8, 8, 20, 16)
        // 光照变化：增益 1.4 + 偏置 -10（NCC 应保持 = 1）
        val lit = linearTransform(img, 1.4, -10)
        val s1 = NccMatcher.nccAt(img, w, tpl, 20, 16, 8, 8)
        val s2 = NccMatcher.nccAt(lit, w, tpl, 20, 16, 8, 8)
        assertEquals(1.0, s1, 1e-9)
        // 变换含字节整数化截断，容差放宽至 1e-3
        assertEquals(1.0, s2, 1e-3)
    }

    @Test
    fun `nccAt constant window returns 0`() {
        // 窗口方差为零（常量区域）应安全返回 0 而非 NaN/Inf
        val w = 32; val h = 32
        val img = ByteArray(w * h) { 128.toByte() } // 全常量
        val tpl = makeScene(16, 16) // 非常量模板
        val score = NccMatcher.nccAt(img, w, tpl, 16, 16, 8, 8)
        assertEquals(0.0, score, 1e-12)
    }

    @Test
    fun `nccAt constant template returns 0`() {
        val img = makeScene(48, 48)
        val tpl = ByteArray(20 * 20) { 77.toByte() } // 常量模板
        val score = NccMatcher.nccAt(img, 48, tpl, 20, 20, 4, 4)
        assertEquals(0.0, score, 1e-12)
    }

    // -------------------------------------------------------------------------
    // searchWindow / searchFull：定位
    // -------------------------------------------------------------------------

    @Test
    fun `searchWindow finds exact template location`() {
        val w = 80; val h = 60
        val img = makeScene(w, h)
        val tw = 20; val th = 16
        val tpl = crop(img, w, 33, 21, tw, th) // 已知位置 (33,21)
        val r = NccMatcher.searchWindow(img, w, h, tpl, tw, th, 0, 0, w - tw, h - th)
        assertEquals(33.0, r[0], 0.0)
        assertEquals(21.0, r[1], 0.0)
        assertEquals(1.0, r[2], 1e-9)
    }

    @Test
    fun `searchFull finds template through pyramid path`() {
        // 320x240 图会触发 1/4 金字塔粗搜 + 全分辨率精化路径
        val w = 320; val h = 240
        val img = makeScene(w, h, seed = 7L)
        val tw = 48; val th = 48
        val tpl = crop(img, w, 173, 95, tw, th)
        val r = NccMatcher.searchFull(img, w, h, tpl, tw, th)
        assertEquals(173.0, r[0], 0.0)
        assertEquals(95.0, r[1], 0.0)
        assertEquals(1.0, r[2], 1e-9)
    }

    @Test
    fun `searchWindow degenerate range is safe`() {
        // x1 < x0 的空范围：应安全返回起点且不抛异常
        val img = makeScene(32, 32)
        val tpl = ByteArray(8 * 8) { 5.toByte() }
        val r = NccMatcher.searchWindow(img, 32, 32, tpl, 8, 8, 5, 5, 2, 2)
        assertEquals(5.0, r[0], 0.0)
        assertEquals(5.0, r[1], 0.0)
        assertEquals(0.0, r[2], 0.0)
    }

    // -------------------------------------------------------------------------
    // similarityMap
    // -------------------------------------------------------------------------

    @Test
    fun `similarityMap peak is at template position`() {
        val w = 64; val h = 64
        val img = makeScene(w, h)
        val tw = 16; val th = 16
        val step = 4
        val tpl = crop(img, w, 24, 24, tw, th) // 位于 (24,24)，恰为 step 整数倍
        val map = NccMatcher.similarityMap(img, w, h, tpl, tw, th, step)
        val gw = (w - tw) / step + 1
        val gh = (h - tw) / step + 1
        assertEquals(gw * gh, map.size)
        var bestIdx = 0
        for (i in map.indices) if (map[i] > map[bestIdx]) bestIdx = i
        val bx = (bestIdx % gw) * step
        val by = (bestIdx / gw) * step
        assertEquals(24, bx)
        assertEquals(24, by)
        assertEquals(1.0f, map[bestIdx], 1e-6f)
    }

    // -------------------------------------------------------------------------
    // downsample
    // -------------------------------------------------------------------------

    @Test
    fun `downsample computes block mean`() {
        // 8x8 已知图：每 4x4 块均值可手算
        val w = 8; val h = 8
        val img = ByteArray(w * h) { i ->
            // 左上块全 40，右上全 80，左下全 120，右下全 200
            val x = i % w; val y = i / w
            when {
                x < 4 && y < 4 -> 40
                x >= 4 && y < 4 -> 80
                x < 4 -> 120
                else -> 200
            }.toByte()
        }
        val out = NccMatcher.downsample(img, w, h, 4)
        assertEquals(4, out.size) // 2x2
        assertEquals(40, out[0].toInt() and 0xFF)
        assertEquals(80, out[1].toInt() and 0xFF)
        assertEquals(120, out[2].toInt() and 0xFF)
        assertEquals(200, out[3].toInt() and 0xFF)
    }

    @Test
    fun `downsample matches NCC of full image`() {
        // 金字塔一致性：模板在 1/4 尺度下的 NCC 应接近全尺度 NCC
        val w = 64; val h = 64
        val img = makeScene(w, h)
        val tw = 32; val th = 32
        val tpl = crop(img, w, 16, 16, tw, th)
        val small = NccMatcher.downsample(img, w, h, 4)
        val smallTpl = NccMatcher.downsample(tpl, tw, th, 4)
        val sFull = NccMatcher.nccAt(img, w, tpl, tw, th, 16, 16)
        val sSmall = NccMatcher.nccAt(small, 16, smallTpl, 8, 8, 4, 4)
        assertEquals(sFull, sSmall, 0.05)
    }
}
