package com.wangyao.imagerecognition.video

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.wangyao.imagerecognition.core.NccMatcher
import java.nio.ByteBuffer

/** 模块统一日志 TAG（adb logcat -s IR_Pipeline）。 */
private const val TAG = "IR_Pipeline"

/**
 * 视频图像识别管线（《数字图像与视频处理》第 11 章图像识别在
 * 视频中的应用：目标跟踪与运动检测）。
 *
 * 对视频执行两类逐帧识别：
 * 1. 模板跟踪：以首帧中央区域为模板，后续帧在上一帧位置邻域内
 *    用 NCC 搜索最佳匹配（第 11 章模板匹配的时序扩展），输出
 *    逐帧跟踪位置——即「目标识别 + 定位」；
 * 2. 帧差运动检测：相邻帧差分二值化（阈值 25），统计变化像素
 *    占比与外接框——最简单有效的运动目标检测方法。
 *
 * 解码使用软件解码器（c2.android.*，无并发实例限制）+
 * 微秒级超时 + 卡死保护——沿用本项目已验证的实践经验。
 */
class VideoRecognitionPipeline {

    /** 单帧识别结果。 */
    class FrameResult(
        val frameIndex: Int,
        val timestampUs: Long,
        /** 模板中心位置（工作分辨率坐标）。 */
        val trackX: Int,
        val trackY: Int,
        /** 该帧 NCC 跟踪得分 ∈[-1,1]。 */
        val trackScore: Double,
        /** 帧差变化像素占比 ∈[0,1]。 */
        val motionRatio: Double,
        /** 运动区域外接框（工作分辨率，无运动为 null）。 */
        val motionBox: android.graphics.RectF?
    )

    /** 整体识别统计。 */
    data class Summary(
        val frames: Int,
        /** 平均跟踪得分。 */
        val avgTrackScore: Double,
        /** 得分低于 [LOST_THRESHOLD] 视为跟踪丢失的帧数。 */
        val lostFrames: Int,
        /** 运动剧烈帧（占比 > 5%）数量。 */
        val activeFrames: Int,
        /** 平均运动占比。 */
        val avgMotionRatio: Double
    ) {
        /** 跟踪命中率 ∈[0,1]。 */
        val trackRate: Double get() = if (frames > 0) (frames - lostFrames).toDouble() / frames else 0.0
    }

    /** 工作分辨率宽度（跟踪/帧差在此尺度执行，兼顾速度与精度）。 */
    private val workWidth = 480

    /** 帧差二值化阈值。 */
    private val diffThreshold = 25

    /** 跟踪搜索窗口半径（工作分辨率像素）。 */
    private val searchRadius = 32

    /** 模板尺寸（工作分辨率，正方形）。 */
    private val templateSize = 48

    /** 跟踪丢失判定阈值（NCC 低于此值认为丢失）。 */
    private val lostThreshold = 0.5

