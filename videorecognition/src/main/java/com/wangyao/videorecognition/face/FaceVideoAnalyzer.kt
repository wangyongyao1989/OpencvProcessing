package com.wangyao.videorecognition.face

import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.wangyao.videorecognition.jni.FaceJni
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "VR_Analyzer"

/**
 * 视频人脸分析器（《视频识别及物体人脸识别需求文档》F-05：
 * 视频人脸检测与跟踪）。
 *
 * 流程：
 * 1. 软件解码（无并发实例限制，沿用项目已验证的实践）逐帧取
 *    亮度平面；
 * 2. 降采样到工作分辨率（宽 640）控制检测耗时；
 * 3. Haar 级联人脸检测（JNI/OpenCV，含直方图均衡化）；
 * 4. 时间平滑：相邻帧检测框按 IoU 关联，对同一目标做滑动
 *    平均，抑制逐帧抖动，使播放时的框选平滑跟随人脸；
 * 5. 输出逐帧人脸框 + 全片统计。
 */
class FaceVideoAnalyzer {

    /** 全片分析结果。 */
    class Result(
        val frames: List<FaceFrame>,
        val videoWidth: Int,
        val videoHeight: Int,
        val workWidth: Int,
        val workHeight: Int,
        val framesWithFaces: Int,
        val totalFaceHits: Int,
        val maxFacesInFrame: Int,
        val elapsedSec: Double
    ) {
        /** 出现人脸的帧占比。 */
        val coverage: Double
            get() = if (frames.isNotEmpty())
                framesWithFaces.toDouble() / frames.size else 0.0

        /** 平均每帧人脸数。 */
        val avgFaces: Double
            get() = if (frames.isNotEmpty())
                totalFaceHits.toDouble() / frames.size else 0.0
    }

    /** 工作分辨率宽度（1920→640，人脸约 40~150px，检测耗时可控）。 */
    private val workWidth = 640

    /** 最小人脸边长（工作分辨率）。 */
    private val minFace = 40

    /** 时间平滑窗口（帧）。 */
    private val smoothWindow = 5

    /**
     * 执行全片人脸分析。
     *
     * @param cascadePath 已释放到本地文件的级联模型路径
     * @param onProgress  进度回调 (done, total)
     */
    fun analyze(
        videoPath: String,
        cascadePath: String,
        onProgress: (Int, Int) -> Unit
    ): Result {
        val handle = FaceJni.nativeCreate(cascadePath)
        check(handle != 0L) { "cascade model load failed: $cascadePath" }

        val t0 = System.currentTimeMillis()
        val total = countSamples(videoPath)
        Log.d(TAG, "analyze: $videoPath, $total frames, workWidth=$workWidth")

        val framesOut = ArrayList<FaceFrame>(total)
        var framesWithFaces = 0
        var totalHits = 0
        var maxFaces = 0

        try {
            FrameSource(videoPath).use { src ->
                // 工作尺度（整数倍降采样最稳）
                val scale = (src.width / workWidth.toFloat()).toInt()
                    .coerceAtLeast(1)
                val ww = src.width / scale
                val wh = src.height / scale
                Log.d(
                    TAG, "video ${src.width}x${src.height} -> work ${ww}x$wh (scale=$scale)"
                )

                var frameIdx = -1
                // 平滑用的历史帧（最近 smoothWindow 帧的框）
                val history = ArrayDeque<List<FaceBox>>()

                while (true) {
                    val luma = src.nextFrame() ?: break
                    frameIdx++

                    val small = if (scale > 1)
                        FaceTrackMath.downsample(luma, src.width, src.height, scale)
                    else luma

                    // ---- Haar 检测 ----
                    val flat = FaceJni.nativeDetect(handle, small, ww, wh, minFace)
                    val n = flat[0]
                    val detected = ArrayList<FaceBox>(n)
                    for (i in 0 until n) {
                        val base = 1 + i * 6
                        detected.add(
                            FaceBox(
                                flat[base], flat[base + 1],
                                flat[base + 2], flat[base + 3],
                                flat[base + 4] / 1000.0
                            )
                        )
                    }

                    // ---- 时间平滑（IoU 关联 + 滑动平均）----
                    val smoothed = FaceTrackMath.smooth(detected, history)
                    history.addLast(detected)
                    if (history.size > smoothWindow) history.removeFirst()

                    framesOut.add(
                        FaceFrame(frameIdx, src.lastPtsUs, smoothed)
                    )
                    if (smoothed.isNotEmpty()) framesWithFaces++
                    totalHits += smoothed.size
                    if (smoothed.size > maxFaces) maxFaces = smoothed.size

                    if ((frameIdx + 1) % 50 == 0) {
                        Log.d(TAG, "frame #${frameIdx + 1}/$total faces=$maxFaces")
                    }
                    onProgress(frameIdx + 1, total)
                }

                val elapsed = (System.currentTimeMillis() - t0) / 1000.0
                return Result(
                    frames = framesOut,
                    videoWidth = src.width,
                    videoHeight = src.height,
                    workWidth = ww,
                    workHeight = wh,
                    framesWithFaces = framesWithFaces,
                    totalFaceHits = totalHits,
                    maxFacesInFrame = maxFaces,
                    elapsedSec = elapsed
                ).also {
                    Log.d(
                        TAG, "analyze done: frames=${it.frames.size} " +
                            "coverage=${"%.2f".format(it.coverage)} " +
                            "avg=${"%.2f".format(it.avgFaces)} elapsed=${elapsed}s"
                    )
                }
            }
        } finally {
            FaceJni.nativeDestroy(handle)
        }
    }

    // -------------------------------------------------------------------------
    // 解码
    // -------------------------------------------------------------------------

    /** 预遍历统计视频轨道 sample 数（≈帧数，进度分母）。 */
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

    /** 拉取式帧源（软解 + 0.5s/轮超时 + 30s 卡死保护）。 */
    private class FrameSource(path: String) : AutoCloseable {

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
    }
}
