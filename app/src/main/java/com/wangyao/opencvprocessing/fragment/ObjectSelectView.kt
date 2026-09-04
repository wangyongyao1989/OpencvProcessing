package com.wangyao.opencvprocessing.fragment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View

/**
 * 手势框选叠加层（框选实物实时检测跟踪）：
 * 覆盖在预览 SurfaceView 之上，按住拖动绘制半透明绿色选框，
 * 松手后把选框（View 坐标）回调给 Fragment 换算为图像坐标。
 *
 * 与 FaceOverlayView 的职责区分：本视图只负责「选」，
 * 跟踪绿框由 native 侧直接绘制在渲染帧上。
 */
class ObjectSelectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 有效框选的最小边长（px，过滤误触的小拖动）。 */
    private val minSelectPx = 32

    /** 松手回调（View 坐标系的归一化选框）。 */
    var onSelect: ((RectF) -> Unit)? = null

    private var dragging = false
    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x3300AA00
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00AA00.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                startX = event.x
                startY = event.y
                curX = event.x
                curY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                curX = event.x
                curY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                    val l = minOf(startX, curX).coerceAtLeast(0f)
                    val t = minOf(startY, curY).coerceAtLeast(0f)
                    val r = maxOf(startX, curX).coerceAtMost(width.toFloat())
                    val b = maxOf(startY, curY).coerceAtMost(height.toFloat())
                    if (r - l >= minSelectPx && b - t >= minSelectPx) {
                        Log.d(TAG, "手势框选松手: viewRect=(${l.toInt()}," +
                                "${t.toInt()},${r.toInt()},${b.toInt()}) " +
                                "size=${(r - l).toInt()}x${(b - t).toInt()}")
                        onSelect?.invoke(RectF(l, t, r, b))
                    } else {
                        Log.d(TAG, "框选过小已忽略: size=" +
                                "${(r - l).toInt()}x${(b - t).toInt()} " +
                                "min=${minSelectPx}px")
                    }
                }
                dragging = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!dragging) return
        val l = minOf(startX, curX)
        val t = minOf(startY, curY)
        val r = maxOf(startX, curX)
        val b = maxOf(startY, curY)
        canvas.drawRect(l, t, r, b, fillPaint)
        canvas.drawRect(l, t, r, b, strokePaint)
    }

    private companion object {
        /** 日志 tag（与 ObjectTrackFragment 的 CR_ObjectTrack 一致）。 */
        const val TAG = "CR_ObjectTrack"
    }
}
