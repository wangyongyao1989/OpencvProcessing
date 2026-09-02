package com.wangyao.videorecognition.face

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FaceTrackMath] 单元测试（《视频识别及物体人脸识别需求文档》
 * F-05：视频人脸检测与跟踪的纯算法部分）。
 *
 * 验证维度：
 * 1. IoU 数学正确性：完全重合 = 1、不相交 = 0、已知重叠的精确
 *    值、对称性、包含关系；
 * 2. 时间平滑：历史为空时透传、抖动均值化、低于关联阈值
 *    （IoU ≤ 0.3）的新目标保持原样、置信度保持、多目标独立关联；
 * 3. 均值下采样：已知网格的精确均值、常数图不变、f=1 恒等、
 *    尺寸不整除时向下取整；
 * 4. 最近帧查找：二分定位的精确命中/就近/边界（首帧、尾帧）、
 *    空列表安全返回 -1。
 */
class FaceTrackMathTest {

    // -------------------------------------------------------------------------
    // 测试数据构造工具
    // -------------------------------------------------------------------------

    /** 正方形人脸框，左上角 (x, y)。 */
    private fun box(x: Int, y: Int, size: Int, conf: Double = 0.9) =
        FaceBox(x, y, size, size, conf)

    /** 按时间戳序列构造分析帧（无人脸）。 */
    private fun frames(vararg ptsUs: Long): List<FaceFrame> =
        ptsUs.mapIndexed { i, pts -> FaceFrame(i, pts, emptyList()) }

    // =========================================================================
    // 1. IoU（交并比）
    // =========================================================================

    @Test
    fun iou_identicalBoxes_isOne() {
        val a = box(10, 20, 50)
        assertEquals(1.0, FaceTrackMath.iou(a, a), 1e-9)
    }

    @Test
    fun iou_disjointBoxes_isZero() {
        val a = box(0, 0, 40)
        val b = box(100, 100, 40)
        assertEquals(0.0, FaceTrackMath.iou(a, b), 1e-9)
    }

    @Test
    fun iou_halfOverlap_isOneThird() {
        // 两个 100x100 框横向错开 50：交 5000，并 15000 → 1/3
        val a = box(0, 0, 100)
        val b = box(50, 0, 100)
        assertEquals(1.0 / 3.0, FaceTrackMath.iou(a, b), 1e-9)
    }

    @Test
    fun iou_isSymmetric() {
        val a = box(0, 0, 60)
        val b = box(30, 20, 80)
        assertEquals(
            FaceTrackMath.iou(a, b),
            FaceTrackMath.iou(b, a),
            1e-12
        )
    }

    @Test
    fun iou_touchingEdge_isZero() {
        // 共边但半开区间不相交
        val a = box(0, 0, 50)
        val b = box(50, 0, 50)
        assertEquals(0.0, FaceTrackMath.iou(a, b), 1e-9)
    }

    @Test
    fun iou_containment_ratioIsSmallOverLarge() {
        // 20x20 完全位于 100x100 内：IoU = 400/10000
        val small = box(40, 40, 20)
        val large = box(0, 0, 100)
        assertEquals(0.04, FaceTrackMath.iou(small, large), 1e-9)
    }

    // =========================================================================
    // 2. 时间平滑（IoU 关联 + 滑动平均）
    // =========================================================================

    @Test
    fun smooth_emptyHistory_passesThrough() {
        val cur = listOf(box(10, 10, 50), box(200, 60, 40))
        val out = FaceTrackMath.smooth(cur, emptyList())
        assertEquals(cur, out)
    }

    @Test
    fun smooth_emptyCurrent_returnsEmpty() {
        assertTrue(FaceTrackMath.smooth(emptyList(), listOf(listOf(box(0, 0, 50)))).isEmpty())
    }

