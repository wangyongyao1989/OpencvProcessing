package com.wangyao.digitalwatermark.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer

/**
 * 视频水印管线（第 8 章 8.4 节：视频水印嵌入和提取方案）。
 *
 * 采用「基于未压缩原始视频」的方案：解码视频 → 在原始视频帧的亮度
 * 分量上嵌入/提取水印（空间域或变换域）→ 重新编码为 H.264/MP4。
 *
 * 管线结构：
 *   MediaExtractor(源 MP4) → MediaCodec 解码器 → Image(YUV_420_888)
 *   → 取 Y 亮度平面 → 水印嵌入/攻击/提取回调 → MediaCodec 编码器
 *   → MediaMuxer(输出 MP4)
 *
 * 水印只作用于亮度平面 Y（与亮度/色度感知特性一致：人眼对亮度更敏感，
 * 教材建议嵌入亮度分量），色度平面原样透传。
 *
 * 说明：输出为纯视频轨（不含音频），保留原始帧时间戳（支持变帧率）；
 * onFrame 返回 false 可丢弃该帧（帧丢失攻击模拟）。
 */
class VideoWatermarkPipeline {

    /** 源视频基本信息。 */
    data class VideoInfo(
        val width: Int,
        val height: Int,
        val durationUs: Long,
        val frameRate: Int,
        val frameCount: Int   // 估计值（用于进度展示）
    )

    companion object {
        private const val TIMEOUT_US = 10_000L

        /** 输出码率：取较高值以减小重编码本身对水印的损伤。 */
        const val OUTPUT_BITRATE = 8_000_000

        /** 关键帧间隔（秒）。 */
        const val I_FRAME_INTERVAL = 1
    }

