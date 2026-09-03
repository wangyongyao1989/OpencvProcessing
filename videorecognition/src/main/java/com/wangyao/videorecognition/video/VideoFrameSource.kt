package com.wangyao.videorecognition.video

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/** 模块统一日志 TAG（adb logcat -s VR_Pipeline）。 */
private const val TAG = "VR_Pipeline"

/**
 * 共享视频帧源（模块内统一解码入口，需求文档 4.5 视频处理管线）：
 * MediaExtractor + MediaCodec 拉取式软解码，逐帧输出 YUV_420_888
 * 的 Y 亮度平面。
 *
 * 三个使用方（人脸分析 / 关键帧索引）共用同一实现：
 * - 软件解码器（c2.android.*，无并发实例限制——项目已验证实践）；
 * - 微秒级超时（0.5s/轮）+ 30s 卡死保护；
 * - 亮度平面紧凑化（适配 rowStride/pixelStride）。
 */
class VideoFrameSource(path: String) : AutoCloseable {

    private val extractor = MediaExtractor().apply { setDataSource(path) }
    private val decoder: MediaCodec

    /** 视频宽（像素）。 */
    val width: Int

    /** 视频高（像素）。 */
    val height: Int

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
            "VideoFrameSource: $path ${width}x$height, " +
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

    /** 查找软件解码器（无并发实例限制）。 */
    private fun createSoftwareDecoder(mime: String): MediaCodec? {
        return try {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            for (ci in codecs) {
                if (ci.isEncoder) continue
                if (!ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }) {
                    continue
                }
                val name = ci.name.lowercase()
                if (name.startsWith("omx.google.") ||
                    name.startsWith("c2.android.") ||
                    name.startsWith("c2.google.")
                ) {
                    return MediaCodec.createByCodecName(ci.name)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "createSoftwareDecoder: lookup failed for $mime", e)
            null
        }
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
                        "Decoder stalled: no output for 30s " +
                            "(decoder=${decoder.name})"
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
        if (pixelStride == 1) {
            if (rowStride == width) {
                buf.get(out, 0, out.size)
            } else {
                for (y in 0 until height) {
                    buf.position(y * rowStride)
                    buf.get(out, y * width, width)
                }
            }
        } else {
            for (y in 0 until height) {
                buf.position(y * rowStride)
                for (x in 0 until width) {
                    out[y * width + x] = buf.get()
                    buf.position(buf.position() + pixelStride - 1)
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

    companion object {

        /** 预遍历统计视频轨道 sample 数（≈帧数，进度分母）。 */
        fun countSamples(path: String): Int {
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
    }
}
