package com.wangyao.opencvprocessing.fragment.image

import com.wangyao.opencvprocessing.fragment.base.BaseFragment

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.wangyao.opencvdeal.jni.OpencvDealJni
import com.wangyao.opencvprocessing.FFViewModel
import com.wangyao.opencvprocessing.R
import com.wangyao.opencvprocessing.databinding.FragmentMorphOpLayoutBinding
import kotlin.concurrent.thread

/**
 * 形态学图像处理通用三级页（《数字图像与视频处理》第 3 章）。
 *
 * 一个 Fragment 通过 [module] 配置驱动四个子页面：
 * - MORPH_BIN_BASIC    二值形态学基本运算（3.2 节：腐蚀/膨胀/开/闭/对偶性）
 * - MORPH_BIN_PROCESS  二值图像的形态学处理（3.3 节：边缘/填充/骨架/细化/去噪）
 * - MORPH_GRAY_BASIC   灰度形态学基本运算（3.4 节：灰度腐蚀/膨胀/开/闭）
 * - MORPH_GRAY_PROCESS 灰度图像的形态学处理（3.5 节：梯度/平滑/顶帽/底帽）
 *
 * 结构与图像增强族三级页一致：互斥单选 checkbar + 原图/结果对比 + 原理公式卡片。
 */
class MorphOpFragment : BaseFragment() {

    private lateinit var binding: FragmentMorphOpLayoutBinding
    private lateinit var ffViewModel: FFViewModel
    private lateinit var module: FFViewModel.FRAGMENT_STATUS

    private var originalBitmap: Bitmap? = null

    // 单选互斥：避免 onCheckedChanged 递归触发
    private var applyingChecked = false

    /** 一个可选运算项：标题 + 原理公式 + 位图计算器（子线程执行） */
    private class Op(
        val title: String,
        val formula: String,
        val compute: (Bitmap) -> Bitmap?
    )

    /** 当前模块的运算目录：由 [buildCatalog] 生成，索引与勾选框一一对应 */
    private val catalog = ArrayList<Op>()

    /** 勾选框列表（布局固定 9 个，多余的隐藏） */
    private lateinit var checkBoxes: List<MaterialCheckBox>

    override fun getLayoutBinding(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentMorphOpLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initView() {
        checkBoxes = listOf(
            binding.cb01, binding.cb02, binding.cb03,
            binding.cb04, binding.cb05, binding.cb06,
            binding.cb07, binding.cb08, binding.cb09,
        )
    }

    override fun initData() {
        module = FFViewModel.FRAGMENT_STATUS.valueOf(
            arguments?.getString(ARG_MODULE) ?: FFViewModel.FRAGMENT_STATUS.MORPH_BIN_BASIC.name
        )

        // 依模块装配目录：标题、勾选栏文案、公式
        buildCatalog(module)

        binding.tvPageTitle.text = pageTitle(module)
        catalog.forEachIndexed { idx, op ->
            val cb = checkBoxes.getOrNull(idx) ?: return@forEachIndexed
            cb.visibility = View.VISIBLE
            cb.text = op.title
            cb.isChecked = idx == 0
        }
        // 隐藏多余勾选框（目录不足 9 项时）
        for (idx in catalog.size until checkBoxes.size) {
            checkBoxes[idx].visibility = View.GONE
        }

        originalBitmap = loadBitmapFromAssets(IMG_ASSET_NAME)
        originalBitmap?.let { binding.ivOriginal.setImageBitmap(it) }
        // 初始显示第一项
        applyOp(catalog.first())
    }

    override fun initObserver() {
        ffViewModel = ViewModelProvider(requireActivity())[FFViewModel::class.java]
    }

    override fun initListener() {
        // 返回二级菜单「形态学图像处理」
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.MORPHOLOGY)
        }

