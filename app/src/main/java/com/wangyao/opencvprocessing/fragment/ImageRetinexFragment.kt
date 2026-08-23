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
import com.wangyao.opencvprocessing.databinding.FragmentImageRetinexLayoutBinding
import kotlin.concurrent.thread

/**
 * 基于 Retinex 理论的图像增强界面（三级页）：
 * - 顶部：9 个 checkbar（单选互斥），①~⑨ 对应 PDF 2.6 节选项。
 * - 中部：左右并排显示「原图」与「当前选择的处理结果」。
 * - 底部：当前所选方法的「原理及公式」说明（式 2-88 ~ 2-96）。
 *
 * 注：为兼顾大 σ 高斯环绕的耗时，加载时先降采样到长边 ≤1080，
 * 大 σ 环绕在 Native 层以降采样方式加速。
 */
class ImageRetinexFragment : BaseFragment() {

    private lateinit var binding: FragmentImageRetinexLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null
    private var applyingChecked = false

    /** 九种 Retinex 增强选项的元数据（对应 PDF 2.6 节）。 */
    private enum class Transform(
        @StringRes val titleRes: Int,
        @StringRes val formulaRes: Int,
    ) {
        ORIGINAL(R.string.retinex_op_original_title, R.string.retinex_op_original_formula),
        ILLUM_L(R.string.retinex_op_illum_title, R.string.retinex_op_illum_formula),
        REFLECT_R(R.string.retinex_op_reflect_title, R.string.retinex_op_reflect_formula),
        SSR15(R.string.retinex_op_ssr15_title, R.string.retinex_op_ssr15_formula),
        SSR80(R.string.retinex_op_ssr80_title, R.string.retinex_op_ssr80_formula),
        SSR250(R.string.retinex_op_ssr250_title, R.string.retinex_op_ssr250_formula),
        MSR3(R.string.retinex_op_msr3_title, R.string.retinex_op_msr3_formula),
        MSR5(R.string.retinex_op_msr5_title, R.string.retinex_op_msr5_formula),
        MSRCR(R.string.retinex_op_msrcr_title, R.string.retinex_op_msrcr_formula),
    }

    private val transforms: Array<Transform> = Transform.values()
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageRetinexLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02IllumL,
            binding.cb03ReflectR,
            binding.cb04Ssr15,
            binding.cb05Ssr80,
            binding.cb06Ssr250,
            binding.cb07Msr3,
            binding.cb08Msr5,
            binding.cb09Msrcr,
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
                    Transform.ILLUM_L -> OpencvDealJni.retinexIllumination(src, 80.0)
                    Transform.REFLECT_R -> OpencvDealJni.retinexReflectance(src, 80.0)
                    Transform.SSR15 -> OpencvDealJni.retinexSSR(src, 15.0)
                    Transform.SSR80 -> OpencvDealJni.retinexSSR(src, 80.0)
                    Transform.SSR250 -> OpencvDealJni.retinexSSR(src, 250.0)
                    Transform.MSR3 -> OpencvDealJni.retinexMSR(src, doubleArrayOf(15.0, 80.0, 250.0))
                    Transform.MSR5 -> OpencvDealJni.retinexMSR(
                        src, doubleArrayOf(15.0, 40.0, 80.0, 150.0, 250.0)
                    )
                    Transform.MSRCR -> OpencvDealJni.retinexMSRCR(src, doubleArrayOf(15.0, 80.0, 250.0))
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