    @Test
    fun smooth_associatedJitter_averagedToMidpoint() {
        // 历史 (100,100,60)；当前抖动到 (104,96,64)：IoU>0.3 关联，
        // 输出 = 当前与历史均值 ((100+104)/2, (100+96)/2, (60+64)/2)
        val history = listOf(listOf(box(100, 100, 60)))
        val cur = listOf(box(104, 96, 64))
        val out = FaceTrackMath.smooth(cur, history)
        assertEquals(1, out.size)
        assertEquals(102, out[0].x)
        assertEquals(98, out[0].y)
        assertEquals(62, out[0].w)
        assertEquals(62, out[0].h)
    }

    @Test
    fun smooth_largeDisplacement_belowThreshold_keptAsIs() {
        // 移动过大（IoU ≤ 0.3）→ 视为新目标，不参与平滑
        val history = listOf(listOf(box(0, 0, 50)))
        val cur = listOf(box(90, 90, 50)) // IoU=0，远离
        val out = FaceTrackMath.smooth(cur, history)
        assertEquals(cur[0], out[0])
    }

    @Test
    fun smooth_confidencePreserved() {
        val history = listOf(listOf(box(100, 100, 60, 0.7)))
        val cur = listOf(box(102, 98, 62, 0.85))
        val out = FaceTrackMath.smooth(cur, history)
        // 置信度始终取当前帧的值（历史只平滑几何量）
        assertEquals(0.85, out[0].conf, 1e-12)
    }

    @Test
    fun smooth_multipleHistoryFrames_averageOfAssociations() {
        // 两帧历史 (100,100,60)、(110,104,64)；当前 (106,100,64)：
        // 与两者 IoU 均 > 0.3 → 历史均值 (105,102,62)，
        // 输出 = ((106+105)/2, (100+102)/2, (64+62)/2) 取整
        val history = listOf(
            listOf(box(100, 100, 60)),
            listOf(box(110, 104, 64))
        )
        val cur = listOf(box(106, 100, 64))
        val out = FaceTrackMath.smooth(cur, history)
        assertEquals(105, out[0].x)
        assertEquals(101, out[0].y)
        assertEquals(63, out[0].w)
        assertEquals(63, out[0].h)
    }

    @Test
    fun smooth_twoTargets_eachAssociatesIndependently() {
        // 两个目标分别被关联到各自的历史框，互不串扰
        val history = listOf(
            listOf(box(0, 0, 50), box(300, 200, 50))
        )
        val cur = listOf(box(2, 2, 52), box(302, 198, 52))
        val out = FaceTrackMath.smooth(cur, history)
        assertEquals(2, out.size)
        // 目标 1：均值 ((0+2)/2, (0+2)/2, (50+52)/2)
        assertEquals(1, out[0].x)
        assertEquals(1, out[0].y)
        // 目标 2：均值 ((300+302)/2, (200+198)/2, (50+52)/2)
        assertEquals(301, out[1].x)
        assertEquals(199, out[1].y)
    }

    // =========================================================================
    // 3. 均值下采样
    // =========================================================================

    @Test
    fun downsample_factorOne_returnsSameArray() {
        val src = byteArrayOf(1, 2, 3, 4)
        assertSame(src, FaceTrackMath.downsample(src, 2, 2, 1))
    }

    @Test
    fun downsample_knownGrid_exactMeans() {
        // 4x4 网格，2x2 分块均值：
        // | 10 20 | 30 40 |     | 15 35 |
        // | 20 10 | 40 30 | --> | 15 35 |
        // |  0  0 | 90 90 |     |  0 90 |
        // |  0  0 | 90 90 |     |  0 90 |
        val src = byteArrayOf(
            10, 20, 30, 40,
            20, 10, 40, 30,
            0, 0, 90, 90,
            0, 0, 90, 90
        )
        val out = FaceTrackMath.downsample(src, 4, 4, 2)
        assertEquals(4, out.size)
        assertArrayEquals(byteArrayOf(15, 35, 0, 90), out)
    }