        val onChecked =
            CompoundButton.OnCheckedChangeListener { view, isChecked ->
                if (applyingChecked) return@OnCheckedChangeListener
                val idx = checkBoxes.indexOfFirst { it === view }
                if (idx < 0 || idx >= catalog.size) return@OnCheckedChangeListener
                if (isChecked) {
                    applyingChecked = true
                    for ((i, cb) in checkBoxes.withIndex()) {
                        if (i != idx && i < catalog.size) cb.isChecked = false
                    }
                    applyingChecked = false
                    applyOp(catalog[idx])
                } else {
                    // 不允许全部取消：至少保留一项勾选
                    if (checkBoxes.none { it.isChecked && it.visibility == View.VISIBLE }) {
                        applyingChecked = true
                        view.isChecked = true
                        applyingChecked = false
                    }
                }
            }
        checkBoxes.forEach { it.setOnCheckedChangeListener(onChecked) }
    }

    // -------------------------------------------------------------------------
    // 运算目录：四个模块的配置中心（标题 / 公式 / JNI 调用）
    // -------------------------------------------------------------------------

    /** 目录装配辅助 */
    private fun addOp(title: String, formula: String, compute: (Bitmap) -> Bitmap?) {
        catalog += Op(title, formula, compute)
    }

    private fun pageTitle(module: FFViewModel.FRAGMENT_STATUS): String = when (module) {
        FFViewModel.FRAGMENT_STATUS.MORPH_BIN_BASIC -> getString(R.string.morph_title_page_bin_basic)
        FFViewModel.FRAGMENT_STATUS.MORPH_BIN_PROCESS -> getString(R.string.morph_title_page_bin_process)
        FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_BASIC -> getString(R.string.morph_title_page_gray_basic)
        else -> getString(R.string.morph_title_page_gray_process)
    }

    private fun buildCatalog(module: FFViewModel.FRAGMENT_STATUS) {
        catalog.clear()
        when (module) {
            FFViewModel.FRAGMENT_STATUS.MORPH_BIN_BASIC -> buildBinBasic()
            FFViewModel.FRAGMENT_STATUS.MORPH_BIN_PROCESS -> buildBinProcess()
            FFViewModel.FRAGMENT_STATUS.MORPH_GRAY_BASIC -> buildGrayBasic()
            else -> buildGrayProcess()
        }
    }

    /** 3.2 二值形态学基本运算：式 3-9 ~ 3-18 */
    private fun buildBinBasic() {
        addOp(getString(R.string.morph_op_bin_basic_baseline_title),
            getString(R.string.morph_op_bin_basic_baseline_formula)
        ) { src -> OpencvDealJni.morphBinarize(src) }

        addOp(getString(R.string.morph_op_bin_basic_erode_title),
            getString(R.string.morph_op_bin_basic_erode_formula)
        ) { src -> OpencvDealJni.morphBinErode(src, 5, 0) }

        addOp(getString(R.string.morph_op_bin_basic_dilate_title),
            getString(R.string.morph_op_bin_basic_dilate_formula)
        ) { src -> OpencvDealJni.morphBinDilate(src, 5, 0) }

        addOp(getString(R.string.morph_op_bin_basic_open_title),
            getString(R.string.morph_op_bin_basic_open_formula)
        ) { src -> OpencvDealJni.morphBinOpen(src, 5, 0) }

        addOp(getString(R.string.morph_op_bin_basic_close_title),
            getString(R.string.morph_op_bin_basic_close_formula)
        ) { src -> OpencvDealJni.morphBinClose(src, 5, 0) }

        addOp(getString(R.string.morph_op_bin_basic_duality_title),
            getString(R.string.morph_op_bin_basic_duality_formula)
        ) { src -> OpencvDealJni.morphBinDuality(src, 5) }
    }

    /** 3.3 二值图像的形态学处理：式 3-20 ~ 3-35 */
    private fun buildBinProcess() {
        addOp(getString(R.string.morph_op_bin_process_baseline_title),
            getString(R.string.morph_op_bin_process_baseline_formula)
        ) { src -> OpencvDealJni.morphBinarize(src) }

        addOp(getString(R.string.morph_op_bin_process_inner_edge_title),
            getString(R.string.morph_op_bin_process_inner_edge_formula)
        ) { src -> OpencvDealJni.morphBinInnerEdge(src, 3) }

        addOp(getString(R.string.morph_op_bin_process_outer_edge_title),
            getString(R.string.morph_op_bin_process_outer_edge_formula)
        ) { src -> OpencvDealJni.morphBinOuterEdge(src, 3) }

        addOp(getString(R.string.morph_op_bin_process_grad_edge_title),
            getString(R.string.morph_op_bin_process_grad_edge_formula)
        ) { src -> OpencvDealJni.morphBinGradEdge(src, 3) }

        addOp(getString(R.string.morph_op_bin_process_fill_holes_title),
            getString(R.string.morph_op_bin_process_fill_holes_formula)
        ) { src -> OpencvDealJni.morphBinFillHoles(src, 3) }

        addOp(getString(R.string.morph_op_bin_process_skeleton_title),
            getString(R.string.morph_op_bin_process_skeleton_formula)
        ) { src -> OpencvDealJni.morphBinSkeleton(src, 3) }

        addOp(getString(R.string.morph_op_bin_process_thinning_title),
            getString(R.string.morph_op_bin_process_thinning_formula)
        ) { src -> OpencvDealJni.morphBinThinning(src) }

        addOp(getString(R.string.morph_op_bin_process_open_close_filter_title),
            getString(R.string.morph_op_bin_process_open_close_filter_formula)
        ) { src -> OpencvDealJni.morphBinOpenCloseFilter(src, 5) }
    }

    /** 3.4 灰度形态学基本运算：式 3-36 ~ 3-45 */
    private fun buildGrayBasic() {
        addOp(getString(R.string.morph_op_gray_basic_baseline_title),
            getString(R.string.morph_op_gray_basic_baseline_formula)
        ) { src -> src }

        addOp(getString(R.string.morph_op_gray_basic_erode_title),
            getString(R.string.morph_op_gray_basic_erode_formula)
        ) { src -> OpencvDealJni.morphGrayErode(src, 5, 1) }

        addOp(getString(R.string.morph_op_gray_basic_dilate_title),
            getString(R.string.morph_op_gray_basic_dilate_formula)
        ) { src -> OpencvDealJni.morphGrayDilate(src, 5, 1) }

        addOp(getString(R.string.morph_op_gray_basic_open_title),
            getString(R.string.morph_op_gray_basic_open_formula)
        ) { src -> OpencvDealJni.morphGrayOpen(src, 5, 1) }

        addOp(getString(R.string.morph_op_gray_basic_close_title),
            getString(R.string.morph_op_gray_basic_close_formula)
        ) { src -> OpencvDealJni.morphGrayClose(src, 5, 1) }
    }

    /** 3.5 灰度图像的形态学处理：式 3-46 ~ 3-49 及扩展 */
    private fun buildGrayProcess() {
        addOp(getString(R.string.morph_op_gray_process_baseline_title),
            getString(R.string.morph_op_gray_process_baseline_formula)
        ) { src -> src }

        addOp(getString(R.string.morph_op_gray_process_gradient_title),
            getString(R.string.morph_op_gray_process_gradient_formula)
        ) { src -> OpencvDealJni.morphGrayGradient(src, 3, 1) }

        addOp(getString(R.string.morph_op_gray_process_open_close_smooth_title),
            getString(R.string.morph_op_gray_process_open_close_smooth_formula)
        ) { src -> OpencvDealJni.morphGrayOpenClose(src, 5, 1) }

        addOp(getString(R.string.morph_op_gray_process_close_open_smooth_title),
            getString(R.string.morph_op_gray_process_close_open_smooth_formula)
        ) { src -> OpencvDealJni.morphGrayCloseOpen(src, 5, 1) }

        addOp(getString(R.string.morph_op_gray_process_top_hat_title),
            getString(R.string.morph_op_gray_process_top_hat_formula)
        ) { src -> OpencvDealJni.morphGrayTopHat(src, 15, 1) }

        addOp(getString(R.string.morph_op_gray_process_bottom_hat_title),
            getString(R.string.morph_op_gray_process_bottom_hat_formula)
        ) { src -> OpencvDealJni.morphGrayBottomHat(src, 15, 1) }

        addOp(getString(R.string.morph_op_gray_process_top_hat_enhance_title),
            getString(R.string.morph_op_gray_process_top_hat_enhance_formula)
        ) { src -> OpencvDealJni.morphGrayTopHatEnhance(src, 15, 1) }
    }

    // -------------------------------------------------------------------------
    // 执行与 UI 渲染
    // -------------------------------------------------------------------------

    /**
     * 应用被选中的运算项：先刷新公式文本，再在后台线程执行 JNI 运算，
     * 完成后回到主线程更新结果图。
     */
    private fun applyOp(op: Op) {
        binding.tvFormula.text = op.formula
        binding.tvResultTitle.text = op.title
        val src = originalBitmap ?: return

        thread(start = true) {
            val result: Bitmap? = runCatching { op.compute(src) }
                .getOrElse { it.printStackTrace(); null } ?: src

            activity?.runOnUiThread {
                binding.ivResult.setImageBitmap(result)
            }
        }
    }

    // -------------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------------

    /**
     * 从 assets 加载位图并统一为 ARGB_8888，供 OpenCV JNI 层稳定处理。
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
        private const val ARG_MODULE = "arg_module"
        private const val IMG_ASSET_NAME = "IMG_20260821_190758.jpg"

        /** 按模块状态实例化三级页。 */
        fun instantiate(module: FFViewModel.FRAGMENT_STATUS): MorphOpFragment {
            return MorphOpFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODULE, module.name)
                }
            }
        }
    }
}
