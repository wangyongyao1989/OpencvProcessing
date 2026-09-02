package com.wangyao.qualityevaluation.video

import com.wangyao.qualityevaluation.core.ImageDistortions
import com.wangyao.qualityevaluation.core.ImageQualityMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VideoQualityPipeline] 纯计算部分单元测试（JVM）。
 *
 * MediaCodec/MediaExtractor 相关路径（probe/transcode/evaluate）
 * 依赖设备编解码器，需在 androidTest（连接设备）上验证；
 * 此处覆盖逐帧评价所依赖的纯函数：2× 下采样、亮度→Bitmap 降采样比例。
 */
class VideoQualityPipelineTest {

    // -------------------------------------------------------------------------
    // downscale2x（SSIM 加速路径）
    // -------------------------------------------------------------------------

    @Test
    fun downscale2x_outputSizeIsHalved() {
        val w = 64; val h = 32
        val img = ByteArray(w * h) { 128.toByte() }
        val out = VideoQualityPipeline().downscale2x(img, w, h)
        assertEquals(w / 2 * h / 2, out.size)
    }

    @Test
    fun downscale2x_averages2x2Neighborhood() {
        // 4×4 图：每个 2×2 邻域分别填 0/1/2/3 的均值 → 期望 2×2 输出
        val w = 4; val h = 4
        val img = byteArrayOf(
            10, 20, 200.toByte(), 200.toByte(),
            30, 40, 200.toByte(), 200.toByte(),
            0, 0, 100, 100,
            0, 0, 100, 100
        )
        val out = VideoQualityPipeline().downscale2x(img, w, h)
        assertEquals(2 * 2, out.size)
        assertEquals(25, out[0].toInt() and 0xFF)   // (10+20+30+40)/4
        assertEquals(200, out[1].toInt() and 0xFF)  // 全 200
        assertEquals(0, out[2].toInt() and 0xFF)    // 全 0
        assertEquals(100, out[3].toInt() and 0xFF)  // 全 100
    }

    @Test
    fun downscale2x_constantImage_staysConstant() {
        val img = ByteArray(32 * 32) { 77.toByte() }
        val out = VideoQualityPipeline().downscale2x(img, 32, 32)
        for (v in out) assertEquals(77, v.toInt() and 0xFF)
    }

    @Test
    fun downscale2x_preservesQualityOrdering() {
        // 下采样不应颠倒质量序：强失真图的下采样 PSNR 仍低于弱失真图
        val w = 64; val h = 64
        val base = ByteArray(w * h) { 128.toByte() }
        val weak = base.copyOf().also { ImageDistortions.brightnessShift(it, 5) }
        val strong = base.copyOf().also { ImageDistortions.brightnessShift(it, 50) }
        val ds = VideoQualityPipeline()

        val pWeak = ImageQualityMetrics.psnr(
            ds.downscale2x(base, w, h), ds.downscale2x(weak, w, h)
        )
        val pStrong = ImageQualityMetrics.psnr(
            ds.downscale2x(base, w, h), ds.downscale2x(strong, w, h)
        )
        assertTrue("ordering broken ($pWeak vs $pStrong)", pWeak > pStrong)
    }

    // -------------------------------------------------------------------------
    // 数据类契约
    // -------------------------------------------------------------------------

    @Test
    fun videoInfo_holdsFields() {
        val info = VideoQualityPipeline.VideoInfo(1920, 1080, 45_840_000L, 60, 459)
        assertEquals(1920, info.width)
        assertEquals(1080, info.height)
        assertEquals(60, info.frameRate)
    }

    @Test
    fun evalResult_defaultsAssignable() {
        val r = VideoQualityPipeline.EvalResult(
            frames = 10, avgPsnr = 35.0, minPsnr = 30.0, minPsnrFrame = 3,
            maxPsnr = 40.0, avgSsim = 0.95, minSsim = 0.9,
            midOriginalLuma = null, midProcessedLuma = null,
            width = 1920, height = 1080
        )
        assertEquals(35.0, r.avgPsnr, 1e-12)
        assertEquals(3, r.minPsnrFrame)
    }
}

/**
 * 「失真生成 → 客观评价」全链路集成测试（JVM）：
 * 模拟视频评价中单帧的处理路径（除 MediaCodec 编解码外的全部计算环节）。
 */
class FrameEvaluationIntegrationTest {

    @Test
    fun brightnessShift_30dBGradeBoundary() {
        // Δ=30 → MSE=900 → PSNR≈18.6dB（与文档说明一致）
        val frame = ByteArray(64 * 64) { 128.toByte() }
        val distorted = frame.copyOf()
        ImageDistortions.brightnessShift(distorted, 30)

        val mse = ImageQualityMetrics.mse(frame, distorted)
        assertEquals(900.0, mse, 1e-9)
        assertTrue(
            ImageQualityMetrics.psnr(frame, distorted) < 20.0
        )
    }

    @Test
    fun blurDestroysRandomStructure_ssimDrops() {
        // 固定种子随机图（像素间独立）模糊后局部结构被破坏 → SSIM 显著下降
        val w = 64; val h = 64
        val rnd = kotlin.random.Random(7)
        val frame = ByteArray(w * h) { rnd.nextInt(256).toByte() }
        val blurred = frame.copyOf()
        ImageDistortions.meanBlur(blurred, w, h)

        val r = ImageQualityMetrics.evaluateAll(frame, blurred, w, h)
        assertTrue("SSIM should drop well below 0.9 (got ${r.ssim})", r.ssim < 0.9)
        assertTrue("PSNR should stay finite/positive (got ${r.psnr})", r.psnr > 0.0)
    }

    @Test
    fun jpegLikeHighFrequencyLoss_reducesEntropy() {
        // 模拟压缩细节丢失：随机高频图被均值滤波抹平 → 灰度直方图集中 → 熵下降
        val w = 64; val h = 64
        val rnd = kotlin.random.Random(7)
        val detailed = ByteArray(w * h) { rnd.nextInt(256).toByte() }
        val flattened = detailed.copyOf()
        ImageDistortions.meanBlur(flattened, w, h)

        assertTrue(
            "entropy should drop by a clear margin " +
                "(${ImageQualityMetrics.entropy(detailed)} -> ${ImageQualityMetrics.entropy(flattened)})",
            ImageQualityMetrics.entropy(flattened) <
                    ImageQualityMetrics.entropy(detailed) - 1.0
        )
    }
}
