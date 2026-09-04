package com.wangyao.opencvprocessing.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.wangyao.videorecognition.face.FaceBox

/**
 * 人脸框叠加视图：绘制在 TextureView 之上，实时显示人脸检测框。
 *
 * 职责：
 * 1. 把工作分辨率下的人脸框映射到视频原始分辨率，再按
 *    fit-center 映射到当前 View 尺寸（与播放器的缩放矩阵一致）；
 * 2. 圆角描边 + 半透明填充 + 顶部标签（编号 + 置信度）。
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var videoW = 0
    private var videoH = 0
    private var workW = 0
    private var faces: List<FaceBox> = emptyList()

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = ACCENT
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ACCENT
        alpha = 36
    }

    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC1B1B1B")
    }

    private val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
    }

    private val tmpRect = RectF()

    /** 设置视频尺寸与工作分辨率（框坐标的来源尺度）。 */
    fun setMapping(videoWidth: Int, videoHeight: Int, workWidth: Int) {
        videoW = videoWidth
        videoH = videoHeight
        workW = workWidth
        invalidate()
    }

    /** 更新当前帧的人脸框并重绘。 */
    fun setFaces(list: List<FaceBox>) {
        faces = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (faces.isEmpty() || videoW <= 0 || workW <= 0) return

        // fit-center：视频铺满 View 且保持宽高比
        val scale = minOf(width.toFloat() / videoW, height.toFloat() / videoH)
        val dx = (width - videoW * scale) / 2f
        val dy = (height - videoH * scale) / 2f
        val k = videoW.toFloat() / workW * scale

        for ((i, f) in faces.withIndex()) {
            val left = dx + f.x * k
            val top = dy + f.y * k
            val right = left + f.w * k
            val bottom = top + f.h * k
            tmpRect.set(left, top, right, bottom)

            canvas.drawRoundRect(tmpRect, 12f, 12f, fillPaint)
            canvas.drawRoundRect(tmpRect, 12f, 12f, boxPaint)

            // 顶部标签：人脸编号 + 置信度
            val label = "人脸 ${i + 1} · ${(f.conf * 100).toInt()}%"
            val tw = chipTextPaint.measureText(label)
            val padH = 10f
            val chipH = 38f
            var chipTop = top - chipH - 6f
            if (chipTop < 0) chipTop = bottom + 6f // 顶部出界则放框下方
            canvas.drawRoundRect(
                left, chipTop, left + tw + padH * 2, chipTop + chipH,
                8f, 8f, chipPaint
            )
            canvas.drawText(
                label, left + padH, chipTop + chipH * 0.72f, chipTextPaint
            )
        }
    }

    companion object {
        private val ACCENT = Color.parseColor("#00E676")
    }
}
