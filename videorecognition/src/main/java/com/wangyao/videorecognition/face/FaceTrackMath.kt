package com.wangyao.videorecognition.face

/**
 * 视频人脸跟踪的纯算法集（无 Android 依赖，可在 JVM 单元测试中
 * 直接验证，参考 imagerecognition 模块 MotionBox 的解耦方式）。
 *
 * 覆盖《视频识别及物体人脸识别需求文档》F-05（视频人脸检测与
 * 跟踪）中与平台无关的数学部分：
 * 1. IoU（交并比）——跨帧检测框关联度量；
 * 2. 时间平滑——IoU 关联 + 滑动平均，抑制逐帧抖动；
 * 3. 均值下采样——控制检测耗时的整倍降采样；
 * 4. 最近帧查找——按播放时间戳二分定位分析帧（播放同步）。
 */

/** 单个人脸框（工作分辨率坐标，半开区间）。 */
data class FaceBox(
    val x: Int, val y: Int, val w: Int, val h: Int,
    /** 置信度 ∈(0,1]。 */
    val conf: Double
)

/** 单帧检测结果。 */
class FaceFrame(
    val frameIndex: Int,
    /** 帧呈现时间戳（µs，与 MediaPlayer 同步的基准）。 */
    val timestampUs: Long,
    val faces: List<FaceBox>
)

object FaceTrackMath {

    /** IoU（交并比）：完全重合 = 1，不相交 = 0。 */
    fun iou(a: FaceBox, b: FaceBox): Double {
        val x1 = maxOf(a.x, b.x)
        val y1 = maxOf(a.y, b.y)
        val x2 = minOf(a.x + a.w, b.x + b.w)
        val y2 = minOf(a.y + a.h, b.y + b.h)
        val inter = (x2 - x1).coerceAtLeast(0) * (y2 - y1).coerceAtLeast(0)
        if (inter == 0) return 0.0
        val union = a.w * a.h + b.w * b.h - inter
        return inter.toDouble() / union
    }

    /**
     * 用历史帧平滑当前帧的检测框：
     * 当前帧每个框在历史窗口中按 IoU 找关联框（阈值 0.3），
     * 位置/尺寸取「当前值与关联均值」的折中——抑制逐帧抖动，
     * 输出即播放时的平滑跟踪框。当前帧未关联的框保持原样
     * （新目标立即显示）。
     */
    fun smooth(
        current: List<FaceBox>,
        history: List<List<FaceBox>>
    ): List<FaceBox> {
        if (history.isEmpty() || current.isEmpty()) return current
        return current.map { box ->
            var sx = 0.0; var sy = 0.0; var sw = 0.0; var sh = 0.0
            var cnt = 0
            for (hf in history) {
                val best = hf.maxByOrNull { iou(box, it) }
                if (best != null && iou(box, best) > 0.3) {
                    sx += best.x; sy += best.y
                    sw += best.w; sh += best.h
                    cnt++
                }
            }
            if (cnt == 0) box
            else FaceBox(
                ((box.x + sx / cnt) / 2).toInt(),
                ((box.y + sy / cnt) / 2).toInt(),
                ((box.w + sw / cnt) / 2).toInt(),
                ((box.h + sh / cnt) / 2).toInt(),
                box.conf
            )
        }
    }

    /** nxn 均值下采样（w、h 需被 f 整除，余数行/列被丢弃）。 */
    fun downsample(src: ByteArray, w: Int, h: Int, f: Int): ByteArray {
        require(f >= 1) { "factor must be >= 1" }
        if (f == 1) return src
        val ow = w / f
        val oh = h / f
        val out = ByteArray(ow * oh)
        for (y in 0 until oh) {
            for (x in 0 until ow) {
                var s = 0
                for (j in 0 until f) {
                    val row = (y * f + j) * w + x * f
                    for (i in 0 until f) s += src[row + i].toInt() and 0xFF
                }
                out[y * ow + x] = (s / (f * f)).toByte()
            }
        }
        return out
    }

    /**
     * 二分查找时间戳最近的分析帧下标（播放同步用）。
     *
     * @return 最近帧下标；frames 为空返回 -1
     */
    fun findNearestFrameIndex(
        frames: List<FaceFrame>,
        targetUs: Long
    ): Int {
        if (frames.isEmpty()) return -1
        var lo = 0
        var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (frames[mid].timestampUs < targetUs) lo = mid + 1 else hi = mid
        }
        // 与前一项比较取更近者
        return if (lo > 0 &&
            Math.abs(frames[lo - 1].timestampUs - targetUs) <
            Math.abs(frames[lo].timestampUs - targetUs)
        ) lo - 1 else lo
    }
}
