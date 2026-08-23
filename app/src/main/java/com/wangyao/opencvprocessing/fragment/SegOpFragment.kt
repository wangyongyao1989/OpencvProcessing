package com.wangyao.opencvprocessing.fragment

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
 * 图像分割通用三级页（《数字图像与视频处理》第 4 章）。
 *
 * 一个 Fragment 通过 [module] 配置驱动四个子页面：
 * - SEG_THRESHOLD 基于灰度阈值化的图像分割（4.2 节：固定/迭代/Otsu/自适应/多级）
 * - SEG_EDGE      基于边缘检测的图像分割（4.3 节：Roberts~Canny/轮廓跟踪）
 * - SEG_REGION    基于区域的图像分割（4.4 节：区域生长/分裂合并/连通/分水岭）
 * - SEG_CONTOUR   基于主动轮廓模型的图像分割（4.5 节：Snake/气球力/测地线/Chan-Vese）
 *
 * 复用形态学三级页布局 fragment_morph_op_layout.xml：互斥单选 checkbar +
 * 原图/结果对比 + 原理公式卡片。
 */
class SegOpFragment : BaseFragment() {

    private lateinit var binding: FragmentMorphOpLayoutBinding
    private lateinit var ffViewModel: FFViewModel
    private lateinit var module: FFViewModel.FRAGMENT_STATUS

    private var originalBitmap: Bitmap? = null

    // 单选互斥：避免 onCheckedChanged 递归触发
    private var applyingChecked = false

    /** 一个可选分割算法项：标题 + 原理公式 + 位图计算器（子线程执行） */
    private class Op(
        val title: String,
        val formula: String,
        val compute: (Bitmap) -> Bitmap?
    )

