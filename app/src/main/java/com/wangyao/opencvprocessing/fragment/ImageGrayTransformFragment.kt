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
        val displayTitle: String,
        val formula: String,
    ) {
        ORIGINAL(0,
            "① 显示原始图像",
            buildString {
                append("【原理】不做任何处理，直接显示原始图像，用作对照基准。\n")
                append("【公式】g(x,y) = f(x,y)\n")
                append("【说明】用于与其他八种灰度变换结果作并排对比，观察各变换对灰度分布的调整作用。")
            }),
        LINEAR(1,
            "② 灰度的线性变换 (式 2-1)",
            buildString {
                append("【原理】对有限范围的像素灰度做线性拉伸/压缩，扩展动态范围、统一映射。适用于曝光不足/过度导致灰度集中在小区间的图像。\n")
                append("【式 2-1】g(x,y) = [(d-c)/(b-a)] · [f(x,y) − a] + c\n")
                append("【参数】本次演示：a=50, b=200, c=0, d=255，即将 [50,200] 拉满到 [0,255]。\n")
                append("【效果】中间层次灰度被拉开，整体对比度增强。")
            }),
        INVERT(2,
            "③ 图像的反转变换 (图 2-3)",
            buildString {
                append("【原理】线性变换的特例：黑变白、白变黑，与底片效果一致。常用于医学图像（如 X 光）、掩膜图像的可视化。\n")
                append("【公式】g(x,y) = 255 − f(x,y)\n")
                append("【对应】图 2-3 所示的反转曲线。")
            }),
        PIECEWISE(3,
            "④ 三段分段线性变换 / 对比度扩展 (式 2-2, 式 2-3)",
            buildString {
                append("【原理】三段分段线性可以对某一灰度区间进行拉伸，同时抑制其他区间，突出感兴趣的灰度范围。\n")
                append("【式 2-2（压缩式）】将 [0,a) 压缩为 c、[b,Mf) 压缩为 d，中间线性映射：\n")
                append("   g(x,y) = c,                                 0 ≤ f < a\n")
                append("          = [(d-c)/(b-a)]·(f(x,y)−a) + c,       a ≤ f < b\n")
                append("          = d,                                 b ≤ f < Mf\n")
                append("【式 2-3（斜率式，本函数实现）】保留两端斜率仅调整大小：\n")
                append("   g(x,y) = (c/a)·f(x,y),                                    0 ≤ f < a\n")
                append("          = [(d-c)/(b-a)]·(f(x,y)−a) + c,                     a ≤ f < b\n")
                append("          = [(Mg−d)/(Mf−b)]·(f(x,y)−b) + d,                   b ≤ f < Mf\n")
                append("【参数】本次演示 a=60, b=200, c=20, d=230，压缩两端低值与高值、扩展中间主体。")
            }),
        CLIP(4,
            "⑤ 削波处理 (图 2-6, 式 2-3 特例)",
            buildString {
                append("【原理】分段线性的特例。令式 2-3 中 c=0、d=Mg=255，把 [0,a] 抑制为黑、[b,Mf] 抑制为白，仅保留并拉伸 [a,b] 区间内容。常用于把物体主体从背景中切分出来。\n")
                append("【公式】代入 c=0, d=255：\n")
                append("   g = 0,                          0 ≤ f < a\n")
                append("   g = [255/(b-a)]·(f(x,y) − a),   a ≤ f < b\n")
                append("   g = 255,                        b ≤ f < Mf\n")
                append("【参数】本次演示 a=100, b=180，对应图 2-6 的效果。")
            }),
        THRESHOLD(5,
            "⑥ 阈值化 (图 2-7, 式 2-3 特例)",
            buildString {
                append("【原理】削波的进一步特例：令式 2-3 中 a=b=threshold、c=0、d=255，图像仅保留两个灰度级 0/255，输出二值图像。\n")
                append("【公式】\n")
                append("   g(x,y) = 0,    f(x,y) <  threshold\n")
                append("          = 255,  f(x,y) ≥ threshold\n")
                append("【参数】本次演示 threshold=128，对应图 2-7。")
            }),
        LOG(6,
            "⑦ 对数变换 (式 2-4)",
            buildString {
                append("【原理】使用对数函数对像素灰度做映射，扩展低灰度范围、压缩高灰度范围，使整体灰度分布更符合人眼视觉特性。典型应用是傅里叶频谱显示：当频谱动态范围过大时，对数变换能将细节呈现出来。\n")
                append("【式 2-4（PDF）】g(x,y) = a + ln[f(x,y) + 1] / (b · ln c)\n")
                append("       其中 a,b,c 用于改变曲线的起点与形状，+1 避免对 0 求对数。\n")
                append("【本实现（工程常用简化，令 a=0，b=1，并自动求解 c 使最大输出=255）】\n")
                append("   g(x,y) = c · ln(1 + f(x,y))\n")
                append("【参数】本次演示 c=0（0 表示由算法自动计算最佳缩放）。")
            }),
        GAMMA(7,
            "⑧ 伽马变换 / 幂次变换 (式 2-5 派生)",
            buildString {
                append("【原理】幂次 / 伽马变换用于不同的显示系统中校正输出设备的 γ 响应（即 CRT/LCD 的灰度曲线）。γ < 1 扩展高灰度、暗部被压缩（提升偏暗图像亮度层次）；γ > 1 相反。\n")
                append("【式 2-5（PDF 指数变换）】g(x,y) = b^{ c · [f(x,y) − a] } − 1\n")
                append("   扩展高灰度区间，压缩低灰度区间。\n")
                append("【本实现：工程通用的伽马（幂次）变换】先把 f 归一化 f' = f/255 ∈ [0,1]：\n")
                append("   g(x,y) = c · f(x,y)^γ    再映射回 [0,255]\n")
                append("【参数】本次演示 c=1.0, γ=0.5（<1），用于提亮偏暗区域、扩展高灰度。")
            }),
        HISTEQ(8,
            "⑨ 直方图均衡化 (式 2-13, 式 2-14)",
            buildString {
                append("【原理】把原始图像的灰度直方图通过累积分布函数变换为近似均匀分布的直方图，使整体对比度增大、层次更清晰。对偏亮/偏暗/对比度较小的图像效果明显。\n")
                append("【式 2-13】离散灰度概率：\n")
                append("   pr(rk) = nk / n    (k = 0, 1, …, L−1)\n")
                append("   其中 nk 表示第 k 个灰度级 rk 的像素数，n 为总像素数。\n")
                append("【式 2-14】离散累积分布（均衡化变换函数）：\n")
                append("   sk = T(rk) = Σ_{j=0}^{k} pr(rj) = Σ_{j=0}^{k} nj / n    (0 ≤ rj ≤ 1)\n")
                append("   把原始灰度 rk 映射为均衡化后的灰度 sk。\n")
                append("【实现】调用 OpenCV cv::equalizeHist。")
            }),
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
        binding.tvFormula.text = transform.formula
        binding.tvResultTitle.text = transform.displayTitle
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