    /** 探测源视频：宽高 / 时长 / 帧率 / 估计帧数。 */
    fun probe(inputPath: String): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputPath)
            val trackIdx = selectVideoTrack(extractor)
            val fmt = extractor.getTrackFormat(trackIdx)
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
            val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val dur = if (fmt.containsKey(MediaFormat.KEY_DURATION))
                fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE))
                fmt.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
            val count = if (dur > 0)
                ((dur / 1_000_000.0) * fps).toInt().coerceAtLeast(1) else 1
            return VideoInfo(w, h, dur, fps, count)
        } finally {
            extractor.release()
        }
    }

    /**
     * 解码 → 逐帧变换 → 编码（嵌入 / 攻击通道）。
     *
     * @param onFrame 帧回调：可直接修改亮度平面 luma（stride = width）；
     *   frameIndex 从 0 递增；返回 false 表示丢弃该帧（帧丢失攻击）。
     * @param onProgress 进度回调（已处理帧数 / 估计总帧数）。
     * @return 实际处理（含丢弃）的帧数
     */
    fun transcode(
        inputPath: String,
        outputPath: String,
        onFrame: (luma: ByteArray, width: Int, height: Int, frameIndex: Int) -> Boolean,
        onProgress: (processed: Int, total: Int) -> Unit
    ): Int {
        val total = probe(inputPath).frameCount
        var processed = 0

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(inputPath)
            val trackIdx = selectVideoTrack(extractor)
            extractor.selectTrack(trackIdx)
            val srcFormat = extractor.getTrackFormat(trackIdx)
            val mime = srcFormat.getString(MediaFormat.KEY_MIME)
                ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val width = srcFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = srcFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val fps = if (srcFormat.containsKey(MediaFormat.KEY_FRAME_RATE))
                srcFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30

            // ---- 解码器（无 Surface，输出 YUV Image）----
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            // ---- 编码器（H.264，高码率减小重编码对水印的损伤）----
            val encFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_BIT_RATE, OUTPUT_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // ---- 复用器 ----
            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val info = MediaCodec.BufferInfo()
            var inputDone = false        // 解码器输入（源数据）已送完
            var decoderDone = false      // 解码器输出已到 EOS
            var encoderEosQueued = false // 编码器已收到 EOS
            var encoderDone = false      // 编码器输出已到 EOS
            var muxTrack = -1
            var muxStarted = false

            // 帧平面缓冲（首次取到输出后分配）
            var y: ByteArray? = null
            var u: ByteArray? = null
            var v: ByteArray? = null

            while (!encoderDone) {
                // ---- 1. 送入解码器 ----
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // ---- 2. 取出解码帧 → 水印回调 → 送入编码器 ----
                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (outIdx >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0 && !eos) {
                            val image = decoder.getOutputImage(outIdx)
                            if (image != null) {
                                try {
                                    if (y == null) {
                                        y = ByteArray(width * height)
                                        u = ByteArray(width * height / 4)
                                        v = ByteArray(width * height / 4)
                                    }
                                    copyImageToArrays(image, y!!, u!!, v!!, width, height)

                                    // 水印嵌入 / 攻击变换（就地修改亮度平面）
                                    val keep = onFrame(y, width, height, processed)

                                    if (keep) feedEncoder(
                                        encoder!!, y, u, v, width, height,
                                        info.presentationTimeUs
                                    )
                                    processed++
                                    onProgress(processed, total)
                                } finally {
                                    image.close()
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (eos) decoderDone = true
                    }
                }

                // ---- 3. 解码完毕后向编码器发 EOS ----
                if (decoderDone && !encoderEosQueued) {
                    val encIdx = encoder!!.dequeueInputBuffer(TIMEOUT_US)
                    if (encIdx >= 0) {
                        encoder.queueInputBuffer(
                            encIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        encoderEosQueued = true
                    }
                }

                // ---- 4. 排空编码器 → 写入 MP4 ----
                var encOut = encoder!!.dequeueOutputBuffer(info, TIMEOUT_US)
                while (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                    encOut >= 0
                ) {
                    if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        muxTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxStarted = true
                    } else {
                        val buf = encoder.getOutputBuffer(encOut)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            info.size = 0
                        }
                        if (info.size > 0 && muxStarted) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            muxer.writeSampleData(muxTrack, buf, info)
                        }
                        encoder.releaseOutputBuffer(encOut, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                        }
                    }
                    if (encoderDone) break
                    encOut = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                }
            }

            if (muxStarted) muxer.stop()
            return processed
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * 仅解码（提取通道）：逐帧回调亮度平面，不重新编码。
     * 用于从含水印视频中提取水印（式 8-2/8-3/8-4 的检测 D）。
     */
    fun decodeOnly(
        inputPath: String,
        onFrame: (luma: ByteArray, width: Int, height: Int, frameIndex: Int) -> Unit,
        onProgress: (processed: Int, total: Int) -> Unit
    ): Int {
        val total = probe(inputPath).frameCount
        var processed = 0

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(inputPath)
            val trackIdx = selectVideoTrack(extractor)
            extractor.selectTrack(trackIdx)
            val srcFormat = extractor.getTrackFormat(trackIdx)
            val mime = srcFormat.getString(MediaFormat.KEY_MIME)
                ?: MediaFormat.MIMETYPE_VIDEO_AVC
            val width = srcFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = srcFormat.getInteger(MediaFormat.KEY_HEIGHT)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(srcFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var y: ByteArray? = null
            var u: ByteArray? = null
            var v: ByteArray? = null

            while (!decoderDone) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        buf.clear()
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.size > 0 && !eos) {
                        val image = decoder.getOutputImage(outIdx)
                        if (image != null) {
                            try {
                                if (y == null) {
                                    y = ByteArray(width * height)
                                    u = ByteArray(width * height / 4)
                                    v = ByteArray(width * height / 4)
                                }
                                copyImageToArrays(image, y!!, u!!, v!!, width, height)
                                onFrame(y, width, height, processed)
                                processed++
                                onProgress(processed, total)
                            } finally {
                                image.close()
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (eos) decoderDone = true
                }
            }
            return processed
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    // -------------------------------------------------------------------------
    // 内部工具
    // -------------------------------------------------------------------------

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i
        }
        throw IllegalArgumentException("No video track found")
    }

    /** 把编码器输入 Image 当作 YUV_420_888 平面写入紧凑的 Y/U/V 数组。 */
    private fun feedEncoder(
        encoder: MediaCodec,
        y: ByteArray, u: ByteArray, v: ByteArray,
        width: Int, height: Int, ptsUs: Long
    ) {
        val encIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
        require(encIdx >= 0) { "Encoder input buffer timeout" }
        val image = encoder.getInputImage(encIdx)
            ?: throw IllegalStateException("getInputImage unavailable")
        try {
            copyArraysToImage(image, y, u, v, width, height)
        } finally {
            image.close()
        }
        encoder.queueInputBuffer(encIdx, 0, 0, ptsUs, 0)
    }

    /** Image(YUV_420_888) → 紧凑 Y/U/V 数组（处理 rowStride/pixelStride）。 */
    private fun copyImageToArrays(
        image: Image,
        y: ByteArray, u: ByteArray, v: ByteArray,
        width: Int, height: Int
    ) {
        copyPlaneToArray(image.planes[0], y, width, height)
        copyPlaneToArray(image.planes[1], u, width / 2, height / 2)
        copyPlaneToArray(image.planes[2], v, width / 2, height / 2)
    }

    private fun copyPlaneToArray(
        plane: Image.Plane, dst: ByteArray, w: Int, h: Int
    ) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var pos = 0
        if (pixelStride == 1 && rowStride == w) {
            buf.position(0)
            buf.get(dst, 0, w * h)
        } else if (pixelStride == 1) {
            for (row in 0 until h) {
                buf.position(row * rowStride)
                buf.get(dst, pos, w)
                pos += w
            }
        } else {
            for (row in 0 until h) {
                var colIdx = row * rowStride
                for (col in 0 until w) {
                    dst[pos++] = buf.get(colIdx)
                    colIdx += pixelStride
                }
            }
        }
    }

    /** 紧凑 Y/U/V 数组 → 编码器输入 Image(YUV_420_888)（处理各自的 stride）。 */
    private fun copyArraysToImage(
        image: Image,
        y: ByteArray, u: ByteArray, v: ByteArray,
        width: Int, height: Int
    ) {
        copyArrayToPlane(image.planes[0], y, width, height)
        copyArrayToPlane(image.planes[1], u, width / 2, height / 2)
        copyArrayToPlane(image.planes[2], v, width / 2, height / 2)
    }

    private fun copyArrayToPlane(
        plane: Image.Plane, src: ByteArray, w: Int, h: Int
    ) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var pos = 0
        if (pixelStride == 1 && rowStride == w) {
            buf.position(0)
            buf.put(src, 0, w * h)
        } else if (pixelStride == 1) {
            for (row in 0 until h) {
                buf.position(row * rowStride)
                buf.put(src, pos, w)
                pos += w
            }
        } else {
            for (row in 0 until h) {
                var colIdx = row * rowStride
                for (col in 0 until w) {
                    buf.put(colIdx, src[pos++])
                    colIdx += pixelStride
                }
            }
        }
    }
}
