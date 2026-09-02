package com.wangyao.qualityevaluation.video

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMuxer.OutputFormat
import android.util.Log
import com.wangyao.qualityevaluation.core.ImageQualityMetrics
import java.io.File
import java.nio.ByteBuffer

/** 模块统一日志 TAG（排查转码/评价流程用：adb logcat -s QE_Pipeline）。 */
private const val TAG = "QE_Pipeline"

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

    /** 探测视频信息（宽高/时长/帧率/帧数）。 */
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
            // 精确帧数：预遍历统计 sample 个数（不解码，开销极小）。
            // 不能用「时长×容器标称帧率」估算——容器帧率可能是流内最大值
            // （如标称 60fps 实际均帧率 10fps），估算会虚高数倍，
            // 导致进度条分母失真、evaluate 的中间帧索引越界。
            val count = countSamples(extractor, track)
            return VideoInfo(w, h, dur, fps, count)
        } finally {
            extractor.release()
        }
    }

    /** 预遍历统计视频轨道 sample 个数（≈ 帧数），完成后 seek 回起点。 */
    private fun countSamples(extractor: MediaExtractor, track: Int): Int {
        extractor.selectTrack(track)
        val buf = java.nio.ByteBuffer.allocateDirect(2 shl 20) // 2MB（容纳最大 I 帧）
        var count = 0
        while (extractor.readSampleData(buf, 0) >= 0) {
            count++
            extractor.advance()
        }
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        return count.coerceAtLeast(1)
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
        Log.d(
            TAG,
            "transcode: start input=$inputPath ${info.width}x${info.height} " +
                "fps=${info.frameRate} frames=${info.frameCount} bitrate=$bitRate"
        )
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
        Log.d(
            TAG,
            "transcode: decoder=${decoder.name}, encoder=${encoder.name}"
        )
        var muxTrack = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var decodeDone = false
        var encodeDone = false
        var ptsBaseUs = -1L
        var processed = 0
        // 是否已向编码器送入 EOS（编码器收到输入 EOS 才会输出 EOS，
        // 主循环的 encodeDone 才可能置 true——漏掉这一步会死循环）
        var encoderEosQueued = false
        var lastPtsUs = 0L

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
                            lastPtsUs = bufferInfo.presentationTimeUs - ptsBaseUs
                            feedEncoder(encoder, image, lastPtsUs)
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

                // ---- 解码已结束：向编码器送 EOS，让其排空并输出 EOS ----
                if (decodeDone && !encoderEosQueued) {
                    val inIdx = encoder.dequeueInputBuffer(60_000)
                    if (inIdx >= 0) {
                        encoder.queueInputBuffer(
                            inIdx, 0, 0, lastPtsUs + 33_000,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        encoderEosQueued = true
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
                try {
                    muxer.stop()
                    Log.d(TAG, "transcode: muxer stopped OK, $processed frames written")
                } catch (e: Exception) {
                    // muxer.stop 失败意味着 MP4 缺少 moov（索引）盒，文件不可
                    // 解复用——绝不能吞掉异常继续进入阶段二拿到坏文件
                    Log.e(TAG, "transcode: muxer.stop() FAILED, file invalid", e)
                    muxer.release()
                    tmp.delete()
                    throw IllegalStateException(
                        "Muxer stop failed (output invalid): ${e.message}"
                    )
                }
            }
            muxer.release()
            extractor.release()
        }

        if (!tmp.renameTo(File(outputPath))) {
            tmp.copyTo(File(outputPath), overwrite = true)
            tmp.delete()
        }
        // 输出文件健康检查：能否被解复用（moov 完整、轨道合法），
        // 提前在阶段一暴露问题，而不是等到阶段二 setDataSource 才失败
        try {
            probe(outputPath)
        } catch (e: Exception) {
            Log.e(TAG, "transcode: output file not readable", e)
            throw IllegalStateException(
                "Transcoded file is not readable: ${e.message}"
            )
        }
        Log.d(
            TAG,
            "transcode: done, $processed frames, output " +
                "${File(outputPath).length() / 1024} KB"
        )
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
        Log.d(
            TAG,
            "evaluate: start original=$originalPath " +
                "processed=$processedPath frames=${info.frameCount}"
        )
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

                Log.d(
                    TAG,
                    "evaluate: done frames=$frames avgPsnr=" +
                        "${if (frames > 0) sumPsnr / frames else 0.0}"
                )
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

    /** 查找指定 MIME 的软件解码器（无并发实例限制）；找不到返回 null。 */
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
            Log.w(TAG, "createSoftwareDecoder: none found for $mime, fallback to hardware")
            null
        } catch (e: Exception) {
            Log.w(TAG, "createSoftwareDecoder: lookup failed for $mime", e)
            null
        }
    }

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
    internal fun downscale2x(luma: ByteArray, w: Int, h: Int): ByteArray {
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
    private inner class FrameSource(path: String) : AutoCloseable {

        private val extractor = MediaExtractor().apply { setDataSource(path) }
        private val decoder: MediaCodec
        val width: Int
        val height: Int

        private var inputDone = false
        private var outputDone = false
        private var frameIndex = 0
        private var firstFrameLogged = false
        private val info = MediaCodec.BufferInfo()

        init {
            val track = selectTrack()
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            width = fmt.getInteger(MediaFormat.KEY_WIDTH)
            height = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            // 优先使用软解码器：双路评价需同时运行两个解码实例，
            // 而多数设备的硬解码器限制并发实例数（阶段一刚释放的
            // 编解码器也可能与之竞争），第二个实例 configure 会抛
            // CodecException。软解无并发限制，输出同样为 YUV_420_888。
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val sw = createSoftwareDecoder(mime)
            Log.d(
                TAG,
                "FrameSource: path=$path ${width}x$height, " +
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
            // 超时保护：注意 dequeueOutputBuffer 的超时单位是【微秒】。
            // 500_000µs = 0.5s，连续 60 轮（30s）无输出才判定解码器异常。
            var idleRounds = 0
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            Log.d(TAG, "nextFrame[${decoder.name}]: input EOS queued")
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

                val outIdx = decoder.dequeueOutputBuffer(info, 500_000) // 0.5 秒
                if (outIdx >= 0) {
                    idleRounds = 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var luma: ByteArray? = null
                    if (info.size > 0) {
                        val image = decoder.getOutputImage(outIdx)
                        if (image != null && image.format == ImageFormat.YUV_420_888) {
                            luma = extractY(image)
                        } else {
                            // 关键诊断点：输出 Image 缺失或格式不符会导致整路
                            // 视频静默丢弃（nextFrame 永远返回 null）
                            Log.w(
                                TAG,
                                "nextFrame[${decoder.name}]: output image " +
                                    "unusable (image=${image != null}" +
                                    ", format=${image?.format})"
                            )
                        }
                        image?.close()
                    }
                    if (luma != null) {
                        frameIndex++
                        if (!firstFrameLogged) {
                            firstFrameLogged = true
                            Log.d(
                                TAG,
                                "nextFrame[${decoder.name}]: first frame decoded " +
                                    "(idx=$frameIndex, ${info.presentationTimeUs}µs)"
                            )
                        } else if (frameIndex % 50 == 0) {
                            Log.d(TAG, "nextFrame[${decoder.name}]: frame #$frameIndex")
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (eos) outputDone = true
                    if (luma != null) return luma
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFmt = decoder.outputFormat
                    Log.d(TAG, "nextFrame[${decoder.name}]: format changed to $newFmt")
                } else {
                    // TRY_AGAIN 等：继续轮询（带超时保护）
                    if (++idleRounds > 60) {
                        throw IllegalStateException(
                            "Decoder stalled: no output for 30s (decoder=${decoder.name})"
                        )
                    }
                }
            }
            return null
        }

        /** 从解码输出 Image 提取紧凑的 Y 平面。 */
        private fun extractY(image: android.media.Image): ByteArray {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val imgW = image.width
            val imgH = image.height

            val out = ByteArray(width * height)
            // 使用 duplicate 避免竞争或影响原始 Image 的 buffer 状态
            val buffer = buf.duplicate()
            buffer.rewind()

            // 鲁棒性改进：针对部分设备 last row 可能不足 rowStride 的情况，逐行拷贝并限制范围。
            // 同时兼容 image 尺寸与初始化 width/height 不一致的异常情况（避免 BufferUnderflow）。
            val copyW = minOf(width, imgW)
            val copyH = minOf(height, imgH)

            for (y in 0 until copyH) {
                val lineStart = y * rowStride
                if (lineStart >= buffer.limit()) break

                if (pixelStride == 1) {
                    buffer.position(lineStart)
                    val readSize = minOf(copyW, buffer.remaining())
                    if (readSize > 0) {
                        buffer.get(out, y * width, readSize)
                    }
                } else {
                    for (x in 0 until copyW) {
                        val pos = lineStart + x * pixelStride
                        if (pos < buffer.limit()) {
                            out[y * width + x] = buffer.get(pos)
                        } else {
                            break
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