    @Test
    fun downsample_constantImage_staysConstant() {
        val src = ByteArray(9 * 9) { 137.toByte() }
        val out = FaceTrackMath.downsample(src, 9, 9, 3)
        assertArrayEquals(ByteArray(9) { 137.toByte() }, out)
    }

    @Test
    fun downsample_byteValues_interpretedUnsigned() {
        // 负字节按无符号（0x80..0xFF）参与均值：
        // (0xFF + 0x01 + 0xFF + 0x01)/4 = 512/4 = 128
        val src = byteArrayOf(
            0xFF.toByte(), 0x01.toByte(),
            0xFF.toByte(), 0x01.toByte()
        )
        val out = FaceTrackMath.downsample(src, 2, 2, 2)
        assertEquals(1, out.size)
        assertEquals(128, out[0].toInt() and 0xFF)
    }

    @Test
    fun downsample_nonDivisibleDimensions_floored() {
        // 5x4 → f=2 → 2x2（末行/列被丢弃）
        val src = ByteArray(5 * 4) { (it * 3).toByte() }
        val out = FaceTrackMath.downsample(src, 5, 4, 2)
        assertEquals(4, out.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun downsample_rejectsInvalidFactor() {
        FaceTrackMath.downsample(byteArrayOf(1, 2, 3, 4), 2, 2, 0)
    }

    // =========================================================================
    // 4. 最近帧查找（播放同步）
    // =========================================================================

    @Test
    fun nearest_emptyFrames_returnsMinusOne() {
        assertEquals(-1, FaceTrackMath.findNearestFrameIndex(emptyList(), 0L))
    }

    @Test
    fun nearest_exactTimestamp_returnsThatIndex() {
        val fs = frames(0L, 33_333L, 66_667L, 100_000L)
        assertEquals(2, FaceTrackMath.findNearestFrameIndex(fs, 66_667L))
    }

    @Test
    fun nearest_betweenTwoFrames_returnsCloserOne() {
        val fs = frames(0L, 33_333L, 66_667L, 100_000L)
        // 40ms 距 33.3ms 约 6.7ms，距 66.7ms 约 26.7ms
        assertEquals(1, FaceTrackMath.findNearestFrameIndex(fs, 40_000L))
        // 60ms 距 66.7ms 约 6.7ms，距 33.3ms 约 26.7ms
        assertEquals(2, FaceTrackMath.findNearestFrameIndex(fs, 60_000L))
    }

    @Test
    fun nearest_beforeFirst_returnsFirst() {
        val fs = frames(1_000L, 2_000L, 3_000L)
        assertEquals(0, FaceTrackMath.findNearestFrameIndex(fs, -500L))
    }

    @Test
    fun nearest_afterLast_returnsLast() {
        val fs = frames(1_000L, 2_000L, 3_000L)
        assertEquals(2, FaceTrackMath.findNearestFrameIndex(fs, 10_000_000L))
    }

    @Test
    fun nearest_singleFrame_alwaysReturnsIt() {
        val fs = frames(1_234L)
        assertEquals(0, FaceTrackMath.findNearestFrameIndex(fs, 0L))
        assertEquals(0, FaceTrackMath.findNearestFrameIndex(fs, 9_999_999L))
    }

    @Test
    fun nearest_probeSweep_alwaysMinimizesDistance() {
        // 对有序时间戳，任意探测点返回的帧到探测点的距离
        // 必须等于全序列的最小距离（平局时允许返回任一侧）
        val fs = frames(0L, 100L, 250L, 260L, 500L, 900L, 1500L)
        for (t in 0..1500 step 7) {
            val minDist = fs.minOf { Math.abs(it.timestampUs - t.toLong()) }
            val idx = FaceTrackMath.findNearestFrameIndex(fs, t.toLong())
            assertTrue("probe t=$t", idx in fs.indices)
            assertEquals(
                "probe t=$t",
                minDist.toLong(),
                Math.abs(fs[idx].timestampUs - t.toLong())
            )
        }
    }
}
