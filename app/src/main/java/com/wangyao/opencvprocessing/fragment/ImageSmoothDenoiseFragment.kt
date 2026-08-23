package com.wangyao.opencvprocessing.fragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.wangyao.opencvdeal.jni.OpencvDealJni
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentImageSmoothDenoiseLayoutBinding
import kotlin.concurrent.thread

/**
 * 图像平滑与去噪界面（三级页，与「图像灰度变换」同级）：
 * - 顶部：9 个 checkbar（单选互斥），①~⑨ 对应 PDF 第二章 2.3 节中的平滑/去噪方法。
 * - 中部：左右并排显示「原图」与「当前选择的处理结果」。
 * - 底部：当前所选方法的「原理及公式」文本说明，包含对应公式编号与表达。
 *
 * 注：为兼顾 NLM/频域 DFT 的处理耗时，加载时先按 inSampleSize 降采样到长边 ≤1080。
 */
class ImageSmoothDenoiseFragment : BaseFragment() {

    private lateinit var binding: FragmentImageSmoothDenoiseLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null

    // 单选互斥：避免 onCheckedChanged 递归触发
    private var applyingChecked = false

    /** 九种平滑/去噪方法的元数据（对应 PDF 2.3 节）。 */
    private enum class Transform(
        @StringRes val titleRes: Int,
        @StringRes val formulaRes: Int,
    ) {
        ORIGINAL(R.string.smooth_op_original_title, R.string.smooth_op_original_formula),
        AVG4(R.string.smooth_op_avg4_title, R.string.smooth_op_avg4_formula),
        AVG8(R.string.smooth_op_avg8_title, R.string.smooth_op_avg8_formula),
        THRESHOLD_AVG(R.string.smooth_op_threshold_avg_title, R.string.smooth_op_threshold_avg_formula),
        MEDIAN3(R.string.smooth_op_median3_title, R.string.smooth_op_median3_formula),
        MEDIAN_CROSS(R.string.smooth_op_median_cross_title, R.string.smooth_op_median_cross_formula),
        ILPF(R.string.smooth_op_ilpf_title, R.string.smooth_op_ilpf_formula),
        GLPF(R.string.smooth_op_glpf_title, R.string.smooth_op_glpf_formula),
        NLM(R.string.smooth_op_nlm_title, R.string.smooth_op_nlm_formula),
    }

    /** 按顺序排列的九项。 */
    private val transforms: Array<Transform> = Transform.values()

    /** 9 个 CheckBox（按 index 0..8 顺序）。 */
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageSmoothDenoiseLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02Avg4,
            binding.cb03Avg8,
            binding.cb04ThresholdAvg,
            binding.cb05Median3,
            binding.cb06MedianCross,
            binding.cb07Ilpf,
            binding.cb08Glpf,
            binding.cb09Nlm,
        )
    }

    override fun initData() {
        originalBitmap = loadBitmapFromAssets(IMG_ASSET_NAME)
        originalBitmap?.let { binding.ivOriginal.setImageBitmap(it) }
        // 初始显示第一项：原图
        applyTransform(Transform.ORIGINAL)
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        // 返回按钮：回到二级菜单「图像增强」
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_ENHANCE)
        }

        // 9 个 CheckBox 互斥单选
        val onChecked =
            CompoundButton.OnCheckedChangeListener { view, isChecked ->
                if (applyingChecked) return@OnCheckedChangeListener
                val idx = checkBoxes.indexOfFirst { it === view }
                if (idx < 0) return@OnCheckedChangeListener
                if (isChecked) {
                    applyingChecked = true
                    for ((i, cb) in checkBoxes.withIndex()) {
                        if (i != idx) cb.isChecked = false
                    }
                    applyingChecked = false
                    applyTransform(transforms[idx])
                } else {
                    // 不允许全部取消：至少保留一项勾选
                    if (checkBoxes.none { it.isChecked }) {
                        applyingChecked = true
                        view.isChecked = true
                        applyingChecked = false
                    }
                }
            }
        checkBoxes.forEach { it.setOnCheckedChangeListener(onChecked) }
    }

    // -------------------------------------------------------------------------
    // 处理执行与 UI 渲染
    // -------------------------------------------------------------------------

    /** 根据所选方法调用 JNI 并更新「结果图 + 原理公式」。 */
    private fun applyTransform(transform: Transform) {
        // 先显示原理公式（UI 立即刷新，图片异步处理好后再更新）。
        // 将【原理】【式 X-X】等段落标记转为加粗富文本，提升可读性。
        binding.tvFormula.text = getString(transform.formulaRes)
            .replace("【".toRegex(), "<b>【")
            .replace("】".toRegex(), "】</b>")
            .let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY) }
        binding.tvResultTitle.text = getString(transform.titleRes)
        val src = originalBitmap ?: return

        thread(start = true) {
            val result: Bitmap? = runCatching {
                when (transform) {
                    Transform.ORIGINAL -> src
                    Transform.AVG4 -> OpencvDealJni.smoothNeighborhoodAverage4(src)
                    Transform.AVG8 -> OpencvDealJni.smoothNeighborhoodAverage8(src)
                    Transform.THRESHOLD_AVG -> OpencvDealJni.smoothThresholdAverage(src, 30.0)
                    Transform.MEDIAN3 -> OpencvDealJni.smoothMedian3x3(src)
                    Transform.MEDIAN_CROSS -> OpencvDealJni.smoothMedianCross5x5(src)
                    Transform.ILPF -> OpencvDealJni.smoothIdealLowPass(src, 60.0)
                    Transform.GLPF -> OpencvDealJni.smoothGaussianLowPass(src, 40.0)
                    Transform.NLM -> OpencvDealJni.smoothNlmDenoise(src, 10.0)
                }
            }.getOrElse { it.printStackTrace(); null } ?: src

            activity?.runOnUiThread {
                binding.ivResult.setImageBitmap(result)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    /**
     * 从 assets 加载图片并降采样（长边 ≤ [MAX_SIDE]）。
     * NLM 与频域 DFT 计算量较大，降采样可保证交互流畅。
     */
    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        return try {
            // 第一次解码仅获取尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            requireContext().assets.open(fileName).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_SIDE) {
                sampleSize *= 2
            }
            // 第二次解码真正取像素
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = requireContext().assets.open(fileName).use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            if (bmp != null && bmp.config != Bitmap.Config.ARGB_8888) {
                bmp.copy(Bitmap.Config.ARGB_8888, false).also { bmp.recycle() }
            } else bmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        private const val IMG_ASSET_NAME = "IMG_20260821_190758.jpg"
        private const val MAX_SIDE = 1080
    }
}
