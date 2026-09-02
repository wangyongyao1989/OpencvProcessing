package com.wangyao.contentsearch.video

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.wangyao.contentsearch.core.ImageFeatures
import java.nio.ByteBuffer

/** 模块统一日志 TAG（adb logcat -s CS_Pipeline）。 */
private const val TAG = "CS_Pipeline"

/**
 * 基于内容的视频检索引擎（第 10 章 10.5 节：视频检索 = 关键帧抽取
 * + 图像检索技术的时序扩展）。
 *
 * 流程：
 * 1. [extractKeyframes]：解码视频，按帧序均匀抽取 N 个关键帧
 *    （关键帧是视频内容的代表，大幅降低索引规模）；
 * 2. 每个关键帧提取「颜色 + 纹理」特征并记录时间戳（索引）；
 * 3. [search]：以查询图像（如某一关键帧的失真副本）检索最相似的
 *    关键帧，返回命中时间点——即「在视频中定位相似内容」。
 *
 * 解码使用软件解码器（c2.android.*）——与质量评价模块相同的经验：
 * 软解无并发实例限制，避免设备硬解实例数约束导致的 CodecException。
 */
class VideoSearchEngine {

    /** 视频关键帧（含时间戳与特征）。 */
    class Keyframe(
        val frameIndex: Int,
        /** 该帧在视频中的时间戳（µs）。 */
        val timestampUs: Long,
        /** 亮度平面（供 UI 展示）。 */
        val luma: ByteArray,
        val width: Int,
        val height: Int,
        /** 综合特征。 */
        var feature: ImageFeatures.Feature? = null
    )

    /** 关键帧命中结果。 */
    data class Match(
        val keyframe: Keyframe,
        val score: Double,
        val colorScore: Double,
        val textureScore: Double
    )

    /** 视频信息。 */
    data class VideoInfo(val width: Int, val height: Int, val frameCount: Int, val durationUs: Long)

    /**
     * 抽取 [count] 个均匀分布的关键帧并建立特征索引。
     * 例如 459 帧抽 12 帧 → 每隔 ~38 帧取一帧。
     */
    fun extractKeyframes(path: String, count: Int): List<Keyframe> {
        require(count > 0) { "keyframe count must be > 0" }
        val info = probe(path)
        Log.d(
            TAG,
            "extractKeyframes: $path ${info.width}x${info.height} " +
                "frames=${info.frameCount} -> $count keyframes"
        )
        // 均匀采样目标帧号（避开第 0 帧，从 1/2 间隔开始）
        val targets = IntArray(count) { i ->
            ((i + 0.5) * info.frameCount / count).toInt().coerceIn(0, info.frameCount - 1)
        }.toSortedSet().toIntArray()

        val keyframes = mutableListOf<Keyframe>()
        FrameSource(path).use { src ->
            var frameIdx = -1
            var tPos = 0
            while (tPos < targets.size) {
                val luma = src.nextFrame() ?: break
                frameIdx++
                if (frameIdx == targets[tPos]) {
                    val kf = Keyframe(
                        frameIndex = frameIdx,
                        timestampUs = src.lastPtsUs,
                        luma = luma,
                        width = src.width,
                        height = src.height
                    )
                    index(kf)
                    keyframes.add(kf)
                    tPos++
                }
            }
        }
        Log.d(TAG, "extractKeyframes: got ${keyframes.size} keyframes indexed")
        return keyframes
    }

    /** 关键帧提取特征（索引）。 */
    private fun index(kf: Keyframe) {
        val color = ImageFeatures.colorHistogramFromLuma(kf.luma)
        val texture = ImageFeatures.textureHistogram(kf.luma, kf.width, kf.height)
        kf.feature = ImageFeatures.Feature(color, texture)
    }