    /**
     * 执行全视频识别：软解码逐帧 → 模板跟踪 + 帧差运动检测。
     *
     * @param onProgress 处理进度回调 (done, total)
     * @param onFrame 每帧识别回调（可用于实时 UI 展示）
     * @return 帧结果列表 + 统计 + 首帧（工作分辨率，供轨迹绘制）
     */
    fun recognize(
        videoPath: String,
        onProgress: (Int, Int) -> Unit,
        onFrame: ((FrameResult) -> Unit)? = null
    ): Pair<List<FrameResult>, Summary> {
        val results = mutableListOf<FrameResult>()
        var sumScore = 0.0
        var lost = 0
        var active = 0
        var sumMotion = 0.0

        // 先精确统计帧数（进度分母）
        val total = countSamples(videoPath)
        Log.d(TAG, "recognize: $videoPath, $total frames, workWidth=$workWidth")

        FrameSource(videoPath).use { src ->
            // 工作尺度
            val scale = src.width / workWidth.coerceAtMost(src.width)
            val ww = src.width / scale
            val wh = src.height / scale
            val tw = templateSize.coerceAtMost(ww / 3)
            val th = templateSize.coerceAtMost(wh / 3)

            var prev: ByteArray? = null
            var curX = -1
            var curY = -1
            var tpl: ByteArray? = null
            var frameIdx = -1

            while (true) {
                val luma = src.nextFrame() ?: break
                frameIdx++
                val small = if (scale > 1)
                    NccMatcher.downsample(luma, src.width, src.height, scale)
                else luma

                if (frameIdx == 0) {
                    // 首帧：中央取模板，初始位置即模板位置
                    val (t, tx, ty) = extractCenterTemplate(small, ww, wh, tw, th)
                    tpl = t
                    curX = tx
                    curY = ty
                    Log.d(TAG, "template ${tw}x${th} taken at ($curX,$curY)")
                }

                // ---- 模板跟踪：上一位置邻域 NCC 搜索 ----
                var score = 0.0
                if (tpl != null && frameIdx > 0) {
                    val r = NccMatcher.searchWindow(
                        small, ww, wh, tpl, tw, th,
                        (curX - searchRadius).coerceAtLeast(0),
                        (curY - searchRadius).coerceAtLeast(0),
                        (curX + searchRadius).coerceAtMost(ww - tw),
                        (curY + searchRadius).coerceAtMost(wh - th)
                    )
                    curX = r[0].toInt()
                    curY = r[1].toInt()
                    score = r[2]
                } else if (frameIdx == 0) {
                    score = 1.0
                }

                // ---- 帧差运动检测 ----
                var motionRatio = 0.0
                var box: android.graphics.RectF? = null
                if (prev != null && prev.size == small.size) {
                    val (ratio, bbox) =
                        computeMotion(small, prev, ww, diffThreshold, 0.0005)
                    motionRatio = ratio
                    box = bbox?.toRectF()
                }
                prev = small

                // ---- 汇总 ----
                sumScore += score
                if (score < lostThreshold) lost++
                if (motionRatio > 0.05) active++
                sumMotion += motionRatio

                val fr = FrameResult(
                    frameIndex = frameIdx,
                    timestampUs = src.lastPtsUs,
                    trackX = curX + tw / 2,
                    trackY = curY + th / 2,
                    trackScore = score,
                    motionRatio = motionRatio,
                    motionBox = box
                )
                results.add(fr)
                onFrame?.invoke(fr)
                onProgress(frameIdx + 1, total)
            }
        }

        val n = results.size
        val summary = Summary(
            frames = n,
            avgTrackScore = if (n > 0) sumScore / n else 0.0,
            lostFrames = lost,
            activeFrames = active,
            avgMotionRatio = if (n > 0) sumMotion / n else 0.0
        )
        Log.d(
            TAG,
            "recognize: done frames=$n avgScore=${summary.avgTrackScore} " +
                "lost=$lost active=$active"
        )
        return results to summary
    }

    // -------------------------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------------------------

