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
import com.wangyao.opencvprocessing.databinding.FragmentImageHomomorphicLayoutBinding
import kotlin.concurrent.thread

/**
 * 图像的同态滤波界面（三级页）：
 * - 顶部：9 个 checkbar（单选互斥），①~⑨ 对应 PDF 2.5 节选项。
 * - 中部：左右并排显示「原图」与「当前选择的处理结果」。
 * - 底部：当前所选方法的「原理及公式」说明（式 2-81 ~ 2-87）。
 *
 * 注：为兼顾逐通道 DFT 的处理耗时，加载时先降采样到长边 ≤1080。
 */
class ImageHomomorphicFragment : BaseFragment() {

    private lateinit var binding: FragmentImageHomomorphicLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null
    private var applyingChecked = false

    /** 九种同态滤波选项的元数据（对应 PDF 2.5 节）。 */
    private enum class Transform(
        @StringRes val titleRes: Int,
        @StringRes val formulaRes: Int,
    ) {
        ORIGINAL(R.string.homo_op_original_title, R.string.homo_op_original_formula),
        ILLUM(R.string.homo_op_illum_title, R.string.homo_op_illum_formula),
        REFLECT(R.string.homo_op_reflect_title, R.string.homo_op_reflect_formula),
        LOG(R.string.homo_op_log_title, R.string.homo_op_log_formula),
        HOMO_STD(R.string.homo_op_std_title, R.string.homo_op_std_formula),
        HOMO_STRONG(R.string.homo_op_strong_title, R.string.homo_op_strong_formula),
        HOMO_D30(R.string.homo_op_d30_title, R.string.homo_op_d30_formula),
        HOMO_D150(R.string.homo_op_d150_title, R.string.homo_op_d150_formula),
        HOMO_MILD(R.string.homo_op_mild_title, R.string.homo_op_mild_formula),
    }

    private val transforms: Array<Transform> = Transform.values()
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageHomomorphicLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02Illum,
            binding.cb03Reflect,
            binding.cb04Log,
            binding.cb05HomoStd,
            binding.cb06HomoStrong,
            binding.cb07HomoD30,
            binding.cb08HomoD150,
            binding.cb09HomoMild,
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
                    Transform.ILLUM -> OpencvDealJni.homoIllumination(src)
                    Transform.REFLECT -> OpencvDealJni.homoReflectance(src)
                    Transform.LOG -> OpencvDealJni.homoLogDomain(src)
                    Transform.HOMO_STD -> OpencvDealJni.homoFilter(src, 80.0, 1.5, 0.5, 2.0)
                    Transform.HOMO_STRONG -> OpencvDealJni.homoFilter(src, 80.0, 1.5, 0.2, 2.5)
                    Transform.HOMO_D30 -> OpencvDealJni.homoFilter(src, 30.0, 1.5, 0.5, 2.0)
                    Transform.HOMO_D150 -> OpencvDealJni.homoFilter(src, 150.0, 1.5, 0.5, 2.0)
                    Transform.HOMO_MILD -> OpencvDealJni.homoFilter(src, 80.0, 1.5, 0.8, 1.5)
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
