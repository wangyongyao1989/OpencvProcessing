package com.wangyao.camerarecognition.camera

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 相机预览助手（CameraX 实现，替代原 legacy Camera API 版本）。
 *
 * 对外接口契约与旧版保持一致（人脸检测跟踪 / 框选实物跟踪两处
 * 调用方无需感知迁移），坐标系语义与迁移前完全一致：
 *
 * 1. 帧来源：ImageAnalysis（YUV_420_888）+ ResolutionSelector 固定
 *    640×480（4:3）目标分辨率，统一转换为 NV21 经 [previewCallback]
 *    送检（native 侧仍按 NV21 解码，检测/跟踪/渲染管线不变）；
 * 2. 帧方向：分析帧与旧 API 预览帧同为「传感器原始方向」（不旋转、
 *    不镜像），[sensorOrientation]/[facing] 供上层按原公式计算校正
 *    角与镜像标志——「框选的/视频中的/自动跟踪的」三者坐标系不变；
 * 3. 实际尺寸：个别设备可能给出邻近分辨率，[frameWidth]/
 *    [frameHeight] 为最近一帧实际值，上层送检应以实际值为准；
 * 4. 异步绑定：CameraX 绑定在主线程异步完成，[onCameraStarted]
 *    回调时 sensorOrientation/facing 已就绪（替代旧版同步语义）；
 * 5. 生命周期：绑定到调用方传入的 [LifecycleOwner]，
 *    stopPreview/switchCamera 显式解绑，进程级 ProcessCameraProvider 复用。
 */
class CameraHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "CameraHelper"

        /** 目标分析分辨率（与旧版一致：640×480，检测实时性最好）。 */
        const val WIDTH = 640
        const val HEIGHT = 480

        /** 朝向常量（沿用旧 legacy API 数值：0=后置 1=前置，调用方比较逻辑不变）。 */
        const val FACING_BACK = 0
        const val FACING_FRONT = 1
    }

    /** 预览帧回调（运行在分析线程，上层直接送 JNI 检测）。 */
    @Volatile var previewCallback: ((ByteArray) -> Unit)? = null

    /** CameraX 绑定完成回调（主线程，sensorOrientation/facing 已更新）。 */
    @Volatile var onCameraStarted: (() -> Unit)? = null

    /** 传感器方向（与旧 API Camera.CameraInfo.orientation 同源，用于计算校正角）。 */
    @Volatile var sensorOrientation: Int = 90
        private set

    /** 当前摄像头朝向（取值为 [FACING_BACK]/[FACING_FRONT] 常量）。 */
    @Volatile var facing: Int = FACING_BACK
        private set

    /** 最近一帧实际宽（分析线程更新，送检前读取）。 */
    @Volatile var frameWidth: Int = WIDTH
        private set

    /** 最近一帧实际高（分析线程更新，送检前读取）。 */
    @Volatile var frameHeight: Int = HEIGHT
        private set

    /** NV21 复用缓冲区（分析线程独占，避免每帧分配引起 GC 抖动）。 */
    private var nv21 = ByteArray(0)

    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null

    /** 启动代号：递增使过期的异步绑定回调失效（快速停止/切换场景）。 */
    private val startGeneration = AtomicInteger(0)

    /** 是否已记录本相机的平面参数日志（排查 stride 排布用）。 */
    @Volatile private var loggedPlaneInfo = false

    /** 切换前后摄像头（先同步解绑旧相机出帧，再异步绑定新相机）。 */
    fun switchCamera() {
        facing = if (facing == FACING_BACK) FACING_FRONT else FACING_BACK
        Log.i(TAG, "switchCamera -> facing=$facing")
        stopPreview()
        startPreview()
    }

    /** 打开摄像头并启动 YUV→NV21 分析流（绑定结果见 [onCameraStarted]）。 */
    fun startPreview() {
        val generation = startGeneration.incrementAndGet()
        val appContext = context.applicationContext
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener({
            // 过期的启动请求（已被停止/切换取代）：直接放弃绑定
            if (generation != startGeneration.get()) {
                Log.i(TAG, "startPreview skipped: stale generation=$generation")
                return@addListener
            }
            try {
                val provider = future.get()
                provider.unbindAll()
                shutdownAnalyzer()

                // 目标分辨率 640×480：优先精确匹配，其次邻近更低档（保证实时性）
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(WIDTH, HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val executor = Executors.newSingleThreadExecutor()
                cameraProvider = provider
                analysisExecutor = executor
                loggedPlaneInfo = false
                analysis.setAnalyzer(executor) { image ->
                    try {
                        frameWidth = image.width
                        frameHeight = image.height
                        if (!loggedPlaneInfo) {
                            loggedPlaneInfo = true
                            logPlaneInfo(image)
                        }
                        previewCallback?.invoke(yuv420ToNv21(image))
                    } catch (t: Throwable) {
                        Log.e(TAG, "analyze frame failed", t)
                    } finally {
                        image.close()
                    }
                }

                val selector = CameraSelector.Builder()
                    .requireLensFacing(
                        if (facing == FACING_FRONT) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    )
                    .build()
                val camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)

                // 传感器方向/朝向（与旧 API 同源，供上层计算校正角与镜像）
                sensorOrientation = camera.cameraInfo.sensorRotationDegrees
                facing = if (camera.cameraInfo.lensFacing ==
                    CameraSelector.LENS_FACING_FRONT
                ) FACING_FRONT else FACING_BACK
                Log.i(TAG, "startPreview ok: facing=$facing " +
                        "sensorOrientation=$sensorOrientation")
                onCameraStarted?.invoke()
            } catch (ex: Exception) {
                Log.e(TAG, "startPreview failed", ex)
                shutdownAnalyzer()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    /** 停止分析流并解绑摄像头（可从任意已启动状态重复调用）。 */
    fun stopPreview() {
        startGeneration.incrementAndGet()
        try {
            cameraProvider?.unbindAll()
        } catch (t: Throwable) {
            Log.e(TAG, "unbind failed", t)
        }
        cameraProvider = null
        shutdownAnalyzer()
        Log.i(TAG, "stopPreview: camera unbound, analyzer stopped")
    }

    /** 关闭分析线程（等待在执行的分析任务自然结束）。 */
    private fun shutdownAnalyzer() {
        analysisExecutor?.let {
            try {
                it.shutdown()
            } catch (_: Exception) {
            }
        }
        analysisExecutor = null
    }

    /** 记录一帧的平面参数（Y/U/V 行距与像素距，排查排布转换用）。 */
    private fun logPlaneInfo(image: ImageProxy) {
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        Log.i(TAG, "frame ${image.width}x${image.height}: " +
                "Y(rowStride=${y.rowStride},pix=${y.pixelStride}) " +
                "U(rowStride=${u.rowStride},pix=${u.pixelStride}) " +
                "V(rowStride=${v.rowStride},pix=${v.pixelStride})")
    }

    /**
     * YUV_420_888 → NV21（Y 平面 + VU 交错平面）：
     * 兼容 planar（U/V 各自连续）与 semi-planar（U/V 已交错）两种
     * 排布，按 rowStride/pixelStride 逐像素取数，行距补齐安全；
     * 输出写入复用缓冲区（分析线程独占，上层同步消费）。
     */
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val size = ySize + ySize / 2
        if (nv21.size != size) nv21 = ByteArray(size)

        // Y 平面（pixelStride 恒为 1，行距可能按硬件对齐补齐）
        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer.duplicate()
        var pos = 0
        if (yPlane.rowStride == width) {
            yBuf.get(nv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yBuf.position(row * yPlane.rowStride)
                yBuf.get(nv21, pos, width)
                pos += width
            }
        }

        // U/V 平面 → NV21 的 VU 交错（绝对索引读取，跳过行距填充）
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuf = uPlane.buffer.duplicate()
        val vBuf = vPlane.buffer.duplicate()
        val halfW = width / 2
        for (row in 0 until height / 2) {
            var uIdx = row * uPlane.rowStride
            var vIdx = row * vPlane.rowStride
            for (col in 0 until halfW) {
                nv21[pos++] = vBuf.get(vIdx) // V 在前（NV21）
                nv21[pos++] = uBuf.get(uIdx) // U 在后
                uIdx += uPlane.pixelStride
                vIdx += vPlane.pixelStride
            }
        }
        return nv21
    }
}