    companion object {
        /**
         * 运动区域外接框（半开区间：right/bottom = 最大索引 + 1）。
         * 独立于 android.graphics.RectF，保证纯算法可在 JVM 单测
         * 中验证（RectF 在本地单测中是 mockable 桩类）。
         */
        data class MotionBox(
            val left: Int, val top: Int, val right: Int, val bottom: Int
        ) {
            /** 转换为 Android RectF（UI 层使用）。 */
            fun toRectF() = android.graphics.RectF(
                left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()
            )
        }

        /**
         * 帧差运动检测（纯函数，便于单元测试）：
         * D(x,y)=|cur-prev| > [threshold] 的像素视为运动像素，
         * 统计占比与外接框；运动像素数不超过总像素 × [minAreaRatio]
         * 时视为噪声、不输出外接框（占比仍然统计）。
         *
         * @return (运动占比 ∈[0,1], 外接框或 null)
         */
        internal fun computeMotion(
            cur: ByteArray, prev: ByteArray, width: Int,
            threshold: Int, minAreaRatio: Double
        ): Pair<Double, MotionBox?> {
            require(cur.size == prev.size) { "frame size mismatch" }
            var cnt = 0
            var minX = Int.MAX_VALUE
            var minY = Int.MAX_VALUE
            var maxX = -1
            var maxY = -1
            for (i in cur.indices) {
                val d = (cur[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF)
                if (d > threshold || d < -threshold) {
                    cnt++
                    val x = i % width
                    val y = i / width
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            val ratio = cnt.toDouble() / cur.size
            val box = if (cnt > 0 && cnt > cur.size * minAreaRatio) {
                MotionBox(minX, minY, maxX + 1, maxY + 1)
            } else null
            return ratio to box
        }

        /**
         * 首帧中央模板提取（纯函数，便于单元测试）。
         * @return (模板像素, 左上角 x, 左上角 y)
         */
        internal fun extractCenterTemplate(
            frame: ByteArray, width: Int, height: Int, tw: Int, th: Int
        ): Triple<ByteArray, Int, Int> {
            val x = (width - tw) / 2
            val y = (height - th) / 2
            val tpl = ByteArray(tw * th)
            for (j in 0 until th) {
                System.arraycopy(frame, (y + j) * width + x, tpl, j * tw, tw)
            }
            return Triple(tpl, x, y)
        }
    }

    /** 预遍历统计视频轨道 sample 数（≈帧数）。 */
    private fun countSamples(path: String): Int {
        val extractor = MediaExtractor().apply { setDataSource(path) }
        try {
            val track = selectVideoTrack(extractor)
            extractor.selectTrack(track)
            val buf = ByteBuffer.allocateDirect(2 shl 20)
            var count = 0
            while (extractor.readSampleData(buf, 0) >= 0) {
                count++
                extractor.advance()
            }
            return count.coerceAtLeast(1)
        } finally {
            extractor.release()
        }
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        throw IllegalArgumentException("No video track found")
    }

    /** 查找软件解码器（无并发实例限制）。 */
    private fun createSoftwareDecoder(mime: String): MediaCodec? {
        return try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (ci in codecs) {
                if (ci.isEncoder) continue
                if (!ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
                val name = ci.name.lowercase()
                if (name.startsWith("omx.google.") ||
                    name.startsWith("c2.android.") ||
                    name.startsWith("c2.google.")
                ) {
                    Log.d(TAG, "createSoftwareDecoder: using ${ci.name} for $mime")
                    return MediaCodec.createByCodecName(ci.name)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "createSoftwareDecoder: lookup failed for $mime", e)
            null
        }
    }

    /** 拉取式帧源（软解 + 0.5s/轮超时 + 30s 卡死保护）。 */
    private inner class FrameSource(path: String) : AutoCloseable {

        private val extractor = MediaExtractor().apply { setDataSource(path) }
        private val decoder: MediaCodec
        val width: Int
        val height: Int

        var lastPtsUs = 0L
            private set

        private var inputDone = false
        private var outputDone = false
        private val info = MediaCodec.BufferInfo()

        init {
            val track = selectTrack()
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            width = fmt.getInteger(MediaFormat.KEY_WIDTH)
            height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val sw = createSoftwareDecoder(mime)
            Log.d(
                TAG,
                "FrameSource: $path ${width}x${height}, " +
                    "decoder=${sw?.name ?: "hardware(fallback)"}"
            )
            decoder = (sw ?: MediaCodec.createDecoderByType(mime)).apply {
                configure(fmt, null, null, 0)
                start()
            }
        }

        private fun selectTrack(): Int {
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return i
            }
            throw IllegalArgumentException("No video track")
        }

        /** 取下一帧亮度平面；流结束返回 null。 */
        fun nextFrame(): ByteArray? {
            var idleRounds = 0
            var frameIndex = 0
            var firstLogged = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIdx, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, 500_000)
                if (outIdx >= 0) {
                    idleRounds = 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var luma: ByteArray? = null
                    if (info.size > 0) {
                        val image = decoder.getOutputImage(outIdx)
                        if (image != null && image.format == ImageFormat.YUV_420_888) {
                            luma = extractY(image)
                            lastPtsUs = info.presentationTimeUs
                            frameIndex++
                            if (!firstLogged) {
                                firstLogged = true
                                Log.d(
                                    TAG,
                                    "first frame decoded ${width}x${height} " +
                                        "pts=${info.presentationTimeUs}µs"
                                )
                            } else if (frameIndex % 100 == 0) {
                                Log.d(TAG, "frame #$frameIndex")
                            }
                        } else {
                            Log.w(
                                TAG,
                                "unusable output image " +
                                    "(image=${image != null}, format=${image?.format})"
                            )
                        }
                        image?.close()
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (eos) outputDone = true
                    if (luma != null) return luma
                } else {
                    if (++idleRounds > 60) {
                        throw IllegalStateException(
                            "Decoder stalled: no output for 30s (decoder=${decoder.name})"
                        )
                    }
                }
            }
            return null
        }

        /** 提取紧凑 Y 平面（适配 rowStride/pixelStride）。 */
        private fun extractY(image: android.media.Image): ByteArray {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val out = ByteArray(width * height)

            // Make sure we are at the start of the buffer
            buf.rewind()

            if (pixelStride == 1) {
                if (rowStride == width) {
                    val length = kotlin.math.min(out.size, buf.remaining())
                    buf.get(out, 0, length)
                } else {
                    for (y in 0 until height) {
                        val pos = y * rowStride
                        if (pos < buf.capacity()) {
                            buf.position(pos)
                            val length = kotlin.math.min(width, buf.remaining())
                            if (length > 0) {
                                buf.get(out, y * width, length)
                            }
                        }
                    }
                }
            } else {
                for (y in 0 until height) {
                    val rowStart = y * rowStride
                    if (rowStart < buf.capacity()) {
                        buf.position(rowStart)
                        for (x in 0 until width) {
                            if (buf.hasRemaining()) {
                                out[y * width + x] = buf.get()
                                if (x < width - 1) {
                                    val nextPos = buf.position() + pixelStride - 1
                                    if (nextPos < buf.capacity()) {
                                        buf.position(nextPos)
                                    } else {
                                        break
                                    }
                                }
                            } else {
                                break
                            }
                        }
                    }
                }
            }
            return out
        }

        override fun close() {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
