package com.wangyao.opencvprocessing.fragment.image

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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
import com.wangyao.opencvprocessing.databinding.FragmentImageGrayTransformLayoutBinding
import kotlin.concurrent.thread

/**
 * Fragment responsible for demonstrating various image grayscale transformation techniques.
 *
 * This fragment provides a UI to interactively explore nine different grayscale transformations
 * described in the context of digital image processing. It features:
 * - A top selection area with 9 mutually exclusive checkboxes.
 * - A side-by-side comparison view of the original and processed images.
 * - A detailed explanation section showing the mathematical formulas and principles.
 */
class ImageGrayTransformFragment : BaseFragment() {

    private lateinit var binding: FragmentImageGrayTransformLayoutBinding
    private lateinit var ffViewModel: FFViewModel

    private var originalBitmap: Bitmap? = null

    // 单选互斥：避免 onCheckedChanged 递归触发
    private var applyingChecked = false

    /** 九种变换方法的元数据。 */
    private enum class Transform(
        val index: Int,
        @StringRes val titleRes: Int,
        @StringRes val formulaRes: Int,
    ) {
        ORIGINAL(0, R.string.gray_op_original_title, R.string.gray_op_original_formula),
        LINEAR(1, R.string.gray_op_linear_title, R.string.gray_op_linear_formula),
        INVERT(2, R.string.gray_op_invert_title, R.string.gray_op_invert_formula),
        PIECEWISE(3, R.string.gray_op_piecewise_title, R.string.gray_op_piecewise_formula),
        CLIP(4, R.string.gray_op_clip_title, R.string.gray_op_clip_formula),
        THRESHOLD(5, R.string.gray_op_threshold_title, R.string.gray_op_threshold_formula),
        LOG(6, R.string.gray_op_log_title, R.string.gray_op_log_formula),
        GAMMA(7, R.string.gray_op_gamma_title, R.string.gray_op_gamma_formula),
        HISTEQ(8, R.string.gray_op_histeq_title, R.string.gray_op_histeq_formula),
    }

    /** 按顺序排列的九项，方便用索引绑定到九组控件。 */
    private val transforms: Array<Transform> = Transform.values()

    /** List of checkboxes for the 9 transformations, managed as a list for easier index-based access. */
    private lateinit var checkBoxes: List<MaterialCheckBox>

    /**
     * Inflates the ViewBinding for this fragment.
     */
    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentImageGrayTransformLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    /**
     * Initializes the view components by grouping the checkboxes into a list.
     */
    override fun initView() {
        checkBoxes = listOf(
            binding.cb01Original,
            binding.cb02Linear,
            binding.cb03Invert,
            binding.cb04Piecewise,
            binding.cb05Clip,
            binding.cb06Threshold,
            binding.cb07Log,
            binding.cb08Gamma,
            binding.cb09Histeq,
        )
    }

    /**
     * Loads the base image from assets and displays it.
     */
    override fun initData() {
        originalBitmap = loadBitmapFromAssets(IMG_ASSET_NAME)
        originalBitmap?.let { binding.ivOriginal.setImageBitmap(it) }
        // 初始显示第一项：原图
        applyTransform(Transform.ORIGINAL)
    }

    /**
     * Sets up the ViewModel for communication with the activity.
     */
    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    /**
     * Sets up UI listeners, including the logic for mutually exclusive checkboxes.
     */
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
    // 变换执行与 UI 渲染
    // -------------------------------------------------------------------------

    /**
     * Applies the selected [Transform] to the original image and updates the UI.
     *
     * The process updates the descriptive text immediately and handles image processing
     * on a background thread to prevent UI blocking. The resulting bitmap is rendered
     * back on the main thread.
     *
     * @param transform The grayscale transformation to apply.
     */
    private fun applyTransform(transform: Transform) {
        // 先显示原理公式（UI 立即刷新，图片异步处理好后再更新）
        binding.tvFormula.text = getString(transform.formulaRes)
        binding.tvResultTitle.text = getString(transform.titleRes)
        val src = originalBitmap ?: return

        thread(start = true) {
            val result: Bitmap? = runCatching {
                when (transform) {
                    Transform.ORIGINAL -> src
                    Transform.LINEAR -> OpencvDealJni.grayLinearTransform(
                        src, 50.0, 200.0, 0.0, 255.0
                    )
                    Transform.INVERT -> OpencvDealJni.grayInvertTransform(src)
                    Transform.PIECEWISE -> OpencvDealJni.grayPiecewiseLinear(
                        src, 60.0, 200.0, 20.0, 230.0
                    )
                    Transform.CLIP -> OpencvDealJni.grayClipTransform(src, 100.0, 180.0)
                    Transform.THRESHOLD -> OpencvDealJni.grayThresholdTransform(src, 128.0)
                    Transform.LOG -> OpencvDealJni.grayLogTransform(src, 0.0)
                    Transform.GAMMA -> OpencvDealJni.grayGammaTransform(src, 1.0, 0.5)
                    Transform.HISTEQ -> OpencvDealJni.grayHistogramEqualize(src)
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
     * Loads a bitmap from the assets folder.
     *
     * This method ensures the bitmap is in [Bitmap.Config.ARGB_8888] format, which is
     * generally required for stable processing within the OpenCV JNI layer.
     *
     * @param fileName The path to the image file in the assets directory.
     * @return The loaded and potentially converted [Bitmap], or null if loading failed.
     */
    private fun loadBitmapFromAssets(fileName: String): Bitmap? {
        return try {
            val input = requireContext().assets.open(fileName)
            val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeStream(input, null, opts)
            input.close()
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
    }
}
