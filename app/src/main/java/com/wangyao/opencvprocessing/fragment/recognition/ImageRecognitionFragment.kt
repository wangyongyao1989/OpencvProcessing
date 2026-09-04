package com.wangyao.opencvprocessing.fragment.recognition

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.lifecycle.ViewModelProvider
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentIrImageLayoutBinding
import com.wangyao.imagerecognition.core.NccMatcher
import com.wangyao.imagerecognition.core.ShapeRecognizer
import kotlin.concurrent.thread

/**
 * 三级页：图像的识别处理（《数字图像与视频处理》第 11 章图像识别）。
 *
 * 对 assets/IMG_20260821_190758.jpg 的两类识别处理：
 *
 * 1. 模板匹配识别（11.2 节）：从图中央取 64×64 模板，
 *    粗到精全图 NCC 搜索（式 11-1 归一化互相关，对光照增益/
 *    偏置不变），输出最佳匹配位置（红框标记）与相似度热力图
 *    （蓝→红表示 0→1）；
 *
 * 2. Hu 不变矩形状识别（11.3~11.4 节）：完整模式识别流程
 *    「特征提取 → 分类决策」。形状库（圆/矩形/三角/星形/椭圆，
 *    每类 4 个变换副本）预先提取对数 Hu 矩特征；用户选择查询
 *    形状并可叠加旋转/缩放变换，分类器按对数 Hu 空间最近邻
 *    输出类别、置信度与 Top-3 候选——验证 Hu 矩的
 *    平移/旋转/尺度不变性。
 */
class ImageRecognitionFragment : BaseFragment() {

    private lateinit var binding: FragmentIrImageLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var workGray: ByteArray? = null
    private var workW = 0
    private var workH = 0
    private var busy = false

    private var queryShape = ShapeRecognizer.Shape.CIRCLE
    private var queryTransform = Transform.NONE

    /** 查询形状变换。 */
    enum class Transform { NONE, ROTATE, SCALE, BOTH }

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentIrImageLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initData() {
        // 降采样解码素材图（最长边 ≤ 512，控制 NCC 计算量）
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        requireContext().assets.open(ASSET_IMAGE).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DIM || bounds.outHeight / sample > MAX_DIM) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = requireContext().assets.open(ASSET_IMAGE).use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return
        workW = bmp.width
        workH = bmp.height

        // 转灰度
        val px = IntArray(workW * workH)
        bmp.getPixels(px, 0, workW, 0, 0, workW, workH)
        workGray = ByteArray(workW * workH)
        for (i in px.indices) {
            val p = px[i]
            workGray!![i] = (((p shr 16 and 0xFF) * 299 +
                (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000).toByte()
        }

        // 初始预览：查询形状
        updateShapePreview()
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_RECOGNITION)
        }

        binding.btnTm.setOnClickListener {
            if (!busy) runTemplateMatch()
        }

        binding.btnClassify.setOnClickListener {
            if (!busy) runShapeClassify()
        }

        // 形状单选
        val shapeChecks = mapOf(
            binding.cbCircle to ShapeRecognizer.Shape.CIRCLE,
            binding.cbRect to ShapeRecognizer.Shape.RECT,
            binding.cbTriangle to ShapeRecognizer.Shape.TRIANGLE,
            binding.cbStar to ShapeRecognizer.Shape.STAR,
            binding.cbEllipse to ShapeRecognizer.Shape.ELLIPSE
        )
        val shapeListener = CompoundButton.OnCheckedChangeListener { btn, checked ->
            if (checked) {
                shapeChecks.forEach { (cb, _) -> if (cb !== btn) cb.isChecked = false }
                shapeChecks[btn]?.let {
                    queryShape = it
                    updateShapePreview()
                }
            }
        }
        shapeChecks.forEach { (cb, _) -> cb.setOnCheckedChangeListener(shapeListener) }

