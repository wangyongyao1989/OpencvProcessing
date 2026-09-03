package com.wangyao.videorecognition.retrieval

import android.graphics.Bitmap
import android.util.Log
import com.wangyao.videorecognition.face.FaceTrackMath
import com.wangyao.videorecognition.video.VideoFrameSource

private const val TAG = "VR_Indexer"

/**
 * 基于内容的视频关键帧索引器（需求文档 4.3.7 VideoStructurer
 * 的关键帧抽取/索引职责 + contentsearch 模块第 10 章 10.5 节
 * 「以查询帧检索视频」概念的自包含实现）。
 *
 * 流程：
 * 1. [extractKeyframes]（抽取关键帧并建立索引）：软解逐帧解码，
 *    按帧序均匀抽取 N 个关键帧——关键帧是视频内容的代表，将
 *    检索规模从「逐帧」降到「镜头级」；每个关键帧降采样到工作
 *    分辨率（宽 320）后提取「亮度直方图 + 梯度方向纹理直方图」
 *    综合特征并记录时间戳，即完成索引；
 * 2. [search]（以查询帧检索视频）：以任一亮度平面为查询帧提取
 *    特征，与全部关键帧特征计算综合相似度（0.6 颜色 + 0.4 纹理），
 *    按相似度降序返回 Top-K 及颜色/纹理分项得分——命中结果的
 *    时间戳即「相似内容在视频中的位置」。
 */
class VideoKeyframeIndexer {

    /** 视频关键帧（含时间戳、缩略亮度平面与综合特征）。 */
    class Keyframe(
        val frameIndex: Int,
        /** 该帧在视频中的时间戳（µs）。 */
        val timestampUs: Long,
        /** 工作分辨率亮度平面（供特征提取与 UI 缩略图）。 */
        val luma: ByteArray,
        val width: Int,
        val height: Int,
        /** 综合特征（索引项）。 */
        var feature: KeyframeFeatures.Feature? = null
    )

    /** 关键帧命中结果：综合相似度及颜色/纹理分项。 */
    data class Match(
        val keyframe: Keyframe,
        /** 综合相似度 ∈[0,1]。 */
        val score: Double,
        /** 颜色（亮度直方图相交）分项 ∈[0,1]。 */
        val colorScore: Double,
        /** 纹理（梯度方向余弦）分项 ∈[0,1]。 */
        val textureScore: Double
    )

    /** 查询帧：用于检索的工作分辨率亮度平面。 */
    class QueryFrame(
        val luma: ByteArray,
        val width: Int,
        val height: Int
    )

    /** 已索引的关键帧（时间升序）。 */
    val keyframes = mutableListOf<Keyframe>()

    /** 关键帧工作分辨率宽度。 */
    private val workWidth = 320

    /**
     * 抽取 [count] 个均匀分布的关键帧并建立特征索引。
     * 例如 459 帧抽 12 帧 → 每隔 ~38 帧取一帧。
     */
    fun extractKeyframes(
        path: String,
        count: Int,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Keyframe> {
        require(count > 0) { "keyframe count must be > 0" }
        keyframes.clear()

        val total = VideoFrameSource.countSamples(path)
        Log.d(TAG, "extractKeyframes: $path, $total frames -> $count keyframes")
        // 均匀采样目标帧号（避开第 0 帧，从 1/2 间隔开始）
        val targets = IntArray(count) { i ->
            ((i + 0.5) * total / count).toInt().coerceIn(0, total - 1)
        }.toSortedSet().toIntArray()

        val result = mutableListOf<Keyframe>()
        VideoFrameSource(path).use { src ->
            val scale = (src.width / workWidth.toFloat()).toInt().coerceAtLeast(1)
            val ww = src.width / scale
            val wh = src.height / scale
            Log.d(TAG, "work resolution ${ww}x$wh (scale=$scale)")

            var frameIdx = -1
            var tPos = 0
            while (tPos < targets.size) {
                val luma = src.nextFrame() ?: break
                frameIdx++
                if (frameIdx == targets[tPos]) {
                    val small = if (scale > 1)
                        FaceTrackMath.downsample(luma, src.width, src.height, scale)
                    else luma
                    val kf = Keyframe(
                        frameIndex = frameIdx,
                        timestampUs = src.lastPtsUs,
                        luma = small,
                        width = ww,
                        height = wh
                    )
                    // 建立索引：提取综合特征
                    kf.feature = KeyframeFeatures.extract(small, ww, wh)
                    result.add(kf)
                    tPos++
                }
                onProgress(frameIdx + 1, total)
            }
        }
        keyframes.addAll(result)
        Log.d(TAG, "extractKeyframes: ${result.size} keyframes indexed")
        return result
    }

    /**
     * 以查询帧（亮度平面）检索最相似的 [k] 个关键帧。
     *
     * @param exclude 需要从结果中排除的关键帧（如查询帧本身），
     *                便于展示「视频中最相似的其他内容」
     */
    fun search(
        queryLuma: ByteArray,
        width: Int,
        height: Int,
        k: Int,
        exclude: Keyframe? = null
    ): List<Match> {
        val qf = KeyframeFeatures.extract(queryLuma, width, height)
        return keyframes
            .filter { it !== exclude }
            .mapNotNull { kf ->
                kf.feature?.let { f ->
                    Match(
                        keyframe = kf,
                        score = KeyframeFeatures.comprehensiveSimilarity(qf, f),
                        colorScore = KeyframeFeatures.histogramIntersection(qf.color, f.color),
                        textureScore = KeyframeFeatures.cosine(qf.texture, f.texture)
                    )
                }
            }
            .sortedByDescending { it.score }
            .take(k)
    }

    companion object {

        /**
         * 任意画面 Bitmap → 查询帧：等比降采样（双线性）到工作
         * 分辨率后按 ITU-R BT.601 加权转亮度平面——使「播放中
         * 捕获的当前帧」与关键帧特征在同一表示空间，可直接
         * 以 [search] 检索（「以查询帧检索视频」的查询来源之一）。
         */
        fun bitmapToLuma(bmp: Bitmap, targetWidth: Int = 320): QueryFrame {
            val scale = maxOf(1, (bmp.width + targetWidth - 1) / targetWidth)
            val w = (bmp.width / scale).coerceAtLeast(1)
            val h = (bmp.height / scale).coerceAtLeast(1)
            val small = if (w != bmp.width || h != bmp.height)
                Bitmap.createScaledBitmap(bmp, w, h, true)
            else
                bmp
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            val luma = ByteArray(w * h)
            for (i in px.indices) {
                val p = px[i]
                val v = (0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)).toInt()
                luma[i] = v.coerceIn(0, 255).toByte()
            }
            return QueryFrame(luma, w, h)
        }

        /** 亮度平面 → 灰度 Bitmap（UI 缩略图）。 */
        fun lumaToBitmap(luma: ByteArray, width: Int, height: Int, maxWidth: Int = 160): Bitmap {
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