    /** 当前模块的算法目录：由 [buildCatalog] 生成，索引与勾选框一一对应 */
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
            arguments?.getString(ARG_MODULE) ?: FFViewModel.FRAGMENT_STATUS.SEG_THRESHOLD.name
        )

        // 依模块装配目录：标题、勾选栏文案、公式
        buildCatalog(module)

        binding.tvPageTitle.text = pageTitle(module)
        binding.tvCheckbarHint.text = getString(R.string.seg_checkbar_hint)
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
        // 返回二级菜单「图像分割」
        binding.btnBack.setOnClickListener {
            ffViewModel.switchFragment.postValue(FFViewModel.FRAGMENT_STATUS.SEGMENTATION)
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
    // 算法目录：四个模块的配置中心（标题 / 公式 / JNI 调用）
    // -------------------------------------------------------------------------

    /** 目录装配辅助 */
    private fun addOp(title: String, formula: String, compute: (Bitmap) -> Bitmap?) {
        catalog += Op(title, formula, compute)
    }

    private fun pageTitle(module: FFViewModel.FRAGMENT_STATUS): String = when (module) {
        FFViewModel.FRAGMENT_STATUS.SEG_THRESHOLD -> getString(R.string.seg_title_page_threshold)
        FFViewModel.FRAGMENT_STATUS.SEG_EDGE -> getString(R.string.seg_title_page_edge)
        FFViewModel.FRAGMENT_STATUS.SEG_REGION -> getString(R.string.seg_title_page_region)
        else -> getString(R.string.seg_title_page_contour)
    }

    private fun buildCatalog(module: FFViewModel.FRAGMENT_STATUS) {
        catalog.clear()
        when (module) {
            FFViewModel.FRAGMENT_STATUS.SEG_THRESHOLD -> buildThreshold()
            FFViewModel.FRAGMENT_STATUS.SEG_EDGE -> buildEdge()
            FFViewModel.FRAGMENT_STATUS.SEG_REGION -> buildRegion()
            else -> buildContour()
        }
    }

    /** 4.2 基于灰度阈值化的图像分割：式 4-1 ~ 4-10 */
    private fun buildThreshold() {
        addOp(getString(R.string.seg_op_threshold_baseline_title),
            getString(R.string.seg_op_threshold_baseline_formula)
        ) { src -> src }

        addOp(getString(R.string.seg_op_threshold_fixed_title),
            getString(R.string.seg_op_threshold_fixed_formula)
        ) { src -> OpencvDealJni.segFixedThreshold(src, 128.0) }

        addOp(getString(R.string.seg_op_threshold_iterative_title),
            getString(R.string.seg_op_threshold_iterative_formula)
        ) { src -> OpencvDealJni.segIterativeThreshold(src) }

        addOp(getString(R.string.seg_op_threshold_otsu_title),
            getString(R.string.seg_op_threshold_otsu_formula)
        ) { src -> OpencvDealJni.segOtsu(src) }

        addOp(getString(R.string.seg_op_threshold_adaptive_title),
            getString(R.string.seg_op_threshold_adaptive_formula)
        ) { src -> OpencvDealJni.segAdaptiveThreshold(src, 31, 10.0) }

        addOp(getString(R.string.seg_op_threshold_multi_level_title),
            getString(R.string.seg_op_threshold_multi_level_formula)
        ) { src -> OpencvDealJni.segMultiLevelThreshold(src) }

        addOp(getString(R.string.seg_op_threshold_overlay_title),
            getString(R.string.seg_op_threshold_overlay_formula)
        ) { src -> OpencvDealJni.segThresholdOverlay(src) }
    }

    /** 4.3 基于边缘检测的图像分割：式 4-14 ~ 4-39 */
    private fun buildEdge() {
        addOp(getString(R.string.seg_op_edge_baseline_title),
            getString(R.string.seg_op_edge_baseline_formula)
        ) { src -> src }

        addOp(getString(R.string.seg_op_edge_roberts_title),
            getString(R.string.seg_op_edge_roberts_formula)
        ) { src -> OpencvDealJni.segRoberts(src) }

        addOp(getString(R.string.seg_op_edge_sobel_title),
            getString(R.string.seg_op_edge_sobel_formula)
        ) { src -> OpencvDealJni.segSobel(src, 3) }

        addOp(getString(R.string.seg_op_edge_prewitt_title),
            getString(R.string.seg_op_edge_prewitt_formula)
        ) { src -> OpencvDealJni.segPrewitt(src) }

        addOp(getString(R.string.seg_op_edge_laplacian_title),
            getString(R.string.seg_op_edge_laplacian_formula)
        ) { src -> OpencvDealJni.segLaplacianEdge(src) }

        addOp(getString(R.string.seg_op_edge_log_title),
            getString(R.string.seg_op_edge_log_formula)
        ) { src -> OpencvDealJni.segLoG(src, 5, 1.5) }

        addOp(getString(R.string.seg_op_edge_canny_title),
            getString(R.string.seg_op_edge_canny_formula)
        ) { src -> OpencvDealJni.segCanny(src, 50.0, 150.0) }

        addOp(getString(R.string.seg_op_edge_trace_title),
            getString(R.string.seg_op_edge_trace_formula)
        ) { src -> OpencvDealJni.segContourTrace(src) }
    }

    /** 4.4 基于区域的图像分割：4.4.1 ~ 4.4.3 */
    private fun buildRegion() {
        addOp(getString(R.string.seg_op_region_grow_center_title),
            getString(R.string.seg_op_region_grow_center_formula)
        ) { src -> OpencvDealJni.segRegionGrowCenter(src, 16) }

        addOp(getString(R.string.seg_op_region_grow_auto_seed_title),
            getString(R.string.seg_op_region_grow_auto_seed_formula)
        ) { src -> OpencvDealJni.segRegionGrowAutoSeed(src, 16) }

        addOp(getString(R.string.seg_op_region_split_merge_title),
            getString(R.string.seg_op_region_split_merge_formula)
        ) { src -> OpencvDealJni.segSplitMerge(src) }

        addOp(getString(R.string.seg_op_region_connected_title),
            getString(R.string.seg_op_region_connected_formula)
        ) { src -> OpencvDealJni.segConnectedComponents(src) }

        addOp(getString(R.string.seg_op_region_watershed_title),
            getString(R.string.seg_op_region_watershed_formula)
        ) { src -> OpencvDealJni.segWatershed(src) }
    }

    /** 4.5 基于主动轮廓模型的图像分割：式 4-40 ~ 4-47 */
    private fun buildContour() {
        addOp(getString(R.string.seg_op_contour_snake_title),
            getString(R.string.seg_op_contour_snake_formula)
        ) { src -> OpencvDealJni.segSnake(src, 300) }

        addOp(getString(R.string.seg_op_contour_inner_energy_title),
            getString(R.string.seg_op_contour_inner_energy_formula)
        ) { src -> OpencvDealJni.segSnakeSmoothCompare(src) }

        addOp(getString(R.string.seg_op_contour_balloon_title),
            getString(R.string.seg_op_contour_balloon_formula)
        ) { src -> OpencvDealJni.segBalloonSnake(src, 400) }

        addOp(getString(R.string.seg_op_contour_geodesic_title),
            getString(R.string.seg_op_contour_geodesic_formula)
        ) { src -> OpencvDealJni.segGeodesicContour(src, 400) }

        addOp(getString(R.string.seg_op_contour_chan_vese_title),
            getString(R.string.seg_op_contour_chan_vese_formula)
        ) { src -> OpencvDealJni.segChanVese(src, 60) }
    }

    // -------------------------------------------------------------------------
    // 执行与 UI 渲染
    // -------------------------------------------------------------------------

    /**
     * 应用被选中的算法项：先刷新公式文本，再在后台线程执行 JNI 运算，
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
        fun instantiate(module: FFViewModel.FRAGMENT_STATUS): SegOpFragment {
            return SegOpFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_MODULE, module.name)
                }
            }
        }
    }
}
