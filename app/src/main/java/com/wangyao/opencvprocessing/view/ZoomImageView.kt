package com.wangyao.opencvprocessing.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import com.wangyao.opencvprocessing.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 支持手势缩放与拖动的 ImageView。
 *
 * 功能：
 * - 双指捏合缩放（1x ~ 5x），围绕双指焦点缩放（焦点处内容保持不动）；
 * - 放大后单指拖动平移，并按 FIT_CENTER 内容尺寸自动限制平移范围，
 *   图片不会被拖出可视边界；
 * - 双击在 1x 与 2.5x 之间切换（带 200ms 动画）；
 * - 缩放期间接管手势（阻止外层 NestedScrollView/ScrollView 拦截），
 *   恢复 1x 后交还滚动容器，页面可正常滚动；
 * - 换图（setImageBitmap 等）时自动复位为 1x 居中。
 *
 * 通过 app:zoomEnabled="false" 可关闭手势（用于装饰性图标，
 * 行为与普通 ImageView 一致，不影响父容器的点击事件）。
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_SCALE = 1f
        private const val MAX_SCALE = 5f
        private const val DOUBLE_TAP_SCALE = 2.5f
        private const val RESET_EPS = 0.001f
        private const val ZOOM_ANIM_DURATION = 200L
    }

    /** 是否启用手势缩放/拖动。 */
    private var zoomEnabled = true

    /** 当前缩放倍率（逻辑值，双击动画期间逐帧同步更新）。 */
    private var scale = 1f

    /** 双击缩放动画。 */
    private var zoomAnimator: ValueAnimator? = null

    /** 双指缩放检测器：围绕焦点缩放并保持焦点处内容不动。 */
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {

            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                zoomAnimator?.cancel()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val oldScale = scale
                val newScale = (oldScale * detector.scaleFactor)
                    .coerceIn(MIN_SCALE, MAX_SCALE)
                if (abs(newScale - oldScale) < 1e-4f) return true
                // 围绕焦点 (fx, fy) 缩放：焦点下的内容点缩放后仍位于焦点处。
                // 视图变换：screen = translation + pivot + scale*(v - pivot)
                val relX = detector.focusX - pivotX - translationX
                val relY = detector.focusY - pivotY - translationY
                val ratio = newScale / oldScale
                translationX = detector.focusX - pivotX - relX * ratio
                translationY = detector.focusY - pivotY - relY * ratio
                applyScale(newScale)
                return true
            }
        })

    /** 单指手势检测器：拖动平移 + 双击缩放。 */
    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent,
                dx: Float, dy: Float,
            ): Boolean {
                // 仅在放大状态下拖动；1x 时把手势留给外层滚动容器
                if (scale <= MIN_SCALE + RESET_EPS) return false
                translationX -= dx
                translationY -= dy
                clampTranslation()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > MIN_SCALE + RESET_EPS) {
                    animateZoom(e.x, e.y, MIN_SCALE)
                    disallowParentIntercept(false)
                } else {
                    animateZoom(e.x, e.y, DOUBLE_TAP_SCALE)
                    disallowParentIntercept(true)
                }
                return true
            }
        })

    init {
        if (attrs != null) {
            val ta = context.obtainStyledAttributes(
                attrs, R.styleable.ZoomImageView, defStyleAttr, 0
            )
            zoomEnabled = ta.getBoolean(R.styleable.ZoomImageView_zoomEnabled, true)
            ta.recycle()
        }
    }

    // -------------------------------------------------------------------------
    // 触摸事件
    // -------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!zoomEnabled) return super.onTouchEvent(event)
        when (event.actionMasked) {
            // 双指按下：接管手势，防止外层滚动容器把捏合当作滚动拦截
            MotionEvent.ACTION_POINTER_DOWN ->
                if (event.pointerCount >= 2) {
                    disallowParentIntercept(true)
                    zoomAnimator?.cancel()
                }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                disallowParentIntercept(false)
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    /** 缩放期间阻止外层滚动容器拦截触摸事件（会沿父链向上传递）。 */
    private fun disallowParentIntercept(disallow: Boolean) {
        parent?.requestDisallowInterceptTouchEvent(disallow)
    }

    // -------------------------------------------------------------------------
    // 缩放状态维护
    // -------------------------------------------------------------------------

    /** 应用新缩放倍率，并在恢复 1x 时复位平移。 */
    private fun applyScale(newScale: Float) {
        scale = newScale
        scaleX = newScale
        scaleY = newScale
        if (newScale <= MIN_SCALE + RESET_EPS) {
            translationX = 0f
            translationY = 0f
        } else {
            clampTranslation()
        }
    }

    /**
     * 限制平移范围：按 FIT_CENTER 后的内容实际显示尺寸计算，
     * 保证缩放后的内容仍铺满可视区域、不被拖出边界。
     */
    private fun clampTranslation() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dr = drawable
        if (dr == null || vw <= 0f || vh <= 0f
            || dr.intrinsicWidth <= 0 || dr.intrinsicHeight <= 0
        ) {
            // 无图或未布局时退化为按视图尺寸限制
            val m = max(0f, (scale - 1f) * vw / 2f)
            translationX = translationX.coerceIn(-m, m)
            translationY = translationY.coerceIn(-m, m)
            return
        }
        val dw = dr.intrinsicWidth.toFloat()
        val dh = dr.intrinsicHeight.toFloat()
        val fit = min(vw / dw, vh / dh)          // FIT_CENTER 基础缩放
        val contentW = dw * fit * scale
        val contentH = dh * fit * scale
        val maxX = max(0f, (contentW - vw) / 2f)
        val maxY = max(0f, (contentH - vh) / 2f)
        translationX = translationX.coerceIn(-maxX, maxX)
        translationY = translationY.coerceIn(-maxY, maxY)
    }

    /** 围绕 (fx, fy) 动画缩放到 [targetScale]。 */
    private fun animateZoom(fx: Float, fy: Float, targetScale: Float) {
        val startScale = scale
        val relX = fx - pivotX - translationX
        val relY = fy - pivotY - translationY
        zoomAnimator?.cancel()
        zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_ANIM_DURATION
            addUpdateListener { animation ->
                val t = animation.animatedValue as Float
                val s = startScale + (targetScale - startScale) * t
                val ratio = s / startScale
                translationX = fx - pivotX - relX * ratio
                translationY = fy - pivotY - relY * ratio
                scale = s
                scaleX = s
                scaleY = s
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    applyScale(targetScale)
                }
            })
            start()
        }
    }

    /** 恢复 1x 居中。 */
    private fun resetTransform() {
        zoomAnimator?.cancel()
        scale = MIN_SCALE
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (scale > MIN_SCALE + RESET_EPS) clampTranslation()
    }

    // -------------------------------------------------------------------------
    // 换图自动复位
    // -------------------------------------------------------------------------

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetTransform()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetTransform()
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        resetTransform()
    }
}