    /**
     * 以亮度数组为查询，检索最相似的 [k] 个关键帧。
     * （视频检索应用场景：给定一帧画面/其失真副本，定位视频中的
     * 对应时间点。）
     */
    fun search(queryLuma: ByteArray, width: Int, height: Int, k: Int): List<Match> {
        val color = ImageFeatures.colorHistogramFromLuma(queryLuma)
        val texture = ImageFeatures.textureHistogram(queryLuma, width, height)
        val qf = ImageFeatures.Feature(color, texture)
        return searchByFeature(qf, k)
    }

    /** 以已提取特征检索（供「选择关键帧再检索」免重复提取）。 */
    fun searchByFeature(qf: ImageFeatures.Feature, k: Int): List<Match> =
        keyframes
            .mapNotNull { kf ->
                kf.feature?.let { f ->
                    Match(
                        keyframe = kf,
                        score = ImageFeatures.similarity(qf, f),
                        colorScore = ImageFeatures.histogramIntersection(qf.color, f.color),
                        textureScore = ImageFeatures.cosine(qf.texture, f.texture)
                    )
                }
            }
            .sortedByDescending { it.score }
            .take(k)

    /** 当前已索引的关键帧。 */
    val keyframes = mutableListOf<Keyframe>()

    // -------------------------------------------------------------------------
    // 视频探测与解码
    // -------------------------------------------------------------------------

    /** 探测视频信息（帧数按 sample 计数精确统计）。 */
    fun probe(path: String): VideoInfo {
        val extractor = MediaExtractor().apply { setDataSource(path) }
        try {
            val track = selectVideoTrack(extractor)
            val fmt = extractor.getTrackFormat(track)
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION))
                fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            // 精确帧数：预遍历统计 sample 个数（不解码）
            extractor.selectTrack(track)
            val buf = ByteBuffer.allocateDirect(2 shl 20)
            var count = 0
            while (extractor.readSampleData(buf, 0) >= 0) {
                count++
                extractor.advance()
            }
            return VideoInfo(w, h, count.coerceAtLeast(1), dur)
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

    /**
     * 拉取式帧源（软解 + 正确的微秒级超时，参考质量评价模块的修复经验）。
     */
    private inner class FrameSource(path: String) : AutoCloseable {

        private val extractor = MediaExtractor().apply { setDataSource(path) }
        private val decoder: MediaCodec
        var width: Int
        var height: Int

        /** 最近一次输出帧的 PTS（µs）。 */
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
            // 超时保护：500_000µs = 0.5s/轮，连续 60 轮（30s）无输出判定异常
            var idleRounds = 0
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
                        } else {
                            Log.w(
                                TAG,
                                "nextFrame: unusable output image " +
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
            val w = image.width
            val h = image.height
            if (w != width) width = w
            if (h != height) height = h

            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val out = ByteArray(w * h)

            val initialPos = buf.position()
            if (pixelStride == 1 && rowStride == w && buf.remaining() >= out.size) {
                buf.get(out)
            } else {
                for (y in 0 until h) {
                    buf.position(initialPos + y * rowStride)
                    if (pixelStride == 1) {
                        buf.get(out, y * w, w)
                    } else {
                        for (x in 0 until w) {
                            out[y * w + x] = buf.get()
                            if (x < w - 1) {
                                buf.position(buf.position() + pixelStride - 1)
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

    /** 亮度平面 → 灰度 Bitmap（UI 展示）。 */
    companion object {
        fun lumaToBitmap(luma: ByteArray, width: Int, height: Int, maxWidth: Int = 480): Bitmap {
            val scale = maxOf(1, (width + maxWidth - 1) / maxWidth)
            val bw = width / scale
            val bh = height / scale
            val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val px = IntArray(bw * bh)
            for (y in 0 until bh) {
                for (x in 0 until bw) {
                    val v = luma[(y * scale) * width + (x * scale)].toInt() and 0xFF
                    px[y * bw + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            bmp.setPixels(px, 0, bw, 0, 0, bw, bh)
            return bmp
        }
    }
}