        // 变换单选
        val tfChecks = mapOf(
            binding.cbTfNone to Transform.NONE,
            binding.cbTfRot to Transform.ROTATE,
            binding.cbTfScale to Transform.SCALE,
            binding.cbTfBoth to Transform.BOTH
        )
        val tfListener = CompoundButton.OnCheckedChangeListener { btn, checked ->
            if (checked) {
                tfChecks.forEach { (cb, _) -> if (cb !== btn) cb.isChecked = false }
                tfChecks[btn]?.let {
                    queryTransform = it
                    updateShapePreview()
                }
            }
        }
        tfChecks.forEach { (cb, _) -> cb.setOnCheckedChangeListener(tfListener) }
    }

    // -------------------------------------------------------------------------
    // 功能一：模板匹配识别
    // -------------------------------------------------------------------------

    private fun runTemplateMatch() {
        val gray = workGray ?: return
        val w = workW
        val h = workH
        busy = true
        binding.btnTm.isEnabled = false
        binding.progressTm.visibility = View.VISIBLE
        binding.progressTm.progress = 0
        binding.tvTmStatus.text = getString(R.string.ir_tm_status_running)

        thread(start = true) {
            // 中央取模板（64×64，或图 1/6 边长取小者）
            val ts = TEMPLATE_SIZE.coerceAtMost(minOf(w, h) / 4)
            val tx = (w - ts) / 2
            val ty = (h - ts) / 2
            val tpl = ByteArray(ts * ts)
            for (j in 0 until ts) {
                System.arraycopy(gray, (ty + j) * w + tx, tpl, j * ts, ts)
            }

            activity?.runOnUiThread {
                if (isAdded) binding.progressTm.progress = 30
            }

            // 粗到精全图搜索
            val r = NccMatcher.searchFull(gray, w, h, tpl, ts, ts)

            activity?.runOnUiThread {
                if (isAdded) binding.progressTm.progress = 70
            }

            // 热力图（粗网格）
            val heat = NccMatcher.similarityMap(gray, w, h, tpl, ts, ts, HEAT_STEP)

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.progressTm.visibility = View.GONE
                binding.btnTm.isEnabled = true
                busy = false

                // 结果图：原图 + 红框标记最佳匹配 + 黄框标记模板原位置
                val resultBmp = grayToBitmap(gray, w, h)
                val canvas = Canvas(resultBmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                paint.color = Color.YELLOW
                canvas.drawRect(Rect(tx, ty, tx + ts, ty + ts), paint)
                paint.color = Color.RED
                canvas.drawRect(
                    Rect(r[0].toInt(), r[1].toInt(), r[0].toInt() + ts, r[1].toInt() + ts),
                    paint
                )
                binding.ivTmResult.setImageBitmap(resultBmp)

                // 热力图：蓝→红
                binding.ivTmHeatmap.setImageBitmap(heatToBitmap(heat, w, h, ts, HEAT_STEP))

                binding.tvTmStatus.text = getString(
                    R.string.ir_tm_status_done, r[2], r[0].toInt(), r[1].toInt(),
                    tx, ty, ts
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // 功能二：Hu 不变矩形状识别
    // -------------------------------------------------------------------------

    private fun updateShapePreview() {
        val mask = renderQueryMask()
        binding.ivShapeQuery.setImageBitmap(maskToBitmap(mask))
    }

    /** 按当前选择渲染查询形状二值图。 */
    private fun renderQueryMask(): ByteArray {
        val (rot, scale) = when (queryTransform) {
            Transform.NONE -> 0.0 to 1.0
            Transform.ROTATE -> 37.0 to 1.0
            Transform.SCALE -> 0.0 to 1.6
            Transform.BOTH -> 53.0 to 1.4
        }
        return ShapeRecognizer.renderShape(queryShape, ShapeRecognizer.SIZE, rot, scale)
    }

    private fun runShapeClassify() {
        busy = true
        binding.btnClassify.isEnabled = false

        thread(start = true) {
            // 特征提取 + 最近邻分类（第 11 章完整识别流程）
            val recognizer = ShapeRecognizer()
            val mask = renderQueryMask()
            val result = recognizer.classify(mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE)
            // 查询特征与各类中心的距离
            val distances = ShapeRecognizer.Shape.entries.joinToString("\n") { shape ->
                val d = shapeDistances(recognizer, shape)
                getString(R.string.ir_shape_dist_row, shapeName(shape), d)
            }

            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.btnClassify.isEnabled = true
                busy = false
                binding.ivShapeQuery.setImageBitmap(maskToBitmap(mask))
                binding.tvShapeResult.text = getString(
                    R.string.ir_shape_result_done,
                    shapeName(result.label),
                    result.confidence,
                    result.distance,
                    shapeName(result.top3[0].first), result.top3[0].second,
                    shapeName(result.top3[1].first), result.top3[1].second,
                    shapeName(result.top3[2].first), result.top3[2].second
                ) + "\n\n" + distances
            }
        }
    }

    /** 计算查询与指定形状原始样本的距离（展示类间可分性，与分类器同口径：仅 H1~H3）。 */
    private fun shapeDistances(recognizer: ShapeRecognizer, shape: ShapeRecognizer.Shape): Double {
        val mask = renderQueryMask()
        val qf = com.wangyao.imagerecognition.core.HuMoments.logTransform(
            com.wangyao.imagerecognition.core.HuMoments.compute(
                mask, ShapeRecognizer.SIZE, ShapeRecognizer.SIZE
            )
        )
        val ref = recognizer.huOf(shape)
        var s = 0.0
        for (i in 0 until 3) {
            val d = qf[i] - ref[i]
            s += d * d
        }
        return kotlin.math.sqrt(s)
    }

    /** 形状本地化名称。 */
    private fun shapeName(shape: ShapeRecognizer.Shape): String = getString(
        when (shape) {
            ShapeRecognizer.Shape.CIRCLE -> R.string.ir_shape_circle
            ShapeRecognizer.Shape.RECT -> R.string.ir_shape_rect
            ShapeRecognizer.Shape.TRIANGLE -> R.string.ir_shape_triangle
            ShapeRecognizer.Shape.STAR -> R.string.ir_shape_star
            ShapeRecognizer.Shape.ELLIPSE -> R.string.ir_shape_ellipse
        }
    )

    // -------------------------------------------------------------------------
    // 位图工具
    // -------------------------------------------------------------------------

    /** 灰度 → Bitmap。 */
    private fun grayToBitmap(gray: ByteArray, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (i in gray.indices) {
            val v = gray[i].toInt() and 0xFF
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 二值掩码 → Bitmap（黑白）。 */
    private fun maskToBitmap(mask: ByteArray): Bitmap {
        val size = ShapeRecognizer.SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val px = IntArray(mask.size)
        for (i in mask.indices) {
            val v = if ((mask[i].toInt() and 0xFF) > 0) 30 else 240
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, size, 0, 0, size, size)
        return bmp
    }

    /**
     * NCC 热力图 → 彩色 Bitmap：
     * 蓝(0) → 青 → 绿 → 黄 → 红(1)，无效区域深灰。
     */
    private fun heatToBitmap(
        heat: FloatArray, w: Int, h: Int, ts: Int, step: Int
    ): Bitmap {
        val gw = (w - ts) / step + 1
        val gh = (h - ts) / step + 1
        val bmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        val px = IntArray(gw * gh)
        for (i in heat.indices) {
            val v = heat[i].coerceIn(0f, 1f)
            val r = (255 * v).toInt()
            val g = (255 * (1 - kotlin.math.abs(v - 0.5f) * 2)).toInt()
            val b = (255 * (1 - v)).toInt()
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        bmp.setPixels(px, 0, gw, 0, 0, gw, gh)
        return Bitmap.createScaledBitmap(bmp, w, h, false)
    }

    companion object {
        private const val ASSET_IMAGE = "IMG_20260821_190758.jpg"
        private const val MAX_DIM = 512
        private const val TEMPLATE_SIZE = 64
        private const val HEAT_STEP = 8
    }
}
