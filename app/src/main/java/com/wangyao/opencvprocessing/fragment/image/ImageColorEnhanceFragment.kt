package com.wangyao.opencvprocessing.fragment.image

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

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
import com.wangyao.opencvprocessing.databinding.FragmentImageColorEnhanceLayoutBinding
import kotlin.concurrent.thread

/**
 * 彩色增强界面（三级页）：
 * - 顶部：9 个 checkbar（单选互斥），①~⑨ 对应 PDF 2.7 节选项
 *   （伪彩色增强三种方法 + 假彩色增强四种映射）。
 * - 中部：左右并排显示「原图」与「当前选择的处理结果」。
 * - 底部：当前所选方法的「原理及公式」说明（图 2-47~2-50、式 2-97/2-98）。
 *
 * 注：为兼顾频域 DFT 的耗时，加载时先降采样到长边 ≤1080。
 */
class ImageColorEnhanceFragment : BaseFragment() {

    private lateinit var binding: FragmentImageColorEnhanceLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null
    private var applyingChecked = false

    /** 九种彩色增强选项的元数据（对应 PDF 2.7 节）。 */
    private enum class Transform(
        @StringRes val titleRes: Int,
        @StringRes val formulaRes: Int,
    ) {
        ORIGINAL(R.string.color_op_original_title, R.string.color_op_original_formula),
        SLICE2(R.string.color_op_slice2_title, R.string.color_op_slice2_formula),
        SLICE_MULTI(R.string.color_op_slice_multi_title, R.string.color_op_slice_multi_formula),
        LEVEL_COLOR(R.string.color_op_level_color_title, R.string.color_op_level_color_formula),
        FREQ_COLOR(R.string.color_op_freq_color_title, R.string.color_op_freq_color_formula),
        FALSE_LINEAR(R.string.color_op_false_linear_title, R.string.color_op_false_linear_formula),
        FALSE_GREEN(R.string.color_op_false_green_title, R.string.color_op_false_green_formula),
        FALSE_BLUE(R.string.color_op_false_blue_title, R.string.color_op_false_blue_formula),
        FALSE_MULTI(R.string.color_op_false_multi_title, R.string.color_op_false_multi_formula),
    }

    private val transforms: Array<Transform> = Transform.values()
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageColorEnhanceLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02Slice2,
            binding.cb03SliceMulti,
            binding.cb04LevelColor,
            binding.cb05FreqColor,
            binding.cb06FalseLinear,
            binding.cb07FalseGreen,
            binding.cb08FalseBlue,
            binding.cb09FalseMulti,
        )
    }

    override fun initData() {
        originalBitmap = loadBitmapFromAssets(IMG_ASSET_NAME)
        originalBitmap?.let { binding.ivOriginal.setImageBitmap(it) }
        applyTransform(Transform.ORIGINAL)
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.IMAGE_ENHANCE)
        }

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
                    if (checkBoxes.none { it.isChecked }) {
                        applyingChecked = true
                        view.isChecked = true
                        applyingChecked = false
                    }
                }
            }
        checkBoxes.forEach { it.setOnCheckedChangeListener(onChecked) }
    }

    /** 根据所选选项调用 JNI 并更新「结果图 + 原理公式」。 */
    private fun applyTransform(transform: Transform) {
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
                    Transform.SLICE2 -> OpencvDealJni.colorGraySlice2(src, 128)
                    Transform.SLICE_MULTI -> OpencvDealJni.colorGraySliceMulti(src, 7)
                    Transform.LEVEL_COLOR -> OpencvDealJni.colorGrayLevelTransform(src)
                    Transform.FREQ_COLOR -> OpencvDealJni.colorFrequencyPseudo(src)
                    Transform.FALSE_LINEAR -> OpencvDealJni.colorFalseLinear(src)
                    Transform.FALSE_GREEN -> OpencvDealJni.colorFalseGreen(src)
                    Transform.FALSE_BLUE -> OpencvDealJni.colorFalseBlue(src)
                    Transform.FALSE_MULTI -> OpencvDealJni.colorFalseMultiSpectral(src)
                }
            }.getOrElse { it.printStackTrace(); null } ?: src

            activity?.runOnUiThread {
                binding.ivResult.setImageBitmap(result)
            }
        }
    }

    /** 从 assets 加载图片并降采样（长边 ≤ [MAX_SIDE]）。 */
    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            requireContext().assets.open(fileName).use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_SIDE) {
                sampleSize *= 2
            }
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
