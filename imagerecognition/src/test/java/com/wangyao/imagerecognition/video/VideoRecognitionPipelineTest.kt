package com.wangyao.imagerecognition.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [VideoRecognitionPipeline] 可测纯逻辑单元测试。
 *
 * 说明：recognize()/FrameSource 依赖 MediaCodec/MediaExtractor
 * （设备编解码器），JVM 单测无法覆盖，需 androidTest 真机验证。
 * 此处覆盖：
 * 1. 统计摘要（Summary）的派生指标；
 * 2. 帧差运动检测 [VideoRecognitionPipeline.computeMotion] 的
 *    数学正确性（占比、外接框、阈值、噪声抑制）；
 * 3. 首帧中央模板提取 [VideoRecognitionPipeline.extractCenterTemplate]。
 */
class VideoRecognitionPipelineTest {

    // =========================================================================
    // Summary：派生指标
    // =========================================================================

    @Test
    fun `trackRate is zero for empty summary`() {
        val s = VideoRecognitionPipeline.Summary(
            frames = 0, avgTrackScore = 0.0, lostFrames = 0,
            activeFrames = 0, avgMotionRatio = 0.0
        )
        assertEquals(0.0, s.trackRate, 1e-12)
    }

    @Test
    fun `trackRate computes hit ratio correctly`() {
        // 100 帧丢失 25 帧 → 命中率 0.75
        val s = VideoRecognitionPipeline.Summary(
            frames = 100, avgTrackScore = 0.62, lostFrames = 25,
            activeFrames = 30, avgMotionRatio = 0.04
        )
        assertEquals(0.75, s.trackRate, 1e-12)
    }

    @Test
    fun `trackRate is one when nothing lost`() {
        val s = VideoRecognitionPipeline.Summary(
            frames = 50, avgTrackScore = 0.9, lostFrames = 0,
            activeFrames = 0, avgMotionRatio = 0.0
        )
        assertEquals(1.0, s.trackRate, 1e-12)
    }

    @Test
    fun `trackRate stays in valid range`() {
        // 极端值：全部丢失
        val s = VideoRecognitionPipeline.Summary(
            frames = 10, avgTrackScore = 0.1, lostFrames = 10,
            activeFrames = 10, avgMotionRatio = 0.2
        )
        assertEquals(0.0, s.trackRate, 1e-12)
        assertTrue(s.trackRate in 0.0..1.0)
    }

    @Test
    fun `summary averages are consistent with frame counts`() {
        // 平均得分/占比必然落在其定义域内
        val s = VideoRecognitionPipeline.Summary(
            frames = 459, avgTrackScore = 0.7, lostFrames = 12,
            activeFrames = 100, avgMotionRatio = 0.03
        )
        assertTrue(s.avgTrackScore in -1.0..1.0)
        assertTrue(s.avgMotionRatio in 0.0..1.0)
        assertTrue(s.lostFrames <= s.frames)
        assertTrue(s.activeFrames <= s.frames)
        assertEquals(459 - 12, (s.trackRate * s.frames + 0.5).toInt())
    }

    // =========================================================================
    // computeMotion：帧差运动检测（第 11 章运动分析基础）
    // =========================================================================

    /** 构造 w×h 的常量帧。 */
    private fun constFrame(w: Int, h: Int, v: Int) = ByteArray(w * h) { v.toByte() }

    @Test
    fun `computeMotion returns zero ratio and null box for identical frames`() {
        val w = 40; val h = 30
        val f = constFrame(w, h, 128)
        val (ratio, box) = VideoRecognitionPipeline.computeMotion(
            f, f.copyOf(), w, threshold = 25, minAreaRatio = 0.0005
        )
        assertEquals(0.0, ratio, 1e-12)
        assertNull(box)
    }

    @Test
    fun `computeMotion computes exact ratio and bounding box for known block`() {
        val w = 40; val h = 30
        val prev = constFrame(w, h, 100)
        val cur = prev.copyOf()
        // 已知矩形块 [x=10..19, y=5..9] 增大 80（50 像素）
        for (y in 5..9) for (x in 10..19) cur[y * w + x] = 180.toByte()
        val (ratio, box) = VideoRecognitionPipeline.computeMotion(
            cur, prev, w, threshold = 25, minAreaRatio = 0.0005
        )
        // 占比 = 50 / 1200
        assertEquals(50.0 / (w * h), ratio, 1e-12)
        // 外接框为半开区间：右/下边界 = 最大索引 + 1
        assertEquals(10, box!!.left)
        assertEquals(5, box.top)
        assertEquals(20, box.right)   // 19 + 1
        assertEquals(10, box.bottom)  // 9 + 1
    }

    @Test
    fun `computeMotion ignores sub-threshold differences`() {
        val w = 32; val h = 32
        val prev = constFrame(w, h, 100)
        val cur = prev.copyOf()
        // 全图 +25：恰好等于阈值（不大于）→ 全部不计为运动
        for (i in cur.indices) cur[i] = 125.toByte()
        val (ratio, box) = VideoRecognitionPipeline.computeMotion(
            cur, prev, w, threshold = 25, minAreaRatio = 0.0005
        )
        assertEquals(0.0, ratio, 1e-12)
        assertNull(box)
    }

