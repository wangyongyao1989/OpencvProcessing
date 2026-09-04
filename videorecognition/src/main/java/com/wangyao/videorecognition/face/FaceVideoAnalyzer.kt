package com.wangyao.videorecognition.face

import android.util.Log
import com.wangyao.videorecognition.jni.FaceJni
import com.wangyao.videorecognition.video.VideoFrameSource

private const val TAG = "VR_Analyzer"

/**
 * 视频人脸分析器（《视频识别及物体人脸识别需求文档》F-05：
 * 视频人脸检测与跟踪）。
 *
 * 流程：
 * 1. 软件解码（[VideoFrameSource]，无并发实例限制）逐帧取
 *    亮度平面；
 * 2. 降采样到工作分辨率（宽 640）控制检测耗时；
 * 3. Haar 级联人脸检测（JNI/OpenCV，含直方图均衡化）；
 * 4. 时间平滑：相邻帧检测框按 IoU 关联，对同一目标做滑动
 *    平均，抑制逐帧抖动，使播放时的框选平滑跟随人脸；
 * 5. 输出逐帧人脸框 + 全片统计（含跟踪命中率）。
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

        /** 跟踪质量统计（跟踪命中率等，IoU 关联评价）。 */
        val trackStats: FaceTrackMath.TrackStats
            get() = FaceTrackMath.trackingStats(frames)
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
     * @param faceCascadePaths    已释放到本地文件的人脸级联模型路径
     * @param featureCascadePaths 已释放到本地文件的面部特征模型路径
     * @param onProgress  进度回调 (done, total)
     */
    fun analyze(
        videoPath: String,
        faceCascadePaths: List<String>,
        featureCascadePaths: List<String>,
        onProgress: (Int, Int) -> Unit
    ): Result {
        val handle = FaceJni.nativeCreate(
            faceCascadePaths.toTypedArray(), featureCascadePaths.toTypedArray()
        )
        check(handle != 0L) { "cascade model load failed: $faceCascadePaths" }

        val t0 = System.currentTimeMillis()
        val total = VideoFrameSource.countSamples(videoPath)
        Log.d(TAG, "analyze: $videoPath, $total frames, workWidth=$workWidth")

        val framesOut = ArrayList<FaceFrame>(total)
        var framesWithFaces = 0
        var totalHits = 0
        var maxFaces = 0

        try {
            VideoFrameSource(videoPath).use { src ->
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
}
