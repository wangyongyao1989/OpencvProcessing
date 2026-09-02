package com.wangyao.qualityevaluation.video

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMuxer.OutputFormat
import com.wangyao.qualityevaluation.core.ImageQualityMetrics
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频质量客观评价管线（《数字图像与视频处理》第 6/7/9 章）。
 *
 * 完整流程（率失真评价，R-D）：
 * 1. [transcode]：把源视频按目标码率重新编码为 H.264/MP4
 *    （第 6 章 H.264 编码标准 + 第 7 章 MP4 文件格式封装），
 *    码率越低压缩比越高、失真越大；
 * 2. [evaluate]：双路解码「原始视频 + 压缩视频」，逐帧在亮度平面
 *    上计算 PSNR（全分辨率）与 SSIM（2× 下采样加速），统计
 *    平均/最差帧指标（第 9 章视频质量客观评价：逐帧全参考
 *    度量 + 时间轴聚合）。
 */
class VideoQualityPipeline {

    /** 视频基础信息。 */
    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val frameRate: Int,
        val frameCount: Int
    )

    /** 逐帧评价聚合结果。 */
    data class EvalResult(
        val frames: Int,
        val avgPsnr: Double,
        val minPsnr: Double,
        val minPsnrFrame: Int,
        val maxPsnr: Double,
        val avgSsim: Double,
        val minSsim: Double,
        /** 中间帧（原始/压缩）亮度，供 UI 对比展示。 */
        val midOriginalLuma: ByteArray?,
        val midProcessedLuma: ByteArray?,
        val width: Int,
        val height: Int
    )

    /** 探测视频信息（宽高/时长/帧率/帧数估计）。 */
    fun probe(path: String): VideoInfo {
        val extractor = MediaExtractor().apply { setDataSource(path) }
        try {
            val track = selectVideoTrack(extractor)
            val fmt = extractor.getTrackFormat(track)
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION))
                fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE))
                fmt.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
            // 帧数估计：时长 × 帧率（MediaFormat 无 KEY_FRAME_COUNT 常量）
            val count = (dur / 1_000_000.0 * fps).toInt().coerceAtLeast(1)
            return VideoInfo(w, h, dur, fps, count)
        } finally {
            extractor.release()
        }
    }

    /**
     * 按目标码率转码（H.264 编码 + MP4 封装）。
     * 返回处理帧数。输出 PTS 归一化到 0 起点，保证播放器兼容。
     */
    fun transcode(
        inputPath: String,
        outputPath: String,
        bitRate: Int,
        onProgress: (done: Int, total: Int) -> Unit
    ): Int {
        val info = probe(inputPath)
        File(outputPath).delete()
        val tmp = File(outputPath + ".tmp")
        if (tmp.exists()) tmp.delete()

        val extractor = MediaExtractor().apply { setDataSource(inputPath) }
        val trackIdx = selectVideoTrack(extractor)
        extractor.selectTrack(trackIdx)
        val srcFormat = extractor.getTrackFormat(trackIdx)

        val decoder = MediaCodec.createDecoderByType(
            srcFormat.getString(MediaFormat.KEY_MIME)!!
        ).apply {
            configure(srcFormat, null, null, 0)
            start()
        }

        val encFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, info.width, info.height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, info.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        val muxer = MediaMuxer(tmp.absolutePath, OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decodeDone = false
        var encodeDone = false
        var ptsBaseUs = -1L
        var processed = 0

        try {
            while (!encodeDone) {
                // ---- 解码输入 ----
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

                // ---- 解码输出 → 编码输入 ----
                if (!decodeDone) {
                    val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    if (outIdx >= 0) {
                        val eos = bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (bufferInfo.size > 0 && !eos) {
                            val image = decoder.getOutputImage(outIdx)!!
                            if (ptsBaseUs < 0) ptsBaseUs = bufferInfo.presentationTimeUs
                            feedEncoder(encoder, image, bufferInfo.presentationTimeUs - ptsBaseUs)
                            image.close()
                            processed++
                            onProgress(processed, info.frameCount)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (eos) decodeDone = true
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        // 继续轮询
                    }
                }

                // ---- 编码输出 → Muxer ----
                var encoderOutputAvailable = true
                while (encoderOutputAvailable) {
                    val encIdx = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        encIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrack = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        encIdx >= 0 -> {
                            val encBuf = encoder.getOutputBuffer(encIdx)!!
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted) {
                                encBuf.position(bufferInfo.offset)
                                encBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(muxTrack, encBuf, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(encIdx, false)
                            if (bufferInfo.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encodeDone = true
                            }
                        }
                        else -> encoderOutputAvailable = false
                    }
                }
            }
        } finally {
            try { decoder.stop() } catch (_: Exception) {}
            try { decoder.release() } catch (_: Exception) {}
            try { encoder.stop() } catch (_: Exception) {}
            try { encoder.release() } catch (_: Exception) {}
            if (muxerStarted) {
                try { muxer.stop() } catch (_: Exception) {}
            }
            muxer.release()
            extractor.release()
        }

        if (!tmp.renameTo(File(outputPath))) {
            tmp.copyTo(File(outputPath), overwrite = true)
            tmp.delete()
        }
        return processed
    }

    /**
     * 双路解码逐帧评价：原始 vs 处理后视频。
     * PSNR 按全分辨率亮度计算；SSIM 在 2× 下采样图上计算（教学演示加速，
     * 结果与全分辨率 SSIM 偏差可忽略）。
     */
    fun evaluate(
        originalPath: String,
        processedPath: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): EvalResult {
        val info = probe(originalPath)
        val midTarget = info.frameCount / 2

        FrameSource(originalPath).use { src ->
            FrameSource(processedPath).use { dst ->
                var frames = 0
                var sumPsnr = 0.0; var sumSsim = 0.0
                var minPsnr = Double.MAX_VALUE; var minPsnrFrame = -1
                var maxPsnr = -Double.MAX_VALUE
                var minSsim = Double.MAX_VALUE
                var midOriginal: ByteArray? = null
                var midProcessed: ByteArray? = null

                while (true) {
                    val a = src.nextFrame() ?: break
                    val b = dst.nextFrame() ?: break

                    // 全分辨率 PSNR
                    val psnr = ImageQualityMetrics.psnr(a, b)
                    // 2× 下采样 SSIM（加速）
                    val da = downscale2x(a, info.width, info.height)
                    val db = downscale2x(b, info.width, info.height)
                    val ssim = ImageQualityMetrics.ssim(
                        da, db, info.width / 2, info.height / 2
                    )

                    if (psnr < minPsnr) { minPsnr = psnr; minPsnrFrame = frames }
                    if (psnr > maxPsnr) maxPsnr = psnr
                    if (ssim < minSsim) minSsim = ssim
                    sumPsnr += psnr; sumSsim += ssim
                    frames++

                    if (frames - 1 == midTarget) {
                        midOriginal = a; midProcessed = b
                    }

                    if (frames % 5 == 0 || frames == info.frameCount) {
                        onProgress(frames, info.frameCount)
                    }
                }

                return EvalResult(
                    frames = frames,
                    avgPsnr = if (frames > 0) sumPsnr / frames else 0.0,
                    minPsnr = if (frames > 0) minPsnr else 0.0,
                    minPsnrFrame = minPsnrFrame,
                    maxPsnr = if (frames > 0) maxPsnr else 0.0,
                    avgSsim = if (frames > 0) sumSsim / frames else 0.0,
                    minSsim = if (frames > 0) minSsim else 0.0,
                    midOriginalLuma = midOriginal,
                    midProcessedLuma = midProcessed,
                    width = info.width,
                    height = info.height
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------------------------

    /** 选择视频轨道，返回轨道下标。 */
    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        throw IllegalArgumentException("No video track found")
    }

    /** 把解码输出的 YUV Image 写入编码器输入 Image（逐平面 + 步长适配）。 */
    private fun feedEncoder(
        encoder: MediaCodec,
        image: android.media.Image,
        ptsUs: Long
    ) {
        val inIdx = encoder.dequeueInputBuffer(60_000)
        if (inIdx < 0) throw IllegalStateException("Encoder input buffer timeout")
        val input = encoder.getInputImage(inIdx)!!

        val srcPlanes = image.planes
        val dstPlanes = input.planes
        // YUV420：plane0=Y，plane1/2=U/V（尺寸减半）
        val sizes = arrayOf(
            intArrayOf(image.width, image.height),
            intArrayOf(image.width / 2, image.height / 2),
            intArrayOf(image.width / 2, image.height / 2)
        )
        for (p in 0 until 3) {
            copyPlane(
                srcPlanes[p].buffer, srcPlanes[p].rowStride, srcPlanes[p].pixelStride,
                dstPlanes[p].buffer, dstPlanes[p].rowStride, dstPlanes[p].pixelStride,
                sizes[p][0], sizes[p][1]
            )
        }
        encoder.queueInputBuffer(inIdx, 0, input.width * input.height * 3 / 2, ptsUs, 0)
    }

    /** 平面拷贝（适配 rowStride/pixelStride）。 */
    private fun copyPlane(
        src: ByteBuffer, srcRow: Int, srcPix: Int,
        dst: ByteBuffer, dstRow: Int, dstPix: Int,
        w: Int, h: Int
    ) {
        if (srcPix == 1 && dstPix == 1) {
            val tmp = ByteArray(w)
            for (row in 0 until h) {
                src.position(row * srcRow)
                src.get(tmp, 0, w)
                dst.position(row * dstRow)
                dst.put(tmp, 0, w)
            }
        } else {
            for (row in 0 until h) {
                for (x in 0 until w) {
                    src.position(row * srcRow + x * srcPix)
                    dst.position(row * dstRow + x * dstPix)
                    dst.put(src.get())
                }
            }
        }
    }

    /** 2× 下采样（2×2 邻域平均），返回 w/2 × h/2 的亮度数组。 */
    private fun downscale2x(luma: ByteArray, w: Int, h: Int): ByteArray {
        val nw = w / 2
        val nh = h / 2
        val out = ByteArray(nw * nh)
        for (y in 0 until nh) {
            val r0 = (y * 2) * w
            val r1 = (y * 2 + 1) * w
            for (x in 0 until nw) {
                val s = (luma[r0 + x * 2].toInt() and 0xFF) +
                        (luma[r0 + x * 2 + 1].toInt() and 0xFF) +
                        (luma[r1 + x * 2].toInt() and 0xFF) +
                        (luma[r1 + x * 2 + 1].toInt() and 0xFF)
                out[y * nw + x] = (s / 4).toByte()
            }
        }
        return out
    }

    /**
     * 拉取式帧源：封装「MediaExtractor + MediaCodec 解码器」，
     * 每次调用 [nextFrame] 返回一帧的亮度平面（Y），流结束返回 null。
     */
    private class FrameSource(path: String) : AutoCloseable {

        private val extractor = MediaExtractor().apply { setDataSource(path) }
        private val decoder: MediaCodec
        val width: Int
        val height: Int

        private var inputDone = false
        private var outputDone = false
        private val info = MediaCodec.BufferInfo()

        init {
            val track = selectTrack()
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            width = fmt.getInteger(MediaFormat.KEY_WIDTH)
            height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            decoder = MediaCodec.createDecoderByType(
                fmt.getString(MediaFormat.KEY_MIME)!!
            ).apply {
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

                val outIdx = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var luma: ByteArray? = null
                    if (info.size > 0) {
                        val image = decoder.getOutputImage(outIdx)
                        if (image != null && image.format == ImageFormat.YUV_420_888) {
                            luma = extractY(image)
                        }
                        image?.close()
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (eos) outputDone = true
                    if (luma != null) return luma
                }
                // TRY_AGAIN / FORMAT_CHANGED：继续循环
            }
            return null
        }

        /** 从解码输出 Image 提取紧凑的 Y 平面。 */
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

    /** 亮度平面 → 灰度 Bitmap（UI 展示用，[maxWidth] 为目标宽度）。 */
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