    @Test
    fun `computeMotion counts both brightening and darkening pixels`() {
        val w = 20; val h = 10
        val prev = constFrame(w, h, 128)
        val cur = prev.copyOf()
        cur[3] = 200.toByte()              // 变亮 +72
        cur[7] = 50.toByte()               // 变暗 -78
        cur[11] = 130.toByte()             // +2 忽略
        val (ratio, box) = VideoRecognitionPipeline.computeMotion(
            cur, prev, w, threshold = 25, minAreaRatio = 0.0
        )
        assertEquals(2.0 / (w * h), ratio, 1e-12)
        // 外接框覆盖第 3 行第 3 列到第 7 列（行优先：索引 3、7 同行）
        assertEquals(3, box!!.left)
        assertEquals(0, box.top)
        assertEquals(8, box.right)
        assertEquals(1, box.bottom)
    }

    @Test
    fun `computeMotion suppresses box for tiny noise regions`() {
        val w = 100; val h = 100
        val prev = constFrame(w, h, 100)
        val cur = prev.copyOf()
        // 3 个孤立噪声点：占比 0.03% < minAreaRatio 0.05% → 有占比无框
        cur[0] = 255.toByte()
        cur[5555] = 0.toByte()
        cur[9876] = 255.toByte()
        val (ratio, box) = VideoRecognitionPipeline.computeMotion(
            cur, prev, w, threshold = 25, minAreaRatio = 0.0005
        )
        assertEquals(3.0 / (w * h), ratio, 1e-12)
        assertNull("tiny noise region should not produce a box", box)
    }

    @Test
    fun `computeMotion rejects mismatched frame sizes`() {
        val a = constFrame(10, 10, 0)
        val b = constFrame(11, 10, 0)
        try {
            VideoRecognitionPipeline.computeMotion(a, b, 10, 25, 0.0005)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // 预期：尺寸不一致直接拒绝
        }
    }

    @Test
    fun `computeMotion ratio equals one for complete scene change`() {
        val w = 16; val h = 16
        val prev = constFrame(w, h, 0)
        val cur = constFrame(w, h, 255)
        val (ratio, box) = VideoRecognitionPipeline.computeMotion(
            cur, prev, w, threshold = 25, minAreaRatio = 0.0005
        )
        assertEquals(1.0, ratio, 1e-12)
        // 全图运动 → 外接框为整帧
        assertEquals(0, box!!.left)
        assertEquals(0, box.top)
        assertEquals(w, box.right)
        assertEquals(h, box.bottom)
    }

    // =========================================================================
    // extractCenterTemplate：首帧模板提取
    // =========================================================================

    @Test
    fun `extractCenterTemplate returns centered position and exact content`() {
        val w = 48; val h = 36; val tw = 8; val th = 6
        // 每像素值 = 索引，内容唯一可校验
        val frame = ByteArray(w * h) { (it % 251).toByte() }
        val (tpl, x, y) = VideoRecognitionPipeline.extractCenterTemplate(
            frame, w, h, tw, th
        )
        // 居中（整除向下取整）
        assertEquals((w - tw) / 2, x)
        assertEquals((h - th) / 2, y)
        assertEquals(tw * th, tpl.size)
        // 内容与源图逐像素一致
        for (j in 0 until th) {
            for (i in 0 until tw) {
                assertEquals(frame[(y + j) * w + x + i], tpl[j * tw + i])
            }
        }
    }

    @Test
    fun `extractCenterTemplate handles even-odd dimension mismatch`() {
        // 奇数宽高：模板偏左上 1 像素仍应整除安全
        val w = 45; val h = 29; val tw = 10; val th = 5
        val frame = ByteArray(w * h) { 7.toByte() }
        val (tpl, x, y) = VideoRecognitionPipeline.extractCenterTemplate(
            frame, w, h, tw, th
        )
        assertEquals(17, x)  // (45-10)/2
        assertEquals(12, y)  // (29-5)/2
        assertEquals(tw * th, tpl.size)
    }

    @Test
    fun `extractCenterTemplate template matches NCC at its own position`() {
        // 模板提取质量验收：模板在自身位置 NCC 必为 1
        // （跟踪算法首帧 score=1 的前提）
        val w = 64; val h = 64; val tw = 16; val th = 16
        val frame = ByteArray(w * h) { ((it * 37) % 256).toByte() }
        val (tpl, x, y) = VideoRecognitionPipeline.extractCenterTemplate(
            frame, w, h, tw, th
        )
        val score = com.wangyao.imagerecognition.core.NccMatcher.nccAt(
            frame, w, tpl, tw, th, x, y
        )
        assertEquals(1.0, score, 1e-9)
    }
}
